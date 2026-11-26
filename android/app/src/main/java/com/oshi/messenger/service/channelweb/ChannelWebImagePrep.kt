package com.oshi.messenger.service.channelweb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.oshi.messenger.ui.components.ImageOrientation
import java.io.ByteArrayOutputStream

/**
 * __CHANNEL_WEB_2026_11_26__ Spec §3.3 "Clients should still convert HEIC to JPEG, apply the
 * EXIF orientation and drop EXIF/GPS before upload; resize to ≤ 2560 px on the long side,
 * quality ~0.82."
 *
 * Re-encoding through a Bitmap drops every metadata block (EXIF, GPS, XMP, maker notes) by
 * construction. GIFs are passed through untouched (re-encoding keeps one frame; the server
 * strips their comment/application blocks, §7.4).
 */
object ChannelWebImagePrep {
    const val MAX_SIDE = 2560
    const val AVATAR_SIDE = 640
    const val MAX_UPLOAD_BYTES = 10 * 1024 * 1024
    private const val QUALITY = 82

    fun isGif(b: ByteArray): Boolean =
        b.size >= 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == '8'.code.toByte()

    /** Upload-ready bytes, or null when the image cannot be decoded / is too large. */
    fun prepare(input: ByteArray, maxSide: Int = MAX_SIDE): ByteArray? {
        if (isGif(input)) return input.takeIf { it.size <= MAX_UPLOAD_BYTES }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(input, 0, input.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val rotated = ImageOrientation.applied(decoded, ImageOrientation.orientationOf(input))
        val long = maxOf(rotated.width, rotated.height)
        val scaled = if (long > maxSide) {
            val f = maxSide.toFloat() / long
            Bitmap.createScaledBitmap(rotated, (rotated.width * f).toInt().coerceAtLeast(1), (rotated.height * f).toInt().coerceAtLeast(1), true)
        } else rotated
        val out = ByteArrayOutputStream()
        // PNG keeps transparency (stickers, logos); everything else becomes a JPEG.
        val png = scaled.hasAlpha() && looksLikePng(input)
        scaled.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, QUALITY, out)
        if (scaled !== rotated) scaled.recycle()
        if (rotated !== decoded) rotated.recycle()
        decoded.recycle()
        return out.toByteArray().takeIf { it.isNotEmpty() && it.size <= MAX_UPLOAD_BYTES }
    }

    private fun looksLikePng(b: ByteArray) = b.size > 8 && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
}
