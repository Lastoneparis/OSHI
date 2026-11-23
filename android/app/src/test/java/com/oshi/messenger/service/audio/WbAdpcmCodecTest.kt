package com.oshi.messenger.service.audio

import com.oshi.messenger.service.CallOfferCapabilities
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

/**
 * __WB_ADPCM_CODEC_2026_09_23__ — bit-exact with iOS `WbAdpcmCodecTests` and Desktop (vectors
 * from the Python reference), 200 B on the wire, self-contained frames, and the capability
 * byte sits in the 5th offer slot without disturbing the callId peel.
 */
class WbAdpcmCodecTest {

    private fun referenceSignal(): ShortArray {
        var seed = 12345L
        return ShortArray(640) { n ->
            seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
            val noise = ((seed shr 16) and 0x7fff).toInt() - 16384
            val v = Math.round(8000 * Math.sin(2 * Math.PI * 440 * n / 16000.0)).toInt() + (noise shr 3)
            v.coerceIn(-32768, 32767).toShort()
        }
    }

    private fun hex(b: ByteArray, n: Int) = b.take(n).joinToString("") { "%02x".format(it) }

    @Test
    fun bitExactVectorsSharedWithIosAndDesktop() {
        val x = referenceSignal()
        val st = WbAdpcmCodec.EncoderState()
        val f1 = WbAdpcmCodec.encode(x.copyOfRange(0, 320), st)
        val f2 = WbAdpcmCodec.encode(x.copyOfRange(320, 640), st)
        assertEquals(163, f1.size)
        assertEquals("0000007777777711d20088cb", hex(f1, 12))
        assertEquals("da8c3e034109431186018388", hex(f2, 12))
        val crc = CRC32(); crc.update(f1); crc.update(f2)
        assertEquals(627_570_784L, crc.value)
    }

    @Test
    fun eachFrameDecodesOnItsOwn() {
        val x = referenceSignal()
        val st = WbAdpcmCodec.EncoderState()
        WbAdpcmCodec.encode(x.copyOfRange(0, 320), st)            // frame 1 "lost"
        val d2 = WbAdpcmCodec.decode(WbAdpcmCodec.encode(x.copyOfRange(320, 640), st))
        assertEquals(320, d2.size)
        var sig = 0.0; var err = 0.0
        for (i in 0 until 320) { val a = x[320 + i].toDouble(); sig += a * a; err += (a - d2[i]) * (a - d2[i]) }
        assertTrue("SNR", 10 * Math.log10(sig / err) > 18)
        assertEquals(0, WbAdpcmCodec.decode(byteArrayOf(0, 0)).size)
    }

    @Test
    fun twentyMsFrameIs200BytesOnTheWire() {
        val pcm48 = ByteArray(1920) { if (it % 2 == 0) 0xE8.toByte() else 0x03 }   // 1000 LE
        val payload = WbAdpcmCodec.encodePcm48(pcm48, WbResampler(), WbAdpcmCodec.EncoderState())
        assertEquals(163, payload.size)
        assertEquals(200, 1 + 8 + 12 + payload.size + 16)
        assertEquals(1920, WbAdpcmCodec.decodeToPcm48(payload, WbResampler()).size)
    }

    @Test
    fun fullChainKeepsA1kHzTone() {
        val tx = WbResampler(); val rx = WbResampler(); val st = WbAdpcmCodec.EncoderState()
        val input = ShortArray(960 * 25) { (10_000 * Math.sin(2 * Math.PI * 1000 * it / 48_000.0)).toInt().toShort() }
        val out = ArrayList<Short>()
        for (f in 0 until 25) {
            val frame = input.copyOfRange(f * 960, (f + 1) * 960)
            rx.up(WbAdpcmCodec.decode(WbAdpcmCodec.encode(tx.down(frame), st))).forEach { out.add(it) }
        }
        assertEquals(input.size, out.size)
        var best = Double.NEGATIVE_INFINITY
        for (lag in 30 until 70) {
            var sig = 0.0; var err = 0.0
            for (i in 4800 until input.size - 100) {
                val a = input[i].toDouble(); val b = out[i + lag].toDouble()
                sig += a * a; err += (a - b) * (a - b)
            }
            best = maxOf(best, 10 * Math.log10(sig / err))
        }
        assertTrue("end-to-end SNR $best", best > 15)
    }

