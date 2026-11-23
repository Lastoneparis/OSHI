package com.oshi.messenger.service.lora

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * LoRa exposed as one more transport, and nothing else changed.
 *
 * PORT OF OSHI/LoRaTransport.swift. The constraint that file respects is
 * respected here: encryption, message routing, the Bluetooth/Wi-Fi meshes and the
 * internet fallback are NOT touched. What was missing was a SEAT AT THE TABLE for
 * LoRa — a transport the rest of the app can reason about, measured quality per
 * transport, and one place that answers "can LoRa carry this right now".
 *
 * `TransportKind` DESCRIBES the transports that already exist rather than
 * replacing them. Only LoRa is implemented against [MessageTransport], because
 * only LoRa is new; retrofitting the others would mean rewriting working delivery
 * code. LoRaTransport.swift:16-23.
 */

/** LoRaTransport.swift:35-63. The order is DESCRIPTIVE — changing it changes no routing. */
enum class TransportKind(val preferenceRank: Int) {
    BLUETOOTH_MESH(0),
    WIFI_MESH(1),
    LORA(2),
    INTERNET_RELAY(3),
}

/**
 * Rolling, MEASURED health of one transport. Diagnostics only — nothing here
 * feeds a routing decision, by design. LoRaTransport.swift:69-115.
 */
data class TransportQuality(
    val packetsSent: Int = 0,
    val packetsReceived: Int = 0,
    val failures: Int = 0,
    val retries: Int = 0,
    /** Seconds, exponentially smoothed. */
    val averageLatency: Double? = null,
    val lastRssi: Int? = null,
    val lastSnr: Double? = null,
    /** Unix millis. */
    val lastActivity: Long? = null,
) {
    /**
     * Accepted-for-transmission rate. NULL rather than 100 % before anything has
     * been sent — a fresh transport is UNMEASURED, not perfect. :84-88
     */
    val successRate: Double?
        get() {
            val attempts = packetsSent + failures
            return if (attempts > 0) packetsSent.toDouble() / attempts else null
        }

    companion object {
        /**
         * Chosen so a single slow packet NUDGES the average instead of redefining
         * it, while a genuine change still shows within a handful of samples. :93
         */
        const val LATENCY_ALPHA = 0.25
    }

    fun recordSent(latencySeconds: Double?): TransportQuality = copy(
        packetsSent = packetsSent + 1,
        lastActivity = System.currentTimeMillis(),
        averageLatency = when {
            latencySeconds == null -> averageLatency
            averageLatency == null -> latencySeconds
            else -> averageLatency + LATENCY_ALPHA * (latencySeconds - averageLatency)
        },
    )

    fun recordReceived(rssi: Int? = null, snr: Double? = null): TransportQuality = copy(
        packetsReceived = packetsReceived + 1,
        lastActivity = System.currentTimeMillis(),
        lastRssi = rssi ?: lastRssi,
        lastSnr = snr ?: lastSnr,
    )

    fun recordFailure(): TransportQuality =
        copy(failures = failures + 1, lastActivity = System.currentTimeMillis())

    fun recordRetry(): TransportQuality = copy(retries = retries + 1)
}

/** A way to move opaque ciphertext between devices. LoRaTransport.swift:125-142. */
interface MessageTransport {
    val kind: TransportKind

    /** True when this transport could carry something RIGHT NOW. Never throws to say "not now". */
    val isAvailable: Boolean

    val quality: StateFlow<TransportQuality>

    val maximumPayloadBytes: Int

    /** Success means ACCEPTED for transmission, not delivered — see [LoRaNode.send]. */
    suspend fun send(payload: ByteArray)
}

/**
 * A thin, testable seam over whichever node is currently active: it owns no radio
 * logic, so the Meshtastic implementation and a future OSHI firmware are
 * interchangeable underneath it. LoRaTransport.swift:151-228.
 */
class LoRaTransport(private val nodeProvider: () -> LoRaNode?) : MessageTransport {

    override val kind = TransportKind.LORA

    private val _quality = MutableStateFlow(TransportQuality())
    override val quality: StateFlow<TransportQuality> = _quality.asStateFlow()

    override val isAvailable: Boolean
        get() {
            val node = nodeProvider() ?: return false
            return node.status.value.health >= LoRaNodeHealth.DEGRADED
        }

    /**
     * Falls back to the smallest sensible frame when no node is attached, so a
     * caller chunking against this value can never produce a payload the radio
     * rejects once one appears. :181-184
     */
    override val maximumPayloadBytes: Int
        get() = nodeProvider()?.maximumPayloadBytes ?: LoRaEnvelope.CHUNK_BYTES

    override suspend fun send(payload: ByteArray) {
        val node = nodeProvider() ?: run {
            _quality.value = _quality.value.recordFailure()
            throw LoRaNodeError.NotConnected
        }
        if (node.status.value.health < LoRaNodeHealth.DEGRADED) {
            _quality.value = _quality.value.recordFailure()
            throw LoRaNodeError.NotConnected
        }
        if (payload.size > node.maximumPayloadBytes) {
            _quality.value = _quality.value.recordFailure()
            throw LoRaNodeError.PayloadTooLarge(payload.size, node.maximumPayloadBytes)
        }
        val started = System.currentTimeMillis()
        try {
            node.send(payload)
            _quality.value = _quality.value.recordSent((System.currentTimeMillis() - started) / 1000.0)
        } catch (e: Throwable) {
            _quality.value = _quality.value.recordFailure()
            throw e
        }
    }
}
