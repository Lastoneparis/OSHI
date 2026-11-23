package com.oshi.messenger

import android.app.Activity
import com.oshi.messenger.service.WelcomeMessage
import com.oshi.messenger.util.RatingPrompter
import com.oshi.messenger.util.RatingStore
import com.oshi.messenger.util.ReviewLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __RATING_THREE_MOMENTS_2026_11_15__ The native Play review card is asked for at
 * exactly three moments, at most three times per rolling 365 days:
 *
 *  1. the user's first message in the welcome thread — may fire in the FIRST session;
 *  2. the first delivery receipt from a real contact, or the first completed call;
 *  3. ten messages sent, at least 21 days after the previous ask.
 *
 * Pure JVM: the store, the clock and the launcher are fakes.
 */
class RatingThreeMomentsTest {

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

    private var now = 1_790_000_000_000L
    private var requests = 0

    private fun prompter(store: FakeStore) = RatingPrompter(
        store = store,
        clock = { now },
        launcher = object : ReviewLauncher {
            override fun launch(activity: Activity) = Unit
        },
    )

    private fun RatingPrompter.deliver(free: Boolean = true) =
        deliverArmedRequest(canPresent = { free }, request = { requests++ })

    private fun days(n: Long) = n * 86_400_000L
    private fun minutes(n: Long) = n * 60_000L

    // MARK: Moment 1 — the early ask

    @Test
    fun `moment 1 fires in the very first session`() {
        // `registerSession` has run once: this is the first launch of the install.
        val store = FakeStore(sessionCount = 1)
        val p = prompter(store)

        assertTrue(p.onWelcomeReply())
        assertTrue(store.armedWaivesFirstSession)
        assertTrue("no session floor for the welcome reply", p.deliver())
        assertEquals(1, requests)
        assertEquals(listOf(now), store.askEpochMillis)
    }

    @Test
    fun `moment 1 still waits for a calm screen and spends nothing while it waits`() {
        val store = FakeStore(sessionCount = 1)
        val p = prompter(store)
        assertTrue(p.onWelcomeReply())

        // Keyboard up / a sheet / a call: the host says "not now".
        assertFalse(p.deliver(free = false))
        assertTrue(p.hasArmedRequest)
        assertTrue(store.askEpochMillis.isEmpty())

        p.setPresented(RatingPrompter.ExclusiveMoment.CALL, isPresented = true)
        assertFalse("never on a call", p.deliver())
        p.setPresented(RatingPrompter.ExclusiveMoment.CALL, isPresented = false)
        now += 10_000L

        assertTrue(p.deliver())
        assertEquals(1, requests)
    }

    @Test
    fun `moment 1 fires once per install`() {
        val store = FakeStore(sessionCount = 1)
        val p = prompter(store)
        assertTrue(p.onWelcomeReply())
        assertTrue(p.deliver())

        now += days(400) // budget fully restored
        assertFalse("a later welcome message is not moment 1 again", p.onWelcomeReply())
        assertFalse(p.hasArmedRequest)
    }

    @Test
    fun `a failed send right before silences the ask for ten minutes`() {
        val store = FakeStore(sessionCount = 1)
        val p = prompter(store)
        assertTrue(p.onWelcomeReply())

        p.recordFriction()
        assertFalse(p.deliver())
        assertTrue("kept armed", p.hasArmedRequest)

        now += minutes(9)
        assertFalse(p.deliver())
        now += minutes(2)
        assertTrue(p.deliver())
    }

    // MARK: Moment 2 — first proof it worked

    @Test
    fun `moment 2 does not need moment 1 and lowers the floor to two sessions`() {
        val store = FakeStore(sessionCount = 1)
        val p = prompter(store)
        assertTrue(p.onFirstSuccess())
        assertFalse("not on the first launch", store.armedWaivesFirstSession)
        assertFalse(p.deliver())

        p.registerSession()
        assertTrue(p.deliver())
    }

