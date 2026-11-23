package com.oshi.messenger

import com.oshi.messenger.service.map.OshiStaticMapRenderer
import com.oshi.messenger.service.map.OshiTileSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "I don't understand, when sharing position with someone, where I am and where
 * the other person is." — the owner, 2026-08-24.
 *
 * ══ WHAT THE BUBBLE DREW BEFORE ══
 *
 * One bitmap centred on the shared coordinate, with a single red Material pin
 * overlaid dead-centre by Compose. That overlay could only ever mark the point
 * the bitmap happened to be centred on, so:
 *
 *   • the viewer's OWN position could not be drawn at all — there was no way to
 *     place a second coordinate;
 *   • the one marker carried no identity: no photo, no initials, nothing to say
 *     whose position it was;
 *   • a live share that moved was framed by the coordinate the bitmap was built
 *     with, because nothing recomputed the camera.
 *
 * iOS draws two annotations that differ in colour, shape and label
 * (`OSHI/LocationSharingView.swift:696-800`) and fits both into frame
 * (`fitBothLocations()` at `:1469`). These tests cover the arithmetic that makes
 * that possible on Android: the camera, and the projection of an arbitrary
 * coordinate into it.
 *
 * ══ WHY PIXELS AND NOT DEGREES ══
 *
 * The obvious fit — take the degree deltas, pad them, pick a zoom — is wrong
 * away from the equator: a degree of longitude is 111 km at Paris's latitude of
 * a parallel but the map projects it as 111 km × cos(48.9) ≈ 73 km of ground.
 * A degree-based fit therefore overestimates the horizontal room it needs at
 * high latitude and underestimates the vertical, and one of the two markers
 * lands outside the frame. `fitTwoPoints` works in Mercator world pixels, which
 * is the space the tiles are actually in, so the answer is exact by
 * construction. The high-latitude case below is the one that catches a
 * regression to degrees.
 */
class LocationPreviewFramingTest {

    // A 280 dp bubble preview on a 3x device, as shipped.
    private val w = 840
    private val h = 540

    /** Where a coordinate lands in the viewport described by [f]. */
    private fun project(f: OshiStaticMapRenderer.Framing, lat: Double, lon: Double): Pair<Int, Int> {
        val plan = OshiStaticMapRenderer.plan(f.centerLat, f.centerLon, f.zoom, w, h)
        return OshiStaticMapRenderer.projectInto(plan, lat, lon)
    }

    private fun assertInsideFrame(name: String, p: Pair<Int, Int>) {
        assertTrue("$name x=${p.first} outside 0..$w", p.first in 0..w)
        assertTrue("$name y=${p.second} outside 0..$h", p.second in 0..h)
    }

    // ───────────────────────────────────────────────────────────────────
    // Projection: the thing that made a second marker possible
    // ───────────────────────────────────────────────────────────────────

    /**
     * The point a plan is built around must project onto the plan's own pin
     * position. If these two disagree, every marker is offset from the map
     * underneath it and the whole preview lies about where people are.
     */
    @Test
    fun `the centre coordinate projects onto the plans own pin`() {
        val lat = 48.8566
        val lon = 2.3522
        val plan = OshiStaticMapRenderer.plan(lat, lon, 15, w, h)
        val (x, y) = OshiStaticMapRenderer.projectInto(plan, lat, lon)
        assertTrue("x $x vs pinX ${plan.pinX}", kotlin.math.abs(x - plan.pinX) <= 1)
        assertTrue("y $y vs pinY ${plan.pinY}", kotlin.math.abs(y - plan.pinY) <= 1)
        assertEquals("centre must be the middle of the viewport", w / 2, plan.pinX)
        assertEquals(h / 2, plan.pinY)
    }

    /**
     * North is up and east is right. Trivial, and exactly the kind of sign error
     * that would put "me" on the wrong side of the contact and be invisible in a
     * screenshot of two dots near each other.
     */
    @Test
    fun `projection puts north up and east right`() {
        val plan = OshiStaticMapRenderer.plan(48.8566, 2.3522, 15, w, h)
        val north = OshiStaticMapRenderer.projectInto(plan, 48.8600, 2.3522)
        val east = OshiStaticMapRenderer.projectInto(plan, 48.8566, 2.3600)
        assertTrue("north should be above centre", north.second < plan.pinY)
        assertTrue("east should be right of centre", east.first > plan.pinX)
    }

