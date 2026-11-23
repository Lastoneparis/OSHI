package com.oshi.messenger

import android.view.WindowManager
import androidx.compose.ui.unit.dp
import com.oshi.messenger.service.ScreenshotProtectionPolicy
import com.oshi.messenger.ui.theme.Oshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pieces of the 2026-08-24 screen review that turned out to be real
 * logic rather than measurement noise.
 *
 * The review's nine "under 48 dp" Settings rows and its one "unlabelled
 * clickable" were artifacts of `uiautomator dump`: AccessibilityNodeInfo
 * bounds are intersected with the scroll container's visible region, so the
 * row straddling the top of the viewport and the row straddling the bottom
 * report truncated heights and lose the label nodes that fell outside the cut.
 * Re-measured across 16 scroll positions, every `SettingsItem` row is 72 dp
 * (Material `ListItem`'s two-line floor) and every `Switch` is exactly 48 dp
 * (`minimumInteractiveComponentSize`).
 *
 * What survived re-measurement is pinned here.
 */
class SettingsTouchAndSecureFlagTest {

    // -----------------------------------------------------------------------
    // Touch targets
    // -----------------------------------------------------------------------

    /**
     * The one Settings target genuinely under the minimum was the profile
     * name + pencil row in `ProfileHeaderCard` — a hand-rolled
     * `Row(...).clickable {}` with no height floor, which measured 92 x 39 dp
     * on a 420 dpi phone. It now takes `Oshi.Touch.minTarget`, so the value of
     * that token is load-bearing: drop it below 48 and the row goes back under
     * the guideline without anything else complaining.
     */
    @Test
    fun `minimum touch target is the 48 dp Material and a11y guidelines require`() {
        assertEquals(48.dp, Oshi.Touch.minTarget)
    }

    /**
     * A floor is only a floor if it is at least as large as what Compose
     * already enforces for the controls it owns; a smaller token would silently
     * make hand-rolled rows the smallest things on the screen again.
     */
    @Test
    fun `minimum touch target is not below Compose's own interactive minimum`() {
        assertTrue(
            "hand-rolled rows must not be allowed below the platform minimum",
            Oshi.Touch.minTarget >= 48.dp
        )
    }

    // -----------------------------------------------------------------------
    // Screenshot protection: preference -> window flag
    // -----------------------------------------------------------------------

    /**
     * The literal in [ScreenshotProtectionPolicy] must be the platform's own
     * FLAG_SECURE. It is written as a literal so the policy object stays
     * loadable off-device; this is the assertion that keeps the two in step.
     */
    @Test
    fun `policy's FLAG_SECURE is the platform constant`() {
        assertEquals(WindowManager.LayoutParams.FLAG_SECURE, ScreenshotProtectionPolicy.FLAG_SECURE)
    }

    /**
     * The Settings switch promises "Prevent screenshots in app". This is the
     * promise, reduced to the only decision the app actually makes.
     */
    @Test
    fun `protection on asks for FLAG_SECURE and off asks for nothing`() {
        assertEquals(
            WindowManager.LayoutParams.FLAG_SECURE,
            ScreenshotProtectionPolicy.windowFlags(protectionEnabled = true)
        )
        assertEquals(0, ScreenshotProtectionPolicy.windowFlags(protectionEnabled = false))
    }

    /**
     * `setFlags` is a mask-and-value call on a window that carries other flags
     * too, so reading protection back has to test the bit rather than compare
     * the whole word — the live window reports
     * `LAYOUT_IN_SCREEN SECURE LAYOUT_INSET_DECOR SPLIT_TOUCH ...`.
     */
    @Test
    fun `isProtected reads the bit out of a window that carries other flags`() {
        val otherFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_SPLIT_TOUCH

        assertFalse(ScreenshotProtectionPolicy.isProtected(otherFlags))
        assertTrue(
            ScreenshotProtectionPolicy.isProtected(
                otherFlags or ScreenshotProtectionPolicy.windowFlags(protectionEnabled = true)
            )
        )
        assertFalse(
            ScreenshotProtectionPolicy.isProtected(
                otherFlags or ScreenshotProtectionPolicy.windowFlags(protectionEnabled = false)
            )
        )
    }
}
