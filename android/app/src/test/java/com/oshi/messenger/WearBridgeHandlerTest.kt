package com.oshi.messenger

import com.oshi.messenger.data.model.OshiReaction
import com.oshi.messenger.service.wear.WearBridgeBackend
import com.oshi.messenger.service.wear.WearBridgeHandler
import com.oshi.messenger.service.wear.WearLockPolicy
import com.oshi.messenger.ui.theme.AvatarIdentity
import com.oshi.wearbridge.WearAvatar
import com.oshi.wearbridge.WearConvKind
import com.oshi.wearbridge.WearConversation
import com.oshi.wearbridge.WearMessage
import com.oshi.wearbridge.WearNet
import com.oshi.wearbridge.WearVoiceFrame
import com.oshi.wearbridge.WearOp
import com.oshi.wearbridge.WearProtocol
import com.oshi.wearbridge.WearRequest
import com.oshi.wearbridge.WearResponse
import com.oshi.wearbridge.WearStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __WEAR_BRIDGE_2026_11_17__ The phone side of the watch bridge: the gate
 * order (off → invalid → locked → not ready → data), the routing to the
 * existing send / reaction paths, and the two parity guards with the rest of
 * the app (quick reactions, avatar palette).
 */
class WearBridgeHandlerTest {

    private class FakeBackend(
        var enabled: Boolean = true,
        var locked: Boolean = false,
        var ready: Boolean = true,
    ) : WearBridgeBackend {
        val calls = mutableListOf<String>()
        override suspend fun isEnabled() = enabled
        override suspend fun isLocked() = locked
        override fun isReady() = ready
        override suspend fun conversations(limit: Int): List<WearConversation> {
            calls += "list:$limit"
            return listOf(WearConversation(WearConvKind.DIRECT, "k=", "Léa", "hi", 1, 1, "k="))
        }
        override suspend fun thread(kind: WearConvKind, id: String, limit: Int): WearBridgeBackend.Thread? {
            calls += "thread:${kind.wire}:$id:$limit"
            if (id == "missing") return null
            return WearBridgeBackend.Thread("Léa", true, listOf(WearMessage("m1", false, text = "yo", timestamp = 1)))
        }
        override suspend fun send(kind: WearConvKind, id: String, text: String): WearStatus {
            calls += "send:${kind.wire}:$id:$text"; return WearStatus.OK
        }
        override suspend fun react(kind: WearConvKind, id: String, messageId: String, emoji: String): WearStatus {
            calls += "react:${kind.wire}:$id:$messageId:$emoji"; return WearStatus.OK
        }
        override suspend fun markRead(kind: WearConvKind, id: String): WearStatus {
            calls += "read:${kind.wire}:$id"; return WearStatus.OK
        }
        var thumb: ByteArray? = byteArrayOf(1, 2, 3)
        override suspend fun thumbnail(kind: WearConvKind, id: String, messageId: String): ByteArray? {
            calls += "thumb:${kind.wire}:$id:$messageId"; return thumb
        }
        override suspend fun sendVoice(kind: WearConvKind, id: String, audio: ByteArray, durationMs: Long): WearStatus {
            calls += "voice:${kind.wire}:$id:${audio.size}:$durationMs"; return WearStatus.OK
        }
        override fun network() = WearNet(internet = false, meshPeers = 2, lora = true)
        var strict = false
        override fun strictGlance() = strict
    }

    /** A minimal ISO-BMFF header: the `ftyp` box the voice path insists on. */
    private val m4a = byteArrayOf(0, 0, 0, 24) + "ftypM4A ".toByteArray() + ByteArray(200)
    private fun voiceFrame(dur: Long = 4_000, audio: ByteArray = m4a, cid: String = "k=") =
        WearVoiceFrame.encode(WearRequest("rv", WearOp.VOICE, WearConvKind.DIRECT, cid, durationMs = dur), audio)
    private fun handleVoice(b: FakeBackend, bytes: ByteArray) = runBlocking { WearBridgeHandler(b).handleVoice(bytes) }

    private fun handle(b: FakeBackend, r: WearRequest): WearResponse =
        runBlocking { WearBridgeHandler(b).handle(r.encode())!! }

    private val list = WearRequest("r", WearOp.LIST)
    private val send = WearRequest("r", WearOp.SEND, WearConvKind.GROUP, "g-1", text = "  on my way  ")
    private val react = WearRequest("r", WearOp.REACT, WearConvKind.DIRECT, "k=", messageId = "m1", emoji = "⭕")

    @Test
    fun `bridge off answers disabled and touches nothing`() {
        val b = FakeBackend(enabled = false, locked = true, ready = false)
        for (r in listOf(list, send, react, WearRequest("r", WearOp.REACT, WearConvKind.DIRECT, "k", messageId = "m", emoji = "🖕"))) {
            val resp = handle(b, r)
            assertEquals(WearStatus.DISABLED, resp.status)
            assertTrue(resp.conversations.isEmpty() && resp.messages.isEmpty())
        }
        assertTrue("backend was asked for data while off: ${b.calls}", b.calls.isEmpty())
    }

