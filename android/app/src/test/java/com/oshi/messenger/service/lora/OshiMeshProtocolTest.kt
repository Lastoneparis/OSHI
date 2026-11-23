package com.oshi.messenger.service.lora

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins OshiMeshProtocol.kt to the bytes the OSHI Mesh firmware expects (src/oshi/OshiProtocol.cpp,
 * test/test_oshi_protocol) and to its iOS twin (OSHITests/OshiMeshRadioTests.swift). The firmware
 * REJECTS middle fragments that are not exactly 182 bytes and ignores a wrong magic/version, so drift
 * here reads as "LoRa is flaky", not as a failure.
 *
 * Regressions guarded: fragment sizing that differs from the firmware, a header field shifted by one
 * byte, STATUS states renumbered, a zlib-WRAPPED body that iOS cannot inflate (iOS emits raw deflate),
 * and reassembly that surfaces a partial message.
 */
class OshiMeshProtocolTest {

    @Test
    fun dataFrameLayoutMatchesFirmware() {
        val body = ByteArray(400) { (it and 0xFF).toByte() }
        val frames = OshiMeshProtocol.dataFrames(0xDEADBEEFu, 0x55667788u, body, OshiMeshProtocol.FLAG_CUSTODY_OK)!!
        assertEquals(3, frames.size)
        assertEquals(18 + 182, frames[0].size)
        assertEquals(18 + 36, frames[2].size)
        val expect = intArrayOf(0x4F, 0x53, 0x11, 0xEF, 0xBE, 0xAD, 0xDE, 0, 0, 0, 0, 0x88, 0x77, 0x66, 0x55, 1, 3, 1)
            .map { it.toByte() }.toByteArray()
        assertArrayEquals(expect, frames[1].copyOfRange(0, 18))
        val p = OshiMeshProtocol.parseData(frames[1])!!
        assertEquals(1, p.idx)
        assertArrayEquals(body.copyOfRange(182, 364), p.payload)
    }

    /**
     * On a stock radio the phone does OMP itself: nothing downstream stamps the origin, so the phone must,
     * and the receiving side keys reassembly on it (two phones' msgId 7 must not merge).
     */
    @Test
    fun phoneStampsOriginOnStockRadio() {
        val broadcast = 0xFFFFFFFFu
        val frames = OshiMeshProtocol.dataFrames(7u, broadcast, ByteArray(300), origin = 0xA23033DBu)!!
        assertArrayEquals(
            intArrayOf(0xDB, 0x33, 0x30, 0xA2).map { it.toByte() }.toByteArray(), frames[0].copyOfRange(7, 11))
        val a = OshiMeshProtocol.parseData(frames[0])!!
        assertEquals(0xA23033DBu, a.origin)
        assertEquals(broadcast, a.dest)

        val rx = OmpReassembler()
        val other = OshiMeshProtocol.dataFrames(7u, broadcast, ByteArray(300), origin = 0x11111111u)!!
        assertNull(rx.accept(a))
        assertNull(rx.accept(OshiMeshProtocol.parseData(other[1])!!))
        assertEquals(300, rx.accept(OshiMeshProtocol.parseData(frames[1])!!)?.size)
    }

    @Test
    fun oversizeRefusedAndLegacyFramesIgnored() {
        assertNotNull(OshiMeshProtocol.dataFrames(1u, 2u, ByteArray(OshiMeshProtocol.MAX_MESSAGE)))
        assertNull(OshiMeshProtocol.dataFrames(1u, 2u, ByteArray(OshiMeshProtocol.MAX_MESSAGE + 1)))
        assertNull(OshiMeshProtocol.typeOf(byteArrayOf(0x4F, 0x4D, 1, 2, 3, 4, 0, 1)))
        assertNull(OshiMeshProtocol.typeOf(byteArrayOf(0x4F, 0x53, 0x21)))
    }

    @Test
    fun statusAndBeacon() {
        val st = OshiMeshProtocol.parseStatus(
            intArrayOf(0x4F, 0x53, 0x16, 9, 0, 0, 0, 4, 0x01, 0xEF, 0xCD, 0xAB).map { it.toByte() }.toByteArray())!!
        assertEquals(9u, st.msgId)
        assertEquals(OshiMeshProtocol.State.IN_CUSTODY, st.state)
        assertEquals(0xABCDEF01u, st.node)
        assertArrayEquals(byteArrayOf(0x4F, 0x53, 0x15, 0, 0, 0, 0), OshiMeshProtocol.beaconProbe())
        val b = OshiMeshProtocol.parseBeacon(byteArrayOf(0x4F, 0x53, 0x15, 3, 0, 1, 31))!!
        assertEquals(3, b.caps)
        assertEquals(0x0100, b.version)
    }

