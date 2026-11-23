package com.oshi.messenger

import com.oshi.messenger.service.CallHangUpNotification
import com.oshi.messenger.service.CallState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * __END_CALL_ALWAYS_VISIBLE_2026_09_23__ The Hang up notification must be offered in every
 * state where the user is on, or placing, a call — the states in which a Galaxy A17 showed
 * a call screen with no End Call button — and never while an incoming call is still ringing
 * (that screen has Accept / Decline) or once the call is over.
 */
class CallHangUpNotificationTest {

    @Test
    fun `hang up is offered while connecting, connected and ringing out`() {
        assertEquals(true, CallHangUpNotification.isHangUpAble(CallState.CONNECTING, isOutgoing = true))
        assertEquals(true, CallHangUpNotification.isHangUpAble(CallState.CONNECTING, isOutgoing = false))
        assertEquals(true, CallHangUpNotification.isHangUpAble(CallState.IN_CALL, isOutgoing = true))
        assertEquals(true, CallHangUpNotification.isHangUpAble(CallState.IN_CALL, isOutgoing = false))
        assertEquals(true, CallHangUpNotification.isHangUpAble(CallState.RINGING, isOutgoing = true))
    }

    @Test
    fun `hang up is not offered for an incoming ring or a finished call`() {
        assertEquals(false, CallHangUpNotification.isHangUpAble(CallState.RINGING, isOutgoing = false))
        assertEquals(false, CallHangUpNotification.isHangUpAble(CallState.IDLE, isOutgoing = true))
        assertEquals(false, CallHangUpNotification.isHangUpAble(CallState.ENDED, isOutgoing = false))
    }
}
