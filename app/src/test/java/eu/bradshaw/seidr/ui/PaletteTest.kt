package eu.bradshaw.seidr.ui

import eu.bradshaw.seidr.ui.theme.ThemeChoice
import eu.bradshaw.seidr.ui.theme.argbToLch
import eu.bradshaw.seidr.ui.theme.lchToArgb
import eu.bradshaw.seidr.ui.theme.vividDarkScheme
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {
    @Test fun extremesAreBlackAndWhite() {
        assertEquals(0xFF000000.toInt(), lchToArgb(0.0, 50.0, 30.0))
        assertEquals(0xFFFFFFFF.toInt(), lchToArgb(100.0, 50.0, 30.0))
    }

    @Test fun lighterTonesAreBrighter() {
        for (choice in ThemeChoice.entries.mapNotNull { it.hue }) {
            val lum = (0..100 step 10).map { luminance(lchToArgb(it.toDouble(), 50.0, choice)) }
            assertTrue("hue $choice: $lum", lum.zipWithNext().all { (a, b) -> b > a })
        }
    }

    @Test fun lchRoundTrips() {
        for (argb in listOf(0xFFADA1F1, 0xFF94B8FF, 0xFF1C1A25, 0xFFFFB4AB).map { it.toInt() }) {
            val (l, c, h) = argbToLch(argb)
            assertEquals(argb, lchToArgb(l, c, h))
        }
    }

    @Test fun vividSchemesStayReadable() {
        for (choice in ThemeChoice.entries.filter { it.hue != null }) {
            val cs = vividDarkScheme(choice.hue!!)
            fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Double {
                val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
                return (hi + 0.05) / (lo + 0.05)
            }
            assertTrue("${choice.label} primary on background", contrast(cs.primary, cs.background) >= 4.5)
            assertTrue("${choice.label} onPrimary on primary", contrast(cs.onPrimary, cs.primary) >= 4.5)
            assertTrue("${choice.label} subtitles", contrast(cs.onSurfaceVariant, cs.background) >= 4.5)
            println("${choice.label}: primary #%06X container #%06X bg #%06X".format(
                argb(cs.primary), argb(cs.primaryContainer), argb(cs.background)))
        }
    }

    private fun argb(c: androidx.compose.ui.graphics.Color) =
        ((c.red * 255 + 0.5).toInt() shl 16) or ((c.green * 255 + 0.5).toInt() shl 8) or (c.blue * 255 + 0.5).toInt()

    private fun luminance(argb: Int): Int = ((argb shr 16) and 0xFF) * 2 + ((argb shr 8) and 0xFF) * 7 + (argb and 0xFF)
}
