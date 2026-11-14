package eu.bradshaw.seidr.sync

import eu.bradshaw.seidr.data.db.Download
import eu.bradshaw.seidr.data.db.DownloadState
import eu.bradshaw.seidr.data.db.OfflineSource
import eu.bradshaw.seidr.data.db.SourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A playlist as seen in the cheap `getPlaylists` listing. */
data class PlaylistInfo(val id: String, val name: String, val changed: String?, val songCount: Int)

/** A full playlist fetch: the track list plus the count the server claims it has. */
data class PlaylistFetch(val tracks: List<ServerTrack>, val songCount: Int, val changed: String?, val name: String)

/** Thrown by [SyncServer] when the server says the item doesn't exist (Subsonic error 70). */
class GoneException(message: String) : Exception(message)

/**
 * The server calls the engine makes. Implementations must throw on any
 * network or server error and must never return cached data: the engine treats
 * a returned value as the server's complete, current truth.
 */
interface SyncServer {
    suspend fun playlistListing(): List<PlaylistInfo>
    /** Throws [GoneException] if the playlist doesn't exist. */
    suspend fun playlist(id: String): PlaylistFetch
    /** Throws [GoneException] if the album doesn't exist. */
    suspend fun album(id: String): List<ServerTrack>
}

/** Storage the engine reads and writes (Room in the app, in-memory in tests). */
interface SyncStore {
    suspend fun sources(): List<OfflineSource>
    suspend fun updateSource(source: OfflineSource)
    /** Deletes the source and, with it, all of its claims. */
    suspend fun removeSource(id: String)
    suspend fun claimsFor(sourceId: String): Set<String>
    suspend fun applyClaims(sourceId: String, add: Set<String>, remove: Set<String>)
    suspend fun claimedTrackIds(): Set<String>
    suspend fun downloads(): List<Download>
    suspend fun upsertDownloads(rows: List<Download>)
    suspend fun deleteDownload(trackId: String)
    suspend fun retryFailed()
}

/**
 * Kid mode: the only tracks any source may claim. [key] tells one gate from
 * the next, so a pass after a change refetches every source in full.
 */
class TrackGate(val allowed: Set<String>, val key: Any = allowed)

/** Removes a track's files from disk (the finished file and any partial). */
fun interface FileDeleter {
    fun delete(trackId: String, path: String?)
}

data class SyncReport(
    val ok: List<String> = emptyList(),
    val unchanged: List<String> = emptyList(),
    val failed: Map<String, String> = emptyMap(),
    val gone: List<String> = emptyList(),
    val queued: Int = 0,
    val redownload: Int = 0,
    val deleted: List<String> = emptyList(),
    val deferred: List<String> = emptyList(),
) {
    val needsDownloads get() = queued > 0 || redownload > 0
}

/**
 * Runs sync passes: fetch each enabled offline source, update its claims, then
 * delete files nothing claims and queue files that are missing.
 *
 * Safety: claims are only ever removed after a complete, validated fetch (see
 * [SyncPlanner.isTrustworthy]). Any error leaves that source's claims, and so
 * its files, exactly as they were. Deletion happens only in the file pass, and
 * only for files with zero claims.
 *
 * @param protectedIds track ids that must not be deleted right now (the playing track)
 * @param now clock, injectable for tests
 */
