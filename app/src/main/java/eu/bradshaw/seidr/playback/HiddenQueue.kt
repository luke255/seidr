package eu.bradshaw.seidr.playback

import kotlinx.serialization.Serializable

/**
 * A queue entry taken out of the player because its song is in Bad: Songs,
 * remembered with its neighbours' keys so it can go back where it was once
 * the song is fixed (taken off that playlist).
 */
@Serializable
data class HiddenEntry(val entry: QueueEntry, val prevKey: String?, val nextKey: String?)

/**
 * Pure bookkeeping for hidden entries; [PlaybackService] applies the results to
 * the player. Keys are queue-slot keys (media ids), unique per slot.
 */
object HiddenQueue {
    /** The indexes of [keys] to hide, ascending, each with its nearest visible neighbours. */
    fun hide(keys: List<String>, bad: Set<String>): List<Triple<Int, String?, String?>> {
        val hide = keys.indices.filter { songIdOf(keys[it]) in bad }
        if (hide.isEmpty()) return emptyList()
        val set = hide.toSet()
        return hide.map { i ->
            val prev = (i - 1 downTo 0).firstOrNull { it !in set }?.let(keys::get)
            val next = (i + 1 until keys.size).firstOrNull { it !in set }?.let(keys::get)
            Triple(i, prev, next)
        }
    }

    data class Restore(
        /** The new timeline: [keys] with restored entries inserted. */
        val keys: List<String>,
        /** Restored entries by key. */
        val restored: Map<String, QueueEntry>,
        /** Entries that stay hidden. */
        val hidden: List<HiddenEntry>,
    )

    /**
     * Puts back every hidden entry whose song is no longer [bad]: after the
     * entry it followed, else before the one it preceded. Entries hidden next to
     * each other keep their order. An entry whose neighbours are both gone (the
     * queue was replaced since) is dropped, whether or not it's still bad; one
     * that had no neighbours (its whole queue was bad) only returns to an empty queue.
     */
    fun restore(keys: List<String>, hidden: List<HiddenEntry>, bad: Set<String>): Restore {
        val out = keys.toMutableList()
        val pending = hidden.toMutableList()
        val restored = linkedMapOf<String, QueueEntry>()
        // The last entry put back after each anchor (START for the queue's start), so siblings stay in order.
        val chain = mutableMapOf<String, String>()
        var progress = true
        while (progress) {
            progress = false
            for (h in pending.toList()) {
                if (h.entry.songId in bad) continue
                val anchor = h.prevKey ?: START
                val after = chain[anchor]?.takeIf { it in out } ?: h.prevKey
                val pos = when {
                    after != null && out.indexOf(after) >= 0 -> out.indexOf(after) + 1
                    // Was at the start: before its old successor, or into a queue that's otherwise empty.
                    h.prevKey == null && (h.nextKey in out || (h.nextKey == null && keys.isEmpty())) -> 0
                    h.nextKey != null && out.indexOf(h.nextKey) >= 0 -> out.indexOf(h.nextKey)
                    else -> continue
                }
                out.add(pos, h.entry.key)
                restored[h.entry.key] = h.entry
                chain[anchor] = h.entry.key
                pending.remove(h)
                progress = true
            }
        }
        // Keep what can still find its place: a neighbour in the queue, or in another hidden entry.
        val live = out.toSet() + pending.map { it.entry.key }
        val kept = pending.filter { h ->
            h.entry.songId in bad &&
                ((h.prevKey == null && h.nextKey == null && keys.isEmpty()) || h.prevKey in live || h.nextKey in live)
        }
        return Restore(out, restored, kept)
    }

    private const val START = "\u0000start"
}
