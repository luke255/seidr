package eu.bradshaw.seidr.ui.theme

import eu.bradshaw.seidr.R
import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.material.ripple.RippleAlpha
import eu.bradshaw.seidr.ui.EdgeBringIntoView
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The user's colour choice. [hue] is an LCh hue for the seeded themes; System
 * follows the phone's Material You palette and Neutral is the original
 * near-black look.
 */
enum class ThemeChoice(@androidx.annotation.StringRes val labelRes: Int, val hue: Double? = null) {
    SYSTEM(R.string.theme_system),
    NEUTRAL(R.string.theme_neutral),
    RED(R.string.theme_red, 30.0),
    ORANGE(R.string.theme_orange, 55.0),
    AMBER(R.string.theme_amber, 75.0),
    YELLOW(R.string.theme_yellow, 98.0),
    LIME(R.string.theme_lime, 120.0),
    GREEN(R.string.theme_green, 140.0),
    EMERALD(R.string.theme_emerald, 160.0),
    TEAL(R.string.theme_teal, 185.0),
    CYAN(R.string.theme_cyan, 210.0),
    SKY(R.string.theme_sky, 240.0),
    BLUE(R.string.theme_blue, 268.0),
    INDIGO(R.string.theme_indigo, 288.0),
    VIOLET(R.string.theme_violet, 305.0),
    PURPLE(R.string.theme_purple, 320.0),
    MAGENTA(R.string.theme_magenta, 338.0),
    PINK(R.string.theme_pink, 0.0);

    val key: String get() = name.lowercase()

    companion object {
        fun fromKey(key: String?): ThemeChoice = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

private val FallbackHue = ThemeChoice.VIOLET.hue!!

/**
 * The phone's Material You hues, made vivid. Hues come from the tonal
 * palettes (`system_accent1_*` etc.), not the Android 14 role colours
 * (`system_primary_dark`) that `dynamicDarkColorScheme` reads: Samsung themes
 * can leave the roles stale (blue on grey under a lavender theme). Tertiary
 * is the usual +60° from primary, since Samsung themes can make accent3 an
 * unrelated olive. A near-grey (monochrome) system theme stays as it is.
 */
private fun systemScheme(context: Context): ColorScheme = runCatching {
    fun lch(name: String): Triple<Double, Double, Double> {
        val id = context.resources.getIdentifier(name, "color", "android")
        require(id != 0)
        return argbToLch(context.getColor(id))
    }
    val (_, chroma, hue) = lch("system_accent1_500")
    if (chroma < 8) return@runCatching dynamicDarkColorScheme(context)
    vividDarkScheme(hue, secondaryHue = lch("system_accent2_500").third, neutralHue = lch("system_neutral1_500").third)
}.getOrElse { vividDarkScheme(FallbackHue) }

/**
 * [key] as another device can show it: System becomes this phone's hue
 * ("hue:268.0"), since the other device's wallpaper isn't ours.
 */
fun portableTheme(key: String, context: Context): String {
    if (ThemeChoice.fromKey(key) != ThemeChoice.SYSTEM) return key
    return runCatching {
        val id = context.resources.getIdentifier("system_accent1_500", "color", "android")
        require(id != 0)
        val (_, chroma, hue) = argbToLch(context.getColor(id))
        if (chroma < 8) ThemeChoice.NEUTRAL.key else "hue:%.1f".format(java.util.Locale.ROOT, hue)
    }.getOrDefault(key)
}

/** A theme key from [portableTheme]: a choice, or "hue:<degrees>". */
fun colorSchemeForKey(key: String, context: Context): ColorScheme =
    key.removePrefix("hue:").takeIf { key.startsWith("hue:") }?.toDoubleOrNull()?.let { vividDarkScheme(it) }
        ?: colorSchemeFor(ThemeChoice.fromKey(key), context)

fun colorSchemeFor(choice: ThemeChoice, context: Context): ColorScheme = when (choice) {
    ThemeChoice.SYSTEM -> systemScheme(context)
    // The original look: near-black backgrounds (classic Spotify), quiet accents from the system.
    ThemeChoice.NEUTRAL -> runCatching { dynamicDarkColorScheme(context) }.getOrElse {
        darkColorScheme(primary = Color(0xFFFFB4A2), secondary = Color(0xFFE7BDB3))
    }.copy(background = Color(0xFF121212), surface = Color(0xFF121212))
    else -> vividDarkScheme(choice.hue!!)
}

/** False for Neutral: screens skip their colour washes and keep the plain near-black look. */
val LocalColourful = staticCompositionLocalOf { true }

/**
 * Material's focus state layer (10%) is too faint to follow from across the
 * room with a remote. Only keyboard/D-pad focus shows it; touch never does.
 */
private val FocusRipple = RippleConfiguration(
    rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.3f, hoveredAlpha = 0.08f, pressedAlpha = 0.1f),
)

/** Dark always (classic-era Spotify was dark-only); colours follow [choice]. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SeidrTheme(key: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val choice = ThemeChoice.fromKey(key)
    val scheme = remember(key, context) { colorSchemeForKey(key, context) }
    val margin = with(LocalDensity.current) { 24.dp.toPx() }
    val bringIntoView = remember(margin) { EdgeBringIntoView(margin) }
    CompositionLocalProvider(
        LocalColourful provides (choice != ThemeChoice.NEUTRAL),
        LocalRippleConfiguration provides FocusRipple,
        LocalBringIntoViewSpec provides bringIntoView,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * A soft wash of the primary container colour fading down from the top, for
 * screen backgrounds. Transparent under Neutral.
 */
@Composable
fun topWash(height: Dp = 440.dp, strength: Float = 0.8f, color: Color? = null): Brush {
    val c = color ?: MaterialTheme.colorScheme.primaryContainer
    val end = with(LocalDensity.current) { height.toPx() }
    return if (LocalColourful.current) {
        Brush.verticalGradient(listOf(c.copy(alpha = c.alpha * strength), c.copy(alpha = c.alpha * strength * 0.3f), Color.Transparent), endY = end)
    } else SolidColor(Color.Transparent)
}
