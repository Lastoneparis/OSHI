package com.oshi.messenger

import com.oshi.messenger.data.model.GroupMessage
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
 * 🗑️ GROUP DELETE FOR EVERYONE — the cross-platform half, which never worked.
 *
 * `parity/verify/2026-08-25-groups-finish.md` §12.1 handed this on: Android broadcast a
 * delete as `{"type":"message_deleted"}` inside a `📢GROUP_UPDATE📢`, and iOS's
 * Android-minimal-JSON switch (`GroupMessaging.swift:1070-1155`) has no such case — it
 * fell to `default:` (`:1153`) and was dropped. **No Android delete has ever reached an
 * iPhone.**
 *
 * The audit understated it in one direction: Android also had **no inbound branch** for
 * iOS's `.messageDeleted` system message (`:2407-2427`, applied at `:3273-3290`), so an
 * iPhone's delete-for-everyone never reached an Android phone either. The envelope
 * carries an empty body and fell through to the system-payload filters.
 *
 * Both legs now go through the same shape as the edit command, which is proven to cross:
 *  - send    → `buildGroupDeleteSystemMessageJson`
 *  - receive → `parseGroupDeleteSystemMessage` → `applyMessageDeletionLocally`
 *              → `applyDeletionToMessages`
 *
 * These tests pin every non-optional field of the Swift `GroupMessage` struct and its
 * JSON *type*, for the reason spelled out in [GroupMessageEditTest]: iOS's lenient
 * fallback decoder does not carry `systemMessageType`/`systemMessageData` at all, so an
 * envelope that only nearly decodes degrades into an empty bubble instead of failing
 * loudly.
 */
class GroupDeleteForEveryoneTest {

    private val groupId = "b7f1a2b0-1111-2222-3333-444455556666"
    private val myKey = "AAAAmykey"
    private val peerKey = "BBBBpeerkey"

    private fun androidId(): String = UUID.randomUUID().toString().lowercase(Locale.US)

    private fun msg(
        id: String,
        sender: String = myKey,
        content: String = "hello",
        isDeleted: Boolean = false
    ) = GroupMessage(
        id = id,
        groupId = groupId,
        senderPublicKey = sender,
        senderName = "n",
        content = content,
        timestamp = 1_700_000_000_000L,
        isFromMe = sender == myKey,
        isDeleted = isDeleted
    )

    private fun deleteEnvelope(
        deletedMessageId: String,
        actor: String = myKey,
        timestampMillis: Long = 1_704_067_200_000L
    ) = JSONObject(
        GroupManager.buildGroupDeleteSystemMessageJson(
            groupId = groupId,
            systemMessageId = androidId(),
            deletedMessageId = deletedMessageId,
            actorPublicKey = actor,
            timestampMillis = timestampMillis
        )
    )

    // =====================================================================
    // THE EMITTER — every field iOS's strict decoder requires
    // =====================================================================

    @Test
    fun `the envelope is a message_deleted system message, not a group update`() {
        val j = deleteEnvelope(androidId())
        assertEquals("message_deleted", j.getString("systemMessageType"))
        // The bug this test exists for: a `type` key is what the OLD, dropped form used.
        assertFalse(
            "a GROUP_UPDATE-shaped `type` key means the old form leaked back in",
            j.has("type")
        )
    }

    @Test
    fun `the sender is the literal SYSTEM, which iOS special-cases`() {
        assertEquals("SYSTEM", deleteEnvelope(androidId()).getString("senderPublicKey"))
    }

    @Test
    fun `every non-optional field of the Swift struct is present`() {
        val j = deleteEnvelope(androidId())
        for (key in listOf(
            "id", "groupId", "senderPublicKey", "encryptedContent",
            "timestamp", "isRead", "messageChainIndex"
        )) {
            assertTrue("`$key` is non-optional in GroupMessage and must be emitted", j.has(key))
        }
    }

    @Test
    fun `encryptedContent is an empty base64 body, matching iOS Data()`() {
        assertEquals("", deleteEnvelope(androidId()).getString("encryptedContent"))
    }

    @Test
    fun `isRead is true and messageChainIndex is zero, as iOS emits`() {
        val j = deleteEnvelope(androidId())
        assertTrue(j.getBoolean("isRead"))
        assertEquals(0, j.getInt("messageChainIndex"))
    }

    /**
     * The date-encoding trap. iOS decodes a `GroupMessage` with a bare `JSONDecoder()`,
     * i.e. `.deferredToDate`, so `timestamp` must be an **Apple-epoch NUMBER** — not
     * ISO-8601 (that rule belongs to the group *definition* payload) and not Unix millis.
     */
    @Test
    fun `the timestamp is an Apple-epoch NUMBER, not ISO-8601 and not millis`() {
        val j = deleteEnvelope(androidId(), timestampMillis = 1_704_067_200_000L)
        val ts = j.get("timestamp")
        assertTrue("timestamp must be a JSON number, got ${ts.javaClass.simpleName}", ts is Number)
        // 2024-01-01T00:00:00Z is 725_846_400 seconds after the Apple epoch.
        assertEquals(725_760_000.0, (ts as Number).toDouble(), 1.0)
    }

    @Test
    fun `systemMessageData carries actorPublicKey, the one non-optional field`() {
        val d = deleteEnvelope(androidId(), actor = myKey).getJSONObject("systemMessageData")
        assertEquals(myKey, d.getString("actorPublicKey"))
    }

