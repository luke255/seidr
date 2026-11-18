package eu.bradshaw.seidr.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.data.api.Song
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

/**
 * A track in the play queue. [key] is unique per queue slot (the same song can
 * be queued twice); [user] marks tracks the user added ("Next in queue") as
 * opposed to ones from the playing context.
 */
@Serializable
data class QueueEntry(
    val key: String,
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val duration: Int = 0,
    val user: Boolean = false,
    /** Position in the original context, used to undo shuffle. -1 for user-queued tracks. */
    val ctxIndex: Int = -1,
    /** "Playlist name", "Album name"… shown as "Next from: …". */
    val ctxName: String? = null,
    /** The playlist the context came from, if it's one: the visualiser shows its picture. */
    val ctxPlaylist: String? = null,
    /** ReplayGain, in dB, when the file is tagged ([Loudness]). */
    val trackGain: Float? = null,
    val albumGain: Float? = null,
)

private val counter = AtomicLong(System.currentTimeMillis())
fun newKey(songId: String) = "$songId|${counter.incrementAndGet().toString(36)}"
fun songIdOf(mediaId: String) = mediaId.substringBefore('|')

/** The URI the player sees; [PlaybackService] resolves it to a local file or a stream at load time. */
fun songUri(songId: String): Uri = Uri.parse("seidr://song/$songId")

fun Song.toEntry(user: Boolean = false, ctxIndex: Int = -1, ctxName: String? = null, ctxPlaylist: String? = null) = QueueEntry(
    key = newKey(id), songId = id, title = title, artist = artistLine, album = album.orEmpty(),
    albumId = albumId, artistId = artistId, coverArt = coverArt, duration = duration,
    user = user, ctxIndex = ctxIndex, ctxName = ctxName, ctxPlaylist = ctxPlaylist,
    trackGain = replayGain?.trackGain, albumGain = replayGain?.albumGain,
)

fun QueueEntry.toMediaItem(graph: Graph): MediaItem {
    // A content:// URI, since Android Auto (and its media card by the map) won't load
    // our http or private file URIs. CoverProvider serves the downloaded cover if there is one.
    val art = coverArt?.let(CoverProvider::uri)
    val extras = Bundle().apply {
        putString("songId", songId)
        putString("albumId", albumId)
        putString("artistId", artistId)
        putString("coverArt", coverArt)
        putInt("duration", duration)
        putBoolean("user", user)
        putInt("ctxIndex", ctxIndex)
        putString("ctxName", ctxName)
        putString("ctxPlaylist", ctxPlaylist)
        trackGain?.let { putFloat("trackGain", it) }
        albumGain?.let { putFloat("albumGain", it) }
    }
    return MediaItem.Builder()
        .setMediaId(key)
        .setUri(songUri(songId))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(art)
                .setDurationMs(duration * 1000L)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(extras)
                .build()
        )
        .build()
}

fun MediaItem.toEntry(): QueueEntry {
    val m = mediaMetadata
    val x = m.extras ?: Bundle.EMPTY
    return QueueEntry(
        key = mediaId,
        songId = x.getString("songId") ?: songIdOf(mediaId),
        title = m.title?.toString().orEmpty(),
        artist = m.artist?.toString().orEmpty(),
        album = m.albumTitle?.toString().orEmpty(),
        albumId = x.getString("albumId"),
        artistId = x.getString("artistId"),
        coverArt = x.getString("coverArt"),
        duration = x.getInt("duration"),
        user = x.getBoolean("user"),
        ctxIndex = x.getInt("ctxIndex", -1),
        ctxName = x.getString("ctxName"),
        ctxPlaylist = x.getString("ctxPlaylist"),
        trackGain = if (x.containsKey("trackGain")) x.getFloat("trackGain") else null,
        albumGain = if (x.containsKey("albumGain")) x.getFloat("albumGain") else null,
    )
}
