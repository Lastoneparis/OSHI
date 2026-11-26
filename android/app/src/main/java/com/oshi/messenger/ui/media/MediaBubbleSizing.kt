package com.oshi.messenger.ui.media

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * __BUBBLE_MEDIA_2026_11_21__ How big a photo / video / GIF is drawn inside a message
 * bubble (BUBBLE_MEDIA_SPEC §1). Pure: dp in, dp out, JVM-testable.
 *
 * ## The defect this replaces ("there is a part that is empty on the side")
 *
 * Reproduced on the emulator 21/11 (docs/design/bubbles-2026-11-21/before-*.png). Both
 * chat screens drew a picture as `fillMaxWidth().heightIn(max = 250.dp)` INSIDE the
 * bubble's text column, which is padded 12 dp left/right (and 8–12 dp top/bottom):
 *
 *  1. the padding is painted with the bubble colour, so every photo sat in a 12 dp
 *     band of purple / white on both sides (and above/below) — the "empty part";
 *  2. the picture never sized the bubble: `fillMaxWidth` made every photo bubble the
 *     full max width (82 % of the screen in groups), whatever the picture;
 *  3. the height was capped at a flat 250 dp, so at ~270 dp of width any picture
 *     taller than ~1.1:1 was cut: in groups (`Crop`) a 1080×1920 portrait lost ~60 %
 *     of its height and even a 1600×1000 landscape lost its sides; in 1:1 (`Fit`) the
 *     same portrait was LETTERBOXED, i.e. bubble-coloured bands left and right of it;
 *  4. in a channel (admins post), every post is the admin's own → right-aligned purple
 *     chat bubble at 82 %, leaving an empty strip on the left of every post.
 *
 * Telegram sizes the BUBBLE from the picture instead. So does this: the picture's own
 * aspect ratio decides the box, the bubble wraps the box, and nothing is left over.
 */
object MediaBubbleSizing {

    /** Below this the picture is scaled UP rather than leaving a bubble too narrow to hold a caption. */
    const val MIN_WIDTH_DP = 200f

    /** A tall picture is centre-cropped at width × this. */
    const val MAX_HEIGHT_RATIO = 1.3f

    /** A panorama is centre-cropped at width × this (no letterbox). */
    const val MIN_HEIGHT_RATIO = 0.5f

    /** Chat lines (1:1, groups): share of the chat width, and the cap on wide screens. */
    const val CHAT_WIDTH_SHARE = 0.75f
    const val CHAT_WIDTH_CAP_DP = 420f

    /** Channel posts are cards, not chat lines. */
    const val CHANNEL_WIDTH_SHARE = 0.90f
    const val CHANNEL_WIDTH_CAP_DP = 560f

    /** Used while the picture's size is not known yet (bytes still loading). */
    const val PLACEHOLDER_ASPECT = 4f / 3f

    data class Size(
        val widthDp: Float,
        val heightDp: Float,
        /** True when the box is not the picture's own aspect, i.e. part of it is cropped away. */
        val cropped: Boolean
    )

    /** The widest a bubble may get in a chat [chatWidthDp] wide. */
    fun maxBubbleWidth(chatWidthDp: Float, channel: Boolean): Float =
        if (channel) min(chatWidthDp * CHANNEL_WIDTH_SHARE, CHANNEL_WIDTH_CAP_DP)
        else min(chatWidthDp * CHAT_WIDTH_SHARE, CHAT_WIDTH_CAP_DP)

    /**
     * @param naturalWidthDp the picture's own width on this screen (pixels / density);
     *   ≤ 0 when unknown.
     * @param naturalHeightDp same, height.
     * @param maxWidthDp [maxBubbleWidth] for this chat.
     */
    fun displaySize(naturalWidthDp: Float, naturalHeightDp: Float, maxWidthDp: Float): Size {
        val maxW = max(1f, maxWidthDp)
        val minW = min(MIN_WIDTH_DP, maxW)
        val known = naturalWidthDp > 0f && naturalHeightDp > 0f
        val aspect = if (known) naturalWidthDp / naturalHeightDp else PLACEHOLDER_ASPECT
        val width = if (known) naturalWidthDp.coerceIn(minW, maxW) else maxW
        val ideal = width / aspect
        val height = ideal.coerceIn(width * MIN_HEIGHT_RATIO, width * MAX_HEIGHT_RATIO)
        return Size(
            widthDp = round1(width),
            heightDp = round1(height),
            cropped = known && kotlin.math.abs(height - ideal) > 0.5f
        )
    }

    private fun round1(v: Float): Float = (v * 10f).roundToInt() / 10f
}