    @Test
    fun `locked answers locked and touches nothing`() {
        val b = FakeBackend(locked = true)
        listOf(list, send, react).forEach { assertEquals(WearStatus.LOCKED, handle(b, it).status) }
        assertTrue(b.calls.isEmpty())
    }

    @Test
    fun `not ready answers unavailable`() {
        val b = FakeBackend(ready = false)
        assertEquals(WearStatus.UNAVAILABLE, handle(b, list).status)
        assertTrue(b.calls.isEmpty())
    }

    @Test
    fun `malformed requests are invalid, and undecodable bytes get no answer`() {
        val b = FakeBackend()
        assertEquals(WearStatus.INVALID, handle(b, react.copy(emoji = "🖕")).status)
        assertEquals(WearStatus.INVALID, handle(b, send.copy(text = "x".repeat(WearProtocol.MAX_SEND_CHARS + 1))).status)
        assertEquals(WearStatus.INVALID, handle(b, WearRequest("r", WearOp.THREAD)).status)
        assertNull(runBlocking { WearBridgeHandler(b).handle("{oops".toByteArray()) })
        assertTrue(b.calls.isEmpty())
    }

    @Test
    fun `enabled and unlocked routes to the backend`() {
        val b = FakeBackend()
        val l = handle(b, list)
        assertEquals(WearStatus.OK, l.status)
        assertEquals("Léa", l.conversations.single().name)
        val t = handle(b, WearRequest("r", WearOp.THREAD, WearConvKind.DIRECT, "k=", limit = 500))
        assertEquals("Léa", t.title)
        assertEquals(WearStatus.NOT_FOUND, handle(b, WearRequest("r", WearOp.THREAD, WearConvKind.DIRECT, "missing")).status)
        assertEquals(WearStatus.OK, handle(b, send).status)
        assertEquals(WearStatus.OK, handle(b, react).status)
        assertEquals(
            listOf(
                "list:${WearProtocol.MAX_CONVERSATIONS}",
                "thread:dm:k=:${WearProtocol.MAX_THREAD_MESSAGES}",
                "thread:dm:missing:${WearProtocol.MAX_THREAD_MESSAGES}",
                "send:group:g-1:on my way",
                "react:dm:k=:m1:⭕",
            ),
            b.calls,
        )
    }

    // ---- __WEAR_POLISH_2026_11_25__ read / preview / voice / caps ----------

    @Test
    fun `list advertises this phone's capabilities and network`() {
        val l = handle(FakeBackend(), list)
        assertEquals(WearProtocol.PHONE_CAPS, l.caps)
        assertEquals(WearNet(internet = false, meshPeers = 2, lora = true), l.net)
        assertEquals(false, l.strict)
        assertEquals(true, handle(FakeBackend().apply { strict = true }, list).strict)
    }

