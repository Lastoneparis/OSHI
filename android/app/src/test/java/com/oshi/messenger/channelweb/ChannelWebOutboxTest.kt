package com.oshi.messenger.channelweb

import com.oshi.messenger.network.v2.V2ChannelWebClient.Outcome
import com.oshi.messenger.service.channelweb.ChannelWebOutbox
import com.oshi.messenger.service.channelweb.ChannelWebOutbox.Image
import com.oshi.messenger.service.channelweb.ChannelWebOutbox.Kind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** __CHANNEL_WEB_2026_11_26__ durable outbox: queue, retry/backoff, postId→n map, restart. */
class ChannelWebOutboxTest {

    private var clock = 1_800_000_000_000L
    private val H = "swiss_quant"
    private val G = "GROUP-1"

    /** Scripted server: each call pops the next answer for its route (default: success). */
    private inner class FakeApi : ChannelWebOutbox.Api {
        val calls = ArrayList<String>()
        val script = HashMap<String, ArrayDeque<Outcome>>()
        var nextN = 1
        var nextMedia = 0
        val published = HashMap<String, Pair<Int, String>>()   // postId -> (n, body)
        fun answer(route: String, vararg o: Outcome) { script.getOrPut(route) { ArrayDeque() }.addAll(o) }
        private fun pop(route: String): Outcome? = script[route]?.removeFirstOrNull()

        override fun enable(handle: String, body: String): Outcome { calls += "enable $body"; return pop("enable") ?: ok("{\"ok\":true}") }
        override fun disable(handle: String): Outcome { calls += "disable $handle"; return pop("disable") ?: ok("{\"ok\":true}") }
        override fun upload(handle: String, image: ByteArray): Outcome {
            calls += "upload ${String(image)}"
            return pop("upload") ?: ok("{\"ok\":true,\"mediaId\":\"m${nextMedia++}\"}")
        }
        override fun publish(handle: String, postId: String, body: String): Outcome {
            calls += "publish $postId $body"
            pop("publish")?.let { return it }
            val n = published[postId]?.first ?: nextN++
            published[postId] = n to body
            return ok("{\"ok\":true,\"n\":$n}")
        }
        override fun deletePost(handle: String, postId: String): Outcome { calls += "delete $postId"; return pop("delete") ?: ok("{\"ok\":true}") }
    }

    private fun ok(s: String) = Outcome.Ok(JSONObject(s))

    private class Files : ChannelWebOutbox.ImageFiles {
        val files = HashMap<String, ByteArray>()
        override fun read(key: String) = files[key]
        override fun delete(key: String) { files.remove(key) }
    }

    private var saved: String? = null
    private fun outbox(api: FakeApi, files: Files = Files()) =
        ChannelWebOutbox(api, files, { saved = it }, { clock })

    @Test fun publishStoresN_andAnEditKeepsItAndTheImage() {
        val api = FakeApi(); val files = Files(); files.files["k1"] = "IMG".toByteArray()
        val o = outbox(api, files)
        o.enqueuePublish(G, H, "p1", "hello", clock, listOf(Image("k1", null)))
        assertEquals(1, o.runDue())
        assertEquals(listOf("upload IMG", "publish p1 {\"text\":\"hello\",\"media\":[\"m0\"],\"createdAt\":$clock}"), api.calls)
        assertEquals(1, o.nFor(H, "p1"))
        assertEquals(listOf("m0"), o.entry(H, "p1")!!.mediaIds)
        assertTrue("image file deleted once published", files.files.isEmpty())
        assertTrue(o.pending().isEmpty())

        // Edit: same postId, the known media id, no upload.
        api.calls.clear()
        o.enqueuePublish(G, H, "p1", "hello edited", clock, o.entry(H, "p1")!!.mediaIds.map { Image(null, it) })
        o.runDue()
        assertEquals(listOf("publish p1 {\"text\":\"hello edited\",\"media\":[\"m0\"],\"createdAt\":$clock}"), api.calls)
        assertEquals(1, o.nFor(H, "p1"))
        assertEquals("p1", o.postIdFor(H, 1))
    }

