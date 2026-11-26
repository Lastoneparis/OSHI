package com.oshi.messenger.ui

import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import com.oshi.messenger.ui.onboarding.OnboardingFlow
import com.oshi.messenger.ui.onboarding.OnboardingIcons
import com.oshi.messenger.ui.onboarding.OnboardingPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __ONBOARDING_2027__ The redesigned first launch: flow, safety content, honesty of
 * the copy, the icon system, the notification ask, and the behaviours that must not
 * change (identity creation / restore / deep links held until onboarded).
 */
class OnboardingRedesignTest {

    private fun src(path: String) = File("src/main/java/com/oshi/messenger/$path").readText()
    private val screen by lazy { src("ui/screens/OnboardingScreen.kt") }
    private val activity by lazy { src("ui/MainActivity.kt") }

    // ── Flow ────────────────────────────────────────────────────────────────

    @Test
    fun `the tour is four one-idea slides then the rules`() {
        assertEquals(
            listOf(OnboardingPage.PRIVATE, OnboardingPage.OFFLINE, OnboardingPage.ANONYMOUS, OnboardingPage.TOGETHER, OnboardingPage.RULES),
            OnboardingFlow.TOUR
        )
        assertEquals(4, OnboardingFlow.TOUR.count { it.isSlide })
    }

    @Test
    fun `notification step appears only where the runtime permission exists and is missing`() {
        assertFalse(OnboardingFlow.needsNotificationStep(sdkInt = 32, granted = false))
        assertTrue(OnboardingFlow.needsNotificationStep(sdkInt = 33, granted = false))
        assertFalse(OnboardingFlow.needsNotificationStep(sdkInt = 35, granted = true))
        assertFalse(OnboardingPage.NOTIFICATIONS in OnboardingFlow.steps(askNotifications = false))
        assertTrue(OnboardingPage.NOTIFICATIONS in OnboardingFlow.steps(askNotifications = true))
    }

    @Test
    fun `next walks every step and ends on identity`() {
        for (ask in listOf(true, false)) {
            val walked = generateSequence(OnboardingPage.PRIVATE) { OnboardingFlow.next(it, ask) }.toList()
            assertEquals(OnboardingFlow.steps(ask), walked)
        }
        assertEquals(OnboardingPage.IDENTITY, OnboardingFlow.next(OnboardingPage.RULES, askNotifications = false))
        assertEquals(OnboardingPage.NOTIFICATIONS, OnboardingFlow.next(OnboardingPage.RULES, askNotifications = true))
    }

    /** The old screen had no BackHandler: system Back on page 7 closed the app. */
    @Test
    fun `back returns step by step and leaves only from the first slide`() {
        assertNull(OnboardingFlow.previous(OnboardingPage.PRIVATE, askNotifications = true))
        assertEquals(OnboardingPage.NOTIFICATIONS, OnboardingFlow.previous(OnboardingPage.IDENTITY, askNotifications = true))
        assertEquals(OnboardingPage.RULES, OnboardingFlow.previous(OnboardingPage.IDENTITY, askNotifications = false))
        assertEquals(OnboardingPage.TOGETHER, OnboardingFlow.previous(OnboardingPage.RULES, askNotifications = true))
        assertTrue(screen.contains("BackHandler(enabled = previous != null"))
    }

    @Test
    fun `skip lands on the rules, never past them`() {
        assertEquals(OnboardingPage.RULES, OnboardingFlow.SKIP_TARGET)
        assertTrue(screen.contains("onSkip = { goTo(OnboardingFlow.SKIP_TARGET) }"))
    }

    // ── Safety disclosures: every string the old two pages showed is still shown ──

