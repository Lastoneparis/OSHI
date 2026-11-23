package com.oshi.messenger

import com.oshi.messenger.service.ActiveConversation
import com.oshi.messenger.service.GroupManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The POLL-path group banner gate.
 *
 * `GroupManager.showGroupMessageNotification` raised a heads-up notification for a group
 * message even when that group was the conversation on screen — the message was already
 * visible, already being marked read by `GroupChatViewModel`'s arrival watcher, and the
 * banner was purely something to dismiss.
 *
 * The 2026-08-24 read-state pass gated the PUSH path (`FCMService.kt:650-658`) and the
 * 1:1 poll path (`MessageRepository.showMessageNotification`) on the same registry, but
 * `GroupManager.kt` was outside that pass's territory and was left as a named, documented
 * gap.
 *
 * There is exactly ONE registry — [ActiveConversation] — and this test drives it, not a
 * copy of it: [GroupManager.shouldRaiseGroupBanner] takes the answer as a parameter so the
 * decision is testable without an Android runtime, and production passes
 * `ActiveConversation.isGroupActive(groupId)` into it.
 */
class GroupBannerGateTest {

    private val onScreenGroup = "b7f1a2b0-1111-2222-3333-444455556666"
    private val otherGroup = "c0ffee00-1111-2222-3333-444455556666"

    @Before
    fun reset() = ActiveConversation.clear()

    @After
    fun tearDown() = ActiveConversation.clear()

    @Test
    fun `an unmuted group with nothing on screen rings`() {
        assertTrue(GroupManager.shouldRaiseGroupBanner(isMuted = false, onScreen = false))
    }

    @Test
    fun `the group the user is looking at never rings`() {
        assertFalse(GroupManager.shouldRaiseGroupBanner(isMuted = false, onScreen = true))
    }

    @Test
    fun `a muted group never rings, on screen or not`() {
        assertFalse(GroupManager.shouldRaiseGroupBanner(isMuted = true, onScreen = false))
        assertFalse(GroupManager.shouldRaiseGroupBanner(isMuted = true, onScreen = true))
    }

    // =====================================================================
    // Driven through the REAL registry, exactly as production wires it
    // =====================================================================

    @Test
    fun `entering a group silences its own banner and nobody else's`() {
        ActiveConversation.enterGroup(onScreenGroup)
        assertFalse(
            "a message for the group on screen must not raise a banner",
            GroupManager.shouldRaiseGroupBanner(false, ActiveConversation.isGroupActive(onScreenGroup))
        )
        assertTrue(
            "a message for a DIFFERENT group must still raise one",
            GroupManager.shouldRaiseGroupBanner(false, ActiveConversation.isGroupActive(otherGroup))
        )
    }

    @Test
    fun `leaving the group restores its banner`() {
        ActiveConversation.enterGroup(onScreenGroup)
        ActiveConversation.leaveGroup(onScreenGroup)
        assertTrue(
            "backgrounding the app must not silence the group for ever",
            GroupManager.shouldRaiseGroupBanner(false, ActiveConversation.isGroupActive(onScreenGroup))
        )
    }

    @Test
    fun `opening a 1-on-1 chat does not silence group banners`() {
        ActiveConversation.enterGroup(onScreenGroup)
        // Exactly one conversation is on screen: entering a direct thread retires the
        // group claim (`ActiveConversation.enterDirect`).
        ActiveConversation.enterDirect("BBBBsomepeerkey")
        assertTrue(
            GroupManager.shouldRaiseGroupBanner(false, ActiveConversation.isGroupActive(onScreenGroup))
        )
    }

    @Test
    fun `a blank group id is never on screen`() {
        ActiveConversation.enterGroup(onScreenGroup)
        // A push or poll payload that lost its groupId must not read as "the user is
        // looking at this" and swallow the banner.
        assertTrue(GroupManager.shouldRaiseGroupBanner(false, ActiveConversation.isGroupActive("")))
    }
}
