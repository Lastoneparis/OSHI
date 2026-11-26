package com.oshi.messenger

import com.oshi.messenger.data.model.DeliveryMethod
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.service.LoRaDeliveryLedger
import com.oshi.messenger.service.MessageSendPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions the LoRa lane makes, tested without a radio, a database or a
 * network — which is the only reason they live in `MessageSendPolicy` at all.
 *
 * Each group below pins one thing that was WRONG before this wiring landed:
 * the radio was never offered a message, the byte budget did not exist, a
 * queued message would have been shown as sent, and a mesh ACK changed nothing.
 */
class LoRaIntegrationPolicyTest {

    // =====================================================================
    // 1. The two trigger conditions — MessageManager.swift:4140-4180
    // =====================================================================

    private val now = 1_770_000_000_000L

    /**
     * (a) THIS PHONE is off-grid. iOS `MessageManager.swift:4140-4157`: skip the
     * internet timeouts entirely rather than spending tens of seconds on
     * transports that cannot possibly succeed.
     *
     * Deliberately NOT gated on the radio being ready — a paired node out of
     * Bluetooth range still gets the message queued, which is a free second
     * chance the online chain would never have given it.
     */
    @Test
    fun `off-grid takes the LoRa lane even with no ready radio`() {
        assertEquals(
            MessageSendPolicy.Lane.OFF_GRID,
            MessageSendPolicy.lane(
                hasPhysicalPath = false,
                radioReady = false,
                peerLastSeenMs = now,          // peer is wide awake; irrelevant
                nowMs = now,
            )
        )
    }

    /**
     * (b) We are online but the PEER looks unreachable. iOS
     * `MessageManager.swift:4159-4180`. Gated on a READY radio: without one this
     * branch does not exist and the transport chain is exactly what it was.
     */
    @Test
    fun `peer dark plus ready radio takes the LoRa lane`() {
        val stale = now - MessageSendPolicy.PEER_ONLINE_WINDOW_MS - 1
        assertEquals(
            MessageSendPolicy.Lane.PEER_UNREACHABLE,
            MessageSendPolicy.lane(
                hasPhysicalPath = true,
                radioReady = true,
                peerLastSeenMs = stale,
                nowMs = now,
            )
        )
    }

    @Test
    fun `peer dark but no ready radio changes nothing`() {
        val stale = now - MessageSendPolicy.PEER_ONLINE_WINDOW_MS - 1
        assertEquals(
            MessageSendPolicy.Lane.NONE,
            MessageSendPolicy.lane(
                hasPhysicalPath = true,
                radioReady = false,
                peerLastSeenMs = stale,
                nowMs = now,
            )
        )
    }

    /**
     * A LoRa frame costs ~2 seconds. Displacing a working network path for a peer
     * we heard from a minute ago would make every conversation slower for
     * everyone who owns a node.
     */
    @Test
    fun `online peer never displaces the network path`() {
        assertEquals(
            MessageSendPolicy.Lane.NONE,
            MessageSendPolicy.lane(
                hasPhysicalPath = true,
                radioReady = true,
                peerLastSeenMs = now - 60_000,
                nowMs = now,
            )
        )
    }

    /**
     * UNKNOWN IS NOT OFFLINE. iOS `MessageManager.swift:4085-4090`: a contact we
     * have never exchanged with has no last-seen, and treating that as "dark"
     * would push every FIRST message onto the radio instead of the internet.
     */
    @Test
    fun `contact with no last-seen counts as online`() {
        assertTrue(MessageSendPolicy.isPeerLikelyOnline(null, now))
        assertTrue(MessageSendPolicy.isPeerLikelyOnline(0L, now))
        assertEquals(
            MessageSendPolicy.Lane.NONE,
            MessageSendPolicy.lane(
                hasPhysicalPath = true,
                radioReady = true,
                peerLastSeenMs = null,
                nowMs = now,
            )
        )
    }

