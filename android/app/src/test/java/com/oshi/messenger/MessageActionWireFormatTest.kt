package com.oshi.messenger

import com.oshi.messenger.data.repository.MessageRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 🔧ACTION🔧 payload Android now EMITS for delete-for-everyone and edit, against
 * iOS's `MessageActionPayload` (`OSHI/MessageManager.swift:8089-8121`).
 *
 * Until this change both actions were a local DAO write plus a `// TODO`: the sender saw
 * "This message was deleted" / "(edited)" and the recipient kept the original forever.
 */
class MessageActionWireFormatTest {

    private val prefix = "🔧ACTION🔧"   // 🔧ACTION🔧

    private fun body(payload: String): JSONObject {
        assertTrue("must carry the iOS prefix", payload.startsWith(prefix))
        return JSONObject(payload.removePrefix(prefix))
    }

    @Test
    fun `deleteForEveryone matches the iOS shape`() {
        val payload = MessageRepository.buildActionPayload(
            "deleteForEveryone", "1B2C3D4E-5F60-7182-9394-A5B6C7D8E9F0", null, nowMs = 1_770_000_000_000L
        )
        val j = body(payload)
        assertEquals("deleteForEveryone", j.getString("actionType"))
        assertEquals("1B2C3D4E-5F60-7182-9394-A5B6C7D8E9F0", j.getString("targetMessageId"))
        // Swift encodes Optionals with encodeIfPresent — a nil newContent is ABSENT.
        assertFalse("newContent must be omitted, not null", j.has("newContent"))
    }

    @Test
    fun `editMessage carries newContent`() {
        val payload = MessageRepository.buildActionPayload(
            "editMessage", "msg-1", "corrected text", nowMs = 1_770_000_000_000L
        )
        val j = body(payload)
        assertEquals("editMessage", j.getString("actionType"))
        assertEquals("msg-1", j.getString("targetMessageId"))
        assertEquals("corrected text", j.getString("newContent"))
    }

    @Test
    fun `timestamp is Apple-epoch seconds, not Unix milliseconds`() {
        // A default Swift JSONEncoder writes Date as secondsSinceReferenceDate
        // (2001-01-01). MessageActionsManager.kt:284 emitted System.currentTimeMillis(),
        // which iOS decodes without throwing as a year-55000 Date and then stamps into
        // `editedAt` (MessageManager.swift:7113), driving the "(edited)" indicator.
        val nowMs = 1_770_000_000_000L
        val j = body(MessageRepository.buildActionPayload("editMessage", "m", "x", nowMs))
        val ts = j.getDouble("timestamp")

        assertEquals((nowMs / 1000.0) - 978307200.0, ts, 1e-6)
        assertEquals(978307200.0, MessageRepository.APPLE_EPOCH_OFFSET_SECONDS, 0.0)
        // Sanity: a plausible Apple-epoch value, i.e. this century, not 1.77e12.
        assertTrue("timestamp looks like Unix millis", ts < 4_000_000_000.0)
        assertTrue(ts > 700_000_000.0)
    }

    @Test
    fun `content with quotes and newlines survives the JSON encoding`() {
        val nasty = "he said \"hi\"\nthen left\\"
        val j = body(MessageRepository.buildActionPayload("editMessage", "m", nasty, 0L))
        assertEquals(nasty, j.getString("newContent"))
    }

    @Test
    fun `prefix is exactly the iOS one`() {
        // MessageActionPayload.messagePrefix = "\u{1F527}ACTION\u{1F527}"
        val payload = MessageRepository.buildActionPayload("pinMessage", "m", null, 0L)
        assertTrue(payload.startsWith("🔧ACTION🔧"))
        // The emoji precedes the '{' — anything scanning for a leading '{' will miss it.
        assertTrue(payload.indexOf('{') > 0)
    }
}
