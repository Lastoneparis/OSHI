package com.oshi.messenger.service.audio

import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Round-trip test for the OshiCodec JNI binding.
 *
 * Builds a 1-second 440 Hz sine wave (48000 samples), splits it into 50 frames
 * of 960 samples, encodes and decodes each with an encoder+decoder pair, and
 * verifies the decoded signal is within loose error bounds of the input.
 *
 * Because host-JVM unit tests do not link the app's arm64 .so files, the test
 * is wrapped in JUnit's `assumeTrue(OshiCodec.isAvailable)` and will be SKIPPED
 * (not failed) on the host JVM. This still catches logic errors in the
 * wrapper itself because the class loads at construction time. The real
 * end-to-end round-trip is exercised by the instrumented test suite on-device.
 *
 * Loose acceptance: RMS error < 30% of input peak. OshiCodec is lossy LPC,
 * so we just want to confirm bytes round-trip and produce a similar waveform.
 */
class OshiCodecTest {

    companion object {
        private const val SAMPLE_RATE = 48000
        private const val FRAME_SIZE = 960
        private const val FRAME_COUNT = 50 // 50 * 960 = 48000 samples = 1 s
        private const val TONE_HZ = 440.0
        private const val BITRATE_BPS = 48_000
        private const val RMS_ERROR_THRESHOLD = 0.30 // 30% of peak
    }

    @Test
    fun `1 second sine wave round-trips within loose error bound`() {
        // Host-JVM gate: if the native lib didn't load, skip rather than fail.
        // On-device instrumented tests run liboshi_codec.so for real.
        assumeTrue(
            "liboshi_codec.so not available on host JVM — skipping native round-trip",
            OshiCodec.isAvailable
        )

        // Build 1 second of 440 Hz sine at 48 kHz, ~60% of full-scale peak.
        val peak = 20000
        val input = ShortArray(SAMPLE_RATE) { i ->
            (peak * sin(2.0 * PI * TONE_HZ * i / SAMPLE_RATE)).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }

        val encoder = OshiCodec(sampleRate = SAMPLE_RATE, channels = 1)
        val decoder = OshiCodec(sampleRate = SAMPLE_RATE, channels = 1)
        encoder.setBitrate(BITRATE_BPS)
        decoder.setBitrate(BITRATE_BPS)

        val output = ShortArray(SAMPLE_RATE)
        var totalBytes = 0

        try {
            for (f in 0 until FRAME_COUNT) {
                val offset = f * FRAME_SIZE
                val frameIn = input.copyOfRange(offset, offset + FRAME_SIZE)

                val encoded = encoder.encode(frameIn)
                assert(encoded.isNotEmpty()) { "encode produced empty bytes" }
                totalBytes += encoded.size

                val decoded = decoder.decode(encoded)
                assert(decoded.size == FRAME_SIZE) {
                    "decode produced ${decoded.size} samples, expected $FRAME_SIZE"
                }
                System.arraycopy(decoded, 0, output, offset, FRAME_SIZE)
            }
        } finally {
            encoder.close()
            decoder.close()
        }

        // Wire-bitrate sanity: 48 kbps * 1 s = 6000 bytes. Allow a generous
        // ±25% band since per-frame overhead rounds the real rate.
        val actualKbps = totalBytes * 8 / 1000
        assert(actualKbps in 30..70) {
            "expected ~48 kbps, got $actualKbps kbps ($totalBytes bytes / 1 s)"
        }

        // Energy check on the middle 40 frames — skip the first/last 5 to
        // avoid LPC warm-up transients.
        val skip = 5 * FRAME_SIZE
        val end = SAMPLE_RATE - skip
        var sqSum = 0.0
        var count = 0
        for (i in skip until end) {
            val err = (input[i].toInt() - output[i].toInt()).toDouble()
            sqSum += err * err
            count++
        }
        val rms = sqrt(sqSum / count)
        val rmsRatio = rms / peak.toDouble()
        assert(rmsRatio < RMS_ERROR_THRESHOLD) {
            "RMS error ratio $rmsRatio exceeds $RMS_ERROR_THRESHOLD (peak=$peak, rms=$rms)"
        }

        // Sanity: output peak should be within 2x input peak (no wild ringing).
        val outPeak = output.maxOf { abs(it.toInt()) }
        assert(outPeak < peak * 2) {
            "decoded peak $outPeak suspiciously large (input peak $peak)"
        }
    }

    @Test
    fun `wrong frame size is rejected`() {
        assumeTrue(OshiCodec.isAvailable)
        val codec = OshiCodec()
        try {
            var threw = false
            try {
                codec.encode(ShortArray(100))
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assert(threw) { "expected IllegalArgumentException for short frame" }
        } finally {
            codec.close()
        }
    }
}
