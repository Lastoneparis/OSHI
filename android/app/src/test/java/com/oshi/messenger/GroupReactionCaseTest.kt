package com.oshi.messenger

import com.oshi.messenger.data.model.GroupMessage
import com.oshi.messenger.service.GroupManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.UUID

/**
 * R-1 — group message-id CASE mismatch (2026-08-24 react-reply audit §3.6).
 *
 * `GroupMessage.id` on iOS is a **`UUID`** (`OSHI/GroupMessaging.swift:342`), not a String.
 * An Android-minted **lowercase** id parsed by iOS with `UUID(uuidString:)`
 * (`GroupMessaging.swift:726`, case-insensitive) is re-rendered by `uuidString` in
 * **UPPERCASE**. Every iOS group reaction targets `m.id.uuidString`
 * (`GroupViews.swift:4417`, `:6128`) and every iOS render reads it (`:2758`, `:3247`,
 * `:3273`, `:6083`).
 *
 * Android compared with a plain `==`, so a reaction / read receipt / deletion aimed at an
 * **Android-originated** group message was silently dropped in both directions. The same
 * defect degraded the group reply quote to a bare "Reply to" label.
 *
 * These tests drive the exact functions the production routes call:
 *  - `handleIncomingGroupMessage` → `parseGroupReaction` → `applyReactionToMessages`
 *  - `handleReadReceiptBatch`     → `applyReadReceiptToMessages`
 *  - `applyMessageDeletionLocally`→ `applyDeletionToMessages`
 * so a revert of the `ignoreCase` comparison fails them.
 */
class GroupReactionCaseTest {

    private val REACTION_PREFIX = "🔥REACTION🔥"  // 🔥REACTION🔥
    private val groupId = "e4f1a2b0-1111-2222-3333-444455556666"
    private val myKey = "AAAAmykey"
    private val peerKey = "BBBBpeerkey"

    /** An id exactly as Android mints it — `UUID.randomUUID().toString()`, lowercase. */
    private val androidMintedId = "7c9e6679-7425-40de-944b-e07fc1f90ae7"

    /** The same id after a round trip through iOS's `UUID` → `uuidString`. */
    private val idAsIosEchoesIt = androidMintedId.uppercase(Locale.US)

    private fun androidMessage(id: String = androidMintedId) = GroupMessage(
        id = id,
        groupId = groupId,
        senderPublicKey = myKey,
        senderName = "Me",
        content = "hello from Android",
        timestamp = 1786622400000L,
        isFromMe = true
    )

    /** Exactly what iOS's `ReactionPayload.toMessageString()` puts on the wire. */
    private fun iosReactionWire(
        messageId: String,
        emoji: String = "👍",
        action: String = "add"
    ): String = REACTION_PREFIX + """{"messageId":"$messageId","emoji":"$emoji",""" +
        """"senderPublicKey":"$peerKey","senderName":"You",""" +
        """"timestamp":774123456.789,"action":"$action"}"""

    // ── sanity: the premise itself ───────────────────────────────────────────

    @Test
    fun `iOS uuidString round trip uppercases an Android lowercase id`() {
        // This is the whole defect in one line: same UUID, different string.
        val roundTripped = UUID.fromString(androidMintedId).toString().uppercase(Locale.US)
        assertEquals(idAsIosEchoesIt, roundTripped)
        assertTrue("premise broken: the two spellings must differ", androidMintedId != idAsIosEchoesIt)
    }

    // ── R-1.1  reactions ─────────────────────────────────────────────────────

    @Test
    fun `iOS reaction on an Android-originated group message is applied`() {
        val messages = listOf(androidMessage())
        val parsed = GroupManager.parseGroupReaction(iosReactionWire(idAsIosEchoesIt), peerKey)
        assertNotNull("the 🔥REACTION🔥 envelope must decode", parsed)

        val updated = GroupManager.applyReactionToMessages(
            messages, parsed!!.messageId, parsed.senderPublicKey, parsed.emoji, parsed.isRemoval
        )
        assertNotNull("UPPERCASE iOS messageId must still match the lowercase stored id", updated)
        assertEquals(mapOf(peerKey to "👍"), updated!![0].reactions)
        // The stored id is never case-folded — it is what Android echoes back to iOS.
        assertEquals(androidMintedId, updated[0].id)
    }

