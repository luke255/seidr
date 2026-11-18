package eu.bradshaw.seidr.sync

import android.util.Log
import eu.bradshaw.seidr.data.Library
import eu.bradshaw.seidr.data.api.SubsonicClient
import eu.bradshaw.seidr.data.api.SubsonicException
import eu.bradshaw.seidr.data.db.Download
import eu.bradshaw.seidr.data.db.DownloadState
import eu.bradshaw.seidr.data.db.OfflineDao
import kotlinx.coroutines.CancellationException
import okhttp3.Request
import retrofit2.HttpException
import java.io.File
import java.io.IOException

/**
 * What a downloaded track needs besides its file to be whole offline: its
 * cover and its lyrics. Covers are saved beside the music ([MusicFiles]);
 * lyrics are kept as the server's last-seen reply, which is where Now Playing
 * looks when the server is out of reach.
 *
 * Downloads fill them in as each file lands; [fill] catches up on the rest
 * (older downloads, and covers whose id moved on the server).
 */
class Companions(
    private val files: MusicFiles,
    private val client: SubsonicClient,
    private val library: Library,
    private val dao: OfflineDao,
) {
    /** Set when the server answers lyrics requests with errors: don't keep asking this run of the app. */
    @Volatile private var lyricsUnsupported = false

    private suspend fun done() = dao.downloads().filter { it.state == DownloadState.DONE }

    /**
     * Every cover offline mode should have: each finished download's, its album's
     * when the song has its own art (album pages ask for the album's), and each
     * downloaded playlist's and album's picture.
     */
    private suspend fun wantedCovers(rows: List<Download>): Set<String> = buildSet {
        for (row in rows) {
            row.coverArt?.let(::add)
            albumCoverIdFor(row.coverArt, row.albumId)?.let(::add)
        }
        for (source in dao.sources()) if (source.enabled) source.coverArt?.let(::add)
    }

    /** Missing, or (for an id that doesn't change with the picture) old enough to check again. */
    private fun coverDue(coverId: String): Boolean {
        val f = files.coverFile(coverId)
        if (!f.isFile || f.length() == 0L) return true
        return !isVersionedCoverId(coverId) && System.currentTimeMillis() - f.lastModified() > REFRESH_MS
    }

    /** Whether any download lacks its cover or lyrics. Reads only local files. */
    suspend fun missing(): Boolean {
        val rows = done()
        return wantedCovers(rows).any(::coverDue) || rows.any(::lyricsMissing)
    }

    private fun lyricsMissing(row: Download) = !lyricsUnsupported &&
        !client.hasCached("getLyricsBySongId.view", "id" to row.trackId) &&
        !client.hasCached("getLyrics.view", "artist" to row.artist, "title" to row.title)

    /** Fetches [row]'s cover and lyrics if they're missing. Throws [IOException] when the server can't be reached. */
    suspend fun fillFor(row: Download) {
        for (cover in listOfNotNull(row.coverArt, albumCoverIdFor(row.coverArt, row.albumId))) {
            if (coverDue(cover)) fetchCover(cover)
        }
        if (lyricsMissing(row)) fetchLyrics(row)
    }

    /**
     * Fills in every finished download's cover and lyrics (at most [LYRICS_PER_RUN]
     * lyrics, the rest next time), then deletes covers no download uses.
     * Stops quietly when the server can't be reached.
     */
    suspend fun fill() {
        val all = dao.downloads()
        val rows = all.filter { it.state == DownloadState.DONE }
        val covers = wantedCovers(rows)
        try {
            for (cover in covers) if (coverDue(cover)) fetchCover(cover)
            var budget = LYRICS_PER_RUN
            for (row in rows) {
                if (!lyricsMissing(row)) continue
                if (budget-- == 0) break
                fetchLyrics(row)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.i(TAG, "stopped: $e")
        }
        // Queued downloads' covers stay too: they may already be saved for another track.
        files.pruneCovers(covers + wantedCovers(all))
    }

    private fun fetchCover(coverId: String) {
        val url = client.coverArtUrl(coverId, 600) ?: return
        val tmp = File(files.coverFile(coverId).path + ".tmp")
        client.mediaHttp.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                // Gone, or not that kind of id: keep any old copy and don't ask again until the refresh comes round.
                files.coverFile(coverId).takeIf { it.isFile }?.setLastModified(System.currentTimeMillis())
                return
            }
            // Synced before the rename, or a crash or power cut can leave an empty file in its place.
            java.io.FileOutputStream(tmp).use { out -> resp.body.byteStream().copyTo(out); out.fd.sync() }
            if (!files.saveCover(coverId, tmp)) tmp.delete()
        }
    }

    private suspend fun fetchLyrics(row: Download) {
        try {
            // Through the lenient client, which keeps the reply for offline use.
            library.lyrics(row.trackId, row.artist, row.title)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The server answered, just not with lyrics: no use asking about every song.
            if (e is SubsonicException || e is HttpException) lyricsUnsupported = true else throw e
        }
    }

    companion object {
        private const val TAG = "Companions"
        /** Lyrics are one small request each; a first catch-up over a big library takes a few runs. */
        const val LYRICS_PER_RUN = 1000
        /** How often a cover whose id doesn't change with the picture is fetched again. */
        const val REFRESH_MS = 30L * 24 * 60 * 60 * 1000
    }
}
