package com.oshi.messenger.service

import com.oshi.messenger.network.vps.CallSignalType

/**
 * __CROSS_CALL_GUARD_2026_11_21__ Which incoming call signals belong to a DIFFERENT call
 * than the one this device holds, and so must not touch it.
 *
 * ## Why (production evidence, call server journal 21/11/2026)
 *
 * `EnhancedCallManager.handleCallSignal` acted on `CALL_ACCEPT` / `CALL_REJECT` /
 * `CALL_END` without looking at their callId — `handleCallRejected()` and
 * `handleCallEnded()` take no callId at all, and the only protection was a 3-second
 * window after the ring started. A late signal from the PREVIOUS call with the same
 * person therefore ended (or "accepted") the new one:
 *
 *  - 12ba9315: an iPhone (1.0.43) re-sent `callEnd` for call d5e5983d, seven minutes
 *    old, while this phone was ringing it again. The relay forwarded it over the
 *    WebSocket at 12:27:42; this phone ended the new call at 12:27:43 — the iPhone had
 *    answered at 12:27:36.
 *  - ab49980b: the callee's `callDeclined` for the call just cancelled (db967e19)
 *    reached the caller in the very second it dialled again; the callee then answered
 *    and talked for 21 s to a caller that had already given up (one-way, 426 frames
 *    parked by the relay).
 *
 * iOS has had exactly this guard for terminal signals since 2026-07-14
 * (`VoiceCallManager.swift` "Ignoring <type> for foreign callId"); desktop refuses all
 * three as `FOREIGN_CALL` (`CallStateMachine.kt`). Android was the only client without it.
 *
 * Accept is included: an accept for another call can never legitimately connect this one
 * (glare resolves by the loser ACCEPTING THE OTHER callId, which becomes the current call
 * before any accept for it can arrive).
 *
 * Comparison is lenient on purpose — trimmed of NUL/control/whitespace (old iOS builds
 * leaked a trailing `\u0000` into the callId) and case-insensitive — and an empty callId
 * on either side is never "foreign": a legacy signal with no id keeps today's behaviour.
 */
object CallSignalGuards {

    private val GUARDED = setOf(
        CallSignalType.CALL_ACCEPT,
        CallSignalType.CALL_REJECT,
        CallSignalType.CALL_END,
    )

    fun normalizeCallId(id: String?): String =
        id.orEmpty().trim { it.isWhitespace() || it.isISOControl() }.lowercase()

    /** True when [signalCallId] names a call other than [activeCallId] and the signal must be dropped. */
    fun isForeignCallSignal(type: CallSignalType, signalCallId: String?, activeCallId: String?): Boolean {
        if (type !in GUARDED) return false
        val incoming = normalizeCallId(signalCallId)
        val active = normalizeCallId(activeCallId)
        if (incoming.isEmpty() || active.isEmpty()) return false
        return incoming != active
    }

    /**
     * __AUTO_ACCEPT_AUTHENTICATED_2026_11_21__ May a media frame stand in for the peer's
     * accept? Only one that DECRYPTED under this call's session key (the caller generates
     * a fresh key per call, so a straggler from the previous call, or anything not from
     * the peer, cannot), from the peer we are calling, while our outgoing call rings.
     * Before this, the first packet of ANY kind — undecrypted, from any sender — moved
     * the caller to IN_CALL, which is how a caller ends up "connected" to nobody.
     */
    fun mayAutoAcceptFromMedia(
        ringingOutgoing: Boolean,
        alreadyAutoAccepted: Boolean,
        decryptedUnderSessionKey: Boolean,
        senderKey: String?,
        peerKey: String?,
    ): Boolean {
        if (!ringingOutgoing || alreadyAutoAccepted || !decryptedUnderSessionKey) return false
        if (senderKey.isNullOrEmpty() || peerKey.isNullOrEmpty()) return true
        return CallAdmissionPolicy.normalizeKey(senderKey) == CallAdmissionPolicy.normalizeKey(peerKey)
    }

    /** Size of the legacy bare hang-up: `[0x04][sender wall-clock ms BE8]`, nothing else. */
    const val BARE_CALL_END_SIZE = 9

    /**
     * __BARE_CALLEND_ATTESTED_2026_11_21__ May a bare `0x04` that arrived on the MEDIA
     * path end the current call?
     *
     * `handleAudioData` used to end the call on ANY media-path packet whose first byte
     * was `0x04`. Unlike every other media frame that byte is outside any encryption and
     * the packet carries no callId, so a straggler from the previous call with the same
     * person, or a 0x04-led packet from anyone who can reach a media endpoint (P2P socket,
     * mesh bridge, a polled AUDIO_DATA signal), hung up a live call.
     *
     * Who sends it: only Android (`endCall` "Path 1", `[0x04][ts]` = 9 bytes over the call
     * WebSocket). iOS and desktop never put 0x04 on the media path; their media-path
     * hang-up is the SEALED in-band `0x0D`, which Android also sends and which decrypts
     * under this call's session key (handled separately). Every current Android receiver
     * drops WS/HTTP audio of <= 9 bytes, so the bare packet can only reach us when the
     * relay forwards it to our UDP relay endpoint.
     *
     * So it is honoured only when it is exactly that legacy shape AND the call relay
     * attests it: the frame came through the per-call, token-gated UDP relay, stamped
     * with THIS call's id and THIS call's peer as sender, while a call is live. Anything
     * else is dropped and the hang-up is left to the signalled `callEnd` (sealed, callId
     * guarded) and the sealed in-band `0x0D` that every sender also emits.
     */
    fun mayEndFromBareMediaCallEnd(
        packetSize: Int,
        callLive: Boolean,
        attestedSender: String?,
        attestedCallId: String?,
        peerKey: String?,
        activeCallId: String?,
    ): Boolean {
        if (!callLive || packetSize != BARE_CALL_END_SIZE) return false
        val frameCall = normalizeCallId(attestedCallId)
        val active = normalizeCallId(activeCallId)
        if (frameCall.isEmpty() || active.isEmpty() || frameCall != active) return false
        if (attestedSender.isNullOrBlank() || peerKey.isNullOrBlank()) return false
        return CallAdmissionPolicy.normalizeKey(attestedSender) == CallAdmissionPolicy.normalizeKey(peerKey)
    }
}
