package com.oshi.messenger

import com.oshi.messenger.ui.components.ImageCropGeometry
import com.oshi.messenger.ui.components.ImageCropGeometry.Box
import com.oshi.messenger.ui.components.ImageCropGeometry.Handle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __IMAGE_CROP_2026_11_02__ (N13) The crop window's arithmetic.
 *
 * Everything here is about the two ways a crop goes wrong where no one can see
 * it: the window maps to the wrong pixels, or it maps to a rectangle
 * `Bitmap.createBitmap` refuses (outside the image, or zero-sized — it throws
 * IllegalArgumentException for both, which on a phone is a crash while sending
 * a photo).
 */
class ImageCropGeometryTest {

    private val eps = 0.01f

    @Test
    fun `fit letterboxes a wide image in a square view`() {
        val d = ImageCropGeometry.fit(1000f, 1000f, 2000, 1000)
        assertEquals(0f, d.left, eps)
        assertEquals(1000f, d.right, eps)
        // Half the height, centred: 250 above and below.
        assertEquals(250f, d.top, eps)
        assertEquals(750f, d.bottom, eps)
    }

    @Test
    fun `fit pillarboxes a tall image and keeps the aspect`() {
        val d = ImageCropGeometry.fit(1000f, 1000f, 500, 1000)
        assertEquals(250f, d.left, eps)
        assertEquals(750f, d.right, eps)
        assertEquals(0f, d.top, eps)
        assertEquals(1000f, d.bottom, eps)
        assertEquals(500f / 1000f, d.width / d.height, eps)
    }

    @Test
    fun `the whole window maps to the whole bitmap`() {
        val d = ImageCropGeometry.fit(800f, 600f, 1600, 1200)
        val s = ImageCropGeometry.toSource(d, d, 1600, 1200)
        assertEquals(0, s.x); assertEquals(0, s.y)
        assertEquals(1600, s.width); assertEquals(1200, s.height)
    }

    @Test
    fun `a centred half window maps to the centred half of the bitmap`() {
        val d = ImageCropGeometry.fit(800f, 800f, 1600, 1600)   // scale 0.5
        val half = Box(d.left + d.width / 4f, d.top + d.height / 4f,
                       d.right - d.width / 4f, d.bottom - d.height / 4f)
        val s = ImageCropGeometry.toSource(half, d, 1600, 1600)
        assertEquals(400, s.x); assertEquals(400, s.y)
        assertEquals(800, s.width); assertEquals(800, s.height)
    }

    @Test
    fun `a window dragged outside the image is clamped, never negative`() {
        val d = ImageCropGeometry.fit(800f, 800f, 1600, 1600)
        val outside = Box(d.left - 500f, d.top - 500f, d.right + 500f, d.bottom + 500f)
        val s = ImageCropGeometry.toSource(outside, d, 1600, 1600)
        assertEquals(0, s.x); assertEquals(0, s.y)
        assertTrue("x+w must stay inside the bitmap", s.x + s.width <= 1600)
        assertTrue("y+h must stay inside the bitmap", s.y + s.height <= 1600)
    }

    @Test
    fun `a window too small to round to a pixel still yields one pixel`() {
        val d = ImageCropGeometry.fit(1000f, 1000f, 100, 100)   // 10 view px per source px
        val sliver = Box(d.left, d.top, d.left + 0.2f, d.top + 0.2f)
        val s = ImageCropGeometry.toSource(sliver, d, 100, 100)
        assertEquals(1, s.width); assertEquals(1, s.height)
    }

    @Test
    fun `the square window for a profile photo is the largest centred square`() {
        val d = ImageCropGeometry.fit(1000f, 1000f, 2000, 1000)   // 1000 x 500 displayed
        val c = ImageCropGeometry.initialCrop(d, 1f)
        assertEquals(500f, c.width, eps)
        assertEquals(500f, c.height, eps)
        assertEquals(d.centerX, c.centerX, eps)
        assertEquals(d.centerY, c.centerY, eps)
        assertTrue(c.left >= d.left - eps && c.right <= d.right + eps)
    }

    @Test
    fun `a free crop starts as the whole image`() {
        val d = ImageCropGeometry.fit(1000f, 600f, 2000, 1000)
        assertEquals(d, ImageCropGeometry.initialCrop(d, null))
    }

    @Test
    fun `moving stops at the edge instead of sliding off`() {
        val d = Box(0f, 0f, 1000f, 1000f)
        val box = Box(800f, 800f, 1000f, 1000f)
        val moved = ImageCropGeometry.move(box, 500f, 500f, d)
        assertEquals(1000f, moved.right, eps)
        assertEquals(1000f, moved.bottom, eps)
        assertEquals(200f, moved.width, eps)   // size unchanged by a blocked move
        assertEquals(200f, moved.height, eps)
    }

