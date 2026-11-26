package com.oshi.messenger

import com.oshi.messenger.data.model.ContentReport
import com.oshi.messenger.data.model.ReportDeliveryState
import com.oshi.messenger.data.model.ReportReason
import com.oshi.messenger.data.model.ReportSubject
import com.oshi.messenger.data.model.ReportWireFormat
import com.oshi.messenger.service.SyncCycleTally
import com.oshi.messenger.ui.onboarding.OnboardingFlow
import com.oshi.messenger.ui.onboarding.OnboardingPage
import com.oshi.messenger.util.PrefsWipePolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.oshi.messenger.util.RatingPrompter
import com.oshi.messenger.util.RatingStore
import com.oshi.messenger.util.ReviewLauncher
import org.junit.Test

/**
 * Regressions for the settings / onboarding / app-shell parity audit
 * (`audit/options.md`, 2026-08-22). Each assertion here fails against the build
 * that was audited.
 */
class OptionsParityFixesTest {

    // ─────────────────────────────────────────────────────────────────────
    // F-2 / F-6 — the account wipe
    // ─────────────────────────────────────────────────────────────────────

    /**
     * The whole point of F-2: an identity that survives a wipe.
     * `oshi_identity_backup` holds the 64-byte recovery blob, is included in
     * Android Auto Backup, and `MainViewModel` re-onboards from it silently
     * whenever DataStore says "not onboarded" (MainViewModel.kt:191-196).
     * Disconnect left it on disk; both exit doors now go through the same wipe.
     */
    @Test
    fun `identity backup blob is always wiped`() {
        assertTrue(PrefsWipePolicy.shouldWipe("oshi_identity_backup"))
        assertTrue(PrefsWipePolicy.shouldWipe("oshi_secure_keys"))
    }

    /**
     * The eleven files the hand-written list had already lost track of. Two of
     * them are private key material: `oshi_ratchet` holds Double-Ratchet chain
     * keys, `v2_prekeys` holds X3DH private prekeys.
     */
    @Test
    fun `every preference file the old named list forgot is now wiped`() {
        val leftBehind = listOf(
            "oshi_ratchet",
            "oshi_ratchet_encrypted",
            "v2_prekeys",
            "v2_sessions_plain",
            "v2_sessions",
            "v2_router",
            "v2_config",
            "wallpaper",
            "groups",
            "pinned_messages",
            "safety_numbers",
            "checkin",
            "key_rotation",
            "oshi_ai",
            "oshi_prefs",
            "fcm_prefs",
            "mesh_booster",
            "app_review_prefs",
            "content_reports",
            "OshiPrefs"
        )
        for (name in leftBehind) {
            assertTrue("'$name' must be erased by an account wipe", PrefsWipePolicy.shouldWipe(name))
        }
    }

    /** Stores we do not own stay put — clearing them breaks the NEXT identity. */
    @Test
    fun `third party preference files are kept`() {
        val kept = listOf(
            "com.google.android.gms.appid",
            "com.google.firebase.messaging",
            "com.android.webview",
            "androidx.security.crypto",
            "WebViewChromiumPrefs",
            "osmdroid"
        )
        for (name in kept) {
            assertFalse("'$name' is not ours to erase", PrefsWipePolicy.shouldWipe(name))
        }
    }

    @Test
    fun `prefs name is the filename without its xml suffix`() {
        assertEquals("oshi_ratchet", PrefsWipePolicy.prefsNameOf("oshi_ratchet.xml"))
        assertNull(PrefsWipePolicy.prefsNameOf("oshi_ratchet.bak"))
        assertNull(PrefsWipePolicy.prefsNameOf("some.dir"))
    }

    // ─────────────────────────────────────────────────────────────────────
    // F-4 — the review prompt gate
    //
    // Moved to `RatingPrompter`, the portfolio's shared rating policy (a port of
    // Supernova iOS). Two of the three original defects were in this gate and
    // both are still pinned, here and in `RatingPrompterTest`:
    //   - it was `count == MESSAGES_THRESHOLD`, an exact equality;
    //   - the "already prompted" flag was written at the decision, not at the
    //     display, so the one prompt was spent on an attempt that drew nothing.
    //
    // What CHANGED deliberately: the budget is no longer one prompt for the life
    // of the install. It is Apple's and Play's own shape — three a year, 21 days
    // apart — because one lifetime prompt behind a pre-ask behind a ten-message
    // threshold is how the listing reached 0 ratings all-time.
    // ─────────────────────────────────────────────────────────────────────

    private class ReviewStore(
        override var sessionCount: Int = 5,
        override var ratingPromptSeen: Boolean = false,
        override var askEpochMillis: List<Long> = emptyList(),
        override var armedAtEpochMillis: Long? = null,
        override var armedWaivesNewness: Boolean = false,
        override var armedWaivesFirstSession: Boolean = false,
        override var legacyMigrated: Boolean = false,
        override var welcomeReplyMomentUsed: Boolean = false,
        override var firstSuccessMomentUsed: Boolean = false,
    ) : RatingStore

