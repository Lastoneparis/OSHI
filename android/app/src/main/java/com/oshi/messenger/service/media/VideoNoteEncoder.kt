package com.oshi.messenger.service.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMuxer
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * __VIDEO_NOTE_2026_09_24__ The recorded clip → the note contract of
 * `docs/VIDEO_NOTE_SPEC.md` §3: square centre crop, ≤ 480 × 480, H.264 + AAC in MP4,
 * ≈ 1.2 Mbit/s, ≤ 15 s, rotation baked in.
 *
 * Why Media3 Transformer and not [VideoTranscoder]: the existing transcoder is a
 * decoder→encoder SURFACE pipe with no GL stage, so it can scale but cannot CROP; a square
 * crop needs a shader. Transformer is the same Media3 family the app already plays video
 * with (`media3-exoplayer`, same 1.2.0), and its [Presentation] effect does crop + scale +
 * rotation in one GL pass on the hardware encoder.
 *
 * Metadata: Transformer writes a fresh MP4 through Media3's in-app muxer with a metadata
 * provider that drops every entry, so no location / make / model / OS-version atom reaches
 * the file (CameraX writes no location unless asked, and it is not asked). The file is written into the caller's plaintext scratch
 * area and the caller deletes it once the bytes are handed to the send pipeline.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object VideoNoteEncoder {

    private const val TAG = "VideoNoteEncoder"

    data class Result(val file: File, val durationMs: Long)

    /**
     * Encode [input] into [output]. Returns null on failure ([output] deleted). Suspends
     * on the main looper: Transformer must be driven from a Looper thread.
     */
    suspend fun encode(context: Context, input: File, output: File, mirror: Boolean = false): Result? =
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(input))
                    // Hard cap on the ENCODE side too: whatever the recorder did, the
                    // note that leaves this device is never longer than 15.0 s.
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setEndPositionMs(VideoNote.MAX_DURATION_MS)
                            .build()
                    )
                    .build()
                val edited = EditedMediaItem.Builder(mediaItem)
                    .setEffects(
                        Effects(
                            emptyList(),
                            buildList {
                                // Spec §3 (rev. 2026-09-24): a FRONT-camera note is encoded
                                // as seen in the selfie preview — mirrored — like iOS, which
                                // mirrors at capture. Receivers never flip.
                                if (mirror) add(ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build())
                                add(
                                    Presentation.createForWidthAndHeight(
                                        VideoNote.SIDE_PX, VideoNote.SIDE_PX,
                                        Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                                    )
                                )
                            }
                        )
                    )
                    .build()
                val encoderFactory = DefaultEncoderFactory.Builder(context.applicationContext)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder()
                            .setBitrate(VideoNote.VIDEO_BITRATE)
                            .build()
                    )
                    .setEnableFallback(true)
                    .build()
                val transformer = Transformer.Builder(context.applicationContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    // Spec §3 "NO location, NO device make/model/software". The framework
                    // MediaMuxer stamps `com.android.version` (the OS release) and copies
                    // nothing else we can veto; Media3's own muxer takes a metadata
                    // provider, and this one drops EVERY entry the input carried.
                    .setMuxerFactory(
                        InAppMuxer.Factory(
                            /* maxDelayBetweenSamplesMs = */ 10_000L,
                            InAppMuxer.MetadataProvider { entries -> entries.clear() }
                        )
                    )
                    .setLooper(Looper.getMainLooper())
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            val d = probeDurationMs(output).takeIf { it > 0L } ?: exportResult.durationMs
                            Log.d(TAG, "🎥 note encoded: ${output.length()}B, ${d}ms")
                            if (cont.isActive) cont.resume(Result(output, d))
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            Log.w(TAG, "note encode failed: ${exportException.errorCodeName} ${exportException.message}")
                            output.delete()
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                    .build()
                try {
                    transformer.start(edited, output.absolutePath)
                } catch (e: Exception) {
                    Log.w(TAG, "note encode could not start: ${e.message}")
                    output.delete()
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post {
                        runCatching { transformer.cancel() }
                        output.delete()
                    }
                }
            }
        }

    /** Container duration in ms, 0 when unreadable. Blocking but cheap (header only). */
    fun probeDurationMs(file: File): Long {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { r.release() }
        }
    }
}
