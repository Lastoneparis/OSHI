package com.oshi.messenger.ui.components

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The arithmetic behind the crop window: view pixels in, source pixels out.
 *
 * __IMAGE_CROP_2026_11_02__ (N13) Asked for as "choosing a profile photo or
 * sending a photo can be cropped on iOS; it would be welcome on Android".
 *
 * WHY THIS IS A SEPARATE, ANDROID-FREE OBJECT: the only way a crop goes wrong
 * is in the mapping between what the finger touched and what is cut out of the
 * bitmap — an off-by-one that takes a pixel of the neighbour's shoulder, or a
 * rounding that asks `Bitmap.createBitmap` for a rectangle outside the image
 * and throws. None of that needs a device to test, and on a device it is
 * exactly the part you cannot see. So the geometry lives here with no Android
 * or Compose imports, and [ImageCropGeometry] is covered by unit tests.
 *
 * COORDINATES. Everything is in VIEW pixels, origin top-left of the crop
 * surface, until [toSource] converts to BITMAP pixels. The image is drawn
 * letterboxed (`ContentScale.Fit`), so the view and the bitmap share no origin
 * and no scale: [fit] is the one place that relationship is computed.
 */
object ImageCropGeometry {

    /** A rectangle in view pixels. Not Compose's, so this file stays testable. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    /** A rectangle in bitmap pixels, ready for `Bitmap.createBitmap`. */
    data class Source(val x: Int, val y: Int, val width: Int, val height: Int)