    // ───────────────────────────────────────────────────────────────────
    // Two-point framing
    // ───────────────────────────────────────────────────────────────────

    /**
     * The headline behaviour: both people are on screen. Checked over a spread
     * of separations, from across-the-street to across-the-city, because a fit
     * that only works at one scale is a coincidence.
     */
    @Test
    fun `both points land inside the frame at every separation`() {
        val meLat = 48.8566
        val meLon = 2.3522
        listOf(0.0005, 0.002, 0.01, 0.05, 0.2, 1.0).forEach { d ->
            val themLat = meLat + d
            val themLon = meLon + d * 1.7
            val f = OshiStaticMapRenderer.fitTwoPoints(themLat, themLon, meLat, meLon, w, h)
            assertInsideFrame("them (d=$d)", project(f, themLat, themLon))
            assertInsideFrame("me (d=$d)", project(f, meLat, meLon))
        }
    }

    /**
     * The regression a degree-based fit would reintroduce.
     *
     * At 70°N a degree of longitude projects to about a third of the ground a
     * degree of latitude does. A pair separated mostly in longitude therefore
     * needs far LESS horizontal room than its degree delta suggests, and a fit
     * that reasons in degrees frames far too wide — or, with the axes swapped,
     * pushes a marker off the edge. Tromsø, and a peer 0.4° of longitude away.
     */
    @Test
    fun `a high latitude pair still fits — the case degree math gets wrong`() {
        val meLat = 69.6492
        val meLon = 18.9553
        val themLat = 69.6600
        val themLon = 18.9553 + 0.40
        val f = OshiStaticMapRenderer.fitTwoPoints(themLat, themLon, meLat, meLon, w, h)
        assertInsideFrame("them", project(f, themLat, themLon))
        assertInsideFrame("me", project(f, meLat, meLon))
    }

    /** Same, on the other side of the equator, where the Mercator y axis flips sign. */
    @Test
    fun `a southern hemisphere pair still fits`() {
        val meLat = -33.8688
        val meLon = 151.2093
        val themLat = -33.9100
        val themLon = 151.1500
        val f = OshiStaticMapRenderer.fitTwoPoints(themLat, themLon, meLat, meLon, w, h)
        assertInsideFrame("them", project(f, themLat, themLon))
        assertInsideFrame("me", project(f, meLat, meLon))
    }

    /**
     * The camera sits between the two people, not on one of them. A fit that
     * quietly centres on the shared point still "contains" both when they are
     * close, and only reveals itself when they are not.
     */
    @Test
    fun `the camera centres between the two points`() {
        val f = OshiStaticMapRenderer.fitTwoPoints(48.90, 2.40, 48.80, 2.30, w, h)
        assertTrue("centre lat ${f.centerLat} not between", f.centerLat in 48.80..48.90)
        assertTrue("centre lon ${f.centerLon} not between", f.centerLon in 2.30..2.40)
        assertEquals("longitude midpoint is exact", 2.35, f.centerLon, 1e-9)
    }

    /**
     * iOS floors the two-point span (`max(0.01, delta * 1.5)`) so that two
     * coordinates a few metres apart do not zoom the camera onto a single
     * doorway. Without the floor, standing next to someone produces a preview of
     * a paving slab.
     */
    @Test
    fun `two nearly identical points do not zoom to a doorway`() {
        val f = OshiStaticMapRenderer.fitTwoPoints(48.8566, 2.3522, 48.85661, 2.35221, w, h)
        val ceiling = OshiStaticMapRenderer.zoomForSpan(
            48.8566, OshiStaticMapRenderer.MIN_TWO_POINT_SPAN_METERS, w
        )
        assertTrue("zoom ${f.zoom} exceeded the ${ceiling} ceiling", f.zoom <= ceiling)
    }

