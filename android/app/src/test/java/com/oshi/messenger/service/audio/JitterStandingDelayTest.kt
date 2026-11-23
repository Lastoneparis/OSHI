package com.oshi.messenger.service.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __STANDING_DELAY_2026_11_11__ A burst must not leave a permanent delay.
 * Before the fix a 500 ms stall at call start held the queue at 480 ms for the
 * whole call (complaint 2d3fe47d, "delay").
 */
class JitterStandingDelayTest {

    private fun frame(amplitude: Int): ByteArray {
        val b = ByteArray(1920)
        for (i in 0 until 960) {
            val v = if (amplitude == 0) 0 else (amplitude * kotlin.math.sin(i * 0.2)).toInt()
            b[2 * i] = (v and 0xFF).toByte(); b[2 * i + 1] = (v shr 8).toByte()
        }
        return b
    }

    /** Returns the queue depth (ms) at the end, plus frames played and shed. */
    private fun run(stallMs: Long, frameOf: (Long) -> ByteArray, durationMs: Long = 20_000): Triple<Int, Long, Long> {
        var now = 0L
        val buf = AudioJitterBuffer(targetDepthMs = 100, minDepthMs = 60, maxDepthMs = 200)
        buf.clock = { now }
        var seq = 0L
        var nextPlay = -1L
        var played = 0L
        while (now <= durationMs) {
            if (now == stallMs) repeat((stallMs / 20).toInt()) { buf.addPacket(seq, frameOf(seq)); seq++ }
            if (now > stallMs && now % 20 == 0L) { buf.addPacket(seq, frameOf(seq)); seq++ }
            if (now >= stallMs && nextPlay < 0) nextPlay = now
            if (nextPlay in 0..now) { if (buf.getNextPacket() != null) played++; nextPlay += 20 }
            now++
        }
        return Triple(buf.bufferDepthMs, played, buf.totalShedFrames)
    }

    @Test fun burstDrainsThroughPauses() {
        // Speech with pauses: 600 ms voiced, 400 ms silent.
        val (depth, _, shed) = run(500, { s -> if ((s * 20) % 1000 < 600) frame(8000) else frame(0) })
        assertTrue("queue still $depth ms", depth <= 140)
        assertTrue(shed in 15..30)
    }

    @Test fun continuousSpeechStillDrainsAfterForceDelay() {
        val (depth, _, _) = run(500, { frame(8000) })
        assertTrue("queue still $depth ms", depth <= 140)
    }

    @Test fun cleanStreamIsNeverShed() {
        val (_, _, shed) = run(20, { frame(8000) })
        assertEquals(0L, shed)
    }
}
