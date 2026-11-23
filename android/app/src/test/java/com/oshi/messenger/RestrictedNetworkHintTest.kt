package com.oshi.messenger

import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.util.RestrictedNetworkHint
import com.oshi.messenger.util.RestrictedNetworkHintStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** __RESTRICTED_NETWORK_HINT_2026_11_18__ RN-HINT v1, docs/RESTRICTED_NETWORK_HINT.md §6 vectors. */
class RestrictedNetworkHintTest {
    private fun hex(s: String) = s.toByteArray(Charsets.UTF_8).joinToString(" ") { "%02x".format(it) }
    private fun fromHex(h: String) = String(h.split(" ").map { it.toInt(16).toByte() }.toByteArray(), Charsets.UTF_8)

    @Test
    fun markerBytesAreTheSpecBytes() {
        assertEquals("e2 81 a3 e2 81 a4 e2 81 a3", hex(RestrictedNetworkHint.MARKER))
    }

    @Test
    fun specVectors() {
        assertEquals(RestrictedNetworkHint.Inbound("Hello", false), RestrictedNetworkHint.strip(fromHex("48 65 6c 6c 6f")))
        assertEquals(RestrictedNetworkHint.Inbound("Hello", true),
            RestrictedNetworkHint.strip(fromHex("48 65 6c 6c 6f e2 81 a3 e2 81 a4 e2 81 a3")))
        val middle = fromHex("48 65 e2 81 a3 e2 81 a4 e2 81 a3 6c 6c 6f")
        assertEquals(RestrictedNetworkHint.Inbound(middle, false), RestrictedNetworkHint.strip(middle))
        val two = fromHex("48 69 e2 81 a3 e2 81 a4")
        assertEquals(RestrictedNetworkHint.Inbound(two, false), RestrictedNetworkHint.strip(two))
        assertEquals("48 65 6c 6c 6f e2 81 a3 e2 81 a4 e2 81 a3", hex(RestrictedNetworkHint.mark("Hello")))
    }

    @Test
    fun replyEnvelopeVectorBothSpellings() {
        val escaped = "💬REPLY💬{\"content\":\"On my way\\u2063\\u2064\\u2063\",\"replyTo\":{\"originalMessageId\":\"m-1\"," +
            "\"originalSenderKey\":\"k\",\"originalText\":\"Where are you?\",\"originalTimestamp\":785152800}}"
        val raw = escaped.replace("\\u2063\\u2064\\u2063", RestrictedNetworkHint.MARKER)
        for (wire in listOf(escaped, raw)) {
            val r = RestrictedNetworkHint.strip(wire)
            assertTrue(r.flagged)
            val o = JSONObject(r.text.removePrefix(RestrictedNetworkHint.REPLY_PREFIX))
            assertEquals("On my way", o.getString("content"))
            assertEquals("m-1", o.getJSONObject("replyTo").getString("originalMessageId"))
        }
    }

    @Test
    fun roundTripPlainAndReplyAndNoDoubleMarker() {
        val r1 = RestrictedNetworkHint.strip(RestrictedNetworkHint.mark("hi there"))
        assertEquals(RestrictedNetworkHint.Inbound("hi there", true), r1)
        assertEquals(RestrictedNetworkHint.mark("x"), RestrictedNetworkHint.mark(RestrictedNetworkHint.mark("x")))
        val reply = "💬REPLY💬" + JSONObject().put("content", "ok").put("replyTo", JSONObject().put("originalMessageId", "a")).toString()
        val marked = RestrictedNetworkHint.mark(reply)
        assertTrue("marker stays inside valid JSON", marked.endsWith("}"))
        val back = RestrictedNetworkHint.strip(marked)
        assertTrue(back.flagged)
        assertEquals("ok", JSONObject(back.text.removePrefix("💬REPLY💬")).getString("content"))
    }

    @Test
    fun oldFormatsAreUntouched() {
        for (s in listOf("", "plain", "💬REPLY💬{\"content\":\"x\",\"replyTo\":{}}", "💬REPLY💬{broken",
                "📬DELIVERY_RECEIPT📬abc", "➡️FORWARDED➡️{\"content\":\"y\"}")) {
            assertEquals(RestrictedNetworkHint.Inbound(s, false), RestrictedNetworkHint.strip(s))
        }
    }

    @Test
    fun controlPayloadsAndForwardsAreNeverMarked() {
        val isControl: (String) -> Boolean = { OSHIControlPayload.isControl(it) }
        for (s in listOf("📬DELIVERY_RECEIPT📬abc", "📖READ_RECEIPT📖abc", "➡️FORWARDED➡️{\"content\":\"y\"}", "")) {
            assertEquals(s, RestrictedNetworkHint.mark(s, isControl))
        }
        assertEquals("hello" + RestrictedNetworkHint.MARKER, RestrictedNetworkHint.mark("hello", isControl))
    }

    @Test
    fun senderRule() {
        assertTrue(RestrictedNetworkHint.shouldMark(coverOn = true, hintSettingOn = true))
        assertFalse(RestrictedNetworkHint.shouldMark(coverOn = false, hintSettingOn = true))
        assertFalse(RestrictedNetworkHint.shouldMark(coverOn = true, hintSettingOn = false))
    }

    @Test
    fun storeKeepsTheNewestIds() {
        val many = (1..RestrictedNetworkHintStore.MAX + 5).map { "id$it" }.toCollection(LinkedHashSet())
        val kept = RestrictedNetworkHintStore.capped(many)
        assertEquals(RestrictedNetworkHintStore.MAX, kept.size)
        assertTrue("id${RestrictedNetworkHintStore.MAX + 5}" in kept)
        assertFalse("id1" in kept)
        assertEquals(kept, RestrictedNetworkHintStore.decode(RestrictedNetworkHintStore.encode(kept)))
    }

    @Test
    fun senderAndReceiverAreWired() {
        val repo = SourceText.read("src/main/java/com/oshi/messenger/data/repository/MessageRepository.kt")
        assertEquals("every V2 send site uses the hinted bytes", 3, Regex("v2Content\\.toByteArray").findAll(repo).count())
        assertTrue(repo.contains("dispatchV2Inbound(rn.text, inbound.from, inbound.msgId, inbound.ts)"))
        assertTrue(repo.contains("val displayContent = rnHint.text"))
        val bubble = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/ChatScreen.kt")
        assertTrue(bubble.contains("rememberRestrictedNetworkFlag(message.id)"))
    }
}
