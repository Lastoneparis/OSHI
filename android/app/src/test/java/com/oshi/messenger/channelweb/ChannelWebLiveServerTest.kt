package com.oshi.messenger.channelweb

import com.oshi.messenger.data.model.GroupSettings
import com.oshi.messenger.network.v2.V2ChannelWebClient
import com.oshi.messenger.network.v2.V2ChannelWebClient.Outcome
import com.oshi.messenger.network.v2.V2GroupHandleClient
import com.oshi.messenger.service.channelweb.ChannelWebLinks
import com.oshi.messenger.service.channelweb.ChannelWebOutbox
import com.oshi.messenger.service.channelweb.ChannelWebText
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * __CHANNEL_WEB_2026_11_26__ End to end against the REAL server (`ServerVPS/v2/v2_server.js`,
 * branch feat/group-handle-registry-server), signed with the spec §10 test identity.
 *
 * Skipped unless `OSHI_CW_BASE_URL` points at a running server that has bound that identity
 * (a prekey publish does it in production; the local launcher calls `bindIdentity`):
 *
 *     node launch.js <group-server>/ServerVPS/v2 <scratch>/webdir 18793
 *     OSHI_CW_BASE_URL=http://127.0.0.1:18793 ./gradlew :app:testDebugUnitTest --tests '*ChannelWebLiveServerTest'
 */
class ChannelWebLiveServerTest {
    private val base = System.getenv("OSHI_CW_BASE_URL")?.trimEnd('/')
    private val http = OkHttpClient()
    private var lastTs = 0L

    private val signer = V2ChannelWebClient.RequestSigner { m, p, b ->
        var t = System.currentTimeMillis(); if (t <= lastTs) t = lastTs + 1; lastTs = t
        ChannelWebSigningTest.signer { t.toString() }.sign(m, p, b)
    }

    private fun get(path: String): Pair<Int, String> =
        http.newCall(Request.Builder().url(base + path).header("User-Agent", "Mozilla/5.0 (Linux; Android 15) Chrome/131").build())
            .execute().use { it.code to it.body!!.string() }

    /** An 8×4 RGB PNG (two colour blocks). */
    private fun png(): ByteArray = java.util.Base64.getDecoder()
        .decode("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAECAIAAAA8r+mnAAAAFUlEQVR4nGM4oaEBRxo2J+CIgXoSAA3NJEGV2DmCAAAAAElFTkSuQmCC")

