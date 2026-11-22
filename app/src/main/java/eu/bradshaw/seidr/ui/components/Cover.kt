package eu.bradshaw.seidr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import eu.bradshaw.seidr.sync.stableCoverId
import kotlinx.coroutines.delay
import eu.bradshaw.seidr.CoverArt

/** Coil's default crossfade, which the image loader turns on. */
private const val CROSSFADE_MS = 200L

/** Album/playlist/artist art by Subsonic cover id, or a placeholder. */
@Composable
fun Cover(
    id: String?,
    modifier: Modifier = Modifier,
    size: Int = 300,
    shape: Shape = RectangleShape,
    artist: Boolean = false,
) {
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        if (id == null) {
            Placeholder(artist)
        } else {
            // Navidrome restamps a cover id whenever its album or playlist changes (each
            // song added to a playlist), so the same card gets a new id. Keep the picture
            // it showed underneath until the new one has loaded and faded in, rather
            // than dropping to the placeholder: that drop is a flicker.
            var shown by remember { mutableStateOf<String?>(null) }
            var loaded by remember { mutableStateOf<String?>(null) }
            val behind = shown?.takeIf { it != id && stableCoverId(it) == stableCoverId(id) }
            if (behind != null) AsyncImage(
                model = CoverArt(behind, size),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            SubcomposeAsyncImage(
                model = CoverArt(id, size),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { if (behind == null) Placeholder(artist) },
                // Out of reach: the old picture is still the right one.
                error = { if (behind == null) Placeholder(artist) },
                onSuccess = { loaded = id },
            )
            // Let go of the old picture once the crossfade over it has finished.
            LaunchedEffect(loaded) {
                if (loaded != id) return@LaunchedEffect
                if (behind != null) delay(CROSSFADE_MS)
                shown = loaded
            }
        }
    }
}

@Composable
private fun Placeholder(artist: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(
            if (artist) Icons.Filled.Person else Icons.Filled.MusicNote, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(0.4f),
        )
    }
}

/** The classic playlist header: four distinct album covers in a square. */
@Composable
fun Mosaic(coverIds: List<String>, modifier: Modifier = Modifier, fallback: String? = null) {
    val ids = coverIds.distinct().take(4)
    if (ids.size < 4) {
        Cover(ids.firstOrNull() ?: fallback, modifier.aspectRatio(1f), size = 600)
        return
    }
    Column(modifier.aspectRatio(1f)) {
        Row(Modifier.weight(1f)) {
            Cover(ids[0], Modifier.weight(1f).fillMaxSize())
            Cover(ids[1], Modifier.weight(1f).fillMaxSize())
        }
        Row(Modifier.weight(1f)) {
            Cover(ids[2], Modifier.weight(1f).fillMaxSize())
            Cover(ids[3], Modifier.weight(1f).fillMaxSize())
        }
    }
}

@Composable
fun ArtistImage(id: String?, modifier: Modifier = Modifier) =
    Cover(id, modifier, shape = CircleShape, artist = true)
