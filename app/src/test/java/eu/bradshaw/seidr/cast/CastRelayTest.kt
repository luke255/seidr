package eu.bradshaw.seidr.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastRelayTest {
    private val token = "0123456789abcdef0123456789abcdef"

    @Test fun routesOnlyWithTheToken() {
        assertEquals(CastRelay.Target('s', "abc"), CastRelay.route("/$token/s/abc", token))
        assertEquals(CastRelay.Target('a', "al-1_2"), CastRelay.route("/$token/a/al-1_2?x=1", token))
        assertEquals(CastRelay.Target('s', "a b"), CastRelay.route("/$token/s/a+b", token))
        assertNull(CastRelay.route("/wrong/s/abc", token))
        assertNull(CastRelay.route("/$token/x/abc", token))
        assertNull(CastRelay.route("/$token/s/", token))
        assertNull(CastRelay.route("/$token/s/a/b", token))
        assertNull(CastRelay.route("/", token))
    }

    @Test fun ranges() {
        assertEquals(0L to 99L, CastRelay.parseRange("bytes=0-", 100))
        assertEquals(10L to 19L, CastRelay.parseRange("bytes=10-19", 100))
        assertEquals(50L to 99L, CastRelay.parseRange("bytes=50-500", 100))
        assertEquals(90L to 99L, CastRelay.parseRange("bytes=-10", 100))
        assertEquals(0L to 99L, CastRelay.parseRange("bytes=-500", 100))
        assertNull(CastRelay.parseRange("bytes=100-", 100))
        assertNull(CastRelay.parseRange("bytes=20-10", 100))
        assertNull(CastRelay.parseRange("bytes=0-1,5-6", 100))
        assertNull(CastRelay.parseRange("items=0-1", 100))
        assertNull(CastRelay.parseRange("bytes=0-", 0))
    }

    @Test fun readsTheRequestHead() {
        val req = "GET /t/s/1 HTTP/1.1\r\nHost: x\r\nRange: bytes=5-\r\n\r\nbody".byteInputStream()
        assertEquals(CastRelay.Head("GET", "/t/s/1", "bytes=5-"), CastRelay.readHead(req))
        assertNull(CastRelay.readHead("GARBAGE".byteInputStream()))
        assertNull(CastRelay.readHead(("GET /" + "a".repeat(20_000) + " HTTP/1.1\r\n\r\n").byteInputStream()))
    }
}
