package com.oshi.messenger.service.audio

import com.oshi.messenger.service.CallOfferCapabilities
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __OPUS_CODEC_2026_09_23__ 0x19: Concentus (vendored source) round trip, the redundancy that
 * recovers one or two lost packets exactly, the seq keying, the capability slot — and a
 * stream ENCODED BY libopus 1.5.2 (iOS OshiCodec.swift, same vendored C) decoded here.
 * Keep in step with the Desktop copy of this test.
 */
class OpusCallCodecTest {

    private fun sine(frames: Int): ShortArray =
        ShortArray(frames * OpusWire.FRAME_SAMPLES) { Math.round(8000 * Math.sin(2 * Math.PI * 1000 * it / 48000.0)).toInt().toShort() }

    /** Best normalised cross-correlation of [y] (from sample [from]) against the tone, 0..1200 samples of lag. */
    private fun toneCorrelation(y: ShortArray, from: Int): Double {
        val x = sine(y.size / OpusWire.FRAME_SAMPLES + 2)
        var best = 0.0
        for (lag in 0..1200) {
            var xy = 0.0; var xx = 0.0; var yy = 0.0
            for (i in from until y.size) {
                val a = y[i].toDouble(); val b = x[i - lag + 1200].toDouble()
                xy += a * b; xx += b * b; yy += a * a
            }
            if (xx > 0 && yy > 0) best = maxOf(best, xy / Math.sqrt(xx * yy))
        }
        return best
    }

    private fun decodeAll(payloads: List<ByteArray>, drop: Set<Int> = emptySet()): List<OpusCallDecoder.Frame> {
        val d = OpusCallDecoder()
        return payloads.withIndex().filter { it.index !in drop }.flatMap { d.decode(it.value) }
    }

    private fun concat(frames: List<OpusCallDecoder.Frame>): ShortArray {
        val out = ShortArray(frames.sumOf { it.pcm.size }); var o = 0
        for (f in frames) { System.arraycopy(f.pcm, 0, out, o, f.pcm.size); o += f.pcm.size }
        return out
    }

    private fun unhex(h: String) = ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test fun `round trip keeps a 1 kHz tone and stays within the 300-byte datagram`() {
        val payloads = OpusCallEncoder().encode(sine(12))
        assertEquals(12, payloads.size)
        payloads.forEach { assertTrue("payload ${it.size} B", it.size <= OpusWire.MAX_PAYLOAD_BYTES) }
        assertEquals(3, OpusWire.unpack(payloads[5])!!.frames.size)                  // newest + 2 repeats
        val y = concat(decodeAll(payloads))
        assertEquals(12 * OpusWire.FRAME_SAMPLES, y.size)
        assertTrue(toneCorrelation(y, 4 * OpusWire.FRAME_SAMPLES) > 0.95)
    }

    @Test fun `the encoder re-cuts any capture size into 20 ms frames`() {
        val e = OpusCallEncoder()
        val x = sine(6)
        val counts = listOf(0, 1024, 2048, 3072, 4096, 5760).zipWithNext { a, b -> e.encode(x.copyOfRange(a, b)).size }
        assertEquals(listOf(1, 1, 1, 1, 2), counts)
    }

    @Test fun `one or two lost packets are recovered bit-exactly from the next one`() {
        val payloads = OpusCallEncoder().encode(sine(12))
        val ref = decodeAll(payloads)
        for (drop in listOf(setOf(5), setOf(5, 6))) {
            val got = decodeAll(payloads, drop)
            assertEquals(ref.size, got.size)
            for (i in ref.indices) assertArrayEquals("frame $i drop $drop", ref[i].pcm, got[i].pcm)
        }
        val d = OpusCallDecoder()
        payloads.take(5).forEach { d.decode(it) }
        assertEquals(listOf(-2, -1, 0), d.decode(payloads[7]).map { it.offset })    // 5 and 6 from redundancy
    }

