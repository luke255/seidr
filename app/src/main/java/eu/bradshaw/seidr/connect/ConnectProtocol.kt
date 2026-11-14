package eu.bradshaw.seidr.connect

import eu.bradshaw.seidr.playback.QueueEntry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Seiðr Connect: one device plays, others on the same Wi-Fi control it.
 *
 * Every device that's signed in runs a small server (ConnectSession) and
 * advertises it over mDNS. Only devices signed in as the same user can talk:
 * the account key is derived from the username and password, each connection
 * derives its own session key from it and two fresh nonces, and every frame is
 * AES-GCM sealed with direction and sequence in the AAD. A device on another
 * account (or a stale password) fails on its first frame.
 *
 *   controller → host   nonce, then Hello
 *   host → controller   nonce, then Hello and its State
 *   controller → host   Op(seq, …)            player commands, in order
 *   host → controller   State(ack = seq, …)   after every change, and every few seconds
 *   host → controller   Moved(to)             playback went to another device; follow it
 */

/** A device on the network, as discovery found it (or as a [ConnectMessage.Moved] names it). */
@Serializable
data class Peer(
    val id: String,
    val name: String,
    val hosts: List<String>,
    val port: Int,
    /** "phone", "tv" or "headset", for the icon. */
    val kind: String = KIND_PHONE,
) {
    companion object {
        const val KIND_PHONE = "phone"
        const val KIND_TV = "tv"
        const val KIND_HEADSET = "headset"
    }
}

/** What a controller asks the playing device to do. Indexes are into the host's timeline. */
@Serializable
sealed interface RemoteOp {
    /**
     * Replaces the queue. Also how playback moves to a device: whoever receives
     * it becomes the playing device. [play], [shuffle] and [repeat] are set too
     * when given.
     */
    @Serializable @SerialName("set")
    data class SetQueue(
        val entries: List<QueueEntry>,
        val index: Int,
        val positionMs: Long,
        val play: Boolean? = null,
        val shuffle: Boolean? = null,
        val repeat: Int? = null,
    ) : RemoteOp

    @Serializable @SerialName("add")
    data class Add(val index: Int, val entries: List<QueueEntry>) : RemoteOp

    @Serializable @SerialName("move")
    data class Move(val from: Int, val to: Int, val newIndex: Int) : RemoteOp

    @Serializable @SerialName("remove")
    data class Remove(val from: Int, val to: Int) : RemoteOp

    /** [command] is the Player.COMMAND_SEEK_* that asked, so next/previous keep their own rules (and fades). */
    @Serializable @SerialName("seek")
    data class Seek(val index: Int, val positionMs: Long, val command: Int) : RemoteOp

    @Serializable @SerialName("play")
    data class Play(val play: Boolean) : RemoteOp

    @Serializable @SerialName("prepare")
    data object Prepare : RemoteOp

    @Serializable @SerialName("stop")
    data object Stop : RemoteOp

    @Serializable @SerialName("repeat")
    data class Repeat(val mode: Int) : RemoteOp

    /** Seiðr's shuffle flag (the reordering itself arrives as queue ops). */
    @Serializable @SerialName("shuffle")
    data class Shuffle(val on: Boolean) : RemoteOp

    /** The host's media volume, in its own steps (0..[ConnectMessage.State.maxVolume]). */
    @Serializable @SerialName("volume")
    data class Volume(val volume: Int) : RemoteOp

    /**
     * Become the playing device, keeping your own state. Sent to a device that
     * is itself controlling another: it takes that device's playback over.
     */
    @Serializable @SerialName("takeover")
    data object TakeOver : RemoteOp

    /**
     * Playback is leaving you for [to]: pause, and send your other controllers
     * there. The ack's State is the final one, which the sender carries over.
     */
    @Serializable @SerialName("release")
    data class Release(val to: Peer?) : RemoteOp
}

@Serializable
sealed interface ConnectMessage {
    @Serializable @SerialName("hello")
    data class Hello(val id: String, val name: String, val version: Int = PROTOCOL_VERSION) : ConnectMessage

    @Serializable @SerialName("op")
    data class Op(val seq: Long, val op: RemoteOp) : ConnectMessage

    /**
     * The playing device's state. [queue] comes only when it changed since the
     * last one this controller got ([version] says which it is).
     */
    @Serializable @SerialName("state")
    data class State(
        /** The last op from this controller that's been applied. */
        val ack: Long,
        val version: Int,
        val queue: List<QueueEntry>? = null,
        val index: Int,
        val positionMs: Long,
        val bufferedMs: Long = 0,
        val durationMs: Long = 0,
        val playWhenReady: Boolean,
        val playbackState: Int,
        val repeat: Int,
        val shuffle: Boolean,
        val volume: Int = 0,
        val maxVolume: Int = 0,
        /** Set when the host is itself only a remote for another device (by id). */
        val remoteOf: String? = null,
    ) : ConnectMessage

    @Serializable @SerialName("moved")
    data class Moved(val to: Peer?) : ConnectMessage
}

