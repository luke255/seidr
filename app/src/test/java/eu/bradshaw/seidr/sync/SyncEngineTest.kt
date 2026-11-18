package eu.bradshaw.seidr.sync

import eu.bradshaw.seidr.data.db.DownloadState
import eu.bradshaw.seidr.data.db.OfflineSource
import eu.bradshaw.seidr.data.db.SourceType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end sync passes against a fake server and store. Each test in the
 * "edge-case table" section maps to one row of the table in the build brief.
 */
class SyncEngineTest {
    private lateinit var fake: FakeServer
    private lateinit var store: FakeStore
    private lateinit var files: FakeFiles
    private var playing = emptySet<String>()
    private var clock = 1_000_000L
    private lateinit var engine: SyncEngine

    @Before fun setUp() {
        fake = FakeServer()
        store = FakeStore()
        files = FakeFiles()
        engine = SyncEngine(fake.server, store, files, protectedIds = { playing }, now = { clock })
    }

    private fun enablePlaylist(id: String) {
        val sid = SourceType.sourceId(SourceType.PLAYLIST, id)
        store.sources[sid] = OfflineSource(sid, SourceType.PLAYLIST, id, id)
    }
    private fun enableAlbum(id: String) {
        val sid = SourceType.sourceId(SourceType.ALBUM, id)
        store.sources[sid] = OfflineSource(sid, SourceType.ALBUM, id, id)
    }

    private fun enableLoved() {
        store.sources[SourceType.STARRED_ID] = OfflineSource(SourceType.STARRED_ID, SourceType.STARRED, "starred", "Loved")
    }

    private suspend fun syncAndDownload(): SyncReport = engine.sync().also { store.completeAll() }
    private fun onDisk() = store.downloads.filterValues { it.state == DownloadState.DONE }.keys

    // --- Basic mirroring ---------------------------------------------------------

    @Test fun `enabling a playlist downloads all its tracks`() = runTest {
        fake.playlist("p", "a", "b", "c")
        enablePlaylist("p")
        val r = syncAndDownload()
        assertEquals(3, r.queued)
        assertEquals(setOf("a", "b", "c"), onDisk())
    }

    @Test fun `tracks added on the server download automatically`() = runTest {
        fake.playlist("p", "a")
        enablePlaylist("p")
        syncAndDownload()
        fake.edit("p") { add(track("b")) }
        syncAndDownload()
        assertEquals(setOf("a", "b"), onDisk())
    }

    @Test fun `tracks removed on the server are deleted`() = runTest {
        fake.playlist("p", "a", "b")
        enablePlaylist("p")
        syncAndDownload()
        fake.edit("p") { removeAll { it.id == "b" } }
        val r = syncAndDownload()
        assertEquals(listOf("b"), r.deleted)
        assertEquals(listOf("b"), files.deleted)
        assertEquals(setOf("a"), onDisk())
    }

    @Test fun `duplicate songs in a playlist are one claim and one file`() = runTest {
        fake.playlist("p", "a", "a", "b")
        enablePlaylist("p")
        val r = syncAndDownload()
        assertEquals(2, r.queued)
        fake.edit("p") { removeAt(0) } // one "a" left
        syncAndDownload()
        assertEquals(setOf("a", "b"), onDisk())
        assertTrue(files.deleted.isEmpty())
    }

    @Test fun `unchanged timestamp skips the full fetch`() = runTest {
        fake.playlist("p", "a")
        enablePlaylist("p")
        syncAndDownload()
        fake.calls.clear()
        val r = syncAndDownload()
        assertEquals(listOf("listing"), fake.calls)
        assertEquals(listOf("playlist:p"), r.unchanged)
    }

    @Test fun `a day-old fetch is redone even with the same timestamp`() = runTest {
        fake.playlist("p", "a")
        enablePlaylist("p")
        syncAndDownload()
        clock += SyncEngine.FULL_RECHECK_MS + 1
        fake.calls.clear()
        syncAndDownload()
        assertTrue("playlist:p" in fake.calls)
    }

    @Test fun `forced source is fetched even when unchanged`() = runTest {
        fake.playlist("p", "a")
        enablePlaylist("p")
        syncAndDownload()
        fake.calls.clear()
        engine.sync(force = setOf("playlist:p"))
        assertTrue("playlist:p" in fake.calls)
    }

