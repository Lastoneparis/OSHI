package com.oshi.messenger

import com.oshi.messenger.data.model.GroupMessage
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.service.GroupManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.UUID

/**
 * ✏️ GROUP MESSAGE EDIT — the half RP-4 deferred for want of a Room column.
 *
 * iOS has carried `editedAt` / `editedContent` on `GroupMessage` since
 * `OSHI/GroupMessaging.swift:361-362`. It broadcasts an edit as a **system message**
 * (`editGroupMessageForEveryone`, `:1320-1345`) whose `systemMessageType` is
 * `message_edited` and whose `systemMessageData` carries `editedMessageId` +
 * `editedContent`, and applies an inbound one at `:3292-3307`.
 *
 * The dangerous part is the emitter. That envelope only reaches iOS's edit branch if it
 * survives the **strict** `JSONDecoder().decode(GroupMessage.self)` — path 1 of
 * `parseCrossPlatformGroupMessage` (`:716`) and the IPFS envelope path
 * (`MessageManager.swift:3712`). iOS's lenient path 2 (`:721-787`) hand-maps fields and
 * **does not carry `systemMessageType`/`systemMessageData` at all**, so a payload that
 * only nearly decodes does not fail loudly: it degrades into an empty visible bubble and
 * the edit is lost. These tests pin every non-optional field of the Swift struct, and its
 * JSON *type*, so a future edit to the emitter cannot quietly fall off that cliff.
 *
 * Everything here drives the exact functions production calls:
 *  - the menu + `broadcastMessageEdit` guard → `canEditGroupMessage`
 *  - `broadcastMessageEdit` → `buildGroupEditSystemMessageJson`
 *  - `handleIncomingGroupMessage` → `parseGroupEditSystemMessage` → `applyEditToMessages`
 *  - `applyMessageEditLocally` → `applyEditToMessages`
 */
class GroupMessageEditTest {

    private val groupId = "b7f1a2b0-1111-2222-3333-444455556666"
    private val myKey = "AAAAmykey"
    private val peerKey = "BBBBpeerkey"

    /** An id exactly as Android mints it — `UUID.randomUUID().toString()`, lowercase. */
    private fun androidId(): String = UUID.randomUUID().toString().lowercase(Locale.US)

    private fun msg(
        id: String,
        sender: String = myKey,
        content: String = "hello",
        timestamp: Long = 1_700_000_000_000L,
        mediaType: MediaType? = null,
        isDeleted: Boolean = false,
        editedContent: String? = null,
        editedAt: Long? = null
    ) = GroupMessage(
        id = id,
        groupId = groupId,
        senderPublicKey = sender,
        senderName = "n",
        content = content,
        timestamp = timestamp,
        isFromMe = sender == myKey,
        mediaType = mediaType,
        isDeleted = isDeleted,
        editedContent = editedContent,
        editedAt = editedAt
    )

    // =====================================================================
    // canEditGroupMessage — the ONE predicate the menu and the sender share
    // =====================================================================

    @Test
    fun `my own recent text message is editable`() {
        val t = 1_700_000_000_000L
        assertTrue(GroupManager.canEditGroupMessage(msg(androidId(), timestamp = t), myKey, t + 1_000L))
    }

    @Test
    fun `someone else's message is never editable`() {
        val t = 1_700_000_000_000L
        assertFalse(
            GroupManager.canEditGroupMessage(msg(androidId(), sender = peerKey, timestamp = t), myKey, t + 1_000L)
        )
    }

    @Test
    fun `the ten minute window is iOS's 600 seconds, to the millisecond`() {
        val t = 1_700_000_000_000L
        // iOS: `guard Date().timeIntervalSince(timestamp) <= 600` (GroupMessaging.swift:1307).
        assertEquals(600_000L, GroupManager.GROUP_EDIT_WINDOW_MS)
        assertTrue(GroupManager.canEditGroupMessage(msg(androidId(), timestamp = t), myKey, t + 599_999L))
        // iOS's guard is INCLUSIVE (`<= 600`), so exactly 600 000 ms still edits.
        assertTrue(GroupManager.canEditGroupMessage(msg(androidId(), timestamp = t), myKey, t + 600_000L))
        assertFalse(GroupManager.canEditGroupMessage(msg(androidId(), timestamp = t), myKey, t + 600_001L))
    }

