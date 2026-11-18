package eu.bradshaw.seidr.data

import eu.bradshaw.seidr.data.api.Playlist
import eu.bradshaw.seidr.data.db.HistoryDao
import eu.bradshaw.seidr.data.db.PlaylistVersion
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.sync.withLock

/**
 * A safety net for the user's playlists, on this device. Each time a full
 * fetch shows one of their playlists changed (from any app, or the server),
 * the new state is kept; the earlier ones, and a deleted playlist's last, can
 * be saved as a copy. Nothing here ever writes to the playlist itself.
 *
 * Fed by `Library` (every playlist fetch, and Wi-Fi warming fetches them all),
 * so it only knows states this device saw. A smart playlist's songs are left
 * out (its rules make them); its rules are kept instead ([seenRules], fed by
 * `SmartPlaylists`). So are the "Bad:" and kids' lists the app keeps.
 */
class PlaylistHistory(private val dao: HistoryDao, private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Mutex()

    /** A full fetch of [p], one of this login's own. */
    suspend fun seen(p: Playlist) = lock.withLock {
        if (!keeps(p)) return@withLock
        val ids = p.entry.joinToString("\n") { it.id }
        val last = dao.newest(p.id)
        if (last != null && last.songIds == ids && last.name == p.name) {
            if (last.deletedAt != null) dao.setDeleted(last.id, null)
            return@withLock
        }
        dao.insert(PlaylistVersion(playlistId = p.id, name = p.name, songIds = ids, savedAt = now()))
        dao.keepNewest(p.id, KEEP)
        dao.dropOlderThan(now() - MAX_AGE_MS)
    }

    /** Smart playlist [playlistId], named [name], has [rules] (Navidrome's JSON) on the server. */
    suspend fun seenRules(playlistId: String, name: String, rules: JsonObject) = lock.withLock {
        if (BadReports.isBadPlaylist(name) || KidRules.isKidsList(name)) return@withLock
        val last = dao.newest(playlistId)
        if (last != null && last.name == name && last.rules?.let(::parseRules) == rules) {
            if (last.deletedAt != null) dao.setDeleted(last.id, null)
            return@withLock
        }
        dao.insert(PlaylistVersion(playlistId = playlistId, name = name, songIds = "", savedAt = now(), rules = rules.toString()))
        dao.keepNewest(playlistId, KEEP)
        dao.dropOlderThan(now() - MAX_AGE_MS)
    }

    /**
     * A listing of every playlist the login can see. One with history that isn't
     * in it has been deleted; one that's back wasn't.
     */
    suspend fun listed(playlists: List<Playlist>) = lock.withLock {
        val present = playlists.mapTo(HashSet()) { it.id }
        for (id in dao.playlistIds()) {
            val newest = dao.newest(id) ?: continue
            val gone = id !in present
            if (gone && newest.deletedAt == null) dao.setDeleted(newest.id, now())
            else if (!gone && newest.deletedAt != null) dao.setDeleted(newest.id, null)
        }
        dao.dropDeletedBefore(now() - MAX_AGE_MS)
    }

    /** This app just deleted [playlistId]. */
    suspend fun deleted(playlistId: String) = lock.withLock {
        dao.newest(playlistId)?.let { if (it.deletedAt == null) dao.setDeleted(it.id, now()) }
    }

    /** Deleted playlist [from] was brought back as a new playlist, [to]: it's no longer deleted, and keeps its history. */
    suspend fun restored(from: String, to: String) = lock.withLock { dao.move(from, to) }

    /** Signed in as someone else: the history was the last login's. */
    suspend fun clear() = lock.withLock { dao.clear() }

    fun versions(playlistId: String) = dao.versions(playlistId)
    fun deleted() = dao.deleted()

    companion object {
        const val KEEP = 30
        const val MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000

        /** A version's rules, or null if it has none (or they no longer read). */
        fun parseRules(s: String): JsonObject? = runCatching { Json.parseToJsonElement(s) as? JsonObject }.getOrNull()

        fun keeps(p: Playlist) = !p.readonly && !BadReports.isBadPlaylist(p.name) && !KidRules.isKidsList(p.name)

        /** How [version] differs from [now]: songs it has that [now] lacks, and songs [now] has that it lacks. */
        fun diff(version: List<String>, now: List<String>): Pair<Int, Int> {
            fun counts(l: List<String>) = l.groupingBy { it }.eachCount()
            val v = counts(version)
            val n = counts(now)
            val missing = v.entries.sumOf { (id, c) -> (c - (n[id] ?: 0)).coerceAtLeast(0) }
            val added = n.entries.sumOf { (id, c) -> (c - (v[id] ?: 0)).coerceAtLeast(0) }
            return missing to added
        }
    }
}