    @Test fun publishEditDeleteAndReadBack() {
        assumeTrue("OSHI_CW_BASE_URL not set: live server test skipped", base != null)
        val handle = "andr_" + (System.currentTimeMillis() % 100_000_000).toString(36)
        val gid = "6A0B1C2D-3E4F-4A5B-8C6D-7E8F9A0B1C2D"

        // 0. The public handle (GROUP_INFO_SETTINGS_SPEC §4), same signer.
        val reg = V2GroupHandleClient.buildRegisterBody(GroupSettings.groupRef(gid), listOf(ChannelWebSigningTest.USER),
            listOf(ChannelWebSigningTest.USER), "Android Web Test", "A test channel", 3).toByteArray()
        val regPath = "/v2/g/$handle"
        val rb = Request.Builder().url(base + regPath).put(reg.toRequestBody("application/json".toMediaType()))
        signer.sign("PUT", regPath, reg).forEach { (k, v) -> rb.header(k, v) }
        assertEquals(200, http.newCall(rb.build()).execute().use { it.code })

        val client = V2ChannelWebClient(http, { base!! }, signer)
        val files = HashMap<String, ByteArray>()
        val outbox = ChannelWebOutbox(
            api = object : ChannelWebOutbox.Api {
                override fun enable(handle: String, body: String) = client.enable(handle, body)
                override fun disable(handle: String) = client.disable(handle)
                override fun upload(handle: String, image: ByteArray) = client.upload(handle, image)
                override fun publish(handle: String, postId: String, body: String) = client.publish(handle, postId, body)
                override fun deletePost(handle: String, postId: String) = client.deletePost(handle, postId)
            },
            images = object : ChannelWebOutbox.ImageFiles {
                override fun read(key: String) = files[key]
                override fun delete(key: String) { files.remove(key) }
            },
            persist = {},
        )

        // 1. Publishing before the preview is on: 409 → the outbox enables first, then publishes.
        val createdAt = System.currentTimeMillis() - 1000
        val appText = "*Weekly position* — long BTC, _see_ __under__ https://example.org/x_y <b>&"
        val webText = ChannelWebText.forPublish(appText)
        assertEquals("**Weekly position** — long BTC, _see_ under https://example.org/x_y <b>&", webText)
        files["img1"] = png()
        outbox.enqueuePublish(gid, handle, "11111111-2222-4333-8444-555555555555", webText, createdAt, listOf(ChannelWebOutbox.Image("img1", null)))
        outbox.runDue()
        Thread.sleep(ChannelWebOutbox.SOON_MS + 100)
        outbox.runDue()
        val n1 = outbox.nFor(handle, "11111111-2222-4333-8444-555555555555")
        assertEquals(1, n1)
        assertTrue(outbox.pending().isEmpty())

        // 2. Public read-back: JSON and the page.
        val (code, body) = get("/v2/c/$handle/1")
        assertEquals(200, code)
        val post = org.json.JSONObject(body).getJSONObject("post")
        assertEquals(webText, post.getString("text"))
        assertEquals(createdAt, post.getLong("createdAt"))
        assertEquals(
            "<strong>Weekly position</strong> — long BTC, <em>see</em> under <a href=\"https://example.org/x_y\" target=\"_blank\" rel=\"noopener noreferrer nofollow ugc\">https://example.org/x_y</a> &lt;b&gt;&amp;",
            post.getString("html"),
        )
        assertEquals(1, post.getJSONArray("media").length())
        assertEquals(ChannelWebLinks.postUrl(handle, 1).replace("https://oshi-messenger.com", "https://oshi-messenger.com"), post.getString("url"))
        val (pageCode, page) = get("/c/$handle/1")
        assertEquals(200, pageCode)
        assertTrue(page.contains("<strong>Weekly position</strong>"))
        assertTrue("Open in OSHI deep link the parser understands", page.contains(ChannelWebLinks.appLink(handle, 1).replace("&", "&amp;")))
        val (embedCode, _) = get("/c/$handle/1?embed=1&dark=1")
        assertEquals(200, embedCode)

        // 3. Edit = same n; second post = n 2; feed + reader matching.
        outbox.enqueuePublish(gid, handle, "11111111-2222-4333-8444-555555555555", "edited", createdAt,
            outbox.entry(handle, "11111111-2222-4333-8444-555555555555")!!.mediaIds.map { ChannelWebOutbox.Image(null, it) })
        outbox.enqueuePublish(gid, handle, "post-two", "second", System.currentTimeMillis(), emptyList())
        outbox.runDue()
        assertEquals(1, outbox.nFor(handle, "11111111-2222-4333-8444-555555555555"))
        assertEquals(2, outbox.nFor(handle, "post-two"))
        val edited = org.json.JSONObject(get("/v2/c/$handle/1").second).getJSONObject("post")
        assertEquals("edited", edited.getString("text"))
        assertEquals(1, edited.getJSONArray("media").length())
        val feed = client.feed(handle) as Outcome.Ok
        val (posts, _) = V2ChannelWebClient.parseFeed(feed.json)
        assertEquals(listOf(2, 1), posts.map { it.n })

        // 4. Admin map (a new device / co-admin).
        val admin = V2ChannelWebClient.parseAdmin((client.adminStatus(handle) as Outcome.Ok).json)!!
        assertTrue(admin.enabled)
        assertEquals(setOf("11111111-2222-4333-8444-555555555555" to 1, "post-two" to 2), admin.posts.toSet())

        // 5. Delete for everyone → gone; number not reused.
        outbox.enqueueDelete(gid, handle, "post-two")
        outbox.runDue()
        assertNull(outbox.nFor(handle, "post-two"))
        assertEquals(404, get("/v2/c/$handle/2").first)
        outbox.enqueuePublish(gid, handle, "post-three", "third", System.currentTimeMillis(), emptyList())
        outbox.runDue()
        assertEquals(3, outbox.nFor(handle, "post-three"))

        // 6. Turning the preview off purges everything.
        assertTrue(client.disable(handle) is Outcome.Ok)
        assertEquals(404, get("/v2/c/$handle/1").first)
        assertEquals(404, get("/v2/c/$handle").first)
    }
}
