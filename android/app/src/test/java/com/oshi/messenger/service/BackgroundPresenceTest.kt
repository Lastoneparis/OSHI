package com.oshi.messenger.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** __BACKGROUND_PRESENCE_2026_11_10__ "the OSHI logo stays in my status bar". */
class BackgroundPresenceTest {
    @Test
    fun `with Google push and no opt-in nothing runs in the background, so no status-bar logo`() {
        assertFalse(BackgroundPresence.shouldRun(userOptIn = false, pushAvailable = true))
    }

    @Test
    fun `a phone without Google push always keeps running, or it would receive nothing`() {
        assertTrue(BackgroundPresence.shouldRun(userOptIn = false, pushAvailable = false))
        assertTrue(BackgroundPresence.shouldRun(userOptIn = true, pushAvailable = false))
    }

    @Test
    fun `the opt-in keeps nearby reachable with push available`() {
        assertTrue(BackgroundPresence.shouldRun(userOptIn = true, pushAvailable = true))
    }
}
