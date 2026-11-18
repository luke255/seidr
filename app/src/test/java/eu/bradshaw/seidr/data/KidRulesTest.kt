package eu.bradshaw.seidr.data

import eu.bradshaw.seidr.data.api.Album
import eu.bradshaw.seidr.data.api.Artist
import eu.bradshaw.seidr.data.api.Playlist
import eu.bradshaw.seidr.data.api.SearchResult
import eu.bradshaw.seidr.data.api.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KidRulesTest {
    private fun song(id: String, album: String = "al-$id", artist: String = "ar-$id") =
        Song(id, title = "Title $id", albumId = album, artistId = artist, coverArt = "mf-$id")

    private val rules = KidRules(KidLists(
        approved = listOf(KidSong("ok", "al-1", listOf("ar-1"))),
        titles = listOf(KidSong("t", "al-2", listOf("ar-2"))),
        art = listOf(KidSong("a", "al-3", listOf("ar-3"))),
        playlistIds = listOf("pl-approved"),
    ))

    @Test fun `approved songs play with title and art`() {
        val s = rules.song(song("ok"))!!
        assertTrue(rules.playable("ok"))
        assertFalse(s.locked)
        assertEquals("Title ok", s.title)
        assertEquals("mf-ok", s.coverArt)
    }

    @Test fun `safe title only shows the title, disabled`() {
        val s = rules.song(song("t"))!!
        assertFalse(rules.playable("t"))
        assertTrue(s.locked)
        assertEquals("Title t", s.title)
        assertNull(s.coverArt)
    }

    @Test fun `safe art only shows the art, disabled`() {
        val s = rules.song(song("a"))!!
        assertTrue(s.locked)
        assertEquals(KidRules.HIDDEN_TITLE, s.title)
        assertEquals("mf-a", s.coverArt)
    }

    @Test fun `songs on no list don't show at all`() {
        assertNull(rules.song(song("x")))
        assertFalse(rules.playable("x"))
    }

    @Test fun `no lists means nothing shows or plays`() {
        val empty = KidRules(KidLists())
        assertNull(empty.song(song("ok")))
        assertTrue(empty.albums(listOf(Album("al-1", "A"))).isEmpty())
        assertTrue(empty.artists(listOf(Artist("ar-1", "B"))).isEmpty())
    }

    @Test fun `albums show while one of their songs does, with art only if a song's art is safe`() {
        val albums = rules.albums(listOf(
            Album("al-1", coverArt = "c1"), Album("al-2", coverArt = "c2"), Album("al-3", coverArt = "c3"), Album("al-9", coverArt = "c9"),
        ))
        assertEquals(listOf("al-1", "al-2", "al-3"), albums.map { it.id })
        assertEquals(listOf("c1", null, "c3"), albums.map { it.coverArt })
    }

    @Test fun `an album's own song list is filtered, and its counts stay the server's`() {
        val a = rules.album(Album("al-1", songCount = 2, duration = 300,
            song = listOf(song("ok", "al-1").copy(duration = 100), song("x", "al-1").copy(duration = 200))))!!
        assertEquals(listOf("ok"), a.song.map { it.id })
        assertEquals(2, a.songCount)
        assertEquals(300, a.duration)
    }

    @Test fun `artists show without pictures, and only their albums that show`() {
        val a = rules.artist(Artist("ar-1", coverArt = "pic", album = listOf(Album("al-1"), Album("al-9"))))!!
        assertNull(a.coverArt)
        assertEquals(listOf("al-1"), a.album.map { it.id })
        assertNull(rules.artist(Artist("ar-9")))
    }

    @Test fun `playlists show filtered and without mosaics, and the kids' lists never do`() {
        val p = rules.playlist(Playlist("p", "Anyone's", owner = "luke", coverArt = "pl-p", songCount = 2, entry = listOf(song("ok"), song("x"))))!!
        assertEquals(listOf("ok"), p.entry.map { it.id })
        assertEquals(2, p.songCount)
        assertNull(p.coverArt)
        assertNull(rules.playlist(Playlist("pl-approved", KidRules.APPROVED)))
        assertNull(rules.playlist(Playlist("t", KidRules.SAFE_TITLES)))
    }

    @Test fun `only the grown-up's playlists with the exact name are trusted`() {
        assertTrue(KidRules.trusted(Playlist("1", "Seidr: Kids Approved", owner = "Luke"), KidRules.APPROVED, "luke"))
        assertFalse(KidRules.trusted(Playlist("2", "Seidr: Kids Approved", owner = "kid"), KidRules.APPROVED, "luke"))
        assertFalse(KidRules.trusted(Playlist("3", "seidr: kids approved", owner = "luke"), KidRules.APPROVED, "luke"))
        assertFalse(KidRules.trusted(Playlist("4", "Seidr: Kids Approved", owner = null), KidRules.APPROVED, "luke"))
    }

    @Test fun `search results are filtered`() {
        val r = rules.search(SearchResult(
            artist = listOf(Artist("ar-1"), Artist("ar-9")),
            album = listOf(Album("al-2"), Album("al-9")),
            song = listOf(song("x"), song("t")),
        ))
        assertEquals(listOf("ar-1"), r.artist.map { it.id })
        assertEquals(listOf("al-2"), r.album.map { it.id })
        assertEquals(listOf("t"), r.song.map { it.id })
    }

    @Test fun `covers are served only where the art would show`() {
        assertTrue(rules.coverAllowed("mf-ok"))
        assertTrue(rules.coverAllowed("mf-a_5f2c"))
        assertFalse("safe title only", rules.coverAllowed("mf-t"))
        assertFalse("on no list", rules.coverAllowed("mf-x"))
        assertTrue(rules.coverAllowed("al-al-1_a4609b56"))
        assertTrue(rules.coverAllowed("al-al-3"))
        assertFalse("album with only a safe title", rules.coverAllowed("al-al-2"))
        assertFalse("artist art never", rules.coverAllowed("ar-ar-1_1a18"))
        assertFalse("playlist art never", rules.coverAllowed("pl-pl-approved_0060"))
        assertFalse("unknown scheme fails closed", rules.coverAllowed("ok"))
        assertFalse("no lists yet", KidRules(KidLists()).coverAllowed("mf-ok"))
        assertTrue("a disc's cover is its album's", rules.coverAllowed("dc-al-1:1"))
        assertFalse(rules.coverAllowed("dc-al-2:1"))
    }

    @Test fun `a picture the grown-up chose shows, on their playlist only`() {
        val pictured = KidRules(KidLists(approved = listOf(KidSong("ok", "al-1")), pictures = listOf("p")))
        assertEquals("pl-p_0060", pictured.playlist(Playlist("p", "Car songs", coverArt = "pl-p_0060"))!!.coverArt)
        assertNull(pictured.playlist(Playlist("q", "Mine", coverArt = "pl-q_0060"))!!.coverArt)
        assertTrue(pictured.coverAllowed("pl-p_0060"))
        assertFalse(pictured.coverAllowed("pl-q_0060"))
    }
}
