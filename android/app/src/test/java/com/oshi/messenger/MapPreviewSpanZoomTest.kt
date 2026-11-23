package com.oshi.messenger

import com.oshi.messenger.service.map.OshiStaticMapRenderer
import com.oshi.messenger.service.map.OshiTileSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * P2-2 — the location preview is framed by a **distance**, not by a fixed zoom.
 *
 * iOS pins the camera to a metre span: 700 m for the share sheet
 * (`OSHI/LocationSharingView.swift:517`) and 800 m for the chat bubble
 * (`:630`), described in-source as "the scale at which a street still carries
 * its name". Android hard-coded `DEFAULT_ZOOM = 15` regardless of latitude or
 * screen width, so the preview covered ~2.6 km — a neighbourhood, not a street.
 *
 * These tests assert the ground the user actually sees, in metres, not the
 * zoom integer — the integer is an implementation detail and asserting it would
 * pass just as happily if the formula were wrong in a compensating direction.
 */
class MapPreviewSpanZoomTest {

    /** Metres across [widthPx] at integer zoom [z] and latitude [lat]. */
    private fun spanMetersAt(z: Int, lat: Double, widthPx: Int): Double =
        156_543.03392804097 * cos(Math.toRadians(lat)) / Math.pow(2.0, z.toDouble()) * widthPx

    // ───────────────────────────────────────────────────────────────────
    // The regression that motivated the change
    // ───────────────────────────────────────────────────────────────────

    /**
     * The measurement from the audit, reproduced as an assertion so nobody
     * "fixes" the renderer back toward a fixed zoom.
     *
     * 280 dp preview on a 3× device = 840 px, Paris latitude.
     */
    @Test
    fun `the old fixed zoom framed the preview several times too wide`() {
        val widthPx = 840
        val lat = 48.9

        val oldSpan = spanMetersAt(OshiStaticMapRenderer.DEFAULT_ZOOM, lat, widthPx)
        assertTrue(
            "z=15 should cover ~2.6 km, measured $oldSpan m",
            oldSpan in 2500.0..2800.0
        )
        assertTrue(
            "the old framing should be >3x iOS's 800 m, measured ${oldSpan / 800.0}x",
            oldSpan / OshiStaticMapRenderer.DEFAULT_SPAN_METERS > 3.0
        )

        val newZoom = OshiStaticMapRenderer.zoomForSpan(
            lat, OshiStaticMapRenderer.DEFAULT_SPAN_METERS, widthPx
        )
        val newSpan = spanMetersAt(newZoom, lat, widthPx)
        assertTrue(
            "derived framing should land near 800 m, measured $newSpan m at z=$newZoom",
            newSpan in 500.0..1300.0
        )
    }

    // ───────────────────────────────────────────────────────────────────
    // The property: the chosen zoom is the closest available one
    // ───────────────────────────────────────────────────────────────────

    /**
     * Raster zoom is integral, so the span can only be hit within a factor of
     * √2. The requirement is that the chosen level is the **nearest** one —
     * neither neighbour may be closer in ratio.
     */
    @Test
    fun `the chosen zoom is the nearest available level to the requested span`() {
        val cases = listOf(
            Triple(48.8566, 800.0, 840),    // Paris, 3x phone, bubble span
            Triple(48.8566, 700.0, 840),    // Paris, sheet span
            Triple(0.0, 800.0, 1080),       // equator, wide screen
            Triple(64.13, 800.0, 540),      // Reykjavik, 2x phone
            Triple(-33.87, 1500.0, 1440),   // Sydney, tablet, wider span
            Triple(1.35, 300.0, 720),       // Singapore, tight span
        )
        cases.forEach { (lat, span, widthPx) ->
            val z = OshiStaticMapRenderer.zoomForSpan(lat, span, widthPx)
            val chosen = spanMetersAt(z, lat, widthPx)
            val err = maxOf(chosen / span, span / chosen)

            listOf(z - 1, z + 1)
                .filter { it in OshiTileSource.MIN_ZOOM..OshiTileSource.MAX_ZOOM }
                .forEach { neighbour ->
                    val nSpan = spanMetersAt(neighbour, lat, widthPx)
                    val nErr = maxOf(nSpan / span, span / nSpan)
                    assertTrue(
                        "lat=$lat span=$span w=$widthPx: z=$neighbour (err $nErr) " +
                            "is closer than the chosen z=$z (err $err)",
                        nErr >= err - 1e-9
                    )
                }
            // Integral zoom can never be worse than sqrt(2) off.
            assertTrue("lat=$lat span=$span: err $err exceeds sqrt(2)", err <= 1.4143)
        }
    }

