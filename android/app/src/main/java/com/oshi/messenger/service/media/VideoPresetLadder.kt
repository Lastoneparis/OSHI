package com.oshi.messenger.service.media

/**
 * What a chat video is encoded AS: an explicit resolution **and an explicit bitrate**.
 *
 * Deliberately free of every `android.media` type so the arithmetic — which is the part
 * that decides whether a nine-second clip ships as 2.5 MB or as 17 MB — is exercised by
 * plain JVM tests.
 *
 * ## __CHAT_VIDEO_BITRATE_2026_10_09__ Why the preset ladder is gone
 *
 * Reported on iOS: "quand j'envoie une vidéo le poids reste trop grand, 17 Mo pour 9 s".
 * The cause was structural on both platforms and it is worth stating precisely, because
 * the old code here looked right and was tested.
 *
 * This object used to hold a ladder of *presets* — `1080p/5 Mbps`, `720p/2.5 Mbps`,
 * `540p/1.2 Mbps` — and `select()` walked it best-first, taking the first rung whose
 * ESTIMATE fitted the byte budget. That is a faithful port of what iOS did with
 * `AVAssetExportSession`, and it is also the bug: the budget for a cloud send is the
 * 200 MB ceiling, so the top rung always "fitted" and every clip went out at 1080p and
 * the top rung's bitrate. The ladder was working exactly as written; what a ladder
 * cannot do is pick a bitrate for THIS clip, because a rung is a pair of constants.
 *
 * A chat video does not need the biggest encode that fits under a ceiling. It needs the
 * bitrate a phone bubble can show. So the size and the rate are now DERIVED
 * ([plan]): 720p-class long side, `pixels × 0.085 bits × fps` ≈ 2.3 Mbps at 1280×720@30,
 * with a hard floor so it can never become mush. iOS `OSHI/ChatVideoTranscoder.swift`
 * is the same arithmetic, constant for constant.
 *
 * The budget has NOT stopped mattering — it is what makes a clip crossing a 10 MB mesh
 * link step down to 960 or 640 ([LONG_SIDE_LADDER]). It simply no longer chooses the
 * quality of a cloud send.
 *
 * ## Never truncate
 *
 * When nothing on the ladder fits the budget, [plan] returns the smallest rung anyway
 * and the file is simply larger than the budget. iOS deliberately does not use
 * `fileLengthLimit` for the same reason: that makes the export stop writing mid-clip.
 * "A shorter video is a worse failure than a larger one — the user loses content and
 * nothing says so." The caller owns the refusal decision.
 */
object VideoPresetLadder {

    // ── The encode target ─────────────────────────────────────────────────────

    /**
     * Longest output side in pixels, best first. 1280 is 720p class: what a phone
     * screen actually shows, and the point where H.264 stops paying for detail nobody
     * can see in a chat bubble. The lower rungs exist for the mesh budget.
     *
     * iOS `ChatVideoTranscoder.longSideLadder`.
     */
    val LONG_SIDE_LADDER: List<Int> = listOf(1280, 960, 640)

    /** The rung every cloud send lands on, and the resolution gate in [needsTranscode]. */
    const val CHAT_LONG_SIDE = 1280

    /**
     * Bits per pixel per frame. 1280×720 at 30 fps lands at ~2.35 Mbps, which is the
     * band WhatsApp and Signal send in. iOS `ChatVideoTranscoder.bitsPerPixelPerFrame`.
     */
    const val BITS_PER_PIXEL_PER_FRAME = 0.085

    /**
     * Never go below this, whatever the budget says: under ~600 kbps at 720p H.264
     * becomes visibly mushy, and a video the recipient cannot make out is a worse
     * outcome than one that is a megabyte larger.
     */
    const val MIN_VIDEO_BITRATE = 600_000

    /**
     * What a chat video's audio costs. iOS re-encodes to 96 kbps AAC; Android passes an
     * already-AAC track through untouched (re-encoding only loses quality), so this is
     * the figure used for *estimating* a re-encoded or unknown track.
     */
    const val CHAT_AUDIO_BITRATE = 96_000

    /** Typical AAC stereo rate for a passthrough track — what the audio actually costs. */
    const val DEFAULT_AUDIO_BITRATE = 128_000

    /** Container/muxer overhead allowance, as a fraction of the payload. */
    private const val CONTAINER_OVERHEAD = 1.05

    /** moov/atom header floor, so a near-zero-length clip is not estimated at 0 bytes. */
    private const val CONTAINER_HEADER_BYTES = 16_384L

    /**
     * Frame-rate cap. A 60 fps source doubles the byte cost for no visible gain in a
     * chat bubble.
     */
    const val MAX_FRAME_RATE = 30

    /** Keyframe interval in seconds — one every 2 s keeps seeking usable without bloating. */
    const val I_FRAME_INTERVAL_SECONDS = 2

