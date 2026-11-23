package com.oshi.messenger.service.audio

import android.content.Context
import android.media.*
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * VoiceEffectProcessor - Compatible with iOS AudioRecorderManager voice effects
 *
 * Processes recorded audio (M4A) with effects:
 * - Robot: Distortion + pitch down
 * - Alien: Pitch up + reverb
 * - Deep: Pitch down + slow
 * - Chipmunk: Pitch up + fast
 * - Echo: Delay feedback
 * - Reverb: Cathedral-style reverb
 */
class VoiceEffectProcessor(private val context: Context) {

    companion object {
        private const val TAG = "VoiceEffectProcessor"
    }

    /**
     * Voice effect types matching iOS VoiceEffect enum
     */
    enum class VoiceEffect(val displayName: String) {
        NORMAL("Normal"),
        ROBOT("Robot"),
        ALIEN("Alien"),
        DEEP("Deep"),
        CHIPMUNK("Chipmunk"),
        ECHO("Echo"),
        REVERB("Reverb")
    }

    /**
     * Apply voice effect to M4A audio data
     * @return processed audio data as M4A, or original if effect is NORMAL
     */
    fun applyEffect(audioData: ByteArray, effect: VoiceEffect): ByteArray {
        if (effect == VoiceEffect.NORMAL) return audioData

        return try {
            // 1. Decode M4A to PCM
            val pcmData = decodeToWav(audioData)
            if (pcmData == null) {
                Log.e(TAG, "Failed to decode audio to PCM")
                return audioData
            }

            // 2. Apply effect on PCM samples
            val processedPcm = when (effect) {
                VoiceEffect.ROBOT -> applyRobotEffect(pcmData)
                VoiceEffect.ALIEN -> applyAlienEffect(pcmData)
                VoiceEffect.DEEP -> applyDeepEffect(pcmData)
                VoiceEffect.CHIPMUNK -> applyChipmunkEffect(pcmData)
                VoiceEffect.ECHO -> applyEchoEffect(pcmData)
                VoiceEffect.REVERB -> applyReverbEffect(pcmData)
                VoiceEffect.NORMAL -> pcmData
            }

            // 3. Re-encode to M4A
            val result = encodeToM4A(processedPcm)
            result ?: audioData

        } catch (e: Exception) {
            Log.e(TAG, "Effect processing failed: ${e.message}", e)
            audioData
        }
    }

    // =========================================================================
    // PCM DATA CLASS
    // =========================================================================

    data class PcmData(
        val samples: ShortArray,
        val sampleRate: Int,
        val channels: Int
    )

    // =========================================================================
    // EFFECT IMPLEMENTATIONS (matching iOS parameters)
    // =========================================================================

