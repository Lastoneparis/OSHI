package com.oshi.messenger.wear

import com.oshi.messenger.wear.data.Reason
import com.oshi.wearbridge.WearConvKind
import com.oshi.wearbridge.WearConversation
import com.oshi.wearbridge.WearMedia
import com.oshi.wearbridge.WearMessage
import com.oshi.wearbridge.WearOp
import com.oshi.wearbridge.WearProtocol
import com.oshi.wearbridge.WearRequest
import com.oshi.wearbridge.WearResponse
import com.oshi.wearbridge.WearStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The watch side of the bridge wire format (the phone runs WearBridgeHandlerTest). */
class WearProtocolTest {

    @Test
    fun `every request shape round trips`() {
        val reqs = listOf(
            WearRequest("r1", WearOp.LIST, limit = 30),
            WearRequest("r2", WearOp.THREAD, WearConvKind.GROUP, "7f3c2a10-5b9e-4c1d-8e2f-a6b7c8d9e0f1", limit = 20),
            WearRequest("r3", WearOp.SEND, WearConvKind.DIRECT, "Lq8m/3Vb+0yT==", text = "On arrive 🚲\nà 19h"),
            WearRequest("r4", WearOp.REACT, WearConvKind.DIRECT, "Lq8m/3Vb+0yT==", messageId = "m-1", emoji = "⭕"),
        )
        for (r in reqs) {
            assertEquals(r, WearRequest.decode(r.encode()))
            assertTrue("$r", r.isWellFormed())
        }
    }

    @Test
    fun `response with conversations and messages round trips`() {
        val resp = WearResponse(
            "rid", WearStatus.OK,
            conversations = listOf(
                WearConversation(WearConvKind.DIRECT, "k1=", "Léa", "📷 Photo", 2, 1_700_000_000_000, "k1=", lastFromMe = true),
                WearConversation(WearConvKind.GROUP, "g-1", "Crew", "Sam: hi", 0, 1_700_000_000_001, "g-1", canReply = false),
            ),
            messages = listOf(
                WearMessage("m1", false, "Sam", "sk=", "", WearMedia.VOICE, 5, listOf("❤️", "⭕"), "❤️"),
                WearMessage("m2", true, text = "ok", timestamp = 6),
            ),
            title = "Crew",
        )
        assertEquals(resp, WearResponse.decode(resp.encode()))
    }

    @Test
    fun `status-only answers decode to the right reason`() {
        for (s in WearStatus.values().filter { it != WearStatus.OK }) {
            val back = WearResponse.decode(WearResponse.status("x", s).encode())!!
            assertEquals(s, back.status)
            assertTrue(back.conversations.isEmpty() && back.messages.isEmpty())
        }
        assertEquals(Reason.DISABLED, Reason.of(WearStatus.DISABLED))
        assertEquals(Reason.LOCKED, Reason.of(WearStatus.LOCKED))
        assertEquals(Reason.UNAVAILABLE, Reason.of(WearStatus.UNAVAILABLE))
    }

    @Test
    fun `an unknown status from a newer phone reads as an error, not a crash`() {
        val bytes = """{"v":1,"rid":"a","status":"quantum"}""".toByteArray()
        assertEquals(WearStatus.ERROR, WearResponse.decode(bytes)!!.status)
    }

    @Test
    fun `garbage, wrong version and oversize are refused`() {
        assertNull(WearRequest.decode(null))
        assertNull(WearRequest.decode("not json".toByteArray()))
        assertNull(WearRequest.decode("""{"v":2,"rid":"a","op":"list"}""".toByteArray()))
        assertNull(WearRequest.decode("""{"v":1,"rid":"a","op":"delete_everything"}""".toByteArray()))
        assertNull(WearResponse.decode(ByteArray(WearProtocol.MAX_PAYLOAD_BYTES + 1) { 'a'.code.toByte() }))
    }

    @Test
    fun `only the seven quick reactions are well formed`() {
        assertEquals(listOf("⭕", "❤️", "😂", "😢", "👍", "😮", "🔥"), WearProtocol.QUICK_REACTIONS)
        for (e in WearProtocol.QUICK_REACTIONS) {
            assertTrue(WearRequest("r", WearOp.REACT, WearConvKind.DIRECT, "k", messageId = "m", emoji = e).isWellFormed())
        }
        for (e in listOf("⭕️", "❤", "🖕", "", "⭕😊")) {
            assertFalse(e, WearRequest("r", WearOp.REACT, WearConvKind.DIRECT, "k", messageId = "m", emoji = e).isWellFormed())
        }
    }

    @Test
    fun `send limits are enforced`() {
        val ok = WearRequest("r", WearOp.SEND, WearConvKind.DIRECT, "k", text = "x".repeat(WearProtocol.MAX_SEND_CHARS))
        val tooLong = ok.copy(text = "x".repeat(WearProtocol.MAX_SEND_CHARS + 1))
        assertTrue(ok.isWellFormed())
        assertFalse(tooLong.isWellFormed())
        assertFalse(ok.copy(text = "   ").isWellFormed())
        assertFalse(ok.copy(cid = null).isWellFormed())
        assertFalse(ok.copy(kind = null).isWellFormed())
    }

    @Test
    fun `a huge thread is trimmed oldest first to fit one message`() {
        val big = (1..20).map { WearMessage("m$it", it % 2 == 0, text = "€".repeat(600), timestamp = it.toLong()) }
        val many = (1..30).map {
            WearConversation(WearConvKind.DIRECT, "k$it".repeat(20), "n".repeat(200), "p".repeat(500), 1, it.toLong(), "k")
        }
        val bytes = WearResponse("r", WearStatus.OK, many, big).encode()
        assertTrue(bytes.size <= WearProtocol.MAX_PAYLOAD_BYTES)
        val back = WearResponse.decode(bytes)!!
        assertTrue(back.messages.isNotEmpty())
        assertEquals("m20", back.messages.last().id)
        // Long texts are clipped with an ellipsis, never split mid surrogate pair.
        assertTrue(back.conversations.first().name.length <= WearProtocol.MAX_NAME_CHARS)
        val emoji = WearConversation(WearConvKind.DIRECT, "k", "😀".repeat(40), "", 0, 0, "k")
        val clipped = WearResponse.decode(WearResponse("r", WearStatus.OK, listOf(emoji)).encode())!!.conversations[0].name
        assertFalse("split a surrogate pair", Character.isHighSurrogate(clipped[clipped.length - 2]))
        assertTrue(clipped.endsWith("…"))
    }
}
