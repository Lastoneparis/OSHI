package com.oshi.messenger.service.stego

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.oshi.messenger.data.model.Contact
import com.oshi.messenger.network.encryption.CryptoManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Android glue for "Disguise as photo" (STEGO-v1, docs/stego/STEGO-v1.md):
 * cover images in, PNG bytes out, share sheet, and reveal against the contact
 * list. The format and crypto live in [StegoV1] / [StegoPng] (pure JVM, tested).
 */
object PhotoDisguise {
    /** Long side of the cover after downscaling. Bigger = more capacity, bigger file. */
    const val MAX_COVER_SIDE = 1600

    data class Revealed(
        val senderPublicKey: String,
        val senderName: String?,
        val payload: StegoV1.Payload,
    )

    sealed class Outcome {
        data class Found(val revealed: Revealed) : Outcome()
        data class Damaged(val senderName: String?) : Outcome()
        object NotForYou : Outcome()
    }

    private fun b64(bytes: ByteArray) = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    fun keyBytes(publicKeyB64: String): ByteArray? = try {
        val std = publicKeyB64.replace('-', '+').replace('_', '/')
        val padded = std + "=".repeat((4 - std.length % 4) % 4)
        android.util.Base64.decode(padded, android.util.Base64.NO_WRAP).takeIf { it.size == 32 }
    } catch (_: Exception) { null }

    // ── Covers ──────────────────────────────────────────────────────────────

    fun coverFromBitmap(source: Bitmap): StegoV1.RgbImage {
        val scale = minOf(1f, MAX_COVER_SIDE.toFloat() / maxOf(source.width, source.height))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(
            source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true
        ) else source
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.copy(Bitmap.Config.ARGB_8888, false).getPixels(px, 0, w, 0, 0, w, h)
        val rgb = ByteArray(w * h * 3)
        for (i in px.indices) {
            val c = px[i]
            rgb[i * 3] = (c shr 16).toByte(); rgb[i * 3 + 1] = (c shr 8).toByte(); rgb[i * 3 + 2] = c.toByte()
        }
        return StegoV1.RgbImage(w, h, rgb)
    }

