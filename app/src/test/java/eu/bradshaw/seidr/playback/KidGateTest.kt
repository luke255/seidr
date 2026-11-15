package eu.bradshaw.seidr.playback

import eu.bradshaw.seidr.data.AlbumPlays
import eu.bradshaw.seidr.data.KidLists
import eu.bradshaw.seidr.data.KidRules
import eu.bradshaw.seidr.data.KidSong
import eu.bradshaw.seidr.playback.KidGate.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class KidGateTest {
    private val kid = KidRules(KidLists(
        approved = listOf(KidSong("ok", "al1")),
        titles = listOf(KidSong("t", "al2")),
        art = listOf(KidSong("a", "al3")),
    ))
    private val noLists = KidRules(KidLists())
    private val file = File("/music/ok.flac")

    @Test fun `the data source refuses an unapproved song, downloaded or not`() {
        assertEquals(Source.Refused("Not approved"), KidGate.source("t", kid, local = File("/music/t.flac"), online = true))
        assertEquals(Source.Refused("Not approved"), KidGate.source("nope", kid, local = null, online = true))
        // A kid's login whose lists haven't loaded yet: nothing plays.
        assertEquals(Source.Refused("Not approved"), KidGate.source("ok", noLists, local = file, online = true))
    }

    @Test fun `an approved song plays, local first`() {
        assertEquals(Source.Local(file), KidGate.source("ok", kid, local = file, online = true))
        assertEquals(Source.Stream, KidGate.source("ok", kid, local = null, online = true))
        assertEquals(Source.Refused("Offline and not downloaded"), KidGate.source("ok", kid, local = null, online = false))
        // Not a kid: anything plays.
        assertEquals(Source.Stream, KidGate.source("t", null, local = null, online = true))
    }

    private fun e(id: String) = QueueEntry("$id|k", id, id, "", "")

    @Test fun `the car's queues hold only approved songs on a kid's login`() {
        val all = listOf(e("t"), e("ok"), e("a"), e("x"))
        val (list, first) = KidGate.queue(all, all[0], kid, online = true) { true }
        assertEquals(listOf(e("ok")), list)
        assertNull(first)
        assertEquals(all to all[0], KidGate.queue(all, all[0], null, online = true) { true })
        assertEquals(emptyList<QueueEntry>(), KidGate.queue(all, null, noLists, online = true) { true }.first)
    }

    @Test fun `offline queues hold only downloaded songs`() {
        val all = listOf(e("ok"), e("t"))
        assertEquals(listOf(e("t")) to null, KidGate.queue(all, all[0], null, online = false) { it == "t" })
    }

    @Test fun `recent albums show only what a kid can see, art only where allowed`() {
        val plays = listOf(
            AlbumPlays.Play("al1", "One", "", "al-al1", 3),
            AlbumPlays.Play("al2", "Two", "", "al-al2", 2),
            AlbumPlays.Play("al9", "Nine", "", "al-al9", 1),
        )
        val shown = KidGate.recentAlbums(plays, kid)
        assertEquals(listOf("al1", "al2"), shown.map { it.id })
        assertEquals(listOf("al-al1", null), shown.map { it.coverArt })
        assertEquals(plays, KidGate.recentAlbums(plays, null))
    }
}
