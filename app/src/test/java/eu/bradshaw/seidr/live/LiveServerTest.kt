package eu.bradshaw.seidr.live

import eu.bradshaw.seidr.data.ServerConfig
import eu.bradshaw.seidr.data.ServerStatus
import eu.bradshaw.seidr.data.api.ResponseStore
import eu.bradshaw.seidr.data.api.SubsonicClient
import eu.bradshaw.seidr.data.api.ok
import eu.bradshaw.seidr.data.db.OfflineSource
import eu.bradshaw.seidr.data.db.PendingEdit
import eu.bradshaw.seidr.data.db.SourceType
import eu.bradshaw.seidr.sync.EditQueue
import eu.bradshaw.seidr.sync.EditResult
import eu.bradshaw.seidr.sync.FakeFiles
import eu.bradshaw.seidr.sync.FakeStore
import eu.bradshaw.seidr.sync.PlaylistEditor
import eu.bradshaw.seidr.sync.SubsonicSyncServer
import eu.bradshaw.seidr.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Properties

/**
 * Runs the real API client and sync engine against a live Navidrome. Skipped
 * unless ~/.config/seidr/test-server.properties exists (url, user, password,
 * and allowWrites=true for the playlist-editing test). SEIDR_TEST_SERVER
 * names another properties file, to try a different server.
 */
class LiveServerTest {
    private val props = Properties().apply {
        val f = System.getenv("SEIDR_TEST_SERVER")?.let(::File)
            ?: File(System.getProperty("user.home"), ".config/seidr/test-server.properties")
        if (f.isFile) f.inputStream().use { load(it) }
    }
    private lateinit var client: SubsonicClient
    private lateinit var config: ServerConfig

    @Before fun setUp() {
        assumeTrue("no live server configured", props.getProperty("url") != null)
        config = ServerConfig(props.getProperty("url"), props.getProperty("user"), props.getProperty("password"))
        client = clientFor(config)
    }

    private fun clientFor(c: ServerConfig) = SubsonicClient(
        { c }, "testsalt", OkHttpClient(),
        ResponseStore(Files.createTempDirectory("seidr-api").toFile()),
        ServerStatus(CoroutineScope(Dispatchers.Unconfined)),
    )

    @Test fun `ping and browse endpoints parse`() = runTest {
        val ping = client.strict.ping().ok()
        assertEquals("ok", ping.status)
        val playlists = client.api.getPlaylists().ok().playlists!!.playlist
        assertTrue(playlists.isNotEmpty())
        val artists = client.api.getArtists().ok().artists!!.index.flatMap { it.artist }
        assertTrue(artists.isNotEmpty())
        val artist = client.api.getArtist(artists.first().id).ok().artist!!
        assertTrue(artist.album.isNotEmpty())
        val album = client.api.getAlbum(artist.album.first().id).ok().album!!
        assertEquals(album.songCount, album.song.size)
        val recent = client.api.getAlbumList2("newest", 5).ok().albumList2!!.album
        assertTrue(recent.isNotEmpty())
        client.api.getStarred2().ok().starred2!!
        val search = client.api.search3(album.name.take(5)).ok().searchResult3!!
        assertTrue(search.album.isNotEmpty() || search.song.isNotEmpty())
        client.api.getTopSongs(artist.name, 5).ok()
        println("live: ${playlists.size} playlists, ${artists.size} artists, server ${ping.serverVersion}")
    }

    @Test fun `every playlist is complete according to the sync server`() = runTest {
        val sync = SubsonicSyncServer { client.strict }
        val listing = sync.playlistListing()
        for (p in listing) {
            val fetch = sync.playlist(p.id)
            assertEquals("${p.name} complete", fetch.songCount, fetch.tracks.size)
            fetch.tracks.forEach { t -> assertTrue("${t.id} has size", t.size > 0); assertTrue(t.suffix.isNotBlank()) }
        }
    }

    @Test fun `sync engine dry run against the live server`() = runTest {
        val store = FakeStore()
        val sync = SubsonicSyncServer { client.strict }
        val small = sync.playlistListing().filter { it.songCount in 1..60 }.minBy { it.songCount }
        val album = client.api.getAlbumList2("newest", 1).ok().albumList2!!.album.first()
        store.sources["playlist:${small.id}"] = OfflineSource("playlist:${small.id}", SourceType.PLAYLIST, small.id, small.name)
        store.sources["album:${album.id}"] = OfflineSource("album:${album.id}", SourceType.ALBUM, album.id, album.name)
        store.sources[SourceType.STARRED_ID] = OfflineSource(SourceType.STARRED_ID, SourceType.STARRED, "starred", "Loved")
        val engine = SyncEngine(sync, store, FakeFiles())
        val r1 = engine.sync()
        assertTrue("no failures: ${r1.failed}", r1.failed.isEmpty())
        assertEquals(store.claims.map { it.first }.toSet().size, r1.queued)
        store.completeAll()
        val r2 = engine.sync()
        // Smart playlists bump `changed` on every read, so only regular ones are skipped.
        assertTrue("playlist:${small.id}" in r2.unchanged + r2.ok)
        assertEquals(0, r2.queued)
        assertTrue(r2.deleted.isEmpty())
        println("live sync: ${small.name} (${small.songCount}) + ${album.name}: queued ${r1.queued}, second pass ${r2}")
    }