    @Test
    fun `iOS reaction removal on an Android-originated group message is applied`() {
        val messages = listOf(androidMessage().copy(reactions = mapOf(peerKey to "👍")))
        val parsed = GroupManager.parseGroupReaction(
            iosReactionWire(idAsIosEchoesIt, action = "remove"), peerKey
        )!!
        assertTrue(parsed.isRemoval)
        val updated = GroupManager.applyReactionToMessages(
            messages, parsed.messageId, parsed.senderPublicKey, parsed.emoji, parsed.isRemoval
        )
        assertNotNull(updated)
        assertTrue("removal must clear the reactor", updated!![0].reactions.isEmpty())
    }

    @Test
    fun `Android-to-Android reaction still works`() {
        val messages = listOf(androidMessage())
        val updated = GroupManager.applyReactionToMessages(
            messages, androidMintedId, peerKey, "❤️", false
        )
        assertNotNull(updated)
        assertEquals("❤️", updated!![0].reactions[peerKey])
    }

    @Test
    fun `a reaction for an unknown message is reported as a miss`() {
        val messages = listOf(androidMessage())
        val updated = GroupManager.applyReactionToMessages(
            messages, UUID.randomUUID().toString(), peerKey, "👍", false
        )
        assertNull("an unrelated id must NOT match", updated)
    }

    @Test
    fun `reaction payload falls back to the transport sender key`() {
        val wire = REACTION_PREFIX + """{"messageId":"$idAsIosEchoesIt","emoji":"🔥"}"""
        val parsed = GroupManager.parseGroupReaction(wire, peerKey)!!
        assertEquals(peerKey, parsed.senderPublicKey)
        assertEquals("🔥", parsed.emoji)
        assertTrue("no action field means add", !parsed.isRemoval)
    }

    @Test
    fun `a non-reaction body is not a reaction`() {
        assertNull(GroupManager.parseGroupReaction("just a normal message", peerKey))
        assertNull(GroupManager.parseGroupReaction(REACTION_PREFIX + "not json", peerKey))
        assertNull(GroupManager.parseGroupReaction(REACTION_PREFIX + """{"emoji":"👍"}""", peerKey))
    }

    @Test
    fun `emoji sent as a rawValue object is tolerated like the 1-1 path`() {
        // iOS emits a bare string (ReactionEmoji is a String-raw-value enum), but the
        // Android 1:1 decoder tolerates {"rawValue":…}; the group decoder must agree.
        val parsed = GroupManager.parseGroupReaction(
            REACTION_PREFIX + """{"messageId":"$idAsIosEchoesIt","emoji":{"rawValue":"👍"}}""",
            peerKey
        )
        assertNotNull(parsed)
        assertEquals("👍", parsed!!.emoji)
    }

    // ── R-1.2  read receipts ─────────────────────────────────────────────────

    @Test
    fun `iOS read receipt on an Android-originated group message is applied`() {
        val messages = listOf(androidMessage().copy(isFromMe = true, isRead = false))
        val updated = GroupManager.applyReadReceiptToMessages(messages, setOf(idAsIosEchoesIt))
        assertNotNull("UPPERCASE receipt id must match the lowercase stored id", updated)
        assertTrue(updated!![0].isRead)
    }

    @Test
    fun `read receipt for an unrelated id changes nothing`() {
        val messages = listOf(androidMessage())
        assertNull(
            GroupManager.applyReadReceiptToMessages(messages, setOf(UUID.randomUUID().toString()))
        )
    }

    // ── R-1.3  delete for everyone ───────────────────────────────────────────

    @Test
    fun `iOS delete-for-everyone on an Android-originated group message is applied`() {
        val messages = listOf(androidMessage())
        val updated = GroupManager.applyDeletionToMessages(messages, idAsIosEchoesIt)
        assertNotNull("UPPERCASE deletion id must match the lowercase stored id", updated)
        assertTrue(updated!![0].isDeleted)
        assertEquals("", updated[0].content)
    }

    // ── R-1.4  the raw matcher, both directions ──────────────────────────────

    @Test
    fun `indexOfMessageId matches across case in both directions`() {
        val lower = listOf(androidMessage(androidMintedId))
        val upper = listOf(androidMessage(idAsIosEchoesIt))
        assertEquals(0, GroupManager.indexOfMessageId(lower, idAsIosEchoesIt))
        assertEquals(0, GroupManager.indexOfMessageId(upper, androidMintedId))
        assertEquals(-1, GroupManager.indexOfMessageId(lower, "no-such-id"))
    }

    @Test
    fun `idInSet matches across case`() {
        assertTrue(GroupManager.idInSet(androidMintedId, setOf(idAsIosEchoesIt)))
        assertTrue(!GroupManager.idInSet(androidMintedId, setOf("other")))
    }
}
