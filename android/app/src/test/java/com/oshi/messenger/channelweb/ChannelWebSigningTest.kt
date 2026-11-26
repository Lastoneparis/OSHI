package com.oshi.messenger.channelweb

import com.oshi.messenger.network.v2.V2ChannelWebClient
import com.oshi.messenger.network.v2.V2Signer
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * __CHANNEL_WEB_2026_11_26__ ServerVPS/v2/PUBLIC_CHANNEL_WEB_SPEC.md §10 signing vectors, and
 * the client's request shape (path, hashed bytes, headers) against them.
 */
class ChannelWebSigningTest {

    companion object {
        val SEED: ByteArray = ByteArray(32) { it.toByte() }
        const val PUB = "A6EHv/POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg="
        const val USER = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc="
        const val POST_ID = "7F3C2A10-1B2C-4D5E-8F90-A1B2C3D4E5F6"
        const val PUBLISH_BODY =
            "{\"text\":\"**Weekly position** — long BTC, see https://example.org/x\",\"media\":[\"3f2a9c0d4e5b6a7182930a1b2c3d4e5f\"],\"createdAt\":1795708790000}"

        val key = Ed25519PrivateKeyParameters(SEED, 0)

        fun sign(canonical: String): String {
            val s = Ed25519Signer()
            s.init(true, key)
            val m = canonical.toByteArray(Charsets.UTF_8)
            s.update(m, 0, m.size)
            return Base64.getEncoder().encodeToString(s.generateSignature())
        }

        /** The same header set V2Signer.sign(withUserHeader = true) produces, with a fixed clock. */
        fun signer(ts: () -> String) = V2ChannelWebClient.RequestSigner { method, path, body ->
            val t = ts()
            mapOf(
                "x-oshi-signature" to sign(V2Signer.canonicalString(method, path, body, t)),
                "x-oshi-timestamp" to t,
                "x-oshi-signing-pubkey" to PUB,
                "x-oshi-user" to USER,
            )
        }
    }

    @Test fun publicKeyMatchesTheSpec() {
        assertEquals(PUB, Base64.getEncoder().encodeToString(key.generatePublicKey().encoded))
    }

    @Test fun publishVector() {
        val body = V2ChannelWebClient.buildPublishBody(
            "**Weekly position** — long BTC, see https://example.org/x",
            listOf("3f2a9c0d4e5b6a7182930a1b2c3d4e5f"), 1795708790000L,
        )
        assertEquals(PUBLISH_BODY, body)
        val bytes = body.toByteArray(Charsets.UTF_8)
        assertEquals("f19a8868d211b8769176a033d27ae6656c0f0a0fca56380915b70393560169e4", V2Signer.sha256Hex(bytes))
        val canonical = V2Signer.canonicalString("PUT", "/v2/c/swiss_quant/posts/$POST_ID", bytes, "1795708800001")
        assertEquals(
            "PUT\n/v2/c/swiss_quant/posts/$POST_ID\nf19a8868d211b8769176a033d27ae6656c0f0a0fca56380915b70393560169e4\n1795708800001",
            canonical,
        )
        assertEquals("HQW0P7TPX+Z81sUL1kU69gJ/ihOu6z1gx4OLyQZKTEJkZfaGdDXEFkB8LGNHC23nfepHUk7T+iBLY31Oa0RVBA==", sign(canonical))
    }

    @Test fun enableVector() {
        val body = V2ChannelWebClient.buildEnableBody("Swiss Quant Notes", null, false)
        assertEquals("{\"kind\":\"channel\",\"title\":\"Swiss Quant Notes\",\"searchIndexing\":false}", body)
        val bytes = body.toByteArray(Charsets.UTF_8)
        assertEquals("29e23ba1db701ad6da1c70432e0f7907d32d66d8bf80c8f6dadcc3185472757a", V2Signer.sha256Hex(bytes))
        assertEquals(
            "TwhiDsrdmc5I/PCFEiZDQ4eIt4GtJFRx4Kh3PDJobEYRyId1asVN+4cXiTov6AlyJ7ORtDJIpmh/DmqPlhC8CA==",
            sign(V2Signer.canonicalString("PUT", "/v2/c/swiss_quant", bytes, "1795708800000")),
        )
    }

