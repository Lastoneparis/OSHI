package com.oshi.messenger.service.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * AAC-ELD Codec - iOS Compatible
 *
 * Uses Android MediaCodec to encode/decode AAC Enhanced Low Delay audio.
 * This matches the iOS VoiceCallManager which uses AudioToolbox AAC-ELD
 * (same codec as FaceTime).
 *
 * Specifications:
 * - Sample rate: 48kHz mono (matching iOS)
 * - Bitrate: 48kbps default (adaptive: 24k-80k matching iOS)
 * - Codec delay: ~15-32ms (low delay, suitable for VoIP)
 * - Compression: ~16:1 (768kbps PCM → 48kbps AAC-ELD)
 */
class AacEldCodec {
    companion object {
        private const val TAG = "AacEldCodec"
        const val SAMPLE_RATE = 48000
        private const val CHANNELS = 1
        private const val DEFAULT_BIT_RATE = 64000 // 64kbps (matches iOS default: kAudioConverterEncodeBitRate = 64000)
        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val TIMEOUT_US = 10000L // 10ms timeout for buffer operations
    }

    private var encoder: MediaCodec? = null
    private var decoder: MediaCodec? = null
    private var csd0: ByteArray? = null
    private var isStarted = false
    private var bitRate = DEFAULT_BIT_RATE
    // Locks to prevent concurrent MediaCodec access (causes "another dequeue request is pending")
    private val encoderLock = Object()
    private val decoderLock = Object()

