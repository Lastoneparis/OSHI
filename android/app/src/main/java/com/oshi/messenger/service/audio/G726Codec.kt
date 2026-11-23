package com.oshi.messenger.service.audio

import android.util.Log

/**
 * G.726 ADPCM Codec - iOS Compatible
 *
 * Implements G.726 ADPCM encoding/decoding at 32 kbps
 * This EXACTLY matches the iOS implementation for cross-platform calls.
 *
 * Specifications:
 * - Input: 16-bit PCM at 16kHz/48kHz (downsampled to 8kHz)
 * - Output: 4-bit ADPCM (G.726-32)
 * - Bitrate: 32 kbps (same as WhatsApp)
 * - Compression: 24:1 (768kbps PCM → 32kbps ADPCM)
 */
class G726Codec {
    companion object {
        private const val TAG = "G726Codec"

        // Target sample rate for encoding (downsample to 8kHz)
        const val TARGET_SAMPLE_RATE = 8000

        // ADPCM step size table (MUST match iOS G726State.stepTable exactly!)
        // iOS table starts at 16 (81 entries), NOT the IMA ADPCM table that starts at 7.
        private val STEP_SIZE_TABLE = intArrayOf(
            16, 17, 19, 21, 23, 25, 28, 31,
            34, 37, 41, 45, 50, 55, 60, 66,
            73, 80, 88, 97, 107, 118, 130, 143,
            157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658,
            724, 796, 876, 963, 1060, 1166, 1282, 1411,
            1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024,
            3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484,
            7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
            15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794,
            32767
        )

        // Max step index (table has 81 entries, indices 0..80)
        private const val MAX_STEP_INDEX = 80

        // Index adjustment table for G.726-32 (4-bit)
        private val INDEX_ADJUST_TABLE = intArrayOf(
            -1, -1, -1, -1, 2, 4, 6, 8,
            -1, -1, -1, -1, 2, 4, 6, 8
        )
    }

    // Encoder state
    private var encodeStepIndex = 0
    private var encodePredicted = 0

    // Decoder state
    private var decodeStepIndex = 0
    private var decodePredicted = 0

    /**
     * Encode PCM samples to G.726 ADPCM
     *
     * @param samples 16-bit PCM samples at 8kHz (after downsampling)
     * @return G.726 encoded data (4 bits per sample, packed into bytes)
     */
    fun encode(samples: ShortArray): ByteArray {
        if (samples.isEmpty()) return ByteArray(0)

        val encoded = ByteArray((samples.size + 1) / 2)
        var encodedIndex = 0

        for (i in samples.indices step 2) {
            val sample1 = encodeSample(samples[i].toInt())
            val sample2 = if (i + 1 < samples.size) {
                encodeSample(samples[i + 1].toInt())
            } else {
                0
            }

            // Pack two 4-bit samples into one byte
            encoded[encodedIndex++] = ((sample1 and 0x0F) or ((sample2 and 0x0F) shl 4)).toByte()
        }

        return encoded
    }

    /**
     * Decode G.726 ADPCM to PCM samples
     *
     * @param encoded G.726 encoded data
     * @return 16-bit PCM samples at 8kHz
     */
    fun decode(encoded: ByteArray): ShortArray {
        if (encoded.isEmpty()) return ShortArray(0)

        val samples = ShortArray(encoded.size * 2)
        var sampleIndex = 0

        for (byte in encoded) {
            val value = byte.toInt() and 0xFF

            // Extract two 4-bit samples from each byte
            samples[sampleIndex++] = decodeSample(value and 0x0F).toShort()
            samples[sampleIndex++] = decodeSample((value shr 4) and 0x0F).toShort()
        }

        return samples
    }

