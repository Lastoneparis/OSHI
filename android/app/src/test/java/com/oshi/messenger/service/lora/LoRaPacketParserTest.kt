package com.oshi.messenger.service.lora

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Meshtastic protobufs OSHI encodes and decodes, against hand-computed bytes.
 *
 * Field numbers are the part nobody can eyeball at runtime: a wrong one produces
 * a frame the node silently ignores, and "Set region" quietly doing nothing is
 * exactly the bug iOS shipped and had to fix (MeshtasticManager.swift:616-621).
 */
class LoRaPacketParserTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // ───────────────────────── encoders ─────────────────────────

    @Test
    fun `want_config is field 3 varint`() {
        // :410-412
        assertArrayEquals(bytes(0x18, 0xAC, 0x02), LoRaPacketParser.wantConfig(300u))
    }

    @Test
    fun `toRadioPacket matches a hand-computed frame`() {
        // :766-782.
        //   Data       : 08 01                (portnum = 1)
        //                12 02 68 69          (payload = "hi")
        //   MeshPacket : 15 FF FF FF FF       (to   = 0xFFFFFFFF, fixed32)
        //                22 06 <data>         (decoded)
        //                35 01 00 00 00       (id   = 1, fixed32)
        //   ToRadio    : 0A 12 <packet>
        val expected = bytes(
            0x0A, 0x12,
            0x15, 0xFF, 0xFF, 0xFF, 0xFF,
            0x22, 0x06, 0x08, 0x01, 0x12, 0x02, 0x68, 0x69,
            0x35, 0x01, 0x00, 0x00, 0x00,
        )
        val actual = LoRaPacketParser.toRadioPacket(
            to = 0xFFFFFFFFu,
            portnum = LoRaPacketParser.PORT_TEXT,
            payload = "hi".toByteArray(),
            wantAck = false,
            packetId = 1u,
        )
        assertArrayEquals(expected, actual)
    }

    /**
     * The OSHI↔OSHI lane, whole, in literal bytes.
     *
     * The frame above pins portnum 1, which is a ONE-BYTE varint. Portnum 256 —
     * PRIVATE_APP, the port every encrypted OSHI envelope rides — is the first
     * value that needs a CONTINUATION byte, `0x80 0x02`. An encoder that
     * truncated it to a single byte would put every OSHI message on portnum 0
     * and the iPhone would never see one, while the interop text on portnum 1
     * kept working perfectly — so the existing fixture cannot catch it.
     * MeshtasticManager.swift:169, :766-782.
     */
    @Test
    fun `portnum 256 is a two-byte varint on the wire`() {
        //   Data       : 08 80 02             (portnum = 256, PRIVATE_APP)
        //                12 02 4F 4D          (payload = "OM")
        //   MeshPacket : 15 FF FF FF FF       (to = broadcast, fixed32)
        //                22 07 <data>         (decoded)
        //                35 22 11 00 00       (id = 0x1122, fixed32 little-endian)
        //                50 01                (want_ack = true)
        //   ToRadio    : 0A 15 <packet>
        val expected = bytes(
            0x0A, 0x15,
            0x15, 0xFF, 0xFF, 0xFF, 0xFF,
            0x22, 0x07, 0x08, 0x80, 0x02, 0x12, 0x02, 0x4F, 0x4D,
            0x35, 0x22, 0x11, 0x00, 0x00,
            0x50, 0x01,
        )
        assertArrayEquals(
            expected,
            LoRaPacketParser.toRadioPacket(
                to = LoRaPacketParser.BROADCAST_ADDR,
                portnum = LoRaPacketParser.PORT_OSHI,
                payload = bytes(0x4F, 0x4D),
                wantAck = true,
                packetId = 0x1122u,
            )
        )
    }

    @Test
    fun `want_ack is OMITTED when false and present when true`() {
        // Protobuf has no "false" on the wire that differs from "absent", so the
        // encoder must not write it. :776
        val without = LoRaPacketParser.toRadioPacket(1u, 1, ByteArray(0), false, 1u)
        val with = LoRaPacketParser.toRadioPacket(1u, 1, ByteArray(0), true, 1u)
        assertEquals(with.size - 2, without.size)

        val pktW = LoRaProto.field(with, 1)!!.payload
        assertEquals(1L, LoRaProto.field(pktW, 10)!!.varintValue)
        assertNull(LoRaProto.field(LoRaProto.field(without, 1)!!.payload, 10))
    }

    @Test
    fun `broadcast address is 0xFFFFFFFF and survives as unsigned`() {
        // :168 — read as signed it would be -1 and address nobody.
        val d = LoRaPacketParser.toRadioPacket(
            LoRaPacketParser.BROADCAST_ADDR, 1, ByteArray(0), false, 1u
        )
        val pkt = LoRaProto.field(d, 1)!!.payload
        assertEquals(0xFFFFFFFFu, LoRaProto.field(pkt, 2)!!.asUInt32())
    }

    @Test
    fun `set_config rides field 34, never 33`() {
        // AdminMessage.set_channel is 33 and carries a Channel; set_config is 34
        // and carries a Config. Under 33 the node parses it as a Channel and
        // throws it away. :616-621
        val admin = LoRaPacketParser.adminSetLoRaConfig(3, 0, 3)
        assertNotNull(LoRaProto.field(admin, 34))
        assertNull(LoRaProto.field(admin, 33))
    }

    @Test
    fun `LoRaConfig writes every field explicitly, byte for byte`() {
        // :604-622. lora = 08 01 | 10 00 | 38 03 | 40 03 | 48 01
        //           config = 32 0A <lora>
        //           admin  = 92 02 0C <config>
        val expected = bytes(
            0x92, 0x02, 0x0C,
            0x32, 0x0A,
            0x08, 0x01,   // use_preset  = true
            0x10, 0x00,   // modem_preset = 0 (LongFast)
            0x38, 0x03,   // region      = 3 (EU868)
            0x40, 0x03,   // hop_limit   = 3
            0x48, 0x01,   // tx_enabled  = true
        )
        assertArrayEquals(expected, LoRaPacketParser.adminSetLoRaConfig(3, 0, 3))
    }

    @Test
    fun `BluetoothConfig writes enabled and mode under Config field 7`() {
        // :738-753. bt = 08 01 | 10 02 ; config = 3A 04 <bt> ; admin = 92 02 06 <config>
        val expected = bytes(0x92, 0x02, 0x06, 0x3A, 0x04, 0x08, 0x01, 0x10, 0x02)
        assertArrayEquals(expected, LoRaPacketParser.adminSetBluetoothConfig(2))
    }

    @Test
    fun `set_owner rides field 32 and truncates short_name to four`() {
        // :702-718
        val admin = LoRaPacketParser.adminSetOwner(0x000023D8u, "OSHI Mesh 23D8", "23D8LONG")
        val user = LoRaProto.field(admin, 32)!!.payload
        assertEquals("!000023d8", String(LoRaProto.field(user, 1)!!.payload))
        assertEquals("OSHI Mesh 23D8", String(LoRaProto.field(user, 2)!!.payload))
        assertEquals("23D8", String(LoRaProto.field(user, 3)!!.payload))
    }

    @Test
    fun `node id string is lowercase eight-digit hex`() {
        assertEquals("!000023d8", LoRaPacketParser.nodeIdString(0x23D8u))
        assertEquals("!ffffffff", LoRaPacketParser.nodeIdString(0xFFFFFFFFu))
    }

    // ───────────────────────── MeshPacket ─────────────────────────

    private fun meshPacket(
        from: UInt,
        portnum: Long,
        payload: ByteArray,
        requestIdVarint: UInt? = null,
        requestIdFixed32: UInt? = null,
        snr: Float? = null,
        rssi: Int? = null,
    ): ByteArray {
        val data = ProtoWriter().varint(1, portnum).bytes(2, payload)
        requestIdVarint?.let { data.varint(6, it.toLong()) }
        requestIdFixed32?.let { data.fixed32(6, it) }
        val p = ProtoWriter().fixed32(1, from).bytes(4, data.data)
        snr?.let { p.fixed32(8, java.lang.Float.floatToRawIntBits(it)) }
        rssi?.let { p.varint(12, it.toLong()) }
        return p.data
    }

    @Test
    fun `MeshPacket reads from, portnum and payload`() {
        // :952-996
        val d = meshPacket(0x11223344u, LoRaPacketParser.PORT_OSHI, bytes(0x4F, 0x4D))
        val p = LoRaPacketParser.parseMeshPacket(d)!!
        assertEquals(0x11223344u, p.from)
        assertEquals(256L, p.portnum)
        assertArrayEquals(bytes(0x4F, 0x4D), p.payload)
    }

    @Test
    fun `request_id is accepted as a VARINT`() {
        // :983-986 — some firmware builds emit it this way.
        val d = meshPacket(1u, LoRaPacketParser.PORT_ROUTING, ByteArray(0), requestIdVarint = 0xABCDu)
        assertEquals(0xABCDu, LoRaPacketParser.parseMeshPacket(d)!!.requestId)
    }

    @Test
    fun `request_id is accepted as a FIXED32`() {
        // :987 — and others emit it this way. Missing either means every ACK on
        // that firmware is silently discarded, so delivery can never be confirmed
        // OR disproved.
        val d = meshPacket(1u, LoRaPacketParser.PORT_ROUTING, ByteArray(0), requestIdFixed32 = 0xABCDu)
        assertEquals(0xABCDu, LoRaPacketParser.parseMeshPacket(d)!!.requestId)
    }

    @Test
    fun `absent request_id reads as zero, which never matches a pending send`() {
        val d = meshPacket(1u, LoRaPacketParser.PORT_ROUTING, ByteArray(0))
        assertEquals(0u, LoRaPacketParser.parseMeshPacket(d)!!.requestId)
    }

    @Test
    fun `snr is a float bit pattern and rssi is signed`() {
        val d = meshPacket(1u, 1, ByteArray(0), snr = -7.25f, rssi = -102)
        val p = LoRaPacketParser.parseMeshPacket(d)!!
        assertEquals(-7.25f, p.snr!!, 0.0f)
        assertEquals(-102, p.rssi)
    }

    @Test
    fun `a MeshPacket with no decoded submessage is dropped`() {
        // :964
        assertNull(LoRaPacketParser.parseMeshPacket(ProtoWriter().fixed32(1, 5).data))
    }

    // ───────────────────────── NodeInfo ─────────────────────────

    @Test
    fun `NodeInfo reads user, position and hops`() {
        // :901-950
        val user = ProtoWriter()
            .bytes(1, "!000023d8".toByteArray())
            .bytes(2, "Heltec V3".toByteArray())
            .bytes(3, "23D8".toByteArray())
            .data
        val pos = ProtoWriter()
            .fixed32(1, 487_000_000)     // +48.7
            .fixed32(2, -23_000_000)     // -2.3
            .data
        val d = ProtoWriter()
            .varint(1, 0x23D8)
            .bytes(2, user)
            .bytes(3, pos)
            .fixed32(4, java.lang.Float.floatToRawIntBits(6.25f))
            .varint(9, 2)
            .data
        val n = LoRaPacketParser.parseNodeInfo(d)!!
        assertEquals(0x23D8u, n.num)
        assertEquals("Heltec V3", n.longName)
        assertEquals("23D8", n.shortName)
        assertEquals(48.7, n.latitude!!, 1e-9)
        // Read unsigned this would land near the north pole. :918-924
        assertEquals(-2.3, n.longitude!!, 1e-9)
        assertEquals(6.25f, n.snr, 0f)
        assertEquals(2, n.hopsAway)
    }

    @Test
    fun `a NodeInfo without a position reports null, never zero`() {
        val d = ProtoWriter().varint(1, 7).data
        val n = LoRaPacketParser.parseNodeInfo(d)!!
        assertNull(n.latitude)
        assertNull(n.longitude)
        assertNull(n.hopsAway)
    }

    @Test
    fun `NodeInfo with num zero is dropped`() {
        assertNull(LoRaPacketParser.parseNodeInfo(ProtoWriter().varint(9, 1).data))
    }

    // ───────────────────────── Config ─────────────────────────

    @Test
    fun `an ABSENT region reads as UNSET`() {
        // Protobuf omits zero values, so "absent" and "unset" are the same thing
        // and both mean CANNOT TRANSMIT. :881-884
        val config = ProtoWriter().bytes(6, ByteArray(0)).data
        val c = LoRaPacketParser.parseConfig(config)
        assertEquals(0L, c.regionRaw)
        assertEquals(true, c.txEnabled)
    }

    @Test
    fun `a region that IS present is read verbatim`() {
        val lora = ProtoWriter().varint(7, 3).varint(9, 0).data
        val c = LoRaPacketParser.parseConfig(ProtoWriter().bytes(6, lora).data)
        assertEquals(3L, c.regionRaw)
        assertEquals(false, c.txEnabled)
    }

    @Test
    fun `no Config-lora at all leaves the region UNREAD, which is not UNSET`() {
        // The distinction the send guard depends on: unknown is not zero.
        // :497-502
        val c = LoRaPacketParser.parseConfig(ByteArray(0))
        assertNull(c.regionRaw)
        assertNull(c.txEnabled)
    }

    @Test
    fun `absent bluetooth mode reads as RANDOM_PIN`() {
        // Mode 0 is the default and is therefore omitted from the wire. :890-895
        val c = LoRaPacketParser.parseConfig(ProtoWriter().bytes(7, ByteArray(0)).data)
        assertEquals(0L, c.bluetoothPairingRaw)
        assertEquals(LoRaBluetoothPairing.RANDOM_PIN, LoRaBluetoothPairing.fromRaw(0))
    }

    @Test
    fun `bluetooth NO_PIN is 2, not 1`() {
        val bt = ProtoWriter().varint(2, 2).data
        val c = LoRaPacketParser.parseConfig(ProtoWriter().bytes(7, bt).data)
        assertEquals(2L, c.bluetoothPairingRaw)
        assertEquals(LoRaBluetoothPairing.NO_PIN, LoRaBluetoothPairing.fromRaw(2))
    }

    // ───────────────────────── FromRadio dispatch ─────────────────────────

    @Test
    fun `FromRadio dispatches on the documented field numbers`() {
        // :837-861 — 2 packet, 3 MyNodeInfo, 4 NodeInfo, 5 Config, 7 config_complete_id
        val d = ProtoWriter()
            .bytes(2, meshPacket(9u, 1, "x".toByteArray()))
            .bytes(3, ProtoWriter().varint(1, 0x23D8).data)
            .bytes(4, ProtoWriter().varint(1, 5).data)
            .bytes(5, ProtoWriter().bytes(6, ByteArray(0)).data)
            .varint(7, 12345)
            .data
        val events = LoRaPacketParser.parseFromRadio(d)
        assertEquals(5, events.size)
        assertTrue(events[0] is LoRaPacketParser.FromRadio.Packet)
        assertEquals(0x23D8u, (events[1] as LoRaPacketParser.FromRadio.MyNodeInfo).myNodeNum)
        assertTrue(events[2] is LoRaPacketParser.FromRadio.NodeInfoUpdate)
        assertTrue(events[3] is LoRaPacketParser.FromRadio.ConfigUpdate)
        assertEquals(12345u, (events[4] as LoRaPacketParser.FromRadio.ConfigComplete).configId)
    }

    @Test
    fun `an unrecognised FromRadio field is ignored, not fatal`() {
        val d = ProtoWriter().varint(99, 1).varint(7, 5).data
        val events = LoRaPacketParser.parseFromRadio(d)
        assertEquals(1, events.size)
    }

    // ───────────────────────── Routing ─────────────────────────

    @Test
    fun `routing reason absent or zero means delivered`() {
        // :790-796
        assertEquals(0L, LoRaPacketParser.routingErrorReason(ByteArray(0)))
        assertEquals(0L, LoRaPacketParser.routingErrorReason(ProtoWriter().varint(3, 0).data))
        assertFalse(LoRaPacketParser.routingErrorReason(ProtoWriter().varint(3, 4).data) == 0L)
    }

    // __ANTI_JAMMING_PHASE1_2026_11_13__ telemetry.proto, read for LoRaJammingDetector.

    private fun f32(x: Float) = java.lang.Float.floatToIntBits(x)

    @Test
    fun localStatsCarriesCountersAndANegativeNoiseFloor() {
        val stats = ProtoWriter()
            .varint(1, 3600)                // uptime_seconds
            .fixed32(2, f32(31.5f))         // channel_utilization
            .fixed32(3, f32(2.25f))         // air_util_tx
            .varint(4, 77)                  // num_packets_tx
            .varint(5, 120)                 // num_packets_rx (good + bad)
            .varint(6, 9)                   // num_packets_rx_bad
            .varint(15, -104L)              // noise_floor, int32: a 10-byte varint on the wire
            .data
        val telemetry = ProtoWriter().fixed32(1, 1_700_000_000u).bytes(6, stats).data
        val t = LoRaPacketParser.parseTelemetry(telemetry)!!
        assertTrue(t.isLocalStats)
        assertEquals(31.5f, t.channelUtilPct!!, 0.001f)
        assertEquals(2.25f, t.airUtilTxPct!!, 0.001f)
        assertEquals(120L, t.rxTotal)
        assertEquals(9L, t.rxBad)
        assertEquals(-104, t.noiseFloorDbm)
    }

    @Test
    fun localStatsWithoutNoiseSampleOrCountersMeansUnknownNoiseAndZeroCounters() {
        val t = LoRaPacketParser.parseTelemetry(ProtoWriter().bytes(6, ProtoWriter().fixed32(2, f32(5f)).data).data)!!
        assertNull(t.noiseFloorDbm)
        assertEquals(0L, t.rxTotal)
        assertEquals(0L, t.rxBad)
    }

    @Test
    fun deviceMetricsCarriesUtilisationOnly() {
        val dm = ProtoWriter().varint(1, 101).fixed32(2, f32(4.1f)).fixed32(3, f32(12f)).fixed32(4, f32(1.5f)).data
        val t = LoRaPacketParser.parseTelemetry(ProtoWriter().bytes(2, dm).data)!!
        assertFalse(t.isLocalStats)
        assertEquals(12f, t.channelUtilPct!!, 0.001f)
        assertEquals(1.5f, t.airUtilTxPct!!, 0.001f)
        assertNull(t.rxTotal)
        assertNull(t.noiseFloorDbm)
    }

    @Test
    fun environmentTelemetryIsNotRadioTelemetry() {
        assertNull(LoRaPacketParser.parseTelemetry(ProtoWriter().bytes(3, ProtoWriter().fixed32(1, f32(21f)).data).data))
    }

    @Test
    fun localStatsRequestIsAnEmptyLocalStatsWithWantResponse() {
        assertArrayEquals(bytes(0x32, 0x00), LoRaPacketParser.localStatsRequest())
        val frame = LoRaPacketParser.toRadioPacket(0x1234u, LoRaPacketParser.PORT_TELEMETRY,
            LoRaPacketParser.localStatsRequest(), wantAck = false, packetId = 7u, wantResponse = true)
        val packet = LoRaProto.field(frame, 1, 2)!!.payload
        val data = LoRaProto.field(packet, 4, 2)!!.payload
        assertEquals(67L, LoRaProto.field(data, 1, 0)!!.varintValue)
        assertEquals(1L, LoRaProto.field(data, 3, 0)!!.varintValue)
        // And the default stays byte-identical to before: no want_response field.
        val plain = LoRaPacketParser.toRadioPacket(0x1234u, 1L, bytes(1), wantAck = false, packetId = 7u)
        assertNull(LoRaProto.field(LoRaProto.field(LoRaProto.field(plain, 1, 2)!!.payload, 4, 2)!!.payload, 3, 0))
    }
}
