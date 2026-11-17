package eu.bradshaw.seidr.data

import eu.bradshaw.seidr.data.api.Playlist
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistChangesTest {
    private fun p(id: String, count: Int = 3, changed: String = "t1", readonly: Boolean = false, name: String = id) =
        Playlist(id = id, name = name, songCount = count, duration = count * 100, changed = changed, readonly = readonly)

    @Test fun unchangedListingAnnouncesNothing() {
        val list = listOf(p("a"), p("b"))
        assertEquals(emptyList<String>(), changedPlaylists(list, list.map { it.copy() }))
    }

    @Test fun anEditMovesChangedOrTheCount() {
        assertEquals(listOf("a"), changedPlaylists(listOf(p("a"), p("b")), listOf(p("a", count = 2, changed = "t2"), p("b"))))
        // A reorder keeps the count; only `changed` moves.
        assertEquals(listOf("b"), changedPlaylists(listOf(p("a"), p("b")), listOf(p("a"), p("b", changed = "t2"))))
        assertEquals(listOf("a"), changedPlaylists(listOf(p("a")), listOf(p("a", name = "Renamed"))))
    }

    @Test fun createdAndDeletedCount() {
        assertEquals(setOf("b", "c"), changedPlaylists(listOf(p("a"), p("b")), listOf(p("a"), p("c"))).toSet())
    }

    @Test fun smartPlaylistsIgnoreChangedButNotTheirSongs() {
        // Navidrome bumps a smart playlist's `changed` on every read.
        assertEquals(emptyList<String>(), changedPlaylists(listOf(p("s", readonly = true)), listOf(p("s", changed = "t9", readonly = true))))
        assertEquals(listOf("s"), changedPlaylists(listOf(p("s", readonly = true)), listOf(p("s", count = 4, changed = "t9", readonly = true))))
    }
}
