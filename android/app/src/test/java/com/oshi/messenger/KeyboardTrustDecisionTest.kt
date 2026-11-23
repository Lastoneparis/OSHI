package com.oshi.messenger

import android.content.pm.ApplicationInfo
import com.oshi.messenger.ui.keyboard.KeyboardInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DEVICE_INTEGRITY_2026_10_01__ The keyboard trust rule behind the third-party
 * keyboard banner (__KEYBOARD_PRIVACY_2026_10_01__), as pure logic.
 */
class KeyboardTrustDecisionTest {

    private val gboard = "com.google.android.inputmethod.latin"
    private val play = "com.android.vending"

    @Test
    fun `system keyboard is trusted`() {
        assertTrue(KeyboardInspector.isTrusted("com.samsung.android.honeyboard", ApplicationInfo.FLAG_SYSTEM, null))
        assertTrue(KeyboardInspector.isTrusted(gboard, ApplicationInfo.FLAG_SYSTEM, null))
    }

    @Test
    fun `updated system keyboard is trusted`() {
        assertTrue(KeyboardInspector.isTrusted("com.android.inputmethod.latin", ApplicationInfo.FLAG_UPDATED_SYSTEM_APP, null))
    }

    @Test
    fun `Play-installed Gboard is trusted`() {
        assertTrue(KeyboardInspector.isTrusted(gboard, 0, play))
    }

    @Test
    fun `sideloaded package named like Gboard is not trusted`() {
        assertFalse(KeyboardInspector.isTrusted(gboard, 0, null))
        assertFalse(KeyboardInspector.isTrusted(gboard, 0, "com.google.android.packageinstaller"))
        assertFalse(KeyboardInspector.isTrusted(gboard, null, "com.sec.android.app.samsungapps"))
    }

    @Test
    fun `third-party keyboard from Play is not trusted`() {
        assertFalse(KeyboardInspector.isTrusted("com.touchtype.swiftkey", 0, play))
        assertFalse(KeyboardInspector.isTrusted("com.example.fancykeys", null, play))
    }

    @Test
    fun `unknown or missing keyboard id yields no package`() {
        assertNull(KeyboardInspector.packageOfInputMethodId(null))
        assertNull(KeyboardInspector.packageOfInputMethodId(""))
        assertNull(KeyboardInspector.packageOfInputMethodId("garbage"))
        assertNull(KeyboardInspector.packageOfInputMethodId("/.Service"))
        assertNull(KeyboardInspector.packageOfInputMethodId("pkg/"))
    }

    @Test
    fun `keyboard id parses to its package`() {
        assertEquals(gboard, KeyboardInspector.packageOfInputMethodId("$gboard/com.android.inputmethod.latin.LatinIME"))
        assertEquals("com.touchtype.swiftkey", KeyboardInspector.packageOfInputMethodId("com.touchtype.swiftkey/com.touchtype.KeyboardService"))
        assertEquals("com.example.ime", KeyboardInspector.packageOfInputMethodId("com.example.ime/.Ime"))
    }
}
