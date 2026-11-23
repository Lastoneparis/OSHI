package com.oshi.messenger.service.lora

import com.oshi.messenger.network.encryption.IOSRatchetHeader
import com.oshi.messenger.network.encryption.IOSRatchetMessage
import com.oshi.messenger.service.MessageSendPolicy
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
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * __LORA_LONG_TEXT_OMP_2026_11_18__ Seeed's failure: a 2,000-character text refused off-grid with
 * "Too large for one LoRa frame (… bytes, limit 500)" while the OSHI Mesh firmware on the radio
 * carries 64 x 182 B. These pin (1) which budget applies to which radio, and (2) that the budget
 * chosen for an OSHI Mesh radio really fits it, by building the envelope the app builds.
 */
class LoRaLongTextTest {

    // ───────────────────────────── (1) limit selection ─────────────────────────────

    @Test
    fun `oshi mesh radio gets 4000 bytes, stock or unknown keeps 500, no radio keeps 16000`() {
        assertEquals(4_000, MessageSendPolicy.LORA_OMP_UTF8_BYTES)
        assertEquals(4_000, MessageSendPolicy.maxUTF8Bytes(loRaPaired = true, oshiFirmware = true))
        // Stock firmware, or a radio whose firmware we never learned: the phone-side 12-frame path.
        assertEquals(500, MessageSendPolicy.maxUTF8Bytes(loRaPaired = true, oshiFirmware = false))
        assertEquals(500, MessageSendPolicy.maxUTF8Bytes(loRaPaired = true))
        // Not on the LoRa route at all: the OSHI flag is irrelevant.
        assertEquals(16_000, MessageSendPolicy.maxUTF8Bytes(loRaPaired = false, oshiFirmware = true))
        assertEquals(16_000, MessageSendPolicy.maxUTF8Bytes(loRaPaired = false, oshiFirmware = false))
    }

    @Test
    fun `seeed's 2000 character text passes with an oshi mesh radio and the boundary is exact`() {
        val seeed = "LoRa long text test. ".repeat(100)   // 2,100 B
        assertEquals(MessageSendPolicy.Outcome.TOO_LONG, MessageSendPolicy.check(seeed, loRaPaired = true))
        assertEquals(
            MessageSendPolicy.Outcome.WITHIN_BUDGET,
            MessageSendPolicy.check(seeed, loRaPaired = true, oshiFirmware = true),
        )
        assertEquals(
            MessageSendPolicy.Outcome.WITHIN_BUDGET,
            MessageSendPolicy.check("a".repeat(4_000), loRaPaired = true, oshiFirmware = true),
        )
        assertEquals(
            MessageSendPolicy.Outcome.TOO_LONG,
            MessageSendPolicy.check("a".repeat(4_001), loRaPaired = true, oshiFirmware = true),
        )
        // Bytes, not characters: 1,001 four-byte emoji = 4,004 B.
        assertEquals(
            MessageSendPolicy.Outcome.TOO_LONG,
            MessageSendPolicy.check("😀".repeat(1_001), loRaPaired = true, oshiFirmware = true),
        )
    }

    // ───────────────────────────── (2) the envelope fits ─────────────────────────────

    private val rng = SecureRandom()
    private fun rnd(n: Int) = ByteArray(n).also { rng.nextBytes(it) }
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    private fun gcm(plain: ByteArray): ByteArray {
        val iv = rnd(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(rnd(32), "AES"), GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)   // nonce || ct || tag, CryptoKit's `combined`
    }

    /** P-256 raw public key as the identity layer spells it: 88 base64 chars, the larger of the two forms. */
    private val myKey = b64(rnd(65))
    private val peerKey = b64(rnd(65))

