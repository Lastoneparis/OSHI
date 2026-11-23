package com.oshi.messenger.service.audio

/**
 * Kotlin wrapper around the proprietary OshiCodec (Component A of Call v2).
 *
 * The underlying C++ implementation lives in llama.cpp/jni/oshi_codec/ and is
 * compiled into liboshi_codec.so via app/src/main/cpp/CMakeLists.txt.
 *
 * Instances are not thread-safe. Each direction of a call should own its own
 * OshiCodec (one for encode, one for decode) to keep predictor state separate.
 *
 * Usage:
 *   OshiCodec(sampleRate = 48000, channels = 1).use { codec ->
 *       codec.setBitrate(48_000)
 *       val bytes = codec.encode(shortArrayOf960SamplesOfPcm)
 *       val pcm   = codec.decode(bytes)
 *   }
 */
class OshiCodec(
    sampleRate: Int = 48000,
    channels: Int = 1
) : AutoCloseable {

    private var handle: Long = 0L

    init {
        handle = nativeCreate(sampleRate, channels)
        if (handle == 0L) {
            throw IllegalStateException(
                "Failed to create OshiCodec (sr=$sampleRate, ch=$channels). " +
                "Check that liboshi_codec.so is packaged in the APK."
            )
        }
    }

    /**
     * Set the target wire bitrate in bits-per-second. Accepts raw bps; the
     * codec rounds to its nearest supported level (16/24/32/48/64 kbps).
     */
    fun setBitrate(bps: Int) {
        ensureOpen()
        nativeSetBitrate(handle, bps)
    }

    /**
     * Reset encoder / decoder state (LPC history, seq counter, etc.). Call
     * this on call start or when the peer signals a stream restart.
     */
    fun reset() {
        ensureOpen()
        nativeReset(handle)
    }

    /**
     * Encode exactly one 20 ms frame: 960 samples of 48 kHz mono int16 PCM.
     *
     * @return compressed bytes for the frame (including 2-byte header).
     * @throws IllegalArgumentException if [pcm] is not [FRAME_SIZE] samples.
     * @throws IllegalStateException if the native call failed.
     */
    fun encode(pcm: ShortArray): ByteArray {
        ensureOpen()
        require(pcm.size == FRAME_SIZE) {
            "OshiCodec.encode requires exactly $FRAME_SIZE samples, got ${pcm.size}"
        }
        return nativeEncode(handle, pcm)
            ?: throw IllegalStateException("OshiCodec native encode failed")
    }

    /**
     * Decode one compressed frame into 960 int16 PCM samples.
     *
     * @throws IllegalStateException if the native call failed.
     */
    fun decode(bytes: ByteArray): ShortArray {
        ensureOpen()
        require(bytes.isNotEmpty()) { "OshiCodec.decode received empty byte array" }
        return nativeDecode(handle, bytes)
            ?: throw IllegalStateException("OshiCodec native decode failed")
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private fun ensureOpen() {
        if (handle == 0L) error("OshiCodec is closed")
    }

    // --- native bindings ------------------------------------------------------
    private external fun nativeCreate(sampleRate: Int, channels: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeReset(handle: Long)
    private external fun nativeSetBitrate(handle: Long, bitrateBps: Int)
    private external fun nativeEncode(handle: Long, pcm: ShortArray): ByteArray?
    private external fun nativeDecode(handle: Long, bytes: ByteArray): ShortArray?

    companion object {
        private const val TAG = "OshiCodec"

        /** Samples per 20 ms frame at 48 kHz mono (matches OSHI_FRAME_SIZE). */
        const val FRAME_SIZE: Int = 960
        const val SAMPLE_RATE: Int = 48000

        /**
         * True if liboshi_codec.so loaded successfully. Callers can gate
         * OshiCodec usage on this flag and fall back to raw PCM otherwise.
         */
        @JvmStatic
        val isAvailable: Boolean = try {
            System.loadLibrary("oshi_codec")
            true
        } catch (t: Throwable) {
            // Swallowed intentionally. In device builds liboshi_codec.so is
            // packaged under lib/arm64-v8a/; in host-JVM unit tests it is
            // absent and callers gate on isAvailable. Using println keeps us
            // out of android.util.Log which isn't mocked on the host JVM.
            println("[$TAG] liboshi_codec.so not loaded: ${t.message}")
            false
        }
    }
}
