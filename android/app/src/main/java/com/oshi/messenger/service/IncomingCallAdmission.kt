package com.oshi.messenger.service

/**
 * What to do with an incoming call offer, given the call this device already holds.
 * Pure (no Android types) so every branch is covered by `IncomingCallAdmissionTest`.
 *
 * __STALE_RING_2026_09_23__ Before this, `EnhancedCallManager.handleIncomingCall` dropped
 * EVERY offer while `_callState != IDLE`, silently. Two failures came out of that one line:
 *
 *  1. **A stale ring blocked the phone.** Once a RINGING state outlived its caller (a lost
 *     `callEnd`, or a cancel swallowed by `endCall`'s `isEndingCall` guard, see below),
 *     every later call was refused with "already in call state: RINGING" — logged, never
 *     shown — until something else happened to clear it. The caller of each refused call
 *     heard ringback for 45 s and got "no answer".
 *  2. **A genuinely busy phone never said so.** iOS answers a call that arrives while it is
 *     in another call with `callDecline` (`VoiceCallManager.swift:6737-6742`,
 *     `declineIncomingCall(from:)` :7880), so the caller stops within a second. Android
 *     sent nothing.
 *
 * The rule here: a ring older than its own watchdog is dead and may be REPLACED; a live
 * call with someone else gets a BUSY decline; the same caller re-dialling replaces its own
 * earlier ring (a caller only ever has one outgoing call); everything else is a duplicate
 * or a teardown in progress and is left alone, exactly as before.
 */
object IncomingCallAdmission {

    enum class State { IDLE, RINGING, CONNECTING, IN_CALL, ENDED }

    enum class Decision {
        /** Nothing in the way — ring. */
        ACCEPT,
        /** The current ring is dead (older than its watchdog, or the same caller re-dialled). End it as missed, then ring. */
        REPLACE_STALE,
        /** Another re-delivery of the call we already hold, or an offer from our own peer mid-call. Ignore. */
        DUPLICATE,
        /** A live call with a DIFFERENT peer. Send `callDecline` (0x03) back, as iOS does, and keep our call. */
        BUSY,
        /** Our previous call is still tearing down. Ignore; the caller retries or times out. */
        TEARDOWN,
    }

    /**
     * @param ringStartedAtMs when the call we hold started ringing (`CurrentCall.startTime`).
     * @param calleeTimeoutMs the incoming-ring watchdog (55 s, iOS `calleeRingWatchdog`).
     * @param callerTimeoutMs the outgoing no-answer watchdog (45 s).
     */
    fun decide(
        state: State,
        currentIsOutgoing: Boolean,
        currentPeer: String?,
        currentCallId: String?,
        ringStartedAtMs: Long?,
        nowMs: Long,
        newPeer: String,
        newCallId: String?,
        calleeTimeoutMs: Long,
        callerTimeoutMs: Long,
    ): Decision {
        if (state == State.IDLE) return Decision.ACCEPT
        // No call object behind a non-idle state is itself a leaked state: nothing to protect.
        if (currentPeer == null) return if (state == State.ENDED) Decision.TEARDOWN else Decision.REPLACE_STALE

        val samePeer = currentPeer == newPeer
        val sameCall = !newCallId.isNullOrEmpty() && newCallId == currentCallId

        return when (state) {
            State.ENDED -> Decision.TEARDOWN
            State.CONNECTING, State.IN_CALL ->
                if (samePeer) Decision.DUPLICATE else Decision.BUSY
            State.RINGING -> {
                if (sameCall) return Decision.DUPLICATE
                val limit = if (currentIsOutgoing) callerTimeoutMs else calleeTimeoutMs
                val stale = ringStartedAtMs != null && nowMs - ringStartedAtMs > limit
                when {
                    stale -> Decision.REPLACE_STALE
                    // Outgoing ring + offer from the SAME peer is glare; the existing
                    // glare handling owns it, so leave it exactly as it was.
                    currentIsOutgoing && samePeer -> Decision.DUPLICATE
                    currentIsOutgoing -> Decision.BUSY
                    // Same caller, a DIFFERENT callId: they hung up and re-dialled. Their
                    // old call is gone even if its callEnd never reached us.
                    samePeer && !newCallId.isNullOrEmpty() -> Decision.REPLACE_STALE
                    samePeer -> Decision.DUPLICATE
                    else -> Decision.BUSY
                }
            }
            State.IDLE -> Decision.ACCEPT
        }
    }
}
