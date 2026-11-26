package com.oshi.messenger

import com.oshi.messenger.network.vps.CallSignalType
import com.oshi.messenger.service.CallSignalGuards
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** __CROSS_CALL_GUARD_2026_11_21__ / __AUTO_ACCEPT_AUTHENTICATED_2026_11_21__ */
class CallSignalGuardsTest {

    @Test fun `late callEnd of the previous call does not end the new one (12ba9315)`() {
        assertTrue(CallSignalGuards.isForeignCallSignal(CallSignalType.CALL_END, "d5e5983d-aaaa", "12ba9315-bbbb"))
    }

    @Test fun `decline of the cancelled call does not decline the redial (ab49980b)`() {
        assertTrue(CallSignalGuards.isForeignCallSignal(CallSignalType.CALL_REJECT, "db967e19-x", "ab49980b-y"))
    }

    @Test fun `accept for another call cannot connect this one`() {
        assertTrue(CallSignalGuards.isForeignCallSignal(CallSignalType.CALL_ACCEPT, "old", "new"))
    }

    @Test fun `signals of the current call pass`() {
        for (t in listOf(CallSignalType.CALL_END, CallSignalType.CALL_REJECT, CallSignalType.CALL_ACCEPT)) {
            assertFalse(CallSignalGuards.isForeignCallSignal(t, "12ba9315-bbbb", "12ba9315-bbbb"))
        }
    }

    @Test fun `case and a trailing NUL from old iOS builds are not a different call`() {
        assertFalse(CallSignalGuards.isForeignCallSignal(CallSignalType.CALL_END, "70E08E22-A61C\u0000", "70e08e22-a61c"))
        assertFalse(CallSignalGuards.isForeignCallSignal(CallSignalType.CALL_ACCEPT, " abc \n", "ABC"))
    }

    @Test fun `missing callId on either side keeps legacy behaviour`() {
        assertFalse(CallSignalGuards.isForeignCallSignal(CallSignalType.CALL_END, "", "abc"))
        assertFalse(CallSignalGuards.isForeignCallSignal(CallSignalType.CALL_END, "abc", null))
    }

    @Test fun `offers, media and ICE are never filtered here`() {
        assertFalse(CallSignalGuards.isForeignCallSignal(CallSignalType.CALL_REQUEST, "a", "b"))
        assertFalse(CallSignalGuards.isForeignCallSignal(CallSignalType.AUDIO_DATA, "a", "b"))
        assertFalse(CallSignalGuards.isForeignCallSignal(CallSignalType.ICE_CANDIDATE, "a", "b"))
    }

    @Test fun `auto-accept needs an authenticated frame from the peer while ringing out`() {
        assertTrue(CallSignalGuards.mayAutoAcceptFromMedia(true, false, true, "a+b/c=", "a-b_c"))
        assertFalse(CallSignalGuards.mayAutoAcceptFromMedia(true, false, false, "peer", "peer"))
        assertFalse(CallSignalGuards.mayAutoAcceptFromMedia(true, false, true, "someoneElse", "peer"))
        assertFalse(CallSignalGuards.mayAutoAcceptFromMedia(false, false, true, "peer", "peer"))
        assertFalse(CallSignalGuards.mayAutoAcceptFromMedia(true, true, true, "peer", "peer"))
    }
}
