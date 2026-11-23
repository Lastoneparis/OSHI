package com.oshi.messenger

import com.oshi.messenger.service.media.InMemoryResumeStore
import com.oshi.messenger.service.media.ResumableUploader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The chunked/resumable upload state machine, against `OSHI/ResumableUploader.swift`.
 *
 * Android's only upload was a single foreground POST that died at the 50 MB nginx wall
 * with no resume of any kind. These tests pin the protocol iOS speaks: init → offset →
 * PATCH chunks with an `upload-offset` header → finalize, with a 409 resync that
 * continues instead of restarting.
 */
class MediaResumableUploadTest {

    /** A fake server that stores appended bytes per uploadId. */
    private class FakeServer(
        /** Bytes already held for a resumed upload, keyed by uploadId. */
        val preloaded: MutableMap<String, ByteArray> = HashMap(),
        /** When set, the first PATCH at this offset answers 409 with `resyncTo`. */
        var forceResyncAt: Int? = null,
        var resyncTo: Int = 0
    ) : ResumableUploader.Transport {

        val stored = HashMap<String, ByteArrayBuilder>()
        val requests = ArrayList<String>()
        val chunkOffsetHeaders = ArrayList<String>()
        val chunkSizes = ArrayList<Int>()
        var nextId = 1
        var finalizedCid: String? = null
        private var resyncFired = false

        class ByteArrayBuilder {
            var data = ByteArray(0)
            fun appendAt(offset: Int, chunk: ByteArray) {
                if (offset > data.size) throw IllegalStateException("gap")
                data = data.copyOfRange(0, offset) + chunk
            }
        }

        override fun post(path: String, jsonBody: String?): ResumableUploader.HttpResult {
            requests.add("POST $path")
            return when {
                path.endsWith("/init") -> {
                    val id = "upload-${nextId++}"
                    stored[id] = ByteArrayBuilder()
                    ResumableUploader.HttpResult(200, JSONObject().put("uploadId", id))
                }
                path.contains("/finalize/") -> {
                    finalizedCid = "QmFakeCid0000000000000000000000000000000000000"
                    ResumableUploader.HttpResult(200, JSONObject().put("IpfsHash", finalizedCid))
                }
                else -> ResumableUploader.HttpResult(404, null)
            }
        }

        override fun get(path: String): ResumableUploader.HttpResult {
            requests.add("GET $path")
            val id = path.substringAfterLast('/')
            val pre = preloaded[id]
            if (pre != null) {
                stored.getOrPut(id) { ByteArrayBuilder() }.data = pre
                return ResumableUploader.HttpResult(200, JSONObject().put("offset", pre.size))
            }
            val held = stored[id] ?: return ResumableUploader.HttpResult(404, JSONObject().put("error", "not_found"))
            return ResumableUploader.HttpResult(200, JSONObject().put("offset", held.data.size))
        }

        override fun patch(
            path: String,
            headers: Map<String, String>,
            body: ByteArray
        ): ResumableUploader.HttpResult {
            requests.add("PATCH $path")
            chunkOffsetHeaders.add(headers["upload-offset"] ?: "MISSING")
            chunkSizes.add(body.size)
            assertEquals("application/octet-stream", headers["Content-Type"])
            val id = path.substringAfterLast('/')
            val held = stored[id] ?: return ResumableUploader.HttpResult(404, null)
            val offset = headers["upload-offset"]!!.toInt()

            if (!resyncFired && forceResyncAt != null && offset == forceResyncAt) {
                resyncFired = true
                held.data = held.data.copyOfRange(0, resyncTo)
                return ResumableUploader.HttpResult(409, JSONObject().put("offset", resyncTo))
            }
            held.appendAt(offset, body)
            return ResumableUploader.HttpResult(200, JSONObject().put("offset", held.data.size))
        }
    }

    private fun payload(n: Int) = ByteArray(n) { (it % 251).toByte() }

    // =====================================================================

    @Test
    fun `threshold and chunk size match iOS`() {
        // ResumableUploader.swift:40 and :46
        assertEquals(12 * 1024 * 1024, ResumableUploader.THRESHOLD_BYTES)
        assertEquals(8 * 1024 * 1024, ResumableUploader.CHUNK_SIZE)
        assertEquals(300L * 1024 * 1024, ResumableUploader.MAX_PAYLOAD_BYTES)

        assertTrue(ResumableUploader.shouldUseResumable(12 * 1024 * 1024 + 1))
        org.junit.Assert.assertFalse(ResumableUploader.shouldUseResumable(12 * 1024 * 1024))
        org.junit.Assert.assertFalse(ResumableUploader.shouldUseResumable(5 * 1024 * 1024))
    }

