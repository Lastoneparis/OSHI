package com.oshi.messenger.network.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * __THE_IP_WE_ANNOUNCE_MUST_BE_THE_LAN_ONE_2026_10_22__
 *
 * The address this device puts in its GATT characteristic and its NSD record is the
 * address the iPhone dials to open the cross-platform TCP link. There is no BLE data
 * path: both platforms expose a READ-only characteristic, so if that TCP connection
 * never comes up, iOS <-> Android mesh does not happen at all.
 *
 * The old selection was "first IPv4 of the first non-loopback interface that is up",
 * in kernel enumeration order. The lists below are that order as real devices report
 * it, and in two of the most ordinary configurations it picked an address no peer on
 * the Wi-Fi could ever reach.
 */
class CrossPlatformMeshLanAddressTest {

    /** What the old code did, kept here so the tests state the difference. */
    private fun firstWins(candidates: List<Pair<String, String>>) = candidates.firstOrNull()?.second

    @Test
    fun `mobile data on puts the carrier address first and it must not be announced`() {
        // Pixel / most Qualcomm devices: rmnet interfaces are created at boot, so they
        // sit before wlan0. 10.137.x.x is carrier NAT — unreachable from the LAN.
        // dummy0 is up but carries no IPv4, so it never reaches this list.
        val enumerated = listOf(
            "rmnet_data0" to "10.137.84.22",
            "wlan0" to "192.168.1.37"
        )
        assertEquals("10.137.84.22", firstWins(enumerated))      // the bug
        assertEquals("192.168.1.37", CrossPlatformMesh.pickLanIPv4(enumerated))
    }

    @Test
    fun `a VPN tunnel is up and has an IPv4 and must not be announced`() {
        val enumerated = listOf(
            "tun0" to "10.8.0.6",
            "rmnet_data0" to "10.137.84.22",
            "wlan0" to "192.168.1.37"
        )
        assertEquals("10.8.0.6", firstWins(enumerated))          // the bug
        assertEquals("192.168.1.37", CrossPlatformMesh.pickLanIPv4(enumerated))
    }

    @Test
    fun `samsung spells the cellular interface differently and it is still excluded`() {
        val enumerated = listOf("ccmni0" to "100.82.17.9", "wlan0" to "192.168.0.14")
        assertEquals("192.168.0.14", CrossPlatformMesh.pickLanIPv4(enumerated))
    }

    @Test
    fun `464XLAT clat is cellular too`() {
        val enumerated = listOf("v4-rmnet_data2" to "192.0.0.4", "wlan0" to "10.0.0.8")
        assertEquals("10.0.0.8", CrossPlatformMesh.pickLanIPv4(enumerated))
    }

    @Test
    fun `hosting the hotspot is a usable LAN and gets announced when there is no station link`() {
        // The iPhone joined OUR hotspot: swlan0/ap0 is where it can reach us.
        val enumerated = listOf("rmnet_data0" to "10.137.84.22", "swlan0" to "192.168.43.1")
        assertEquals("192.168.43.1", CrossPlatformMesh.pickLanIPv4(enumerated))
    }

    @Test
    fun `a station link beats the hotspot we are hosting`() {
        val enumerated = listOf("swlan0" to "192.168.43.1", "wlan0" to "192.168.1.37")
        assertEquals("192.168.1.37", CrossPlatformMesh.pickLanIPv4(enumerated))
    }

    @Test
    fun `wifi direct group addresses are not announced - an iPhone cannot join one`() {
        val enumerated = listOf("p2p-wlan0-0" to "192.168.49.1", "wlan0" to "192.168.1.37")
        assertEquals("192.168.1.37", CrossPlatformMesh.pickLanIPv4(enumerated))
    }

    @Test
    fun `link-local means DHCP never finished - last resort, never a choice`() {
        assertEquals(
            "192.168.1.37",
            CrossPlatformMesh.pickLanIPv4(listOf("wlan0" to "169.254.7.7", "eth0" to "192.168.1.37"))
        )
        // but better than announcing nothing at all
        assertEquals("169.254.7.7", CrossPlatformMesh.pickLanIPv4(listOf("wlan0" to "169.254.7.7")))
    }

    @Test
    fun `usb ethernet and rndis count as a LAN`() {
        assertEquals("192.168.42.129", CrossPlatformMesh.pickLanIPv4(listOf("rndis0" to "192.168.42.129")))
    }

    @Test
    fun `cellular only means there is nothing to announce, and saying so beats lying`() {
        // Announcing the carrier address is what made the iPhone dial into a void.
        // Empty is honest: iOS then falls back to Bonjour instead of a dead connect.
        assertNull(CrossPlatformMesh.pickLanIPv4(listOf("rmnet_data0" to "10.137.84.22")))
        assertNull(CrossPlatformMesh.pickLanIPv4(emptyList()))
    }

    @Test
    fun `an unknown interface name is a last resort but still usable`() {
        assertEquals("192.168.5.9", CrossPlatformMesh.pickLanIPv4(listOf("weird9" to "192.168.5.9")))
        // ...and loses to wlan0
        assertEquals(
            "192.168.1.37",
            CrossPlatformMesh.pickLanIPv4(listOf("weird9" to "192.168.5.9", "wlan0" to "192.168.1.37"))
        )
    }
}
