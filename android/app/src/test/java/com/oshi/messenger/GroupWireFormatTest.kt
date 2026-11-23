package com.oshi.messenger

import com.oshi.messenger.data.model.GroupType
import com.oshi.messenger.service.GroupManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests for every group payload Android sends to the shipping iOS app.
 *
 * The reference is the Swift source, not the Android code:
 *  - `MessageGroup.init(from:)`               GroupMessaging.swift:178-252
 *  - `GroupMember.init(from:)`                GroupMessaging.swift:285-297
 *  - `handleIncomingGroupUpdate`              GroupMessaging.swift:786-998
 *  - `MessageWithReply` / `ReplyMessage`      MessageActionsManager.swift:15-129
 *  - `TypingStatus.init(from:)`               TypingIndicatorManager.swift:36-43
 *
 * Every function under test is a pure companion-object function, so no Android runtime and no
 * device are needed. `android.util.Base64` is deliberately kept out of them — blobs are passed
 * in already base64-encoded.
 */
class GroupWireFormatTest {

    private val APPLE_OFFSET = 978307200.0

    // 2026-08-13T12:00:00Z
    private val createdAtMs = 1786622400000L
    private val lastActivityMs = 1786626000000L // +1h

    private val adminKey = "AAAAadminkey"
    private val memberKey = "BBBBmemberkey"
    private val groupId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"

    private fun sampleGroupJson(
        typeRaw: String = "admin_only",
        pictureB64: String? = null,
        wallpaperB64: String? = null,
        pinnedMessageId: String? = null
    ): JSONObject = JSONObject(
        GroupManager.buildIosMessageGroupJson(
            groupId = groupId,
            name = "Team OSHI",
            typeRaw = typeRaw,
            adminPublicKey = adminKey,
            memberKeys = listOf(adminKey, memberKey),
            adminKeys = listOf(adminKey),
            createdAtMs = createdAtMs,
            lastActivityMs = lastActivityMs,
            memberAliases = mapOf(memberKey to "Bob"),
            groupPictureBase64 = pictureB64,
            groupPictureUpdatedBy = if (pictureB64 != null) adminKey else null,
            groupPictureUpdatedAtMs = if (pictureB64 != null) lastActivityMs else null,
            groupWallpaperBase64 = wallpaperB64,
            groupWallpaperUpdatedBy = if (wallpaperB64 != null) memberKey else null,
            groupWallpaperUpdatedAtMs = if (wallpaperB64 != null) lastActivityMs else null,
            pinnedMessageId = pinnedMessageId,
            pinnedBy = if (pinnedMessageId != null) adminKey else null
        )
    )

    // =====================================================================
    // 1. Full iOS MessageGroup JSON
    // =====================================================================

    /**
     * The four keys `MessageGroup.init(from:)` decodes NON-optionally (swift:181-184). Any of them
     * missing or mistyped throws, the strict decode fails, and the payload falls through to the
     * minimal switch whose `default:` (swift:992) silently drops it.
     */
    @Test
    fun `full group JSON carries every required iOS key`() {
        val json = sampleGroupJson()
        assertTrue(json.has("id"))
        assertTrue(json.has("name"))
        assertTrue(json.has("type"))
        assertTrue(json.has("adminPublicKey"))
        assertTrue(json.has("members"))
        assertTrue(json.has("createdAt"))
        assertTrue(json.has("lastActivity"))
        assertEquals("Team OSHI", json.getString("name"))
        assertEquals(adminKey, json.getString("adminPublicKey"))
    }

    /** iOS re-encodes `UUID` as an uppercase uuidString; match it byte for byte. */
    @Test
    fun `group id is emitted as an uppercase UUID`() {
        assertEquals(groupId.uppercase(), sampleGroupJson().getString("id"))
    }

    /** A non-UUID invite gid must survive verbatim rather than being mangled by uppercasing. */
    @Test
    fun `non-UUID group id is left untouched`() {
        assertEquals("my-Custom-Gid", GroupManager.canonicalGroupId("my-Custom-Gid"))
    }

    /**
     * `type` is `GroupType` (swift:37-40), decoded strictly at swift:183. Only these three raw
     * values exist; anything else sinks the whole payload.
     */
    @Test
    fun `group type raw values match the iOS enum`() {
        assertEquals("admin_only", GroupManager.iosGroupTypeRaw(GroupType.ADMIN_ONLY))
        assertEquals("collaborative", GroupManager.iosGroupTypeRaw(GroupType.COLLABORATIVE))
        assertEquals("public", GroupManager.iosGroupTypeRaw(GroupType.PUBLIC))
        assertEquals("admin_only", sampleGroupJson("admin_only").getString("type"))
    }

