package com.oshi.messenger

import com.oshi.messenger.data.model.ContactCardWire
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.repository.MessageRepository
import com.oshi.messenger.network.v2.V2FileKeyMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android side of three cross-platform fixes (2026-09-27):
 *  - D2: local caption markers never reach a peer;
 *  - D1: a photo/video caption reaches a shipped iPhone (follow-up text) and an Android
 *    receiver (file-key `caption`) exactly once;
 *  - D3: the mesh leg of a dual-send carries the relay copy's id.
 */
class MediaCaptionAndMeshIdWireTest {

    // ---- D2 -------------------------------------------------------------------------

    @Test
    fun `local markers are not captions`() {
        assertNull(MessageRepository.wireCaption(MediaType.AUDIO, "[AUDIO] 0:05"))
        assertNull(MessageRepository.wireCaption(MediaType.DOCUMENT, "[DOCUMENT] Contrat.pdf"))
        assertNull(MessageRepository.wireCaption(MediaType.CONTACT, "[CONTACT] Alice"))
        assertNull(MessageRepository.wireCaption(MediaType.IMAGE, "[IMAGE]"))
        assertNull(MessageRepository.wireCaption(MediaType.VIDEO, "  "))
        assertNull(MessageRepository.wireCaption(MediaType.IMAGE, null))
    }

    @Test
    fun `a typed caption is kept verbatim`() {
        assertEquals("Look at this 🌅", MessageRepository.wireCaption(MediaType.IMAGE, "Look at this 🌅"))
        assertEquals("[not a marker] ok", MessageRepository.wireCaption(MediaType.VIDEO, "[not a marker] ok"))
    }

    @Test
    fun `a document's text is its name, never a caption`() {
        assertNull(MessageRepository.wireCaption(MediaType.DOCUMENT, "Contrat.pdf"))
        assertEquals("Contrat.pdf", MessageRepository.documentNameFromCaption("[DOCUMENT] Contrat.pdf"))
        assertEquals("Contrat.pdf", MessageRepository.documentNameFromCaption("Contrat.pdf"))
        assertNull(MessageRepository.documentNameFromCaption("[DOCUMENT]"))
        assertNull(MessageRepository.documentNameFromCaption(null))
    }

    @Test
    fun `the legacy envelope without a marker carries an empty caption and the file name`() {
        val env = JSONObject(
            MessageRepository.legacyMediaEnvelope(
                "DOCUMENT",
                MessageRepository.wireCaption(MediaType.DOCUMENT, "[DOCUMENT] a.pdf"),
                "AAAA", false,
                fileName = MessageRepository.documentNameFromCaption("[DOCUMENT] a.pdf"),
            )
        )
        assertEquals("", env.getString("caption"))
        assertEquals("a.pdf", env.getString("fileName"))
    }

    @Test
    fun `an inbound card without a caption still names the person`() {
        val card = ContactCardWire.encode("K", "Alice", 1_790_000_000_000L).toByteArray()
        assertEquals(
            "[CONTACT] Alice",
            MessageRepository.inboundMediaDisplayText(MediaType.CONTACT, "", null, contactBytes = card)
        )
        // __A_PHOTO_WITH_NO_CAPTION_WAS_A_BLANK_ROW_2026_11_04__ This line used to
        // assert "", which made the defect a tested requirement: a captionless
        // media message stored an empty body, and the conversation row rendered a
        // sender, a time and a blank space. The marker is what this app's own
        // SENDER has always stored locally, the bubble suppresses anything opening
        // with "[", and the list turns it into an icon and a word.
        assertEquals("[AUDIO]", MessageRepository.inboundMediaDisplayText(MediaType.AUDIO, "", null))
    }

    // ---- D1 -------------------------------------------------------------------------

    @Test
    fun `the follow-up id is derived from the media id alone`() {
        val a = MessageRepository.captionFollowUpId("0f3c1b2a-1111-4222-8333-444455556666")
        assertEquals(a, MessageRepository.captionFollowUpId("0F3C1B2A-1111-4222-8333-444455556666"))
        assertNotEquals(a, MessageRepository.captionFollowUpId("0f3c1b2a-1111-4222-8333-444455556667"))
        assertEquals(36, a.length)
    }

    @Test
    fun `only a typed photo or video caption gets a follow-up`() {
        assertTrue(MessageRepository.needsCaptionFollowUp(MediaType.IMAGE, "hi"))
        assertTrue(MessageRepository.needsCaptionFollowUp(MediaType.VIDEO, "hi"))
        assertFalse(MessageRepository.needsCaptionFollowUp(MediaType.IMAGE, null))
        assertFalse(MessageRepository.needsCaptionFollowUp(MediaType.AUDIO, "hi"))
        assertFalse(MessageRepository.needsCaptionFollowUp(MediaType.DOCUMENT, "hi"))
    }

