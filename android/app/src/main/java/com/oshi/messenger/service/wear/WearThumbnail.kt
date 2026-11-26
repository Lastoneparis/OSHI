package com.oshi.messenger.service.wear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import com.oshi.wearbridge.WearProtocol
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * __WEAR_POLISH_2026_11_25__ A photo, made small enough for the wrist: longest
 * side [WearProtocol.THUMB_PX], JPEG, at most [WearProtocol.MAX_THUMB_BYTES].
 * Made on demand from the phone's decrypted bytes; never written anywhere.
 * The watch keeps it in memory and drops it when the app leaves the screen.
 */
object WearThumbnail {

    fun jpeg(source: ByteArray, maxPx: Int = WearProtocol.THUMB_PX, maxBytes: Int = WearProtocol.MAX_THUMB_BYTES): ByteArray? {
        val bitmap = decode(source, maxPx) ?: return null
        try {
            var quality = 72
            while (quality >= 30) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= maxBytes) return out.toByteArray()
                quality -= 14
            }
            return null
        } finally {
            bitmap.recycle()
        }
    }

    /** Target size keeping the aspect ratio, longest side [maxPx], never upscaled. */
    fun targetSize(width: Int, height: Int, maxPx: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val longest = maxOf(width, height)
        if (longest <= maxPx) return width to height
        val scale = maxPx.toDouble() / longest
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    private fun decode(bytes: ByteArray, maxPx: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies the EXIF orientation, so a portrait photo stays upright.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val (w, h) = targetSize(info.size.width, info.size.height, maxPx)
                decoder.setTargetSize(w, h)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val (w, h) = targetSize(raw.width, raw.height, maxPx)
            if (w == raw.width && h == raw.height) raw
            else Bitmap.createScaledBitmap(raw, w, h, true).also { if (it !== raw) raw.recycle() }
        }
    }.getOrNull()
}
