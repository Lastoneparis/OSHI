package com.oshi.messenger.service.lora

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The vendor-neutral contract every LoRa node implementation satisfies.
 *
 * REFERENCE: OSHI/LoRaNodeProtocol.swift (whole file). Ported one-for-one so the
 * two platforms describe a radio the same way; only the reactive plumbing
 * differs (Combine publishers → Kotlin flows) and the identifier type differs
 * (CoreBluetooth hands out a UUID, Android a MAC-shaped address string).
 *
 * A node here is a RADIO. The payload handed to [LoRaNode.send] is already
 * ciphertext produced by the existing pipeline; an implementation that tried to
 * interpret it would be wrong. LoRaNodeProtocol.swift:14-21.
 */

/** Which firmware family an implementation speaks. LoRaNodeProtocol.swift:40-47 */
enum class LoRaFirmwareFamily { MESHTASTIC, OSHI, UNKNOWN }

/**
 * One value the UI can render without interpreting anything.
 * Declared worst→best so `ordinal` comparison means "worse".
 * LoRaNodeProtocol.swift:94-105 (`LoRaNodeHealth: Int, Comparable`).
 */
enum class LoRaNodeHealth { DISCONNECTED, CONNECTING, DEGRADED, HEALTHY }

/**
 * What a node can actually do. Never assumed — a T-Echo has a screen and a
 * Heltec V3 does not. LoRaNodeProtocol.swift:52-86.
 */
data class LoRaNodeCapabilities(
    val canConfigureRegion: Boolean = false,
    val canConfigureChannel: Boolean = false,
    val canSetTransmitPower: Boolean = false,
    val canReportBattery: Boolean = false,
    val canReportPosition: Boolean = false,
    val supportsOTA: Boolean = false,
    val supportsRelay: Boolean = false,
) {
    companion object {
        /**
         * What Meshtastic 2.x over BLE gives us. OTA is false on purpose: it is
         * not implemented, and claiming it would put a button on screen that
         * cannot work. LoRaNodeProtocol.swift:79-85.
         */
        val MESHTASTIC_2X = LoRaNodeCapabilities(
            canConfigureRegion = true,
            canConfigureChannel = true,
            canSetTransmitPower = true,
            canReportBattery = true,
            canReportPosition = true,
            supportsOTA = false,
            supportsRelay = true,
        )
    }
}

/**
 * Everything the LoRa Node screen shows, in one snapshot.
 *
 * Deliberately all-optional for the MEASURED values: a node that has not yet
 * reported its battery renders "—", never 0 %. The difference between "unknown"
 * and "empty" is the difference between a blank field and a false alarm.
 * LoRaNodeProtocol.swift:107-161.
 */
data class LoRaNodeStatus(
    val health: LoRaNodeHealth = LoRaNodeHealth.DISCONNECTED,
    val displayName: String = "",
    val firmwareVersion: String? = null,
    /** 0…1. Null until the node reports it. */
    val batteryLevel: Double? = null,
    val isCharging: Boolean? = null,
    /** BLE signal to the phone, dBm. */
    val bluetoothRssi: Int? = null,
    /** Last LoRa packet's signal, dBm, and signal-to-noise ratio, dB. */
    val loRaRssi: Int? = null,
    val loRaSnr: Double? = null,
    val region: String? = null,
    val frequencyMhz: Double? = null,
    val uptimeSeconds: Long? = null,
    /** Unix millis. */
    val lastPacketAt: Long? = null,
    /** A sentence to show when the node is reachable but needs attention. */
    val attention: String? = null,
)

/**
 * A node seen while scanning, before any connection exists.
 * LoRaNodeProtocol.swift:166-195.
 *
 * DIVERGENCE: `id` is a String, not a UUID. CoreBluetooth invents a per-install
 * UUID for a peripheral; Android identifies a remote device by its address, and
 * that address is what `BluetoothAdapter.getRemoteDevice` needs to reconnect.
 */
data class DiscoveredLoRaNode(
    val id: String,
    val name: String,
    val rssi: Int,
    val family: LoRaFirmwareFamily,
    /** True when this node is already paired and we are only re-seeing it. */
    val isKnown: Boolean = false,
) {
    /**
     * Four buckets, because "-73 dBm" means nothing to the person holding the
     * phone and "Good" does. LoRaNodeProtocol.swift:187-194.
     */
    val signalBars: Int
        get() = when {
            rssi >= -60 -> 4
            rssi >= -70 -> 3
            rssi >= -85 -> 2
            else -> 1
        }
}

/** LoRaNodeProtocol.swift:199-224. */
sealed class LoRaNodeError : Exception() {
    object BluetoothUnavailable : LoRaNodeError()
    object BluetoothPermissionDenied : LoRaNodeError()
    object NotConnected : LoRaNodeError()

    /** The payload exceeds what the radio can carry after framing. */
    data class PayloadTooLarge(val bytes: Int, val limit: Int) : LoRaNodeError()
    data class CapabilityUnsupported(val what: String) : LoRaNodeError()
    object TimedOut : LoRaNodeError()
    data class FirmwareRejected(val why: String) : LoRaNodeError()
    data class Underlying(val why: String) : LoRaNodeError()
}

/**
 * A LoRa radio OSHI can talk to. Implementations own exactly ONE physical node.
 * LoRaNodeProtocol.swift:233-265.
 */
interface LoRaNode {

    /** Stable identity of the physical node, for persistence and reconnection. */
    val id: String

    val family: LoRaFirmwareFamily
    val capabilities: LoRaNodeCapabilities

    /** Current state, republished whenever anything in it changes. */
    val status: StateFlow<LoRaNodeStatus>

    /**
     * Ciphertext arriving from the mesh, exactly as the radio delivered it.
     * Subscribers inject it into the EXISTING receive pipeline; nothing here
     * parses or decrypts it.
     */
    val inbound: SharedFlow<ByteArray>

    /**
     * Largest single payload this node will carry after its own framing.
     * Callers chunk against this; they do not guess it.
     */
    val maximumPayloadBytes: Int

    suspend fun connect()
    fun disconnect()

    /**
     * Hand opaque ciphertext to the radio.
     *
     * Returning normally means the node ACCEPTED the bytes for transmission —
     * not that anything received them. LoRa has no delivery guarantee and this
     * API does not pretend otherwise.
     */
    suspend fun send(payload: ByteArray)
}
