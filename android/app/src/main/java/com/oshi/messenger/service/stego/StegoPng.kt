package com.oshi.messenger.service.stego

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Minimal PNG reader/writer for STEGO-v1 (spec §5).
 *
 * Why not BitmapFactory / Bitmap.compress: Android decodes into premultiplied
 * ARGB and may colour-manage; iOS draws through a colour space. Either can move
 * a sample by one, which is the whole message. Reading the samples ourselves is
 * the only way both platforms see the same bits. Writing ourselves guarantees
 * there are no ancillary chunks (no metadata, no gAMA/iCCP).
 */
object StegoPng {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val MAX_PIXELS = 4096L * 4096L

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(SIGNATURE)

    // ── Writer ──────────────────────────────────────────────────────────────

    fun encode(image: StegoV1.RgbImage): ByteArray {
        val w = image.width; val h = image.height; val stride = w * 3
        val raw = ByteArrayOutputStream(h * (stride + 1))
        val prev = ByteArray(stride)
        val cand = Array(5) { ByteArray(stride) }
        for (y in 0 until h) {
            val row = image.rgb.copyOfRange(y * stride, (y + 1) * stride)
            // Adaptive filter: smallest sum of absolute signed residuals (libpng heuristic).
            var best = 0; var bestScore = Long.MAX_VALUE
            for (f in 0..4) {
                filterRow(f, row, if (y == 0) null else prev, cand[f])
                var score = 0L
                for (b in cand[f]) score += kotlin.math.abs(b.toInt())
                if (score < bestScore) { bestScore = score; best = f }
            }
            raw.write(best); raw.write(cand[best])
            row.copyInto(prev)
        }
        val deflater = Deflater(6)
        deflater.setInput(raw.toByteArray()); deflater.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!deflater.finished()) { val n = deflater.deflate(buf); z.write(buf, 0, n) }
        deflater.end()

        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        val ihdr = ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(2).put(0).put(0).put(0).array()
        chunk(out, "IHDR", ihdr)
        val zb = z.toByteArray()
        var off = 0
        while (off < zb.size) { val n = minOf(1 shl 16, zb.size - off); chunk(out, "IDAT", zb.copyOfRange(off, off + n)); off += n }
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        out.write(t); out.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - b); val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun filterRow(f: Int, row: ByteArray, prev: ByteArray?, out: ByteArray, bpp: Int = 3) {
        for (i in row.indices) {
            val x = row[i].toInt() and 0xFF
            val a = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
            val b = prev?.get(i)?.toInt()?.and(0xFF) ?: 0
            val c = if (prev != null && i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
            out[i] = when (f) {
                0 -> x; 1 -> x - a; 2 -> x - b; 3 -> x - ((a + b) ushr 1); else -> x - paeth(a, b, c)
            }.toByte()
        }
    }

    // ── Reader ──────────────────────────────────────────────────────────────

    class UnsupportedPng(msg: String) : Exception(msg)

    /**
     * Exact 8-bit samples of a non-interlaced 8-bit PNG, as RGB (alpha dropped).
     *
     * Throws ONLY [UnsupportedPng]. __STEGO_DAMAGED_PNG_2026_11_18__ A file that
     * was damaged on its way (bit rot in the zlib stream, a 5-byte IHDR, a
     * truncated header) used to escape as `DataFormatException` /
     * `BufferUnderflowException`; every caller catches [UnsupportedPng] only, so
     * opening such a photo crashed the app instead of answering "no hidden
     * message for you" (found by tools/covert-regression/netem/stego on the
     * emulator: 4 of 41 hostile copies).
     */
    fun decode(bytes: ByteArray): StegoV1.RgbImage = try {
        decodeStrict(bytes)
    } catch (e: UnsupportedPng) {
        throw e
    } catch (e: Exception) {
        throw UnsupportedPng("damaged PNG (${e.javaClass.simpleName})")
    }

    private fun decodeStrict(bytes: ByteArray): StegoV1.RgbImage {
        if (!isPng(bytes)) throw UnsupportedPng("not a PNG")
        val buf = ByteBuffer.wrap(bytes, 8, bytes.size - 8)
        var w = 0; var h = 0; var colorType = -1
        var palette: ByteArray? = null
        val idat = ByteArrayOutputStream()
        while (buf.remaining() >= 12) {
            val len = buf.int
            if (len < 0 || len > buf.remaining() - 8) throw UnsupportedPng("truncated chunk")
            val type = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
            val data = ByteArray(len).also { buf.get(it) }
            buf.int // CRC (not checked: a broken CRC simply yields no message)
            when (type) {
                "IHDR" -> {
                    val d = ByteBuffer.wrap(data)
                    w = d.int; h = d.int
                    val depth = d.get().toInt(); colorType = d.get().toInt()
                    d.get(); d.get(); val interlace = d.get().toInt()
                    if (depth != 8) throw UnsupportedPng("bit depth $depth")
                    if (interlace != 0) throw UnsupportedPng("interlaced")
                    if (colorType !in intArrayOf(0, 2, 3, 4, 6)) throw UnsupportedPng("colour type $colorType")
                    if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) throw UnsupportedPng("size")
                }
                "PLTE" -> palette = data
                "IDAT" -> idat.write(data)
                "IEND" -> break
            }
        }
        if (w == 0) throw UnsupportedPng("no IHDR")
        val channels = when (colorType) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; else -> 4 }
        val stride = w * channels
        val expected = (stride + 1).toLong() * h
        val inflater = Inflater()
        inflater.setInput(idat.toByteArray())
        val raw = ByteArray(expected.toInt())
        var got = 0
        try {
            while (got < raw.size && !inflater.finished()) {
                val n = inflater.inflate(raw, got, raw.size - got)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                got += n
            }
        } finally { inflater.end() }
        if (got != raw.size) throw UnsupportedPng("short image data")

        val rgb = ByteArray(w * h * 3)
        val prev = ByteArray(stride); val cur = ByteArray(stride)
        for (y in 0 until h) {
            val base = y * (stride + 1)
            val f = raw[base].toInt()
            for (i in 0 until stride) {
                val x = raw[base + 1 + i].toInt() and 0xFF
                val a = if (i >= channels) cur[i - channels].toInt() and 0xFF else 0
                val b = if (y > 0) prev[i].toInt() and 0xFF else 0
                val c = if (y > 0 && i >= channels) prev[i - channels].toInt() and 0xFF else 0
                cur[i] = when (f) {
                    0 -> x; 1 -> x + a; 2 -> x + b; 3 -> x + ((a + b) ushr 1); 4 -> x + paeth(a, b, c)
                    else -> throw UnsupportedPng("filter $f")
                }.toByte()
            }
            for (px in 0 until w) {
                val o = (y * w + px) * 3
                when (colorType) {
                    0, 4 -> { val g = cur[px * channels]; rgb[o] = g; rgb[o + 1] = g; rgb[o + 2] = g }
                    3 -> {
                        val p = palette ?: throw UnsupportedPng("no palette")
                        val idx = (cur[px].toInt() and 0xFF) * 3
                        if (idx + 2 >= p.size) throw UnsupportedPng("palette index")
                        rgb[o] = p[idx]; rgb[o + 1] = p[idx + 1]; rgb[o + 2] = p[idx + 2]
                    }
                    else -> { rgb[o] = cur[px * channels]; rgb[o + 1] = cur[px * channels + 1]; rgb[o + 2] = cur[px * channels + 2] }
                }
            }
            cur.copyInto(prev)
        }
        return StegoV1.RgbImage(w, h, rgb)
    }
}
