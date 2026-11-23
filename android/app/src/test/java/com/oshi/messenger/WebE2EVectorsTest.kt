package com.oshi.messenger

import com.oshi.messenger.network.v2.OSHICryptoV2
import com.oshi.messenger.network.web.WebE2E
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * __WEBSESSION_E2E_2026_09_23__ Golden vectors of `ServerPatches/websession_e2e/test/vectors.json`
 * (CLIENT_SPEC.md §3). The same bytes are produced by the node phone reference and by the page's
 * WebCrypto module; this Android port must reproduce every one of them.
 */
class WebE2EVectorsTest {

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    // Inputs + outputs, copied verbatim from test/vectors.json (and cross-checked against the file
    // when the repository layout is present — see `vectors file matches the embedded copy`).
    private val browserPriv = "a8abababababababababababababababababababababababababababababab6b"
    private val phonePriv = "c8cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd4d"
    private val qrSecret = "000102030405060708090a0b0c0d0e0f"
    private val token = "5f1c0e9d3b2a41786655443322110099aabbccddeeff00112233445566778899"
    private val sid = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val phoneEpoch = "1112131415161718"
    private val ts = 1790000000000L

    private val browserPub = "e3712d851a0e5d79b831c5e34ab22b41a198171de209b8b8faca23a11c624859"
    private val phonePub = "b5bea823d9c9ff576091c54b7c596c0ae296884f0e150290e88455d7fba6126f"
    private val qrString = "oshi://web-auth?token=5f1c0e9d3b2a41786655443322110099aabbccddeeff00112233445566778899&v=2&k=43EthRoOXXm4McXjSrIrQaGYFx3iCbi4-sojoRxiSFk&s=AAECAwQFBgcICQoLDA0ODw"
    private val shared = "235101b705734aae8d4c2d9d0f1baf90bbb2a8c233d831a80d43815bb47ead10"
    private val th = "39523158113e5c066f93cd7bb75029000349d532475a767680e63825beec3d67"
    private val kB2p = "8c3e8087ed1b6d7d1c1a58b65f951275d5db71495b629681486dee60319f5ef8"
    private val kP2b = "00a61460459156f234ce24dde653940e1473ee16cedbb1d6bad9a1e9d88ee7aa"
    private val kConfirm = "31c3e5b134ece5ada3d222669e1429c1fbc89e3c0fa91046d0656329a8e98433"
    private val confirmTag = "de82a8ac49b83ef91227b99ddc52f9a65f0873fe17850a4c323266cd7aad3a88"
    private val confirmB64 = "3oKorEm4PvkSJ7md3FL5pl8Ic/4XhQpMMjJmzXqtOog="
    private val sas = "035469"
    private val frameEp = "ERITFBUWFxg="
    private val frameCt = "3PsamvMnbOkenTlj7cyyjtcapQXtUFK399S/A5SoF/W3hBdzy75e2AEqEM1Dj0FVmHMiXneHqGbrRbWVtu9QlkKxm4o5qkIcA6LrPCRPLumUXvkleODIIcGTQNv8nlKuiri9ZN3PJermg7x3Rhby5qXLjQW+viiTROp3"
    private val frameAad = "OSHI-WS2|0123456789abcdef0123456789abcdef0123456789abcdef|p2b|ERITFBUWFxg=|1"
    private val frameNonce = "111213141516171800000001"
    private val framePlaintext = "{\"t\":\"new_message\",\"contactPublicKey\":\"QUJD\",\"message\":{\"id\":\"m1\",\"content\":\"Bonjour\"},\"_ts\":1790000000000}"

    @Test
    fun `public keys and shared secret`() {
        assertEquals(browserPub, hexOf(OSHICryptoV2.x25519PubFromPriv(hex(browserPriv))))
        assertEquals(phonePub, hexOf(OSHICryptoV2.x25519PubFromPriv(hex(phonePriv))))
        assertEquals(shared, hexOf(OSHICryptoV2.dh(hex(phonePriv), hex(browserPub))))
        // Both sides agree.
        assertEquals(shared, hexOf(OSHICryptoV2.dh(hex(browserPriv), hex(phonePub))))
    }

    @Test
    fun `qr parse`() {
        val qr = WebE2E.parseQr(qrString) as WebE2E.Qr.V2
        assertEquals(token, qr.token)
        assertEquals(browserPub, hexOf(qr.browserKey))
        assertEquals(qrSecret, hexOf(qr.secret))
    }

    @Test
    fun `qr without v2 is v1, bad lengths are rejected`() {
        assertEquals(WebE2E.Qr.V1(token), WebE2E.parseQr("oshi://web-auth?token=$token"))
        assertEquals(WebE2E.Qr.V1(token), WebE2E.parseQr("oshi://web-auth?token=$token&v=1&k=AAAA&s=BBBB"))
        assertNull(WebE2E.parseQr("oshi://web-auth?token=$token&v=2&k=AAAA&s=AAECAwQFBgcICQoLDA0ODw"))
        assertNull(WebE2E.parseQr("oshi://web-auth?token=$token&v=2&k=43EthRoOXXm4McXjSrIrQaGYFx3iCbi4-sojoRxiSFk&s=AAEC"))
        assertNull(WebE2E.parseQr("oshi://web-auth?v=2"))
        assertNull(WebE2E.parseQr("https://example.com/?token=$token"))
    }