    /**
     * Robot: Distortion (quantize) + pitch down (-200 cents)
     * iOS: multiDistortedCubed at 50% wet, pitch -200 cents
     */
    private fun applyRobotEffect(pcm: PcmData): PcmData {
        val samples = pcm.samples.clone()

        // Apply distortion: quantize to fewer levels (robot-like)
        val quantizeLevels = 16
        val step = (Short.MAX_VALUE * 2) / quantizeLevels
        for (i in samples.indices) {
            val quantized = ((samples[i].toInt() + Short.MAX_VALUE) / step) * step - Short.MAX_VALUE
            samples[i] = quantized.toShort()
        }

        // Mix 50% wet (distorted) + 50% dry (original) like iOS
        for (i in samples.indices) {
            val mixed = (samples[i].toInt() * 0.5 + pcm.samples[i].toInt() * 0.5).toInt()
            samples[i] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        // Apply pitch shift down (-200 cents ≈ factor 0.891)
        val pitchFactor = Math.pow(2.0, -200.0 / 1200.0)  // ~0.891
        val shifted = resamplePcm(samples, pitchFactor)

        return PcmData(shifted, pcm.sampleRate, pcm.channels)
    }

    /**
     * Alien: Pitch up (+800 cents) at 1.2x speed + reverb (30% wet)
     * iOS: pitch +800 cents, rate 1.2, largeHall reverb at 30%
     */
    private fun applyAlienEffect(pcm: PcmData): PcmData {
        // Pitch up: +800 cents ≈ factor 1.587, but played at 1.2x speed
        // Effective: resample by 1.587/1.2 = 1.323
        val pitchFactor = Math.pow(2.0, 800.0 / 1200.0) / 1.2
        val pitched = resamplePcm(pcm.samples, pitchFactor)

        // Apply reverb (large hall, 30% wet)
        val reverbed = applySimpleReverb(pitched, pcm.sampleRate, wetMix = 0.3f, decayMs = 800)

        return PcmData(reverbed, pcm.sampleRate, pcm.channels)
    }

    /**
     * Deep: Pitch down (-500 cents) at 0.8x speed
     * iOS: pitch -500 cents, rate 0.8
     */
    private fun applyDeepEffect(pcm: PcmData): PcmData {
        // Pitch down: -500 cents ≈ factor 0.749, played at 0.8x speed
        // Effective: resample by 0.749/0.8 = 0.936
        val pitchFactor = Math.pow(2.0, -500.0 / 1200.0) / 0.8
        val shifted = resamplePcm(pcm.samples, pitchFactor)

        return PcmData(shifted, pcm.sampleRate, pcm.channels)
    }

    /**
     * Chipmunk: Pitch up (+1000 cents) at 1.5x speed
     * iOS: pitch +1000 cents, rate 1.5
     */
    private fun applyChipmunkEffect(pcm: PcmData): PcmData {
        // Pitch up: +1000 cents ≈ factor 1.782, played at 1.5x speed
        // Effective: resample by 1.782/1.5 = 1.188
        val pitchFactor = Math.pow(2.0, 1000.0 / 1200.0) / 1.5
        val shifted = resamplePcm(pcm.samples, pitchFactor)

        return PcmData(shifted, pcm.sampleRate, pcm.channels)
    }

    /**
     * Echo: 300ms delay, 50% feedback, 40% wet
     * iOS: delayTime 0.3s, feedback 50, wetDryMix 40
     */
    private fun applyEchoEffect(pcm: PcmData): PcmData {
        val delaySamples = (0.3 * pcm.sampleRate).toInt()  // 300ms delay
        val feedback = 0.5f   // 50% feedback
        val wetMix = 0.4f     // 40% wet

        val samples = pcm.samples.clone()
        val delayBuffer = FloatArray(samples.size + delaySamples * 4)

        // Fill delay buffer with original signal
        for (i in samples.indices) {
            delayBuffer[i] = samples[i].toFloat()
        }

        // Apply delay with feedback
        for (i in delaySamples until delayBuffer.size) {
            delayBuffer[i] += delayBuffer[i - delaySamples] * feedback
        }

        // Mix wet + dry
        val outputSize = minOf(delayBuffer.size, samples.size + delaySamples * 2)
        val output = ShortArray(outputSize)
        for (i in output.indices) {
            val dry = if (i < samples.size) samples[i].toFloat() else 0f
            val wet = delayBuffer[i]
            val mixed = (dry * (1f - wetMix) + wet * wetMix).toInt()
            output[i] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        return PcmData(output, pcm.sampleRate, pcm.channels)
    }

    /**
     * Reverb: Cathedral preset, 60% wet
     * iOS: cathedral preset, wetDryMix 60
     */
    private fun applyReverbEffect(pcm: PcmData): PcmData {
        // Cathedral-style reverb: longer decay, 60% wet
        val reverbed = applySimpleReverb(pcm.samples, pcm.sampleRate, wetMix = 0.6f, decayMs = 1500)
        return PcmData(reverbed, pcm.sampleRate, pcm.channels)
    }

    // =========================================================================
    // DSP HELPERS
    // =========================================================================

    /**
     * Resample PCM data by pitch factor (>1 = higher pitch, <1 = lower pitch)
     * Uses linear interpolation
     */
    private fun resamplePcm(samples: ShortArray, pitchFactor: Double): ShortArray {
        val newLength = (samples.size / pitchFactor).toInt()
        val output = ShortArray(newLength)

        for (i in output.indices) {
            val srcIndex = i * pitchFactor
            val srcIndexInt = srcIndex.toInt()
            val fraction = (srcIndex - srcIndexInt).toFloat()

            if (srcIndexInt + 1 < samples.size) {
                val interpolated = samples[srcIndexInt] * (1f - fraction) + samples[srcIndexInt + 1] * fraction
                output[i] = interpolated.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            } else if (srcIndexInt < samples.size) {
                output[i] = samples[srcIndexInt]
            }
        }

        return output
    }

    /**
     * Simple multi-tap reverb using comb filters
     */
    private fun applySimpleReverb(
        samples: ShortArray,
        sampleRate: Int,
        wetMix: Float,
        decayMs: Int
    ): ShortArray {
        val maxDelaySamples = (decayMs * sampleRate / 1000.0).toInt()

        // Multi-tap delays (simulating reflections in a space)
        val delays = intArrayOf(
            (29 * sampleRate / 1000.0).toInt(),    // 29ms
            (37 * sampleRate / 1000.0).toInt(),    // 37ms
            (67 * sampleRate / 1000.0).toInt(),    // 67ms
            (113 * sampleRate / 1000.0).toInt(),   // 113ms
            (181 * sampleRate / 1000.0).toInt(),   // 181ms
            (293 * sampleRate / 1000.0).toInt()    // 293ms
        )
        val gains = floatArrayOf(0.7f, 0.6f, 0.5f, 0.4f, 0.3f, 0.2f)

        val outputLength = samples.size + maxDelaySamples
        val wet = FloatArray(outputLength)

        // Sum delayed copies with decreasing gain
        for (d in delays.indices) {
            val delay = delays[d]
            val gain = gains[d]
            for (i in samples.indices) {
                wet[i + delay] += samples[i].toFloat() * gain
            }
        }

        // Mix wet + dry
        val output = ShortArray(outputLength)
        for (i in output.indices) {
            val dry = if (i < samples.size) samples[i].toFloat() else 0f
            val mixed = (dry * (1f - wetMix) + wet[i] * wetMix).toInt()
            output[i] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        return output
    }

    // =========================================================================
    // CODEC: M4A <-> PCM
    // =========================================================================

    /**
     * Decode M4A audio data to PCM samples
     */
    private fun decodeToWav(m4aData: ByteArray): PcmData? {
        val tempFile = File(context.cacheDir, "temp_decode_${UUID.randomUUID()}.m4a")
        return try {
            FileOutputStream(tempFile).use { it.write(m4aData) }

            val extractor = MediaExtractor()
            extractor.setDataSource(tempFile.absolutePath)

            // Find audio track
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex == -1 || format == null) {
                Log.e(TAG, "No audio track found")
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val pcmSamples = mutableListOf<Short>()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Read output
                val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }

                    val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)!!
                    outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                    val shortBuffer = outputBuffer.asShortBuffer()
                    val shorts = ShortArray(shortBuffer.remaining())
                    shortBuffer.get(shorts)
                    pcmSamples.addAll(shorts.toList())

                    decoder.releaseOutputBuffer(outputBufferIndex, false)
                }
            }

            decoder.stop()
            decoder.release()
            extractor.release()

            PcmData(pcmSamples.toShortArray(), sampleRate, channels)

        } catch (e: Exception) {
            Log.e(TAG, "Decode failed: ${e.message}", e)
            null
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Encode PCM samples back to M4A
     */
    private fun encodeToM4A(pcm: PcmData): ByteArray? {
        val outputFile = File(context.cacheDir, "temp_encode_${UUID.randomUUID()}.m4a")
        return try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                pcm.sampleRate,
                pcm.channels
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerTrackIndex = -1
            var muxerStarted = false

            val bufferInfo = MediaCodec.BufferInfo()
            val pcmBytes = ShortArray(pcm.samples.size).also {
                System.arraycopy(pcm.samples, 0, it, 0, pcm.samples.size)
            }

            // Convert shorts to bytes (little-endian)
            val byteBuffer = ByteBuffer.allocate(pcmBytes.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcmBytes) {
                byteBuffer.putShort(sample)
            }
            byteBuffer.flip()

            var inputOffset = 0
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    val inputBufferIndex = encoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputBufferIndex)!!
                        val remaining = byteBuffer.remaining()
                        val size = minOf(remaining, inputBuffer.capacity())

                        if (size <= 0) {
                            encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val slice = ByteArray(size)
                            byteBuffer.get(slice)
                            inputBuffer.put(slice)

                            val presentationTimeUs = (inputOffset.toLong() * 1000000L) / (pcm.sampleRate * pcm.channels * 2)
                            encoder.queueInputBuffer(inputBufferIndex, 0, size, presentationTimeUs, 0)
                            inputOffset += size
                        }
                    }
                }

                // Read output
                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                when {
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            muxerTrackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outputBufferIndex >= 0 -> {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }

                        if (bufferInfo.size > 0 && muxerStarted) {
                            val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)!!
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(muxerTrackIndex, outputBuffer, bufferInfo)
                        }

                        encoder.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }
            }

            encoder.stop()
            encoder.release()
            muxer.stop()
            muxer.release()

            outputFile.readBytes()

        } catch (e: Exception) {
            Log.e(TAG, "Encode failed: ${e.message}", e)
            null
        } finally {
            outputFile.delete()
        }
    }
}
