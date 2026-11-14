package eu.bradshaw.seidr.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import eu.bradshaw.seidr.BuildConfig
import eu.bradshaw.seidr.Graph
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves cover art as `content://` URIs, which is what Android Auto loads for
 * browse items (it won't fetch our http URLs). Uses the cover saved with
 * downloads when there is one, else fetches from the server into a small cache.
 * Read-only; covers aren't sensitive.
 */
class CoverProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = uri.lastPathSegment?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: throw FileNotFoundException()
        val ctx = context ?: throw FileNotFoundException()
        val graph = Graph.get(ctx)
        val file = graph.files.coverFile(id).takeIf { it.isFile } ?: cached(ctx.cacheDir, graph, id)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun cached(cacheDir: File, graph: Graph, id: String): File {
        val dir = File(cacheDir, "auto-covers").apply { mkdirs() }
        val f = File(dir, "$id.jpg")
        if (f.isFile) return f
        val url = graph.client.coverArtUrl(id, 600) ?: throw FileNotFoundException()
        graph.baseHttp.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw FileNotFoundException()
            val tmp = File(dir, "$id.tmp")
            tmp.outputStream().use { resp.body.byteStream().copyTo(it) }
            tmp.renameTo(f)
        }
        return f
    }

    override fun getType(uri: Uri) = "image/*"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        private val AUTHORITY = "${BuildConfig.APPLICATION_ID}.covers"
        fun uri(coverId: String): Uri = Uri.parse("content://$AUTHORITY/$coverId")
    }
}