    /** An unknown type would throw in Swift — coerce it to a legal value instead of shipping it. */
    @Test
    fun `unknown group type is coerced to a legal iOS raw value`() {
        assertEquals("collaborative", sampleGroupJson("PRIVATE").getString("type"))
    }

    /**
     * Dates must be ISO-8601 strings: the broadcast encoder uses `.iso8601` (swift:2041) and the
     * decoder is configured to match (swift:790). `groupPictureUpdatedAt`/`groupWallpaperUpdatedAt`
     * go through a bare `decodeIfPresent(Date.self,…)` (swift:203, :208) that is NOT wrapped in
     * `try?`, so a numeric timestamp there throws and drops the entire group update.
     */
    @Test
    fun `dates are ISO-8601 strings, never epoch numbers`() {
        val json = sampleGroupJson(pictureB64 = "aGVsbG8=", wallpaperB64 = "d29ybGQ=")
        assertEquals("2026-08-13T12:00:00Z", json.getString("createdAt"))
        assertEquals("2026-08-13T13:00:00Z", json.getString("lastActivity"))
        assertEquals("2026-08-13T13:00:00Z", json.getString("groupPictureUpdatedAt"))
        assertEquals("2026-08-13T13:00:00Z", json.getString("groupWallpaperUpdatedAt"))
        // A Double here would be a silent, total protocol failure.
        assertTrue(json.get("createdAt") is String)
        assertTrue(json.get("groupWallpaperUpdatedAt") is String)
    }

    @Test
    fun `iso8601 round-trips through the parser`() {
        assertEquals(createdAtMs, GroupManager.parseIso8601(GroupManager.iso8601(createdAtMs)))
    }

    /**
     * The member array is what carries admin rights across (swift:818-826 diffs it, and
     * `isAdmin` at swift:233/:289 drives every permission check on the iPhone).
     */
    @Test
    fun `members carry publicKey, joinedAt and per-member isAdmin`() {
        val members = sampleGroupJson().getJSONArray("members")
        assertEquals(2, members.length())
        val admin = members.getJSONObject(0)
        assertEquals(adminKey, admin.getString("publicKey"))
        assertTrue(admin.getBoolean("isAdmin"))
        assertEquals("2026-08-13T12:00:00Z", admin.getString("joinedAt"))
        val member = members.getJSONObject(1)
        assertEquals(memberKey, member.getString("publicKey"))
        assertFalse(member.getBoolean("isAdmin"))
        assertEquals("Bob", member.getString("alias"))
    }

    /** No alias resolved ⇒ the key is simply absent; iOS reads it with `decodeIfPresent` (swift:288). */
    @Test
    fun `member alias is omitted when unknown`() {
        assertFalse(sampleGroupJson().getJSONArray("members").getJSONObject(0).has("alias"))
    }

    /** Blobs are optional. A lightweight broadcast must not emit empty keys (iOS strips at swift:2036). */
    @Test
    fun `picture and wallpaper keys are absent when there is nothing to send`() {
        val json = sampleGroupJson()
        assertFalse(json.has("groupPictureData"))
        assertFalse(json.has("groupWallpaperData"))
        assertFalse(json.has("pinnedMessageId"))
    }

    @Test
    fun `picture, wallpaper and pin ride the same payload when present`() {
        val json = sampleGroupJson(
            pictureB64 = "aGVsbG8=",
            wallpaperB64 = "d29ybGQ=",
            pinnedMessageId = "11111111-2222-3333-4444-555555555555"
        )
        assertEquals("aGVsbG8=", json.getString("groupPictureData"))
        assertEquals(adminKey, json.getString("groupPictureUpdatedBy"))
        assertEquals("d29ybGQ=", json.getString("groupWallpaperData"))
        assertEquals(memberKey, json.getString("groupWallpaperUpdatedBy"))
        assertEquals("11111111-2222-3333-4444-555555555555", json.getString("pinnedMessageId"))
        assertEquals(adminKey, json.getString("pinnedBy"))
    }

    /**
     * Mute is a personal preference: iOS restores its own value over anything we send
     * (swift:810), so pushing ours would be noise at best.
     */
    @Test
    fun `isMuted is always false on the wire`() {
        assertFalse(sampleGroupJson().getBoolean("isMuted"))
    }

