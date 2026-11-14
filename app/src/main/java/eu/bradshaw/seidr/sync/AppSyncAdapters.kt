package eu.bradshaw.seidr.sync

import eu.bradshaw.seidr.data.api.Song
import eu.bradshaw.seidr.data.api.SubsonicApi
import eu.bradshaw.seidr.data.api.SubsonicException
import eu.bradshaw.seidr.data.api.ok
import eu.bradshaw.seidr.data.db.Download
import eu.bradshaw.seidr.data.db.OfflineDao
import eu.bradshaw.seidr.data.db.OfflineSource

fun Song.toServerTrack() = ServerTrack(
    id = id,
    size = size,
    suffix = suffix ?: contentType?.substringAfter('/')?.substringBefore(';') ?: "bin",
    title = title,
    artist = artistLine,
    album = album.orEmpty(),
    albumId = albumId,
    artistId = artistId,
    coverArt = coverArt,
    duration = duration,
)

/** [SyncServer] over the strict API: no cached fallbacks, every failure throws. */
class SubsonicSyncServer(private val api: () -> SubsonicApi) : SyncServer {
    private inline fun <T> notFoundAsGone(what: String, block: () -> T): T = try {
        block()
    } catch (e: SubsonicException) {
        if (e.code == 70) throw GoneException("$what not found") else throw e
    }

    override suspend fun playlistListing(): List<PlaylistInfo> =
        api().getPlaylists().ok().playlists?.playlist.orEmpty()
            .map { PlaylistInfo(it.id, it.name, it.changed, it.songCount) }

    override suspend fun playlist(id: String): PlaylistFetch = notFoundAsGone("playlist $id") {
        val p = api().getPlaylist(id).ok().playlist ?: throw SubsonicException(0, "no playlist in response")
        PlaylistFetch(p.entry.map { it.toServerTrack() }, p.songCount, p.changed, p.name)
    }

    override suspend fun album(id: String): List<ServerTrack> = notFoundAsGone("album $id") {
        val a = api().getAlbum(id).ok().album ?: throw SubsonicException(0, "no album in response")
        // The album's own count must match, same as playlists.
        if (a.songCount != a.song.size) throw SubsonicException(0, "album response incomplete")
        a.song.map { it.toServerTrack() }
    }
}

class RoomSyncStore(private val dao: OfflineDao) : SyncStore {
    override suspend fun sources() = dao.sources()
    override suspend fun updateSource(source: OfflineSource) {
        // The user may have switched Download off while the fetch ran: never resurrect it.
        if (dao.source(source.id) != null) dao.upsertSource(source)
    }
    override suspend fun removeSource(id: String) = dao.deleteSource(id)
    override suspend fun claimsFor(sourceId: String) = dao.claimsFor(sourceId).toSet()
    override suspend fun applyClaims(sourceId: String, add: Set<String>, remove: Set<String>) {
        if (dao.source(sourceId) == null) return
        dao.applyClaims(sourceId, add, remove)
    }
    override suspend fun claimedTrackIds() = dao.claimedTrackIds().toSet()
    override suspend fun downloads() = dao.downloads()
    override suspend fun upsertDownloads(rows: List<Download>) {
        // Keep the state of rows that already exist unless they're being re-queued.
        dao.upsertDownloads(rows)
    }
    override suspend fun deleteDownload(trackId: String) = dao.deleteDownload(trackId)
    override suspend fun retryFailed() = dao.retryFailed()
}