    @Test fun `three lost packets - the two newest recovered, the oldest concealed`() {
        val payloads = OpusCallEncoder().encode(sine(12))
        val d = OpusCallDecoder()
        payloads.take(5).forEach { d.decode(it) }
        val frames = d.decode(payloads[8])                                             // 5, 6, 7 lost
        assertEquals(listOf(-3, -2, -1, 0), frames.map { it.offset })
        frames.forEach { assertEquals(OpusWire.FRAME_SAMPLES, it.pcm.size) }
    }

    @Test fun `duplicates and late packets play nothing`() {
        val payloads = OpusCallEncoder().encode(sine(4))
        val d = OpusCallDecoder()
        payloads.forEach { d.decode(it) }
        assertTrue(d.decode(payloads[3]).isEmpty())
        assertTrue(d.decode(payloads[1]).isEmpty())
    }

    @Test fun `keyed files recovered frames under the lost packets' seqs and merges when short`() {
        val f = { o: Int -> OpusCallDecoder.Frame(o, ShortArray(OpusWire.FRAME_SAMPLES) { o.toShort() }) }
        val three = listOf(f(-2), f(-1), f(0))
        assertEquals(listOf(98L, 99L, 100L), OpusCallDecoder.keyed(three, 100, 97).map { it.first })
        val merged = OpusCallDecoder.keyed(three, 100, 98)                              // only 99 and 100 free
        assertEquals(listOf(99L, 100L), merged.map { it.first })
        assertEquals(2 * OpusWire.FRAME_SAMPLES, merged[0].second.size)                // nothing dropped
        assertTrue(OpusCallDecoder.keyed(three, 100, 100).isEmpty())
        assertEquals(listOf(100L), OpusCallDecoder.keyed(listOf(f(0)), 100, -1).map { it.first })
    }

    @Test fun `malformed payloads are refused without throwing`() {
        assertNull(OpusWire.unpack(ByteArray(0)))
        assertNull(OpusWire.unpack(byteArrayOf(0x20, 0, 1, 5)))                        // wrong version
        assertNull(OpusWire.unpack(byteArrayOf(0x11, 0, 1, 50, 1, 2)))                 // length past the end
        assertTrue(OpusCallDecoder().decode(byteArrayOf(0x10, 0, 1, 0xFF.toByte(), 0xFF.toByte())).size <= 1)
    }

