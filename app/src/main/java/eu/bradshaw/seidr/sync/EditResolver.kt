package eu.bradshaw.seidr.sync

/**
 * Pure helpers that apply a playlist edit, identified by song id, to a freshly
 * fetched track list. Subsonic removes by index, which is only safe against a
 * list fetched moments ago.
 */
object EditResolver {
    /**
     * Which index to remove for [songId]. When the song appears more than once,
     * [hint] (the index the user saw) picks the occurrence if it still matches;
     * otherwise the occurrence nearest the hint. Null means the song is already
     * gone, so the edit can be dropped.
     */
    fun removeIndex(fresh: List<String>, songId: String, hint: Int?): Int? {
        if (hint != null && fresh.getOrNull(hint) == songId) return hint
        val positions = fresh.indices.filter { fresh[it] == songId }
        if (positions.isEmpty()) return null
        return if (hint == null) positions.first() else positions.minBy { kotlin.math.abs(it - hint) }
    }

    /**
     * Reorder. Subsonic has no move call, so the only way is to send the whole
     * new order — which would silently undo any change made elsewhere since the
     * user loaded the list. So the move is applied only if the fresh list is
     * exactly what the user was looking at; otherwise null (conflict).
     */
    fun move(fresh: List<String>, seen: List<String>, from: Int, to: Int): List<String>? {
        if (fresh != seen) return null
        if (from !in fresh.indices || to !in fresh.indices) return null
        return fresh.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Several moves at once: [desired] must be a permutation of what the user saw. */
    fun reorder(fresh: List<String>, seen: List<String>, desired: List<String>): List<String>? {
        if (fresh != seen) return null
        if (desired.sorted() != seen.sorted()) return null
        return desired
    }

    /**
     * Undoing a remove: the song is added back (on the end), then [before] (the
     * list as it was) is the order to restore, but only while the playlist holds
     * exactly the same songs again. Null when it's already in place, or when
     * something else changed meanwhile (the song then stays on the end).
     */
    fun undoRemove(fresh: List<String>, before: List<String>): List<String>? =
        before.takeIf { fresh != before && fresh.sorted() == before.sorted() }
}
