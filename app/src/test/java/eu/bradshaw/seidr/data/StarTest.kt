package eu.bradshaw.seidr.data

import eu.bradshaw.seidr.data.api.Album
import eu.bradshaw.seidr.data.api.Artist
import org.junit.Assert.assertEquals
import org.junit.Test

class StarTest {
    @Test fun newestFirstAcrossBothTimeFormats() {
        val stars = listOf(
            Star.OfAlbum(Album("album"), "2026-11-18T10:00:00Z"),
            // Navidrome's own API: nanoseconds.
            Star.OfPlaylist("playlist", "2026-11-18T15:36:09.348179102Z"),
            // Subsonic: milliseconds, and some servers write an offset.
            Star.OfArtist(Artist("artist"), "2026-11-18T16:00:00.5+01:00"),
            Star.OfAlbum(Album("unknown"), null),
            Star.OfAlbum(Album("garbled"), "yesterday"),
        )
        assertEquals(listOf("playlist", "artist", "album", "unknown", "garbled"), Star.sorted(stars).map { it.id })
    }
}
