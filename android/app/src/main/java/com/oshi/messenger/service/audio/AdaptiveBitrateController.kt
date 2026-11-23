package com.oshi.messenger.service.audio

/**
 * AdaptiveBitrateController - iOS Compatible
 * Matches iOS AdaptiveBitrateController with 5 bitrate levels.
 * Tracks sliding window of packet loss and latency to dynamically
 * adjust audio packet size (10-40ms).
 */
class AdaptiveBitrateController {

    enum class BitrateLevel(val samplesPerPacket: Int, val packetDurationMs: Int, val label: String) {
        ULTRA_LOW(160, 10, "Ultra Low"),    // 10ms at 16kHz
        LOW(240, 15, "Low"),                // 15ms at 16kHz
        MEDIUM(320, 20, "Medium"),          // 20ms at 16kHz (default)
        HIGH(480, 30, "High"),              // 30ms at 16kHz
        ULTRA_HIGH(640, 40, "Ultra High")   // 40ms at 16kHz
    }

    var currentLevel: BitrateLevel = BitrateLevel.MEDIUM
        private set

    private val windowSize = 50

    /**
     * How many packets the codec-bitrate loss window holds. ~10 s of 20 ms audio, and
     * 0.1-point resolution — comfortably finer than the thresholds that read it.
     * [AdaptiveCodecBitratePolicy.MIN_LOSS_SAMPLES] is what actually gates the
     * decision; this is only the cap on how far back it looks.
     */
    private val codecWindowSize = 1000

    // 🔒 Every read and write of the two windows below happens under this lock.
    //
    // They were unsynchronised, and that CRASHED A LIVE CALL on a Galaxy A17
    // (2026-08-24 11:53): `reportPacketReceived`/`reportPacketLost` run on the
    // audio RX path while `EnhancedCallManager.startStatsUpdater` reads
    // `packetLossPercent` from a coroutine. Kotlin's ArrayDeque is a circular
    // array and `removeFirst()` NULLS the vacated slot, so an iteration racing a
    // trim reads that null — and because the element type is non-null `Boolean`,
    // the compiler emits an unchecked unboxing:
    //   NullPointerException: Boolean.booleanValue() on a null object reference
    // `recentLatencies.average()` had the identical exposure.
    private val lock = Any()
    private val recentPacketLoss = ArrayDeque<Boolean>() // true = received, false = lost
    private val recentLatencies = ArrayDeque<Double>()

    /**
     * A SECOND, much longer loss window, used only by the codec-bitrate decision
     * ([AdaptiveCodecBitratePolicy]) — never by [adjustLevelLocked].
     *
     * [windowSize] is 50 because that is what iOS's packet-SIZE ladder uses, and its
     * thresholds (10 %, 5 %) are coarse enough that 2-point resolution is fine. The
     * codec-bitrate decision is not: it turns on whether loss is above or below
     * fractions of one percent, and a 50-packet window cannot represent a number
     * between 0 % and 2 % at all. Feeding it that window is what made the encoder
     * flip 80 ↔ 48 kbps once a second on any ordinary 1 %-loss link — the whole of
     * [AdaptiveCodecBitratePolicy]'s header.
     *
     * Kept as its own deque rather than by widening [windowSize] so the packet-size
     * ladder keeps the exact iOS-matching behaviour it is tested for.
     */
    private val codecLossWindow = ArrayDeque<Boolean>()

    var onBitrateChange: ((BitrateLevel, Double, Double) -> Unit)? = null // level, loss%, avgLatency

    val packetLossPercent: Double
        get() = synchronized(lock) { packetLossPercentLocked() }

    private fun packetLossPercentLocked(): Double {
        if (recentPacketLoss.isEmpty()) return 0.0
        val lost = recentPacketLoss.count { !it }
        return (lost.toDouble() / recentPacketLoss.size) * 100.0
    }

    /**
     * Packet loss over the long [codecLossWindow], for [AdaptiveCodecBitratePolicy].
     * Returned together with its sample count because a percentage measured over a
     * handful of packets is not a percentage — the policy refuses to act on one.
     */
    val codecLossWindowStats: Pair<Double, Int>
        get() = synchronized(lock) {
            val size = codecLossWindow.size
            if (size == 0) return@synchronized 0.0 to 0
            val lost = codecLossWindow.count { !it }
            ((lost.toDouble() / size) * 100.0) to size
        }

    val averageLatency: Double
        get() = synchronized(lock) { averageLatencyLocked() }

    private fun averageLatencyLocked(): Double {
        if (recentLatencies.isEmpty()) return 0.0
        return recentLatencies.average()
    }

    /** A level change decided under the lock, to be announced outside it. */
    private data class LevelChange(val level: BitrateLevel, val loss: Double, val latency: Double)

    fun reportPacketReceived() {
        val change = synchronized(lock) {
            recentPacketLoss.addLast(true)
            codecLossWindow.addLast(true)
            trimWindowLocked()
            adjustLevelLocked()
        }
        announce(change)
    }

    fun reportPacketLost() {
        val change = synchronized(lock) {
            recentPacketLoss.addLast(false)
            codecLossWindow.addLast(false)
            trimWindowLocked()
            adjustLevelLocked()
        }
        announce(change)
    }

    fun reportLatency(latencyMs: Double) {
        synchronized(lock) {
            recentLatencies.addLast(latencyMs)
            if (recentLatencies.size > windowSize) {
                recentLatencies.removeFirst()
            }
        }
    }

    // The callback is invoked OUTSIDE the lock: it reaches call-manager code that
    // can take locks of its own, and holding this one across it invites a deadlock
    // on the audio path.
    private fun announce(change: LevelChange?) {
        if (change != null) onBitrateChange?.invoke(change.level, change.loss, change.latency)
    }

    private fun trimWindowLocked() {
        while (recentPacketLoss.size > windowSize) {
            recentPacketLoss.removeFirst()
        }
        while (codecLossWindow.size > codecWindowSize) {
            codecLossWindow.removeFirst()
        }
    }

    /**
     * Asymmetric adjustment matching iOS:
     * - Downgrade immediately on poor conditions
     * - Upgrade only after full window of good data
     *
     * Returns the change to announce, or null. Caller must hold [lock].
     */
    private fun adjustLevelLocked(): LevelChange? {
        val loss = packetLossPercentLocked()
        val latency = averageLatencyLocked()
        val levels = BitrateLevel.entries

        val currentIndex = levels.indexOf(currentLevel)

        // Downgrade immediately if conditions are poor
        if (loss > 10.0 || latency > 300.0) {
            if (currentIndex > 0) {
                currentLevel = levels[currentIndex - 1]
                return LevelChange(currentLevel, loss, latency)
            }
        } else if (loss > 5.0 || latency > 200.0) {
            if (currentIndex > 1) {
                currentLevel = levels[currentIndex - 1]
                return LevelChange(currentLevel, loss, latency)
            }
        }
        // Upgrade after 10 consecutive good packets (faster recovery)
        else if (recentPacketLoss.size >= 10 && recentPacketLoss.takeLast(10).all { it } && loss < 1.0 && latency < 80.0) {
            if (currentIndex < levels.size - 1) {
                currentLevel = levels[currentIndex + 1]
                recentPacketLoss.clear() // Reset window after upgrade
                return LevelChange(currentLevel, loss, latency)
            }
        }
        return null
    }

    fun reset() {
        synchronized(lock) {
            currentLevel = BitrateLevel.MEDIUM
            recentPacketLoss.clear()
            codecLossWindow.clear()
            recentLatencies.clear()
        }
    }
}
