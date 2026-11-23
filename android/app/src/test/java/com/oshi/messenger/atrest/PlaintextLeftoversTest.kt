package com.oshi.messenger.atrest

import com.oshi.messenger.data.local.atrest.AtRestKeyProvider
import com.oshi.messenger.data.local.atrest.PlaintextScratch
import com.oshi.messenger.data.local.atrest.SealedFile
import com.oshi.messenger.ui.components.LinkPreviewCache
import com.oshi.messenger.ui.components.LinkPreviewData
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom

/**
 * __PLAINTEXT_LEFTOVERS_2026_09_24__ Temporary plaintext is deleted after use / swept
 * at startup; the link-preview cache is sealed and HMAC-named.
 */
class PlaintextLeftoversTest {

    private lateinit var root: File
    private val master = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val keys = AtRestKeyProvider { master }

    @Before fun setUp() { root = Files.createTempDirectory("oshi-leftovers-").toFile() }
    @After fun tearDown() { root.deleteRecursively() }

    private fun containsWindow(hay: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    // ── startup sweep ───────────────────────────────────────────────────────

    @Test fun `startup sweep removes old voice notes, captures, codec temps and shares, keeps fresh and unrelated files`() {
        val cache = File(root, "cache").apply { mkdirs() }
        fun old(f: File) = f.apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)); setLastModified(0) }
        val stale = listOf(
            old(File(cache, "voice_messages/voice_0b3c.m4a")),
            old(File(cache, "voice_playback/voice_msg-1.m4a")),
            old(File(cache, "oshi_photo_123456789.jpg")),
            old(File(cache, "oshi_video_-42.mp4")),
            old(File(cache, "temp_video_0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0.mp4")),
            old(File(cache, "input_0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0.mp4")),
            old(File(cache, "temp_decode_0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0.m4a")),
            old(File(cache, "temp_duration.m4a")),
            old(File(cache, "mail-drive-preview123.pdf")),
            old(File(cache, "${PlaintextScratch.SHARE_DIR_NAME}/Contrat-2026.pdf")),
        )
        val freshShare = File(cache, "${PlaintextScratch.SHARE_DIR_NAME}/now.pdf").apply { writeBytes(byteArrayOf(9)) }
        val freshCapture = File(cache, "oshi_photo_99.jpg").apply { writeBytes(byteArrayOf(9)) }
        val unrelated = old(File(cache, "input_notes.txt"))

        assertEquals(stale.size, PlaintextScratch.sweepAtStartup(cache))
        stale.forEach { assertFalse(it.path, it.exists()) }
        assertTrue("a share target may still be reading it", freshShare.exists())
        assertTrue("a capture may be in progress", freshCapture.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun `deleteCapture only deletes our capture names`() {
        val cache = File(root, "cache").apply { mkdirs() }
        val photo = File(cache, "oshi_photo_-17.jpg").apply { writeBytes(byteArrayOf(1)) }
        val video = File(cache, "oshi_video_42.mp4").apply { writeBytes(byteArrayOf(1)) }
        val other = File(cache, "keep.jpg").apply { writeBytes(byteArrayOf(1)) }
        assertTrue(PlaintextScratch.deleteCapture(cache, photo.name))
        assertTrue(PlaintextScratch.deleteCapture(cache, video.name))
        assertFalse(PlaintextScratch.deleteCapture(cache, "keep.jpg"))
        assertFalse(PlaintextScratch.deleteCapture(cache, "../oshi_photo_1.jpg"))
        assertFalse(PlaintextScratch.deleteCapture(cache, null))
        assertFalse(photo.exists()); assertFalse(video.exists()); assertTrue(other.exists())
    }

    @Test fun `a new share purges the previous one and keeps a sanitized display name`() {
        val cache = File(root, "cache").apply { mkdirs() }
        val first = PlaintextScratch.newShareFile(cache, "Contrat 2026.pdf").apply { writeBytes(byteArrayOf(1)) }
        assertEquals("Contrat_2026.pdf", first.name)
        val second = PlaintextScratch.newShareFile(cache, "../../x.jpg").apply { writeBytes(byteArrayOf(2)) }
        assertFalse("one decrypted export on disk at a time", first.exists())
        assertEquals(File(cache, PlaintextScratch.SHARE_DIR_NAME), second.parentFile)
        assertEquals(1, second.parentFile.listFiles()!!.size)
    }

    // ── link preview cache ──────────────────────────────────────────────────

    private val url = "https://example.org/secret-article?id=42"
    private val data = LinkPreviewData(url = url, title = "Private title", description = "desc", imageUrl = null, siteName = "Example")

    @Test fun `link preview entries are sealed and named by HMAC, not by the URL`() {
        val dir = File(root, "LinkPreviews")
        val cache = LinkPreviewCache(dir, keys)
        cache.store(url, data, byteArrayOf(7, 7, 7))

        val files = dir.listFiles()!!.filter { it.isFile }
        assertEquals(1, files.size)
        val f = files.single()
        assertTrue("hex HMAC name", Regex("^[0-9a-f]{64}$").matches(f.name))
        assertFalse("the name must not be the reversible base64 of the URL", f.name == LinkPreviewCache.key(url))
        val raw = f.readBytes()
        assertTrue(SealedFile.isSealed(raw))
        assertFalse(containsWindow(raw, "Private title".toByteArray()))
        assertFalse(containsWindow(raw, "secret-article".toByteArray()))

        // A fresh instance (cold memory) reads it back through the disk tier.
        val hit = LinkPreviewCache(dir, keys).lookup(url)
        assertNotNull(hit)
        assertEquals("Private title", hit!!.data!!.title)
        assertArrayEquals(byteArrayOf(7, 7, 7), hit.image)

        // Another device key cannot read it (and a different key gives a different name).
        assertNull(LinkPreviewCache(dir, AtRestKeyProvider { ByteArray(32) { 3 } }).lookup(url))
    }

    @Test fun `without a key the link preview cache is memory only`() {
        val dir = File(root, "LinkPreviews")
        val cache = LinkPreviewCache(dir, AtRestKeyProvider.NONE)
        cache.store(url, data, null)
        assertEquals("Private title", cache.lookup(url)!!.data!!.title)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun `legacy base64-named plaintext previews are sealed and removed`() {
        val dir = File(root, "LinkPreviews").apply { mkdirs() }
        // What the pre-2026-09-24 build wrote: plaintext JSON under base64(URL).
        val legacy = File(dir, LinkPreviewCache.key(url)).apply {
            writeText("""{"storedAt":${System.currentTimeMillis()},"url":"$url","title":"Old title"}""")
        }
        val tmp = File(dir, LinkPreviewCache.key(url) + ".tmp").apply { writeText("{}") }
        val cache = LinkPreviewCache(dir, keys)
        assertEquals(2, cache.migrateLegacy())
        assertFalse(legacy.exists()); assertFalse(tmp.exists())
        assertEquals("Old title", LinkPreviewCache(dir, keys).lookup(url)!!.data!!.title)
        dir.listFiles()!!.forEach { assertTrue(SealedFile.isSealed(it.readBytes())) }

        // No key: legacy plaintext is simply deleted (a re-fetchable cache).
        val dir2 = File(root, "LP2").apply { mkdirs() }
        val legacy2 = File(dir2, LinkPreviewCache.key(url)).apply { writeText("{}") }
        assertEquals(1, LinkPreviewCache(dir2, keys = AtRestKeyProvider.NONE).migrateLegacy())
        assertFalse(legacy2.exists())
    }
}