    private fun reviewPrompter(store: ReviewStore) = RatingPrompter(
        store = store,
        clock = { REVIEW_NOW },
        launcher = object : ReviewLauncher {
            override fun launch(activity: android.app.Activity) = Unit
        },
    )

    /**
     * The gate was `count == MESSAGES_THRESHOLD`. A single dropped increment —
     * process death between the two `apply()` calls — skipped the one lifetime
     * prompt permanently. Every count at or past the threshold is now eligible.
     */
    @Test
    fun `review gate is a threshold, not an exact equality`() {
        for (count in listOf(RatingPrompter.MESSAGES_THRESHOLD, 11, 500)) {
            assertTrue("$count messages", reviewPrompter(ReviewStore())
                .armIfAllowedAfterMessagesSent(count))
        }
    }

    @Test
    fun `review gate stays shut below the threshold`() {
        assertFalse(reviewPrompter(ReviewStore()).armIfAllowedAfterMessagesSent(0))
        assertFalse(
            reviewPrompter(ReviewStore())
                .armIfAllowedAfterMessagesSent(RatingPrompter.MESSAGES_THRESHOLD - 1),
        )
    }

    /**
     * An install that already had its one lifetime prompt is NOT retired for good
     * any more: the old flag migrates to one spent slot out of three, dated 21
     * days back, and the other two become available.
     */
    @Test
    fun `an install that already had its lifetime prompt gets the normal budget back`() {
        val store = ReviewStore(ratingPromptSeen = true)
        val p = reviewPrompter(store)

        assertTrue(p.armIfAllowedAfterMessagesSent(RatingPrompter.MESSAGES_THRESHOLD))
        assertTrue(p.deliverArmedRequest(canPresent = { true }, request = {}))
        assertEquals("one migrated slot plus this ask", 2, store.askEpochMillis.size)
        assertEquals(
            "the migrated slot is dated 21 days back, not now",
            REVIEW_NOW - RatingPrompter.MIN_DAYS_BETWEEN_MILLIS,
            store.askEpochMillis.first(),
        )
    }

    /** A busy screen must leave the token armed and spend nothing. */
    @Test
    fun `the budget is spent at the display, never at the decision`() {
        val store = ReviewStore()
        val p = reviewPrompter(store)
        assertTrue(p.armIfAllowedAfterMessagesSent(RatingPrompter.MESSAGES_THRESHOLD))
        assertTrue("arming spends nothing", store.askEpochMillis.isEmpty())

        assertFalse(p.deliverArmedRequest(canPresent = { false }, request = {}))
        assertTrue("a prompt that drew nothing spends nothing", store.askEpochMillis.isEmpty())
        assertTrue("and stays armed", p.hasArmedRequest)

        assertTrue(p.deliverArmedRequest(canPresent = { true }, request = {}))
        assertEquals(1, store.askEpochMillis.size)
    }

    // ─────────────────────────────────────────────────────────────────────
    // F-5 — "Synced · Last sync: today" on a virgin install
    // ─────────────────────────────────────────────────────────────────────

    /**
     * A cycle that never reached the server must not stamp `last_sync`. On
     * device, `shared_prefs/oshi_multidevice_sync.xml` on a brand-new install
     * held `last_sync = 1787430300254` with no device ever linked, because
     * every HTTP helper swallows its own failure and `Success` was
     * unconditional.
     */
    @Test
    fun `a sync cycle that reached nothing may not stamp a sync`() {
        val tally = SyncCycleTally()
        assertFalse(tally.didSync)
        assertTrue("nothing attempted is neither success nor failure", tally.idle)

        tally.serverFailed()
        tally.serverFailed()
        assertFalse("failures are not a sync", tally.didSync)
        assertFalse(tally.idle)
    }

    /** One 2xx is enough — an empty archive is still an answer from the server. */
    @Test
    fun `one successful call is enough to stamp a sync`() {
        val tally = SyncCycleTally()
        tally.serverFailed()
        tally.serverReached()
        assertTrue(tally.didSync)
        assertFalse(tally.idle)
        assertEquals(1, tally.reached)
        assertEquals(1, tally.failed)
    }

    // ─────────────────────────────────────────────────────────────────────
    // F-7 — onboarding pages
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Android once shipped three pages against iOS's eight, and the two SAFETY
     * disclosures (Good Conduct, Disclaimer) were among the missing ones. Since the
     * 2027 redesign both live on ONE page, [OnboardingPage.RULES]: it must follow
     * the slides, precede the terminal page, and be where Skip lands (a user can
     * skip the tour, never the rules). Its content is pinned by `OnboardingRedesignTest`.
     */
    @Test
    fun `onboarding ships the safety disclosures before the identity step`() {
        val order = OnboardingPage.ORDER
        val rules = order.indexOf(OnboardingPage.RULES)
        assertTrue("Rules page missing", rules >= 0)
        assertTrue("Rules must follow every slide", order.filter { it.isSlide }.all { order.indexOf(it) < rules })
        assertTrue("Rules must precede the terminal page", rules < order.indexOf(OnboardingPage.IDENTITY))
        assertEquals("Skip must stop at the rules", OnboardingPage.RULES, OnboardingFlow.SKIP_TARGET)
    }