    /** The window itself: 5 minutes, matching `peerOnlineWindow` (`:4104`). */
    @Test
    fun `peer online window boundary is five minutes`() {
        assertEquals(300_000L, MessageSendPolicy.PEER_ONLINE_WINDOW_MS)
        val edge = now - MessageSendPolicy.PEER_ONLINE_WINDOW_MS
        assertFalse("exactly at the window is already stale", MessageSendPolicy.isPeerLikelyOnline(edge, now))
        assertTrue(MessageSendPolicy.isPeerLikelyOnline(edge + 1, now))
    }

    /** Off-grid outranks everything: iOS checks it first, before the relay. */
    @Test
    fun `off-grid wins over peer-unreachable`() {
        assertEquals(
            MessageSendPolicy.Lane.OFF_GRID,
            MessageSendPolicy.lane(
                hasPhysicalPath = false,
                radioReady = true,
                peerLastSeenMs = now - 10 * 60 * 1000,
                nowMs = now,
            )
        )
    }

    // =====================================================================
    // 2. The byte budget — MessageSendPolicy.swift:56-64
    // =====================================================================

    /**
     * The numbers are READ from iOS, not invented here. 500 is the measured
     * ceiling that survives 12 × 180 wire bytes once the envelope, the double
     * AES-GCM wrap and the base64 have taken their ~1.83× cut.
     */
    @Test
    fun `budget constants match iOS`() {
        assertEquals(500, MessageSendPolicy.LORA_SAFE_UTF8_BYTES)
        assertEquals(16_000, MessageSendPolicy.STANDARD_UTF8_BYTES)
        assertEquals(500, MessageSendPolicy.maxUTF8Bytes(loRaPaired = true))
        assertEquals(16_000, MessageSendPolicy.maxUTF8Bytes(loRaPaired = false))
    }

    /** Just under / just over, with a radio paired. */
    @Test
    fun `lora budget boundary is exact`() {
        val atLimit = "a".repeat(500)
        val overLimit = "a".repeat(501)
        assertEquals(
            MessageSendPolicy.Outcome.WITHIN_BUDGET,
            MessageSendPolicy.check(atLimit, loRaPaired = true)
        )
        assertEquals(
            MessageSendPolicy.Outcome.TOO_LONG,
            MessageSendPolicy.check(overLimit, loRaPaired = true)
        )
    }

    /** Just under / just over, with no radio. */
    @Test
    fun `standard budget boundary is exact`() {
        assertEquals(
            MessageSendPolicy.Outcome.WITHIN_BUDGET,
            MessageSendPolicy.check("a".repeat(16_000), loRaPaired = false)
        )
        assertEquals(
            MessageSendPolicy.Outcome.TOO_LONG,
            MessageSendPolicy.check("a".repeat(16_001), loRaPaired = false)
        )
        // …and the same 501-byte text that a paired radio refuses sails through
        // when there is no radio. The budget follows the reachable transport.
        assertEquals(
            MessageSendPolicy.Outcome.WITHIN_BUDGET,
            MessageSendPolicy.check("a".repeat(501), loRaPaired = false)
        )
    }

    /**
     * BYTES, not characters. 200 emoji are 200 characters and 800 UTF-8 bytes —
     * a character-counted budget would hand the radio a message it must refuse.
     */
    @Test
    fun `budget counts UTF-8 bytes not characters`() {
        val emoji = "😀".repeat(200)          // 200 code points, 800 bytes
        assertEquals(800, MessageSendPolicy.utf8Size(emoji))
        assertEquals(
            MessageSendPolicy.Outcome.TOO_LONG,
            MessageSendPolicy.check(emoji, loRaPaired = true)
        )
    }

