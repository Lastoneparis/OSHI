package com.oshi.messenger.service.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 0x19 receive chain driven exactly as the call manager drives it, over a whole
 * call rather than a handful of packets.
 *
 * WHY: a 1/5 "theyCouldNotHearMe" on call 97d98d39 (29/10, Android↔Android, 85 s)
 * carried server counters that exonerate the transport — 4257 packets / 905 KB one
 * way, 4311 / 772 KB the other, udp 100 %, buffered 0, ratio 0.99 — so the audio was
 * captured, encoded, relayed and decryptable, and still nothing was heard. That
 * leaves the receive chain, which until now was only tested a dozen packets at a
 * time. These drive `decode()` → `keyed(outerSeq, lastKey)` → file, the real loop of
 * EnhancedCallManager.kt:4152-4170, and assert that what was spoken comes out.
 */
class OpusStreamContinuityTest {

    private fun speech(frames: Int): ShortArray =
        ShortArray(frames * OpusWire.FRAME_SAMPLES) { i ->
            val t = i / 48000.0
            val env = 0.5 + 0.5 * Math.sin(2 * Math.PI * 3.0 * t)       // syllable rate
            (6000 * env * (Math.sin(2 * Math.PI * 220 * t) + 0.5 * Math.sin(2 * Math.PI * 740 * t))).toInt()
                .coerceIn(-32768, 32767).toShort()
        }

    /** The receiver loop: returns every frame it files, in key order. */
    private fun receive(packets: List<Pair<Long, ByteArray>>): LinkedHashMap<Long, ShortArray> {
        val dec = OpusCallDecoder()
        var lastKey = -1L
        val filed = LinkedHashMap<Long, ShortArray>()
        for ((outerSeq, payload) in packets) {
            val frames = dec.decode(payload)
            val keyed = OpusCallDecoder.keyed(frames, outerSeq, lastKey)
            if (keyed.isNotEmpty()) lastKey = keyed.last().first
            for ((k, pcm) in keyed) filed[k] = pcm
        }
        return filed
    }

    private fun energy(pcm: ShortArray): Double {
        var s = 0.0
        for (v in pcm) s += v.toDouble() * v
        return Math.sqrt(s / maxOf(1, pcm.size))
    }

    @Test fun `a full minute of speech arrives frame for frame`() {
        val seconds = 60
        val payloads = OpusCallEncoder().encode(speech(seconds * 50))
        assertEquals(seconds * 50, payloads.size)
        // The outer media seq is shared with video and control, so it is not
        // contiguous: start it high and let it advance by one per media packet.
        val packets = payloads.mapIndexed { i, p -> (10_000L + i) to p }
        val filed = receive(packets)
        assertEquals("every 20 ms frame must be filed exactly once", payloads.size, filed.size)
        val quiet = filed.values.count { energy(it) < 50.0 }
        assertTrue("filed $quiet silent frames of ${filed.size} — audio was lost in the chain", quiet < filed.size / 10)
    }

    @Test fun `the stream survives the duplicates the relay reports`() {
        // Call 97d98d39 was relayed with dup 2 %.
        val payloads = OpusCallEncoder().encode(speech(30 * 50))
        val packets = ArrayList<Pair<Long, ByteArray>>()
        payloads.forEachIndexed { i, p ->
            packets.add((10_000L + i) to p)
            if (i % 50 == 49) packets.add((10_000L + i) to p)        // 2 % duplicates
        }
        val filed = receive(packets)
        assertEquals("duplicates must not cost a frame", payloads.size, filed.size)
    }

    @Test fun `one packet reordered ahead does not wedge the rest of the call`() {
        // UDP reorders, and the relay mirrors a frame to the WS while an endpoint is
        // unproven (45 such frames on this call), so a later packet can overtake.
        val payloads = OpusCallEncoder().encode(speech(30 * 50))
        val packets = ArrayList<Pair<Long, ByteArray>>()
        payloads.forEachIndexed { i, p -> packets.add((10_000L + i) to p) }
        val jumped = packets.removeAt(900)
        packets.add(100, jumped)                                     // 800 packets early
        val filed = receive(packets)
        val heard = filed.values.count { energy(it) >= 50.0 }
        assertTrue("only $heard frames of ${payloads.size} survived one reordered packet", heard > payloads.size * 0.8)
    }

    @Test fun `a genuinely late packet is still discarded, a resynchronising one is not`() {
        val payloads = OpusCallEncoder().encode(speech(300))
        val dec = OpusCallDecoder()
        payloads.take(200).forEach { dec.decode(it) }
        assertEquals(0, dec.resyncs)
        // A few slots late: already played, still dropped — the reorder tolerance stands.
        assertTrue("a packet 3 slots late must not be replayed", dec.decode(payloads[196]).isEmpty())
        assertEquals(0, dec.resyncs)
        // Far behind the mark: the live stream, picked up again rather than discarded.
        assertTrue("a packet far behind the mark must resynchronise", dec.decode(payloads[20]).isNotEmpty())
        assertEquals(1, dec.resyncs)
    }

    @Test fun `keyed resynchronises when the mark was set by an overtaking packet`() {
        val f = { o: Int -> OpusCallDecoder.Frame(o, ShortArray(OpusWire.FRAME_SAMPLES) { o.toShort() }) }
        val one = listOf(f(0))
        // Duplicate and a near-late key stay dropped.
        assertTrue(OpusCallDecoder.keyed(one, 100, 100).isEmpty())
        assertTrue(OpusCallDecoder.keyed(one, 100, 150).isEmpty())
        // The boundary is pinned on both sides, because iOS `OshiCodec.swift` mirrors it and a
        // one-off difference there would silence one platform and not the other.
        assertTrue("exactly RESYNC_FRAMES behind is still late",
            OpusCallDecoder.keyed(one, 100, 100L + OpusWire.RESYNC_FRAMES).isEmpty())
        assertEquals("one past it is the live stream", listOf(100L),
            OpusCallDecoder.keyed(one, 100, 101L + OpusWire.RESYNC_FRAMES).map { it.first })
        // A key far behind the mark is the stream, not a straggler.
        assertEquals(listOf(100L), OpusCallDecoder.keyed(one, 100, 5000).map { it.first })
    }
}
