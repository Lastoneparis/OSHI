package com.oshi.messenger

import com.oshi.messenger.ui.components.MarkupGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __PHOTO_MARKUP_2026_11_08__
 *
 * The coordinate mapping behind drawing on a photo. This is tested and the
 * drawing is not, because this is where the silent failure lives: a stroke
 * recorded in view coordinates lands somewhere else once flattened into the
 * full-size bitmap, and only on photos whose aspect ratio differs from the box.
 */
class MarkupGeometryTest {

    /** A wide photo in a square box: bars top and bottom, not left and right. */
    @Test
    fun `a wide photo letterboxes vertically`() {
        val f = MarkupGeometry.fit(imageW = 2000, imageH = 1000, viewW = 1000f, viewH = 1000f)
        assertEquals(0.5f, f.scale, 1e-4f)
        assertEquals("no horizontal bar expected", 0f, f.offsetX, 1e-4f)
        assertEquals("expected 250 px bars", 250f, f.offsetY, 1e-4f)
    }

    @Test
    fun `a tall photo letterboxes horizontally`() {
        val f = MarkupGeometry.fit(imageW = 1000, imageH = 2000, viewW = 1000f, viewH = 1000f)
        assertEquals(0.5f, f.scale, 1e-4f)
        assertEquals(250f, f.offsetX, 1e-4f)
        assertEquals(0f, f.offsetY, 1e-4f)
    }

    /**
     * THE test. Touch the centre of the view on a letterboxed photo and the
     * mark must land on the centre of the PHOTO. Skip the offset and it lands
     * a quarter of the way up the image.
     */
    @Test
    fun `the centre of the view is the centre of the photo`() {
        val p = MarkupGeometry.toImage(500f, 500f, 2000, 1000, 1000f, 1000f)
        assertNotNull(p)
        assertEquals(1000f, p!!.first, 1e-3f)
        assertEquals(500f, p.second, 1e-3f)
    }

    /** The top-left of the drawn photo, not of the view. */
    @Test
    fun `the top-left corner of the photo maps to the image origin`() {
        val p = MarkupGeometry.toImage(0f, 250f, 2000, 1000, 1000f, 1000f)
        assertNotNull(p)
        assertEquals(0f, p!!.first, 1e-3f)
        assertEquals(0f, p.second, 1e-3f)
    }

    /**
     * A touch in the letterbox belongs to no pixel. Clamping it would draw a
     * mark along the edge that the user never made.
     */
    @Test
    fun `a touch in the letterbox is refused`() {
        assertNull("above the photo", MarkupGeometry.toImage(500f, 10f, 2000, 1000, 1000f, 1000f))
        assertNull("below the photo", MarkupGeometry.toImage(500f, 990f, 2000, 1000, 1000f, 1000f))
    }

    /** Round trip: a point on the photo maps back to where it was drawn. */
    @Test
    fun `mapping is consistent across aspect ratios`() {
        for ((iw, ih) in listOf(4000 to 3000, 1080 to 1920, 512 to 512, 3000 to 1000)) {
            val f = MarkupGeometry.fit(iw, ih, 900f, 1200f)
            // take the image centre, project it forward, map it back
            val viewX = f.offsetX + (iw / 2f) * f.scale
            val viewY = f.offsetY + (ih / 2f) * f.scale
            val back = MarkupGeometry.toImage(viewX, viewY, iw, ih, 900f, 1200f)
            assertNotNull("centre of ${iw}x$ih should be on the photo", back)
            assertEquals(iw / 2f, back!!.first, 0.5f)
            assertEquals(ih / 2f, back.second, 0.5f)
        }
    }

    /**
     * A pen chosen on screen must stay visible at full resolution. A 6 px
     * stroke on a photo shown at quarter size has to flatten to 24 px, or the
     * circle the user drew becomes a hairline nobody can see.
     */
    @Test
    fun `stroke width scales up with the image`() {
        val w = MarkupGeometry.strokeWidthInImage(6f, 4000, 3000, 1000f, 750f)
        assertEquals("quarter-size view means a 4x pen", 24f, w, 0.1f)
        assertTrue(w > 6f)
    }

    /** Degenerate inputs must not divide by zero. */
    @Test
    fun `a zero-sized image or view is survivable`() {
        assertEquals(1f, MarkupGeometry.fit(0, 0, 100f, 100f).scale, 1e-4f)
        assertEquals(1f, MarkupGeometry.fit(100, 100, 0f, 0f).scale, 1e-4f)
        assertNull(MarkupGeometry.toImage(5f, 5f, 0, 0, 100f, 100f))
    }
}
