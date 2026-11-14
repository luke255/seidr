package eu.bradshaw.seidr.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The colours picked from a piece of album art. [wash] is the art's dominant
 * colour, for the background; [accent] is the best accent (Material's "Score":
 * mostly how much of the art a hue covers, a little for how vivid it is).
 * Both come from the art, and usually share a hue.
 *
 * [splash] art is neutral apart from one deliberate vivid colour (Riot!'s
 * orange title, the umbrella on Hazy Eyes): the accent is that colour and the
 * wash is the art's near-grey.
 */
data class ArtColours(
    val accentHue: Double,
    val accentChroma: Double,
    val washHue: Double,
    val washChroma: Double,
    /** Tone of the background wash: darker for dark art, kept in 20..32 so text stays readable. */
    val washTone: Double,
    val splash: Boolean = false,
)

/** A colour scheme built from album art plus the background wash colour. */
data class ArtScheme(val scheme: ColorScheme, val wash: Color)

private const val BUCKETS = 36
private const val BUCKET_DEG = 360.0 / BUCKETS
/** LCh chroma below this counts as grey (black, white, greys, near-greys). */
private const val GREY_CHROMA = 10.0
/** Art with less colourful area than this is treated as monochrome. */
private const val MIN_COLOURFUL = 0.05
/** A hue must cover at least this much of the art to be the accent. */
private const val MIN_ACCENT_SHARE = 0.01

// Splash detection. Tuned on real covers: Riot! (2.8% orange on black and
// white), Hazy Eyes (23% red over muted skin and hair) and Blood Sugar Sex
// Magik / Deftones (red roses) are splashes; Fallen (all muted blue) and
// Pablo Honey (half yellow) aren't.
/** Chroma of the splash colour itself. */
private const val VIVID_CHROMA = 40.0
/** Chroma below this is the neutral part of a splash cover. */
private const val MUTED_CHROMA = 20.0
private const val MIN_SPLASH_SHARE = 0.01
/** Share of the vivid pixels that must be the one splash hue. */
private const val SPLASH_PURITY = 0.75
private const val MIN_MUTED_SHARE = 0.6
/** Most other non-muted colour a splash cover may have. */
private const val MAX_OTHER_SHARE = 0.05
/** Degrees either side of the splash hue that still count as the splash. */
private const val SPLASH_SPREAD = 25.0

// Mono splash: a thin line of colour on black-and-white art, like Adele 21's
// "21" (0.09%) or the "Move Along" script. Only for art that's almost all
// grey, where any pure colour is deliberate; found 10 in 651 covers, no
// false hits. Runs on the 224px sample: at 112px the "21" blurs to grey.
private const val MONO_GREY_SHARE = 0.97
private const val MONO_SPLASH_CHROMA = 25.0
private const val MIN_MONO_SPLASH = 0.0005
private const val MONO_PURITY = 0.9

// Accent splash: a small vivid colour on art that's otherwise one colour, like
// the red "incubus" on Morning View's blue. The background keeps the art's
// colour; only the accent changes. Tuned on 651 covers (36 match, e.g.
// Rihanna, Foo Fighters, Moby's red titles, the Enter Shikari lion).
private const val ACCENT_SPLASH_CHROMA = 35.0
/** At least 0.2% of the art (Morning View's title is 0.3%) and at most 15%. */
private const val MIN_ACCENT_SPLASH = 0.002
private const val MAX_ACCENT_SPLASH = 0.15
/** How far from the background hue the splash must be. */
private const val ACCENT_SPLASH_FAR = 60.0
/** How much more vivid the splash must be than the background colour. */
private const val ACCENT_SPLASH_RATIO = 1.8
/** Most other colour (neither background nor splash) the art may have. */
private const val MAX_ACCENT_OTHER = 0.03

/**
 * Picks [ArtColours] from ARGB pixels of the cover at 112px, or null for
 * monochrome art, which goes grey. [finePixels] (the cover at 224px) is only
 * read for monochrome art, to find a thin line of colour that blurs away at
 * 112px; every other rule was tuned at 112px.
 */
fun extractArtColours(pixels: IntArray, finePixels: IntArray? = null): ArtColours? {
    val art = Lch.of(pixels) ?: return null
    val washTone = (art.ls.sum() / art.n * 0.45).coerceIn(20.0, 32.0)
    return splash(art.cs, art.hs, art.n, washTone)
        ?: dominant(art.cs, art.hs, art.n, washTone)
        ?: (finePixels?.let(Lch::of) ?: art).let { monoSplash(it.cs, it.hs, it.n, washTone) }
}

