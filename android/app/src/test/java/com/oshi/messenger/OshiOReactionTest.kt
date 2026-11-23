package com.oshi.messenger

import androidx.compose.ui.unit.sp
import com.oshi.messenger.data.model.OshiReaction
import com.oshi.messenger.data.model.ReactionEmojis
import com.oshi.messenger.data.model.ReactionSummary
import com.oshi.messenger.data.model.ReactionToggle
import com.oshi.messenger.ui.components.OshiReactionMetrics
import com.oshi.messenger.ui.components.ReactionBarMetrics
import com.oshi.messenger.ui.components.reactionPreviewInlineContent
import com.oshi.messenger.ui.components.reactionPreviewText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __OSHI_O_REACTION_2026_11_15__ · __REACTION_BAR_ONE_COMPONENT_2026_11_15__
 *
 * The OSHI "O" reaction travels as the plain "⭕" (U+2B55, no variation
 * selector) through the existing reaction payload and is DRAWN as the brand O
 * wherever a reaction is rendered. Same wire value on iOS.
 */
class OshiOReactionTest {

    // ------------------------------------------------------------ wire value

    @Test
    fun `the OSHI O is exactly U+2B55 on the wire, no variation selector`() {
        assertArrayEquals(
            byteArrayOf(0xe2.toByte(), 0xad.toByte(), 0x95.toByte()),
            OshiReaction.OSHI_O.toByteArray(Charsets.UTF_8)
        )
        assertEquals(listOf(0x2B55), OshiReaction.OSHI_O.codePoints().toArray().toList())
    }

    @Test
    fun `only the exact value maps to the OSHI O drawing`() {
        assertTrue(OshiReaction.isOshiO("⭕"))
        for (near in listOf("⭕️", "⭕😊", " ⭕", "⭕ ", "○", "◯", "O", "", null)) {
            assertFalse("'$near' drawn as the OSHI O", OshiReaction.isOshiO(near))
        }
        // The smiling O keeps its own drawing.
        assertFalse(OshiReaction.isOshiO(OshiReaction.OSHI_SMILE))
        assertFalse(OshiReaction.isOshiSmile(OshiReaction.OSHI_O))
    }

    @Test
    fun `both OSHI values are marks and no iOS emoji is`() {
        assertTrue(OshiReaction.isOshiMark(OshiReaction.OSHI_O))
        assertTrue(OshiReaction.isOshiMark(OshiReaction.OSHI_SMILE))
        for (emoji in ReactionEmojis.ALL) assertFalse(emoji, OshiReaction.isOshiMark(emoji))
    }

    // ------------------------------------------------------------ picker order

    @Test
    fun `quick bar is OSHI O first then the six O-bubble mascots`() {
        // __O_BUBBLE_MASCOT_2026_11_16__ was ⭕ ✅ ❌ 😢 👍 😘 💯 (iOS quickReactions);
        // those are all still in the full picker (as themselves or their mascot).
        assertEquals(
            listOf("⭕", "❤️", "😂", "😢", "👍", "😮", "🔥"), // safe mapping 1.6.98
            OshiReaction.QUICK_ROW
        )
        assertFalse("🖕 one tap away", OshiReaction.QUICK_ROW.contains(ReactionEmojis.MIDDLE_FINGER))
    }

    @Test
    fun `expanded picker has every reaction exactly once`() {
        assertEquals(OshiReaction.PICKER_ALL.size, OshiReaction.PICKER_ALL.toSet().size)
        assertTrue(OshiReaction.PICKER_ALL.containsAll(OshiReaction.QUICK_ROW))
        assertTrue(OshiReaction.PICKER_ALL.contains(OshiReaction.OSHI_SMILE))
        // 2 OSHI marks + 20 mascots + the 8 iOS reactions no mascot draws.
        assertEquals(2 + 20 + 8, OshiReaction.PICKER_ALL.size)
    }

    // ------------------------------------------------------------ toggle / count

    @Test
    fun `toggling the OSHI O follows the same rule as any emoji`() {
        val o = OshiReaction.OSHI_O
        assertEquals(o, ReactionToggle.next(null, o))
        assertNull(ReactionToggle.next(o, o))
        assertEquals(o, ReactionToggle.next(ReactionEmojis.THUMBS_UP, o))
        assertEquals(ReactionEmojis.THUMBS_UP, ReactionToggle.next(o, ReactionEmojis.THUMBS_UP))
        // ⭕ and ⭕😊 are different reactions: switching is a replace, not a remove.
        assertEquals(OshiReaction.OSHI_SMILE, ReactionToggle.next(o, OshiReaction.OSHI_SMILE))
    }