    @Test
    fun `a clock that has gone backwards does not reopen the window`() {
        val t = 1_700_000_000_000L
        // A negative age must not read as "0 ms old". NTP corrections and timezone-less
        // peers both produce future timestamps.
        assertFalse(GroupManager.canEditGroupMessage(msg(androidId(), timestamp = t), myKey, t - 1L))
    }

    @Test
    fun `media and tombstones are not editable`() {
        val t = 1_700_000_000_000L
        // The envelope carries a String `editedContent` and nothing else, so "editing" a
        // photo would drop the photo on every peer.
        assertFalse(
            GroupManager.canEditGroupMessage(msg(androidId(), timestamp = t, mediaType = MediaType.IMAGE), myKey, t + 1L)
        )
        assertFalse(
            GroupManager.canEditGroupMessage(msg(androidId(), timestamp = t, isDeleted = true), myKey, t + 1L)
        )
    }

    @Test
    fun `an empty identity never owns anything`() {
        val t = 1_700_000_000_000L
        // `cryptoManager.getPublicKeyString()` is "" before the identity exists; if ""
        // matched a message whose sender key failed to decode, every such bubble would
        // offer Edit.
        assertFalse(GroupManager.canEditGroupMessage(msg(androidId(), sender = ""), "", t))
    }

    // =====================================================================
    // applyEditToMessages
    // =====================================================================

    @Test
    fun `an iOS edit targeting the UPPERCASE id lands on the lowercase Android row`() {
        val id = androidId()
        val before = listOf(msg(id, content = "original"))
        val after = GroupManager.applyEditToMessages(
            before, id.uppercase(Locale.US), "corrected", 1_700_000_500_000L
        )
        assertNotNull("uppercase iOS id must match the lowercase Android row", after)
        assertEquals("corrected", after!![0].editedContent)
        assertEquals(1_700_000_500_000L, after[0].editedAt)
        // The ORIGINAL body is preserved, exactly as iOS leaves `encryptedContent` alone
        // and only overwrites `plaintextContent` (GroupMessaging.swift:1312-1315).
        assertEquals("original", after[0].content)
        assertEquals("corrected", after[0].displayContent)
        assertTrue(after[0].isEdited)
    }

    @Test
    fun `an edit for an unknown message returns null so nothing is written`() {
        val before = listOf(msg(androidId()))
        assertNull(GroupManager.applyEditToMessages(before, androidId(), "x", 1L))
    }

    @Test
    fun `an edit cannot resurrect a tombstoned message`() {
        val id = androidId()
        val before = listOf(msg(id, isDeleted = true, content = ""))
        assertNull(GroupManager.applyEditToMessages(before, id, "back from the dead", 1L))
    }

    @Test
    fun `a repeated edit is a no-op so a relay replay does not churn the UI`() {
        val id = androidId()
        val once = GroupManager.applyEditToMessages(listOf(msg(id)), id, "v2", 99L)!!
        assertNull(GroupManager.applyEditToMessages(once, id, "v2", 99L))
    }

    @Test
    fun `only the targeted row changes`() {
        val a = androidId()
        val b = androidId()
        val before = listOf(msg(a, content = "A"), msg(b, content = "B"))
        val after = GroupManager.applyEditToMessages(before, b, "B2", 5L)!!
        assertEquals("A", after[0].displayContent)
        assertNull(after[0].editedAt)
        assertEquals("B2", after[1].displayContent)
    }

    @Test
    fun `an edit to the empty string is a legal edit`() {
        val id = androidId()
        val after = GroupManager.applyEditToMessages(listOf(msg(id, content = "oops")), id, "", 7L)
        assertNotNull(after)
        // displayContent falls back to the original when the edit is empty — the same
        // `!edited.isEmpty()` guard iOS uses at GroupViews.swift:2615.
        assertEquals("oops", after!![0].displayContent)
        assertTrue(after[0].isEdited)
    }

