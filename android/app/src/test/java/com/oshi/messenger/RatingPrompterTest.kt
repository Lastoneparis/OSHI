package com.oshi.messenger

import android.app.Activity
import com.oshi.messenger.util.RatingPrompter
import com.oshi.messenger.util.RatingStore
import com.oshi.messenger.util.ReviewLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The review-timing policy: arm first, charge second.
 *
 * A port of the Supernova suite (`supernova-android/.../RatingPrompterTest.kt`,
 * itself a port of iOS `Tests/RatingPrompterDeliveryTests.swift`), so one reading
 * covers the whole portfolio. Pure JVM — the store, the clock and the Play
 * launcher are all fakes, which is the point of keeping the rules I/O-free.
 *
 * The OSHI-specific trigger shapes (a delivery receipt, a first contact, ten
 * messages sent) are in `VoiceMiniPlayerAndEarlyAskTest`; the budget-at-display
 * invariant is additionally pinned in `OptionsParityFixesTest` (F-4).
 */
class RatingPrompterTest {

    private class FakeStore(
        override var sessionCount: Int = 0,
        override var ratingPromptSeen: Boolean = false,
        override var askEpochMillis: List<Long> = emptyList(),
        override var armedAtEpochMillis: Long? = null,
        override var armedWaivesNewness: Boolean = false,
        override var armedWaivesFirstSession: Boolean = false,
        override var legacyMigrated: Boolean = false,
        override var welcomeReplyMomentUsed: Boolean = false,
        override var firstSuccessMomentUsed: Boolean = false,
    ) : RatingStore

    private class FakeLauncher : ReviewLauncher {
        var launches = 0
        override fun launch(activity: Activity) {
            launches++
        }
    }

    private var now = 1_786_000_000_000L // a fixed wall clock; only deltas matter
    private var requests = 0

    private fun prompter(store: FakeStore) =
        RatingPrompter(store = store, clock = { now }, launcher = FakeLauncher())

    private fun days(n: Long) = n * 86_400_000L
    private fun hours(n: Long) = n * 3_600_000L
    private fun seconds(n: Long) = n * 1_000L

    private fun RatingPrompter.deliver(free: Boolean = true) =
        deliverArmedRequest(canPresent = { free }, request = { requests++ })

    /** Arm after an ordinary milestone and deliver on a calm screen. */
    private fun RatingPrompter.askNow() = armOrdinary() && deliver()

    /** Ten messages sent: real use, but not proof of anything on its own. */
    private fun RatingPrompter.armOrdinary() =
        armIfAllowedAfterMessagesSent(RatingPrompter.MESSAGES_THRESHOLD)

    // MARK: Arming spends nothing

    @Test
    fun `arming records a token but spends no budget`() {
        val store = FakeStore(sessionCount = 5)
        val prompter = prompter(store)

        assertTrue(prompter.armOrdinary())
        assertTrue(prompter.hasArmedRequest)
        assertTrue(store.askEpochMillis.isEmpty())
        assertEquals(0, requests)
    }

    @Test
    fun `a second milestone does not re-arm an armed request`() {
        val prompter = prompter(FakeStore(sessionCount = 5))
        assertTrue(prompter.armOrdinary())
        assertFalse(prompter.armOrdinary())
        assertFalse(prompter.armIfAllowedAfterDelivery())
        assertFalse(prompter.armIfAllowedAfterWelcomeReply())
    }

    @Test
    fun `the ordinary gate refuses someone who has barely started`() {
        val store = FakeStore(sessionCount = 5)
        val prompter = prompter(store)

        assertFalse(prompter.armIfAllowedAfterMessagesSent(1))
        assertFalse(prompter.hasArmedRequest)
    }

    /**
     * A delivery receipt is proof OSHI worked for this person: the message reached
     * its peer, not merely a transport. That is the hero moment, and it lowers the
     * newness floor.
     */
    @Test
    fun `a delivery receipt earns the ask on its own and lowers the floor`() {
        val store = FakeStore(sessionCount = 5)
        val prompter = prompter(store)

        assertTrue(prompter.armIfAllowedAfterDelivery())
        assertTrue(store.armedWaivesNewness)
    }

