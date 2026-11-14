package eu.bradshaw.seidr.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * When each playlist was last played from this phone (epoch ms). Subsonic has
 * no per-playlist play history, so this is local only: it starts empty on a
 * fresh install and isn't shared between devices.
 */
class PlaylistPlays(context: Context) {
    private val prefs = context.getSharedPreferences("playlist_plays", Context.MODE_PRIVATE)

    private val _lastPlayed = MutableStateFlow(prefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap())
    val lastPlayed: StateFlow<Map<String, Long>> = _lastPlayed.asStateFlow()

    fun record(playlistId: String) {
        val now = System.currentTimeMillis()
        prefs.edit().putLong(playlistId, now).apply()
        _lastPlayed.update { it + (playlistId to now) }
    }
}
