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
import com.oshi.wearbridge.WearOp
import com.oshi.wearbridge.WearProtocol
import com.oshi.wearbridge.WearRequest
import com.oshi.wearbridge.WearResponse
import com.oshi.wearbridge.WearStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
    }

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
