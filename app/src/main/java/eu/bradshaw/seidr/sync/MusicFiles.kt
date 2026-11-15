package eu.bradshaw.seidr.sync

import android.content.Context
import android.os.storage.StorageManager
import java.io.File

/** Where downloaded tracks and their cover art live: app-specific storage, one file per track named by id. */
class MusicFiles(context: Context) : FileDeleter {
    private val storage = context.getSystemService(StorageManager::class.java)
    val musicDir = File(context.filesDir, "music").apply { mkdirs() }
    val coverDir = File(context.filesDir, "covers").apply { mkdirs() }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    fun trackFile(id: String, suffix: String) = File(musicDir, "${safe(id)}.${safe(suffix)}")
    fun partFile(id: String) = File(musicDir, "${safe(id)}.part")
    fun coverFile(coverId: String) = File(coverDir, "${safe(coverId)}.jpg")

    override fun delete(trackId: String, path: String?) {
        path?.let { File(it).delete() }
        partFile(trackId).delete()
        val prefix = safe(trackId) + "."
        musicDir.listFiles { f -> f.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    /**
     * Space downloads can use, counting cache the system would clear for us.
     * A binder call: not on the main thread.
     */
    fun freeBytes(): Long = runCatching { storage.getAllocatableBytes(storage.getUuidForPath(musicDir)) }
        .getOrElse { musicDir.freeSpace }

    fun usedBytes(): Long = musicDir.listFiles()?.sumOf { it.length() } ?: 0L
}
