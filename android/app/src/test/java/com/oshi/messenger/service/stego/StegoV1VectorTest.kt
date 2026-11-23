package com.oshi.messenger.service.stego

import com.oshi.messenger.network.v2.OSHICryptoV2
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Cross-platform STEGO-v1 vectors (docs/fixtures/stego/). The SAME files are
 * checked by iOS `StegoV1Tests.swift`:
 *  - vectors.json: keys, intermediates and the SHA-256 of the embedded samples
 *    for a deterministic cover + deterministic RNG (spec §5 test mode);
 *  - android_v1.png (written by this implementation) and ios_v1.png (written by
 *    the Swift one) must both reveal to the vector plaintext here.
 *
 * Regenerate (only when the spec changes): STEGO_WRITE_VECTORS=1 ./gradlew
 * :app:testDebugUnitTest --tests '*StegoV1VectorTest*'
 */
class StegoV1VectorTest {

    companion object {
        fun sha(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        val SENDER_PRIV = sha("OSHI-STEGO-v1 vector sender")
        val RECIPIENT_PRIV = sha("OSHI-STEGO-v1 vector recipient")
        val EPHEMERAL_PRIV = sha("OSHI-STEGO-v1 vector ephemeral")
        const val W = 64
        const val H = 48
        val PAYLOAD = StegoV1.Payload(
            text = "Bonjour 👋 — rendez-vous 18h, porte B.",
            createdAtMs = 1_763_460_000_000L,
            file = StegoV1.Attachment("note.txt", "text/plain", "OSHI STEGO-v1 attachment\n".toByteArray()),
        )

        fun cover(): StegoV1.RgbImage {
            val px = ByteArray(W * H * 3)
            for (y in 0 until H) for (x in 0 until W) {
                val o = (y * W + x) * 3
                px[o] = ((x * 7 + y * 13) % 256).toByte()
                px[o + 1] = ((x * 3 + y * 5 + 50) % 256).toByte()
                px[o + 2] = ((x * 11 + y * 2 + 100) % 256).toByte()
            }
            return StegoV1.RgbImage(W, H, px)
        }

        fun fixtures(): File {
            var d: File? = File(System.getProperty("user.dir")).absoluteFile
            while (d != null) {
                val f = File(d, "docs/fixtures/stego")
                if (File(d, "docs/stego/STEGO-v1.md").exists()) return f.also { it.mkdirs() }
                d = d.parentFile
            }
            error("docs/stego not found above ${System.getProperty("user.dir")}")
        }
    }

    private fun build(): Pair<JSONObject, StegoV1.RgbImage> {
        val sPub = OSHICryptoV2.x25519PubFromPriv(SENDER_PRIV)
        val rPub = OSHICryptoV2.x25519PubFromPriv(RECIPIENT_PRIV)
        val ePub = OSHICryptoV2.x25519PubFromPriv(EPHEMERAL_PRIV)
        val ssStatic = OSHICryptoV2.dh(SENDER_PRIV, rPub)
        val ssEph = OSHICryptoV2.dh(EPHEMERAL_PRIV, rPub)
        val kOrder = StegoV1.orderKey(ssStatic)
        val (kH, kB) = StegoV1.sealKeys(ssEph, ssStatic, ePub, sPub, rPub)
        val order = StegoV1.SlotOrder(kOrder, W * H * 3)
        val first = JSONArray().apply { repeat(32) { put(order.next()) } }
        val body = StegoV1.encodePayload(PAYLOAD)
        val out = StegoV1.hide(cover(), PAYLOAD, SENDER_PRIV, sPub, rPub, EPHEMERAL_PRIV, StegoV1.testByteSource())
        val v = JSONObject()
            .put("spec", "docs/stego/STEGO-v1.md")
            .put("sender_priv", hex(SENDER_PRIV)).put("sender_pub", hex(sPub))
            .put("recipient_priv", hex(RECIPIENT_PRIV)).put("recipient_pub", hex(rPub))
            .put("ephemeral_priv", hex(EPHEMERAL_PRIV)).put("ephemeral_pub", hex(ePub))
            .put("ss_static", hex(ssStatic)).put("ss_eph", hex(ssEph))
            .put("k_order", hex(kOrder)).put("k_header", hex(kH)).put("k_body", hex(kB))
            .put("width", W).put("height", H)
            .put("cover", "R=(7x+13y)%256 G=(3x+5y+50)%256 B=(11x+2y+100)%256")
            .put("first_slots", first)
            .put("payload_text", PAYLOAD.text).put("payload_created_at_ms", PAYLOAD.createdAtMs)
            .put("payload_file_name", PAYLOAD.file!!.name).put("payload_file_mime", PAYLOAD.file!!.mime)
            .put("payload_file_hex", hex(PAYLOAD.file!!.data))
            .put("body_plain_hex", hex(body))
            .put("embedded_rgb_sha256", hex(MessageDigest.getInstance("SHA-256").digest(out.rgb)))
        return v to out
    }

    @Test
    fun vectorsMatchAndBothPlatformsPngsReveal() {
        val dir = fixtures()
        val (v, out) = build()
        val json = File(dir, "vectors.json")
        if (System.getenv("STEGO_WRITE_VECTORS") == "1" || !json.exists()) {
            json.writeText(v.toString(2) + "\n")
            File(dir, "android_v1.png").writeBytes(StegoPng.encode(out))
        }
        val expected = JSONObject(json.readText())
        for (k in expected.keys()) assertEquals("vector field $k", expected.get(k).toString(), v.get(k).toString())

        val rPub = unhex(expected.getString("recipient_pub"))
        val sPub = unhex(expected.getString("sender_pub"))
        val decoys = listOf(OSHICryptoV2.generateX25519().pub, OSHICryptoV2.generateX25519().pub)
        var checked = 0
        for (name in listOf("android_v1.png", "ios_v1.png")) {
            val f = File(dir, name)
            if (!f.exists()) { assertEquals("android_v1.png must exist", "ios_v1.png", name); continue }
            val img = StegoPng.decode(f.readBytes())
            assertEquals("$name samples", expected.getString("embedded_rgb_sha256"),
                hex(MessageDigest.getInstance("SHA-256").digest(img.rgb)))
            val r = StegoV1.reveal(img, RECIPIENT_PRIV, rPub, decoys + listOf(sPub)) as StegoV1.RevealResult.Found
            assertArrayEquals(sPub, r.senderPublicKey)
            assertEquals(PAYLOAD.text, r.payload.text)
            assertEquals(PAYLOAD.createdAtMs, r.payload.createdAtMs)
            assertArrayEquals(PAYLOAD.file!!.data, r.payload.file!!.data)
            checked++
        }
        println("STEGO-v1 vectors OK; PNGs revealed: $checked")
    }

    /**
     * The PRODUCTION path (CSPRNG fill, fresh ephemeral) across platforms:
     *  - ios_prod_v1.png was made by the Swift code (`stego-harness hide`) for the
     *    vector recipient, from the vector sender; it must reveal here;
     *  - android_prod_v1.png is made here (once) and revealed by the Swift harness
     *    (`stego-harness reveal`) and by iOS `StegoV1Tests`.
     */
    @Test
    fun productionPathPngsCrossPlatforms() {
        val dir = fixtures()
        val sPub = OSHICryptoV2.x25519PubFromPriv(SENDER_PRIV)
        val rPub = OSHICryptoV2.x25519PubFromPriv(RECIPIENT_PRIV)
        val ours = File(dir, "android_prod_v1.png")
        if (!ours.exists()) {
            val px = ByteArray(480 * 360 * 3) { ((it / 3 % 480) + (it / 1440) + (it % 3) * 40).toByte() }
            val out = StegoV1.hide(StegoV1.RgbImage(480, 360, px),
                StegoV1.Payload("Android production path (SecureRandom fill, fresh ephemeral) → iOS", 1_763_460_000_000L, null),
                SENDER_PRIV, sPub, rPub)
            ours.writeBytes(StegoPng.encode(out))
        }
        for ((name, prefix) in listOf("android_prod_v1.png" to "Android production", "ios_prod_v1.png" to "iOS production")) {
            val f = File(dir, name)
            if (!f.exists()) { assertEquals("ios_prod_v1.png", name); continue }
            val r = StegoV1.reveal(StegoPng.decode(f.readBytes()), RECIPIENT_PRIV, rPub, listOf(sPub)) as StegoV1.RevealResult.Found
            org.junit.Assert.assertTrue(name, r.payload.text.startsWith(prefix))
        }
    }
}
