package eu.bradshaw.seidr.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.data.db.Download
import eu.bradshaw.seidr.data.db.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "Downloads"

/** Periodic and on-reconnect sync, so offline playlists stay mirrored while the app is closed. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = Graph.get(applicationContext)
        if (g.settings.server.value == null) return Result.success()
        val report = g.sync.runNow()
        Log.i("Sync", "worker pass: $report")
        return Result.success()
    }
}

/**
 * Works through the download queue one file at a time. Resumes partial files
 * with a Range request, pauses (with a notification) when storage runs low,
 * and re-checks claims before committing so a track un-claimed mid-download
 * is thrown away rather than kept.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val g = Graph.get(context)

    override suspend fun getForegroundInfo() = foregroundInfo("Preparing…", 0, -1)

    private fun foregroundInfo(title: String, remaining: Int, progress: Int) = ForegroundInfo(
        Notifications.DOWNLOAD_ID,
        Notifications.downloading(applicationContext, title, remaining, progress),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val dao = g.db.offline()
        dao.resetInterrupted()
        Notifications.clearStorageFull(applicationContext)
        val attempted = mutableSetOf<String>()
        var networkTrouble = false

        while (true) {
            ensureActive()
            val pending = dao.pendingDownloads().filter { it.state != DownloadState.FAILED && it.trackId !in attempted }
            val next = pending.firstOrNull() ?: break
            attempted += next.trackId

            if (dao.claimCount(next.trackId) == 0) {
                g.engine.lock.withLock { discard(next) }
                continue
            }

            // Long runs get a foreground service; if Android refuses (background start
            // limits), carry on — WorkManager will reschedule if we're stopped.
            runCatching { setForeground(foregroundInfo("${next.artist} – ${next.title}", pending.size, -1)) }

            val part = g.files.partFile(next.trackId)
            val need = (next.size - part.length()).coerceAtLeast(0) + RESERVE_BYTES
            if (g.files.freeBytes() < need) {
                Log.w(TAG, "storage full, pausing (need ${need / 1_000_000} MB)")
                Notifications.storageFull(applicationContext, need / 1_000_000)
                return@withContext Result.success()
            }

            dao.setState(next.trackId, DownloadState.DOWNLOADING)
            try {
                val file = fetch(next, part, pending.size)
                g.engine.lock.withLock {
                    val row = dao.download(next.trackId)
                    if (row == null || dao.claimCount(next.trackId) == 0) {
                        // Un-claimed while downloading: don't keep it.
                        file.delete()
                        dao.deleteDownload(next.trackId)
                    } else {
                        dao.upsertDownload(row.copy(
                            state = DownloadState.DONE, path = file.absolutePath,
                            bytesDone = file.length(), error = null, updatedAt = System.currentTimeMillis(),
                        ))
                    }
                }
                next.coverArt?.let { fetchCover(it) }
            } catch (e: IOException) {
                Log.w(TAG, "download ${next.trackId} failed: $e")
                val noSpace = e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true
                dao.setState(next.trackId, DownloadState.FAILED, if (noSpace) "Storage full" else e.message)
                if (noSpace) {
                    Notifications.storageFull(applicationContext, need / 1_000_000)
                    return@withContext Result.success()
                }
                if (!g.status.online.value) { networkTrouble = true; break }
            }
        }
        // Network dropped mid-run: WorkManager retries once the constraint is met again.
        if (networkTrouble) {
            dao.retryFailed()
            Result.retry()
        } else Result.success()
    }

    private suspend fun discard(d: Download) {
        g.files.delete(d.trackId, d.path)
        g.db.offline().deleteDownload(d.trackId)
    }

    private suspend fun fetch(d: Download, part: File, remaining: Int): File {
        val url = g.client.downloadUrl(d.trackId) ?: throw IOException("not logged in")
        // The first response's Last-Modified/ETag, so a resume only continues the same file.
        val since = File(part.path + ".since")
        if (!part.exists()) since.delete()
        val have = part.length()
        val validator = if (have > 0) since.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() } else null
        val req = Request.Builder().url(url).apply {
            if (have > 0) header("Range", "bytes=$have-")
            if (validator != null) header("If-Range", validator)
        }.build()
        g.client.mediaHttp.newCall(req).execute().use { resp ->
            val append = when (val start = DownloadRules.start(resp.code, resp.header("Content-Type"), have)) {
                DownloadRules.Start.Restart -> null
                is DownloadRules.Start.Fail -> {
                    if (!DownloadRules.isEnvelope(resp.header("Content-Type"))) throw IOException(start.message)
                    // The server answered, but with an error: the part is no use either.
                    part.delete(); since.delete()
                    val why = runCatching { resp.body.string() }.getOrNull()?.let(DownloadRules::envelopeMessage)
                    throw IOException(why ?: start.message)
                }
                is DownloadRules.Start.Write -> start.append
            }
            if (append == null) {
                // A part at or past full size: throw it away and start again (have is 0 next time).
                part.delete(); since.delete()
                return fetch(d, part, remaining)
            }
            if (!append) (resp.header("ETag") ?: resp.header("Last-Modified"))
                ?.let { since.writeText(it) } ?: since.delete()
            val total = if (append) have + resp.body.contentLength() else resp.body.contentLength()
            var written = if (append) have else 0L
            var lastUpdate = 0L
            FileOutputStream(part, append).use { out ->
                resp.body.byteStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 1000) {
                            lastUpdate = now
                            g.db.offline().setProgress(d.trackId, written)
                            val pct = if (total > 0) (written * 100 / total).toInt() else -1
                            runCatching { setForeground(foregroundInfo("${d.artist} – ${d.title}", remaining, pct)) }
                        }
                    }
                }
                out.fd.sync()
            }
            DownloadRules.finish(d.size, total, part.length())?.let { why ->
                // A short read can resume; anything else (wrong size, wrong body) starts clean.
                val len = part.length()
                if (len >= total || (d.size > 0 && len >= d.size)) { part.delete(); since.delete() }
                throw IOException(why)
            }
        }
        since.delete()
        val dest = g.files.trackFile(d.trackId, d.suffix)
        if (!part.renameTo(dest)) throw IOException("could not move ${part.name} into place")
        return dest
    }

    private fun fetchCover(coverId: String) {
        val f = g.files.coverFile(coverId)
        if (f.isFile) return
        val url = g.client.coverArtUrl(coverId, 600) ?: return
        runCatching {
            g.client.mediaHttp.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return
                val tmp = File(f.path + ".tmp")
                tmp.outputStream().use { resp.body.byteStream().copyTo(it) }
                tmp.renameTo(f)
            }
        }
    }

    companion object {
        /** Always leave this much free for the system and other apps. */
        const val RESERVE_BYTES = 300L * 1024 * 1024
    }
}
