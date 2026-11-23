package com.oshi.messenger

import com.oshi.messenger.data.model.OshiReaction
import com.oshi.messenger.data.model.ReactionEmojis
import com.oshi.messenger.data.model.ReactionToggle
import com.oshi.messenger.ui.components.BubbleGestureRule
import com.oshi.messenger.ui.screens.formatConversationTimestamp
import com.oshi.messenger.ui.screens.formatGroupTimestamp
import com.oshi.messenger.ui.theme.AvatarIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __OSHI_SMILE_REACTION_2026_11_09__ · __AVATAR_COLOUR_LIST_EQUALS_CHAT_2026_11_09__ ·
 * __LONG_PRESS_ANYWHERE_ON_THE_BUBBLE_2026_11_09__ · __RELATIVE_TIMES_FROZE_2026_11_09__
 */
class OshiSmileAndListParityTest {

    // ------------------------------------------------------------ OSHI smile reaction

    @Test
    fun `the OSHI reaction is exactly U+2B55 U+1F60A on the wire`() {
        val expected = byteArrayOf(
            0xe2.toByte(), 0xad.toByte(), 0x95.toByte(),
            0xf0.toByte(), 0x9f.toByte(), 0x98.toByte(), 0x8a.toByte(),
        )
        assertArrayEquals(expected, OshiReaction.OSHI_SMILE.toByteArray(Charsets.UTF_8))
        assertEquals(listOf(0x2B55, 0x1F60A), OshiReaction.OSHI_SMILE.codePoints().toArray().toList())
    }

    @Test
    fun `no other value is ever drawn as the OSHI mark`() {
        for (emoji in ReactionEmojis.ALL) {
            assertFalse("$emoji drawn as the OSHI O", OshiReaction.isOshiSmile(emoji))
        }
        for (near in listOf("⭕", "😊", "⭕ 😊", "😊⭕", "⭕️😊", "⭕😊️", "", null)) {
            assertFalse("'$near' drawn as the OSHI O", OshiReaction.isOshiSmile(near))
        }
        assertTrue(OshiReaction.isOshiSmile("⭕😊"))
    }

    @Test
    fun `the picker leads with the OSHI O and the iOS-pinned lists are untouched`() {
        // __OSHI_O_REACTION_2026_11_15__ the plain O ("⭕") now leads the quick
        // row; the smiling O stays reachable in the expanded picker, right after it.
        assertEquals(OshiReaction.OSHI_O, OshiReaction.QUICK_ROW.first())
        // __O_BUBBLE_MASCOT_2026_11_16__ then the six mascots (OshiMascotTest);
        // the iOS-pinned lists below are still untouched.
        assertEquals(6, OshiReaction.QUICK_ROW.drop(1).size)
        assertEquals(listOf(OshiReaction.OSHI_O, OshiReaction.OSHI_SMILE), OshiReaction.PICKER_ALL.take(2))
        assertEquals(listOf("✅", "❌", "😢", "👍", "😘", "💯"), ReactionEmojis.QUICK)
        assertEquals(20, ReactionEmojis.ALL.size)
        assertFalse(ReactionEmojis.QUICK.contains(OshiReaction.OSHI_SMILE))
        assertFalse(ReactionEmojis.QUICK.contains(OshiReaction.OSHI_O))
    }

    @Test
    fun `double-tap toggles - add, remove, replace`() {
        val o = OshiReaction.OSHI_SMILE
        assertEquals(o, ReactionToggle.next(null, o))          // not reacted → add
        assertNull(ReactionToggle.next(o, o))                  // already mine → remove
        assertEquals(o, ReactionToggle.next(ReactionEmojis.THUMBS_UP, o)) // other → replace
    }

    @Test
    fun `both chat screens double-tap the OSHI O, not a thumbs-up`() {
        val chat = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/ChatScreen.kt")
        val dt = SourceText.window(chat, "onDoubleTap = {", 1200)
        assertTrue(dt.contains("OshiReaction.OSHI_SMILE"))
        assertFalse(dt.contains("THUMBS_UP"))
        val group = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/GroupChatScreen.kt")
        assertFalse("group double-tap is a one-way thumbs-up again", group.contains("onReact(ReactionEmojis.THUMBS_UP)"))
        assertTrue(group.contains("viewModel.toggleReaction("))
    }

    // ------------------------------------------------------------ avatar colour

    /** base64 with both '+' and '/' — see CanonicalIdentityTest. */
    private val standard = "++8//DRWeJq83vARIjNEVWZ3iJmqu8zd7v8ADz77734="
    private val urlUnpadded = "--8__DRWeJq83vARIjNEVWZ3iJmqu8zd7v8ADz77734"