    /** Literal fixture in the exact iOS shape — the whole point of this file. */
    @Test
    fun `literal fixture matches the iOS MessageGroup shape`() {
        val expected = JSONObject(
            """
            {"id":"3F2504E0-4F89-11D3-9A0C-0305E82C3301",
             "name":"Team OSHI",
             "type":"collaborative",
             "adminPublicKey":"AAAAadminkey",
             "members":[{"publicKey":"AAAAadminkey","joinedAt":"2026-08-13T12:00:00Z","isAdmin":true}],
             "createdAt":"2026-08-13T12:00:00Z",
             "lastActivity":"2026-08-13T12:00:00Z",
             "isMuted":false}
            """.trimIndent()
        )
        val actual = JSONObject(
            GroupManager.buildIosMessageGroupJson(
                groupId = groupId,
                name = "Team OSHI",
                typeRaw = "collaborative",
                adminPublicKey = adminKey,
                memberKeys = listOf(adminKey),
                adminKeys = listOf(adminKey),
                createdAtMs = createdAtMs,
                lastActivityMs = createdAtMs
            )
        )
        assertEquals(expected.keys().asSequence().toSortedSet(), actual.keys().asSequence().toSortedSet())
        expected.keys().forEach { key ->
            assertEquals("mismatch on '$key'", expected.get(key).toString(), actual.get(key).toString())
        }
    }

    /** The dispatcher must recognise this shape; `type` here is the GROUP type, not an update kind. */
    @Test
    fun `full group payload is distinguishable from a minimal update`() {
        assertTrue(GroupManager.looksLikeIosMessageGroup(sampleGroupJson()))
        assertFalse(
            GroupManager.looksLikeIosMessageGroup(
                JSONObject("""{"type":"member_added","groupId":"x","memberPublicKey":"y"}""")
            )
        )
        assertFalse(
            GroupManager.looksLikeIosMessageGroup(JSONObject("""{"type":"created","name":"n"}"""))
        )
    }

    // =====================================================================
    // 2. member_removed / group_renamed  (iOS parses both, swift:962 & :972)
    // =====================================================================

    @Test
    fun `member_removed matches the keys iOS reads`() {
        val json = JSONObject(GroupManager.buildMemberRemovedJson(groupId, memberKey))
        assertEquals("member_removed", json.getString("type"))
        assertEquals(groupId.uppercase(), json.getString("groupId"))
        assertEquals(memberKey, json.getString("memberPublicKey"))
    }

    @Test
    fun `group_renamed matches the keys iOS reads`() {
        val json = JSONObject(GroupManager.buildGroupRenamedJson(groupId, "New Name"))
        assertEquals("group_renamed", json.getString("type"))
        assertEquals(groupId.uppercase(), json.getString("groupId"))
        assertEquals("New Name", json.getString("name"))
    }

    // =====================================================================
    // 3. Reply envelope
    // =====================================================================

    @Test
    fun `reply envelope uses the iOS prefix and key names`() {
        val raw = GroupManager.buildReplyEnvelope(
            content = "on it",
            originalMessageId = "msg-1",
            originalSenderKey = memberKey,
            originalText = "who is on it?",
            originalTimestampMs = createdAtMs,
            originalMediaType = null
        )
        assertTrue(raw.startsWith("💬REPLY💬"))
        val json = JSONObject(raw.removePrefix("💬REPLY💬"))
        assertEquals("on it", json.getString("content"))
        val replyTo = json.getJSONObject("replyTo")
        assertEquals("msg-1", replyTo.getString("originalMessageId"))
        assertEquals(memberKey, replyTo.getString("originalSenderKey"))
        assertEquals("who is on it?", replyTo.getString("originalText"))
        assertTrue(replyTo.isNull("originalMediaType"))
    }

    /**
     * `ReplyMessage` is encoded by a DEFAULT `JSONEncoder` (MessageActionsManager.swift:105), so
     * `originalTimestamp` is Apple-epoch seconds — not ISO-8601, not Unix millis.
     */
    @Test
    fun `reply timestamp is Apple-epoch seconds`() {
        val json = JSONObject(
            GroupManager.buildReplyEnvelope("x", "id", "k", "t", createdAtMs)
                .removePrefix("💬REPLY💬")
        )
        val ts = json.getJSONObject("replyTo").getDouble("originalTimestamp")
        assertEquals((createdAtMs / 1000.0) - APPLE_OFFSET, ts, 0.001)
        // Sanity: a Unix-millis leak would be ~1.79e12 and put the quote 55 000 years out.
        assertTrue(ts < 1e10)
    }

    /** iOS truncates the quoted preview at 180 chars before it goes on the wire (GroupViews.swift:4673). */
    @Test
    fun `quoted preview is truncated to 180 characters`() {
        val json = JSONObject(
            GroupManager.buildReplyEnvelope("x", "id", "k", "z".repeat(500), createdAtMs)
                .removePrefix("💬REPLY💬")
        )
        assertEquals(180, json.getJSONObject("replyTo").getString("originalText").length)
    }

    /** Group media spelling: iOS's group `MediaType` says "photo", not "image" (swift:376-382). */
    @Test
    fun `reply media type uses the group MediaType spelling`() {
        val json = JSONObject(
            GroupManager.buildReplyEnvelope("x", "id", "k", "t", createdAtMs, "photo")
                .removePrefix("💬REPLY💬")
        )
        assertEquals("photo", json.getJSONObject("replyTo").getString("originalMediaType"))
    }

