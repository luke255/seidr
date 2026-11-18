package eu.bradshaw.seidr.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamQualityTest {
    @Test fun bitrateSuitsTheConnection() {
        assertEquals(128, StreamQuality.pickKbps(0))
        assertEquals(128, StreamQuality.pickKbps(1_500_000))
        assertEquals(192, StreamQuality.pickKbps(2_000_000))
        assertEquals(320, StreamQuality.pickKbps(10_000_000))
    }

    @Test fun evenOutVolumeFollowsTheLibraryUntilChosen() {
        assertFalse(LoudnessCoverage.shouldEvenOut(null))
        assertFalse(LoudnessCoverage.shouldEvenOut(0.03f))
        assertTrue(LoudnessCoverage.shouldEvenOut(0.8f))
        assertTrue(LoudnessCoverage.shouldEvenOut(1f))
    }
}
