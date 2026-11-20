package eu.bradshaw.seidr.connect

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.data.Account
import eu.bradshaw.seidr.data.ServerConfig
import eu.bradshaw.seidr.data.Settings
import eu.bradshaw.seidr.data.api.ok
import eu.bradshaw.seidr.playback.PlayerPrefs
import eu.bradshaw.seidr.playback.QueueEntry
import eu.bradshaw.seidr.playback.toEntry
import eu.bradshaw.seidr.playback.toMediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * Seiðr Connect inside PlaybackService. Two roles at once:
 *
 * - **Host**: serves this device's playback ([local], the session's own
 *   player) to controllers on other devices, applying their ops and sending
 *   them state.
 * - **Controller**: while [link] is set, this device is a remote for another
 *   one. Its [RemotePlayer] is in the media session in place of [local], which
 *   stays paused.
 *
 * Only one device plays: moving playback somewhere pauses where it was, and
 * that device's other controllers are sent after it ([ConnectMessage.Moved]).
 *
 * Signed out, it hosts guests: whoever connects sends a login to play with
 * ([GuestLogin], in `Graph.guest`), and another user connecting takes the
 * device over. Playback leaving, the Disconnect button or a sign-in ends it.
 * Everything here runs on the main thread except socket reads and writes.
 */
