package com.oshi.messenger.ui.components

/**
 * One picked attachment, on its way to the composer.
 *
 * 🖼️ M-14. `viewOnce` is PER ITEM, matching iOS's `MediaManager.MediaAttachment`
 * (`MediaPickerView.swift:383-404` toggles it on one thumbnail at a time). It is
 * seeded from the composer's global toggle so a single pick behaves exactly as it
 * did, and can then be overridden on any one item in the strip.
 */
data class PendingMedia(
    val bytes: ByteArray,
    val isVideo: Boolean,
    val viewOnce: Boolean
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is PendingMedia &&
                isVideo == other.isVideo &&
                viewOnce == other.viewOnce &&
                bytes.contentEquals(other.bytes)
            )

    override fun hashCode(): Int =
        31 * (31 * bytes.contentHashCode() + isVideo.hashCode()) + viewOnce.hashCode()
}

/** One outbound message the composer will send. */
data class MediaSendPlan(
    val bytes: ByteArray,
    val isVideo: Boolean,
    val viewOnce: Boolean,
    /** `null` = send with no body at all. */
    val caption: String?
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is MediaSendPlan &&
                isVideo == other.isVideo &&
                viewOnce == other.viewOnce &&
                caption == other.caption &&
                bytes.contentEquals(other.bytes)
            )

    override fun hashCode(): Int {
        var r = bytes.contentHashCode()
        r = 31 * r + isVideo.hashCode()
        r = 31 * r + viewOnce.hashCode()
        r = 31 * r + (caption?.hashCode() ?: 0)
        return r
    }
}

/**
 * The multi-select composer's pure decisions, lifted out of `ChatScreen` so they can
 * be asserted without a device.
 *
 * Everything here is a pure function of its arguments. The list operations return a
 * NEW list rather than mutating: the caller holds them in Compose state, where an
 * in-place mutation is invisible to recomposition.
 */
object ChatMediaSelection {

    /**
     * Ceiling on one pick. iOS's picker is bounded by the `PHPickerConfiguration`
     * `selectionLimit` it sets; here the cost is that every selected item is decoded
     * and held in RAM before the dialog can draw it, so the bound is a memory bound.
     */
    const val MAX_SELECTION = 10

    /**
     * Turn the picked items into one outbound message each.
     *
     * The caption rule is iOS's, and it is the reason this is a function rather than
     * a `forEach` in the dialog: `sendMultipleMediaMessages` sends every attachment
     * with `content: ""` (`ChatView.swift:2343`) and only the SINGLE-attachment path
     * carries the typed caption (`:2272`). Applying a typed caption to all N would
     * repeat it under every photo; dropping it silently on a single send would lose
     * it. So: a caption exists only when there is exactly one item — and the dialog
     * hides the field otherwise, so nothing is ever typed and then discarded.
     *
     * A blank caption is `null`, never `""`: `MessageRepository.sendMediaMessage`
     * writes `caption ?: the media-type marker`, and an empty string would put a literal
     * empty body on the row where the type marker belongs.
     */
    fun plan(items: List<PendingMedia>, typedCaption: String): List<MediaSendPlan> {
        val caption = if (items.size == 1) typedCaption.ifBlank { null } else null
        return items.map { MediaSendPlan(it.bytes, it.isVideo, it.viewOnce, caption) }
    }

    /** Flip view-once on ONE item. Out-of-range is a no-op, never a crash. */
    fun toggleViewOnce(items: List<PendingMedia>, index: Int): List<PendingMedia> =
        items.mapIndexed { i, item ->
            if (i == index) item.copy(viewOnce = !item.viewOnce) else item
        }

    /** Drop ONE item. Out-of-range is a no-op, never a crash. */
    fun removeAt(items: List<PendingMedia>, index: Int): List<PendingMedia> =
        items.filterIndexed { i, _ -> i != index }
}

/**
 * 📎 M-17 — what a message bubble knows about its attachment's bytes.
 *
 * `MessageRepository.hydrateMedia` re-attaches file-backed blobs newest-first under
 * iOS's own cache bounds (60 items / 96 MB — `MediaAttachmentCache.swift:37-38`).
 * The bound is right; what was wrong is that on Android it was the **only** read
 * path the message list had, so the 61st-oldest photo in a thread arrived with
 * `mediaData = null`, drew an empty bubble, and stayed that way forever — no
 * spinner, no error, no retry, with the bytes sitting untouched on disk. On iOS the
 * same budget governs a cache and a miss is re-read inside the bubble's own `.task`.
 */
enum class BubbleMediaKind {
    /** Bytes in hand — inline on the row, or re-read from the store. */
    READY,

    /** The re-read is in flight. Draw a spinner, not an empty box. */
    LOADING,

    /** No bytes. Draw the retry affordance, not an empty box. */
    UNAVAILABLE;

    companion object {
        /**
         * The whole state machine, as a pure function.
         *
         * @param hasInline the row carried its own bytes (budget not exceeded).
         * @param hasLoader a re-read is even possible.
         * @param loading   a re-read is in flight.
         * @param loadedSize bytes the re-read produced; `null` = it produced nothing.
         */
        fun of(
            hasInline: Boolean,
            hasLoader: Boolean,
            loading: Boolean,
            loadedSize: Int?
        ): BubbleMediaKind = when {
            hasInline -> READY
            !hasLoader -> UNAVAILABLE
            // An empty array is NOT bytes. Treating `ByteArray(0)` as ready would
            // hand `BitmapFactory` nothing and draw the empty box this exists to
            // remove — and, worse, it would look like a success and never retry.
            loadedSize != null && loadedSize > 0 -> READY
            loading -> LOADING
            else -> UNAVAILABLE
        }
    }
}