const val PROTOCOL_VERSION = 1

/** Keys for Connect. Pure, so the tests can check that accounts match or don't. */
object ConnectKeys {
    /**
     * The same on every device signed in as this user. Not the server URL: one
     * device may reach the server over the LAN and another over Tailscale.
     */
    fun account(username: String, password: String): ByteArray =
        hmac("seidr-connect/1".toByteArray(), "${username.trim().lowercase()}\u0000$password".toByteArray())

    /** Advertised over mDNS so discovery can skip other accounts. Reveals nothing about the login. */
    fun tag(account: ByteArray): String = hmac(account, "tag".toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    internal fun session(account: ByteArray, controllerNonce: ByteArray, hostNonce: ByteArray): ByteArray =
        hmac(account, "session".toByteArray() + controllerNonce + hostNonce)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
}

/** Thrown when a peer's first frame doesn't decrypt: it's signed in as someone else. */
class WrongAccountException : IOException("not the same account")

/**
 * Length-prefixed, deflated, AES-GCM frames. [open] runs the nonce exchange;
 * [send] is safe from any thread, [receive] is for one reader.
 */
class ConnectChannel private constructor(private val socket: Socket, private val isHost: Boolean) : Closeable {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private lateinit var key: SecretKeySpec
    private var sent = 0L
    private var received = 0L
    private var greeted = false

    private fun exchangeNonces(account: ByteArray) {
        val mine = ByteArray(NONCE_BYTES).also(random::nextBytes)
        output.write(MAGIC)
        output.write(mine)
        output.flush()
        val magic = ByteArray(MAGIC.size).also(input::readFully)
        if (!magic.contentEquals(MAGIC)) throw IOException("Not a Seiðr Connect peer")
        val theirs = ByteArray(NONCE_BYTES).also(input::readFully)
        key = SecretKeySpec(
            if (isHost) ConnectKeys.session(account, theirs, mine) else ConnectKeys.session(account, mine, theirs), "AES",
        )
    }

    @Synchronized
    fun send(message: ConnectMessage) {
        val plain = deflate(json.encodeToString(ConnectMessage.serializer(), message).toByteArray())
        val nonce = ByteArray(GCM_NONCE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(fromHost = isHost, seq = sent++))
        val sealed = cipher.doFinal(plain)
        output.writeInt(GCM_NONCE + sealed.size)
        output.write(nonce)
        output.write(sealed)
        output.flush()
    }

    fun receive(): ConnectMessage {
        val len = input.readInt()
        if (len !in GCM_NONCE + 16..MAX_FRAME) throw if (greeted) IOException("Bad frame") else WrongAccountException()
        val frame = ByteArray(len).also(input::readFully)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, frame, 0, GCM_NONCE))
            cipher.updateAAD(aad(fromHost = !isHost, seq = received++))
            cipher.doFinal(frame, GCM_NONCE, len - GCM_NONCE)
        } catch (e: GeneralSecurityException) {
            throw if (greeted) IOException("Frame didn't verify", e) else WrongAccountException()
        }
        greeted = true
        return try {
            json.decodeFromString(ConnectMessage.serializer(), inflate(plain).decodeToString())
        } catch (e: IllegalArgumentException) {
            throw IOException("Unreadable message", e)
        }
    }

    override fun close() = runCatching { socket.close() }.let { }

    private fun aad(fromHost: Boolean, seq: Long) = "seidr-connect/1/${if (fromHost) "h" else "c"}/$seq".toByteArray()

    companion object {
        /** Exchanges nonces over [socket] and derives this connection's key from [account]. */
        fun open(socket: Socket, account: ByteArray, isHost: Boolean): ConnectChannel =
            ConnectChannel(socket, isHost).also { it.exchangeNonces(account) }

        private val MAGIC = "SLC1".toByteArray()
        private const val NONCE_BYTES = 16
        private const val GCM_NONCE = 12
        /** A queue of tens of thousands of tracks still fits, deflated. */
        private const val MAX_FRAME = 32 * 1024 * 1024
        private val random = SecureRandom()
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        private fun deflate(data: ByteArray): ByteArray {
            val d = Deflater(Deflater.BEST_SPEED)
            d.setInput(data); d.finish()
            val out = ByteArrayOutputStream(data.size / 4 + 64)
            val buf = ByteArray(16 * 1024)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            d.end()
            return out.toByteArray()
        }

        private fun inflate(data: ByteArray): ByteArray {
            val i = Inflater()
            i.setInput(data)
            val out = ByteArrayOutputStream(data.size * 4)
            val buf = ByteArray(16 * 1024)
            while (!i.finished()) {
                val n = i.inflate(buf)
                if (n == 0 && (i.needsInput() || i.needsDictionary())) throw IOException("Truncated message")
                out.write(buf, 0, n)
                if (out.size() > MAX_FRAME * 8) throw IOException("Message too large")
            }
            i.end()
            return out.toByteArray()
        }
    }
}
