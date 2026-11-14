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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import coil3.compose.SubcomposeAsyncImage
import eu.bradshaw.seidr.CoverArt

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
            SubcomposeAsyncImage(
                model = CoverArt(id, size),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { Placeholder(artist) },
                error = { Placeholder(artist) },
            )
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
