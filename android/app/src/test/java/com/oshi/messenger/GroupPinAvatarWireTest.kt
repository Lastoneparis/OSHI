package com.oshi.messenger

import com.oshi.messenger.service.GroupManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 📌🎨 The pin and the emoji avatar, on the wire, in BOTH directions.
 *
 * These three fields ride inside the iOS `MessageGroup` payload —
 * `pinnedMessageId` / `pinnedBy` (`OSHI/GroupMessaging.swift:104`) and `avatar`
 * (`:112`), CodingKeys at `:259`, decoded at `:213-219` — and Android was dropping all
 * of them: `VPSClient.handleiOSGroupUpdateBroadcast` never parsed them, and
 * `Group` had no `avatar` field for `buildIosMessageGroupJson` to encode.
 *
 * Net effect before this: an iPhone pinning a message in a mixed group moved nothing on
 * any Android member, and every Android broadcast CLEARED the group's emoji avatar on
 * every iPhone — because an incoming update that omits `avatar` clears it on iOS
 * (only picture / wallpaper / isMuted survive a stripped update, `swift:800-820`).
 */
class GroupPinAvatarWireTest {

    private val groupId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private val adminKey = "AAAAadminkey"
    private val memberKey = "BBBBmemberkey"
    private val pinnedId = "9c8b7a6d-1111-2222-3333-444455556666"
    private val createdAtMs = 1786622400000L

    private fun build(
        pinnedMessageId: String? = null,
        pinnedBy: String? = null,
        avatar: String? = null
    ): JSONObject = JSONObject(
        GroupManager.buildIosMessageGroupJson(
            groupId = groupId,
            name = "Team",
            typeRaw = "collaborative",
            adminPublicKey = adminKey,
            memberKeys = listOf(adminKey, memberKey),
            adminKeys = listOf(adminKey),
            createdAtMs = createdAtMs,
            lastActivityMs = createdAtMs,
            pinnedMessageId = pinnedMessageId,
            pinnedBy = pinnedBy,
            avatar = avatar
        )
    )

    // =====================================================================
    // OUTBOUND — what an iPhone decodes
    // =====================================================================

    @Test
    fun `pin and its author both ride the payload`() {
        val json = build(pinnedMessageId = pinnedId, pinnedBy = memberKey)
        assertEquals(pinnedId, json.getString("pinnedMessageId"))
        assertEquals(memberKey, json.getString("pinnedBy"))
    }

    @Test
    fun `emoji avatar rides the payload`() {
        val json = build(avatar = "🐙")
        assertEquals("🐙", json.getString("avatar"))
    }

    /**
     * Swift's synthesized encoder uses `encodeIfPresent` for Optionals, so a nil field
     * is an ABSENT key — never `null`. A literal `null` would decode fine here but the
     * asymmetry is what lets `hasPinnedField` distinguish "unpinned" from "not carried".
     */
    @Test
    fun `absent fields are omitted keys, not nulls`() {
        val json = build()
        assertFalse(json.has("pinnedMessageId"))
        assertFalse(json.has("pinnedBy"))
        assertFalse(json.has("avatar"))
    }

    /** `pinnedBy` is meaningless without a pin, and iOS only reads it alongside one. */
    @Test
    fun `pinnedBy is not emitted without a pinned message`() {
        val json = build(pinnedMessageId = null, pinnedBy = memberKey)
        assertFalse(json.has("pinnedMessageId"))
        assertFalse(json.has("pinnedBy"))
    }

    /** Empty strings must not be emitted as if they were real values. */
    @Test
    fun `blank values are treated as absent`() {
        val json = build(pinnedMessageId = "", pinnedBy = "", avatar = "")
        assertFalse(json.has("pinnedMessageId"))
        assertFalse(json.has("avatar"))
    }

    // =====================================================================
    // INBOUND — what Android extracts from an iOS broadcast
    // =====================================================================

    /**
     * The exact extraction `VPSClient.handleiOSGroupUpdateBroadcast` performs. Presence
     * of the KEY is the signal, not the value: `{"pinnedMessageId": null}` means the
     * sender explicitly UNPINNED, while an absent key means this update simply does not
     * carry pin state and the local pin must survive.
     */
    @Test
    fun `key presence distinguishes unpinned from not-carried`() {
        val carriedPin = build(pinnedMessageId = pinnedId, pinnedBy = adminKey)
        assertTrue(carriedPin.has("pinnedMessageId"))
        assertEquals(pinnedId, carriedPin.optString("pinnedMessageId", "").ifEmpty { null })

        val notCarried = build()
        assertFalse(notCarried.has("pinnedMessageId"))

        // An explicit clear, as iOS encodes it when a pin is removed and the field is
        // still written by a hand-built payload.
        val explicitClear = JSONObject(build().toString()).put("pinnedMessageId", JSONObject.NULL)
        assertTrue(explicitClear.has("pinnedMessageId"))
        assertEquals(null, explicitClear.optString("pinnedMessageId", "").ifEmpty { null })
    }

    @Test
    fun `avatar presence distinguishes cleared from not-carried`() {
        assertTrue(build(avatar = "🐙").has("avatar"))
        assertFalse(build().has("avatar"))
    }

    /**
     * A full round-trip through the encoder Android uses to talk to iOS: nothing that
     * went in comes back changed, and the decoder-visible key set is stable.
     */
    @Test
    fun `round trip preserves pin, author and avatar`() {
        val json = build(pinnedMessageId = pinnedId, pinnedBy = memberKey, avatar = "🎯")
        val reparsed = JSONObject(json.toString())
        assertEquals(pinnedId, reparsed.optString("pinnedMessageId", "").ifEmpty { null })
        assertEquals(memberKey, reparsed.optString("pinnedBy", "").ifEmpty { null })
        assertEquals("🎯", reparsed.optString("avatar", "").ifEmpty { null })
        // The three fields must not have disturbed the required ones.
        assertEquals("Team", reparsed.getString("name"))
        assertEquals("collaborative", reparsed.getString("type"))
        assertEquals(adminKey, reparsed.getString("adminPublicKey"))
    }
}