    @Test
    fun `moment 2 is owed, not lost, when it lands just after the welcome ask`() {
        val store = FakeStore(sessionCount = 1)
        val p = prompter(store)
        assertTrue(p.onWelcomeReply())
        assertTrue(p.deliver())

        // Day 2: the first receipt from a friend. Too close to the welcome ask.
        now += days(2)
        p.registerSession()
        assertFalse(p.onFirstSuccess())
        assertFalse("still owed", store.firstSuccessMomentUsed)

        // Day 8: the next receipt (or a completed call) collects it.
        now += days(6)
        assertTrue(p.onFirstSuccess())
        assertFalse("used only once it is actually shown", store.firstSuccessMomentUsed)
        assertTrue(p.deliver())
        assertTrue(store.firstSuccessMomentUsed)
        assertEquals(2, requests)

        now += days(30)
        assertFalse("and only once", p.onFirstSuccess())
    }

    @Test
    fun `moment 2 is not lost to a still-armed welcome ask either`() {
        val store = FakeStore(sessionCount = 1)
        val p = prompter(store)
        assertTrue(p.onWelcomeReply())
        // Not yet delivered (screen busy) when a receipt arrives.
        assertFalse(p.onFirstSuccess())
        assertFalse(store.firstSuccessMomentUsed)
    }

    @Test
    fun `moment 2 armed on a screen that never calmed down is still owed`() {
        val store = FakeStore(sessionCount = 3)
        val p = prompter(store)
        assertTrue(p.onFirstSuccess())
        assertFalse(p.deliver(free = false))

        now += 25 * 3_600_000L // the 24 h arm lifetime runs out
        assertFalse(p.deliver())
        assertFalse(p.hasArmedRequest)
        assertFalse(store.firstSuccessMomentUsed)

        assertTrue("the next receipt re-arms it", p.onFirstSuccess())
        assertTrue(p.deliver())
        assertTrue(store.firstSuccessMomentUsed)
    }

    @Test
    fun `the welcome ask does not use up moment 2`() {
        val store = FakeStore(sessionCount = 1)
        val p = prompter(store)
        assertTrue(p.onWelcomeReply())
        assertTrue(p.deliver())
        assertFalse(store.firstSuccessMomentUsed)
    }

    // MARK: Moment 3 — ten messages, three weeks after the previous ask

    @Test
    fun `moment 3 waits three weeks after the previous ask`() {
        val store = FakeStore(sessionCount = 5)
        val p = prompter(store)
        assertTrue(p.onFirstSuccess())
        assertTrue(p.deliver())

        now += days(20)
        assertFalse(p.armIfAllowedAfterMessagesSent(RatingPrompter.MESSAGES_THRESHOLD))
        now += days(2)
        assertTrue(p.armIfAllowedAfterMessagesSent(RatingPrompter.MESSAGES_THRESHOLD))
        assertTrue(p.deliver())
    }

    @Test
    fun `moment 3 needs about ten messages`() {
        val p = prompter(FakeStore(sessionCount = 5))
        assertFalse(p.armIfAllowedAfterMessagesSent(RatingPrompter.MESSAGES_THRESHOLD - 1))
        assertTrue(p.armIfAllowedAfterMessagesSent(RatingPrompter.MESSAGES_THRESHOLD))
    }

    @Test
    fun `moment 3 keeps the ordinary session floor`() {
        val p = prompter(FakeStore(sessionCount = 2))
        assertTrue(p.armIfAllowedAfterMessagesSent(RatingPrompter.MESSAGES_THRESHOLD))
        assertFalse(p.deliver())
    }

    // MARK: The yearly ceiling

