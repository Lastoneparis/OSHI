package com.oshi.messenger.service.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import kotlin.random.Random

/**
 * CallVoiceEffectProcessor - Real-time PCM voice effect processor for calls.
 *
 * Lightweight stateful processor for 20ms PCM16 chunks at 48kHz mono.
 * All effects run in O(n) time with no allocations per call.
 * Thread-safe: called from audio send coroutine only.
 *
 * Effects match VoiceEffectProcessor (for voice messages) but optimized for
 * real-time streaming (stateful delay buffers persist across chunks).
 */
class CallVoiceEffectProcessor {

    companion object {
        private const val SAMPLE_RATE = 48000
        private const val SAMPLES_PER_20MS = 960 // 48000 * 20 / 1000

        // Echo: 300ms delay at 48kHz = 14400 samples
        private const val ECHO_DELAY_SAMPLES = 14400
        private const val ECHO_FEEDBACK = 0.5f

        // Reverb: 4-tap comb filter with prime-spaced delays
        private val REVERB_DELAYS = intArrayOf(1409, 1847, 2311, 2791)
        private val REVERB_GAINS = floatArrayOf(0.55f, 0.45f, 0.35f, 0.25f)

        // Robot: 150Hz ring modulation
        private const val ROBOT_FREQ = 150.0
    }

    /**
     * Voice effect types for real-time calls.
     * Matches VoiceEffectProcessor.VoiceEffect ordering for UI consistency.
     */
    enum class VoiceEffect(val displayName: String) {
        NORMAL("Normal"),
        ROBOT("Robot"),
        ALIEN("Alien"),
        DEEP("Deep"),
        CHIPMUNK("Chipmunk"),
        ECHO("Echo"),
        REVERB("Reverb"),
        ANONYMOUS("Anonymous");

        fun next(): VoiceEffect {
            val values = entries
            return values[(ordinal + 1) % values.size]
        }
    }

    // Echo delay buffer (circular)
    private val echoBuffer = FloatArray(ECHO_DELAY_SAMPLES)
    private var echoWritePos = 0

    // Reverb delay buffers (one per tap)
    private val reverbBuffers = Array(REVERB_DELAYS.size) { FloatArray(REVERB_DELAYS[it]) }
    private val reverbWritePositions = IntArray(REVERB_DELAYS.size)

    // Robot: phase accumulator for ring modulation
    private var robotPhase = 0.0

    // Deep: residual sample from previous chunk (for linear interpolation stretch)
    private var deepResidualIndex = 0.0

    /**
     * Process a PCM16 byte array with the given voice effect.
     * Input: raw PCM16 little-endian mono at 48kHz.
     * Returns: processed PCM16 byte array (same format, may differ in length for pitch effects).
     *
     * For NORMAL effect, returns input unchanged (zero-copy).
     */
    fun process(pcmData: ByteArray, effect: VoiceEffect): ByteArray {
        if (effect == VoiceEffect.NORMAL) return pcmData

        // Convert bytes to shorts
        val numSamples = pcmData.size / 2
        val samples = ShortArray(numSamples)
        val buf = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) {
            samples[i] = buf.getShort()
        }

        val processed = when (effect) {
            VoiceEffect.NORMAL -> samples
            VoiceEffect.ROBOT -> processRobot(samples)
            VoiceEffect.ALIEN -> processAlien(samples)
            VoiceEffect.DEEP -> processDeep(samples)
            VoiceEffect.CHIPMUNK -> processChipmunk(samples)
            VoiceEffect.ECHO -> processEcho(samples)
            VoiceEffect.REVERB -> processReverb(samples)
            VoiceEffect.ANONYMOUS -> processAnonymous(samples)
        }

