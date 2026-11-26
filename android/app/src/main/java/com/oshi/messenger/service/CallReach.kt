package com.oshi.messenger.service

/**
 * __CALL_WAKE_2026_11_22__ What the CALLER knows about the callee's phone while it rings.
 *
 * Journal 21/11: callers hung up after ~11 s on calls the callee's phone had not even
 * received, and waited 45 s on calls it never would. The outgoing screen said "Ringing…"
 * from the first millisecond whatever happened. The call server now tracks the wake
 * (`GET /call-reach/:callId`, ServerPatches/call_wake_2026_11_22) and a ringing callee
 * reports `callRinging`; this is the client side of both.
 *
 * Server states, in the order they can only move forward:
 *   calling     → nothing known yet
 *   pushed      → Apple/Google accepted the wake push, no sign of the device yet
 *   delivered   → the callee's device fetched the offer / its socket answered
 *   ringing     → the callee's app says it is ringing (1.7.2+, iOS 1.0.49+, desktop 1.6.3+)
 *   unreachable → no push device and no live socket (or every push failed)
 */
enum class CallReach {
    CALLING, PUSHED, DELIVERED, RINGING, UNREACHABLE;

    companion object {
        fun fromServer(state: String?): CallReach? = when (state) {
            "calling" -> CALLING
            "pushed" -> PUSHED
            "delivered" -> DELIVERED
            "ringing" -> RINGING
            "unreachable" -> UNREACHABLE
            else -> null
        }

        private fun rank(r: CallReach): Int = when (r) {
            CALLING -> 0
            UNREACHABLE -> 1
            PUSHED -> 2
            DELIVERED -> 3
            RINGING -> 4
        }

        /**
         * Never step back: a later poll that knows less (server restarted, a sibling
         * device's answer missing) must not turn "ringing" back into "calling".
         * UNREACHABLE is overtaken by any positive evidence (the callee came online).
         */
        fun merge(old: CallReach?, new: CallReach?): CallReach? {
            if (new == null) return old
            if (old == null) return new
            return if (rank(new) >= rank(old)) new else old
        }
    }
}

/** Which words the outgoing call screen shows while the call rings. */
object CallReachLabel {
    enum class Label { RINGING, CALLING, UNREACHABLE }

    /**
     * null = the server does not know `/call-reach` (not deployed / older relay): keep the
     * old "Ringing…" so nothing regresses. "Not reachable" only after 6 s, so a push that
     * is a second late never flashes a scary label.
     */
    fun forOutgoing(reach: CallReach?, ringingForMs: Long): Label = when (reach) {
        null -> Label.RINGING
        CallReach.RINGING, CallReach.DELIVERED -> Label.RINGING
        CallReach.UNREACHABLE -> if (ringingForMs >= 6_000L) Label.UNREACHABLE else Label.CALLING
        CallReach.PUSHED, CallReach.CALLING -> Label.CALLING
    }
}

/** The wire form of the callee's ringing report (server: call_wake_2026_11_22.js `RINGING`). */
object CallReachWire {
    const val RINGING_TYPE = "callRinging"
    /** `/signal` requires a non-empty `signal`; the server records and drops it. */
    const val RINGING_PLACEHOLDER = "cmluZw=="
}

/** __CALL_WAKE_2026_11_22__ Foreground FCM call push with no offer after 3 s: ring anyway? */
object ForegroundCallPushPolicy {
    fun shouldRaiseSystemRinger(callId: String, idle: Boolean, alreadyThisCall: Boolean, recentlyEnded: Boolean): Boolean =
        callId.isNotEmpty() && idle && !alreadyThisCall && !recentlyEnded
}
