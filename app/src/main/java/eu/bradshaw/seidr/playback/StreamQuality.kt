package eu.bradshaw.seidr.playback

import android.content.Context
import android.net.ConnectivityManager
import eu.bradshaw.seidr.data.Settings
import eu.bradshaw.seidr.data.api.SubsonicClient
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Settings → Save mobile data: an opt-in lower bitrate while the network is
 * metered (mobile data, a hotspot). Downloads, and streams on Wi-Fi, stay the
 * original files. Each song's bitrate suits the connection, from ExoPlayer's
 * own estimate of it ([pickKbps]).
 *
 * Transcodes are constant-bitrate MP3, which ExoPlayer can seek by bytes; each
 * seek becomes a fresh request from that time (`timeOffset`), see
 * [PlaybackService]. So the server has to convert (Navidrome needs ffmpeg) and
 * support `transcodeOffset`; [supported] says whether this one does.
 *
 * A song keeps the format it started in until it stops being current, so a
 * seek after the network changes never mixes two formats in one stream.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StreamQuality(
    private val context: Context,
    private val settings: Settings,
    private val client: SubsonicClient,
    private val scope: CoroutineScope,
) {
    private val _supported = MutableStateFlow(settings.server.value?.url?.let(settings::transcodeSupport))
    /** Whether the signed-in server can do this; null until it's been checked. */
    val supported: StateFlow<Boolean?> = _supported.asStateFlow()

    private val decided = ConcurrentHashMap<String, Int>()
    @Volatile private var checking = false

    init {
        scope.launch { settings.server.collect { s -> _supported.value = s?.url?.let(settings::transcodeSupport) } }
    }

    /** Asks the server again (Settings shows the answer). */
    fun check() {
        val url = settings.server.value?.url ?: return
        if (checking) return
        checking = true
        scope.launch {
            try {
                client.canTranscode()?.let { ok ->
                    settings.setTranscodeSupport(url, ok)
                    if (settings.server.value?.url == url) _supported.value = ok
                }
            } finally { checking = false }
        }
    }

    /** The MP3 bitrate to stream [songId] at, or 0 for the original. Called from the loader thread. */
    fun kbpsFor(songId: String): Int = decided.getOrPut(songId) {
        when {
            !settings.saveMobileData.value || !metered() -> 0
            _supported.value == true -> pickKbps(DefaultBandwidthMeter.getSingletonInstance(context).bitrateEstimate)
            else -> { if (_supported.value == null) check(); 0 }
        }
    }

    /** [songId] stopped being current: its next play decides afresh. */
    fun forget(songId: String) { decided.remove(songId) }

    private fun metered(): Boolean = context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered

    companion object {
        /**
         * A bitrate the connection carries comfortably (about a tenth of it, so a
         * song buffers well ahead of itself and survives a dip), from 128 to 320 kbps.
         */
        fun pickKbps(bitsPerSecond: Long): Int = when {
            bitsPerSecond >= 3_200_000 -> 320
            bitsPerSecond >= 1_920_000 -> 192
            else -> 128
        }
    }
}
