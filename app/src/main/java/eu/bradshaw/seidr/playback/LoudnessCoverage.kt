package eu.bradshaw.seidr.playback

import android.content.Context
import eu.bradshaw.seidr.data.Settings
import eu.bradshaw.seidr.data.api.SubsonicClient
import eu.bradshaw.seidr.data.api.ok
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * How much of the library has ReplayGain tags, from a random sample, and so
 * whether Even out volume is on: the user's own choice once they've made one,
 * otherwise on when most of the library is tagged ([shouldEvenOut]). Checked
 * at most daily per server; kept per device.
 */
class LoudnessCoverage(context: Context, private val settings: Settings, private val client: SubsonicClient, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)

    private val _fraction = MutableStateFlow(stored())
    /** The tagged share of the signed-in server's library, 0..1; null until it's been checked. */
    val fraction: StateFlow<Float?> = _fraction.asStateFlow()

    /** Whether Even out volume is on right now. */
    val on: StateFlow<Boolean> = combine(settings.replayGainChoice, _fraction) { choice, f -> choice ?: shouldEvenOut(f) }
        .stateIn(scope, SharingStarted.Eagerly, settings.replayGainChoice.value ?: shouldEvenOut(_fraction.value))

    init {
        scope.launch { settings.server.collect { _fraction.value = stored() } }
    }

    /** At app start: samples the library again if a day has passed. */
    fun refresh() {
        val url = settings.server.value?.url ?: return
        if (System.currentTimeMillis() - prefs.getLong("rg_checked:$url", 0) < DAY_MS) return
        scope.launch {
            val songs = runCatching { client.strict.getRandomSongs(SAMPLE).ok().randomSongs?.song }.getOrNull() ?: return@launch
            if (songs.isEmpty()) return@launch
            val f = songs.count { it.replayGain?.trackGain != null || it.replayGain?.albumGain != null }.toFloat() / songs.size
            prefs.edit().putFloat("rg_fraction:$url", f).putLong("rg_checked:$url", System.currentTimeMillis()).apply()
            if (settings.server.value?.url == url) _fraction.value = f
        }
    }

    private fun stored(): Float? = settings.server.value?.url?.let { url ->
        if (prefs.contains("rg_fraction:$url")) prefs.getFloat("rg_fraction:$url", 0f) else null
    }

    companion object {
        private const val SAMPLE = 500
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        /** Mostly tagged: the untagged rest are turned down to match ([Loudness.UNTAGGED_DB]). */
        const val THRESHOLD = 0.8f

        fun shouldEvenOut(fraction: Float?): Boolean = (fraction ?: 0f) >= THRESHOLD
    }
}
