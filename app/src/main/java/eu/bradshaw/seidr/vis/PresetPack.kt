package eu.bradshaw.seidr.vis

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * The visualiser's presets and textures, fetched once from projectM's own
 * repositories rather than shipped in the APK (about 36 MB). They're kept in
 * `noBackupFilesDir`, so updates keep them and backups don't carry them.
 *
 * `assets/milkdrop/manifest.txt` is the whole definition: its header pins a
 * commit of each repository, and each line is a file's git blob hash and
 * path. A download counts only when every listed file matches its hash
 * (GitHub's archives aren't byte-for-byte stable, but their contents are).
 * Presets not listed are never used, which is how one is left out.
 *
 * The presets stay in their zip (read with [ZipFile]: unpacked they'd be
 * 120 MB); the textures are unpacked, because projectM wants files.
 */
class PresetPack(private val context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) {
    sealed interface State {
        data object Missing : State
        /** [fraction] 0..1, roughly (GitHub doesn't say how big an archive is). */
        data class Downloading(val fraction: Float) : State
        data object Ready : State
        data object Failed : State
    }

    private class Manifest(val presetsCommit: String, val texturesCommit: String, val files: Map<String, String>) {
        /** Unique to this exact set of files, so a changed manifest downloads afresh. */
        val id: String = sha1(files.entries.joinToString("\n") { "${it.value} ${it.key}" }.toByteArray()).take(12)
        val presets: List<String> = files.keys.filter { it.startsWith(PRESETS) }.map { it.removePrefix(PRESETS) }
    }

    private val manifest by lazy {
        val lines = context.assets.open(MANIFEST).bufferedReader(Charsets.UTF_8).readLines()
        val commits = lines.filter { it.startsWith("#") }.map { it.substringAfterLast(' ') }
        val files = lines.filter { it.isNotEmpty() && !it.startsWith("#") }.associate { it.substringAfter(' ') to it.substringBefore(' ') }
        Manifest(commits[0], commits[1], files)
    }

    private val root = File(context.noBackupFilesDir, "milkdrop")
    private val dir: File get() = File(root, manifest.id)
    private val lock = Mutex()

    private val _state = MutableStateFlow(if (File(dir, DONE).isFile) State.Ready else State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()
    val ready: Boolean get() = _state.value == State.Ready

    /** Every preset's path in the pack, in manifest order. */
    val presets: List<String> get() = manifest.presets

    val textureDir: File get() = File(dir, "textures")

    @Volatile private var zip: ZipFile? = null

    /** A preset's text. Only once [ready]. */
    fun read(path: String): String {
        val z = zip ?: synchronized(this) { zip ?: ZipFile(File(dir, PRESETS_ZIP)).also { zip = it } }
        val entry = z.getEntry(zipPrefix(PRESET_REPO, manifest.presetsCommit) + path) ?: throw IOException("$path isn't in the pack")
        return z.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** Downloads the pack now, on any network, unless it's here or on its way. */
    fun download() {
        if (_state.value == State.Ready || _state.value is State.Downloading) return
        scope.launch(Dispatchers.IO) { fetch() }
    }

    /** At start-up: fetches the pack by itself on Wi-Fi (or any unmetered network). */
    fun downloadIfUnmetered() {
        if (_state.value == State.Ready) return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm.activeNetwork != null && !cm.isActiveNetworkMetered) download()
    }

    private suspend fun fetch() = lock.withLock {
        if (_state.value == State.Ready) return@withLock
        _state.value = State.Downloading(0f)
        val m = manifest
        val part = File(root, "${m.id}.part")
        try {
            part.deleteRecursively(); part.mkdirs()
            val presetsZip = File(part, PRESETS_ZIP)
            get(archive(PRESET_REPO, m.presetsCommit), presetsZip, from = 0f, to = 0.85f, expected = 33_000_000)
            val texturesZip = File(part, "textures.zip")
            get(archive(TEXTURE_REPO, m.texturesCommit), texturesZip, from = 0.85f, to = 0.95f, expected = 3_500_000)

            // Every listed file, checked; the presets stay zipped, the textures come out.
            var checked = 0
            ZipFile(presetsZip).use { z ->
                val prefix = zipPrefix(PRESET_REPO, m.presetsCommit)
                for (path in m.presets) {
                    val e = z.getEntry(prefix + path) ?: throw IOException("missing $path")
                    verify(PRESETS + path, z.getInputStream(e).use { it.readBytes() })
                    checked++
                }
            }
            ZipFile(texturesZip).use { z ->
                val prefix = zipPrefix(TEXTURE_REPO, m.texturesCommit)
                for (path in m.files.keys.filter { it.startsWith(TEXTURES) }) {
                    val e = z.getEntry(prefix + path) ?: throw IOException("missing $path")
                    val bytes = z.getInputStream(e).use { it.readBytes() }
                    verify(path, bytes)
                    File(part, path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
                    checked++
                }
            }
            texturesZip.delete()
            if (checked != m.files.size) throw IOException("checked $checked of ${m.files.size}")
            File(part, DONE).writeText(m.id)

            dir.deleteRecursively()
            if (!part.renameTo(dir)) throw IOException("couldn't move the pack into place")
            // Older packs, and the textures older versions unpacked from the APK.
            root.listFiles()?.filter { it.name != m.id }?.forEach { it.deleteRecursively() }
            Log.i(VisEngine.TAG, "pack ${m.id} ready: ${m.presets.size} presets")
            _state.value = State.Ready
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(VisEngine.TAG, "pack download failed: $e")
            part.deleteRecursively()
            _state.value = State.Failed
        }
    }

    /** Downloads [url] to [file], reporting progress between [from] and [to]. */
    private fun get(url: String, file: File, from: Float, to: Float, expected: Long) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            val total = r.body.contentLength().takeIf { it > 0 } ?: expected
            r.body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    var lastShown = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (got - lastShown > 256 * 1024) {
                            lastShown = got
                            _state.value = State.Downloading(from + (to - from) * (got.toFloat() / total).coerceAtMost(0.99f))
                        }
                    }
                }
            }
        }
    }

    private fun verify(path: String, bytes: ByteArray) {
        val want = manifest.files[path] ?: throw IOException("$path isn't listed")
        if (blobSha(bytes) != want) throw IOException("$path doesn't match")
    }

    companion object {
        const val MANIFEST = "milkdrop/manifest.txt"
        /** Roughly what a download costs, for the prompt on mobile data. */
        const val SIZE_MB = 36
        private const val PRESETS = "presets/"
        private const val TEXTURES = "textures/"
        private const val PRESETS_ZIP = "presets.zip"
        private const val DONE = ".complete"
        private const val PRESET_REPO = "projectM-visualizer/presets-cream-of-the-crop"
        private const val TEXTURE_REPO = "projectM-visualizer/presets-milkdrop-texture-pack"

        private fun archive(repo: String, commit: String) = "https://codeload.github.com/$repo/zip/$commit"

        /** GitHub's archives put everything under "<repo>-<commit>/". */
        private fun zipPrefix(repo: String, commit: String) = "${repo.substringAfter('/')}-$commit/"

        /** Git's hash of a file's contents, as the manifest lists it. */
        fun blobSha(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-1")
            md.update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
            return md.digest(bytes).joinToString("") { "%02x".format(it) }
        }

        private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
