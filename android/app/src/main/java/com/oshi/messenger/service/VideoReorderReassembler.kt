package com.oshi.messenger.service

/**
 * __VIDEO_REORDER_2026_09_23__
 *
 * Video fragment reassembly with a small REORDER WINDOW, shared by Android
 * (`VideoCallManager.receiveRawVideoData`) and the desktop (`VideoReassembler`) — no
 * imports, so the desktop compiles this very file (OSHI-Desktop/build.gradle.kts).
 *
 * Wire (inside each sealed `0xF1`, unchanged):
 * `[0x01][frame_id 2 BE][fragment_index 1][total_fragments 1][payload]`, and byte 0 of a
 * reassembled frame packet is the flags byte whose bit 0 says "keyframe" (iOS and Android
 * write the same layout).
 *
 * WHY. Both previous receivers held ONE frame in flight: the first fragment of frame N+1
 * abandoned an unfinished N, and once N+1 completed every late fragment of N was "late".
 * So a single datagram overtaking another across a frame boundary — ordinary on Wi-Fi,
 * 5G and any multi-path relay — cost a whole frame AND a keyframe request, exactly like
 * a loss. Measured with `VideoLossBench` (desktop encoder, 5 % of datagrams delayed
 * 10-80 ms, no loss): 82.6 % frames complete, 59 % clean.
 *
 * WHAT IT DOES NOW.
 *  - Fragments are stored per `frame_id`, and a frame completes whenever its last
 *    fragment arrives, in any order.
 *  - Frames are RELEASED to the decoder strictly in `frame_id` order: an H.264 P-frame
 *    decoded before its reference is garbage, so a frame that completes early waits for
 *    the ones before it.
 *  - A missing frame is waited for at most [maxHoldFrames] newer frames or [maxHoldMs]
 *    — whichever comes first — then declared lost and skipped (the "stale" drop), and
 *    only THEN is a keyframe requested, and only when the frame that follows the hole is
 *    not itself a keyframe: an IDR after a hole already is the recovery.
 *  - A keyframe that completes while an older frame is still missing is released at
 *    once: nothing before an IDR is needed to decode it or anything after it.
 *  - Anything older than the next frame to release is late and dropped.
 *
 * Not thread-safe: one instance per call, driven from the receive path (callers lock).
 */