    @Test
    fun `key schedule, confirm and sas`() {
        val p = WebE2E.pair(WebE2E.parseQr(qrString) as WebE2E.Qr.V2, phonePriv = hex(phonePriv))
        assertEquals(phonePub, hexOf(p.phonePub))
        assertEquals(th, hexOf(p.keys.th))
        assertEquals(kB2p, hexOf(p.keys.b2p))
        assertEquals(kP2b, hexOf(p.keys.p2b))
        assertEquals(kConfirm, hexOf(p.keys.confirmKey))
        assertEquals(confirmTag, hexOf(p.keys.confirm))
        assertEquals(confirmB64, p.confirmB64)
        assertEquals(sas, p.sas)
        assertEquals(Base64.getEncoder().encodeToString(hex(phonePub)), p.phoneKeyB64)
    }

    @Test
    fun `p2b frame seq 1 is byte-identical`() {
        assertEquals(frameAad, String(WebE2E.aad(sid, "p2b", frameEp, 1), Charsets.UTF_8))
        assertEquals(frameNonce, hexOf(WebE2E.nonce(hex(phoneEpoch), 1)))
        val ch = WebE2E.Channel(sid, hex(kP2b), hex(kB2p), now = { ts }, epoch = hex(phoneEpoch))
        val f = ch.sealPlaintext(framePlaintext.toByteArray(Charsets.UTF_8))
        assertEquals("e2e", f.getString("type"))
        assertEquals(2, f.getInt("v"))
        assertEquals(sid, f.getString("sid"))
        assertEquals("p2b", f.getString("dir"))
        assertEquals(frameEp, f.getString("ep"))
        assertEquals(1, f.getInt("seq"))
        assertEquals(frameCt, f.getString("ct"))
        // and the browser (k_p2b receiver) opens it back to the same plaintext
        val pt = OSHICryptoV2.aesGcmOpen(hex(kP2b), hex(frameNonce), Base64.getDecoder().decode(frameCt), frameAad.toByteArray())
        assertEquals(framePlaintext, String(pt, Charsets.UTF_8))
    }

    /** A browser-side (b2p) seal, written independently of [WebE2E.Channel]. */
    private fun browserFrame(ep: ByteArray, seq: Long, inner: String, key: ByteArray = hex(kB2p), dir: String = "b2p"): JSONObject {
        val epS = Base64.getEncoder().encodeToString(ep)
        val ct = OSHICryptoV2.aesGcmSeal(key, ep + OSHICryptoV2.u32be(seq), inner.toByteArray(), "OSHI-WS2|$sid|$dir|$epS|$seq".toByteArray())
        return JSONObject().put("type", "e2e").put("v", 2).put("sid", sid).put("dir", dir).put("ep", epS).put("seq", seq)
            .put("ct", Base64.getEncoder().encodeToString(ct))
    }

    @Test
    fun `b2p open, replay, stale epoch, tamper, wrong sid or dir`() {
        var now = ts
        val ch = WebE2E.Channel(sid, hex(kP2b), hex(kB2p), now = { now })
        val ep = hex("0102030405060708")
        val inner = "{\"t\":\"send_message\",\"recipientPublicKey\":\"QUJD\",\"content\":\"hi\",\"clientMsgId\":\"c1\",\"_ts\":$ts}"
        val opened = ch.open(browserFrame(ep, 1, inner))
        assertEquals("send_message", opened.getString("t"))
        assertEquals("hi", opened.getString("content"))
        expectRefused("replay") { ch.open(browserFrame(ep, 1, inner)) }
        // seq 2 in a known epoch is fine even after the freshness window
        now = ts + 10 * 60_000
        assertNotNull(ch.open(browserFrame(ep, 2, inner)))
        // a NEW epoch whose first frame is older than 5 min is refused
        expectRefused("stale") { ch.open(browserFrame(hex("0807060504030201"), 1, inner)) }
        // tampered ciphertext
        val f = browserFrame(ep, 3, inner)
        val raw = Base64.getDecoder().decode(f.getString("ct")); raw[0] = (raw[0].toInt() xor 1).toByte()
        f.put("ct", Base64.getEncoder().encodeToString(raw))
        expectRefused("auth-failed") { ch.open(f) }
        // wrong direction key / label
        expectRefused("wrong-dir") { ch.open(browserFrame(ep, 4, inner, hex(kP2b), dir = "p2b")) }
        expectRefused("wrong-sid") { ch.open(browserFrame(ep, 5, inner).put("sid", "f".repeat(48))) }
    }

    private fun expectRefused(reason: String, block: () -> Unit) {
        try { block(); fail("expected $reason") } catch (e: WebE2E.FrameException) { assertEquals(reason, e.message) }
    }

