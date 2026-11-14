package eu.bradshaw.seidr.connect

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.playback.QueueEntry
import eu.bradshaw.seidr.playback.toEntry
import eu.bradshaw.seidr.playback.toMediaItem

/**
 * Another device's playback, as a [Player]. PlaybackService puts it in the
 * media session while this device is a remote, so the app, the notification,
 * the lockscreen and the volume keys all control that device instead.
 *
 * Commands go out as [RemoteOp]s through [send] (which returns its sequence
 * number, or null when the link is down). Each one's future completes when the
 * host's state acks it; until then [SimpleBasePlayer] shows the state the
 * command asked for, so the UI doesn't wait on the network.
 */
@OptIn(UnstableApi::class)
class RemotePlayer(private val graph: Graph, private val send: (RemoteOp) -> Long?) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val handler = Handler(Looper.getMainLooper())
    private var last: ConnectMessage.State? = null
    private var receivedAt = 0L
    /** The host's queue as of [last]. */
    var queue: List<QueueEntry> = emptyList()
        private set
    private var items: ImmutableList<MediaItemData> = ImmutableList.of()
    private val waiting = mutableListOf<Pair<Long, SettableFuture<Unit>>>()

    val state: ConnectMessage.State? get() = last

    /** The host's position now, extrapolated from its last report while it plays. */
    fun positionNow(): Long {
        val s = last ?: return 0
        val playing = s.playWhenReady && s.playbackState == Player.STATE_READY
        val pos = s.positionMs + if (playing) SystemClock.elapsedRealtime() - receivedAt else 0
        return if (s.durationMs > 0) pos.coerceAtMost(s.durationMs) else pos
    }

    fun update(s: ConnectMessage.State) {
        s.queue?.let { q ->
            queue = q
            val seen = HashSet<String>()
            // Uids must be unique; keys are, but don't trust a peer with an assertion.
            items = ImmutableList.copyOf(q.mapIndexed { i, e ->
                MediaItemData.Builder(if (seen.add(e.key)) e.key else "${e.key}#$i")
                    .setMediaItem(e.toMediaItem(graph))
                    .setDurationUs(if (e.duration > 0) e.duration * 1_000_000L else C.TIME_UNSET)
                    .build()
            })
        }
        last = s
        receivedAt = SystemClock.elapsedRealtime()
        val done = waiting.filter { it.first <= s.ack }
        waiting.removeAll(done)
        invalidateState()
        done.forEach { it.second.set(Unit) }
    }

    /** The link is gone: nothing more will be acked. */
    fun detach() {
        val all = waiting.toList()
        waiting.clear()
        all.forEach { it.second.set(Unit) }
    }

    override fun getState(): State {
        val s = last
        val b = State.Builder().setAvailableCommands(COMMANDS)
        if (s == null || items.isEmpty()) {
            return b.setPlaybackState(Player.STATE_IDLE)
                .setDeviceInfo(deviceInfo(s)).setDeviceVolume(s?.volume ?: 0)
                .build()
        }
        val playing = s.playWhenReady && s.playbackState == Player.STATE_READY
        val pos = positionNow()
        return b
            .setPlaylist(items)
            .setCurrentMediaItemIndex(s.index.coerceIn(0, items.size - 1))
            .setPlayWhenReady(s.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(s.playbackState)
            .setRepeatMode(s.repeat)
            .setContentPositionMs(if (playing) PositionSupplier.getExtrapolating(pos, 1f) else PositionSupplier.getConstant(pos))
            .setContentBufferedPositionMs(PositionSupplier.getConstant(maxOf(pos, s.bufferedMs)))
            .setDeviceInfo(deviceInfo(s))
            .setDeviceVolume(s.volume.coerceIn(0, s.maxVolume.coerceAtLeast(0)))
            .build()
    }

    private fun deviceInfo(s: ConnectMessage.State?) =
        DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(s?.maxVolume ?: 0).build()

    private fun op(o: RemoteOp): ListenableFuture<*> {
        val seq = send(o) ?: return Futures.immediateVoidFuture()
        val f = SettableFuture.create<Unit>()
        waiting += seq to f
        // A lost ack mustn't freeze the state for good.
        handler.postDelayed({ if (waiting.removeAll { it.second === f }) { f.set(Unit) } }, ACK_TIMEOUT_MS)
        return f
    }

    private fun List<MediaItem>.entries() = map { it.toEntry() }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean) = op(RemoteOp.Play(playWhenReady))
    override fun handlePrepare() = op(RemoteOp.Prepare)
    override fun handleStop() = op(RemoteOp.Stop)
    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handleSetRepeatMode(repeatMode: Int) = op(RemoteOp.Repeat(repeatMode))

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) = op(
        RemoteOp.SetQueue(
            mediaItems.entries(),
            if (startIndex == C.INDEX_UNSET) 0 else startIndex,
            if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs,
        )
    )

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>) = op(RemoteOp.Add(index, mediaItems.entries()))
    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) = op(RemoteOp.Move(fromIndex, toIndex, newIndex))
    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int) = op(RemoteOp.Remove(fromIndex, toIndex))

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int) =
        op(RemoteOp.Seek(mediaItemIndex, if (positionMs == C.TIME_UNSET) 0 else positionMs, seekCommand))

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int) = op(RemoteOp.Volume(deviceVolume))
    override fun handleIncreaseDeviceVolume(flags: Int) = op(RemoteOp.Volume(((last?.volume ?: 0) + 1).coerceAtMost(last?.maxVolume ?: 0)))
    override fun handleDecreaseDeviceVolume(flags: Int) = op(RemoteOp.Volume(((last?.volume ?: 0) - 1).coerceAtLeast(0)))

    private companion object {
        const val ACK_TIMEOUT_MS = 5_000L
        // No shuffle: Seiðr's own reorders the queue (see PlayerConnection).
        val COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS, Player.COMMAND_RELEASE,
        ).build()
    }
}
