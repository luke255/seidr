package eu.bradshaw.seidr.ui

import eu.bradshaw.seidr.playback.QueueEntry
import eu.bradshaw.seidr.ui.player.artPages
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtPagesTest {
    /** One entry per letter; the letter is the album. */
    private fun queue(albums: String) = albums.mapIndexed { i, a ->
        QueueEntry("k$i", "s$i", "t$i", "", a.toString(), albumId = a.toString(), coverArt = "mf-$i")
    }

    /** Each page as "album:landing index". */
    private fun pages(albums: String, index: Int) = artPages(queue(albums), index).map { "${it.art.removePrefix("al:")}:${it.land}" }

    @Test fun runsCollapseAndTheCurrentRunSplits() {
        assertEquals(listOf("X:2", "Y:3", "Y:4", "Y:5", "Z:7"), pages("XXXYYYYZ", 4))
    }

    @Test fun runEdgesHaveNoEmptySide() {
        assertEquals(listOf("X:2", "Y:3", "Y:4", "Z:7"), pages("XXXYYYYZ", 3))
        assertEquals(listOf("X:2", "Y:5", "Y:6", "Z:7"), pages("XXXYYYYZ", 6))
    }

    @Test fun aWholeAlbumStillSwipesTrackByTrack() {
        assertEquals(listOf("X:0", "X:1", "X:2"), pages("XXXXX", 1))
    }

    @Test fun noCurrentMeansRunsOnly() {
        assertEquals(listOf("X:0", "Y:2"), pages("XXYY", -1))
    }

    @Test fun keysAreUnique() {
        val p = artPages(queue("XXXYYYYZ"), 4)
        assertEquals(p.size, p.map { it.key }.toSet().size)
    }
}
