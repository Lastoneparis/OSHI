package com.oshi.messenger.ui

import com.oshi.messenger.ui.viewmodel.DiscoveryPhase
import com.oshi.messenger.ui.viewmodel.GroupsViewModel
import com.oshi.messenger.ui.viewmodel.NewMessageViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions for the defects proven on device on 2026-08-13
 * (`parity/verify/2026-08-13-device-walkthrough.md`).
 *
 * All three subjects were deliberately extracted as pure functions so they can
 * be pinned without a device, Hilt or Robolectric.
 */
class WalkthroughRegressionTest {

    // ═══════════════════════════════════════════════════════════════════════
    // P0 #1 — the AI tab had no bottom navigation bar
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * THE regression. `showBottomBar` listed seven routes and `aiChat` was not
     * one of them, so landing on AI removed global navigation entirely: the Map
     * and "+" taps hit nothing and the three device captures came back
     * pixel-identical.
     */
    @Test
    fun `every bottom-nav destination shows the bottom bar`() {
        for (route in BOTTOM_NAV_ROUTES) {
            assertTrue(
                "$route is a bottom tab but would hide the bar",
                shouldShowBottomBar(route)
            )
        }
    }

    @Test
    fun `aiChat is one of the bottom tabs`() {
        assertTrue("aiChat", BOTTOM_NAV_ROUTES.contains(ROUTE_AI_CHAT))
        assertTrue(shouldShowBottomBar(ROUTE_AI_CHAT))
    }

    @Test
    fun `the bar is exactly four tabs, in order`() {
        // __TAB_BAR_FOUR_2026_10_26__ `newMessage` left the bar: the Chats tab's own
        // compose FAB already opens it, and the fifth item is what truncated the
        // labels on a small phone. It is still reachable and still shows the bar —
        // AdaptiveLayoutTest pins that half.
        assertEquals(
            listOf("messages", "aiChat", "map", "settings"),
            BOTTOM_NAV_ROUTES
        )
    }

    /**
     * The only Map route registered in the graph carries query arguments, so a
     * literal comparison against `"map"` never matched the destination the user
     * is actually on.
     */
    @Test
    fun `parameterised routes are matched on their base path`() {
        assertTrue(shouldShowBottomBar("map?lat={lat}&lon={lon}&name={name}"))
        assertTrue(shouldShowBottomBar("map?lat=48.85&lon=2.35&name=Paris"))
        assertTrue(shouldShowBottomBar("newMessage?scan={scan}"))
        assertTrue(shouldShowBottomBar("newMessage?scan=true"))
    }

    @Test
    fun `pushed detail screens do not show the bar`() {
        for (route in listOf(
            "chat/{publicKey}", "groupChat/{groupId}", "call/{publicKey}/{isVideo}",
            "incomingCall/{publicKey}/{isVideo}", "qrCode", "onboarding",
            "discoverGroups", "keyBackup", "webSession", "blockedContacts"
        )) {
            assertFalse("$route must not show the bar", shouldShowBottomBar(route))
        }
    }

    @Test
    fun `a null route never shows the bar`() {
        assertFalse(shouldShowBottomBar(null))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // P1 #5 — Discover Groups spun forever
    // ═══════════════════════════════════════════════════════════════════════

    private fun phase(
        groups: Int,
        meshIdle: Boolean = false,
        elapsed: Long
    ) = GroupsViewModel.discoveryPhase(groups, meshIdle, elapsed)

    @Test
    fun `discovery starts in the scanning state`() {
        assertEquals(DiscoveryPhase.SCANNING, phase(groups = 0, elapsed = 0))
    }

    @Test
    fun `discovery stays scanning inside the window`() {
        assertEquals(
            DiscoveryPhase.SCANNING,
            phase(groups = 0, elapsed = GroupsViewModel.DISCOVERY_TIMEOUT_MS - 1)
        )
    }

    /** The actual bug: at t+30 s the screen was byte-identical to t+0. */
    @Test
    fun `discovery reaches a terminal empty state after the timeout`() {
        assertEquals(
            DiscoveryPhase.EMPTY,
            phase(groups = 0, elapsed = GroupsViewModel.DISCOVERY_TIMEOUT_MS)
        )
        assertEquals(DiscoveryPhase.EMPTY, phase(groups = 0, elapsed = 30_000))
    }

    @Test
    fun `any result wins over the timeout`() {
        assertEquals(DiscoveryPhase.RESULTS, phase(groups = 1, elapsed = 0))
        // An ad arriving after the window must pull the screen back out of EMPTY.
        assertEquals(DiscoveryPhase.RESULTS, phase(groups = 1, elapsed = 120_000))
        assertEquals(
            DiscoveryPhase.RESULTS,
            phase(groups = 3, meshIdle = true, elapsed = 120_000)
        )
    }

    @Test
    fun `an idle mesh is reported as an error, not as an empty result`() {
        assertEquals(
            DiscoveryPhase.ERROR,
            phase(groups = 0, meshIdle = true, elapsed = GroupsViewModel.MESH_GRACE_MS)
        )
    }

    /** The mesh is briefly IDLE while it starts up; that must not flash an error. */
    @Test
    fun `an idle mesh inside the grace period still reads as scanning`() {
        assertEquals(
            DiscoveryPhase.SCANNING,
            phase(groups = 0, meshIdle = true, elapsed = GroupsViewModel.MESH_GRACE_MS - 1)
        )
    }

    @Test
    fun `the grace period is shorter than the scan window`() {
        assertTrue(GroupsViewModel.MESH_GRACE_MS < GroupsViewModel.DISCOVERY_TIMEOUT_MS)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // P0 #2 — New Message could not send; recipient validation
    // ═══════════════════════════════════════════════════════════════════════

    // The keys here used to be `MCowBQYDK2VuAyEA…` strings that decode to nothing:
    // they only ever had to be 20-odd characters long and different from each
    // other. A recipient is now a real 32-byte X25519 key, in the spelling
    // `CryptoManager.getPublicKeyString()` produces — see RecipientKeyTest for
    // the full accept/reject table.
    private val myKey = "turSEKag/stcMtIZHzaHSxYHSJIVoT1mWvRi7bWqX7w="
    private val theirKey = "hTiEnKV10MM9IfHxzvlJ59WZj2y4wM/LeERb/kcs4AE="

    @Test
    fun `a real 32-byte key that is not mine is a valid recipient`() {
        assertTrue(NewMessageViewModel.isValidRecipient(theirKey, myKey))
    }

    @Test
    fun `whitespace around a pasted key is tolerated`() {
        assertTrue(NewMessageViewModel.isValidRecipient("  $theirKey\n", myKey))
    }

    @Test
    fun `my own key is never a valid recipient`() {
        assertFalse(NewMessageViewModel.isValidRecipient(myKey, myKey))
        assertFalse(NewMessageViewModel.isValidRecipient(" $myKey ", myKey))
    }

    @Test
    fun `short and empty input is rejected`() {
        assertFalse(NewMessageViewModel.isValidRecipient("", myKey))
        assertFalse(NewMessageViewModel.isValidRecipient("   ", myKey))
        assertFalse(NewMessageViewModel.isValidRecipient("a".repeat(19), myKey))
        // 20 characters used to be enough. It is 15 bytes; a key is 32.
        assertFalse(NewMessageViewModel.isValidRecipient("a".repeat(20), myKey))
    }
}
