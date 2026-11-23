package com.oshi.messenger.ui.components.attach

/**
 * __ATTACH_PANEL_2027_2026_11_16__ The pure half of the attachment panel: no Android
 * types, so every rule the panel follows is pinned by a JVM test
 * (`AttachmentPanelLogicTest`).
 */

/** One photo or video of the device gallery, as the panel lists it. */
data class RecentMediaItem(
    /** MediaStore `_ID` — the selection key, stable across pages. */
    val id: Long,
    /** `content://media/…` of the item, image or video table. */
    val uri: String,
    val isVideo: Boolean,
    val mimeType: String?,
    /** Video length in ms; 0 for a photo or when MediaStore does not know it. */
    val durationMs: Long,
    val dateAddedSec: Long,
) {
    val isGif: Boolean get() = mimeType.equals("image/gif", ignoreCase = true)
}

/** How much of the gallery the app may read. */
enum class GalleryAccess {
    /** Everything (READ_MEDIA_IMAGES/VIDEO, or READ_EXTERNAL_STORAGE before 13). */
    FULL,
    /** Android 14+ "Select photos": only what the user picked (READ_MEDIA_VISUAL_USER_SELECTED). */
    PARTIAL,
    /** Nothing yet — the panel explains and offers the system picker instead. */
    NONE,
}

object AttachmentPanelLogic {

    /** Thumbnails per MediaStore query. A screenful is ~15; two pages ahead is plenty. */
    const val PAGE_SIZE = 60

    /** Start the next page when the user is this close to the end of what is loaded. */
    const val PREFETCH_DISTANCE = 24

    /**
     * The access level from the raw grants, per platform generation.
     *
     * On 14+ a FULL grant of either visual permission wins over a partial one (the
     * system keeps USER_SELECTED granted alongside a full grant). On 13 there is no
     * partial mode. Before 13 one storage permission covers both kinds.
     */
    fun access(
        sdk: Int,
        readImages: Boolean,
        readVideo: Boolean,
        userSelected: Boolean,
        legacyRead: Boolean,
    ): GalleryAccess = when {
        sdk >= 33 && (readImages || readVideo) -> GalleryAccess.FULL
        sdk >= 34 && userSelected -> GalleryAccess.PARTIAL
        sdk >= 33 -> GalleryAccess.NONE
        legacyRead -> GalleryAccess.FULL
        else -> GalleryAccess.NONE
    }

    /** The permissions to ask for, as manifest names, on [sdk]. */
    fun permissionsToRequest(sdk: Int): List<String> = when {
        sdk >= 34 -> listOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        )
        sdk >= 33 -> listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO")
        else -> listOf("android.permission.READ_EXTERNAL_STORAGE")
    }

    /**
     * After a refusal, whether the next tap must open the app's settings instead of
     * asking again: the system shows nothing once the user has said no twice (or
     * "don't ask again"), and a button that silently does nothing is the worst state.
     */
    fun mustOpenSettings(askedBefore: Boolean, access: GalleryAccess, shouldShowRationale: Boolean): Boolean =
        askedBefore && access == GalleryAccess.NONE && !shouldShowRationale

    /**
     * Toggle [id] in an ORDERED selection. The order is the send order and the number
     * on each badge, so removing #2 of 4 renumbers the last two — exactly what a
     * numbered badge promises. A pick past [max] is refused (the caller says why).
     */
    fun toggle(selection: List<Long>, id: Long, max: Int): ToggleResult = when {
        id in selection -> ToggleResult(selection - id, ToggleOutcome.REMOVED)
        selection.size >= max -> ToggleResult(selection, ToggleOutcome.LIMIT)
        else -> ToggleResult(selection + id, ToggleOutcome.ADDED)
    }

    /** 1-based badge number of [id], or null when it is not selected. */
    fun badgeNumber(selection: List<Long>, id: Long): Int? =
        selection.indexOf(id).takeIf { it >= 0 }?.plus(1)

    /** Keep only ids still present (an item deleted from the gallery while the panel was open). */
    fun prune(selection: List<Long>, present: Set<Long>): List<Long> = selection.filter { it in present }

    /** `0:07`, `3:45`, `1:02:03` — the duration chip on a video tile. */
    fun formatDuration(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) + 500L) / 1000L
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Whether to fetch the next page, given the last visible index and what is loaded. */
    fun shouldLoadMore(lastVisibleIndex: Int, loaded: Int, endReached: Boolean, loading: Boolean): Boolean =
        !endReached && !loading && loaded > 0 && lastVisibleIndex >= loaded - PREFETCH_DISTANCE

    /**
     * The caption for each of N outgoing items. The panel has ONE caption field for the
     * whole pick; it rides the FIRST item (an album caption, as every messenger shows
     * it) and the rest go bare — repeating it under all N would be noise. Blank ⇒ none.
     */
    fun captions(count: Int, typed: String): List<String?> {
        val c = typed.trim().ifEmpty { null }
        return List(count) { i -> if (i == 0) c else null }
    }

    /**
     * The sheet's resting points, in px of downward offset from fully expanded.
     * [collapsedFraction] of the available height shows when it opens.
     */
    fun anchors(fullHeightPx: Float, collapsedFraction: Float = 0.58f): SheetAnchors {
        val collapsedVisible = fullHeightPx * collapsedFraction.coerceIn(0.3f, 1f)
        return SheetAnchors(expanded = 0f, collapsed = fullHeightPx - collapsedVisible, hidden = fullHeightPx)
    }

    /**
     * Where a released drag settles. A flick (|v| above [flingThreshold] px/s) moves one
     * step in its direction; a slow release snaps to the nearest anchor. Dragging well
     * below the collapsed point dismisses.
     */
    fun settle(offset: Float, velocity: Float, a: SheetAnchors, flingThreshold: Float = 1200f): SheetTarget {
        if (velocity > flingThreshold) {
            return if (offset < a.collapsed - 1f) SheetTarget.COLLAPSED else SheetTarget.HIDDEN
        }
        if (velocity < -flingThreshold) {
            return if (offset > a.collapsed + 1f) SheetTarget.COLLAPSED else SheetTarget.EXPANDED
        }
        val dismissLine = a.collapsed + (a.hidden - a.collapsed) * 0.45f
        return when {
            offset >= dismissLine -> SheetTarget.HIDDEN
            offset >= a.collapsed / 2f -> SheetTarget.COLLAPSED
            else -> SheetTarget.EXPANDED
        }
    }
}

enum class ToggleOutcome { ADDED, REMOVED, LIMIT }

data class ToggleResult(val selection: List<Long>, val outcome: ToggleOutcome)

data class SheetAnchors(val expanded: Float, val collapsed: Float, val hidden: Float) {
    fun of(t: SheetTarget): Float = when (t) {
        SheetTarget.EXPANDED -> expanded
        SheetTarget.COLLAPSED -> collapsed
        SheetTarget.HIDDEN -> hidden
    }
}

enum class SheetTarget { EXPANDED, COLLAPSED, HIDDEN }
