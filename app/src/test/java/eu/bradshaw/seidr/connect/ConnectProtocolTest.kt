package eu.bradshaw.seidr.connect

import eu.bradshaw.seidr.playback.QueueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class ConnectProtocolTest {
    @Test
    fun sameUserSameKeyWhateverTheCase() {
        assertTrue(ConnectKeys.account("Luke", "pw").contentEquals(ConnectKeys.account(" luke", "pw")))
        assertEquals(ConnectKeys.tag(ConnectKeys.account("luke", "pw")), ConnectKeys.tag(ConnectKeys.account("LUKE", "pw")))
    }

    @Test
    fun otherUserOrPasswordDiffers() {
        val tag = ConnectKeys.tag(ConnectKeys.account("luke", "pw"))
        assertNotEquals(tag, ConnectKeys.tag(ConnectKeys.account("jake", "pw")))
        assertNotEquals(tag, ConnectKeys.tag(ConnectKeys.account("luke", "pw2")))
    }

    private fun pair(hostKey: ByteArray, controllerKey: ByteArray, host: (ConnectChannel) -> Unit, controller: (ConnectChannel) -> Unit) {
        ServerSocket(0).use { server ->
            var hostError: Throwable? = null
            val t = thread {
                try { server.accept().use { host(ConnectChannel.open(it, hostKey, isHost = true)) } } catch (e: Throwable) { hostError = e }
            }
            Socket("127.0.0.1", server.localPort).use { controller(ConnectChannel.open(it, controllerKey, isHost = false)) }
            t.join(5_000)
            hostError?.let { throw it }
        }
    }

    @Test
    fun roundTripsALargeQueue() {
        val key = ConnectKeys.account("luke", "pw")
        val queue = (0 until 5_000).map { QueueEntry("s$it|k$it", "s$it", "Title $it", "Artist", "Album", duration = 200, ctxIndex = it, ctxName = "Liked") }
        var got: ConnectMessage? = null
        pair(key, key, host = { ch ->
            val hello = ch.receive() as ConnectMessage.Hello
            assertEquals("fold", hello.id)
            ch.send(ConnectMessage.State(ack = 3, version = 1, queue = queue, index = 7, positionMs = 1234,
                playWhenReady = true, playbackState = 3, repeat = 0, shuffle = true))
        }, controller = { ch ->
            ch.send(ConnectMessage.Hello("fold", "Fold"))
            got = ch.receive()
        })
        val s = got as ConnectMessage.State
        assertEquals(queue, s.queue)
        assertEquals(7, s.index)
        assertTrue(s.shuffle)
    }

    @Test
    fun anotherAccountFailsOnTheFirstFrame() {
        var hostSaw: Throwable? = null
        pair(ConnectKeys.account("luke", "pw"), ConnectKeys.account("jake", "pw"), host = { ch ->
            hostSaw = runCatching { ch.receive() }.exceptionOrNull()
        }, controller = { ch ->
            ch.send(ConnectMessage.Hello("x", "Jake's phone"))
        })
        assertTrue(hostSaw is WrongAccountException)
    }

    @Test
    fun opsSurviveTheTrip() {
        val key = ConnectKeys.account("luke", "pw")
        val ops = listOf(RemoteOp.Seek(2, 0, 9), RemoteOp.Release(Peer("a", "A", listOf("10.0.0.2"), 4000)), RemoteOp.TakeOver, RemoteOp.Volume(5))
        val got = mutableListOf<RemoteOp>()
        pair(key, key, host = { ch -> repeat(ops.size) { got += (ch.receive() as ConnectMessage.Op).op } },
            controller = { ch -> ops.forEachIndexed { i, op -> ch.send(ConnectMessage.Op(i + 1L, op)) } })
        assertEquals(ops, got)
    }

    // --- Guests (signed-out hosts) ---------------------------------------------------

    private fun <H, C> guestPair(host: (Socket) -> H, controller: (Socket) -> C): Pair<H?, C> {
        ServerSocket(0).use { server ->
            var hostResult: H? = null
            var hostError: Throwable? = null
            val t = thread {
                try { server.accept().use { hostResult = host(it) } } catch (e: Throwable) { hostError = e }
            }
            val c = Socket("127.0.0.1", server.localPort).use { controller(it) }
            t.join(5_000)
            hostError?.let { throw it }
            return hostResult to c
        }
    }

    @Test
    fun guestHandshakeAgreesOnKeyAndCode() {
        val identity = ConnectKeys.newKeyPair()
        val (hostSide, controllerSide) = guestPair(host = { sock ->
            val ch = ConnectChannel.accept(sock, null) { identity }
            val hello = ch.receive() as ConnectMessage.Hello
            ch.send(ConnectMessage.Hello("tab", "Tablet"))
            val guest = ch.receive() as ConnectMessage.Guest
            Triple(ch.guest!!.code, hello.verify, guest)
        }, controller = { sock ->
            val ch = ConnectChannel.dialGuest(sock)
            ch.send(ConnectMessage.Hello("fold", "Fold", verify = true))
            val hello = ch.receive() as ConnectMessage.Hello
            ch.send(ConnectMessage.Guest("https://m.example", "luke", "pw", "neutral"))
            Triple(ch.guest!!.code, ch.guest!!.hostKey, hello.name)
        })
        val (hostCode, verify, guest) = hostSide!!
        assertEquals(controllerSide.first, hostCode)
        assertTrue(hostCode.matches(Regex("\\d{6}")))
        assertTrue(controllerSide.second.contentEquals(identity.public.encoded))
        assertEquals("Tablet", controllerSide.third)
        assertTrue(verify)
        assertEquals("luke", guest.username)
    }

    @Test
    fun eachGuestConnectionHasItsOwnCode() {
        val identity = ConnectKeys.newKeyPair()
        val codes = (1..3).map {
            guestPair(host = { ConnectChannel.accept(it, null) { identity }.guest!!.code },
                controller = { ConnectChannel.dialGuest(it).guest!!.code }).second
        }
        assertEquals(3, codes.toSet().size)
    }

    @Test
    fun signedInHostRefusesAGuestAndSignedOutHostRefusesAnAccount() {
        val identity = ConnectKeys.newKeyPair()
        val account = ConnectKeys.account("luke", "pw")
        val signedIn = runCatching {
            guestPair(host = { ConnectChannel.accept(it, account) { identity } }, controller = { runCatching { ConnectChannel.dialGuest(it) } })
        }.exceptionOrNull()
        assertTrue(signedIn is SignedInOrOutException)
        val signedOut = runCatching {
            guestPair(host = { ConnectChannel.accept(it, null) { identity } }, controller = { runCatching { ConnectChannel.open(it, account, isHost = false) } })
        }.exceptionOrNull()
        assertTrue(signedOut is SignedInOrOutException)
    }

    @Test
    fun codesComeOnlyFromTheExchangedValues() {
        val a = ByteArray(91) { 1 }; val b = ByteArray(91) { 2 }; val n1 = ByteArray(16) { 3 }; val n2 = ByteArray(16) { 4 }
        assertEquals(ConnectKeys.code(a, b, n1, n2), ConnectKeys.code(a, b, n1, n2))
        assertNotEquals(ConnectKeys.code(a, b, n1, n2), ConnectKeys.code(b, a, n1, n2))
    }
}
