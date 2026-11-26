package com.oshi.messenger.service

import com.oshi.messenger.network.auth.CallPushAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** __CALL_WAKE_2026_11_22__ caller reach, callee readiness, re-sign on a clock refusal. */
class CallWakeTest {

    @Test fun `server states map, unknown is null`() {
        assertEquals(CallReach.RINGING, CallReach.fromServer("ringing"))
        assertEquals(CallReach.DELIVERED, CallReach.fromServer("delivered"))
        assertEquals(CallReach.PUSHED, CallReach.fromServer("pushed"))
        assertEquals(CallReach.UNREACHABLE, CallReach.fromServer("unreachable"))
        assertEquals(CallReach.CALLING, CallReach.fromServer("calling"))
        assertNull(CallReach.fromServer("online"))
        assertNull(CallReach.fromServer(null))
    }

    @Test fun `reach never steps back, unreachable is overtaken`() {
        assertEquals(CallReach.RINGING, CallReach.merge(CallReach.RINGING, CallReach.CALLING))
        assertEquals(CallReach.RINGING, CallReach.merge(CallReach.RINGING, CallReach.PUSHED))
        assertEquals(CallReach.DELIVERED, CallReach.merge(CallReach.UNREACHABLE, CallReach.DELIVERED))
        assertEquals(CallReach.UNREACHABLE, CallReach.merge(CallReach.CALLING, CallReach.UNREACHABLE))
        assertEquals(CallReach.PUSHED, CallReach.merge(CallReach.PUSHED, CallReach.UNREACHABLE))
        assertEquals(CallReach.PUSHED, CallReach.merge(null, CallReach.PUSHED))
        assertEquals(CallReach.PUSHED, CallReach.merge(CallReach.PUSHED, null))
    }

    @Test fun `outgoing label - old server keeps Ringing, unreachable only after 6 s`() {
        assertEquals(CallReachLabel.Label.RINGING, CallReachLabel.forOutgoing(null, 0))
        assertEquals(CallReachLabel.Label.CALLING, CallReachLabel.forOutgoing(CallReach.CALLING, 20_000))
        assertEquals(CallReachLabel.Label.CALLING, CallReachLabel.forOutgoing(CallReach.PUSHED, 20_000))
        assertEquals(CallReachLabel.Label.RINGING, CallReachLabel.forOutgoing(CallReach.DELIVERED, 1_000))
        assertEquals(CallReachLabel.Label.RINGING, CallReachLabel.forOutgoing(CallReach.RINGING, 1_000))
        assertEquals(CallReachLabel.Label.CALLING, CallReachLabel.forOutgoing(CallReach.UNREACHABLE, 5_999))
        assertEquals(CallReachLabel.Label.UNREACHABLE, CallReachLabel.forOutgoing(CallReach.UNREACHABLE, 6_000))
    }

    @Test fun `foreground FCM push rings the system way only when nothing else did`() {
        assertTrue(ForegroundCallPushPolicy.shouldRaiseSystemRinger("c1", idle = true, alreadyThisCall = false, recentlyEnded = false))
        assertFalse(ForegroundCallPushPolicy.shouldRaiseSystemRinger("c1", idle = false, alreadyThisCall = false, recentlyEnded = false))
        assertFalse(ForegroundCallPushPolicy.shouldRaiseSystemRinger("c1", idle = true, alreadyThisCall = true, recentlyEnded = false))
        assertFalse(ForegroundCallPushPolicy.shouldRaiseSystemRinger("c1", idle = true, alreadyThisCall = false, recentlyEnded = true))
        assertFalse(ForegroundCallPushPolicy.shouldRaiseSystemRinger("", idle = true, alreadyThisCall = false, recentlyEnded = false))
    }

    @Test fun `ringing report wire form`() {
        assertEquals("callRinging", CallReachWire.RINGING_TYPE)
        assertTrue(CallReachWire.RINGING_PLACEHOLDER.isNotEmpty())
    }

    private fun facts(
        notif: Boolean = true, channel: Boolean? = true, fullScreen: Boolean? = true,
        ignoring: Boolean = false, bucket: Int? = 10,
    ) = CallReadiness.Facts(notif, channel, fullScreen, ignoring, bucket)

    @Test fun `readiness - healthy phone, battery-optimised by default, is fine`() {
        assertEquals(emptyList<CallReadiness.Issue>(), CallReadiness.evaluate(facts()))
        assertEquals(emptyList<CallReadiness.Issue>(), CallReadiness.evaluate(facts(channel = null, fullScreen = null, bucket = null)))
    }

    @Test fun `readiness - issues, most severe first`() {
        assertEquals(
            listOf(CallReadiness.Issue.NOTIFICATIONS_OFF, CallReadiness.Issue.CALL_CHANNEL_OFF,
                CallReadiness.Issue.FULL_SCREEN_DENIED, CallReadiness.Issue.BACKGROUND_RESTRICTED),
            CallReadiness.evaluate(facts(notif = false, channel = false, fullScreen = false, bucket = 45)))
        assertEquals(listOf(CallReadiness.Issue.FULL_SCREEN_DENIED), CallReadiness.evaluate(facts(fullScreen = false)))
        // restricted bucket but the user exempted the app from optimisation: fine
        assertEquals(emptyList<CallReadiness.Issue>(), CallReadiness.evaluate(facts(ignoring = true, bucket = 45)))
        assertEquals(listOf(CallReadiness.Issue.BACKGROUND_RESTRICTED), CallReadiness.evaluate(facts(bucket = 45)))
        assertEquals(emptyList<CallReadiness.Issue>(), CallReadiness.evaluate(facts(bucket = 40)))   // RARE is not restricted
    }

    @Test fun `re-sign once only when a refusal moved the clock`() {
        assertTrue(CallPushAuth.shouldResignAfter(403, 0, 120_000))
        assertTrue(CallPushAuth.shouldResignAfter(401, 0, -90_000))
        assertFalse(CallPushAuth.shouldResignAfter(403, 120_000, 120_000), "already corrected: the key is the problem")
        assertFalse(CallPushAuth.shouldResignAfter(200, 0, 120_000))
        assertFalse(CallPushAuth.shouldResignAfter(500, 0, 120_000))
        assertFalse(CallPushAuth.shouldResignAfter(403, 0, 3_000))
    }

    private fun assertFalse(cond: Boolean, msg: String) = org.junit.Assert.assertFalse(msg, cond)
}
