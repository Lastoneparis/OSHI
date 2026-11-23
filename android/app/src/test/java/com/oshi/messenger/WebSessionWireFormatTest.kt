package com.oshi.messenger

import com.oshi.messenger.network.web.WebSessionManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format guards for the OSHI Web relay protocol.
 *
 * Every expectation here is a line of `OSHI/WebSessionManager.swift`. The
 * browser is written against the iOS shapes; a divergence here is invisible in
 * a build and shows up as "the web page is empty".
 */
class WebSessionWireFormatTest {

    // -----------------------------------------------------------------------
    // AUTH — the bug that made OSHI Web one-way on Android
    // -----------------------------------------------------------------------

    @Test
    fun `signed auth envelope is mobile_auth with camelCase publicKey`() {
        val json = WebSessionManager.buildSignedAuthEnvelope(
            publicKey = "AAAA/BBB+CC=", signingPublicKeyB64 = "c2lnbmluZ0tleQ==",
            timestamp = "1786961472345", nonce = "abcd1234abcd1234"
        ) { ByteArray(64) }
        // swift — {"type":"mobile_auth","publicKey":<camelCase>,…}
        assertEquals("mobile_auth", json.getString("type"))
        assertEquals("AAAA/BBB+CC=", json.getString("publicKey"))
        // The old snake_case Android envelope is not spoken by the relay.
        assertFalse(json.has("public_key"))
        assertFalse(json.has("platform"))
    }

    @Test
    fun `signed auth envelope carries proof-of-key fields`() {
        var signedBytes: ByteArray? = null
        val json = WebSessionManager.buildSignedAuthEnvelope(
            publicKey = "PK", signingPublicKeyB64 = "U0lHTlBL",
            timestamp = "1700000000000", nonce = "n0nc3n0nc3n0nc3x"
        ) { bytes -> signedBytes = bytes; ByteArray(64) { 7 } }
        // Every field the relay's verifyMobileAuth() requires must be present.
        assertEquals(1, json.getInt("protocolVersion"))
        assertEquals("/ws", json.getString("path"))
        assertEquals("1700000000000", json.getString("timestamp"))
        assertEquals("n0nc3n0nc3n0nc3x", json.getString("nonce"))
        assertEquals("U0lHTlBL", json.getString("signingPublicKey"))
        assertTrue(json.has("signature"))
        // What was signed is EXACTLY the canonical string — byte-identical across
        // iOS, Android and the server, including the literal backslash-n separators.
        assertEquals(
            "OSHI-MOBILE-AUTH-V1\\n1\\nPK\\n1700000000000\\nn0nc3n0nc3n0nc3x\\n/ws",
            String(signedBytes!!, Charsets.UTF_8)
        )
    }

    @Test
    fun `canonical mobile auth separator is literal backslash-n not a newline`() {
        val c = WebSessionManager.canonicalMobileAuth("PK", "123", "nonce")
        // A real newline here would silently never verify once enforce is on.
        assertFalse("must not contain a real newline", c.contains('\n'))
        assertTrue(c.startsWith("OSHI-MOBILE-AUTH-V1\\n1\\nPK\\n"))
        assertTrue(c.endsWith("\\n/ws"))
    }

    // -----------------------------------------------------------------------
    // TIMESTAMPS — iOS uses ISO-8601 WITH fractional seconds (swift:627-631)
    // -----------------------------------------------------------------------

