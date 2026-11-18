package eu.bradshaw.seidr.cast

/**
 * The receiver holds only a short window of Seiðr's queue: the current song
 * and the next [AHEAD] (so it can preload). A whole playlist wouldn't fit in a
 * message, and the queue model ([eu.bradshaw.seidr.playback.PlayerConnection])
 * stays Seiðr's. Pure, so it's unit-tested.
 */
object CastQueue {
    const val AHEAD = 2

    /**
     * The keys that should follow [index]: the next [n], wrapping under
     * repeat-all (never back round to [index] itself).
     */
    fun upcoming(keys: List<String>, index: Int, repeatAll: Boolean, n: Int = AHEAD): List<String> {
        if (index !in keys.indices) return emptyList()
        val out = mutableListOf<String>()
        for (step in 1..minOf(n, keys.size - 1)) {
            val i = index + step
            if (i >= keys.size && !repeatAll) break
            out += keys[i % keys.size]
        }
        return out
    }

    /** What to send so the receiver's queue reads current + [upcoming]. */
    data class Plan(val remove: List<Int>, val insert: List<String>) {
        val empty get() = remove.isEmpty() && insert.isEmpty()
    }

    /**
     * [items] is the receiver's queue in order (item id → slot key), with
     * [currentItemId] playing. Items before it go; the ones after it are kept
     * as far as they already match [upcoming] (so a preloaded next song
     * survives), and the rest is replaced. Null when the current item isn't
     * in [items]: then the window has to be loaded afresh.
     */
    fun plan(items: List<Pair<Int, String>>, currentItemId: Int, upcoming: List<String>): Plan? {
        val c = items.indexOfFirst { it.first == currentItemId }
        if (c < 0) return null
        val after = items.drop(c + 1)
        var same = 0
        while (same < after.size && same < upcoming.size && after[same].second == upcoming[same]) same++
        return Plan(
            remove = items.take(c).map { it.first } + after.drop(same).map { it.first },
            insert = upcoming.drop(same),
        )
    }
}