    // ── The plan ──────────────────────────────────────────────────────────────

    /**
     * @param width         output width, encoder-aligned
     * @param height        output height, encoder-aligned
     * @param frameRate     frames per second to encode at
     * @param videoBitrate  the rate handed to `MediaFormat.KEY_BIT_RATE` — the whole
     *                      point of this file
     * @param estimatedBytes what the encode is expected to weigh, container included
     * @param fitsBudget    false when even the smallest rung is over budget; the plan is
     *                      still returned (see "Never truncate")
     */
    data class Plan(
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val videoBitrate: Int,
        val estimatedBytes: Long,
        val fitsBudget: Boolean,
    ) {
        val label: String get() = "${width}x${height}@${videoBitrate / 1000}kbps"
    }

    /**
     * Pick the output size and bitrate for a `width × height` source of [durationMs],
     * staying inside [budgetBytes] when that is possible without going below
     * [MIN_VIDEO_BITRATE].
     *
     * Pure and side-effect free: the sizing rule is the part of this file most likely to
     * be wrong, and it is the part a test can actually pin down. iOS
     * `ChatVideoTranscoder.plan(naturalSize:seconds:sourceFrameRate:budgetBytes:)`.
     *
     * @param sourceFrameRate 0 or less means "unknown", which uses [MAX_FRAME_RATE].
     */
    fun plan(
        width: Int,
        height: Int,
        durationMs: Long,
        sourceFrameRate: Int = MAX_FRAME_RATE,
        budgetBytes: Long = Long.MAX_VALUE,
        audioBitrate: Int = DEFAULT_AUDIO_BITRATE,
        ladder: List<Int> = LONG_SIDE_LADDER,
    ): Plan {
        require(ladder.isNotEmpty()) { "long-side ladder must not be empty" }
        val fps = if (sourceFrameRate > 0) minOf(MAX_FRAME_RATE, sourceFrameRate) else MAX_FRAME_RATE
        val seconds = (if (durationMs > 0) durationMs else 0L) / 1000.0
        if (width <= 0 || height <= 0) {
            // An unreadable probe: encode something safe and small rather than guess big.
            val rate = MIN_VIDEO_BITRATE
            return Plan(640, 640, fps, rate, estimatedBytes(rate, seconds, audioBitrate), true)
        }
        val sourceLongSide = maxOf(width, height)

        var smallest: Plan? = null
        for (candidate in ladder) {
            // Never upscale: a 640-wide source stays 640 wide.
            val longSide = minOf(candidate, sourceLongSide)
            val (ow, oh) = computeTargetSize(width, height, longSide)
            val ideal = (ow.toDouble() * oh.toDouble() * BITS_PER_PIXEL_PER_FRAME * fps).toInt()
            val rate = maxOf(MIN_VIDEO_BITRATE, ideal)
            val bytes = estimatedBytes(rate, seconds, audioBitrate)
            val p = Plan(ow, oh, fps, rate, bytes, fitsBudget = true)
            if (bytes <= budgetBytes) return p     // first rung that fits wins
            smallest = p
        }
        // Nothing fit. The smallest rung, flagged — never a truncation, never a refusal.
        val last = smallest!!
        return last.copy(fitsBudget = false)
    }

    /**
     * Encoded size for a bitrate over a duration, with container overhead and a header
     * floor. iOS `ChatVideoTranscoder.estimatedBytes`.
     */
    fun estimatedBytes(
        videoBitrate: Int,
        seconds: Double,
        audioBitrate: Int = DEFAULT_AUDIO_BITRATE,
    ): Long {
        val bits = (videoBitrate.toDouble() + audioBitrate.toDouble()) * maxOf(seconds, 0.0)
        return (bits / 8.0 * CONTAINER_OVERHEAD).toLong() + CONTAINER_HEADER_BYTES
    }

    /**
     * Byte budget above which a clip is worth a transcode even when it is nowhere near
     * the hard ceiling. Anchored to iOS `V2.streamingSendThreshold`
     * (`V2ClientModels.swift:262`) rather than invented: 16 MiB is where iOS itself
     * stops treating a media send as small.
     */
    const val SOFT_BUDGET_BYTES = 16L * 1024 * 1024

    /**
     * How far above [plan]'s own estimate a source may sit before it is re-encoded for
     * bitrate alone. 2× is deliberately slack: the estimate is an estimate, and a clip
     * already in the right band must not pay a generation of quality for nothing.
     */
    const val BITRATE_OVERSHOOT_TOLERANCE = 2.0

    // ── The cross-platform contract ───────────────────────────────────────────

