package eu.bradshaw.seidr.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.bradshaw.seidr.ui.player.SchemeFade
import eu.bradshaw.seidr.ui.player.lerpScheme
import eu.bradshaw.seidr.ui.player.rememberArtLook
import eu.bradshaw.seidr.ui.theme.ArtScheme
import eu.bradshaw.seidr.ui.theme.LocalColourful
import eu.bradshaw.seidr.ui.theme.topWash

/**
 * One back-stack entry's background. The shell draws it (behind the status bar
 * too), so a screen sets [art] here rather than painting its own: null is the
 * theme's top wash, otherwise Now Playing's whole-page gradient from [art],
 * faded in by [shown].
 */
@Stable
class WashState {
    var art by mutableStateOf<ArtScheme?>(null)
    /** How far [art] has faded in (0..1). Snapshot-backed: read it while drawing. */
    var shown: () -> Float = { 0f }

    /** The page's background colour at the bottom, now. Read it while drawing. */
    fun bottom(theme: Color): Color = art?.let { lerp(theme, it.scheme.background, shown()) } ?: theme
}

val LocalWash = staticCompositionLocalOf<WashState?> { null }

/** Now Playing's background: [wash] at the top, down to [under]. */
fun artGradient(wash: Color, under: Color): Brush = Brush.verticalGradient(listOf(wash, lerp(under, wash, 0.6f), under))

/**
 * Gives [content] Now Playing's treatment from the cover [coverId]: its colour
 * scheme, and its gradient over the whole page (not under Neutral). Until the
 * colours load (or if they can't), the page keeps the app's theme; then it
 * fades across, stepping the theme as [SchemeFade] does.
 */
@Composable
fun ArtPage(coverId: String?, content: @Composable () -> Unit) {
    val state = LocalWash.current
    val base = MaterialTheme.colorScheme
    val look = rememberArtLook(if (LocalColourful.current) coverId else null)
    // The last look, kept while fading back out.
    val kept = remember { arrayOfNulls<ArtScheme>(1) }
    if (look != null) kept[0] = look
    val target = kept[0]
    // Already known (cached) on arrival: no fade.
    val shown = remember { Animatable(if (look != null) 1f else 0f) }
    LaunchedEffect(look != null) { shown.animateTo(if (look != null) 1f else 0f, tween(ART_PAGE_FADE_MS, easing = EaseInOutSine)) }
    val scheme by remember(base, target) {
        derivedStateOf { if (target == null) base else lerpScheme(base, target.scheme, SchemeFade.stepOf(shown.value)) }
    }
    if (state != null) {
        DisposableEffect(state, target) {
            state.shown = { shown.value }
            state.art = target
            onDispose { state.art = null; state.shown = { 0f } }
        }
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}

/** Slow and eased at both ends: the colours arrive a moment after the page, so they settle in rather than snap. */
private const val ART_PAGE_FADE_MS = 1200

/**
 * [base] taken toward [state]'s art as far as it has faded in, in
 * [SchemeFade]'s steps: for chrome over an [ArtPage], such as the nav bar.
 */
@Composable
fun rememberPageScheme(state: WashState, base: ColorScheme): ColorScheme {
    val scheme by remember(state, base) {
        derivedStateOf { state.art?.let { lerpScheme(base, it.scheme, SchemeFade.stepOf(state.shown())) } ?: base }
    }
    return scheme
}

/** The theme's top wash, under every screen. */
@Composable
fun washBrush(height: Dp = 440.dp, themeStrength: Float = 0.8f): Brush =
    topWash(height, 1f, MaterialTheme.colorScheme.primaryContainer.copy(alpha = themeStrength))

/** [state]'s art gradient over whatever's under it, drawn per frame (recomposes nothing). */
fun Modifier.artBackground(state: WashState): Modifier = drawBehind {
    val art = state.art ?: return@drawBehind
    val a = state.shown()
    if (a <= 0f) return@drawBehind
    drawRect(artGradient(art.wash, art.scheme.background), alpha = a)
}
