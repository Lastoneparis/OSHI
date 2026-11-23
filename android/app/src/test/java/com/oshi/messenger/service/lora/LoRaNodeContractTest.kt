package com.oshi.messenger.service.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vendor-neutral contract types: the small rules the UI renders directly and
 * that no radio is involved in.
 */
class LoRaNodeContractTest {

    private fun node(rssi: Int) = DiscoveredLoRaNode(
        id = "AA:BB:CC:DD:EE:FF", name = "Meshtastic 23d8", rssi = rssi,
        family = LoRaFirmwareFamily.MESHTASTIC,
    )

    @Test
    fun `signal bars bucket exactly as iOS does`() {
        // LoRaNodeProtocol.swift:187-194
        assertEquals(4, node(-40).signalBars)
        assertEquals(4, node(-60).signalBars)     // -60 is INCLUSIVE at the top
        assertEquals(3, node(-61).signalBars)
        assertEquals(3, node(-70).signalBars)
        assertEquals(2, node(-71).signalBars)
        assertEquals(2, node(-85).signalBars)
        assertEquals(1, node(-86).signalBars)
        assertEquals(1, node(-120).signalBars)
    }

    @Test
    fun `health is ordered worst to best`() {
        // The strongest-node picker compares these. LoRaNodeProtocol.swift:94-105
        assertTrue(LoRaNodeHealth.DISCONNECTED < LoRaNodeHealth.CONNECTING)
        assertTrue(LoRaNodeHealth.CONNECTING < LoRaNodeHealth.DEGRADED)
        assertTrue(LoRaNodeHealth.DEGRADED < LoRaNodeHealth.HEALTHY)
    }

    @Test
    fun `a fresh status reports UNKNOWN, never zero`() {
        // A node that has not reported its battery must render "—", never 0 %.
        // LoRaNodeProtocol.swift:109-113
        val s = LoRaNodeStatus()
        assertEquals(LoRaNodeHealth.DISCONNECTED, s.health)
        assertNull(s.batteryLevel)
        assertNull(s.isCharging)
        assertNull(s.loRaRssi)
        assertNull(s.loRaSnr)
        assertNull(s.region)
        assertNull(s.frequencyMhz)
        assertNull(s.uptimeSeconds)
        assertNull(s.lastPacketAt)
    }

    @Test
    fun `Meshtastic 2x capabilities do not claim OTA`() {
        // Claiming it would put a button on screen that cannot work.
        // LoRaNodeProtocol.swift:79-85
        val c = LoRaNodeCapabilities.MESHTASTIC_2X
        assertTrue(c.canConfigureRegion)
        assertTrue(c.supportsRelay)
        assertEquals(false, c.supportsOTA)
    }

    @Test
    fun `an unmeasured transport reports null success, not one hundred percent`() {
        // LoRaTransport.swift:84-88 — a fresh transport is UNMEASURED, not perfect.
        assertNull(TransportQuality().successRate)
        assertEquals(1.0, TransportQuality().recordSent(null).successRate!!, 1e-9)
        assertEquals(0.5, TransportQuality().recordSent(null).recordFailure().successRate!!, 1e-9)
    }

    @Test
    fun `latency is smoothed, not replaced`() {
        // LoRaTransport.swift:93-100
        val q = TransportQuality().recordSent(1.0).recordSent(5.0)
        assertEquals(1.0 + 0.25 * (5.0 - 1.0), q.averageLatency!!, 1e-9)
    }

    @Test
    fun `LoRa sits between the meshes and the internet relay`() {
        // Descriptive of the routing that already exists. LoRaTransport.swift:41-50
        assertTrue(TransportKind.LORA.preferenceRank > TransportKind.WIFI_MESH.preferenceRank)
        assertTrue(TransportKind.LORA.preferenceRank < TransportKind.INTERNET_RELAY.preferenceRank)
    }

    // ───────────────────────── range estimate ─────────────────────────

    @Test
    fun `range estimate is a BAND, never a single number`() {
        // MeshtasticManager.swift:110-147. False precision here could send
        // someone the wrong way in a field.
        val (near, far) = LoRaMeshNode(num = 1u, rssi = -80).estimatedRangeKm!!
        assertTrue(near < far)
        assertTrue(near > 0)
    }

    @Test
    fun `a node that never reported RSSI gets no estimate`() {
        assertNull(LoRaMeshNode(num = 1u, rssi = null).estimatedRangeKm)
    }

    @Test
    fun `a weak signal gets NO estimate rather than a useless one`() {
        // Past roughly -105 dBm the band opens to tens of kilometres and the
        // model stops saying anything worth reading. :140-145
        assertNull(LoRaMeshNode(num = 1u, rssi = -130).estimatedRangeKm)
        assertNull(LoRaMeshNode(num = 1u, rssi = -140).estimatedRangeKm)
    }

    @Test
    fun `hasPosition needs BOTH coordinates`() {
        assertEquals(false, LoRaMeshNode(num = 1u, latitude = 48.7).hasPosition)
        assertTrue(LoRaMeshNode(num = 1u, latitude = 48.7, longitude = -2.3).hasPosition)
    }

    @Test
    fun `refusal keys are stable and localizable`() {
        // The driver must not hard-code user-facing English; res/values belongs to
        // the settings work stream.
        assertEquals("lora.status.needs_region", LoRaSendRefusal.NO_REGION.stringKey)
        assertEquals("lora.blocked.media", LoRaSendRefusal.CARRIES_MEDIA.stringKey)
        LoRaSendRefusal.entries.forEach {
            assertTrue(it.stringKey.startsWith("lora."))
            assertTrue(it.detailStringKey.startsWith("lora."))
        }
    }
}