    /**
     * Encode a single sample using G.726 ADPCM
     */
    private fun encodeSample(sample: Int): Int {
        val stepSize = STEP_SIZE_TABLE[encodeStepIndex]
        var diff = sample - encodePredicted
        var code = 0

        // Sign bit
        if (diff < 0) {
            code = 8
            diff = -diff
        }

        // Quantize
        if (diff >= stepSize) {
            code = code or 4
            diff -= stepSize
        }
        if (diff >= (stepSize shr 1)) {
            code = code or 2
            diff -= (stepSize shr 1)
        }
        if (diff >= (stepSize shr 2)) {
            code = code or 1
        }

        // Update predictor
        var diffq = stepSize shr 3
        if ((code and 4) != 0) diffq += stepSize
        if ((code and 2) != 0) diffq += stepSize shr 1
        if ((code and 1) != 0) diffq += stepSize shr 2

        if ((code and 8) != 0) {
            encodePredicted -= diffq
        } else {
            encodePredicted += diffq
        }

        // Clamp predictor
        encodePredicted = encodePredicted.coerceIn(-32768, 32767)

        // Update step index
        encodeStepIndex += INDEX_ADJUST_TABLE[code]
        encodeStepIndex = encodeStepIndex.coerceIn(0, MAX_STEP_INDEX)

        return code
    }

    /**
     * Decode a single G.726 ADPCM sample
     */
    private fun decodeSample(code: Int): Int {
        val stepSize = STEP_SIZE_TABLE[decodeStepIndex]

        // Compute difference
        var diffq = stepSize shr 3
        if ((code and 4) != 0) diffq += stepSize
        if ((code and 2) != 0) diffq += stepSize shr 1
        if ((code and 1) != 0) diffq += stepSize shr 2

        // Apply sign
        if ((code and 8) != 0) {
            decodePredicted -= diffq
        } else {
            decodePredicted += diffq
        }

        // Clamp output
        decodePredicted = decodePredicted.coerceIn(-32768, 32767)

        // Update step index
        decodeStepIndex += INDEX_ADJUST_TABLE[code]
        decodeStepIndex = decodeStepIndex.coerceIn(0, MAX_STEP_INDEX)

        return decodePredicted
    }

    /**
     * Reset encoder state
     */
    fun resetEncoder() {
        encodeStepIndex = 0
        encodePredicted = 0
    }

    /**
     * Reset decoder state
     */
    fun resetDecoder() {
        decodeStepIndex = 0
        decodePredicted = 0
    }

    /**
     * Reset both encoder and decoder states
     */
    fun reset() {
        resetEncoder()
        resetDecoder()
    }
}

/**
 * Audio Resampler - Converts between sample rates
 *
 * Used to downsample 48kHz/16kHz to 8kHz for G.726 encoding
 * and upsample 8kHz back to 16kHz for playback
 */
class AudioResampler {
    companion object {
        /**
         * Downsample from source rate to 8kHz
         */
        fun downsampleTo8kHz(samples: ShortArray, sourceSampleRate: Int): ShortArray {
            if (sourceSampleRate == 8000) return samples

            val ratio = sourceSampleRate / 8000
            val outputSize = samples.size / ratio
            val output = ShortArray(outputSize)

            for (i in 0 until outputSize) {
                // Simple decimation with averaging for anti-aliasing
                var sum = 0L
                for (j in 0 until ratio) {
                    sum += samples[i * ratio + j]
                }
                output[i] = (sum / ratio).toShort()
            }

            return output
        }

        /**
         * Upsample from 8kHz to target rate using Catmull-Rom cubic interpolation (iOS compatible)
         */
        fun upsampleFrom8kHz(samples: ShortArray, targetSampleRate: Int): ShortArray {
            if (targetSampleRate == 8000) return samples

            val ratio = targetSampleRate / 8000

            // Use Catmull-Rom cubic interpolation for ratio=2 (8kHz→16kHz), matching iOS
            if (ratio == 2) {
                val output = ShortArray(samples.size * 2)
                for (i in samples.indices) {
                    output[i * 2] = samples[i]

                    val s0 = if (i > 0) samples[i - 1].toInt() else samples[i].toInt()
                    val s1 = samples[i].toInt()
                    val s2 = if (i < samples.size - 1) samples[i + 1].toInt() else samples[i].toInt()
                    val s3 = if (i < samples.size - 2) samples[i + 2].toInt() else s2

                    val interpolated = (-s0 + 9 * s1 + 9 * s2 - s3) shr 4
                    output[i * 2 + 1] = interpolated.coerceIn(-32768, 32767).toShort()
                }
                return output
            }

            // Fallback: linear interpolation for other ratios
            val output = ShortArray(samples.size * ratio)
            for (i in samples.indices) {
                val current = samples[i].toInt()
                val next = if (i + 1 < samples.size) samples[i + 1].toInt() else current
                for (j in 0 until ratio) {
                    val t = j.toFloat() / ratio
                    output[i * ratio + j] = (current + (next - current) * t).toInt().toShort()
                }
            }
            return output
        }
    }
}

