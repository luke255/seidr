package eu.bradshaw.seidr.vis

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decoded audio for the visualiser, kept with its timestamps so the picture
 * follows what's audible, not what's decoded: the sink takes audio a few
 * hundred ms before it reaches the speaker. Written on ExoPlayer's playback
 * thread ([TapSink]), read on the GL thread ([readAudible]). Idle unless a
 * visualiser is showing ([active]).
 */
object VisAudio {
    @Volatile var active = false

    private const val CAP = 1 shl 16 // frames: ~1.4 s at 48 kHz, well past any sink buffer
    private val ring = FloatArray(CAP * 2) // interleaved stereo
    private var written = 0L // frames ever written since the last reset
    private var rate = 44_100

    // Where buffers started, so a timestamp maps to a frame in the ring.
    private const val ANCHORS = 512
    private val anchorPts = LongArray(ANCHORS)
    private val anchorFrame = LongArray(ANCHORS)
    private var anchors = 0L

    // The audible position, as the sink last reported it.
    private var posUs = C.TIME_UNSET
    private var posAtMs = 0L
    private var playing = false

    private var read = 0L

    @Synchronized fun reset() {
        written = 0; anchors = 0; read = 0; posUs = C.TIME_UNSET
    }

    @Synchronized fun clock(positionUs: Long, isPlaying: Boolean) {
        posUs = positionUs
        posAtMs = SystemClock.uptimeMillis()
        playing = isPlaying
    }

    @Synchronized fun playing(isPlaying: Boolean) {
        if (playing == isPlaying) return
        if (posUs != C.TIME_UNSET) posUs = audibleUs()
        posAtMs = SystemClock.uptimeMillis()
        playing = isPlaying
    }

    @Synchronized fun write(buffer: ByteBuffer, ptsUs: Long, encoding: Int, channels: Int, sampleRate: Int) {
        val bytes = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        if (channels < 1) return
        if (sampleRate != rate) { reset(); rate = sampleRate }
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frameBytes = bytes * channels
        val frames = b.remaining() / frameBytes
        if (frames == 0) return

        val a = (anchors % ANCHORS).toInt()
        anchorPts[a] = ptsUs; anchorFrame[a] = written; anchors++

        val start = b.position()
        for (i in 0 until frames) {
            val at = start + i * frameBytes
            val l = sample(b, at, encoding)
            val r = if (channels > 1) sample(b, at + bytes, encoding) else l
            val slot = ((written % CAP).toInt()) * 2
            ring[slot] = l; ring[slot + 1] = r
            written++
        }
    }

    private fun sample(b: ByteBuffer, at: Int, encoding: Int): Float = when (encoding) {
        C.ENCODING_PCM_16BIT -> b.getShort(at) / 32768f
        C.ENCODING_PCM_FLOAT -> b.getFloat(at)
        C.ENCODING_PCM_32BIT -> b.getInt(at) / 2147483648f
        else -> ((b.get(at).toInt() and 0xff) or ((b.get(at + 1).toInt() and 0xff) shl 8) or (b.get(at + 2).toInt() shl 16)) / 8388608f
    }

    /** How far decoding runs ahead of what's audible, in ms: the delay [readAudible] applies. */
    @Synchronized fun aheadMs(): Long {
        if (posUs == C.TIME_UNSET || anchors == 0L) return -1
        val a = ((anchors - 1) % ANCHORS).toInt()
        val newestUs = anchorPts[a] + (written - anchorFrame[a]) * 1_000_000 / rate
        return (newestUs - audibleUs()) / 1000
    }

    private fun audibleUs(): Long {
        if (!playing) return posUs
        // Extrapolate between reports, but not far: a stalled sink stops reporting.
        val since = (SystemClock.uptimeMillis() - posAtMs).coerceAtMost(200)
        return posUs + since * 1000
    }

    /**
     * Copies the frames that became audible since the last call into [out]
     * (interleaved stereo) and returns how many. Silence while paused or
     * stopped, so the picture calms down instead of replaying the last beat.
     */
    @Synchronized fun readAudible(out: FloatArray): Int {
        if (posUs == C.TIME_UNSET || anchors == 0L || !playing) {
            val n = minOf(out.size / 2, rate / 60)
            out.fill(0f, 0, n * 2)
            return n
        }
        val now = audibleUs()
        val oldest = written - CAP
        // The newest anchor at or before the audible time.
        var target = -1L
        var i = anchors - 1
        while (i >= 0 && i >= anchors - ANCHORS) {
            val a = (i % ANCHORS).toInt()
            if (anchorFrame[a] < oldest) break
            if (anchorPts[a] <= now) {
                target = anchorFrame[a] + (now - anchorPts[a]) * rate / 1_000_000
                break
            }
            i--
        }
        if (target < 0) return 0
        target = target.coerceIn(maxOf(oldest, 0), written)

        val max = out.size / 2
        var from = read
        if (target < from) { read = target; return 0 } // went back (seek, new track)
        if (target - from > max || from < oldest) from = target - max
        from = maxOf(from, oldest, 0)
        val n = (target - from).toInt()
        for (k in 0 until n) {
            val slot = (((from + k) % CAP).toInt()) * 2
            out[k * 2] = ring[slot]; out[k * 2 + 1] = ring[slot + 1]
        }
        read = target
        return n
    }
}

/** Copies the audio the player decodes into [VisAudio], with its timestamps. */
@OptIn(UnstableApi::class)
class TapSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    private var encoding = C.ENCODING_INVALID
    private var channels = 0
    private var sampleRate = 0
    private var lastBuffer: ByteBuffer? = null
    private var lastPts = Long.MIN_VALUE

    override fun configure(config: AudioSink.AudioSinkConfig) {
        encoding = config.format.pcmEncoding
        channels = config.format.channelCount
        sampleRate = config.format.sampleRate
        super.configure(config)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        // A buffer the sink couldn't take yet comes back unchanged; copy it once.
        if (VisAudio.active && (buffer !== lastBuffer || presentationTimeUs != lastPts)) {
            VisAudio.write(buffer, presentationTimeUs, encoding, channels, sampleRate)
        }
        lastBuffer = buffer
        lastPts = presentationTimeUs
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val p = super.getCurrentPositionUs(sourceEnded)
        if (VisAudio.active && p != AudioSink.CURRENT_POSITION_NOT_SET) VisAudio.clock(p, playing)
        return p
    }

    private var playing = false

    override fun play() { playing = true; VisAudio.playing(true); super.play() }
    override fun pause() { playing = false; VisAudio.playing(false); super.pause() }

    override fun flush() { lastBuffer = null; VisAudio.reset(); super.flush() }
    override fun reset() { lastBuffer = null; VisAudio.reset(); super.reset() }
}
