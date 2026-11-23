package com.oshi.messenger

import java.io.File
import java.util.zip.Inflater
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __THE_LAUNCHER_ICON_WAS_TOO_TIGHT_2026_11_07__ (Anatole N7: "the logo in the
 * notification is well framed while the home-screen one is too close".)
 *
 * An adaptive icon draws on a 108dp canvas of which the launcher only
 * GUARANTEES the inner 72dp, then masks that to a circle or squircle. The
 * artwork filled 90% of that visible circle, so the ring touched the mask on
 * every side with no breathing room — while the notification icon, which the
 * system pads itself, looked fine. That contrast is exactly what was reported.
 *
 * THE CHECK I FIRST GOT WRONG, kept because it is the instructive part: I
 * measured the artwork against the 108dp CANVAS (58%, inside the 66.7% safe
 * zone) and called it fine. That answers "does it get CLIPPED" — it does not.
 * It does not answer "does it look CRAMPED" — it did. The right denominator is
 * the visible circle, not the canvas.
 *
 * The PNG is decoded by hand because `javax.imageio` is not on the Android
 * unit-test classpath, and a guard that cannot read its own input is no guard.
 */
class LauncherIconFramingTest {

    private val visibleRatio = 72.0 / 108.0
    private val maxFill = 0.75   // beyond this it touches the mask
    private val minFill = 0.55   // below this it looks lost

    @Test
    fun `the launcher artwork is comfortably inside the masked circle`() {
        var checked = 0
        for (d in listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")) {
            val f = File("src/main/res/mipmap-$d/ic_launcher_foreground.png")
            if (!f.exists()) continue
            checked++
            val (w, alpha) = decodeAlpha(f)
            var minX = w; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1
            val h = alpha.size / w
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (alpha[y * w + x].toInt() and 0xff > 8) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            assertTrue("$d: no opaque pixels found — decode failed", maxX >= 0)
            val art = maxOf(maxX - minX + 1, maxY - minY + 1).toDouble()
            val fill = art / (w * visibleRatio)
            assertTrue(
                "$d: artwork fills ${"%.0f".format(fill * 100)}% of the visible circle — " +
                    "past ${(maxFill * 100).toInt()}% it touches the mask and reads as 'too zoomed'",
                fill <= maxFill,
            )
            assertTrue(
                "$d: artwork fills only ${"%.0f".format(fill * 100)}% — it will look lost",
                fill >= minFill,
            )
        }
        assertTrue("no launcher foregrounds found to check", checked >= 4)
    }

    /** Width and the alpha plane of an 8-bit RGBA PNG. */
    private fun decodeAlpha(f: File): Pair<Int, ByteArray> {
        val b = f.readBytes()
        fun int(at: Int) = ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or
            ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)
        var p = 8
        var w = 0; var h = 0; var colour = -1
        val idat = java.io.ByteArrayOutputStream()
        while (p < b.size) {
            val len = int(p)
            val type = String(b, p + 4, 4, Charsets.US_ASCII)
            when (type) {
                "IHDR" -> { w = int(p + 8); h = int(p + 12); colour = b[p + 17].toInt() }
                "IDAT" -> idat.write(b, p + 8, len)
                "IEND" -> p = b.size
            }
            p += 12 + len
        }
        assertTrue("only 8-bit RGBA PNGs are handled here, got colour type $colour", colour == 6)
        val inf = Inflater(); inf.setInput(idat.toByteArray())
        val raw = ByteArray((w * 4 + 1) * h)
        var off = 0
        while (!inf.finished() && off < raw.size) {
            val n = inf.inflate(raw, off, raw.size - off)
            if (n == 0) break
            off += n
        }
        // Undo the per-scanline filters; we only need the alpha byte of each pixel.
        val stride = w * 4
        val cur = ByteArray(stride); val prev = ByteArray(stride)
        val alpha = ByteArray(w * h)
        var q = 0
        for (y in 0 until h) {
            val filter = raw[q].toInt() and 0xff; q++
            System.arraycopy(raw, q, cur, 0, stride); q += stride
            for (i in 0 until stride) {
                val a = if (i >= 4) cur[i - 4].toInt() and 0xff else 0
                val up = prev[i].toInt() and 0xff
                val ul = if (i >= 4) prev[i - 4].toInt() and 0xff else 0
                val x = cur[i].toInt() and 0xff
                val v = when (filter) {
                    0 -> x
                    1 -> x + a
                    2 -> x + up
                    3 -> x + (a + up) / 2
                    4 -> {
                        val pp = a + up - ul
                        val pa = Math.abs(pp - a); val pb = Math.abs(pp - up); val pc = Math.abs(pp - ul)
                        x + if (pa <= pb && pa <= pc) a else if (pb <= pc) up else ul
                    }
                    else -> x
                }
                cur[i] = (v and 0xff).toByte()
            }
            for (x in 0 until w) alpha[y * w + x] = cur[x * 4 + 3]
            System.arraycopy(cur, 0, prev, 0, stride)
        }
        return w to alpha
    }
}
