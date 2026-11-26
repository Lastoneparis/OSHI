package com.oshi.messenger.service

/**
 * __LATE_ACCEPT_2026_11_23__ An Accept that arrives after the call is already over.
 *
 * Call ea1fac0f (22/11): an Android 1.6.99 callee tapped Accept one second after the
 * caller's ring timed out. Nothing on the accept path asked whether the call still
 * existed, so the phone went to IN_CALL and streamed 358 frames to nobody until the
 * 45 s silence backstop. Worse, a `callEnd` for that very call arriving while the
 * callee was CONNECTING was thrown away by [EnhancedCallManager.endCall]'s "stale
 * signal" guard — written before [CallSignalGuards] refused end signals of OTHER calls,
 * so it could not tell this call's real end from an old one.
 *
 * Pure (no Android types) so every case is exercised on the JVM by `CallStreamGuardsTest`.
 */
object LateAcceptPolicy {

    /** What the accept path does next. */
    enum class Verdict { PROCEED, CALL_OVER }

    /** `type` values meaning the call is finished (iOS / desktop / server vocabulary). */
    private val END_TYPES = setOf(
        "callend", "call_end", "callcancelled", "callcanceled", "calltimeout",
        "callmissed", "hangup", "end"
    )

    /** `state` values meaning the call is finished. */
    private val ENDED_STATES = setOf("ended", "terminal", "cancelled", "canceled", "timeout", "missed")

    /**
     * Does the call server's reply to our `callAccept` POST say the call is over?
     *
     * Contract for the server (`/call-signal`, call_server.js; side 3 of the 23/11 ranking,
     * "send callEnd on a late accept"), any of:
     *  - HTTP **410 Gone**;
     *  - 2xx JSON with `"callEnded": true` (or `"ended": true`);
     *  - 2xx JSON with `"state"` / `"callState"` in [ENDED_STATES];
     *  - 2xx JSON with `"type"` in [END_TYPES] (the server echoing a callEnd back).
     * Today's server answers `{success:true, delivery:…}`: none of these, so PROCEED and
     * nothing changes until the server side ships. A body that is not JSON is not proof.
     */
    fun acceptReplySaysEnded(httpCode: Int, body: String?): Boolean {
        if (httpCode == 410) return true
        if (httpCode !in 200..299 || body.isNullOrBlank()) return false
        val json = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return false
        if (json.optBoolean("callEnded", false) || json.optBoolean("ended", false)) return true
        val state = (json.optString("state", "").ifEmpty { json.optString("callState", "") }).lowercase()
        if (state in ENDED_STATES) return true
        val type = json.optString("type", "").lowercase()
        return type in END_TYPES
    }

    /**
     * A `callEnd` that names THIS call (exact id, both non-empty). Only such an end may
     * cut through the CONNECTING / just-connected guards: an end with no id could still
     * be the previous call's straggler, and an end with another id is already dropped
     * by [CallSignalGuards.isForeignCallSignal].
     */
    fun isEndForThisCall(signalCallId: String?, activeCallId: String?): Boolean {
        val incoming = CallSignalGuards.normalizeCallId(signalCallId)
        val active = CallSignalGuards.normalizeCallId(activeCallId)
        return incoming.isNotEmpty() && incoming == active
    }

    /**
     * May the accept path go on and start media?
     *
     * @param serverSaysEnded [acceptReplySaysEnded] on the accept POST.
     * @param remoteEndForThisCall a callEnd for this exact callId was received.
     * @param callIdRecentlyEnded the id is in the recently-ended set (we already tore it down).
     * @param stillThisCall the manager's current call is still the one being accepted.
     * @param endingOrEnded the manager is ending / has ended a call (endCall ran).
     */
    fun verdict(
        serverSaysEnded: Boolean,
        remoteEndForThisCall: Boolean,
        callIdRecentlyEnded: Boolean,
        stillThisCall: Boolean,
        endingOrEnded: Boolean,
    ): Verdict =
        if (serverSaysEnded || remoteEndForThisCall || callIdRecentlyEnded || !stillThisCall || endingOrEnded) {
            Verdict.CALL_OVER
        } else {
            Verdict.PROCEED
        }
}

