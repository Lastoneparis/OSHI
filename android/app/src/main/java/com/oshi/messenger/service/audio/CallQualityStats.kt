package com.oshi.messenger.service.audio

/**
 * CallQualityStats - iOS Compatible
 * Matches iOS CallQualityStats struct.
 * Aggregates latency, jitter, loss, and FEC/PLC recovery stats.
 */
data class CallQualityStats(
    val latencyMs: Int = 0,
    val jitterMs: Int = 0,
    val packetLossPercent: Double = 0.0,
    val bitrateLevel: String = "Medium",
    val jitterBufferDepthMs: Double = 0.0,
    val packetsRecovered: Long = 0,     // FEC
    val packetsConcealed: Long = 0,     // PLC
    val audioPacketsSent: Long = 0,
    val audioPacketsReceived: Long = 0
) {
    /**
     * Connection quality matching iOS thresholds
     */
    val quality: NetworkQuality
        get() = when {
            latencyMs < 50 && jitterMs < 20 && packetLossPercent < 1.0 -> NetworkQuality.EXCELLENT
            latencyMs < 100 && jitterMs < 40 && packetLossPercent < 3.0 -> NetworkQuality.GOOD
            latencyMs < 200 && jitterMs < 60 && packetLossPercent < 5.0 -> NetworkQuality.FAIR
            latencyMs < 400 -> NetworkQuality.POOR
            else -> NetworkQuality.VERY_POOR
        }
}
