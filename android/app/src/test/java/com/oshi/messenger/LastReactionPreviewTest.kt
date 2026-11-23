package com.oshi.messenger

import com.oshi.messenger.service.LastReactionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * __REACTION_WAS_SILENT_2026_11_04__ (Anatole) "réagir à un message avec un
 * émoji … ne s'affiche pas dans l'aperçu de conversation."
 *
 * The rule is one line and the whole risk is in it: a reaction may take over
 * the preview ONLY while it is the most recent thing that happened. Get it
 * wrong in the permissive direction and a thumbs-up from last Tuesday
 * permanently hides every message since, which is a worse bug than the one it
 * replaces.
 */
class LastReactionPreviewTest {

    @Test
    fun `a reaction newer than the last message is shown`() {
        val entry = LastReactionStore.Entry("👍", atMillis = 2_000)
        assertEquals("👍", LastReactionStore.previewEmoji(entry, lastMessageAtMillis = 1_000))
    }

    @Test
    fun `a reaction older than the last message is not shown`() {
        // The one that matters: somebody reacted, then the conversation
        // carried on. The preview must follow the conversation.
        val entry = LastReactionStore.Entry("👍", atMillis = 1_000)
        assertNull(LastReactionStore.previewEmoji(entry, lastMessageAtMillis = 2_000))
    }

    @Test
    fun `a reaction at exactly the last message's time does not win`() {
        // Ties go to the message. A reaction cannot arrive before the message
        // it reacts to, so equal timestamps mean clock granularity, not order,
        // and the message is the thing with content.
        val entry = LastReactionStore.Entry("👍", atMillis = 1_000)
        assertNull(LastReactionStore.previewEmoji(entry, lastMessageAtMillis = 1_000))
    }

    @Test
    fun `no reaction means the preview is untouched`() {
        assertNull(LastReactionStore.previewEmoji(null, lastMessageAtMillis = 1_000))
    }

    @Test
    fun `record keeps the most recent emoji per conversation`() {
        LastReactionStore.clear()
        LastReactionStore.record("peer-a", "👍", 1_000)
        LastReactionStore.record("peer-a", "🎉", 2_000)
        LastReactionStore.record("peer-b", "❤️", 1_500)
        assertEquals("🎉", LastReactionStore.entries.value["peer-a"]?.emoji)
        assertEquals("❤️", LastReactionStore.entries.value["peer-b"]?.emoji)
        LastReactionStore.clear()
    }

    @Test
    fun `forgetting a conversation leaves nothing behind`() {
        LastReactionStore.clear()
        LastReactionStore.record("peer-a", "👍", 1_000)
        LastReactionStore.forget("peer-a")
        assertNull(LastReactionStore.entries.value["peer-a"])
    }

    @Test
    fun `empty inputs are ignored rather than stored`() {
        LastReactionStore.clear()
        LastReactionStore.record("", "👍", 1_000)
        LastReactionStore.record("peer-a", "", 1_000)
        assertEquals(0, LastReactionStore.entries.value.size)
    }
}
