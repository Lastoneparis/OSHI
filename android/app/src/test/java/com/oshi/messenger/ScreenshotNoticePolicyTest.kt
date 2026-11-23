package com.oshi.messenger

import com.oshi.messenger.service.ScreenshotProtectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** __SCREENSHOT_NOTICE_2026_11_18__ the "screenshots are blocked" pill: when, how often, wired where. */
class ScreenshotNoticePolicyTest {
    @Test
    fun noticeOnlyWhileProtectedAndAtMostOncePerInterval() {
        val gap = ScreenshotProtectionPolicy.CAPTURE_NOTICE_MIN_INTERVAL_MS
        assertTrue(ScreenshotProtectionPolicy.captureNoticeDue(true, 10_000, null))
        assertFalse(ScreenshotProtectionPolicy.captureNoticeDue(false, 10_000, null))
        assertFalse(ScreenshotProtectionPolicy.captureNoticeDue(true, 10_000 + gap - 1, 10_000))
        assertTrue(ScreenshotProtectionPolicy.captureNoticeDue(true, 10_000 + gap, 10_000))
        assertTrue("a clock moved backwards never mutes it forever", ScreenshotProtectionPolicy.captureNoticeDue(true, 5_000, 10_000))
    }

    @Test
    fun defaultIsOnAndTheFlagIsTheSecureFlag() {
        assertTrue(ScreenshotProtectionPolicy.resolveUserChoice(null))
        assertEquals(0x2000, ScreenshotProtectionPolicy.windowFlags(true))
    }

    @Test
    fun callbackPermissionHostAndStringAreWired() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.DETECT_SCREEN_CAPTURE"))
        val mgr = SourceText.read("src/main/java/com/oshi/messenger/service/ScreenshotProtectionManager.kt")
        assertTrue(mgr.contains("registerScreenCaptureCallback("))
        assertTrue(mgr.contains("unregisterScreenCaptureCallback("))
        val main = SourceText.read("src/main/java/com/oshi/messenger/ui/MainActivity.kt")
        assertTrue(main.contains("ScreenCaptureNoticeHost()"))
        val dirs = java.io.File("src/main/res").listFiles()!!.filter { it.name == "values" || it.name.startsWith("values-") }
        val withStrings = dirs.filter { java.io.File(it, "strings_stego.xml").exists() }
        assertEquals(34, withStrings.size)
        for (d in withStrings) {
            val x = java.io.File(d, "strings_privacy_17.xml").readText()
            for (k in listOf("screenshot_blocked_notice", "rn_hint_chip", "rn_hint_setting_title", "rn_hint_setting_caption",
                    "rn_hint_explain_title", "rn_hint_explain_body")) {
                assertTrue("${d.name} has $k", x.contains("name=\"$k\""))
            }
        }
    }
}
