package com.oshi.messenger

import com.oshi.messenger.service.ScreenshotProtectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Screenshot / screen-recording protection is ON by default
 * (__SCREEN_CAPTURE_ON_BY_DEFAULT_2026_11_09__): only an explicit opt-out turns
 * it off, only a DEBUG build can override it for tooling, and every window
 * that can show content is covered.
 */
class ScreenshotProtectionDefaultTest {

    private val flag = ScreenshotProtectionPolicy.FLAG_SECURE

    @Test
    fun `never set resolves to ON`() {
        assertTrue(ScreenshotProtectionPolicy.resolveUserChoice(null))
    }

    @Test
    fun `an explicit choice is respected either way`() {
        assertFalse(ScreenshotProtectionPolicy.resolveUserChoice(false))
        assertTrue(ScreenshotProtectionPolicy.resolveUserChoice(true))
    }

    @Test
    fun `release build ignores the debug capture override`() {
        assertTrue(
            ScreenshotProtectionPolicy.effectiveProtection(
                userChoice = true, isDebugBuild = false, debugCaptureOverride = true
            )
        )
    }

    @Test
    fun `debug build with override allows capture, without it follows the user`() {
        assertFalse(
            ScreenshotProtectionPolicy.effectiveProtection(
                userChoice = true, isDebugBuild = true, debugCaptureOverride = true
            )
        )
        assertTrue(
            ScreenshotProtectionPolicy.effectiveProtection(
                userChoice = true, isDebugBuild = true, debugCaptureOverride = false
            )
        )
        assertFalse(
            ScreenshotProtectionPolicy.effectiveProtection(
                userChoice = false, isDebugBuild = false, debugCaptureOverride = false
            )
        )
    }

    @Test
    fun `reconcile adds or clears only the secure bit and skips no-op updates`() {
        val other = 0x00000100 or 0x00000200 // LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS
        assertEquals(other or flag, ScreenshotProtectionPolicy.reconcile(other, true))
        assertEquals(other, ScreenshotProtectionPolicy.reconcile(other or flag, false))
        assertNull(ScreenshotProtectionPolicy.reconcile(other or flag, true))
        assertNull(ScreenshotProtectionPolicy.reconcile(other, false))
    }

    // ------------------------------------------------------------------
    // Source scans. Note: Gradle does not re-run a test because a SOURCE
    // file it reads changed — run with --rerun-tasks to re-check after edits.
    // ------------------------------------------------------------------

    private val root = File("src/main/java/com/oshi/messenger")

    private fun kotlinSources(): Sequence<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }

    @Test
    fun `source tree is where the scan expects it`() {
        assertTrue("run from the :app module dir", root.isDirectory)
        assertTrue(kotlinSources().count() > 100)
    }

    /**
     * Compose Dialog/AlertDialog/Popup/DropdownMenu inherit FLAG_SECURE from the
     * activity window under the default `SecureFlagPolicy.Inherit`. Opting a
     * window out would leak whatever it shows.
     */
    @Test
    fun `no window opts out of the secure flag`() {
        val offenders = kotlinSources().filter { f ->
            val t = f.readText()
            t.contains("SecureFlagPolicy.SecureOff") ||
                t.contains("clearFlags(WindowManager.LayoutParams.FLAG_SECURE)") &&
                !f.name.startsWith("ScreenshotProtection")
        }.map { it.name }.toList()
        assertTrue("windows opting out of FLAG_SECURE: $offenders", offenders.isEmpty())
    }

    /** Every Activity in the manifest applies protection. */
    @Test
    fun `every activity applies screenshot protection`() {
        val main = File(root, "ui/MainActivity.kt").readText()
        assertTrue(main.contains("screenshotProtection.registerActivity(this)"))
        val scan = File(root, "ui/scan/OshiScanActivity.kt").readText()
        assertTrue(scan.contains("ScreenshotProtectionManager.applyToUnmanagedActivity(this)"))

        val manifest = File("src/main/AndroidManifest.xml").readText()
        val activities = Regex("<activity\\s+android:name=\"([^\"]+)\"").findAll(manifest)
            .map { it.groupValues[1] }.toSet()
        assertEquals(
            "a new Activity must apply screenshot protection and be listed here",
            setOf(".ui.MainActivity", ".ui.scan.OshiScanActivity"),
            activities,
        )
    }

    /**
     * Material3 1.1.2's ModalBottomSheet has no securePolicy and does not set
     * FLAG_SECURE on its window, so the manager reconciles auxiliary windows.
     * If that sweep disappears, sheets become capturable again.
     */
    @Test
    fun `auxiliary windows (bottom sheets, platform pickers) are reconciled`() {
        val mgr = File(root, "service/ScreenshotProtectionManager.kt").readText()
        assertTrue(mgr.contains("WindowInspector.getGlobalWindowViews()"))
        assertTrue(mgr.contains("watchAuxiliaryWindows(activity)"))
    }

    /** The capture override must be gated on BuildConfig.DEBUG. */
    @Test
    fun `debug capture override is gated on a debug build`() {
        val mgr = File(root, "service/ScreenshotProtectionManager.kt").readText()
        val body = mgr.substringAfter("fun applyDebugCaptureOverride(").substringBefore("\n    }\n")
        assertTrue(body.contains("if (!com.oshi.messenger.BuildConfig.DEBUG"))
    }
}