    /** Identical coordinates must not divide by zero or return a NaN camera. */
    @Test
    fun `identical points give a usable camera`() {
        val f = OshiStaticMapRenderer.fitTwoPoints(48.8566, 2.3522, 48.8566, 2.3522, w, h)
        assertTrue(f.zoom in OshiTileSource.MIN_ZOOM..OshiTileSource.MAX_ZOOM)
        assertEquals(48.8566, f.centerLat, 1e-6)
        assertEquals(2.3522, f.centerLon, 1e-6)
    }

    /**
     * Two points on opposite sides of the planet cannot both be shown at a
     * sensible scale; the camera must still be a legal one rather than a zoom
     * below the tile pyramid.
     */
    @Test
    fun `an absurd separation still yields a legal zoom`() {
        val f = OshiStaticMapRenderer.fitTwoPoints(-33.86, 151.20, 48.85, 2.35, w, h)
        assertTrue("zoom ${f.zoom}", f.zoom in OshiTileSource.MIN_ZOOM..OshiTileSource.MAX_ZOOM)
    }

    // ───────────────────────────────────────────────────────────────────
    // One-point framing
    // ───────────────────────────────────────────────────────────────────

    /**
     * With only the shared point to show, the camera is the point itself at the
     * app's standard preview span — the framing a live share must be re-derived
     * against as it moves, which the static bitmap never was.
     */
    @Test
    fun `one point framing centres on the point at the standard span`() {
        val f = OshiStaticMapRenderer.fitOnePoint(48.8566, 2.3522, w)
        assertEquals(48.8566, f.centerLat, 1e-12)
        assertEquals(2.3522, f.centerLon, 1e-12)
        assertEquals(
            OshiStaticMapRenderer.zoomForSpan(48.8566, OshiStaticMapRenderer.DEFAULT_SPAN_METERS, w),
            f.zoom
        )
    }

    /**
     * A live share that moves must move the camera. Trivially true now, and the
     * exact property the old `remember(lat, lon)` bitmap key silently satisfied
     * while the pin stayed nailed to the centre — so it is worth stating.
     */
    @Test
    fun `a moving point moves the camera`() {
        val a = OshiStaticMapRenderer.fitOnePoint(48.8566, 2.3522, w)
        val b = OshiStaticMapRenderer.fitOnePoint(48.8600, 2.3600, w)
        assertNotEquals(a.centerLat, b.centerLat)
        assertNotEquals(a.centerLon, b.centerLon)
    }

    // ───────────────────────────────────────────────────────────────────
    // Initials, when there is no profile picture
    // ───────────────────────────────────────────────────────────────────

    /**
     * iOS uses `String(contactName.prefix(2)).uppercased()`
     * (`OSHI/OfflineMapView.swift:2467`) — the first two CHARACTERS, not the
     * initials of two words. Matched deliberately so the same contact shows the
     * same disc on both platforms.
     */
    @Test
    fun `initials are the first two characters, uppercased — as on iOS`() {
        assertEquals("HU", OshiStaticMapRenderer.initialsFor("Hugo Moriceau"))
        assertEquals("AL", OshiStaticMapRenderer.initialsFor("alice"))
        assertEquals("É", OshiStaticMapRenderer.initialsFor("é"))
        assertEquals("ЖЕ", OshiStaticMapRenderer.initialsFor("Женя"))
    }

    /** A name is whatever the contact typed. None of it may crash the bubble. */
    @Test
    fun `a missing or blank name falls back to a placeholder`() {
        assertEquals("?", OshiStaticMapRenderer.initialsFor(null))
        assertEquals("?", OshiStaticMapRenderer.initialsFor(""))
        assertEquals("?", OshiStaticMapRenderer.initialsFor("   "))
    }

    /**
     * `take(2)` counts UTF-16 units and would cut an emoji in half, rendering two
     * replacement boxes. Code points keep the character whole.
     */
    @Test
    fun `an astral plane character is not cut in half`() {
        // U+1F600 is a surrogate PAIR: naive take(2) would return half of it.
        val out = OshiStaticMapRenderer.initialsFor("😀A")
        assertEquals("the emoji must survive whole", "😀A", out)
        assertEquals(3, out.length)
    }
}
