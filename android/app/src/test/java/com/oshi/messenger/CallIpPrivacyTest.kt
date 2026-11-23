package com.oshi.messenger

import com.oshi.messenger.service.p2p.P2PTransport
import com.oshi.messenger.service.p2p.P2PTransport.LocalAddress
import com.oshi.messenger.service.p2p.P2PTransport.CandidateType
import com.oshi.messenger.service.p2p.P2PTransport.P2PCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ICE candidates are the one thing a call hands the peer before any media
 * flows, so they are where an IP leaks. Two policies are asserted here:
 *
 *  1. While a VPN is up, Android still shows the app BOTH tun0 and the
 *     physical wlan0/rmnet address. We must advertise only the tunnel.
 *  2. "Always use relay server" must actually stop the peer learning our
 *     address, not merely change which pair carries audio.
 *
 * Each fixture is built so the PREVIOUS behaviour (advertise everything
 * gathered) produces a different answer from the expected one — a test that
 * merely re-ran the implementation's own filter would pass either way.
 */
class CallIpPrivacyTest {

    private fun host(ip: String) = P2PCandidate(CandidateType.HOST, ip, 5000, 0)
    private fun srflx(ip: String) = P2PCandidate(CandidateType.SRFLX, ip, 5000, 0)
    private fun relay(ip: String) = P2PCandidate(CandidateType.RELAY, ip, 5000, 0)

    // ---------------------------------------------------------------
    // 1. Tunnel filtering
    // ---------------------------------------------------------------

    @Test
    fun `wifi address is withheld while a vpn tunnel is up`() {
        val gathered = listOf(
            LocalAddress("wlan0", "192.168.1.37", 4),
            LocalAddress("tun0", "10.8.0.2", 4)
        )

        val safe = P2PTransport.hostAddressesSafeToAdvertise(gathered)

        assertEquals(listOf("10.8.0.2"), safe.map { it.ip })
        assertFalse(
            "the real LAN address must never reach the peer while a tunnel is up",
            safe.any { it.ip == "192.168.1.37" }
        )
    }

    @Test
    fun `a global ipv6 on the physical link is withheld when the tunnel is v4 only`() {
        // The sharpest case: a VPN that does not capture IPv6. The v6 address is
        // globally routable and per-device, so leaking it is worse than the v4.
        val gathered = listOf(
            LocalAddress("rmnet_data0", "2a01:cb00:1234:5678:1:2:3:4", 6),
            LocalAddress("tun0", "10.8.0.2", 4)
        )

        val safe = P2PTransport.hostAddressesSafeToAdvertise(gathered)

        assertEquals(listOf("10.8.0.2"), safe.map { it.ip })
    }

    @Test
    fun `every address is advertised when no tunnel is present`() {
        // Without a VPN nothing changes — LAN-direct calls must still work.
        val gathered = listOf(
            LocalAddress("wlan0", "192.168.1.37", 4),
            LocalAddress("rmnet_data0", "10.144.2.9", 4)
        )

        val safe = P2PTransport.hostAddressesSafeToAdvertise(gathered)

        assertEquals(gathered, safe)
    }

    @Test
    fun `wireguard and ipsec devices count as tunnels`() {
        assertTrue(P2PTransport.isTunnelInterface("tun0"))
        assertTrue(P2PTransport.isTunnelInterface("wg0"))
        assertTrue(P2PTransport.isTunnelInterface("ipsec1"))
        assertTrue(P2PTransport.isTunnelInterface("ppp0"))
        assertFalse(P2PTransport.isTunnelInterface("wlan0"))
        assertFalse(P2PTransport.isTunnelInterface("rmnet_data0"))
        assertFalse(P2PTransport.isTunnelInterface("eth0"))
    }

