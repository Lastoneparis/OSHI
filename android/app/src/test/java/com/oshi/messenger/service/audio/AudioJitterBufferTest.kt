package com.oshi.messenger.service.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Simulation tests for the adaptive jitter buffer.
 *
 * Three network profiles are simulated with a deterministic sim clock:
 *   1. Clean   — packets arrive every 20 ms exactly.
 *   2. Jittery — inter-arrival uniform [10,30] ms, 10% loss.
 *   3. Lossy   — inter-arrival uniform [10,50] ms, 25% loss.
 *
 * Producer: emits packets at the simulated delay intervals (with loss).
 * Consumer: ticks every 20 ms of sim time (mirrors the real audio hardware
 * clock, which is steady regardless of network delay). When producer falls
 * behind, packets arrive after their slot was already played — "late" per
 * RFC 3550 — and the buffer is expected to widen.
 */
class AudioJitterBufferTest {

    private class SimClock { var nowMs: Long = 0 }

    /**
     * Run a simulation and return the final buffer state.
     *
     * Both producer and consumer share one simulated clock. The loop walks 1 ms
     * at a time so arrival and consumer-tick timing are exact.
     */
    private fun simulate(
        interArrivalMin: Int,
        interArrivalMax: Int,
        lossRate: Double,
        durationMs: Int,
        seed: Long
    ): AudioJitterBuffer {
        val clock = SimClock()
        val buf = AudioJitterBuffer(targetDepthMs = 60, minDepthMs = 60, maxDepthMs = 400)
        buf.clock = { clock.nowMs }
        val rng = Random(seed)
        val payload = ByteArray(640) // 20 ms @ 16 kHz 16-bit

        var consumerTick = -1L
        var nextArrivalTime = 0L
        var seq = 0

        while (clock.nowMs <= durationMs) {
            if (clock.nowMs >= nextArrivalTime) {
                val dropped = rng.nextDouble() < lossRate
                if (!dropped) buf.addPacket(seq.toLong(), payload)
                if (consumerTick < 0) consumerTick = clock.nowMs + 20
                seq++
                nextArrivalTime = clock.nowMs + rng.nextInt(interArrivalMin, interArrivalMax + 1)
            }
            if (consumerTick >= 0 && clock.nowMs >= consumerTick) {
                buf.getNextPacket()
                consumerTick += 20
            }
            clock.nowMs++
        }
        return buf
    }

    @Test
    fun clean_traceHolds60ms() {
        val buf = simulate(
            interArrivalMin = 20,
            interArrivalMax = 20,
            lossRate = 0.0,
            durationMs = 5000,
            seed = 1L
        )
        val stats = buf.getStats()
        assertEquals(
            "clean stream should keep target depth at 60 ms (got ${stats.targetDepthMs})",
            60, stats.targetDepthMs
        )
    }

    @Test
    fun jittery10PercentLoss_expandsTo150To250() {
        // Uniform inter-arrival [10, 30] ms, 10% drop-rate, 2 s of sim time.
        val buf = simulate(
            interArrivalMin = 10,
            interArrivalMax = 30,
            lossRate = 0.10,
            durationMs = 2000,
            seed = 1234L
        )
        // __OPUS_LOOKAHEAD_2026_09_23__ This asserted 150-250 ms. That depth came from a
        // death spiral: this producer is a random walk, it fell behind the slot clock, and
        // every later packet was "late" — the old buffer played 54 of 97 frames here and
        // 54 of 995 over 20 s (930 silent ticks). The late-streak resync re-anchors on the
        // stream instead, so the late run is short and the estimate grows less; what
        // matters is what reaches the speaker.
        val stats = buf.getStats()
        assertTrue(
            "jittery target depth should stay within 60-250 ms (got ${stats.targetDepthMs}, J=${"%.1f".format(buf.jitterEstimateMs)})",
            stats.targetDepthMs in 60..250
        )
        assertTrue("real frames played: ${stats.packetsPlayed} (old buffer: 54)", stats.packetsPlayed >= 70)
    }

    @Test
    fun lossy25Percent_expandsTo300To400() {
        // Uniform inter-arrival [10, 50] ms, 25% drop-rate, 2 s of sim time.
        val buf = simulate(
            interArrivalMin = 10,
            interArrivalMax = 50,
            lossRate = 0.25,
            durationMs = 2000,
            seed = 5678L
        )
        val depth = buf.getStats().targetDepthMs
        assertTrue(
            "lossy target depth should reach 300-400 ms within 2 s (got $depth, J=${"%.1f".format(buf.jitterEstimateMs)})",
            depth in 300..400
        )
    }

