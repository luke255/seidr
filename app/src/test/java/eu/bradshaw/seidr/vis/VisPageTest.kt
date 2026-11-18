package eu.bradshaw.seidr.vis

import eu.bradshaw.seidr.vis.VisPage.LOGO
import eu.bradshaw.seidr.vis.VisPage.LYRICS
import eu.bradshaw.seidr.vis.VisPage.PLAYLIST
import eu.bradshaw.seidr.vis.VisPage.PRESET
import eu.bradshaw.seidr.vis.VisPage.TRACK
import org.junit.Assert.assertEquals
import org.junit.Test

class VisPageTest {
    private val noPlaylist: (VisPage) -> Boolean = { it != PLAYLIST }

    @Test fun turnsThroughEveryPageAndNone() {
        assertEquals(PRESET, VisPage.turn(null, 1))
        assertEquals(PLAYLIST, VisPage.turn(TRACK, 1))
        assertEquals(null, VisPage.turn(LOGO, 1))
        assertEquals(LOGO, VisPage.turn(null, -1))
    }

    @Test fun skipsThePlaylistPageWithoutAPicture() {
        assertEquals(LYRICS, VisPage.turn(TRACK, 1, noPlaylist))
        assertEquals(TRACK, VisPage.turn(LYRICS, -1, noPlaylist))
    }

    @Test fun leavesAHiddenPageForItsNeighbours() {
        assertEquals(LYRICS, VisPage.turn(PLAYLIST, 1, noPlaylist))
        assertEquals(TRACK, VisPage.turn(PLAYLIST, -1, noPlaylist))
    }
}