    @Test
    fun `read and thumbnail route to the backend`() {
        val b = FakeBackend()
        assertEquals(WearStatus.OK, handle(b, WearRequest("r", WearOp.READ, WearConvKind.GROUP, "g-1")).status)
        val t = handle(b, WearRequest("r", WearOp.THUMB, WearConvKind.DIRECT, "k=", messageId = "m1"))
        assertEquals(WearStatus.OK, t.status)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(t.thumb))
        b.thumb = null
        assertEquals(WearStatus.NOT_FOUND, handle(b, WearRequest("r", WearOp.THUMB, WearConvKind.DIRECT, "k=", messageId = "m2")).status)
        b.thumb = ByteArray(WearProtocol.MAX_THUMB_BYTES + 1)
        assertEquals(WearStatus.NOT_FOUND, handle(b, WearRequest("r", WearOp.THUMB, WearConvKind.DIRECT, "k=", messageId = "m3")).status)
        assertEquals(listOf("read:group:g-1", "thumb:dm:k=:m1", "thumb:dm:k=:m2", "thumb:dm:k=:m3"), b.calls)
    }

    @Test
    fun `voice goes through the same gates in the same order`() {
        assertEquals(WearStatus.DISABLED, handleVoice(FakeBackend(enabled = false), voiceFrame())!!.status)
        assertEquals(WearStatus.LOCKED, handleVoice(FakeBackend(locked = true), voiceFrame())!!.status)
        assertEquals(WearStatus.UNAVAILABLE, handleVoice(FakeBackend(ready = false), voiceFrame())!!.status)
        val b = FakeBackend()
        assertEquals(WearStatus.OK, handleVoice(b, voiceFrame())!!.status)
        assertEquals(listOf("voice:dm:k=:${m4a.size}:4000"), b.calls)
    }

    @Test
    fun `voice that is too long, not m4a or oversized is refused before the backend`() {
        val b = FakeBackend()
        assertEquals(WearStatus.INVALID, handleVoice(b, voiceFrame(dur = WearProtocol.MAX_VOICE_MS + 1))!!.status)
        assertEquals(WearStatus.INVALID, handleVoice(b, voiceFrame(dur = 10))!!.status)
        assertNull(handleVoice(b, voiceFrame(audio = "RIFF....WAVEfmt ".toByteArray() + ByteArray(100))))
        assertNull(handleVoice(b, voiceFrame(audio = m4a + ByteArray(WearProtocol.MAX_VOICE_BYTES))))
        assertNull(handleVoice(b, byteArrayOf(0, 0, 0)))
        assertTrue(b.calls.isEmpty())
    }

    @Test
    fun `a voice op sent as a plain message is invalid (it needs its audio)`() {
        val b = FakeBackend()
        assertEquals(WearStatus.INVALID, handle(b, WearRequest("r", WearOp.VOICE, WearConvKind.DIRECT, "k=", durationMs = 3_000)).status)
        assertTrue(b.calls.isEmpty())
    }

    @Test
    fun `voice caption keeps the phone's M SS shape both ways`() {
        assertEquals("0:04", com.oshi.messenger.service.wear.WearVoiceDuration.caption(4_200))
        assertEquals("1:00", com.oshi.messenger.service.wear.WearVoiceDuration.caption(60_000))
        assertEquals(64, com.oshi.messenger.service.wear.WearVoiceDuration.fromCaption("[AUDIO] 1:04"))
        assertEquals(0, com.oshi.messenger.service.wear.WearVoiceDuration.fromCaption("hello"))
    }

    @Test
    fun `a watch voice note never goes to a radio-only thread (Meshtastic, LoRa channel, MeshCore)`() {
        val r = com.oshi.messenger.service.wear.WearVoiceRoute
        assertTrue(r.carriesAudio("MCowBQYDK2VuAyEAexampleOshiKey="))
        assertFalse(r.carriesAudio("lora!a1b2c3d4"))
        assertFalse(r.carriesAudio("lorachan!longfast"))
        assertFalse(r.carriesAudio("meshcore!0123456789ab"))
        assertFalse(r.carriesAudio("mcchan!public"))
    }

    @Test
    fun `thumbnail size keeps the aspect and never upscales`() {
        val t = com.oshi.messenger.service.wear.WearThumbnail
        assertEquals(200 to 150, t.targetSize(4000, 3000, 200))
        assertEquals(112 to 200, t.targetSize(2268, 4032, 200))
        assertEquals(120 to 80, t.targetSize(120, 80, 200))
    }

    // ---- the lock rule ------------------------------------------------------

    @Test
    fun `lock policy follows the phone's auto lock`() {
        val now = 1_000_000L
        fun locked(bio: Boolean, t: Int, overlay: Boolean = false, ever: Boolean = true, fg: Boolean = false, bg: Long = now - 10_000) =
            WearLockPolicy.isLocked(bio, t, overlay, ever, fg, bg, now)
        // No app lock, or "auto-lock: never": the watch reads like the phone does.
        assertEquals(false, locked(bio = false, t = 30))
        assertEquals(false, locked(bio = true, t = 0))
        // Lock cover on screen: locked whatever else is true.
        assertEquals(true, locked(bio = true, t = 30, overlay = true, fg = true))
        // In use on the phone right now: open.
        assertEquals(false, locked(bio = true, t = 30, fg = true))
        // Backgrounded 10 s with a 30 s delay: open; with a 5 s delay: locked.
        assertEquals(false, locked(bio = true, t = 30))
        assertEquals(true, locked(bio = true, t = 5))
        // A process the watch woke up, never unlocked by the person: locked.
        assertEquals(true, locked(bio = true, t = 30, ever = false, bg = 0))
    }

    // ---- parity guards ------------------------------------------------------

    /** The watch must send EXACTLY what the phone's long-press quick bar sends. */
    @Test
    fun `watch quick reactions are the phone quick row`() {
        assertEquals(OshiReaction.QUICK_ROW, WearProtocol.QUICK_REACTIONS)
    }

    /** A contact must be the same colour on the wrist as on the phone. */
    @Test
    fun `watch avatar palette is the phone palette`() {
        assertEquals(AvatarIdentity.entries.size, WearAvatar.entries.size)
        AvatarIdentity.entries.forEachIndexed { i, e ->
            val w = WearAvatar.entries[i]
            assertEquals("start $i", e.start, androidx.compose.ui.graphics.Color(w.start))
            assertEquals("end $i", e.end, androidx.compose.ui.graphics.Color(w.end))
            assertEquals("ink $i", e.ink, androidx.compose.ui.graphics.Color(w.ink))
        }
        for (key in listOf("contact-1", "BHq2Y7qkzL0m1yQe0kq9XW3sV1d6c7m3pPq9yZ4b8tE=", "7F3C2A10-5B9E-4C1D-8E2F-A6B7C8D9E0F1", "")) {
            assertEquals(key, AvatarIdentity.slot(key), WearAvatar.slot(key))
        }
        assertEquals(com.oshi.messenger.ui.theme.avatarInitials("Léa Martin", "k"), WearAvatar.initials("Léa Martin", "k"))
    }
}