    // =====================================================================
    // Cross-version: the iPhone App Store build sends ~32 ms frames at ~30 pkt/s
    // =====================================================================

    /**
     * Producer every [frameMs] ms with [frameMs]-long 48 kHz frames; consumer paced like
     * the playback loop: the period is the duration of what it just wrote, min 20 ms,
     * and a null is written as 20 ms of silence. Returns concealed + silence counts
     * after the 3 s grace window.
     */
    private fun cadence(frameMs: Int, durationMs: Int): Pair<Long, Int> {
        val clock = SimClock()
        val buf = AudioJitterBuffer()
        buf.clock = { clock.nowMs }
        val payload = ByteArray(frameMs * 96)
        var seq = 0L
        var nextArrival = 0L
        var nextTick = -1L
        var silences = 0
        var concealedBefore = 0L
        while (clock.nowMs <= durationMs) {
            if (clock.nowMs >= nextArrival) {
                buf.addPacket(seq++, payload)
                nextArrival += frameMs
                if (nextTick < 0) nextTick = clock.nowMs + frameMs
            }
            if (nextTick >= 0 && clock.nowMs >= nextTick) {
                if (clock.nowMs == 3_000L || (concealedBefore == 0L && clock.nowMs > 3_000L)) {
                    concealedBefore = buf.totalPacketsConcealed
                }
                val out = buf.getNextPacket()
                if (out == null && clock.nowMs > 3_000L) silences++
                nextTick += maxOf(20, (out?.size ?: 1920) / 96)
            }
            clock.nowMs++
        }
        return (buf.totalPacketsConcealed - concealedBefore) to silences
    }

    @Test
    fun twenty_ms_frames_play_without_invented_audio() {
        val (concealed, silences) = cadence(frameMs = 20, durationMs = 10_000)
        assertEquals(0L, concealed)
        assertEquals(0, silences)
    }

    @Test
    fun thirty_two_ms_frames_play_without_invented_audio() {
        // Before: a 20 ms slot clock declared a hole between every two 32 ms frames and
        // filled it with PLC — ~20 invented frames per second against an App Store iPhone.
        val (concealed, silences) = cadence(frameMs = 32, durationMs = 10_000)
        assertEquals(0L, concealed)
        assertEquals(0, silences)
    }

    @Test
    fun a_20ms_slot_clock_no_longer_invents_plc_between_32ms_frames() {
        // The buffer half on its own: even polled every 20 ms, it must not declare a
        // hole until a whole 32 ms frame's worth of time has passed.
        val clock = SimClock()
        val buf = AudioJitterBuffer()
        buf.clock = { clock.nowMs }
        val payload = ByteArray(32 * 96)
        var seq = 0L
        var nextArrival = 0L
        var concealedAtGraceEnd = -1L
        var playedAtGraceEnd = -1L
        while (clock.nowMs <= 10_000) {
            if (clock.nowMs >= nextArrival) { buf.addPacket(seq++, payload); nextArrival += 32 }
            if (clock.nowMs >= 20 && clock.nowMs % 20 == 0L) buf.getNextPacket()
            if (clock.nowMs == 3_001L) {
                concealedAtGraceEnd = buf.totalPacketsConcealed
                playedAtGraceEnd = buf.getStats().packetsPlayed
            }
            clock.nowMs++
        }
        assertEquals(0L, buf.totalPacketsConcealed - concealedAtGraceEnd)
        // …and every real frame is still played. With the 20 ms rule the slot outran a
        // 31 pkt/s stream, every arrival became "late", and after 5 PLC frames the
        // buffer played nothing at all.
        val played = buf.getStats().packetsPlayed - playedAtGraceEnd
        assertTrue("played only $played of ~218 frames", played >= 210)
    }

    @Test
    fun frame_duration_is_derived_from_48k_pcm_and_never_below_the_slot() {
        assertEquals(20, AudioJitterBuffer.frameMsFor(1920))
        assertEquals(32, AudioJitterBuffer.frameMsFor(3072))
        assertEquals(20, AudioJitterBuffer.frameMsFor(640))   // tests' 16 kHz payloads
        assertEquals(20, AudioJitterBuffer.frameMsFor(0))
        assertEquals(120, AudioJitterBuffer.frameMsFor(1 shl 20))
    }

    // =====================================================================
    // __DROP_CLICK_FADE_2026_09_22__
    // =====================================================================

    private fun constantFrame(value: Int, samples: Int = 960): ByteArray {
        val b = ByteArray(samples * 2)
        for (i in 0 until samples) { b[2 * i] = (value and 0xFF).toByte(); b[2 * i + 1] = ((value shr 8) and 0xFF).toByte() }
        return b
    }

