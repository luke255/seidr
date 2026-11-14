package eu.bradshaw.seidr.data

import eu.bradshaw.seidr.data.api.PlainLyrics
import eu.bradshaw.seidr.data.api.StructuredLyrics

/**
 * One song's lyrics, ready to show. When [synced], every line has a [Line.startMs]
 * (with the file's offset already applied) and lines are in time order.
 */
data class Lyrics(val lines: List<Line>, val synced: Boolean) {
    data class Line(val startMs: Long?, val text: String)

    /**
     * Index of the line being sung at [positionMs], or -1 before the first line.
     * A blank (♪) first line covers everything before it too, so the intro's ♪
     * is lit from the very start even when a timing nudge puts the position
     * below zero.
     */
    fun lineAt(positionMs: Long): Int {
        if (!synced) return -1
        if (lines.isNotEmpty() && lines[0].text.isBlank() && positionMs < (lines[0].startMs ?: 0)) return 0
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if ((lines[mid].startMs ?: 0) <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    /**
     * These lyrics with a ♪ (a blank line) wherever the music carries on without
     * singing but the file doesn't say so: a long intro, a long gap after a line,
     * and (when [durationMs] is known, else 0) a long outro. The file's own blank
     * lines stay only if the music runs [MIN_BLANK_MS] or more before the next
     * line: many files put a brief one between verses, and a ♪ flashing up for a
     * second is noise. Back-to-back blank lines become one. Unsynced lyrics come
     * back as they are.
     *
     * A line's singing is guessed from its length ([sungMs]); the ♪ goes in where
     * that guess ends, and only if at least [MIN_BREAK_MS] of the gap is left.
     */
    fun withBreaks(durationMs: Long = 0): Lyrics {
        if (!synced || lines.isEmpty()) return this
        val out = ArrayList<Line>(lines.size + 4)
        val first = lines.first()
        if (first.text.isNotBlank() && first.startMs!! >= MIN_INTRO_MS) out += Line(0, "")
        for ((i, line) in lines.withIndex()) {
            if (line.text.isBlank()) {
                if (out.lastOrNull()?.text?.isBlank() == true) continue
                val until = lines.drop(i + 1).firstOrNull { it.text.isNotBlank() }?.startMs ?: durationMs.takeIf { it > 0 }
                if (until != null && until - line.startMs!! < MIN_BLANK_MS) continue
            }
            out += line
            val after = lines.getOrNull(i + 1)
            // A blank line next already marks where the singing stops, better than a guess.
            if (line.text.isBlank() || after?.text?.isBlank() == true) continue
            val next = after?.startMs ?: durationMs.takeIf { it > 0 } ?: continue
            val sungTo = line.startMs!! + sungMs(line.text)
            if (next - sungTo >= MIN_BREAK_MS) out += Line(sungTo, "")
        }
        return if (out == lines) this else Lyrics(out, synced = true)
    }

    companion object {
        /** An intro shorter than this goes without a ♪. */
        const val MIN_INTRO_MS = 5_000L
        /** The shortest blank line in a file that keeps its ♪. */
        const val MIN_BLANK_MS = 4_000L
        /** The least silence after a line's (guessed) singing that gets a ♪. */
        const val MIN_BREAK_MS = 6_000L

        /**
         * A generous guess at how long [text] takes to sing. Tuned on the real
         * library, where lines followed by a blank line were sung for about
         * 2.7s + 0.08s a character at the median; this sits near the 75th centile.
         */
        fun sungMs(text: String): Long = (3_000L + 100L * text.trim().length).coerceAtMost(9_000L)


        /**
         * Picks one set from the server's list: synced beats unsynced, otherwise
         * the server's order. Null when there's nothing worth showing.
         *
         * The offset follows LRC: a positive value makes lines appear sooner.
         */
        fun from(sets: List<StructuredLyrics>): Lyrics? {
            val usable = sets.filter { set -> set.line.any { it.value.isNotBlank() } }
            val pick = usable.firstOrNull { set -> set.synced && set.line.all { it.start != null } } ?: usable.firstOrNull()
                ?: return null
            val synced = pick.synced && pick.line.all { it.start != null }
            val lines = pick.line.map { Line(if (synced) it.start!! - pick.offset else null, it.value.trimEnd()) }
            return Lyrics(if (synced) lines.sortedBy { it.startMs } else lines, synced)
        }

        fun from(plain: PlainLyrics?): Lyrics? {
            val text = plain?.value?.takeIf { it.isNotBlank() } ?: return null
            return Lyrics(text.trim().lines().map { Line(null, it.trimEnd()) }, synced = false)
        }
    }
}