/**
 * WhatsApp-Quality Audio Codec wrapper
 *
 * Combines G.726 + resampling for iOS-compatible audio
 * Input: Any sample rate (resampled to 8kHz)
 * Output: 32 kbps ADPCM stream
 */
class WhatsAppQualityCodec(private val inputSampleRate: Int = 48000) {
    private val g726Codec = G726Codec()

    companion object {
        private const val TAG = "WhatsAppQualityCodec"

        // Packet size for 20ms at 8kHz = 160 samples = 80 bytes ADPCM
        const val PACKET_SIZE_SAMPLES = 160
        const val PACKET_SIZE_BYTES = 80
        const val PACKET_DURATION_MS = 20

        // Pre-emphasis coefficient: 0.97 in Q15 fixed-point (matching iOS)
        private const val PRE_EMPH_COEFF = 31785  // 0.97 * 32768

        // Volume boost applied after decode (matching iOS volumeGain = 4.0)
        private const val VOLUME_GAIN = 4.0f
    }

    // Pre-emphasis filter state (encode side)
    private var preEmphPrev: Int = 0

    // Anti-aliasing LPF state (encode side, 3-tap FIR matching iOS)
    private var lpfPrev1: Int = 0
    private var lpfPrev2: Int = 0

    // De-emphasis filter state (decode side)
    private var deEmphPrev: Int = 0

    // RX AGC state (decode side, matching iOS playAudioData() exactly)
    private var rxAgcGain: Float = 5.0f       // iOS: 5.0 (was 50.0 — too aggressive)
    private var rxAgcPeakTracker: Float = 0.01f  // iOS: 0.01 (was 0.001)

    /**
     * Encode PCM audio to G.726 ADPCM
     * Pipeline: PCM → pre-emphasis → anti-alias LPF → downsample → G.726
     * (Matches iOS OpusCodec.encode() pipeline)
     */
    fun encode(pcmData: ByteArray): ByteArray {
        // Convert bytes to shorts
        val samples = ShortArray(pcmData.size / 2)
        for (i in samples.indices) {
            samples[i] = ((pcmData[i * 2 + 1].toInt() shl 8) or (pcmData[i * 2].toInt() and 0xFF)).toShort()
        }

        // Pre-emphasis filter: y[n] = x[n] - 0.97 * x[n-1] (matching iOS)
        val emphasized = ShortArray(samples.size)
        for (i in samples.indices) {
            val s = samples[i].toInt()
            val emph = s - ((PRE_EMPH_COEFF * preEmphPrev) shr 15)
            preEmphPrev = s
            emphasized[i] = emph.coerceIn(-32768, 32767).toShort()
        }

        // Anti-aliasing LPF: 3-tap FIR [0.25, 0.5, 0.25] (matching iOS)
        val filtered = ShortArray(emphasized.size)
        for (i in emphasized.indices) {
            val cur = emphasized[i].toInt()
            val out = (lpfPrev2 + cur + 2 * lpfPrev1) shr 2
            lpfPrev2 = lpfPrev1
            lpfPrev1 = cur
            filtered[i] = out.coerceIn(-32768, 32767).toShort()
        }

        // Downsample to 8kHz
        val downsampled = AudioResampler.downsampleTo8kHz(filtered, inputSampleRate)

        // Encode with G.726
        return g726Codec.encode(downsampled)
    }

