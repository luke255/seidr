package eu.bradshaw.seidr.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class StableCoverIdTest {
    @Test fun `navidrome's version stamp is dropped`() {
        assertEquals("al-4svsSu8UW3XBHtB3ALK03f", stableCoverId("al-4svsSu8UW3XBHtB3ALK03f_aaf57efc43e57651"))
        assertEquals("mf-abc", stableCoverId("mf-abc_6543abcd"))
    }

    @Test fun `a disc's cover stands in for its album's`() {
        assertEquals("al-4svsSu8UW3XBHtB3ALK03f", stableCoverId("dc-4svsSu8UW3XBHtB3ALK03f:1"))
        assertEquals("al-4svsSu8UW3XBHtB3ALK03f", stableCoverId("dc-4svsSu8UW3XBHtB3ALK03f_2"))
    }

    @Test fun `other servers' ids pass through`() {
        assertEquals("al-123", stableCoverId("al-123"))
        assertEquals("cover_art", stableCoverId("cover_art"))
    }
}
