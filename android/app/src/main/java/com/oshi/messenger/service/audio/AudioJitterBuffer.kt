package com.oshi.messenger.service.audio

import com.oshi.messenger.service.diag.CallDiag
import android.util.Log
import java.util.concurrent.PriorityBlockingQueue

/**
 * Adaptive (network-aware) Audio Jitter Buffer — cross-platform (matches iOS).
 *
 * Rewritten per CALL_V2_PLAN Component B. Behaviour:
 * - Target depth starts at 60 ms.
 * - Inter-arrival jitter tracked via RFC 3550 formula:
 *       J += (|D(i,i-1)| - J) / 16
 *   where D is the deviation between the expected packet interval (20 ms per seq
 *   step) and the actual arrival-time delta.
 * - On a late arrival (packet arrives after its expected playback slot has been
 *   consumed) the target depth is raised instantly to max(current, 4*J), clamped
 *   to 60–800 ms — fast expand.
 * - On >=3 consecutive real losses (slot advances with no packet available) the
 *   target depth jumps immediately to min(current * 2, 800) — burst-loss
 *   escalation, so we don't wait for 4*J smoothing to catch up to a sudden
 *   burst (which causes audible pumping).
 * - When eight consecutive 1-second windows see no late arrivals and J < 10 ms,
 *   target depth shrinks by 10 ms/s down to the 60 ms floor — slow shrink,
 *   mirroring NetEQ's asymmetric behaviour. The 8 s window (previously 3 s) is
 *   deliberately long so we don't shrink prematurely and then have to
 *   re-expand on the next loss burst.
 * - Late-but-not-yet-played packets still slot in if the corresponding hole
 *   hasn't been consumed yet.
 * - PLC (packet loss concealment) logic is unchanged: codec-agnostic packet
 *   repetition with a caller-applied fade gain.
 *
 * Public surface matches the previous fixed-depth implementation so callers in
 * EnhancedCallManager do not need edits.
 */
