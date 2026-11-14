package eu.bradshaw.seidr.ui

import eu.bradshaw.seidr.ui.player.LyricsPath
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsPathTest {
    // An intro, quick lines, a long instrumental, a two-line wrap, lines starting together, then the end.
    private val starts = listOf(10_000L, 11_500L, 13_000L, 14_500L, 40_000L, 42_000L, 42_000L, 43_000L, 46_000L)
    private val heights = floatArrayOf(110f, 110f, 110f, 110f, 190f, 110f, 110f, 110f, 110f)
    private val tops = FloatArray(starts.size).also { t -> for (i in 1 until t.size) t[i] = t[i - 1] + heights[i - 1] }
    private val band = 120f
    private val path = LyricsPath(starts, tops, sigmaMs = 2500.0, band = band)

    private fun each(step: Double = 5.0, block: (Double) -> Unit) {
        var t = 0.0
        while (t <= 60_000) { block(t); t += step }
    }

    @Test fun startsAtTheFirstLineAndEndsAtTheLast() {
        assertEquals(tops.first().toDouble(), path.at(0.0), 0.01)
        assertEquals(tops.last().toDouble(), path.at(60_000.0), 0.01)
    }

    @Test fun keepsEachLineNearTheMarkAsItStarts() {
        for (i in starts.indices) {
            val off = path.at(starts[i].toDouble()) - tops[i]
            assertTrue("line $i is $off px off", abs(off) < band)
        }
    }

    @Test fun onlyMovesForward() {
        var last = path.at(0.0)
        each { t ->
            val y = path.at(t)
            assertTrue("went back at $t", y >= last - 1e-6)
            last = y
        }
    }

    @Test fun speedChangesGently() {
        // px/ms between 5ms samples; the strict path would jump by up to ~0.07 at a line change.
        var lastSpeed = 0.0
        var worst = 0.0
        each { t ->
            val speed = (path.at(t + 5) - path.at(t)) / 5
            worst = maxOf(worst, abs(speed - lastSpeed))
            lastSpeed = speed
        }
        assertTrue("speed jumped by $worst px/ms", worst < 0.002)
    }

    @Test fun singleLineStandsStill() {
        val one = LyricsPath(listOf(5_000L), floatArrayOf(0f), 2500.0, band)
        assertEquals(0.0, one.at(0.0), 0.0)
        assertEquals(0.0, one.at(9_000.0), 0.0)
    }
}
