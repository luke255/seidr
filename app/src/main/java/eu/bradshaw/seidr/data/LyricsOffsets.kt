package eu.bradshaw.seidr.data

import android.content.Context
import android.util.Log
import eu.bradshaw.seidr.data.api.SubsonicApi
import eu.bradshaw.seidr.data.api.isNotFound
import eu.bradshaw.seidr.data.api.ok
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-song lyrics timing nudges: milliseconds added to the playback position
 * when matching synced lyrics, so positive shows lines sooner.
 *
 * Subsonic has no storage for app data, so each nudge is kept on the server as
 * a bookmark on the song (position 0, comment "Seidr lyrics offset: +650ms").
 * Bookmarks with no comment are plain resume points and may be replaced; ones
 * with any other comment belong to the user or another app and are never changed. Local prefs hold the current values; changes not yet on the
 * server are marked dirty and pushed on the next [sync].
 */
class LyricsOffsets(context: Context, private val api: () -> SubsonicApi, private val scope: CoroutineScope) {
    private val values = context.getSharedPreferences("lyrics_offsets", Context.MODE_PRIVATE)
    private val dirty = context.getSharedPreferences("lyrics_offsets_dirty", Context.MODE_PRIVATE)
    private val lock = Mutex()

    private val _version = MutableStateFlow(0)
    /** Bumped when a sync changes a value, so an open lyrics view re-reads it. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun get(songId: String): Long = values.getLong(songId, 0)

    fun set(songId: String, ms: Long) {
        synchronized(this) {
            if (ms == get(songId) && !dirty.contains(songId)) return
            write(songId, ms)
            dirty.edit().putBoolean(songId, true).apply()
        }
        scope.launch { sync() }
    }

    /** Pushes local changes, then takes everything else from the server. Never throws. */
    suspend fun sync() = lock.withLock {
        runCatching {
            val api = api()
            val marks = api.getBookmarks().ok().bookmarks?.bookmark.orEmpty()
            val ours = HashMap<String, Long>()
            val foreign = HashSet<String>()
            for (b in marks) {
                val id = b.entry?.id ?: continue
                val ms = parse(b.comment)
                // A blank comment is a plain resume point (youamp left plenty); fair game.
                if (ms != null) ours[id] = ms else if (!b.comment.isNullOrBlank()) foreign += id
            }

            for (id in dirty.all.keys) {
                val ms = get(id)
                when {
                    id in foreign -> Log.i(TAG, "song $id has someone else's bookmark; keeping its offset on this device only")
                    ms == 0L && id in ours -> try { api.deleteBookmark(id).ok() } catch (e: Exception) { if (!e.isNotFound) throw e }
                    ms != 0L && ours[id] != ms -> api.createBookmark(id, 0, format(ms)).ok()
                }
                if (ms == 0L) ours -= id else if (id !in foreign) ours[id] = ms
                synchronized(this) { if (get(id) == ms) dirty.edit().remove(id).apply() }
            }

            // The server is the truth for everything not changed here since.
            var changed = false
            synchronized(this) {
                val pending = dirty.all.keys
                for ((id, ms) in ours) if (id !in pending && get(id) != ms) { write(id, ms); changed = true }
                for (id in values.all.keys) if (id !in ours && id !in pending && id !in foreign) { write(id, 0); changed = true }
            }
            if (changed) _version.value++
        }.onFailure { Log.w(TAG, "sync failed: $it") }
        Unit
    }

    /** Sign-out: offsets belong to the account. */
    fun clear() {
        values.edit().clear().apply()
        dirty.edit().clear().apply()
        _version.value++
    }

    private fun write(songId: String, ms: Long) {
        values.edit().apply { if (ms == 0L) remove(songId) else putLong(songId, ms) }.apply()
    }

    companion object {
        private const val TAG = "LyricsOffsets"
        private val PATTERN = Regex("""^Seidr lyrics offset: ([+-]?\d+)ms$""")

        fun format(ms: Long) = "Seidr lyrics offset: %+dms".format(ms)
        fun parse(comment: String?): Long? = comment?.trim()?.let { PATTERN.matchEntire(it) }?.groupValues?.get(1)?.toLongOrNull()
    }
}
