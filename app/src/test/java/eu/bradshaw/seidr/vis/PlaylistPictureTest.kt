package eu.bradshaw.seidr.vis

import eu.bradshaw.seidr.data.KidLists
import eu.bradshaw.seidr.data.KidRules
import eu.bradshaw.seidr.data.api.Playlist
import eu.bradshaw.seidr.playback.PlayerUi
import eu.bradshaw.seidr.playback.QueueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which picture the visualiser's playlist page shows: this device's lookup, else the queue's. */
class PlaylistPictureTest {
    private fun playing(playlist: String?, art: String?) = PlayerUi(
        queue = listOf(QueueEntry("s1|a", "s1", "Song", "Artist", "Album", ctxName = "Mix", ctxPlaylist = playlist, ctxArt = art)),
        index = 0,
    )
    private val mine = Playlist("p1", "Mix", coverArt = "pl-p1_new")

    @Test fun ownLookupWins() {
        // Changed mid-play: the lookup has the new one.
        assertEquals("pl-p1_new", playlistPicture(playing("p1", "pl-p1_old"), setOf("p1"), true, listOf(mine), null))
    }

    @Test fun ownLookupSaysNoPicture() {
        assertNull(playlistPicture(playing("p1", "pl-p1_old"), emptySet(), true, listOf(mine), null))
    }

    @Test fun guestUsesTheQueuesPicture() {
        // Signed out (playing for a guest): no playlists of its own to look in.
        assertEquals("pl-p1_old", playlistPicture(playing("p1", "pl-p1_old"), emptySet(), false, null, null))
        // Another login's device, or pictures unsupported here: the queue's too.
        assertEquals("pl-p1_old", playlistPicture(playing("p1", "pl-p1_old"), emptySet(), true, emptyList(), null))
        assertEquals("pl-p1_old", playlistPicture(playing("p1", "pl-p1_old"), emptySet(), false, listOf(mine), null))
    }

    @Test fun noPlaylistNoPage() {
        assertNull(playlistPicture(playing(null, null), setOf("p1"), true, listOf(mine), null))
        assertNull(playlistPicture(playing("p1", null), emptySet(), false, null, null))
    }

    @Test fun kidsSeeOnlyChosenPictures() {
        val allowed = KidRules(KidLists(pictures = listOf("p1")))
        val not = KidRules(KidLists())
        assertEquals("pl-p1_old", playlistPicture(playing("p1", "pl-p1_old"), emptySet(), false, null, allowed))
        assertNull(playlistPicture(playing("p1", "pl-p1_old"), emptySet(), false, null, not))
    }
}