/** LCh of the opaque pixels; the first [n] entries are used. */
private class Lch(val ls: DoubleArray, val cs: DoubleArray, val hs: DoubleArray, val n: Int) {
    companion object {
        fun of(pixels: IntArray): Lch? {
            val ls = DoubleArray(pixels.size)
            val cs = DoubleArray(pixels.size)
            val hs = DoubleArray(pixels.size)
            var n = 0
            for (px in pixels) {
                if ((px ushr 24) < 128) continue
                val (l, c, h) = argbToLch(px)
                ls[n] = l; cs[n] = c; hs[n] = h
                n++
            }
            return if (n == 0) null else Lch(ls, cs, hs, n)
        }
    }
}

private fun bucket(h: Double) = (h / BUCKET_DEG).toInt().coerceIn(0, BUCKETS - 1)
private fun IntArray.excited(b: Int) = this[(b + BUCKETS - 1) % BUCKETS] + this[b] + this[(b + 1) % BUCKETS]
private fun hueDistance(a: Double, b: Double) = abs(((a - b) % 360 + 540) % 360 - 180)

/** Accumulates chroma-weighted LCh hue vectors, for averaging hues around the circle. */
private class HueMean {
    var a = 0.0; var b = 0.0; var c = 0.0; var count = 0
    fun add(chroma: Double, hue: Double) {
        val rad = Math.toRadians(hue)
        a += chroma * cos(rad); b += chroma * sin(rad); c += chroma; count++
    }
    val hue get() = (Math.toDegrees(atan2(b, a)) + 360) % 360
    /** Mean of the chromas. */
    val chroma get() = c / count
    /** Length of the mean vector: lower than [chroma] when the hues disagree. */
    val netChroma get() = if (count == 0) 0.0 else hypot(a, b) / count
}

/**
 * Neutral art with one vivid colour, or null. The colour must be most of the
 * art's vivid pixels, the art must be mostly muted, and there must be little
 * other colour.
 */
private fun splash(cs: DoubleArray, hs: DoubleArray, n: Int, washTone: Double): ArtColours? {
    val vivid = IntArray(BUCKETS)
    var vividTotal = 0
    for (i in 0 until n) if (cs[i] >= VIVID_CHROMA) { vivid[bucket(hs[i])]++; vividTotal++ }
    if (vividTotal < n * MIN_SPLASH_SHARE) return null
    val top = (0 until BUCKETS).maxBy { vivid.excited(it) }
    if (vivid.excited(top) < vividTotal * SPLASH_PURITY) return null

    val centre = (top + 0.5) * BUCKET_DEG
    val accent = HueMean()
    for (i in 0 until n) if (cs[i] >= VIVID_CHROMA && hueDistance(hs[i], centre) <= BUCKET_DEG * 1.5) accent.add(cs[i], hs[i])
    val splashHue = accent.hue

    val neutral = HueMean()
    var other = 0
    for (i in 0 until n) when {
        cs[i] < MUTED_CHROMA -> neutral.add(cs[i], hs[i])
        hueDistance(hs[i], splashHue) > SPLASH_SPREAD -> other++
    }
    if (neutral.count < n * MIN_MUTED_SHARE || other > n * MAX_OTHER_SHARE) return null
    return ArtColours(
        accentHue = splashHue,
        accentChroma = accent.chroma,
        washHue = neutral.hue,
        washChroma = neutral.netChroma,
        washTone = washTone,
        splash = true,
    )
}

/** Black-and-white art with a fine line of one colour, or null. */
private fun monoSplash(cs: DoubleArray, hs: DoubleArray, n: Int, washTone: Double): ArtColours? {
    val vivid = IntArray(BUCKETS)
    var vividTotal = 0
    var grey = 0
    for (i in 0 until n) {
        if (cs[i] < GREY_CHROMA) grey++
        if (cs[i] >= MONO_SPLASH_CHROMA) { vivid[bucket(hs[i])]++; vividTotal++ }
    }
    if (grey < n * MONO_GREY_SHARE || vividTotal < n * MIN_MONO_SPLASH) return null
    val top = (0 until BUCKETS).maxBy { vivid.excited(it) }
    if (vivid.excited(top) < vividTotal * MONO_PURITY) return null

    val centre = (top + 0.5) * BUCKET_DEG
    val accent = HueMean()
    val neutral = HueMean()
    for (i in 0 until n) {
        if (cs[i] >= MONO_SPLASH_CHROMA && hueDistance(hs[i], centre) <= BUCKET_DEG * 1.5) accent.add(cs[i], hs[i])
        else if (cs[i] < MUTED_CHROMA) neutral.add(cs[i], hs[i])
    }
    return ArtColours(
        accentHue = accent.hue,
        accentChroma = accent.chroma,
        washHue = neutral.hue,
        washChroma = neutral.netChroma,
        washTone = washTone,
        splash = true,
    )
}

/**
 * The general case. Hues are bucketed in 10° steps; each bucket's share
 * counts its ±10° neighbours, so a hue spread over a gradient isn't split.
 */
