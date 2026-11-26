package com.oshi.messenger.wear.data

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.oshi.wearbridge.WearProtocol
import java.io.File

/**
 * __WEAR_POLISH_2026_11_25__ Records one voice message on the watch, in the
 * format the phone's own voice path sends (MPEG-4 / AAC, `.m4a`, which iOS
 * and Android both play), at a speech bitrate small enough to cross the Data
 * Layer quickly: mono 16 kHz, 32 kbit/s ≈ 240 KB a minute.
 *
 * MediaRecorder needs a SEEKABLE output (the MP4 index is written at the end),
 * so the note lives for the few seconds of recording in a file under
 * `noBackupFilesDir/voice` — excluded from backup, never shared — and is read
 * into memory and DELETED the moment recording stops or is cancelled. Any
 * leftover from a killed process is swept by [sweep] at the next launch.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    fun elapsedMs(): Long = if (startedAt == 0L) 0L else SystemClock.elapsedRealtime() - startedAt

    /** False when the microphone could not be opened (busy, no permission, no mic). */
    fun start(): Boolean {
        cancel()
        val dir = dir(context).apply { mkdirs() }
        val f = File(dir, "note-${System.nanoTime()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = f
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            runCatching { r.release() }
            f.delete()
            false
        }
    }

    /** Stops and returns the note (bytes + length), deleting the file. Null if nothing usable. */
    fun stop(): Pair<ByteArray, Long>? {
        val r = recorder ?: return null
        val f = file
        // The screen stops it at the cap itself (no setMaxDuration: a recorder that
        // stopped on its own throws from stop() and the note would be lost).
        val duration = elapsedMs().coerceAtMost(WearProtocol.MAX_VOICE_MS)
        recorder = null
        file = null
        startedAt = 0L
        val ok = runCatching { r.stop() }.isSuccess // throws when stopped right after start
        runCatching { r.release() }
        return try {
            if (!ok || f == null || duration < WearProtocol.MIN_VOICE_MS) null
            else f.readBytes().takeIf { it.isNotEmpty() && it.size <= WearProtocol.MAX_VOICE_BYTES }?.let { it to duration }
        } catch (e: Exception) {
            null
        } finally {
            f?.delete()
        }
    }

    /** Throw the note away. */
    fun cancel() {
        val r = recorder
        recorder = null
        startedAt = 0L
        if (r != null) {
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        file?.delete()
        file = null
    }

    companion object {
        fun dir(context: Context) = File(context.noBackupFilesDir, "voice")

        /** Leftovers of a process killed mid-recording. */
        fun sweep(context: Context) {
            dir(context).listFiles()?.forEach { it.delete() }
        }
    }
}
