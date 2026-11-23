package com.oshi.messenger.service.stego

import com.oshi.messenger.network.v2.OSHICryptoV2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** STEGO-v1 behaviour (docs/stego/STEGO-v1.md). Cross-platform bytes: [StegoV1VectorTest]. */
class StegoV1Test {

    private val alice = OSHICryptoV2.generateX25519()
    private val bob = OSHICryptoV2.generateX25519()
    private val eve = OSHICryptoV2.generateX25519()

    private fun noisyCover(w: Int = 320, h: Int = 240, seed: Long = 7): StegoV1.RgbImage {
        val r = java.util.Random(seed)
        val px = ByteArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 3
            px[o] = ((x * 255 / w) + r.nextInt(7) - 3).coerceIn(0, 255).toByte()
            px[o + 1] = ((y * 255 / h) + r.nextInt(7) - 3).coerceIn(0, 255).toByte()
            px[o + 2] = (128 + r.nextInt(9) - 4).toByte()
        }
        return StegoV1.RgbImage(w, h, px)
    }

    private fun hideFor(cover: StegoV1.RgbImage, p: StegoV1.Payload, to: ByteArray = bob.pub) =
        StegoV1.hide(cover, p, alice.priv, alice.pub, to)

    private fun viaPng(img: StegoV1.RgbImage) = StegoPng.decode(StegoPng.encode(img))

    @Test
    fun roundTripTextAndFileThroughPng() {
        val file = StegoV1.Attachment("plan.pdf", "application/pdf", ByteArray(3000) { (it * 31).toByte() })
        val p = StegoV1.Payload("Rendez-vous à 18h — porte B 🚪", 1_763_460_000_000, file)
        val png = StegoPng.encode(hideFor(noisyCover(), p))
        assertTrue(StegoPng.isPng(png))
        val r = StegoV1.reveal(StegoPng.decode(png), bob.priv, bob.pub, listOf(eve.pub, alice.pub))
        r as StegoV1.RevealResult.Found
        assertArrayEquals(alice.pub, r.senderPublicKey)
        assertEquals(p.text, r.payload.text)
        assertEquals(p.createdAtMs, r.payload.createdAtMs)
        assertEquals("plan.pdf", r.payload.file!!.name)
        assertArrayEquals(file.data, r.payload.file!!.data)
    }

    @Test
    fun wrongRecipientSeesOnlyAPhoto() {
        val img = viaPng(hideFor(noisyCover(), StegoV1.Payload("secret", 1)))
        // Eve has Alice as a contact but is not the recipient.
        assertEquals(StegoV1.RevealResult.NotForYou, StegoV1.reveal(img, eve.priv, eve.pub, listOf(alice.pub, bob.pub)))
        // Bob without Alice in his contacts cannot even locate it.
        assertEquals(StegoV1.RevealResult.NotForYou, StegoV1.reveal(img, bob.priv, bob.pub, listOf(eve.pub)))
    }

    @Test
    fun plainPhotoIsNotForYou() {
        assertEquals(StegoV1.RevealResult.NotForYou, StegoV1.reveal(noisyCover(), bob.priv, bob.pub, listOf(alice.pub)))
    }

    @Test
    fun tamperedBodyIsReportedDamagedNeverWrongText() {
        val p = StegoV1.Payload("x".repeat(2000), 1)
        val img = hideFor(noisyCover(), p)
        // Flip the LSB of every 7th sample: the header region survives with
        // probability ~0, so also try a single-slot flip deep in the body.
        var damagedOrNot = 0
        for (k in listOf(1, 7, 101)) {
            val copy = img.rgb.copyOf()
            var i = 0
            while (i < copy.size) { copy[i] = (copy[i].toInt() xor 1).toByte(); i += 997 * k }
            val r = StegoV1.reveal(StegoV1.RgbImage(img.width, img.height, copy), bob.priv, bob.pub, listOf(alice.pub))
            // A flip that misses every used slot is harmless (the fill is noise), so
            // Found is legitimate — but only ever with the ORIGINAL text.
            if (r is StegoV1.RevealResult.Found) assertEquals(p.text, r.payload.text)
            damagedOrNot++
        }
        assertEquals(3, damagedOrNot)
    }

    @Test
    fun singleBodyBitFlipIsDamaged() {
        val p = StegoV1.Payload("y".repeat(500), 1)
        val img = hideFor(noisyCover(), p)
        // Locate a slot that carries a BODY bit using the same order as the reader.
        val order = StegoV1.SlotOrder(StegoV1.orderKey(OSHICryptoV2.dh(bob.priv, alice.pub)), img.slots)
        repeat(StegoV1.PREFIX * 8 + 100) { order.next() }
        val slot = order.next()
        val copy = img.rgb.copyOf(); copy[slot] = (copy[slot].toInt() xor 1).toByte()
        val r = StegoV1.reveal(StegoV1.RgbImage(img.width, img.height, copy), bob.priv, bob.pub, listOf(alice.pub))
        assertTrue(r is StegoV1.RevealResult.Damaged)
    }

    @Test
    fun capacityIsEnforcedAndReported() {
        val small = noisyCover(40, 30)        // 3600 slots → 450 bytes − 74 = 376
        assertEquals(376, StegoV1.capacityBytes(small.slots))
        try {
            hideFor(small, StegoV1.Payload("z".repeat(400), 1))
            fail("expected CapacityException")
        } catch (e: StegoV1.CapacityException) {
            assertEquals(376, e.available)
        }
        // Exactly at capacity still works: kind(1)+ts(8)+len(4)+hasFile(1) = 14 bytes overhead.
        val exact = StegoV1.Payload("z".repeat(376 - 14), 1)
        val r = StegoV1.reveal(hideFor(small, exact), bob.priv, bob.pub, listOf(alice.pub))
        assertEquals(exact.text, (r as StegoV1.RevealResult.Found).payload.text)
    }

    /**
     * Random fill: the LSB plane must not reveal the payload size. Compare a
     * 10-byte and a near-capacity payload — the fraction of LSBs equal to 1 is
     * ~0.5 in both, and the fraction of changed samples is ~0.5 in both.
     */
    @Test
    fun randomFillHidesPayloadSize() {
        val cover = noisyCover(200, 150)
        val tiny = hideFor(cover, StegoV1.Payload("hi", 1))
        val big = hideFor(cover, StegoV1.Payload("w".repeat(StegoV1.capacityBytes(cover.slots) - 14), 1))
        for (img in listOf(tiny, big)) {
            val ones = img.rgb.count { (it.toInt() and 1) == 1 }.toDouble() / img.slots
            val changed = img.rgb.indices.count { img.rgb[it] != cover.rgb[it] }.toDouble() / img.slots
            assertEquals(0.5, ones, 0.01)
            assertEquals(0.5, changed, 0.01)
            // ±1 only — never more.
            assertTrue(img.rgb.indices.all { kotlin.math.abs((img.rgb[it].toInt() and 0xFF) - (cover.rgb[it].toInt() and 0xFF)) <= 1 })
        }
    }

    /**
     * The lossy step of JPEG — 8×8 DCT, quantisation at a given quality, inverse
     * DCT — applied per channel (android.jar has no JPEG encoder on the JVM test
     * classpath; the iOS harness does a real JPEG round trip through ImageIO).
     * Even quality 95 moves enough samples by ±1 that nothing is found, and the
     * result is the clean NotForYou, never garbage.
     */
    private fun jpegLike(img: StegoV1.RgbImage, quality: Int): StegoV1.RgbImage {
        val base = intArrayOf(16,11,10,16,24,40,51,61,12,12,14,19,26,58,60,55,14,13,16,24,40,57,69,56,14,17,22,29,51,87,80,62,
            18,22,37,56,68,109,103,77,24,35,55,64,81,104,113,92,49,64,78,87,103,121,120,101,72,92,95,98,112,100,103,99)
        val scale = if (quality < 50) 5000 / quality else 200 - quality * 2
        val q = IntArray(64) { ((base[it] * scale + 50) / 100).coerceIn(1, 255) }
        val out = img.rgb.copyOf()
        val c = DoubleArray(8) { if (it == 0) Math.sqrt(0.125) else 0.5 }
        for (ch in 0..2) for (by in 0 until img.height / 8) for (bx in 0 until img.width / 8) {
            val blk = DoubleArray(64) { val x = bx * 8 + it % 8; val y = by * 8 + it / 8; (img.rgb[(y * img.width + x) * 3 + ch].toInt() and 0xFF) - 128.0 }
            val coef = DoubleArray(64)
            for (v in 0..7) for (u in 0..7) {
                var sum = 0.0
                for (y in 0..7) for (x in 0..7) sum += blk[y * 8 + x] * Math.cos((2 * x + 1) * u * Math.PI / 16) * Math.cos((2 * y + 1) * v * Math.PI / 16)
                coef[v * 8 + u] = Math.round(c[u] * c[v] * sum / q[v * 8 + u]) * q[v * 8 + u].toDouble()
            }
            for (y in 0..7) for (x in 0..7) {
                var sum = 0.0
                for (v in 0..7) for (u in 0..7) sum += c[u] * c[v] * coef[v * 8 + u] * Math.cos((2 * x + 1) * u * Math.PI / 16) * Math.cos((2 * y + 1) * v * Math.PI / 16)
                out[((by * 8 + y) * img.width + bx * 8 + x) * 3 + ch] = (Math.round(sum) + 128).toInt().coerceIn(0, 255).toByte()
            }
        }
        return StegoV1.RgbImage(img.width, img.height, out)
    }

    @Test
    fun recompressedLikeJpegFailsCleanly() {
        val img = hideFor(noisyCover(160, 120), StegoV1.Payload("survive?", 1))
        for (quality in listOf(75, 90, 95)) {
            val lossy = jpegLike(img, quality)
            val changed = lossy.rgb.indices.count { lossy.rgb[it] != img.rgb[it] }
            assertTrue("quality $quality changed $changed samples", changed > 0)
            assertEquals(StegoV1.RevealResult.NotForYou, StegoV1.reveal(lossy, bob.priv, bob.pub, listOf(alice.pub)))
        }
    }

    @Test
    fun resizedFailsCleanly() {
        val img = hideFor(noisyCover(160, 120), StegoV1.Payload("survive?", 1))
        // 2× nearest-neighbour downscale + upscale: what a "photo mode" resize does at best.
        val px = ByteArray(img.slots)
        for (y in 0 until img.height) for (x in 0 until img.width) for (ch in 0..2)
            px[(y * img.width + x) * 3 + ch] = img.rgb[((y / 2 * 2) * img.width + x / 2 * 2) * 3 + ch]
        assertEquals(StegoV1.RevealResult.NotForYou,
            StegoV1.reveal(StegoV1.RgbImage(img.width, img.height, px), bob.priv, bob.pub, listOf(alice.pub)))
    }

    @Test
    fun garbageAndTruncatedPngNeverThrow() {
        val png = StegoPng.encode(hideFor(noisyCover(), StegoV1.Payload("t", 1)))
        for (cut in listOf(10, 100, png.size / 2, png.size - 13)) {
            try { StegoPng.decode(png.copyOf(cut)); } catch (_: StegoPng.UnsupportedPng) { }
        }
        try { StegoPng.decode(ByteArray(50)); fail() } catch (_: StegoPng.UnsupportedPng) { }
    }

    /** A PNG written by hand in another layout (colour type, filter, chunking), as other encoders do. */
    private fun foreignPng(w: Int, h: Int, colorType: Int, rows: List<ByteArray>, filter: Int, plte: ByteArray? = null): ByteArray {
        val raw = java.io.ByteArrayOutputStream()
        rows.forEach { raw.write(filter); raw.write(it) }
        val d = java.util.zip.Deflater(9); d.setInput(raw.toByteArray()); d.finish()
        val z = java.io.ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) z.write(buf, 0, d.deflate(buf))
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(t: String, data: ByteArray) {
            out.write(java.nio.ByteBuffer.allocate(4).putInt(data.size).array()); out.write(t.toByteArray()); out.write(data)
            val crc = java.util.zip.CRC32(); crc.update(t.toByteArray()); crc.update(data)
            out.write(java.nio.ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
        }
        chunk("IHDR", java.nio.ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(colorType.toByte()).put(0).put(0).put(0).array())
        chunk("gAMA", byteArrayOf(0, 0, 0xB1.toByte(), 0x8F.toByte()))
        plte?.let { chunk("PLTE", it) }
        val zb = z.toByteArray()
        chunk("IDAT", zb.copyOfRange(0, zb.size / 2)); chunk("IDAT", zb.copyOfRange(zb.size / 2, zb.size))
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    @Test
    fun pngReaderHandlesOtherLayouts() {
        val w = 5; val h = 3
        val rgb = ByteArray(w * h * 3) { (it * 37 + 11).toByte() }
        // RGBA (type 6), filter None: alpha dropped, samples exact.
        val rgbaRows = (0 until h).map { y -> ByteArray(w * 4) { i -> if (i % 4 == 3) 0x80.toByte() else rgb[(y * w + i / 4) * 3 + i % 4] } }
        assertArrayEquals(rgb, StegoPng.decode(foreignPng(w, h, 6, rgbaRows, 0)).rgb)
        // Grey (type 0) and palette (type 3).
        val grey = (0 until h).map { y -> ByteArray(w) { (y * 50 + it * 9).toByte() } }
        val g = StegoPng.decode(foreignPng(w, h, 0, grey, 0)).rgb
        assertEquals(grey[2][4], g[(2 * w + 4) * 3 + 1])
        val plte = byteArrayOf(10, 20, 30, 40, 50, 60)
        val idx = (0 until h).map { ByteArray(w) { i -> (i % 2).toByte() } }
        val p = StegoPng.decode(foreignPng(w, h, 3, idx, 0, plte)).rgb
        assertEquals(40, p[3].toInt()); assertEquals(30, p[2].toInt())
        // Our own writer's adaptive filters all round-trip (covered by every test that goes viaPng).
        val img = noisyCover(37, 23)
        assertArrayEquals(img.rgb, viaPng(img).rgb)
    }

    @Test
    fun outputHasNoAncillaryChunks() {
        val png = StegoPng.encode(noisyCover(16, 16))
        val types = mutableListOf<String>()
        var off = 8
        while (off < png.size) {
            val len = java.nio.ByteBuffer.wrap(png, off, 4).int
            types += String(png, off + 4, 4, Charsets.US_ASCII); off += 12 + len
        }
        assertEquals(setOf("IHDR", "IDAT", "IEND"), types.toSet())
    }

    // __STEGO_DAMAGED_PNG_2026_11_18__ A damaged file must read as "no message",
    // never as a crash: decode throws UnsupportedPng and nothing else, and the
    // reveal of whatever decodes is NotForYou / Damaged / the exact message.
    private fun chunkOffsets(png: ByteArray): Map<String, Pair<Int, Int>> {
        val out = linkedMapOf<String, Pair<Int, Int>>()
        var off = 8
        while (off + 12 <= png.size) {
            val len = java.nio.ByteBuffer.wrap(png, off, 4).int
            out.putIfAbsent(String(png, off + 4, 4, Charsets.US_ASCII), off to len); off += 12 + len
        }
        return out
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val crc = java.util.zip.CRC32().apply { update(type.toByteArray()); update(data) }
        return java.nio.ByteBuffer.allocate(12 + data.size).putInt(data.size).put(type.toByteArray()).put(data)
            .putInt(crc.value.toInt()).array()
    }

    @Test
    fun damagedPngThrowsOnlyUnsupportedPng() {
        val png = StegoPng.encode(hideFor(noisyCover(), StegoV1.Payload("x", 1)))
        val (idatOff, idatLen) = chunkOffsets(png).getValue("IDAT")
        val variants = mutableMapOf<String, ByteArray>()
        variants["idat mid flip"] = png.copyOf().also { it[idatOff + 8 + idatLen / 2] = (it[idatOff + 8 + idatLen / 2].toInt() xor 0x10).toByte() }
        variants["idat start flip"] = png.copyOf().also { it[idatOff + 8 + 3] = (it[idatOff + 8 + 3].toInt() xor 0xFF).toByte() }
        variants["adler flip"] = png.copyOf().also { it[idatOff + 8 + idatLen - 4] = (it[idatOff + 8 + idatLen - 4].toInt() xor 1).toByte() }
        val rest = png.copyOfRange(8 + 25, png.size)
        variants["5-byte IHDR"] = png.copyOfRange(0, 8) + pngChunk("IHDR", byteArrayOf(0, 0, 1, 0, 0)) + rest
        variants["huge IHDR"] = png.copyOfRange(0, 8) + pngChunk("IHDR",
            java.nio.ByteBuffer.allocate(13).putInt(-1).putInt(-1).put(8.toByte()).put(2.toByte()).put(0.toByte()).put(0.toByte()).put(0.toByte()).array()) + rest
        variants["empty IDAT"] = png.copyOfRange(0, 8 + 25) + pngChunk("IDAT", ByteArray(0)) + pngChunk("IEND", ByteArray(0))
        for (cut in listOf(9, 20, 33, 40, png.size / 4, png.size / 2, png.size - 13)) variants["cut $cut"] = png.copyOf(cut)
        for ((name, bytes) in variants) {
            val img = try { StegoPng.decode(bytes) } catch (_: StegoPng.UnsupportedPng) { continue }
                catch (e: Exception) { fail("$name escaped as ${e.javaClass.name}: ${e.message}"); return }
            val r = StegoV1.reveal(img, bob.priv, bob.pub, listOf(alice.pub))
            if (r is StegoV1.RevealResult.Found) assertEquals("$name revealed something else", "x", r.payload.text)
        }
    }

    @Test
    fun randomByteDamageNeverEscapes() {
        val png = StegoPng.encode(hideFor(noisyCover(64, 48), StegoV1.Payload("bonjour", 2)))
        val rnd = java.util.Random(1)
        repeat(400) { n ->
            val b = png.copyOf()
            repeat(1 + rnd.nextInt(4)) { val i = 8 + rnd.nextInt(b.size - 8); b[i] = rnd.nextInt(256).toByte() }
            val img = try { StegoPng.decode(b) } catch (_: StegoPng.UnsupportedPng) { return@repeat }
                catch (e: Exception) { fail("mutation $n escaped as ${e.javaClass.name}"); return }
            val r = StegoV1.reveal(img, bob.priv, bob.pub, listOf(alice.pub))
            if (r is StegoV1.RevealResult.Found) assertEquals("bonjour", r.payload.text)
        }
    }
}