    /**
     * Control payloads ride the same `sendTextMessage` as user text. Refusing a
     * long live-location update or a group envelope for LENGTH protects nobody
     * and breaks delivery state on a busy conversation — iOS applies both send
     * rules only to `isUserProse` (`MessageSendPolicy.swift:110-118`).
     */
    @Test
    fun `control payloads are never refused for length`() {
        val hugeReceipt = OSHIControlPayload.DELIVERY_RECEIPT + "x".repeat(5000)
        assertFalse(MessageSendPolicy.isUserProse(hugeReceipt))
        assertEquals(
            MessageSendPolicy.Outcome.WITHIN_BUDGET,
            MessageSendPolicy.check(hugeReceipt, loRaPaired = true)
        )

        val hugeLocation = OSHIControlPayload.LOCATION + "y".repeat(5000)
        assertEquals(
            MessageSendPolicy.Outcome.WITHIN_BUDGET,
            MessageSendPolicy.check(hugeLocation, loRaPaired = true)
        )

        // Group media rides as a raw JSON object in `content`.
        val mediaJson = """{"type":"IMAGE","data":"${"A".repeat(5000)}"}"""
        assertFalse(MessageSendPolicy.isUserProse(mediaJson))
        assertEquals(
            MessageSendPolicy.Outcome.WITHIN_BUDGET,
            MessageSendPolicy.check(mediaJson, loRaPaired = true)
        )
    }

    /**
     * Reply and forward wrappers ARE user prose — they are envelopes around
     * something a person typed, and iOS splits inside them rather than exempting
     * them. A 4 KB reply is still 4 KB on the wire.
     */
    @Test
    fun `reply and forward envelopes stay subject to the budget`() {
        val longReply = OSHIControlPayload.REPLY + "z".repeat(900)
        assertTrue(MessageSendPolicy.isUserProse(longReply))
        assertEquals(
            MessageSendPolicy.Outcome.TOO_LONG,
            MessageSendPolicy.check(longReply, loRaPaired = true)
        )
    }

    /** A sentinel that merely APPEARS mid-sentence is prose, not a control payload. */
    @Test
    fun `a mentioned sentinel does not exempt ordinary prose`() {
        val prose = "we should add a ${OSHIControlPayload.LOCATION} marker " + "w".repeat(600)
        assertTrue(MessageSendPolicy.isUserProse(prose))
        assertEquals(
            MessageSendPolicy.Outcome.TOO_LONG,
            MessageSendPolicy.check(prose, loRaPaired = true)
        )
    }

    // =====================================================================
    // 3. Queued ≠ sent — MeshtasticManager.kt:755-782
    // =====================================================================

    private fun outgoingRow(
        isDelivered: Boolean = false,
        isFailed: Boolean = false,
        method: DeliveryMethod = DeliveryMethod.VPS,
    ) = Message(
        id = "m-1",
        senderPublicKey = "me",
        recipientPublicKey = "them",
        content = "hello",
        timestamp = now,
        isFromMe = true,
        isDelivered = isDelivered,
        isFailed = isFailed,
        deliveryMethod = method,
    )

    /**
     * THE ONE THAT MATTERS. `queueOrSendSecureMessage` returns false for a
     * message it could only QUEUE — no radio was connected, so no frame went
     * out. Writing anything onto the row at that point turns a message that may
     * never leave the device into a ✓ on screen, and takes it off the retry path
     * that would otherwise deliver it.
     */
    @Test
    fun `a queued message is not marked sent`() {
        val row = outgoingRow()
        assertNull(
            "a queued (not transmitted) message must leave the row untouched",
            LoRaDeliveryLedger.afterSendAttempt(row, framesWritten = false)
        )
    }

    /** A message that already failed stays failed while it is only queued. */
    @Test
    fun `a queued message keeps its failed state for the retry path`() {
        val failed = outgoingRow(isFailed = true)
        assertNull(LoRaDeliveryLedger.afterSendAttempt(failed, framesWritten = false))
    }

    /** Frames actually on the air: now, and only now, the row is stamped. */
    @Test
    fun `frames on the air mark the row sent over LoRa`() {
        val updated = LoRaDeliveryLedger.afterSendAttempt(outgoingRow(), framesWritten = true)
        assertNotNull(updated)
        assertEquals(DeliveryMethod.LORA, updated!!.deliveryMethod)
        assertTrue(updated.isDelivered)
        assertFalse(updated.isFailed)
    }

    // =====================================================================
    // 4. ACK → delivered — MeshtasticManager.kt:1052-1062
    // =====================================================================

