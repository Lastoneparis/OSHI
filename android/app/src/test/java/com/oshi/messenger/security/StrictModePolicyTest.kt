package com.oshi.messenger.security

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 🛡 __STRICT_MODE_2026_11_22__ forced values, restore, and each guard switch. */
class StrictModePolicyTest {

    private val relaxed = StrictGuardedSettings(
        appLock = false, screenshotProtection = false, incognitoKeyboard = false,
        notificationPreview = true, readReceipts = true, typingIndicators = true,
        privacyRelay = false, blockForwardCopy = false,
    )

    @After fun resetGate() {
        StrictModeGate.active = false
        StrictModeGate.options = StrictOptions()
        StrictModeGate.nonToolServiceEnabled = false
    }

    @Test fun strictForcesEveryGuardOn() {
        val f = StrictModePolicy.forced(relaxed, StrictOptions(), lockAvailable = true, relayAvailable = true)
        assertTrue(f.appLock)
        assertTrue(f.screenshotProtection)
        assertTrue(f.incognitoKeyboard)
        assertFalse(f.notificationPreview)
        assertFalse(f.readReceipts)
        assertFalse(f.typingIndicators)
        assertTrue(f.privacyRelay)
        assertTrue(f.blockForwardCopy)
    }

    @Test fun noScreenLockMeansTheAppLockIsNotForced() {
        // Forcing a lock the phone cannot open would strand the person.
        val f = StrictModePolicy.forced(relaxed, StrictOptions(), lockAvailable = false, relayAvailable = false)
        assertFalse(f.appLock)
        assertFalse(f.privacyRelay)
    }

    @Test fun presenceIsKeptWhenTheSubSwitchIsOff() {
        val f = StrictModePolicy.forced(relaxed, StrictOptions(hidePresence = false), true, true)
        assertTrue(f.readReceipts)
        assertTrue(f.typingIndicators)
    }

    @Test fun turningOffRestoresThePersonsOwnValues() {
        val forced = StrictModePolicy.forced(relaxed, StrictOptions(), true, true)
        val back = StrictModePolicy.restore(relaxed, forced, StrictOptions(), true, true)
        assertEquals(relaxed, back)
    }

    @Test fun aSettingAlreadyStrictBeforeStaysAsItWas() {
        val careful = relaxed.copy(screenshotProtection = true, appLock = true, notificationPreview = false)
        val forced = StrictModePolicy.forced(careful, StrictOptions(), true, true)
        val back = StrictModePolicy.restore(careful, forced, StrictOptions(), true, true)
        assertTrue(back.screenshotProtection)
        assertTrue(back.appLock)
        assertFalse(back.notificationPreview)
    }

    @Test fun aFieldTheModeDidNotTouchKeepsItsCurrentValue() {
        // hidePresence OFF: receipts were the person's to change while the mode was on.
        val opts = StrictOptions(hidePresence = false)
        val forced = StrictModePolicy.forced(relaxed, opts, true, true)
        val changedMeanwhile = forced.copy(readReceipts = false)
        val back = StrictModePolicy.restore(relaxed, changedMeanwhile, opts, true, true)
        assertFalse(back.readReceipts)
        assertFalse(back.screenshotProtection)
    }

    @Test fun strictLocksOnEveryReturn() {
        assertTrue(StrictModePolicy.shouldAutoLock(appLock = true, timeoutSec = 0, elapsedMs = 10, strict = true))
        assertFalse(StrictModePolicy.shouldAutoLock(appLock = true, timeoutSec = 0, elapsedMs = 10, strict = false))
        assertFalse(StrictModePolicy.shouldAutoLock(appLock = true, timeoutSec = 60, elapsedMs = 10_000, strict = false))
        assertTrue(StrictModePolicy.shouldAutoLock(appLock = true, timeoutSec = 60, elapsedMs = 60_000, strict = false))
        assertFalse(StrictModePolicy.shouldAutoLock(appLock = false, timeoutSec = 0, elapsedMs = 10, strict = true))
    }

    @Test fun copyAndPasteGuards() {
        assertTrue(StrictModePolicy.copyAllowed(false))
        assertFalse(StrictModePolicy.copyAllowed(true))
        assertFalse(StrictModePolicy.pasteAllowed(true, StrictOptions(blockPaste = true)))
        assertTrue(StrictModePolicy.pasteAllowed(true, StrictOptions(blockPaste = false)))
        assertTrue(StrictModePolicy.pasteAllowed(false, StrictOptions(blockPaste = true)))
    }

    @Test fun previewsGiphyAndMediaExportAreOffInStrict() {
        assertFalse(StrictModePolicy.thirdPartyFetchAllowed(true))
        assertTrue(StrictModePolicy.thirdPartyFetchAllowed(false))
        assertFalse(StrictModePolicy.mediaExportAllowed(true))
        assertTrue(StrictModePolicy.mediaExportAllowed(false))
    }

    @Test fun injectedTouchesAreDroppedOnlyWithANonToolServiceEnabled() {
        val injected = StrictModePolicy.FLAG_IS_ACCESSIBILITY_EVENT
        assertTrue(StrictModePolicy.dropInjectedTouch(true, injected, nonToolServiceEnabled = true))
        // Only TalkBack/Switch Access enabled: their gestures must pass.
        assertFalse(StrictModePolicy.dropInjectedTouch(true, injected, nonToolServiceEnabled = false))
        // A finger is never dropped.
        assertFalse(StrictModePolicy.dropInjectedTouch(true, 0, nonToolServiceEnabled = true))
        assertFalse(StrictModePolicy.dropInjectedTouch(false, injected, nonToolServiceEnabled = true))
    }

    @Test fun settingsRowsAreLockedOnlyWhileForced() {
        val o = StrictOptions()
        assertTrue(StrictModePolicy.isLocked(true, StrictModePolicy.FIELD_SCREENSHOT, o))
        assertTrue(StrictModePolicy.isLocked(true, StrictModePolicy.FIELD_READ_RECEIPTS, o))
        assertFalse(StrictModePolicy.isLocked(true, StrictModePolicy.FIELD_READ_RECEIPTS, o.copy(hidePresence = false)))
        assertFalse(StrictModePolicy.isLocked(false, StrictModePolicy.FIELD_SCREENSHOT, o))
    }

    @Test fun theGateFollowsTheStore() {
        val store = StrictModeStore(MapKeyValue())
        assertFalse(StrictModeGate.active)
        store.activate(relaxed)
        assertTrue(StrictModeGate.active)
        assertFalse(StrictModeGate.copyAllowed)
        assertFalse(StrictModeGate.pasteAllowed)
        assertFalse(StrictModeGate.thirdPartyFetchAllowed)
        store.setOptions(StrictOptions(blockPaste = false))
        assertTrue(StrictModeGate.pasteAllowed)
        assertFalse(StrictModeGate.copyAllowed)
        store.deactivate()
        assertTrue(StrictModeGate.copyAllowed)
        assertNull(store.snapshot())
    }
}

/** In-memory [StrictKeyValue]: what SharedPreferences would hold. */
class MapKeyValue(val map: MutableMap<String, Boolean> = mutableMapOf()) : StrictKeyValue {
    override fun getBoolean(key: String, default: Boolean) = map[key] ?: default
    override fun contains(key: String) = key in map
    override fun edit(block: MutableMap<String, Boolean?>.() -> Unit) {
        mutableMapOf<String, Boolean?>().apply(block).forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
    }
}
