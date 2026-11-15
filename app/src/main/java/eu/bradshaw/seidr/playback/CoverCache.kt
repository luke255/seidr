package eu.bradshaw.seidr.playback

/** Keeps `CoverProvider`'s cache small. Pure, for the tests. */
object CoverCache {
    const val MAX_FILES = 200
    const val MAX_BYTES = 50L * 1024 * 1024

    data class Entry(val name: String, val bytes: Long, val modified: Long)

    /** Names to delete, oldest first, until both limits hold. */
    fun evict(entries: List<Entry>, maxFiles: Int = MAX_FILES, maxBytes: Long = MAX_BYTES): Set<String> {
        var count = entries.size
        var bytes = entries.sumOf { it.bytes }
        val out = mutableSetOf<String>()
        for (e in entries.sortedBy { it.modified }) {
            if (count <= maxFiles && bytes <= maxBytes) break
            out += e.name
            count--
            bytes -= e.bytes
        }
        return out
    }
}
