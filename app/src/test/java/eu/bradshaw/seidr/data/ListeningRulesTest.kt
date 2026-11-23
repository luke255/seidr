package eu.bradshaw.seidr.data

import eu.bradshaw.seidr.data.api.NowPlayingEntry
import eu.bradshaw.seidr.data.api.Playlist
import eu.bradshaw.seidr.data.api.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningRulesTest {
    private val now = 1_000_000_000L

    private fun status(song: String?, state: String = DeviceStatus.PLAY, at: Long = now - 10_000, until: Long = now + 60_000, playlist: String? = null, name: String = "Fold") =
        DeviceStatus(name, "phone", song = song, playlist = playlist, state = state, position = 5_000, at = at, until = until)

    private fun list(owner: String, vararg devices: Pair<String, DeviceStatus>, id: String = "st-$owner") =
        Playlist(id, ListeningRules.NAME, comment = ListeningRules.encode(StatusList(devices = devices.toMap())), owner = owner, public = true)

    private fun np(user: String, song: String = "s1", minutesAgo: Int = 0, player: String = "Feishin", duration: Int = 300) =
        NowPlayingEntry(song, "Title", "Album", "Artist", albumId = "al-$song", duration = duration, username = user, minutesAgo = minutesAgo, playerName = player)

    @Test fun commentRoundTrips() {
        val l = StatusList(devices = mapOf("d1" to status("s1", playlist = "p1").copy(output = Output("speaker", "Kitchen"))))
        assertEquals(l, ListeningRules.parse(ListeningRules.encode(l)))
        assertNull(ListeningRules.parse("not json"))
        assertNull(ListeningRules.parse(""))
        assertNull(ListeningRules.parse(null))
    }

    @Test fun onlyTheExactNameIsAStatusList() {
        assertTrue(ListeningRules.isStatusList("Seidr: Listening"))
        assertTrue(ListeningRules.isStatusList(" Seidr: Listening "))
        assertFalse(ListeningRules.isStatusList("seidr: listening party"))
    }

    @Test fun playingAndPausedShowStoppedAndExpiredDont() {
        val lists = listOf(
            list("sam", "a" to status("s1"), "b" to status("s2", DeviceStatus.PAUSE)),
            list("alex", "c" to status("s3", DeviceStatus.STOP)),
            list("kim", "d" to status("s4", until = now - 1)),
        )
        val got = ListeningRules.listeners("luke", lists, emptyList(), now)
        assertEquals(listOf("s1" to false, "s2" to true), got.map { it.songId to it.paused })
        assertEquals("sam", got[0].user)
    }

    @Test fun leavesOutMe() {
        val got = ListeningRules.listeners("Luke", listOf(list("luke", "a" to status("s1"))), listOf(np("LUKE")), now)
        assertTrue(got.isEmpty())
    }

    @Test fun otherAppsComeFromTheServerUntilTheSongMustBeOver() {
        val got = ListeningRules.listeners("luke", emptyList(), listOf(np("sam", minutesAgo = 2), np("alex", "s9", minutesAgo = 29, duration = 439)), now)
        assertEquals(listOf("sam"), got.map { it.user })
        assertTrue(got[0].isApp)
        assertEquals("Feishin", got[0].device)
        assertNull(got[0].position)
    }

    @Test fun aStatusListCoversItsUsersSeidrReports() {
        // Sam stopped after the server heard of the song: the status says so.
        val stopped = list("sam", "a" to status("s1", DeviceStatus.STOP, at = now))
        assertTrue(ListeningRules.listeners("luke", listOf(stopped), listOf(np("sam", player = "seidr", minutesAgo = 1)), now).isEmpty())
        // Their other apps still show.
        assertEquals(1, ListeningRules.listeners("luke", listOf(stopped), listOf(np("sam", player = "Feishin")), now).size)
        // A Seiðr report newer than any status (a guest device playing their login) shows as an app.
        val old = list("sam", "a" to status("s1", DeviceStatus.STOP, at = now - 30 * 60_000L))
        val got = ListeningRules.listeners("luke", listOf(old), listOf(np("sam", player = "seidr", minutesAgo = 0)), now)
        assertEquals(1, got.size)
        assertTrue(got[0].isApp)
    }

    @Test fun twoListsFromOneUserBothCount() {
        val got = ListeningRules.listeners("luke", listOf(list("sam", "a" to status("s1"), id = "x"), list("sam", "b" to status("s2"), id = "y")), emptyList(), now)
        assertEquals(setOf("s1", "s2"), got.map { it.songId }.toSet())
    }

    @Test fun playingComesFirstThenNewest() {
        val lists = listOf(list("a", "1" to status("s1", DeviceStatus.PAUSE, at = now)), list("b", "2" to status("s2", at = now - 50_000)), list("c", "3" to status("s3", at = now - 1_000)))
        assertEquals(listOf("s3", "s2", "s1"), ListeningRules.listeners(null, lists, emptyList(), now).map { it.songId })
    }

    @Test fun untilCoversTheRestOfTheSongOrAPause() {
        assertEquals(now + 60_000 + ListeningRules.GRACE_MS, ListeningRules.until(DeviceStatus.PLAY, now, 120_000, 180_000))
        assertEquals(now + ListeningRules.PAUSED_MS, ListeningRules.until(DeviceStatus.PAUSE, now, 0, 180_000))
        assertEquals(now, ListeningRules.until(DeviceStatus.STOP, now, 0, 0))
    }

    @Test fun withDeviceReplacesMineAndDropsLongGoneOnes() {
        val old = StatusList(devices = mapOf(
            "me" to status("s1"),
            "other" to status("s2", until = now - 1),
            "ancient" to status("s3", until = now - ListeningRules.KEEP_MS - 1),
        ))
        val mine = status("s9")
        assertEquals(setOf("me", "other"), ListeningRules.withDevice(old, "me", mine, now).devices.keys)
        assertEquals(mine, ListeningRules.withDevice(old, "me", mine, now).devices["me"])
        assertEquals(setOf("other"), ListeningRules.withDevice(old, "me", null, now).devices.keys)
        assertEquals(setOf("me"), ListeningRules.withDevice(null, "me", mine, now).devices.keys)
    }

    @Test fun cardsArePlaylistsTheViewerCanSeeElseAlbums() {
        fun l(user: String, playlist: String?, album: String?) =
            Listener(user, "s-$user", Song("s-$user", albumId = album), "Fold", "phone", null, playlist, false, 0, now)
        val got = ListeningRules.contexts(listOf(l("a", "p1", "al1"), l("b", "p1", "al2"), l("c", "private", "al3"), l("d", null, null)), setOf("p1"))
        assertEquals(
            listOf(ListeningRules.Context.OfPlaylist("p1"), ListeningRules.Context.OfAlbum("al3"), ListeningRules.Context.OfSong("s-d")),
            got.map { it.first },
        )
        assertEquals(listOf("a", "b"), got[0].second.map { it.user })
    }

    @Test fun positionMovesOnlyWhilePlaying() {
        val playing = Listener("a", "s", null, "Fold", "phone", null, null, false, 5_000, now)
        assertEquals(15_000L, playing.positionAt(now + 10_000))
        assertEquals(5_000L, playing.copy(paused = true).positionAt(now + 10_000))
        assertNull(playing.copy(position = null).positionAt(now))
    }
}