    /**
     * The identity page must stay last: its primary button creates the identity.
     * A page appended after IDENTITY would strand the user on an unreachable action.
     */
    @Test
    fun `the identity page is the terminal onboarding page`() {
        assertEquals(OnboardingPage.IDENTITY, OnboardingPage.ORDER.last())
        assertEquals(OnboardingPage.IDENTITY, OnboardingFlow.steps(askNotifications = false).last())
        assertNull(OnboardingFlow.next(OnboardingPage.IDENTITY, askNotifications = true))
    }

    // ─────────────────────────────────────────────────────────────────────
    // F-10a — reporting
    // ─────────────────────────────────────────────────────────────────────

    private fun sampleReport(state: ReportDeliveryState = ReportDeliveryState.PENDING) = ContentReport(
        id = "11111111-2222-3333-4444-555555555555",
        subject = ReportSubject.CONTACT,
        subjectId = "hTiEnKV10MM9IfHxzvlJ59WZj2y4wM/LeERb/kcs4AE=",
        subjectName = "Bob",
        reason = ReportReason.HARASSMENT,
        details = "unsolicited messages",
        createdAt = 1_755_800_000_000L,
        state = state
    )

    /** Field-for-field port of iOS `ReportPayload` (ContactActionsView.swift:112-124). */
    @Test
    fun `report payload matches the iOS wire format`() {
        val json = JSONObject(
            ReportWireFormat.payloadJson(
                report = sampleReport(),
                reporterKey = "REPORTER_KEY",
                clientVersion = "1.6.3 (163)"
            )
        )
        assertEquals("11111111-2222-3333-4444-555555555555", json.getString("reportId"))
        assertEquals("contact", json.getString("subjectType"))
        assertEquals("hTiEnKV10MM9IfHxzvlJ59WZj2y4wM/LeERb/kcs4AE=", json.getString("subjectId"))
        assertEquals("harassment", json.getString("reason"))
        assertEquals("unsolicited messages", json.getString("details"))
        assertEquals("REPORTER_KEY", json.getString("reporterKey"))
        assertEquals("1.6.3 (163)", json.getString("clientVersion"))
        assertEquals("android", json.getString("platform"))
        // ISO-8601 with internet date-time options: seconds precision, Z suffix.
        assertTrue(
            json.getString("createdAt"),
            Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$""").matches(json.getString("createdAt"))
        )
    }

    /** Only what the reporter typed leaves the device — never the conversation. */
    @Test
    fun `report details are trimmed and capped`() {
        assertEquals("hello", ReportWireFormat.trimDetails("  hello \n"))
        assertEquals(
            ReportWireFormat.MAX_DETAIL_LENGTH,
            ReportWireFormat.trimDetails("x".repeat(5000)).length
        )
    }

    /**
     * One malformed row must not take the whole log with it. Decoding a list in
     * a single block is how a stored list of 46 items is lost to one unknown
     * value.
     */
    @Test
    fun `one malformed stored report does not empty the list`() {
        val goodRow = ReportWireFormat.encodeAll(listOf(sampleReport(ReportDeliveryState.SENT)))
            .removeSurrounding("[", "]")
        val withGarbage = "[$goodRow,{\"subject\":\"contact\"},{\"nope\":1}]"
        val decoded = ReportWireFormat.decodeAll(withGarbage)
        assertEquals(1, decoded.size)
        assertEquals("Bob", decoded[0].subjectName)
        assertEquals(ReportDeliveryState.SENT, decoded[0].state)
    }

    @Test
    fun `report round trips through the local store`() {
        val original = sampleReport(ReportDeliveryState.REJECTED)
        val decoded = ReportWireFormat.decodeAll(ReportWireFormat.encodeAll(listOf(original)))
        assertEquals(listOf(original), decoded)
    }

    @Test
    fun `unknown enum values degrade instead of dropping the row`() {
        val decoded = ReportWireFormat.decodeAll(
            """[{"id":"a","subject":"planet","subjectId":"k","reason":"vibes","state":"maybe"}]"""
        )
        assertEquals(1, decoded.size)
        assertEquals(ReportSubject.CONTACT, decoded[0].subject)
        assertEquals(ReportReason.OTHER, decoded[0].reason)
        assertEquals(ReportDeliveryState.PENDING, decoded[0].state)
    }

    private companion object {
        /** A fixed wall clock for the review-policy tests; only deltas matter. */
        const val REVIEW_NOW = 1_800_000_000_000L
    }
}
