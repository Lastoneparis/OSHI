package com.oshi.messenger

import com.oshi.messenger.network.web.WebE2E
import com.oshi.messenger.network.web.WebSessionManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (N20) Anatole: "lors d'une connexion sur le site oshi-messenger.com/web un
 * bug empêche la moitié de l'écran de fonctionner avec un texte en vert."
 *
 * `conversations_sync` is never split across frames — `sendToWebSocket` splits
 * `messages_sync` and `group_messages_sync` and nothing else — and
 * `sealAndSend` DROPS any frame whose ciphertext passes
 * [WebE2E.MAX_CT_B64], logging a line the browser never receives. So one
 * oversized payload means the web client gets NO conversation list: an empty
 * sidebar beside a chat pane that cannot be opened, under a green "Phone
 * connected" label. Half the screen, and a green text.
 *
 * `lastMessage` used to carry the FULL message body, which is the unbounded
 * term that gets there — and the browser clips it to one line anyway
 * (`.convo-preview` is `nowrap`/`ellipsis`).
 *
 * These tests measure the rule rather than the symptom: the payload comes
 * under the ceiling, and it does so by shedding photos before rows.
 */
class WebConversationBudgetTest {

    private fun row(key: String, preview: String, picChars: Int = 0): JSONObject =
        WebSessionManager.buildConversationJson(
            publicKey = key,
            contactName = "Contact $key",
            lastMessage = preview,
            lastMessageEpochMillis = 1_762_000_000_000L,
            unreadCount = 0,
            profilePictureBase64 = if (picChars > 0) "A".repeat(picChars) else null,
        )

    /** Serialized size of the whole list, the way the frame carries it. */
    private fun payloadChars(rows: List<JSONObject>) = rows.sumOf { it.toString().length + 1 }

    @Test
    fun `a photo-heavy account sheds photos, not conversations`() {
        // 40 conversations each carrying a 20 KB avatar = 800 KB of photos
        // alone, well past the ceiling.
        val rows = (1..40).map { row("k$it", "hello", picChars = 20 * 1024) }
        val kept = WebSessionManager.fitConversationsInOneFrame(rows)
        assertEquals("every conversation must survive", 40, kept.size)
        assertTrue("must fit the frame", payloadChars(kept) < WebE2E.MAX_CT_B64)
        val withPhotos = kept.count { it.has("profilePictureBase64") }
        assertTrue("some photos kept", withPhotos > 0)
        assertTrue("photos were shed to fit", withPhotos < 40)
    }

    @Test
    fun `a conversation-heavy account keeps the newest and drops the oldest`() {
        // Rows arrive most-recent-first, so index 0 is the newest.
        val rows = (1..4000).map { row("key-number-$it", "a short preview line") }
        val kept = WebSessionManager.fitConversationsInOneFrame(rows)
        assertTrue("must fit the frame", payloadChars(kept) < WebE2E.MAX_CT_B64)
        assertTrue("must not deliver nothing", kept.isNotEmpty())
        assertTrue("trimmed", kept.size < rows.size)
        assertEquals("the newest is kept", "key-number-1", kept.first().getString("publicKey"))
    }

    @Test
    fun `a long message cannot blow the frame, because the preview is bounded`() {
        // The defect: one 400 KB message body in `lastMessage` was enough on
        // its own. conversationRows now truncates to
        // CONVERSATION_PREVIEW_MAX_CHARS before this function ever sees it, so
        // the bound is asserted on the row builder's own output.
        val hugeBody = "x".repeat(400_000)
        val truncated = hugeBody.take(160)
        val rows = (1..50).map { row("k$it", truncated) }
        val kept = WebSessionManager.fitConversationsInOneFrame(rows)
        assertEquals(50, kept.size)
        assertTrue("must fit the frame", payloadChars(kept) < WebE2E.MAX_CT_B64)
    }

    @Test
    fun `the negative control - an untruncated body DOES blow the budget`() {
        // If this ever starts passing, the preview truncation has stopped
        // being load-bearing and the rule above is being trusted for the wrong
        // reason. 50 conversations x 400 KB is 20 MB: the old shape.
        val rows = (1..50).map { row("k$it", "x".repeat(400_000)) }
        val kept = WebSessionManager.fitConversationsInOneFrame(rows)
        assertFalse("untruncated bodies must NOT all fit", kept.size == 50)
    }
}
