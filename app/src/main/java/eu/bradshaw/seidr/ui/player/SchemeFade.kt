package eu.bradshaw.seidr.ui.player

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.floor

/**
 * Now Playing's colours moving from one track's to the next, cheaply.
 *
 * A new colour scheme recomposes everything under the theme (Material's
 * scheme is a static composition local), so handing the theme a fresh one
 * every frame of a two-second fade recomposed the whole screen 60 times a
 * second while the cover slid: on a TV, that held the slide to 10–15 fps. So
 * the theme ([scheme]) moves in [STEPS] steps, and the big areas that would
 * show steps, the wash and the background, are drawn every frame from
 * [wash] and [background], which are read while drawing and recompose
 * nothing.
 */
@Stable
class SchemeFade(scheme: ColorScheme, wash: Color) {
    private var from by mutableStateOf(scheme)
    private var to by mutableStateOf(scheme)
    private var fromWash by mutableStateOf(wash)
    private var toWash by mutableStateOf(wash)
    private var progress by mutableFloatStateOf(1f)
    private val step by derivedStateOf { stepOf(progress) }
    private var cached: Triple<ColorScheme, ColorScheme, Float>? = null
    private var cachedScheme = scheme

    /** For the theme: changes only [STEPS] times a fade. */
    val scheme: ColorScheme
        get() {
            val key = Triple(from, to, step)
            if (key != cached) { cached = key; cachedScheme = lerpScheme(from, to, key.third) }
            return cachedScheme
        }

    /** The wash now. Read it while drawing. */
    fun wash(): Color = lerp(fromWash, toWash, progress)

    /** The background now. Read it while drawing. */
    fun background(): Color = lerp(from.background, to.background, progress)

    /** Fades from wherever it is now to [target], over [spec]. */
    suspend fun fadeTo(target: ColorScheme, wash: Color, spec: AnimationSpec<Float>) {
        if (target == to && wash == toWash) return
        from = lerpScheme(from, to, progress)
        fromWash = wash()
        to = target
        toWash = wash
        progress = 0f
        animate(0f, 1f, animationSpec = spec) { v, _ -> progress = v }
    }

    companion object {
        const val STEPS = 12

        internal fun stepOf(p: Float): Float = if (p >= 1f) 1f else floor(p * STEPS) / STEPS
    }
}

/** A fade starting at [scheme]/[wash] and following them, over [spec] each time they change. */
@Composable
fun rememberSchemeFade(scheme: ColorScheme, wash: Color, spec: AnimationSpec<Float>): SchemeFade {
    val fade = remember { SchemeFade(scheme, wash) }
    LaunchedEffect(scheme, wash) { fade.fadeTo(scheme, wash, spec) }
    return fade
}

/** [to] with the roles Now Playing shows taken [t] of the way from [from]. */
internal fun lerpScheme(from: ColorScheme, to: ColorScheme, t: Float): ColorScheme {
    if (t >= 1f || from === to) return to
    fun Color.toward(other: Color) = lerp(this, other, t)
    return to.copy(
        primary = from.primary.toward(to.primary),
        onPrimary = from.onPrimary.toward(to.onPrimary),
        primaryContainer = from.primaryContainer.toward(to.primaryContainer),
        onPrimaryContainer = from.onPrimaryContainer.toward(to.onPrimaryContainer),
        secondaryContainer = from.secondaryContainer.toward(to.secondaryContainer),
        onSecondaryContainer = from.onSecondaryContainer.toward(to.onSecondaryContainer),
        background = from.background.toward(to.background),
        onBackground = from.onBackground.toward(to.onBackground),
        surface = from.surface.toward(to.surface),
        onSurface = from.onSurface.toward(to.onSurface),
        surfaceVariant = from.surfaceVariant.toward(to.surfaceVariant),
        onSurfaceVariant = from.onSurfaceVariant.toward(to.onSurfaceVariant),
        surfaceContainer = from.surfaceContainer.toward(to.surfaceContainer),
        surfaceContainerHigh = from.surfaceContainerHigh.toward(to.surfaceContainerHigh),
        surfaceContainerHighest = from.surfaceContainerHighest.toward(to.surfaceContainerHighest),
        outline = from.outline.toward(to.outline),
        outlineVariant = from.outlineVariant.toward(to.outlineVariant),
    )
}
