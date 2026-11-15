package eu.bradshaw.seidr.data

import android.content.Context
import android.util.Log
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.data.api.Playlist
import eu.bradshaw.seidr.data.api.SubsonicApi
import eu.bradshaw.seidr.data.api.ok
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import eu.bradshaw.seidr.data.db.EditType
import eu.bradshaw.seidr.data.db.OfflineDao
import eu.bradshaw.seidr.sync.EditResult
import eu.bradshaw.seidr.sync.PlaylistEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One of the server's "Bad: " playlists, where problems are reported for fixing elsewhere. */
enum class BadKind(val playlistName: String, val noun: String) {
    SONG("Bad: Songs", "song"),
    ART("Bad: Art", "artwork"),
    LYRICS("Bad: Lyrics", "lyrics"),
}

/**
 * Opt-in (Settings → Reporting) link to the "Bad: " playlists that an outside
 * process works through, created if missing. Reporting adds the song to the
 * matching playlist; "Bad: " playlists are hidden from screens; songs in
 * Bad: Songs are kept out of the queue ([skipped]; `PlaybackService` hides
 * them and puts them back once fixed).
 *
 * The server's playlists are the truth, so once that process takes a song out
 * of Bad: Songs (fixed or replaced), it plays again. Until then, a report not
 * yet on the server (in flight, or queued offline in `pending_edit`) counts.
 */
class BadReports(
    /** Settings → Reporting is on (and this isn't a kid's login). */
    val enabled: StateFlow<Boolean>,
    private val library: Library,
    private val editor: PlaylistEditor,
    private val dao: OfflineDao,
    private val scope: CoroutineScope,
    private val context: Context,
    /** The strict API: deciding a playlist is missing must never rest on a cached list. */
    private val api: () -> SubsonicApi,
) {
    private val refreshLock = Mutex()

    /** The playlist for each kind, matched by name. A kind without one can't be reported. */
    val playlists: StateFlow<Map<BadKind, Playlist>> =
        library.allPlaylists.map { find(it.orEmpty()) }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Wanted state (true = reported) of edits the server doesn't reflect yet, per kind and song. */
    private val queued = MutableStateFlow<Map<BadKind, Map<String, Boolean>>>(emptyMap())
    private val inFlight = MutableStateFlow<Map<BadKind, Map<String, Boolean>>>(emptyMap())

    /** Reported song ids per kind: the server's lists, as last seen, plus unconfirmed edits. */
    val reported: StateFlow<Map<BadKind, Set<String>>> =
        combine(playlists, library.playlistSongs, queued, inFlight) { pls, songs, q, f ->
            BadKind.entries.associateWith { kind ->
                val wanted = q[kind].orEmpty() + f[kind].orEmpty()
                pls[kind]?.let { songs[it.id] }.orEmpty().toSet() +
                    wanted.filterValues { it }.keys - wanted.filterValues { !it }.keys
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Songs playback must skip: Bad: Songs, while the integration is on. */
    val skipped: StateFlow<Set<String>> =
        combine(enabled, reported) { on, r -> if (on) r[BadKind.SONG].orEmpty() else emptySet() }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    init {
        // Edits from here, and other clients' edits via LiveEvents, refetch the list.
        scope.launch {
            library.playlistEdited.collect { id ->
                if (enabled.value && playlists.value.values.any { it.id == id }) runCatching { library.playlist(id) }
            }
        }
        scope.launch { enabled.collect { if (it) refresh() } }
    }

    /**
     * Creates any missing playlists, then refetches the three (the lenient API:
     * offline, the last-seen lists stand).
     */
    suspend fun refresh() = refreshLock.withLock {
        if (!enabled.value) return@withLock
        createMissing()
        val all = runCatching { library.fetchAllPlaylists() }.getOrNull() ?: library.allPlaylists.value.orEmpty()
        val found = find(all)
        loadQueued(found)
        for (p in found.values) runCatching { library.playlist(p.id) }
    }

    /** Reports [songId], or takes the report back if it's already there. Returns a message for the user. */
    suspend fun toggle(kind: BadKind, songId: String): String {
        val playlist = find(library.allPlaylists.value.orEmpty())[kind]
            ?: return context.getString(R.string.report_no_playlist, kind.playlistName)
        val report = songId !in reported.value[kind].orEmpty()
        inFlight.update { it.with(kind, songId, report) }
        val result = if (report) editor.add(playlist.id, listOf(songId)) else editor.remove(playlist.id, songId, null)
        if (result == EditResult.Applied) runCatching { library.playlist(playlist.id) }
        loadQueued(find(library.allPlaylists.value.orEmpty()))
        inFlight.update { m -> m + (kind to m[kind].orEmpty().minus(songId)) }
        return when (result) {
            EditResult.Applied -> when {
                !report -> context.getString(R.string.report_removed, kind.playlistName)
                kind == BadKind.SONG -> context.getString(R.string.report_song_reported)
                kind == BadKind.ART -> context.getString(R.string.report_art_reported)
                else -> context.getString(R.string.report_lyrics_reported)
            }
            EditResult.Queued -> context.getString(R.string.report_queued, kind.playlistName)
            EditResult.Conflict -> context.getString(R.string.report_conflict, kind.playlistName)
            is EditResult.Failed -> context.getString(R.string.report_failed, kind.playlistName, result.message)
        }
    }

    /**
     * Creates the playlists that two fresh fetches in a row agree are missing,
     * so a glitchy or truncated list can't produce duplicates. Any playlist with
     * the name counts as there, even one we can't add to.
     */
    private suspend fun createMissing() {
        suspend fun missing(): Set<BadKind>? = runCatching {
            val names = api().getPlaylists().ok().playlists?.playlist.orEmpty().map { it.name.trim().lowercase() }.toSet()
            BadKind.entries.filter { it.playlistName.lowercase() !in names }.toSet()
        }.getOrNull()
        val first = missing()?.takeIf { it.isNotEmpty() } ?: return
        val confirmed = first intersect (missing() ?: return)
        for (kind in confirmed) {
            if (editor.create(kind.playlistName, emptyList()) == null) Log.w("BadReports", "couldn't create ${kind.playlistName}")
        }
    }

    private suspend fun loadQueued(found: Map<BadKind, Playlist>) {
        val kindOf = found.entries.associate { (k, p) -> p.id to k }
        val out = mutableMapOf<BadKind, Map<String, Boolean>>()
        for (e in runCatching { dao.edits() }.getOrDefault(emptyList())) {
            val kind = kindOf[e.playlistId] ?: continue
            val song = e.songId ?: continue
            when (e.type) {
                EditType.ADD -> out[kind] = out[kind].orEmpty() + (song to true)
                EditType.REMOVE -> out[kind] = out[kind].orEmpty() + (song to false)
            }
        }
        queued.value = out
    }

    fun clear() {
        queued.value = emptyMap()
        inFlight.value = emptyMap()
    }

    private fun Map<BadKind, Map<String, Boolean>>.with(kind: BadKind, song: String, value: Boolean) =
        this + (kind to this[kind].orEmpty().plus(song to value))

    companion object {
        fun isBadPlaylist(name: String) = name.trimStart().startsWith("Bad:", ignoreCase = true)

        /** The editable playlist named for each kind ("Bad: Songs" and so on, any case). */
        fun find(all: List<Playlist>): Map<BadKind, Playlist> = BadKind.entries.mapNotNull { kind ->
            all.firstOrNull { !it.readonly && it.name.trim().equals(kind.playlistName, ignoreCase = true) }?.let { kind to it }
        }.toMap()
    }
}
