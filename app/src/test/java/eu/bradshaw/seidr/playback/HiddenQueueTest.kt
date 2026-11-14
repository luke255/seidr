package eu.bradshaw.seidr.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class HiddenQueueTest {
    private fun e(song: String, slot: Int = 0) = QueueEntry("$song|$slot", song, song, "", "")
    private fun keys(vararg songs: String) = songs.map { "$it|0" }

    /** Hides [bad] from [songs] the way PlaybackService does; returns what's left and the hidden entries. */
    private fun hide(songs: List<String>, bad: Set<String>): Pair<List<String>, List<HiddenEntry>> {
        val k = songs.map { "$it|0" }
        val plan = HiddenQueue.hide(k, bad)
        val hidden = plan.map { (i, prev, next) -> HiddenEntry(e(songs[i]), prev, next) }
        val gone = plan.map { it.first }.toSet()
        return k.filterIndexed { i, _ -> i !in gone } to hidden
    }

    @Test fun hidesBadSongsWithTheirNeighbours() {
        val (left, hidden) = hide(listOf("a", "x", "b", "y", "z", "c"), setOf("x", "y", "z"))
        assertEquals(keys("a", "b", "c"), left)
        assertEquals(listOf("a|0" to "b|0", "b|0" to "c|0", "b|0" to "c|0"), hidden.map { it.prevKey to it.nextKey })
    }

    @Test fun fixedSongsGoBackInOrder() {
        val (left, hidden) = hide(listOf("a", "x", "y", "b"), setOf("x", "y"))
        val r = HiddenQueue.restore(left, hidden, emptySet())
        assertEquals(keys("a", "x", "y", "b"), r.keys)
        assertEquals(emptyList<HiddenEntry>(), r.hidden)
    }

    @Test fun oneFixedAtATimeStillLandsInOrder() {
        val (left, hidden) = hide(listOf("a", "x", "y", "b"), setOf("x", "y"))
        val first = HiddenQueue.restore(left, hidden, setOf("x"))
        assertEquals(keys("a", "y", "b"), first.keys)
        val second = HiddenQueue.restore(first.keys, first.hidden, emptySet())
        assertEquals(keys("a", "x", "y", "b"), second.keys)
    }

    @Test fun fallsBackToTheNextNeighbour() {
        val (left, hidden) = hide(listOf("a", "x", "b"), setOf("x"))
        val r = HiddenQueue.restore(left - "a|0", hidden, emptySet())
        assertEquals(keys("x", "b"), r.keys)
    }

    @Test fun firstInQueueGoesBackFirst() {
        val (left, hidden) = hide(listOf("x", "y", "a"), setOf("x", "y"))
        assertEquals(keys("x", "y", "a"), HiddenQueue.restore(left, hidden, emptySet()).keys)
    }

    @Test fun aReplacedQueueDropsTheOldHiddenEntries() {
        val (_, hidden) = hide(listOf("a", "x", "b"), setOf("x"))
        val newQueue = keys("p", "q")
        assertEquals(newQueue, HiddenQueue.restore(newQueue, hidden, emptySet()).keys)
        assertEquals(emptyList<HiddenEntry>(), HiddenQueue.restore(newQueue, hidden, setOf("x")).hidden)
    }

    @Test fun stillBadStaysHidden() {
        val (left, hidden) = hide(listOf("a", "x", "b"), setOf("x"))
        val r = HiddenQueue.restore(left, hidden, setOf("x"))
        assertEquals(left, r.keys)
        assertEquals(hidden, r.hidden)
    }

    @Test fun aWhollyBadQueueOnlyReturnsToAnEmptyQueue() {
        val (left, hidden) = hide(listOf("x"), setOf("x"))
        assertEquals(keys("x"), HiddenQueue.restore(left, hidden, emptySet()).keys)
        assertEquals(keys("p"), HiddenQueue.restore(keys("p"), hidden, emptySet()).keys)
    }
}
