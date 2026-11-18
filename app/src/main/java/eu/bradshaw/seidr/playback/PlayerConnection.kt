package eu.bradshaw.seidr.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.data.api.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerUi(
    val queue: List<QueueEntry> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    /**
     * Playing or about to be (buffering a new track, mid-fade): what the
     * play/pause button shows, so it doesn't flash "play" between tracks.
     */
    val showPause: Boolean = false,
    val buffering: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val durationMs: Long = 0,
    /** Next would go somewhere: not the last track, or repeating the whole queue. */
    val hasNext: Boolean = false,
) {
    val current: QueueEntry? get() = queue.getOrNull(index)

    /** The run of user-queued tracks straight after the current one ("Next in queue"). */
    val userRange: IntRange get() {
        if (index < 0) return IntRange.EMPTY
        var end = index
        while (end + 1 < queue.size && queue[end + 1].user) end++
        return (index + 1)..end
    }

    val contextName: String? get() = contextEntry?.ctxName

    /** The playlist playing (or next up after "Next in queue"), if the context is one. */
    val contextPlaylist: String? get() = contextEntry?.ctxPlaylist

    private val contextEntry: QueueEntry? get() = current?.takeIf { it.ctxName != null }
        ?: queue.drop(index + 1).firstOrNull { !it.user }
}

