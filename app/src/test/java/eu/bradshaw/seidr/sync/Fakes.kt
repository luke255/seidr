package eu.bradshaw.seidr.sync

import eu.bradshaw.seidr.data.db.Download
import eu.bradshaw.seidr.data.db.DownloadState
import eu.bradshaw.seidr.data.db.OfflineSource
import java.io.IOException

fun track(id: String, size: Long = 1000, suffix: String = "flac") = ServerTrack(id, size, suffix, title = "Song $id")

/**
 * An in-memory Navidrome. Tests mutate it like a user editing on the web UI,
 * and can make it misbehave (offline, truncated responses, flaky not-found).
 */
class FakeServer {
    class Pl(var name: String, var tracks: MutableList<ServerTrack>, var changed: Int = 1)

    val playlists = linkedMapOf<String, Pl>()
    val albums = linkedMapOf<String, MutableList<ServerTrack>>()

    var offline = false
    /** Return only the first N tracks of a playlist while still reporting the full count. */
    var truncateTo: Int? = null
    /** Leave this playlist out of the listing although it still exists. */
    var hideFromListing: String? = null
    /** Next N album calls report not-found even though the album exists. */
    var flakyAlbumNotFound = 0

    val calls = mutableListOf<String>()

    private fun net(call: String) {
        calls += call
        if (offline) throw IOException("unreachable")
    }

    fun playlist(id: String, vararg ids: String) {
        playlists[id] = Pl(id, ids.map { track(it) }.toMutableList())
    }

    fun edit(id: String, block: MutableList<ServerTrack>.() -> Unit) {
        val p = playlists.getValue(id)
        p.tracks.block()
        p.changed++
    }

    /** Deletes a song from the whole library, as a rescan would. */
    fun deleteSong(songId: String) {
        playlists.values.forEach { p -> if (p.tracks.removeAll { it.id == songId }) p.changed++ }
        albums.values.forEach { it.removeAll { t -> t.id == songId } }
    }

    val server = object : SyncServer {
        override suspend fun playlistListing(): List<PlaylistInfo> {
            net("listing")
            return playlists.filterKeys { it != hideFromListing }
                .map { (id, p) -> PlaylistInfo(id, p.name, "v${p.changed}", p.tracks.size) }
        }

        override suspend fun playlist(id: String): PlaylistFetch {
            net("playlist:$id")
            val p = playlists[id] ?: throw GoneException(id)
            val list = truncateTo?.let { p.tracks.take(it) } ?: p.tracks.toList()
            return PlaylistFetch(list, p.tracks.size, "v${p.changed}", p.name)
        }

        override suspend fun album(id: String): List<ServerTrack> {
            net("album:$id")
            if (flakyAlbumNotFound > 0) { flakyAlbumNotFound--; throw GoneException(id) }
            return albums[id]?.toList() ?: throw GoneException(id)
        }
    }
}

class FakeStore : SyncStore {
    val sources = linkedMapOf<String, OfflineSource>()
    val claims = mutableSetOf<Pair<String, String>>() // trackId to sourceId
    val downloads = linkedMapOf<String, Download>()

    override suspend fun sources() = sources.values.toList()
    override suspend fun updateSource(source: OfflineSource) { sources[source.id] = source }
    override suspend fun removeSource(id: String) {
        sources.remove(id)
        claims.removeAll { it.second == id }
    }
    override suspend fun claimsFor(sourceId: String) = claims.filter { it.second == sourceId }.map { it.first }.toSet()
    override suspend fun applyClaims(sourceId: String, add: Set<String>, remove: Set<String>) {
        add.forEach { claims += it to sourceId }
        remove.forEach { claims -= it to sourceId }
    }
    override suspend fun claimedTrackIds() = claims.map { it.first }.toSet()
    override suspend fun downloads() = downloads.values.toList()
    override suspend fun upsertDownloads(rows: List<Download>) { rows.forEach { downloads[it.trackId] = it } }
    override suspend fun deleteDownload(trackId: String) { downloads.remove(trackId) }
    override suspend fun retryFailed() {
        downloads.replaceAll { _, d -> if (d.state == DownloadState.FAILED) d.copy(state = DownloadState.QUEUED) else d }
    }

    /** Pretend the download worker finished every queued file. */
    fun completeAll() {
        downloads.replaceAll { id, d ->
            if (d.state == DownloadState.QUEUED) d.copy(state = DownloadState.DONE, path = "/music/$id.${d.suffix}") else d
        }
    }
}

class FakeFiles : FileDeleter {
    val deleted = mutableListOf<String>()
    override fun delete(trackId: String, path: String?) { deleted += trackId }
}
