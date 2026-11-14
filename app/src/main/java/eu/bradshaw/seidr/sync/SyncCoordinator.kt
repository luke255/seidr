package eu.bradshaw.seidr.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import eu.bradshaw.seidr.data.Settings
import eu.bradshaw.seidr.data.db.OfflineDao
import eu.bradshaw.seidr.data.db.OfflineSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

data class SyncStatus(
    val running: Boolean = false,
    val lastRunAt: Long = 0,
    val lastReport: SyncReport? = null,
)

/**
 * Decides when sync runs and wires its results to the download worker. All
 * triggers (app start, foreground, opening a playlist, after edits, periodic
 * work, reconnect) funnel through [request] or [runNow].
 */
class SyncCoordinator(
    private val context: Context,
    private val engine: SyncEngine,
    private val dao: OfflineDao,
    private val settings: Settings,
    private val scope: CoroutineScope,
    private val beforeSync: suspend () -> Unit,
    private val afterSync: suspend () -> Unit = {},
) {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val runLock = Mutex()
    private val work get() = WorkManager.getInstance(context)

    /** Fire-and-forget sync. Forced sources are fetched in full even if unchanged. */
    fun request(force: Set<String> = emptySet()) {
        scope.launch { runNow(force) }
    }

    suspend fun runNow(force: Set<String> = emptySet()): SyncReport? = runLock.withLock {
        if (settings.server.value == null) return null
        _status.value = _status.value.copy(running = true)
        try {
            // Replay offline playlist edits first so the mirror reflects them.
            runCatching { beforeSync() }.onFailure { Log.w("Sync", "pending replay failed: $it") }
            // Liked Songs is no longer a download source; this pass's file sweep drops what only it claimed.
            engine.lock.withLock { dao.deleteSource(RETIRED_STARRED_ID) }
            val report = engine.sync(force)
            Log.i("Sync", "pass: ok=${report.ok.size} unchanged=${report.unchanged.size} failed=${report.failed} " +
                "gone=${report.gone} queued=${report.queued} redl=${report.redownload} deleted=${report.deleted.size} deferred=${report.deferred}")
            if (dao.pendingDownloads().isNotEmpty()) enqueueDownloads()
            if (report.failed.isNotEmpty()) enqueueRetryOnReconnect()
            _status.value = SyncStatus(false, System.currentTimeMillis(), report)
            scope.launch { runCatching { afterSync() }.onFailure { Log.w("Sync", "after-sync failed: $it") } }
            report
        } catch (e: Exception) {
            Log.w("Sync", "pass crashed: $e")
            _status.value = _status.value.copy(running = false)
            null
        }
    }

    fun sweep() {
        scope.launch { runCatching { engine.sweep() } }
    }

    /** The Download toggle. Switching on fetches straight away; switching off removes now-unclaimed files. */
    fun setOffline(source: OfflineSource, enabled: Boolean) {
        scope.launch {
            if (enabled) {
                dao.upsertSource(source)
                runNow(setOf(source.id))
            } else {
                engine.lock.withLock { dao.deleteSource(source.id) }
                engine.sweep()
            }
        }
    }

    fun retryFailed() {
        scope.launch {
            dao.retryFailed()
            enqueueDownloads()
        }
    }

    fun enqueueDownloads() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (settings.wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE: if a run is in progress, queue one more after it so
        // rows added mid-run are never missed.
        work.enqueueUniqueWork("downloads", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** Called when the Wi-Fi-only setting changes, so the queued work picks up the new constraint. */
    fun requeueDownloads() {
        work.cancelUniqueWork("downloads")
        scope.launch { if (dao.pendingDownloads().isNotEmpty()) enqueueDownloads() }
    }

    private fun enqueueRetryOnReconnect() {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(1, TimeUnit.MINUTES)
            .build()
        work.enqueueUniqueWork("sync-retry", ExistingWorkPolicy.KEEP, req)
    }

    fun schedulePeriodic() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(45, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancelAll() {
        work.cancelUniqueWork("sync-periodic")
        work.cancelUniqueWork("sync-retry")
        work.cancelUniqueWork("downloads")
    }

    private companion object {
        const val RETIRED_STARRED_ID = "starred:starred"
    }
}