/**
 * The app's handle on playback. Wraps a [MediaController] connected to
 * [PlaybackService] and implements the queue model on top of ExoPlayer's
 * single timeline:
 *
 *   [history…] [current] [Next in queue (user)…] [Next from context…]
 *
 * Shuffle is done here by reordering the context part of the timeline (so the
 * queue screen shows the real play order and gapless keeps working), with each
 * item's original context position kept in [QueueEntry.ctxIndex] to undo it.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerConnection(private val context: Context, private val graph: Graph) {
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val _ui = MutableStateFlow(PlayerUi(shuffle = PlayerPrefs.shuffle(context)))
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh()
            })
            refresh()
            pending.forEach { it(c) }
            pending.clear()
        }, MoreExecutors.directExecutor())
    }

    /** Lets go of the service so it can stop (the app was swiped away). [connect] makes a new one. */
    fun disconnect() {
        pending.clear()
        controller?.release()
        controller = null
    }

    /** Voice search: the service's [LibraryTree.search] picks what to play. */
    fun playFromSearch(query: String) = withController { c ->
        c.setMediaItem(
            MediaItem.Builder()
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).build())
                .build()
        )
        c.prepare()
        c.play()
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else { pending += block; connect() }
    }

    private fun refresh() {
        val c = controller ?: return
        val items = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toEntry() }
        _ui.value = PlayerUi(
            queue = items,
            index = if (items.isEmpty()) -1 else c.currentMediaItemIndex,
            isPlaying = c.isPlaying,
            showPause = !Util.shouldShowPlayButton(c),
            buffering = c.playbackState == Player.STATE_BUFFERING,
            shuffle = PlayerPrefs.shuffle(context),
            repeat = c.repeatMode,
            durationMs = c.duration.coerceAtLeast(0),
            hasNext = c.hasNextMediaItem(),
        )
    }

    val positionMs: Long get() = controller?.currentPosition ?: 0
    val bufferedMs: Long get() = controller?.bufferedPosition ?: 0

    // --- Starting playback -------------------------------------------------------

    /**
     * Plays [songs] as a context (playlist, album, artist top tracks…) starting at
     * [start]. Tracks the user had queued stay queued, as they would in any streaming app.
     * When offline, tracks that aren't downloaded are left out.
     */
    fun playContext(songs: List<Song>, start: Int, name: String, shuffle: Boolean? = null, playlistId: String? = null) {
        if (songs.isEmpty()) return
        val online = graph.status.online.value
        var all = songs.mapIndexed { i, s -> s.toEntry(user = false, ctxIndex = i, ctxName = name, ctxPlaylist = playlistId) }
        var startEntry = all.getOrNull(start)
        // A kid's disabled songs stay out (PlaybackService would hide them anyway).
        val kid = graph.kids.current
        if (kid != null) {
            all = all.filterIndexed { i, e -> !songs[i].locked && kid.playable(e.songId) }
            if (startEntry != null && startEntry !in all) startEntry = null
            if (all.isEmpty()) return
        }
        if (!online) {
            all = all.filter { graph.downloads.isDownloaded(it.songId) }
            if (startEntry != null && startEntry !in all) startEntry = null
            if (all.isEmpty()) return
        }
        savedShuffle = null
        val useShuffle = shuffle ?: _ui.value.shuffle
        if (shuffle != null) setShuffleFlag(shuffle)

        val first = startEntry ?: if (useShuffle) all.random() else all.first()
        val ordered: List<QueueEntry>
        val startIndex: Int
        if (useShuffle) {
            ordered = listOf(first) + (all - first).shuffled()
            startIndex = 0
        } else {
            ordered = all
            startIndex = all.indexOf(first)
        }
        val keptUser = _ui.value.let { ui -> ui.userRange.map { ui.queue[it] } }
        val timeline = ordered.toMutableList().apply { addAll(startIndex + 1, keptUser) }

        withController { c ->
            c.setMediaItems(timeline.map { it.toMediaItem(graph) }, startIndex, 0)
            c.prepare()
            c.play()
        }
    }

    fun shufflePlay(songs: List<Song>, name: String, playlistId: String? = null) = playContext(songs, -1, name, shuffle = true, playlistId)

    // --- Queue -------------------------------------------------------------------

    /** Adds to the end of "Next in queue". */
    fun addToQueue(songs: List<Song>) {
        val kid = graph.kids.current
        val allowed = if (kid == null) songs else songs.filter { !it.locked && kid.playable(it.id) }
        if (allowed.isEmpty()) return
        val entries = allowed.map { it.toEntry(user = true) }
        withController { c ->
            if (c.mediaItemCount == 0) {
                c.setMediaItems(entries.map { it.toMediaItem(graph) })
                c.prepare()
                return@withController
            }
            val insertAt = _ui.value.userRange.let { if (it.isEmpty()) c.currentMediaItemIndex + 1 else it.last + 1 }
            c.addMediaItems(insertAt, entries.map { it.toMediaItem(graph) })
        }
    }

    fun moveQueueItem(from: Int, to: Int) = withController { c ->
        if (from != to && from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) c.moveMediaItem(from, to)
    }

    fun removeQueueItem(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount && index != c.currentMediaItemIndex) c.removeMediaItem(index)
    }

    fun clearUserQueue() = withController { c ->
        val r = _ui.value.userRange
        if (!r.isEmpty()) c.removeMediaItems(r.first, r.last + 1)
    }

    fun skipTo(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) { c.seekTo(index, 0); c.play() }
    }

    // --- Transport ----------------------------------------------------------------

    fun playPause() = withController { c ->
        if (!Util.shouldShowPlayButton(c)) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(c.currentMediaItemIndex, 0)
            c.play()
        }
    }

    fun next() = withController { it.seekToNext() }
    /** Restarts the track if more than 3 s in, otherwise goes back one. */
    fun previous() = withController { it.seekToPrevious() }
    // With the index: a restored queue isn't prepared, so its item isn't "seekable"
    // yet and a plain seekTo(ms) is silently dropped. Seeking to an item still works.
    fun seekTo(ms: Long) = withController { it.seekTo(it.currentMediaItemIndex, ms) }

    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun setShuffleFlag(on: Boolean) {
        PlayerPrefs.setShuffle(context, on)
        _ui.value = _ui.value.copy(shuffle = on)
        graph.connect.session?.shuffleChanged(on)
    }

    /** The playing device (another one, under Connect) changed the shuffle flag. */
    fun syncShuffle(on: Boolean) {
        _ui.value = _ui.value.copy(shuffle = on)
    }

    /**
     * The context in its shuffled order, saved when shuffle goes off, so that
     * turning it back on restores that order (as if "off" were undone) rather
     * than dealing a new one. Dropped when a new context starts or on [reshuffle].
     */
    private var savedShuffle: List<QueueEntry>? = null

    /**
     * Toggles shuffle without interrupting the current track. On: the context
     * goes back to the order it had when shuffle was last turned off, or is
     * shuffled after "Next in queue" if there's none. Off: the context resumes
     * in its original order from the current track.
     */
    fun toggleShuffle() = setShuffle(!_ui.value.shuffle, fresh = false)

    /** Shuffle on, with a newly dealt order, whether or not it was on already. */
    fun reshuffle() = setShuffle(true, fresh = true)

    private fun setShuffle(on: Boolean, fresh: Boolean) = withController { c ->
        val before = _ui.value
        setShuffleFlag(on)
        val ui = _ui.value
        val cur = ui.current ?: return@withController
        val user = ui.userRange.map { ui.queue[it] }
        val idx = c.currentMediaItemIndex
        // A user-queued track is playing: the context carries on from the next context track.
        val anchor = if (!cur.user) cur else ui.queue.drop(idx + 1).firstOrNull { !it.user }
        val ctx = ui.queue.filter { !it.user && it.key != cur.key }
        if (on) {
            val saved = savedShuffle?.takeIf { !fresh && it.map(QueueEntry::key).toSet() == ui.queue.filter { e -> !e.user }.map(QueueEntry::key).toSet() }
            savedShuffle = null
            val p = saved?.indexOfFirst { it.key == anchor?.key } ?: -1
            if (saved != null && p >= 0) {
                val upcoming = saved.drop(if (!cur.user) p + 1 else p)
                replaceAround(c, idx, history = saved.take(p), upcoming = user + upcoming)
            } else {
                replaceAround(c, idx, history = emptyList(), upcoming = user + ctx.shuffled())
            }
        } else {
            if (before.shuffle) savedShuffle = ui.queue.filter { !it.user }
            val resumeFrom = anchor?.let { if (!cur.user) it.ctxIndex + 1 else it.ctxIndex } ?: 0
            val sorted = ctx.sortedBy { it.ctxIndex }
            val history = sorted.filter { it.ctxIndex < resumeFrom }
            val after = sorted.filter { it.ctxIndex >= resumeFrom }
            replaceAround(c, idx, history = history, upcoming = user + after)
        }
    }

    /** Rewrites everything except the current item, which keeps playing untouched. */
    private fun replaceAround(c: MediaController, idx: Int, history: List<QueueEntry>, upcoming: List<QueueEntry>) {
        val count = c.mediaItemCount
        if (idx + 1 < count) c.removeMediaItems(idx + 1, count)
        if (idx > 0) c.removeMediaItems(0, idx)
        if (history.isNotEmpty()) c.addMediaItems(0, history.map { it.toMediaItem(graph) })
        if (upcoming.isNotEmpty()) c.addMediaItems(c.mediaItemCount, upcoming.map { it.toMediaItem(graph) })
    }

    fun currentMediaItem(): MediaItem? = controller?.currentMediaItem

    fun stopAndClear() = withController { c ->
        c.stop()
        c.clearMediaItems()
    }
}