    @Test
    fun `list and chat colour one contact identically`() {
        // The list hashes the stored (canonical) key; every other screen goes
        // through contactGradient. Same key → same gradient, and a wire spelling
        // of the same key cannot drift to another colour.
        assertEquals(AvatarIdentity.gradient(standard), AvatarIdentity.contactGradient(standard))
        assertEquals(AvatarIdentity.contactGradient(standard), AvatarIdentity.contactGradient(urlUnpadded))
    }

    @Test
    fun `a group is one colour in the list and inside the group, matching iOS uuidString`() {
        val lower = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
        val upper = lower.uppercase()
        assertEquals(AvatarIdentity.gradient(upper), AvatarIdentity.groupGradient(lower))
        assertEquals(AvatarIdentity.groupGradient(upper), AvatarIdentity.groupGradient(lower))
        // A UUID must NOT go through base64 canonicalisation ('-' would become '+');
        // v2 spec: group ids are keyed LOWERCASE on every platform.
        assertEquals(lower, AvatarIdentity.groupKey(upper))
    }

    @Test
    fun `the group list no longer has its own palette`() {
        val vm = SourceText.read("src/main/java/com/oshi/messenger/ui/viewmodel/GroupsViewModel.kt")
        assertFalse("GroupsViewModel grew its own six-colour palette back", vm.contains("0xFF4CD964"))
        assertTrue(SourceText.window(vm, "fun getGradientColors", 300).contains("AvatarIdentity.groupGradient"))
        val list = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/ConversationsScreen.kt")
        assertTrue(list.contains("AvatarIdentity.contactEntry("))
    }

    @Test
    fun `call screens draw the caller in their own colour`() {
        for (f in listOf("CallScreen.kt", "VoiceCallScreen.kt", "VideoCallScreen.kt", "IncomingCallOverlay.kt")) {
            val src = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/$f")
            assertTrue("$f has no ContactAvatar", src.contains("ContactAvatar("))
            assertFalse("$f is back to a fixed blue→purple", src.contains("0x992196F3"))
        }
    }

    // ------------------------------------------------------------ long-press anywhere

    @Test
    fun `message text only consumes a short tap on a link`() {
        assertTrue(BubbleGestureRule.textConsumesTap(hitsLink = true, pressDurationMs = 100, longPressTimeoutMs = 400))
        assertFalse("long-press on a link must reach the bubble",
            BubbleGestureRule.textConsumesTap(hitsLink = true, pressDurationMs = 600, longPressTimeoutMs = 400))
        assertFalse("a tap on plain text must reach the bubble",
            BubbleGestureRule.textConsumesTap(hitsLink = false, pressDurationMs = 100, longPressTimeoutMs = 400))
        assertTrue(BubbleGestureRule.childForwardsLongPress {})
        assertFalse(BubbleGestureRule.childForwardsLongPress(null))
    }

    @Test
    fun `the message text is not a ClickableText and the bubble children give long-press back`() {
        val link = SourceText.read("src/main/java/com/oshi/messenger/ui/components/LinkPreview.kt")
        assertFalse("ClickableText consumes every down on the paragraph", link.contains("ClickableText("))
        val chat = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/ChatScreen.kt")
        assertTrue(chat.contains("LocalBubbleLongPress provides"))
        val quote = SourceText.window(chat, "if (replyData != null) {", 700)
        assertTrue("reply quote swallows long-press again", quote.contains(".bubbleClickable"))
        val group = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/GroupChatScreen.kt")
        assertTrue(group.contains("LocalBubbleLongPress provides"))
    }

    // ------------------------------------------------------------ relative times

    @Test
    fun `the list label moves with the clock it is given`() {
        val cal = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 9, 12, 0, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val sent = cal.timeInMillis
        assertEquals("Just now", formatConversationTimestamp(sent, sent + 10_000))
        assertEquals("5m ago", formatConversationTimestamp(sent, sent + 5 * 60_000))
        assertEquals("Now", formatGroupTimestamp(sent, sent + 10_000))
        assertEquals("5m", formatGroupTimestamp(sent, sent + 5 * 60_000))
    }

    @Test
    fun `both lists read one shared clock`() {
        for (f in listOf("ConversationsScreen.kt", "GroupsScreen.kt")) {
            val src = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/$f")
            assertTrue("$f has no shared list clock", src.contains("rememberListClock()"))
        }
        val clock = SourceText.read("src/main/java/com/oshi/messenger/ui/components/ListClock.kt")
        assertTrue("the clock must refresh on resume", clock.contains("Lifecycle.Event.ON_RESUME"))
    }
}
