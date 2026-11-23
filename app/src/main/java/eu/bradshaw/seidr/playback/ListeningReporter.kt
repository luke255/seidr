package eu.bradshaw.seidr.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.common.Player
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.connect.Peer
import eu.bradshaw.seidr.data.DeviceStatus
import eu.bradshaw.seidr.data.ListeningRules
import eu.bradshaw.seidr.data.Output
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Tells others what this device plays (Listening now), from whichever player
 * the session drives: this device's own, or a Cast device it plays through.
 * While it controls another Seiðr device, that one reports itself and this
 * one reports stopped. Changes are batched: a skip is a burst of events.
 */
class ListeningReporter(
    private val context: Context,
    private val graph: Graph,
    /** The service's (main-thread) scope. */
    private val scope: CoroutineScope,
    /** Android Auto is connected: the music's in the car. */
    private val inCar: () -> Boolean,
) {
    private var player: Player? = null
    private var cast: Peer? = null
    private var job: Job? = null
    private val audio = context.getSystemService(AudioManager::class.java)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,
                )
            ) changed()
        }
    }

    private val outputs = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = changed()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = changed()
    }

    fun start() {
        audio.registerAudioDeviceCallback(outputs, null)
        // Switched back on: say what's playing now.
        scope.launch { graph.listening.sharing.drop(1).collect { if (it) changed() } }
    }

    /** The session's player changed: [p] is this device's or a Cast device's (with [castTo]); null while controlling another device. */
    fun follow(p: Player?, castTo: Peer? = null) {
        player?.removeListener(listener)
        player = p
        cast = castTo
        p?.addListener(listener)
        changed()
    }

    /** Something may have changed: report in a moment, once a burst of events has settled. */
    fun changed() {
        job?.cancel()
        job = scope.launch {
            delay(SETTLE_MS)
            val status = snapshot()
            graph.scope.launch(Dispatchers.IO) { graph.listening.publish(status) }
        }
    }

    /** The service is going: report stopped straight away (its scope goes with it). */
    fun stop() {
        job?.cancel()
        player?.removeListener(listener)
        player = null
        runCatching { audio.unregisterAudioDeviceCallback(outputs) }
        val status = snapshot()
        graph.scope.launch(Dispatchers.IO) { graph.listening.publish(status) }
    }

    private fun snapshot(): DeviceStatus {
        val connect = graph.connect
        val now = System.currentTimeMillis()
        val p = player
        val item = p?.currentMediaItem
        val state = when {
            p == null || item == null -> DeviceStatus.STOP
            p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED -> DeviceStatus.STOP
            p.playWhenReady -> DeviceStatus.PLAY
            else -> DeviceStatus.PAUSE
        }
        if (state == DeviceStatus.STOP || p == null || item == null) return DeviceStatus(connect.name, connect.kind, state = DeviceStatus.STOP, at = now, until = now)
        val entry = item.toEntry()
        val position = p.currentPosition.coerceAtLeast(0)
        val duration = p.duration.takeIf { it > 0 } ?: (entry.duration * 1000L)
        return DeviceStatus(
            name = connect.name,
            kind = connect.kind,
            output = output(),
            song = entry.songId,
            playlist = entry.ctxPlaylist,
            state = state,
            position = position,
            at = now,
            until = ListeningRules.until(state, now, position, duration),
        )
    }

    /** Where the sound goes: a Cast device by name; headphones, Bluetooth or the car by kind only. */
    private fun output(): Output? {
        cast?.let { return Output(it.kind, it.name) }
        if (inCar()) return Output(ListeningRules.OUT_CAR)
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val device = runCatching { audio.getAudioDevicesForAttributes(attrs).firstOrNull() }.getOrNull() ?: return null
        return when (device.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET ->
                Output(ListeningRules.OUT_HEADPHONES)
            AudioDeviceInfo.TYPE_BLE_HEADSET -> Output(ListeningRules.OUT_HEADPHONES)
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST ->
                Output(ListeningRules.OUT_BLUETOOTH)
            // The device's own speaker, or a TV's screen.
            else -> null
        }
    }

    private companion object {
        const val SETTLE_MS = 2_000L
    }
}
