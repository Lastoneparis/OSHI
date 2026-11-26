package com.oshi.messenger.security

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 🛡 __STRICT_MODE_2026_11_22__ persistence + snapshot of the person's own values. */
class StrictModeStoreTest {
    private val mine = StrictGuardedSettings(
        appLock = false, screenshotProtection = true, incognitoKeyboard = false,
        notificationPreview = true, readReceipts = true, typingIndicators = false,
        privacyRelay = false, blockForwardCopy = false,
    )

    @After fun resetGate() {
        StrictModeGate.active = false
        StrictModeGate.options = StrictOptions()
    }

    @Test fun offByDefaultWithBothSubSwitchesOn() {
        val store = StrictModeStore(MapKeyValue())
        assertFalse(store.isActive)
        assertEquals(StrictOptions(blockPaste = true, hidePresence = true), store.options.value)
        assertNull(store.snapshot())
    }

    @Test fun stateAndSnapshotSurviveARestart() {
        val disk = MapKeyValue()
        StrictModeStore(disk).activate(mine)
        val reopened = StrictModeStore(disk)
        assertTrue(reopened.isActive)
        assertEquals(mine, reopened.snapshot())
    }

    @Test fun reEnablingKeepsTheFirstSnapshot() {
        // A second "on" must not record the FORCED values as the person's own.
        val disk = MapKeyValue()
        val store = StrictModeStore(disk)
        store.activate(mine)
        store.activate(mine.copy(screenshotProtection = true, notificationPreview = false, appLock = true))
        assertEquals(mine, store.snapshot())
    }

    @Test fun deactivateReturnsTheSnapshotAndForgetsIt() {
        val disk = MapKeyValue()
        val store = StrictModeStore(disk)
        store.activate(mine)
        assertEquals(mine, store.deactivate())
        assertFalse(store.isActive)
        assertNull(store.snapshot())
        assertTrue(disk.map.keys.none { it.startsWith("strict_prev_") })
        assertFalse(StrictModeStore(disk).isActive)
    }

    @Test fun optionsPersist() {
        val disk = MapKeyValue()
        StrictModeStore(disk).setOptions(StrictOptions(blockPaste = false, hidePresence = true))
        assertEquals(StrictOptions(blockPaste = false, hidePresence = true), StrictModeStore(disk).options.value)
    }

    @Test fun fullCycleRestoresEverything() {
        val disk = MapKeyValue()
        val store = StrictModeStore(disk)
        store.activate(mine)
        val forced = StrictModePolicy.forced(mine, store.options.value, lockAvailable = true, relayAvailable = true)
        val snap = store.deactivate()!!
        assertEquals(mine, StrictModePolicy.restore(snap, forced, store.options.value, true, true))
    }
}
