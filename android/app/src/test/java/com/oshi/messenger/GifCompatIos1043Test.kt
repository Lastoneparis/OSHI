package com.oshi.messenger

import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.repository.MessageRepository
import com.oshi.messenger.network.v2.V2FileKeyMessage
import com.oshi.messenger.service.media.GifBytes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __GIF_COMPAT_IOS1043_2026_11_21__ A 1:1 GIF / OSHI sticker must not reach iOS 1.0.43
 * (the build on sale) as an EMPTY bubble.
 *
 * 1.0.43 (commit 4befbea0) has `MediaManager.MediaType` = image/video/audio/document/contact
 * and three 1:1 receive decoders, modelled below line for line:
 *  - V2 blob   `v2DeliverFile`:  `MediaType(rawValue: label) ?? v2MediaType(forMime: mime)`
 *  - V2 inline `v2ParseEmbeddedMediaJSON`: `MediaType(rawValue: label)` — no fallback
 *  - legacy / LAN mesh `parseAndStoreMedia`: lowercased label, `photo`→`image`, then
 *    `MediaType(rawValue:)` — no fallback
 * and `MediaMessageView.loadMedia()` renders nothing when the type is nil.
 *
 * Every envelope Android emits for a GIF is fed to those three decoders as BYTES (the JSON
 * the iPhone actually parses), never as a label handed over by hand.
 */
class GifCompatIos1043Test {

