package eu.bradshaw.seidr.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditResolverTest {
    @Test fun `remove uses the hint when it still matches`() {
        assertEquals(2, EditResolver.removeIndex(listOf("a", "b", "a"), "a", 2))
    }

    @Test fun `remove finds the song when the list shifted`() {
        // Someone added a track at the top elsewhere; "b" moved from 1 to 2.
        assertEquals(2, EditResolver.removeIndex(listOf("x", "a", "b"), "b", 1))
    }

    @Test fun `remove picks the nearest duplicate`() {
        assertEquals(3, EditResolver.removeIndex(listOf("a", "b", "c", "a"), "a", 4))
    }

    @Test fun `remove of a song already gone is dropped`() {
        assertNull(EditResolver.removeIndex(listOf("a", "c"), "b", 1))
    }

    @Test fun `move applies only to the list the user saw`() {
        assertEquals(listOf("b", "c", "a"), EditResolver.move(listOf("a", "b", "c"), listOf("a", "b", "c"), 0, 2))
        assertNull(EditResolver.move(listOf("a", "b", "c", "d"), listOf("a", "b", "c"), 0, 2))
    }

    @Test fun `reorder rejects a changed list or a non-permutation`() {
        val seen = listOf("a", "b", "c")
        assertEquals(listOf("c", "a", "b"), EditResolver.reorder(seen, seen, listOf("c", "a", "b")))
        assertNull(EditResolver.reorder(listOf("a", "b"), seen, listOf("c", "a", "b")))
        assertNull(EditResolver.reorder(seen, seen, listOf("a", "a", "b")))
    }

    @Test fun undoRemovePutsTheSongBackInPlace() {
        assertEquals(listOf("a", "b", "c"), EditResolver.undoRemove(listOf("a", "c", "b"), listOf("a", "b", "c")))
    }

    @Test fun undoRemoveLeavesItOnTheEndIfSomethingElseChanged() {
        assertEquals(null, EditResolver.undoRemove(listOf("a", "c", "d", "b"), listOf("a", "b", "c")))
        assertEquals(null, EditResolver.undoRemove(listOf("a", "b", "c"), listOf("a", "b", "c")))
    }
}