class AudioJitterBuffer(
    @Suppress("UNUSED_PARAMETER") targetDepthMs: Int = 60,
    private val minDepthMs: Int = 60,
    // Compromise ceiling: low enough that delay stays sub-perceptible on a
    // clean network, high enough to absorb a real loss burst without slot-jump
    // thrash. Was tried at 400 ms (too tight, frequent underrun on mobile
    // network jitter) and 800 ms (too loose, perceived lag).
    private val maxDepthMs: Int = 600
) {
    companion object {
        private const val TAG = "AudioJitterBuffer"
        private const val PACKET_DURATION_MS = 20
        private const val MAX_BUFFER_SIZE = 50          // hard cap on buffered packets

        // __STANDING_DELAY_2026_11_11__ Queue drain (see [shedExcess]).
        /** Queue may exceed the adaptive target by this much before it counts as excess. */
        private const val EXCESS_MARGIN_MS = 60
        /** Excess must last this long before anything is shed (absorbs a normal jitter spike). */
        private const val EXCESS_HOLD_MS = 500L
        /** After this long, shed even voiced frames (fade-in covers the seam). */
        private const val EXCESS_FORCE_MS = 3000L
        /** Frames played between two shed frames: at most one in three skipped (≤1.5× speed). */
        private const val SHED_SPACING = 2
        /** RMS below this (on [VoiceMeter]'s scale, −45 dBFS) is a pause, safe to skip. */
        private const val SHED_QUIET_LEVEL = 0.23f
        private const val MAX_CONSECUTIVE_LOSS = 5      // before hard reset
        private const val INITIAL_TARGET_DEPTH_MS = 60  // start
        private const val EXPAND_JITTER_MULTIPLIER = 4  // 4*J per NetEQ-style rule
        // Moderate shrink: too fast (was 20/3) caused chronic underrun when the
        // network briefly bumped J above stability threshold. Too slow (was
        // 10/8) left buffer pinned high for minutes after a single burst.
        private const val SHRINK_STEP_MS = 15
        private const val SHRINK_WINDOW_MS = 1000L      // 1 s windows
        private const val SHRINK_STABLE_WINDOWS = 5     // 5 clean windows
        private const val SHRINK_JITTER_THRESHOLD_MS = 10.0
        private const val LOSS_WINDOW_SIZE = 50
        private const val BURST_LOSS_THRESHOLD = 3      // >=3 consecutive losses -> escalate
        // If the playback slot lags further than this behind the head of the
        // buffer, fast-forward the slot to the head. Without this the slot can
        // get stuck chronically behind the head: when buffer overflow trims the
        // oldest packet, head jumps forward by 1 at the same rate that slot
        // ticks forward — they advance in lockstep and never converge, leaving
        // 90 %+ of incoming packets to be trimmed without ever being played.
        // 25 packets = 500 ms lag triggers the jump (our max target depth is
        // 800 ms, so we tolerate legitimate reordering inside that window).
        private const val SLOT_LAG_JUMP_THRESHOLD = 25

        /**
         * __OPUS_LOOKAHEAD_2026_09_23__ Consecutive late arrivals, spread over at least
         * [LATE_STREAK_MIN_SPAN_FRAMES] frame durations of wall clock, that mean the slot
         * clock has run AHEAD of the stream (a delay step: Wi-Fi → LTE, a relay switch)
         * rather than a bunched burst (which arrives all at once). Before this, a step
         * larger than the playout margin made every later packet "late" for the rest of
         * the call: measured in a JVM simulation of this class, +50 ms at packet 1000 ⇒
         * 0 real frames played out of the next 2000.
         */
        private const val LATE_STREAK_RESYNC = 3
        private const val LATE_STREAK_MIN_SPAN_FRAMES = 2

        /** 48 kHz mono Int16: every PCM frame this buffer stores is 96 bytes per ms. */
        private const val PCM_BYTES_PER_MS = 96

        /** Longest frame duration we believe (a malformed 64 KB frame is not 680 ms of slot). */
        private const val MAX_FRAME_MS = 120

        /**
         * __DROP_CLICK_FADE_2026_09_22__ 2 ms at 48 kHz — the fade-in applied to the
         * first real frame after a discontinuity (iOS: `min(frameLength, 96)`).
         */
        const val FADE_IN_SAMPLES = 96

        /**
         * __PITCH_PLC_2026_09_23__ Envelope of consecutive concealment frames: frame k ramps
         * from PLC_GAINS[k-1] to PLC_GAINS[k], so the level never steps and reaches zero at
         * [MAX_CONSECUTIVE_LOSS], where the loop switches to silence.
         */
        private val PLC_GAINS = floatArrayOf(1.0f, 0.8f, 0.6f, 0.4f, 0.2f, 0.0f)
        private const val PLC_PITCH_WINDOW = 240   // 5 ms @ 48 kHz
        private const val PLC_MIN_PERIOD = 96      // 2 ms  = 500 Hz
        private const val PLC_MAX_PERIOD = 720     // 15 ms = 67 Hz

        /**
         * Duration of one stored PCM frame, never below the 20 ms slot the buffer was
         * built around. The iPhone App Store build sends ~32 ms frames at ~30 pkt/s; a
         * slot clock pinned at 20 ms declared a "hole" between every two of them and
         * filled it with PLC — invented audio, the same defect iOS removed on its side
         * (__KEEPALIVE_IS_NOT_AUDIO_2026_09_22__).
         */
        fun frameMsFor(pcmBytes: Int): Int =
            (pcmBytes / PCM_BYTES_PER_MS).coerceIn(PACKET_DURATION_MS, MAX_FRAME_MS)

        /**
         * Returns a copy of [pcm] (Int16 LE) whose first [FADE_IN_SAMPLES] samples ramp
         * linearly from 0 to unity. Never mutates the input: it is also the PLC source.
         */
        fun fadeInCopy(pcm: ByteArray): ByteArray {
            val out = pcm.copyOf()
            val n = minOf(pcm.size / 2, FADE_IN_SAMPLES)
            for (i in 0 until n) {
                val lo = out[2 * i].toInt() and 0xFF
                val hi = out[2 * i + 1].toInt()
                val s = ((hi shl 8) or lo).toShort()
                val scaled = (s * i / n)
                out[2 * i] = (scaled and 0xFF).toByte()
                out[2 * i + 1] = ((scaled shr 8) and 0xFF).toByte()
            }
            return out
        }
    }

    /** Packet wrapper — identical shape to previous version for binary/test compat. */
    data class AudioPacket(
        val sequenceNumber: Long,
        val data: ByteArray,
        val timestamp: Long = System.currentTimeMillis()
    ) : Comparable<AudioPacket> {
        override fun compareTo(other: AudioPacket): Int =
            sequenceNumber.compareTo(other.sequenceNumber)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AudioPacket) return false
            return sequenceNumber == other.sequenceNumber
        }

        override fun hashCode(): Int = sequenceNumber.hashCode()
    }

    // Priority queue keeps packets sequence-ordered.
    private val buffer = PriorityBlockingQueue<AudioPacket>(MAX_BUFFER_SIZE)

    // Core state
    private var expectedSequence = -1L
    private var lastPlayedSequence = -1L
    // The next sequence the consumer will try to play. Advances when a real
    // packet is popped, OR when at least one packet-duration has elapsed
    // since the last advance (wall-clock rate-limiting, so repeated
    // getNextPacket() calls triggered by a burst of arrivals don't run the
    // slot past legitimately reordered packets). A real arrival with seq
    // below this is "late" — its slot has already been played.
    private var nextSlotSequence = -1L
    private var lastSlotAdvanceMs = 0L
    private var consecutiveLosses = 0
    private var totalPacketsReceived = 0L
    private var totalPacketsLost = 0L
    private var totalPacketsPlayed = 0L
    @Volatile var totalPacketsConcealed = 0L
    // WHY: DIAG_JITTER_JUMP — each slot-lag fast-forward bumps this so
    // EnhancedCallManager's DIAG_PLAYBACK_RATE can surface a total without
    // reading a private field.
    @Volatile var totalSlotJumps = 0L

    private var lastValidPacket: ByteArray? = null

    /**
     * __PITCH_PLC_2026_09_23__ Pitch lock of [lastValidPacket] for concealment (samples at
     * 48 kHz; 0 = unvoiced / no lock), the phase the tile has reached, the gain the last
     * concealment frame ended on, and whether the previous slot was concealment.
     */
    private var plcPeriod = 0
    private var plcPhase = 0
    private var plcEndGain = 0f
    private var lastWasConcealment = false

    /** Duration of the last real frame played — the slot clock (see [frameMsFor]). */
    private var lastFrameMs = PACKET_DURATION_MS

    /**
     * __DROP_CLICK_FADE_2026_09_22__ Set whenever what the speaker gets next is NOT the
     * continuation of the last real frame: an overflow trim, a slot-lag jump, a
     * concealment frame (which fades to zero), or a null the loop plays as silence.
     * The next real frame then starts with a 2 ms fade-in instead of a step — the step
     * is the click. Measured on iOS (call 6216CD2F): 766 queue-full drops in 29 min,
     * one click every ~2 s on that side.
     */
    private var fadeInNext = false

    /** Real frames that were faded in after a discontinuity. Diagnostic. */
    @Volatile var totalFadeIns = 0L

    /** Packets discarded because the buffer was full. Diagnostic. */
    @Volatile var totalOverflowDrops = 0L

    /** __STANDING_DELAY_2026_11_11__ Frames skipped to bring a standing queue back to target. */
    @Volatile var totalShedFrames = 0L
    /** Deepest queue seen this call, ms. Diagnostic. */
    @Volatile var maxQueueMs = 0
    private var excessSinceMs = 0L
    private var playedSinceShed = 0

    /**
     * __OPUS_LOOKAHEAD_2026_09_23__ Frames that must be queued BEYOND the one about to play
     * before playout (re)starts. 0x19 Opus repeats the previous frames inside each packet,
     * so a lost frame k comes back inside packet k+1 — but only if slot k has not been
     * played (concealed) by the time k+1 arrives. Priming at 1 packet played every frame
     * the tick after it arrived (0-20 ms of margin, lockstep), so the recovered copy was
     * always late and dropped. One frame of look-ahead (+20 ms) lets it land in time.
     * MEASURED (audio_lab/opus, 4 voices, this class in the loop, 3 % random loss):
     * PESQ-WB 3.44 lockstep → 4.27 with 20 ms of margin. Set by the 0x19 receive path;
     * cleared by [reset].
     */
    @Volatile var lookAheadFrames = 0

    /** Late-streak resyncs (see [LATE_STREAK_RESYNC]). Diagnostic. */
    @Volatile var totalResyncs = 0L

    /** True until playout (re)starts: initially, and after a late-streak resync. */
    private var priming = true
    /** Packets needed to leave [priming] after a resync (the initial prime uses the depth). */
    private var resyncPrime = false
    private var lateStreak = 0
    private var lateStreakStartMs = 0L

    // Adaptive depth state
    private var currentDepthMs = INITIAL_TARGET_DEPTH_MS
    private var networkJitterMs = 0.0              // rolling RFC 3550 J estimate
    private var lastArrivalSeq = -1L
    private var lastArrivalTimeMs = 0L

    // Shrink accounting
    private var windowStartMs = 0L
    private var stableWindows = 0
    private var lateInCurrentWindow = false

    // Grace period: suppress false POOR at start while buffer fills
    private var firstPacketReceivedTime = 0L
    private val INITIAL_GRACE_PERIOD_MS = 3000L

    // Rolling loss window
    private val recentLossWindow = ArrayDeque<Boolean>(LOSS_WINDOW_SIZE)

    // Duplicate detection. EnhancedCallManager delivers the same encrypted
    // packet from multiple transports (WebSocket, HTTP VPS poller, mesh TCP,
    // direct UDP), each on its own coroutine — so addPacket is hot from
    // 10+ worker threads. A plain HashSet corrupts under concurrent write +
    // sort: `seenSequencesLong.sorted()` iterates the hash table while
    // another thread is resizing it, pulls out a transiently-null entry, and
    // throws "Comparable.compareTo on a null object reference" — exactly the
    // NPE we see in logcat. java.util.TreeSet is sorted+thread-safe-enough
    // when we also wrap the compound operations in `synchronized(this)`.
    private val seenSequencesLong = java.util.TreeSet<Long>()
    private val maxSeenSize = 100

    // Injectable clock so tests can simulate arrivals without real wall-clock
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * Add a packet to the buffer.
     * Returns true if accepted, false if duplicate or too old to be useful.
     */
    @Synchronized
    fun addPacket(sequenceNumber: Long, data: ByteArray): Boolean {
        val now = clock()

        if (expectedSequence < 0) {
            expectedSequence = sequenceNumber
            nextSlotSequence = sequenceNumber
        }
        if (seenSequencesLong.contains(sequenceNumber)) return false

        // RFC 3550 jitter update (runs for every arrival, including late ones,
        // so J converges to true network jitter regardless of whether the
        // packet makes it into the buffer).
        if (lastArrivalSeq >= 0) {
            val arrivalDelta = now - lastArrivalTimeMs
            val expectedDelta = (sequenceNumber - lastArrivalSeq) * frameMsFor(data.size)
            val d = arrivalDelta - expectedDelta
            val absD = if (d < 0) -d else d
            networkJitterMs += (absD.toDouble() - networkJitterMs) / 16.0
        }
        lastArrivalSeq = sequenceNumber
        lastArrivalTimeMs = now

        // Late arrival — the consumer has already advanced past this seq,
        // either by playing a neighbour or by emitting PLC for this slot.
        if (nextSlotSequence >= 0 && sequenceNumber < nextSlotSequence) {
            val latenessMs = (nextSlotSequence - sequenceNumber) * frameMsFor(data.size)
            // Feed the observed lateness into J so the RFC 3550 smooth estimator
            // reflects real scheduling deviation, not just inter-arrival spacing.
            // Without this, steady-state J barely moves under loss because the
            // formula's expected_delta already accounts for missing seq numbers.
            networkJitterMs += (latenessMs.toDouble() - networkJitterMs) / 16.0
            recordLateArrival(latenessMs.toInt())
            // __OPUS_LOOKAHEAD_2026_09_23__ a sustained run of late arrivals: the stream
            // is now behind the slot clock for good. Re-anchor on it and re-prime with
            // the look-ahead instead of dropping every packet for the rest of the call.
            if (lateStreak == 0) lateStreakStartMs = now
            lateStreak++
            val span = now - lateStreakStartMs
            if (lateStreak < LATE_STREAK_RESYNC ||
                span < LATE_STREAK_MIN_SPAN_FRAMES.toLong() * frameMsFor(data.size)
            ) return false
            // runCatching: android.util.Log is a stub that throws in JVM unit tests.
            runCatching { CallDiag.w(TAG, "DIAG_JITTER_RESYNC | slot=$nextSlotSequence | seq=$sequenceNumber | lateStreak=$lateStreak | spanMs=$span | lookAhead=$lookAheadFrames | totalResyncs=${totalResyncs + 1}") }
            totalResyncs++
            buffer.removeIf { it.sequenceNumber < sequenceNumber }
            nextSlotSequence = sequenceNumber
            consecutiveLosses = 0
            fadeInNext = true
            priming = true
            resyncPrime = true
            lateStreak = 0
            // fall through: this packet is the new head
        } else {
            lateStreak = 0
        }

        // Record into seen set. TreeSet is sorted by insertion so no sort needed
        // when trimming — `first()` gives the smallest key, drop until we're back
        // under cap.
        seenSequencesLong.add(sequenceNumber)
        while (seenSequencesLong.size > maxSeenSize) {
            seenSequencesLong.pollFirst()
        }

        buffer.offer(AudioPacket(sequenceNumber, data, now))
        totalPacketsReceived++
        if (firstPacketReceivedTime == 0L) firstPacketReceivedTime = now

        while (buffer.size > MAX_BUFFER_SIZE) {
            if (buffer.poll() != null) {
                totalOverflowDrops++
                fadeInNext = true
            }
        }

        // Run the slow-shrink bookkeeper.
        maybeAdaptDepth(now)
        return true
    }

    /** Called when a packet arrives after its slot was already played. Fast expand.
     *  Per CALL_V2_PLAN Component B: widens to max(current, 4*J). We use the
     *  observed lateness directly in the expansion decision in addition to the
     *  smoothed J, because a single large late packet is the clearest signal
     *  that headroom is needed — EWMA J is too slow to react to short bursts.
     *  Both are clamped to [60, 800] ms. */
    private fun recordLateArrival(latenessMs: Int) {
        lateInCurrentWindow = true
        stableWindows = 0
        val jBased = (EXPAND_JITTER_MULTIPLIER * networkJitterMs).toInt()
        val lateBased = EXPAND_JITTER_MULTIPLIER * latenessMs
        val target = kotlin.math.max(currentDepthMs, kotlin.math.max(jBased, lateBased))
        currentDepthMs = target.coerceIn(minDepthMs, maxDepthMs)
    }

    /**
     * Run once per addPacket — closes out 1-second windows and shrinks target
     * depth if the stream has been stable (no late arrivals, low J) for
     * [SHRINK_STABLE_WINDOWS] consecutive windows.
     */
    private fun maybeAdaptDepth(now: Long) {
        if (windowStartMs == 0L) {
            windowStartMs = now
            return
        }
        if (now - windowStartMs < SHRINK_WINDOW_MS) return

        // Close this 1 s window.
        if (!lateInCurrentWindow && networkJitterMs < SHRINK_JITTER_THRESHOLD_MS) {
            stableWindows++
            if (stableWindows >= SHRINK_STABLE_WINDOWS) {
                currentDepthMs = kotlin.math.max(
                    minDepthMs,
                    currentDepthMs - SHRINK_STEP_MS
                )
                // Keep at the "stable for 3" count so continued stability => keeps shrinking 10ms/s.
                stableWindows = SHRINK_STABLE_WINDOWS
            }
        } else {
            stableWindows = 0
        }
        lateInCurrentWindow = false
        windowStartMs = now
    }

    /**
     * Get the next packet for playback.
     * Returns null while still priming, or concealment data on underrun.
     */
    @Synchronized
    fun getNextPacket(): ByteArray? {
        val now = clock()

        // Still priming: hold off playback until half of the target depth is
        // queued (or until we've ever played anything — after that we never
        // re-prime without an explicit reset).
        // __OPUS_LOOKAHEAD_2026_09_23__ plus [lookAheadFrames] beyond the frame that plays
        // first. After a resync only the look-ahead is re-primed: the depth estimate was just
        // inflated by the late arrivals that triggered it, and re-priming to it would add up
        // to 100 ms of delay for good.
        if (priming) {
            val base = if (resyncPrime) 1 else kotlin.math.max(1, currentDepthMs / PACKET_DURATION_MS / 2)
            val primePackets = kotlin.math.max(base, 1 + lookAheadFrames)
            if (buffer.size < primePackets) return null
            priming = false
            resyncPrime = false
        }

        // Peek at head — may be ahead of nextSlotSequence (hole / loss).
        var head = buffer.peek()

        // nextSlotSequence should already be initialised on first addPacket,
        // but guard defensively.
        if (nextSlotSequence < 0) {
            nextSlotSequence = head?.sequenceNumber ?: 0L
        }

        // Slot-lag fast-forward: if the buffer head is many packets ahead of
        // the slot, the incoming stream has run past us (sustained overflow
        // trim, or a big reorder). Walking forward one slot per tick would
        // never catch up to a 50 pps stream. Jump the slot to head, reset the
        // consecutive-loss counter, and play immediately.
        if (head != null && head.sequenceNumber - nextSlotSequence > SLOT_LAG_JUMP_THRESHOLD) {
            val oldSlot = nextSlotSequence
            val newSlot = head.sequenceNumber
            val lag = newSlot - oldSlot
            totalSlotJumps++
            // WHY: DIAG_JITTER_JUMP — fires on every slot-lag fast-forward. If this appears
            // repeatedly on a single call, the jitter buffer is being starved (consumer was
            // paused or the network is reordering huge bursts). Skipped packets = lag - 1
            // (the slot jumps to the head, and everything in (oldSlot, newSlot) is voided
            // as losses). totalPacketsLost reflects prior reset.
            CallDiag.w(
                TAG,
                "DIAG_JITTER_JUMP | oldSlot=$oldSlot | newSlot=$newSlot | lag=$lag | skipped=${lag - 1} | bufferDepth=${buffer.size} | totalJumps=$totalSlotJumps | J=${"%.1f".format(networkJitterMs)} | depthMs=$currentDepthMs"
            )
            nextSlotSequence = newSlot
            consecutiveLosses = 0
            fadeInNext = true
        }

        if (head != null && head.sequenceNumber == nextSlotSequence && shedExcess(head, now)) {
            head = buffer.peek()
        }

        // Case 1: head matches the slot we're about to play — consume it.
        if (head != null && head.sequenceNumber == nextSlotSequence) {
            playedSinceShed++
            buffer.poll()
            lastPlayedSequence = head.sequenceNumber
            // __PITCH_PLC_2026_09_23__ back from a voiced concealment: cross-fade from the
            // tile's continuation instead of fading in from zero — the concealment ended
            // at plcEndGain, so a ramp from 0 would dip (a click) right after the hole.
            val tileSource = lastValidPacket
            val crossFade = lastWasConcealment && plcPeriod > 0 && tileSource != null &&
                consecutiveLosses in 1..MAX_CONSECUTIVE_LOSS && plcEndGain > 0f
            lastWasConcealment = false
            lastValidPacket = head.data
            lastFrameMs = frameMsFor(head.data.size)
            consecutiveLosses = 0
            totalPacketsPlayed++
            nextSlotSequence++
            lastSlotAdvanceMs = now
            pushToLossWindow(true)
            if (crossFade) {
                fadeInNext = false
                return crossFadeFromTile(tileSource!!, head.data)
            }
            if (fadeInNext) {
                fadeInNext = false
                totalFadeIns++
                return fadeInCopy(head.data)
            }
            return head.data
        }

        // Case 2/3: head is ahead of the slot (hole) or buffer is empty
        // (underrun). Only advance the slot if a full packet-duration has
        // elapsed since the last advance — this rate-limits slot motion to
        // real-time playback speed even if the caller is draining faster,
        // preserving legitimate reordering inside a 20 ms window.
        // The slot lasts as long as the frame that filled it (see [frameMsFor]); for the
        // 20 ms frames of every current build this is exactly the old rule.
        val canAdvance = lastSlotAdvanceMs == 0L ||
            (now - lastSlotAdvanceMs) >= lastFrameMs
        // Whatever the loop plays instead of this slot — silence, concealment — the next
        // real frame no longer continues the last one's waveform.
        fadeInNext = true
        if (!canAdvance) return null

        consecutiveLosses++
        val inGracePeriod = firstPacketReceivedTime == 0L ||
            (now - firstPacketReceivedTime < INITIAL_GRACE_PERIOD_MS)
        if (!inGracePeriod) {
            totalPacketsLost++
            pushToLossWindow(false)
            // Burst-loss escalation: on >=3 consecutive real losses, jump the
            // target depth immediately to min(current * 2, maxDepthMs). Waiting
            // for the 4*J smoothing to catch up causes audible pumping when a
            // burst hits — this pre-empts it. We also reset the shrink-stability
            // counter so we don't immediately start shrinking back.
            if (consecutiveLosses >= BURST_LOSS_THRESHOLD) {
                val escalated = (currentDepthMs * 2).coerceAtMost(maxDepthMs)
                if (escalated > currentDepthMs) {
                    currentDepthMs = escalated.coerceAtLeast(minDepthMs)
                }
                stableWindows = 0
                lateInCurrentWindow = true
            }
        }
        nextSlotSequence++
        lastSlotAdvanceMs = now

        if (consecutiveLosses > MAX_CONSECUTIVE_LOSS) {
            // Too many in a row — stop regenerating PLC; let caller play
            // silence. Don't hard-reset state: the adapted target depth we
            // just widened is still useful.
            return null
        }
        return generateConcealmentAudio()
    }

    /**
     * 🔴 __STANDING_DELAY_2026_11_11__ Bring a standing queue back to the target.
     *
     * The adaptive target ([currentDepthMs]) only decided when playout STARTS; after
     * that exactly one frame left per 20 ms tick, so anything that arrived in a burst
     * stayed queued for the rest of the call. Measured with this class: a 500 ms
     * stall at call start (25 frames delivered at once, then a perfect 50 pps
     * stream) held the queue at 480 ms for all 20 s simulated — a permanent half
     * second of mouth-to-ear delay. Complaint 2d3fe47d (Android 1.6.93 ← iOS, "delay",
     * 27 packets over WebSocket at setup, 11 % duplicates) is that shape.
     *
     * When the queue has exceeded target + [EXCESS_MARGIN_MS] for [EXCESS_HOLD_MS],
     * the head frame is skipped if it is a pause; after [EXCESS_FORCE_MS] it is
     * skipped regardless. At most one frame in [SHED_SPACING] + 1, so speech is never
     * swallowed in a block.
     *
     * @return true if the head frame was dropped.
     */
    private fun shedExcess(head: AudioPacket, now: Long): Boolean {
        val queuedMs = buffer.size * PACKET_DURATION_MS
        if (queuedMs > maxQueueMs) maxQueueMs = queuedMs
        val limit = currentDepthMs + lookAheadFrames * PACKET_DURATION_MS + EXCESS_MARGIN_MS
        if (queuedMs <= limit || buffer.size < 2) {
            excessSinceMs = 0L
            return false
        }
        if (excessSinceMs == 0L) excessSinceMs = now
        val held = now - excessSinceMs
        if (held < EXCESS_HOLD_MS || playedSinceShed < SHED_SPACING) return false
        val quiet = VoiceMeter.levelOfInt16Le(head.data) < SHED_QUIET_LEVEL
        if (!quiet && held < EXCESS_FORCE_MS) return false
        buffer.poll()
        nextSlotSequence++
        totalShedFrames++
        playedSinceShed = 0
        // A skipped voiced frame leaves a seam: fade the next one in, as after any jump.
        if (!quiet) fadeInNext = true
        return true
    }

    /**
     * PLC — codec-agnostic concealment. __PITCH_PLC_2026_09_23__: a VOICED frame is now
     * concealed by tiling its last pitch period (see the body); the per-sample fade below
     * remains the unvoiced fallback. Original notes:
     *
     * **per-sample linear fade-to-silence**.
     *
     * The previous implementation just returned `lastValidPacket` unchanged with
     * a constant per-frame gain applied by the caller. Repeating the same 20 ms
     * of audio with the same spectral content is the textbook cause of the
     * "metallic / vocoded / robotic" artifact users hear: the listener's ear
     * picks up the waveform's near-DC self-correlation as a tone.
     *
     * Here we instead linearly fade the previous packet's samples down to
     * silence WITHIN the 20 ms frame:
     *   sample[i] *= startGain + (endGain - startGain) * i / (n - 1)
     *
     * - 1st concealment in a row: 0.6 → 0.0 (audible but ramps to silence)
     * - 2nd:                      0.3 → 0.0
     * - 3rd+:                     0.15 → 0.0
     *
     * After MAX_CONSECUTIVE_LOSS the caller plays pure silence (we return null).
     * This kills the spectral repeat that produced the metallic sound and
     * substitutes a small ramp-down click — much closer to telephone-quality PLC.
     */
    private fun generateConcealmentAudio(): ByteArray? {
        totalPacketsConcealed++
        val source = lastValidPacket ?: return null
        val out = ByteArray(source.size)
        val n = source.size / 2
        if (n == 0) return out
        val s = IntArray(n) { ((source[2 * it + 1].toInt() shl 8) or (source[2 * it].toInt() and 0xFF)).toShort().toInt() }
        // __PITCH_PLC_2026_09_23__ (iOS PacketLossConcealer's idea, with a male-voice range
        // and phase carried across consecutive losses). Was: the last frame replayed from
        // its START at 0.6→0 — the step from the previous frame's end to 0.6 × its start
        // is a broadband click at every lost packet, visible as a vertical line on a
        // spectrogram. MEASURED (audio lab, 4 `say` voices FR/EN, this class in the loop),
        // PESQ-WB mean old → new: raw PCM 3 % random loss 3.07 → 3.59; 0x18 + post-filter
        // 3 % 2.27 → 2.47, 5 % 1.87 → 2.11, bursty 3 % 2.72 → 2.85; STOI up everywhere.
        if (consecutiveLosses == 1) {
            plcPeriod = estimatePitchPeriod(s)
            plcPhase = 0
        }
        val period = plcPeriod
        val k = consecutiveLosses.coerceIn(1, PLC_GAINS.size - 1)
        // Unvoiced (no pitch lock): the previous behaviour — the frame replayed at
        // 0.6/0.3/0.15 fading to zero inside the frame; noise masks its start step.
        val g0 = if (period > 0) PLC_GAINS[k - 1] else when (consecutiveLosses) { 1 -> 0.6f; 2 -> 0.3f; else -> 0.15f }
        val g1 = if (period > 0) PLC_GAINS[k] else 0f
        for (i in 0 until n) {
            val env = g0 + (g1 - g0) * i / n
            // Voiced: tile the LAST pitch period, continuing where the previous frame (or
            // the previous concealment) stopped — no phase jump. Unvoiced: replay the frame.
            val v = if (period > 0) s[n - period + (plcPhase + i) % period] else s[i]
            val scaled = (v * env).toInt().coerceIn(-32768, 32767)
            out[2 * i] = (scaled and 0xFF).toByte()
            out[2 * i + 1] = ((scaled shr 8) and 0xFF).toByte()
        }
        if (period > 0) plcPhase = (plcPhase + n) % period
        plcEndGain = g1
        lastWasConcealment = true
        return out
    }

    /**
     * Normalised autocorrelation over the last 5 ms of [s], lags 2-15 ms (500-67 Hz:
     * iOS stops at 5.3 ms = 187 Hz, which misses most male voices). The SHORTEST lag
     * scoring within 10 % of the best wins, so a period is not doubled. 0 = no lock.
     */
    private fun estimatePitchPeriod(s: IntArray): Int {
        val n = s.size
        val win = PLC_PITCH_WINDOW
        val maxP = minOf(PLC_MAX_PERIOD, n - win)
        if (maxP <= PLC_MIN_PERIOD) return 0
        var e0 = 0L
        for (i in n - win until n) e0 += s[i].toLong() * s[i]
        if (e0 < win.toLong() * 900) return 0              // < ~−61 dBFS rms: nothing to lock
        val score = DoubleArray(maxP + 1)
        var best = 0.0
        for (p in PLC_MIN_PERIOD..maxP) {
            var num = 0L; var den = 0L
            for (i in n - win until n) { val b = s[i - p].toLong(); num += s[i] * b; den += b * b }
            val sc = if (den > 0) num / Math.sqrt(den.toDouble() * e0) else 0.0
            score[p] = sc
            if (sc > best) best = sc
        }
        if (best < 0.5) return 0
        for (p in PLC_MIN_PERIOD..maxP) {
            val prev = if (p > PLC_MIN_PERIOD) score[p - 1] else -1.0
            val next = if (p < maxP) score[p + 1] else -1.0
            if (score[p] >= 0.9 * best && score[p] >= prev && score[p] >= next) return p
        }
        return 0
    }

    /** The first [FADE_IN_SAMPLES] of [real] blended in from the concealment tile's continuation. */
    private fun crossFadeFromTile(source: ByteArray, real: ByteArray): ByteArray {
        val n = source.size / 2
        val out = real.copyOf()
        val period = plcPeriod
        if (n < period || period <= 0) return fadeInCopy(real)
        val m = minOf(real.size / 2, FADE_IN_SAMPLES)
        for (i in 0 until m) {
            val j = n - period + (plcPhase + i) % period
            val t = ((source[2 * j + 1].toInt() shl 8) or (source[2 * j].toInt() and 0xFF)).toShort().toInt()
            val r = ((out[2 * i + 1].toInt() shl 8) or (out[2 * i].toInt() and 0xFF)).toShort().toInt()
            val w = i.toFloat() / m
            val v = (r * w + t * plcEndGain * (1f - w)).toInt().coerceIn(-32768, 32767)
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Caller-applied fade gain for PLC output.
     *  - 1st loss: 1.0
     *  - 2nd loss: 0.6
     *  - 3rd+:     0.3
     */
    /**
     * PLC fade gain — applied by caller to the PCM bytes of the concealment
     * frame. First concealment is 0.5 (not 1.0) so a single-packet hole isn't
     * a straight repeat of the previous 20 ms at full volume (that's the
     * tonal/metallic artifact users hear when loss is a few percent). Softer
     * repeat masks the hole as a brief dip rather than a retriggered sample.
     */
    fun plcFadeGain(): Float = when (consecutiveLosses) {
        0 -> 1.0f
        1 -> 0.5f
        2 -> 0.3f
        else -> 0.15f
    }

    private fun pushToLossWindow(received: Boolean) {
        synchronized(recentLossWindow) {
            recentLossWindow.addLast(received)
            if (recentLossWindow.size > LOSS_WINDOW_SIZE) recentLossWindow.removeFirst()
        }
    }

    /** Reset — public, idempotent, clears everything. */
    @Synchronized
    fun reset() {
        buffer.clear()
        expectedSequence = -1L
        lastPlayedSequence = -1L
        nextSlotSequence = -1L
        lastSlotAdvanceMs = 0L
        consecutiveLosses = 0
        lastValidPacket = null
        plcPeriod = 0; plcPhase = 0; plcEndGain = 0f; lastWasConcealment = false
        lastFrameMs = PACKET_DURATION_MS
        fadeInNext = false
        totalFadeIns = 0
        totalOverflowDrops = 0
        totalShedFrames = 0
        maxQueueMs = 0
        excessSinceMs = 0L
        playedSinceShed = 0
        seenSequencesLong.clear()
        synchronized(recentLossWindow) { recentLossWindow.clear() }
        currentDepthMs = INITIAL_TARGET_DEPTH_MS
        networkJitterMs = 0.0
        lastArrivalSeq = -1L
        lastArrivalTimeMs = 0L
        windowStartMs = 0L
        stableWindows = 0
        lateInCurrentWindow = false
        totalPacketsReceived = 0
        totalPacketsLost = 0
        totalPacketsPlayed = 0
        totalPacketsConcealed = 0
        totalSlotJumps = 0
        firstPacketReceivedTime = 0L
        lookAheadFrames = 0
        totalResyncs = 0
        priming = true
        resyncPrime = false
        lateStreak = 0
        lateStreakStartMs = 0L
    }

    /** Duplicate dedup for FEC / redundant-frame injection paths. */
    @Synchronized
    fun hasPacket(sequenceNumber: Long): Boolean =
        seenSequencesLong.contains(sequenceNumber)

    val bufferDepth: Int get() = buffer.size
    val bufferDepthMs: Int get() = buffer.size * PACKET_DURATION_MS

    val packetLossRatio: Float
        get() {
            val total = totalPacketsReceived + totalPacketsLost
            return if (total > 0) totalPacketsLost.toFloat() / total else 0f
        }

    /** Rolling-window quality — same semantics as before. */
    val networkQuality: NetworkQuality
        get() {
            val snapshot: List<Boolean> =
                synchronized(recentLossWindow) { recentLossWindow.toList() }
            val lost = snapshot.count { !it }
            val lossRatio = if (snapshot.isEmpty()) 0f else lost.toFloat() / snapshot.size
            // 📶 The bands are iOS's and live in [NetworkQualityBands], where they
            // can be tested — this class cannot be constructed on the JVM.
            return NetworkQualityBands.forLoss(lossRatio, snapshot.size)
        }

    fun getStats(): JitterBufferStats = JitterBufferStats(
        bufferDepth = buffer.size,
        bufferDepthMs = bufferDepthMs,
        targetDepthMs = currentDepthMs,
        packetsReceived = totalPacketsReceived,
        packetsLost = totalPacketsLost,
        packetsPlayed = totalPacketsPlayed,
        lossRatio = packetLossRatio,
        quality = networkQuality,
        packetsConcealed = totalPacketsConcealed
    )

    /** Exposed so tests / diag can read current jitter estimate J. */
    val jitterEstimateMs: Double get() = networkJitterMs
}

/**
 * Network quality levels
 */
enum class NetworkQuality {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR,
    VERY_POOR,
    RECONNECTING,
    DISCONNECTED
}

/**
 * Jitter buffer statistics. `targetDepthMs` reflects the *adaptive* target,
 * not the constructor default.
 */
data class JitterBufferStats(
    val bufferDepth: Int,
    val bufferDepthMs: Int,
    val targetDepthMs: Int,
    val packetsReceived: Long,
    val packetsLost: Long,
    val packetsPlayed: Long,
    val lossRatio: Float,
    val quality: NetworkQuality,
    val packetsConcealed: Long = 0
)

/**
 * Video Jitter Buffer
 *
 * Similar to audio buffer but for video frames.
 * Handles frame reordering and keyframe management.
 */
class VideoJitterBuffer(
    private val maxBufferSize: Int = 5,
    private val minBufferDepth: Int = 2
) {
    companion object {
        private const val TAG = "VideoJitterBuffer"
    }

    data class VideoFrame(
        val timestamp: Long,
        val data: ByteArray,
        val isKeyFrame: Boolean,
        val spsData: ByteArray? = null,
        val ppsData: ByteArray? = null
    ) : Comparable<VideoFrame> {
        override fun compareTo(other: VideoFrame): Int =
            timestamp.compareTo(other.timestamp)
    }

    private val buffer = PriorityBlockingQueue<VideoFrame>(maxBufferSize)
    private var lastKeyFrame: VideoFrame? = null
    private var isStarted = false

    fun addFrame(
        timestamp: Long,
        data: ByteArray,
        isKeyFrame: Boolean,
        spsData: ByteArray? = null,
        ppsData: ByteArray? = null
    ) {
        val frame = VideoFrame(timestamp, data, isKeyFrame, spsData, ppsData)
        if (isKeyFrame) lastKeyFrame = frame
        buffer.offer(frame)
        while (buffer.size > maxBufferSize) {
            val dropped = buffer.poll()
            if (dropped?.isKeyFrame == true) {
                Log.w(TAG, "Dropped keyframe due to buffer overflow")
            }
        }
    }

    fun getNextFrame(): VideoFrame? {
        if (!isStarted && buffer.size < minBufferDepth) return null
        isStarted = true
        return buffer.poll()
    }

    val depth: Int get() = buffer.size

    fun reset() {
        buffer.clear()
        lastKeyFrame = null
        isStarted = false
    }

    fun needsKeyFrame(): Boolean = lastKeyFrame == null || buffer.isEmpty()
}
