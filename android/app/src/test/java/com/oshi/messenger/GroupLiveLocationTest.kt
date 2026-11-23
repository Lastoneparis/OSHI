package com.oshi.messenger

import com.oshi.messenger.data.model.GroupMessage
import com.oshi.messenger.service.GroupManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * __GROUP_LIVE_LOCATION_2026_09_27__ A group live location is ONE tile that moves and
 * ends — on Android, and on a shipped iPhone, which only applies updates that arrive
 * as `.messageEdited` of the first message.
 */
class GroupLiveLocationTest {

    private val alice = "ALICEKEY"
    private val bob = "BOBKEY"

    private fun loc(session: String?, isUpdate: Boolean, isStopped: Boolean = false, lat: Double = 48.85): String {
        val body = JSONObject().apply {
            put("latitude", lat); put("longitude", 2.35); put("timestamp", 812_000_000.0)
            put("isLive", true); put("expiresAt", 812_003_600.0)
            session?.let { put("sessionId", it) }
            put("isUpdate", isUpdate); put("isStopped", isStopped)
        }
        return "📍LOCATION📍$body"
    }

    private fun msg(id: String, sender: String, content: String) = GroupMessage(
        id = id, groupId = "g", senderPublicKey = sender, senderName = "", content = content, isFromMe = false
    )

    @Test
    fun `an update merges into the same sender's tile for that session`() {
        val thread = listOf(
            msg("a1", alice, loc("S1", isUpdate = false)),
            msg("b1", bob, loc("S1", isUpdate = false)),
            msg("t", alice, "hello"),
        )
        assertEquals(0, GroupManager.liveLocationMergeTarget(thread, alice, loc("S1", isUpdate = true, lat = 48.9)))
        assertEquals(1, GroupManager.liveLocationMergeTarget(thread, bob, loc("S1", isUpdate = true)))
    }

    @Test
    fun `the stop merges too`() {
        val thread = listOf(msg("a1", alice, loc("S1", isUpdate = false)))
        assertEquals(0, GroupManager.liveLocationMergeTarget(thread, alice, loc("S1", isUpdate = true, isStopped = true)))
    }

    @Test
    fun `a tile already moved by an edit is still found`() {
        val moved = msg("a1", alice, loc("S1", isUpdate = false))
            .copy(editedContent = loc("S1", isUpdate = true, lat = 49.0), editedAt = 1L)
        assertEquals(0, GroupManager.liveLocationMergeTarget(listOf(moved), alice, loc("S1", isUpdate = true)))
    }

    @Test
    fun `an initial share, another session, or no session never merges`() {
        val thread = listOf(msg("a1", alice, loc("S1", isUpdate = false)))
        assertEquals(-1, GroupManager.liveLocationMergeTarget(thread, alice, loc("S1", isUpdate = false)))
        assertEquals(-1, GroupManager.liveLocationMergeTarget(thread, alice, loc("S2", isUpdate = true)))
        assertEquals(-1, GroupManager.liveLocationMergeTarget(thread, alice, loc(null, isUpdate = true)))
        assertEquals(-1, GroupManager.liveLocationMergeTarget(thread, alice, "not a location"))
    }

    @Test
    fun `the update goes out as the edit an iPhone applies`() {
        val update = loc("S1", isUpdate = true, lat = 49.0)
        val json = JSONObject(
            GroupManager.buildGroupEditSystemMessageJson(
                groupId = "G", systemMessageId = "11111111-1111-1111-1111-111111111111",
                editedMessageId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", newContent = update,
                actorPublicKey = alice, timestampMillis = 1_790_000_000_000L
            )
        )
        assertEquals("message_edited", json.getString("systemMessageType"))
        val data = json.getJSONObject("systemMessageData")
        assertEquals("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA", data.getString("editedMessageId"))
        assertEquals(update, data.getString("editedContent"))
    }
}
