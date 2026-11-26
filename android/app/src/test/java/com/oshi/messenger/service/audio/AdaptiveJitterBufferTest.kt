package com.oshi.messenger.service.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __CALL_FLUIDITY_2026_11_21__ [AdaptiveJitterBuffer] rules, one per test, in virtual time.
 * Frames are PCM ([AdaptiveJitterBuffer.PcmFrames]) whose sample [TAG] carries the seq, so
 * what reached the speaker can be read back exactly.
 */
class AdaptiveJitterBufferTest {
    private companion object { const val TAG = 480 }

    private var now = 0L
    private fun jb() = AdaptiveJitterBuffer(clock = { now })

    /** A 20 ms PCM frame, loud (voiced) or quiet, tagged with [seq] in sample [TAG] (past any cross-fade). */
    private fun frame(seq: Long, amp: Int = 6000): ByteArray {
        val b = ByteArray(1920)
        for (i in 0 until 960) {
            val v = if (i == TAG) seq.toInt() else (amp * Math.sin(2 * Math.PI * 150 * i / 48_000.0)).toInt()
            b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte()
        }
        return b
    }

    private fun AdaptiveJitterBuffer.feed(seq: Long, amp: Int = 6000) { put(seq, frame(seq, amp)) }

    /** Pull one frame 20 ms later; returns (kind, tag) where tag is sample 0 for a real frame. */
    private fun AdaptiveJitterBuffer.tick(): Pair<AdaptiveJitterBuffer.Kind, Int> {
        now += 20
        val p = pull()
        return kind() to p[TAG].toInt()
    }

    @Test fun `every pull is exactly 20 ms`() {
        val b = jb()
        repeat(5) { assertEquals(960, b.pull().size) }
        b.feed(1)
        repeat(5) { assertEquals(960, b.pull().size) }
    }

    @Test fun `in order stream plays every frame once, in order`() {
        val b = jb()
        b.feed(100)
        val got = ArrayList<Int>()
        for (s in 101L..150L) { b.feed(s); val (k, t) = b.tick(); if (k == AdaptiveJitterBuffer.Kind.NORMAL) got += t }
        assertEquals((100..149).toList(), got)
        assertEquals(0L, b.framesExpanded + b.framesLossConcealed)
    }

    @Test fun `a LATE frame is waited for and still played (expand, not discard)`() {
        val b = jb()
        b.feed(1); b.feed(2)
        b.tick(); b.tick()                                // 1, 2 played
        val (k1, _) = b.tick()                            // 3 not here, nothing queued
        assertEquals(AdaptiveJitterBuffer.Kind.EXPAND, k1)
        b.feed(3); b.feed(4)                              // 3 arrives 20+ ms late
        assertEquals(AdaptiveJitterBuffer.Kind.NORMAL to 3, b.tick())
        assertEquals(AdaptiveJitterBuffer.Kind.NORMAL to 4, b.tick())
        assertEquals("nothing was discarded", 0L, b.framesLate)
    }

    @Test fun `a frame missing with later ones queued is concealed and the slot advances`() {
        val b = jb()
        b.feed(1); b.tick()
        b.feed(3); b.feed(4)                              // 2 lost
        assertEquals(AdaptiveJitterBuffer.Kind.LOSS_CONCEAL, b.tick().first)
        assertEquals(AdaptiveJitterBuffer.Kind.NORMAL to 3, b.tick())
        assertEquals(1L, b.framesLossConcealed)
    }

    @Test fun `after an outage the concealment played stands in for the lost frames - no added delay`() {
        val b = jb()
        for (s in 1L..5L) { b.feed(s); b.tick() }
        repeat(150) { b.tick() }                          // 3 s outage: 150 expands
        for (s in 156L..160L) b.feed(s)                   // stream resumes where real time is
        assertEquals(AdaptiveJitterBuffer.Kind.NORMAL to 156, b.tick())
        assertEquals("no jump, no backlog", 0L, b.jumps)
        assertTrue("delay ${b.delayMs()} ms", b.delayMs() <= 100)
    }

    @Test fun `a burst after a stall is cut back to the target in one step`() {
        val b = jb()
        for (s in 1L..20L) { b.feed(s); b.tick() }
        for (s in 21L..100L) b.feed(s)                    // 1.6 s delivered at once
        b.tick()
        assertEquals(1L, b.flushes)
        assertTrue("delay after flush ${b.delayMs()} ms", b.delayMs() <= b.targetMs() + 40)
    }

