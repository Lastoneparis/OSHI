package com.oshi.messenger.ui

import com.oshi.messenger.ui.theme.HeightClass
import com.oshi.messenger.ui.theme.WidthClass
import com.oshi.messenger.ui.theme.windowSizeOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adaptive shell, tested at the sizes it will actually meet.
 *
 * These are pure functions of `Configuration.screenWidthDp/screenHeightDp`
 * precisely so they can be pinned here rather than only being looked at once on
 * one emulator. The dp figures below are the real values reported by the
 * devices named beside them.
 */
class AdaptiveLayoutTest {

    // ─────────────────────────────────────────────────────────────────────
    // Window classification
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `phone portrait is compact and keeps the bottom bar`() {
        // Pixel 6 portrait: 411 x 869 dp.
        val w = windowSizeOf(411, 869)
        assertEquals(WidthClass.Compact, w.width)
        assertEquals(HeightClass.Medium, w.height)
        assertFalse("a phone held upright must keep the bottom bar", w.useNavigationRail)
        assertEquals(
            "no width cap on a phone — the pane is the screen",
            Dp.Unspecified, w.contentMaxWidth
        )
    }

    @Test
    fun `phone landscape moves navigation to the rail`() {
        // Pixel 6 landscape: 869 x 411 dp — Expanded width, so the tabs move to
        // the rail, which also buys back the ~80 dp of height a bottom bar
        // would have cost out of the 411 dp available.
        val w = windowSizeOf(869, 411)
        assertEquals(WidthClass.Expanded, w.width)
        assertEquals(HeightClass.Compact, w.height)
        assertTrue(w.useNavigationRail)
    }

    /**
     * A split-screen half on a landscape phone: short AND narrow. The rail is
     * decided on width alone precisely so this case keeps its bottom bar —
     * spending 80 dp of 430 dp of width to save 80 dp of height is not a trade
     * worth making, and Material 3 says compact width takes a bottom bar.
     */
    @Test
    fun `a narrow short window keeps the bottom bar`() {
        val w = windowSizeOf(430, 400)
        assertEquals(WidthClass.Compact, w.width)
        assertEquals(HeightClass.Compact, w.height)
        assertFalse(w.useNavigationRail)
    }

    @Test
    fun `small tablet in portrait is medium — rail, modest cap`() {
        // Nexus 7 / foldable inner display portrait: 600 x 960 dp.
        val w = windowSizeOf(600, 960)
        assertEquals(WidthClass.Medium, w.width)
        assertTrue("Android 16 rotates this device whatever we ask", w.useNavigationRail)
        assertEquals(720.dp, w.contentMaxWidth)
    }

    @Test
    fun `10-inch tablet landscape is expanded and capped`() {
        // Pixel Tablet landscape: 1280 x 800 dp.
        val w = windowSizeOf(1280, 800)
        assertEquals(WidthClass.Expanded, w.width)
        assertTrue(w.useNavigationRail)
        assertEquals(
            "1280 dp of conversation list is a line length nobody can track",
            1040.dp, w.contentMaxWidth
        )
    }

    @Test
    fun `folded outer display is still a phone`() {
        // Pixel Fold, folded: 360 x 830 dp.
        val w = windowSizeOf(360, 830)
        assertEquals(WidthClass.Compact, w.width)
        assertFalse(w.useNavigationRail)
    }

    @Test
    fun `unfolding crosses the breakpoint`() {
        // Pixel Fold inner display: 841 x 942 dp.
        val folded = windowSizeOf(360, 830)
        val unfolded = windowSizeOf(841, 942)
        assertFalse(folded.useNavigationRail)
        assertTrue(unfolded.useNavigationRail)
        assertEquals(WidthClass.Expanded, unfolded.width)
    }

