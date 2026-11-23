package com.oshi.messenger.ui.media

import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything a voice-note bubble needs that is NOT a composable: the waveform bars and
 * the real duration.
 *
 * ## Why this exists
 *
 * Android drew a flat `LinearProgressIndicator` where iOS draws a real waveform
 * (`AudioMessageView.swift:83-90` → `AudioWaveformView`, fed by
 * `AudioRecorderManager.AudioAttachment.waveformData`, generated from the audio itself
 * when none was supplied — `AudioRecorderManager.swift:236,258-372`).
 *
 * And the duration was scraped out of the message BODY (`ChatScreen.extractAudioDuration`
 * on `"[AUDIO] 0:05"`), which is wrong twice over:
 *  * an iPhone voice note has an empty body (the V2 file path carries no caption), so
 *    every inbound iOS note rendered **0:00**;
 *  * Android's own body was that literal string, and in GROUPS `content` is copied into
 *    `plaintextContent` — the one key iOS draws a caption from (`GroupViews.swift:2617`) —
 *    so every iPhone printed "[AUDIO] 0:05" under the voice note. Same shape as the
 *    document bug fixed in the shell pass.
 *
 * ## Order of truth for the duration
 *
 * [resolveDurationSeconds] returns the stored field first, then the legacy body, and
 * `null` when neither can answer — `null` means "ask the bytes" ([probeDurationSeconds]).
 * The legacy body is kept ON PURPOSE: notes already on disk, and notes from an older
 * Android build, carry the duration only there, and dropping the fallback would blank
 * every voice note a user already has.
 */
object VoiceNoteAudio {

    /** iOS `AudioAttachment.generateWaveform` uses 50 samples (`AudioRecorderManager.swift:257`). */
    const val WAVEFORM_SAMPLE_COUNT = 50

    /**
     * Port of iOS `AudioAttachment.generateWaveform(from:)`
     * (`AudioRecorderManager.swift:256-277`) **minus its first rung**.
     *
     * iOS tries three things in order: decode the file to PCM and take per-window peaks
     * (`generateWaveformFromAudioFile`), else analyse the raw container bytes
     * (`generateWaveformFromRawData`), else — when that comes out flat — a deterministic
     * pseudo-random wave seeded by the data (`generatePseudoRandomWaveform`).
     *
     * Only the last two are ported. The PCM rung would need a `MediaCodec` decode of the
     * whole clip per bubble; iOS gets it from a single `AVAudioFile` read and still falls
     * through to exactly these two whenever the decode fails or the result is flat. The
     * bars are decoration (`accessibilityHidden` on iOS, `AudioMessageView.swift:90`), the
     * duration is the load-bearing number, and the duration comes from the container
     * metadata, not from here.
     */
    fun generateWaveform(data: ByteArray, sampleCount: Int = WAVEFORM_SAMPLE_COUNT): FloatArray {
        val raw = waveformFromRawData(data, sampleCount)
        return if (variance(raw) < 0.01f) pseudoRandomWaveform(data, sampleCount) else raw
    }

    /**
     * Verbatim port of iOS `generateWaveformFromRawData`
     * (`AudioRecorderManager.swift:375-406`): RMS + peak of each byte window, centred on
     * 128, combined and clamped into iOS's own 0.15…0.95 band.
     *
     * ⚠️ `ByteArray` is SIGNED in Kotlin and `Data`'s elements are `UInt8` in Swift, so
     * every byte is read through `and 0xFF`. Without that, half the samples come out
     * negative and the "centred" value is off by 256 — the bars would still look
     * plausible, which is why it is called out here rather than left to the reader.
     */
    fun waveformFromRawData(data: ByteArray, sampleCount: Int): FloatArray {
        if (sampleCount <= 0) return FloatArray(0)
        val out = FloatArray(sampleCount)
        val bytesPerSample = max(1, data.size / sampleCount)
        for (i in 0 until sampleCount) {
            val start = i * bytesPerSample
            val end = min(start + bytesPerSample, data.size)
            if (start < data.size && start < end) {
                var sumSquared = 0f
                var maxVal = 0f
                for (j in start until end) {
                    val centered = (data[j].toInt() and 0xFF).toFloat() - 128f
                    sumSquared += centered * centered
                    val a = abs(centered)
                    if (a > maxVal) maxVal = a
                }
                val rms = sqrt(sumSquared / (end - start).toFloat())
                val combined = (rms / 64f + maxVal / 128f) / 2f
                out[i] = min(0.95f, max(0.15f, combined * 2f))
            } else {
                out[i] = 0.3f
            }
        }
        return out
    }