    private fun sampleAt(pcm: ByteArray, i: Int): Int =
        ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toInt()

    @Test
    fun fade_in_ramps_the_first_2ms_and_never_touches_the_source() {
        val src = constantFrame(10_000)
        val out = AudioJitterBuffer.fadeInCopy(src)
        assertEquals(0, sampleAt(out, 0))
        assertTrue(sampleAt(out, 48) in 4_900..5_100)
        assertEquals(10_000, sampleAt(out, 96))
        assertEquals(10_000, sampleAt(out, 959))
        assertEquals(10_000, sampleAt(src, 0)) // the PLC source stays intact
    }

    @Test
    fun the_frame_after_an_overflow_drop_is_faded_in_and_continuous_frames_are_not() {
        val clock = SimClock()
        val buf = AudioJitterBuffer()
        buf.clock = { clock.nowMs }
        // 51 packets at once: the buffer holds 50 and drops the oldest.
        for (s in 0L..50L) buf.addPacket(s, constantFrame(10_000))
        assertEquals(1L, buf.totalOverflowDrops)
        clock.nowMs = 20
        // Slot 0 was dropped → a hole. Nothing was played yet, so there is nothing to
        // conceal from: the loop writes silence.
        assertEquals(null, buf.getNextPacket())
        clock.nowMs = 40
        val next = buf.getNextPacket()!!    // first real frame after the gap
        assertEquals(0, sampleAt(next, 0))
        assertEquals(10_000, sampleAt(next, 200))
        clock.nowMs = 60
        val cont = buf.getNextPacket()!!    // continuous: untouched
        assertEquals(10_000, sampleAt(cont, 0))
        assertEquals(1L, buf.totalFadeIns)
    }

    @Test
    fun a_clean_stream_is_never_faded() {
        val clock = SimClock()
        val buf = AudioJitterBuffer()
        buf.clock = { clock.nowMs }
        for (s in 0L..5L) buf.addPacket(s, constantFrame(10_000))
        repeat(5) {
            clock.nowMs += 20
            val out = buf.getNextPacket()!!
            if (it > 0) assertEquals(10_000, sampleAt(out, 0))
        }
    }

    // =====================================================================
    // __PITCH_PLC_2026_09_23__
    // =====================================================================

