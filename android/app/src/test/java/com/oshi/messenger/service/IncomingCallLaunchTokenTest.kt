package com.oshi.messenger.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** __LOCKSCREEN_INTENT_SPOOF_2026_11_20__ */
class IncomingCallLaunchTokenTest {
    @Test fun aLaunchWithoutTheTokenIsNotAnIncomingCall() {
        val secret = IncomingCallLaunchToken.value
        assertFalse(IncomingCallLaunchToken.matches(null, secret))
        assertFalse(IncomingCallLaunchToken.matches("", secret))
        assertFalse(IncomingCallLaunchToken.matches("true", secret))
        assertFalse(IncomingCallLaunchToken.matches(secret.dropLast(1), secret))
    }

    @Test fun theProcessTokenMatchesAndIsUnguessable() {
        val secret = IncomingCallLaunchToken.value
        assertTrue(IncomingCallLaunchToken.matches(secret, secret))
        assertTrue(secret.length == 32)
        assertTrue(secret == IncomingCallLaunchToken.value) // stable within the process
    }
}
