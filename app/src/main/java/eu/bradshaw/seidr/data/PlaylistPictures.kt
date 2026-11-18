package eu.bradshaw.seidr.data

import android.content.Context
import android.util.Log
import eu.bradshaw.seidr.data.api.SubsonicClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Playlist pictures (Navidrome 0.61+): which playlists have one of their own,
 * and setting or removing one. Subsonic can't tell a chosen picture from the
 * server's mosaic, so this asks Navidrome's own API, again whenever a
 * playlist's cover id moves (a new picture gives it a new one). Kept per
 * login, so it holds offline.
 */
class PlaylistPictures(
    context: Context,
    private val api: NavidromeApi,
    private val login: () -> ServerConfig?,
    private val library: Library,
    private val kids: Kids,
    scope: CoroutineScope,
    /** Every listing fetched here, or null when the server turns out not to be Navidrome (smart playlists use it). */
    private val onListing: (List<NdPlaylist>?) -> Unit = {},
) {
    private val prefs = context.getSharedPreferences("playlist_pictures", Context.MODE_PRIVATE)

    private fun key() = login()?.let { SubsonicClient.md5("${it.url}|${it.username.trim().lowercase()}") }

    private val _ids = MutableStateFlow(stored())
    /** Playlists with a picture of their own: their header shows it instead of a mosaic. */
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    private val _supported = MutableStateFlow(prefs.getBoolean("supported", false) && prefs.getString("key", null) == key())
    /** Whether this server takes playlist pictures. */
    val supported: StateFlow<Boolean> = _supported.asStateFlow()

    private fun stored(): Set<String> =
        if (prefs.getString("key", null) == key()) prefs.getStringSet("ids", null).orEmpty() else emptySet()

    init {
        scope.launch {
            library.allPlaylists.filterNotNull()
                .map { list -> list.associate { it.id to it.coverArt } }
                .distinctUntilChanged()
                .collect { runCatching { refresh() }.onFailure { Log.i(TAG, "refresh failed: $it") } }
        }
    }

    private suspend fun refresh() {
        val k = key() ?: return
        val found = try {
            val (list, supported) = api.playlists()
            onListing(list)
            if (supported) list.filter { it.hasPicture }.map { it.id }.toSet() else null
        } catch (e: NotNavidromeException) {
            onListing(null)
            null
        }
        val ids = found.orEmpty()
        if (key() != k) return
        _ids.value = ids
        _supported.value = found != null
        prefs.edit().putString("key", k).putStringSet("ids", ids).putBoolean("supported", found != null).apply()
    }

    /** Makes [jpeg] [playlistId]'s picture. Throws [IOException] if it can't. */
    suspend fun set(playlistId: String, jpeg: ByteArray) {
        api.setPlaylistPicture(playlistId, jpeg)
        changed(playlistId, true)
    }

    /** Takes [playlistId]'s own picture off, so the mosaic shows again. Throws [IOException] if it can't. */
    suspend fun remove(playlistId: String) {
        api.removePlaylistPicture(playlistId)
        changed(playlistId, false)
    }

    private suspend fun changed(playlistId: String, has: Boolean) {
        _ids.value = if (has) _ids.value + playlistId else _ids.value - playlistId
        prefs.edit().putStringSet("ids", _ids.value).apply()
        // A kid's own pictures count once the rules have heard of them.
        if (kids.isKid) runCatching { kids.refresh() }
        // Its cover id moves with the picture: fetch it, so every screen and download follows.
        runCatching { library.refreshPlaylists() }
    }

    companion object {
        private const val TAG = "PlaylistPictures"
    }
}