    @Test
    fun `the OSHI O is counted per value like any emoji`() {
        val s = ReactionSummary(
            messageId = "m",
            reactions = mapOf(
                OshiReaction.OSHI_O to listOf("a", "b"),
                OshiReaction.OSHI_SMILE to listOf("c"),
                ReactionEmojis.THUMBS_UP to listOf("d"),
            ),
            myReaction = OshiReaction.OSHI_O,
        )
        assertEquals(2, s.getCount(OshiReaction.OSHI_O))
        assertEquals(1, s.getCount(OshiReaction.OSHI_SMILE))
        assertEquals(4, s.totalCount)
    }

    // ------------------------------------------------------------ list preview

    @Test
    fun `list preview draws an OSHI mark inline and leaves other emoji as text`() {
        val o = reactionPreviewText(OshiReaction.OSHI_O, "Reacted to a message")
        assertTrue("no inline mark", o.getStringAnnotations(0, o.length).isNotEmpty())
        assertTrue(o.text.endsWith(" Reacted to a message"))
        assertEquals(1, reactionPreviewInlineContent(OshiReaction.OSHI_O, 13.sp).size)
        assertEquals(1, reactionPreviewInlineContent(OshiReaction.OSHI_SMILE, 13.sp).size)

        val up = reactionPreviewText(ReactionEmojis.THUMBS_UP, "Reacted to a message")
        assertEquals("👍 Reacted to a message", up.text)
        assertTrue(up.getStringAnnotations(0, up.length).isEmpty())
        assertTrue(reactionPreviewInlineContent(ReactionEmojis.THUMBS_UP, 13.sp).isEmpty())
    }

    // ------------------------------------------------------------ sizing

    @Test
    fun `picker cells are at least 48dp and the mark is emoji-sized`() {
        assertTrue(ReactionBarMetrics.CELL_DP >= 48)
        assertTrue(ReactionBarMetrics.DISC_DP <= ReactionBarMetrics.CELL_DP)
        assertTrue(OshiReactionMetrics.GLYPH_BOX_PER_SP in 1.1f..1.3f)
    }

    // ------------------------------------------------------------ drawables

    @Test
    fun `both O drawables carry the launcher mark geometry`() {
        val res = "src/main/res/drawable/"
        val ring = "M54,23 A27,27 0 0 1 81,50"
        val tail = "M49.31,76.59 Q43.80,81.38 37.10,81.79"
        for (name in listOf("ic_reaction_oshi_o.xml", "ic_reaction_oshi_o_dark.xml")) {
            val f = File(res + name)
            assertTrue("$name missing", f.exists())
            val xml = f.readText()
            assertTrue("$name lost the ring", xml.contains(ring))
            assertTrue("$name lost the tail", xml.contains(tail))
            assertTrue("$name is not 24dp", xml.contains("android:viewportWidth=\"24\""))
        }
        // The notification icon is the reference geometry.
        assertTrue(File(res + "ic_notification.xml").readText().contains(ring))
    }

    // ------------------------------------------------------------ wiring

    @Test
    fun `both threads use the one picker and the one chip`() {
        for (f in listOf("ChatScreen.kt", "GroupChatScreen.kt")) {
            val src = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/$f")
            assertTrue("$f has its own picker again", src.contains("ReactionPicker("))
            assertTrue("$f has its own chip again", src.contains("ReactionChip("))
            assertFalse("$f iterates the picker list itself", src.contains("OshiReaction.QUICK_ROW"))
        }
        val bar = SourceText.read("src/main/java/com/oshi/messenger/ui/components/ReactionBar.kt")
        assertFalse("picker scrolls sideways and hides '+' again", bar.contains("horizontalScroll"))
        assertTrue(bar.contains("FlowRow("))
    }

    @Test
    fun `group taps toggle like 1-1`() {
        val group = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/GroupChatScreen.kt")
        assertTrue(group.contains("onReact = { emoji -> viewModel.toggleReaction(message.id, emoji) }"))
        assertTrue(SourceText.window(group, "onReactWith = { emoji ->", 300).contains("viewModel.toggleReaction("))
        assertFalse(SourceText.window(group, "onReactWith = { emoji ->", 300).contains("viewModel.addReaction("))
    }

    @Test
    fun `every reaction surface goes through ReactionGlyph or the inline preview`() {
        val glyph = SourceText.read("src/main/java/com/oshi/messenger/ui/components/OshiSmileMark.kt")
        assertTrue(glyph.contains("OshiReaction.isOshiO(code) -> OshiOMark("))
        val list = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/ConversationsScreen.kt")
        assertTrue(list.contains("reactionPreviewText(reactionEmoji"))
        assertFalse("raw ⭕ in the list preview", list.contains("reactionEmoji + \" \" +"))
    }
}
