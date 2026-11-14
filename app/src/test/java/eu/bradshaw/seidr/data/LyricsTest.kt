package eu.bradshaw.seidr.data

import eu.bradshaw.seidr.data.api.LyricLine
import eu.bradshaw.seidr.data.api.PlainLyrics
import eu.bradshaw.seidr.data.api.StructuredLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {
    private val plain = StructuredLyrics(synced = false, line = listOf(LyricLine(value = "one"), LyricLine(value = "two")))
    private val synced = StructuredLyrics(
        synced = true, offset = 500,
        line = listOf(LyricLine(1000, "a"), LyricLine(3000, "b"), LyricLine(2000, "c")),
    )

    @Test fun prefersSyncedAndAppliesOffset() {
        val l = Lyrics.from(listOf(plain, synced))!!
        assertTrue(l.synced)
        assertEquals(listOf(500L, 1500L, 2500L), l.lines.map { it.startMs })
        assertEquals(listOf("a", "c", "b"), l.lines.map { it.text })
    }

    @Test fun syncedWithMissingTimesFallsBackToUnsynced() {
        val broken = synced.copy(line = synced.line + LyricLine(null, "d"))
        val l = Lyrics.from(listOf(broken))!!
        assertFalse(l.synced)
        assertTrue(l.lines.all { it.startMs == null })
    }

    @Test fun emptyIsNone() {
        assertNull(Lyrics.from(emptyList<StructuredLyrics>()))
        assertNull(Lyrics.from(listOf(StructuredLyrics(line = listOf(LyricLine(value = " "))))))
        assertNull(Lyrics.from(PlainLyrics(value = "")))
        assertEquals(3, Lyrics.from(PlainLyrics(value = "x\n\ny\n"))!!.lines.size)
    }

    @Test fun lineAt() {
        val l = Lyrics.from(listOf(synced.copy(offset = 0)))!!
        assertEquals(-1, l.lineAt(999))
        assertEquals(0, l.lineAt(1000))
        assertEquals(1, l.lineAt(2999))
        assertEquals(2, l.lineAt(60_000))
        assertEquals(-1, Lyrics.from(listOf(plain))!!.lineAt(5000))
    }

    private fun timed(vararg lines: Pair<Long, String>) = Lyrics(lines.map { Lyrics.Line(it.first, it.second) }, synced = true)

    @Test fun breaksGoInAfterALongGap() {
        // "short" is sung for ~3.5s, then 20s of music.
        val l = timed(6_000L to "short", 26_000L to "next").withBreaks()
        assertEquals(listOf(0L, 6_000L, 9_500L, 26_000L), l.lines.map { it.startMs })
        assertEquals(listOf("", "short", "", "next"), l.lines.map { it.text })
    }

    @Test fun ordinaryGapsAndShortIntrosAreLeftAlone() {
        val l = timed(1_000L to "one", 5_000L to "two", 14_000L to "three").withBreaks()
        assertEquals(3, l.lines.size)
    }

    @Test fun anExistingBlankLineIsTrustedAndRunsOfThemMerge() {
        val l = timed(1_000L to "one", 4_000L to "", 5_000L to "", 30_000L to "two").withBreaks()
        assertEquals(listOf(1_000L, 4_000L, 30_000L), l.lines.map { it.startMs })
    }

    @Test fun outroNeedsTheSongLength() {
        val l = timed(1_000L to "last")
        assertEquals(1, l.withBreaks().lines.size)
        assertEquals(listOf(1_000L, 4_400L), l.withBreaks(durationMs = 60_000).lines.map { it.startMs })
    }

    @Test fun unsyncedIsUntouched() {
        val l = Lyrics.from(listOf(plain))!!
        assertTrue(l.withBreaks(60_000) === l)
    }

    @Test fun introNoteIsLitBeforeZero() {
        // A -2s timing nudge puts the song's first two seconds at -2000..0.
        val l = timed(10_000L to "first").withBreaks()
        assertEquals(0, l.lineAt(-2_000))
        assertEquals(-1, timed(10_000L to "first").lineAt(-2_000))
    }

    @Test fun briefBlankLinesAreDropped() {
        // Timings from a real song: a 0.4s blank between verses, a 14.8s one for the break.
        val l = timed(46_860L to "end of verse one", 49_570L to "", 49_960L to "chorus",
            81_200L to "", 96_030L to "verse two").withBreaks()
        assertEquals(listOf(0L, 46_860L, 49_960L, 81_200L, 96_030L), l.lines.map { it.startMs })
    }
}
