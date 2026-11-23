package com.oshi.messenger.service.lora

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins OSHI-over-MeshCore to the two things it must match byte for byte: the "OB" envelope of
 * oshi-meshcore-bridge (vectors produced by oshi_bridge/wire.py, the same as
 * OSHITests/MeshCoreRadioTests.swift) and the MeshCore companion protocol
 * (examples/companion_radio/MyMesh.cpp). A drift in either reads as "MeshCore never delivers": the
 * bridge drops a foreign envelope silently and the radio answers ERR to a malformed command.
 */
class MeshCoreProtocolTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    private fun bytes(vararg v: Int) = v.map { it.toByte() }.toByteArray()

    @Test
    fun envelopeMatchesBridge() {
        val frame = ByteArray(200) { (it and 0xFF).toByte() }
        val parts = OshiBridgeWire.split(frame, 0x11223344u, 7)!!
        assertEquals(listOf(160, 58), parts.map { it.size })
        assertEquals("4f4210443322110702000102", hex(parts[0].copyOfRange(0, 12)))
        assertEquals("4f4210443322110712979899", hex(parts[1].copyOfRange(0, 12)))

        val joiner = OshiBridgeWire.Joiner()
        val p1 = OshiBridgeWire.parse(parts[1])!!
        val p0 = OshiBridgeWire.parse(parts[0])!!
        assertEquals(0x11223344u, p0.sender)
        assertNull(joiner.push(p1))
        assertArrayEquals(frame, joiner.push(p0))
    }

    @Test
    fun channelSecretMatchesBridge() {
        assertEquals("485a8d4bcd50fd075ea87d7ee19f4135", hex(OshiBridgeWire.CHANNEL_SECRET))
    }

    @Test
    fun envelopeRejectsForeignDatagrams() {
        assertNull(OshiBridgeWire.parse("hello world".toByteArray()))
        assertNull(OshiBridgeWire.parse(bytes(0x4F, 0x42, 0x10, 0, 0, 0, 0, 0, 0x22)))   // part 2 of 2
        assertNull(OshiBridgeWire.parse(bytes(0x4F, 0x42, 0x20, 0, 0, 0, 0, 0, 0x01)))   // version 2
    }

    @Test
    fun companionCommands() {
        assertArrayEquals(bytes(1, 0, 0, 0, 0, 0, 0, 0), MeshCore.appStart().copyOfRange(0, 8))
        assertArrayEquals(bytes(31, 3), MeshCore.getChannel(3))
        val set = MeshCore.setChannel(7, "#oshi-bridge", OshiBridgeWire.CHANNEL_SECRET)
        assertEquals(2 + 32 + 16, set.size)            // the firmware refuses 2+32+32 (256-bit) as unsupported
        assertArrayEquals(bytes(32, 7), set.copyOfRange(0, 2))
        assertArrayEquals("#oshi-bridge".toByteArray(), set.copyOfRange(2, 14))
        assertArrayEquals(ByteArray(20), set.copyOfRange(14, 34))
        assertArrayEquals(
            bytes(62, 7, 0xFF, 0x4F, 0xFF, 0xAA),
            MeshCore.sendChannelData(7, 0xFF4F, bytes(0xAA)),
        )
    }

    @Test
    fun companionReplies() {
        val info = bytes(18, 7) + "#oshi-bridge".toByteArray() + ByteArray(32 - 12) + OshiBridgeWire.CHANNEL_SECRET
        val ch = MeshCore.parseChannelInfo(info)!!
        assertEquals(7, ch.idx)
        assertEquals("#oshi-bridge", ch.name)
        assertArrayEquals(OshiBridgeWire.CHANNEL_SECRET, ch.secret)

        val d = MeshCore.parseChannelData(bytes(27, 0x18, 0, 0, 7, 3, 0x4F, 0xFF, 3, 0x4F, 0x42, 0x10))!!
        assertEquals(7, d.channelIdx)
        assertEquals(0xFF4F, d.dataType)
        assertArrayEquals(bytes(0x4F, 0x42, 0x10), d.payload)
        assertNull(MeshCore.parseChannelData(bytes(27, 0, 0, 0, 7, 3, 0x4F, 0xFF, 9, 1)))   // truncated

        val key = ByteArray(32) { it.toByte() }
        assertArrayEquals(key, MeshCore.selfPublicKey(bytes(5, 1, 22, 22) + key + ByteArray(20)))
    }

    /**
     * End to end on the wire: a message a MeshCore phone sends comes back out of the envelope and the OMP
     * reassembler as the same body, with the phone's id as origin.
     */
    @Test
    fun ompOverEnvelopeRoundTrip() {
        val body = OshiMeshProtocol.encodeBody(ByteArray(900) { 0x41 })
        val frames = OshiMeshProtocol.dataFrames(99u, CrossBridgeDedup.BROADCAST, body, origin = 0xCAFEBABEu)!!
        val joiner = OshiBridgeWire.Joiner()
        val rx = OmpReassembler()
        var out: ByteArray? = null
        frames.forEachIndexed { i, fr ->
            for (dg in OshiBridgeWire.split(fr, 0xCAFEBABEu, i)!!) {
                val joined = joiner.push(OshiBridgeWire.parse(dg)!!) ?: continue
                val f = OshiMeshProtocol.parseData(joined)!!
                assertEquals(0xCAFEBABEu, f.origin)
                rx.accept(f)?.let { out = it }
            }
        }
        assertArrayEquals(body, out)
    }

    @Test
    fun serialFramingRoundTripAndResync() {
        val framed = MeshCore.Serial.encode(bytes(1, 2, 3))
        assertArrayEquals(bytes(0x3C, 3, 0, 1, 2, 3), framed)
        val fromRadio = bytes(0x00, 0x7F) + MeshCore.Serial.encode(bytes(9, 8), MeshCore.Serial.FROM_RADIO) + bytes(0x3E)
        val d = MeshCore.Serial.decode(fromRadio)!!
        assertArrayEquals(bytes(9, 8), d.payload)
        assertEquals(7, d.consumed)
        assertNull(MeshCore.Serial.decode(bytes(0x3E, 5, 0, 1, 2)))   // incomplete: wait for more
    }

    /** The setup conversation: never overwrite a user's channel, claim the first EMPTY slot > 0. */
    @Test
    fun sessionClaimsFirstEmptySlotAndNeverAUserChannel() {
        val s = MeshCoreSession()
        s.start()
        assertArrayEquals(MeshCore.appStart(), s.nextCommand())
        assertNull(s.nextCommand())                                   // one request in flight at a time
        val pub = bytes(0xDB, 0x33, 0x30, 0xA2) + ByteArray(28)
        s.onFrame(bytes(5, 1, 22, 22) + pub + ByteArray(20))
        assertEquals(0xA23033DBu, s.nodeId)
        fun channel(idx: Int, name: String) =
            bytes(18, idx) + name.toByteArray() + ByteArray(32 - name.length) + ByteArray(16) { 1 }
        val names = listOf("Public", "family", "", "", "work", "", "", "")
        for (i in 0 until 8) {
            assertArrayEquals(MeshCore.getChannel(i), s.nextCommand())
            s.onFrame(channel(i, names[i]))
        }
        assertArrayEquals(MeshCore.setChannel(2, OshiBridgeWire.CHANNEL_NAME, OshiBridgeWire.CHANNEL_SECRET), s.nextCommand())
        assertFalse(s.isReady)
        val events = s.onFrame(bytes(MeshCore.RESP_OK))
        assertTrue(events.single() is MeshCoreSession.Event.Ready)
        assertTrue(s.isReady)
        assertEquals(2, s.channelIdx)
        assertArrayEquals(MeshCore.syncNextMessage, s.nextCommand())
    }

    @Test
    fun sessionReportsNoFreeChannelAndFindsAnExistingOne() {
        val full = MeshCoreSession().apply { start(); nextCommand(); onFrame(bytes(5, 1, 22, 22) + ByteArray(52)) }
        var events: List<MeshCoreSession.Event> = emptyList()
        for (i in 0 until 8) {
            full.nextCommand()
            events = full.onFrame(bytes(18, i) + "ch$i".toByteArray() + ByteArray(29) + ByteArray(16))
        }
        assertTrue(events.single() is MeshCoreSession.Event.NoFreeChannel)
        assertFalse(full.isReady)

        val known = MeshCoreSession().apply { start(); nextCommand(); onFrame(bytes(5, 1, 22, 22) + ByteArray(52)) }
        known.nextCommand()
        known.onFrame(bytes(18, 0) + "Public".toByteArray() + ByteArray(26) + ByteArray(16))
        known.nextCommand()
        val ev = known.onFrame(bytes(18, 1) + OshiBridgeWire.CHANNEL_NAME.toByteArray() + ByteArray(20) + OshiBridgeWire.CHANNEL_SECRET)
        assertTrue(ev.single() is MeshCoreSession.Event.Ready)
        assertEquals(1, known.channelIdx)
    }

    /** A frame another phone put on #oshi-bridge surfaces; our own echo and other channels do not. */
    @Test
    fun sessionReceivesForeignFramesOnly() {
        val s = MeshCoreSession().apply { start(); nextCommand(); onFrame(bytes(5, 1, 22, 22) + bytes(1, 0, 0, 0) + ByteArray(48)) }
        s.nextCommand()
        s.onFrame(bytes(18, 0) + OshiBridgeWire.CHANNEL_NAME.toByteArray() + ByteArray(20) + OshiBridgeWire.CHANNEL_SECRET)
        assertTrue(s.isReady)
        val frame = OshiMeshProtocol.dataFrames(5u, CrossBridgeDedup.BROADCAST, bytes(1, 2, 3), origin = 9u)!![0]
        fun recv(ch: Int, dg: ByteArray) = bytes(27, 0, 0, 0, ch, 0, 0x4F, 0xFF, dg.size) + dg
        val theirs = OshiBridgeWire.split(frame, 9u, 1)!!.single()
        val ours = OshiBridgeWire.split(frame, 1u, 1)!!.single()
        assertTrue(s.onFrame(recv(1, theirs)).isEmpty())            // wrong channel
        assertTrue(s.onFrame(recv(0, ours)).isEmpty())              // our own echo
        val got = s.onFrame(recv(0, theirs)).single() as MeshCoreSession.Event.Frame
        assertEquals(9u, got.parsed.origin)
        assertArrayEquals(frame, got.frame)
        // Outbound: our id as envelope sender, on our slot, as channel data 0xFF4F.
        val out = s.channelDatagrams(frame).single()
        assertArrayEquals(bytes(62, 0, 0xFF, 0x4F, 0xFF, 0x4F, 0x42, 0x10, 1, 0, 0, 0), out.copyOfRange(0, 12))
    }

    @Test
    fun crossBridgeRepeatsEachBroadcastFrameOnce() {
        val d = CrossBridgeDedup()
        val f = OshiMeshProtocol.parseData(OshiMeshProtocol.dataFrames(77u, CrossBridgeDedup.BROADCAST, ByteArray(10), origin = 1u)!![0])!!
        assertTrue(d.firstTime(f, nowMs = 0))
        assertFalse(d.firstTime(f, nowMs = 1_000))
        // The same frame re-stamped by an OSHI Mesh radio (other origin, same msgId) is still a repeat.
        assertFalse(d.firstTime(f.copy(origin = 2u), nowMs = 2_000))
        assertTrue(d.firstTime(f, nowMs = 31 * 60_000L))           // TTL over
        val unicast = OshiMeshProtocol.parseData(OshiMeshProtocol.dataFrames(78u, 5u, ByteArray(10))!![0])!!
        assertFalse(d.firstTime(unicast))
    }
}