    @Test
    fun `breakpoints sit exactly on the Material 3 boundaries`() {
        assertEquals(WidthClass.Compact, windowSizeOf(599, 800).width)
        assertEquals(WidthClass.Medium, windowSizeOf(600, 800).width)
        assertEquals(WidthClass.Medium, windowSizeOf(839, 800).width)
        assertEquals(WidthClass.Expanded, windowSizeOf(840, 800).width)
        assertEquals(HeightClass.Compact, windowSizeOf(800, 479).height)
        assertEquals(HeightClass.Medium, windowSizeOf(800, 480).height)
        assertEquals(HeightClass.Expanded, windowSizeOf(800, 900).height)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Which destinations opt out of the reading-width cap
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Every route in [FULL_BLEED_ROUTES] carries arguments in the real graph,
     * so a literal equality test would match none of them — the same trap that
     * once hid the Map tab from `shouldShowBottomBar`.
     */
    @Test
    fun `full-bleed routes are matched with their real arguments attached`() {
        assertTrue(isFullBleedRoute("map"))
        assertTrue(isFullBleedRoute("map?lat=46.2&lon=6.1&name=Gen%C3%A8ve"))
        assertTrue(isFullBleedRoute("mapNavigation"))
        assertTrue(isFullBleedRoute("call/AbCd_123/true"))
        assertTrue(isFullBleedRoute("call/AbCd_123/false"))
        assertTrue(isFullBleedRoute("incomingCall/AbCd_123/true"))
    }

    @Test
    fun `lists and forms are capped, including the chat thread`() {
        assertFalse(isFullBleedRoute("messages"))
        assertFalse(isFullBleedRoute("chat/AbCd_123"))
        assertFalse(isFullBleedRoute("groupChat/42"))
        assertFalse(isFullBleedRoute("settings"))
        assertFalse(isFullBleedRoute("aiChat"))
        assertFalse(isFullBleedRoute("onboarding"))
        assertFalse(isFullBleedRoute(null))
    }

    /**
     * `mapRegions` is a download LIST that happens to start with "map". If the
     * prefix match were sloppy it would lose its width cap and stretch across a
     * tablet; it is a list of countries with a size and a button, not a map.
     */
    @Test
    fun `map-prefixed list screens are not mistaken for the map`() {
        assertFalse(isFullBleedRoute("mapRegions"))
        assertFalse(isFullBleedRoute("mapSearch"))
    }

    /**
     * The rail and the bar must offer the same destinations. They used to be two
     * hand-maintained literals and they drifted, which stranded the user on the
     * AI tab; both now derive from `BOTTOM_NAV_ROUTES`.
     */
    @Test
    fun `both navigation surfaces draw from one list of destinations`() {
        assertEquals(4, BOTTOM_NAV_ROUTES.size)
        assertEquals(BOTTOM_NAV_ROUTES.distinct(), BOTTOM_NAV_ROUTES)
        BOTTOM_NAV_ROUTES.forEach { route ->
            assertTrue("$route must keep global navigation", shouldShowBottomBar(route))
        }
    }

    /**
     * __TAB_BAR_FOUR_2026_10_26__ « passer à 4 icônes sachant que celle-ci est
     * en double ».
     *
     * `newMessage` left the bar because the Chats tab's own compose FAB already
     * opens it. Dropping a route from [BOTTOM_NAV_ROUTES] is the one edit that
     * can silently take the bar away with it — `shouldShowBottomBar` reads that
     * same list — and the screen would then have a single way out. It is listed
     * as a secondary route instead, and this is what proves it.
     */
    @Test
    fun `new message left the bar without losing it`() {
        assertFalse("newMessage must not be a tab any more", ROUTE_NEW_MESSAGE in BOTTOM_NAV_ROUTES)
        assertTrue("newMessage must still show the bottom bar", shouldShowBottomBar(ROUTE_NEW_MESSAGE))
        // The QR deep link arrives with a query string, which is the form that a
        // literal route comparison misses.
        assertTrue(shouldShowBottomBar("$ROUTE_NEW_MESSAGE?scan=true"))
    }
}
