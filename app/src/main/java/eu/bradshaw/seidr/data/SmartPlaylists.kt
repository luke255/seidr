package eu.bradshaw.seidr.data

import android.content.Context
import android.util.Log
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.sync.EditResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.IOException

/**
 * Smart playlists' rules, through Navidrome's own API (Subsonic can't see
 * them). The server is the truth: every save checks the rules are still what
 * the editor started from, and every set of rules seen goes into
 * [PlaylistHistory] first, so an edit can always be taken back.
 */
class SmartPlaylists(
    context: Context,
    private val api: NavidromeApi,
    private val history: PlaylistHistory,
    private val text: (Int) -> String,
    /** After any change: the playlist reloads everywhere, and a downloaded one syncs. */
    private val afterEdit: (playlistId: String) -> Unit,
    /** A playlist this device made: Recent puts it first. */
    private val onCreated: (playlistId: String) -> Unit,
) {
    private val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)
    private val _available = MutableStateFlow(prefs.getBoolean(KEY, false))
    /** The server is Navidrome, so smart playlists can be made here. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    /** Smart playlists seen in the last listing (anyone's): Subsonic marks others' ordinary playlists read-only too. */
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    /** A listing from Navidrome's own API (null: the server isn't Navidrome). Notes what it shows. */
    suspend fun listed(list: List<NdPlaylist>?, me: String?) {
        setAvailable(list != null)
        _ids.value = list.orEmpty().filter { it.rules != null }.mapTo(HashSet()) { it.id }
        list.orEmpty().forEach { p ->
            if (p.rules != null && (me == null || p.owner.equals(me, ignoreCase = true))) history.seenRules(p.id, p.name, p.rules)
        }
    }

    private fun setAvailable(on: Boolean) {
        if (_available.value == on) return
        _available.value = on
        prefs.edit().putBoolean(KEY, on).apply()
    }

    /** Playlist [id] fresh from the server, with its rules (noted in its history). */
    suspend fun load(id: String): NdPlaylist = api.playlist(id).also { p ->
        setAvailable(true)
        if (p.rules != null) _ids.value += p.id
        p.rules?.let { history.seenRules(p.id, p.name, it) }
    }

    /** What a save wrote: its [result], and on success the rules as the server now has them (for Undo). */
    data class Saved(val result: EditResult, val rules: JsonObject? = null, val name: String? = null)

    /**
     * Saves [rules] (and [name], if given) to [id], but only if its rules are
     * still [seen]: if anyone changed them since, it's a [EditResult.Conflict]
     * and nothing is written.
     */
    suspend fun save(id: String, seen: JsonObject, rules: JsonObject, name: String? = null): Saved = attempt {
        val now = load(id)
        if (now.rules != seen) return@attempt Saved(EditResult.Conflict)
        write(id, rules, name)
    }

    /** Puts [rules] back on [id] (Earlier versions). Whatever it has now is noted in its history first. */
    suspend fun restore(id: String, rules: JsonObject, name: String? = null): Saved = attempt {
        load(id).rules ?: throw NavidromeRefusedException(400)
        write(id, rules, name)
    }

    private suspend fun write(id: String, rules: JsonObject, name: String?): Saved {
        api.updatePlaylist(id, buildJsonObject {
            put("rules", rules)
            if (name != null) put("name", JsonPrimitive(name))
        })
        val after = load(id)
        afterEdit(id)
        if (after.rules == null) throw IOException("rules didn't stick")
        return Saved(EditResult.Applied, after.rules, after.name)
    }

    private suspend fun attempt(block: suspend () -> Saved): Saved = try {
        block()
    } catch (e: NotNavidromeException) {
        setAvailable(false)
        Saved(EditResult.Failed(text(R.string.smart_not_navidrome)))
    } catch (e: NavidromeRefusedException) {
        Saved(EditResult.Failed(text(R.string.smart_refused).format(e.code)))
    } catch (e: IOException) {
        Log.w(TAG, "save failed: $e")
        Saved(EditResult.Failed(text(R.string.sync_cant_reach)))
    }

    /** A new smart playlist, private, named [name], with [rules]. Its id, or null if it couldn't be made. */
    suspend fun create(name: String, rules: JsonObject): String? = try {
        api.createPlaylist(buildJsonObject {
            put("name", JsonPrimitive(name))
            put("comment", JsonPrimitive(""))
            put("public", JsonPrimitive(false))
            put("rules", rules)
        }).also { id ->
            // Make sure it's the smart playlist asked for, not something the server read otherwise.
            if (api.playlist(id).rules == null) {
                runCatching { api.deletePlaylist(id) }
                throw IOException("created without rules")
            }
            setAvailable(true)
            _ids.value += id
            history.seenRules(id, name, rules)
            onCreated(id)
        }
    } catch (e: IOException) {
        Log.w(TAG, "create failed: $e")
        null
    }

    private companion object {
        const val TAG = "SmartPlaylists"
        const val KEY = "smart_playlists"
    }
}
