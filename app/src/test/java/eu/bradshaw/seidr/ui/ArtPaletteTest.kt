package eu.bradshaw.seidr.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import eu.bradshaw.seidr.ui.theme.ArtColours
import eu.bradshaw.seidr.ui.theme.argbToLch
import eu.bradshaw.seidr.ui.theme.artScheme
import eu.bradshaw.seidr.ui.theme.extractArtColours
import eu.bradshaw.seidr.ui.theme.monochromeArtScheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ArtPaletteTest {
    private fun art(vararg parts: Pair<Int, Long>): IntArray =
        parts.flatMap { (n, argb) -> List(n) { argb.toInt() } }.toIntArray()

    private fun hueOf(argb: Long) = argbToLch(argb.toInt()).third
    private fun hueDistance(a: Double, b: Double) = abs(((a - b) % 360 + 540) % 360 - 180)

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test fun monochromeArtKeepsAppTheme() {
        assertNull(extractArtColours(art(500 to 0xFF000000, 400 to 0xFFFFFFFF, 100 to 0xFF808080)))
        // Specks of different colours aren't a deliberate splash.
        assertNull(extractArtColours(art(990 to 0xFF202020, 5 to 0xFFFF0000, 5 to 0xFF2060FF)))
        assertNull(extractArtColours(IntArray(0)))
    }

    @Test fun dominantHueWins() {
        val blue = 0xFF1E4FA0
        val c = extractArtColours(art(700 to blue, 280 to 0xFF101010, 20 to 0xFFFFD000))!!
        assertTrue(hueDistance(c.washHue, hueOf(blue)) < 3)
        assertTrue(hueDistance(c.accentHue, hueOf(blue)) < 3)
    }

    @Test fun vividColourBeatsSlightlyLargerMutedOne() {
        // Background follows the larger muted area; the accent takes the vivid one.
        val brown = 0xFF5A4636
        val red = 0xFFE8202A
        val c = extractArtColours(art(520 to brown, 480 to red))!!
        assertTrue(hueDistance(c.washHue, hueOf(brown)) < 3)
        assertTrue(hueDistance(c.accentHue, hueOf(red)) < 3)
    }

    @Test fun oneVividColourOnNeutralArtIsASplash() {
        // Riot!: black and white, with an orange title.
        val orange = 0xFFF06A1E
        val c = extractArtColours(art(600 to 0xFFF4F4F2, 370 to 0xFF141414, 30 to orange))!!
        assertTrue(c.splash)
        assertTrue(hueDistance(c.accentHue, hueOf(orange)) < 3)
        assertTrue(c.washChroma < 3)
        // Hazy Eyes: a big red umbrella over muted skin and dark hair.
        val red = 0xFFD8202A
        val hazy = extractArtColours(art(230 to red, 300 to 0xFF7A6A64, 470 to 0xFF2A2C34))!!
        assertTrue(hazy.splash)
        assertTrue(hueDistance(hazy.accentHue, hueOf(red)) < 3)
    }

    @Test fun fineColourOnBlackAndWhiteIsASplash() {
        // Adele 21: a thin olive "21" on a black-and-white photo, 0.1% of the art.
        val olive = 0xFF9AA040
        val c = extractArtColours(art(6000 to 0xFF101010, 3990 to 0xFFE0E0E0, 10 to olive))!!
        assertTrue(c.splash)
        assertTrue(hueDistance(c.accentHue, hueOf(olive)) < 3)
        // Two fine colours aren't one deliberate one.
        assertNull(extractArtColours(art(6000 to 0xFF101010, 3980 to 0xFFE0E0E0, 10 to olive, 10 to 0xFF3060FF)))
        // Grey art with a little colour all over isn't black and white.
        assertNull(extractArtColours(art(9590 to 0xFF101010, 400 to 0xFF4A3A2A, 10 to olive)))
        // The fine sample is what finds it: blurred to grey at 112px, still olive at 224px.
        val blurred = art(6000 to 0xFF101010, 4000 to 0xFFE0E0E0)
        assertNull(extractArtColours(blurred))
        assertTrue(extractArtColours(blurred, art(6000 to 0xFF101010, 3990 to 0xFFE0E0E0, 10 to olive))!!.splash)
    }

    @Test fun notSplashes() {
        // Two vivid colours.
        assertFalse(extractArtColours(art(900 to 0xFF808080, 50 to 0xFFE8202A, 50 to 0xFF2050F0))!!.splash)
        // Mostly vivid.
        assertFalse(extractArtColours(art(500 to 0xFFF0C020, 500 to 0xFFF4F4F2))!!.splash)
        // Muted colour all over (Fallen): the colour is the theme, not a splash.
        assertFalse(extractArtColours(art(700 to 0xFF2A3A5A, 300 to 0xFF101010, 3 to 0xFF3060FF))!!.splash)
        // Neutral with lots of a second, softer colour.
        assertFalse(extractArtColours(art(800 to 0xFF808080, 50 to 0xFFE8202A, 150 to 0xFF4A6A9A))!!.splash)
    }

    @Test fun smallVividColourOnOneColourArtBecomesTheAccent() {
        // Morning View: hazy blue sea and sky, a tiny red-orange "incubus".
        val blue = 0xFF6F7FA0
        val red = 0xFFD0502A
        val c = extractArtColours(art(700 to blue, 296 to 0xFF202428, 4 to red))!!
        assertFalse(c.splash)
        assertTrue(hueDistance(c.washHue, hueOf(blue)) < 3)
        assertTrue(hueDistance(c.accentHue, hueOf(red)) < 3)
        // Two different small colours aren't one deliberate splash.
        val two = extractArtColours(art(700 to blue, 292 to 0xFF202428, 4 to red, 4 to 0xFF30C040))!!
        assertTrue(hueDistance(two.accentHue, hueOf(blue)) < 3)
        // Nor is a colour that's no more vivid than the art.
        val dull = extractArtColours(art(700 to 0xFF3060D0, 296 to 0xFF202428, 4 to red))!!
        assertTrue(hueDistance(dull.accentHue, hueOf(0xFF3060D0)) < 3)
    }

    @Test fun splashSchemeIsNeutralWithColourfulControls() {
        val orange = argbToLch(0xFFF06A1E.toInt())
        val (cs, wash) = artScheme(ArtColours(orange.third, orange.second, 90.0, 2.0, 30.0, splash = true))
        assertTrue(argbToLch(wash.toArgbInt()).second < 7)
        assertTrue(argbToLch(cs.background.toArgbInt()).second < 5)
        assertTrue(hueDistance(argbToLch(cs.primary.toArgbInt()).third, orange.third) < 10)
        assertTrue(argbToLch(cs.primary.toArgbInt()).second > 50)
    }

    @Test fun monochromeSchemeIsGreyAndReadable() {
        val (cs, wash) = monochromeArtScheme
        for (c in listOf(cs.primary, cs.background, cs.onSurface, cs.onSurfaceVariant, cs.secondaryContainer, wash)) {
            assertTrue(argbToLch(c.toArgbInt()).second < 1)
        }
        assertTrue(contrast(cs.primary, cs.background) >= 4.5)
        assertTrue(contrast(cs.onPrimary, cs.primary) >= 4.5)
        assertTrue(contrast(cs.onBackground, wash) >= 4.5)
        // Shuffle/repeat on (primary) must look different from off (onSurfaceVariant).
        assertTrue(contrast(cs.primary, cs.onSurfaceVariant) >= 1.4)
    }

    @Test fun darkArtGivesDarkerWash() {
        val dark = extractArtColours(art(1000 to 0xFF101A40))!!
        val light = extractArtColours(art(1000 to 0xFF8FB0FF))!!
        assertTrue(dark.washTone < light.washTone)
        assertTrue(dark.washTone in 20.0..32.0 && light.washTone in 20.0..32.0)
    }

    @Test fun artSchemesStayReadable() {
        for (hue in 0 until 360 step 15) for (chroma in listOf(10.0, 30.0, 60.0, 120.0)) for (tone in listOf(20.0, 32.0))
        for (splash in listOf(false, true)) {
            val (cs, wash) = artScheme(ArtColours(hue.toDouble(), chroma, (hue + 90.0) % 360, chroma, tone, splash))
            val tag = "hue $hue chroma $chroma tone $tone splash $splash"
            assertTrue("$tag primary on background", contrast(cs.primary, cs.background) >= 4.5)
            assertTrue("$tag onPrimary on primary", contrast(cs.onPrimary, cs.primary) >= 4.5)
            assertTrue("$tag text on wash", contrast(cs.onBackground, wash) >= 4.5)
            assertTrue("$tag icons in primary on wash", contrast(cs.primary, wash) >= 3.0)
        }
    }

    @Test fun mutedArtGetsMutedAccent() {
        val muted = artScheme(ArtColours(60.0, 15.0, 60.0, 15.0, 26.0)).scheme.primary
        val vivid = artScheme(ArtColours(60.0, 80.0, 60.0, 80.0, 26.0)).scheme.primary
        assertTrue(argbToLch(muted.toArgbInt()).second < argbToLch(vivid.toArgbInt()).second)
    }

    private fun Color.toArgbInt(): Int =
        (0xFF shl 24) or ((red * 255 + 0.5).toInt() shl 16) or ((green * 255 + 0.5).toInt() shl 8) or (blue * 255 + 0.5).toInt()
}