    /** Which part of the window a drag is moving. */
    enum class Handle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, BODY }

    /** Shortest side a crop window may be reduced to, in view pixels. */
    const val MIN_SIDE = 56f

    /**
     * Where a bitmap of [srcW] x [srcH] lands inside a [viewW] x [viewH] surface
     * under `ContentScale.Fit`: scaled to touch two edges, centred on the other
     * axis.
     */
    fun fit(viewW: Float, viewH: Float, srcW: Int, srcH: Int): Box {
        if (viewW <= 0f || viewH <= 0f || srcW <= 0 || srcH <= 0) {
            return Box(0f, 0f, 0f, 0f)
        }
        val scale = min(viewW / srcW, viewH / srcH)
        val w = srcW * scale
        val h = srcH * scale
        val left = (viewW - w) / 2f
        val top = (viewH - h) / 2f
        return Box(left, top, left + w, top + h)
    }

    /**
     * The window the user is first shown: the whole image for a free crop, the
     * largest centred square when [aspect] is 1 (a profile photo is displayed
     * in a circle, so anything else would be cropped again behind their back).
     *
     * [aspect] is width / height, or null for free.
     */
    fun initialCrop(display: Box, aspect: Float?): Box {
        if (aspect == null || aspect <= 0f || display.width <= 0f || display.height <= 0f) return display
        val wantH = display.width / aspect
        return if (wantH <= display.height) {
            val top = display.centerY - wantH / 2f
            Box(display.left, top, display.right, top + wantH)
        } else {
            val wantW = display.height * aspect
            val left = display.centerX - wantW / 2f
            Box(left, display.top, left + wantW, display.bottom)
        }
    }

    /** Slide the whole window, never past the edges of the image. */
    fun move(box: Box, dx: Float, dy: Float, bounds: Box): Box {
        val clampedDx = dx.coerceIn(bounds.left - box.left, bounds.right - box.right)
        val clampedDy = dy.coerceIn(bounds.top - box.top, bounds.bottom - box.bottom)
        return Box(box.left + clampedDx, box.top + clampedDy,
                   box.right + clampedDx, box.bottom + clampedDy)
    }

    /**
     * Drag one corner.
     *
     * With an [aspect] the moved corner follows the finger on the axis it
     * travelled furthest and the other side is derived, so the window cannot
     * drift off-ratio by a pixel per frame. Without one the two axes are
     * independent. Either way the result stays inside [bounds] and no smaller
     * than [MIN_SIDE] — a zero-width window is the rectangle that makes
     * `createBitmap` throw.
     */
    fun resize(box: Box, handle: Handle, dx: Float, dy: Float, bounds: Box, aspect: Float?): Box {
        if (handle == Handle.BODY) return move(box, dx, dy, bounds)

        val movesLeft = handle == Handle.TOP_LEFT || handle == Handle.BOTTOM_LEFT
        val movesTop = handle == Handle.TOP_LEFT || handle == Handle.TOP_RIGHT

        // The corner that stays put. Everything is derived from it.
        val anchorX = if (movesLeft) box.right else box.left
        val anchorY = if (movesTop) box.bottom else box.top

        var newW: Float
        var newH: Float
        if (aspect != null && aspect > 0f) {
            val wantW = (box.width + if (movesLeft) -dx else dx)
            val wantH = (box.height + if (movesTop) -dy else dy)
            // Follow whichever axis the finger pushed harder, derive the other.
            newW = if (kotlin.math.abs(wantW - box.width) >= kotlin.math.abs(wantH - box.height)) {
                wantW
            } else {
                wantH * aspect
            }
            newW = newW.coerceAtLeast(MIN_SIDE)
            // Headroom from the anchor to the edges the window grows towards.
            val maxW = if (movesLeft) anchorX - bounds.left else bounds.right - anchorX
            val maxH = if (movesTop) anchorY - bounds.top else bounds.bottom - anchorY
            newW = min(newW, min(maxW, maxH * aspect))
            newH = newW / aspect
        } else {
            newW = (box.width + if (movesLeft) -dx else dx).coerceAtLeast(MIN_SIDE)
            newH = (box.height + if (movesTop) -dy else dy).coerceAtLeast(MIN_SIDE)
            newW = min(newW, if (movesLeft) anchorX - bounds.left else bounds.right - anchorX)
            newH = min(newH, if (movesTop) anchorY - bounds.top else bounds.bottom - anchorY)
        }
        // A window that cannot fit MIN_SIDE in the space available is left alone
        // rather than silently snapped somewhere the finger never was.
        if (newW < MIN_SIDE || newH < MIN_SIDE) return box

        val left = if (movesLeft) anchorX - newW else anchorX
        val top = if (movesTop) anchorY - newH else anchorY
        return Box(left, top, left + newW, top + newH)
    }

    /** Which handle a touch at ([x], [y]) grabs, [slop] view pixels being "on" a corner. */
    fun handleAt(box: Box, x: Float, y: Float, slop: Float): Handle {
        fun near(px: Float, py: Float) =
            kotlin.math.abs(x - px) <= slop && kotlin.math.abs(y - py) <= slop
        return when {
            near(box.left, box.top) -> Handle.TOP_LEFT
            near(box.right, box.top) -> Handle.TOP_RIGHT
            near(box.left, box.bottom) -> Handle.BOTTOM_LEFT
            near(box.right, box.bottom) -> Handle.BOTTOM_RIGHT
            else -> Handle.BODY
        }
    }

    /**
     * The crop window, in bitmap pixels.
     *
     * CLAMPED, ALWAYS. The window is kept inside the image by [move] and
     * [resize], but a layout change between the gesture and the tap on "Done"
     * can leave it a fraction outside, and `Bitmap.createBitmap` answers that
     * with an IllegalArgumentException rather than a smaller rectangle. A crop
     * that rounds to nothing is returned as a single pixel for the same reason.
     */
    fun toSource(crop: Box, display: Box, srcW: Int, srcH: Int): Source {
        if (display.width <= 0f || display.height <= 0f || srcW <= 0 || srcH <= 0) {
            return Source(0, 0, max(srcW, 1), max(srcH, 1))
        }
        val scaleX = srcW / display.width
        val scaleY = srcH / display.height
        val x = ((crop.left - display.left) * scaleX).roundToInt().coerceIn(0, srcW - 1)
        val y = ((crop.top - display.top) * scaleY).roundToInt().coerceIn(0, srcH - 1)
        val w = (crop.width * scaleX).roundToInt().coerceIn(1, srcW - x)
        val h = (crop.height * scaleY).roundToInt().coerceIn(1, srcH - y)
        return Source(x, y, w, h)
    }
}