    @Test
    fun `a first completed call earns it too`() {
        val store = FakeStore(sessionCount = 5)
        assertTrue(prompter(store).armIfAllowedAfterFirstSuccess())
        assertTrue(store.armedWaivesNewness)
        assertFalse("only the welcome reply waives the first session", store.armedWaivesFirstSession)
    }

    @Test
    fun `nothing armed means nothing delivered`() {
        val prompter = prompter(FakeStore(sessionCount = 9))
        assertFalse(prompter.deliver())
        assertEquals(0, requests)
    }

    // MARK: Delivery is the only place a slot is spent

    @Test
    fun `delivery on a calm screen launches the flow and spends one slot`() {
        val store = FakeStore(sessionCount = 3)
        val prompter = prompter(store)

        assertTrue(prompter.armOrdinary())
        assertTrue(prompter.deliver())
        assertEquals(1, requests)
        assertEquals(listOf(now), store.askEpochMillis)
        assertTrue(store.ratingPromptSeen)
        assertFalse("the token is consumed", prompter.hasArmedRequest)
        assertFalse("and cannot fire twice", prompter.deliver())
    }

    /**
     * The defect the old `Engagement.shouldAskForReview()` shipped with: a deck
     * finished behind the paywall spent one of three yearly asks and the 21-day
     * spacing without a single pixel being drawn.
     */
    @Test
    fun `a busy screen keeps the request armed and spends nothing`() {
        val store = FakeStore(sessionCount = 9)
        val prompter = prompter(store)
        prompter.armOrdinary()

        assertFalse(prompter.deliver(free = false))
        assertTrue(prompter.hasArmedRequest)
        assertTrue(store.askEpochMillis.isEmpty())
        assertEquals(0, requests)

        now += 30 * 60 * 1_000L
        assertTrue("the next calm moment delivers it", prompter.deliver())
        assertEquals(1, requests)
    }

    @Test
    fun `an armed request expires after a day instead of firing out of context`() {
        val store = FakeStore(sessionCount = 9)
        val prompter = prompter(store)
        prompter.armOrdinary()

        now += hours(25)
        assertFalse(prompter.deliver())
        assertFalse(prompter.hasArmedRequest)
        assertTrue(store.askEpochMillis.isEmpty())
        assertNull(store.armedAtEpochMillis)
    }

    // MARK: Newness floor (never on the first launch)

    @Test
    fun `an ordinary success is not delivered in the first sessions, but stays armed`() {
        val store = FakeStore(sessionCount = 2)
        val prompter = prompter(store)

        assertTrue("arming ignores the floor", prompter.armOrdinary())
        assertFalse(prompter.deliver())
        assertTrue(prompter.hasArmedRequest)

        prompter.registerSession() // the third launch
        assertTrue(prompter.deliver())
    }

    @Test
    fun `a first delivery receipt on launch two is kept and delivered`() {
        val prompter = prompter(FakeStore(sessionCount = 2))
        assertTrue(prompter.armIfAllowedAfterDelivery())
        assertTrue("a demonstrated success lowers the floor to two launches", prompter.deliver())
    }

    @Test
    fun `nothing is ever delivered on the first launch, not even a delivery receipt`() {
        val prompter = prompter(FakeStore(sessionCount = 1))

        assertTrue(prompter.armIfAllowedAfterDelivery())
        assertFalse(prompter.deliver())
        assertTrue("kept for the next launch", prompter.hasArmedRequest)

        prompter.registerSession()
        assertTrue(prompter.deliver())
    }

    @Test
    fun `registerSession counts cold launches`() {
        val store = FakeStore(sessionCount = 0)
        val prompter = prompter(store)
        repeat(3) { prompter.registerSession() }
        assertEquals(3, store.sessionCount)
        assertTrue(prompter.askNow())
    }

    // MARK: Budget (3 per rolling year, 21 days apart)

    @Test
    fun `asks are spaced at least three weeks apart`() {
        val prompter = prompter(FakeStore(sessionCount = 5))

        assertTrue(prompter.askNow())
        now += days(20)
        assertFalse("arming is refused inside the spacing", prompter.armOrdinary())
        now += days(2)
        assertTrue(prompter.askNow())
    }

