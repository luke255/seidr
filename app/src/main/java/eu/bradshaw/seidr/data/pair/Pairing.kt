package eu.bradshaw.seidr.data.pair

import eu.bradshaw.seidr.data.ServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * QR sign-in between two Seiðr installs, in either direction.
 *
 * The QR never holds credentials. The device showing it (the host) listens on a
 * random port, and the code carries its addresses, that port and a fresh
 * 256-bit key. The scanner (the joiner) connects over the local network or
 * tailnet, and every message is AES-GCM sealed with that key, so only a device
 * that saw the code can talk to the host. The signed-in side (giver) sends the
 * server config; the other side (taker) checks it against the server and reports
 * back. One success ends the session.
 *
 *   joiner → host   Hello(name, giving)      first frame; a bad key fails here
 *   host → joiner   Hello(name, giving)
 *   giver → taker   Creds(url, user, password)
 *   taker → giver   Result(ok, error)
 */

/** What a pairing QR carries: where to connect and a one-time key. Never credentials. */
class PairCode(
    val hosts: List<String>,
    val port: Int,
    val key: ByteArray,
    /** True when the device showing the code is signed in and will hand over its login. */
    val giving: Boolean,
    /** The showing device's name, for prompts on the scanner. */
    val name: String,
) {
    fun encode(): String = buildString {
        append(PREFIX).append("v=1")
        append("&r=").append(if (giving) "g" else "t")
        append("&p=").append(port)
        append("&h=").append(hosts.joinToString(","))
        append("&k=").append(b64e.encodeToString(key))
        append("&n=").append(URLEncoder.encode(name, Charsets.UTF_8))
    }

    companion object {
        const val PREFIX = "seidr://pair?"

        /** Null for anything that isn't a version-1 Seiðr pairing code. */
        fun parse(text: String): PairCode? {
            if (!text.startsWith(PREFIX)) return null
            val q = text.removePrefix(PREFIX).split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), Charsets.UTF_8)
            }.toMap()
            if (q["v"] != "1") return null
            val giving = when (q["r"]) { "g" -> true; "t" -> false; else -> return null }
            val port = q["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val hosts = q["h"]?.split(',')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: return null
            val key = runCatching { b64d.decode(q["k"]) }.getOrNull()?.takeIf { it.size == KEY_BYTES } ?: return null
            return PairCode(hosts, port, key, giving, q["n"].orEmpty().ifBlank { "the other device" })
        }

        internal const val KEY_BYTES = 32
        private val b64e = Base64.getUrlEncoder().withoutPadding()
        private val b64d = Base64.getUrlDecoder()
    }
}

sealed interface PairRole {
    val giving: Boolean

    /** Signed in: hands [config] to the other device. */
    class Giver(val config: ServerConfig) : PairRole { override val giving = true }

    /**
     * Not signed in: [check] tries the received config against the server and
     * returns an error message, or null if it works. It must not sign in yet:
     * that switches screens and would cut the reply to the giver.
     */
    class Taker(val check: suspend (ServerConfig) -> String?) : PairRole { override val giving = false }
}

/** How a pairing ended. [config] is what a taker received (checked or not). */
data class PairOutcome(val peer: String, val error: String? = null, val config: ServerConfig? = null)

@Serializable
internal sealed interface PairMessage {
    @Serializable @SerialName("hello")
    data class Hello(val name: String, val giving: Boolean) : PairMessage
    @Serializable @SerialName("creds")
    data class Creds(val url: String, val username: String, val password: String) : PairMessage
    @Serializable @SerialName("result")
    data class Result(val ok: Boolean, val error: String? = null) : PairMessage
}

/** Thrown when a frame doesn't decrypt: the peer has a different (stale or forged) key. */
internal class WrongKeyException : IOException("wrong key")

/** Length-prefixed AES-GCM frames. The AAD binds direction and sequence, so frames can't be replayed or reflected. */
internal class SecureChannel(private val socket: Socket, key: ByteArray, private val isHost: Boolean) : Closeable {
    private val key = SecretKeySpec(key, "AES")
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private var sent = 0
    private var received = 0