    /**
     * iOS reads `deletedMessageId` here (`GroupMessaging.swift:3275`). Emitting the edit
     * command's `editedMessageId` instead would decode cleanly and then do nothing.
     */
    @Test
    fun `systemMessageData names deletedMessageId, never editedMessageId`() {
        val target = androidId()
        val d = deleteEnvelope(target).getJSONObject("systemMessageData")
        assertTrue(d.has("deletedMessageId"))
        assertFalse("`editedMessageId` here would be silently ignored by iOS", d.has("editedMessageId"))
        assertTrue(d.getString("deletedMessageId").equals(target, ignoreCase = true))
    }

    @Test
    fun `ids are emitted uppercase, following the convention of every other id on this wire`() {
        val target = androidId()
        val d = deleteEnvelope(target).getJSONObject("systemMessageData")
        assertEquals(target.uppercase(Locale.US), d.getString("deletedMessageId"))
    }

    @Test
    fun `no mediaType key is emitted — an absent optional decodes as nil, a wrong one throws`() {
        assertFalse(deleteEnvelope(androidId()).has("mediaType"))
    }

    // =====================================================================
    // THE RECEIVER
    // =====================================================================

    @Test
    fun `what Android emits is what Android's own receiver reads back`() {
        val target = androidId()
        val parsed = GroupManager.parseGroupDeleteSystemMessage(deleteEnvelope(target))
        assertNotNull("Android must be able to read its own delete envelope", parsed)
        assertTrue(parsed!!.equals(target, ignoreCase = true))
    }

    @Test
    fun `an iOS-shaped envelope is accepted`() {
        val target = UUID.randomUUID().toString().uppercase(Locale.US)
        val json = JSONObject(
            """
            {
              "id":"${UUID.randomUUID().toString().uppercase(Locale.US)}",
              "groupId":"$groupId",
              "senderPublicKey":"SYSTEM",
              "encryptedContent":"",
              "timestamp":725760000,
              "isRead":true,
              "messageChainIndex":0,
              "systemMessageType":"message_deleted",
              "systemMessageData":{"actorPublicKey":"$peerKey","deletedMessageId":"$target"}
            }
            """.trimIndent()
        )
        assertEquals(target, GroupManager.parseGroupDeleteSystemMessage(json))
    }

    @Test
    fun `an edit envelope is not read as a delete`() {
        val editJson = JSONObject(
            GroupManager.buildGroupEditSystemMessageJson(
                groupId = groupId,
                systemMessageId = androidId(),
                editedMessageId = androidId(),
                newContent = "corrected",
                actorPublicKey = myKey,
                timestampMillis = 1_704_067_200_000L
            )
        )
        assertNull(GroupManager.parseGroupDeleteSystemMessage(editJson))
    }

    @Test
    fun `an envelope with no systemMessageData is ignored rather than deleting nothing loudly`() {
        val j = deleteEnvelope(androidId())
        j.remove("systemMessageData")
        assertNull(GroupManager.parseGroupDeleteSystemMessage(j))
    }

    @Test
    fun `an envelope with a blank deletedMessageId is ignored`() {
        val j = deleteEnvelope(androidId())
        j.getJSONObject("systemMessageData").put("deletedMessageId", "")
        assertNull(GroupManager.parseGroupDeleteSystemMessage(j))
    }

    @Test
    fun `an ordinary group message is not read as a delete`() {
        val j = JSONObject("""{"id":"x","groupId":"$groupId","senderPublicKey":"$peerKey"}""")
        assertNull(GroupManager.parseGroupDeleteSystemMessage(j))
    }

    // =====================================================================
    // APPLYING IT
    // =====================================================================

    @Test
    fun `an inbound delete tombstones the target and clears its body`() {
        val target = androidId()
        val rows = listOf(msg(androidId(), content = "keep"), msg(target, content = "secret"))
        val after = GroupManager.applyDeletionToMessages(rows, target)
        assertNotNull(after)
        assertTrue(after!![1].isDeleted)
        assertEquals("the retracted body must not survive the tombstone", "", after[1].content)
        assertFalse("only the target is touched", after[0].isDeleted)
        assertEquals("keep", after[0].content)
    }

    /**
     * An iPhone targets `messageId.uuidString`, which is UPPERCASE, while Android mints
     * lowercase ids. Matching case-sensitively would drop every inbound iOS delete.
     */
    @Test
    fun `an iOS uppercase id matches an Android lowercase row`() {
        val target = androidId()
        val rows = listOf(msg(target))
        val after = GroupManager.applyDeletionToMessages(rows, target.uppercase(Locale.US))
        assertNotNull("an UPPERCASE iOS id must match a lowercase Android row", after)
        assertTrue(after!![0].isDeleted)
    }

    @Test
    fun `a delete for an unknown message changes nothing`() {
        val rows = listOf(msg(androidId()))
        assertNull(
            "a miss must return null so the caller skips the state and disk writes",
            GroupManager.applyDeletionToMessages(rows, androidId())
        )
    }

    /**
     * Both broadcast legs — the legacy `📢GROUP_UPDATE📢` and the new system message —
     * reach a peer that understands both. Applying twice must be a no-op, or the second
     * leg would rewrite state and re-save on every delete.
     */
    @Test
    fun `applying the same delete twice is idempotent`() {
        val target = androidId()
        val once = GroupManager.applyDeletionToMessages(listOf(msg(target)), target)
        assertNotNull(once)
        assertNull(
            "the second leg must no-op on an already-tombstoned row",
            GroupManager.applyDeletionToMessages(once!!, target)
        )
    }

    @Test
    fun `an edit cannot resurrect a message a delete tombstoned`() {
        val target = androidId()
        val tombstoned = GroupManager.applyDeletionToMessages(listOf(msg(target)), target)!!
        assertNull(
            GroupManager.applyEditToMessages(tombstoned, target, "back from the dead", 1L)
        )
    }
}
