package com.oshi.messenger

import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.service.TypingIndicatorManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The typing ping must survive iOS's `JSONDecoder`.
 *
 * `TypingStatus` (OSHI/TypingIndicatorManager.swift:14-42) declares:
 *
 *     senderPublicKey: String   try c.decode          NON-optional
 *     isTyping:        Bool     try c.decode          NON-optional
 *     timestamp:       Date     try c.decode          NON-optional
 *     groupId:         String?  decodeIfPresent
 *     senderName:      String?  decodeIfPresent
 *
 * and decodes with a plain `JSONDecoder()` (swift:57), whose default date
 * strategy is `.deferredToDate` — a Double count of seconds since the Apple
 * reference date, 2001-01-01. Android used to send `{"isTyping":true}`: the
 * decode threw, `fromMessageString` returned nil, and the iPhone silently
 * dropped every ping.
 */
class TypingIndicatorWireFormatTest {

    private val appleOffset = 978307200.0

    @Test
    fun payloadCarriesEveryFieldIOSDecodesNonOptionally() {
        val json = TypingIndicatorManager.buildTypingPayload(
            senderPublicKey = "abc123",
            isTyping = true,
            appleTimestamp = 800_000_000.0
        )

        assertTrue("senderPublicKey missing → iOS decode throws", json.has("senderPublicKey"))
        assertTrue("isTyping missing → iOS decode throws", json.has("isTyping"))
        assertTrue("timestamp missing → iOS decode throws", json.has("timestamp"))

        assertEquals("abc123", json.getString("senderPublicKey"))
        assertEquals(true, json.getBoolean("isTyping"))
        assertEquals(800_000_000.0, json.getDouble("timestamp"), 0.0)
    }

    @Test
    fun optionalFieldsAreOmittedNotNulled() {
        // iOS uses decodeIfPresent for both; a 1-on-1 ping must look exactly like
        // iOS's own (`var groupId: String? = nil`, swift:20-21).
        val oneToOne = TypingIndicatorManager.buildTypingPayload(
            senderPublicKey = "abc",
            isTyping = false,
            appleTimestamp = 1.0
        )
        assertFalse(oneToOne.has("groupId"))
        assertFalse(oneToOne.has("senderName"))

        val group = TypingIndicatorManager.buildTypingPayload(
            senderPublicKey = "abc",
            isTyping = true,
            appleTimestamp = 1.0,
            groupId = "g-1",
            senderName = "Ada"
        )
        assertEquals("g-1", group.getString("groupId"))
        assertEquals("Ada", group.getString("senderName"))
    }

    @Test
    fun timestampIsAppleReferenceSecondsNotUnixMillis() {
        val before = (System.currentTimeMillis() / 1000.0) - appleOffset
        val ts = TypingIndicatorManager.nowAsAppleTimestamp()
        val after = (System.currentTimeMillis() / 1000.0) - appleOffset

        assertTrue("timestamp not in range", ts in before..after)
        // Apple-epoch seconds today are ~8e8; Unix millis are ~1.7e12 and Unix
        // seconds ~1.7e9. Anything above 1e10 means the epoch is wrong.
        assertTrue("looks like Unix millis, not Apple seconds", ts < 1e10)
        assertTrue("suspiciously small", ts > 7e8)
    }

    /** The whole wire string, prefix included. */
    @Test
    fun wireStringUsesTheIOSPrefixAndDecodableBody() {
        val json = TypingIndicatorManager.buildTypingPayload(
            senderPublicKey = "key",
            isTyping = true,
            appleTimestamp = 800_000_000.0
        )
        val wire = "${OSHIControlPayload.TYPING_IOS}$json"

        assertEquals(OSHIControlPayload.TYPING_IOS, OSHIControlPayload.matchedPrefix(wire))
        // The prefix is in iOS's SILENT catalog, so a malformed body is dropped
        // without a trace — which is why this has to be tested, not eyeballed.
        assertTrue(OSHIControlPayload.isSilent(wire))

        val body = org.json.JSONObject(OSHIControlPayload.stripPrefix(wire))
        assertEquals("key", body.getString("senderPublicKey"))
        assertEquals(true, body.getBoolean("isTyping"))
        assertEquals(800_000_000.0, body.getDouble("timestamp"), 0.0)
    }

    /** An older client that emits the bare ⌨ codepoint must still parse. */
    @Test
    fun bareCodepointTypingPrefixStillStrips() {
        val bare = "⌨TYPING⌨{\"senderPublicKey\":\"k\",\"isTyping\":true,\"timestamp\":1.0}"
        assertEquals(OSHIControlPayload.TYPING_IOS, OSHIControlPayload.matchedPrefix(bare))
        val body = org.json.JSONObject(OSHIControlPayload.stripPrefix(bare))
        assertEquals("k", body.getString("senderPublicKey"))
    }
}
