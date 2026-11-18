package eu.bradshaw.seidr.cast

import android.annotation.SuppressLint
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * A TLS connection to a Cast device (port 8009, or a group's own port),
 * carrying [CastWire] frames. Writes are synchronised; [receive] blocks and
 * belongs to one reader.
 *
 * Cast devices present self-signed certificates, so TLS here encrypts but
 * doesn't prove the device is genuine (Google's SDK does that with a separate
 * challenge; pychromecast, like this, doesn't). The worst a fake "speaker" on
 * the LAN gets is what a real one would: the stream links we send it.
 */
class CastChannel private constructor(private val socket: SSLSocket) : Closeable {
    private val input = DataInputStream(socket.inputStream.buffered())
    private val output = socket.outputStream

    @Synchronized
    fun send(m: CastMessage) {
        output.write(CastWire.frame(m))
        output.flush()
    }

    fun receive(): CastMessage {
        val n = input.readInt()
        if (n < 0 || n > CastWire.MAX_MESSAGE) throw IOException("Bad Cast frame length $n")
        val body = ByteArray(n)
        input.readFully(body)
        return CastWire.decode(body)
    }

    /** This device's address on the network that reaches the Cast device. */
    val localHost: String? get() = socket.localAddress?.hostAddress?.substringBefore('%')

    var readTimeoutMs: Int
        get() = socket.soTimeout
        set(value) { socket.soTimeout = value }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        fun open(host: String, port: Int, timeoutMs: Int = 3_000): CastChannel {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(AnyCertificate), SecureRandom())
            val socket = ctx.socketFactory.createSocket() as SSLSocket
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                socket.tcpNoDelay = true
                socket.startHandshake()
                return CastChannel(socket)
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
        }
    }
}

/** See [CastChannel]: a Cast device's certificate is its own, signed by nobody we could check. */
@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
private object AnyCertificate : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Not a server")

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw CertificateException("No certificate")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
