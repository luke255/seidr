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
 * Read-only. It's exported (the car needs it), so any app can ask: on a kid's
 * login it serves only art the kid's rules would show.
 */
class CoverProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = uri.lastPathSegment?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: throw FileNotFoundException()
        val ctx = context ?: throw FileNotFoundException()
        val graph = Graph.get(ctx)
        // Fails closed: a kid's login with no lists yet has empty rules.
        graph.kids.current?.let { if (!it.coverAllowed(id)) throw FileNotFoundException() }
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
        trim(dir)
        return f
    }

    private fun trim(dir: File) {
        val files = dir.listFiles { f -> f.name.endsWith(".jpg") }?.toList() ?: return
        val evict = CoverCache.evict(files.map { CoverCache.Entry(it.name, it.length(), it.lastModified()) })
        files.filter { it.name in evict }.forEach { it.delete() }
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