    private val gif = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x3B
    )
    private val gifB64 = java.util.Base64.getEncoder().encodeToString(gif)

    // ------------------------------------------------------------ iOS 1.0.43, modelled

    private val ios1043Types = setOf("image", "video", "audio", "document", "contact")

    private fun rawValue(s: String?): String? = s?.takeIf { it in ios1043Types }

    private fun v2MediaTypeForMime(mime: String): String = when {
        mime.startsWith("image/") -> "image"
        mime.startsWith("video/") -> "video"
        mime.startsWith("audio/") -> "audio"
        mime.contains("vcard") -> "contact"
        else -> "document"
    }

    /** `v2DeliverFile` on a parsed `V2FileKeyMessage`. */
    private fun ios1043Blob(keyJson: String): String? {
        val o = JSONObject(keyJson)
        return rawValue(o.optString("mediaType").ifEmpty { null }) ?: v2MediaTypeForMime(o.getString("mime"))
    }

    /** `v2ParseEmbeddedMediaJSON`. */
    private fun ios1043Inline(text: String): String? {
        val o = JSONObject(text)
        if (o.optString("type") != "media" || o.optString("content").isEmpty()) return "<not media>"
        return rawValue(o.optString("mediaType").ifEmpty { null })
    }

    /** `parseAndStoreMedia` (after `isMediaMessage`). */
    private fun ios1043Legacy(text: String): String? {
        val o = JSONObject(text)
        val top = o.optString("type")
        val androidDirect = top.uppercase() in setOf("IMAGE", "VIDEO", "AUDIO", "DOCUMENT")
        val raw = if (androidDirect) top else o.optString("mediaType").ifEmpty { return "<not media>" }
        var n = raw.lowercase()
        if (n == "photo") n = "image"
        return rawValue(n)
    }

    // ------------------------------------------------------------ sender labels

    @Test
    fun `a GIF is labelled image on every 1to1 wire but keeps its MIME`() {
        assertTrue(GifBytes.isGif(gif))
        assertEquals("image", MessageRepository.GIF_WIRE_MEDIA_TYPE)
        assertEquals("image", MessageRepository.v2WireMediaType(MediaType.IMAGE, isGif = true))
        assertEquals("image/gif", MessageRepository.v2WireMime(MediaType.IMAGE, isGif = true))
        assertEquals("image", MessageRepository.legacyWireMediaType(MediaType.IMAGE, isGif = true))
        assertEquals("image", MessageRepository.crossPlatformMeshWireType(MediaType.IMAGE, isGif = true))
    }

    @Test
    fun `non GIF media keep exactly the labels that shipped`() {
        val cases = mapOf(
            MediaType.IMAGE to ("image" to "image/jpeg"),
            MediaType.VIDEO to ("video" to "video/mp4"),
            MediaType.AUDIO to ("audio" to "audio/m4a"),
            MediaType.CONTACT to ("contact" to "text/vcard"),
            MediaType.DOCUMENT to ("document" to "application/octet-stream"),
        )
        for ((t, want) in cases) {
            assertEquals(t.name, want.first, MessageRepository.v2WireMediaType(t, false))
            assertEquals(t.name, want.second, MessageRepository.v2WireMime(t, false))
        }
        assertNull(MessageRepository.legacyWireMediaType(MediaType.IMAGE, false))
        assertEquals("contact", MessageRepository.legacyWireMediaType(MediaType.CONTACT, false))
    }

    // ------------------------------------------------------------ fixtures through 1.0.43

    @Test
    fun `the blob key message we send decodes to image on iOS 1043`() {
        val key = V2FileKeyMessage(
            blobId = "b1", fileKey = ByteArray(32), fileNonce = ByteArray(8), chunkCount = 1,
            manifest = ByteArray(16), filename = "oshi_ok.gif",
            mime = MessageRepository.v2WireMime(MediaType.IMAGE, true),
            mediaType = MessageRepository.v2WireMediaType(MediaType.IMAGE, true),
        ).toJson()
        val o = JSONObject(key)
        assertEquals("image", o.getString("mediaType"))
        assertEquals("image/gif", o.getString("mime"))
        assertEquals("oshi_ok.gif", o.getString("filename"))
        assertEquals("image", ios1043Blob(key))
    }

    @Test
    fun `the inline V2 envelope we send decodes to image on iOS 1043`() {
        val env = MessageRepository.v2InlineMediaEnvelope(
            MessageRepository.v2WireMediaType(MediaType.IMAGE, true), gifB64, "oshi_ok.gif",
            gif.size, null, false,
        )
        assertEquals("image", ios1043Inline(env))
        val o = JSONObject(env)
        assertEquals("oshi_ok.gif", o.getString("fileName"))
        assertTrue(GifBytes.isGif(java.util.Base64.getDecoder().decode(o.getString("content"))))
    }

    @Test
    fun `the legacy and LAN mesh envelope we send decodes to image on iOS 1043`() {
        val env = MessageRepository.legacyMediaEnvelope(
            MediaType.IMAGE.name, null, gifB64, false, fileName = "oshi_ok.gif",
            iosMediaType = MessageRepository.legacyWireMediaType(MediaType.IMAGE, true),
        )
        assertEquals("image", ios1043Legacy(env))
        assertEquals("oshi_ok.gif", JSONObject(env).getString("fileName"))
    }

    /** The defect itself, pinned: what the previous label did on the two label-only paths. */
    @Test
    fun `the old gif label was nil on iOS 1043 inline and legacy but not on blob`() {
        val inline = MessageRepository.v2InlineMediaEnvelope("gif", gifB64, "x.gif", gif.size, null, false)
        assertNull(ios1043Inline(inline))
        val legacy = MessageRepository.legacyMediaEnvelope(
            MediaType.IMAGE.name, null, gifB64, false, fileName = "x.gif", iosMediaType = "gif",
        )
        assertNull(ios1043Legacy(legacy))
        val blob = """{"kind":"v2file","mediaType":"gif","mime":"image/gif"}"""
        assertEquals("image", ios1043Blob(blob))
    }

    // ------------------------------------------------------------ receiver side

    /** What an OLD peer (Android ≤ 1.7.1, desktop ≤ 1.6.2, iOS ≥ 1.0.44) still sends us. */
    @Test
    fun `an old peer's gif labelled key message still lands as an IMAGE with GIF bytes`() {
        val old = """{"kind":"v2file","blobId":"b","fileKey":"${b64(32)}","fileNonce":"${b64(8)}",""" +
            """"chunkCount":1,"manifest":"${b64(16)}","filename":"a.gif","mime":"image/gif","mediaType":"gif"}"""
        val k = V2FileKeyMessage.parse(old)
        assertNotNull(k)
        assertEquals("gif", k!!.mediaType)
        assertEquals(MediaType.IMAGE, MediaType.fromWireLabel(k.mediaType))
    }

    @Test
    fun `an image labelled GIF is still recognised as a GIF from its bytes`() {
        // The label no longer says GIF; Android never needed it to: both map to IMAGE and
        // the bubble's Coil loader (ImageDecoderDecoder / GifDecoder) animates from the bytes.
        assertEquals(MediaType.fromWireLabel("gif"), MediaType.fromWireLabel("image"))
        assertTrue(GifBytes.isGif(gif))
        assertTrue(GifBytes.isGif("GIF87a......".toByteArray()))
        assertEquals(false, GifBytes.isGif(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 0, 0)))
    }

    private fun b64(n: Int) = java.util.Base64.getEncoder().encodeToString(ByteArray(n))
}