    @Test
    fun `a slot spent after arming is re-checked at delivery`() {
        val store = FakeStore(sessionCount = 5)
        val prompter = prompter(store)
        prompter.armOrdinary()
        // Another path spent a slot in between (restored prefs, a second process).
        store.askEpochMillis = listOf(now)
        assertFalse(prompter.deliver())
        assertEquals(0, requests)
    }

    @Test
    fun `the rolling-year budget is three asks`() {
        val store = FakeStore(sessionCount = 5)
        val prompter = prompter(store)

        repeat(3) {
            assertTrue(prompter.askNow())
            now += days(22)
        }
        assertFalse("a fourth ask inside the year must be refused", prompter.armOrdinary())
        assertEquals(3, store.askEpochMillis.size)

        now += days(300)
        assertTrue(prompter.askNow())
    }

    /**
     * An install that had its one lifetime prompt under the retired policy keeps
     * one slot spent, but is not handed a fresh 21-day silence on update day — and
     * gets the other two slots back.
     */
    @Test
    fun `an upgrader carrying only the old flag keeps one slot spent but no fresh silence`() {
        val store = FakeStore(sessionCount = 9, ratingPromptSeen = true)
        val prompter = prompter(store)

        assertTrue("the migrated slot is dated 21 days back", prompter.askNow())
        assertEquals(2, store.askEpochMillis.size)
        assertEquals(now - RatingPrompter.MIN_DAYS_BETWEEN_MILLIS, store.askEpochMillis.first())
        assertTrue(store.legacyMigrated)
    }

    @Test
    fun `an install with real ask dates is not migrated again`() {
        val earlier = now - days(30)
        val store = FakeStore(sessionCount = 9, ratingPromptSeen = true, askEpochMillis = listOf(earlier))
        val prompter = prompter(store)

        assertTrue(prompter.askNow())
        assertEquals(listOf(earlier, now), store.askEpochMillis)
    }

    // MARK: The exclusions

    @Test
    fun `the flow never fires while Play's forced-update screen is up, nor just after`() {
        val store = FakeStore(sessionCount = 9)
        val prompter = prompter(store)
        prompter.armOrdinary()

        prompter.setPresented(RatingPrompter.ExclusiveMoment.FORCED_UPDATE, isPresented = true)
        assertFalse(prompter.deliver())
        assertTrue(store.askEpochMillis.isEmpty())

        prompter.setPresented(RatingPrompter.ExclusiveMoment.FORCED_UPDATE, isPresented = false)
        assertFalse("inside the cooldown", prompter.deliver())

        now += seconds(10)
        assertTrue(prompter.deliver())
    }

    @Test
    fun `a sheet on screen is not a calm screen, nor are the seconds after it`() {
        val store = FakeStore(sessionCount = 9)
        val prompter = prompter(store)
        prompter.armOrdinary()

        prompter.setPresented(RatingPrompter.ExclusiveMoment.SHEET, isPresented = true)
        assertFalse("a sheet is up", prompter.deliver())
        assertTrue("nothing was spent while it was up", store.askEpochMillis.isEmpty())
        assertTrue("the request stays armed for a calm moment", prompter.hasArmedRequest)

        prompter.setPresented(RatingPrompter.ExclusiveMoment.SHEET, isPresented = false)
        now += seconds(10)
        assertTrue(prompter.deliver())
    }

    /** A call is the one thing in OSHI that must never be covered by anything. */
    @Test
    fun `the card never lands on a call`() {
        val prompter = prompter(FakeStore(sessionCount = 9))
        prompter.armIfAllowedAfterDelivery()

        prompter.setPresented(RatingPrompter.ExclusiveMoment.CALL, isPresented = true)
        assertFalse(prompter.deliver())
        assertTrue(prompter.hasArmedRequest)

        prompter.setPresented(RatingPrompter.ExclusiveMoment.CALL, isPresented = false)
        now += seconds(10)
        assertTrue(prompter.deliver())
    }