    @Test fun `size or suffix change triggers a re-download`() = runTest {
        fake.playlist("p", "a", "b")
        enablePlaylist("p")
        syncAndDownload()
        fake.edit("p") {
            this[0] = track("a", size = 2000)       // re-tagged
            this[1] = track("b", suffix = "opus")   // replaced with another format
        }
        val r = engine.sync()
        assertEquals(2, r.redownload)
        assertEquals(setOf("a", "b"), files.deleted.toSet())
        assertEquals(DownloadState.QUEUED, store.downloads.getValue("a").state)
        assertEquals(2000, store.downloads.getValue("a").size)
        assertEquals("opus", store.downloads.getValue("b").suffix)
    }

    @Test fun `a kept file takes the server's new cover id without re-downloading`() = runTest {
        fake.playlist("p", "a")
        fake.edit("p") { this[0] = track("a").copy(coverArt = "al-x_1") }
        enablePlaylist("p")
        syncAndDownload()
        fake.edit("p") { this[0] = track("a").copy(coverArt = "al-x_2") }
        val r = engine.sync()
        assertEquals(0, r.redownload)
        assertEquals(DownloadState.DONE, store.downloads.getValue("a").state)
        assertEquals("al-x_2", store.downloads.getValue("a").coverArt)
    }

    @Test fun `a downloaded playlist's picture follows the listing`() = runTest {
        fake.playlist("p", "a")
        enablePlaylist("p")
        syncAndDownload()
        fake.playlists.getValue("p").coverArt = "pl-p_2"
        val r = engine.sync()
        assertEquals(listOf("playlist:p"), r.unchanged)
        assertEquals("pl-p_2", store.sources.getValue("playlist:p").coverArt)
    }

    @Test fun `turning download off deletes files nothing else claims`() = runTest {
        fake.playlist("p", "a", "b")
        fake.playlist("q", "b")
        enablePlaylist("p"); enablePlaylist("q")
        syncAndDownload()
        store.removeSource("playlist:p") // what the Download toggle does
        engine.sync()
        assertEquals(setOf("b"), onDisk())
        assertEquals(listOf("a"), files.deleted)
    }

    // --- Edge-case table ---------------------------------------------------------

    @Test fun `edge 1 - track removed from playlist but in an offline album stays`() = runTest {
        fake.playlist("p", "a", "b")
        fake.albums["al"] = mutableListOf(track("b"), track("c"))
        enablePlaylist("p"); enableAlbum("al")
        syncAndDownload()
        fake.edit("p") { removeAll { it.id == "b" } }
        val r = syncAndDownload()
        assertTrue(r.deleted.isEmpty())
        assertEquals(setOf("a", "b", "c"), onDisk())
    }

    @Test fun `edge 2 - playlist deleted on the server drops source, claims and unclaimed files`() = runTest {
        fake.playlist("p", "a", "b")
        fake.playlist("q", "b")
        enablePlaylist("p"); enablePlaylist("q")
        syncAndDownload()
        fake.playlists.remove("p")
        val r = syncAndDownload()
        assertEquals(listOf("playlist:p"), r.gone)
        assertFalse("playlist:p" in store.sources)
        assertEquals(listOf("a"), r.deleted)
        assertEquals(setOf("b"), onDisk())
    }

    @Test fun `edge 3 - track deleted from the library loses its claims and file`() = runTest {
        fake.playlist("p", "a", "b")
        fake.albums["al"] = mutableListOf(track("b"), track("c"))
        fake.playlist("p2", "b")
        fake.starred = mutableListOf(track("b"))
        enablePlaylist("p"); enablePlaylist("p2"); enableAlbum("al"); enableLoved()
        syncAndDownload()
        fake.deleteSong("b")
        val r = syncAndDownload()
        assertEquals(listOf("b"), r.deleted)
        assertEquals(setOf("a", "c"), onDisk())
    }

    @Test fun `edge 4 - removed track that is playing finishes, then gets deleted`() = runTest {
        fake.playlist("p", "a", "b")
        enablePlaylist("p")
        syncAndDownload()
        playing = setOf("b")
        fake.edit("p") { removeAll { it.id == "b" } }
        val r = syncAndDownload()
        assertEquals(listOf("b"), r.deferred)
        assertTrue(files.deleted.isEmpty())
        assertTrue("b" in onDisk())
        // Playback moves on; the sweep run on track change removes it.
        playing = setOf("a")
        val sweep = engine.sweep()
        assertEquals(listOf("b"), sweep.deleted)
        assertEquals(setOf("a"), onDisk())
    }