    @Test
    fun `the three moments in a row, then a fourth ask inside 365 days is refused`() {
        val store = FakeStore(sessionCount = 1)
        val p = prompter(store)
        val start = now

        // Day 0, first session.
        assertTrue(p.onWelcomeReply())
        assertTrue(p.deliver())

        // Day 8.
        now = start + days(8)
        p.registerSession()
        assertTrue(p.onFirstSuccess())
        assertTrue(p.deliver())

        // Day 30.
        now = start + days(30)
        p.registerSession()
        assertTrue(p.armIfAllowedAfterMessagesSent(25))
        assertTrue(p.deliver())
        assertEquals(3, requests)

        // Day 60, 200, 364: nothing arms, whatever the trigger.
        for (day in listOf(60L, 200L, 364L)) {
            now = start + days(day)
            assertFalse("day $day", p.armIfAllowedAfterMessagesSent(500))
            assertFalse("day $day", p.armIfAllowedAfterWelcomeReply())
            assertFalse("day $day", p.armIfAllowedAfterFirstSuccess())
            assertFalse(p.deliver())
        }
        assertEquals(3, requests)

        // Day 366: the first slot has rolled out of the window.
        now = start + days(366)
        assertTrue(p.armIfAllowedAfterMessagesSent(500))
        assertTrue(p.deliver())
        assertEquals(4, requests)
    }

    @Test
    fun `a fourth ask is refused at delivery too, if the slots were spent after arming`() {
        val store = FakeStore(sessionCount = 5)
        val p = prompter(store)
        assertTrue(p.armIfAllowedAfterMessagesSent(10))
        store.askEpochMillis = listOf(now - days(100), now - days(70), now - days(40))
        assertFalse(p.deliver())
        assertEquals(0, requests)
    }

    // MARK: Who counts as the welcome thread

    @Test
    fun `the welcome author is recognised in any base64 spelling`() {
        val key = WelcomeMessage.AUTHOR_PUBLIC_KEY
        assertTrue(WelcomeMessage.isAuthor(key))
        val urlSafeUnpadded = key.replace('+', '-').replace('/', '_').trimEnd('=')
        assertTrue(WelcomeMessage.isAuthor(urlSafeUnpadded))
        assertFalse(WelcomeMessage.isAuthor("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
    }

    // MARK: The wiring, pinned on the source (no Android runtime in unit tests)

    private fun source(path: String): String {
        val root = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
        return File(root, path).readText()
    }

    @Test
    fun `every text send reports its recipient so the welcome reply can be told apart`() {
        val repo = source("com/oshi/messenger/data/repository/MessageRepository.kt")
        assertFalse(
            "a bare trackMessageSent(context) cannot see the welcome thread",
            repo.contains("AppReviewManager.trackMessageSent(context)"),
        )
        assertTrue(repo.contains("AppReviewManager.trackMessageSent(context, recipientPublicKey"))
        assertFalse("first contact is no longer a moment", repo.contains("trackContactAdded("))
    }

    @Test
    fun `the delivery receipt reaches moment 2 even when the transport already set isDelivered`() {
        val repo = source("com/oshi/messenger/data/repository/MessageRepository.kt")
        val start = repo.indexOf("private suspend fun handleDeliveryReceipt")
        val body = repo.substring(start, repo.indexOf("private suspend fun markSendFailed"))
        val gate = body.indexOf("!message.isDelivered")
        val track = body.indexOf("AppReviewManager.trackMessageDelivered(context, senderPublicKey)")
        assertTrue(track > 0)
        // The trigger sits after the `!isDelivered` block has closed, not inside it.
        val closing = body.indexOf("}", gate)
        assertTrue("trigger must be outside the !isDelivered block", track > closing)
    }

    @Test
    fun `both call managers arm moment 2 on a completed call`() {
        for (f in listOf("CallManager.kt", "EnhancedCallManager.kt")) {
            val src = source("com/oshi/messenger/service/$f")
            assertTrue(f, src.contains("AppReviewManager.trackCallCompleted(context)"))
            assertTrue(f, src.contains("AppReviewManager.trackFriction(context)"))
        }
    }
}
