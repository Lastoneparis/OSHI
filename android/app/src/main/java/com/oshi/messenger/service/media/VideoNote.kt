package com.oshi.messenger.service.media

import org.json.JSONObject

/**
 * __VIDEO_NOTE_2026_09_24__ Round video messages ("video notes").
 *
 * The wire contract is `docs/VIDEO_NOTE_SPEC.md`, shared with iOS and Desktop. In one line:
 * a video note IS an ordinary video attachment (`mediaType:"video"`, `mime:"video/mp4"`)
 * plus two OPTIONAL keys — `"videoNote": true` and `"durationMs": <int>` — so a client that
 * does not know them renders an ordinary video, and nothing else on the wire changes.
 *
 * Everything that must agree across platforms lives here, as pure functions, so the JVM
 * tests can pin it: the key names, the emit rule (`true` or ABSENT, never `false`), the
 * tolerant duration read (§1 rule 2) and the 15 s cap the recorder and the sender enforce.
 */
object VideoNote {

    /** §1 — the flag. Emitted only as `true`; a note-less message omits the key. */
    const val KEY_FLAG = "videoNote"

    /** §1 — recorded duration in milliseconds, emitted when known. */
    const val KEY_DURATION = "durationMs"

    /** §3 — the recorder auto-stops here. */
    const val MAX_DURATION_MS = 15_000L

    /** §3 — the sender refuses anything longer than this AFTER encode (container rounding). */
    const val MAX_ENCODED_DURATION_MS = 15_500L

    /** A tap shorter than this is not a recording: the recorder discards it. */
    const val MIN_DURATION_MS = 700L

    /** §3 — square side of the encoded video. */
    const val SIDE_PX = 480

    /** §3 — ≈1.2 Mbit/s video + 64 kbit/s audio ≈ 2.4 MB for 15 s. */
    const val VIDEO_BITRATE = 1_200_000
    const val AUDIO_BITRATE = 64_000
    const val FRAME_RATE = 30

    /** §3 — the informative filename every platform uses. */
    fun fileName(nowMs: Long = System.currentTimeMillis()): String = "videonote_$nowMs.mp4"

    // ── Recording cap ──────────────────────────────────────────────────────────

    /** Fraction [0,1] of the 15 s budget used — drives the progress ring. */
    fun progress(elapsedMs: Long): Float =
        (elapsedMs.coerceAtLeast(0L).toFloat() / MAX_DURATION_MS).coerceIn(0f, 1f)

    /** True once the recorder must stop on its own (hard stop at exactly 15.0 s). */
    fun mustAutoStop(elapsedMs: Long): Boolean = elapsedMs >= MAX_DURATION_MS

    /** Milliseconds still available, never negative. */
    fun remainingMs(elapsedMs: Long): Long = (MAX_DURATION_MS - elapsedMs).coerceAtLeast(0L)

    /** Whether a finished recording is long enough to be worth offering for sending. */
    fun isKeepable(elapsedMs: Long): Boolean = elapsedMs >= MIN_DURATION_MS

    /** Whether an ENCODED clip may be sent as a note (§3: refuse > 15.5 s). */
    fun isSendableDuration(durationMs: Long): Boolean =
        durationMs in 1..MAX_ENCODED_DURATION_MS

    /** The duration the wire carries for a clip: clamped to the 15 s contract. */
    fun wireDuration(durationMs: Long): Int? =
        if (durationMs <= 0L) null else durationMs.coerceAtMost(MAX_DURATION_MS).toInt()

    /** `0:12` — the capsule label under the round bubble and in the recorder. */
    fun formatClock(ms: Long): String {
        val totalSeconds = ((ms.coerceAtLeast(0L) + 500L) / 1000L)
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    // ── Receiver rules (§1) ────────────────────────────────────────────────────

    /**
     * §1 rule 1: a note only when the flag is `true` AND the media is a video. A
     * `videoNote:true` on a photo, an audio clip or a document is ignored.
     */
    fun isVideoNote(flag: Boolean?, mediaType: String?, mime: String?): Boolean {
        if (flag != true) return false
        val t = mediaType?.trim()?.lowercase()
        if (t == "video") return true
        if (t != null && t.isNotEmpty()) return false
        return mime?.trim()?.lowercase()?.startsWith("video/") == true
    }

    /**
     * §1 rule 2: integer OR floating (rounded), clamped to [0, 15000]. Missing, negative,
     * non-numeric (including a numeric STRING) → null = unknown. Never throws.
     */
    fun durationFrom(raw: Any?): Int? {
        val n = when (raw) {
            null, JSONObject.NULL -> return null
            is Int -> raw.toDouble()
            is Long -> raw.toDouble()
            is Number -> raw.toDouble()
            else -> return null
        }
        if (n.isNaN() || n.isInfinite() || n < 0.0) return null
        return Math.round(n).coerceAtMost(MAX_DURATION_MS).toInt()
    }

    /** Read the flag off any JSON object: only a real boolean `true` counts. */
    fun flagFrom(o: JSONObject?): Boolean? {
        if (o == null || !o.has(KEY_FLAG) || o.isNull(KEY_FLAG)) return null
        return when (val v = o.opt(KEY_FLAG)) {
            is Boolean -> v
            else -> null
        }
    }

    fun durationFrom(o: JSONObject?): Int? =
        if (o == null || !o.has(KEY_DURATION)) null else durationFrom(o.opt(KEY_DURATION))

    /** Add the two keys to a JSON object following the emit rule (true-or-absent). */
    fun putInto(o: JSONObject, isNote: Boolean, durationMs: Int?) {
        if (!isNote) return
        o.put(KEY_FLAG, true)
        durationMs?.takeIf { it > 0 }?.let { o.put(KEY_DURATION, it.coerceAtMost(MAX_DURATION_MS.toInt())) }
    }

    /**
     * The same keys as a raw JSON fragment (`,"videoNote":true,"durationMs":N`) for the
     * hand-built envelopes; the empty string for a normal media message so its bytes are
     * identical to before.
     */
    fun jsonFragment(isNote: Boolean, durationMs: Int?): String {
        if (!isNote) return ""
        val d = durationMs?.takeIf { it > 0 }?.coerceAtMost(MAX_DURATION_MS.toInt())
        return if (d != null) ",\"$KEY_FLAG\":true,\"$KEY_DURATION\":$d" else ",\"$KEY_FLAG\":true"
    }

    /**
     * §1 rule 5 (group): the flag is on if EITHER location says so; the duration comes
     * from the first location that has one, file-key JSON first.
     */
    fun merge(
        fileKeyFlag: Boolean?, fileKeyDurationMs: Int?,
        innerFlag: Boolean?, innerDurationMs: Int?,
    ): Pair<Boolean, Int?> =
        ((fileKeyFlag == true) || (innerFlag == true)) to (fileKeyDurationMs ?: innerDurationMs)
}

/**
 * The composer's shortcut rule (owner request, 2026-09-24): while the user is typing, the
 * camera/video shortcuts step aside so the text field can take the row; they come back
 * as soon as the field is cleared. Pure so the rule is pinned by a JVM test.
 */
object ComposerShortcuts {
    /** Camera/video shortcuts are shown only on an empty composer that is not editing. */
    fun visible(text: String, isEditing: Boolean = false): Boolean = text.isEmpty() && !isEditing

    /** The field grows up to this many lines once the shortcuts have stepped aside. */
    fun maxLines(text: String, isEditing: Boolean = false): Int =
        if (visible(text, isEditing)) 4 else 6
}
