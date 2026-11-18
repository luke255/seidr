package eu.bradshaw.seidr.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastTest {
    @Test fun wireRoundTrips() {
        val m = CastMessage("sender-0", "receiver-0", CastProtocol.NS_RECEIVER, """{"type":"GET_STATUS","requestId":1,"é":"ü"}""")
        val framed = CastWire.frame(m)
        val n = ((framed[0].toInt() and 0xff) shl 24) or ((framed[1].toInt() and 0xff) shl 16) or
            ((framed[2].toInt() and 0xff) shl 8) or (framed[3].toInt() and 0xff)
        assertEquals(framed.size - 4, n)
        assertEquals(m, CastWire.decode(framed.copyOfRange(4, framed.size)))
    }

    @Test fun wireMatchesProtobuf() {
        // protoc --encode=CastMessage: version 0, "a", "b", "c", STRING, "{}".
        val expected = byteArrayOf(0x08, 0x00, 0x12, 0x01, 'a'.code.toByte(), 0x1a, 0x01, 'b'.code.toByte(),
            0x22, 0x01, 'c'.code.toByte(), 0x28, 0x00, 0x32, 0x02, '{'.code.toByte(), '}'.code.toByte())
        assertEquals(expected.toList(), CastWire.encode(CastMessage("a", "b", "c", "{}")).toList())
    }

    @Test fun wireSkipsUnknownAndBinary() {
        // Field 7 (payload_binary) and an unknown field 15 varint.
        val b = CastWire.encode(CastMessage("s", "d", "n", "x")) + byteArrayOf(0x3a, 0x01, 0x05, 0x78, 0x01)
        assertEquals("x", CastWire.decode(b).payload)
    }

    @Test fun upcomingStopsAtTheEndOrWraps() {
        val keys = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "d"), CastQueue.upcoming(keys, 1, repeatAll = false))
        assertEquals(listOf("d"), CastQueue.upcoming(keys, 2, repeatAll = false))
        assertEquals(listOf("d", "a"), CastQueue.upcoming(keys, 2, repeatAll = true))
        assertEquals(listOf("b"), CastQueue.upcoming(listOf("a", "b"), 0, repeatAll = true))
        assertEquals(emptyList<String>(), CastQueue.upcoming(listOf("a"), 0, repeatAll = true))
    }

    @Test fun planKeepsAMatchingPreload() {
        val p = CastQueue.plan(listOf(1 to "a", 2 to "b", 3 to "c"), 2, listOf("c", "d"))!!
        assertEquals(listOf(1), p.remove)
        assertEquals(listOf("d"), p.insert)
    }

    @Test fun planReplacesAChangedTail() {
        val p = CastQueue.plan(listOf(2 to "b", 3 to "c", 4 to "d"), 2, listOf("x", "c"))!!
        assertEquals(listOf(3, 4), p.remove)
        assertEquals(listOf("x", "c"), p.insert)
    }

    @Test fun planNothingWhenInStep() {
        assert(CastQueue.plan(listOf(2 to "b", 3 to "c"), 2, listOf("c"))!!.empty)
    }

    @Test fun planNeedsTheCurrentItem() {
        assertNull(CastQueue.plan(listOf(2 to "b"), 9, listOf("c")))
    }

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun parsesMediaStatus() {
        val s = CastProtocol.mediaStatus(obj("""{"type":"MEDIA_STATUS","status":[{"mediaSessionId":3,"playerState":"PLAYING",
            "currentTime":12.5,"currentItemId":7,"media":{"contentId":"song|k","duration":201.3}}],"requestId":0}"""))!!
        assertEquals(3L, s.mediaSessionId)
        assertEquals("PLAYING", s.playerState)
        assertEquals(7, s.currentItemId)
        assertEquals("song|k", s.contentId)
        assertEquals(201.3, s.durationS!!, 0.001)
        assertNull(CastProtocol.mediaStatus(obj("""{"type":"MEDIA_STATUS","status":[]}""")))
    }

    @Test fun parsesReceiverStatus() {
        val r = CastProtocol.receiverStatus(obj("""{"type":"RECEIVER_STATUS","requestId":1,"status":{"applications":[
            {"appId":"CC1AD845","sessionId":"s1","transportId":"t1","displayName":"Default Media Receiver"}],
            "volume":{"level":0.3,"muted":false,"stepInterval":0.05}}}"""))!!
        assertEquals(listOf(CastProtocol.App("CC1AD845", "s1", "t1")), r.apps)
        assertEquals(0.3, r.volume!!, 0.0001)
        assertEquals(0.05, r.step!!, 0.0001)
    }

    @Test fun parsesQueueReplies() {
        assertEquals(listOf(4, 5), CastProtocol.itemIds(obj("""{"type":"QUEUE_ITEM_IDS","itemIds":[4,5]}""")))
        assertEquals(mapOf(4 to "a|1"), CastProtocol.itemKeys(obj("""{"type":"QUEUE_ITEMS","items":[{"itemId":4,"media":{"contentId":"a|1"}}]}""")))
    }

    @Test fun queueLoadStartsTheFirstItemOnly() {
        val item = CastProtocol.Item("k1", "http://s/rest/stream.view?id=1", "audio/flac", "T", "A", "Al", null, 200)
        val load = CastProtocol.queueLoad(9, listOf(item, item.copy(key = "k2")), play = false, startS = 30.0, repeatOne = false)
        val items = load["items"].toString()
        assert(items.contains("\"autoplay\":false")) { items }
        assert(items.contains("\"startTime\":30.0")) { items }
        assertEquals(1, Regex("startTime").findAll(items).count())
    }
}
