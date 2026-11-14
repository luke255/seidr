package eu.bradshaw.seidr.data

import android.content.Context
import eu.bradshaw.seidr.data.api.Album
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Albums played from their own page (or from the car), newest first, for the
 * car's Recent tab. A song played from search, a playlist or Liked Songs
 * doesn't count. Local only, like [PlaylistPlays]; keeps enough to show the
 * album without a server round trip.
 */
class AlbumPlays(context: Context) {
    @Serializable
    data class Play(val id: String, val name: String, val artist: String, val coverArt: String?, val at: Long)

    private val prefs = context.getSharedPreferences("album_plays", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var plays: List<Play> = prefs.getString(KEY, null)
        ?.let { runCatching { json.decodeFromString<List<Play>>(it) }.getOrNull() }
        .orEmpty()

    fun recent(): List<Play> = plays

    @Synchronized
    fun record(album: Album) {
        val play = Play(album.id, album.name, album.artistLine, album.coverArt, System.currentTimeMillis())
        plays = (listOf(play) + plays.filter { it.id != album.id }).take(MAX)
        prefs.edit().putString(KEY, json.encodeToString(plays)).apply()
    }

    private companion object {
        const val KEY = "plays"
        const val MAX = 50
    }
}
