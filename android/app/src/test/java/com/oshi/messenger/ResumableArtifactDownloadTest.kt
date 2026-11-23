package com.oshi.messenger

import com.oshi.messenger.service.map.ResumableArtifactDownload
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * __MAPS_COMPLETE_2026_10_13__
 *
 * Android's road-graph download used to open a plain GET and begin with
 * `if (staged.exists()) staged.delete()`, so an interrupted transfer destroyed
 * its own progress and the catch deleted the partial too. France is a 10.1 GB
 * artifact; restarting from zero on every network drop means it never lands.
 *
 * These tests pin the three behaviours that make resume safe rather than merely
 * present: continuing from the right offset, refusing to continue a partial
 * that came from a DIFFERENT url (the road-graph path reuses one .part file for
 * the regional artifact and the country fallback), and keeping the bytes when
 * the transfer fails.
 */
class ResumableArtifactDownloadTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var staged: File
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = File(System.getProperty("java.io.tmpdir"), "rad-" + System.nanoTime())
        dir.mkdirs()
        staged = File(dir, "roadgraph.db.gz.part")
        client = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    @Test
    fun `a fresh download writes the whole body`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ABCDEFGHIJ"))
        val url = server.url("/files/roadgraph/ni-roadgraph.db.gz").toString()

        val out = ResumableArtifactDownload.fetch(client, url, staged, "ni")

        assertEquals(ResumableArtifactDownload.Outcome.Complete, out)
        assertEquals("ABCDEFGHIJ", staged.readText())
        // No Range on a fresh download -- there is nothing to continue from.
        assertEquals(null, server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `an interrupted download resumes from its offset and appends`() {
        val url = server.url("/files/roadgraph/ni-roadgraph.db.gz").toString()

        // First leg: the server closes mid-body, so the transfer fails with
        // bytes already on disk.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("ETag", "\"abc-123\"")
                .setBody("ABCDE")
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        val first = ResumableArtifactDownload.fetch(client, url, staged, "ni")
        assertTrue("an interrupted leg must not report Complete", first is ResumableArtifactDownload.Outcome.Failed)
        server.takeRequest()

        // Whatever arrived must still be there -- this is the line the old code
        // had backwards.
        assertTrue("the partial must be kept so the next attempt can resume", staged.exists())
        val have = staged.length()

        // Second leg: the server honours the range.
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $have-9/10")
                .setBody("FGHIJ".substring((have - 5).coerceAtLeast(0).toInt()))
        )
        val second = ResumableArtifactDownload.fetch(client, url, staged, "ni")
        assertEquals(ResumableArtifactDownload.Outcome.Complete, second)

        val ranged = server.takeRequest()
        assertEquals("bytes=$have-", ranged.getHeader("Range"))
        // If-Range pins the ETag we started with, so a server-side rebuild
        // cannot be stitched into the middle of the old file.
        assertEquals("\"abc-123\"", ranged.getHeader("If-Range"))
    }

    @Test
    fun `a partial from a different url is discarded rather than appended`() {
        // The road-graph path reuses ONE roadgraph.db.gz.part for the regional
        // artifact and then the country-level fallback. Appending the country's
        // bytes onto the region's would produce a corrupt file, not a short one.
        val regionUrl = server.url("/files/roadgraph/fr-idf-roadgraph.db.gz").toString()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("REGION")
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        ResumableArtifactDownload.fetch(client, regionUrl, staged, "fr-idf")
        server.takeRequest()

        val countryUrl = server.url("/files/roadgraph/fr-roadgraph.db.gz").toString()
        server.enqueue(MockResponse().setResponseCode(200).setBody("COUNTRY"))
        val out = ResumableArtifactDownload.fetch(client, countryUrl, staged, "fr-idf")

        assertEquals(ResumableArtifactDownload.Outcome.Complete, out)
        assertEquals("COUNTRY", staged.readText())
        val second = server.takeRequest()
        assertEquals("a different url must not be resumed", null, second.getHeader("Range"))
    }

    @Test
    fun `a 404 is reported as unavailable and not retried forever`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val url = server.url("/files/roadgraph/zz-roadgraph.db.gz").toString()

        val out = ResumableArtifactDownload.fetch(client, url, staged, "zz")

        assertEquals(ResumableArtifactDownload.Outcome.NotAvailable, out)
    }

    @Test
    fun `extraHeaders are sent on the download, not just the caller's probe`() {
        // The streets path probes with HEAD carrying a User-Agent and then hands
        // the url to fetch(). If the download did not carry the same header, the
        // two requests would be subject to different server policy -- the probe
        // would say 200 and the transfer could be refused.
        server.enqueue(MockResponse().setResponseCode(200).setBody("STREETS"))
        val url = server.url("/files/streets/fr-streets.db.gz").toString()

        val out = ResumableArtifactDownload.fetch(
            client, url, staged, "fr/streets",
            extraHeaders = mapOf("User-Agent" to "OSHI-Test/1.0"),
        )

        assertEquals(ResumableArtifactDownload.Outcome.Complete, out)
        assertEquals("OSHI-Test/1.0", server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun `resumed progress is reported against the whole artifact`() {
        val url = server.url("/files/streets/fr-streets.db.gz").toString()
        // 4 bytes already on disk, sidecar says they came from this url.
        staged.writeText("ABCD")
        File(dir, staged.name + ".src").writeText(url + "\n\"tag-1\"")

        // The server sends only the REMAINING 6 bytes, so contentLength is 6,
        // not 10. Progress must still be measured against 10, or a transfer
        // resuming at 40% would appear to start at 0%.
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 4-9/10")
                .setBody("EFGHIJ")
        )

        val seen = mutableListOf<Float>()
        val out = ResumableArtifactDownload.fetch(client, url, staged, "fr/streets") { seen.add(it) }

        assertEquals(ResumableArtifactDownload.Outcome.Complete, out)
        assertEquals("ABCDEFGHIJ", staged.readText())
        assertTrue("progress must be emitted", seen.isNotEmpty())
        assertTrue(
            "first report must reflect the 4 bytes already held, not 0 -- got ${seen.first()}",
            seen.first() >= 0.4f
        )
        assertEquals("final report must be 1.0", 1.0f, seen.last(), 1e-6f)
    }

    @Test
    fun `clear removes the partial and its sidecar`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("DATA"))
        val url = server.url("/files/roadgraph/ni-roadgraph.db.gz").toString()
        ResumableArtifactDownload.fetch(client, url, staged, "ni")
        server.takeRequest()
        assertTrue(staged.exists())

        ResumableArtifactDownload.clear(staged)

        assertFalse(staged.exists())
        assertFalse(File(dir, staged.name + ".src").exists())
    }
}