private fun dominant(cs: DoubleArray, hs: DoubleArray, n: Int, washTone: Double): ArtColours? {
    val pop = IntArray(BUCKETS)
    val means = Array(BUCKETS) { HueMean() }
    var colourful = 0
    for (i in 0 until n) {
        if (cs[i] < GREY_CHROMA) continue
        colourful++
        val b = bucket(hs[i])
        pop[b]++
        means[b].add(cs[i], hs[i])
    }
    if (colourful < n * MIN_COLOURFUL) return null

    fun share(b: Int) = pop.excited(b).toDouble() / n
    val occupied = (0 until BUCKETS).filter { pop[it] > 0 }
    val wash = occupied.maxBy { share(it) }
    val accent = occupied.filter { pop[it].toDouble() / n >= MIN_ACCENT_SHARE }
        .maxByOrNull { b ->
            val c = means[b].chroma
            share(b) * 70 + (c - 48) * (if (c < 48) 0.1 else 0.3)
        } ?: wash
    val splash = accentSplash(cs, hs, n, means[wash].hue, means[wash].chroma)
    return ArtColours(
        accentHue = splash?.hue ?: means[accent].hue,
        accentChroma = splash?.chroma ?: means[accent].chroma,
        washHue = means[wash].hue,
        washChroma = means[wash].chroma,
        washTone = washTone,
    )
}

/**
 * A small, single vivid colour well away from [washHue] on art that's
 * otherwise consistently coloured, or null.
 */
private fun accentSplash(cs: DoubleArray, hs: DoubleArray, n: Int, washHue: Double, washChroma: Double): HueMean? {
    val far = IntArray(BUCKETS)
    var farTotal = 0
    for (i in 0 until n) {
        if (cs[i] >= ACCENT_SPLASH_CHROMA && hueDistance(hs[i], washHue) > ACCENT_SPLASH_FAR) { far[bucket(hs[i])]++; farTotal++ }
    }
    if (farTotal < n * MIN_ACCENT_SPLASH || farTotal > n * MAX_ACCENT_SPLASH) return null
    val top = (0 until BUCKETS).maxBy { far.excited(it) }
    if (far.excited(top) < farTotal * SPLASH_PURITY) return null

    val centre = (top + 0.5) * BUCKET_DEG
    val accent = HueMean()
    for (i in 0 until n) {
        if (cs[i] >= ACCENT_SPLASH_CHROMA && hueDistance(hs[i], washHue) > ACCENT_SPLASH_FAR &&
            hueDistance(hs[i], centre) <= BUCKET_DEG * 1.5) accent.add(cs[i], hs[i])
    }
    if (accent.chroma < washChroma * ACCENT_SPLASH_RATIO) return null
    var other = 0
    for (i in 0 until n) {
        if (cs[i] >= 15.0 && hueDistance(hs[i], washHue) > 40.0 && hueDistance(hs[i], accent.hue) > SPLASH_SPREAD) other++
    }
    return accent.takeIf { other <= n * MAX_ACCENT_OTHER }
}

/**
 * The Now Playing scheme for [art]: accent from the art's accent hue (muted
 * art gives a muted accent, never neon), backgrounds and text tinted with the
 * dominant hue. A splash keeps backgrounds near-grey so the colour is only in
 * the controls. Tones come from [vividDarkScheme], so contrast holds whatever
 * the art.
 */
fun artScheme(art: ArtColours): ArtScheme {
    val chroma = (art.accentChroma * 1.3).coerceIn(32.0, 90.0)
    if (art.splash) {
        val scheme = vividDarkScheme(
            hue = art.accentHue,
            secondaryHue = art.accentHue,
            neutralHue = art.washHue,
            chroma = chroma,
            // As grey as the art: true grey for black and white, a hint of tint for a cool photo.
            neutralChroma = art.washChroma.coerceAtMost(4.0),
        )
        val wash = Color(lchToArgb(art.washTone, art.washChroma.coerceAtMost(6.0), art.washHue))
        return ArtScheme(scheme, wash)
    }
    val scheme = vividDarkScheme(
        hue = art.accentHue,
        secondaryHue = art.washHue,
        neutralHue = art.washHue,
        chroma = chroma,
    )
    val wash = Color(lchToArgb(art.washTone, art.washChroma.coerceIn(8.0, 50.0), art.washHue))
    return ArtScheme(scheme, wash)
}

/**
 * For monochrome art, or none: greys only, with a white accent (classic
 * Spotify's play button), so black-and-white covers don't jump to the app's
 * theme colour.
 */
val monochromeArtScheme: ArtScheme = run {
    fun grey(tone: Double) = Color(lchToArgb(tone, 0.0, 0.0))
    val scheme = vividDarkScheme(0.0, chroma = 0.0, neutralChroma = 0.0)
        .copy(primary = grey(95.0), onPrimary = grey(10.0), surfaceTint = grey(95.0))
    ArtScheme(scheme, grey(26.0))
}
