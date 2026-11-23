package com.oshi.messenger

import com.oshi.messenger.network.v2.V2FileKeyMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Wire parity for the ratchet payload that carries a file's key material —
 * iOS `struct V2FileKeyMessage` (V2MessageRouter.swift:44-74), produced by
 * `sendFile` (:745-757) and consumed by `parse(_:env:)` (:1057-1064).
 *
 * If these fail, an iPhone photo does not render on Android (or vice versa).
 */
class V2FileKeyMessageTest {

    // A literal payload in EXACTLY the shape iOS's JSONEncoder emits for
    // `V2FileKeyMessage`: Swift declaration order, standard padded base64, nil
    // optionals omitted. `isViewOnce` is written as `true` or left out entirely
    // (V2MessageRouter.swift:755), never as `false`.
    private val iosFixture = """
        {"kind":"v2file","blobId":"blob_2b7f1c","fileKey":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=","fileNonce":"qrvM3QECAwQ=","chunkCount":3,"manifest":"ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1+f4CBgoOEhYaHiImKi4yNjo+QkZKT","filename":"photo.jpg","mime":"image/jpeg","mediaType":"image","isViewOnce":true}
    """.trimIndent()

    @Test
    fun `parses the literal iOS v2file payload`() {
        val key = V2FileKeyMessage.parse(iosFixture)
        assertNotNull("iOS v2file payload must parse", key)
        key!!
        assertEquals("blob_2b7f1c", key.blobId)
        assertEquals(32, key.fileKey.size)
        assertEquals(8, key.fileNonce.size)
        assertEquals(3, key.chunkCount)
        assertEquals(48, key.manifest.size)
        assertEquals("photo.jpg", key.filename)
        assertEquals("image/jpeg", key.mime)
        assertEquals("image", key.mediaType)
        assertEquals(true, key.isViewOnce)
        assertNull(key.groupMessage)
        // Base64 is STANDARD + padded (Swift Data.base64EncodedString), not base64url.
        assertEquals(
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
            Base64.getEncoder().encodeToString(key.fileKey),
        )
        assertEquals(ByteArray(32) { it.toByte() }.toList(), key.fileKey.toList())
    }

    @Test
    fun `group media payload carries the stripped GroupMessage`() {
        val json = """{"kind":"v2file","blobId":"b1","fileKey":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=","fileNonce":"qrvM3QECAwQ=","chunkCount":1,"manifest":"ZGVm","filename":"clip.mp4","mime":"video/mp4","mediaType":"video","groupMessage":"eyJpZCI6IkcxIiwiZ3JvdXBJZCI6IkFCQyJ9"}"""
        val key = V2FileKeyMessage.parse(json)!!
        assertEquals("eyJpZCI6IkcxIiwiZ3JvdXBJZCI6IkFCQyJ9", key.groupMessage)
        assertNull("absent isViewOnce must stay null, not false", key.isViewOnce)
        assertEquals(
            """{"id":"G1","groupId":"ABC"}""",
            String(Base64.getDecoder().decode(key.groupMessage!!), Charsets.UTF_8),
        )
    }

    @Test
    fun `encode round-trips and emits the iOS field names`() {
        val original = V2FileKeyMessage(
            blobId = "blob_2b7f1c",
            fileKey = ByteArray(32) { it.toByte() },
            fileNonce = byteArrayOf(-86, -69, -52, -35, 1, 2, 3, 4),
            chunkCount = 3,
            manifest = ByteArray(48) { (it + 100).toByte() },
            filename = "photo.jpg",
            mime = "image/jpeg",
            mediaType = "image",
            isViewOnce = true,
            groupMessage = null,
        )
        val encoded = original.toJson()
        assertEquals("iOS re-decodes what we emit", original, V2FileKeyMessage.parse(encoded))

        val o = JSONObject(encoded)
        assertEquals("v2file", o.getString("kind"))
        assertEquals("blob_2b7f1c", o.getString("blobId"))
        assertEquals(3, o.getInt("chunkCount"))
        assertEquals("photo.jpg", o.getString("filename"))
        assertEquals(true, o.getBoolean("isViewOnce"))
        // nil optionals are OMITTED, matching Swift JSONEncoder's default.
        assertTrue("groupMessage must be absent when null", !o.has("groupMessage"))
        // The byte-identical literal iOS would have produced.
        assertEquals(iosFixture, encoded)
    }

    @Test
    fun `a false isViewOnce is omitted exactly as iOS omits it`() {
        val encoded = V2FileKeyMessage(
            blobId = "b", fileKey = ByteArray(32), fileNonce = ByteArray(8),
            chunkCount = 1, manifest = ByteArray(16), filename = "f.dat",
            mime = "application/octet-stream", mediaType = null,
            isViewOnce = null, groupMessage = null,
        ).toJson()
        val o = JSONObject(encoded)
        assertTrue(!o.has("isViewOnce"))
        assertTrue(!o.has("mediaType"))
    }

    // __VOICE_PREVIEW_2026_09_29__ A view-once VOICE note from an iPhone (AudioPreviewSheet
    // "1" button): the same flag as a photo, on mediaType "audio". Android now renders it
    // with the one-shot player instead of a replayable voice bubble.
    @Test
    fun `iOS view-once voice note keeps its flag and audio type`() {
        val json = """{"kind":"v2file","blobId":"b9","fileKey":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=","fileNonce":"qrvM3QECAwQ=","chunkCount":1,"manifest":"ZGVm","filename":"audio_1234.m4a","mime":"audio/m4a","mediaType":"audio","isViewOnce":true}"""
        val key = V2FileKeyMessage.parse(json)!!
        assertEquals("audio", key.mediaType)
        assertEquals(true, key.isViewOnce)
        assertEquals(key, V2FileKeyMessage.parse(key.toJson()))
    }

    @Test
    fun `plain text and foreign JSON are not files`() {
        assertNull(V2FileKeyMessage.parse("hello world"))
        assertNull(V2FileKeyMessage.parse("""{"type":"media","content":"AAA="}"""))
        assertNull(V2FileKeyMessage.parse("""{"kind":"something-else","blobId":"b"}"""))
        // Missing a NON-optional field → iOS's JSONDecoder fails → text, not a file.
        assertNull(V2FileKeyMessage.parse("""{"kind":"v2file","blobId":"b"}"""))
        assertNull(V2FileKeyMessage.parse("📬DELIVERY_RECEIPT📬abc"))
    }

    @Test
    fun `filenames with quotes and newlines survive the round trip`() {
        val original = V2FileKeyMessage(
            blobId = "b", fileKey = ByteArray(32), fileNonce = ByteArray(8),
            chunkCount = 1, manifest = ByteArray(16),
            filename = "my \"weird\"\nfile\\name.pdf", mime = "application/pdf",
        )
        assertEquals(original, V2FileKeyMessage.parse(original.toJson()))
    }
}
