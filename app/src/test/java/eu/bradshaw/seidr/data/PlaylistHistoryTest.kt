package eu.bradshaw.seidr.data

import eu.bradshaw.seidr.data.api.Playlist
import eu.bradshaw.seidr.data.api.Song
import eu.bradshaw.seidr.data.db.HistoryDao
import eu.bradshaw.seidr.data.db.PlaylistVersion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The DAO's queries, over a list (newest is highest id). */
private class FakeHistoryDao : HistoryDao {
    val rows = mutableListOf<PlaylistVersion>()
    private var next = 1L
    override suspend fun insert(version: PlaylistVersion) = next++.also { rows += version.copy(id = it) }
    override suspend fun newest(playlistId: String) = rows.filter { it.playlistId == playlistId }.maxByOrNull { it.id }
    override fun versions(playlistId: String): Flow<List<PlaylistVersion>> = flowOf(rows.filter { it.playlistId == playlistId }.sortedByDescending { it.id })
    override suspend fun playlistIds() = rows.map { it.playlistId }.distinct()
    override fun deleted(): Flow<List<PlaylistVersion>> = flowOf(rows.filter { it.deletedAt != null })
    override suspend fun setDeleted(versionId: Long, at: Long?) {
        rows.replaceAll { if (it.id == versionId) it.copy(deletedAt = at) else it }
    }
    override suspend fun keepNewest(playlistId: String, keep: Int) {
        val keepIds = rows.filter { it.playlistId == playlistId }.sortedByDescending { it.id }.take(keep).map { it.id }.toSet()
        rows.removeAll { it.playlistId == playlistId && it.id !in keepIds }
    }
    override suspend fun dropOlderThan(before: Long) {
        val newest = rows.groupBy { it.playlistId }.values.map { l -> l.maxOf { it.id } }.toSet()
        rows.removeAll { it.savedAt < before && it.id !in newest }
    }
    override suspend fun dropDeletedBefore(before: Long) {
        val gone = rows.filter { (it.deletedAt ?: Long.MAX_VALUE) < before }.map { it.playlistId }.toSet()
        rows.removeAll { it.playlistId in gone }
    }
    override suspend fun clear() = rows.clear()
    override suspend fun move(from: String, to: String) {
        rows.replaceAll { if (it.playlistId == from) it.copy(playlistId = to, deletedAt = null) else it }
    }
}

class PlaylistHistoryTest {
    private val dao = FakeHistoryDao()
    private var clock = 1_000L
    private val history = PlaylistHistory(dao) { clock }

    private fun pl(vararg ids: String, name: String = "Mix", id: String = "p", readonly: Boolean = false) =
        Playlist(id, name = name, readonly = readonly, entry = ids.map { Song(it) })

    @Test fun keepsEachChangeOnce() = runTest {
        history.seen(pl("a", "b"))
        history.seen(pl("a", "b"))
        history.seen(pl("a"))
        history.seen(pl("a", name = "Renamed"))
        assertEquals(listOf("a\nb", "a", "a"), dao.rows.map { it.songIds })
    }

    private fun rules(year: Int) = kotlinx.serialization.json.Json.parseToJsonElement(
        """{"all": [{"is": {"year": $year}}], "sort": "+title"}""",
    ) as kotlinx.serialization.json.JsonObject

    @Test fun keepsEachChangeOfASmartPlaylistsRules() = runTest {
        history.seenRules("s", "Smart", rules(2000))
        // The same rules, keys in another order, aren't a change.
        history.seenRules("s", "Smart", kotlinx.serialization.json.Json.parseToJsonElement(
            """{"sort": "+title", "all": [{"is": {"year": 2000}}]}""",
        ) as kotlinx.serialization.json.JsonObject)
        history.seenRules("s", "Smart", rules(2001))
        history.seenRules("s", "Renamed", rules(2001))
        assertEquals(3, dao.rows.size)
        assertEquals(rules(2000), PlaylistHistory.parseRules(dao.rows[0].rules!!))
        assertEquals(listOf("", "", ""), dao.rows.map { it.songIds })
        // A smart playlist's song lists never go in.
        history.seen(pl("a", id = "s", readonly = true))
        assertEquals(3, dao.rows.size)
        // Nor do the app's own lists' rules.
        history.seenRules("b", "Bad: Songs", rules(1))
        assertEquals(3, dao.rows.size)
    }

    @Test fun leavesOutSmartAndAppLists() = runTest {
        history.seen(pl("a", readonly = true))
        history.seen(pl("a", name = "Bad: Songs"))
        assertEquals(0, dao.rows.size)
    }

    @Test fun missingFromAListingIsDeletedAndBackIsNot() = runTest {
        history.seen(pl("a"))
        history.listed(emptyList())
        assertEquals(1_000L, dao.rows.single().deletedAt)
        history.listed(listOf(pl("a")))
        assertNull(dao.rows.single().deletedAt)
    }

    @Test fun seenAgainUndeletes() = runTest {
        history.seen(pl("a"))
        history.deleted("p")
        history.seen(pl("a"))
        assertNull(dao.rows.single().deletedAt)
    }

    @Test fun oldVersionsGoButTheNewestStays() = runTest {
        history.seen(pl("a"))
        clock += PlaylistHistory.MAX_AGE_MS + 1
        history.seen(pl("a", "b"))
        assertEquals(listOf("a\nb"), dao.rows.map { it.songIds })
        clock += PlaylistHistory.MAX_AGE_MS + 1
        history.seen(pl("a", "b"))
        assertEquals(1, dao.rows.size)
    }

    @Test fun deletedPlaylistsGoAfterTheirTime() = runTest {
        history.seen(pl("a"))
        history.deleted("p")
        clock += PlaylistHistory.MAX_AGE_MS + 1
        history.listed(emptyList())
        assertEquals(0, dao.rows.size)
    }

    @Test fun restoredTakesItsHistoryAndIsNoLongerDeleted() = runTest {
        history.seen(pl("a"))
        history.seen(pl("a", "b"))
        history.deleted("p")
        history.restored("p", "q")
        assertEquals(listOf("q", "q"), dao.rows.map { it.playlistId })
        assertNull(dao.rows.last().deletedAt)
    }

    @Test fun diffCountsDuplicates() {
        assertEquals(2 to 1, PlaylistHistory.diff(listOf("a", "a", "b"), listOf("a", "c")))
    }
}