    /** iOS `calculateVariance` (`AudioRecorderManager.swift:278-283`). */
    fun variance(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        var sum = 0f
        for (v in values) sum += v
        val mean = sum / values.size
        var sq = 0f
        for (v in values) sq += (v - mean) * (v - mean)
        return sq / values.size
    }

    /**
     * iOS `generatePseudoRandomWaveform` (`AudioRecorderManager.swift:285-308`).
     * Deterministic: same bytes ⇒ same bars, so a bubble does not flicker a new shape on
     * every recomposition. The LCG is 32-bit wrapping, hence [UInt].
     */
    fun pseudoRandomWaveform(data: ByteArray, sampleCount: Int): FloatArray {
        if (sampleCount <= 0) return FloatArray(0)
        var seed = 12345u
        val prefix = min(100, data.size)
        for (index in 0 until prefix) {
            seed += (data[index].toInt() and 0xFF).toUInt() * (index + 1).toUInt()
        }
        val out = FloatArray(sampleCount)
        for (i in 0 until sampleCount) {
            seed = seed * 1103515245u + 12345u
            val random = ((seed shr 16) and 0x7FFFu).toFloat() / 0x7FFF.toFloat()
            val baseWave = sin(i.toFloat() * 0.3f) * 0.3f + 0.5f
            val variation = (random - 0.5f) * 0.4f
            out[i] = max(0.15f, min(0.95f, baseWave + variation))
        }
        return out
    }

    /**
     * The legacy body convention Android wrote before the duration had a field of its
     * own: `"[AUDIO] 0:05"` (`ChatViewModel.sendVoiceMessage`,
     * `GroupChatViewModel.sendVoiceMessage`).
     *
     * The old reader (`ChatScreen.extractAudioDuration`) matched `\d+:\d+` ANYWHERE in
     * the body, so a caption reading "call me at 3:30" became a 3½-minute voice note.
     * The marker is now required.
     */
    const val LEGACY_AUDIO_MARKER = "[AUDIO]"

    /**
     * Field first, legacy body second, `null` = "the bytes are the only remaining
     * source" (an iPhone note, or an Android note recorded before the field existed and
     * whose body was already emptied).
     */
    fun resolveDurationSeconds(storedMs: Long?, legacyBody: String?): Float? {
        if (storedMs != null && storedMs > 0L) return storedMs / 1000f
        val body = legacyBody ?: return null
        if (!body.contains(LEGACY_AUDIO_MARKER)) return null
        val m = Regex("""(\d+):(\d{1,2})""").find(body) ?: return null
        val minutes = m.groupValues[1].toFloatOrNull() ?: return null
        val seconds = m.groupValues[2].toFloatOrNull() ?: return null
        val total = minutes * 60f + seconds
        return if (total > 0f) total else null
    }