    @Test fun `downloads support resume and art loads`() = runTest {
        val sync = SubsonicSyncServer { client.strict }
        val song = sync.playlistListing().first { it.songCount > 0 }.let { sync.playlist(it.id).tracks.first() }
        val http = OkHttpClient()
        http.newCall(Request.Builder().url(client.downloadUrl(song.id)!!).header("Range", "bytes=100-199").build()).execute().use {
            assertEquals("download Range", 206, it.code)
            assertEquals(100, it.body.bytes().size)
        }
        http.newCall(Request.Builder().url(client.streamUrl(song.id)!!).header("Range", "bytes=0-99").build()).execute().use {
            assertTrue("stream ok ${it.code}", it.code == 206 || it.code == 200)
        }
        val cover = song.coverArt ?: return@runTest
        http.newCall(Request.Builder().url(client.coverArtUrl(cover, 300)!!).build()).execute().use {
            assertEquals(200, it.code)
            assertTrue(it.header("Content-Type").orEmpty().startsWith("image/"))
        }
        // Stable cover URLs, so image caches can key on them.
        assertEquals(client.coverArtUrl(cover, 300), client.coverArtUrl(cover, 300))
    }

    @Test fun `offline fallback serves the last response`() = runTest {
        val store = ResponseStore(Files.createTempDirectory("seidr-api").toFile())
        val status = ServerStatus(CoroutineScope(Dispatchers.Unconfined))
        var target: ServerConfig = config
        val c = SubsonicClient({ target }, "s", OkHttpClient(), store, status)
        val online = c.api.getPlaylists().ok().playlists!!.playlist
        target = config.copy(url = "http://127.0.0.1:9") // nothing listens here
        val offline = c.api.getPlaylists().ok().playlists!!.playlist
        assertEquals(online.map { it.id }, offline.map { it.id })
        assertTrue(!status.reachable.value)
        // The strict client must fail instead.
        assertTrue(runCatching { c.strict.getPlaylists().ok() }.isFailure)
    }

    @Test fun `playlist editing round trip on a scratch playlist`() = runTest {
        assumeTrue("writes not allowed", props.getProperty("allowWrites") == "true")
        val songs = SubsonicSyncServer { client.strict }.let { s ->
            s.playlistListing().first { it.songCount >= 6 }.let { s.playlist(it.id).tracks.map { t -> t.id }.distinct().take(5) }
        }
        val queue = object : EditQueue {
            val list = mutableListOf<PendingEdit>()
            var next = 1L
            override suspend fun insertEdit(edit: PendingEdit) { list += edit.copy(id = next++) }
            override suspend fun edits() = list.toList()
            override suspend fun deleteEdit(id: Long) { list.removeAll { it.id == id } }
        }
        val synced = mutableListOf<String>()
        val editor = PlaylistEditor({ client.strict }, queue) { synced += it }
        val id = editor.create("Seiðr integration test (safe to delete)", songs.take(3))
        assertNotNull(id)
        id!!
        suspend fun fresh() = client.strict.getPlaylist(id).ok().playlist!!.entry.map { it.id }
        try {
            assertEquals(songs.take(3), fresh())
            assertEquals(EditResult.Applied, editor.add(id, listOf(songs[3])))
            assertEquals(EditResult.Applied, editor.add(id, listOf(songs[1]))) // a duplicate
            assertEquals(listOf(songs[0], songs[1], songs[2], songs[3], songs[1]), fresh())
            // Remove the second copy of songs[1], as seen at index 4.
            assertEquals(EditResult.Applied, editor.remove(id, songs[1], 4))
            assertEquals(listOf(songs[0], songs[1], songs[2], songs[3]), fresh())
            // Reorder against what we "saw".
            val seen = fresh()
            assertEquals(EditResult.Applied, editor.reorder(id, seen, seen.reversed()))
            assertEquals(seen.reversed(), fresh())
            // A stale view must be refused, not applied.
            assertEquals(EditResult.Conflict, editor.reorder(id, seen, seen))
            assertEquals(seen.reversed(), fresh())
            assertEquals(EditResult.Applied, editor.rename(id, "Seiðr integration test (renamed)"))
            assertEquals("Seiðr integration test (renamed)", client.strict.getPlaylist(id).ok().playlist!!.name)

            // Offline: edits are queued as intents...
            val offlineEditor = PlaylistEditor({ clientFor(config.copy(url = "http://127.0.0.1:9")).strict }, queue) {}
            assertEquals(EditResult.Queued, offlineEditor.add(id, listOf(songs[4])))
            assertEquals(EditResult.Queued, offlineEditor.remove(id, songs[0], 3))
            assertEquals(2, queue.list.size)
            // ...meanwhile someone edits on the web UI (song 3 moved to the end)...
            val before = fresh()
            client.strict.replacePlaylistSongs(id, before.filter { it != songs[3] } + songs[3]).ok()
            // ...and replay resolves by song id against a fresh fetch.
            editor.replayPending()
            assertTrue(queue.list.isEmpty())
            val after = fresh()
            assertTrue(songs[4] in after)
            assertTrue(songs[0] !in after)
            assertEquals(4, after.size)
            assertTrue(synced.all { it == id })
        } finally {
            client.strict.deletePlaylist(id).ok()
        }
    }
}
