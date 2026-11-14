package eu.bradshaw.seidr.playback

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SavedQueue(
    val entries: List<QueueEntry>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean = false,
    val repeat: Int = 0,
    /** Entries hidden because their song is in Bad: Songs (see [HiddenQueue]). */
    val hidden: List<HiddenEntry> = emptyList(),
)

/**
 * Keeps the queue across process death so the player comes back where it
 * was. The queue file is rewritten on queue changes; the playback position is
 * checkpointed separately (small and cheap) while playing, so a killed process
 * resumes near where it stopped.
 */
class QueueStore(context: Context) {
    private val file = File(context.filesDir, "queue.json")
    private val posFile = File(context.filesDir, "queue-position")
    private val json = Json { ignoreUnknownKeys = true }

    fun save(q: SavedQueue) {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(q))
            tmp.renameTo(file)
        }
        q.entries.getOrNull(q.index)?.let { savePosition(q.index, it.key, q.positionMs) }
    }

    /** [key] identifies the item at [index], so a checkpoint from an older queue is ignored. */
    fun savePosition(index: Int, key: String, positionMs: Long) {
        runCatching {
            val tmp = File(posFile.path + ".tmp")
            tmp.writeText("$index\n$key\n$positionMs")
            tmp.renameTo(posFile)
        }
    }

    fun load(): SavedQueue? {
        val q = runCatching { json.decodeFromString<SavedQueue>(file.readText()) }.getOrNull() ?: return null
        val p = runCatching { posFile.readLines() }.getOrNull()?.takeIf { it.size == 3 } ?: return q
        val index = p[0].toIntOrNull() ?: return q
        val pos = p[2].toLongOrNull() ?: return q
        return if (q.entries.getOrNull(index)?.key == p[1]) q.copy(index = index, positionMs = pos) else q
    }

    fun clear() { file.delete(); posFile.delete() }
}
