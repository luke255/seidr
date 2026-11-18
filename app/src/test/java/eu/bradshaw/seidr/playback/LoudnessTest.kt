package eu.bradshaw.seidr.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class LoudnessTest {
    private fun db(v: Float) = 10f.pow(v / 20f)

    @Test fun picksTheLevelForTheMode() {
        assertEquals(db(-6f), Loudness.volume(-6f, -9f, albumMode = false), 1e-5f)
        assertEquals(db(-9f), Loudness.volume(-6f, -9f, albumMode = true), 1e-5f)
    }

    @Test fun fallsBackToTheOtherLevelThenTheUntaggedOne() {
        assertEquals(db(-6f), Loudness.volume(-6f, null, albumMode = true), 1e-5f)
        assertEquals(db(-9f), Loudness.volume(null, -9f, albumMode = false), 1e-5f)
        assertEquals(db(Loudness.UNTAGGED_DB), Loudness.volume(null, null, albumMode = false), 1e-5f)
    }

    @Test fun neverTurnsUp() {
        assertEquals(1f, Loudness.volume(3.5f, 2f, albumMode = false), 0f)
    }

    @Test fun albumModeNeedsANeighbourFromTheSameAlbum() {
        assertTrue(Loudness.albumMode("a", "a", "b"))
        assertTrue(Loudness.albumMode("a", null, "a"))
        assertFalse(Loudness.albumMode("a", "b", "c"))
        assertFalse(Loudness.albumMode(null, null, null))
    }
}
