package com.oshi.messenger.service

/**
 * The `callAnsweredElsewhere` (0x0A) body, and the only place that decides what it
 * looks like on the wire.
 *
 * Port of iOS `VoiceCallManager.swift:7566-7571` (build) and `:7061-7078` /
 * `:6430-6447` (parse). Layout:
 *
 *     [type 0x0A][timestamp BE8][deviceIdLen 1][deviceId N][callId …]
 *
 * The deviceId is the whole point of the packet. It is addressed to the user's OWN
 * public key, so the VPS fan-out delivers it back to the device that sent it; a
 * receiver that does not compare the embedded deviceId against its own hangs up the
 * call it just answered. Producer and parser live together so changing one breaks a
 * test rather than a call.
 */
object AnsweredElsewherePacket {

    const val TYPE: Byte = 0x0A

    /** Offset of `deviceIdLen`: past the type byte and the 8-byte timestamp. */
    private const val PAYLOAD_START = 9

    fun build(timestampMs: Long, deviceId: String, callId: String): ByteArray {
        val deviceIdBytes = deviceId.toByteArray(Charsets.UTF_8)
        require(deviceIdBytes.size <= 255) { "deviceId too long: ${deviceIdBytes.size}" }
        val callIdBytes = callId.toByteArray(Charsets.UTF_8)

        val out = ByteArray(PAYLOAD_START + 1 + deviceIdBytes.size + callIdBytes.size)
        out[0] = TYPE
        for (i in 0..7) out[1 + i] = (timestampMs shr (56 - i * 8)).toByte()
        out[PAYLOAD_START] = deviceIdBytes.size.toByte()
        System.arraycopy(deviceIdBytes, 0, out, PAYLOAD_START + 1, deviceIdBytes.size)
        System.arraycopy(callIdBytes, 0, out, PAYLOAD_START + 1 + deviceIdBytes.size, callIdBytes.size)
        return out
    }

    /** `null` when the packet is truncated or carries no deviceId (an old peer). */
    fun deviceIdOf(packet: ByteArray): String? {
        if (packet.isEmpty() || packet[0] != TYPE) return null
        if (packet.size <= PAYLOAD_START + 1) return null
        val len = packet[PAYLOAD_START].toInt() and 0xFF
        if (len == 0 || packet.size < PAYLOAD_START + 1 + len) return null
        return String(packet, PAYLOAD_START + 1, len, Charsets.UTF_8)
    }

    /** `null` when absent; iOS does not act on it either, it is carried for diagnostics. */
    fun callIdOf(packet: ByteArray): String? {
        if (packet.isEmpty() || packet[0] != TYPE) return null
        if (packet.size <= PAYLOAD_START + 1) return null
        val len = packet[PAYLOAD_START].toInt() and 0xFF
        val start = PAYLOAD_START + 1 + len
        if (len == 0 || packet.size <= start) return null
        return String(packet, start, packet.size - start, Charsets.UTF_8)
    }

    /**
     * The echo guard. iOS `VoiceCallManager.swift:7069-7072`: a packet whose deviceId
     * is ours is our own signal coming back and must NOT end the call.
     *
     * A packet with no deviceId at all is honoured — that is an old peer that could
     * only have sent it from another device.
     */
    fun endsThisDevicesCall(packet: ByteArray, myDeviceId: String): Boolean {
        val sender = deviceIdOf(packet) ?: return true
        return sender != myDeviceId
    }

    /**
     * The whole "should this device stop ringing" decision, for BOTH transports that
     * carry it: the sealed 0x0A packet (relay / cross-platform mesh) and the
     * `callAnsweredElsewhere` FCM push (`FCMService.kt:787-824`). Kept here, pure, so
     * the two call sites cannot drift apart and so the guards are testable off-device.
     *
     * Three guards, in the order iOS applies the ones it has:
     *  1. own-device echo — iOS `VoiceCallManager.swift:7069-7072`. The signal is
     *     addressed to our OWN identity, so the fan-out hands it back to the sender.
     *     An absent deviceId is an old peer that could only have sent it from another
     *     device, so it is honoured.
     *  2. ringing only — iOS `guard callState == .ringing || incomingCall != nil`,
     *     `VoiceCallManager.swift:7511-7514`. 0x0A is addressed to the IDENTITY, not
     *     to a call, so without this a sibling dismissing some *other* call tears down
     *     the live one on this device.
     *  3. lenient callId match — stricter than iOS, which carries the callId
     *     (`VoiceCallManager.swift:7566-7575`) but never compares it. Both siblings
     *     ring on the same offer and therefore hold the same callId, so a mismatch
     *     means the packet is about another call. Unknown on EITHER side ⇒ honour it:
     *     a false negative here leaves a phone ringing, which is the worse of the two
     *     failures.
     */
    fun dismisses(
        senderDeviceId: String?,
        myDeviceId: String,
        isRinging: Boolean,
        signalCallId: String?,
        ourCallId: String?
    ): Boolean {
        if (!senderDeviceId.isNullOrEmpty() && senderDeviceId == myDeviceId) return false
        if (!isRinging) return false
        if (!signalCallId.isNullOrEmpty() && !ourCallId.isNullOrEmpty() && signalCallId != ourCallId) return false
        return true
    }
}
