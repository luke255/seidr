package eu.bradshaw.seidr.playback

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaSession
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.data.api.Album
import eu.bradshaw.seidr.data.api.Playlist
import eu.bradshaw.seidr.data.api.Song
import eu.bradshaw.seidr.ui.screens.Loved
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The browse tree for Android Auto and other media browsers. The root's
 * folders are the car's tabs, Recent first so it's the one that opens:
 *
 * - **Recent**: playlists and albums, most recently played on this phone first,
 *   then the never-played playlists A–Z (as the phone's Recent sort). An album
 *   only counts when it was played from its own page ([AlbumPlays]).
 * - **Playlists**: Loved (the starred songs), then all the others A–Z.
 *
 * Playlists and albums are playable, not folders: a playlist shuffles, an album
 * plays in order. Browse ids name the context: `play/<context>/<index>` or
 * `shuffle/<context>`.
 *
 * "For you" on the media card by the map is the Google app's suggestions,
 * not ours: it reads this tree on its own schedule and plays by media id, so
 * ids from older builds (`pl:<id>`, `al:<id>`) still resolve. Media types
 * are set so it can tell playlists and albums from tracks.
 *
 * Lists come from memory or the last-seen responses, never waiting on the
 * network; [refresh] fetches behind them. Starting playback tries the server
 * briefly, then falls back to the last-seen copy.
 */
@OptIn(UnstableApi::class)
class LibraryTree(private val context: Context, private val graph: Graph) {

    fun root(): MediaItem = folder(ROOT, context.getString(R.string.app_name))

    suspend fun children(parentId: String): List<MediaItem> = when (parentId) {
        ROOT -> listOf(
            folder(RECENT, context.getString(R.string.car_recent), grid = true),
            folder(PLAYLISTS, context.getString(R.string.car_playlists)),
        )
        RECENT -> recent()
        PLAYLISTS -> playlistItems()
        else -> emptyList()
    }

    /**
     * Fetches what [children] shows for [parentId]. Returns the new item count
     * if the list changed (so browsers should reload it), else null.
     */
    suspend fun refresh(parentId: String): Int? {
        val before = children(parentId).map(::signature)
        runCatching {
            when (parentId) {
                RECENT, PLAYLISTS -> graph.library.refreshPlaylists()
                else -> return null
            }
        }.onFailure { return null }
        val after = children(parentId).map(::signature)
        return if (after != before) after.size else null
    }

    suspend fun item(mediaId: String): MediaItem? = when (mediaId) {
        ROOT -> root()
        else -> null
    }

    /**
     * Turns what a browser asked to play into a queue. Returns null for items
     * that aren't ours to expand (the app's own controller sends real queue items).
     */
    suspend fun resolve(mediaId: String): MediaSession.MediaItemsWithStartPosition? {
        val parts = mediaId.split('/')
        val (shuffle, ctx, start) = when {
            mediaId.startsWith(PL) -> Triple(true, mediaId, -1)
            mediaId.startsWith(AL) -> Triple(false, mediaId, 0)
            parts.size == 3 && parts[0] == "play" -> Triple(false, parts[1], parts[2].toIntOrNull() ?: 0)
            parts.size == 2 && parts[0] == "shuffle" -> Triple(true, parts[1], -1)
            else -> return null
        }
        val c = contextSongs(ctx) ?: return null
        if (ctx.startsWith(PL)) graph.playlistPlays.record(ctx.removePrefix(PL))
        if (ctx == LOVED) graph.playlistPlays.record(Loved.PLAYS_KEY)
        c.album?.let(graph.albumPlays::record)
        return queue(c.songs, start, c.name, shuffle)
    }

    private suspend fun playlists(): List<Playlist> =
        graph.library.cachedPlaylists() ?: fetchOrNull { graph.library.refreshPlaylists() }.orEmpty()

    private suspend fun playlistItems(): List<MediaItem> =
        listOf(lovedItem(withCount = true)) +
            playlists().sortedWith(byName).map { playable("shuffle/$PL${it.id}", it.name, songCount(it.songCount), it.coverArt, MediaMetadata.MEDIA_TYPE_PLAYLIST) }

    /** Loved, with the newest loved song's cover (it has none of its own). */
    private fun lovedItem(withCount: Boolean): MediaItem {
        val songs = graph.library.cachedStarredSongs()
        val cover = songs?.firstNotNullOfOrNull { it.coverArt }
        val sub = if (withCount && songs != null) songCount(songs.size) else context.getString(R.string.car_playlist)
        return playable("shuffle/$LOVED", Loved.NAME, sub, cover, MediaMetadata.MEDIA_TYPE_PLAYLIST)
    }

    private suspend fun recent(): List<MediaItem> {
        val last = graph.playlistPlays.lastPlayed.value
        val (played, unplayed) = playlists().partition { it.id in last }
        val loved = last[Loved.PLAYS_KEY]
        val pls = played.map { p -> last.getValue(p.id) to playlistItem(p) } +
            listOfNotNull(loved?.let { it to lovedItem(withCount = false) })
        val albums = KidGate.recentAlbums(graph.albumPlays.recent(), graph.kids.current).map { a ->
            a.at to playable("play/$AL${a.id}/0", a.name, a.artist, a.coverArt, MediaMetadata.MEDIA_TYPE_ALBUM)
        }
        return (pls + albums).sortedByDescending { it.first }.take(RECENT_COUNT).map { it.second } +
            listOfNotNull(if (loved == null) lovedItem(withCount = false) else null) +
            unplayed.sortedWith(byName).map(::playlistItem)
    }

    private fun playlistItem(p: Playlist) = playable("shuffle/$PL${p.id}", p.name, context.getString(R.string.car_playlist), p.coverArt, MediaMetadata.MEDIA_TYPE_PLAYLIST)

    private fun songCount(n: Int) = context.resources.getQuantityString(R.plurals.car_songs, n, n)

    private val byName = compareBy<Playlist, String>(String.CASE_INSENSITIVE_ORDER) { it.name }

    /** Plays the best match for a voice search ("play X on Seiðr"). */
    suspend fun search(query: String): MediaSession.MediaItemsWithStartPosition? {
        if (query.isBlank()) {
            // "Play music on Seiðr": shuffle the playlist played most recently.
            val last = graph.playlistPlays.lastPlayed.value
            val p = playlists().filter { it.id in last }.maxByOrNull { last.getValue(it.id) } ?: return null
            val (name, songs) = contextSongs("$PL${p.id}") ?: return null
            graph.playlistPlays.record(p.id)
            return queue(songs, -1, name, shuffle = true)
        }
        // A playlist named exactly what was asked for wins ("play Active library").
        playlists().firstOrNull { it.name.equals(query.trim(), ignoreCase = true) }?.let { p ->
            contextSongs("$PL${p.id}")?.let { (name, songs) ->
                graph.playlistPlays.record(p.id)
                return queue(songs, -1, name, shuffle = true)
            }
        }
        val r = runCatching { graph.library.search(query) }.getOrNull() ?: return null
        r.album.firstOrNull()?.takeIf { r.song.isEmpty() || it.name.equals(query, ignoreCase = true) }?.let { a ->
            contextSongs("$AL${a.id}")?.let { (name, songs) -> return queue(songs, 0, name, shuffle = false) }
        }
        if (r.song.isNotEmpty()) return queue(r.song, 0, query, shuffle = false)
        r.artist.firstOrNull()?.let { a ->
            val top = runCatching { graph.library.topSongs(a.name) }.getOrNull().orEmpty()
            if (top.isNotEmpty()) return queue(top, 0, a.name, shuffle = false)
        }
        return null
    }

    private data class Ctx(val name: String, val songs: List<Song>, val album: Album? = null)

    private suspend fun contextSongs(ctx: String): Ctx? = when {
        ctx == LOVED -> freshOr({ graph.library.cachedStarredSongs() }) { graph.library.starredSongsList() }
            ?.let { Ctx(Loved.NAME, it) }
        ctx.startsWith(PL) -> ctx.removePrefix(PL).let { id ->
            freshOr({ graph.library.cachedPlaylist(id) }) { graph.library.playlist(id) }?.let { Ctx(it.name, it.entry) }
        }
        ctx.startsWith(AL) -> ctx.removePrefix(AL).let { id ->
            freshOr({ graph.library.cachedAlbum(id) }) { graph.library.album(id) }?.let { Ctx(it.name, it.song, it) }
        }
        else -> null
    }

    /** The server's copy if it answers quickly, else the last-seen one: a tap in the car shouldn't hang. */
    private suspend fun <T> freshOr(cached: () -> T?, fetch: suspend () -> T): T? =
        withTimeoutOrNull(PLAY_FETCH_MS) { runCatching { fetch() }.getOrNull() } ?: cached()

    /** For lists with no last-seen copy yet (first use): wait, but not forever. */
    private suspend fun <T> fetchOrNull(fetch: suspend () -> T): T? =
        withTimeoutOrNull(FIRST_FETCH_MS) { runCatching { fetch() }.getOrNull() }

    /** The same queue rules as [PlayerConnection.playContext], minus "Next in queue". */
    private fun queue(songs: List<Song>, start: Int, name: String, shuffle: Boolean): MediaSession.MediaItemsWithStartPosition? {
        val entries = songs.mapIndexed { i, s -> s.toEntry(ctxIndex = i, ctxName = name) }
        val (all, first) = KidGate.queue(entries, entries.getOrNull(start), graph.kids.current, graph.status.online.value,
            graph.downloads::isDownloaded)
        if (all.isEmpty()) return null
        PlayerPrefs.setShuffle(context, shuffle)
        val head = first ?: if (shuffle) all.random() else all.first()
        val ordered = if (shuffle) listOf(head) + (all - head).shuffled() else all
        val index = ordered.indexOf(head)
        return MediaSession.MediaItemsWithStartPosition(ordered.map { it.toMediaItem(graph) }, index, 0)
    }

    private fun signature(item: MediaItem) = with(item.mediaMetadata) { "${item.mediaId}|$title|$subtitle" }

    private fun folder(id: String, title: String, grid: Boolean = false) =
        item(id, title, null, null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, browsable = true, grid = grid)

    private fun playable(id: String, title: String, subtitle: String?, cover: String?, type: Int = MediaMetadata.MEDIA_TYPE_MUSIC) =
        item(id, title, subtitle, cover, type, browsable = false)

    private fun item(
        id: String, title: String, subtitle: String?, cover: String?, type: Int, browsable: Boolean, grid: Boolean = false,
    ): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtist(subtitle)
                    .setArtworkUri(cover?.let { CoverProvider.uri(it) })
                    .setIsBrowsable(browsable)
                    .setIsPlayable(!browsable)
                    .setMediaType(type)
                    .apply {
                        // How the car lays out this folder's playable items.
                        if (browsable) setExtras(Bundle().apply {
                            putInt(
                                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                                if (grid) MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                                else MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                            )
                        })
                    }
                    .build(),
            )
            .build()

    companion object {
        const val ROOT = "root"
        const val RECENT = "recent"
        const val PLAYLISTS = "playlists"
        const val PL = "pl:"
        const val AL = "al:"
        const val LOVED = "loved"
        private const val RECENT_COUNT = 40
        private const val PLAY_FETCH_MS = 4_000L
        private const val FIRST_FETCH_MS = 10_000L
    }
}