    /**
     * Decode G.726 ADPCM to PCM audio
     * Pipeline: G.726 → de-emphasis → upsample → volume boost → AGC
     * (Matches iOS OpusCodec.decode() + playAudioData() pipeline)
     */
    fun decode(adpcmData: ByteArray, outputSampleRate: Int = 16000): ByteArray {
        // Handle silence marker (iOS sends 0xFF for comfort noise)
        // iOS generates ±5 noise (nearly silent) — match exactly
        if (adpcmData.size == 1 && adpcmData[0] == 0xFF.toByte()) {
            val noiseLength = (outputSampleRate * PACKET_DURATION_MS / 1000) * 2
            val noise = ByteArray(noiseLength)
            for (i in noise.indices step 2) {
                val sample = (Math.random() * 10 - 5).toInt().toShort()  // iOS: Int16.random(in: -5...5)
                noise[i] = (sample.toInt() and 0xFF).toByte()
                noise[i + 1] = (sample.toInt() shr 8).toByte()
            }
            return noise
        }

        // Decode G.726
        val samples8k = g726Codec.decode(adpcmData)

        // De-emphasis filter: y[n] = x[n] + 0.97 * y[n-1] (matching iOS)
        for (i in samples8k.indices) {
            val s = samples8k[i].toInt()
            val deEmph = s + ((PRE_EMPH_COEFF * deEmphPrev) shr 15)
            val clamped = deEmph.coerceIn(-32768, 32767)
            deEmphPrev = clamped
            samples8k[i] = clamped.toShort()
        }

        // Upsample to output rate (cubic interpolation for 8kHz→16kHz)
        val upsampled = AudioResampler.upsampleFrom8kHz(samples8k, outputSampleRate)

        // Volume boost: 4.0x gain (matching iOS volumeGain = 4.0)
        for (i in upsampled.indices) {
            val boosted = upsampled[i].toFloat() * VOLUME_GAIN
            upsampled[i] = boosted.toInt().coerceIn(-32768, 32767).toShort()
        }

        // RX AGC: normalize to target peak with soft clipping (matching iOS playAudioData() exactly)
        // iOS values: targetPeak=0.3, smoothing=0.9/0.1, attack=0.5/0.5, release=0.98/0.02, range=1..20
        var bufferPeak = 0.0001f
        for (sample in upsampled) {
            val normalized = kotlin.math.abs(sample.toFloat() / 32768f)
            if (normalized > bufferPeak) bufferPeak = normalized
        }

        rxAgcPeakTracker = rxAgcPeakTracker * 0.9f + bufferPeak * 0.1f  // iOS: 0.9/0.1

        val targetPeak = 0.3f  // iOS: 0.3 (was 0.35)
        val desiredGain = targetPeak / maxOf(rxAgcPeakTracker, 0.0001f)

        rxAgcGain = if (desiredGain < rxAgcGain) {
            rxAgcGain * 0.5f + desiredGain * 0.5f   // iOS fast attack: 0.5/0.5 (was 0.7/0.3)
        } else {
            rxAgcGain * 0.98f + desiredGain * 0.02f  // iOS slow release: 0.98/0.02 (was 0.99/0.01)
        }
        rxAgcGain = rxAgcGain.coerceIn(1.0f, 20.0f)  // iOS: max 20.0 (was 500.0 — way too aggressive)

        // Apply gain with hard clamp at ±0.95 (matching iOS playAudioData exactly — NO tanh soft clipping)
        for (i in upsampled.indices) {
            val normalized = upsampled[i].toFloat() / 32768f
            val amplified = (normalized * rxAgcGain).coerceIn(-0.95f, 0.95f)
            upsampled[i] = (amplified * 32768f).toInt().coerceIn(-32768, 32767).toShort()
        }

        // Convert shorts to bytes
        val output = ByteArray(upsampled.size * 2)
        for (i in upsampled.indices) {
            val sample = upsampled[i].toInt()
            output[i * 2] = (sample and 0xFF).toByte()
            output[i * 2 + 1] = (sample shr 8).toByte()
        }

        return output
    }

    /**
     * Reset codec state (call between sessions)
     */
    fun reset() {
        g726Codec.reset()
        preEmphPrev = 0
        lpfPrev1 = 0
        lpfPrev2 = 0
        deEmphPrev = 0
        rxAgcGain = 5.0f      // Match iOS initial value
        rxAgcPeakTracker = 0.01f  // Match iOS initial value
    }

    /**
     * Get compression ratio info
     */
    val compressionRatio: Float
        get() = inputSampleRate.toFloat() / 8000 * 4  // 4 bits vs 16 bits = 4x, plus sample rate ratio

    val name: String
        get() = "G.726 ADPCM (32kbps WhatsApp-quality)"
}
