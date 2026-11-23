package com.oshi.messenger.service.media

/**
 * __MEDIA_INTEROP_2026_09_18__ Which photo bytes may leave this app untouched.
 *
 * The same question [VideoPresetLadder.isInteroperable] asks about video, and it
 * had the same wrong answer: a SIZE test standing in for a FORMAT test.
 *
 * `MediaCompressionManager.compressImage` decoded, downsampled to 1920 px and
 * re-encoded as JPEG only when the source was over its 5 MB threshold. Under it,
 * the original bytes went out exactly as the gallery held them — and an Android
 * gallery is not a JPEG folder. Samsung and every recent Android camera shoot
 * **HEIC** by default; screenshots are **PNG**; anything saved from the web is
 * routinely **WebP**. A 3 MB HEIC is under the threshold, so it shipped as HEIC,
 * at whatever resolution the sensor gave it, under a `MediaType.IMAGE` label that
 * promises nothing about encoding.
 *
 * iOS never had the problem, by construction rather than by care: `processImage`
 * (`MediaManager.swift:385-388`) decodes to a `UIImage` and takes
 * `jpegData(compressionQuality:)` on EVERY path, so what leaves an iPhone is JPEG
 * at 1920 px or smaller whatever came in. This file is Android making the same
 * promise.
 *
 * Pure, and therefore testable: it reads magic bytes, nothing else. No
 * `BitmapFactory`, no `android.*` type, so the rule that decides what a peer
 * receives is exercised by plain JVM tests rather than only on a device.
 */
object ImageInterop {

    /** The one still-image encoding every OSHI client decodes. */
    const val INTEROP_MIME = "image/jpeg"

    /** Longest side, in pixels, above which a photo is downscaled. iOS: 1920. */
    const val MAX_DIMENSION = 1920

    /** JPEG: `FF D8 FF`. */
    fun isJpeg(b: ByteArray?): Boolean =
        b != null && b.size >= 3 && at(b, 0) == 0xFF && at(b, 1) == 0xD8 && at(b, 2) == 0xFF

    /** PNG: `89 50 4E 47 0D 0A 1A 0A`. */
    fun isPng(b: ByteArray?): Boolean =
        b != null && b.size >= 8 &&
            at(b, 0) == 0x89 && at(b, 1) == 0x50 && at(b, 2) == 0x4E && at(b, 3) == 0x47 &&
            at(b, 4) == 0x0D && at(b, 5) == 0x0A && at(b, 6) == 0x1A && at(b, 7) == 0x0A

    /** WebP: `RIFF....WEBP`. */
    fun isWebp(b: ByteArray?): Boolean =
        b != null && b.size >= 12 &&
            at(b, 0) == 0x52 && at(b, 1) == 0x49 && at(b, 2) == 0x46 && at(b, 3) == 0x46 &&
            at(b, 8) == 0x57 && at(b, 9) == 0x45 && at(b, 10) == 0x42 && at(b, 11) == 0x50

    /**
     * HEIC / HEIF: an ISO-BMFF box `....ftyp` whose brand is one of the HEIF ones.
     *
     * The brand matters: `ftyp` alone also heads an MP4. Reading the four brand
     * bytes is what separates "a photo from a modern Android camera" from "a
     * video", and mistaking one for the other would send a clip through the image
     * encoder.
     */
    fun isHeif(b: ByteArray?): Boolean {
        if (b == null || b.size < 12) return false
        if (at(b, 4) != 0x66 || at(b, 5) != 0x74 || at(b, 6) != 0x79 || at(b, 7) != 0x70) return false
        val brand = String(b, 8, 4, Charsets.US_ASCII)
        return brand in HEIF_BRANDS
    }

    private val HEIF_BRANDS = setOf(
        "heic", "heix", "hevc", "hevx",   // HEVC-coded stills
        "heim", "heis", "hevm", "hevs",   // sequences
        "mif1", "msf1",                   // generic HEIF
        "avif", "avis",                   // AV1-coded stills, increasingly common
    )

    /**
     * Must these bytes be decoded and re-encoded before they are sent?
     *
     * True unless the photo is ALREADY the interoperable shape: JPEG, and within
     * the dimension cap. A JPEG that is already small is handed on untouched —
     * re-encoding it would cost a generation of quality to change nothing.
     *
     * @param width  longest-side source width, or <= 0 when the probe failed
     * @param height likewise. An unreadable probe is NOT read as "already fine":
     *               it re-encodes, which is the recoverable direction.
     */
    fun needsReencode(bytes: ByteArray?, width: Int, height: Int): Boolean {
        if (bytes == null || bytes.isEmpty()) return false      // nothing to do
        // A GIF is the one thing that must NEVER be re-encoded: JPEG keeps one
        // frame and the animation is the file (`__GIF_WIRE_TYPE_2026_09_13__`).
        if (GifBytes.isGif(bytes)) return false
        if (!isJpeg(bytes)) return true
        if (width <= 0 || height <= 0) return true
        return maxOf(width, height) > MAX_DIMENSION
    }

    /** A human name for the log, so "why did this re-encode" is answerable. */
    fun describe(bytes: ByteArray?): String = when {
        bytes == null || bytes.isEmpty() -> "empty"
        GifBytes.isGif(bytes) -> "gif"
        isJpeg(bytes) -> "jpeg"
        isPng(bytes) -> "png"
        isWebp(bytes) -> "webp"
        isHeif(bytes) -> "heif/heic"
        else -> "unknown"
    }

    private fun at(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
}
