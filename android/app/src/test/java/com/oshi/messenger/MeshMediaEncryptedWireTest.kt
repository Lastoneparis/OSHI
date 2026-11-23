package com.oshi.messenger

import com.oshi.messenger.data.repository.MessageRepository
import com.oshi.messenger.network.mesh.CrossPlatformMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * __LEAK_FIX_2026_09_25__ — Android → iPhone LAN mesh media is ratchet-ENCRYPTED.
 *
 * Before: a photo/document to an iPhone on the same Wi-Fi went out as a PLAINTEXT
 * `MEDIA_MESSAGE` frame `{data:<b64 photo>, mediaType, fileName, caption, …}`.
 * After: the frame is a `TEXT_MESSAGE` whose payload is the ratchet `EncryptedMessage`
 * JSON (`{ciphertext, signature, senderPublicKey, timestamp}`) — the same bytes the VPS
 * dual-send carries, the same shape a text already uses.
 *
 * `encryptWithDoubleRatchet` needs the Hilt graph and `android.util.Base64`, so this
 * vector rebuilds its OUTER layer (step 3-4: AES-GCM over the ratchet blob, then the
 * EncryptedMessage JSON) with JCE + java.util.Base64 — byte-compatible, just not the
 * inner ratchet — and drives the real [MessageRepository.crossPlatformMeshEncryptedPayload]
 * and the real [CrossPlatformMessage.toJSON] frame encoder. The branch wiring itself is
 * pinned on the source, like `MeshGifWireTypeTest`.
 */
class MeshMediaEncryptedWireTest {

    private val photo = ByteArray(4096).also { SecureRandom().nextBytes(it) }
    private val photoB64 = Base64.getEncoder().encodeToString(photo)
    private val caption = "Contrat signé — ne pas diffuser"
    private val fileName = "Contrat-2026.pdf"

    /** What `sendMediaMessage` encrypts: the legacy Android-direct envelope. */
    private val plaintextEnvelope = MessageRepository.legacyMediaEnvelope(
        "DOCUMENT", caption, photoB64, false, fileName = fileName,
    )

    /** Outer layer of `encryptWithDoubleRatchet`, JCE edition. */
    private fun encryptedMessageJson(plain: String): ByteArray {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        // Stand-in for base64(ratchetJson(ratchetCiphertext)): the real one is
        // ciphertext too, so this is the conservative case for "no plaintext on wire".
        val inner = Base64.getEncoder().encode(plain.toByteArray(Charsets.UTF_8))
        val sealed = nonce + c.doFinal(inner)
        return JSONObject().apply {
            put("ciphertext", Base64.getEncoder().encodeToString(sealed))
            put("signature", "")
            put("senderPublicKey", "ANDROID_SENDER_KEY_B64")
            put("timestamp", 780000000.0)
        }.toString().toByteArray(Charsets.UTF_8)
    }

    private fun frame(payload: String) = CrossPlatformMessage(
        id = "11111111-2222-3333-4444-555555555555",
        type = "TEXT_MESSAGE",
        senderPublicKey = "ANDROID_SENDER_KEY_B64",
        senderName = "",
        recipientPublicKey = "IPHONE_KEY_B64",
        payload = payload,
        timestamp = 1.0,
        hopCount = 0,
        maxHops = 5,
        seenBy = mutableListOf("ANDROID_SENDER_KEY_B64"),
        platform = "android",
    ).toJSON()

    @Test
    fun `the mesh frame carries ciphertext only - no media, caption or file name`() {
        val encrypted = encryptedMessageJson(plaintextEnvelope)
        val payload = MessageRepository.crossPlatformMeshEncryptedPayload(encrypted)
        assertNotNull(payload)
        val wire = String(frame(payload!!), Charsets.UTF_8)

        assertFalse("media bytes on the wire", wire.contains(photoB64.substring(0, 64)))
        assertFalse("caption on the wire", wire.contains("Contrat sign"))
        assertFalse("file name on the wire", wire.contains(fileName))
        assertFalse("media envelope keys on the wire", wire.contains("\"mediaType\"") || wire.contains("\"fileName\""))
        assertFalse("must not be a raw MEDIA_MESSAGE frame", wire.contains("MEDIA_MESSAGE"))
    }

