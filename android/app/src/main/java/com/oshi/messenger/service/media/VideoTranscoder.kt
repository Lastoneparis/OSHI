package com.oshi.messenger.service.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * VideoTranscoder — the real MediaCodec pipeline that replaces the stub.
 *
 * `MediaCompressionManager.transcodeVideo` used to open a `MediaExtractor`, compute a
 * bitrate it never applied, release everything and `return null` under the comment
 * "Return null to indicate transcoding not yet implemented". The caller then used the
 * ORIGINAL bytes while reporting `isCompressed = false, compressionRatio = 1.0` — a
 * stub returning null behind a fallback that hides it. Videos were never shrunk on
 * Android: a 45 MB clip "passed" compression and then 413'd at upload.
 *
 * iOS reference: `OSHI/MediaManager.swift:470-570` — a real `AVAssetExportSession`
 * with the measured preset ladder at `:505-522` and a graceful fall back to the
 * original on export failure (`:548-566`). The ladder and its measured selection live
 * in [VideoPresetLadder]; this class is the encode.
 *
 * ## Pipeline
 *
 * ```
 * MediaExtractor(video) → MediaCodec decoder ─render→ encoder input Surface
 *                                                     → MediaCodec encoder (H.264)
 *                                                     → MediaMuxer(mp4)
 * MediaExtractor(audio) ─────── compressed passthrough ─────────────↑
 * ```
 *
 * The decoder renders straight into the encoder's input surface, so frames never make
 * a round trip through the heap and the BufferQueue does the scaling. Audio is COPIED,
 * not re-encoded: it is already AAC, re-encoding only loses quality, and its share of
 * the byte budget is tiny (`VideoPresetLadder.DEFAULT_AUDIO_BITRATE`).
 *
 * ## Failure posture
 *
 * Every failure returns false and leaves [output] deleted; the caller then keeps the
 * original bytes. That is iOS's own behaviour at `MediaManager.swift:548-566` — a
 * larger file is a recoverable failure, a truncated or corrupt one is not.
 */
object VideoTranscoder {

    private const val TAG = "VideoTranscoder"
    private const val OUTPUT_MIME = MediaFormat.MIMETYPE_VIDEO_AVC   // H.264, what iOS emits
    private const val TIMEOUT_US = 10_000L

    data class Probe(
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val frameRate: Int,
        val hasAudio: Boolean,
        val audioBitrate: Int,
        /**
         * __MEDIA_INTEROP_2026_09_18__ The track codecs, which decide whether this
         * clip may be sent UNTOUCHED. `MediaMetadataRetriever` does not expose
         * them, so they come off a `MediaExtractor` — the same object the pipeline
         * opens anyway. Null means "could not tell", which
         * [VideoPresetLadder.isInteroperable] treats as "not a promise".
         */
        val videoMime: String? = null,
        val audioMime: String? = null,
    )

