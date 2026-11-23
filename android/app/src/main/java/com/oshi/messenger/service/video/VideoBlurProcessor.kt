package com.oshi.messenger.service.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import com.oshi.messenger.service.BlurMode

/**
 * VideoBlurProcessor - iOS Compatible Video Blur Effects
 *
 * Implements 6 blur modes matching iOS VideoCallManager:
 * - NONE: passthrough
 * - BACKGROUND_BLUR: person segmentation + gaussian blur on background
 * - FULL_BLUR: gaussian blur on entire frame
 * - PIXELATE: scale down/up (blockSize=12)
 * - HEAVY_PIXELATE: blockSize=24
 * - ULTRA_PIXELATE: blockSize=48
 */
class VideoBlurProcessor(private val context: Context) {

    companion object {
        private const val TAG = "VideoBlurProcessor"
    }

    // ML Kit Selfie Segmenter (lazy init)
    private var selfieSegmenter: com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions? = null
    private var segmenter: com.google.mlkit.vision.segmentation.Segmenter? = null

    init {
        try {
            val options = com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions.Builder()
                .setDetectorMode(com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions.STREAM_MODE)
                .build()
            segmenter = com.google.mlkit.vision.segmentation.Segmentation.getClient(options)
            Log.d(TAG, "ML Kit Selfie Segmenter initialized")
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit Selfie Segmenter not available: ${e.message}")
        }
    }

    /**
     * Process a video frame with the specified blur mode.
     * Returns processed bitmap (or original if NONE).
     */
    fun processFrame(bitmap: Bitmap, blurMode: BlurMode): Bitmap {
        return when (blurMode) {
            BlurMode.NONE -> bitmap
            BlurMode.BACKGROUND_BLUR -> applyBackgroundBlur(bitmap)
            BlurMode.FULL_BLUR -> applyGaussianBlur(bitmap, 15)
            BlurMode.PIXELATE -> applyPixelation(bitmap, 12)
            BlurMode.HEAVY_PIXELATE -> applyPixelation(bitmap, 24)
            BlurMode.ULTRA_PIXELATE -> applyPixelation(bitmap, 48)
        }
    }

    /**
     * Apply gaussian blur to the entire frame.
     * Uses scale-down/up technique for efficient blur.
     */
    private fun applyGaussianBlur(bitmap: Bitmap, radius: Int): Bitmap {
        try {
            // Scale down then scale up = efficient blur
            val scaleFactor = maxOf(1, radius / 2)
            val smallWidth = maxOf(1, bitmap.width / scaleFactor)
            val smallHeight = maxOf(1, bitmap.height / scaleFactor)

            val small = Bitmap.createScaledBitmap(bitmap, smallWidth, smallHeight, true)
            val blurred = Bitmap.createScaledBitmap(small, bitmap.width, bitmap.height, true)
            small.recycle()
            return blurred
        } catch (e: Exception) {
            Log.e(TAG, "Gaussian blur failed: ${e.message}")
            return bitmap
        }
    }

    /**
     * Apply pixelation effect by scaling down to block size then back up.
     */
    private fun applyPixelation(bitmap: Bitmap, blockSize: Int): Bitmap {
        try {
            val pixelWidth = maxOf(1, bitmap.width / blockSize)
            val pixelHeight = maxOf(1, bitmap.height / blockSize)

            // Scale down to create blocky pixels
            val small = Bitmap.createScaledBitmap(bitmap, pixelWidth, pixelHeight, false)
            // Scale back up without interpolation for sharp pixel edges
            val pixelated = Bitmap.createScaledBitmap(small, bitmap.width, bitmap.height, false)
            small.recycle()
            return pixelated
        } catch (e: Exception) {
            Log.e(TAG, "Pixelation failed: ${e.message}")
            return bitmap
        }
    }

    /**
     * Apply background blur using ML Kit person segmentation.
     * Keeps person sharp, blurs background.
     * Falls back to full blur if segmentation unavailable.
     */
    private fun applyBackgroundBlur(bitmap: Bitmap): Bitmap {
        val seg = segmenter ?: return applyGaussianBlur(bitmap, 15) // fallback

        try {
            val inputImage = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
            // Process synchronously for real-time video (blocking)
            val result = com.google.android.gms.tasks.Tasks.await(seg.process(inputImage))

            val mask = result.buffer
            val maskWidth = result.width
            val maskHeight = result.height

            // Create blurred background
            val blurred = applyGaussianBlur(bitmap, 20)

            // Composite: person from original, background from blurred
            val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawBitmap(blurred, 0f, 0f, null)

            // Scale mask to bitmap size and apply
            val scaleX = bitmap.width.toFloat() / maskWidth
            val scaleY = bitmap.height.toFloat() / maskHeight

            mask.rewind()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

            val outputPixels = IntArray(bitmap.width * bitmap.height)
            output.getPixels(outputPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val maskX = (x / scaleX).toInt().coerceIn(0, maskWidth - 1)
                    val maskY = (y / scaleY).toInt().coerceIn(0, maskHeight - 1)
                    val maskIndex = maskY * maskWidth + maskX

                    if (maskIndex < mask.limit()) {
                        mask.position(maskIndex * 4) // Float buffer
                        val confidence = if (mask.remaining() >= 4) mask.float else 0f

                        if (confidence > 0.7f) {
                            // Person pixel - use original
                            outputPixels[y * bitmap.width + x] = pixels[y * bitmap.width + x]
                        }
                        // else: keep blurred background
                    }
                }
            }

            output.setPixels(outputPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            blurred.recycle()
            return output

        } catch (e: Exception) {
            Log.w(TAG, "Background blur segmentation failed, using full blur: ${e.message}")
            return applyGaussianBlur(bitmap, 15)
        }
    }

    fun cleanup() {
        try {
            segmenter?.close()
        } catch (_: Exception) {}
    }
}

// BlurMode is defined in com.oshi.messenger.service.VideoCallManager (imported above)
