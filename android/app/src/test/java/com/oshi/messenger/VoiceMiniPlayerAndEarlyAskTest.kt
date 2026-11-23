package com.oshi.messenger

import com.oshi.messenger.service.AudioPlayerManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.oshi.messenger.util.RatingPrompter
import com.oshi.messenger.util.RatingStore
import com.oshi.messenger.util.ReviewLauncher
import org.junit.Test

/**
 * Two of the 2026-10-09 iOS fixes, ported: the floating voice-note player's visibility
 * rule and the two one-off rating moments.
 *
 * Both are here rather than on a device because both are *decisions*, and in both cases
 * the iOS bug was a decision taken in the wrong place.
 */
class VoiceMiniPlayerAndEarlyAskTest {

    // =====================================================================
    // __VOICE_MINIPLAYER_2026_10_09__
    // =====================================================================

    @Test
    fun `the pill shows while a note plays with its bubble off screen`() {
        assertTrue(
            AudioPlayerManager.showsMiniPlayer(
                currentId = "m1", title = "Yassin", isLive = true,
                visibleOwners = emptySet(), dismissed = false
            )
        )
    }

    @Test
    fun `the pill hides while the bubble that owns the note is on screen`() {
        // That bubble already draws play/pause, a waveform and the speed control;
        // a floating copy on top of it is clutter, not a feature.
        assertFalse(
            AudioPlayerManager.showsMiniPlayer(
                currentId = "m1", title = "Yassin", isLive = true,
                visibleOwners = setOf("m1"), dismissed = false
            )
        )
        // Another voice bubble being visible is not the same thing.
        assertTrue(
            AudioPlayerManager.showsMiniPlayer(
                currentId = "m1", title = "Yassin", isLive = true,
                visibleOwners = setOf("m2", "m3"), dismissed = false
            )
        )
    }

    @Test
    fun `the pill goes away by itself when the note ends`() {
        // `isLive` is PLAYING-or-PAUSED. A finished note is neither, and nobody has
        // to dismiss anything — that is the "disappears at the end of playback" half
        // of the requirement.
        assertFalse(
            AudioPlayerManager.showsMiniPlayer(
                currentId = "m1", title = "Yassin", isLive = false,
                visibleOwners = emptySet(), dismissed = false
            )
        )
    }

    @Test
    fun `nothing loaded and an explicit dismissal both hide it`() {
        assertFalse(
            AudioPlayerManager.showsMiniPlayer(
                currentId = null, title = "Yassin", isLive = true,
                visibleOwners = emptySet(), dismissed = false
            )
        )
        assertFalse(
            AudioPlayerManager.showsMiniPlayer(
                currentId = "m1", title = "Yassin", isLive = true,
                visibleOwners = emptySet(), dismissed = true
            )
        )
    }

    @Test
    fun `a player with no title never raises the pill — that is the opt-in`() {
        // The record-preview bottom sheet and the view-once full-screen player drive
        // the SAME singleton and are consumed inside their own screen. A floating
        // pill over a modal sheet, or one offering to resume a view-once note from
        // another tab, is a bug. iOS draws the same line by keeping
        // `ViewOnceAudioPlayer` off `VoiceNotePlayback` entirely.
        assertFalse(
            AudioPlayerManager.showsMiniPlayer(
                currentId = "preview-robot", title = null, isLive = true,
                visibleOwners = emptySet(), dismissed = false
            )
        )
    }

