package eu.bradshaw.seidr.ui.player

import kotlin.math.abs

/**
 * The song position for animation, advanced by the frame clock rather than read
 * fresh each frame. The controller's position is an estimate that corrects itself
 * whenever the session reports in, and it's read at a slightly different point in
 * each frame; both wobble, and anything that moves with the song (the lyrics, the
 * scrubber) shows the wobble as judder. So: advance by vsync time, drift gently
 * toward the player's reading, and jump only when they're far apart (a seek).
 */
internal class SongClock {
    private var ms = Double.NaN
    private var atNanos = 0L

    fun at(frameNanos: Long, playerMs: Long): Double {
        val predicted = ms + (frameNanos - atNanos) / 1e6
        val error = playerMs - predicted
        ms = if (ms.isNaN() || abs(error) > 250) playerMs.toDouble() else predicted + error * 0.03
        atNanos = frameNanos
        return ms
    }
}