    private fun key(caption: String?) = V2FileKeyMessage(
        blobId = "b", fileKey = ByteArray(32), fileNonce = ByteArray(8), chunkCount = 1,
        manifest = ByteArray(16), filename = "p.jpg", mime = "image/jpeg", mediaType = "image",
        caption = caption,
    )

    @Test
    fun `the file key carries the caption as a trailing optional key`() {
        val json = key("Sunset \"here\"\n").toJson()
        assertEquals("Sunset \"here\"\n", JSONObject(json).getString("caption"))
        assertEquals("Sunset \"here\"\n", V2FileKeyMessage.parse(json)?.caption)
    }

    @Test
    fun `no caption keeps the payload byte-identical`() {
        val json = key(null).toJson()
        assertFalse(json.contains("caption"))
        assertNull(V2FileKeyMessage.parse(json)?.caption)
    }

    // ---- D3 -------------------------------------------------------------------------

    private val encrypted =
        """{"ciphertext":"Y2lwaGVy","signature":"c2ln","senderPublicKey":"ME","timestamp":812000000.5}"""

    @Test
    fun `the mesh leg is a SecureMessage an iPhone decodes under the relay id`() {
        val wrapped = MessageRepository.crossPlatformMeshSecureMessage(
            encrypted, "msg-1", "ME", "YOU", 1_790_000_000_000L
        )!!
        val o = JSONObject(wrapped)
        // Every key iOS `SecureMessage.init(from:)` requires, with the JSON types it expects.
        assertEquals("msg-1", o.getString("id"))
        assertEquals("ME", o.getString("senderAddress"))
        assertEquals("YOU", o.getString("recipientAddress"))
        assertEquals("c2ln", o.getJSONObject("encryptedContent").getString("signature"))
        assertEquals(1_790_000_000.0 - 978_307_200.0, o.getDouble("timestamp"), 0.001)
        assertFalse(o.getBoolean("isRead"))
        assertEquals("delivered", o.getString("deliveryStatus"))
        assertEquals("ME", o.getString("senderPublicKey"))
    }

    @Test
    fun `a receiver dedups on the wrapped id, else on the frame id`() {
        val wrapped = MessageRepository.crossPlatformMeshSecureMessage(encrypted, "msg-1", "ME", "YOU", 0L)!!
        assertEquals("msg-1", MessageRepository.meshEnvelopeMessageId(wrapped, "frame"))
        assertEquals("frame", MessageRepository.meshEnvelopeMessageId(encrypted, "frame"))
        assertEquals("frame", MessageRepository.meshEnvelopeMessageId("QmFzZTY0", "frame"))
        assertNull(MessageRepository.crossPlatformMeshSecureMessage("not json", "m", "a", "b", 0L))
    }
    // ---- Legacy wrapper and mesh control payloads (2026-09-27 second pass) -----------

    @Test
    fun `a contact card or GIF on the legacy leg uses iOS's own wrapper`() {
        assertEquals("contact", MessageRepository.legacyWireMediaType(MediaType.CONTACT, false))
        assertEquals("gif", MessageRepository.legacyWireMediaType(MediaType.IMAGE, true))
        assertNull(MessageRepository.legacyWireMediaType(MediaType.IMAGE, false))
        assertNull(MessageRepository.legacyWireMediaType(MediaType.AUDIO, false))
        val env = JSONObject(
            MessageRepository.legacyMediaEnvelope(
                "CONTACT", null, "QUJD", false, fileName = "c.vcf", iosMediaType = "contact"
            )
        )
        assertEquals("media", env.getString("type"))
        assertEquals("contact", env.getString("mediaType"))
        assertEquals("QUJD", env.getString("data"))
        assertEquals("c.vcf", env.getString("fileName"))
    }

    @Test
    fun `a photo's legacy envelope is unchanged`() {
        assertEquals(
            """{"type":"IMAGE","caption":"","data":"QUJD","isViewOnce":false}""",
            MessageRepository.legacyMediaEnvelope("IMAGE", null, "QUJD", false)
        )
    }

    @Test
    fun `raw sealed bytes never ride the cross-platform mesh`() {
        assertEquals(encrypted, MessageRepository.crossPlatformMeshControlPayload(encrypted.toByteArray()))
        assertNull(MessageRepository.crossPlatformMeshControlPayload(byteArrayOf(0x12, 0x7f, -3, 0)))
    }
}
