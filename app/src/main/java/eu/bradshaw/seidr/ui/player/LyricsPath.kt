package eu.bradshaw.seidr.ui.player

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Where synced lyrics should be scrolled to at each moment: the content y (px)
 * that sits at the mark.
 *
 * The strict path puts each line at the mark exactly as it starts, moving at a
 * steady speed in between. Its speed changes at every line, sharply when a
 * quick line meets a slow one. This path is that one blurred over time with a
 * Gaussian ([sigmaMs]), so speed changes spread over a few seconds, then held
 * within [band] px of a lightly blurred copy ([TIGHT_MS]) by a soft clamp; the
 * clamp works against a blurred copy rather than the strict path so that it never
 * passes on a sharp change of speed. A line can arrive a little early or late and
 * sit a little above or below the mark, but the scrolling no longer speeds up and
 * slows down at every line. It only moves forward.
 *
 * The strict path is linear between lines, so it is a sum of ramps, one per
 * change of speed. A ramp blurred by a Gaussian has a closed form, so the
 * blurred path is exact and cheap enough to compute every frame.
 *
 * [tops] are the lines' tops in content px, the same order as [startsMs].
 */
class LyricsPath(startsMs: List<Long>, tops: FloatArray, private val sigmaMs: Double, private val band: Float) {
    private val origin = tops.firstOrNull()?.toDouble() ?: 0.0
    private val tops = tops.copyOf()
    private val knots: DoubleArray
    private val turns: DoubleArray // change of speed at each knot, px per ms

    init {
        require(startsMs.size == tops.size)
        // Lines that start together (or out of order) get a moment each, so speeds stay finite.
        val t = DoubleArray(startsMs.size)
        for (i in t.indices) t[i] = if (i == 0) startsMs[0].toDouble() else max(startsMs[i].toDouble(), t[i - 1] + MIN_LINE_MS)
        val speeds = DoubleArray(max(t.size - 1, 0)) { (tops[it + 1] - tops[it]) / (t[it + 1] - t[it]) }
        knots = t
        turns = DoubleArray(t.size) { i -> speeds.getOrElse(i) { 0.0 } - speeds.getOrElse(i - 1) { 0.0 } }
    }

    /** The top of line [i], in content px. */
    fun top(i: Int): Float = tops[i]

    /** The content y to put at the mark at [posMs]. */
    fun at(posMs: Double): Double {
        var tight = origin
        var smooth = origin
        for (k in knots.indices) {
            val x = posMs - knots[k]
            tight += turns[k] * blurredRamp(x, TIGHT_MS)
            smooth += turns[k] * blurredRamp(x, sigmaMs)
        }
        return tight + softClamp(smooth - tight, band.toDouble())
    }

    private companion object {
        const val MIN_LINE_MS = 50.0
        const val TIGHT_MS = 500.0
        val INV_SQRT_2PI = 1 / sqrt(2 * Math.PI)

        /** max(x, 0) convolved with a Gaussian of deviation [s]: x·Φ(x/s) + s·φ(x/s). */
        fun blurredRamp(x: Double, s: Double): Double {
            val z = x / s
            if (z > 8) return x
            if (z < -8) return 0.0
            return x * phi(z) + s * INV_SQRT_2PI * exp(-z * z / 2)
        }

        /** Standard normal CDF, via Abramowitz & Stegun 7.1.26 (error < 2e-7). */
        fun phi(z: Double): Double {
            val x = abs(z) / sqrt(2.0)
            val t = 1 / (1 + 0.3275911 * x)
            val erf = 1 - t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429)))) * exp(-x * x)
            return if (z >= 0) (1 + erf) / 2 else (1 - erf) / 2
        }

        /**
         * Keeps [d] within ±[b]: nearly the identity until about b/2, then easing
         * off towards b. Its slope stays between 0 and 1, so the result still
         * only moves forward.
         */
        fun softClamp(d: Double, b: Double): Double = d / (1 + (abs(d) / b).pow(4)).pow(0.25)
    }
}
