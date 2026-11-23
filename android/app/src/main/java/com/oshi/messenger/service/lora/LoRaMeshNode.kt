package com.oshi.messenger.service.lora

import kotlin.math.pow

/**
 * A node heard on the LoRa mesh. Port of `MeshtasticNode`
 * (OSHI/MeshtasticManager.swift:88-148).
 *
 * Every measured value that a bare board may never report is NULLABLE. Most
 * boards have no GPS (a Heltec V3 has none), so `latitude`/`longitude` stay null
 * for them for ever and the UI must cope rather than showing 0 km.
 */
data class LoRaMeshNode(
    val num: UInt,
    /** "!a1b2c3d4" */
    val userId: String = "",
    val longName: String = "",
    val shortName: String = "",
    /** Unix millis. */
    val lastHeard: Long = 0L,
    val snr: Float = 0f,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Radio hops away. 0 = heard directly, null = not reported. :100-101 */
    val hopsAway: Int? = null,
    /**
     * Signal strength of the last packet heard FROM this node, dBm. Null until we
     * have actually heard a packet — NodeInfo alone does not carry it. :102-104
     */
    val rssi: Int? = null,
) {
    val hasPosition: Boolean get() = latitude != null && longitude != null

    /**
     * A distance estimated from signal strength, for the majority of nodes with
     * no GPS. Port of `estimatedRangeKm` (MeshtasticManager.swift:110-147).
     *
     * Genuinely approximate: the same RSSI means a very different distance across
     * open water than through a town. It therefore returns a RANGE, computed with
     * a path-loss exponent from 2.4 (open-ish ground) to 3.2 (built-up), and the
     * UI must label it as an estimate. A single number would be false precision
     * and someone might act on it in the field.
     *
     * Assumes ~22 dBm transmit and ~2 dBi at each antenna; a node running lower
     * power or a worse antenna reads as further away than it is.
     *
     * @return (minKm, maxKm), or null when the model stops saying anything worth
     *         reading — past roughly -105 dBm the band opens to tens of
     *         kilometres, and "14 km – 470 km" is worse than showing nothing.
     */
    val estimatedRangeKm: Pair<Double, Double>?
        get() {
            val r = rssi ?: return null
            if (r >= 0 || r <= -140) return null                 // :125
            val eirp = 22.0 + 2.0 + 2.0                          // :126
            val lossAtOneMetre = 31.2                            // :127
            val budget = eirp - lossAtOneMetre - r.toDouble()    // :128
            if (budget <= 0) return null                         // :129

            fun metres(n: Double) = 10.0.pow(budget / (10.0 * n))
            // Larger exponent = more obstruction = the SAME signal implies a
            // SHORTER distance, so 3.2 gives the near end of the band. :132-136
            val near = metres(3.2) / 1000.0
            val far = metres(2.4) / 1000.0
            if (!near.isFinite() || !far.isFinite()) return null  // :138
            if (far > 20) return null                             // :145
            return minOf(near, far) to maxOf(near, far)
        }
}

/** One line in the live traffic list. MeshtasticManager.swift:150-155 */
data class LoRaEvent(
    /** "→" sent, "←" received. */
    val direction: String,
    val summary: String,
    val atMillis: Long = System.currentTimeMillis(),
    val id: String = java.util.UUID.randomUUID().toString(),
)

/**
 * Why the last message OSHI tried to put on the radio did not go.
 * MeshtasticManager.swift:226-260.
 *
 * Every refusal path used to return false after writing a log line, and a log
 * nobody can read on a phone is not a surface: the user saw a message fail with
 * no reason given, on the one feature they bought hardware for — and three of the
 * four reasons are things they could have fixed in ten seconds had anyone told
 * them (:213-222).
 */
enum class LoRaSendRefusal {
    NOT_CONNECTED,
    RADIO_MUTED,
    NO_REGION,
    TOO_LARGE,
    CARRIES_MEDIA;

