package eu.bradshaw.seidr.cast

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * One Cast v2 message: a JSON [payload] on a [namespace], between a sender
 * ([source]) and a receiver ([destination]). Binary payloads (only device
 * authentication uses them) arrive with [payload] null.
 */
data class CastMessage(val source: String, val destination: String, val namespace: String, val payload: String?)

/**
 * The Cast v2 wire format, by hand: each message is a protobuf `CastMessage`
 * (seven fields, so no protobuf library) after a 4-byte big-endian length.
 * Pure, so it's unit-tested.
 */
object CastWire {
    /** The receiver drops anything larger. */
    const val MAX_MESSAGE = 65_536

    fun frame(m: CastMessage): ByteArray {
        val body = encode(m)
        if (body.size > MAX_MESSAGE) throw IOException("Cast message too large (${body.size} bytes)")
        val n = body.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + body
    }

    fun encode(m: CastMessage): ByteArray {
        val out = ByteArrayOutputStream()
        varintField(out, 1, 0) // protocol_version: CASTV2_1_0
        stringField(out, 2, m.source)
        stringField(out, 3, m.destination)
        stringField(out, 4, m.namespace)
        varintField(out, 5, 0) // payload_type: STRING
        stringField(out, 6, m.payload.orEmpty())
        return out.toByteArray()
    }

    fun decode(b: ByteArray): CastMessage {
        var source = ""
        var destination = ""
        var namespace = ""
        var payload: String? = null
        var i = 0
        fun varint(): Long {
            var shift = 0
            var v = 0L
            while (true) {
                if (i >= b.size) throw IOException("Truncated Cast message")
                val x = b[i++].toInt() and 0xff
                v = v or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return v
                shift += 7
                if (shift > 63) throw IOException("Bad varint in Cast message")
            }
        }
        fun bytes(): ByteArray {
            val n = varint()
            if (n < 0 || n > b.size - i) throw IOException("Truncated Cast message")
            return b.copyOfRange(i, i + n.toInt()).also { i += n.toInt() }
        }
        while (i < b.size) {
            val tag = varint()
            val field = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> varint()
                1 -> i += 8
                2 -> {
                    val v = bytes()
                    when (field) {
                        2 -> source = v.decodeToString()
                        3 -> destination = v.decodeToString()
                        4 -> namespace = v.decodeToString()
                        6 -> payload = v.decodeToString()
                    }
                }
                5 -> i += 4
                else -> throw IOException("Bad wire type in Cast message")
            }
            if (i > b.size) throw IOException("Truncated Cast message")
        }
        return CastMessage(source, destination, namespace, payload)
    }

    private fun varintField(out: ByteArrayOutputStream, field: Int, value: Long) {
        varint(out, (field shl 3).toLong())
        varint(out, value)
    }

    private fun stringField(out: ByteArrayOutputStream, field: Int, value: String) {
        val bytes = value.encodeToByteArray()
        varint(out, ((field shl 3) or 2).toLong())
        varint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7fL.inv() != 0L) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}