class SyncEngine(
    private val server: SyncServer,
    private val store: SyncStore,
    private val files: FileDeleter,
    private val protectedIds: () -> Set<String> = { emptySet() },
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Kid mode's allowed tracks, fresh for each pass; null when anything goes.
     * Throws when they can't be fetched, and the pass then changes nothing.
     */
    private val gate: suspend () -> TrackGate? = { null },
) {
    /** The gate the last clean pass applied in full. */
    private var lastGateKey: Any? = null

    /** Held for a whole pass, and by the downloader while it commits a file, so they never race. */
    val lock = Mutex()

    private sealed interface Outcome {
        data class Complete(val tracks: List<ServerTrack>, val updated: OfflineSource) : Outcome
        data object Unchanged : Outcome
        data object Gone : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /**
     * One full pass.
     * @param force source ids to fetch in full even if their timestamp is unchanged
     */
    suspend fun sync(force: Set<String> = emptySet()): SyncReport = lock.withLock {
        val sources = store.sources().filter { it.enabled }
        val gate = try {
            gate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = "kids' lists unavailable: ${e.message ?: e.javaClass.simpleName}"
            return@withLock SyncReport(failed = sources.associate { it.id to reason })
        }
        // A new gate can let in tracks of sources whose timestamps haven't moved.
        val forceAll = gate != null && gate.key != lastGateKey
        val ok = mutableListOf<String>()
        val unchanged = mutableListOf<String>()
        val failed = mutableMapOf<String, String>()
        val gone = mutableListOf<String>()
        val known = mutableMapOf<String, ServerTrack>()

        // One cheap call gives every playlist's change timestamp.
        var listingError: String? = null
        val listing: Map<String, PlaylistInfo>? = if (sources.any { it.type == SourceType.PLAYLIST }) {
            try {
                server.playlistListing().associateBy { it.id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listingError = e.message ?: e.javaClass.simpleName
                null
            }
        } else emptyMap()

        for (source in sources) {
            val outcome = try {
                when (source.type) {
                    SourceType.PLAYLIST ->
                        if (listing == null) Outcome.Failed("playlist listing failed: $listingError")
                        else fetchPlaylist(source, listing, forceAll || source.id in force)
                    SourceType.ALBUM -> fetchAlbum(source)
                    else -> Outcome.Failed("unknown source type ${source.type}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Failed(e.message ?: e.javaClass.simpleName)
            }

            when (outcome) {
                is Outcome.Failed -> failed[source.id] = outcome.reason
                Outcome.Unchanged -> unchanged += source.id
                Outcome.Gone -> {
                    // Confirmed deleted on the server: drop the source and its claims.
                    store.removeSource(source.id)
                    gone += source.id
                }
                is Outcome.Complete -> {
                    val tracks = if (gate == null) outcome.tracks else outcome.tracks.filter { it.id in gate.allowed }
                    val diff = SyncPlanner.diffClaims(tracks.map { it.id }, store.claimsFor(source.id))
                    if (!diff.isEmpty) store.applyClaims(source.id, diff.add, diff.remove)
                    tracks.associateByTo(known) { it.id }
                    store.updateSource(outcome.updated)
                    ok += source.id
                }
            }
        }

        if (gate != null) {
            // Whatever the gate no longer allows loses its claims, fetched this pass or not.
            for (source in store.sources()) {
                val drop = store.claimsFor(source.id).filterNot { it in gate.allowed }.toSet()
                if (drop.isNotEmpty()) store.applyClaims(source.id, emptySet(), drop)
            }
            if (failed.isEmpty()) lastGateKey = gate.key
        } else lastGateKey = null

        // A full pass is a good moment to give failed downloads another go.
        store.retryFailed()
        filePass(known).copy(ok = ok, unchanged = unchanged, failed = failed, gone = gone)
    }

    /**
     * Deletes zero-claim files without fetching anything. Run when the playing
     * track changes, so a track removed while playing goes once it's finished.
     */
    suspend fun sweep(): SyncReport = lock.withLock { filePass(emptyMap()) }

    private suspend fun filePass(known: Map<String, ServerTrack>): SyncReport {
        val rows = store.downloads()
        val plan = SyncPlanner.planFiles(
            claimed = store.claimedTrackIds(),
            files = rows.map { LocalFile(it.trackId, it.state == DownloadState.DONE, it.size, it.suffix) },
            known = known,
            protected = protectedIds(),
        )
        val rowsById = rows.associateBy { it.trackId }
        for (id in plan.delete) {
            files.delete(id, rowsById[id]?.path)
            store.deleteDownload(id)
        }
        for (t in plan.redownload) files.delete(t.id, rowsById[t.id]?.path)
        val queueRows = (plan.queue + plan.redownload).map { it.toQueuedRow() }
        if (queueRows.isNotEmpty()) store.upsertDownloads(queueRows)
        return SyncReport(
            queued = plan.queue.size,
            redownload = plan.redownload.size,
            deleted = plan.delete,
            deferred = plan.deferred,
        )
    }

    private suspend fun fetchPlaylist(source: OfflineSource, listing: Map<String, PlaylistInfo>, force: Boolean): Outcome {
        val info = listing[source.serverId]
        if (info == null) {
            // Missing from the listing. Only a direct "not found" confirms deletion;
            // anything else (it loads fine, or errors) leaves everything alone.
            return try {
                server.playlist(source.serverId)
                Outcome.Failed("playlist missing from listing but still loads")
            } catch (e: GoneException) {
                Outcome.Gone
            }
        }
        val stale = now() - source.lastFullFetch > FULL_RECHECK_MS
        if (!force && !stale && info.changed != null && info.changed == source.changed) return Outcome.Unchanged

        val fetch = try {
            server.playlist(source.serverId)
        } catch (e: GoneException) {
            // It's in the listing, so a not-found here is an inconsistency, not a deletion.
            return Outcome.Failed("playlist listed but not found")
        }
        val claims = store.claimsFor(source.id).size
        // An empty playlist is confirmed by the listing independently saying 0.
        val trusted = SyncPlanner.isTrustworthy(
            fetched = fetch.tracks.size,
            reportedCount = fetch.songCount,
            currentClaims = claims,
            emptyConfirmed = info.songCount == 0 && fetch.songCount == 0,
        )
        if (!trusted) return Outcome.Failed("incomplete playlist response (${fetch.tracks.size} of ${fetch.songCount}, listing ${info.songCount})")
        return Outcome.Complete(
            fetch.tracks,
            source.copy(changed = fetch.changed ?: info.changed, name = fetch.name, lastFullFetch = now()),
        )
    }

    private suspend fun fetchAlbum(source: OfflineSource): Outcome {
        val tracks = try {
            server.album(source.serverId)
        } catch (e: GoneException) {
            // Ask twice: album ids are stable, and a one-off glitch must not wipe an album.
            try {
                server.album(source.serverId)
                return Outcome.Failed("album not-found was not repeatable")
            } catch (e2: GoneException) {
                return Outcome.Gone
            }
        }
        // An album with no songs isn't a real state on the server: never trust it.
        if (!SyncPlanner.isTrustworthy(tracks.size, null, store.claimsFor(source.id).size, emptyConfirmed = false) || tracks.isEmpty()) {
            return Outcome.Failed("album returned no songs")
        }
        return Outcome.Complete(tracks, source.copy(lastFullFetch = now()))
    }

    companion object {
        /** Timestamps don't move on re-tags, so re-fetch everything at least this often. */
        const val FULL_RECHECK_MS = 24L * 60 * 60 * 1000
    }
}

fun ServerTrack.toQueuedRow() = Download(
    trackId = id, state = DownloadState.QUEUED, size = size, suffix = suffix,
    title = title, artist = artist, album = album, albumId = albumId, artistId = artistId,
    coverArt = coverArt, duration = duration,
)