    @Test
    fun capabilityIsTheFifthOfferSlotAndKeepsTheCallId() {
        val callId = "B37DC742-43E1-4A5B-9C3D-0123456789AB"
        val body = callId.toByteArray() + byteArrayOf(0x00, 0x00, 0x04, 0x08, 0x10)
        val p = CallOfferCapabilities.peel(body, 0)
        assertEquals(callId, String(body, 0, p.callIdEnd))
        assertTrue(p.wbAdpcm)
        assertEquals(true, p.video)
        // A peer from before this build (4 slots) did not advertise it.
        assertFalse(CallOfferCapabilities.peel(callId.toByteArray() + byteArrayOf(0, 0, 4, 8), 0).wbAdpcm)
        assertTrue(WbAdpcmCodec.CAP_FLAG < 0x2D)
    }

    // __WB_POSTFILTER_2026_09_23__ receiver-side hiss removal. Not a wire contract, but the
    // Float pipeline is bit-identical on iOS/Android/Desktop: the CRC below is asserted
    // verbatim by all three suites (iOS `WbAdpcmCodecTests`).
    private fun pfSignal(): ShortArray {
        var seed = 12345L
        return ShortArray(640) { n ->
            seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
            val noise = ((seed shr 16) and 0x7fff).toInt() - 16384
            val v = Math.round(8000 * Math.sin(2 * Math.PI * 440 * n / 16000.0)).toInt() + (noise shr 3)
            v.coerceIn(-32768, 32767).toShort()
        }
    }

    @Test
    fun postFilterOutputIsPinnedByACrcSharedWithIos() {
        val x = pfSignal()
        val st = WbAdpcmCodec.EncoderState()
        val frames = listOf(WbAdpcmCodec.encode(x.copyOfRange(0, 320), st), WbAdpcmCodec.encode(x.copyOfRange(320, 640), st))
        val pf = WbPostFilter()
        val out = ByteArray(1280)
        var o = 0
        for (f in frames) {
            val steps = IntArray(320)
            val d = WbAdpcmCodec.decode(f, steps)
            assertArrayEquals("decode(steps) must not change the samples", WbAdpcmCodec.decode(f), d)
            assertTrue(steps.all { it in 7..32767 })
            for (s in pf.process(d, steps)) { out[o++] = s.toByte(); out[o++] = (s.toInt() shr 8).toByte() }
        }
        assertEquals(2_068_779_908L, CRC32().apply { update(out) }.value)
    }

    @Test
    fun postFilterRemovesAdpcmNoiseWithoutChangingTheLevel() {
        val n = 16_000
        val tone = ShortArray(n) { Math.round(8000 * Math.sin(2 * Math.PI * 440 * it / 16000.0)).toInt().toShort() }
        val st = WbAdpcmCodec.EncoderState()
        val pf = WbPostFilter()
        val raw = ShortArray(n); val flt = ShortArray(n)
        for (f in 0 until n / 320) {
            val p = WbAdpcmCodec.encode(tone.copyOfRange(f * 320, f * 320 + 320), st)
            val steps = IntArray(320)
            val d = WbAdpcmCodec.decode(p, steps)
            System.arraycopy(d, 0, raw, f * 320, 320)
            val y = pf.process(d, steps)
            assertEquals(320, y.size)
            System.arraycopy(y, 0, flt, f * 320, 320)
        }
        val lag = WbPostFilter.N - WbPostFilter.HOP + WbPostFilter.PRIME   // 192 samples = 12 ms
        fun snr(y: ShortArray, l: Int): Double {
            var s = 0.0; var e = 0.0
            for (i in 3200 until n - 400) { val a = tone[i].toDouble(); val b = y[i + l].toDouble(); s += a * a; e += (a - b) * (a - b) }
            return 10 * Math.log10(s / e)
        }
        fun rms(y: ShortArray, l: Int) = Math.sqrt((3200 until n - 400).sumOf { y[it + l].toDouble() * y[it + l] } / (n - 3600))
        val before = snr(raw, 0); val after = snr(flt, lag)
        assertTrue("SNR $before → $after dB", after > before + 5.0)
        assertEquals(0.0, 20 * Math.log10(rms(flt, lag) / rms(raw, 0)), 0.1)
    }

    @Test
    fun postFilterKeepsSilenceSilentAndResets() {
        val pf = WbPostFilter()
        assertTrue(pf.process(ShortArray(320), IntArray(320) { 7 }).all { it.toInt() == 0 })
        val x = pfSignal()
        val p = WbAdpcmCodec.encode(x.copyOfRange(0, 320), WbAdpcmCodec.EncoderState())
        val steps = IntArray(320); val d = WbAdpcmCodec.decode(p, steps)
        val first = WbPostFilter().process(d, steps)
        pf.process(d, steps); pf.reset()
        assertArrayEquals(first, pf.process(d, steps))
        // A malformed call (fewer steps than samples) passes through untouched.
        assertArrayEquals(d, pf.process(d, IntArray(3)))
    }
}
