package com.oshi.messenger.service.lora

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON inside the "OM" envelope, checked against what iOS's `JSONEncoder`
 * and `SecureMessage.init(from:)` actually do.
 *
 * The two failures this file exists to prevent are both SILENT:
 *  • a date written as Unix millis instead of Apple reference seconds decodes on
 *    iOS as a timestamp about 55 years in the future;
 *  • `plaintextContent` left in the encode puts the user's message text on the
 *    air in the clear, beside the ciphertext, on a channel whose key is public.
 */
class LoRaWireMessageTest {

    private val nowMillis = 1_717_200_000_000L   // 2024-06-01T00:00:00Z

    private fun sample(
        plaintext: String? = "the quiet part",
        media: ByteArray? = null,
    ) = LoRaWireMessage(
        id = "11111111-2222-3333-4444-555555555555",
        senderAddress = "alice",
        recipientAddress = "bob",
        encryptedContent = LoRaEncryptedContent(
            ciphertext = "Y2lwaGVydGV4dA==",
            signature = "c2ln",
            senderPublicKey = "AAAA1111BBBB2222CCCC3333DDDD4444EEEE5555FFF=",
            timestamp = nowMillis,
        ),
        timestamp = nowMillis,
        isRead = false,
        deliveryStatus = LoRaWireMessage.STATUS_SENT,
        senderPublicKey = "AAAA1111BBBB2222CCCC3333DDDD4444EEEE5555FFF=",
        recipientPublicKey = "ZZZZ9999YYYY8888XXXX7777WWWW6666VVVV5555UUU=",
        plaintextContent = plaintext,
        mediaAttachment = media,
        deliveryMethod = LoRaWireMessage.METHOD_LORA,
        editedContent = "an edit",
    )

    // ───────────────────────── dates ─────────────────────────

    @Test
    fun `dates are Apple reference seconds, not Unix millis`() {
        // Swift's default JSONEncoder writes a Date as seconds since 2001-01-01.
        val json = JSONObject(String(sample().toWireJson()))
        assertEquals(nowMillis / 1000.0 - 978_307_200.0, json.getDouble("timestamp"), 1e-6)
        assertEquals(
            nowMillis / 1000.0 - 978_307_200.0,
            json.getJSONObject("encryptedContent").getDouble("timestamp"),
            1e-6
        )
    }

    @Test
    fun `date conversion round-trips to the millisecond`() {
        assertEquals(nowMillis, LoRaWireMessage.unixMillis(LoRaWireMessage.appleSeconds(nowMillis)))
    }

    @Test
    fun `an Apple-epoch value is 30 years smaller than the Unix one`() {
        // Guards against the offset being dropped or added twice.
        assertTrue(LoRaWireMessage.appleSeconds(nowMillis) < nowMillis / 1000.0)
        assertEquals(978_307_200.0, nowMillis / 1000.0 - LoRaWireMessage.appleSeconds(nowMillis), 1e-6)
    }

    // ───────────────────────── the plaintext strip ─────────────────────────

    @Test
    fun `meshWireSafe strips the local-only sidecar`() {
        // MeshNetworkManager.swift:2005-2019
        val safe = sample().meshWireSafe()
        assertNull(safe.plaintextContent)
        assertNull(safe.mediaAttachment)
        assertNull(safe.originalMediaData)
        assertNull(safe.editedContent)
        assertNull(safe.deliveryMethod)
        // …and keeps everything delivery needs.
        assertEquals("alice", safe.senderAddress)
        assertEquals("Y2lwaGVydGV4dA==", safe.encryptedContent.ciphertext)
    }

    @Test
    fun `the wire bytes NEVER contain the plaintext`() {
        // MeshtasticManager.swift:486-491 — the bug this replaces put the user's
        // text on the LongFast channel, whose key is public.
        val wire = String(sample(plaintext = "MEET ME AT THE BRIDGE").toWireJson())
        assertFalse(wire.contains("MEET ME AT THE BRIDGE"))
        assertFalse(wire.contains("plaintextContent"))
        assertFalse(wire.contains("an edit"))
        assertFalse(wire.contains("mediaAttachment"))
    }

    @Test
    fun `deliveryMethod never reaches the wire`() {
        // It is the one field carrying an enum raw value across; a case an older
        // peer has not heard of makes its decoder THROW and drops the whole
        // message. MessageManager.swift:7973-7979.
        assertFalse(String(sample().toWireJson()).contains("deliveryMethod"))
    }

    @Test
    fun `carriesMedia is what the media refusal keys on`() {
        assertFalse(sample().carriesMedia)
        assertTrue(sample(media = byteArrayOf(1, 2, 3)).carriesMedia)
    }

    // ───────────────────────── iOS decoder requirements ─────────────────────────