    /** First 8 payloads of a 1 kHz tone encoded by iOS (libopus 1.5.2 via OshiCodec.swift). */
    private val iosPayloads = listOf(
        "10000178830f9cb48ffaf52c78c815e4493d58c87868b9e97a7484a48ddea5fdca634bb09ea8fa81097f6efd56bb4143213301dfd39b9220b7a9d328d1713407b20e98a45711e53ddfedc909ecea2c38c4166d89c403ebb369ed919ce3ae",
        "1100025b78830f9cb48ffaf52c78c815e4493d58c87868b9e97a7484a48ddea5fdca634bb09ea8fa81097f6efd56bb4143213301dfd39b9220b7a9d328d1713407b20e98a45711e53ddfedc909ecea2c38c4166d89c403ebb369ed919ce3ae78a1f7f79f8b3f192e5e34a5b02ddf3344e9c9dcff6a825c9220a57f3870963c2f49a6576e85f6e7cf7118e1be807c136ca29eac8e6e2744c32c654ced5a3f0ff840cd53",
        "1200035b78830f9cb48ffaf52c78c815e4493d58c87868b9e97a7484a48ddea5fdca634bb09ea8fa81097f6efd56bb4143213301dfd39b9220b7a9d328d1713407b20e98a45711e53ddfedc909ecea2c38c4166d89c403ebb369ed919ce3ae4478a1f7f79f8b3f192e5e34a5b02ddf3344e9c9dcff6a825c9220a57f3870963c2f49a6576e85f6e7cf7118e1be807c136ca29eac8e6e2744c32c654ced5a3f0ff840cd53789c186fa44117f63e8b31175264f87334d3c1acdd482611659949c2c21a552593aa7ca53fa97e56c895582d20a8608c654ced5a3f0ff840cdc7",
        "1200044478a1f7f79f8b3f192e5e34a5b02ddf3344e9c9dcff6a825c9220a57f3870963c2f49a6576e85f6e7cf7118e1be807c136ca29eac8e6e2744c32c654ced5a3f0ff840cd533a789c186fa44117f63e8b31175264f87334d3c1acdd482611659949c2c21a552593aa7ca53fa97e56c895582d20a8608c654ced5a3f0ff840cdc7789c186fa44117f63e8b31189a0823aa6220e85d9ee462448047e8bf065406f62d65a0d5c526a00c03097aa3e013c017416f697a3307ad9a89f6ad1ee94360d550",
        "1200053a789c186fa44117f63e8b31175264f87334d3c1acdd482611659949c2c21a552593aa7ca53fa97e56c895582d20a8608c654ced5a3f0ff840cdc741789c186fa44117f63e8b31189a0823aa6220e85d9ee462448047e8bf065406f62d65a0d5c526a00c03097aa3e013c017416f697a3307ad9a89f6ad1ee94360d550789c186fa44117f63e8b312408a882e47051cac35fc931c1a43653d4e9dabeac625f8d5906471e1a36f645011d21993f5078787dcc4d7fec166ccce94360d547",
        "12000641789c186fa44117f63e8b31189a0823aa6220e85d9ee462448047e8bf065406f62d65a0d5c526a00c03097aa3e013c017416f697a3307ad9a89f6ad1ee94360d55040789c186fa44117f63e8b312408a882e47051cac35fc931c1a43653d4e9dabeac625f8d5906471e1a36f645011d21993f5078787dcc4d7fec166ccce94360d547789c186fa44117f63e8b311835cff8f1cbe1979dbd583e78ace63240056632ea80c554be32fb7fae92566097666149daa068d9c5d36d018d9a89f6ad1ee94360d5c7",
        "12000740789c186fa44117f63e8b312408a882e47051cac35fc931c1a43653d4e9dabeac625f8d5906471e1a36f645011d21993f5078787dcc4d7fec166ccce94360d54742789c186fa44117f63e8b311835cff8f1cbe1979dbd583e78ace63240056632ea80c554be32fb7fae92566097666149daa068d9c5d36d018d9a89f6ad1ee94360d5c7789c186fa44117f63e8b31024b8431b451d9b3ab85e1024b5eefdad2da09d37d1b990d2c4df8a831afa2bec5b9d5e4848b8d8767ff07a7ec166ccce94360d547",
        "12000842789c186fa44117f63e8b311835cff8f1cbe1979dbd583e78ace63240056632ea80c554be32fb7fae92566097666149daa068d9c5d36d018d9a89f6ad1ee94360d5c740789c186fa44117f63e8b31024b8431b451d9b3ab85e1024b5eefdad2da09d37d1b990d2c4df8a831afa2bec5b9d5e4848b8d8767ff07a7ec166ccce94360d547789c186fa44117f63e8b310e175cc59d2903c03dd68da92fe4eda1b56e7ed0097f4e4d002a5820b45fec20dd86df784bf2abab1959185fdab47e6a26e94040cd47"
    )

    @Test fun `decodes a stream encoded by libopus on iOS`() {
        val y = concat(decodeAll(iosPayloads.map(::unhex)))
        assertEquals(8 * OpusWire.FRAME_SAMPLES, y.size)
        assertTrue(toneCorrelation(y, 3 * OpusWire.FRAME_SAMPLES) > 0.95)
    }

    @Test fun `capability 0x20 is the 6th offer slot and leaves the callId intact`() {
        val id = "7F9E3C1A-0000-4000-8000-000000000001".toByteArray()
        val body = ByteArray(45) + id + byteArrayOf(0, 0, 0, 8, 0x10, 0x20)
        val caps = CallOfferCapabilities.peel(body, 45)
        assertEquals(45 + id.size, caps.callIdEnd)
        assertTrue(caps.opus)
        assertTrue(caps.wbAdpcm)
        // A pre-Opus peer (five slots): no Opus, same callId.
        val old = CallOfferCapabilities.peel(body.copyOf(body.size - 1), 45)
        assertFalse(old.opus)
        assertEquals(45 + id.size, old.callIdEnd)
        assertEquals(0x20, OpusWire.CAP_FLAG)
        assertTrue(OpusWire.CAP_FLAG < 0x2D)
    }
}
