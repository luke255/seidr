package eu.bradshaw.seidr.data

import eu.bradshaw.seidr.data.api.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackSortTest {
    private val list = listOf(
        Song("a", title = "Zebra", artist = "beta", album = "Two", track = 2, year = 1999, playCount = 3),
        Song("b", title = "apple", artist = "Alpha", album = "One", track = 1, year = 2005, playCount = 9),
        Song("c", title = "Mango", artist = "beta", album = "Two", track = 1, year = 1999),
        Song("a", title = "Zebra", artist = "beta", album = "Two", track = 2, year = 1999, playCount = 3),
    )

    private fun ids(sort: TrackSort) = sort.sort(list).map { it.value.id }

    @Test fun customIsThePlaylistsOwnOrder() = assertEquals(listOf("a", "b", "c", "a"), ids(TrackSort.CUSTOM))

    @Test fun titleIgnoresCase() = assertEquals(listOf("b", "c", "a", "a"), ids(TrackSort.TITLE))

    @Test fun artistThenAlbumThenTrack() = assertEquals(listOf("b", "c", "a", "a"), ids(TrackSort.ARTIST))

    @Test fun yearIsNewestFirst() = assertEquals(listOf("b", "c", "a", "a"), ids(TrackSort.YEAR))

    @Test fun mostPlayedFirstAndTiesKeepOrder() = assertEquals(listOf("b", "a", "a", "c"), ids(TrackSort.PLAYS))

    @Test fun keepsPlaylistPositionsForRemoves() {
        // A duplicate keeps its own position, so removing the second "a" removes that one.
        assertEquals(listOf(1, 0, 3, 2), TrackSort.PLAYS.sort(list).map { it.index })
    }

    @Test fun unknownKeyIsCustom() = assertEquals(TrackSort.CUSTOM, TrackSort.of("shuffle"))
}