    @Test
    fun `web timestamp is ISO8601 with milliseconds in UTC`() {
        // 2026-08-13T10:11:12.345Z
        val ms = 1786961472345L
        val s = WebSessionManager.webTimestamp(ms)
        assertTrue("got $s", Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$").matches(s))
        assertTrue(s.endsWith(".345Z"))
    }

    @Test
    fun `two messages in the same second do not collapse to the same string`() {
        val a = WebSessionManager.webTimestamp(1_700_000_000_001L)
        val b = WebSessionManager.webTimestamp(1_700_000_000_999L)
        assertFalse("truncating to the second destroys message ordering", a == b)
        assertTrue("fixed-width ISO strings must sort chronologically", a < b)
    }

    // -----------------------------------------------------------------------
    // CONVERSATION / MESSAGE ROWS
    // -----------------------------------------------------------------------

    @Test
    fun `conversation row matches iOS keys`() {
        val j = WebSessionManager.buildConversationJson(
            publicKey = "PK", contactName = "Ada",
            lastMessage = "hi", lastMessageEpochMillis = 1_700_000_000_000L, unreadCount = 3
        )
        // swift:636-645
        assertEquals("PK", j.getString("publicKey"))
        assertEquals("Ada", j.getString("contactName"))
        assertEquals("hi", j.getString("lastMessage"))
        assertEquals(3, j.getInt("unreadCount"))
        // backward-compat duplicate iOS also sends (swift:644)
        assertEquals("Ada", j.getString("alias"))
        assertTrue(j.getString("lastMessageTime").endsWith("Z"))
    }

    @Test
    fun `message row matches iOS keys and is not an epoch number`() {
        val j = WebSessionManager.buildMessageJson(
            id = "m1", content = "yo", epochMillis = 1_700_000_000_000L, isFromMe = true,
            senderPublicKey = "A", recipientPublicKey = "B", status = "sent"
        )
        assertEquals("m1", j.getString("id"))
        assertEquals("yo", j.getString("content"))
        assertEquals(true, j.getBoolean("isFromMe"))
        assertEquals("A", j.getString("senderPublicKey"))
        assertEquals("B", j.getString("recipientPublicKey"))
        assertEquals("sent", j.getString("status"))
        assertTrue(j.get("timestamp") is String)
    }

    @Test
    fun `media under 5MB is inlined and above it becomes a url`() {
        val small = WebSessionManager.buildMessageJson(
            id = "m", content = "", epochMillis = 0, isFromMe = false,
            senderPublicKey = "A", recipientPublicKey = "B", status = "delivered",
            mediaType = "image", fileName = "a.jpg", mediaSize = 50_000, mediaBase64 = "QUJD"
        )
        assertEquals("QUJD", small.getString("mediaData"))
        // iOS keeps a thumbnail duplicate for small images (swift:677-679)
        assertEquals("QUJD", small.getString("thumbnailData"))
        assertFalse(small.has("mediaUrl"))

        val big = WebSessionManager.buildMessageJson(
            id = "m2", content = "", epochMillis = 0, isFromMe = false,
            senderPublicKey = "A", recipientPublicKey = "B", status = "delivered",
            mediaType = "video", fileName = null, mediaSize = 9_000_000, mediaBase64 = null
        )
        assertEquals("/api/web/media/m2", big.getString("mediaUrl"))
        assertFalse(big.has("mediaData"))
    }

    @Test
    fun `media type names match the iOS web vocabulary`() {
        // swift:939-947 — DOCUMENT maps to "file", not "document"
        assertEquals("image", WebSessionManager.mapMediaType("IMAGE"))
        assertEquals("video", WebSessionManager.mapMediaType("VIDEO"))
        assertEquals("audio", WebSessionManager.mapMediaType("AUDIO"))
        assertEquals("file", WebSessionManager.mapMediaType("DOCUMENT"))
        assertEquals("contact", WebSessionManager.mapMediaType("CONTACT"))
        assertEquals(null, WebSessionManager.mapMediaType(null))
    }

    @Test
    fun `delivery status read wins over everything`() {
        // swift:928-937 — `if isRead { return "read" }` before the switch
        assertEquals("read", WebSessionManager.mapDeliveryStatus(true, true, false, true))
        assertEquals("failed", WebSessionManager.mapDeliveryStatus(false, false, true, true))
        assertEquals("delivered", WebSessionManager.mapDeliveryStatus(false, true, false, true))
        assertEquals("sent", WebSessionManager.mapDeliveryStatus(false, false, false, true))
    }

    // -----------------------------------------------------------------------
    // NORMALISATION — callers outside the manager still emit epoch millis
    // -----------------------------------------------------------------------

    @Test
    fun `numeric timestamps from other callers are rewritten to ISO`() {
        val group = JSONObject()
            .put("id", "g1")
            .put("lastMessageTime", 1_700_000_000_000L)
        val out = WebSessionManager.normalizeTimestamps(group)
        assertTrue(out.get("lastMessageTime") is String)
        assertTrue(out.getString("lastMessageTime").endsWith("Z"))
    }

    @Test
    fun `already ISO timestamps are left alone`() {
        val j = JSONObject().put("timestamp", "2026-08-13T10:11:12.345Z")
        WebSessionManager.normalizeTimestamps(j)
        assertEquals("2026-08-13T10:11:12.345Z", j.getString("timestamp"))
    }

    // -----------------------------------------------------------------------
    // CONTROL ENVELOPES never reach the browser as chat content
    // -----------------------------------------------------------------------

    @Test
    fun `control envelopes are not chat content`() {
        assertTrue(WebSessionManager.isControlMessage("📬" + "DELIVERY_RECEIPT" + "📬{}"))
        assertTrue(WebSessionManager.isControlMessage("🔗" + "LINKS_VISIBILITY" + "🔗{}"))
        assertFalse(WebSessionManager.isControlMessage("hello there"))
    }
}
