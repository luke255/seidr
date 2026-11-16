package eu.bradshaw.seidr.data

import eu.bradshaw.seidr.data.api.Album
import eu.bradshaw.seidr.data.api.Artist
import eu.bradshaw.seidr.data.api.Playlist
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySortTest {
    private val playlists = listOf(
        Playlist("old", name = "Zed", created = "2020-01-01T00:00:00Z", changed = "2026-01-01T00:00:00Z"),
        Playlist("new", name = "alpha", created = "2026-06-01T00:00:00Z", changed = "2026-06-01T00:00:00Z"),
        Playlist("mid", name = "Mid", created = "2024-01-01T00:00:00Z", changed = "2024-01-01T00:00:00Z"),
    )
    private fun ms(iso: String) = java.time.Instant.parse(iso).toEpochMilli()
    private fun ids(l: List<Playlist?>) = l.map { it?.id ?: "loved" }

    @Test fun recentIsLastPlayedOrCreated() {
        val played = mapOf("old" to ms("2026-11-01T00:00:00Z"))
        assertEquals(listOf("old", "new", "mid"), ids(PlaylistSort.RECENT.sort(playlists, played, null)))
    }

    @Test fun lovedGoesWhereItsNewestPlayOrLovePlacesIt() {
        val loved = LovedAt(played = null, lastLoved = "2025-01-01T00:00:00Z")
        assertEquals(listOf("new", "loved", "mid", "old"), ids(PlaylistSort.RECENT.sort(playlists, emptyMap(), loved)))
        assertEquals(listOf("new", "old", "loved", "mid"), ids(PlaylistSort.UPDATED.sort(playlists, emptyMap(), loved)))
    }

    @Test fun lovedHeadsNameAndCustom() {
        val loved = LovedAt(null, null)
        assertEquals(listOf("loved", "new", "mid", "old"), ids(PlaylistSort.NAME.sort(playlists, emptyMap(), loved)))
        assertEquals(listOf("loved", "old", "new", "mid"), ids(PlaylistSort.CUSTOM.sort(playlists, emptyMap(), loved)))
    }

    private val albums = listOf(
        Album("b2", name = "Second", artist = "Beta", year = 2001, playCount = 5),
        Album("a1", name = "Only", artist = "alpha", year = 2001, playCount = 5),
        Album("b1", name = "First", artist = "Beta", year = 2001),
    )

    @Test fun albumTiesFallThroughArtistThenAlbum() {
        assertEquals(listOf("a1", "b1", "b2"), AlbumSort.YEAR.sort(albums).map { it.id })
        assertEquals(listOf("a1", "b2", "b1"), AlbumSort.PLAYS.sort(albums).map { it.id })
        assertEquals(listOf("a1", "b1", "b2"), AlbumSort.ARTIST.sort(albums).map { it.id })
    }

    @Test fun savedTrustsTheLiveSetOverTheCachedList() {
        val list = listOf(
            Album("old", name = "A", starred = "2024-01-01T00:00:00Z"),
            Album("new", name = "B", starred = "2026-01-01T00:00:00Z"),
            Album("unsaved", name = "C", starred = "2026-06-01T00:00:00Z"),
            Album("just", name = "D"),
            Album("never", name = "E"),
        )
        assertEquals(listOf("just", "new", "old", "unsaved", "never"),
            AlbumSort.SAVED.sort(list, saved = setOf("old", "new", "just")).map { it.id })
        assertEquals(listOf("unsaved", "new", "old", "just", "never"), AlbumSort.SAVED.sort(list).map { it.id })
    }

    @Test fun artistPlaysAreTheirAlbums() {
        val artists = listOf(Artist("x", name = "X"), Artist("y", name = "Y"))
        val plays = ArtistSort.plays(listOf(Album("1", artistId = "y", playCount = 2), Album("2", artistId = "y", playCount = 2), Album("3", artistId = "x", playCount = 3)))
        assertEquals(listOf("y", "x"), ArtistSort.PLAYS.sort(artists) { plays }.map { it.id })
    }

    @Test fun oldHomeChoiceCarriesOver() {
        assertEquals(PlaylistSort.NAME, PlaylistSort.of(null, PlaylistSort.NAME))
        assertEquals(PlaylistSort.UPDATED, PlaylistSort.of("updated", PlaylistSort.NAME))
    }
}
