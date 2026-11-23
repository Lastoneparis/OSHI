package com.oshi.messenger.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.util.Log

/**
 * Put a decoded photo the right way up before anyone crops it.
 *
 * __IMAGE_CROP_2026_11_02__ (N13) WHY THIS IS PART OF THE CROP and not a
 * separate nicety: a camera photo is almost always stored LANDSCAPE with an
 * EXIF `Orientation` tag telling the viewer to turn it. `BitmapFactory` ignores
 * that tag. Gallery and chat get away with it because they hand the ORIGINAL
 * FILE around and the renderer reads the tag — but a crop screen has to show
 * the pixels, and a crop window drawn over a picture lying on its side cuts out
 * the wrong part of it. Worse, the cropped bitmap is re-encoded with no EXIF at
 * all, so a sideways crop stays sideways everywhere, forever.
 *
 * Uses `android.media.ExifInterface`, deprecated but present since API 5 and
 * able to read a stream since API 24 (minSdk here is 26), rather than adding
 * `androidx.exifinterface` for one tag.
 */
object ImageOrientation {

    /** Degrees clockwise to turn a decoded bitmap, from an EXIF orientation value. */
    fun rotationFor(exifOrientation: Int): Int = when (exifOrientation) {
        3, 4 -> 180      // 180°, and 180° + mirrored
        6, 5 -> 90       // 90° CW, and 90° + mirrored
        8, 7 -> 270      // 270° CW, and 270° + mirrored
        else -> 0        // 1 (upright), 0/2 (absent or mirrored only), anything unknown
    }

    /**
     * The mirror flag EXIF carries alongside the rotation (values 2, 4, 5, 7).
     *
     * Honoured as well as the rotation: a mirrored selfie that is only rotated
     * comes out back-to-front, which on a photo of text is unreadable.
     */
    fun mirroredFor(exifOrientation: Int): Boolean =
        exifOrientation == 2 || exifOrientation == 4 || exifOrientation == 5 || exifOrientation == 7

    /** Apply [exifOrientation] to [bitmap]. Returns the same instance when there is nothing to do. */
    fun applied(bitmap: Bitmap, exifOrientation: Int): Bitmap {
        val rotation = rotationFor(exifOrientation)
        val mirrored = mirroredFor(exifOrientation)
        if (rotation == 0 && !mirrored) return bitmap
        val m = Matrix()
        if (rotation != 0) m.postRotate(rotation.toFloat())
        if (mirrored) m.postScale(-1f, 1f)
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }.getOrDefault(bitmap)
    }

    private fun tag(read: () -> Int): Int = runCatching { read() }.getOrDefault(1)

    /** Orientation tag of [uri], or 1 (upright) when there is none or it cannot be read. */
    @Suppress("DEPRECATION")
    fun orientationOf(context: Context, uri: Uri): Int = tag {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            android.media.ExifInterface(stream).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION, 1
            )
        } ?: 1
    }

    /** Orientation tag inside JPEG [bytes], or 1. */
    @Suppress("DEPRECATION")
    fun orientationOf(bytes: ByteArray): Int = tag {
        java.io.ByteArrayInputStream(bytes).use { stream ->
            android.media.ExifInterface(stream).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION, 1
            )
        }
    }

    /** Convenience: decode-time orientation applied, failures logged and ignored. */
    fun upright(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val o = orientationOf(context, uri)
        if (o != 1) Log.d("ImageOrientation", "EXIF orientation $o → rotate ${rotationFor(o)}°")
        return applied(bitmap, o)
    }
}
