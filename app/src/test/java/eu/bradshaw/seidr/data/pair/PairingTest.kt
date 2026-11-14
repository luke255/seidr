package eu.bradshaw.seidr.data.pair

import eu.bradshaw.seidr.data.ServerConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    private val config = ServerConfig("http://10.0.0.5:4533", "luke", "p@ss&word=1")
    private val loopback = listOf("127.0.0.1")

    @Test fun codeRoundTripsWithoutCredentials() {
        val host = PairHost.open("Living Room TV", giving = false, hosts = listOf("10.0.0.20", "100.64.1.2"))!!
        host.use {
            val text = it.code.encode()
            assertFalse(text.contains("luke"))
            val back = PairCode.parse(text)!!
            assertEquals(listOf("10.0.0.20", "100.64.1.2"), back.hosts)
            assertEquals(it.code.port, back.port)
            assertArrayEquals(it.code.key, back.key)
            assertFalse(back.giving)
            assertEquals("Living Room TV", back.name)
        }
    }

    @Test fun rejectsOtherCodes() {
        assertNull(PairCode.parse("https://example.com"))
        assertNull(PairCode.parse("seidr://pair?v=2&r=g&p=1&h=a&k=AAAA"))
        assertNull(PairCode.parse("seidr://pair?v=1&r=g&p=0&h=10.0.0.1&k=AAAA"))
        assertNull(PairCode.parse("seidr://pair?v=1&r=g&p=4000&h=10.0.0.1&k=short"))
        assertNull(PairHost.open("x", giving = true, hosts = emptyList()))
    }

    @Test fun tvShowsPhoneScans() = runBlocking {
        withTimeout(10_000) {
            PairHost.open("TV", giving = false, hosts = loopback)!!.use { host ->
                val tv = async { host.await(PairRole.Taker { null }) }
                val phone = joinPair(host.code, PairRole.Giver(config), "Phone")
                assertEquals(PairOutcome("TV"), phone)
                assertEquals(PairOutcome("Phone", null, config), tv.await())
            }
        }
    }

    @Test fun phoneShowsQuestScans() = runBlocking {
        withTimeout(10_000) {
            PairHost.open("Phone", giving = true, hosts = loopback)!!.use { host ->
                val phone = async { host.await(PairRole.Giver(config)) }
                val quest = joinPair(host.code, PairRole.Taker { null }, "Quest 3")
                assertEquals(PairOutcome("Phone", null, config), quest)
                assertEquals(PairOutcome("Quest 3"), phone.await())
            }
        }
    }

    @Test fun takerFailureReachesGiver() = runBlocking {
        withTimeout(10_000) {
            PairHost.open("TV", giving = false, hosts = loopback)!!.use { host ->
                val tv = async { host.await(PairRole.Taker { "Couldn't connect to ${it.url}" }) }
                val phone = joinPair(host.code, PairRole.Giver(config), "Phone")
                assertEquals("Couldn't connect to http://10.0.0.5:4533", phone.error)
                // The taker keeps what it received, so the form can be prefilled.
                assertEquals(config, tv.await().config)
            }
        }
    }

    @Test fun wrongKeyIsDroppedAndHostKeepsListening() = runBlocking {
        withTimeout(15_000) {
            PairHost.open("TV", giving = false, hosts = loopback)!!.use { host ->
                val tv = async { host.await(PairRole.Taker { null }) }
                val c = host.code
                val stale = PairCode(c.hosts, c.port, ByteArray(32) { 7 }, c.giving, c.name)
                val bad = joinPair(stale, PairRole.Giver(config), "Intruder")
                assertTrue(bad.error!!.contains("expired"))
                val good = joinPair(c, PairRole.Giver(config), "Phone")
                assertNull(good.error)
                assertEquals("Phone", tv.await().peer)
            }
        }
    }

    @Test fun unreachableHostSaysSo() = runBlocking {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val code = PairCode(loopback, port, ByteArray(32), giving = false, name = "TV")
        val out = joinPair(code, PairRole.Giver(config), "Phone")
        assertNotNull(out.error)
        assertTrue(out.error!!.startsWith("Couldn't reach TV"))
    }

    @Test fun sameRolesRefuse() = runBlocking {
        withTimeout(10_000) {
            PairHost.open("Phone A", giving = true, hosts = loopback)!!.use { host ->
                val a = async { host.await(PairRole.Giver(config)) }
                val b = joinPair(host.code, PairRole.Giver(config), "Phone B")
                assertEquals("Phone A is already signed in.", b.error)
                assertEquals("Phone B is already signed in.", a.await().error)
            }
        }
    }
}
