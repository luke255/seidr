package eu.bradshaw.seidr.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Talks to a real Cast device, only when `SEIDR_TEST_CAST` names one
 * (`host[:port]`). By itself it only asks for the receiver's status, which
 * makes no sound. With `SEIDR_TEST_CAST_URL` (an audio link the device can
 * reach) it also launches the Default Media Receiver, plays that for a few
 * seconds at `SEIDR_TEST_CAST_VOLUME` (default 0.1) and stops it.
 */
class CastLiveTest {
    private val target = System.getenv("SEIDR_TEST_CAST")
    private val json = Json { ignoreUnknownKeys = true }

    private fun open(): CastChannel {
        val host = target!!.substringBefore(':')
        val port = target.substringAfter(':', "").toIntOrNull() ?: 8009
        return CastChannel.open(host, port).also { it.readTimeoutMs = 15_000 }
    }

    private fun CastChannel.say(dest: String, ns: String, body: String) = send(CastMessage("sender-test", dest, ns, body))

    /** The next message of [type] (skipping heartbeats and anything else). */
    private fun CastChannel.await(type: String): kotlinx.serialization.json.JsonObject {
        repeat(50) {
            val m = receive()
            val o = m.payload?.let { json.parseToJsonElement(it).jsonObject } ?: return@repeat
            println("<- ${m.namespace.substringAfterLast('.')}: ${m.payload!!.take(400)}")
            if (o.type() == "PING") say(m.source, CastProtocol.NS_HEARTBEAT, """{"type":"PONG"}""")
            if (o.type() == type) return o
        }
        error("no $type")
    }

    @Test fun receiverStatus() {
        assumeTrue(target != null)
        open().use { ch ->
            ch.say(CastProtocol.PLATFORM, CastProtocol.NS_CONNECTION, """{"type":"CONNECT"}""")
            ch.say(CastProtocol.PLATFORM, CastProtocol.NS_RECEIVER, """{"type":"GET_STATUS","requestId":1}""")
            println(CastProtocol.receiverStatus(ch.await("RECEIVER_STATUS")))
        }
    }

    @Test fun playsAQueue() {
        val url = System.getenv("SEIDR_TEST_CAST_URL")
        assumeTrue(target != null && url != null)
        val volume = System.getenv("SEIDR_TEST_CAST_VOLUME")?.toDoubleOrNull() ?: 0.1
        open().use { ch ->
            ch.say(CastProtocol.PLATFORM, CastProtocol.NS_CONNECTION, """{"type":"CONNECT"}""")
            ch.say(CastProtocol.PLATFORM, CastProtocol.NS_RECEIVER, CastProtocol.setVolume(1, volume).toString())
            ch.await("RECEIVER_STATUS")
            ch.say(CastProtocol.PLATFORM, CastProtocol.NS_RECEIVER, CastProtocol.launch(2).toString())
            var app: CastProtocol.App? = null
            while (app == null) app = CastProtocol.receiverStatus(ch.await("RECEIVER_STATUS"))?.apps?.firstOrNull { it.appId == CastProtocol.DEFAULT_RECEIVER }
            try {
                play(ch, app, url)
            } finally {
                ch.say(CastProtocol.PLATFORM, CastProtocol.NS_RECEIVER, CastProtocol.stop(5, app.sessionId).toString())
                ch.await("RECEIVER_STATUS")
            }
        }
    }

    private fun play(ch: CastChannel, app: CastProtocol.App, url: String) {
            ch.say(app.transportId, CastProtocol.NS_CONNECTION, """{"type":"CONNECT"}""")
            val item = CastProtocol.Item("test|1", url, "audio/flac", "Seiðr test", "Seiðr", "Test", null, 0)
            ch.say(app.transportId, CastProtocol.NS_MEDIA,
                CastProtocol.queueLoad(3, listOf(item, item.copy(key = "test|2")), play = true, startS = 0.0, repeatOne = false).toString())
            var playing: CastProtocol.MediaStatus? = null
            val until = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < until) {
                val s = CastProtocol.mediaStatus(ch.await("MEDIA_STATUS")) ?: continue
                println("status: $s")
                if (s.playerState == "PLAYING") { playing = s; break }
            }
            checkNotNull(playing) { "never played" }
            ch.say(app.transportId, CastProtocol.NS_MEDIA, CastProtocol.queueItemIds(4, playing.mediaSessionId).toString())
            println("ids: ${CastProtocol.itemIds(ch.await("QUEUE_ITEM_IDS"))}")
            Thread.sleep(4_000)
    }
}
