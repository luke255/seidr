package eu.bradshaw.seidr.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * A Material-style tonal palette: one hue and chroma, any tone. Material
 * proper uses HCT; CIELAB LCh is close enough for picking accents and saves
 * pulling in material-color-utilities. Chroma is reduced where a tone can't
 * hold it, so every colour is in sRGB.
 */
class TonalPalette(private val hue: Double, private val chroma: Double) {
    operator fun invoke(tone: Int): Color = Color(lchToArgb(tone.toDouble(), chroma, hue))
}

/**
 * A vivid dark scheme in the spirit of Material's "Vibrant" variant: the
 * primary runs at high chroma (capped short of neon), at the tone where its
 * hue is most vivid (yellows and greens light, blues and reds deeper), instead of
 * Material's pastel tone 80. Backgrounds carry a visible tint of [neutralHue].
 * A lower [chroma] gives a muted accent, a lower [neutralChroma] greyer
 * backgrounds (both for album-art schemes).
 */
fun vividDarkScheme(
    hue: Double,
    secondaryHue: Double = hue,
    tertiaryHue: Double = hue + 60.0,
    neutralHue: Double = hue,
    chroma: Double = 90.0,
    neutralChroma: Double = 10.0,
): ColorScheme {
    val p = TonalPalette(hue, chroma)
    val t = TonalPalette(tertiaryHue, chroma)
    // Containers stay off the gamut edge, where CIELAB hues drift (violet goes blue).
    val pc = TonalPalette(hue, minOf(60.0, chroma))
    val tc = TonalPalette(tertiaryHue, minOf(60.0, chroma))
    val s = TonalPalette(secondaryHue, minOf(40.0, chroma))
    val n = TonalPalette(neutralHue, neutralChroma)
    val nv = TonalPalette(neutralHue, neutralChroma * 1.8)
    val pt = vividTone(hue)
    val tt = vividTone(tertiaryHue)
    return darkColorScheme(
        primary = p(pt), onPrimary = p(10), primaryContainer = pc(30), onPrimaryContainer = pc(90), inversePrimary = p(40),
        secondary = s(80), onSecondary = s(20), secondaryContainer = s(30), onSecondaryContainer = s(90),
        tertiary = t(tt), onTertiary = t(10), tertiaryContainer = tc(30), onTertiaryContainer = tc(90),
        background = n(6), onBackground = n(92), surface = n(6), onSurface = n(92),
        surfaceVariant = nv(30), onSurfaceVariant = nv(78), surfaceTint = p(pt),
        inverseSurface = n(90), inverseOnSurface = n(20),
        outline = nv(60), outlineVariant = nv(30),
        surfaceBright = n(24), surfaceDim = n(6),
        surfaceContainerLowest = n(4), surfaceContainerLow = n(10), surfaceContainer = n(12),
        surfaceContainerHigh = n(17), surfaceContainerHighest = n(22),
    )
}

private const val MAX_CHROMA = 150.0

/**
 * The tone in 65..85 where [hue] holds the most chroma. Text and icons in the
 * primary colour stay readable on tone-6 backgrounds anywhere in that range,
 * and tone-10 content on a primary button does too.
 */
internal fun vividTone(hue: Double): Int = (65..85).maxBy { maxChroma(it.toDouble(), hue) }

internal fun maxChroma(l: Double, hue: Double): Double {
    var lo = 0.0
    var hi = MAX_CHROMA
    repeat(16) {
        val mid = (lo + hi) / 2
        if (srgbOrNull(l, mid, hue) != null) lo = mid else hi = mid
    }
    return lo
}

/** LCh(ab) of an ARGB colour: (L*, chroma, hue in degrees). */
internal fun argbToLch(argb: Int): Triple<Double, Double, Double> {
    fun lin(v: Int): Double { val s = v / 255.0; return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
    val r = lin((argb shr 16) and 0xFF); val g = lin((argb shr 8) and 0xFF); val b = lin(argb and 0xFF)
    val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
    val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
    val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
    fun f(t: Double) = if (t > 216.0 / 24389.0) Math.cbrt(t) else (24389.0 / 27.0 * t + 16) / 116
    val l = 116 * f(y) - 16
    val a = 500 * (f(x) - f(y))
    val bb = 200 * (f(y) - f(z))
    val h = (Math.toDegrees(atan2(bb, a)) + 360) % 360
    return Triple(l, hypot(a, bb), h)
}

/** LCh(ab) to opaque ARGB, lowering chroma until the colour fits in sRGB. */
internal fun lchToArgb(l: Double, c: Double, hDeg: Double): Int {
    if (l <= 0.0) return 0xFF000000.toInt()
    if (l >= 100.0) return 0xFFFFFFFF.toInt()
    srgbOrNull(l, c, hDeg)?.let { return it }
    var lo = 0.0
    var hi = c
    repeat(20) {
        val mid = (lo + hi) / 2
        if (srgbOrNull(l, mid, hDeg) != null) lo = mid else hi = mid
    }
    return srgbOrNull(l, lo, hDeg) ?: srgbOrNull(l, 0.0, hDeg)!!
}

private fun srgbOrNull(l: Double, c: Double, hDeg: Double): Int? {
    val h = Math.toRadians(hDeg)
    val fy = (l + 16) / 116
    val fx = fy + c * cos(h) / 500
    val fz = fy - c * sin(h) / 200
    val x = 0.95047 * labInv(fx)
    val y = labInv(fy)
    val z = 1.08883 * labInv(fz)
    val r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
    val g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
    val b = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
    val eps = 1e-4
    if (listOf(r, g, b).any { it < -eps || it > 1 + eps }) return null
    return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}

private fun labInv(t: Double): Double {
    val t3 = t * t * t
    return if (t3 > 216.0 / 24389.0) t3 else (116 * t - 16) / (24389.0 / 27.0)
}

private fun channel(linear: Double): Int {
    val v = linear.coerceIn(0.0, 1.0)
    val s = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055
    return (s * 255 + 0.5).toInt().coerceIn(0, 255)
}
