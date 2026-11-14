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