    @Test fun `edge 5 - claimed files are never evicted, whatever happens to downloads`() = runTest {
        // Storage-full itself is handled by the download worker pausing; the
        // engine's half of the rule is that nothing claimed is ever deleted.
        fake.playlist("p", "a", "b", "c")
        enablePlaylist("p")
        engine.sync()
        store.downloads["a"] = store.downloads.getValue("a").copy(state = DownloadState.DONE, path = "/a")
        store.downloads["b"] = store.downloads.getValue("b").copy(state = DownloadState.FAILED, error = "No space left")
        repeat(3) { engine.sync() }
        assertTrue(files.deleted.isEmpty())
        assertEquals(setOf("a", "b", "c"), store.downloads.keys)
        // Failed downloads are retried on the next full pass.
        assertEquals(DownloadState.QUEUED, store.downloads.getValue("b").state)
    }

    @Test fun `edge 6 - server unreachable deletes nothing`() = runTest {
        fake.playlist("p", "a", "b")
        fake.albums["al"] = mutableListOf(track("c"))
        fake.playlist("p2", "d")
        fake.starred = mutableListOf(track("e"))
        enablePlaylist("p"); enablePlaylist("p2"); enableAlbum("al"); enableLoved()
        syncAndDownload()
        fake.offline = true
        val r = syncAndDownload()
        assertEquals(4, r.failed.size)
        assertTrue(r.deleted.isEmpty())
        assertTrue(files.deleted.isEmpty())
        assertEquals(setOf("a", "b", "c", "d", "e"), onDisk())
        assertEquals(4, store.sources.size)
    }

    // --- The safety rule -----------------------------------------------------------

    @Test fun `truncated playlist response is not read as removals`() = runTest {
        fake.playlist("p", "a", "b", "c")
        enablePlaylist("p")
        syncAndDownload()
        fake.edit("p") { add(track("d")) }
        fake.truncateTo = 1
        val r = syncAndDownload()
        assertTrue(r.failed.containsKey("playlist:p"))
        assertTrue(files.deleted.isEmpty())
        assertEquals(setOf("a", "b", "c"), onDisk())
    }

    @Test fun `playlist missing from listing but still loadable is left alone`() = runTest {
        fake.playlist("p", "a")
        enablePlaylist("p")
        syncAndDownload()
        fake.hideFromListing = "p"
        val r = syncAndDownload()
        assertTrue(r.failed.containsKey("playlist:p"))
        assertTrue("playlist:p" in store.sources)
        assertEquals(setOf("a"), onDisk())
    }

    @Test fun `listing failure fails every playlist without deleting`() = runTest {
        fake.playlist("p", "a")
        enablePlaylist("p")
        syncAndDownload()
        fake.offline = true
        val r = engine.sync()
        assertTrue(r.failed.getValue("playlist:p").startsWith("playlist listing failed"))
        assertEquals(setOf("a"), onDisk())
    }

    @Test fun `a playlist emptied on the server is mirrored when both signals agree`() = runTest {
        fake.playlist("p", "a", "b")
        enablePlaylist("p")
        syncAndDownload()
        fake.edit("p") { clear() }
        val r = syncAndDownload()
        assertEquals(setOf("a", "b"), r.deleted.toSet())
        assertTrue(onDisk().isEmpty())
    }

    @Test fun `a single album not-found is not a deletion`() = runTest {
        fake.albums["al"] = mutableListOf(track("a"))
        enableAlbum("al")
        syncAndDownload()
        fake.flakyAlbumNotFound = 1
        val r = syncAndDownload()
        assertTrue(r.failed.containsKey("album:al"))
        assertEquals(setOf("a"), onDisk())
    }

    @Test fun `album confirmed gone is removed`() = runTest {
        fake.albums["al"] = mutableListOf(track("a"))
        enableAlbum("al")
        syncAndDownload()
        fake.albums.remove("al")
        val r = syncAndDownload()
        assertEquals(listOf("album:al"), r.gone)
        assertEquals(listOf("a"), r.deleted)
    }

    @Test fun `album returning no songs is not trusted`() = runTest {
        fake.albums["al"] = mutableListOf(track("a"))
        enableAlbum("al")
        syncAndDownload()
        fake.albums["al"]!!.clear()
        val r = syncAndDownload()
        assertTrue(r.failed.containsKey("album:al"))
        assertEquals(setOf("a"), onDisk())
    }

    @Test fun `one failing source doesn't stop the others`() = runTest {
        fake.playlist("p", "a")
        fake.albums["al"] = mutableListOf(track("b"))
        enablePlaylist("p"); enableAlbum("al")
        syncAndDownload()
        fake.albums["al"]!!.clear() // untrusted
        fake.edit("p") { add(track("c")) }
        val r = syncAndDownload()
        assertTrue(r.failed.containsKey("album:al"))
        assertEquals(setOf("a", "b", "c"), onDisk())
    }

