package com.oshi.messenger.network.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __A_SIGHTING_WITH_AN_ADDRESS_WAS_THROWN_AWAY_2026_11_09__
 *
 * Anatole, 2026-11-09: an iPhone listed "À proximité", 0 connected, and Connecter
 * answering "Pas encore d'adresse… nouvelle recherche" forever. The discovered list
 * kept the FIRST sighting of a phone: a BLE announce read before the iPhone had a
 * LAN address pinned the row at `ipAddress = null`, and every later announce or NSD
 * resolve that carried one was dialled once and discarded.
 */
class CrossPlatformMeshSightingTest {

    private val fullKey = "Q2xhdWRlT3B1c0lzQVRlc3RLZXlGb3JNZXNoMTIzNDU2Nzg5MA=="
    private val blePrefix = fullKey.take(32)

    private fun peer(pk: String, ip: String? = null, port: Int? = null, name: String = "Livio りびB", platform: String = "ios") =
        CrossPlatformPeer(publicKey = pk, displayName = name, platform = platform, ipAddress = ip, port = port)

    @Test
    fun `a later announce with an address fills the row the first one left empty`() {
        val first = CrossPlatformMesh.mergeSighting(emptyList(), peer(blePrefix, ip = null, port = 52011))
        assertNull(first.single().ipAddress)

        val after = CrossPlatformMesh.mergeSighting(first, peer(blePrefix, ip = "192.168.1.42", port = 52011))
        assertEquals(1, after.size)
        assertEquals("192.168.1.42", after.single().ipAddress)
        assertEquals(52011, after.single().port)
    }

    @Test
    fun `the NSD resolve and the BLE prefix are one device, and the full key wins`() {
        val ble = CrossPlatformMesh.mergeSighting(emptyList(), peer(blePrefix))
        val both = CrossPlatformMesh.mergeSighting(ble, peer(fullKey, ip = "192.168.1.42", port = 52011))
        assertEquals(1, both.size)
        assertEquals(fullKey, both.single().publicKey)
        assertEquals("192.168.1.42", both.single().ipAddress)

        // And the other way round: a BLE prefix arriving after the full key does not shorten it.
        val again = CrossPlatformMesh.mergeSighting(both, peer(blePrefix))
        assertEquals(fullKey, again.single().publicKey)
    }

    @Test
    fun `an address is never replaced by a sighting without one`() {
        val known = listOf(peer(fullKey, ip = "192.168.1.42", port = 52011))
        val after = CrossPlatformMesh.mergeSighting(known, peer(blePrefix, ip = null, port = null, name = "Unknown", platform = "unknown"))
        assertEquals("192.168.1.42", after.single().ipAddress)
        assertEquals(52011, after.single().port)
        assertEquals("Livio りびB", after.single().displayName)
        assertEquals("ios", after.single().platform)
    }

    @Test
    fun `two duplicate rows already in the list collapse into one, in place`() {
        val other = peer("T3RoZXJQZWVyS2V5VGhhdElzTG9uZ0Vub3VnaFRvQmVSZWFs", ip = "192.168.1.9", port = 1, name = "Other")
        val list = listOf(other, peer(blePrefix), peer(fullKey, ip = "192.168.1.42", port = 52011))
        val after = CrossPlatformMesh.mergeSighting(list, peer(blePrefix))
        assertEquals(listOf("Other", "Livio りびB"), after.map { it.displayName })
        assertEquals("192.168.1.42", after[1].ipAddress)
    }

    @Test
    fun `a different device is appended, and an empty key is ignored`() {
        val list = listOf(peer(fullKey))
        val other = peer("T3RoZXJQZWVyS2V5VGhhdElzTG9uZ0Vub3VnaFRvQmVSZWFs", name = "Other")
        assertEquals(2, CrossPlatformMesh.mergeSighting(list, other).size)
        assertEquals(list, CrossPlatformMesh.mergeSighting(list, peer("")))
    }

    @Test
    fun `identity matching folds base64url and accepts only a 32-char prefix`() {
        assertTrue(CrossPlatformMesh.sameMeshIdentity(fullKey, blePrefix))
        assertTrue(CrossPlatformMesh.sameMeshIdentity("ab-_cd==", "ab+/cd"))
        // 16 shared characters is not enough to call two keys one device.
        assertFalse(CrossPlatformMesh.sameMeshIdentity(fullKey, fullKey.take(16)))
        assertFalse(CrossPlatformMesh.sameMeshIdentity("", ""))
    }

    @Test
    fun `the iPhone hotspot host is the gateway of a 172_20_10 slash 28 client`() {
        assertEquals("172.20.10.1", CrossPlatformMesh.iosHotspotHostFor("172.20.10.3"))
        assertEquals("172.20.10.1", CrossPlatformMesh.iosHotspotHostFor("172.20.10.14"))
        // We ARE the host, or outside the /28, or not on a hotspot at all.
        assertNull(CrossPlatformMesh.iosHotspotHostFor("172.20.10.1"))
        assertNull(CrossPlatformMesh.iosHotspotHostFor("172.20.10.17"))
        assertNull(CrossPlatformMesh.iosHotspotHostFor("192.168.1.37"))
        assertNull(CrossPlatformMesh.iosHotspotHostFor(null))
    }
}