    @Test
    fun `reply envelope round-trips`() {
        val raw = GroupManager.buildReplyEnvelope("hello", "msg-9", "k", "quoted", createdAtMs)
        val parsed = GroupManager.parseReplyEnvelope(raw)
        assertNotNull(parsed)
        assertEquals("hello", parsed!!.content)
        assertEquals("msg-9", parsed.replyToId)
    }

    @Test
    fun `a plain message is not treated as a reply`() {
        assertNull(GroupManager.parseReplyEnvelope("just a message"))
        assertNull(GroupManager.parseReplyEnvelope("💬REPLY💬not json"))
    }

    // =====================================================================
    // 4. Group typing
    // =====================================================================

    /**
     * `TypingStatus.init(from:)` (TypingIndicatorManager.swift:36-43) decodes `senderPublicKey`,
     * `isTyping` and `timestamp` with plain `decode`, not `decodeIfPresent`. The 1.5.6 payload
     * `{"isTyping":…,"groupId":…}` threw on the very first field.
     */
    @Test
    fun `typing payload carries the three non-optional iOS fields`() {
        val raw = GroupManager.buildGroupTypingPayload(adminKey, true, createdAtMs, groupId, "Alice")
        assertTrue(raw.startsWith("⌨️TYPING⌨️"))
        val json = JSONObject(raw.removePrefix("⌨️TYPING⌨️"))
        assertEquals(adminKey, json.getString("senderPublicKey"))
        assertTrue(json.getBoolean("isTyping"))
        assertTrue(json.has("timestamp"))
        assertEquals(groupId.uppercase(), json.getString("groupId"))
        assertEquals("Alice", json.getString("senderName"))
    }

    /** `timestamp` is a `Date` read by a DEFAULT decoder ⇒ Apple-epoch seconds. */
    @Test
    fun `typing timestamp is Apple-epoch seconds`() {
        val json = JSONObject(
            GroupManager.buildGroupTypingPayload(adminKey, false, createdAtMs, groupId)
                .removePrefix("⌨️TYPING⌨️")
        )
        assertEquals((createdAtMs / 1000.0) - APPLE_OFFSET, json.getDouble("timestamp"), 0.001)
        assertFalse(json.getBoolean("isTyping"))
        assertFalse(json.has("senderName"))
    }

    // =====================================================================
    // 5. Inbound body precedence — the captioned-photo data loss
    // =====================================================================

    private val mediaJson =
        """{"type":"media","mediaType":"photo","content":"aW1hZ2VieXRlcw==","size":11}"""

    /**
     * 🔴 The P0 this fixes. iOS sends a captioned group photo with the caption in
     * `plaintextContent` (GroupMessaging.swift:2368) and the media JSON in `encryptedContent`
     * (:2323-2336). The old ordered fallback let the caption win, the `{`-prefixed media branch
     * never ran, and the image bytes were discarded and never persisted.
     */
    @Test
    fun `captioned iOS photo resolves to the media JSON, not the caption`() {
        val body = GroupManager.resolveIncomingBody(
            contentField = "",
            plaintextContentField = "look at this",
            decodedEncryptedContent = mediaJson
        )
        assertEquals(mediaJson, body)
    }

    /** The uncaptioned case worked before and must keep working. */
    @Test
    fun `uncaptioned iOS photo still resolves to the media JSON`() {
        assertEquals(
            mediaJson,
            GroupManager.resolveIncomingBody("", "", mediaJson)
        )
    }

    /** Android→Android captioned media had the identical bug: `content` held the caption. */
    @Test
    fun `captioned Android photo resolves to the media JSON`() {
        assertEquals(
            mediaJson,
            GroupManager.resolveIncomingBody("look at this", "", mediaJson)
        )
    }

    @Test
    fun `plain text still follows the original fallback order`() {
        assertEquals("hi", GroupManager.resolveIncomingBody("hi", "ignored", "ignored"))
        assertEquals("hi", GroupManager.resolveIncomingBody("", "hi", "ignored"))
        assertEquals("hi", GroupManager.resolveIncomingBody("", "", "hi"))
    }

    /** A `{`-prefixed body that is not a media envelope must not be mistaken for one. */
    @Test
    fun `non-media JSON body is not promoted over the caption`() {
        val locationish = """{"type":"location","lat":1.0}"""
        assertEquals("caption", GroupManager.resolveIncomingBody("caption", "", locationish))
    }

    // =====================================================================
    // 6. Apple epoch helper
    // =====================================================================

    @Test
    fun `apple epoch offset matches 2001-01-01`() {
        assertEquals(978307200.0, GroupManager.APPLE_EPOCH_OFFSET, 0.0)
        assertEquals(0.0, GroupManager.appleEpoch(978307200000L), 0.001)
    }
}