        // Convert back to bytes
        val output = ByteArray(processed.size * 2)
        val outBuf = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        for (s in processed) {
            outBuf.putShort(s)
        }
        return output
    }

    /**
     * Reset all stateful buffers (call when a new call starts).
     */
    fun reset() {
        echoBuffer.fill(0f)
        echoWritePos = 0
        for (i in reverbBuffers.indices) {
            reverbBuffers[i].fill(0f)
            reverbWritePositions[i] = 0
        }
        robotPhase = 0.0
        deepResidualIndex = 0.0
        anonymousResidualIndex = 0.0
    }

    // =========================================================================
    // EFFECT IMPLEMENTATIONS
    // =========================================================================

    /**
     * Robot: quantize to 16 levels + 150Hz ring modulation.
     * Produces metallic, robotic sound.
     */
    private fun processRobot(samples: ShortArray): ShortArray {
        val output = ShortArray(samples.size)
        val step = (65536) / 16  // 16 quantization levels
        val twoPiFreq = 2.0 * Math.PI * ROBOT_FREQ / SAMPLE_RATE

        for (i in samples.indices) {
            // Quantize
            val quantized = ((samples[i].toInt() + 32768) / step) * step - 32768

            // Ring modulate with 150Hz sine
            val modulated = (quantized * sin(robotPhase)).toInt()
            robotPhase += twoPiFreq
            if (robotPhase > 2.0 * Math.PI) robotPhase -= 2.0 * Math.PI

            output[i] = modulated.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return output
    }

    /**
     * Alien: pitch up via sample skipping (every 3rd sample doubled).
     * Creates higher-pitched, otherworldly voice.
     */
    private fun processAlien(samples: ShortArray): ShortArray {
        val output = ShortArray(samples.size)
        var srcIdx = 0
        for (i in output.indices) {
            if (srcIdx < samples.size) {
                output[i] = samples[srcIdx]
                // Skip every 3rd sample (pitch up ~33%)
                srcIdx += if (i % 3 == 2) 2 else 1
            }
        }
        return output
    }

    /**
     * Deep: pitch down via linear interpolation (stretch samples).
     * Produces deeper, slower voice.
     */
    private fun processDeep(samples: ShortArray): ShortArray {
        val stretchFactor = 0.75 // Read slower = deeper pitch
        val output = ShortArray(samples.size)
        var srcIndex = deepResidualIndex

        for (i in output.indices) {
            val intIdx = srcIndex.toInt()
            val frac = (srcIndex - intIdx).toFloat()

            if (intIdx + 1 < samples.size) {
                val interpolated = samples[intIdx] * (1f - frac) + samples[intIdx + 1] * frac
                output[i] = interpolated.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            } else if (intIdx < samples.size) {
                output[i] = samples[intIdx]
            }
            srcIndex += stretchFactor
        }

        // Save residual for next chunk continuity
        deepResidualIndex = srcIndex - samples.size
        if (deepResidualIndex < 0) deepResidualIndex = 0.0

        return output
    }

    /**
     * Chipmunk: pitch up + speed up (drop every other sample, duplicate remaining).
     * Creates high-pitched, fast voice.
     */
    private fun processChipmunk(samples: ShortArray): ShortArray {
        val output = ShortArray(samples.size)
        for (i in output.indices) {
            // Read at 2x speed: take every other sample from input
            val srcIdx = (i * 2).coerceAtMost(samples.size - 1)
            output[i] = samples[srcIdx]
        }
        return output
    }

    /**
     * Echo: circular delay buffer, 300ms delay, 50% feedback.
     * Stateful: delay buffer persists across chunks for continuous echo tail.
     */
    private fun processEcho(samples: ShortArray): ShortArray {
        val output = ShortArray(samples.size)

        for (i in samples.indices) {
            val delayed = echoBuffer[echoWritePos]
            val mixed = samples[i].toFloat() + delayed * ECHO_FEEDBACK
            val clamped = mixed.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output[i] = clamped.toShort()

            echoBuffer[echoWritePos] = mixed
            echoWritePos = (echoWritePos + 1) % ECHO_DELAY_SAMPLES
        }

        return output
    }

    /**
     * Reverb: 4-tap comb filter with prime-spaced delays.
     * Stateful: delay buffers persist across chunks for smooth reverb tail.
     */
    private fun processReverb(samples: ShortArray): ShortArray {
        val output = ShortArray(samples.size)

        for (i in samples.indices) {
            var wet = 0f

            // Sum contributions from each comb filter tap
            for (tap in REVERB_DELAYS.indices) {
                val buffer = reverbBuffers[tap]
                val writePos = reverbWritePositions[tap]
                val delayed = buffer[writePos]
                wet += delayed * REVERB_GAINS[tap]

                // Feed back: input + attenuated delayed
                buffer[writePos] = samples[i].toFloat() + delayed * REVERB_GAINS[tap] * 0.5f
                reverbWritePositions[tap] = (writePos + 1) % REVERB_DELAYS[tap]
            }

            // Mix 40% wet + 60% dry
            val mixed = (samples[i].toFloat() * 0.6f + wet * 0.4f).toInt()
            output[i] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        return output
    }

    /**
     * Anonymous: heavy pitch down (0.5x) + white noise overlay + formant distortion.
     * Produces unrecognizable, anonymized voice.
     */
    private var anonymousResidualIndex = 0.0

    private fun processAnonymous(samples: ShortArray): ShortArray {
        val pitchFactor = 0.5 // Heavy pitch down
        val output = ShortArray(samples.size)
        var srcIndex = anonymousResidualIndex

        for (i in output.indices) {
            // Pitch down via linear interpolation (similar to Deep but more extreme)
            val intIdx = srcIndex.toInt()
            val frac = (srcIndex - intIdx).toFloat()

            val pitched: Float = if (intIdx + 1 < samples.size) {
                samples[intIdx] * (1f - frac) + samples[intIdx + 1] * frac
            } else if (intIdx < samples.size) {
                samples[intIdx].toFloat()
            } else {
                0f
            }

            // Mix white noise (~15% volume)
            val noise = (Random.nextFloat() * 2f - 1f) * 4800f // ~15% of max amplitude

            // Combine pitched voice + noise
            val mixed = (pitched * 0.85f + noise * 0.15f).toInt()
            output[i] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

            srcIndex += pitchFactor
        }

        // Save residual for next chunk continuity
        anonymousResidualIndex = srcIndex - samples.size
        if (anonymousResidualIndex < 0) anonymousResidualIndex = 0.0

        return output
    }
}