    @Test fun `disabled sources are not synced`() = runTest {
        fake.playlist("p", "a")
        store.sources["playlist:p"] = OfflineSource("playlist:p", SourceType.PLAYLIST, "p", "p", enabled = false)
        val r = engine.sync()
        assertEquals(0, r.queued)
        assertTrue(fake.calls.isEmpty())
    }

    // --- Kid mode (TrackGate) ---------------------------------------------------

    private var gate: TrackGate? = null
    private var gateFails = false
    private fun kidEngine() = SyncEngine(fake.server, store, files, protectedIds = { playing }, now = { clock },
        gate = { if (gateFails) throw java.io.IOException("lists unreachable") else gate })

    @Test fun `a kid downloads only approved tracks`() = runTest {
        engine = kidEngine()
        gate = TrackGate(setOf("a", "c"))
        fake.playlist("p", "a", "b", "c")
        enablePlaylist("p")
        syncAndDownload()
        assertEquals(setOf("a", "c"), onDisk())
    }

    @Test fun `un-approving deletes the file even when the playlist is unchanged`() = runTest {
        engine = kidEngine()
        gate = TrackGate(setOf("a", "b"))
        fake.playlist("p", "a", "b")
        enablePlaylist("p")
        syncAndDownload()
        gate = TrackGate(setOf("a"))
        val r = syncAndDownload()
        assertEquals(listOf("b"), r.deleted)
        assertEquals(setOf("a"), onDisk())
    }

    @Test fun `approving downloads it even when the playlist is unchanged`() = runTest {
        engine = kidEngine()
        gate = TrackGate(setOf("a"))
        fake.playlist("p", "a", "b")
        enablePlaylist("p")
        syncAndDownload()
        gate = TrackGate(setOf("a", "b"))
        syncAndDownload()
        assertEquals(setOf("a", "b"), onDisk())
    }

    @Test fun `unreachable kids' lists change nothing`() = runTest {
        engine = kidEngine()
        gate = TrackGate(setOf("a", "b"))
        fake.playlist("p", "a", "b")
        enablePlaylist("p")
        syncAndDownload()
        gateFails = true
        val r = syncAndDownload()
        assertTrue(r.deleted.isEmpty())
        assertEquals(setOf("a", "b"), onDisk())
        assertTrue(r.failed.isNotEmpty())
    }

    @Test fun `an un-approved track that's playing goes once it's finished`() = runTest {
        engine = kidEngine()
        gate = TrackGate(setOf("a", "b"))
        fake.playlist("p", "a", "b")
        enablePlaylist("p")
        syncAndDownload()
        gate = TrackGate(setOf("a"))
        playing = setOf("b")
        syncAndDownload()
        assertEquals(setOf("a", "b"), onDisk())
        playing = emptySet()
        engine.sweep()
        assertEquals(setOf("a"), onDisk())
    }

    // --- Loved (starred songs) ---------------------------------------------------

    @Test fun `loved list empty once is not trusted`() = runTest {
        enableLoved()
        fake.starred = mutableListOf(track("a"), track("b"))
        syncAndDownload()
        fake.emptyStarredCalls = 1 // glitch: first call empty, confirmation call full
        val r = syncAndDownload()
        assertTrue(r.failed.containsKey(SourceType.STARRED_ID))
        assertEquals(setOf("a", "b"), onDisk())
    }

    @Test fun `loved list really emptied is mirrored after confirmation`() = runTest {
        enableLoved()
        fake.starred = mutableListOf(track("a"))
        syncAndDownload()
        fake.starred.clear()
        val r = syncAndDownload()
        assertEquals(listOf("a"), r.deleted)
    }

    @Test fun `loved changes are diffed every pass`() = runTest {
        enableLoved()
        fake.starred = mutableListOf(track("a"))
        syncAndDownload()
        fake.starred = mutableListOf(track("b"))
        val r = syncAndDownload()
        assertEquals(listOf("a"), r.deleted)
        assertEquals(setOf("b"), onDisk())
    }

    @Test fun `unloved track in a downloaded playlist stays`() = runTest {
        fake.playlist("p", "a")
        fake.starred = mutableListOf(track("a"), track("b"))
        enablePlaylist("p"); enableLoved()
        syncAndDownload()
        fake.starred.clear(); fake.starred += track("b")
        val r = syncAndDownload()
        assertTrue(r.deleted.isEmpty())
        assertEquals(setOf("a", "b"), onDisk())
    }
}
