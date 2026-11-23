package com.oshi.messenger

import com.oshi.messenger.service.map.OshiStaticMapRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

/**
 * Slippy-map math behind the location preview that replaces the dead
 * `staticmap.openstreetmap.de` image (the host is NXDOMAIN).
 *
 * The reference values are the canonical OSM ones
 * (https://wiki.openstreetmap.org/wiki/Slippy_map_tilenames).
 */
class MapPreviewTileMathTest {

    @Test
    fun `tile indices match the canonical OSM examples`() {
        // Null Island at z=0 is the single world tile.
        assertEquals(0, floor(OshiStaticMapRenderer.tileXf(0.0, 0)).toInt())
        assertEquals(0, floor(OshiStaticMapRenderer.tileYf(0.0, 0)).toInt())

        // Berlin (52.5163, 13.3777) at z=13 → tile 4400/2686.
        assertEquals(4400, floor(OshiStaticMapRenderer.tileXf(13.3777, 13)).toInt())
        assertEquals(2686, floor(OshiStaticMapRenderer.tileYf(52.5163, 13)).toInt())

        // Paris (48.8566, 2.3522) at the preview's own zoom, z=15 → 16598/11273.
        assertEquals(16598, floor(OshiStaticMapRenderer.tileXf(2.3522, 15)).toInt())
        assertEquals(11273, floor(OshiStaticMapRenderer.tileYf(48.8566, 15)).toInt())

        // Antimeridian and poles stay in range. Clamping the latitude alone does
        // NOT clamp the result — the Mercator expression evaluates to -1.99e-10
        // at the north limit, and floor(-1.99e-10) is tile row -1.
        assertTrue(OshiStaticMapRenderer.tileXf(179.999, 5) < 32.0)
        assertTrue(OshiStaticMapRenderer.tileYf(89.0, 5) in 0.0..32.0)
        assertTrue(OshiStaticMapRenderer.tileYf(-89.0, 5) in 0.0..32.0)
        assertEquals(0, floor(OshiStaticMapRenderer.tileYf(89.9, 5)).toInt())
        assertTrue(floor(OshiStaticMapRenderer.tileYf(-89.9, 5)).toInt() <= 32)
    }

    @Test
    fun `tile to coordinate round-trips`() {
        val z = 15
        val lat = 48.8566
        val lon = 2.3522
        val x = OshiStaticMapRenderer.tileXf(lon, z)
        val y = OshiStaticMapRenderer.tileYf(lat, z)
        assertEquals(lon, OshiStaticMapRenderer.tileXToLon(x, z), 1e-6)
        assertEquals(lat, OshiStaticMapRenderer.tileYToLat(y, z), 1e-6)
    }

    @Test
    fun `plan covers the whole viewport and centres the pin`() {
        val w = 560
        val h = 360
        val p = OshiStaticMapRenderer.plan(48.8566, 2.3522, 15, w, h)

        assertEquals(15, p.zoom)
        // Offsets place tile (x0,y0) at or before the viewport origin.
        assertTrue(p.offsetX <= 0 && p.offsetX > -256)
        assertTrue(p.offsetY <= 0 && p.offsetY > -256)
        // The blitted grid must reach past the far edge, or the preview would
        // have an unpainted strip.
        assertTrue(p.offsetX + p.cols * 256 >= w)
        assertTrue(p.offsetY + p.rows * 256 >= h)
        // The coordinate lands at the centre — the pin is drawn there, locally,
        // so the exact point is never part of any request.
        assertEquals(w / 2, p.pinX)
        assertEquals(h / 2, p.pinY)
    }

    @Test
    fun `a viewport smaller than one tile still needs at least one tile`() {
        val p = OshiStaticMapRenderer.plan(0.0, 0.0, 4, 64, 64)
        assertTrue(p.cols >= 1)
        assertTrue(p.rows >= 1)
    }
}
