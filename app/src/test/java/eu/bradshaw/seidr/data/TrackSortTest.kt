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

    private fun ids(sort: TrackSort, descending: Boolean = sort.firstDescending) = sort.sort(list, descending).map { it.value.id }

    @Test fun customIsThePlaylistsOwnOrder() = assertEquals(listOf("a", "b", "c", "a"), ids(TrackSort.CUSTOM))

    @Test fun customBackwardsIsThePlaylistReversed() = assertEquals(listOf("a", "c", "b", "a"), ids(TrackSort.CUSTOM, true))

    @Test fun titleIgnoresCase() = assertEquals(listOf("b", "c", "a", "a"), ids(TrackSort.TITLE))

    @Test fun titleBackwardsKeepsTiesForwards() = assertEquals(listOf("a", "a", "c", "b"), ids(TrackSort.TITLE, true))

    @Test fun artistThenAlbumThenTrack() = assertEquals(listOf("b", "c", "a", "a"), ids(TrackSort.ARTIST))

    // Within beta's album the tracks stay in order: only the artist turns round.
    @Test fun artistBackwardsKeepsAlbumOrder() = assertEquals(listOf("c", "a", "a", "b"), ids(TrackSort.ARTIST, true))

    @Test fun yearIsOldestFirstAtFirst() = assertEquals(listOf("c", "a", "a", "b"), ids(TrackSort.YEAR))

    @Test fun yearBackwardsIsNewestFirst() = assertEquals(listOf("b", "c", "a", "a"), ids(TrackSort.YEAR, true))

    @Test fun playCountIsMostFirstAtFirstAndTiesKeepOrder() = assertEquals(listOf("b", "a", "a", "c"), ids(TrackSort.PLAYS))

    @Test fun playCountBackwardsIsLeastFirst() = assertEquals(listOf("c", "a", "a", "b"), ids(TrackSort.PLAYS, false))

    @Test fun keepsPlaylistPositionsForRemoves() {
        // A duplicate keeps its own position, so removing the second "a" removes that one.
        assertEquals(listOf(1, 0, 3, 2), TrackSort.PLAYS.sort(list).map { it.index })
    }

    @Test fun pickingTheSameSortTurnsItRound() {
        val title = TrackOrder(TrackSort.TITLE)
        assertEquals(false, title.descending)
        assertEquals(TrackOrder(TrackSort.TITLE, true), title.pick(TrackSort.TITLE))
        assertEquals(title, title.pick(TrackSort.TITLE).pick(TrackSort.TITLE))
    }

    @Test fun pickingAnotherSortGoesItsFirstWay() {
        val back = TrackOrder(TrackSort.TITLE, true)
        assertEquals(TrackOrder(TrackSort.ALBUM, false), back.pick(TrackSort.ALBUM))
        assertEquals(TrackOrder(TrackSort.PLAYS, true), back.pick(TrackSort.PLAYS))
        assertEquals(TrackOrder(TrackSort.PLAYS, false), back.pick(TrackSort.PLAYS).pick(TrackSort.PLAYS))
    }

    @Test fun keysRoundTrip() = TrackSort.entries.forEach { s ->
        listOf(false, true).forEach { d -> TrackOrder(s, d).let { assertEquals(it, TrackOrder.of(it.key)) } }
    }

    // Kept before orders had a way: Year and Play count were newest and most first.
    @Test fun bareKeysGoTheirOldWay() {
        assertEquals(TrackOrder(TrackSort.YEAR, true), TrackOrder.of("year"))
        assertEquals(TrackOrder(TrackSort.PLAYS, true), TrackOrder.of("plays"))
        assertEquals(TrackOrder(TrackSort.TITLE, false), TrackOrder.of("title"))
    }

    @Test fun unknownKeyIsTheFallback() {
        assertEquals(TrackOrder(TrackSort.CUSTOM), TrackOrder.of("shuffle"))
        assertEquals(TrackOrder(TrackSort.ARTIST), TrackOrder.of(null, TrackOrder(TrackSort.ARTIST)))
    }
}