    @Test
    fun `rules page shows every conduct and disclaimer string`() {
        val keys = listOf(
            "onboarding_conduct_title", "onboarding_conduct_subtitle",
            "onboarding_rule_respect", "onboarding_rule_privacy", "onboarding_rule_secure", "onboarding_rule_backup",
            "onboarding_rule_no_spam", "onboarding_rule_no_abuse", "onboarding_rule_no_illegal",
            "onboarding_disclaimer_audit_title", "onboarding_disclaimer_audit_desc",
            "onboarding_disclaimer_anonymous_title", "onboarding_disclaimer_anonymous_desc",
            "onboarding_disclaimer_notifications_title", "onboarding_disclaimer_notifications_desc",
            "onboarding_disclaimer_mesh_title", "onboarding_disclaimer_mesh_desc",
            "onboarding_disclaimer_cloud_title", "onboarding_disclaimer_cloud_desc",
            "onboarding_disclaimer_critical_title", "onboarding_disclaimer_critical_desc",
            "onboarding_disclaimer_note", "onboarding_reach_disclaimer",
        )
        val missing = keys.filter { !screen.contains("R.string.$it") }
        assertTrue("not rendered any more: $missing", missing.isEmpty())
    }

    // ── Honest copy ─────────────────────────────────────────────────────────

    @Test
    fun `onboarding copy never claims Tor or a superlative`() {
        val res = File("src/main/res")
        val files = res.listFiles { f -> f.name.startsWith("values") }!!
            .mapNotNull { File(it, "strings_onboarding_2027.xml").takeIf { f -> f.exists() } }
        assertEquals("English + 33 locales", 34, files.size)
        val english = File(res, "values/strings_onboarding_2027.xml").readText()
            .substringAfter("<resources>")
        for (word in listOf("Tor", "onion", "first messenger", "most secure", "the only", "unhackable", "100%")) {
            assertFalse("English onboarding copy must not say \"$word\"", english.contains(word, ignoreCase = word != "Tor"))
        }
        for (f in files) {
            val body = f.readText().substringAfter("<resources>")
            assertFalse("${f.parentFile.name}: mentions Tor", Regex("""\bTor\b""").containsMatchIn(body))
        }
    }

    // ── Icons: one family ───────────────────────────────────────────────────

    private fun paths(node: VectorNode): List<VectorPath> = when (node) {
        is VectorPath -> listOf(node)
        is VectorGroup -> node.flatMap { paths(it) }
        else -> emptyList()
    }

    @Test
    fun `every onboarding icon is one stroke weight, round, unfilled, on a 24 grid`() {
        assertTrue(OnboardingIcons.ALL.size >= 20)
        for (icon in OnboardingIcons.ALL) {
            assertEquals(icon.name, 24f, icon.viewportWidth)
            assertEquals(icon.name, 24f, icon.viewportHeight)
            val ps = paths(icon.root)
            assertTrue("${icon.name} has no path", ps.isNotEmpty())
            for (p in ps) {
                assertNull("${icon.name}: filled", p.fill)
                assertEquals("${icon.name}: stroke", OnboardingIcons.STROKE, p.strokeLineWidth)
                assertEquals(androidx.compose.ui.graphics.StrokeCap.Round, p.strokeLineCap)
            }
        }
    }

    @Test
    fun `directional icons mirror right to left`() {
        assertTrue(OnboardingIcons.ArrowForward.autoMirror)
        assertTrue(OnboardingIcons.ArrowBack.autoMirror)
        assertFalse("the old Material arrows did not mirror", screen.contains("Icons.Default.ArrowForward"))
    }

    @Test
    fun `every step has its mascot pose`() {
        val dir = File("src/main/res/drawable-nodpi")
        for (name in listOf("wave", "cool", "peek", "party", "ok", "wow", "neutral", "hearteyes")) {
            assertTrue("onboarding_mascot_$name", File(dir, "onboarding_mascot_$name.webp").isFile)
        }
    }

    // ── Accessibility ───────────────────────────────────────────────────────

