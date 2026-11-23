package com.oshi.messenger

import com.oshi.messenger.service.media.GifBytes
import com.oshi.messenger.service.media.ImageInterop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * __MEDIA_INTEROP_2026_09_18__ What a PHOTO is allowed to be when it leaves.
 *
 * The video half of this question lives in `VideoSendBudgetTest`; this is the
 * still-image half, and it was the same bug wearing different clothes: a SIZE
 * test standing in for a FORMAT test.
 *
 * `compressImage` decoded, downsampled and re-encoded only above its 5 MB
 * threshold. Under it, the gallery's own bytes went out — and an Android gallery
 * is not a JPEG folder. Recent cameras (Samsung among them) shoot HEIC by
 * default, screenshots are PNG, and anything saved from the web is WebP. All
 * three travelled under a `MediaType.IMAGE` label that promises nothing about
 * encoding, at full sensor resolution, to a peer that may decode none of them.
 *
 * iOS never had it: `processImage` takes `jpegData(...)` on every path, so an
 * iPhone cannot send anything but JPEG. These tests pin Android to the same
 * promise, and they are pure — magic bytes in, a decision out — so the rule that
 * decides what a peer receives is exercised here rather than only on a device.
 */
class MediaInteropTest {

    // Real magic numbers, not mocks: this is a test OF the sniffing.
    private fun jpeg(w: Int = 100, h: Int = 100) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(20)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(20)
    private val webp = "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + ByteArray(12)
    private val heic = ByteArray(4) + "ftyp".toByteArray() + "heic".toByteArray() + ByteArray(12)
    private val avif = ByteArray(4) + "ftyp".toByteArray() + "avif".toByteArray() + ByteArray(12)
    private val gif = "GIF89a".toByteArray() + ByteArray(20)
    private val mp4 = ByteArray(4) + "ftyp".toByteArray() + "isom".toByteArray() + ByteArray(12)

    @Test
    fun `each format is recognised by its bytes, not by a file name`() {
        assertEquals("jpeg", ImageInterop.describe(jpeg()))
        assertEquals("png", ImageInterop.describe(png))
        assertEquals("webp", ImageInterop.describe(webp))
        assertEquals("heif/heic", ImageInterop.describe(heic))
        assertEquals("heif/heic", ImageInterop.describe(avif))
        assertEquals("gif", ImageInterop.describe(gif))
    }

    @Test
    fun `an mp4 is not mistaken for a HEIC just because both start with ftyp`() {
        // Both are ISO base media files; only the BRAND separates a photo from a
        // clip. Getting this wrong would push a video through the image encoder.
        assertFalse(ImageInterop.isHeif(mp4))
        assertTrue(ImageInterop.isHeif(heic))
    }

    @Test
    fun `a small HEIC, PNG or WebP is re-encoded even though its size is fine`() {
        // 2 MB, 4000x3000 — comfortably under the old 5 MB threshold, which is
        // exactly why each of these used to travel untouched.
        for (bytes in listOf(heic, png, webp, avif)) {
            assertTrue(
                "${ImageInterop.describe(bytes)} must be re-encoded — the peer may " +
                    "not decode it at all",
                ImageInterop.needsReencode(bytes, 4000, 3000),
            )
        }
    }

    @Test
    fun `a JPEG already within the cap is left alone`() {
        // Re-encoding it would cost a generation of quality to change nothing.
        assertFalse(ImageInterop.needsReencode(jpeg(), 1600, 1200))
        // …but an oversized JPEG is still downscaled, as iOS downscales at 1920.
        assertTrue(ImageInterop.needsReencode(jpeg(), 4000, 3000))
        assertEquals(1920, ImageInterop.MAX_DIMENSION)
    }

    @Test
    fun `a GIF is never re-encoded, whatever its size`() {
        // JPEG keeps one frame, and for a GIF the animation IS the file.
        assertFalse(ImageInterop.needsReencode(gif, 4000, 3000))
        assertTrue(GifBytes.isGif(gif))
    }

    @Test
    fun `an unreadable probe re-encodes rather than assuming the best`() {
        assertTrue(ImageInterop.needsReencode(jpeg(), 0, 0))
        assertTrue(ImageInterop.needsReencode(byteArrayOf(1, 2, 3, 4), 100, 100))
    }

    @Test
    fun `the image path consults the format, not only the byte count`() {
        val src = moduleFile(
            "src/main/java/com/oshi/messenger/service/MediaCompressionManager.kt").readText()
        assertTrue(
            "the 5 MB threshold alone is what let HEIC and WebP through",
            src.contains("originalSize > COMPRESSION_THRESHOLD || mustReencode"),
        )
        assertTrue(src.contains("ImageInterop.needsReencode("))
    }

    @Test
    fun `iOS re-encodes every photo to JPEG, which is why this is the target`() {
        val swift = repoFile("OSHI/MediaManager.swift") ?: return
        assertTrue(
            "iOS must still take jpegData on the main image path — if it ever ships " +
                "HEIC, Android's rule has to change with it",
            swift.contains("jpegData(compressionQuality:"),
        )
        assertTrue("iOS caps the longest side at 1920 too", swift.contains("maxImageDimension: CGFloat = 1920"))
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun moduleFile(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            val nested = File(dir, "app/$relative")
            if (nested.isFile) return nested
            dir = dir.parentFile
        }
        fail("could not locate $relative")
        error("unreachable")
    }

    private fun repoFile(relative: String): String? {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        return null
    }
}
