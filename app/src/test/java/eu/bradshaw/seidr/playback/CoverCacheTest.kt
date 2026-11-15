package eu.bradshaw.seidr.playback

import eu.bradshaw.seidr.playback.CoverCache.Entry
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverCacheTest {
    @Test fun `within both limits nothing goes`() {
        assertEquals(emptySet<String>(), CoverCache.evict(listOf(Entry("a", 10, 1), Entry("b", 10, 2)), maxFiles = 2, maxBytes = 20))
    }

    @Test fun `too many files, oldest first`() {
        val entries = listOf(Entry("new", 1, 30), Entry("old", 1, 10), Entry("mid", 1, 20))
        assertEquals(setOf("old"), CoverCache.evict(entries, maxFiles = 2, maxBytes = 100))
    }

    @Test fun `too many bytes, oldest first until it fits`() {
        val entries = listOf(Entry("a", 40, 1), Entry("b", 40, 2), Entry("c", 40, 3))
        assertEquals(setOf("a", "b"), CoverCache.evict(entries, maxFiles = 10, maxBytes = 50))
    }
}
