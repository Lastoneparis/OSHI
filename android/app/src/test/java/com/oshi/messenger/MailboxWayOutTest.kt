package com.oshi.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __MAILBOX_HAD_NO_WAY_OUT_2026_11_06__
 *
 * The owner reported a useless cross in the iOS mailbox: a leading
 * `xmark.circle.fill` wired to `@Environment(\.dismiss)` on a view that nothing
 * presents, so tapping it did nothing. Checking Android for the same affordance
 * turned up the mirror image rather than a copy of the bug:
 *
 *  - `"mail"` is in neither `BOTTOM_NAV_ROUTES` nor `BOTTOM_BAR_SECONDARY_ROUTES`,
 *    so the global bottom bar is hidden while the mailbox is open, and
 *  - `MailScreen`'s `TopAppBar` declared no `navigationIcon`.
 *
 * So the mailbox had no on-screen exit at all — only the system back gesture —
 * on a route whose siblings `groups`/`peers`/`botPortal` are listed in
 * `BOTTOM_BAR_SECONDARY_ROUTES` precisely so the user "can leave them without
 * hunting for the back arrow". The two early returns (`loading`, `!hasMailbox`)
 * were worse still: they bypass the Scaffold, so the address-claim form was a
 * naked Column with nothing to tap to back out.
 *
 * Reads the source because these are Compose-structure facts, and
 * `testReleaseUnitTest` cannot host Compose at all (see
 * `compose-ui-tests-only-run-on-the-debug-variant`).
 */
class MailboxWayOutTest {

    private val mailScreen: String by lazy { read("src/main/java/com/oshi/messenger/ui/screens/MailScreen.kt") }
    private val mainActivity: String by lazy { read("src/main/java/com/oshi/messenger/ui/MainActivity.kt") }

    private fun read(relative: String): String = SourceText.read(relative)

    private fun stripComments(source: String) = SourceText.stripComments(source)

    // The stripper, before anything is trusted to it.
    @Test
    fun comment_stripper_keeps_code_and_drops_prose() {
        val stripped = stripComments(
            """
            // a navigationIcon note, with ArrowBack in it
            /* and a block saying navigationIcon too */
            IconButton(onClick = onBack) { }
            val site = "https://oshi.im/mail"
            """.trimIndent()
        )
        assertFalse("prose survived: $stripped", stripped.contains("navigationIcon"))
        assertFalse(stripped.contains("ArrowBack"))
        assertTrue("code was eaten: $stripped", stripped.contains("IconButton(onClick = onBack)"))
        assertTrue("a URL is not a comment", stripped.contains("https://oshi.im/mail"))
        assertTrue("line numbering must survive", stripped.lines().size >= 4)
    }

    // MARK: the premise — the bottom bar really is hidden here

    @Test
    fun the_mailbox_route_does_not_keep_the_bottom_bar() {
        val navRoutes = between(mainActivity, "internal val BOTTOM_NAV_ROUTES", ")")
        val secondary = between(mainActivity, "internal val BOTTOM_BAR_SECONDARY_ROUTES", ")")
        assertFalse("mail is a bottom-nav tab now — then the top-bar back arrow is redundant and this guard needs rewriting",
            navRoutes.contains("\"mail\""))
        assertFalse("mail keeps the bar now — then revisit whether the back arrow is still wanted",
            secondary.contains("\"mail\""))
    }

    // MARK: the fix

    @Test
    fun the_mailbox_top_bar_carries_a_back_arrow() {
        val topBar = between(mailScreen, "topBar = {", "TabRow(selectedTabIndex = tab)")
        assertTrue("sanity: this is the mailbox top bar", topBar.contains("Icons.Default.Refresh"))
        assertTrue("the mailbox TopAppBar has no navigationIcon — the bottom bar is hidden here, so there is no on-screen way out",
            topBar.contains("navigationIcon"))
        assertTrue("the navigation icon must be a back arrow, matching every other pushed screen",
            topBar.contains("Icons.Default.ArrowBack"))
        assertTrue("and it must actually pop, not decorate", topBar.contains("IconButton(onClick = onBack)"))
    }

    @Test
    fun the_loading_and_setup_states_are_leavable_too() {
        // Both bypass the main Scaffold with an early `return`, so they need
        // their own chrome; MailSetup in particular is a form that asks for a
        // name and used to offer nothing to back out of.
        assertTrue("the loading state lost its back affordance",
            mailScreen.contains("MailBackdrop(title = \"Mail\", onBack = onBack) {"))
        assertTrue("the address-claim form lost its back affordance",
            mailScreen.contains("MailBackdrop(title = \"Mail\", onBack = onBack) { MailSetup(viewModel) }"))
        val backdrop = between(mailScreen, "private fun MailBackdrop", "fun MailSetup")
        assertTrue(backdrop.contains("Icons.Default.ArrowBack"))
        assertTrue(backdrop.contains("IconButton(onClick = onBack)"))
    }

    @Test
    fun onBack_is_required_and_really_pops_the_back_stack() {
        // A `= {}` default is the exact shape of the iOS bug: an affordance that
        // looks like a way out and silently is not.
        val signature = between(mailScreen, "fun MailScreen(", ") {")
        assertTrue("onBack is not a parameter of MailScreen", signature.contains("onBack: () -> Unit"))
        assertFalse("onBack has a no-op default; a back arrow wired to {} is a dead button",
            signature.contains("onBack: () -> Unit = {}"))

        // A window, not between(…, ")"): the first ")" after the call site is
        // the one inside popBackStack(), so a naive slice ended before the
        // argument it was looking for and failed on correct code.
        val at = mainActivity.indexOf("com.oshi.messenger.ui.screens.MailScreen(")
        assertTrue("MailScreen is never called — the route is gone", at >= 0)
        val call = mainActivity.substring(at, minOf(at + 400, mainActivity.length))
        assertTrue("the only caller must hand MailScreen a real pop",
            call.contains("onBack = { navController.popBackStack() }"))
        assertEquals("a second call site would need its own way out audited",
            -1, mainActivity.indexOf("com.oshi.messenger.ui.screens.MailScreen(", at + 1))
    }

    private fun between(source: String, start: String, end: String) =
        SourceText.between(source, start, end)
}
