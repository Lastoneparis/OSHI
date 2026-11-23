package com.oshi.messenger

import com.oshi.messenger.service.IncomingRingPolicy
import com.oshi.messenger.service.IncomingRingPolicy.Ring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __B1_RING_DEDUPE_2026_09_23__ One incoming call delivered by FCM AND by the relay must ring
 * exactly once — and the FCM full-screen ringer, when it came first, must not be cut by the
 * relay copy of the offer.
 */
class IncomingRingDedupeTest {

    @Test
    fun `the first ingress rings in-app`() {
        assertEquals(Ring.PLAY_IN_APP, IncomingRingPolicy.onIncomingOffer(firstIngress = true))
    }

    @Test
    fun `a relay offer after the FCM ringer keeps the system ringer and adds no second ringtone`() {
        assertEquals(Ring.KEEP_SYSTEM_RINGER, IncomingRingPolicy.onIncomingOffer(firstIngress = false))
    }

    /** The regression itself: the duplicate-ingress branch used to call dismissIncomingCallRinger(). */
    @Test
    fun `handleIncomingCall never dismisses the system ringer on a duplicate ingress`() {
        val src = File("src/main/java/com/oshi/messenger/service/EnhancedCallManager.kt").readText()
        val start = src.indexOf("private suspend fun handleIncomingCall(")
        val end = src.indexOf("private suspend fun handleCallAccepted(")
        assertTrue(start in 0 until end)
        val body = src.substring(start, end)
        assertFalse("the FCM full-screen ring must survive the relay copy", body.contains("dismissIncomingCallRinger()"))
        assertTrue(body.contains("IncomingRingPolicy.onIncomingOffer(tryPresentIncomingSurface(callId))"))
        assertTrue("no second in-app ringtone over the system ringer",
            body.contains("if (ring == IncomingRingPolicy.Ring.PLAY_IN_APP) playRingtone()"))
    }
}
