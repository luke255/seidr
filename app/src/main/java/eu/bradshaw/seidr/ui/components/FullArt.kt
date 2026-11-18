package eu.bradshaw.seidr.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.bradshaw.seidr.CoverArt
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.ui.artGradient
import eu.bradshaw.seidr.ui.player.rememberArtLook
import eu.bradshaw.seidr.ui.theme.LocalColourful
import eu.bradshaw.seidr.ui.washBrush

/**
 * An album's or playlist's art, shown full screen: its [cover], or for a
 * playlist without a picture of its own the [mosaic] of its albums (with
 * [cover] as the fallback when there aren't four).
 */
data class FullArt(val cover: String?, val mosaic: List<String>? = null)

/**
 * [art] big, centred on its own colours (Now Playing's gradient; the
 * theme's wash for a mosaic, or under Neutral). A tap anywhere closes it.
 * [back] is how far a back swipe has gone (0..1): the picture fades and
 * shrinks a little with the finger, so letting go finishes the fade.
 */
@Composable
fun FullArtView(art: FullArt, back: () -> Float, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val look = rememberArtLook(if (LocalColourful.current && art.mosaic == null) art.cover else null)
    val background = MaterialTheme.colorScheme.background
    val wash = washBrush()
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - back() }
            .drawBehind {
                drawRect(background)
                if (look != null) drawRect(artGradient(look.wash, look.scheme.background)) else drawRect(wash)
            }
            .clickable(interactionSource = null, indication = null, onClickLabel = stringResource(R.string.common_close), onClick = onClose)
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, maxHeight)
            Box(
                Modifier
                    .size(side)
                    .graphicsLayer { val s = 1f - BACK_SHRINK * back(); scaleX = s; scaleY = s }
                    .shadow(24.dp, shape)
                    .clip(shape),
            ) {
                if (art.mosaic != null) Mosaic(art.mosaic, Modifier.fillMaxSize(), fallback = art.cover)
                else if (art.cover != null) {
                    // The header's copy is cached, so it shows at once; the full size fades in over it.
                    Cover(art.cover, Modifier.fillMaxSize(), size = 600)
                    AsyncImage(CoverArt(art.cover, FULL_SIZE), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else Cover(null, Modifier.fillMaxSize())
            }
        }
    }
}

/** Big enough for a tablet's or a Fold's inner screen; the server never scales art up. */
private const val FULL_SIZE = 1600

/** How much smaller the picture gets as a back swipe goes all the way. */
private const val BACK_SHRINK = 0.1f
