package eu.bradshaw.seidr.data

import eu.bradshaw.seidr.data.api.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BadReportsTest {
    @Test fun hidesEveryBadPlaylist() {
        assertTrue(BadReports.isBadPlaylist("Bad: Songs"))
        assertTrue(BadReports.isBadPlaylist("bad: something else"))
        assertFalse(BadReports.isBadPlaylist("Badlands"))
        assertFalse(BadReports.isBadPlaylist("Not Bad: really"))
    }

    @Test fun findsEachKindByName() {
        val found = BadReports.find(listOf(
            Playlist("1", "Bad: Songs"),
            Playlist("2", "bad: art "),
            Playlist("3", "Bad: Lyrics", readonly = true), // smart playlist: can't be added to
            Playlist("4", "Good stuff"),
        ))
        assertEquals(mapOf(BadKind.SONG to "1", BadKind.ART to "2"), found.mapValues { it.value.id })
    }
}
