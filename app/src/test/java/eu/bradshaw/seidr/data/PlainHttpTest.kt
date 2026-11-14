package eu.bradshaw.seidr.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class PlainHttpTest {
    private fun private(ip: String) = PlainHttp.isPrivate(InetAddress.getByName(ip))

    @Test fun homeAndTailnetAddressesArePrivate() {
        listOf("10.0.0.5", "192.168.1.20", "172.16.4.1", "127.0.0.1", "169.254.3.3",
            "100.64.0.1", "100.103.49.60", "100.127.255.254", "fd7a:115c:a1e0::1", "fe80::1", "::1")
            .forEach { assertTrue(it, private(it)) }
    }

    @Test fun internetAddressesArePublic() {
        listOf("8.8.8.8", "100.63.255.255", "100.128.0.1", "172.32.0.1", "2001:4860:4860::8888")
            .forEach { assertFalse(it, private(it)) }
    }

    @Test fun onlyHttpCanBeExposed() {
        assertFalse(PlainHttp.exposed("https://8.8.8.8"))
        assertFalse(PlainHttp.exposed("http://10.0.0.5:4533"))
        assertTrue(PlainHttp.exposed("http://8.8.8.8:4533"))
    }
}