    @Test
    fun `every key iOS decodes with plain decode is always emitted`() {
        // MessageManager.swift:8022-8028 — these seven are `decode`, not
        // `decodeIfPresent`. A message missing one is dropped WHOLE on iOS.
        val json = JSONObject(String(sample().toWireJson()))
        listOf(
            "id", "senderAddress", "recipientAddress", "encryptedContent",
            "timestamp", "isRead", "deliveryStatus",
        ).forEach { assertTrue("missing $it", json.has(it)) }
        // encryptedContent's own required members. IdentityManager.swift:787-792.
        val enc = json.getJSONObject("encryptedContent")
        listOf("ciphertext", "signature", "senderPublicKey", "timestamp")
            .forEach { assertTrue("missing encryptedContent.$it", enc.has(it)) }
    }

    @Test
    fun `non-optional booleans are present even when false`() {
        val json = JSONObject(String(sample().toWireJson()))
        assertTrue(json.has("isViewOnce"))
        assertTrue(json.has("hasBeenViewed"))
        assertTrue(json.has("isDeletedForEveryone"))
        assertFalse(json.getBoolean("isViewOnce"))
    }

    @Test
    fun `a Swift nil is an ABSENT KEY, never a JSON null`() {
        // Both decode, but a null costs bytes out of a 2160-byte budget.
        val json = JSONObject(String(sample().toWireJson()))
        assertFalse(json.has("replyToId"))
        assertFalse(json.has("editedAt"))
        assertFalse(json.has("mediaType"))
        assertFalse(json.has("mediaFileName"))
    }

    // ───────────────────────── round trip ─────────────────────────

    @Test
    fun `round-trips through the wire form`() {
        val decoded = LoRaWireMessage.fromWireJson(sample().toWireJson())!!
        assertEquals("11111111-2222-3333-4444-555555555555", decoded.id)
        assertEquals("alice", decoded.senderAddress)
        assertEquals("bob", decoded.recipientAddress)
        assertEquals(nowMillis, decoded.timestamp)
        assertEquals(nowMillis, decoded.encryptedContent.timestamp)
        assertEquals("Y2lwaGVydGV4dA==", decoded.encryptedContent.ciphertext)
        assertEquals(LoRaWireMessage.STATUS_SENT, decoded.deliveryStatus)
        // Stripped on the way out, so absent on the way back in — by design.
        assertNull(decoded.plaintextContent)
        assertNull(decoded.deliveryMethod)
    }

    @Test
    fun `optional fields survive when they are set`() {
        val m = sample().copy(
            replyToId = "abc",
            editedAt = nowMillis,
            isViewOnce = true,
            mediaFileName = "report.pdf",
        )
        val decoded = LoRaWireMessage.fromWireJson(m.toWireJson())!!
        assertEquals("abc", decoded.replyToId)
        assertEquals(nowMillis, decoded.editedAt)
        assertTrue(decoded.isViewOnce)
        assertEquals("report.pdf", decoded.mediaFileName)
    }

    @Test
    fun `a message missing a required key is refused, not half-built`() {
        val json = JSONObject(String(sample().toWireJson()))
        json.remove("deliveryStatus")
        assertNull(LoRaWireMessage.fromWireJson(json.toString().toByteArray()))
    }

    @Test
    fun `garbage decodes to null instead of throwing`() {
        // :1080-1083 — a frame that does not parse is a frame, not a crash.
        assertNull(LoRaWireMessage.fromWireJson("not json".toByteArray()))
        assertNull(LoRaWireMessage.fromWireJson(ByteArray(0)))
        assertNull(LoRaWireMessage.fromWireJson(byteArrayOf(0x00, 0x01, 0x02)))
    }

    @Test
    fun `an unknown deliveryMethod on the wire does not drop the message`() {
        // The whole reason the field is carried as a raw String on this side.
        val json = JSONObject(String(sample().toWireJson()))
        json.put("deliveryMethod", "SomethingFromTheFuture")
        val decoded = LoRaWireMessage.fromWireJson(json.toString().toByteArray())
        assertNotNull(decoded)
        assertEquals("SomethingFromTheFuture", decoded!!.deliveryMethod)
    }

    // ───────────────────────── budget ─────────────────────────

    @Test
    fun `an empty stripped envelope fits well inside the twelve-frame cap`() {
        // MessageSendPolicy.swift:24-36 measures a stripped SecureMessage at
        // ~1114 bytes empty against a 2160-byte ceiling. If this ever exceeds the
        // cap, no LoRa message can be sent at all.
        val wire = sample().toWireJson()
        assertTrue("stripped envelope is ${wire.size} bytes", wire.size < 2160)
        assertNotNull(LoRaEnvelope.frames(1u, wire))
    }

    @Test
    fun `a whole envelope survives chunking and reassembly`() {
        // End to end over the exact framing the radio sees.
        val m = sample().copy(
            encryptedContent = sample().encryptedContent.copy(ciphertext = "A".repeat(1200))
        )
        val wire = m.toWireJson()
        val frames = LoRaEnvelope.frames(0xABCDEF01u, wire)!!
        assertTrue(frames.size > 1)
        val r = LoRaReassembler()
        var out: ByteArray? = null
        frames.forEachIndexed { i, f -> out = r.accept(f, i.toLong()) ?: out }
        assertArrayEquals(wire, out)
        assertEquals(m.id, LoRaWireMessage.fromWireJson(out!!)!!.id)
    }
}