    /**
     * The exact layering of `MessageRepository.encryptWithDoubleRatchet` + `buildLoRaWireMessage`:
     * ratchet AES-GCM → IOSRatchetMessage JSON → base64 → AES-GCM → base64 inside the iOS
     * EncryptedMessage → LoRaWireMessage → `toWireJson()` (meshWireSafe). Only the keys are random —
     * which is the worst case for deflate, since ciphertext does not compress.
     */
    private fun wireFor(text: String): LoRaWireMessage {
        val ratchet = IOSRatchetMessage(
            header = IOSRatchetHeader(
                publicKey = rnd(32),
                messageNumber = 4_242,
                previousChainLength = 4_241,
                isDHRatchet = false,
                timestamp = System.currentTimeMillis() / 1000.0 - 978_307_200.0,
                sessionVersion = 3,
                sessionId = UUID.randomUUID().toString(),
            ),
            ciphertext = gcm(text.toByteArray(Charsets.UTF_8)),
        )
        val ratchetB64 = b64(ratchet.toJSON().toString().toByteArray(Charsets.UTF_8))
        val enc = LoRaEncryptedContent(
            ciphertext = b64(gcm(ratchetB64.toByteArray(Charsets.UTF_8))),
            signature = "",
            senderPublicKey = myKey,
            timestamp = System.currentTimeMillis(),
        )
        return LoRaWireMessage(
            id = UUID.randomUUID().toString().uppercase(),
            senderAddress = myKey,
            recipientAddress = peerKey,
            encryptedContent = enc,
            timestamp = System.currentTimeMillis(),
            isRead = false,
            deliveryStatus = LoRaWireMessage.STATUS_SENT,
            senderPublicKey = myKey,
            recipientPublicKey = peerKey,
            plaintextContent = text,   // LOCAL ONLY: must not reach the air
        )
    }

    @Test
    fun `4000 bytes of text fit an oshi mesh radio without counting on deflate`() {
        val text = "é".repeat(2_000)   // 4,000 UTF-8 bytes
        val json = wireFor(text).toWireJson()
        // Measured layering: ~1,350 + 2.38 x bytes (three base64 layers), NOT the 1.83x once quoted.
        assertTrue("wire ${json.size} B", json.size <= 1_350 + (2.38 * 4_000).toInt() + 200)
        assertTrue("raw JSON must already fit 64 x 182", json.size <= OshiMeshProtocol.MAX_MESSAGE)
        val body = OshiMeshProtocol.encodeBody(json)
        val frames = OshiMeshProtocol.dataFrames(1u, 0x51e37c4au, body, OshiMeshProtocol.FLAG_CUSTODY_OK)
        assertNotNull(frames)
        assertTrue(LoRaRadioGate.fits(body, oshiFirmware = true))
        // …and the same envelope is honestly too large for the phone-side stock path.
        assertFalse(LoRaRadioGate.fits(body, oshiFirmware = false))
        // The user's text never travels in the clear.
        assertFalse(String(json, Charsets.UTF_8).contains("ééééé"))
    }

    @Test
    fun `500 bytes still fit the stock 12-frame path`() {
        val body = OshiMeshProtocol.encodeBody(wireFor("a".repeat(500)).toWireJson())
        assertTrue(LoRaRadioGate.fits(body, oshiFirmware = false))
    }

    /**
     * For the bench (`OSHI_LORA_DUMP=<dir>`): the OMP body the fixed app hands an OSHI Mesh radio for a
     * 3,000-character French text, so two real T1000-E can carry exactly that over the air.
     */
    @Test
    fun `dump the omp body of a 3000 character text for the hardware bench`() {
        val base = "Le message long part en un seul envoi par la radio LoRa, même hors réseau. "
        val text = base.repeat(3_000 / base.length + 1).take(3_000)
        assertEquals(3_000, text.length)
        val bytes = text.toByteArray(Charsets.UTF_8).size
        assertTrue("$bytes B", bytes <= MessageSendPolicy.LORA_OMP_UTF8_BYTES)
        assertEquals(
            MessageSendPolicy.Outcome.WITHIN_BUDGET,
            MessageSendPolicy.check(text, loRaPaired = true, oshiFirmware = true),
        )
        val json = wireFor(text).toWireJson()
        val body = OshiMeshProtocol.encodeBody(json)
        val frames = OshiMeshProtocol.dataFrames(7u, 0xce0a9903u, body, OshiMeshProtocol.FLAG_CUSTODY_OK)!!
        assertTrue(frames.size <= OshiMeshProtocol.MAX_FRAGS)
        assertEquals(json.toList(), OshiMeshProtocol.decodeBody(body)!!.toList())
        System.getenv("OSHI_LORA_DUMP")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "longtext3000.body").writeBytes(body)
            File(dir, "longtext3000.wire.json").writeBytes(json)
            File(dir, "longtext3000.txt").writeText(
                "chars=${text.length} utf8=$bytes wire=${json.size} body=${body.size} frames=${frames.size}\n"
            )
        }
    }

    @Test
    fun `wire json survives the held queue byte for byte`() {
        val json = wireFor("hello").toWireJson()
        val q = LoRaHeldQueue()
        q.add("m1", peerKey, json, 1L)
        assertTrue(q.snapshot().single().wireJson.contentEquals(json))
        assertNull(JSONObject(String(json)).optString("plaintextContent", null))
    }
}