    @Test
    fun `seal then page-side open roundtrip with increasing seq`() {
        val ch = WebE2E.Channel(sid, hex(kP2b), hex(kB2p), now = { ts })
        val f1 = ch.seal(JSONObject().put("t", "conversations_sync").put("conversations", org.json.JSONArray()))
        val f2 = ch.seal(JSONObject().put("t", "send_result").put("clientMsgId", "x").put("status", "sent"))
        assertEquals(1, f1.getInt("seq")); assertEquals(2, f2.getInt("seq"))
        assertEquals(f1.getString("ep"), f2.getString("ep"))
        val ep = Base64.getDecoder().decode(f2.getString("ep"))
        val pt = OSHICryptoV2.aesGcmOpen(hex(kP2b), ep + OSHICryptoV2.u32be(2), Base64.getDecoder().decode(f2.getString("ct")),
            "OSHI-WS2|$sid|p2b|${f2.getString("ep")}|2".toByteArray())
        val inner = JSONObject(String(pt))
        assertEquals("send_result", inner.getString("t"))
        assertEquals(ts, inner.getLong("_ts"))
    }

    @Test
    fun `v1 types map to v2 inner names`() {
        assertEquals("conversations_sync", WebE2E.innerTypeFor("sync_conversations"))
        assertEquals("messages_sync", WebE2E.innerTypeFor("sync_messages"))
        assertEquals("groups_sync", WebE2E.innerTypeFor("sync_groups"))
        assertEquals("group_messages_sync", WebE2E.innerTypeFor("sync_group_messages"))
        assertEquals("new_message", WebE2E.innerTypeFor("new_message"))
        val inner = WebE2E.toInner(JSONObject().put("type", "sync_messages").put("contactPublicKey", "K"))
        assertEquals("messages_sync", inner.getString("t"))
        assertTrue(!inner.has("type"))
    }

    @Test
    fun `media chunks and upload assembly`() {
        val data = ByteArray(WebE2E.MEDIA_CHUNK * 2 + 5) { it.toByte() }
        val chunks = WebE2E.mediaChunks("m1", "private", "image/jpeg", "a.jpg", data)
        assertEquals(3, chunks.size)
        assertEquals(0, chunks[0].getInt("off")); assertEquals(WebE2E.MEDIA_CHUNK, chunks[1].getInt("off"))
        assertTrue(chunks[2].getBoolean("last"))
        val asm = WebE2E.UploadAssembler()
        var done: WebE2E.UploadAssembler.Done? = null
        for (c in chunks) {
            val up = JSONObject().put("uploadId", "u1").put("recipientPublicKey", "K").put("kind", "image")
                .put("fileName", "a.jpg").put("mime", "image/jpeg").put("size", data.size)
                .put("off", c.getInt("off")).put("last", c.getBoolean("last")).put("data", c.getString("data"))
            done = asm.feed(up)
        }
        assertArrayEquals(data, done!!.bytes)
        // an out-of-order chunk abandons the upload
        val asm2 = WebE2E.UploadAssembler()
        assertNull(asm2.feed(JSONObject().put("uploadId", "u2").put("size", 10).put("off", 5).put("last", true).put("data", "AAAA")))
    }

    @Test
    fun `messages_sync is split under the frame budget`() {
        val arr = org.json.JSONArray()
        repeat(40) { arr.put(JSONObject().put("id", "m$it").put("content", "x".repeat(20_000))) }
        val parts = WebE2E.splitList(JSONObject().put("t", "messages_sync").put("contactPublicKey", "K").put("messages", arr), "messages", 100_000)
        assertTrue(parts.size > 1)
        assertEquals(40, parts.sumOf { it.getJSONArray("messages").length() })
        parts.forEach { assertTrue(it.toString().length <= 100_000); assertEquals("K", it.getString("contactPublicKey")) }
    }

    /** When the repo layout is present, the embedded copy above must equal the spec's file. */
    @Test
    fun `vectors file matches the embedded copy`() {
        val f = listOf("../../ServerPatches/websession_e2e/test/vectors.json", "../ServerPatches/websession_e2e/test/vectors.json")
            .map { File(it) }.firstOrNull { it.isFile } ?: return
        val v = JSONObject(f.readText())
        val i = v.getJSONObject("inputs"); val o = v.getJSONObject("outputs")
        assertEquals(browserPriv, i.getString("browser_x25519_priv"))
        assertEquals(phonePriv, i.getString("phone_x25519_priv"))
        assertEquals(token, i.getString("token")); assertEquals(sid, i.getString("sid"))
        assertEquals(qrString, o.getString("qr_string"))
        assertEquals(th, o.getString("transcript_hash_th"))
        assertEquals(kB2p, o.getString("key_b2p")); assertEquals(kP2b, o.getString("key_p2b"))
        assertEquals(confirmB64, o.getString("confirm_b64")); assertEquals(sas, o.getString("sas"))
        assertEquals(frameCt, o.getJSONObject("frame_p2b_seq1").getString("ct"))
        assertEquals(framePlaintext, o.getString("frame_plaintext_utf8"))
    }
}
