package com.oshi.messenger

import com.oshi.messenger.network.v2.V2FileKeyMessage
import com.oshi.messenger.network.v2.V2RevealPlan
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Progressive / timed reveal on the `v2file` wire (M-1, groundwork).
 *
 * The four optional scalars iOS declares between `isViewOnce` and `groupMessage`
 * (V2MessageRouter.swift:80-91), emitted at `:893-898` and validated on receipt by
 * `RevealWirePlan.init?(key:)` (MessageManager+V2.swift:943-961).
 *
 * SCOPE. This is the wire model only. The row-collapsing behaviour — layer 0 creates
 * the bubble, layer k>0 UPDATEs it instead of inserting a new row — lives in
 * `MessageRepository.dispatchV2InboundFile`; the INSERT-vs-UPDATE decision it makes off
 * these fields is covered by `V2InboundFilePayloadTest`. Nothing is on the wire in
 * production today:
 * the iOS SENDER has no call site (see the audit's honest-status note), so this exists
 * so Android is not caught out the day iOS connects it.
 */
class V2FileKeyRevealWireTest {

    private fun base(
        revealId: String? = null,
        revealLayerIndex: Int? = null,
        revealLayerCount: Int? = null,
        revealTotalMs: Int? = null,
    ) = V2FileKeyMessage(
        blobId = "b", fileKey = ByteArray(32), fileNonce = ByteArray(8),
        chunkCount = 1, manifest = ByteArray(16), filename = "f.jpg", mime = "image/jpeg",
        mediaType = "image", isViewOnce = null,
        revealId = revealId, revealLayerIndex = revealLayerIndex,
        revealLayerCount = revealLayerCount, revealTotalMs = revealTotalMs,
        groupMessage = null,
    )

    // ---- Today's traffic must not move one bit --------------------------------

    @Test
    fun `no reveal armed leaves the payload byte-identical`() {
        val encoded = base().toJson()
        val o = JSONObject(encoded)
        assertFalse(o.has("revealId"))
        assertFalse(o.has("revealLayerIndex"))
        assertFalse(o.has("revealLayerCount"))
        assertFalse(o.has("revealTotalMs"))
        // The key SET is exactly what shipped before the four fields existed.
        assertEquals(
            listOf("kind", "blobId", "fileKey", "fileNonce", "chunkCount", "manifest", "filename", "mime", "mediaType").sorted(),
            o.keys().asSequence().toList().sorted(),
        )
        assertNull(base().revealPlan)
    }

    @Test
    fun `a payload written before the reveal fields existed still parses`() {
        // Every message already in flight when the update lands.
        val legacy = """{"kind":"v2file","blobId":"b","fileKey":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=","fileNonce":"qrvM3QECAwQ=","chunkCount":1,"manifest":"ZGVm","filename":"f.jpg","mime":"image/jpeg","mediaType":"image"}"""
        val key = V2FileKeyMessage.parse(legacy)!!
        assertNull("absent must read as 'no reveal'", key.revealId)
        assertNull(key.revealLayerIndex)
        assertNull(key.revealLayerCount)
        assertNull(key.revealTotalMs)
        assertNull(key.revealPlan)
    }

    // ---- An armed reveal crosses intact ---------------------------------------

    @Test
    fun `an armed reveal round-trips and keys sit between isViewOnce and groupMessage`() {
        val msg = V2FileKeyMessage(
            blobId = "blob1", fileKey = ByteArray(32) { it.toByte() },
            fileNonce = byteArrayOf(-86, -69, -52, -35, 1, 2, 3, 4),
            chunkCount = 2, manifest = ByteArray(16) { it.toByte() },
            filename = "photo.jpg", mime = "image/jpeg", mediaType = "image",
            isViewOnce = null,
            revealId = "msg-7", revealLayerIndex = 0, revealLayerCount = 4,
            revealTotalMs = 2 * 24 * 3600 * 1000,
            groupMessage = null,
        )
        val encoded = msg.toJson()
        assertEquals("iOS re-decodes what we emit", msg, V2FileKeyMessage.parse(encoded))

        val o = JSONObject(encoded)
        assertEquals("msg-7", o.getString("revealId"))
        assertEquals(0, o.getInt("revealLayerIndex"))
        assertEquals(4, o.getInt("revealLayerCount"))
        assertEquals(172_800_000, o.getInt("revealTotalMs"))
        // Swift declaration order IS the JSON key order.
        assertTrue(encoded.indexOf("\"mime\"") < encoded.indexOf("\"revealId\""))
        assertTrue(encoded.indexOf("\"revealId\"") < encoded.indexOf("\"revealTotalMs\""))
    }

    @Test
    fun `a literal iOS reveal payload parses into a valid plan`() {
        val ios = """{"kind":"v2file","blobId":"b","fileKey":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=","fileNonce":"qrvM3QECAwQ=","chunkCount":1,"manifest":"ZGVm","filename":"f.jpg","mime":"image/jpeg","mediaType":"image","revealId":"msg-7","revealLayerIndex":2,"revealLayerCount":5,"revealTotalMs":1200000}"""
        val key = V2FileKeyMessage.parse(ios)
        assertNotNull(key)
        val plan = key!!.revealPlan!!
        assertEquals("msg-7", plan.revealId)
        assertEquals(2, plan.layerIndex)
        assertEquals(5, plan.layerCount)
        assertEquals(1_200_000, plan.totalMs)
        assertEquals(1200.0, plan.totalSeconds, 0.0)
        assertEquals(4, plan.finalIndex)
    }

    @Test
    fun `a week in milliseconds fits an Int and survives the round trip`() {
        val week = 7 * 24 * 3600 * 1000
        assertEquals(604_800_000, week)
        val msg = base("x", 3, 4, week)
        assertEquals(week, V2FileKeyMessage.parse(msg.toJson())!!.revealTotalMs)
        assertEquals(week, msg.revealPlan!!.totalMs)
    }

    // ---- Validation mirrors RevealWirePlan exactly -----------------------------

    @Test
    fun `a half-present plan is rejected rather than half-applied`() {
        // iOS: a layerCount of 1 gives finalIndex 0 → division by zero → NaN countdown.
        assertNull("no id", base(null, 0, 4, 1000).revealPlan)
        assertNull("empty id", base("", 0, 4, 1000).revealPlan)
        assertNull("no index", base("x", null, 4, 1000).revealPlan)
        assertNull("no count", base("x", 0, null, 1000).revealPlan)
        assertNull("no duration", base("x", 0, 4, null).revealPlan)
    }

    @Test
    fun `out-of-range scalars are rejected on the same bounds as iOS`() {
        assertNull("index < 0", base("x", -1, 4, 1000).revealPlan)
        assertNull("index >= count", base("x", 4, 4, 1000).revealPlan)
        assertNull("count < 2", base("x", 0, 1, 1000).revealPlan)
        assertNull("count > 64", base("x", 0, 65, 1000).revealPlan)
        assertNull("ms <= 0", base("x", 0, 4, 0).revealPlan)
        assertNull("ms > 7 d", base("x", 0, 4, V2RevealPlan.MAX_TOTAL_MS + 1).revealPlan)
        assertNotNull("exactly 7 d is allowed", base("x", 0, 4, V2RevealPlan.MAX_TOTAL_MS).revealPlan)
        assertNotNull("64 layers is allowed", base("x", 63, 64, 1000).revealPlan)
    }

    @Test
    fun `a garbage reveal scalar degrades to no-reveal instead of sinking the envelope`() {
        // An unknown KEY is ignored by both decoders; a bad VALUE must not throw either,
        // or one malformed reveal would take the whole photo with it.
        val json = """{"kind":"v2file","blobId":"b","fileKey":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=","fileNonce":"qrvM3QECAwQ=","chunkCount":1,"manifest":"ZGVm","filename":"f.jpg","mime":"image/jpeg","revealId":"x","revealLayerIndex":"not-a-number","revealLayerCount":4,"revealTotalMs":1000}"""
        val key = V2FileKeyMessage.parse(json)
        assertNotNull("the file itself must still decode", key)
        assertEquals("x", key!!.revealId)
        assertNull("an unreadable scalar reads as absent", key.revealLayerIndex)
        assertNull("…so no plan is armed", key.revealPlan)
    }

    // ---- Layer ids -------------------------------------------------------------

    @Test
    fun `layer 0 travels under the reveal id and later layers under revealId hash k`() {
        // TimedReveal.swift:238 — the '#k' suffix is what stops an id-dedup queue from
        // dropping every layer after the first.
        assertEquals("msg-7", V2RevealPlan.layerMsgId("msg-7", 0))
        assertEquals("msg-7#1", V2RevealPlan.layerMsgId("msg-7", 1))
        assertEquals("msg-7#4", V2RevealPlan.layerMsgId("msg-7", 4))
    }
}
