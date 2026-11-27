package eu.bradshaw.seidr.cast

import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.connect.Peer
import eu.bradshaw.seidr.connect.RemoteOp
import eu.bradshaw.seidr.playback.NowPlaying
import eu.bradshaw.seidr.playback.QueueEntry
import eu.bradshaw.seidr.playback.toEntry
import eu.bradshaw.seidr.playback.toMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A Cast device's playback, as a [Player]. ConnectSession puts it in the
 * media session while Seiðr plays there, so the app, notification,
 * lockscreen and volume keys drive the speaker.
 *
 * The speaker fetches each song from the server itself (the original file,
 * the same link ExoPlayer streams); the phone only sends commands. Seiðr's
 * queue stays here: the receiver holds a short window of it ([CastQueue]),
 * topped up as songs change, and repeat-all is ours. Each item's `contentId`
 * is its queue key, so the receiver's status names the slot it's on.
 *
 * This device scrobbles what the speaker plays, since the speaker can't.
 * [onGone] hands playback back when the speaker is lost or taken over.
 */
@OptIn(UnstableApi::class)
class CastPlayer(
    private val graph: Graph,
    val peer: Peer,
    private val onGone: (CastPlayer, String) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val link = CastLink(peer.hosts, peer.port, scope)
    private val relay = CastRelay(graph)
    /** Held while the speaker plays: it fetches from [relay], so this device has to stay awake and on Wi-Fi. */
    private val wake = graph.app.getSystemService(android.os.PowerManager::class.java)
        .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "seidr:cast").apply { setReferenceCounted(false) }
    @Suppress("DEPRECATION") // What ExoPlayer's own network wake mode uses.
    private val wifi = graph.app.getSystemService(android.net.wifi.WifiManager::class.java)
        .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "seidr:cast").apply { setReferenceCounted(false) }
    private val app = graph.app

    // Seiðr's queue, which this player owns.
    private var queue: List<QueueEntry> = emptyList()
    private var items: ImmutableList<MediaItemData> = ImmutableList.of()
    private var index = 0
    private var repeat = Player.REPEAT_MODE_OFF
    private var wantPlay = false
    private var wantSetAt = 0L
    /** Where to start when the window is next loaded (and the position shown until then). */
    private var startMs = 0L

    // The receiver's side.
    private var status: CastProtocol.MediaStatus? = null
    private var statusAt = 0L
    private val itemKeys = mutableMapOf<Int, String>()
    /** The slot a QUEUE_LOAD in flight is for. */
    private var loadingKey: String? = null
    private var ended = false
    private var level = 0.0
    private var step = DEFAULT_STEP
    private var errors = 0
    private var toldError = false

    // Scrobbling, as PlaybackService does it.
    private var currentKey: String? = null
    private var playedMs = 0L
    private var startedAt = 0L
    private var scrobbled = false
    private var tick: Job? = null

    init {
        link.onMedia = ::onMedia
        link.onReceiver = ::onReceiver
        link.onClosed = ::onClosed
    }

    /** Connects and launches the receiver; the error to show, or null. */
    suspend fun connect(): String? = try {
        link.open()
        relay.start()
        null
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.i(TAG, "couldn't cast to ${peer.name}: $e")
        link.close()
        app.getString(R.string.cast_unreachable, peer.name)
    }

    /** Takes over playback as [h] left it (nothing to play when null or empty). */
    fun start(h: RemoteOp.SetQueue?) {
        startTicking()
        if (h == null || h.entries.isEmpty()) { invalidateState(); return }
        setQueue(h.entries, h.index, h.positionMs)
        h.repeat?.let { repeat = it }
        want(h.play == true)
        invalidateState()
        scope.launch { load() }
    }

    /** Where playback has got to, to carry to the next device. */
    fun handoff(): RemoteOp.SetQueue? = queue.takeIf { it.isNotEmpty() }?.let {
        RemoteOp.SetQueue(it, index, positionNow(), play = wantPlay && !ended, repeat = repeat)
    }

    private var leaving = false

    /**
     * Playback is leaving the speaker: it stops, and the link closes. Safe to
     * call before the session releases this player.
     */
    fun leave() {
        if (leaving) return
        leaving = true
        tick?.cancel()
        reconcileJob?.cancel()
        graph.scope.launch(Dispatchers.Main) {
            link.stopApp()
            relay.close()
            locks(false)
            scope.cancel()
        }
    }

    // --- The receiver's reports -------------------------------------------------------

    private fun onReceiver(s: CastProtocol.ReceiverStatus) {
        val mine = link.app
        if (mine != null && s.apps.none { it.sessionId == mine.sessionId }) {
            gone(app.getString(R.string.cast_taken, peer.name))
            return
        }
        s.volume?.let { level = if (s.muted) 0.0 else it }
        s.step?.takeIf { it > 0 }?.let { step = it }
        invalidateState()
    }

    private fun onMedia(s: CastProtocol.MediaStatus?) {
        status = s
        statusAt = SystemClock.elapsedRealtime()
        Log.d(TAG, "status: ${s?.playerState} ${s?.idleReason ?: ""} item ${s?.currentItemId} ${s?.contentId ?: ""} at ${s?.currentTimeS}")
        if (s == null) { invalidateState(); return }
        if (s.contentId != null && s.currentItemId != null) itemKeys[s.currentItemId] = s.contentId
        val key = s.contentId ?: s.currentItemId?.let(itemKeys::get)
        // The receiver reports a fresh load as IDLE before it buffers: still loading.
        val loaded = key != null && key == loadingKey && (s.playerState != "IDLE" || s.idleReason != null)
        if (loaded) loadingKey = null
        val i = key?.let { k -> queue.indexOfFirst { it.key == k } } ?: -1
        val moved = i >= 0 && i != index && loadingKey == null
        if (moved) index = i
        when (s.playerState) {
            "PLAYING" -> { errors = 0; ended = false; if (settled()) wantPlay = true }
            "PAUSED" -> if (settled()) wantPlay = false
            "IDLE" -> if (loadingKey == null) when (s.idleReason) {
                "FINISHED" -> finished()
                "ERROR" -> failed()
            }
        }
        transitioned()
        invalidateState()
        // After a load, learn the receiver's item ids, so a skip can jump to the next song there.
        if (moved || loaded) reconcileSoon()
    }

    /** The speaker ran out of queue: the end, or it got ahead of the window. */
    private fun finished() {
        val next = nextIndex()
        if (next == null) { ended = true; return }
        index = next
        startMs = 0
        scope.launch { load() }
    }

    /** The current song wouldn't play there: skip it, but not forever. */
    private fun failed() {
        val song = queue.getOrNull(index)?.title.orEmpty()
        Log.w(TAG, "${peer.name} couldn't play ${queue.getOrNull(index)?.songId}")
        if (!toldError) { toldError = true; graph.connect.tell(app.getString(R.string.cast_load_failed, peer.name, song)) }
        val next = nextIndex()
        if (++errors >= MAX_ERRORS || next == null) { wantPlay = false; ended = next == null; return }
        index = next
        startMs = 0
        scope.launch { load() }
    }

    private fun onClosed() {
        scope.launch {
            repeat(2) {
                delay(1_500)
                if (link.rejoin()) return@launch
            }
            gone(app.getString(R.string.connect_lost_connection, peer.name))
        }
    }

    private fun gone(reason: String) {
        if (leaving) return
        leaving = true
        tick?.cancel()
        link.close()
        relay.close()
        locks(false)
        onGone(this, reason)
        scope.cancel()
    }

    /** Too many songs in a row wouldn't play: nothing more is tried until the user plays, skips or picks. */
    private fun gaveUp() = errors >= MAX_ERRORS

    /** The user asked for something: worth trying again. */
    private fun retry() { errors = 0 }

    /** A recent command from here outranks the receiver's report for a moment. */
    private fun settled() = SystemClock.elapsedRealtime() - wantSetAt > SETTLE_MS

    private fun want(play: Boolean) { wantPlay = play; wantSetAt = SystemClock.elapsedRealtime() }

    // --- Driving the receiver -----------------------------------------------------------

    private fun castItem(e: QueueEntry): CastProtocol.Item? {
        // An emulator's own address is behind the laptop's NAT; the laptop forwards the relay's port.
        val host = eu.bradshaw.seidr.connect.emulatorLanHost ?: link.localHost ?: return null
        val url = relay.songUrl(host, e.songId) ?: return null
        return CastProtocol.Item(
            key = e.key,
            url = url,
            // Only steers the receiver's choice of player; the audio element sniffs the rest.
            contentType = "audio/flac",
            title = e.title, artist = e.artist, album = e.album,
            art = e.coverArt?.let { relay.artUrl(host, it) },
            durationS = e.duration,
        )
    }

    private fun upcoming() = CastQueue.upcoming(queue.map { it.key }, index, repeat == Player.REPEAT_MODE_ALL)

    /** Loads the window afresh from [index] at [startMs]. */
    private suspend fun load() {
        val cur = queue.getOrNull(index) ?: return
        val keys = listOf(cur.key) + upcoming()
        val byKey = queue.associateBy { it.key }
        val window = keys.mapNotNull { k -> byKey[k]?.let(::castItem) }
        if (window.isEmpty()) return
        loadingKey = cur.key
        ended = false
        itemKeys.clear()
        invalidateState()
        // The reply comes once the song has started, which can take a while; statuses arrive meanwhile.
        val reply = link.media(LOAD_TIMEOUT_MS) { CastProtocol.queueLoad(it, window, wantPlay, startMs / 1000.0, repeat == Player.REPEAT_MODE_ONE) }
        if (loadingKey != cur.key) return
        when (reply?.type()) {
            "LOAD_FAILED", "INVALID_REQUEST", "INVALID_PLAYER_STATE" -> {
                Log.w(TAG, "QUEUE_LOAD: ${reply.type()}")
                loadingKey = null
                failed()
            }
            // No word either way: ask where it's got to.
            null -> link.media { CastProtocol.type("GET_STATUS", it) }
        }
        invalidateState()
    }

    private var reconcileJob: Job? = null

    /** Brings the receiver's window in line with the queue, once things settle. */
    private fun reconcileSoon() {
        reconcileJob?.cancel()
        reconcileJob = scope.launch { delay(300); reconcile() }
    }

    private suspend fun reconcile() {
        // Given up after failures: reloading would only fail again, over and over, until the user acts.
        if (gaveUp()) return
        val session = link.mediaSessionId ?: return
        val cur = queue.getOrNull(index) ?: return
        if (loadingKey != null) return
        val currentId = status?.currentItemId
        if (currentId == null || itemKeys[currentId] != cur.key) {
            startMs = positionNow().takeIf { currentId != null && itemKeys[currentId] == cur.key } ?: 0
            load()
            return
        }
        val ids = link.media { CastProtocol.queueItemIds(it, session) }?.let(CastProtocol::itemIds) ?: return
        val unknown = ids.filter { it !in itemKeys }
        if (unknown.isNotEmpty()) {
            link.media { CastProtocol.queueItems(it, session, unknown) }?.let { itemKeys += CastProtocol.itemKeys(it) }
        }
        val plan = CastQueue.plan(ids.mapNotNull { id -> itemKeys[id]?.let { id to it } }, currentId, upcoming())
            ?: run { startMs = positionNow(); load(); return }
        if (plan.empty) return
        if (plan.remove.isNotEmpty()) link.media { CastProtocol.queueRemove(it, session, plan.remove) }
        plan.remove.forEach(itemKeys::remove)
        val byKey = queue.associateBy { it.key }
        val add = plan.insert.mapNotNull { k -> byKey[k]?.let(::castItem) }
        if (add.isNotEmpty()) link.media { CastProtocol.queueInsert(it, session, add) }
    }

    private fun media(type: String) {
        val session = link.mediaSessionId ?: return
        scope.launch { link.media { CastProtocol.mediaCommand(type, it, session) } }
    }

    private fun nextIndex(): Int? = when {
        index + 1 < queue.size -> index + 1
        repeat == Player.REPEAT_MODE_ALL && queue.isNotEmpty() -> 0
        else -> null
    }

    // --- Scrobbling -----------------------------------------------------------------------

    private fun startTicking() {
        tick?.cancel()
        tick = scope.launch {
            while (isActive) {
                delay(1_000)
                locks(wantPlay && !ended)
                if (status?.playerState == "PLAYING" && loadingKey == null) {
                    playedMs += 1_000
                    maybeScrobble()
                }
            }
        }
    }

    /** Called every second while casting: the wake lock's timeout is renewed each time, so it can't outlive us. */
    private fun locks(on: Boolean) {
        if (on) {
            wake.acquire(WAKE_TIMEOUT_MS)
            if (!wifi.isHeld) wifi.acquire()
        } else {
            if (wake.isHeld) wake.release()
            if (wifi.isHeld) wifi.release()
        }
    }

    private fun transitioned() {
        val e = queue.getOrNull(index) ?: return
        if (e.key == currentKey || status?.playerState !in PLAYING_STATES) return
        currentKey = e.key
        playedMs = 0; scrobbled = false; startedAt = System.currentTimeMillis()
        graph.scope.launch(Dispatchers.IO) { NowPlaying.nowPlaying(graph, e.songId) }
    }

    private fun maybeScrobble() {
        if (scrobbled) return
        val e = queue.firstOrNull { it.key == currentKey } ?: return
        val durMs = e.duration * 1000L
        if (durMs <= 0 || playedMs < minOf(durMs / 2, 240_000L)) return
        scrobbled = true
        val at = startedAt
        graph.scope.launch(Dispatchers.IO) { NowPlaying.scrobble(graph, e.songId, at) }
    }

    // --- Player ------------------------------------------------------------------------------

    private fun setQueue(entries: List<QueueEntry>, start: Int, positionMs: Long) {
        queue = entries
        index = start.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
        startMs = positionMs.coerceAtLeast(0)
        rebuildItems()
    }

    private fun rebuildItems() {
        relay.songs = queue.mapTo(HashSet()) { it.songId }
        val seen = HashSet<String>()
        items = ImmutableList.copyOf(queue.mapIndexed { i, e ->
            MediaItemData.Builder(if (seen.add(e.key)) e.key else "${e.key}#$i")
                .setMediaItem(e.toMediaItem(graph))
                .setDurationUs(if (e.duration > 0) e.duration * 1_000_000L else C.TIME_UNSET)
                .build()
        })
    }

    /** The speaker's position now, extrapolated while it plays. */
    private fun positionNow(): Long {
        val s = status
        val key = queue.getOrNull(index)?.key
        if (s == null || loadingKey != null || s.currentItemId?.let(itemKeys::get) != key) return startMs
        val playing = s.playerState == "PLAYING"
        val pos = (s.currentTimeS * 1000).toLong() + if (playing) SystemClock.elapsedRealtime() - statusAt else 0
        val dur = durationMs()
        return if (dur > 0) pos.coerceAtMost(dur) else pos
    }

    private fun durationMs(): Long {
        status?.durationS?.takeIf { it > 0 && loadingKey == null }?.let { return (it * 1000).toLong() }
        return (queue.getOrNull(index)?.duration ?: 0) * 1000L
    }

    override fun getState(): State {
        val b = State.Builder().setAvailableCommands(COMMANDS)
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(maxVolume()).build())
            .setDeviceVolume(volumeSteps())
        if (queue.isEmpty()) return b.setPlaybackState(Player.STATE_IDLE).build()
        val s = status
        val state = when {
            ended -> Player.STATE_ENDED
            loadingKey != null -> Player.STATE_BUFFERING
            s == null -> Player.STATE_IDLE
            s.playerState == "BUFFERING" -> Player.STATE_BUFFERING
            s.playerState == "PLAYING" || s.playerState == "PAUSED" -> Player.STATE_READY
            else -> Player.STATE_IDLE
        }
        val pos = positionNow()
        val moving = state == Player.STATE_READY && s?.playerState == "PLAYING"
        return b.setPlaylist(items)
            .setCurrentMediaItemIndex(index.coerceIn(0, items.size - 1))
            .setPlayWhenReady(wantPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(state)
            .setRepeatMode(repeat)
            .setContentPositionMs(if (moving) PositionSupplier.getExtrapolating(pos, 1f) else PositionSupplier.getConstant(pos))
            .build()
    }

    private fun maxVolume() = (1 / step).roundToInt().coerceAtLeast(1)
    private fun volumeSteps() = (level / step).roundToInt().coerceIn(0, maxVolume())

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        want(playWhenReady)
        if (playWhenReady && gaveUp()) { retry(); scope.launch { load() }; return done() }
        when {
            playWhenReady && (ended || status == null) -> {
                if (ended) startMs = 0
                ended = false
                scope.launch { load() }
            }
            status != null -> media(if (playWhenReady) "PLAY" else "PAUSE")
        }
        return done()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (status == null && loadingKey == null && queue.isNotEmpty()) scope.launch { load() }
        return done()
    }

    override fun handleStop(): ListenableFuture<*> {
        want(false)
        startMs = positionNow()
        media("STOP")
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> {
        tick?.cancel()
        // A leave() under way closes things itself, once the speaker has been told.
        if (!leaving) { link.close(); relay.close(); locks(false); scope.cancel() }
        return done()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        val oneChanged = (repeat == Player.REPEAT_MODE_ONE) != (repeatMode == Player.REPEAT_MODE_ONE)
        repeat = repeatMode
        val session = link.mediaSessionId
        if (oneChanged && session != null) {
            scope.launch { link.media { CastProtocol.queueRepeat(it, session, repeatMode == Player.REPEAT_MODE_ONE) } }
        }
        reconcileSoon()
        return done()
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        retry()
        setQueue(
            mediaItems.map { it.toEntry() },
            if (startIndex == C.INDEX_UNSET) 0 else startIndex,
            if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs,
        )
        if (queue.isEmpty()) media("STOP") else scope.launch { load() }
        return done()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        val at = index.coerceIn(0, queue.size)
        val wasEmpty = queue.isEmpty()
        queue = queue.toMutableList().apply { addAll(at, mediaItems.map { it.toEntry() }) }
        if (!wasEmpty && at <= this.index) this.index += mediaItems.size
        rebuildItems()
        if (wasEmpty) { startMs = 0; scope.launch { load() } } else reconcileSoon()
        return done()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val key = queue.getOrNull(index)?.key
        val list = queue.toMutableList()
        val moving = list.subList(fromIndex, toIndex).toList()
        list.subList(fromIndex, toIndex).clear()
        list.addAll(newIndex.coerceIn(0, list.size), moving)
        queue = list
        index = queue.indexOfFirst { it.key == key }.coerceAtLeast(0)
        rebuildItems()
        reconcileSoon()
        return done()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val removingCurrent = index in fromIndex until toIndex
        queue = queue.toMutableList().apply { subList(fromIndex, toIndex).clear() }
        rebuildItems()
        when {
            queue.isEmpty() -> { index = 0; media("STOP") }
            removingCurrent -> {
                index = fromIndex.coerceAtMost(queue.size - 1)
                startMs = 0
                scope.launch { load() }
            }
            else -> {
                if (index >= toIndex) index -= toIndex - fromIndex
                reconcileSoon()
            }
        }
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val pos = if (positionMs == C.TIME_UNSET) 0 else positionMs.coerceAtLeast(0)
        if (mediaItemIndex == C.INDEX_UNSET || queue.isEmpty()) return done()
        if (gaveUp()) {
            // The receiver's queue is in an error state: start afresh where asked.
            retry()
            index = mediaItemIndex.coerceIn(0, queue.size - 1)
            startMs = pos
            scope.launch { load() }
            return done()
        }
        val session = link.mediaSessionId
        val target = queue.getOrNull(mediaItemIndex)?.key
        val held = itemKeys.entries.firstOrNull { it.value == target }?.key
        if (mediaItemIndex != index && held != null && session != null && !ended && loadingKey == null) {
            // Already on the receiver (the preloaded next song, say): jump to it there.
            index = mediaItemIndex
            loadingKey = target
            startMs = pos
            invalidateState()
            scope.launch { link.media { CastProtocol.queueJump(it, session, held, pos / 1000.0) } }
        } else if (mediaItemIndex == index && session != null && status != null && !ended && loadingKey == null) {
            // Shown at once; the receiver's next report confirms it.
            status = status?.copy(currentTimeS = pos / 1000.0)
            statusAt = SystemClock.elapsedRealtime()
            scope.launch { link.media { CastProtocol.seek(it, session, pos / 1000.0) } }
        } else {
            index = mediaItemIndex.coerceIn(0, queue.size - 1)
            startMs = pos
            scope.launch { load() }
        }
        return done()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        level = (deviceVolume.coerceIn(0, maxVolume()) * step).coerceIn(0.0, 1.0)
        val l = level
        scope.launch { link.receiver { CastProtocol.setVolume(it, l) } }
        return done()
    }

    override fun handleIncreaseDeviceVolume(flags: Int) = handleSetDeviceVolume(volumeSteps() + 1, flags)
    override fun handleDecreaseDeviceVolume(flags: Int) = handleSetDeviceVolume(volumeSteps() - 1, flags)

    private companion object {
        const val TAG = "Cast"
        const val DEFAULT_STEP = 0.05
        const val SETTLE_MS = 2_500L
        const val MAX_ERRORS = 5
        const val LOAD_TIMEOUT_MS = 30_000L
        const val WAKE_TIMEOUT_MS = 60_000L
        val PLAYING_STATES = setOf("PLAYING", "BUFFERING")
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