    /**
     * A stable key the UI maps to a localized string. Deliberately NOT English
     * prose: `res/values*` belongs to the settings/UI agent, and a driver that
     * hard-codes user-facing text ships an untranslatable app.
     */
    val stringKey: String
        get() = when (this) {
            NOT_CONNECTED -> "lora.error.not_connected"
            RADIO_MUTED -> "lora.status.tx_off"
            NO_REGION -> "lora.status.needs_region"
            TOO_LARGE -> "lora.blocked.too_large"
            CARRIES_MEDIA -> "lora.blocked.media"
        }

    val detailStringKey: String
        get() = when (this) {
            NOT_CONNECTED -> "lora.status.searching.detail"
            RADIO_MUTED -> "lora.status.tx_off.detail"
            NO_REGION -> "lora.status.needs_region.detail"
            TOO_LARGE -> "lora.blocked.too_large.detail"
            CARRIES_MEDIA -> "lora.blocked.media.detail"
        }
}

// `LoRaBluetoothPairing` (MeshtasticManager.swift:640-657) lives in LoRaRegion.kt,
// which the LoRa settings work stream owns. It is not redeclared here.

/** An OSHI envelope that came off the radio, with the node that put it there. */
data class LoRaReceivedMessage(
    val message: LoRaWireMessage,
    /**
     * The ORIGINAL sender in mesh.proto — it survives relaying, so it identifies
     * the node that composed the packet, not the last hop.
     * MeshtasticManager.swift:1098-1104.
     */
    val nodeNum: UInt,
    val frames: Int,
    /**
     * False when [nodeNum] is not a Meshtastic node number — an envelope that came off a MeshCore
     * radio carries a MeshCore-derived id, and binding it would file a Meshtastic node's plain text
     * under the wrong contact. The pipeline skips the node→identity binding then.
     */
    val bindsNode: Boolean = true,
)

/**
 * Plain text from ANY Meshtastic client (the official app included).
 * MeshtasticManager.swift:999-1056.
 *
 * A raw TEXT_MESSAGE_APP packet carries NO identity — a node number and a string,
 * nothing else. `conversationKey` is decided by `LoRaNodeIdentityMap`, never
 * guessed here.
 */
data class LoRaInteropText(
    val text: String,
    val nodeNum: UInt,
    /** The conversation this belongs in: a bound OSHI key, or "lora!<nodehex>". */
    val conversationKey: String,
    /** What to call the sender on screen. */
    val displayName: String,
    /** True when the node has previously PROVED an OSHI identity. */
    val isKnownIdentity: Boolean,
    /**
     * __A_CHANNEL_BROADCAST_IS_NOT_A_PRIVATE_MESSAGE_2026_11_04__ True when this
     * went to the whole channel rather than to us alone (`MeshPacket.to` was the
     * broadcast address).
     *
     * A public channel is readable by every radio in range — the default key is
     * published in Meshtastic's own source — so a broadcast shown as an ordinary
     * private thread invites exactly the wrong reply. The UI must say which it is.
     */
    val isBroadcast: Boolean = false,
    /**
     * The node's own channel-slot index this arrived on: 0 for the primary, 1-7
     * for a secondary. Resolve it against `MeshtasticManager.nodeChannels` for a
     * name — the index is local to the node and means nothing on its own.
     */
    val channelIndex: Int = 0,
    /**
     * The name of the slot it arrived on, as the NODE reports it, or empty for
     * the stock unnamed primary.
     *
     * Resolved here rather than by the receiver because only the radio layer
     * holds the node's channel table, and the index alone is meaningless off
     * this device — see `LoRaChannelThread.keyFor`.
     */
    val channelName: String = "",
)

/**
 * Meshtastic's own definition of "online": heard within the last 2 hours.
 * Pure, so the top-bar count is unit-testable. iOS: `MeshtasticManager.onlineNodeCount`.
 */
object LoRaOnline {
    const val WINDOW_MS: Long = 2 * 60 * 60 * 1000L

    fun count(nodes: List<LoRaMeshNode>, myNodeNum: UInt, now: Long): Int =
        nodes.count { it.num != myNodeNum && it.lastHeard > 0 && now - it.lastHeard < WINDOW_MS }
}