    @Test
    fun `reduce motion is honoured and touch targets are at least 48dp`() {
        assertTrue(screen.contains("isSystemReduceMotion(context)"))
        assertTrue(screen.contains("val animate = !reduceMotion"))
        assertTrue("instant page jumps when motion is off", screen.contains("pagerState.scrollToPage(idx)"))
        val visuals = src("ui/onboarding/OnboardingVisuals.kt")
        assertTrue(visuals.contains(".heightIn(min = 56.dp)"))
        assertFalse("no Lottie: motion is transform-only", File("build.gradle.kts").readText().contains("lottie", ignoreCase = true))
    }

    // ── Notification ask in context ─────────────────────────────────────────

    @Test
    fun `startup notification ask only for a user already onboarded at load`() {
        assertFalse(StartupNotificationAsk.shouldAsk(isLoading = true, onboardedAtLoad = true))
        assertFalse(StartupNotificationAsk.shouldAsk(isLoading = false, onboardedAtLoad = false))
        assertTrue(StartupNotificationAsk.shouldAsk(isLoading = false, onboardedAtLoad = true))
        val onCreate = activity.substringAfter("override fun onCreate(savedInstanceState: Bundle?)")
            .substringBefore("setContent {")
        assertFalse("no unconditional ask in onCreate", onCreate.contains("requestNotificationPermission()"))
        assertTrue(activity.contains("StartupNotificationAsk.shouldAsk("))
    }

    // ── Behaviour that must not change ──────────────────────────────────────

    @Test
    fun `identity creation, restore and recover still go through the view model`() {
        assertTrue(screen.contains("viewModel.createIdentity(displayName)"))
        assertTrue(screen.contains("viewModel.importRecoveryKey(recoveryKey)"))
        assertTrue(screen.contains("viewModel.recoverIdentityFromDevice()"))
        assertTrue(screen.contains("viewModel.resetIdentityCreationState()"))
        assertTrue("completion via isOnboarded too (restore path)", screen.contains("LaunchedEffect(isOnboarded)"))
        assertTrue("restore QR prefix stripped", screen.contains("removePrefix(\"oshi-recovery:\")"))
        assertTrue("pasted recovery key leaves the clipboard", screen.contains("SensitiveClipboard.clearIfStillHolds"))
    }

    /**
     * An invite / add-contact link opened before the account exists is parsed in
     * onCreate whatever the state (so it also survives the activity recreate the
     * onboarding language picker triggers) and is only acted on once onboarded.
     */
    @Test
    fun `deep links opened during onboarding are held until onboarded`() {
        val onCreate = activity.substringAfter("override fun onCreate(savedInstanceState: Bundle?)")
            .substringBefore("setContent {")
        assertTrue(onCreate.contains("handleNavIntent(intent)"))
        for (gate in listOf(
            "if (invite != null && isOnboarded && !isLoading)",
            "if (publicHandle != null && isOnboarded && !isLoading)",
            "if (addKey != null && isOnboarded && !isLoading)",
            "if (pendingRoute != null && isOnboarded && !isLoading)",
        )) assertTrue("missing gate: $gate", activity.contains(gate))
        assertFalse("onboarding must not consume pending links", screen.contains("pendingGroupInvite"))
    }

    @Test
    fun `onboarding does not touch window security flags`() {
        assertFalse(screen.contains("LayoutParams.FLAG"))
        assertFalse(screen.contains("addFlags("))
        assertFalse(screen.contains("setFlags("))
        assertFalse(screen.contains("clearFlags("))
    }

    /** The "apps that can read OSHI" alarm is not the first screen any more, and no longer misreads system apps. */
    @Test
    fun `spy-surface warning waits for an identity and can see system apps`() {
        assertTrue(activity.contains("if (spyOnboarded && !spyLoading) com.oshi.messenger.security.SpySurfaceHost()"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (action in listOf(
            "android.service.notification.NotificationListenerService",
            "android.accessibilityservice.AccessibilityService",
            "android.app.action.DEVICE_ADMIN_ENABLED",
        )) assertTrue("queries $action", manifest.contains("<action android:name=\"$action\" />"))
        assertFalse(manifest.contains("android:name=\"android.permission.QUERY_ALL_PACKAGES\""))
    }
}
