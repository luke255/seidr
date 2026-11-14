package eu.bradshaw.seidr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsOffsetsTest {
    @Test fun roundTrips() {
        for (ms in listOf(650L, -1200L, 50L)) assertEquals(ms, LyricsOffsets.parse(LyricsOffsets.format(ms)))
        assertEquals("Seidr lyrics offset: +650ms", LyricsOffsets.format(650))
    }

    @Test fun ignoresOtherBookmarks() {
        assertNull(LyricsOffsets.parse(null))
        assertNull(LyricsOffsets.parse(""))
        assertNull(LyricsOffsets.parse("Chapter 3"))
        assertNull(LyricsOffsets.parse("Seidr lyrics offset: lots"))
    }
}