    /**
     * Same span, wider viewport ⇒ must zoom OUT is wrong — it must zoom IN,
     * because more pixels covering the same ground means finer resolution.
     * Getting this backwards is the easiest sign error in the formula.
     */
    @Test
    fun `a wider viewport at a fixed span zooms in, not out`() {
        val lat = 48.8566
        val span = 800.0
        val narrow = OshiStaticMapRenderer.zoomForSpan(lat, span, 360)
        val wide = OshiStaticMapRenderer.zoomForSpan(lat, span, 1440)
        assertTrue("expected wide($wide) > narrow($narrow)", wide > narrow)
        // 4x the pixels across the same ground is exactly 2 zoom levels.
        assertEquals(2, wide - narrow)
    }

    /**
     * A larger requested span must zoom OUT. Doubling the span is one level.
     */
    @Test
    fun `a larger span zooms out by one level per doubling`() {
        val lat = 48.8566
        val w = 840
        val tight = OshiStaticMapRenderer.zoomForSpan(lat, 500.0, w)
        val loose = OshiStaticMapRenderer.zoomForSpan(lat, 1000.0, w)
        assertEquals(1, tight - loose)
    }

    /**
     * Web Mercator resolution scales with cos(latitude): the same zoom covers
     * far less ground near the poles. A latitude-blind formula would show a
     * Tromsø user roughly half the ground a Nairobi user gets.
     */
    @Test
    fun `latitude is taken into account, not assumed to be the equator`() {
        val span = 800.0
        val w = 840
        val equator = OshiStaticMapRenderer.zoomForSpan(0.0, span, w)
        val tromso = OshiStaticMapRenderer.zoomForSpan(69.65, span, w)
        assertTrue(
            "high latitude must use a lower zoom (equator=$equator tromso=$tromso)",
            tromso < equator
        )
        // Both must still land near the requested span.
        listOf(0.0 to equator, 69.65 to tromso).forEach { (lat, z) ->
            val s = spanMetersAt(z, lat, w)
            assertTrue("lat=$lat span $s far from $span", maxOf(s / span, span / s) <= 1.4143)
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // Degenerate inputs — a preview must never crash a chat screen
    // ───────────────────────────────────────────────────────────────────

    @Test
    fun `degenerate inputs fall back to the fixed zoom instead of throwing`() {
        val d = OshiStaticMapRenderer.DEFAULT_ZOOM
        assertEquals(d, OshiStaticMapRenderer.zoomForSpan(48.85, 800.0, 0))
        assertEquals(d, OshiStaticMapRenderer.zoomForSpan(48.85, 800.0, -100))
        assertEquals(d, OshiStaticMapRenderer.zoomForSpan(48.85, 0.0, 840))
        assertEquals(d, OshiStaticMapRenderer.zoomForSpan(48.85, -5.0, 840))
        assertEquals(d, OshiStaticMapRenderer.zoomForSpan(48.85, Double.NaN, 840))
        assertEquals(d, OshiStaticMapRenderer.zoomForSpan(48.85, Double.POSITIVE_INFINITY, 840))
        assertEquals(d, OshiStaticMapRenderer.zoomForSpan(Double.NaN, 800.0, 840))
    }

    /**
     * The result must always be a zoom for which raster tiles physically
     * exist — asking for a 1 cm span must not produce z=31 and a 404 wall.
     */
    @Test
    fun `the result is always inside the tile sources zoom range`() {
        val spans = listOf(0.01, 1.0, 50.0, 800.0, 50_000.0, 20_000_000.0)
        val lats = listOf(-85.0, -60.0, 0.0, 48.85, 85.0)
        val widths = listOf(1, 64, 840, 4096)
        spans.forEach { s ->
            lats.forEach { lat ->
                widths.forEach { w ->
                    val z = OshiStaticMapRenderer.zoomForSpan(lat, s, w)
                    assertTrue(
                        "z=$z out of range for span=$s lat=$lat w=$w",
                        z in OshiTileSource.MIN_ZOOM..OshiTileSource.MAX_ZOOM
                    )
                }
            }
        }
    }

    /**
     * The renderer's own advertised span must be the one iOS uses for the
     * bubble, and the derived zoom must actually be tighter than the legacy
     * constant on a typical phone — otherwise the fix is inert.
     */
    @Test
    fun `the default span is iOS's and it tightens the framing`() {
        assertEquals(800.0, OshiStaticMapRenderer.DEFAULT_SPAN_METERS, 0.0)
        val z = OshiStaticMapRenderer.zoomForSpan(
            48.8566, OshiStaticMapRenderer.DEFAULT_SPAN_METERS, 840
        )
        assertTrue(
            "derived zoom $z must be tighter than the legacy ${OshiStaticMapRenderer.DEFAULT_ZOOM}",
            z > OshiStaticMapRenderer.DEFAULT_ZOOM
        )
    }
}
