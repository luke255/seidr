package eu.bradshaw.seidr.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerTest {
    @Test fun `diff adds new and removes gone`() {
        val d = SyncPlanner.diffClaims(listOf("a", "b", "b", "c"), listOf("c", "d"))
        assertEquals(setOf("a", "b"), d.add)
        assertEquals(setOf("d"), d.remove)
    }

    @Test fun `trust rules`() {
        assertTrue(SyncPlanner.isTrustworthy(3, 3, 3, false))
        assertFalse("count mismatch", SyncPlanner.isTrustworthy(2, 3, 3, false))
        assertFalse("unconfirmed empty", SyncPlanner.isTrustworthy(0, null, 3, false))
        assertTrue("confirmed empty", SyncPlanner.isTrustworthy(0, 0, 3, true))
        assertTrue("empty with no claims", SyncPlanner.isTrustworthy(0, 0, 0, false))
    }

    @Test fun `file plan never deletes claimed or protected files`() {
        val plan = SyncPlanner.planFiles(
            claimed = setOf("a", "b"),
            files = listOf(
                LocalFile("a", true, 1000, "flac"),
                LocalFile("x", true, 1000, "flac"),
                LocalFile("y", false, 1000, "flac"),
                LocalFile("z", true, 1000, "flac"),
            ),
            known = mapOf("b" to track("b"), "a" to track("a")),
            protected = setOf("z"),
        )
        assertEquals(listOf("x", "y"), plan.delete)
        assertEquals(listOf("z"), plan.deferred)
        assertEquals(listOf("b"), plan.queue.map { it.id })
        assertTrue(plan.redownload.isEmpty())
    }

    @Test fun `suffix comparison ignores case and zero size is unknown`() {
        val plan = SyncPlanner.planFiles(
            claimed = setOf("a"),
            files = listOf(LocalFile("a", true, 1000, "FLAC")),
            known = mapOf("a" to track("a", size = 0, suffix = "flac")),
            protected = emptySet(),
        )
        assertTrue(plan.redownload.isEmpty())
    }

    @Test fun `a queued row with a stale size is re-queued with the server's`() {
        val plan = SyncPlanner.planFiles(
            claimed = setOf("a"),
            files = listOf(LocalFile("a", false, 1000, "flac")),
            known = mapOf("a" to track("a", size = 2000)),
            protected = emptySet(),
        )
        assertEquals(listOf(2000L), plan.redownload.map { it.size })
    }
}
