package com.oshi.messenger

import com.oshi.messenger.service.lora.LoRaPacketParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __LORA_POSITION_BEACON_2026_10_28__ the bytes a POSITION_APP beacon puts on the air.
 *
 * The beacon is what lets other radios draw this node on their map. Its encoding is not
 * ours to choose: `Position.latitude_i` and `longitude_i` are **sfixed32 scaled by 1e-7**,
 * and portnum 3 is POSITION_APP. Get either wrong and the packet is still a valid frame —
 * it simply puts the node somewhere else in the world, on somebody else's screen, with
 * nothing red anywhere.
 *
 * So this asserts against the PARSER, which is the same code every inbound position goes
 * through: encode a known point, read it back the way a receiver would, and require the
 * coordinates to survive. A round trip through one implementation is not proof of a wire
 * format — but this parser was written from Meshtastic's own protobuf and is already
 * verified against real radios (`LiveRadioHeaderBench` reads live traffic through it), so
 * agreeing with it is agreeing with the mesh.
 */
class LoRaPositionBeaconTest {

    /**
     * The SHIPPED encoder — the one `broadcastPosition` calls. The first version of this
     * test re-derived `Math.round(lat * 1e7)` here instead, which agrees with itself
     * whatever the sender actually does and would have passed through a sign bug.
     */
    private fun positionPayload(lat: Double, lon: Double): ByteArray =
        LoRaPacketParser.positionPayload(lat, lon, 1_793_200_000L)

    @Test
    fun `POSITION_APP is portnum 3`() {
        assertEquals(
            "portnum 3 is POSITION_APP in Meshtastic's portnums.proto — every client maps on it",
            3L, LoRaPacketParser.PORT_POSITION,
        )
    }

    /** Decode exactly as `MeshtasticManager.handlePosition` does. */
    private fun decode(payload: ByteArray): Pair<Double, Double>? {
        var lat: Double? = null
        var lon: Double? = null
        for (pf in com.oshi.messenger.service.lora.LoRaProto.parseFields(payload)) {
            when {
                pf.number == 1 && pf.wire == 5 -> lat = pf.asInt32() * 1e-7
                pf.number == 2 && pf.wire == 5 -> lon = pf.asInt32() * 1e-7
            }
        }
        val la = lat ?: return null
        val lo = lon ?: return null
        return la to lo
    }

    @Test
    fun `a beacon round-trips through the receive parser`() {
        // Geneva, to five decimals.
        val lat = 46.20440
        val lon = 6.14320
        val payload = positionPayload(lat, lon)
        val parsed = decode(payload)
        assertNotNull("the parser must read what the beacon writes", parsed)
        assertEquals(lat, parsed!!.first, 1e-6)
        assertEquals(lon, parsed.second, 1e-6)
    }

    @Test
    fun `a southern and western point keeps its sign`() {
        // sfixed32 is SIGNED and little-endian. An unsigned read puts Ushuaia in Siberia,
        // which is the failure a positive-only fixture would never catch.
        val lat = -54.80190
        val lon = -68.30300
        val parsed = decode(positionPayload(lat, lon))
        assertNotNull(parsed)
        assertTrue("latitude must stay negative: ${parsed!!.first}", parsed.first < 0)
        assertTrue("longitude must stay negative: ${parsed.second}", parsed.second < 0)
        assertEquals(lat, parsed.first, 1e-6)
        assertEquals(lon, parsed.second, 1e-6)
    }
}
