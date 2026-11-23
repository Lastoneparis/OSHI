package com.oshi.messenger.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.*
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaCompressionManager - Compatible with iOS MediaManager.swift
 *
 * Smart compression with delivery method-specific size limits:
 * - Mesh: 10 MB max (direct P2P)
 * - IPFS/VPS: 50 MB max
 * - Compression threshold: 5 MB (optimize above this)
 *
 * Three-phase image compression:
 * 1. Quality reduction (0.85 → 0.3)
 * 2. Image resizing (80% per step, max 5 steps)
 * 3. Aggressive compression (0.5 → 0.1)
 */
@Singleton
class MediaCompressionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "MediaCompression"

        const val MESH_MAX_SIZE = 10 * 1024 * 1024L        // 10 MB for mesh
        /**
         * Max raw payload that may take the IPFS/VPS path — iOS parity, 200 MB.
         *
         * Raised from 50 MB ONLY because `VPSClient.sendMessageViaIPFS` now routes
         * anything over `ResumableUploader.THRESHOLD_BYTES` (12 MB) through the chunked
         * resumable uploader. Before that landed, Android's single foreground POST hit
         * the 50 MB nginx wall (base64 adds ~33 % on top of the raw bytes), so a cap
         * above ~35 MB just moved the failure from "too large" to a 413 mid-send.
         *
         * Do NOT raise this again without checking `ResumableUploader.MAX_PAYLOAD_BYTES`
         * (300 MB, `ResumableUploader.swift:69`), which is the hard server-side ceiling
         * on the ENCODED envelope — 200 MB raw ≈ 267 MB base64, which still fits.
         */
        const val IPFS_MAX_SIZE = 200 * 1024 * 1024L       // 200 MB for IPFS/VPS (iOS parity)
        const val COMPRESSION_THRESHOLD = 5 * 1024 * 1024L  // 5 MB threshold

        private const val DEFAULT_QUALITY = 85              // 0.85 in iOS
        private const val THUMBNAIL_SIZE = 200
        private const val THUMBNAIL_QUALITY = 60            // 0.6 in iOS
        private const val MAX_RESIZE_STEPS = 5
        private const val RESIZE_SCALE = 0.8f
        private const val MAX_IMAGE_DIMENSION = 1920        // Max px on longest side before send
    }

    // Progress tracking (matching iOS)
    private val _compressionProgress = MutableStateFlow(0.0)
    val compressionProgress: StateFlow<Double> = _compressionProgress.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    enum class DeliveryTarget {
        MESH,    // 10 MB limit
        VPS      // 50 MB limit
    }

    data class CompressedMedia(
        val data: ByteArray,
        val originalSize: Int,
        val compressedSize: Int,
        val isCompressed: Boolean,
        val compressionRatio: Double,
        val thumbnail: ByteArray?
    ) {
        val compressionInfo: String
            get() = if (isCompressed) {
                String.format("Compressed by %.1f%%", (1.0 - compressionRatio) * 100)
            } else {
                "No compression needed"
            }
    }

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /**
     * Compress image data for delivery
     * Uses three-phase intelligent compression matching iOS MediaManager
     */
    suspend fun compressImage(
        imageData: ByteArray,
        target: DeliveryTarget = DeliveryTarget.VPS
    ): CompressedMedia = withContext(Dispatchers.Default) {
        _isProcessing.value = true
        _compressionProgress.value = 0.0

        try {
            val maxSize = when (target) {
                DeliveryTarget.MESH -> MESH_MAX_SIZE
                DeliveryTarget.VPS -> IPFS_MAX_SIZE
            }
            val originalSize = imageData.size

            // ALWAYS downsample large images first to prevent OOM (max 1920px on longest side)
            //
            // __MEDIA_INTEROP_2026_09_18__ …and re-encode anything that is not
            // already a JPEG within the cap, whatever its SIZE.
            //
            // This branch used to read `originalSize > COMPRESSION_THRESHOLD`
            // alone, so a photo under 5 MB left the phone byte-for-byte as the
            // gallery held it. An Android gallery is not a JPEG folder: recent
            // cameras shoot HEIC, screenshots are PNG, anything saved from the web
            // is WebP. Those went out under a `MediaType.IMAGE` label that promises
            // nothing, at full sensor resolution, to a peer that may decode none of
            // them. iOS never had the problem because `processImage` takes
            // `jpegData(...)` on every path (`MediaManager.swift:385-388`) — what
            // leaves an iPhone is JPEG at ≤1920 px whatever came in. This is
            // Android making the same promise.
            //
            // A GIF is the one exception and [ImageInterop.needsReencode] knows it:
            // re-encoding one keeps a single frame (`__GIF_WIRE_TYPE_2026_09_13__`).
            val sourceDims = probeImageDimensions(imageData)
            val mustReencode = com.oshi.messenger.service.media.ImageInterop.needsReencode(
                imageData, sourceDims.first, sourceDims.second
            )
            val preprocessed = if (originalSize > COMPRESSION_THRESHOLD || mustReencode) {
                val downsampled = safeDecodeBitmap(imageData, MAX_IMAGE_DIMENSION)
                if (downsampled != null) {
                    val w = downsampled.width
                    val h = downsampled.height
                    val result = compressBitmap(downsampled, DEFAULT_QUALITY)
                    downsampled.recycle()
                    Log.d(
                        TAG,
                        "Pre-processed ${com.oshi.messenger.service.media.ImageInterop.describe(imageData)}: " +
                            "$originalSize → ${result.size} bytes (${w}x${h})"
                    )
                    result
                } else {
                    imageData
                }
            } else {
                imageData
            }

            // Already small enough?
            if (preprocessed.size <= maxSize) {
                // Still optimize if above threshold
                if (preprocessed.size > COMPRESSION_THRESHOLD) {
                    val optimized = compressJpeg(preprocessed, DEFAULT_QUALITY)
                    if (optimized != null && optimized.size < preprocessed.size) {
                        _compressionProgress.value = 1.0
                        val thumbnail = generateThumbnail(optimized)
                        return@withContext CompressedMedia(
                            data = optimized,
                            originalSize = originalSize,
                            compressedSize = optimized.size,
                            isCompressed = true,
                            compressionRatio = optimized.size.toDouble() / originalSize,
                            thumbnail = thumbnail
                        )
                    }
                }

                _compressionProgress.value = 1.0
                val thumbnail = generateThumbnail(preprocessed)
                return@withContext CompressedMedia(
                    data = preprocessed,
                    originalSize = originalSize,
                    compressedSize = preprocessed.size,
                    isCompressed = preprocessed !== imageData,
                    compressionRatio = preprocessed.size.toDouble() / originalSize,
                    thumbnail = thumbnail
                )
            }

            // Need full compression
            val compressed = intelligentCompress(preprocessed, maxSize)
            val thumbnail = generateThumbnail(compressed)

            _compressionProgress.value = 1.0

            CompressedMedia(
                data = compressed,
                originalSize = originalSize,
                compressedSize = compressed.size,
                isCompressed = true,
                compressionRatio = compressed.size.toDouble() / originalSize,
                thumbnail = thumbnail
            )
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during image compression (${imageData.size} bytes)")
            _compressionProgress.value = 1.0
            // Return original data as last resort so the caller can handle gracefully
            CompressedMedia(
                data = imageData,
                originalSize = imageData.size,
                compressedSize = imageData.size,
                isCompressed = false,
                compressionRatio = 1.0,
                thumbnail = null
            )
        } finally {
            _isProcessing.value = false
        }
    }

    /**
     * Compress video data for delivery
     * Selects quality preset based on target size
     */
    suspend fun compressVideo(
        videoData: ByteArray,
        target: DeliveryTarget = DeliveryTarget.VPS
    ): CompressedMedia = withContext(Dispatchers.Default) {
        _isProcessing.value = true
        _compressionProgress.value = 0.0

        try {
            val maxSize = when (target) {
                DeliveryTarget.MESH -> MESH_MAX_SIZE
                DeliveryTarget.VPS -> IPFS_MAX_SIZE
            }
            val originalSize = videoData.size

            // 🔧 M-9. This used to be `if (originalSize <= maxSize) return original`,
            // i.e. the transcoder only ever ran ABOVE 200 MB. A 45-second 4K clip
            // (~150 MB) therefore shipped at full size. iOS transcodes every video and
            // caps the result at 720p-class, so the decision is taken by
            // [VideoPresetLadder.needsTranscode] on the clip's resolution, its own
            // bitrate and a soft byte budget, not only on the hard ceiling.
            //
            // __CHAT_VIDEO_BITRATE_2026_10_09__ ONE temp file for the whole operation.
            // `transcodeVideo` spilled `videoData` to disk and deleted it, and
            // `generateVideoThumbnail` then wrote the SAME bytes out to a second temp
            // file just to pull one frame — a redundant ~100 MB write per 4K clip, on
            // top of the one the transcode needs. The thumbnail now reads whichever
            // file is already on disk. (Same class of waste as the iOS picker fix;
            // what remains on Android is the `ByteArray` itself, which is the media
            // pipeline's shape and not something this method can change.)
            val inputFile = File(context.cacheDir, "input_${UUID.randomUUID()}.mp4")
            val outputFile = File(context.cacheDir, "output_${UUID.randomUUID()}.mp4")
            val compressed = try {
                FileOutputStream(inputFile).use { it.write(videoData) }
                transcodeVideo(inputFile, outputFile, videoData.size.toLong(), maxSize)
            } catch (e: Exception) {
                Log.e(TAG, "Video transcoding failed: ${e.message}")
                null
            } catch (e: OutOfMemoryError) {
                // A multi-hundred-MB readBytes() can genuinely blow the heap; that must
                // not take the whole app with it when the original is still sendable.
                Log.e(TAG, "Video transcoding OOM — keeping original")
                null
            }
            val thumbnail = try {
                generateVideoThumbnail(if (compressed != null) outputFile else inputFile)
            } finally {
                inputFile.delete()
                outputFile.delete()
            }

            _compressionProgress.value = 1.0

            CompressedMedia(
                data = compressed ?: videoData,
                originalSize = originalSize,
                compressedSize = (compressed ?: videoData).size,
                isCompressed = compressed != null,
                compressionRatio = (compressed ?: videoData).size.toDouble() / originalSize,
                thumbnail = thumbnail
            )
        } finally {
            _isProcessing.value = false
        }
    }

    /**
     * Get the delivery target based on available connections
     */
    fun getMaxSizeForTarget(target: DeliveryTarget): Long {
        return when (target) {
            DeliveryTarget.MESH -> MESH_MAX_SIZE
            DeliveryTarget.VPS -> IPFS_MAX_SIZE
        }
    }

    // =========================================================================
    // THREE-PHASE IMAGE COMPRESSION (matching iOS)
    // =========================================================================

    /**
     * __MEDIA_INTEROP_2026_09_18__ `(width, height)` of [imageData] without decoding
     * a single pixel — `inJustDecodeBounds` reads the header only. `(0, 0)` when the
     * bytes are not a decodable image, which [ImageInterop.needsReencode] reads as
     * "cannot tell", i.e. re-encode.
     */
    private fun probeImageDimensions(imageData: ByteArray): Pair<Int, Int> = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageData, 0, imageData.size, opts)
        opts.outWidth to opts.outHeight
    } catch (_: Throwable) {
        0 to 0
    }

    /**
     * Calculate inSampleSize for safe downsampled decoding (avoids OOM on large images).
     * Returns the largest power-of-2 sample size that keeps both dimensions >= reqWidth/reqHeight.
     */
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * Safely decode a byte array into a Bitmap, using inSampleSize to prevent OOM.
     * Downsamples so the longest side is at most maxDimension pixels.
     */
    private fun safeDecodeBitmap(imageData: ByteArray, maxDimension: Int = MAX_IMAGE_DIMENSION): Bitmap? {
        return try {
            // First pass: read dimensions only
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageData, 0, imageData.size, boundsOptions)

            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            if (origWidth <= 0 || origHeight <= 0) return null

            // Second pass: decode with inSampleSize
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(boundsOptions, maxDimension, maxDimension)
            }
            val sampled = BitmapFactory.decodeByteArray(imageData, 0, imageData.size, decodeOptions)
                ?: return null

            // If still larger than maxDimension, scale down precisely
            val longestSide = maxOf(sampled.width, sampled.height)
            if (longestSide > maxDimension) {
                val scale = maxDimension.toFloat() / longestSide
                val targetW = (sampled.width * scale).toInt().coerceAtLeast(1)
                val targetH = (sampled.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(sampled, targetW, targetH, true)
                if (scaled !== sampled) sampled.recycle()
                scaled
            } else {
                sampled
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM decoding image (${imageData.size} bytes), trying smaller sample")
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                BitmapFactory.decodeByteArray(imageData, 0, imageData.size, opts)
            } catch (e2: OutOfMemoryError) {
                Log.e(TAG, "OOM even at inSampleSize=8, giving up")
                null
            }
        }
    }

    /**
     * Intelligent three-phase compression matching iOS MediaManager algorithm
     */
    private fun intelligentCompress(imageData: ByteArray, maxSize: Long): ByteArray {
        val bitmap = safeDecodeBitmap(imageData)
            ?: return imageData

        // Phase 1: Quality reduction (0.85 → 0.3, decrement 0.1)
        var currentQuality = DEFAULT_QUALITY
        while (currentQuality > 30) {
            val compressed = compressBitmap(bitmap, currentQuality)
            _compressionProgress.value = (100 - currentQuality).toDouble() / 100.0

            if (compressed.size <= maxSize) {
                Log.d(TAG, "Phase 1: Quality $currentQuality% → ${compressed.size} bytes")
                bitmap.recycle()
                return compressed
            }
            currentQuality -= 10
        }

        // Phase 2: Image resizing (80% per step, max 5 steps)
        var currentBitmap = bitmap
        for (step in 1..MAX_RESIZE_STEPS) {
            val newWidth = (currentBitmap.width * RESIZE_SCALE).toInt()
            val newHeight = (currentBitmap.height * RESIZE_SCALE).toInt()

            if (newWidth < 100 || newHeight < 100) break

            val resized = Bitmap.createScaledBitmap(currentBitmap, newWidth, newHeight, true)
            if (currentBitmap != bitmap) currentBitmap.recycle()
            currentBitmap = resized

            val compressed = compressBitmap(currentBitmap, DEFAULT_QUALITY)
            _compressionProgress.value = 0.5 + (step.toDouble() / MAX_RESIZE_STEPS) * 0.5

            if (compressed.size <= maxSize) {
                Log.d(TAG, "Phase 2: Resize step $step (${newWidth}x${newHeight}) → ${compressed.size} bytes")
                currentBitmap.recycle()
                return compressed
            }
        }

        // Phase 3: Aggressive compression (0.5 → 0.1)
        var aggressiveQuality = 50
        while (aggressiveQuality >= 10) {
            val compressed = compressBitmap(currentBitmap, aggressiveQuality)

            if (compressed.size <= maxSize) {
                Log.d(TAG, "Phase 3: Aggressive quality $aggressiveQuality% → ${compressed.size} bytes")
                if (currentBitmap != bitmap) currentBitmap.recycle()
                return compressed
            }
            aggressiveQuality -= 10
        }

        // Best effort: return at lowest quality
        val bestEffort = compressBitmap(currentBitmap, 10)
        Log.w(TAG, "Phase 3: Best effort at 10% quality → ${bestEffort.size} bytes (target: $maxSize)")
        if (currentBitmap != bitmap) currentBitmap.recycle()
        return bestEffort
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private fun compressBitmap(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    private fun compressJpeg(imageData: ByteArray, quality: Int): ByteArray? {
        return try {
            val bitmap = safeDecodeBitmap(imageData) ?: return null
            val result = compressBitmap(bitmap, quality)
            bitmap.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "compressJpeg failed: ${e.message}")
            null
        }
    }

    /**
     * Generate thumbnail (200x200 aspect-fit, matching iOS)
     * Uses inSampleSize to avoid OOM when decoding large images.
     */
    private fun generateThumbnail(imageData: ByteArray): ByteArray? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageData, 0, imageData.size, boundsOptions)

            val width = boundsOptions.outWidth
            val height = boundsOptions.outHeight
            if (width <= 0 || height <= 0) return null

            val aspectRatio = width.toFloat() / height

            val targetWidth: Int
            val targetHeight: Int
            if (aspectRatio > 1) {
                targetWidth = THUMBNAIL_SIZE
                targetHeight = (THUMBNAIL_SIZE / aspectRatio).toInt().coerceAtLeast(1)
            } else {
                targetWidth = (THUMBNAIL_SIZE * aspectRatio).toInt().coerceAtLeast(1)
                targetHeight = THUMBNAIL_SIZE
            }

            // Decode with inSampleSize targeting thumbnail dimensions (avoids OOM)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(boundsOptions, THUMBNAIL_SIZE, THUMBNAIL_SIZE)
            }
            val sampledBitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size, decodeOptions) ?: return null
            val thumbnail = Bitmap.createScaledBitmap(sampledBitmap, targetWidth, targetHeight, true)
            if (thumbnail !== sampledBitmap) sampledBitmap.recycle()

            val stream = ByteArrayOutputStream()
            thumbnail.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, stream)
            thumbnail.recycle()

            stream.toByteArray()
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during thumbnail generation, skipping")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Thumbnail generation failed: ${e.message}")
            null
        }
    }

    /**
     * Generate video thumbnail (first frame at 1 second).
     *
     * __CHAT_VIDEO_BITRATE_2026_10_09__ Takes the FILE the caller already has on disk.
     * It used to take the bytes and write its own copy of the whole clip to `cacheDir`
     * just to read one frame back out of it.
     */
    private fun generateVideoThumbnail(videoFile: File): ByteArray? {
        if (!videoFile.isFile || videoFile.length() == 0L) return null
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoFile.absolutePath)

            // Get frame at 1 second (matching iOS)
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()

            frame?.let {
                val scaled = Bitmap.createScaledBitmap(it, THUMBNAIL_SIZE, THUMBNAIL_SIZE, true)
                val stream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, stream)
                scaled.recycle()
                it.recycle()
                stream.toByteArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Video thumbnail failed: ${e.message}")
            null
        }
    }

    /**
     * Transcode a video down to fit [maxSize], resolution-capped.
     *
     * 🔧 P0 FIX (2026-08-13 media-steg audit, "Video transcode"). This method used to
     * open a `MediaExtractor`, compute a bitrate it never applied, release everything
     * and `return null` under the comment "Return null to indicate transcoding not yet
     * implemented" — while the caller above reported `isCompressed = false,
     * compressionRatio = 1.0`. Videos were NEVER shrunk on Android; a 45 MB clip
     * "passed" compression and then 413'd at the 50 MB nginx wall.
     *
     * It now runs the real decode→scale→encode pipeline in [VideoTranscoder], at the
     * explicit chat bitrate [VideoPresetLadder.plan] derives
     * (iOS `OSHI/ChatVideoTranscoder.swift`).
     *
     * Two iOS design decisions are carried over deliberately:
     *  - cap resolution AND bitrate, never truncate (`fileLengthLimit` makes the export
     *    stop writing mid-clip — "a shorter video is a worse failure than a larger
     *    one");
     *  - on any failure, fall back to the ORIGINAL bytes rather than failing the send.
     *
     * @param inputFile  the clip, already on disk — the caller owns and deletes it
     * @param outputFile where to put the transcode; populated only on success
     * @return the transcoded bytes, or null to mean "keep the original" — and unlike
     *   before, null now really is a failure signal rather than the normal path.
     */
    private fun transcodeVideo(
        inputFile: File,
        outputFile: File,
        originalSize: Long,
        maxSize: Long,
    ): ByteArray? {
        return try {
            // 🔧 M-9 gate. Probe ONCE here (the transcoder probes again for its own
            // plan, but that read is cheap next to an encode) and skip the encode
            // entirely for a clip that is already the right shape — that is iOS's
            // short-circuit before `compressVideo`. An unreadable probe falls through
            // to `needsTranscode`'s size-only rules.
            val probe = com.oshi.messenger.service.media.VideoTranscoder.probe(inputFile)
            val needs = com.oshi.messenger.service.media.VideoPresetLadder.needsTranscode(
                width = probe?.width ?: 0,
                height = probe?.height ?: 0,
                sizeBytes = originalSize,
                hardCeilingBytes = maxSize,
                // __CHAT_VIDEO_BITRATE_2026_10_09__ Lets the gate also catch a clip in
                // the right resolution band carrying far more bits than a bubble needs.
                durationMs = probe?.durationMs ?: 0L,
                // __MEDIA_INTEROP_2026_09_18__ The codecs decide BEFORE the size does.
                // A probe that failed reports nulls, and nulls are not a promise that
                // the file is H.264/AAC — they transcode, which is the safe direction:
                // the cost of an unnecessary re-encode is one generation of quality,
                // the cost of skipping a necessary one is a video the recipient cannot
                // open at all.
                videoMime = probe?.videoMime,
                audioMime = probe?.audioMime,
            )
            if (!needs) {
                Log.d(TAG, "🎬 Already H.264/AAC, ≤720p-class and in the chat bitrate band (${originalSize / 1024} KB) — no transcode")
                return null
            }

            val ok = com.oshi.messenger.service.media.VideoTranscoder.transcode(
                input = inputFile,
                output = outputFile,
                budgetBytes = maxSize
            )
            if (!ok) {
                Log.w(TAG, "🎬 Transcode unavailable for this clip — keeping original ${originalSize / 1024} KB")
                return null
            }

            val bytes = outputFile.readBytes()
            // A transcode that came out BIGGER is a loss on every axis: more bytes AND a
            // generation of quality gone. Keep the original in that case.
            if (bytes.isEmpty() || bytes.size >= originalSize) {
                Log.w(TAG, "🎬 Transcode not smaller (${bytes.size / 1024} KB vs ${originalSize / 1024} KB) — keeping original")
                return null
            }
            Log.d(TAG, "🎬 Transcoded ${originalSize / 1024} KB → ${bytes.size / 1024} KB")
            bytes
        } catch (e: Exception) {
            Log.e(TAG, "Video transcoding failed: ${e.message}")
            null
        }
    }
}