    /**
     * The interface table measured on a real iPhone 17 Pro Max on 2026-09-14, with
     * Mullvad up on utun3. It is here rather than only in the iOS suite because the
     * policy is shared and this is the case that broke the first version of it.
     *
     * The first fix filtered by interface NAME and passed every synthetic fixture —
     * but iOS keeps ~15 ipsec/utun interfaces up for Wi-Fi Calling and IMS, and
     * ALIASES the carrier's global IPv6 onto them: pdp_ip3, ipsec5 and ipsec9 all
     * carry 2a04:cec0:33:b6b8:96:ff17:37e9:5bab. A name filter re-advertised it, so
     * the leak survived a green suite and a green device run.
     */
    @Test
    fun `the carrier global ipv6 does not survive even when aliased onto an ipsec interface`() {
        val realIPhone = listOf(
            LocalAddress("pdp_ip0", "10.81.88.217", 4),
            LocalAddress("pdp_ip1", "10.32.165.223", 4),
            LocalAddress("pdp_ip3", "2a04:cec0:33:b6b8:96:ff17:37e9:5bab", 6),
            LocalAddress("pdp_ip3", "2a04:cec0:33:b6b8:19a3:b871:20d9:d9b2", 6),
            LocalAddress("en2", "169.254.135.240", 4),
            LocalAddress("ipsec4", "192.0.0.6", 4),
            LocalAddress("ipsec4", "fd74:6572:6d6e:7573:d:b0a:ffd6:68f3", 6),
            LocalAddress("utun3", "10.66.71.10", 4),
            LocalAddress("utun3", "2a0e:7d43:0:26::a:a", 6),
            LocalAddress("ipsec5", "2a04:cec0:33:b6b8:96:ff17:37e9:5bab", 6),
            LocalAddress("ipsec9", "2a04:cec0:33:b6b8:96:ff17:37e9:5bab", 6),
            LocalAddress("bridge100", "172.20.10.1", 4),
            LocalAddress("ipsec6", "10.32.165.223", 4),
            LocalAddress("ipsec8", "10.32.165.223", 4),
            LocalAddress("utun8", "fda3:cb00:59d9::1", 6)
        )

        val advertised = P2PTransport.hostAddressesSafeToAdvertise(realIPhone).map { it.ip }

        assertFalse(
            "the carrier IPv6 is aliased onto ipsec5/ipsec9 and must not survive: $advertised",
            advertised.any { it.startsWith("2a04:cec0") }
        )
        assertFalse(
            "the VPN's own global IPv6 is still a global address: $advertised",
            advertised.any { it.startsWith("2a0e:7d43") }
        )
        assertTrue(
            "nothing globally routable may be advertised: $advertised",
            advertised.none { ip -> P2PTransport.isGloballyRoutable(ip, if (ip.contains(':')) 6 else 4) }
        )
        assertEquals(
            "an address aliased onto four interfaces must be advertised once",
            advertised.distinct(), advertised
        )
    }

    @Test
    fun `globally routable classification`() {
        assertTrue(P2PTransport.isGloballyRoutable("93.184.216.34", 4))
        assertTrue(P2PTransport.isGloballyRoutable("2a04:cec0:33:b6b8:96:ff17:37e9:5bab", 6))
        assertTrue(P2PTransport.isGloballyRoutable("2001:db8::1", 6))
        // Private / carrier / protocol blocks are not a deanonymiser.
        assertFalse(P2PTransport.isGloballyRoutable("192.168.1.37", 4))
        assertFalse(P2PTransport.isGloballyRoutable("10.66.71.10", 4))
        assertFalse(P2PTransport.isGloballyRoutable("172.20.10.1", 4))
        assertFalse(P2PTransport.isGloballyRoutable("100.100.5.5", 4))   // CGNAT
        assertFalse(P2PTransport.isGloballyRoutable("192.0.0.6", 4))     // 464XLAT
        assertFalse(P2PTransport.isGloballyRoutable("169.254.135.240", 4))
        assertFalse(P2PTransport.isGloballyRoutable("fd74:6572::1", 6))  // ULA
        // 172.32 is OUTSIDE 172.16/12 — a naive "starts with 172" would get this wrong.
        assertTrue(P2PTransport.isGloballyRoutable("172.32.0.1", 4))
    }

    // ---------------------------------------------------------------
    // 2. Force-relay actually hides the address
    // ---------------------------------------------------------------

    @Test
    fun `force relay hands the peer the relay candidate and nothing else`() {
        val gathered = listOf(
            host("192.168.1.37"),
            srflx("93.184.216.34"),
            relay("45.67.216.197")
        )

        val published = P2PTransport.publishableCandidates(gathered, forceRelay = true)

        assertEquals(listOf(relay("45.67.216.197")), published)
    }

    @Test
    fun `force relay withholds srflx too because it is our public address`() {
        // srflx is the NAT-mapped public IP. Emitting it under a toggle labelled
        // "always use relay" would defeat the toggle even though media relays.
        val gathered = listOf(srflx("93.184.216.34"), relay("45.67.216.197"))

        val published = P2PTransport.publishableCandidates(gathered, forceRelay = true)

        assertFalse(published.any { it.type == CandidateType.SRFLX })
    }

    @Test
    fun `without force relay the full set is published`() {
        val gathered = listOf(host("192.168.1.37"), srflx("93.184.216.34"))

        val published = P2PTransport.publishableCandidates(gathered, forceRelay = false)

        assertEquals(gathered, published)
    }

    @Test
    fun `force relay before the allocation lands publishes nothing rather than leaking`() {
        // Gathering finishes before TURN allocates. The old code emitted the
        // host+srflx set at that moment; the peer had our address before the
        // relay candidate ever existed.
        val gathered = listOf(host("192.168.1.37"), srflx("93.184.216.34"))

        val published = P2PTransport.publishableCandidates(gathered, forceRelay = true)

        assertTrue(published.isEmpty())
    }
}