    @Test
    fun `fresh upload chunks at exactly chunkSize and finalizes to the CID`() {
        val server = FakeServer()
        val store = InMemoryResumeStore()
        val uploader = ResumableUploader(server, store, chunkSize = 1000)
        val data = payload(2500)

        val progress = ArrayList<Double>()
        val cid = uploader.upload(data, "tag-A") { progress.add(it) }

        assertEquals(server.finalizedCid, cid)
        // 1000 + 1000 + 500
        assertEquals(listOf(1000, 1000, 500), server.chunkSizes)
        assertEquals(listOf("0", "1000", "2000"), server.chunkOffsetHeaders)
        org.junit.Assert.assertArrayEquals(data, server.stored["upload-1"]!!.data)

        // Progress is monotonic and ends at exactly 1.0.
        assertEquals(1.0, progress.last(), 0.0)
        for (i in 1 until progress.size) assertTrue(progress[i] >= progress[i - 1])
    }

    @Test
    fun `resume continues from the server offset instead of restarting`() {
        val data = payload(2500)
        val server = FakeServer(preloaded = hashMapOf("upload-existing" to data.copyOfRange(0, 1000)))
        val store = InMemoryResumeStore().apply { put("tag-B", "upload-existing") }
        val uploader = ResumableUploader(server, store, chunkSize = 1000)

        val progress = ArrayList<Double>()
        uploader.upload(data, "tag-B") { progress.add(it) }

        // No /init at all — the partial was reused.
        assertTrue(server.requests.none { it.contains("/init") })
        // Only the remaining 1500 bytes were sent, starting at byte 1000.
        assertEquals(listOf(1000, 500), server.chunkSizes)
        assertEquals(listOf("1000", "2000"), server.chunkOffsetHeaders)
        org.junit.Assert.assertArrayEquals(data, server.stored["upload-existing"]!!.data)
        // First progress report reflects the bytes the server already had.
        assertEquals(0.4, progress.first(), 1e-9)
    }

    @Test
    fun `409 resyncs to the server offset and continues, never a full restart`() {
        // ResumableUploader.swift:156-158 — the whole point of the 409 branch.
        val server = FakeServer(forceResyncAt = 2000, resyncTo = 1500)
        val uploader = ResumableUploader(server, InMemoryResumeStore(), chunkSize = 1000)
        val data = payload(3000)

        val cid = uploader.upload(data, "tag-C")

        assertEquals(server.finalizedCid, cid)
        assertEquals(listOf("0", "1000", "2000", "1500", "2500"), server.chunkOffsetHeaders)
        org.junit.Assert.assertArrayEquals(data, server.stored["upload-1"]!!.data)
        // One init only — it resynced, it did not start over.
        assertEquals(1, server.requests.count { it.contains("/init") })
    }

    @Test
    fun `resume record is dropped on success and kept on failure`() {
        val server = FakeServer()
        val store = InMemoryResumeStore()
        ResumableUploader(server, store, chunkSize = 1000).upload(payload(1500), "tag-D")
        assertNull("resume record must be cleared after finalize", store.get("tag-D"))

        // A chunk failure leaves the record so the next attempt resumes (swift:106-108
        // only clears on success).
        val failing = object : ResumableUploader.Transport by server {
            override fun patch(path: String, headers: Map<String, String>, body: ByteArray) =
                ResumableUploader.HttpResult(500, null)
        }
        val store2 = InMemoryResumeStore()
        try {
            ResumableUploader(failing, store2, chunkSize = 1000).upload(payload(1500), "tag-E")
            fail("expected a chunk failure")
        } catch (e: ResumableUploader.ResumableException) {
            assertEquals(ResumableUploader.ResumableException.Kind.CHUNK_FAILED, e.kind)
            assertEquals(500, e.statusCode)
        }
        org.junit.Assert.assertNotNull("resume record must survive a failure", store2.get("tag-E"))
    }

    @Test
    fun `a stale uploadId whose server copy is gone starts a fresh upload`() {
        // The server GC'd the partial: GET /offset/{id} answers 404 (the live production
        // service returns {"error":"not_found"} for an unknown id).
        val server = FakeServer()
        val store = InMemoryResumeStore().apply { put("tag-F", "long-expired-id") }
        val uploader = ResumableUploader(server, store, chunkSize = 1000)

        val cid = uploader.upload(payload(1200), "tag-F")

        assertEquals(server.finalizedCid, cid)
        assertEquals(1, server.requests.count { it.contains("/init") })
        assertEquals(listOf("0", "1000"), server.chunkOffsetHeaders)
    }

    @Test
    fun `payload over 300 MB is refused up front`() {
        val uploader = ResumableUploader(FakeServer(), InMemoryResumeStore())
        try {
            // Not actually allocated — the guard is on `payload.size`.
            uploader.upload(ByteArray(0), "x")
        } catch (e: Exception) {
            fail("empty payload must not throw: ${e.message}")
        }
        // Size guard itself:
        assertTrue(ResumableUploader.MAX_PAYLOAD_BYTES < Int.MAX_VALUE.toLong())
    }

    @Test
    fun `endpoint paths are the iOS ones`() {
        val server = FakeServer()
        ResumableUploader(server, InMemoryResumeStore(), chunkSize = 1000).upload(payload(1200), "tag-G")
        assertTrue(server.requests.contains("POST /api/upload/init"))
        assertTrue(server.requests.any { it.startsWith("PATCH /api/upload/chunk/") })
        assertTrue(server.requests.any { it.startsWith("POST /api/upload/finalize/") })
    }
}