@OptIn(UnstableApi::class)
class ConnectSession(
    private val context: Context,
    private val graph: Graph,
    private val local: Player,
    private val setSessionPlayer: (Player) -> Unit,
) {
    private val connect = graph.connect
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var server: ServerSocket? = null
    private var port = 0
    private val clients = mutableListOf<Client>()
    private var link: Link? = null
    /** The Cast device playing, while one is (never at the same time as [link]). */
    private var cast: eu.bradshaw.seidr.cast.CastPlayer? = null
    private var busy = false

    fun start() {
        local.addListener(hostListener)
        connect.session = this
        scope.launch(Dispatchers.IO) {
            // A fixed port on an emulator, so the laptop can forward it (see [emulatorLanHost]).
            val s = (if (isEmulator) runCatching { ServerSocket(Connect.EMULATOR_PORT) }.getOrNull() else null)
                ?: runCatching { ServerSocket(0) }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) { server = s; port = s.localPort; connect.advertise(port) }
            while (true) {
                val socket = try { s.accept() } catch (_: IOException) { return@launch }
                launch { serve(socket) }
            }
        }
        // The advertised tag is per account (or none, signed out). Either way the old
        // connections were made under the other key.
        scope.launch {
            // (Connect re-advertises itself, once the new account key is ready.)
            graph.settings.server.drop(1).collect {
                if (it == null) {
                    link?.let(::dropLink)
                    cast?.let { c -> endCast(c) }
                    clients.toList().forEach { c -> c.close() }
                } else {
                    endGuest(connect.str(R.string.connect_peer_signed_in, connect.name))
                }
            }
        }
        // Controllers extrapolate the position; this keeps them honest, and catches volume changes.
        scope.launch {
            var lastVolume = -1
            var lastSent = 0L
            while (isActive) {
                delay(1_000)
                if (clients.isEmpty()) continue
                val v = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (v != lastVolume || SystemClock.elapsedRealtime() - lastSent > HEARTBEAT_MS) {
                    lastVolume = v; lastSent = SystemClock.elapsedRealtime()
                    broadcast()
                }
            }
        }
    }

    fun close() {
        connect.unadvertise()
        connect._shown.value = null
        if (graph.settings.server.value == null) graph.guest.value = null
        if (connect.session === this) connect.session = null
        connect._active.value = null
        local.removeListener(hostListener)
        link?.close()
        link = null
        cast?.let { c -> cast = null; c.leave() }
        clients.toList().forEach { it.close() }
        runCatching { server?.close() }
        scope.cancel()
    }

    /** This device as others should reach it. */
    /** A kid's login names the grown-up who signed them in; blank means it isn't one. */
    private fun kidOf(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() }

    private fun me() = Peer(connect.deviceId, connect.name, localAddresses(), port, connect.kind)

    // --- Moving playback -----------------------------------------------------------

    /** Plays on [target], or here when it's null. [onError]: see [openLink]. */
    suspend fun playOn(target: Peer?, onError: ((String) -> Boolean)? = null) {
        if (busy) return
        busy = true
        try {
            val cur = link
            val curCast = cast
            if (target == null) {
                if (cur != null) bringBack(cur)
                if (curCast != null) endCast(curCast)?.let { adopt(it) }
                return
            }
            if ((cur?.peer ?: curCast?.peer)?.id == target.id) return
            connect._connecting.value = target.id
            if (target.cast) { castOn(target, onError); return }
            val next = openLink(target, onError = onError) ?: return
            val first = next.first.await()
            if (curCast != null) {
                endCast(curCast, back = false)?.let { next.send(it) }
            } else if (cur != null) {
                // From one remote device to another: carry its final state over. Whether it
                // was playing is read first: the release pauses it.
                val wasPlaying = cur.player.state?.playWhenReady == true
                val final = cur.request(RemoteOp.Release(next.peer)) ?: cur.player.state
                val queue = cur.player.queue
                cur.close()
                if (final != null && queue.isNotEmpty()) next.send(final.toSetQueue(queue).copy(play = wasPlaying))
                else next.send(RemoteOp.TakeOver)
            } else {
                val playingHere = local.playWhenReady && local.playbackState != Player.STATE_ENDED && local.mediaItemCount > 0
                when {
                    playingHere -> next.send(localState(play = true))
                    // It's controlling another device: it takes that playback over.
                    first.remoteOf != null -> next.send(RemoteOp.TakeOver)
                    // Already playing there: just control it.
                    first.playWhenReady && first.playbackState != Player.STATE_IDLE -> {}
                    local.mediaItemCount > 0 -> next.send(localState(play = false))
                }
            }
            use(next)
        } finally {
            busy = false
            connect._connecting.value = null
        }
    }

    /** Playback comes back to this device, where the remote one had got to. */
    private suspend fun bringBack(cur: Link) {
        // Read before the release, which pauses it.
        val wasPlaying = cur.player.state?.playWhenReady == true
        val final = cur.request(RemoteOp.Release(me())) ?: cur.player.state
        val queue = cur.player.queue
        dropLink(cur)
        if (final != null && queue.isNotEmpty()) adopt(final, queue, play = wasPlaying)
    }

    private fun localState(play: Boolean) = RemoteOp.SetQueue(
        entries = (0 until local.mediaItemCount).map { local.getMediaItemAt(it).toEntry() },
        index = local.currentMediaItemIndex,
        positionMs = local.currentPosition.coerceAtLeast(0),
        play = play,
        shuffle = PlayerPrefs.shuffle(context),
        repeat = local.repeatMode,
    )

    private fun ConnectMessage.State.toSetQueue(queue: List<QueueEntry>) =
        RemoteOp.SetQueue(queue, index, positionMs, playWhenReady, shuffle, repeat)

    // --- Cast devices ---------------------------------------------------------------

    /** Moves playback to a Cast device, from wherever it is now. */
    private suspend fun castOn(target: Peer, onError: ((String) -> Boolean)?) {
        val next = eu.bradshaw.seidr.cast.CastPlayer(graph, target, ::castGone)
        next.connect()?.let { message ->
            if (onError?.invoke(message) != true) connect.tell(message)
            return
        }
        val l = link
        val c = cast
        val from: RemoteOp.SetQueue? = when {
            c != null -> endCast(c, back = false)
            l != null -> {
                // Read before the release, which pauses it. Not sent anywhere: other devices can't follow to a speaker.
                val wasPlaying = l.player.state?.playWhenReady == true
                val final = l.request(RemoteOp.Release(null)) ?: l.player.state
                val queue = l.player.queue
                link = null
                l.close()
                if (final != null && queue.isNotEmpty()) final.toSetQueue(queue).copy(play = wasPlaying) else null
            }
            local.mediaItemCount > 0 -> localState(play = local.playWhenReady && local.playbackState != Player.STATE_ENDED)
            else -> null
        }
        if (local.playWhenReady) local.pause()
        cast = next
        setSessionPlayer(next)
        connect._active.value = target
        next.start(from)
    }

    /**
     * Stops the Cast device (it goes quiet) and returns where it had got to.
     * With [back], the session gets this device's own player again.
     */
    private fun endCast(c: eu.bradshaw.seidr.cast.CastPlayer, back: Boolean = true): RemoteOp.SetQueue? {
        val h = c.handoff()
        // First: the session releasing it would otherwise hang up before the speaker is told to stop.
        c.leave()
        if (cast === c) {
            cast = null
            if (back) { setSessionPlayer(local); connect._active.value = null }
        }
        return h
    }

    /** The speaker dropped out or someone else took it: the music comes back here, paused. */
    private fun castGone(c: eu.bradshaw.seidr.cast.CastPlayer, reason: String) {
        if (cast !== c) return
        val h = c.handoff()
        cast = null
        setSessionPlayer(local)
        connect._active.value = null
        h?.let { adopt(it.copy(play = false)) }
        connect.tell(reason)
    }

    private fun adopt(h: RemoteOp.SetQueue) {
        if (h.entries.isEmpty()) return
        local.setMediaItems(h.entries.map { it.toMediaItem(graph) }, h.index.coerceIn(0, h.entries.lastIndex), h.positionMs)
        h.repeat?.let { local.repeatMode = it }
        h.shuffle?.let(::setShuffleFlag)
        local.prepare()
        if (h.play == true) local.play()
    }

    /** Makes [next] the device this one controls. */
    private fun use(next: Link) {
        val old = link
        link = next
        cast = null
        if (local.playWhenReady) local.pause()
        setSessionPlayer(next.player)
        connect._active.value = next.peer
        old?.takeIf { it !== next }?.close()
        // Anyone controlling this device follows the music.
        clients.forEach { it.post(ConnectMessage.Moved(next.peer)) }
    }

    /** Stops controlling [l]; the session gets this device's own player back. */
    private fun dropLink(l: Link) {
        if (link === l) {
            link = null
            setSessionPlayer(local)
            connect._active.value = null
        }
        l.close()
    }

    private fun adopt(state: ConnectMessage.State, queue: List<QueueEntry>, play: Boolean) {
        local.setMediaItems(queue.map { it.toMediaItem(graph) }, state.index.coerceIn(0, queue.lastIndex), state.positionMs)
        local.repeatMode = state.repeat
        setShuffleFlag(state.shuffle)
        local.prepare()
        if (play) local.play()
    }

    private fun setShuffleFlag(on: Boolean) {
        if (PlayerPrefs.shuffle(context) == on) return
        PlayerPrefs.setShuffle(context, on)
        graph.player.syncShuffle(on)
    }

    /** PlayerConnection toggled shuffle; a remote device needs the flag too. */
    fun shuffleChanged(on: Boolean) {
        link?.let { it.shuffleSeq = it.send(RemoteOp.Shuffle(on)) ?: 0 }
    }

    /** This device is taking playback (from a controller's op): stop controlling anything else. */
    private fun becomePlaying() {
        cast?.let { endCast(it) }
        val l = link ?: return
        l.send(RemoteOp.Release(me()))
        dropLink(l)
    }

    // --- Host: serving controllers --------------------------------------------------

    private var version = 0
    private var queueCache: List<QueueEntry>? = null
    private var broadcastQueued = false

    private val hostListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) { version++; queueCache = null }
        override fun onEvents(player: Player, events: Player.Events) = scheduleBroadcast()
    }

    private suspend fun serve(socket: Socket) {
        var client: Client? = null
        try {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.tcpNoDelay = true
            val ch = ConnectChannel.accept(socket, connect.awaitAccountKey()) { connect.identity }
            val hello = ch.receive() as? ConnectMessage.Hello ?: throw IOException("Expected hello")
            ch.send(ConnectMessage.Hello(connect.deviceId, connect.name, version = PROTOCOL_VERSION))
            var guestKey: ByteArray? = null
            val handshake = ch.guest
            if (handshake != null) {
                // Signed out: show the code while they compare it, then they say what they want.
                val shown = Connect.ShownCode(handshake.code, hello.name)
                if (hello.verify) withContext(Dispatchers.Main) { connect._shown.value = shown }
                socket.soTimeout = (Connect.CHECK_TIMEOUT_MS + 10_000).toInt()
                val wants = try {
                    ch.receive()
                } finally {
                    withContext(NonCancellable + Dispatchers.Main) { if (connect._shown.value === shown) connect._shown.value = null }
                }
                when (wants) {
                    is ConnectMessage.SignIn -> {
                        val config = ServerConfig(Settings.normaliseUrl(wants.url), wants.username.trim(), wants.password, kidOf(wants.kidOf))
                        // The code protects the sender, not this device: ask here before anything is tried.
                        val host = runCatching { java.net.URI(config.url).host }.getOrNull() ?: config.url
                        val req = Connect.SignInRequest(hello.name, config.username, host, config.isKid)
                        withContext(Dispatchers.Main) { connect._signInRequest.value = req }
                        val wait = if (hello.version >= SIGN_IN_PROMPT_VERSION) Connect.ASK_TIMEOUT_MS else Connect.OLD_ASK_TIMEOUT_MS
                        val allowed = try {
                            withTimeoutOrNull(wait) { req.answer.await() }
                        } finally {
                            withContext(NonCancellable + Dispatchers.Main) {
                                if (connect._signInRequest.value === req) connect._signInRequest.value = null
                            }
                        }
                        val error = when (allowed) {
                            null -> connect.str(R.string.connect_nobody_answered, connect.name)
                            false -> connect.str(R.string.connect_not_allowed, connect.name)
                            true -> Account.probe(graph, context, config)
                        }
                        if (error == null) withContext(Dispatchers.Main) { Account.commit(graph, config) }
                        ch.send(ConnectMessage.SignedIn(error))
                        Log.i(Connect.TAG, "${hello.name} signed this device in: ${error ?: "ok"}")
                        ch.close()
                        return
                    }
                    is ConnectMessage.Guest -> {
                        val config = ServerConfig(Settings.normaliseUrl(wants.url), wants.username.trim(), wants.password, kidOf(wants.kidOf))
                        guestKey = ConnectKeys.loginId(config.username, config.password)
                        withContext(Dispatchers.Main) { startGuest(GuestLogin(config, guestKey, hello.name, wants.theme)) }
                    }
                    else -> throw IOException("Expected guest or sign-in")
                }
            }
            // A quiet controller is fine; a dead one fails our next write.
            socket.soTimeout = 0
            socket.keepAlive = true
            val c = Client(ch, hello, guestKey)
            client = c
            withContext(Dispatchers.Main) { clients += c; c.start(); sendState(c) }
            Log.i(Connect.TAG, "${hello.name} connected")
            while (true) {
                val msg = ch.receive()
                withContext(Dispatchers.Main) { onClientMessage(c, msg) }
            }
        } catch (e: IOException) {
            Log.i(Connect.TAG, "controller ${client?.hello?.name ?: socket.inetAddress} gone: $e")
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { client?.let { clients -= it; it.close() } }
            runCatching { socket.close() }
        }
    }

    private suspend fun onClientMessage(c: Client, msg: ConnectMessage) {
        if (msg !is ConnectMessage.Op) return
        try {
            apply(msg.op, c)
        } catch (e: RuntimeException) {
            Log.w(Connect.TAG, "op ${msg.op::class.simpleName} failed", e)
        }
        c.ack = msg.seq
        scheduleBroadcast()
    }

    private suspend fun apply(op: RemoteOp, from: Client) {
        val count = local.mediaItemCount
        when (op) {
            is RemoteOp.SetQueue -> {
                becomePlaying()
                if (op.entries.isEmpty()) { local.clearMediaItems(); return }
                local.setMediaItems(op.entries.map { it.toMediaItem(graph) }, op.index.coerceIn(0, op.entries.lastIndex), op.positionMs)
                op.repeat?.let { local.repeatMode = it }
                op.shuffle?.let(::setShuffleFlag)
                local.prepare()
                when (op.play) { true -> playLocal(); false -> local.pause(); null -> {} }
                showOnTv()
            }
            is RemoteOp.Add -> local.addMediaItems(op.index.coerceIn(0, count), op.entries.map { it.toMediaItem(graph) })
            is RemoteOp.Move -> if (op.from in 0..op.to && op.to <= count && op.newIndex in 0..count - (op.to - op.from)) {
                local.moveMediaItems(op.from, op.to, op.newIndex)
            }
            is RemoteOp.Remove -> if (op.from in 0..op.to && op.to <= count) local.removeMediaItems(op.from, op.to)
            is RemoteOp.Seek -> when (op.command) {
                Player.COMMAND_SEEK_TO_NEXT -> local.seekToNext()
                Player.COMMAND_SEEK_TO_PREVIOUS -> local.seekToPrevious()
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> local.seekToNextMediaItem()
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> local.seekToPreviousMediaItem()
                else -> if (op.index in 0 until count) local.seekTo(op.index, op.positionMs)
            }
            is RemoteOp.Play -> if (op.play) playLocal() else local.pause()
            RemoteOp.Prepare -> if (local.playbackState == Player.STATE_IDLE) local.prepare()
            RemoteOp.Stop -> local.stop()
            is RemoteOp.Repeat -> local.repeatMode = op.mode
            is RemoteOp.Shuffle -> setShuffleFlag(op.on)
            is RemoteOp.Volume -> audio.setStreamVolume(
                AudioManager.STREAM_MUSIC, op.volume.coerceIn(0, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0,
            )
            RemoteOp.TakeOver -> {
                val l = link
                if (l != null) bringBack(l) else if (count > 0) playLocal()
            }
            is RemoteOp.Release -> {
                local.pause()
                op.to?.let { to -> clients.filter { it !== from }.forEach { it.post(ConnectMessage.Moved(to)) } }
                // A guest's music has gone elsewhere: once the final state is on its way, forget them.
                if (from.guestKey != null) endGuestJob = scope.launch { delay(2_000); endGuest(null) }
            }
        }
    }

    // --- Guests (this device signed out) ---------------------------------------------

    /** Someone is playing here with [login]. Another user takes over: the last one's music stops. */
    /**
     * Android TV: playback just arrived from another device, so put Now
     * Playing on screen. Android may refuse while Seiðr is in the background
     * (it doesn't let apps open themselves); then the music plays anyway.
     */
    private fun showOnTv() {
        if (!eu.bradshaw.seidr.ui.isTv(context)) return
        val i = android.content.Intent(context, eu.bradshaw.seidr.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("open", "nowplaying")
        runCatching { context.startActivity(i) }.onFailure { Log.i(Connect.TAG, "couldn't show Now Playing: $it") }
    }

    private fun startGuest(login: GuestLogin) {
        endGuestJob?.cancel()
        val cur = graph.guest.value
        if (cur != null && !cur.key.contentEquals(login.key)) {
            clients.filter { it.guestKey?.contentEquals(login.key) != true }.forEach {
                it.post(ConnectMessage.Ended(connect.str(R.string.connect_taken_over, login.from, connect.name)))
                it.close()
            }
            clearPlayback()
        }
        graph.guest.value = login
        // Online or not decides whether streams are tried; check against this login.
        graph.scope.launch { runCatching { graph.client.strict.ping().ok() } }
        Log.i(Connect.TAG, "playing for ${login.config.username} from ${login.from}")
    }

    /** Stops playing for the guest and forgets their login. [reason] goes to their controllers. */
    fun endGuest(reason: String?) {
        if (graph.guest.value == null) return
        endGuestJob?.cancel()
        clients.filter { it.guestKey != null }.forEach { c ->
            reason?.let { c.post(ConnectMessage.Ended(it)) }
            c.close()
        }
        clearPlayback()
        graph.guest.value = null
        // What their login fetched stays theirs.
        graph.client.clearCache()
        graph.library.clear()
        Log.i(Connect.TAG, "guest ended: $reason")
    }

    private var endGuestJob: kotlinx.coroutines.Job? = null

    private fun clearPlayback() {
        local.stop()
        local.clearMediaItems()
    }

    private fun playLocal() {
        if (local.playbackState == Player.STATE_ENDED) local.seekTo(local.currentMediaItemIndex, 0)
        local.play()
    }

    private fun scheduleBroadcast() {
        if (broadcastQueued || clients.isEmpty()) return
        broadcastQueued = true
        // Posted, so a burst of changes (a shuffle rewrites the queue in steps) goes as one.
        scope.launch(Dispatchers.Main) { broadcastQueued = false; broadcast() }
    }

    private fun broadcast() = clients.forEach(::sendState)

    private fun sendState(c: Client) {
        val withQueue = c.sentVersion != version
        val queue = if (!withQueue) null else queueCache
            ?: (0 until local.mediaItemCount).map { local.getMediaItemAt(it).toEntry() }.also { queueCache = it }
        c.post(ConnectMessage.State(
            ack = c.ack,
            version = version,
            queue = queue,
            index = local.currentMediaItemIndex,
            positionMs = local.currentPosition.coerceAtLeast(0),
            bufferedMs = local.bufferedPosition.coerceAtLeast(0),
            durationMs = local.duration.coerceAtLeast(0),
            playWhenReady = local.playWhenReady,
            playbackState = local.playbackState,
            repeat = local.repeatMode,
            shuffle = PlayerPrefs.shuffle(context),
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            remoteOf = link?.peer?.id,
        ))
        c.sentVersion = version
    }

    /** A controller connected to this device. */
    private inner class Client(val ch: ConnectChannel, val hello: ConnectMessage.Hello, val guestKey: ByteArray? = null) {
        var ack = 0L
        var sentVersion = -1
        private val outbox = Channel<ConnectMessage>(Channel.UNLIMITED)

        fun start() = scope.launch(Dispatchers.IO) {
            try { for (m in outbox) ch.send(m) } catch (_: IOException) {} finally { ch.close() }
        }

        fun post(m: ConnectMessage) { outbox.trySend(m) }

        /** Sends what's queued, then hangs up. */
        fun close() { outbox.close() }
    }

    // --- Controller: links to other devices -----------------------------------------

    /**
     * Connects to [peer] for control. A signed-out one gets this login to play
     * with (its code checked first, unless [quiet] or already pinned).
     * Failures go to [onError] when it takes them, else to a toast; never when [quiet].
     */
    private suspend fun openLink(peer: Peer, quiet: Boolean = false, onError: ((String) -> Boolean)? = null): Link? {
        fun fail(message: String) { if (!quiet && onError?.invoke(message) != true) connect.tell(message) }
        val config = graph.settings.server.value ?: return null
        val d = try {
            connect.dial(peer, ask = !quiet) ?: return null
        } catch (e: IOException) {
            Log.i(Connect.TAG, "couldn't connect to ${peer.name}: $e")
            fail(connect.dialError(peer, e))
            return null
        }
        val socket = d.socket
        val ch = d.ch
        if (peer.guest) {
            if (config.isKid && d.hello.version < eu.bradshaw.seidr.connect.KIDS_VERSION) {
                ch.close()
                fail(connect.str(R.string.connect_update_first, peer.name))
                return null
            }
            val sent = withContext(Dispatchers.IO) {
                runCatching { ch.send(ConnectMessage.Guest(config.url, config.username, config.password,
                    eu.bradshaw.seidr.ui.theme.portableTheme(graph.settings.theme.value, context), config.kidOf)) }.isSuccess
            }
            if (!sent) { ch.close(); fail(connect.str(R.string.connect_lost_connection, peer.name)); return null }
        }
        val l = Link(peer, ch, socket)
        l.start()
        if (withTimeoutOrNull(FIRST_STATE_TIMEOUT_MS) { l.first.await() } == null) {
            l.close()
            fail(connect.str(R.string.connect_no_answer, peer.name))
            return null
        }
        return l
    }

    /** The connection to a device this one controls. */
    private inner class Link(val peer: Peer, private val ch: ConnectChannel, private val socket: Socket) {
        val player = RemotePlayer(graph, ::send)
        val first = CompletableDeferred<ConnectMessage.State>()
        /** The last shuffle op sent: states that don't ack it yet still show the old flag. */
        var shuffleSeq = 0L
        private var seq = 0L
        private var closed = false
        private val outbox = Channel<ConnectMessage>(Channel.UNLIMITED)
        private val acks = mutableListOf<Pair<Long, CompletableDeferred<ConnectMessage.State>>>()

        fun start() {
            scope.launch(Dispatchers.IO) {
                try { for (m in outbox) ch.send(m) } catch (_: IOException) {} finally { ch.close() }
            }
            scope.launch(Dispatchers.IO) {
                try {
                    while (true) {
                        val msg = ch.receive()
                        withContext(Dispatchers.Main) { onMessage(msg) }
                    }
                } catch (e: IOException) {
                    withContext(NonCancellable + Dispatchers.Main) {
                        if (!closed) { Log.i(Connect.TAG, "lost ${peer.name}: $e"); closed = true; lost(this@Link) }
                    }
                }
            }
        }

        fun send(op: RemoteOp): Long? {
            if (closed) return null
            val s = ++seq
            outbox.trySend(ConnectMessage.Op(s, op))
            return s
        }

        /** Sends [op] and waits for the state that acks it. */
        suspend fun request(op: RemoteOp): ConnectMessage.State? {
            val s = send(op) ?: return null
            val d = CompletableDeferred<ConnectMessage.State>()
            acks += s to d
            return withTimeoutOrNull(REQUEST_TIMEOUT_MS) { d.await() }
        }

        private fun onMessage(msg: ConnectMessage) {
            when (msg) {
                is ConnectMessage.State -> {
                    player.update(msg)
                    first.complete(msg)
                    if (link === this && msg.ack >= shuffleSeq) setShuffleFlag(msg.shuffle)
                    val done = acks.filter { it.first <= msg.ack }
                    acks.removeAll(done)
                    done.forEach { it.second.complete(msg) }
                }
                is ConnectMessage.Moved -> {
                    val to = msg.to ?: return
                    // Coming here arrives as a SetQueue of its own.
                    if (to.id != connect.deviceId && link === this) follow(this, to)
                }
                is ConnectMessage.Ended -> ended(this, msg.reason)
                else -> {}
            }
        }

        /** Says what's queued, then hangs up. */
        fun close() {
            if (closed && outbox.isClosedForSend) return
            closed = true
            outbox.close()
            player.detach()
            acks.forEach { it.second.cancel() }
            acks.clear()
            // In case the writer is stuck on a dead peer.
            scope.launch { delay(2_000); runCatching { socket.close() } }
        }
    }

    /** The host ended the link on purpose: no retries; the music comes back here, paused. */
    private fun ended(l: Link, reason: String) {
        if (link !== l) { l.close(); return }
        val state = l.player.state
        val queue = l.player.queue
        dropLink(l)
        if (state != null && queue.isNotEmpty()) adopt(state, queue, play = false)
        connect.tell(reason)
    }

    /** The playing device handed playback to [to]: control that one instead. */
    private fun follow(from: Link, to: Peer) = scope.launch {
        val next = openLink(connect.devices.value.find { it.id == to.id } ?: to, quiet = true)
        if (link !== from) { next?.close(); return@launch }
        if (next == null) { from.close(); lost(from); return@launch }
        use(next)
    }

    /**
     * The link dropped by itself. Tries again briefly (a Wi-Fi blip), else
     * playback comes back here, paused where the other device was.
     */
    private fun lost(l: Link) {
        scope.launch {
            if (link !== l) return@launch
            repeat(2) {
                delay(1_500)
                if (link !== l) return@launch
                val peer = connect.devices.value.find { it.id == l.peer.id } ?: l.peer
                val again = openLink(peer, quiet = true)
                if (again != null) {
                    if (link === l) use(again) else again.close()
                    return@launch
                }
            }
            if (link !== l) return@launch
            val state = l.player.state
            val queue = l.player.queue
            dropLink(l)
            if (state != null && queue.isNotEmpty()) adopt(state, queue, play = false)
            connect.tell(connect.str(R.string.connect_lost_connection, l.peer.name))
        }
    }

    private companion object {
        const val HEARTBEAT_MS = 5_000L
        const val HANDSHAKE_TIMEOUT_MS = 5_000
        const val FIRST_STATE_TIMEOUT_MS = 5_000L
        const val REQUEST_TIMEOUT_MS = 3_000L
    }
}
