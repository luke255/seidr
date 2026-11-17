package eu.bradshaw.seidr.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.vis.xr.XrVisualiserActivity
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.MainActivity
import eu.bradshaw.seidr.ui.isTv
import eu.bradshaw.seidr.vis.TapSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Owns the ExoPlayer and the media session (notification, lockscreen,
 * Bluetooth and headset controls, and Android Auto via [LibraryTree]). Queue logic lives in [PlayerConnection];
 * this service only plays what it's given, resolving each track to its local
 * file when one exists and to the original stream otherwise.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private lateinit var graph: Graph
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    /** The session's player while playback is on this device. */
    private lateinit var fading: FadingPlayer
    /** Seiðr Connect: serves this device to others, and controls another one. */
    private lateinit var connect: eu.bradshaw.seidr.connect.ConnectSession
    private lateinit var tree: LibraryTree
    private lateinit var queueStore: QueueStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickJob: Job? = null
    /** Non-null on Android TV, where Cast (and Home Assistant) need http artwork. */
    private var publicArt: PublicArt? = null
    /** Queue slot of the last real transition; a metadata-only replace keeps it. */
    private var currentKey: String? = null

    // Scrobble bookkeeping for the current track.
    private var playedMs = 0L
    private var scrobbled = false
    private var startedAt = 0L
    private var consecutiveErrors = 0

    override fun onCreate() {
        super.onCreate()
        graph = Graph.get(this)
        queueStore = QueueStore(this)
        tree = LibraryTree(this, graph)

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(30000)
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            if (spec.uri.scheme != "seidr") return@Factory spec
            val songId = spec.uri.lastPathSegment ?: return@Factory spec
            val kid = graph.kids.current
            val local = if (kid?.playable(songId) == false) null else graph.downloads.localFile(songId)
            when (val s = KidGate.source(songId, kid, local, graph.status.online.value)) {
                is KidGate.Source.Refused -> throw IOException(s.why)
                is KidGate.Source.Local -> spec.withUri(Uri.fromFile(s.file))
                KidGate.Source.Stream -> spec.withUri(Uri.parse(graph.client.streamUrl(songId) ?: throw IOException("Not logged in")))
            }
        }

        // The audio sink is wrapped so the visualiser can see what's playing
        // (idle unless it's on screen). Never enable audio offload: the tap
        // would go silent.
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, float: Boolean, params: Boolean): AudioSink? =
                super.buildAudioSink(context, float, params)?.let(::TapSink)
        }
        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        // Buffer the start of the next item while this one plays, so a manual
        // skip to a streamed track doesn't wait on a fresh connection.
        player.preloadConfiguration = ExoPlayer.PreloadConfiguration(PRELOAD_NEXT_US)

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra("open", "nowplaying"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fading = FadingPlayer(player, scope)
        session = MediaLibrarySession.Builder(this, fading, Callback())
            .setSessionActivity(open)
            .build()
        // The playlists changed (an edit, here or elsewhere): the car's lists follow.
        scope.launch {
            graph.library.playlists.drop(1).collectLatest {
                for (id in listOf(LibraryTree.RECENT, LibraryTree.PLAYLISTS)) {
                    runCatching { session.notifyChildrenChanged(id, tree.children(id).size, null) }
                }
            }
        }
        connect = eu.bradshaw.seidr.connect.ConnectSession(this, graph, fading, ::setSessionPlayer)
        connect.start()

        if (isTv(this)) publicArt = PublicArt(graph, scope, player)
        player.addListener(listener)
        restoreQueue()
        updateLikeButton()
        scope.launch { graph.library.starredSongs.collectLatest { updateLikeButton() } }
        scope.launch {
            kotlinx.coroutines.flow.combine(graph.bad.skipped, graph.kids.rules) { _, _ -> }
                .collect { restoreFixedSongs(); hideBadSongs() }
        }
    }

    /** Songs kept out of the queue: in Bad: Songs, or (a kid's login) not approved. */
    private fun blocked(songId: String): Boolean =
        songId in graph.bad.skipped.value || graph.kids.current?.playable(songId) == false

    private fun anyBlocked() = graph.bad.skipped.value.isNotEmpty() || graph.kids.isKid

    /** Queue entries whose song is blocked, out of the player until it isn't. */
    private var hidden: List<HiddenEntry> = emptyList()

    private fun timelineKeys() = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    /**
     * Takes songs in Bad: Songs out of the queue, wherever they came from (the
     * app, Android Auto, a restored queue), and keeps them in [hidden].
     * Hiding the current one moves playback on to the next, as a skip would.
     */
    private fun hideBadSongs() {
        if (!anyBlocked()) return
        val plan = HiddenQueue.hide(timelineKeys(), ::blocked)
        if (plan.isEmpty()) return
        hidden = (hidden + plan.map { (i, prev, next) -> HiddenEntry(player.getMediaItemAt(i).toEntry(), prev, next) })
            .takeLast(MAX_HIDDEN)
        val current = player.currentMediaItemIndex
        // From the end, so earlier indexes stay put; the current item goes last.
        for ((i, _, _) in plan.asReversed()) if (i != current) player.removeMediaItem(i)
        if (plan.any { it.first == current }) player.removeMediaItem(player.currentMediaItemIndex)
        saveQueue()
    }

    /**
     * Puts hidden entries back where they were once their song leaves Bad: Songs
     * (fixed, or the integration switched off), so skipping back reaches them.
     * Only on a change to that list, not on queue edits: a shuffle toggle
     * rebuilds the queue in steps, and a half-built queue would look like a
     * replaced one and drop entries.
     */
    private fun restoreFixedSongs() {
        if (hidden.isEmpty()) return
        val r = HiddenQueue.restore(timelineKeys(), hidden, ::blocked)
        hidden = r.hidden
        // Ascending, so each insert lands at its final index.
        r.keys.forEachIndexed { i, key -> r.restored[key]?.let { player.addMediaItem(i, it.toMediaItem(graph)) } }
        saveQueue()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    /**
     * Swiping the app away quits it, music and notification included. The
     * app's own controller lets go so nothing keeps the service bound; one
     * Android Auto still holds keeps it alive (paused) for the car. Leaving
     * the headset's immersive visualiser ends its own task too; that isn't
     * quitting.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (rootIntent?.component?.className == XrVisualiserActivity::class.java.name) return
        player.pause()
        saveQueue(now = true)
        graph.player.disconnect()
        stopSelf()
    }

    override fun onDestroy() {
        saveQueue(now = true)
        connect.close()
        scope.cancel()
        session.release()
        player.release()
        NowPlaying.currentSongId = null
        super.onDestroy()
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            publicArt?.let { scope.launch { it.fill() } }
            // Same queue slot, new metadata (PublicArt's swap): not a new play.
            if (mediaItem?.mediaId == currentKey && reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
            currentKey = mediaItem?.mediaId
            val previous = NowPlaying.currentSongId
            val songId = mediaItem?.mediaId?.let(::songIdOf)
            NowPlaying.currentSongId = songId
            playedMs = 0; scrobbled = false; startedAt = System.currentTimeMillis()
            if (songId != null) scope.launch(Dispatchers.IO) { NowPlaying.nowPlaying(graph, songId) }
            // A track removed from its offline playlist while playing can go now.
            if (previous != null && previous != songId) graph.sync.sweep()
            updateLikeButton()
            saveQueue()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            tickJob?.cancel()
            if (isPlaying) {
                consecutiveErrors = 0
                // A restored queue "starts" its track at app launch; the play really starts now.
                if (playedMs == 0L) startedAt = System.currentTimeMillis()
                tickJob = scope.launch {
                    while (isActive) {
                        delay(1000)
                        playedMs += 1000
                        maybeScrobble()
                        if (playedMs % 10_000 == 0L) checkpointPosition()
                    }
                }
            } else saveQueue()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                // Posted: changing the timeline from inside its own callback confuses other listeners.
                if (anyBlocked()) scope.launch { hideBadSongs() }
                publicArt?.let { scope.launch { it.fill() } }
                saveQueue()
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.w("Playback", "error on ${player.currentMediaItem?.mediaId}: $error")
            // Streaming hits a dead server before anything else does; switch to offline mode
            // now (SeidrApp keeps probing and switches back).
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            ) graph.status.reportFailure()
            // Skip a track that won't play (offline and not downloaded, say) rather than
            // stall, but give up after a run of failures so repeat-all can't spin forever.
            if (player.playWhenReady && ++consecutiveErrors < 5) {
                val next = nextPlayableIndex() ?: return
                player.seekTo(next, 0)
                player.prepare()
            }
        }
    }

    /** The next item to try after an error. Offline, that's the next downloaded one. */
    private fun nextPlayableIndex(): Int? {
        val count = player.mediaItemCount
        if (count == 0) return null
        val wrap = player.repeatMode == Player.REPEAT_MODE_ALL
        if (graph.status.online.value) {
            return player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }
        }
        val from = player.currentMediaItemIndex
        for (step in 1 until count) {
            val i = from + step
            if (i >= count && !wrap) return null
            val item = player.getMediaItemAt(i % count)
            if (graph.downloads.localFile(songIdOf(item.mediaId)) != null) return i % count
        }
        return null
    }

    private fun maybeScrobble() {
        if (scrobbled) return
        val item = player.currentMediaItem ?: return
        val durMs = player.duration.takeIf { it > 0 } ?: return
        // The usual rule: half the track or four minutes, whichever is first.
        if (playedMs >= minOf(durMs / 2, 240_000L)) {
            scrobbled = true
            val songId = songIdOf(item.mediaId)
            val at = startedAt
            // Playing for a guest: their login is only here while they are, so no retries later.
            val keep = graph.settings.server.value != null
            scope.launch(Dispatchers.IO) { NowPlaying.scrobble(graph, songId, at, keepForLater = keep) }
        }
    }

    private var saveJob: Job? = null

    /** Snapshot on the main thread (the player lives there), write on IO: queues can be thousands long. */
    private fun saveQueue(now: Boolean = false) {
        if (!::player.isInitialized) return
        // Signed out, the queue is a guest's: it isn't kept.
        if (graph.settings.server.value == null) { scope.launch(Dispatchers.IO) { queueStore.clear() }; return }
        val n = player.mediaItemCount
        val snapshot = if (n == 0) null else SavedQueue(
            (0 until n).map { player.getMediaItemAt(it).toEntry() },
            player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0),
            PlayerPrefs.shuffle(this), player.repeatMode, hidden,
        )
        val write = { if (snapshot == null) queueStore.clear() else queueStore.save(snapshot) }
        if (now) { write(); return }
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) { write() }
    }

    private fun checkpointPosition() {
        if (graph.settings.server.value == null) return
        val key = player.currentMediaItem?.mediaId ?: return
        val index = player.currentMediaItemIndex
        val pos = player.currentPosition
        scope.launch(Dispatchers.IO) { queueStore.savePosition(index, key, pos) }
    }

    private fun restoreQueue() {
        if (graph.settings.server.value == null) return
        val saved = queueStore.load() ?: return
        if (saved.entries.isEmpty()) return
        player.setMediaItems(saved.entries.map { it.toMediaItem(graph) }, saved.index.coerceIn(0, saved.entries.lastIndex), saved.positionMs)
        player.repeatMode = saved.repeat
        hidden = saved.hidden
        // Not prepared here: preparing would hit the network (and, offline, skip
        // tracks via onPlayerError). PlayerConnection.playPause prepares on demand.
        NowPlaying.currentSongId = player.currentMediaItem?.mediaId?.let(::songIdOf)
    }

    /**
     * Under Connect, the session (app, notification, lockscreen, volume keys)
     * drives another device's [eu.bradshaw.seidr.connect.RemotePlayer];
     * [fading] stays this device's own.
     */
    private fun setSessionPlayer(p: Player) {
        val old = session.player
        if (old === p) return
        if (old !== fading) old.removeListener(remoteListener)
        if (p !== fading) p.addListener(remoteListener)
        session.player = p
        if (old !== fading) old.release()
        updateLikeButton()
    }

    private val remoteListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = updateLikeButton()
    }

    // --- Like button on the notification and lockscreen ---------------------------

    private fun updateLikeButton() {
        val id = session.player.currentMediaItem?.mediaId?.let(::songIdOf)
        val liked = id != null && id in graph.library.starredSongs.value
        val button = CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(getString(if (liked) R.string.playback_unlove else R.string.playback_love))
            .setSessionCommand(SessionCommand(CMD_LIKE, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
        session.setMediaButtonPreferences(ImmutableList.of(button))
    }

    private val refreshedAt = mutableMapOf<String, Long>()

    /**
     * Browse lists are served from the last-seen copies; this fetches behind them
     * and tells browsers to reload if anything changed. At most every 30 s per list,
     * since the reload asks for the list again.
     */
    private fun refreshInBackground(parentId: String) {
        val now = SystemClock.elapsedRealtime()
        if (refreshedAt[parentId]?.let { now - it < 30_000 } == true) return
        refreshedAt[parentId] = now
        scope.launch {
            val count = withContext(Dispatchers.IO) { runCatching { tree.refresh(parentId) }.getOrNull() }
            if (count != null) session.notifyChildrenChanged(parentId, count, null)
        }
    }

    /** Runs [block] on the service scope and hands its result to Media3 as a future. */
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        scope.launch { runCatching { block() }.fold(f::set, f::setException) }
        return f
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val base = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_LIKE, Bundle.EMPTY))
                .build()
            // Shuffle is ours (PlayerConnection reorders the queue); ExoPlayer's must never switch on,
            // so other controllers (Android Auto, Bluetooth) don't get its toggle.
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .remove(Player.COMMAND_SET_SHUFFLE_MODE)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(base)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        // --- Browsing (Android Auto) ---

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(tree.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            val all = withContext(Dispatchers.IO) { tree.children(parentId) }
            refreshInBackground(parentId)
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = future {
            tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        /** A browse item or voice search becomes a whole queue; the app's own items pass through. */
        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
            startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val single = mediaItems.singleOrNull()
            val query = single?.requestMetadata?.searchQuery
            val browseId = single?.mediaId?.takeIf { id -> listOf("play/", "shuffle/", LibraryTree.PL, LibraryTree.AL).any(id::startsWith) }
            if (query == null && browseId == null) {
                return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            }
            return future {
                (if (browseId != null) tree.resolve(browseId) else tree.search(query!!))
                    ?: throw IllegalArgumentException("Nothing to play for ${browseId ?: query}")
            }
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_LIKE) {
                val id = session.player.currentMediaItem?.mediaId?.let(::songIdOf)
                if (id != null) scope.launch {
                    if (graph.library.setSongStarred(id, id !in graph.library.starredSongs.value)) graph.lovedChanged()
                    updateLikeButton()
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        // Controllers strip the URI from items they send, so put it back from the id.
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { it.buildUpon().setUri(songUri(songIdOf(it.mediaId))).build() }.toMutableList()
        )

        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = queueStore.load()
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("no saved queue"))
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(
                saved.entries.map { it.toMediaItem(graph) }, saved.index, saved.positionMs,
            ))
        }
    }

    companion object {
        const val CMD_LIKE = "eu.bradshaw.seidr.LIKE"
        private const val PRELOAD_NEXT_US = 10_000_000L
        private const val MAX_HIDDEN = 1000
    }
}
