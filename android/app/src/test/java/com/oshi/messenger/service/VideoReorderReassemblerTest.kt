package com.oshi.messenger.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __VIDEO_REORDER_2026_09_23__ The receive reorder window (shared with the desktop).
 *
 * Fragments are built exactly as `VideoCallManager.sendVideoFrame` builds them:
 * `[0x01][frame_id BE 2][idx][total][payload]`, frame byte 0 = flags (bit 0 keyframe).
 */
class VideoReorderReassemblerTest {

    private var now = 1_000L
    private fun r(holdFrames: Int = 3, holdMs: Long = 150) =
        VideoReorderReassembler(holdFrames, holdMs, nowMs = { now })

    private fun frame(key: Boolean, size: Int, fill: Int): ByteArray =
        ByteArray(size) { fill.toByte() }.also { it[0] = if (key) 1 else 0 }

    private fun frags(id: Int, frame: ByteArray, max: Int = 1000): List<ByteArray> {
        val total = (frame.size + max - 1) / max
        return (0 until total).map { idx ->
            val start = idx * max
            val end = minOf(start + max, frame.size)
            byteArrayOf(0x01, (id ushr 8).toByte(), id.toByte(), idx.toByte(), total.toByte()) +
                frame.copyOfRange(start, end)
        }
    }

    private fun p(id: Int, size: Int = 300) = frags(id, frame(false, size, id))

    @Test
    fun `in-order frames are released at once`() {
        val a = r()
        for (id in 0..5) {
            val out = a.offer(p(id).single())
            assertEquals(listOf(id), out.released.map { it.frameId })
            assertFalse(out.requestKeyframe)
        }
        assertEquals(0L, a.lostFrames)
    }

    @Test
    fun `a multi-fragment frame reassembles in any fragment order, byte for byte`() {
        val f = frame(true, 2500, 7)
        val parts = frags(9, f)
        val a = r()
        assertEquals(VideoReorderReassembler.Reason.BUFFERED, a.offer(parts[2]).reason)
        assertEquals(VideoReorderReassembler.Reason.BUFFERED, a.offer(parts[0]).reason)
        val out = a.offer(parts[1])
        assertArrayEquals(f, out.released.single().packet)
        assertTrue(out.released.single().isKeyFrame)
    }

    /** THE bug: one datagram overtaking another across a frame boundary. */
    @Test
    fun `reordering across frames loses nothing and asks for nothing`() {
        val a = r()
        val f10 = p(10, 1500); val f11 = p(11, 1500)
        a.offer(f10[0])
        a.offer(f11[0]); val held = a.offer(f11[1])
        assertTrue("11 waits for 10", held.released.isEmpty())
        val out = a.offer(f10[1])
        assertEquals(listOf(10, 11), out.released.map { it.frameId })
        assertFalse(out.requestKeyframe)
        assertEquals(0L, a.lostFrames)
        assertEquals(1L, a.reorderedFrames)
    }

    @Test
    fun `a whole lost frame is skipped after 3 newer frames and asks for a keyframe once`() {
        val a = r()
        a.offer(p(0).single())
        // 1 never arrives
        for (id in 2..4) assertTrue(a.offer(p(id).single()).released.isEmpty())
        val out = a.offer(p(5).single())
        assertEquals(listOf(2, 3, 4, 5), out.released.map { it.frameId })
        assertEquals(1, out.released.first().lostBefore)
        assertTrue(out.requestKeyframe)
        assertFalse(a.offer(p(6).single()).requestKeyframe)
        assertEquals(1L, a.lostFrames)
    }

    @Test
    fun `the hold is bounded in time too`() {
        val a = r()
        a.offer(p(0).single())
        assertTrue(a.offer(p(2).single()).released.isEmpty())
        now += 149
        assertTrue(a.poll().released.isEmpty())
        now += 1
        val out = a.poll()
        assertEquals(listOf(2), out.released.map { it.frameId })
        assertTrue(out.requestKeyframe)
    }

    @Test
    fun `a keyframe after a hole is released at once and is its own recovery`() {
        val a = r()
        a.offer(p(0).single())
        val out = a.offer(frags(3, frame(true, 400, 3)).single())
        assertEquals(listOf(3), out.released.map { it.frameId })
        assertFalse("an IDR after a hole needs no request", out.requestKeyframe)
        assertEquals(2L, a.lostFrames)
        assertEquals(VideoReorderReassembler.Reason.LATE, a.offer(p(2).single()).reason)
    }

    @Test
    fun `a torn frame (fragment lost) is skipped, not waited for forever`() {
        val a = r()
        a.offer(p(0).single())
        a.offer(p(1, 2500)[0]) // 1 of 3
        for (id in 2..4) a.offer(p(id).single())
        val out = a.offer(p(5).single())
        assertEquals(listOf(2, 3, 4, 5), out.released.map { it.frameId })
        assertTrue(out.requestKeyframe)
        assertEquals(0, a.pending())
    }

    @Test
    fun `duplicates and late fragments are refused`() {
        val a = r()
        val f = p(4).single()
        assertEquals(1, a.offer(f).released.size)
        assertEquals(VideoReorderReassembler.Reason.LATE, a.offer(f).reason)
        assertEquals(1L, a.completedFrames)
    }

    @Test
    fun `frame ids wrap on the circle`() {
        val a = r()
        a.offer(p(65534).single())
        a.offer(p(65535).single())
        assertEquals(listOf(0), a.offer(p(0).single()).released.map { it.frameId })
        assertEquals(VideoReorderReassembler.Reason.LATE, a.offer(p(65533).single()).reason)
    }

    @Test
    fun `a restarted sender resynchronises instead of looking 30000 frames late`() {
        val a = r()
        a.offer(p(100).single())
        val out = a.offer(frags(5000, frame(true, 200, 1)).single())
        assertEquals(listOf(5000), out.released.map { it.frameId })
    }

    @Test
    fun `never more than 32 unfinished frames are held`() {
        val a = r()
        for (id in 0 until 32) a.offer(byteArrayOf(0x01, 0, id.toByte(), 0, 2, 0x55))
        assertEquals(32, a.pending())
        val out = a.offer(byteArrayOf(0x01, 0, 32, 0, 2, 0x55))
        assertTrue(out.requestKeyframe)
        assertEquals(32, a.pending())
    }

    @Test
    fun `two totals for one frame drop it`() {
        val a = r()
        a.offer(byteArrayOf(0x01, 0, 5, 0, 3, 0x11))
        assertEquals(VideoReorderReassembler.Reason.TOTAL_MISMATCH,
            a.offer(byteArrayOf(0x01, 0, 5, 1, 4, 0x22)).reason)
        assertEquals(0, a.pending())
    }

    @Test
    fun `malformed headers are refused`() {
        val a = r()
        assertEquals(VideoReorderReassembler.Reason.MALFORMED, a.offer(byteArrayOf(0x02, 0, 1, 0, 1, 9)).reason)
        assertEquals(VideoReorderReassembler.Reason.MALFORMED, a.offer(byteArrayOf(0x01, 0, 1, 2, 2, 9)).reason)
        assertEquals(VideoReorderReassembler.Reason.MALFORMED, a.offer(byteArrayOf(0x01, 0, 1, 0, 1)).reason)
    }
}