    @Test fun `a standing excess is drained, pauses first`() {
        val b = jb()
        // 240 ms delivered at once, all quiet, then a steady stream. The burst itself reads
        // as delay variation, so the target first rises to cover it; once it has left the
        // estimate window the standing queue is shed — whole quiet frames, nothing voiced.
        for (s in 1L..12L) b.feed(s, amp = 50)
        var seq = 13L
        repeat(300) { b.feed(seq++, amp = 50); b.tick() }
        assertTrue("skipped ${b.framesSkipped}", b.framesSkipped >= 5)
        assertEquals(0L, b.samplesAccelerated)
        assertTrue("delay ${b.delayMs()}", b.delayMs() <= b.targetMs() + AdaptiveJitterBuffer.ACCEL_ABOVE_MS + 20)
    }

    @Test fun `voiced excess is shortened by pitch periods once forced`() {
        val b = jb()
        for (s in 1L..12L) b.feed(s)
        var seq = 13L
        repeat(150) { b.feed(seq++); b.tick() }
        assertTrue("accelerated ${b.samplesAccelerated} samples", b.samplesAccelerated > 960 * 3)
        assertEquals("voiced frames are not dropped whole", 0L, b.framesSkipped)
    }

    @Test fun `target follows measured jitter and is capped`() {
        val b = jb()
        val rnd = java.util.Random(1)
        var seq = 0L
        // Arrival = send + 0..120 ms jitter.
        val arrivals = (0 until 300).map { it to it * 20L + rnd.nextInt(120) }.sortedBy { it.second }
        for ((s, at) in arrivals) { now = at; b.put(s.toLong(), frame(s.toLong())); b.pull(); seq = s.toLong() }
        assertTrue("target ${b.targetMs()}", b.targetMs() in 100..AdaptiveJitterBuffer.MAX_TARGET_MS)
        // Then a clean network for longer than the window: the target comes back down.
        for (i in 1..400) { now += 20; seq++; b.feed(seq); b.pull() }
        assertTrue("target ${b.targetMs()} after calm", b.targetMs() <= 40)
    }

    @Test fun `a copy of a frame that already arrived does not raise the target (redundancy covered it)`() {
        val b = jb()
        for (s in 0L until 60L) { now = s * 20; b.feed(s); b.pull() }
        val before = b.targetMs()
        // Frame 40's own packet comes ~400 ms late — its audio came long ago in 41's redundancy.
        b.put(40, frame(40))
        repeat(10) { now += 20; b.feed(60 + it.toLong()); b.pull() }
        assertEquals(before, b.targetMs())
    }

    @Test fun `a frame that only arrives after its slot raises the target`() {
        val b = jb()
        for (s in 0L until 60L) { now = s * 20; b.feed(s); b.pull() }
        val before = b.targetMs()
        // 60..64 arrive 200 ms late, nothing carried them earlier.
        now += 200
        for (s in 60L..64L) b.feed(s)
        repeat(10) { now += 20; b.feed(65 + it.toLong()); b.pull() }
        assertTrue("target ${b.targetMs()} > $before", b.targetMs() >= before + 100)
    }

    @Test fun `duplicates and frames behind the playout point are ignored`() {
        val b = jb()
        b.feed(1); b.feed(1); b.tick()
        assertEquals(1L, b.framesDuplicate)
        b.feed(1)
        assertEquals(1L, b.framesLate)
        assertEquals(0L, b.resyncs)
    }

    @Test fun `a seq far behind the playout point is a new stream - resync`() {
        val b = jb()
        for (s in 1000L..1010L) { b.feed(s); b.tick() }
        b.feed(5); b.feed(6)
        assertEquals(1L, b.resyncs)
        assertEquals("the new stream plays from its start", AdaptiveJitterBuffer.Kind.NORMAL to 5, b.tick())
    }

    @Test fun `a long hole with audio queued behind it is jumped, not concealed for seconds`() {
        val b = jb()
        b.feed(1); b.tick()
        for (s in 40L..45L) b.feed(s)                     // 38 frames never came, no expansion yet
        assertEquals(AdaptiveJitterBuffer.Kind.NORMAL to 40, b.tick())
        assertEquals(1L, b.jumps)
    }

    @Test fun `codec concealment is asked with the next frame when it is queued (FEC hook)`() {
        val seen = ArrayList<ByteArray?>()
        val dec = object : AdaptiveJitterBuffer.FrameDecoder {
            override fun decode(frame: ByteArray) = AdaptiveJitterBuffer.PcmFrames.decode(frame)
            override fun conceal(next: ByteArray?): ShortArray { seen += next; return ShortArray(960) }
        }
        val b = AdaptiveJitterBuffer(dec, clock = { now })
        b.feed(1); b.tick()
        b.feed(3)
        b.tick()                                          // 2 lost, 3 queued
        assertEquals(1, seen.size)
        assertEquals(3, AdaptiveJitterBuffer.PcmFrames.decode(seen[0]!!)[TAG].toInt())
        b.tick(); b.tick()                                // 3 played, then nothing queued → plain PLC
        assertEquals(null, seen.last())
    }
}
