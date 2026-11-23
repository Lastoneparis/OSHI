package com.oshi.messenger.ui.components

/**
 * __PHOTO_MARKUP_2026_11_08__
 *
 * Where a finger on the screen lands in the IMAGE.
 *
 * Kept pure and separate from the drawing because this is the part that is
 * actually hard and the part that fails silently: the photo is shown scaled and
 * letterboxed to fit the dialog, so a stroke recorded in view coordinates is
 * drawn somewhere else entirely once it is flattened into the full-resolution
 * bitmap. A circle round someone's face becomes a circle round their shoulder,
 * and only on photos whose aspect ratio differs from the box — which is exactly
 * the kind of bug that looks fine in a quick test on one picture.
 *
 * So strokes are stored in image space from the first touch, and the only thing
 * that has to be right is this mapping.
 */
object MarkupGeometry {

    /** Where the image actually sits inside [viewW] x [viewH] under a fit-scale. */
    data class Fitted(
        val offsetX: Float,
        val offsetY: Float,
        val scale: Float,
    )

    /**
     * Fit [imageW] x [imageH] inside [viewW] x [viewH], centred, preserving the
     * aspect ratio — the same rule `ContentScale.Fit` uses, restated here so the
     * maths can be tested without a composition.
     */
    fun fit(imageW: Int, imageH: Int, viewW: Float, viewH: Float): Fitted {
        if (imageW <= 0 || imageH <= 0 || viewW <= 0f || viewH <= 0f) {
            return Fitted(0f, 0f, 1f)
        }
        val scale = minOf(viewW / imageW, viewH / imageH)
        val drawnW = imageW * scale
        val drawnH = imageH * scale
        return Fitted(
            offsetX = (viewW - drawnW) / 2f,
            offsetY = (viewH - drawnH) / 2f,
            scale = scale,
        )
    }

    /**
     * A view point to an image point. Returns null when the touch is in the
     * letterbox rather than on the photo — a stroke that starts outside the
     * image has no place to be flattened to, and clamping it would draw a mark
     * the user never made along the edge.
     */
    fun toImage(
        x: Float,
        y: Float,
        imageW: Int,
        imageH: Int,
        viewW: Float,
        viewH: Float,
    ): Pair<Float, Float>? {
        val f = fit(imageW, imageH, viewW, viewH)
        if (f.scale <= 0f) return null
        val ix = (x - f.offsetX) / f.scale
        val iy = (y - f.offsetY) / f.scale
        if (ix < 0f || iy < 0f || ix > imageW || iy > imageH) return null
        return ix to iy
    }

    /**
     * Stroke width in image pixels for a width chosen on screen.
     *
     * Without this a 6 dp pen on a 4000 px photo flattens to a hairline that is
     * invisible at full size — the mark survives the round trip but says nothing.
     */
    fun strokeWidthInImage(viewWidth: Float, imageW: Int, imageH: Int, viewW: Float, viewH: Float): Float {
        val f = fit(imageW, imageH, viewW, viewH)
        return if (f.scale > 0f) viewWidth / f.scale else viewWidth
    }
}