    @Test
    fun `an edited message is not equal to its unedited self`() {
        // If `equals` ignored the edit, `GroupChatViewModel`'s StateFlow would consider
        // the list unchanged and DROP the emission — the bubble would keep the old text
        // until some unrelated change forced a re-collect.
        //
        // Each field is checked ALONE. Together they hide each other: with both changed,
        // dropping either one from `equals` still leaves the other making the two
        // unequal, and the test passes over a real regression.
        val id = androidId()
        val before = msg(id)

        val contentOnly = before.copy(editedContent = "new")
        assertFalse("editedContent alone must make the rows unequal", before == contentOnly)
        assertFalse(before.hashCode() == contentOnly.hashCode())

        val stampOnly = before.copy(editedAt = 12L)
        assertFalse("editedAt alone must make the rows unequal", before == stampOnly)
        assertFalse(before.hashCode() == stampOnly.hashCode())

        // And a re-edit of an already-edited row: the second edit changes only the text.
        val edited = before.copy(editedContent = "v1", editedAt = 12L)
        assertFalse("a second edit must not read as no change", edited == edited.copy(editedContent = "v2"))
    }

    // =====================================================================
    // parseGroupEditSystemMessage — the inbound half
    // =====================================================================

    private fun iosEditEnvelope(
        targetId: String,
        newContent: String,
        appleEpochSeconds: Double
    ): JSONObject = JSONObject(
        """
        {
          "id":"${UUID.randomUUID().toString().uppercase(Locale.US)}",
          "groupId":"${groupId.uppercase(Locale.US)}",
          "senderPublicKey":"SYSTEM",
          "encryptedContent":"",
          "timestamp":$appleEpochSeconds,
          "isRead":true,
          "messageChainIndex":0,
          "systemMessageType":"message_edited",
          "systemMessageData":{
            "actorPublicKey":"$peerKey",
            "editedMessageId":"${targetId.uppercase(Locale.US)}",
            "editedContent":"$newContent"
          }
        }
        """.trimIndent()
    )

    @Test
    fun `an iOS edit envelope decodes, Apple epoch and all`() {
        val target = androidId()
        // 2024-01-01T00:00:00Z = 1704067200000 ms since 1970 = 725846400 s since 2001.
        val parsed = GroupManager.parseGroupEditSystemMessage(
            iosEditEnvelope(target, "fixed", 725760000.0), fallbackEditedAt = 1L
        )
        assertNotNull(parsed)
        assertTrue(parsed!!.editedMessageId.equals(target, ignoreCase = true))
        assertEquals("fixed", parsed.newContent)
        assertEquals(1_704_067_200_000L, parsed.editedAt)
    }

    @Test
    fun `Unix millis on the wire are accepted by magnitude`() {
        val target = androidId()
        val parsed = GroupManager.parseGroupEditSystemMessage(
            iosEditEnvelope(target, "fixed", 1_704_067_200_000.0), fallbackEditedAt = 1L
        )!!
        assertEquals(1_704_067_200_000L, parsed.editedAt)
    }

    @Test
    fun `a missing timestamp falls back rather than stamping 1970`() {
        val json = iosEditEnvelope(androidId(), "fixed", 725760000.0)
        json.remove("timestamp")
        assertEquals(4242L, GroupManager.parseGroupEditSystemMessage(json, 4242L)!!.editedAt)
    }

    @Test
    fun `an ordinary group message is not an edit command`() {
        val json = JSONObject("""{"id":"x","groupId":"$groupId","content":"hi"}""")
        assertNull(GroupManager.parseGroupEditSystemMessage(json, 1L))
    }

    @Test
    fun `another system message type is not an edit command`() {
        val json = iosEditEnvelope(androidId(), "fixed", 1.0)
        json.put("systemMessageType", "message_deleted")
        assertNull(GroupManager.parseGroupEditSystemMessage(json, 1L))
    }

    @Test
    fun `an incomplete edit command is ignored rather than blanking a message`() {
        val target = androidId()
        val noTarget = iosEditEnvelope(target, "fixed", 1.0)
        noTarget.getJSONObject("systemMessageData").remove("editedMessageId")
        assertNull(GroupManager.parseGroupEditSystemMessage(noTarget, 1L))

        val noContent = iosEditEnvelope(target, "fixed", 1.0)
        noContent.getJSONObject("systemMessageData").remove("editedContent")
        assertNull(GroupManager.parseGroupEditSystemMessage(noContent, 1L))

        val noData = iosEditEnvelope(target, "fixed", 1.0)
        noData.remove("systemMessageData")
        assertNull(GroupManager.parseGroupEditSystemMessage(noData, 1L))
    }