/**
 * __EGRESS_STALL_2026_11_23__ Stop streaming to nobody.
 *
 * When this phone has NO way to get media out — the call WebSocket is down, refused
 * (4403, register auth) or clogged (OkHttp's send queue keeps growing), the :8089 UDP
 * relay refused the call's token or has not answered for longer than its heartbeat,
 * the HTTP fallback has not accepted a frame lately, and P2P is not carrying — AND nothing has come in from the peer either, the call is
 * dead. Before this the only end was [PeerSilencePolicy]'s 45 s backstop, while the
 * microphone and the camera kept producing frames that went nowhere.
 *
 * Both conditions, never one: a peer that is muted (no inbound) on a working uplink is
 * a call; an uplink hiccup while the peer's voice still arrives is a call. The UDP
 * relay only counts as silent after [UDP_SILENT_MS] (1.5 heartbeats), so a lost
 * heartbeat or a Wi-Fi→LTE handover (WS reconnects in a few seconds) never starts the
 * clock; then [STALL_MS] more of both. On a total network loss that is ≥ 23 s, before
 * the 45 s backstop, after the 10 s "reconnecting" warning.
 *
 * Pure, so the boundaries are tested on the JVM (`CallStreamGuardsTest`).
 */
object EgressStallPolicy {

    /** Egress blocked AND no inbound media for this long ⇒ end the call ("Connection lost"). */
    const val STALL_MS = 8_000L

    /** The UDP relay answers each 10 s register heartbeat; silent this long = not carrying. */
    const val UDP_SILENT_MS = 15_000L

    /** OkHttp WebSocket send queue this deep (bytes) = frames are piling up, not leaving. */
    const val WS_QUEUE_CLOGGED_BYTES = 64L * 1024L

    /** The HTTP fallback (POST /audio) counts as carrying if it accepted a frame this recently. */
    const val HTTP_FRESH_MS = 4_000L

    /**
     * Is there NO carrier able to take our frames right now?
     *
     * @param msSinceUdpReply time since the relay last sent us anything; null = never.
     * @param msSinceHttpOk time since the HTTP fallback last accepted a frame; null = never.
     */
    fun egressBlocked(
        p2pCarrying: Boolean,
        udpRegistered: Boolean,
        udpAuthRefused: Boolean,
        msSinceUdpReply: Long?,
        wsConnected: Boolean,
        wsAuthRefused: Boolean,
        wsQueuedBytes: Long,
        msSinceHttpOk: Long? = null,
    ): Boolean {
        if (p2pCarrying) return false
        val udpCarrying = udpRegistered && !udpAuthRefused &&
            msSinceUdpReply != null && msSinceUdpReply in 0 until UDP_SILENT_MS
        if (udpCarrying) return false
        val wsCarrying = wsConnected && !wsAuthRefused && wsQueuedBytes < WS_QUEUE_CLOGGED_BYTES
        if (wsCarrying) return false
        val httpCarrying = msSinceHttpOk != null && msSinceHttpOk in 0 until HTTP_FRESH_MS
        return !httpCarrying
    }

    /** End the call? Both clocks must have run [STALL_MS]. */
    fun shouldEnd(msEgressBlocked: Long, msSinceInbound: Long): Boolean =
        msEgressBlocked >= STALL_MS && msSinceInbound >= STALL_MS

    /** How long egress has been blocked without a break. One per call; [reset] at call start. */
    class Tracker {
        /** -1 = not blocked (0 is a valid instant on a test clock). */
        private var blockedSinceMs = -1L

        /** Feed the current [blocked] state; returns how long it has been blocked (0 if not). */
        fun update(nowMs: Long, blocked: Boolean): Long {
            if (!blocked) {
                blockedSinceMs = -1L
                return 0L
            }
            if (blockedSinceMs < 0L) blockedSinceMs = nowMs
            return nowMs - blockedSinceMs
        }

        fun reset() { blockedSinceMs = -1L }
    }
}