class VideoReorderReassembler(
    val maxHoldFrames: Int = DEFAULT_HOLD_FRAMES,
    val maxHoldMs: Long = DEFAULT_HOLD_MS,
    private val maxInFlight: Int = DEFAULT_MAX_IN_FLIGHT,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val MAGIC: Byte = 0x01
        const val HEADER_SIZE = 5
        /**
         * ~66 ms at 30 fps, and never more than 100 ms. Measured (`VideoLossBench`, 5 % of
         * datagrams delayed 10-80 ms): 2 frames / 100 ms already absorb 99.7 % of the
         * reordering, while 3 / 150 ms cost ~3 points of clean frames under pure loss (the
         * keyframe request for a truly lost frame leaves one frame later) and 1 / 60 ms
         * absorbed only 81.6 %.
         */
        const val DEFAULT_HOLD_FRAMES = 2
        const val DEFAULT_HOLD_MS = 100L
        /** Bounds a hostile stream of never-finished frames (each may hold 255 fragments). */
        const val DEFAULT_MAX_IN_FLIGHT = 32
        /** A jump further ahead than this is a restarted sender, not reordering. */
        const val RESYNC_DISTANCE = 1000

        /** Bit 0 of the frame packet's flags byte. */
        fun isKeyFramePacket(frame: ByteArray): Boolean = frame.isNotEmpty() && (frame[0].toInt() and 1) != 0
    }

    enum class Reason {
        /** Not a fragment, or a header that contradicts itself. */
        MALFORMED,
        /** For a frame already released or skipped. */
        LATE,
        /** Stored; nothing released by this fragment. */
        BUFFERED,
        /** Two different `total_fragments` for one `frame_id`: the entry is gone. */
        TOTAL_MISMATCH,
        /** [Outcome.released] holds at least one whole frame, in decode order. */
        RELEASED,
    }

    /** A whole frame packet, released in `frame_id` order. */
    class Frame(
        val frameId: Int,
        val packet: ByteArray,
        val isKeyFrame: Boolean,
        /** Frames skipped as lost immediately before this one. */
        val lostBefore: Int,
    )

    class Outcome(
        val reason: Reason,
        val frameId: Int = -1,
        val released: List<Frame> = emptyList(),
        /** A reference frame was truly lost and no keyframe followed: ask the peer. */
        val requestKeyframe: Boolean = false,
    )

    private class Entry(val total: Int, val firstSeenMs: Long) {
        val parts = arrayOfNulls<ByteArray>(total)
        var received = 0
        var frame: ByteArray? = null
        var completedAtMs = 0L
    }

    private val pending = HashMap<Int, Entry>()
    /** Next `frame_id` to release; -1 until the first fragment. */
    private var nextId = -1
    /** Newest `frame_id` seen so far. */
    private var newestId = -1

    var completedFrames = 0L
        private set
    /** Frames skipped as lost (never completed within the window). */
    var lostFrames = 0L
        private set
    /** Frames that completed while an OLDER frame was still missing — reordering absorbed. */
    var reorderedFrames = 0L
        private set
    /** Fragments that arrived after their frame was released or skipped. */
    var lateFragments = 0L
        private set

    fun completionRate(): Double {
        val seen = completedFrames + lostFrames
        return if (seen == 0L) 100.0 else completedFrames * 100.0 / seen
    }

    /** In-flight entries (incomplete or held), for tests and diagnostics. */
    fun pending(): Int = pending.size

    private fun dist(from: Int, to: Int): Int = (to - from) and 0xFFFF
    private fun isAhead(from: Int, to: Int): Boolean { val d = dist(from, to); return d in 1..32767 }

    fun offer(data: ByteArray): Outcome {
        if (data.size < HEADER_SIZE + 1 || data[0] != MAGIC) return Outcome(Reason.MALFORMED)
        val id = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        val idx = data[3].toInt() and 0xFF
        val total = data[4].toInt() and 0xFF
        if (total < 1 || idx >= total) return Outcome(Reason.MALFORMED)
        val now = nowMs()

        if (nextId < 0) { nextId = id; newestId = id }
        val d = dist(nextId, id)
        if (d >= 32768) { lateFragments++; return Outcome(Reason.LATE, id) }
        var resyncLoss = false
        if (d > RESYNC_DISTANCE) {
            // The sender restarted its counter (or we were away for a long time): nothing
            // pending can ever be decoded against what comes next.
            lostFrames += pending.size.toLong()
            resyncLoss = pending.isNotEmpty()
            pending.clear()
            nextId = id; newestId = id
        }
        if (isAhead(newestId, id)) newestId = id

        var evicted = false
        if (!pending.containsKey(id) && pending.size >= maxInFlight) {
            // Too many unfinished frames: give up on the oldest one we are waiting for.
            skipTo(oldestPendingAfter(nextId) ?: id)
            evicted = true
        }
        val e = pending.getOrPut(id) { Entry(total, now) }
        if (e.total != total) {
            pending.remove(id)
            val out = drain(now)
            return Outcome(Reason.TOTAL_MISMATCH, id, out.first, out.second || evicted || resyncLoss)
        }
        if (e.frame == null && e.parts[idx] == null) {
            e.parts[idx] = data.copyOfRange(HEADER_SIZE, data.size)
            e.received++
            if (e.received == e.total) {
                var size = 0
                for (p in e.parts) size += p!!.size
                val whole = ByteArray(size)
                var pos = 0
                for (p in e.parts) { p!!.copyInto(whole, pos); pos += p.size }
                e.frame = whole
                e.completedAtMs = now
                completedFrames++
                if (id != nextId) reorderedFrames++
            }
        }
        val (released, kf) = drain(now)
        return if (released.isNotEmpty()) Outcome(Reason.RELEASED, id, released, kf || evicted || resyncLoss)
        else Outcome(Reason.BUFFERED, id, released, kf || evicted || resyncLoss)
    }

    /** Release what the clock alone allows (call on a timer when no packets arrive). */
    fun poll(): Outcome {
        if (nextId < 0) return Outcome(Reason.BUFFERED)
        val (released, kf) = drain(nowMs())
        return Outcome(if (released.isEmpty()) Reason.BUFFERED else Reason.RELEASED, -1, released, kf)
    }

    private fun oldestPendingAfter(from: Int): Int? {
        var best: Int? = null
        var bestD = Int.MAX_VALUE
        for (k in pending.keys) {
            val d = dist(from, k)
            if (d in 1..32767 && d < bestD) { bestD = d; best = k }
        }
        return best
    }

    /** Declare every frame from [nextId] up to (excluding) [id] lost. */
    private fun skipTo(id: Int): Int {
        var n = 0
        while (nextId != id) {
            pending.remove(nextId)
            nextId = (nextId + 1) and 0xFFFF
            n++
        }
        lostFrames += n.toLong()
        return n
    }

    private fun drain(now: Long): Pair<List<Frame>, Boolean> {
        var out: ArrayList<Frame>? = null
        var wantKey = false
        var lostRun = 0
        while (true) {
            val head = pending[nextId]
            val f = head?.frame
            if (f != null) {
                pending.remove(nextId)
                val key = isKeyFramePacket(f)
                if (lostRun > 0 && !key) wantKey = true
                (out ?: ArrayList<Frame>(2).also { out = it }).add(Frame(nextId, f, key, lostRun))
                lostRun = 0
                nextId = (nextId + 1) and 0xFFFF
                continue
            }
            // The head is missing or incomplete. Is there a whole frame waiting behind it?
            var firstDone: Int? = null
            var firstDoneD = Int.MAX_VALUE
            var keyDone: Int? = null
            var keyDoneD = Int.MAX_VALUE
            var oldestDoneAt = Long.MAX_VALUE
            for ((k, e) in pending) {
                val fr = e.frame ?: continue
                val d = dist(nextId, k)
                if (d == 0 || d >= 32768) continue
                if (d < firstDoneD) { firstDoneD = d; firstDone = k }
                if (e.completedAtMs < oldestDoneAt) oldestDoneAt = e.completedAtMs
                if (isKeyFramePacket(fr) && d < keyDoneD) { keyDoneD = d; keyDone = k }
            }
            val target = when {
                // An IDR behind the hole: everything before it is useless, jump now.
                keyDone != null -> keyDone
                firstDone == null -> null
                dist(nextId, newestId) > maxHoldFrames -> firstDone
                now - oldestDoneAt >= maxHoldMs -> firstDone
                else -> null
            } ?: break
            lostRun += skipTo(target)
        }
        return (out ?: emptyList<Frame>()) to wantKey
    }
}
