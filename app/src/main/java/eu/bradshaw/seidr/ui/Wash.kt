package eu.bradshaw.seidr.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.bradshaw.seidr.ui.player.rememberArtColours
import eu.bradshaw.seidr.ui.theme.LocalColourful
import eu.bradshaw.seidr.ui.theme.artScheme
import eu.bradshaw.seidr.ui.theme.topWash

/**
 * The colour of one back-stack entry's top wash. The shell draws the wash
 * (behind the status bar too), so a screen sets its colour here rather than
 * painting its own. Null is the theme's wash.
 */
class WashState {
    var color by mutableStateOf<Color?>(null)
}

val LocalWash = staticCompositionLocalOf<WashState?> { null }

/**
 * Colours this screen's wash from the cover [coverId], as Now Playing does
 * (not under Neutral). Monochrome or unloaded art keeps the theme's wash.
 */
@Composable
fun ArtWash(coverId: String?) {
    val state = LocalWash.current ?: return
    val art = rememberArtColours(if (LocalColourful.current) coverId else null)
    val color = art?.let { artScheme(it).wash }
    DisposableEffect(state, color) {
        state.color = color
        onDispose { state.color = null }
    }
}

/** The wash for [state], cross-fading when it changes. */
@Composable
fun washBrush(state: WashState?, height: Dp = 440.dp, themeStrength: Float = 0.8f): Brush {
    val target = state?.color ?: MaterialTheme.colorScheme.primaryContainer.copy(alpha = themeStrength)
    val c by animateColorAsState(target, tween(500), label = "wash")
    return topWash(height, 1f, c)
}
