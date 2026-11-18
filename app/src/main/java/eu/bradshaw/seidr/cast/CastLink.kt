package eu.bradshaw.seidr.cast

import android.util.Log
import eu.bradshaw.seidr.cast.CastProtocol.NS_CONNECTION
import eu.bradshaw.seidr.cast.CastProtocol.NS_HEARTBEAT
import eu.bradshaw.seidr.cast.CastProtocol.NS_MEDIA
import eu.bradshaw.seidr.cast.CastProtocol.NS_RECEIVER
import eu.bradshaw.seidr.cast.CastProtocol.PLATFORM
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A session with one Cast device running the Default Media Receiver: connects,
 * launches (or [rejoin]s) the receiver app, keeps the heartbeat, and matches
 * replies to requests. Status arrives through [onReceiver] and [onMedia] on
 * [scope]'s thread (the main one); [onClosed] once, when the socket dies.
 */
class CastLink(private val hosts: List<String>, private val port: Int, private val scope: CoroutineScope) {
    private val sender = "sender-seidr-" + (System.nanoTime() and 0xffffff).toString(16)
    private val json = Json { ignoreUnknownKeys = true }
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    @Volatile private var ch: CastChannel? = null
    private var jobs = emptyList<Job>()

    var onReceiver: (CastProtocol.ReceiverStatus) -> Unit = {}
    var onMedia: (CastProtocol.MediaStatus?) -> Unit = {}
    var onClosed: () -> Unit = {}

    /** The receiver app this link drives, once launched. */
    var app: CastProtocol.App? = null
        private set
    /** The current media session on it, from the latest MEDIA_STATUS. */
    var mediaSessionId: Long? = null
        private set
    @Volatile var closed = false
        private set

    /** This device's address as the Cast device sees it, for links back to [CastRelay]. */
    val localHost: String? get() = ch?.localHost

    fun nextId() = ids.getAndIncrement()

    /** Connects and launches the receiver. Throws IOException when that fails. */
    suspend fun open() {
        connectSocket()
        val reply = request(NS_RECEIVER, PLATFORM, LAUNCH_TIMEOUT_MS) { CastProtocol.launch(it) }
            ?: throw IOException("No answer to LAUNCH")
        val app = CastProtocol.receiverStatus(reply)?.apps?.firstOrNull { it.appId == CastProtocol.DEFAULT_RECEIVER }
            ?: throw IOException("LAUNCH failed: ${reply.type()}")
        attach(app)
    }

    /**
     * After a dropped socket: reconnects, and carries on with [app] if it's
     * still the receiver's. False when it's gone (someone else is casting).
     */
    suspend fun rejoin(): Boolean {
        val mine = app ?: return false
        runCatching { connectSocket() }.onFailure { return false }
        val reply = request(NS_RECEIVER, PLATFORM) { CastProtocol.type("GET_STATUS", it) } ?: return false
        val app = CastProtocol.receiverStatus(reply)?.apps?.firstOrNull { it.sessionId == mine.sessionId } ?: return false
        attach(app)
        return true
    }

    private suspend fun attach(app: CastProtocol.App) {
        this.app = app
        send(NS_CONNECTION, app.transportId, CastProtocol.type("CONNECT"))
        request(NS_MEDIA, app.transportId) { CastProtocol.type("GET_STATUS", it) }
    }

