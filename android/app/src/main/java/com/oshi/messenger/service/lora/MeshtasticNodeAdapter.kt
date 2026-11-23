package com.oshi.messenger.service.lora

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Makes [MeshtasticManager] satisfy [LoRaNode].
 *
 * PORT OF OSHI/MeshtasticNodeAdapter.swift. It is a PURE ADAPTER: no radio logic,
 * no packet handling, no messaging behaviour. Everything it reports is read from
 * the manager, which keeps doing exactly what it does.
 *
 * ONE THING IT DELIBERATELY DOES NOT DO (MeshtasticNodeAdapter.swift:10-19)
 * ------------------------------------------------------------------------
 * It does not republish received messages. `MeshtasticManager` already
 * reassembles an OSHI envelope and publishes it on `receivedMessages` — the same
 * path the rest of the mesh uses, and the reason LoRa receive works at all.
 * Re-emitting through [inbound] would deliver every message TWICE. [inbound]
 * therefore exists for FUTURE firmware that hands us raw frames and stays silent
 * for Meshtastic. A contract that lies about where data flows is worse than a
 * contract with an unused member.
 *
 * HONEST CAPABILITIES (MeshtasticNodeAdapter.swift:21-28)
 * -------------------------------------------------------
 * The driver parses node info and text/OSHI packets. It does NOT parse device
 * telemetry, so battery, uptime, region and frequency are reported as null and
 * the matching capabilities are false. The settings screen renders "—" for those
 * rather than a plausible zero. Claiming otherwise would put numbers on screen
 * that no packet produced.
 */
class MeshtasticNodeAdapter(
    private val manager: MeshtasticManager,
    override val id: String,
) : LoRaNode {

    override val family: LoRaFirmwareFamily = LoRaFirmwareFamily.MESHTASTIC

    /** MeshtasticNodeAdapter.swift:46-54 — everything unproven stays false. */
    override val capabilities = LoRaNodeCapabilities(
        canConfigureRegion = false,
        canConfigureChannel = false,
        canSetTransmitPower = false,
        canReportBattery = false,
        canReportPosition = false,
        supportsOTA = false,
        supportsRelay = true,   // the mesh relays by nature; nothing to enable
    )

    /**
     * Meshtastic's frame budget is ~237 bytes; the driver chunks OSHI envelopes at
     * 180 to leave room for its own 8-byte header. Mirrored, not guessed.
     * MeshtasticNodeAdapter.swift:56-58.
     */
    override val maximumPayloadBytes: Int = LoRaEnvelope.CHUNK_BYTES

    override val status: StateFlow<LoRaNodeStatus> get() = manager.status

    private val inboundFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)

    /** Silent for Meshtastic, on purpose. See the class doc. */
    override val inbound: SharedFlow<ByteArray> = inboundFlow.asSharedFlow()

    override suspend fun connect() {
        if (!manager.bluetoothOn.value) {
            throw if (manager.permissionDenied.value) LoRaNodeError.BluetoothPermissionDenied
            else LoRaNodeError.BluetoothUnavailable
        }
        // The driver reconnects to the paired radio by itself once scanning
        // starts; this adapter does not second-guess that logic.
        // MeshtasticNodeAdapter.swift:163-165.
        manager.startScan()
    }

    override fun disconnect() = manager.disconnect(forget = false)

    /**
     * ⚠️ Meshtastic's OSHI path is ENVELOPE-shaped: the driver owns the chunking,
     * the 0x4F 0x4D magic and the reassembly on the far side. There is no
     * supported way to inject an arbitrary blob through it without duplicating
     * that framing — and duplicating it is how the two ends stop agreeing. So this
     * throws instead of half-working, and callers keep using
     * `queueOrSendSecureMessage`. MeshtasticNodeAdapter.swift:174-191.
     */
    override suspend fun send(payload: ByteArray) {
        if (payload.size > maximumPayloadBytes) {
            throw LoRaNodeError.PayloadTooLarge(payload.size, maximumPayloadBytes)
        }
        throw LoRaNodeError.CapabilityUnsupported("lora.error.raw_frames")
    }
}
