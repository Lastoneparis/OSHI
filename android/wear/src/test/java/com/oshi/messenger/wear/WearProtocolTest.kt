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
import com.oshi.wearbridge.WearDelivery
import com.oshi.wearbridge.WearNet
import com.oshi.wearbridge.WearVoiceFrame
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

    // ---- __WEAR_POLISH_2026_11_25__ -------------------------------------

    @Test
    fun `new request shapes round trip and are checked`() {
        val read = WearRequest("r5", WearOp.READ, WearConvKind.DIRECT, "k=")
        val thumb = WearRequest("r6", WearOp.THUMB, WearConvKind.GROUP, "g-1", messageId = "m-9")
        val voice = WearRequest("r7", WearOp.VOICE, WearConvKind.DIRECT, "k=", durationMs = 4_200)
        for (r in listOf(read, thumb, voice)) {
            assertEquals(r, WearRequest.decode(r.encode()))
            assertTrue("$r", r.isWellFormed())
        }
        assertFalse(read.copy(cid = null).isWellFormed())
        assertFalse(thumb.copy(messageId = null).isWellFormed())
        assertFalse(thumb.copy(messageId = "x".repeat(129)).isWellFormed())
        assertFalse(voice.copy(durationMs = WearProtocol.MAX_VOICE_MS + 1).isWellFormed())
        assertFalse(voice.copy(durationMs = WearProtocol.MIN_VOICE_MS - 1).isWellFormed())
    }

    @Test
    fun `list answer carries caps, network, strict and pins`() {
        val resp = WearResponse(
            "rid", WearStatus.OK,
            conversations = listOf(WearConversation(WearConvKind.DIRECT, "k", "Léa", "hi", 1, 1, "k", pinned = true)),
            caps = WearProtocol.PHONE_CAPS,
            net = WearNet(internet = false, meshPeers = 3, lora = true),
            strict = true,
        )
        val back = WearResponse.decode(resp.encode())!!
        assertEquals(resp, back)
        assertTrue(back.conversations.single().pinned)
        assertFalse(back.net!!.isOffline)
        assertTrue(WearNet(internet = false).isOffline)
    }

    @Test
    fun `a 1_7_3 phone answer (no caps, no net) reads as nothing new supported`() {
        // Exactly what the 1.7.3 phone sends: none of the new keys.
        val old = """{"v":1,"rid":"a","status":"ok","convs":[{"kind":"dm","cid":"k","name":"Léa","preview":"hi","unread":1,"ts":1,"akey":"k"}],
            "msgs":[{"mid":"m","mine":true,"text":"yo","ts":2}]}""".toByteArray()
        val r = WearResponse.decode(old)!!
        assertTrue(r.caps.isEmpty())
        assertNull(r.net)
        assertFalse(r.strict)
        assertFalse(r.conversations.single().pinned)
        val m = r.messages.single()
        assertNull(m.delivery)
        assertFalse(m.hasThumb)
        assertEquals(0, m.durationSec)
    }

    @Test
    fun `message delivery, preview and duration round trip`() {
        val msgs = listOf(
            WearMessage("a", true, text = "x", timestamp = 1, delivery = WearDelivery.READ),
            WearMessage("b", false, text = "", media = WearMedia.PHOTO, timestamp = 2, hasThumb = true),
            WearMessage("c", false, text = "", media = WearMedia.VOICE, timestamp = 3, durationSec = 14),
            WearMessage("d", true, text = "y", timestamp = 4, delivery = WearDelivery.FAILED),
        )
        assertEquals(msgs, WearResponse.decode(WearResponse("r", WearStatus.OK, messages = msgs).encode())!!.messages)
        // Unknown future delivery value: no tick rather than a crash.
        val future = """{"v":1,"rid":"a","status":"ok","msgs":[{"mid":"m","mine":true,"text":"yo","ts":2,"st":"teleported"}]}"""
        assertNull(WearResponse.decode(future.toByteArray())!!.messages.single().delivery)
    }

    @Test
    fun `a thumbnail round trips and an oversized one is dropped`() {
        val jpeg = ByteArray(1_000) { (it * 7).toByte() }
        val back = WearResponse.decode(WearResponse("r", WearStatus.OK, thumb = jpeg).encode())!!
        assertTrue(jpeg.contentEquals(back.thumb))
        val huge = WearResponse("r", WearStatus.OK, thumb = ByteArray(WearProtocol.MAX_THUMB_BYTES + 1)).encode()
        assertNull(WearResponse.decode(huge)!!.thumb)
        assertTrue(huge.size <= WearProtocol.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun `voice frame round trips and refuses what it must`() {
        val m4a = byteArrayOf(0, 0, 0, 24) + "ftypM4A ".toByteArray() + ByteArray(5_000) { it.toByte() }
        val req = WearRequest("v", WearOp.VOICE, WearConvKind.GROUP, "g", durationMs = 3_000)
        val (h, a) = WearVoiceFrame.decode(WearVoiceFrame.encode(req, m4a))!!
        assertEquals(req, h)
        assertTrue(m4a.contentEquals(a))
        // Not an MP4 container.
        assertNull(WearVoiceFrame.decode(WearVoiceFrame.encode(req, ByteArray(500))))
        // Over the cap: refused while reading, before it is all in memory.
        assertNull(WearVoiceFrame.decode(WearVoiceFrame.encode(req, m4a + ByteArray(WearProtocol.MAX_VOICE_BYTES))))
        // A header that is not a VOICE request.
        assertNull(WearVoiceFrame.decode(WearVoiceFrame.encode(WearRequest("x", WearOp.LIST), m4a)))
        // Truncated / absurd header lengths.
        assertNull(WearVoiceFrame.decode(byteArrayOf(0, 0)))
        assertNull(WearVoiceFrame.decode(byteArrayOf(0x7f, 0, 0, 0) + m4a))
    }

    @Test
    fun `tile picks who to show and whom Reply answers`() {
        fun c(id: String, unread: Int, ts: Long, canReply: Boolean = true) =
            WearConversation(WearConvKind.DIRECT, id, id, "", unread, ts, id, canReply = canReply)
        val convs = listOf(c("a", 0, 9), c("b", 2, 8), c("sys", 5, 7, canReply = false), c("d", 0, 6))
        assertEquals("b", com.oshi.messenger.wear.tile.TileLayout.replyTarget(convs)!!.id)
        assertEquals("a", com.oshi.messenger.wear.tile.TileLayout.replyTarget(listOf(c("a", 0, 9)))!!.id)
        assertNull(com.oshi.messenger.wear.tile.TileLayout.replyTarget(listOf(c("sys", 1, 1, canReply = false))))
        // Unread first, then recent; 2 on a small round screen, 3 on a large one.
        assertEquals(listOf("b", "sys"), com.oshi.messenger.wear.tile.TileLayout.avatars(convs, 192).map { it.id })
        assertEquals(listOf("b", "sys", "a"), com.oshi.messenger.wear.tile.TileLayout.avatars(convs, 227).map { it.id })
    }
}
