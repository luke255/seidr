package eu.bradshaw.seidr.playback

import android.util.Log
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.data.api.ok
import eu.bradshaw.seidr.data.db.PendingScrobble
import java.io.IOException

/** Process-wide facts about playback that non-UI code needs (the sync engine, mostly). */
object NowPlaying {
    /** The song the player is on; its file must not be deleted until playback moves on. */
    @Volatile var currentSongId: String? = null

    /** Reports a play to the server, or (with [keepForLater]) keeps it for later if the server is out of reach. */
    suspend fun scrobble(graph: Graph, songId: String, timeMs: Long, keepForLater: Boolean = true) {
        try {
            graph.client.strict.scrobble(songId, timeMs, true).ok()
        } catch (e: IOException) {
            if (keepForLater) graph.db.offline().insertScrobble(PendingScrobble(songId = songId, time = timeMs))
        } catch (e: Exception) {
            Log.w("Scrobble", "rejected: $e")
        }
    }

    /** Tells the server what's playing now, unless the user has opted out of sharing it (`Listening`). */
    suspend fun nowPlaying(graph: Graph, songId: String) {
        if (!graph.listening.reportsNowPlaying()) return
        runCatching { graph.client.strict.scrobble(songId, System.currentTimeMillis(), false).ok() }
    }

    suspend fun flushScrobbles(graph: Graph) {
        val dao = graph.db.offline()
        for (s in dao.scrobbles()) {
            try {
                graph.client.strict.scrobble(s.songId, s.time, true).ok()
                dao.deleteScrobble(s.id)
            } catch (e: IOException) {
                return
            } catch (e: Exception) {
                dao.deleteScrobble(s.id)
            }
        }
    }
}