    @Test
    fun bodyIsRawDeflateLikeIos() {
        val json = "{\"ciphertext\":\"QUJDREVGR0g=\",\"senderPublicKey\":\"abc\"}".repeat(20).toByteArray()
        val body = OshiMeshProtocol.encodeBody(json)
        assertEquals(2, body[0].toInt())
        assertTrue(body.size < json.size)
        assertArrayEquals(json, OshiMeshProtocol.decodeBody(body))
        // A raw deflate stream never starts with a zlib header (0x78 xx).
        assertTrue(body[1].toInt() and 0xFF != 0x78)
        assertNull(OshiMeshProtocol.decodeBody(byteArrayOf(0x44, 1, 4)))
    }

    @Test
    fun reassemblyOutOfOrderAndPartial() {
        val body = ByteArray(500) { (it * 7).toByte() }
        val frames = OshiMeshProtocol.dataFrames(42u, 7u, body)!!.map { OshiMeshProtocol.parseData(it)!! }
        val r = OmpReassembler()
        assertNull(r.accept(frames[2]))
        assertNull(r.accept(frames[0]))
        assertNull(r.accept(frames[0]))
        assertArrayEquals(body, r.accept(frames[1]))
        val other = OshiMeshProtocol.dataFrames(43u, 7u, body)!!.map { OshiMeshProtocol.parseData(it)!! }
        assertNull(r.accept(other[0], nowMs = 0))
        assertNull(r.accept(other[1], nowMs = 1_000_000))
    }

    /** Produced on macOS by `NSData.compressed(using: .zlib)` — exactly what the iOS app sends. */
    @Test
    fun inflatesABodyCompressedByIos() {
        val apple = "ab564ace2cc8482d2a49ad2851b2520a0cf57209720d730f3248b755d2512a4ecd4b492d0a284dcac94cf64ead042a484c4a56aaad1ed5446f4d00".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val expected = "{\"ciphertext\":\"QUJDREVGR0g=\",\"senderPublicKey\":\"abc\"}".repeat(8).toByteArray()
        assertArrayEquals(expected, OshiMeshProtocol.decodeBody(byteArrayOf(2) + apple))
    }

    @Test
    fun channelPskMatchesFirmware() {
        assertEquals(32, OshiMeshProtocol.CHANNEL_PSK.size)
        assertArrayEquals(byteArrayOf(0x29, 0x43, 0x6d, 0x19), OshiMeshProtocol.CHANNEL_PSK.copyOfRange(0, 4))
    }

    /**
     * __ANTI_JAMMING_PHASE1_2026_11_13__ The RADIO frame (type 8) the OSHI Mesh firmware sends its phone every
     * 30 s. Same bytes as test_radio_stats_roundtrip in the firmware's test/test_oshi_protocol: noise is a
     * SIGNED byte (-97 dBm = 0x9F), -128 means "no sample", counters little-endian.
     */
    @Test
    fun radioFrameLayoutMatchesFirmware() {
        val expect = intArrayOf(0x4F, 0x53, 0x18, 0x9F, 0x25, 0x04, 0xD2, 0x04, 0, 0, 0x38, 0, 0, 0, 0x15, 0x03, 0, 0, 0x02, 0)
            .map { it.toByte() }.toByteArray()
        val stats = OshiMeshProtocol.RadioStats(-97, 37, 4, 1234, 56, 789, 2)
        assertArrayEquals(expect, OshiMeshProtocol.radioFrame(stats))
        assertEquals(OshiMeshProtocol.FrameType.RADIO, OshiMeshProtocol.typeOf(expect))
        assertEquals(stats, OshiMeshProtocol.parseRadio(expect))
        // Longer frames are a newer firmware appending fields: still read.
        assertEquals(stats, OshiMeshProtocol.parseRadio(expect + byteArrayOf(1, 2, 3)))
        // Truncated: rejected.
        assertNull(OshiMeshProtocol.parseRadio(expect.copyOf(19)))
        // No idle-RSSI sample yet.
        val unknown = expect.copyOf().also { it[3] = 0x80.toByte() }
        assertNull(OshiMeshProtocol.parseRadio(unknown)!!.noiseFloorDbm)
        // A BEACON is not a RADIO frame.
        assertNull(OshiMeshProtocol.parseRadio(OshiMeshProtocol.beaconProbe()))
    }
}
