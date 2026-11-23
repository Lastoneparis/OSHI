package com.oshi.messenger.service

/**
 * __B1_RING_DEDUPE_2026_09_23__ What the relay/mesh CALL_REQUEST does when the SAME call
 * already raised its incoming surface through FCM.
 *
 * The FCM `call` / `incoming_call_wake` push only claims the surface when the app is NOT
 * in the foreground, and then starts [IncomingCallService] — the full-screen notification
 * with its own ringtone and vibration. When the relay copy of the offer arrives next,
 * `handleIncomingCall` used to "coalesce onto the in-app overlay" by DISMISSING that
 * service. On a locked or backgrounded phone there is no visible overlay to coalesce onto,
 * and the full-screen intent itself brings the activity to the front a moment later — so
 * the one ring the user could see and hear was cut within a second of starting.
 *
 * The rule now: the first ingress owns the ring. A later ingress for the same callId
 * never dismisses the system ringer and never starts a second in-app ringtone on top of
 * it (two ringtones + two vibrators). The call state still goes to RINGING, so the in-app
 * overlay, accept and decline work as before; the system ringer is torn down by
 * `stopRingtoneAndRingback()` on accept / decline / end, exactly as for a relay-first call.
 */
object IncomingRingPolicy {

    enum class Ring {
        /** First ingress: this process rings (in-app ringtone + vibration). */
        PLAY_IN_APP,

        /** FCM already rings through [IncomingCallService]: keep it, add nothing. */
        KEEP_SYSTEM_RINGER,
    }

    /** @param firstIngress the result of `EnhancedCallManager.tryPresentIncomingSurface`. */
    fun onIncomingOffer(firstIngress: Boolean): Ring =
        if (firstIngress) Ring.PLAY_IN_APP else Ring.KEEP_SYSTEM_RINGER
}