    /** The app lock is a biometric prompt: its own window, invisible to the host. */
    @Test
    fun `the card never lands on the app lock`() {
        val prompter = prompter(FakeStore(sessionCount = 9))
        prompter.armIfAllowedAfterDelivery()

        prompter.setPresented(RatingPrompter.ExclusiveMoment.APP_LOCK, isPresented = true)
        assertFalse(prompter.deliver())
        assertTrue(prompter.hasArmedRequest)

        prompter.setPresented(RatingPrompter.ExclusiveMoment.APP_LOCK, isPresented = false)
        now += seconds(10)
        assertTrue(prompter.deliver())
    }

    @Test
    fun `both surfaces have to close before the flow is allowed`() {
        val prompter = prompter(FakeStore(sessionCount = 9))
        prompter.armOrdinary()

        prompter.setPresented(RatingPrompter.ExclusiveMoment.FORCED_UPDATE, isPresented = true)
        prompter.setPresented(RatingPrompter.ExclusiveMoment.SHARE_SHEET, isPresented = true)
        prompter.setPresented(RatingPrompter.ExclusiveMoment.FORCED_UPDATE, isPresented = false)

        now += seconds(10)
        assertFalse("the share sheet is still up", prompter.deliver())

        prompter.setPresented(RatingPrompter.ExclusiveMoment.SHARE_SHEET, isPresented = false)
        now += seconds(10)
        assertTrue(prompter.deliver())
    }

    // MARK: Parity with the iOS policy file

    @Test
    fun `the budget and lifetime numbers match the Supernova iOS policy`() {
        val ios = File(System.getProperty("user.home"), "supernova/Sources/Services/RatingPrompter.swift")
        if (!ios.exists()) return // the iOS tree is not on every machine
        val src = ios.readText()
        assertTrue(src.contains("minSessions = ${RatingPrompter.MIN_SESSIONS}"))
        assertTrue(src.contains("maxPerYear = ${RatingPrompter.MAX_PER_YEAR}"))
        assertTrue(src.contains("minDaysBetween = ${RatingPrompter.MIN_DAYS_BETWEEN_MILLIS / 86_400_000L}.0"))
        assertTrue(src.contains("armedLifetime: TimeInterval = ${RatingPrompter.ARMED_LIFETIME_MILLIS / 3_600_000L} * 3_600"))
    }

    // MARK: Google Play policy guards on the source itself

    /**
     * Play's in-app review design guidelines: "Your app shouldn't ask the user any
     * questions before or while presenting the rating button or card, including
     * questions about their opinion (such as 'Do you like the app?')". The Settings
     * "Rate" row is user-initiated and open to everyone, which Play allows; what is
     * forbidden is a question that *gates* the flow.
     */
    @Test
    fun `the app never asks the user a question before the Play review flow`() {
        val root = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
        // Comments are stripped: the policy and its host both name the retired
        // pre-ask on purpose, to record why it is gone. A test that cannot tell a
        // call from an explanation of a call would have to ban writing it down.
        val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val lineComment = Regex("""//.*""")
        val sources = root.walkTopDown()
            .filter { it.extension == "kt" }
            .joinToString("\n") {
                it.readText().replace(blockComment, "").replace(lineComment, "")
            }

        for (banned in listOf("Enjoying OSHI", "EnjoyingOshiSheet", "onPreAskPositive",
                              "preAskVisible", "Do you like OSHI")) {
            assertFalse("found \"$banned\": Play forbids pre-prompts", sources.contains(banned))
        }
        assertTrue("the prompt is Play's own card", sources.contains("launchReviewFlow"))
    }

    /**
     * Nothing may reinstate the budget-at-decision defect through the old door.
     *
     * Comments are stripped before the search: several of them name the retired
     * policy on purpose, to say why it is retired, and a test that cannot tell a
     * call from an explanation of a call would either fail on its own
     * documentation or have to ban writing it down.
     */
    @Test
    fun `no call site is left on the retired ReviewPromptPolicy`() {
        val root = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
        val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val lineComment = Regex("""//.*""")
        val offenders = root.walkTopDown()
            .filter { it.extension == "kt" && it.name != "RatingPrompter.kt" && it.name != "AppReviewManager.kt" }
            .filter { file ->
                file.readText()
                    .replace(blockComment, "")
                    .replace(lineComment, "")
                    .contains("ReviewPromptPolicy")
            }
            .map { it.name }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