    /**
     * The transition the ACK exists for: the radio could only queue the message,
     * so it was marked failed; the store-and-forward flush then delivered it and
     * the mesh confirmed. Without this the bubble stays red for a message that
     * arrived.
     */
    @Test
    fun `mesh ack promotes a failed message to delivered`() {
        val promoted = LoRaDeliveryLedger.afterMeshAck(outgoingRow(isFailed = true))
        assertNotNull(promoted)
        assertTrue(promoted!!.isDelivered)
        assertFalse("the red retry affordance must clear", promoted.isFailed)
        assertEquals(DeliveryMethod.LORA, promoted.deliveryMethod)
    }

    /** An ack for a row that was already delivered over another transport still
     *  corrects the transport label — the mesh is what actually carried it. */
    @Test
    fun `mesh ack corrects the transport of an already-delivered row`() {
        val promoted = LoRaDeliveryLedger.afterMeshAck(
            outgoingRow(isDelivered = true, method = DeliveryMethod.VPS)
        )
        assertNotNull(promoted)
        assertEquals(DeliveryMethod.LORA, promoted!!.deliveryMethod)
    }

    /**
     * `loraAcked` is a StateFlow of a growing SET — every emission re-delivers
     * every id ever acked. A row that already says what the ack says must produce
     * no write, or a single ack rewrites the whole conversation on each emission.
     */
    @Test
    fun `an ack for an already-correct row writes nothing`() {
        val settled = outgoingRow(isDelivered = true, method = DeliveryMethod.LORA)
        assertNull(LoRaDeliveryLedger.afterMeshAck(settled))
    }

    // ── __LORA_SEND_FAILED_VISIBLE_2026_11_23__ a LoRa "not delivered" verdict ──

    @Test
    fun `a LoRa failure fails a row the radio carried`() {
        // What afterSendAttempt leaves after hand-off: delivered, via LoRa.
        val handedOff = LoRaDeliveryLedger.afterSendAttempt(outgoingRow(), framesWritten = true)
        assertTrue(LoRaDeliveryLedger.mayMarkNotDelivered(handedOff, confirmedElsewhere = false))
    }

    @Test
    fun `a LoRa failure fails a row that never left`() {
        assertTrue(LoRaDeliveryLedger.mayMarkNotDelivered(outgoingRow(), confirmedElsewhere = false))
    }

    @Test
    fun `a LoRa failure never downgrades a row the relay or the mesh carried`() {
        for (m in listOf(DeliveryMethod.VPS, DeliveryMethod.MESH, DeliveryMethod.IPFS)) {
            assertFalse(m.name, LoRaDeliveryLedger.mayMarkNotDelivered(
                outgoingRow(isDelivered = true, method = m), confirmedElsewhere = false))
        }
    }

    @Test
    fun `a LoRa failure never downgrades a confirmed, read or already failed row`() {
        val lora = outgoingRow(isDelivered = true, method = DeliveryMethod.LORA)
        assertFalse(LoRaDeliveryLedger.mayMarkNotDelivered(lora, confirmedElsewhere = true))
        assertFalse(LoRaDeliveryLedger.mayMarkNotDelivered(lora.copy(isRead = true), confirmedElsewhere = false))
        assertFalse(LoRaDeliveryLedger.mayMarkNotDelivered(outgoingRow(isFailed = true), confirmedElsewhere = false))
        assertFalse(LoRaDeliveryLedger.mayMarkNotDelivered(null, confirmedElsewhere = false))
        assertFalse(LoRaDeliveryLedger.mayMarkNotDelivered(lora.copy(isFromMe = false), confirmedElsewhere = false))
    }

    /** Failed by a timeout, then the radio's DELIVERED arrives late: the ack wins. */
    @Test
    fun `a late mesh ack upgrades a row a LoRa failure marked failed`() {
        val failed = outgoingRow(isDelivered = false, isFailed = true, method = DeliveryMethod.LORA)
        val promoted = LoRaDeliveryLedger.afterMeshAck(failed)
        assertNotNull(promoted)
        assertTrue(promoted!!.isDelivered)
        assertFalse(promoted.isFailed)
    }
}