    /**
     * __MEDIA_INTEROP_2026_09_18__ What every video that leaves this app must be.
     *
     * H.264 video + AAC audio in MP4 is the intersection an iPhone, a Mac and an
     * Android phone all decode without asking anyone's permission. iOS guarantees it by
     * construction — an iPhone CANNOT send anything else.
     *
     * Android could, and did. [needsTranscode] asked only about resolution and
     * size, so a clip that was small and ≤1080p went out byte-for-byte as it sat
     * in the gallery. The gallery is not a camera roll of one format: it holds
     * WebM/VP9 and Opus from downloads, AV1 from newer sources, HEVC from the
     * phone's own camera on most recent models, and Matroska from anywhere. An
     * iPhone opens none of VP9, AV1 or Opus. The send SUCCEEDED and the bubble
     * arrived; it simply would not play, which is the worst shape a bug can take
     * because neither end sees an error.
     */
    const val INTEROP_VIDEO_MIME = "video/avc"          // H.264
    const val INTEROP_AUDIO_MIME = "audio/mp4a-latm"    // AAC

    /**
     * True when a clip is ALREADY the shape both platforms read, so re-encoding it
     * would only cost a generation of quality.
     *
     * A null mime is "unknown", and unknown is not a promise: it transcodes. A
     * clip with no audio track at all is fine — silence is portable.
     */
    fun isInteroperable(videoMime: String?, audioMime: String?): Boolean {
        if (!videoMime.equals(INTEROP_VIDEO_MIME, ignoreCase = true)) return false
        if (audioMime == null) return true              // no audio track
        return audioMime.equals(INTEROP_AUDIO_MIME, ignoreCase = true)
    }

    /**
     * Should this clip be re-encoded at all?
     *
     * 🔧 M-9. `MediaCompressionManager.compressVideo` used to transcode ONLY when the
     * source already exceeded the delivery ceiling (200 MB), so a 150 MB 4K clip was
     * sent at full size: minutes of upload and very likely a failure.
     *
     * The order is: codec first (no size excuses an unplayable file), then the hard
     * ceiling, then the soft byte budget, then — __CHAT_VIDEO_BITRATE_2026_10_09__ —
     * the clip's own BITRATE, then its resolution.
     *
     * The bitrate rung is the one the 17 MB / 9 s report needed. Nine seconds of
     * camera 1080p is ~17 MB, which is under the 16 MiB soft budget often enough to
     * matter and is nowhere near the 200 MB ceiling; the resolution rule happens to
     * catch 1080p, but a 720p clip at 10 Mbps is heavy, short, and small, and every
     * other rule here would have waved it through.
     *
     * The resolution gate is [CHAT_LONG_SIDE] — the resolution the encode actually
     * targets. It used to be 1920, i.e. "anything 1080p or under is fine", which is
     * exactly the band the complaint came from.
     */
    fun needsTranscode(
        width: Int,
        height: Int,
        sizeBytes: Long,
        hardCeilingBytes: Long,
        durationMs: Long = 0L,
        softBudgetBytes: Long = SOFT_BUDGET_BYTES,
        maxDimension: Int = CHAT_LONG_SIDE,
        videoMime: String? = INTEROP_VIDEO_MIME,
        audioMime: String? = null,
    ): Boolean {
        if (!isInteroperable(videoMime, audioMime)) return true
        if (sizeBytes > hardCeilingBytes) return true
        if (sizeBytes > softBudgetBytes) return true
        // A probe that could not read the dimensions must not be read as "already small".
        if (width <= 0 || height <= 0) return false
        if (maxOf(width, height) > maxDimension) return true
        // Same resolution band, far more bits than a bubble needs.
        if (durationMs > 0L && sizeBytes > 0L) {
            val target = plan(width, height, durationMs).estimatedBytes
            if (target > 0L && sizeBytes > (target * BITRATE_OVERSHOOT_TOLERANCE)) return true
        }
        return false
    }

    /**
     * Scale (w × h) down so the longest side is at most [maxDimension], preserving
     * aspect ratio, and round both sides to a multiple of 16.
     *
     * Aspect ratio is preserved rather than forced to a nominal frame, so the
     * decoder→encoder surface never stretches the picture (the Android equivalent of
     * AVFoundation's `renderSize` handling). The multiple-of-16 rounding is a
     * hardware-encoder requirement on many chipsets: an odd or non-aligned width is a
     * classic source of green edges and `configure()` failures. It is stricter than
     * iOS's even-dimensions rule (`ChatVideoTranscoder.evenDimensions`) on purpose —
     * AVFoundation has no such constraint, MediaCodec does.
     *
     * A source already at or below the cap is returned unchanged apart from alignment —
     * never UPscaled, which would only add bytes.
     */
    fun computeTargetSize(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val longest = maxOf(width, height)
        val scale = if (longest > maxDimension) maxDimension.toDouble() / longest else 1.0
        val w = align16((width * scale).toInt())
        val h = align16((height * scale).toInt())
        return w to h
    }

    private fun align16(v: Int): Int {
        val a = ((v + 8) / 16) * 16
        return if (a < 16) 16 else a
    }
}
