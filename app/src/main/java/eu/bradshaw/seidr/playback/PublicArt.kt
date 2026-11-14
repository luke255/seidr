package eu.bradshaw.seidr.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.data.api.ok
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android TV only. Things outside the TV read its media session too (Google
 * Cast, and so Home Assistant), and they can't open our `content://` artwork.
 * This swaps it, on the current and next items, for the album's cover link
 * from `getAlbumInfo2`: Navidrome signs those for public use, one image each,
 * with no credentials in the URL. Android Auto never runs on a TV, so the
 * `content://` URIs it needs aren't lost.
 *
 * Only the item's metadata changes, so ExoPlayer keeps playing through the swap.
 */
@OptIn(UnstableApi::class)
class PublicArt(private val graph: Graph, private val scope: CoroutineScope, private val player: ExoPlayer) {
    /** Album id → its public cover link, or null when the server has none. */
    private val links = mutableMapOf<String, Uri?>()
    private val fetching = mutableSetOf<String>()

    /** On the main thread, outside player callbacks (it edits the timeline). */
    fun fill() {
        val count = player.mediaItemCount
        val indexes = listOf(player.currentMediaItemIndex, player.nextMediaItemIndex)
            .filter { it != C.INDEX_UNSET && it < count }.distinct()
        for (i in indexes) {
            val item = player.getMediaItemAt(i)
            val meta = item.mediaMetadata
            if (meta.artworkUri?.scheme != "content") continue
            val albumId = meta.extras?.getString("albumId") ?: continue
            if (albumId in links) {
                val link = links[albumId] ?: continue
                player.replaceMediaItem(i, item.buildUpon().setMediaMetadata(meta.buildUpon().setArtworkUri(link).build()).build())
            } else if (fetching.add(albumId)) scope.launch {
                // Lenient client: offline, a link seen before still works once HA can reach the server.
                val info = withContext(Dispatchers.IO) { runCatching { graph.client.api.getAlbumInfo2(albumId).ok().albumInfo } }
                fetching.remove(albumId)
                // A failed fetch isn't remembered, so the next track change tries again.
                info.onSuccess { links[albumId] = publicLink(it?.mediumImageUrl ?: it?.largeImageUrl); fill() }
            }
        }
    }

    private fun publicLink(url: String?): Uri? =
        url?.toUri()?.takeIf { it.scheme == "http" || it.scheme == "https" }
}
