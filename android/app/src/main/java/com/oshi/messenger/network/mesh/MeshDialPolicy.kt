package com.oshi.messenger.network.mesh

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * 🔒 __MESH_DIAL_LAN_ONLY_2026_11_20__ Which peer addresses the mesh may dial.
 *
 * A peer's IP and port arrive in an UNAUTHENTICATED Bluetooth announcement (or NSD
 * record). [CrossPlatformMesh.connectToPeer] used to dial whatever it said and then send
 * our identity frame (public key + display name) to it. A Bluetooth beacon near a
 * protester could therefore make the phone open a TCP connection to an internet server
 * the attacker runs and hand it the OSHI identity together with the phone's public IP —
 * linking the pseudonymous key to a network location. The mesh is a LOCAL transport:
 * only literal private / link-local addresses are dialled. Hostnames are refused
 * outright (resolving one would itself leak a DNS query).
 * Mirrors the iOS fix 8fcaeb34.
 */
object MeshDialPolicy {
    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun isDialable(ip: String?): Boolean {
        val s = ip?.trim()?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (s.isEmpty()) return false
        IPV4.matchEntire(s)?.let { m ->
            val o = m.groupValues.drop(1).map { it.toInt() }
            if (o.any { it > 255 }) return false
            return o[0] == 10 ||
                (o[0] == 172 && o[1] in 16..31) ||
                (o[0] == 192 && o[1] == 168) ||
                (o[0] == 169 && o[1] == 254)
        }
        // IPv6: literals only (must contain ':' and only hex/':'/'.'/'%' scope chars).
        if (!s.contains(':') || !s.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }) return false
        // Drop a `%scope` suffix before parsing: an interface name the JVM does not know
        // (`%wlan0` off-device) would otherwise fail the parse; the scope does not change
        // whether the address itself is local.
        val literal = s.substringBefore('%')
        val addr = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return false
        return when (addr) {
            is Inet6Address -> addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
                (addr.address[0].toInt() and 0xFE) == 0xFC   // fc00::/7 unique local
            is Inet4Address -> isDialable(addr.hostAddress)  // ::ffff:a.b.c.d
            else -> false
        }
    }
}