    // =====================================================================
    // __EARLY_ASK_2026_10_09__ — now expressed against the shared RatingPrompter
    //
    // The early asks used to be "waive the ten-message threshold, keep every
    // cooldown of the pre-ask". There is no pre-ask any more (Play's design
    // guidelines forbid a question before the rating card), so what these pin
    // now is the thing that replaced it: a first delivery receipt or a first
    // contact ARMS the ask and lowers the newness floor, while ten messages
    // sent arms it without lowering anything.
    //
    // The full policy suite is `RatingPrompterTest`; these are the OSHI-specific
    // trigger shapes.
    // =====================================================================

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
        override fun launch(activity: android.app.Activity) { launches++ }
    }

    private val now = 1_800_000_000_000L

    private fun prompter(store: FakeStore) =
        RatingPrompter(store = store, clock = { now }, launcher = FakeLauncher())

    @Test
    fun `a first delivery receipt waives the message threshold`() {
        // One sent message, nowhere near ten: the ordinary path refuses it.
        val ordinary = prompter(FakeStore(sessionCount = 5))
        assertFalse(ordinary.armIfAllowedAfterMessagesSent(messagesSent = 1))
        assertFalse(ordinary.hasArmedRequest)

        // A receipt is proof OSHI worked for this person, so it arms on its own.
        val store = FakeStore(sessionCount = 5)
        val early = prompter(store)
        assertTrue(early.armIfAllowedAfterDelivery())
        assertTrue("a demonstrated success lowers the newness floor", store.armedWaivesNewness)
    }

    @Test
    fun `a first completed call waives the message threshold too`() {
        val store = FakeStore(sessionCount = 5)
        val p = prompter(store)
        assertTrue(p.armIfAllowedAfterFirstSuccess())
        assertTrue(store.armedWaivesNewness)
    }

    /**
     * What an early milestone must NOT waive: the budget. The old rule said the
     * same thing about its one lifetime prompt — a user who already had their
     * prompt is not asked again because they happened to add a contact.
     */
    @Test
    fun `an early milestone waives the threshold and nothing else`() {
        // Inside the (one-week) early spacing.
        val spaced = FakeStore(sessionCount = 5, askEpochMillis = listOf(now - 5 * DAY_MILLIS))
        assertFalse(prompter(spaced).armIfAllowedAfterDelivery())
        assertFalse(prompter(spaced).armIfAllowedAfterWelcomeReply())

        // Over the yearly budget.
        val spent = FakeStore(
            sessionCount = 5,
            askEpochMillis = listOf(now - 300 * DAY_MILLIS, now - 200 * DAY_MILLIS, now - 100 * DAY_MILLIS),
        )
        assertFalse(prompter(spent).armIfAllowedAfterDelivery())

        // And never on the very first launch, whatever earned it.
        val fresh = FakeStore(sessionCount = 1)
        val p = prompter(fresh)
        assertTrue("arming ignores the floor", p.armIfAllowedAfterDelivery())
        assertFalse(
            "delivery does not",
            p.deliverArmedRequest(canPresent = { true }, request = {}),
        )
        assertTrue("and the token is kept for the next launch", p.hasArmedRequest)
    }

    @Test
    fun `the ordinary ten-message path is unchanged`() {
        // The general engagement rule was deliberately NOT loosened: iOS states
        // that explicitly, and the Android equivalent of it is this threshold.
        val below = prompter(FakeStore(sessionCount = 5))
        assertFalse(below.armIfAllowedAfterMessagesSent(RatingPrompter.MESSAGES_THRESHOLD - 1))

        val store = FakeStore(sessionCount = 5)
        val at = prompter(store)
        assertTrue(at.armIfAllowedAfterMessagesSent(RatingPrompter.MESSAGES_THRESHOLD))
        assertFalse("an ordinary milestone does not lower the floor", store.armedWaivesNewness)
    }

    /**
     * 🔴 `>=`, NEVER `==`. The old gate was an exact equality, so the window was a
     * single message: one dropped increment — process death between two `apply()`
     * calls — closed it permanently.
     */
    @Test
    fun `the message gate is a floor, not an exact count`() {
        for (count in listOf(RatingPrompter.MESSAGES_THRESHOLD, 11, 500)) {
            assertTrue("$count messages", prompter(FakeStore(sessionCount = 5))
                .armIfAllowedAfterMessagesSent(count))
        }
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