    @Test fun networkErrorsBackOffThenSucceed_andTheQueueSurvivesARestart() {
        val api = FakeApi()
        api.answer("publish", Outcome.Retry(0, "IOException"), Outcome.Retry(503, null))
        val o = outbox(api)
        o.enqueuePublish(G, H, "p1", "hi", clock, emptyList())
        o.runDue()
        val j1 = o.pending().single()
        assertEquals(1, j1.attempts)
        assertEquals(clock + ChannelWebOutbox.backoffMs(1), j1.nextAt)
        assertEquals(0, o.runDue())                         // not due yet: nothing sent
        clock += ChannelWebOutbox.backoffMs(1)

        // "Restart": a new outbox loaded from what the old one persisted.
        val o2 = outbox(api).apply { load(saved) }
        assertEquals(1, o2.pending().size)
        o2.runDue()
        assertEquals(2, o2.pending().single().attempts)
        assertEquals(clock + ChannelWebOutbox.backoffMs(2), o2.nextDueAt())
        clock += ChannelWebOutbox.backoffMs(2)
        o2.runDue()
        assertTrue(o2.pending().isEmpty())
        assertEquals(1, o2.nFor(H, "p1"))
        assertEquals(3, api.calls.count { it.startsWith("publish p1") })
        // and the map itself survives a restart too
        assertEquals(1, outbox(api).apply { load(saved) }.nFor(H, "p1"))
    }

    @Test fun backoffGrowsAndIsCapped() {
        assertEquals(5_000L, ChannelWebOutbox.backoffMs(1))
        assertTrue(ChannelWebOutbox.backoffMs(4) > ChannelWebOutbox.backoffMs(3))
        assertEquals(6 * 3600_000L, ChannelWebOutbox.backoffMs(50))
    }

    @Test fun unknownMediaReuploadsThenPublishes() {
        val api = FakeApi(); val files = Files(); files.files["k1"] = "IMG".toByteArray()
        api.answer("publish", Outcome.UnknownMedia("m0"))
        val o = outbox(api, files)
        o.enqueuePublish(G, H, "p1", "x", clock, listOf(Image("k1", null)))
        o.runDue()
        assertEquals(listOf(Image("k1", null)), o.pending().single().images)  // id forgotten, file kept
        clock += ChannelWebOutbox.SOON_MS
        o.runDue()
        assertEquals(listOf("upload IMG", "publish p1 {\"text\":\"x\",\"media\":[\"m0\"],\"createdAt\":${clock - ChannelWebOutbox.SOON_MS}}",
            "upload IMG", "publish p1 {\"text\":\"x\",\"media\":[\"m1\"],\"createdAt\":${clock - ChannelWebOutbox.SOON_MS}}"), api.calls)
        assertEquals(listOf("m1"), o.entry(H, "p1")!!.mediaIds)
    }

    @Test fun anImageTheServerRefusesLeavesTheTextPost() {
        val api = FakeApi(); val files = Files(); files.files["k1"] = "HEIC".toByteArray()
        api.answer("upload", Outcome.Rejected(415, "unsupported-type"))
        val o = outbox(api, files)
        o.enqueuePublish(G, H, "p1", "caption", clock, listOf(Image("k1", null)))
        o.runDue()
        assertEquals("publish p1 {\"text\":\"caption\",\"media\":[],\"createdAt\":$clock}", api.calls.last())
        assertTrue(files.files.isEmpty())
    }

    @Test fun anImageOnlyPostWhoseImageIsRefusedPublishesNothing() {
        val api = FakeApi(); val files = Files(); files.files["k1"] = "BAD".toByteArray()
        api.answer("upload", Outcome.Rejected(400, "jpeg-bad"))
        val o = outbox(api, files)
        o.enqueuePublish(G, H, "p1", "", clock, listOf(Image("k1", null)))
        o.runDue()
        assertTrue(api.calls.none { it.startsWith("publish") })
        assertTrue(o.pending().isEmpty())
    }

    @Test fun deleteDropsAPendingPublishAndCallsDelete() {
        val api = FakeApi()
        api.answer("publish", Outcome.Retry(0, "x"))
        val o = outbox(api)
        o.enqueuePublish(G, H, "p1", "hi", clock, emptyList())
        o.runDue()
        o.enqueueDelete(G, H, "p1")
        assertEquals(listOf(Kind.DELETE_POST), o.pending().map { it.kind })
        o.runDue()
        assertEquals("delete p1", api.calls.last())
        assertTrue(o.pending().isEmpty())
    }

    @Test fun deleteOfAPublishedPostForgetsItsNumber_404IsDone() {
        val api = FakeApi()
        val o = outbox(api)
        o.enqueuePublish(G, H, "p1", "hi", clock, emptyList()); o.runDue()
        api.answer("delete", Outcome.NotFound("unknown-post"))
        o.enqueueDelete(G, H, "p1"); o.runDue()
        assertNull(o.nFor(H, "p1"))
        assertTrue(o.pending().isEmpty())
    }