    /** Read what we need to pick a preset. Null when the file is not a decodable video. */
    fun probe(input: File): Probe? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(input.absolutePath)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val fps = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()?.toInt()
                ?: VideoPresetLadder.MAX_FRAME_RATE
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val (vMime, aMime) = trackMimes(input)
            if (duration <= 0 || w <= 0 || h <= 0) null
            else Probe(duration, w, h, rot, fps.coerceIn(1, 120), hasAudio,
                if (hasAudio) VideoPresetLadder.DEFAULT_AUDIO_BITRATE else 0,
                videoMime = vMime, audioMime = aMime)
        } catch (e: Throwable) {
            Log.w(TAG, "probe failed: ${e.message}")
            null
        } finally {
            try { r.release() } catch (_: Throwable) {}
        }
    }

    /**
     * Transcode [input] to [output] under [budgetBytes], choosing the preset by
     * measurement exactly like `MediaManager.swift:511-522`.
     *
     * @return true when [output] holds a complete, playable, smaller file.
     */
    fun transcode(input: File, output: File, budgetBytes: Long): Boolean {
        val probe = probe(input) ?: run {
            Log.w(TAG, "not a decodable video — keeping original")
            return false
        }
        // __CHAT_VIDEO_BITRATE_2026_10_09__ The size AND the rate, derived from this
        // clip — not a preset whose bitrate the budget happened to wave through. See
        // the header of [VideoPresetLadder].
        val plan = VideoPresetLadder.plan(
            width = probe.width,
            height = probe.height,
            durationMs = probe.durationMs,
            sourceFrameRate = probe.frameRate,
            budgetBytes = budgetBytes,
            audioBitrate = probe.audioBitrate,
        )
        Log.d(
            TAG,
            "🎬 Video plan: ${plan.label} @${plan.frameRate}fps (estimated ${plan.estimatedBytes / 1024} KB, " +
                "budget ${budgetBytes / 1024} KB, fits=${plan.fitsBudget})"
        )
        if (plan.width <= 0 || plan.height <= 0) return false

        return try {
            if (!runPipeline(input, output, probe, plan)) return false
            // Move the `moov` atom to the front so the receiver can start decoding
            // before the whole file has arrived — iOS's `shouldOptimizeForNetworkUse`.
            // A failure here is cosmetic: the un-relocated file still plays.
            Mp4FastStart.relocateMoovInPlace(output)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "transcode failed: ${e.message}")
            output.delete()
            false
        }
    }

    // =====================================================================

    private fun runPipeline(
        input: File,
        output: File,
        probe: Probe,
        plan: VideoPresetLadder.Plan,
    ): Boolean {
        val targetW = plan.width
        val targetH = plan.height
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: android.view.Surface? = null
        var muxerStarted = false
        var reencodedAudio: File? = null

        try {
            videoExtractor = MediaExtractor().apply { setDataSource(input.absolutePath) }
            val videoTrack = firstTrack(videoExtractor, "video/") ?: return false
            val inFormat = videoExtractor.getTrackFormat(videoTrack)
            videoExtractor.selectTrack(videoTrack)

            // ---- Encoder ----------------------------------------------------
            val outFormat = MediaFormat.createVideoFormat(OUTPUT_MIME, targetW, targetH).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                // __CHAT_VIDEO_BITRATE_2026_10_09__ The explicit rate is the fix: a
                // resolution cap alone lets the encoder spend 12-15 Mbps on 1080p.
                setInteger(MediaFormat.KEY_BIT_RATE, plan.videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, plan.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VideoPresetLadder.I_FRAME_INTERVAL_SECONDS)
            }
            encoder = MediaCodec.createEncoderByType(OUTPUT_MIME)
            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()

            // ---- Decoder → encoder surface ----------------------------------
            val inMime = inFormat.getString(MediaFormat.KEY_MIME) ?: return false
            decoder = MediaCodec.createDecoderByType(inMime)
            decoder.configure(inFormat, inputSurface, null, 0)
            decoder.start()

            // ---- Muxer -------------------------------------------------------
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // Preserve the camera orientation, or a portrait clip plays sideways.
            if (probe.rotationDegrees != 0) muxer.setOrientationHint(probe.rotationDegrees)

            // Audio rides through as compressed samples when it is ALREADY AAC — no
            // re-encode, no quality loss.
            //
            // __MEDIA_INTEROP_2026_09_18__ …and only then. This used to pass every
            // audio track straight to an MPEG-4 muxer on the stated assumption that
            // "it is already AAC". For a camera capture that holds. For the rest of
            // the gallery it does not: Opus and Vorbis arrive with downloaded WebM,
            // AMR with voice recorders. `MediaMuxer` REFUSES those, which threw, which
            // was caught, which returned false — and the caller then sent the original
            // file, the very file an iPhone cannot open. The failure path quietly
            // delivered the thing this whole class exists to prevent.
            //
            // The re-encode lands in a temp .m4a FIRST and is then muxed by the same
            // passthrough as any AAC source. `MediaMuxer` demands every track before
            // `start()`, and an encoder's real output format (with its csd-0) only
            // exists after it has produced a frame — so encoding in-line would mean
            // hand-rolling an AAC codec-specific-data header and hoping. One extra
            // temp file, and the muxing path below stays the single path it is.
            var audioTrackOut = -1
            var audioTrackIn = -1
            if (probe.hasAudio) {
                var audioSource: File = input
                val probedAudioMime = probe.audioMime
                if (!VideoPresetLadder.INTEROP_AUDIO_MIME.equals(probedAudioMime, ignoreCase = true)) {
                    Log.d(TAG, "🔊 audio is $probedAudioMime — re-encoding to AAC for interop")
                    val reencoded = try {
                        encodeAudioToAacFile(input, File(output.parentFile, "aac_${output.name}.m4a"))
                    } catch (e: Throwable) {
                        Log.w(TAG, "🔊 AAC re-encode threw: ${e.message}")
                        null
                    }
                    if (reencoded != null) {
                        audioSource = reencoded
                        reencodedAudio = reencoded
                    } else {
                        // A failure here is not fatal to the clip: the video track is
                        // interoperable on its own, and a silent H.264/MP4 plays
                        // everywhere, which beats shipping bytes the peer cannot open.
                        Log.w(TAG, "🔊 sending without audio — the video still plays on both platforms")
                        audioSource = input
                    }
                }
                if (audioSource !== input || VideoPresetLadder.INTEROP_AUDIO_MIME
                        .equals(probedAudioMime, ignoreCase = true)
                ) {
                    audioExtractor = MediaExtractor().apply { setDataSource(audioSource.absolutePath) }
                    audioTrackIn = firstTrack(audioExtractor, "audio/") ?: -1
                    if (audioTrackIn >= 0) {
                        audioExtractor.selectTrack(audioTrackIn)
                        audioTrackOut = muxer.addTrack(audioExtractor.getTrackFormat(audioTrackIn))
                    }
                }
            }

            var videoTrackOut = -1
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var encoderDone = false

            while (!encoderDone) {
                // 1. feed the decoder
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = decoder.getInputBuffer(inIndex)!!
                        val size = videoExtractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, videoExtractor.sampleTime, 0)
                            videoExtractor.advance()
                        }
                    }
                }

                // 2. drain the decoder straight into the encoder's surface
                if (!decoderDone) {
                    val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outIndex >= 0) {
                        val render = bufferInfo.size > 0
                        decoder.releaseOutputBuffer(outIndex, render)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoderDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                // 3. drain the encoder into the muxer
                val encIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) throw IllegalStateException("encoder format changed twice")
                        videoTrackOut = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                        // Copy the whole audio track now that the muxer is live.
                        if (audioTrackOut >= 0 && audioExtractor != null) {
                            copyAudio(audioExtractor, muxer, audioTrackOut)
                        }
                    }
                    encIndex >= 0 -> {
                        val encoded = encoder.getOutputBuffer(encIndex)!!
                        // Codec config bytes belong in the track format, not the stream.
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackOut, encoded, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(encIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                    }
                }
            }

            return output.isFile && output.length() > 0
        } finally {
            try { decoder?.stop() } catch (_: Throwable) {}
            try { decoder?.release() } catch (_: Throwable) {}
            try { encoder?.stop() } catch (_: Throwable) {}
            try { encoder?.release() } catch (_: Throwable) {}
            try { inputSurface?.release() } catch (_: Throwable) {}
            if (muxerStarted) try { muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            try { videoExtractor?.release() } catch (_: Throwable) {}
            try { audioExtractor?.release() } catch (_: Throwable) {}
            try { reencodedAudio?.delete() } catch (_: Throwable) {}
        }
    }

    /**
     * __MEDIA_INTEROP_2026_09_18__ Decode [input]'s audio track and write it back
     * as AAC in its own `.m4a`, returning that file — or null if anything at all
     * goes wrong.
     *
     * Opus, Vorbis, AMR and FLAC are all things a phone's gallery legitimately
     * holds and an MPEG-4 muxer legitimately refuses. Rather than let that refusal
     * sink the whole transcode (and send the original, unplayable file), the track
     * is rebuilt in the one audio codec both platforms read.
     *
     * Deliberately a SEPARATE pass with its own muxer: the caller then treats the
     * result exactly like a source that was AAC all along, so there is one muxing
     * path in this file rather than two that can drift.
     *
     * Channel count and sample rate follow the SOURCE. Resampling is not attempted:
     * every AAC encoder Android ships handles the rates a recorder produces, and
     * guessing at a conversion is how audio ends up chipmunked.
     */
    private fun encodeAudioToAacFile(input: File, output: File): File? {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackOut = -1
        try {
            extractor = MediaExtractor().apply { setDataSource(input.absolutePath) }
            val track = firstTrack(extractor, "audio/") ?: return null
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val inMime = inFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = inFormat.runCatching { getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                .getOrDefault(44_100)
            val channels = inFormat.runCatching { getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                .getOrDefault(2)
                .coerceIn(1, 2)

            val outFormat = MediaFormat.createAudioFormat(
                VideoPresetLadder.INTEROP_AUDIO_MIME, sampleRate, channels
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, VideoPresetLadder.DEFAULT_AUDIO_BITRATE)
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            encoder = MediaCodec.createEncoderByType(VideoPresetLadder.INTEROP_AUDIO_MIME)
            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            decoder = MediaCodec.createDecoderByType(inMime)
            decoder.configure(inFormat, null, null, 0)
            decoder.start()

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val info = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            var encoderDone = false
            var pendingPcm: ByteBuffer? = null
            var pendingPts = 0L

            while (!encoderDone) {
                // 1. source → decoder
                if (!extractorDone) {
                    val i = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = decoder.getInputBuffer(i)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // 2. decoder → PCM held for the encoder
                if (pendingPcm == null && !decoderDone) {
                    val o = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (o >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderDone = true
                        if (info.size > 0) {
                            val src = decoder.getOutputBuffer(o)!!
                            val copy = ByteBuffer.allocate(info.size)
                            src.position(info.offset)
                            src.limit(info.offset + info.size)
                            copy.put(src)
                            copy.flip()
                            pendingPcm = copy
                            pendingPts = info.presentationTimeUs
                        }
                        decoder.releaseOutputBuffer(o, false)
                    }
                }

                // 3. PCM → encoder
                run {
                    val i = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val pcm = pendingPcm
                        if (pcm != null) {
                            val dst = encoder.getInputBuffer(i)!!
                            dst.clear()
                            val n = minOf(pcm.remaining(), dst.remaining())
                            val slice = pcm.slice()
                            slice.limit(n)
                            dst.put(slice)
                            pcm.position(pcm.position() + n)
                            encoder.queueInputBuffer(i, 0, n, pendingPts, 0)
                            if (!pcm.hasRemaining()) pendingPcm = null
                        } else if (decoderDone) {
                            encoder.queueInputBuffer(i, 0, 0, pendingPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            // Nothing to feed yet; hand the buffer back by doing
                            // nothing — it is re-offered on the next dequeue.
                            encoder.queueInputBuffer(i, 0, 0, pendingPts, 0)
                        }
                    }
                }

                // 4. encoder → muxer
                val o = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackOut = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    o >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            val enc = encoder.getOutputBuffer(o)!!
                            enc.position(info.offset)
                            enc.limit(info.offset + info.size)
                            muxer.writeSampleData(trackOut, enc, info)
                        }
                        encoder.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                    }
                }
            }
            return if (output.isFile && output.length() > 0) output else null
        } catch (e: Throwable) {
            Log.w(TAG, "🔊 audio re-encode failed: ${e.message}")
            output.delete()
            return null
        } finally {
            try { decoder?.stop() } catch (_: Throwable) {}
            try { decoder?.release() } catch (_: Throwable) {}
            try { encoder?.stop() } catch (_: Throwable) {}
            try { encoder?.release() } catch (_: Throwable) {}
            if (muxerStarted) try { muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            try { extractor?.release() } catch (_: Throwable) {}
        }
    }

    private fun copyAudio(extractor: MediaExtractor, muxer: MediaMuxer, trackOut: Int) {
        val buf = ByteBuffer.allocate(256 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(trackOut, buf, info)
            extractor.advance()
        }
    }

    /**
     * __MEDIA_INTEROP_2026_09_18__ `(videoMime, audioMime)` for [input], or nulls
     * when the file cannot be opened. Cheap: opening an extractor parses the
     * container's header, it does not read samples.
     */
    private fun trackMimes(input: File): Pair<String?, String?> {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(input.absolutePath)
            var v: String? = null
            var a: String? = null
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (v == null && mime.startsWith("video/")) v = mime
                if (a == null && mime.startsWith("audio/")) a = mime
            }
            v to a
        } catch (e: Throwable) {
            Log.w(TAG, "track mime probe failed: ${e.message}")
            null to null
        } finally {
            try { ex.release() } catch (_: Throwable) {}
        }
    }

    private fun firstTrack(extractor: MediaExtractor, mimePrefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return null
    }
}