    @Test
    fun `an explicit empty editedContent is honoured`() {
        val target = androidId()
        val json = iosEditEnvelope(target, "", 1.0)
        val parsed = GroupManager.parseGroupEditSystemMessage(json, 1L)
        assertNotNull("an edit to \"\" is a legal edit, not a missing field", parsed)
        assertEquals("", parsed!!.newContent)
    }

    // =====================================================================
    // buildGroupEditSystemMessageJson — the strict-decoder contract
    // =====================================================================

    private fun built(): JSONObject = JSONObject(
        GroupManager.buildGroupEditSystemMessageJson(
            groupId = groupId,
            systemMessageId = "11111111-2222-3333-4444-555555555555",
            editedMessageId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            newContent = "corrected text",
            actorPublicKey = myKey,
            timestampMillis = 1_704_067_200_000L
        )
    )

    @Test
    fun `every non-optional field of the Swift GroupMessage is present`() {
        val j = built()
        // GroupMessaging.swift:341-352 — `let`/non-optional `var` members.
        for (k in listOf("id", "groupId", "senderPublicKey", "encryptedContent", "timestamp", "isRead", "messageChainIndex")) {
            assertTrue("missing non-optional field `$k` — iOS's strict decode would THROW", j.has(k))
        }
    }

    @Test
    fun `the timestamp is an Apple-epoch NUMBER, not ISO-8601 and not millis`() {
        // A bare `JSONDecoder()` means `.deferredToDate`: seconds since 2001-01-01.
        // ISO-8601 belongs to the group DEFINITION payload, which iOS decodes with
        // `.iso8601` (`handleIncomingGroupUpdate`, GroupMessaging.swift:934) — a
        // different payload with a different rule.
        val j = built()
        assertTrue("timestamp must be a JSON number", j.get("timestamp") is Number)
        assertEquals(725760000.0, j.getDouble("timestamp"), 0.001)
    }

    @Test
    fun `the envelope is a SYSTEM message with iOS's exact enum raw value`() {
        val j = built()
        assertEquals("SYSTEM", j.getString("senderPublicKey"))
        // GroupSystemMessageType.messageEdited raw value (GroupMessaging.swift:3923).
        // Any other string is an unknown enum value and throws in the strict decode.
        assertEquals("message_edited", j.getString("systemMessageType"))
        assertTrue(j.getBoolean("isRead"))
        assertEquals(0, j.getInt("messageChainIndex"))
        // iOS sends `encryptedContent: Data()`; base64 of no bytes is the empty string.
        assertEquals("", j.getString("encryptedContent"))
    }

    @Test
    fun `systemMessageData carries actorPublicKey, the one non-optional field`() {
        val d = built().getJSONObject("systemMessageData")
        // GroupSystemMessageData.actorPublicKey is `let String` (GroupMessaging.swift:3963)
        // — absent, the whole GroupMessage decode fails and the edit is lost.
        assertEquals(myKey, d.getString("actorPublicKey"))
        assertEquals("corrected text", d.getString("editedContent"))
    }

    @Test
    fun `ids go on the wire UPPERCASE, the established convention`() {
        val j = built()
        assertEquals("11111111-2222-3333-4444-555555555555".uppercase(Locale.US), j.getString("id"))
        assertEquals(
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee".uppercase(Locale.US),
            j.getJSONObject("systemMessageData").getString("editedMessageId")
        )
    }

    @Test
    fun `no mediaType key is emitted — a wrong optional throws, an absent one does not`() {
        val j = built()
        assertFalse(j.has("mediaType"))
        assertFalse(j.has("mediaData"))
    }

    @Test
    fun `what Android emits is what Android's own receiver reads back`() {
        // Round trip: the emitter and the inbound parser must agree, or an
        // Android→Android edit is silently dropped while Android→iOS works.
        val target = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val parsed = GroupManager.parseGroupEditSystemMessage(built(), fallbackEditedAt = 0L)
        assertNotNull(parsed)
        assertTrue(parsed!!.editedMessageId.equals(target, ignoreCase = true))
        assertEquals("corrected text", parsed.newContent)
        assertEquals(1_704_067_200_000L, parsed.editedAt)

        val row = msg(target, content = "before")
        val after = GroupManager.applyEditToMessages(
            listOf(row), parsed.editedMessageId, parsed.newContent, parsed.editedAt
        )!!
        assertEquals("corrected text", after[0].displayContent)
    }
}