    fun send(message: PairMessage) {
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(fromHost = isHost, seq = sent++))
        val sealed = cipher.doFinal(json.encodeToString(PairMessage.serializer(), message).toByteArray())
        output.writeInt(NONCE + sealed.size)
        output.write(nonce)
        output.write(sealed)
        output.flush()
    }

    fun receive(): PairMessage {
        val len = input.readInt()
        if (len !in NONCE + 16..MAX_FRAME) throw WrongKeyException()
        val frame = ByteArray(len).also(input::readFully)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val plain = try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, frame, 0, NONCE))
            cipher.updateAAD(aad(fromHost = !isHost, seq = received++))
            cipher.doFinal(frame, NONCE, len - NONCE)
        } catch (e: GeneralSecurityException) {
            throw WrongKeyException()
        }
        return try {
            json.decodeFromString(PairMessage.serializer(), plain.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw IOException("Unreadable message", e)
        }
    }

    override fun close() = socket.close()

    private fun aad(fromHost: Boolean, seq: Int) = "seidr-pair/1/${if (fromHost) "h" else "j"}/$seq".toByteArray()

    private companion object {
        const val NONCE = 12
        const val MAX_FRAME = 64 * 1024
        val random = SecureRandom()
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The device showing the code. Listens on every interface until one peer with
 * the right key completes the exchange; anything that fails before saying
 * hello (port scans, stale codes) is dropped and listening carries on.
 */
class PairHost private constructor(private val server: ServerSocket, val code: PairCode) : Closeable {

    suspend fun await(role: PairRole, onPeer: (String) -> Unit = {}): PairOutcome = withContext(Dispatchers.IO) {
        server.soTimeout = 500
        while (true) {
            ensureActive()
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            var peer: String? = null
            try {
                socket.soTimeout = PEER_TIMEOUT_MS
                return@withContext SecureChannel(socket, code.key, isHost = true).use { ch ->
                    converse(ch, isHost = true, role, code.name) { peer = it; onPeer(it) }
                }
            } catch (e: IOException) {
                runCatching { socket.close() }
                // Before hello it could be anything; after, it was our peer and it's over.
                peer?.let { return@withContext PairOutcome(it, "Lost the connection to $it.") }
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    override fun close() = server.close()

    companion object {
        /** Opens a listening socket and a fresh code. Null when there's no usable network. */
        fun open(name: String, giving: Boolean, hosts: List<String> = localAddresses()): PairHost? {
            if (hosts.isEmpty()) return null
            val server = ServerSocket(0)
            val key = ByteArray(PairCode.KEY_BYTES).also(SecureRandom()::nextBytes)
            return PairHost(server, PairCode(hosts, server.localPort, key, giving, name))
        }
    }
}

/** The scanner's side: connect to whichever of the code's addresses answers first, then exchange. */
suspend fun joinPair(code: PairCode, role: PairRole, name: String): PairOutcome = withContext(Dispatchers.IO) {
    val socket = connectAny(code.hosts, code.port)
        ?: return@withContext PairOutcome(code.name,
            "Couldn't reach ${code.name}. Both devices need to be on the same Wi-Fi or tailnet, with the code still showing.")
    var greeted = false
    try {
        socket.soTimeout = PEER_TIMEOUT_MS
        SecureChannel(socket, code.key, isHost = false).use { ch ->
            converse(ch, isHost = false, role, name) { greeted = true }
        }
    } catch (e: IOException) {
        runCatching { socket.close() }
        // The host drops a joiner whose first frame doesn't decrypt.
        PairOutcome(code.name, if (greeted) "Lost the connection to ${code.name}." else "That code has expired. Show a new one on ${code.name}.")
    }
}

private suspend fun converse(ch: SecureChannel, isHost: Boolean, role: PairRole, name: String, onPeer: (String) -> Unit): PairOutcome {
    val me = PairMessage.Hello(name, role.giving)
    if (!isHost) ch.send(me)
    val peer = ch.receive() as? PairMessage.Hello ?: throw IOException("Expected hello")
    if (isHost) ch.send(me)
    onPeer(peer.name)
    if (peer.giving == role.giving) {
        return PairOutcome(peer.name, if (role.giving) "${peer.name} is already signed in." else "${peer.name} isn't signed in either.")
    }
    return when (role) {
        is PairRole.Giver -> {
            ch.send(PairMessage.Creds(role.config.url, role.config.username, role.config.password))
            val r = ch.receive() as? PairMessage.Result ?: throw IOException("Expected result")
            PairOutcome(peer.name, if (r.ok) null else r.error ?: "${peer.name} couldn't sign in.")
        }
        is PairRole.Taker -> {
            val c = ch.receive() as? PairMessage.Creds ?: throw IOException("Expected login")
            val config = ServerConfig(c.url, c.username, c.password)
            val error = role.check(config)
            ch.send(PairMessage.Result(error == null, error))
            PairOutcome(peer.name, error, config)
        }
    }
}

/** First address to accept a TCP connection wins; the rest are closed. Null if none answer. */
private suspend fun connectAny(hosts: List<String>, port: Int): Socket? = coroutineScope {
    val sockets = hosts.map { Socket() }
    val winner = CompletableDeferred<Socket?>()
    try {
        val attempts = hosts.zip(sockets).map { (host, socket) ->
            launch(Dispatchers.IO) {
                try {
                    socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    if (!winner.complete(socket)) socket.close()
                } catch (_: IOException) {
                    runCatching { socket.close() }
                }
            }
        }
        launch { attempts.joinAll(); winner.complete(null) }
        winner.await().also { won -> sockets.filter { it !== won }.forEach { runCatching { it.close() } } }
    } catch (e: CancellationException) {
        sockets.forEach { runCatching { it.close() } }
        throw e
    }
}

/**
 * IPv4 addresses another device could reach: private LAN ranges plus
 * 100.64/10 (Tailscale). Wi-Fi and Ethernet first; cellular interfaces skipped.
 */
fun localAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !CELLULAR.any(it.name::startsWith) }
        .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it } }
        .filter { (_, a) -> a.isSiteLocalAddress || isCgnat(a) }
        .sortedBy { (name, _) -> if (name.startsWith("wlan") || name.startsWith("eth")) 0 else 1 }
        .mapNotNull { it.second.hostAddress }
        .distinct()
}.getOrDefault(emptyList())

private fun isCgnat(a: Inet4Address): Boolean {
    val b = a.address
    return (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
}

private val CELLULAR = listOf("rmnet", "ccmni", "clat", "v4-", "dummy")
private const val CONNECT_TIMEOUT_MS = 5_000
/** Long enough for a taker to ping the server (8s connect + a slow reply) before answering. */
private const val PEER_TIMEOUT_MS = 60_000
