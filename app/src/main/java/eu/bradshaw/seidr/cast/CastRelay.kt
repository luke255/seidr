package eu.bradshaw.seidr.cast

import android.util.Log
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.connect.Connect
import eu.bradshaw.seidr.connect.isEmulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A small web server on the phone that Cast devices fetch songs and covers
 * from, while one is playing. It answers only on a random per-session path,
 * and only for the songs in the queue it's told about ([songs]):
 *
 * - A downloaded song comes from disk, so casting works with no server.
 * - Anything else the phone fetches from the server, by whatever route it
 *   has (Tailscale, a name only local DNS knows, https), and passes on.
 *   Range requests go through, so the speaker can seek.
 * - The server login never leaves the phone.
 * - Kid mode holds: a kid's login serves only approved songs and allowed art,
 *   and clean mode only approved songs.
 *
 * Each request gets its own connection (`Connection: close`).
 */
class CastRelay(private val graph: Graph) {
    private val token = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null

    /** The song ids the speaker may fetch: the queue. */
    @Volatile var songs: Set<String> = emptySet()

    /** Starts listening on any free port. */
    fun start() {
        if (server != null) return
        // A fixed port on an emulator, which the laptop forwards (see Connect's emulatorLanHost).
        val s = (if (isEmulator) runCatching { ServerSocket(Connect.EMULATOR_RELAY_PORT) }.getOrNull() else null) ?: ServerSocket(0)
        server = s
        scope.launch {
            while (isActive) {
                val c = try { s.accept() } catch (_: IOException) { return@launch }
                launch { c.use(::serve) }
            }
        }
    }

    fun close() {
        runCatching { server?.close() }
        server = null
        scope.cancel()
    }

    private fun base(host: String): String? {
        val port = server?.localPort ?: return null
        val h = if (':' in host) "[$host]" else host
        return "http://$h:$port/$token"
    }

    /** The link a speaker on [host]'s network uses for [songId]. */
    fun songUrl(host: String, songId: String): String? = base(host)?.let { "$it/s/${enc(songId)}" }

    fun artUrl(host: String, coverId: String): String? = base(host)?.let { "$it/a/${enc(coverId)}" }

    private fun serve(c: Socket) {
        try {
            c.soTimeout = 30_000
            val req = readHead(c.getInputStream()) ?: return
            val out = c.getOutputStream().buffered()
            val target = route(req.path, token)
            val head = req.method == "HEAD"
            when {
                req.method != "GET" && !head -> status(out, 405, "Method Not Allowed")
                target == null -> status(out, 404, "Not Found")
                target.kind == 's' -> song(out, target.id, req.range, head)
                else -> art(out, target.id, head)
            }
            out.flush()
        } catch (e: IOException) {
            // The speaker hangs up on every seek and skip: nothing to report.
            Log.d(TAG, "relay: $e")
        }
    }

    private fun song(out: OutputStream, id: String, range: String?, head: Boolean) {
        // A kid's rules, or clean mode's (this device's, as the queue is).
        val kid = graph.kids.playing
        if (id !in songs || kid?.playable(id) == false) return status(out, 404, "Not Found")
        graph.downloads.localFile(id)?.let { return file(out, it, audioType(it.extension), range, head) }
        val url = graph.client.streamUrl(id) ?: return status(out, 503, "Service Unavailable")
        proxy(out, url, range, head)
    }

    private fun art(out: OutputStream, coverId: String, head: Boolean) {
        graph.kids.current?.let { if (!it.coverAllowed(coverId)) return status(out, 404, "Not Found") }
        graph.files.coverFor(coverId)?.let { return file(out, it, "image/jpeg", null, head) }
        val url = graph.client.coverArtUrl(coverId, ART_SIZE) ?: return status(out, 503, "Service Unavailable")
        proxy(out, url, null, head)
    }

