package eu.bradshaw.seidr.sync

import eu.bradshaw.seidr.data.db.Download
import eu.bradshaw.seidr.data.db.DownloadState
import eu.bradshaw.seidr.data.db.OfflineDao
import eu.bradshaw.seidr.data.db.OfflineSource
import eu.bradshaw.seidr.data.db.SourceUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import java.io.File

/**
 * Live view of the download table for the UI, plus a lock-free lookup of
 * finished files for the player's data source (which runs on a loader thread).
 */
class DownloadIndex(dao: OfflineDao, scope: CoroutineScope) {
    @Volatile private var doneFiles: Map<String, File> = emptyMap()

    val all: StateFlow<Map<String, Download>> = dao.downloadsFlow()
        .map { rows -> rows.associateBy { it.trackId } }
        .onEach { m ->
            doneFiles = m.values.filter { it.state == DownloadState.DONE && it.path != null }
                .associate { it.trackId to File(it.path!!) }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * The downloads under way, as one batch: from the first queued until the
     * queue empties, including those finished meanwhile. Null when idle.
     */
    val batch: StateFlow<DownloadBatch?> = all
        .map(BatchTracker()::update)
        .stateIn(scope, SharingStarted.Eagerly, null)

    val sources: StateFlow<Map<String, OfflineSource>> = dao.sourcesFlow()
        .map { list -> list.associateBy { it.id } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Per-source track counts and bytes on disk, for Download toggles and the Downloads screen. */
    val usage: StateFlow<Map<String, SourceUsage>> = dao.usageFlow()
        .map { list -> list.associateBy { it.sourceId } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** The finished local file for a track, if it is on the phone. */
    fun localFile(trackId: String): File? = doneFiles[trackId]?.takeIf { it.isFile }

    fun isDownloaded(trackId: String) = doneFiles.containsKey(trackId)
}

/** [left] still to come; [fraction] of the batch's bytes on the phone. */
data class DownloadBatch(val left: Int, val fraction: Float)

/**
 * Remembers which tracks belong to the batch running now, so the progress
 * covers it all rather than restarting with each file. Pure; one per flow.
 */
internal class BatchTracker {
    private val members = mutableSetOf<String>()

    fun update(rows: Map<String, Download>): DownloadBatch? {
        val pending = rows.values.filter { it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING }
        if (pending.isEmpty()) { members.clear(); return null }
        pending.mapTo(members) { it.trackId }
        // Gone (unclaimed) or failed: no longer part of it.
        members.retainAll { id -> rows[id]?.state.let { it != null && it != DownloadState.FAILED } }
        var total = 0L
        var have = 0L
        for (id in members) {
            val d = rows.getValue(id)
            val size = d.size.coerceAtLeast(0)
            total += size
            have += when (d.state) {
                DownloadState.DONE -> size
                DownloadState.DOWNLOADING -> d.bytesDone.coerceIn(0, size)
                else -> 0
            }
        }
        val fraction = if (total > 0) have.toFloat() / total else (members.size - pending.size).toFloat() / members.size
        return DownloadBatch(pending.size, fraction.coerceIn(0f, 1f))
    }
}
