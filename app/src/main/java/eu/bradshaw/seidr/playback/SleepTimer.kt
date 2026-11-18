package eu.bradshaw.seidr.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The sleep timer (Now Playing's ⋮): set here, carried out by [PlaybackService],
 * which fades the music out slowly and pauses. Not kept across a restart.
 */
class SleepTimer {
    sealed interface Mode {
        /** Stop at [atElapsedMs] (`SystemClock.elapsedRealtime`). */
        data class At(val atElapsedMs: Long) : Mode
        /** Stop when the current song ends. */
        data object EndOfSong : Mode
    }

    private val _mode = MutableStateFlow<Mode?>(null)
    val mode: StateFlow<Mode?> = _mode.asStateFlow()

    fun stopIn(minutes: Int) { _mode.value = Mode.At(SystemClock.elapsedRealtime() + minutes * 60_000L) }
    fun stopAtEndOfSong() { _mode.value = Mode.EndOfSong }
    fun cancel() { _mode.value = null }

    /** The service did it: off, unless it's been set again since. */
    fun done(mode: Mode) { _mode.compareAndSet(mode, null) }

    companion object {
        val CHOICES_MIN = listOf(15, 30, 45, 60, 90)
        /** How long the music takes to fade away; it ends at the time set. */
        const val FADE_MS = 20_000
    }
}
