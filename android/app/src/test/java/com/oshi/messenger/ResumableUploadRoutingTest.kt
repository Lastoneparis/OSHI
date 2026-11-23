package com.oshi.messenger

import com.oshi.messenger.service.MediaCompressionManager
import com.oshi.messenger.service.media.ResumableUploader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ROUTING boundary between the single-shot POST and the chunked resumable upload.
 *
 * `MediaResumableUploadTest` already covers the uploader's state machine (init, resume,
 * 409 resync, finalize). What was missing — and what actually shipped broken — is the
 * decision of WHICH path a given payload takes, and the invariant that ties the media
 * size cap to it.
 *
 * Reference: `OSHI/ResumableUploader.swift:40,46,69`; routing at
 * `OSHI/MessageManager.swift:4467`.
 */
class ResumableUploadRoutingTest {

    /** iOS `kResumableUploadThreshold = 12 * 1024 * 1024` (`ResumableUploader.swift:40`). */
    @Test
    fun `threshold matches iOS exactly`() {
        assertEquals(12 * 1024 * 1024, ResumableUploader.THRESHOLD_BYTES)
        assertEquals(8 * 1024 * 1024, ResumableUploader.CHUNK_SIZE)
        assertEquals(300L * 1024 * 1024, ResumableUploader.MAX_PAYLOAD_BYTES)
        assertEquals("/api/upload", ResumableUploader.PATH_PREFIX)
    }

    /** Strictly greater-than: a payload exactly ON the threshold still takes one POST. */
    @Test
    fun `routing is exclusive at the boundary`() {
        assertFalse(ResumableUploader.shouldUseResumable(0))
        assertFalse(ResumableUploader.shouldUseResumable(ResumableUploader.THRESHOLD_BYTES - 1))
        assertFalse(ResumableUploader.shouldUseResumable(ResumableUploader.THRESHOLD_BYTES))
        assertTrue(ResumableUploader.shouldUseResumable(ResumableUploader.THRESHOLD_BYTES + 1))
    }

    /**
     * A small text message must never touch the chunked endpoint — it would turn one
     * request into three (init + chunk + finalize) for a few hundred bytes.
     */
    @Test
    fun `ordinary messages keep the single-shot path`() {
        assertFalse(ResumableUploader.shouldUseResumable(512))
        assertFalse(ResumableUploader.shouldUseResumable(2 * 1024 * 1024))       // 2 MB photo
        assertTrue(ResumableUploader.shouldUseResumable(40 * 1024 * 1024))       // 40 MB video
    }

    /**
     * 🔒 THE INVARIANT THAT MADE RAISING THE CAP SAFE.
     *
     * `IPFS_MAX_SIZE` was raised from 50 MB to iOS's 200 MB ONLY because
     * `VPSClient.sendMessageViaIPFS` now routes over-threshold payloads through the
     * chunked uploader. Every byte above the threshold MUST have a chunked path,
     * or the cap is just permission to hit the 50 MB nginx wall mid-send.
     *
     * If someone lowers the routing threshold back above the cap, or raises the cap
     * without the uploader, this fails.
     */
    @Test
    fun `every payload the size cap admits above the threshold has a chunked path`() {
        assertTrue(
            "IPFS_MAX_SIZE must exceed the resumable threshold or the chunked path is dead code",
            MediaCompressionManager.IPFS_MAX_SIZE > ResumableUploader.THRESHOLD_BYTES
        )
        // The largest raw payload the compressor will admit, base64'd into the JSON
        // envelope (+~33 %), must still fit under the uploader's hard ceiling.
        val worstCaseEncoded = (MediaCompressionManager.IPFS_MAX_SIZE * 4 + 2) / 3
        assertTrue(
            "200 MB raw ≈ ${worstCaseEncoded / 1024 / 1024} MB base64 — must stay under " +
                "${ResumableUploader.MAX_PAYLOAD_BYTES / 1024 / 1024} MB",
            worstCaseEncoded <= ResumableUploader.MAX_PAYLOAD_BYTES
        )
        // And the cap itself is the iOS number.
        assertEquals(200L * 1024 * 1024, MediaCompressionManager.IPFS_MAX_SIZE)
    }
}