    @Test
    fun `the frame is what iOS handleCrossPlatformTextMessage path 1 decodes`() {
        val payload = MessageRepository.crossPlatformMeshEncryptedPayload(encryptedMessageJson(plaintextEnvelope))!!
        val f = JSONObject(String(frame(payload), Charsets.UTF_8))
        // iOS CrossPlatformMesh dispatcher: TEXT_MESSAGE → handleCrossPlatformTextMessage.
        assertEquals("TEXT_MESSAGE", f.getString("type"))
        val p = f.getString("payload")
        // Path 1: `payloadString.hasPrefix("{")` + JSONDecoder(EncryptedMessage).
        assertTrue(p.startsWith("{"))
        val em = JSONObject(p)
        // `struct EncryptedMessage: Codable { ciphertext, signature, senderPublicKey, timestamp: Date }`
        assertEquals(setOf("ciphertext", "signature", "senderPublicKey", "timestamp"), em.keySet())
        assertTrue(em.get("timestamp") is Number)   // Date, deferredToDate = Double
        Base64.getDecoder().decode(em.getString("ciphertext"))
    }

    @Test
    fun `after decryption iOS sees the media envelope it already parses`() {
        // What `receiveMessage` gets after ratchet-decrypt is `plaintextEnvelope`.
        // iOS `isMediaMessage` pattern + `parseAndStoreMedia` Android-direct form.
        assertTrue(plaintextEnvelope.contains("\"type\":\"DOCUMENT\""))
        val j = JSONObject(plaintextEnvelope)
        assertEquals(photoB64, j.getString("data"))
        assertEquals(caption, j.getString("caption"))
        assertEquals(fileName, j.getString("fileName"))
    }

    @Test
    fun `too large or non-envelope payloads never ride the mesh`() {
        assertNull(MessageRepository.crossPlatformMeshEncryptedPayload(ByteArray(0)))
        assertNull(
            MessageRepository.crossPlatformMeshEncryptedPayload(
                ByteArray(MessageRepository.CROSS_PLATFORM_MESH_MAX_ENCRYPTED_BYTES + 1) { '{'.code.toByte() }
            )
        )
        // A plaintext non-JSON string (e.g. a raw base64 photo) is refused outright.
        assertNull(MessageRepository.crossPlatformMeshEncryptedPayload(photoB64.toByteArray()))
    }

    /** The branch sends the SAME ciphertext as the VPS leg, as a TEXT_MESSAGE, and nothing else. */
    @Test
    fun `the iOS peer media branch sends the encrypted envelope`() {
        val src = File("src/main/java/com/oshi/messenger/data/repository/MessageRepository.kt").readText()
        val start = src.indexOf("CROSS-PLATFORM MESH MEDIA")
        assertTrue(start > 0)
        val end = src.indexOf("vpsClient.sendMessage(", start)
        val code = src.substring(start, end).lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertTrue(code.contains("crossPlatformMeshEncryptedPayload(encrypted)"))
        assertTrue(code.contains("crossPlatformMesh.sendMessage(recipientPublicKey, meshPayload, \"TEXT_MESSAGE\")"))
        assertFalse(code.contains("Base64.encodeToString(compressedData"))
        // …and the raw cleartext senders are gone from the transport.
        val mesh = File("src/main/java/com/oshi/messenger/network/mesh/CrossPlatformMesh.kt").readText()
        assertFalse(mesh.contains("fun sendMedia("))
        assertFalse(mesh.contains("fun sendDocument("))
        assertFalse(mesh.contains("fun sendLocation("))
        // Receive side still accepts the legacy raw frames.
        assertTrue(mesh.contains("\"MEDIA_MESSAGE\""))
        assertTrue(mesh.contains("\"DOCUMENT_MESSAGE\""))
    }
}