    @Test fun deleteAndAdminVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", V2Signer.sha256Hex(ByteArray(0)))
        assertEquals(
            "SiCYwd57OUWnVFabd8ZEGJzIcN/J+sm57joNAg2H9Y2zZJ/DXSXvCHgiLDeW2y7NpQGNeK9BRiQ4E8pWkyr1Bw==",
            sign(V2Signer.canonicalString("DELETE", "/v2/c/swiss_quant/posts/$POST_ID", ByteArray(0), "1795708800002")),
        )
        assertEquals(
            "FksWcYVBXrETLgzFK9KGtdZZye/VE2JT+nPFZE3ZFrkbZ9OlNcBGIsXbpa/0lyQAgARiwpDS2zZzV1R2+0TwAw==",
            sign(V2Signer.canonicalString("GET", "/v2/c/swiss_quant/admin", ByteArray(0), "1795708800003")),
        )
    }

    @Test fun jsonStringEscapesOnlyWhatJsonNeeds() {
        assertEquals("\"a\\\"b\\\\c\\nd/é—\\u0001\"", V2ChannelWebClient.jsonString("a\"b\\c\nd/é—\u0001"))
        // A text with every escape round-trips through a real parser.
        val t = "quote \" back \\ tab \t cr \r nl \n ls   emoji 🎉"
        val parsed = org.json.JSONObject(V2ChannelWebClient.buildPublishBody(t, emptyList(), 1L)).getString("text")
        assertEquals(t, parsed)
    }

    /** The client sends exactly the vector request: path, bytes, headers. */
    @Test fun clientSendsTheVectorRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"n":7,"url":"https://oshi-messenger.com/c/swiss_quant/7","created":true,"changed":true,"editedAt":null}"""))
            server.enqueue(MockResponse().setBody("""{"ok":true,"n":7}"""))
            server.enqueue(MockResponse().setBody("""{"ok":true,"handle":"swiss_quant","enabled":true,"posts":[{"postId":"$POST_ID","n":7,"createdAt":1,"editedAt":null}]}"""))
            server.start()
            val stamps = ArrayDeque(listOf("1795708800001", "1795708800002", "1795708800003"))
            val client = V2ChannelWebClient(OkHttpClient(), { server.url("").toString().trimEnd('/') }, signer { stamps.removeFirst() })

            val r = client.publish("swiss_quant", POST_ID, PUBLISH_BODY)
            assertTrue(r is V2ChannelWebClient.Outcome.Ok)
            assertEquals(7, (r as V2ChannelWebClient.Outcome.Ok).json.getInt("n"))
            val req = server.takeRequest()
            assertEquals("PUT", req.method)
            assertEquals("/v2/c/swiss_quant/posts/$POST_ID", req.path)
            assertEquals(PUBLISH_BODY, req.body.readUtf8())
            assertEquals("HQW0P7TPX+Z81sUL1kU69gJ/ihOu6z1gx4OLyQZKTEJkZfaGdDXEFkB8LGNHC23nfepHUk7T+iBLY31Oa0RVBA==", req.getHeader("x-oshi-signature"))
            assertEquals("1795708800001", req.getHeader("x-oshi-timestamp"))
            assertEquals(PUB, req.getHeader("x-oshi-signing-pubkey"))
            assertEquals(USER, req.getHeader("x-oshi-user"))

            client.deletePost("swiss_quant", POST_ID)
            val del = server.takeRequest()
            assertEquals("DELETE", del.method)
            assertEquals(0L, del.bodySize)
            assertEquals("SiCYwd57OUWnVFabd8ZEGJzIcN/J+sm57joNAg2H9Y2zZJ/DXSXvCHgiLDeW2y7NpQGNeK9BRiQ4E8pWkyr1Bw==", del.getHeader("x-oshi-signature"))

            val st = client.adminStatus("swiss_quant") as V2ChannelWebClient.Outcome.Ok
            val get = server.takeRequest()
            assertEquals("GET", get.method)
            assertEquals("/v2/c/swiss_quant/admin", get.path)
            assertEquals("FksWcYVBXrETLgzFK9KGtdZZye/VE2JT+nPFZE3ZFrkbZ9OlNcBGIsXbpa/0lyQAgARiwpDS2zZzV1R2+0TwAw==", get.getHeader("x-oshi-signature"))
            assertEquals(listOf(POST_ID to 7), V2ChannelWebClient.parseAdmin(st.json)!!.posts)
        }
    }

    @Test fun classifyFollowsTheSpecTable() {
        fun c(s: Int, e: String) = V2ChannelWebClient.classify(s, """{"error":"$e","mediaId":"ab"}""")
        assertTrue(c(200, "") is V2ChannelWebClient.Outcome.Ok)
        assertEquals(V2ChannelWebClient.Outcome.UnknownMedia("ab"), c(400, "unknown-media"))
        assertTrue(c(400, "empty-post") is V2ChannelWebClient.Outcome.Rejected)
        assertTrue(c(401, "unauthorized") is V2ChannelWebClient.Outcome.Retry)
        assertTrue(c(403, "signer-not-a-controller") is V2ChannelWebClient.Outcome.Retry)
        assertTrue(c(404, "unknown-post") is V2ChannelWebClient.Outcome.NotFound)
        assertTrue(c(409, "web-preview-off") === V2ChannelWebClient.Outcome.PreviewOff)
        assertTrue(c(413, "body-too-large") is V2ChannelWebClient.Outcome.Rejected)
        assertTrue(c(415, "unsupported-type") is V2ChannelWebClient.Outcome.Rejected)
        assertTrue(c(429, "rate-limited") is V2ChannelWebClient.Outcome.Retry)
        assertTrue(c(503, "") is V2ChannelWebClient.Outcome.Retry)
        assertTrue(c(507, "storage-full") is V2ChannelWebClient.Outcome.Retry)
        assertTrue(V2ChannelWebClient.classify(502, "<html>") is V2ChannelWebClient.Outcome.Retry)
    }
}