    fun coverFromUri(context: Context, uri: Uri): StegoV1.RgbImage? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_COVER_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }?.let { coverFromBitmap(it) }
    } catch (_: Exception) { null }

    /**
     * An innocuous generated landscape (sky gradient, layered hills, film grain).
     * Grain matters: a perfectly smooth synthetic image would make the noisy LSB
     * plane stand out more. Not part of the wire format.
     */
    fun generatedCover(seed: Long = System.nanoTime(), w: Int = 1280, h: Int = 960): StegoV1.RgbImage {
        val r = java.util.Random(seed)
        val hue = r.nextInt(3)
        val sky = when (hue) { 0 -> intArrayOf(255, 170, 120, 90, 120, 200); 1 -> intArrayOf(120, 180, 240, 220, 235, 250); else -> intArrayOf(250, 200, 150, 160, 120, 170) }
        val hills = Array(4) { doubleArrayOf(0.45 + it * 0.12 + r.nextDouble() * 0.05, r.nextDouble() * 2 * PI, 1.5 + r.nextDouble() * 2.5, 0.03 + r.nextDouble() * 0.05) }
        val hillColor = Array(4) { k -> intArrayOf(40 + k * 18 + r.nextInt(20), 70 + k * 20 + r.nextInt(30), 50 + k * 10 + r.nextInt(20)) }
        val sunX = 0.2 + r.nextDouble() * 0.6; val sunY = 0.25 + r.nextDouble() * 0.15
        val rgb = ByteArray(w * h * 3)
        for (y in 0 until h) {
            val fy = y.toDouble() / h
            for (x in 0 until w) {
                val fx = x.toDouble() / w
                var c = IntArray(3) { (sky[it] + (sky[it + 3] - sky[it]) * fy * 1.6).toInt() }
                val d = kotlin.math.hypot(fx - sunX, (fy - sunY) * h / w)
                if (d < 0.05) c = intArrayOf(255, 240, 210) else if (d < 0.12) {
                    val t = (0.12 - d) / 0.07; c = IntArray(3) { (c[it] + (255 - c[it]) * t * 0.5).toInt() }
                }
                for (k in hills.indices) {
                    val (base, phase, freq, amp) = hills[k].let { listOf(it[0], it[1], it[2], it[3]) }
                    val ridge = base + amp * sin(fx * freq * 2 * PI + phase) + amp * 0.4 * sin(fx * freq * 5.3 * PI + phase * 2)
                    if (fy > ridge) { val shade = (fy - ridge) * 60; c = IntArray(3) { (hillColor[k][it] - shade).toInt() } }
                }
                val o = (y * w + x) * 3
                for (ch in 0..2) rgb[o + ch] = (c[ch] + r.nextInt(7) - 3).coerceIn(0, 255).toByte()
            }
        }
        return StegoV1.RgbImage(w, h, rgb)
    }

    // ── Hide / share ────────────────────────────────────────────────────────

    /** PNG bytes carrying [payload] for [recipientPublicKeyB64], from this device's identity. */
    fun hide(crypto: CryptoManager, recipientPublicKeyB64: String, cover: StegoV1.RgbImage, payload: StegoV1.Payload): ByteArray {
        val recipient = keyBytes(recipientPublicKeyB64) ?: throw IllegalArgumentException("Invalid contact key")
        val out = StegoV1.hide(cover, payload, crypto.exportPrivateKeyBytes(), crypto.getPublicKey(), recipient)
        return StegoPng.encode(out)
    }

    /** Writes the PNG under cache/disguise with a camera-like name and returns a share intent. */
    fun shareIntent(context: Context, png: ByteArray): Intent {
        val dir = File(context.cacheDir, "disguise").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L) it.delete() }
        val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        val file = File(dir, name).apply { writeBytes(png) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ── Reveal ──────────────────────────────────────────────────────────────

    /** Exact samples for a PNG; the platform decoder for anything else (which then simply holds no message). */
    fun imageFromBytes(bytes: ByteArray): StegoV1.RgbImage? {
        if (StegoPng.isPng(bytes)) {
            try { return StegoPng.decode(bytes) } catch (_: StegoPng.UnsupportedPng) { }
        }
        // __STEGO_DAMAGED_PNG_2026_11_18__ Never throw out of here (the reveal sheet and
        // the inbound-share check call it from a coroutine with no handler), and never
        // decode a picture that cannot carry a message: one larger than the format's
        // 4096 x 4096 cap is refused from its header alone. Decoding first and checking
        // after, as before, meant a 48-MP camera JPEG picked in "Reveal" allocated
        // ~190 MB of ARGB plus the same again as IntArray — an OutOfMemoryError.
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth.toLong() * bounds.outHeight * 3 > StegoV1.MAX_SLOTS) null
            else platformImage(bytes)
        } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
    }

    private fun platformImage(bytes: ByteArray): StegoV1.RgbImage? {
        val opts = BitmapFactory.Options().apply { inPremultiplied = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        if (bmp.width.toLong() * bmp.height * 3 > StegoV1.MAX_SLOTS) return null
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val rgb = ByteArray(w * h * 3)
        for (i in px.indices) { val c = px[i]; rgb[i * 3] = (c shr 16).toByte(); rgb[i * 3 + 1] = (c shr 8).toByte(); rgb[i * 3 + 2] = c.toByte() }
        return StegoV1.RgbImage(w, h, rgb)
    }

    fun reveal(crypto: CryptoManager, bytes: ByteArray, contacts: List<Contact>): Outcome {
        val image = imageFromBytes(bytes) ?: return Outcome.NotForYou
        val byKey = contacts.mapNotNull { c -> keyBytes(c.publicKey)?.let { it to c } }
        val result = StegoV1.reveal(image, crypto.exportPrivateKeyBytes(), crypto.getPublicKey(), byKey.map { it.first })
        fun nameOf(k: ByteArray) = byKey.firstOrNull { it.first.contentEquals(k) }?.second?.alias?.takeIf { it.isNotBlank() }
        return when (result) {
            is StegoV1.RevealResult.Found -> Outcome.Found(
                Revealed(byKey.first { it.first.contentEquals(result.senderPublicKey) }.second.publicKey, nameOf(result.senderPublicKey), result.payload)
            )
            is StegoV1.RevealResult.Damaged -> Outcome.Damaged(nameOf(result.senderPublicKey))
            StegoV1.RevealResult.NotForYou -> Outcome.NotForYou
        }
    }

    fun readUri(context: Context, uri: Uri, limit: Int = 64 * 1024 * 1024): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(65536)
            while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > limit) return null }
            out.toByteArray()
        }
    } catch (_: Exception) { null }

    /** Saves a revealed attachment to cache and returns a VIEW/SEND-able uri. */
    fun attachmentUri(context: Context, file: StegoV1.Attachment): Uri {
        val dir = File(context.cacheDir, "disguise/revealed").apply { mkdirs() }
        val safe = file.name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifBlank { "attachment" }
        val f = File(dir, safe).apply { writeBytes(file.data) }
        return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
    }
}