    /** 20 ms of a 150 Hz tone (period 320 samples @ 48 kHz), phase-continuous across frames. */
    private fun toneFrame(index: Int): ByteArray {
        val b = ByteArray(1920)
        for (i in 0 until 960) {
            val v = Math.round(10_000 * Math.sin(2 * Math.PI * 150 * (index * 960 + i) / 48_000.0)).toInt()
            b[2 * i] = (v and 0xFF).toByte(); b[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return b
    }

    @Test
    fun a_lost_voiced_frame_is_concealed_without_a_step_and_rejoined_without_a_dip() {
        val clock = SimClock()
        val buf = AudioJitterBuffer()
        buf.clock = { clock.nowMs }
        clock.nowMs = 4_000   // past the grace period
        buf.addPacket(0, toneFrame(0))
        buf.addPacket(1, toneFrame(1))
        val first = buf.getNextPacket()!!
        clock.nowMs += 20
        val second = buf.getNextPacket()!!
        // Frame 2 is lost; frame 3 arrives.
        buf.addPacket(3, toneFrame(3))
        clock.nowMs += 20
        val concealed = buf.getNextPacket()!!
        assertEquals(1L, buf.totalPacketsConcealed)
        // Continuity: the concealment picks up where frame 1 stopped. The old PLC restarted
        // frame 1 from its start at 0.6× — a step of thousands.
        val step = Math.abs(sampleAt(concealed, 0) - sampleAt(second, 959))
        assertTrue("step into concealment $step", step < 400)
        // It is the tone, not a fade to silence: full level at its start.
        val expected2 = toneFrame(2)
        for (i in 0 until 100) assertTrue(Math.abs(sampleAt(concealed, i) - sampleAt(expected2, i)) < 400)
        clock.nowMs += 20
        val back = buf.getNextPacket()!!
        // Rejoin by cross-fade, not by a 2 ms fade-in from zero (a dip right after the hole).
        val expected3 = toneFrame(3)
        for (i in 0 until 96) assertTrue("dip at $i", Math.abs(sampleAt(back, i) - sampleAt(expected3, i)) < 2_500)
        assertEquals(sampleAt(expected3, 500), sampleAt(back, 500))
        assertTrue(first.isNotEmpty())
    }

    @Test
    fun unvoiced_concealment_keeps_the_old_fade_and_long_losses_end_in_silence() {
        val clock = SimClock()
        val buf = AudioJitterBuffer()
        buf.clock = { clock.nowMs }
        clock.nowMs = 4_000
        val noise = ByteArray(1920).also { Random(7).nextBytes(it) }
        buf.addPacket(0, noise)
        buf.getNextPacket()!!
        var nulls = 0
        repeat(7) {
            clock.nowMs += 20
            val p = buf.getNextPacket()
            if (p == null) nulls++ else assertEquals(0, sampleAt(p, 959) / 200)   // fades to ~0
        }
        assertEquals(2, nulls)   // MAX_CONSECUTIVE_LOSS = 5 concealments, then silence
    }

    // __OPUS_LOOKAHEAD_2026_09_23__ Arrivals every 20 ms, consumer ticks 1 ms after each
    // arrival (the lockstep that made every redundant 0x19 copy late). Packet [lost] is
    // never delivered; packet lost+1 brings a copy of it filed under its own seq, as
    // EnhancedCallManager does via OpusCallDecoder.keyed.
    private fun opusLossRun(lookAhead: Int, lost: Int): Pair<Int, Int> {
        val clock = SimClock()
        val buf = AudioJitterBuffer(targetDepthMs = 100, minDepthMs = 60, maxDepthMs = 200)
        buf.clock = { clock.nowMs }
        buf.lookAheadFrames = lookAhead
        val frame = ByteArray(1920) { 3 }
        var real = 0; var concealed = 0
        for (f in 0 until 60) {
            clock.nowMs = 10_000L + 20L * f
            if (f != lost) {
                if (f == lost + 1) buf.addPacket(lost.toLong(), frame)   // the redundant copy
                buf.addPacket(f.toLong(), frame)
            }
            clock.nowMs += 1
            val p = buf.getNextPacket() ?: continue
            if (buf.plcFadeGain() >= 1f) real++ else concealed++
        }
        return real to concealed
    }

    @Test
    fun lookahead_lets_the_next_packets_copy_of_a_lost_frame_play() {
        val (realLockstep, concLockstep) = opusLossRun(lookAhead = 0, lost = 30)
        assertEquals("lockstep: slot 30 is concealed before its copy lands", 1, concLockstep)
        val (real, conc) = opusLossRun(lookAhead = 1, lost = 30)
        assertEquals("one frame of look-ahead: the copy plays, nothing is concealed", 0, conc)
        // One frame of added delay (the priming tick), no more.
        assertEquals(realLockstep, real)
    }

    @Test
    fun a_delay_step_larger_than_the_margin_resyncs_instead_of_silencing_the_call() {
        val clock = SimClock()
        val buf = AudioJitterBuffer(targetDepthMs = 100, minDepthMs = 60, maxDepthMs = 200)
        buf.clock = { clock.nowMs }
        buf.lookAheadFrames = 1
        val frame = ByteArray(1920) { 5 }
        var realAfterStep = 0
        // Arrivals every 20 ms, +50 ms from packet 100 on (Wi-Fi → LTE); consumer every 20 ms.
        var nextArrival = 0
        for (t in 0 until 20 * 400) {
            clock.nowMs = 50_000L + t
            while (nextArrival < 400 && 20 * nextArrival + (if (nextArrival >= 100) 50 else 0) == t) {
                buf.addPacket(nextArrival.toLong(), frame); nextArrival++
            }
            if (t % 20 == 5) {
                val p = buf.getNextPacket()
                if (p != null && buf.plcFadeGain() >= 1f && t > 20 * 110) realAfterStep++
            }
        }
        assertEquals(1L, buf.totalResyncs)
        // Before the fix: 0 — every packet after the step was "late" for the rest of the call.
        assertTrue("real frames after the step: $realAfterStep", realAfterStep >= 280)
    }

    @Test
    fun a_bunched_burst_does_not_resync() {
        val clock = SimClock()
        val buf = AudioJitterBuffer()
        buf.clock = { clock.nowMs }
        val frame = ByteArray(1920) { 5 }
        for (t in 0 until 20 * 200) {
            clock.nowMs = 70_000L + t
            // Packets 100..104 are held 100 ms and delivered together.
            for (i in 0 until 200) {
                val at = if (i in 100..104) 20 * 105 else 20 * i
                if (at == t) buf.addPacket(i.toLong(), frame)
            }
            if (t % 20 == 5) buf.getNextPacket()
        }
        assertEquals(0L, buf.totalResyncs)
    }
}