    @Test fun anEditWhileTheFirstVersionIsInFlightIsSentAfterIt() {
        val api = FakeApi()
        lateinit var o: ChannelWebOutbox
        val inner = object : ChannelWebOutbox.Api by api {
            var edited = false
            override fun publish(handle: String, postId: String, body: String): Outcome {
                if (!edited) { edited = true; o.enqueuePublish(G, H, postId, "v2", clock, emptyList()) }
                return api.publish(handle, postId, body)
            }
        }
        o = ChannelWebOutbox(inner, Files(), { saved = it }, { clock })
        o.enqueuePublish(G, H, "p1", "v1", clock, emptyList())
        o.runDue()          // v1 sent; v2 arrived meanwhile and stays queued
        assertEquals(1, o.pending().size)
        o.runDue()
        assertTrue(o.pending().isEmpty())
        assertEquals(listOf("v1", "v2"), api.calls.filter { it.startsWith("publish") }.map { JSONObject(it.substringAfter("p1 ")).getString("text") })
        assertEquals(1, o.nFor(H, "p1"))
    }

    @Test fun previewOffEnablesFirstThenRetries() {
        val api = FakeApi()
        api.answer("publish", Outcome.PreviewOff)
        val o = outbox(api)
        o.enqueuePublish(G, H, "p1", "hi", clock, emptyList())
        o.runDue()
        // The enable job went in FRONT and ran in the same pass; the post waits a moment.
        assertEquals("enable {\"kind\":\"channel\"}", api.calls[1])
        assertEquals(listOf(Kind.PUBLISH), o.pending().map { it.kind })
        clock += ChannelWebOutbox.SOON_MS
        o.runDue()
        assertEquals(1, o.nFor(H, "p1"))
    }

    @Test fun inactiveChannelPublishesNothing_rejectedIsDropped() {
        val api = FakeApi()
        val o = outbox(api)
        o.isActive = { _, _ -> false }
        o.enqueuePublish(G, H, "p1", "hi", clock, emptyList())
        o.runDue()
        assertTrue(api.calls.isEmpty())
        assertTrue(o.pending().isEmpty())

        o.isActive = { _, _ -> true }
        api.answer("publish", Outcome.Rejected(400, "text-too-long"))
        o.enqueuePublish(G, H, "p2", "hi", clock, emptyList())
        o.runDue()
        assertTrue(o.pending().isEmpty())
        assertNull(o.nFor(H, "p2"))
    }

    @Test fun aPostOlderThanTheServerWindowIsDropped() {
        val api = FakeApi()
        val o = outbox(api)
        o.enqueuePublish(G, H, "p1", "old", clock - 31L * 24 * 3600_000, emptyList())
        o.runDue()
        assertTrue(api.calls.isEmpty())
        assertTrue(o.pending().isEmpty())
    }

    @Test fun disableForgetsTheHandle_profileGoesFirst_adminMapMerges() {
        val api = FakeApi(); val files = Files(); files.files["av"] = "AV".toByteArray()
        val o = outbox(api, files)
        o.enqueuePublish(G, H, "p1", "a", clock, emptyList()); o.runDue()
        o.enqueuePublish(G, H, "p2", "b", clock, emptyList())
        o.enqueueProfile(G, H, "Title", null, true, "av", clearAvatar = false)
        assertEquals(Kind.PROFILE, o.pending().first().kind)
        o.runDue()
        assertEquals("enable {\"kind\":\"channel\",\"title\":\"Title\",\"searchIndexing\":true}", api.calls[1])
        assertEquals("upload AV", api.calls[2])
        assertEquals("enable {\"kind\":\"channel\",\"avatarMediaId\":\"m0\"}", api.calls[3])
        assertFalse(files.files.containsKey("av"))

        o.mergeAdminMap(H, listOf("p1" to 1, "p9" to 9))
        assertEquals(9, o.nFor(H, "p9"))
        assertNull("p2 is not on the server", o.nFor(H, "p2"))

        o.enqueuePublish(G, H, "p3", "c", clock, emptyList())
        o.enqueueDisable(G, H)
        assertEquals(listOf(Kind.DISABLE), o.pending().map { it.kind })
        assertNull(o.nFor(H, "p1"))
        o.runDue()
        assertEquals("disable $H", api.calls.last())
    }

    @Test fun garbageStateLoadsAsEmpty() {
        val o = outbox(FakeApi())
        o.load("not json"); o.load(null); o.load("{\"jobs\":[{\"k\":\"NOPE\"}],\"posts\":{\"h\":{\"x\":{\"n\":0}}}}")
        assertTrue(o.pending().isEmpty())
        assertNull(o.nFor("h", "x"))
    }
}
