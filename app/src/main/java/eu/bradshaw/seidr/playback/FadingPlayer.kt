package eu.bradshaw.seidr.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the media session controls: the ExoPlayer, with short volume fades on
 * play, pause and skips instead of hard cuts. Every controller (app,
 * notification, headset, Android Auto) goes through here. The service's own
 * calls (error recovery, queue restore) use the ExoPlayer directly and don't
 * fade, and nor do ExoPlayer's own pauses (audio focus, headphones unplugged).
 *
 * A fade-out holds back the real pause or seek. While it's held, the pending
 * future makes [ForwardingSimpleBasePlayer] report the state the command asked
 * for (paused, or on the new track), so controllers don't flick back to the old
 * state mid-fade. Any new command first carries out whatever is held back, so
 * rapid taps never lose a skip. Skipping forward while paused also starts
 * playback; skipping back keeps the play state.
 */
@OptIn(UnstableApi::class)
class FadingPlayer(private val exo: ExoPlayer, private val scope: CoroutineScope) : ForwardingSimpleBasePlayer(exo) {
    private var job: Job? = null
    private var held: (() -> Unit)? = null
    private var heldDone: SettableFuture<Unit>? = null
    private var holdingPause = false
    /** Perceived loudness 0..1; the player volume is its square, which sounds even. */
    private var level = 1f
    private var pauseFadeMs = PAUSE_FADE_MS

    /** ReplayGain's volume for the current song ([Loudness]); the fades work under it. */
    var gain = 1f
        set(value) { field = value.coerceIn(0f, 1f); exo.volume = level * level * field }

    /** Pauses after a slow fade (the sleep timer); playing again during it cancels the pause. */
    fun pauseSlowly(fadeMs: Int) {
        pauseFadeMs = fadeMs
        try { pause() } finally { pauseFadeMs = PAUSE_FADE_MS }
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
        if (playWhenReady) fadeIn() else fadeOutThenPause()

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val changesTrack = when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
            Player.COMMAND_SEEK_TO_MEDIA_ITEM -> mediaItemIndex != exo.currentMediaItemIndex
            else -> false
        }
        if (!changesTrack) {
            // Scrubbing within the track stays instant.
            flush(); setLevel(1f)
            return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
        flush()
        val forward = seekCommand == Player.COMMAND_SEEK_TO_NEXT ||
            seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
        // "Paused" as the play button sees it: this includes a restored queue that
        // isn't prepared yet, and audio focus held by another app.
        if (forward && Util.shouldShowPlayButton(exo) && exo.hasNextMediaItem()) {
            // Skipping forward while paused means "play the next one"; back doesn't.
            super.handleSeek(mediaItemIndex, positionMs, seekCommand)
            return fadeIn()
        }
        if (!exo.isPlaying) {
            super.handleSeek(mediaItemIndex, positionMs, seekCommand)
            setLevel(1f)
            return Futures.immediateVoidFuture()
        }
        val done = hold { super.handleSeek(mediaItemIndex, positionMs, seekCommand) }
        job = scope.launch {
            ramp(0f, SKIP_FADE_OUT_MS)
            runHeld()
            awaitReady()
            ramp(1f, SKIP_FADE_IN_MS)
        }
        return done
    }

    private fun fadeIn(): ListenableFuture<*> {
        if (holdingPause) {
            // Play during a pause fade: drop the pause and come back up from here.
            job?.cancel()
            dropHeld()
            job = scope.launch { ramp(1f, FADE_IN_MS) }
            return Futures.immediateVoidFuture()
        }
        // A restored queue isn't prepared (PlaybackService doesn't, to stay off the network).
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        if (exo.playWhenReady) {
            // Already playing (say, play() straight after a skip): let a running fade finish.
            if (job?.isActive != true) setLevel(1f)
            return super.handleSetPlayWhenReady(true)
        }
        flush()
        setLevel(0f)
        super.handleSetPlayWhenReady(true)
        job = scope.launch { awaitReady(); ramp(1f, FADE_IN_MS) }
        return Futures.immediateVoidFuture()
    }

    private fun fadeOutThenPause(): ListenableFuture<*> {
        heldDone?.takeIf { holdingPause }?.let { return it }
        flush()
        if (!exo.isPlaying) {
            super.handleSetPlayWhenReady(false)
            setLevel(1f)
            return Futures.immediateVoidFuture()
        }
        val done = hold { super.handleSetPlayWhenReady(false); setLevel(1f) }
        holdingPause = true
        val fadeMs = pauseFadeMs
        job = scope.launch { ramp(0f, fadeMs); runHeld() }
        return done
    }

    private fun hold(action: () -> Unit): SettableFuture<Unit> {
        val done = SettableFuture.create<Unit>()
        held = action; heldDone = done
        return done
    }

    /** Stops any fade and carries out a held-back pause or seek straight away. */
    private fun flush() {
        job?.cancel(); job = null
        runHeld()
    }

    private fun runHeld() {
        val a = held
        val done = heldDone
        held = null; heldDone = null; holdingPause = false
        a?.invoke()
        done?.set(Unit)
    }

    private fun dropHeld() {
        val done = heldDone
        held = null; heldDone = null; holdingPause = false
        done?.set(Unit)
    }

    private suspend fun awaitReady() {
        var waited = 0
        while (exo.playbackState == Player.STATE_BUFFERING && waited < 3000) { delay(20); waited += 20 }
    }

    private suspend fun ramp(to: Float, ms: Int) {
        val from = level
        val steps = (ms / 16).coerceAtLeast(1)
        for (i in 1..steps) {
            setLevel(from + (to - from) * i / steps)
            delay(16)
        }
    }

    private fun setLevel(v: Float) {
        level = v.coerceIn(0f, 1f)
        exo.volume = level * level * gain
    }

    private companion object {
        const val FADE_IN_MS = 250
        const val PAUSE_FADE_MS = 250
        const val SKIP_FADE_OUT_MS = 150
        const val SKIP_FADE_IN_MS = 200
    }
}
