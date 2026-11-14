package eu.bradshaw.seidr.data

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Plain http:// is allowed (network_security_config.xml) because most home
 * servers have no certificate, and on a home network or a tailnet that's fine:
 * the LAN is trusted and Tailscale encrypts anyway. Across the open internet it
 * isn't, so login warns when an http:// server resolves to a public address.
 */
object PlainHttp {
    /** True when [url] is http:// and its host resolves to any public address. Blocks: call off the main thread. */
    fun exposed(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("http", ignoreCase = true)) return false
        val host = uri.host ?: return false
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return false
        return addresses.any { !isPrivate(it) }
    }

    /** Loopback, LAN, link-local, Tailscale/CGNAT (100.64/10) or IPv6 unique-local. */
    fun isPrivate(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress) return true
        val b = a.address
        return when (a) {
            is Inet4Address -> (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
            is Inet6Address -> (b[0].toInt() and 0xfe) == 0xfc
            else -> false
        }
    }
}