    /**
     * Start the codec. Returns true if AAC-ELD is supported on this device.
     */
    fun start(): Boolean {
        try {
            // Create and configure encoder
            val encFormat = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNELS)
            encFormat.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectELD
            )
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            encFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SAMPLE_RATE * 2) // 1s of PCM16

            encoder = MediaCodec.createEncoderByType(MIME_TYPE)
            encoder?.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder?.start()

            // Extract CSD (AudioSpecificConfig) from encoder
            extractCsd()

            // Create and configure decoder with CSD
            val decFormat = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNELS)
            decFormat.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectELD
            )
            csd0?.let {
                decFormat.setByteBuffer("csd-0", ByteBuffer.wrap(it))
            }

            decoder = MediaCodec.createDecoderByType(MIME_TYPE)
            decoder?.configure(decFormat, null, null, 0)
            decoder?.start()

            isStarted = true
            Log.d(TAG, "AAC-ELD codec started (csd=${csd0?.size} bytes, bitrate=${bitRate}bps)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "AAC-ELD not supported on this device: ${e.message}")
            release()
            return false
        }
    }

    /**
     * Feed a silent frame to the encoder to trigger CSD output.
     */
    private fun extractCsd() {
        val enc = encoder ?: return

        // Feed one frame of silence (20ms at 48kHz = 960 samples = 1920 bytes PCM16)
        val silence = ByteArray(960 * 2)
        val inIdx = enc.dequeueInputBuffer(TIMEOUT_US)
        if (inIdx >= 0) {
            val inBuf = enc.getInputBuffer(inIdx) ?: return
            inBuf.clear()
            inBuf.put(silence)
            enc.queueInputBuffer(inIdx, 0, silence.size, 0, 0)
        }

        // Drain output looking for CSD
        val bufInfo = MediaCodec.BufferInfo()
        for (attempt in 0..5) {
            val outIdx = enc.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
            if (outIdx >= 0) {
                val outBuf = enc.getOutputBuffer(outIdx)
                if (outBuf != null && bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    csd0 = ByteArray(bufInfo.size)
                    outBuf.get(csd0!!) // safe: just assigned on line above
                    Log.d(TAG, "Extracted CSD: ${csd0?.size} bytes")
                }
                enc.releaseOutputBuffer(outIdx, false)
                if (csd0 != null) break
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Format changed, try again
                continue
            }
        }
    }

    /**
     * Encode PCM16 audio to AAC-ELD.
     *
     * @param pcmData 16-bit PCM samples at 48kHz mono (little-endian byte pairs)
     * @return AAC-ELD compressed data, or null if encoding failed
     */
    fun encode(pcmData: ByteArray): ByteArray? {
        val enc = encoder ?: return null
        if (!isStarted) return null

        synchronized(encoderLock) {
            try {
                val inIdx = enc.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx < 0) return null

                val inBuf = enc.getInputBuffer(inIdx) ?: return null
                inBuf.clear()
                inBuf.put(pcmData)
                enc.queueInputBuffer(inIdx, 0, pcmData.size, System.nanoTime() / 1000, 0)

                val output = ByteArrayOutputStream()
                val bufInfo = MediaCodec.BufferInfo()

                // Retry output dequeue — MediaCodec may need time to produce output,
                // especially on lower-end devices (Samsung A17). Original 1ms was too short.
                for (attempt in 0..4) {
                    val timeout = if (attempt == 0) TIMEOUT_US else 5000L // 10ms first, then 5ms retries
                    val outIdx = enc.dequeueOutputBuffer(bufInfo, timeout)
                    if (outIdx >= 0) {
                        val outBuf = enc.getOutputBuffer(outIdx)
                        if (outBuf != null) {
                            if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                enc.releaseOutputBuffer(outIdx, false)
                                continue
                            }
                            if (bufInfo.size > 0) {
                                val data = ByteArray(bufInfo.size)
                                outBuf.get(data)
                                output.write(data)
                            }
                        }
                        enc.releaseOutputBuffer(outIdx, false)
                        // Got output, drain any remaining
                        while (true) {
                            val extraIdx = enc.dequeueOutputBuffer(bufInfo, 1000)
                            if (extraIdx < 0) break
                            val extraBuf = enc.getOutputBuffer(extraIdx)
                            if (extraBuf != null && bufInfo.size > 0 &&
                                bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val data = ByteArray(bufInfo.size)
                                extraBuf.get(data)
                                output.write(data)
                            }
                            enc.releaseOutputBuffer(extraIdx, false)
                        }
                        break
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        continue // Format changed, retry
                    }
                    // outIdx < 0 and not format change — wait and retry
                }

                val result = output.toByteArray()
                return if (result.isNotEmpty()) result else null
            } catch (e: Exception) {
                Log.e(TAG, "Encode failed: ${e.message}")
                return null
            }
        }
    }

    /**
     * Decode AAC-ELD audio to PCM16.
     *
     * @param aacData AAC-ELD compressed data
     * @return 16-bit PCM samples at 48kHz mono, or null if decoding failed
     */
    fun decode(aacData: ByteArray): ByteArray? {
        val dec = decoder ?: return null
        if (!isStarted) return null

        synchronized(decoderLock) {
            try {
                val inIdx = dec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx < 0) return null

                val inBuf = dec.getInputBuffer(inIdx) ?: return null
                inBuf.clear()
                inBuf.put(aacData)
                dec.queueInputBuffer(inIdx, 0, aacData.size, System.nanoTime() / 1000, 0)

                val output = ByteArrayOutputStream()
                val bufInfo = MediaCodec.BufferInfo()
                // Use longer timeout and retry — MediaCodec may need time on slower devices
                for (attempt in 0..4) {
                    val timeout = if (attempt == 0) TIMEOUT_US else 5000L
                    val outIdx = dec.dequeueOutputBuffer(bufInfo, timeout)
                    if (outIdx >= 0) {
                        val outBuf = dec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufInfo.size > 0) {
                            val data = ByteArray(bufInfo.size)
                            outBuf.get(data)
                            output.write(data)
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                        // Drain any remaining output
                        while (true) {
                            val extraIdx = dec.dequeueOutputBuffer(bufInfo, 1000)
                            if (extraIdx < 0) break
                            val extraBuf = dec.getOutputBuffer(extraIdx)
                            if (extraBuf != null && bufInfo.size > 0) {
                                val data = ByteArray(bufInfo.size)
                                extraBuf.get(data)
                                output.write(data)
                            }
                            dec.releaseOutputBuffer(extraIdx, false)
                        }
                        break
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        continue
                    }
                    // Not ready yet, retry with next attempt
                }

                val result = output.toByteArray()
                return if (result.isNotEmpty()) result else null
            } catch (e: Exception) {
                Log.e(TAG, "Decode failed: ${e.message}")
                return null
            }
        }
    }

    /**
     * Set bitrate for adaptive quality (matching iOS adaptive bitrate).
     * AAC-ELD: 24k (emergency), 32k (poor), 48k (normal), 64k (good), 80k (excellent)
     */
    fun setBitrate(newBitrate: Int) {
        val oldBitRate = bitRate
        bitRate = newBitrate
        encoder?.let { enc ->
            try {
                // Android MediaCodec only exposes PARAMETER_KEY_VIDEO_BITRATE for dynamic changes.
                // Most audio encoders ignore this, but some vendor implementations honor it.
                val params = android.os.Bundle()
                params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)
                enc.setParameters(params)
                Log.d(TAG, "Dynamic bitrate change attempted: ${oldBitRate/1000}kbps → ${newBitrate/1000}kbps")
            } catch (e: Exception) {
                // Not all encoders support dynamic bitrate change — log for diagnostics
                Log.d(TAG, "Dynamic bitrate change not supported (${oldBitRate/1000}→${newBitrate/1000}kbps): ${e.message}")
            }
        }
    }

    /**
     * Reset codec state (flush buffers).
     */
    fun reset() {
        try {
            encoder?.flush()
            encoder?.start()
            decoder?.flush()
            decoder?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Reset failed: ${e.message}")
        }
    }

    /**
     * Release all codec resources.
     */
    fun release() {
        try {
            encoder?.stop()
            encoder?.release()
        } catch (_: Exception) { }
        try {
            decoder?.stop()
            decoder?.release()
        } catch (_: Exception) { }
        encoder = null
        decoder = null
        isStarted = false
    }

    val name: String
        get() = "AAC-ELD (48kHz, ${bitRate / 1000}kbps)"
}
