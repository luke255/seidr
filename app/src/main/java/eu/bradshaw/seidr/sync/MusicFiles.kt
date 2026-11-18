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

    // Saved covers by [stableCoverId], so a cover id that has moved on (or an old one
    // in a last-seen response or the saved queue) still finds a picture.
    private val byStable: MutableMap<String, File> by lazy {
        val map = java.util.concurrent.ConcurrentHashMap<String, File>()
        coverDir.listFiles { f -> f.name.endsWith(".jpg") && usable(f) }?.forEach { map[stableCoverId(it.name.removeSuffix(".jpg"))] = it }
        map
    }

    /**
     * The saved cover for [coverId]: its own file, or else one saved under another
     * version of the same id. Null when there's neither.
     */
    fun coverFor(coverId: String): File? =
        coverFile(coverId).takeIf(::usable) ?: byStable[stableCoverId(safe(coverId))]?.takeIf(::usable)

    // An empty file is what an interrupted save leaves behind.
    private fun usable(f: File) = f.isFile && f.length() > 0

    /** Moves a fetched [tmp] into place as [coverId]'s cover. */
    fun saveCover(coverId: String, tmp: File): Boolean {
        val dest = coverFile(coverId)
        if (!tmp.renameTo(dest)) return false
        byStable[stableCoverId(safe(coverId))] = dest
        return true
    }

    /**
     * Deletes saved covers (and stray partial fetches) for ids not in [keep]. An
     * id in [keep] without its own file yet keeps its older version as a stand-in.
     */
    fun pruneCovers(keep: Set<String>) {
        val names = keep.mapTo(HashSet()) { "${safe(it)}.jpg" }
        val standIns = keep.filterNot { usable(coverFile(it)) }.mapTo(HashSet()) { stableCoverId(safe(it)) }
        coverDir.listFiles()?.forEach { f ->
            if (f.name in names) return@forEach
            val stable = stableCoverId(f.name.removeSuffix(".jpg"))
            if (f.name.endsWith(".jpg") && usable(f) && stable in standIns) return@forEach
            if (f.delete() && byStable[stable] == f) byStable.remove(stable)
        }
    }

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

/**
 * What [coverId] shows, whatever its version, so a saved cover can stand in for
 * another id of the same picture. Navidrome's album ids end in `_<hex timestamp>`
 * (`al-4svsSu8UW3XBHtB3ALK03f_aaf57efc43e57651`), which changes whenever the album
 * does, and songs carry their disc's cover (`dc-<album id>:<disc>`, saved with `_`
 * for `:`), which is the album's. Other servers' ids pass through unchanged.
 */
fun stableCoverId(coverId: String): String =
    DISC.matchEntire(coverId)?.let { "al-" + it.groupValues[1] } ?: coverId.replace(STAMP, "")

/**
 * Whether [coverId] names one version of its picture (Navidrome's stamped ids), so
 * a saved copy never goes stale: a new picture comes with a new id.
 */
fun isVersionedCoverId(coverId: String): Boolean = STAMP.containsMatchIn(coverId)

/**
 * Navidrome's id for an album's own cover, when [songCoverId] is the song's own
 * (`mf-…`, art embedded in the file): album pages ask for that one. Null otherwise.
 */
fun albumCoverIdFor(songCoverId: String?, albumId: String?): String? =
    if (albumId != null && songCoverId?.startsWith("mf-") == true) "al-$albumId" else null

private val STAMP = Regex("_[0-9a-f]{8,}$")
private val DISC = Regex("dc-([A-Za-z0-9]+)[:_][0-9]+")