    private suspend fun connectSocket() = withContext(Dispatchers.IO) {
        jobs.forEach { it.cancel() }
        ch?.close()
        var last: IOException? = null
        val c = hosts.firstNotNullOfOrNull { h ->
            try { CastChannel.open(h, port) } catch (e: IOException) { last = e; null }
        } ?: throw last ?: IOException("No address")
        c.readTimeoutMs = READ_TIMEOUT_MS
        ch = c
        closed = false
        c.send(CastMessage(sender, PLATFORM, NS_CONNECTION, CastProtocol.type("CONNECT").toString()))
        jobs = listOf(
            scope.launch(Dispatchers.IO) { read(c) },
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    runCatching { c.send(CastMessage(sender, PLATFORM, NS_HEARTBEAT, PING)) }
                    delay(HEARTBEAT_MS)
                }
            },
        )
    }

    private suspend fun read(c: CastChannel) {
        try {
            while (true) {
                val m = c.receive()
                val body = m.payload?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: continue
                val type = body.type()
                if (m.namespace == NS_HEARTBEAT) {
                    if (type == "PING") runCatching { c.send(CastMessage(sender, m.source, NS_HEARTBEAT, PONG)) }
                    continue
                }
                body["requestId"]?.jsonPrimitive?.longOrNull?.takeIf { it != 0L }?.let { pending.remove(it)?.complete(body) }
                withContext(Dispatchers.Main) { dispatch(m, type, body) }
            }
        } catch (e: IOException) {
            if (ch === c) {
                Log.i(TAG, "socket gone: $e")
                withContext(Dispatchers.Main) {
                    if (ch === c && !closed) { closed = true; c.close(); onClosed() }
                }
            }
        }
    }

    private fun dispatch(m: CastMessage, type: String?, body: JsonObject) {
        when {
            m.namespace == NS_RECEIVER && type == "RECEIVER_STATUS" -> CastProtocol.receiverStatus(body)?.let(onReceiver)
            m.namespace == NS_MEDIA && type == "MEDIA_STATUS" -> {
                val s = CastProtocol.mediaStatus(body)
                mediaSessionId = s?.mediaSessionId
                onMedia(s)
            }
            // The receiver app closed its end (stopped, or replaced by another app).
            m.namespace == NS_CONNECTION && type == "CLOSE" && m.source == app?.transportId ->
                onReceiver(CastProtocol.ReceiverStatus(emptyList(), null, false, null))
        }
    }

    fun send(namespace: String, destination: String, body: JsonObject) {
        val c = ch ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { c.send(CastMessage(sender, destination, namespace, body.toString())) }
                .onFailure { Log.i(TAG, "send failed: $it") }
        }
    }

    /** Sends what [build] makes with a fresh request id; its reply, or null on timeout or a dead link. */
    suspend fun request(namespace: String, destination: String, timeoutMs: Long = REQUEST_TIMEOUT_MS, build: (Long) -> JsonObject): JsonObject? {
        val c = ch ?: return null
        val id = nextId()
        val d = CompletableDeferred<JsonObject>()
        pending[id] = d
        return try {
            withContext(Dispatchers.IO) { c.send(CastMessage(sender, destination, namespace, build(id).toString())) }
            withTimeoutOrNull(timeoutMs) { d.await() }
        } catch (e: IOException) {
            null
        } finally {
            pending.remove(id)
        }
    }

    /** A media-namespace request to the receiver app. */
    suspend fun media(timeoutMs: Long = REQUEST_TIMEOUT_MS, build: (Long) -> JsonObject): JsonObject? {
        val t = app?.transportId ?: return null
        return request(NS_MEDIA, t, timeoutMs, build)
    }

    suspend fun receiver(build: (Long) -> JsonObject): JsonObject? = request(NS_RECEIVER, PLATFORM, build = build)

    /** Stops the receiver app (the speaker goes quiet), then hangs up. */
    suspend fun stopApp() {
        app?.let { a -> withTimeoutOrNull(2_000) { receiver { CastProtocol.stop(it, a.sessionId) } } }
        close()
    }

    fun close() {
        closed = true
        jobs.forEach { it.cancel() }
        val c = ch
        ch = null
        if (c != null) {
            app?.let { a -> runCatching { c.send(CastMessage(sender, a.transportId, NS_CONNECTION, CastProtocol.type("CLOSE").toString())) } }
            c.close()
        }
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    private companion object {
        const val TAG = "Cast"
        const val HEARTBEAT_MS = 5_000L
        /** Three missed heartbeats. */
        const val READ_TIMEOUT_MS = 15_000
        const val REQUEST_TIMEOUT_MS = 5_000L
        /** A cold receiver can take a while to load. */
        const val LAUNCH_TIMEOUT_MS = 15_000L
        val PING = CastProtocol.type("PING").toString()
        val PONG = CastProtocol.type("PONG").toString()
    }
}

fun JsonObject.type(): String? = this["type"]?.jsonPrimitive?.contentOrNull