    private fun file(out: OutputStream, f: File, type: String, range: String?, head: Boolean) {
        val size = f.length()
        val r = range?.let { parseRange(it, size) ?: return status(out, 416, "Range Not Satisfiable", "Content-Range: bytes */$size") }
        val (from, to) = r ?: (0L to size - 1)
        val lines = mutableListOf("Content-Type: $type", "Content-Length: ${to - from + 1}", "Accept-Ranges: bytes")
        if (r != null) lines += "Content-Range: bytes $from-$to/$size"
        writeHead(out, if (r != null) 206 else 200, if (r != null) "Partial Content" else "OK", lines)
        if (head) return
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(from)
            val buf = ByteArray(64 * 1024)
            var left = to - from + 1
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
    }

    private fun proxy(out: OutputStream, url: String, range: String?, head: Boolean) {
        val req = Request.Builder().url(url).apply { if (range != null) header("Range", range) }.build()
        val resp = try {
            graph.client.mediaHttp.newCall(req).execute()
        } catch (e: IOException) {
            Log.i(TAG, "relay can't reach the server: $e")
            return status(out, 502, "Bad Gateway")
        }
        resp.use {
            if (!it.isSuccessful) return status(out, it.code.takeIf { c -> c == 416 } ?: 502, "Upstream ${it.code}")
            val lines = listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges")
                .mapNotNull { h -> it.header(h)?.let { v -> "$h: $v" } }
            writeHead(out, it.code, it.message.ifEmpty { "OK" }, lines)
            if (!head) it.body.byteStream().copyTo(out, 64 * 1024)
        }
    }

    private fun status(out: OutputStream, code: Int, reason: String, vararg extra: String) =
        writeHead(out, code, reason, listOf("Content-Length: 0") + extra)

    private fun writeHead(out: OutputStream, code: Int, reason: String, lines: List<String>) {
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            lines.forEach { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    data class Head(val method: String, val path: String, val range: String?)
    data class Target(val kind: Char, val id: String)

    companion object {
        private const val TAG = "Cast"
        private const val ART_SIZE = 600
        private const val MAX_HEAD = 16 * 1024

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** The request line and the Range header; null for anything malformed. */
        fun readHead(input: InputStream): Head? {
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            var total = 0
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (++total > MAX_HEAD) return null
                if (b == '\n'.code) {
                    val l = line.toString().trimEnd('\r')
                    line.clear()
                    if (l.isEmpty()) break
                    lines += l
                } else line.append(b.toChar())
            }
            val parts = lines.firstOrNull()?.split(' ') ?: return null
            if (parts.size < 3) return null
            val range = lines.drop(1).firstOrNull { it.startsWith("range:", ignoreCase = true) }?.substringAfter(':')?.trim()
            return Head(parts[0], parts[1], range)
        }

        /** `/<token>/s/<song id>` or `/<token>/a/<cover id>`, with the right token; else null. */
        fun route(path: String, token: String): Target? {
            val p = path.substringBefore('?').trimStart('/').split('/')
            if (p.size != 3 || p[1].length != 1 || p[1][0] !in "sa") return null
            if (!MessageDigest.isEqual(p[0].toByteArray(), token.toByteArray())) return null
            val id = runCatching { URLDecoder.decode(p[2], "UTF-8") }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            return Target(p[1][0], id)
        }

        /** One `bytes=` range over [size] bytes, as first..last; null when it can't be met. */
        fun parseRange(header: String, size: Long): Pair<Long, Long>? {
            val spec = header.trim().removePrefix("bytes=").takeIf { it != header.trim() && ',' !in it } ?: return null
            val (a, b) = spec.split('-', limit = 2).let { it[0].trim() to it.getOrElse(1) { "" }.trim() }
            val range = when {
                a.isEmpty() -> b.toLongOrNull()?.takeIf { it > 0 }?.let { maxOf(0, size - it) to size - 1 }
                else -> a.toLongOrNull()?.let { from -> from to (b.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)) }
            } ?: return null
            return range.takeIf { it.first in 0 until size && it.second >= it.first }
        }

        fun audioType(ext: String): String = when (ext.lowercase()) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "m4a", "mp4", "aac", "alac" -> "audio/mp4"
            "ogg", "oga", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }
}
