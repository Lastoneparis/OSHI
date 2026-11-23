package com.oshi.messenger.service.lora

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-rolled protobuf codec, checked against HAND-COMPUTED byte vectors.
 *
 * This exists because the encoder must produce byte-identical output to iOS's
 * (OSHI/MeshtasticManager.swift:25-84) — a Meshtastic node is the only referee,
 * and it does not report a malformed field, it just ignores it. A round-trip
 * test alone would pass happily on a codec that agreed with itself and with
 * nothing else, so every vector below is written out by hand.
 */
class LoRaProtoTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // ───────────────────────── varint ─────────────────────────

    @Test
    fun `varint matches the canonical vectors`() {
        // MeshtasticManager.swift:29-33. Note the repeat/while: ZERO is one 0x00
        // byte, not nothing — a codec that emitted nothing would shift every
        // following field.
        assertArrayEquals(bytes(0x00), ProtoWriter.vint(0))
        assertArrayEquals(bytes(0x01), ProtoWriter.vint(1))
        assertArrayEquals(bytes(0x7F), ProtoWriter.vint(127))
        assertArrayEquals(bytes(0x80, 0x01), ProtoWriter.vint(128))
        assertArrayEquals(bytes(0xAC, 0x02), ProtoWriter.vint(300))
        assertArrayEquals(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x0F), ProtoWriter.vint(0xFFFFFFFFL))
    }

    @Test
    fun `varint is unsigned, never sign-extended`() {
        // A node number of 0xFFFFFFFF must not be shifted arithmetically into an
        // endless run of 0xFF bytes.
        assertEquals(5, ProtoWriter.vint(0xFFFFFFFFL).size)
    }

    @Test
    fun `a negative int32 on the wire is ten bytes and reads back signed`() {
        // Meshtastic sends rx_rssi as a protobuf int32, so -95 arrives as the
        // 64-bit two's complement: ten varint bytes. MeshtasticManager.swift:960
        // truncates it back to Int32.
        // key = field 12, wire 0 = 12<<3 = 0x60.
        val wire = bytes(0x60, 0xA1, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01)
        val f = LoRaProto.parseFields(wire)
        assertEquals(1, f.size)
        assertEquals(12, f[0].number)
        assertEquals(-95, f[0].asInt32())
    }

    // ───────────────────────── keys and wire types ─────────────────────────

    @Test
    fun `field keys are field shl 3 or wiretype`() {
        // ToRadio.want_config_id is field 3, wire 0 → key 0x18. :411
        assertArrayEquals(bytes(0x18, 0xAC, 0x02), ProtoWriter().varint(3, 300).data)
        // MeshPacket.to is field 2, wire 5 → key 0x15, then LITTLE-endian. :773
        assertArrayEquals(
            bytes(0x15, 0x78, 0x56, 0x34, 0x12),
            ProtoWriter().fixed32(2, 0x12345678).data
        )
        // MeshPacket.decoded is field 4, wire 2 → key 0x22, then length. :774
        assertArrayEquals(
            bytes(0x22, 0x02, 0x68, 0x69),
            ProtoWriter().bytes(4, bytes(0x68, 0x69)).data
        )
        // AdminMessage.set_config is field 34 → key 34<<3|2 = 274 → 0x92 0x02. :622
        assertArrayEquals(bytes(0x92, 0x02, 0x00), ProtoWriter().bytes(34, ByteArray(0)).data)
    }

    @Test
    fun `fixed32 is little-endian in both directions`() {
        val encoded = ProtoWriter().fixed32(1, 0x11223344).data
        assertArrayEquals(bytes(0x0D, 0x44, 0x33, 0x22, 0x11), encoded)
        val f = LoRaProto.parseFields(encoded).single()
        assertEquals(5, f.wire)
        assertEquals(0x11223344u, f.asUInt32())
    }

    @Test
    fun `fixed32 carries a float bit pattern intact`() {
        // MeshPacket.rx_snr / NodeInfo.snr. :928, :959
        val encoded = ProtoWriter().fixed32(8, java.lang.Float.floatToRawIntBits(5.5f)).data
        assertEquals(5.5f, LoRaProto.parseFields(encoded).single().asFloat(), 0.0f)
    }

    @Test
    fun `fixed32 read as signed is what latitude needs`() {
        // latitude_i is sfixed32 scaled by 1e-7 — read unsigned, everything south
        // of the equator lands near the north pole. :918-924
        val encoded = ProtoWriter().fixed32(1, -487_000_000).data
        assertEquals(-48.7, LoRaProto.parseFields(encoded).single().asInt32() * 1e-7, 1e-9)
    }

    // ───────────────────────── parser behaviour ─────────────────────────

    @Test
    fun `round-trips a mixed message`() {
        val d = ProtoWriter()
            .varint(1, 256)
            .bytes(2, bytes(0xDE, 0xAD))
            .fixed32(6, 0x0000FFFF)
            .data
        val f = LoRaProto.parseFields(d)
        assertEquals(3, f.size)
        assertEquals(256L, f[0].varintValue)
        assertArrayEquals(bytes(0xDE, 0xAD), f[1].payload)
        assertEquals(0xFFFFu, f[2].asUInt32())
    }

    @Test
    fun `a truncated field ends the parse and keeps what was read`() {
        // :65, :72 — a radio handing us half a frame must not take the
        // connection down with it.
        val full = ProtoWriter().varint(1, 7).bytes(2, bytes(1, 2, 3, 4)).data
        val truncated = full.copyOf(full.size - 2)
        val f = LoRaProto.parseFields(truncated)
        assertEquals(1, f.size)
        assertEquals(7L, f[0].varintValue)
    }

    @Test
    fun `an unknown wire type stops the parse`() {
        // :80 — wire 3/4 (groups) are not in Meshtastic and are not decoded.
        val d = ProtoWriter().varint(1, 7).data + bytes(0x1B /* field 3, wire 3 */, 0x00)
        assertEquals(1, LoRaProto.parseFields(d).size)
    }

    @Test
    fun `an over-long varint is refused rather than wrapped`() {
        // :56 — shift > 63 aborts. Eleven continuation bytes is not a number.
        val d = ByteArray(12) { 0xFF.toByte() }
        assertTrue(LoRaProto.parseFields(d).isEmpty())
    }

    @Test
    fun `field lookup returns null for an absent field`() {
        assertNull(LoRaProto.field(ProtoWriter().varint(1, 1).data, 2))
    }

    // ───────────────────────── omit-defaults ─────────────────────────

    @Test
    fun `the writer emits only what it is told - the omit-defaults rule`() {
        // Protobuf has no way to say "this field is absent" other than not
        // writing it, so a field left out ARRIVES AS ZERO. That is why setRegion
        // writes every LoRaConfig field explicitly (:587-591): omitting
        // use_preset would send false and switch the radio to manual bandwidth /
        // spread factor of 0 — configured, on the air, unable to talk to anyone.
        val complete = LoRaPacketParser.adminSetLoRaConfig(regionRaw = 3, presetRaw = 0, hopLimit = 3)
        val loraConfig = LoRaProto.field(
            LoRaProto.field(complete, 34)!!.payload, 6
        )!!.payload
        val numbers = LoRaProto.parseFields(loraConfig).map { it.number }
        assertEquals(listOf(1, 2, 7, 8, 9), numbers)

        // The same config with use_preset dropped is a DIFFERENT, shorter
        // encoding, and the node cannot tell it from "use_preset = false".
        val partial = ProtoWriter().varint(2, 0).varint(7, 3).varint(8, 3).varint(9, 1).data
        assertTrue(partial.size < loraConfig.size)
        assertNull(LoRaProto.field(partial, 1))
    }
}