    @Test
    fun `a square resize stays square and inside the image`() {
        val d = Box(0f, 0f, 1000f, 500f)
        val box = Box(250f, 0f, 750f, 500f)   // the centred square
        val grown = ImageCropGeometry.resize(box, Handle.TOP_LEFT, -400f, -400f, d, 1f)
        assertEquals(grown.width, grown.height, eps)
        assertTrue(grown.top >= d.top - eps)
        assertTrue(grown.left >= d.left - eps)
        // It cannot have grown past the 500 px the image offers vertically.
        assertTrue(grown.height <= 500f + eps)
    }

    @Test
    fun `a resize is refused rather than collapsing below the minimum`() {
        val d = Box(0f, 0f, 1000f, 1000f)
        val box = Box(0f, 0f, 100f, 100f)
        val shrunk = ImageCropGeometry.resize(box, Handle.BOTTOM_RIGHT, -900f, -900f, d, null)
        assertTrue(shrunk.width >= ImageCropGeometry.MIN_SIDE - eps)
        assertTrue(shrunk.height >= ImageCropGeometry.MIN_SIDE - eps)
    }

    @Test
    fun `a free resize moves only the axes the finger moved`() {
        val d = Box(0f, 0f, 1000f, 1000f)
        val box = Box(200f, 200f, 600f, 600f)
        val r = ImageCropGeometry.resize(box, Handle.BOTTOM_RIGHT, 100f, 0f, d, null)
        assertEquals(500f, r.width, eps)
        assertEquals(400f, r.height, eps)
        assertEquals(200f, r.left, eps)
        assertEquals(200f, r.top, eps)
    }

    @Test
    fun `a touch near a corner grabs that corner, elsewhere the body`() {
        val box = Box(100f, 100f, 400f, 400f)
        assertEquals(Handle.TOP_LEFT, ImageCropGeometry.handleAt(box, 110f, 110f, 48f))
        assertEquals(Handle.BOTTOM_RIGHT, ImageCropGeometry.handleAt(box, 390f, 395f, 48f))
        assertEquals(Handle.TOP_RIGHT, ImageCropGeometry.handleAt(box, 400f, 100f, 48f))
        assertEquals(Handle.BOTTOM_LEFT, ImageCropGeometry.handleAt(box, 100f, 400f, 48f))
        assertEquals(Handle.BODY, ImageCropGeometry.handleAt(box, 250f, 250f, 48f))
    }

    @Test
    fun `a degenerate surface never produces an invalid source rect`() {
        val s = ImageCropGeometry.toSource(Box(0f, 0f, 0f, 0f), Box(0f, 0f, 0f, 0f), 640, 480)
        assertEquals(0, s.x); assertEquals(0, s.y)
        assertEquals(640, s.width); assertEquals(480, s.height)
        val empty = ImageCropGeometry.fit(0f, 0f, 640, 480)
        assertEquals(0f, empty.width, eps)
    }
}

/**
 * __IMAGE_CROP_2026_11_02__ The EXIF half: the crop window is only over the
 * right part of the picture if the picture is the right way up first.
 */
class ImageOrientationTest {

    @Test
    fun `the four rotations EXIF can ask for`() {
        assertEquals(0, com.oshi.messenger.ui.components.ImageOrientation.rotationFor(1))
        assertEquals(180, com.oshi.messenger.ui.components.ImageOrientation.rotationFor(3))
        assertEquals(90, com.oshi.messenger.ui.components.ImageOrientation.rotationFor(6))
        assertEquals(270, com.oshi.messenger.ui.components.ImageOrientation.rotationFor(8))
    }

    @Test
    fun `a mirrored tag keeps its rotation and reports the mirror`() {
        // 5, 4 and 7 are the transposed cases: a rotation AND a flip. Dropping
        // the flip is how a photo of text comes out readable-backwards.
        assertEquals(90, com.oshi.messenger.ui.components.ImageOrientation.rotationFor(5))
        assertEquals(180, com.oshi.messenger.ui.components.ImageOrientation.rotationFor(4))
        assertEquals(270, com.oshi.messenger.ui.components.ImageOrientation.rotationFor(7))
        for (o in listOf(2, 4, 5, 7)) {
            assertTrue("$o is mirrored", com.oshi.messenger.ui.components.ImageOrientation.mirroredFor(o))
        }
        for (o in listOf(1, 3, 6, 8)) {
            assertTrue("$o is not mirrored", !com.oshi.messenger.ui.components.ImageOrientation.mirroredFor(o))
        }
    }

    @Test
    fun `an absent or nonsense tag is treated as upright`() {
        for (o in listOf(0, 1, 9, 42, -1)) {
            assertEquals(0, com.oshi.messenger.ui.components.ImageOrientation.rotationFor(o))
        }
    }
}