    /**
     * Read the true duration out of the container, iOS's own source of truth (it reads
     * the decoded file — `AudioRecorderManager.swift:228`).
     *
     * Feeds the retriever from RAM through [ByteArrayMediaDataSource]: no temp file, so
     * this cannot leave plaintext audio in `cacheDir` the way the video-thumbnail path
     * used to. Returns null on anything unreadable rather than throwing — a bubble with
     * no duration is a cosmetic loss, a crash is not.
     */
    /**
     * Contract of `MediaDataSource.readAt`: return the number of bytes actually copied,
     * or **-1 at end of stream**. Returning 0 there makes the retriever spin forever.
     *
     * It lives here, and not on [ByteArrayMediaDataSource], so a JVM unit test can reach
     * it: that class extends an Android framework type and nothing about it can be
     * exercised off-device — but this arithmetic is the part that can be wrong.
     */
    fun readInto(data: ByteArray, position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0L || position >= data.size) return -1
        if (size <= 0) return 0
        if (offset < 0 || offset > buffer.size) return -1
        val available = (data.size - position).toInt()
        val room = buffer.size - offset
        val n = min(min(size, available), room)
        if (n <= 0) return -1
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        return n
    }

    /**
     * __VOICE_SCRUB_2026_10_26__ Where a touch at [x] on a strip [width] px wide
     * lands, as 0…1.
     *
     * Trivial arithmetic, and exactly the kind that ships wrong: an unclamped
     * value seeks past the end (`MediaPlayer.seekTo` beyond the duration throws
     * on some devices), and a zero width — a Canvas that has not been measured
     * yet — divides by zero and hands `NaN` to the player. Both are one line to
     * get right and impossible to see in a screenshot, which is why they have a
     * test.
     */
    fun seekProgress(x: Float, width: Float): Float {
        if (!width.isFinite() || width <= 0f) return 0f
        if (!x.isFinite()) return 0f
        return (x / width).coerceIn(0f, 1f)
    }

    fun probeDurationSeconds(data: ByteArray): Float? {
        if (data.isEmpty()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(ByteArrayMediaDataSource(data))
            val ms = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
            if (ms != null && ms > 0L) ms / 1000f else null
        } catch (_: Throwable) {
            null
        } finally {
            try { retriever.release() } catch (_: Throwable) { }
        }
    }
}

/**
 * A `MediaDataSource` over a byte array, so `MediaMetadataRetriever` can read media that
 * only ever exists in RAM.
 *
 * The index arithmetic lives in `VoiceNoteAudio.readInto` because `MediaDataSource` is an
 * Android framework class that cannot be loaded in a JVM unit test — the arithmetic is the
 * part that can be wrong, and there it is testable.
 */
class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        VoiceNoteAudio.readInto(data, position, buffer, offset, size)

    override fun getSize(): Long = data.size.toLong()

    override fun close() { /* nothing to release: the array is the caller's */ }
}

/** Waveform + duration for one message, computed once. */
data class VoiceNoteAnalysis(
    val waveform: FloatArray,
    val durationSeconds: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VoiceNoteAnalysis
        return durationSeconds == other.durationSeconds && waveform.contentEquals(other.waveform)
    }

    override fun hashCode(): Int = 31 * waveform.contentHashCode() + durationSeconds.hashCode()
}

/**
 * Bounded, process-wide, keyed by **message id** — never by the byte array.
 *
 * `ByteArray.hashCode()` is IDENTITY, and Room hands out a fresh array on every Flow
 * emission for inline media, so a byte-keyed cache never hits: every delivery receipt
 * would recompute the waveform of every visible voice note. That is the same defect
 * audit M-7 records for images and M-8 for video thumbnails.
 */
object VoiceNoteAnalysisCache {
    /** iOS bounds its media cache at 80 entries (`MediaAttachmentCache.swift:143`). */
    const val MAX_ENTRIES = 80

    private val entries = object : LinkedHashMap<String, VoiceNoteAnalysis>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VoiceNoteAnalysis>): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun get(messageId: String): VoiceNoteAnalysis? = entries[messageId]

    @Synchronized
    fun put(messageId: String, analysis: VoiceNoteAnalysis) {
        entries[messageId] = analysis
    }

    @Synchronized
    fun evict(messageId: String) {
        entries.remove(messageId)
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size
}
