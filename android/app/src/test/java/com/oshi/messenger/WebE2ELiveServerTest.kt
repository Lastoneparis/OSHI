package com.oshi.messenger

import com.oshi.messenger.network.v2.OSHICryptoV2
import com.oshi.messenger.network.v2.V2Signer
import com.oshi.messenger.network.web.WebE2E
import com.oshi.messenger.network.web.WebSessionManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * __WEBSESSION_E2E_2026_09_23__ The Android phone side ([WebE2E] + the signed `mobile_auth` builder)
 * against the REAL patched `web_session_server.js` (express + ws from `test/node_modules`), with a
 * browser emulated here from the spec (independently of [WebE2E.Channel]). Opt-in, see [LiveHarness].
 */
class WebE2ELiveServerTest {

    private val ok = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    inner class Sock(url: String) {
        val inbox = LinkedBlockingQueue<JSONObject>()
        val ws: WebSocket = ok.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { inbox.add(JSONObject(text)) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { inbox.add(JSONObject().put("type", "__failure").put("e", t.toString())) }
        })
        fun send(o: JSONObject) = check(ws.send(o.toString()))
        fun next(type: String, ms: Long = 5000): JSONObject {
            val deadline = System.currentTimeMillis() + ms
            while (true) {
                val left = deadline - System.currentTimeMillis()
                check(left > 0) { "timeout waiting for $type" }
                val j = inbox.poll(left, TimeUnit.MILLISECONDS) ?: continue
                if (j.optString("type") == type) return j
            }
        }
    }

    /** Phone identity as the fixture auth module expects it: userKey == Ed25519 signing key. */
    private val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val phonePub = Base64.getEncoder().encodeToString(com.oshi.messenger.network.v2.devicemailbox.DeviceAuth.ed25519Public(seed))
    private fun sign(b: ByteArray) = com.oshi.messenger.network.v2.devicemailbox.DeviceAuth.ed25519Sign(seed, b)

    private fun signedPost(path: String, body: JSONObject): Pair<Int, JSONObject> {
        val bytes = body.toString().toByteArray()
        val ts = System.currentTimeMillis().toString()
        val h = mapOf(
            "x-oshi-signature" to Base64.getEncoder().encodeToString(sign(V2Signer.canonicalString("POST", path, bytes, ts).toByteArray())),
            "x-oshi-timestamp" to ts, "x-oshi-signing-pubkey" to phonePub,
        )
        val r = LiveHarness.post(port, path, bytes, h)
        return r.statusCode() to runCatching { JSONObject(r.body()) }.getOrDefault(JSONObject())
    }

    private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    @Test
    fun `v2 pairing, confirm, SAS, signed wsv2 auth, frames both ways`() {
        // --- browser: token + its X25519 key + the 16-byte secret, all in the QR only
        val g = JSONObject(LiveHarness.post(port, "/api/web/qr-generate", JSONObject().put("userAgent", "jvm").put("wsv", 2).toString().toByteArray()).body())
        val token = g.getString("token")
        val browser = OSHICryptoV2.generateX25519()
        val s = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val b = Sock("ws://127.0.0.1:$port/ws")
        b.send(JSONObject().put("type", "subscribe_qr").put("token", token))
        Thread.sleep(100)
        val qr = "oshi://web-auth?token=$token&v=2&k=${b64url(browser.pub)}&s=${b64url(s)}"

        // --- phone (the code under test)
        val pairing = WebE2E.pair(WebE2E.parseQr(qr) as WebE2E.Qr.V2)
        val (st, v) = signedPost("/api/web/qr-validate", JSONObject().put("qrToken", token).put("publicKey", phonePub)
            .put("deviceName", "Android").put("wsv", 2).put("phoneKey", pairing.phoneKeyB64).put("confirm", pairing.confirmB64))
        assertEquals(v.toString(), 200, st)
        assertEquals(2, v.getInt("wsv"))
        val sid = v.getString("sessionId")

        // --- browser: recompute the confirm tag with ITS private key, compare, same SAS
        val qv = b.next("qr_validated")
        assertEquals(2, qv.getInt("wsv"))
        val phoneKey = Base64.getDecoder().decode(qv.getString("phoneKey"))
        val bk = WebE2E.deriveKeys(OSHICryptoV2.dh(browser.priv, phoneKey), s, token, browser.pub, phoneKey)
        assertArrayEquals(bk.confirm, Base64.getDecoder().decode(qv.getString("confirm")))
        assertEquals(pairing.sas, bk.sas)
        b.send(JSONObject().put("type", "auth").put("sessionId", sid))
        assertEquals(2, b.next("auth_success").getInt("wsv"))

        // --- phone socket: signed mobile_auth + wsv 2
        val m = Sock("ws://127.0.0.1:$port/ws")
        val ts = System.currentTimeMillis().toString()
        m.send(WebSessionManager.buildSignedAuthEnvelope(phonePub, phonePub, ts, "n" + ts, wsv = 2) { sign(it) })
        assertEquals(2, m.next("mobile_auth_success").getInt("wsv"))
        assertEquals(sid, m.next("e2e_peers").getJSONArray("sids").getString(0))

        // --- phone → browser (p2b), opened with the BROWSER's derivation of k_p2b
        val ch = WebE2E.Channel(sid, pairing.keys.p2b, pairing.keys.b2p)
        m.send(ch.seal(WebE2E.toInner(JSONObject().put("type", "sync_conversations").put("conversations", org.json.JSONArray().put(JSONObject().put("publicKey", "QUJD").put("lastMessage", "secret-marker"))))))
        assertEquals("relayed", m.next("e2e_status").getString("state"))
        val f = b.next("e2e")
        val epRaw = Base64.getDecoder().decode(f.getString("ep"))
        val pt = OSHICryptoV2.aesGcmOpen(bk.p2b, epRaw + OSHICryptoV2.u32be(f.getLong("seq")), Base64.getDecoder().decode(f.getString("ct")),
            WebE2E.aad(sid, "p2b", f.getString("ep"), f.getLong("seq")))
        val inner = JSONObject(String(pt))
        assertEquals("conversations_sync", inner.getString("t"))
        assertEquals("secret-marker", inner.getJSONArray("conversations").getJSONObject(0).getString("lastMessage"))

        // --- browser → phone (b2p), opened by the phone channel
        val bep = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val bepS = Base64.getEncoder().encodeToString(bep)
        val binner = JSONObject().put("t", "send_message").put("recipientPublicKey", "QUJD").put("content", "typed").put("clientMsgId", "c1").put("_ts", System.currentTimeMillis())
        val bct = OSHICryptoV2.aesGcmSeal(bk.b2p, bep + OSHICryptoV2.u32be(1), binner.toString().toByteArray(), WebE2E.aad(sid, "b2p", bepS, 1))
        b.send(JSONObject().put("type", "e2e").put("v", 2).put("sid", sid).put("dir", "b2p").put("ep", bepS).put("seq", 1).put("ct", Base64.getEncoder().encodeToString(bct)))
        val got = ch.open(m.next("e2e"))
        assertEquals("send_message", got.getString("t")); assertEquals("typed", got.getString("content"))

        // --- a v1 plaintext type from a v2 phone socket is dropped by the server, never relayed
        m.send(JSONObject().put("type", "sync_conversations").put("conversations", org.json.JSONArray()))
        Thread.sleep(300)
        assertTrue(b.inbox.none { it.optString("type") == "sync_conversations" })

        // --- a media stream (192 KiB chunks) reassembles byte for byte on the browser
        val media = ByteArray(WebE2E.MEDIA_CHUNK + 1000) { (it * 7).toByte() }
        val out = java.io.ByteArrayOutputStream()
        for (c in WebE2E.mediaChunks("m1", "private", "image/jpeg", "a.jpg", media)) {
            m.send(ch.seal(c))
            val fr = b.next("e2e")
            val p = OSHICryptoV2.aesGcmOpen(bk.p2b, Base64.getDecoder().decode(fr.getString("ep")) + OSHICryptoV2.u32be(fr.getLong("seq")),
                Base64.getDecoder().decode(fr.getString("ct")), WebE2E.aad(sid, "p2b", fr.getString("ep"), fr.getLong("seq")))
            out.write(Base64.getDecoder().decode(JSONObject(String(p)).getString("data")))
        }
        assertArrayEquals(media, out.toByteArray())
        b.ws.close(1000, null); m.ws.close(1000, null)
    }

    @Test
    fun `v1 pairing refused with 426 once the server flag is off`() {
        val off = LiveHarness.freePort()
        val srv = startWs(off, v1 = false)
        try {
            val g = JSONObject(LiveHarness.post(off, "/api/web/qr-generate", "{}".toByteArray()).body())
            val body = JSONObject().put("qrToken", g.getString("token")).put("publicKey", phonePub).put("deviceName", "Android")
            val bytes = body.toString().toByteArray(); val ts = System.currentTimeMillis().toString()
            val r = LiveHarness.post(off, "/api/web/qr-validate", bytes, mapOf(
                "x-oshi-signature" to Base64.getEncoder().encodeToString(sign(V2Signer.canonicalString("POST", "/api/web/qr-validate", bytes, ts).toByteArray())),
                "x-oshi-timestamp" to ts, "x-oshi-signing-pubkey" to phonePub))
            assertEquals(426, r.statusCode())   // → the app shows "Reload the OSHI Web page and scan again"
        } finally { srv.stop() }
    }

    companion object {
        private var server: LiveHarness.Server? = null
        private var port = 0

        fun startWs(p: Int, v1: Boolean): LiveHarness.Server {
            val root = File(LiveHarness.repoRoot, "ServerPatches/websession_e2e")
            return LiveHarness.start(File(root, "www-api/web_session_server.js"), mapOf(
                "WEB_SESSION_PORT" to p.toString(),
                "NODE_PATH" to File(root, "test/node_modules").absolutePath,
                "OSHI_AUTH_MODULE" to File(root, "test/fixtures/oshi_auth.js").absolutePath,
                "OSHI_AUTH_MODE" to "dual",
                "OSHI_PAIRING_STATS_FILE" to File.createTempFile("ws2-pairing", ".json").absolutePath,
                "OSHI_PUSH_PORT" to "1", "OSHI_QUEUE_PORT" to "1",
                "OSHI_WEBSESSION_V1" to if (v1) "on" else "off",
            ), "/health", p)
        }

        @BeforeClass @JvmStatic
        fun up() {
            LiveHarness.assumeEnabled()
            port = LiveHarness.freePort()
            server = startWs(port, v1 = true)
        }

        @AfterClass @JvmStatic
        fun down() { server?.stop() }
    }
}
