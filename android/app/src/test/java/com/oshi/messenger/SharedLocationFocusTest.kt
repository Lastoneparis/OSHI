package com.oshi.messenger

import com.oshi.messenger.service.map.MapEntryFocus
import com.oshi.messenger.service.map.SharedLocationFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stuck "Y aller" — reported 2026-08-24.
 *
 * ══ WHAT THE OWNER SAW ══
 *
 * "When I click on the map inside the shared position I go into the Carte view,
 * and when I re-click on the Discussion view menu it sticks and still shows
 * *Y aller* in the Carte view."
 *
 * ══ WHAT WAS ACTUALLY HAPPENING ══
 *
 * Tapping a location bubble navigates to the Map TAB with the coordinate in the
 * route (`map?lat=…&lon=…&name=…`). `MainActivity` then called
 * `MapViewModel.selectPOI(...)`, which raises the POI sheet — the sheet that
 * carries the Navigate / "Y aller" button.
 *
 * Three facts turned that into a permanent state:
 *
 *   1. `selectedPOI` lives in a ViewModel scoped to the Map **NavBackStackEntry**.
 *   2. The bottom bar navigates with `popUpTo(start){saveState = true}` +
 *      `restoreState = true`, so leaving the Map tab SAVES that entry and tapping
 *      "Carte" later RESTORES it — arguments, saved state and ViewModel included.
 *   3. Reading a nav argument does not consume it. The restored entry still said
 *      `lat=…&lon=…`, so the effect re-selected the same POI on every return.
 *
 * Nothing in the app ever cleared it. The Map tab had quietly become a
 * single-purpose viewer for one shared location.
 *
 * ══ WHY THE TEST LOOKS LIKE THIS ══
 *
 * The temptation was to clear the selection from the chat screen's "back"
 * callback. That fixes the one exit path in the report and none of the others —
 * system Back, the navigation rail, a deep link, process death and restore. The
 * decision was therefore moved to the map ENTRY, where every path converges, and
 * expressed as a pure function of (arguments, latch) so it can be tested here
 * rather than on a device.
 *
 * `alreadyConsumed` is the whole fix in one parameter: it is stored in the
 * entry's `SavedStateHandle`, which is saved and restored WITH the entry — so the
 * restored entry that still carries the stale coordinate also carries the flag
 * that disarms it.
 */
class SharedLocationFocusTest {

    private val paris = "48.8566" to "2.3522"

    // ───────────────────────────────────────────────────────────────────
    // The reported bug, as an assertion
    // ───────────────────────────────────────────────────────────────────

    /**
     * The exact sequence from the report: open a shared location, leave, come
     * back to the Map tab. The second arrival must NOT re-raise the sheet.
     *
     * On the restored entry the arguments are unchanged — that is the point of
     * `restoreState` — so a decision that looked only at `lat`/`lon` would
     * happily focus the same POI again, which is precisely the bug.
     */
    @Test
    fun `returning to the map tab does not re-raise the shared location sheet`() {
        val first = SharedLocationFocus.decide(paris.first, paris.second, "Rue de Rivoli", alreadyConsumed = false)
        assertTrue("first arrival must focus the shared point", first is MapEntryFocus.FocusShared)

        // Same entry, same arguments, latch now set: this is what a restored
        // back-stack entry looks like.
        val second = SharedLocationFocus.decide(paris.first, paris.second, "Rue de Rivoli", alreadyConsumed = true)
        assertEquals(
            "a restored map entry must retire the stale focus, not re-apply it",
            MapEntryFocus.ClearStaleFocus,
            second
        )
    }

    /**
     * Opening the Map tab normally — no coordinate at all — must actively CLEAR,
     * not merely do nothing.
     *
     * "Do nothing" is what the old code did, and it is why a selection made in a
     * previous visit survived into a fresh one. The tab is not a blank slate: it
     * reuses a ViewModel that may still hold the last visit's pin.
     */
    @Test
    fun `opening the map tab with no coordinate clears any leftover focus`() {
        assertEquals(MapEntryFocus.ClearStaleFocus, SharedLocationFocus.decide(null, null, null, false))
        assertEquals(MapEntryFocus.ClearStaleFocus, SharedLocationFocus.decide("", "", "", false))
        assertEquals(MapEntryFocus.ClearStaleFocus, SharedLocationFocus.decide("  ", "  ", null, false))
    }

    // ───────────────────────────────────────────────────────────────────
    // The focus itself
    // ───────────────────────────────────────────────────────────────────

    @Test
    fun `a coordinate in the route focuses that exact point`() {
        val focus = SharedLocationFocus.decide("48.8566", "2.3522", "Rue de Rivoli", false)
        assertEquals(
            MapEntryFocus.FocusShared(48.8566, 2.3522, "Rue de Rivoli"),
            focus
        )
    }

    /** A blank `name` is "no label", not a label made of spaces. */
    @Test
    fun `a blank name argument becomes no label`() {
        val focus = SharedLocationFocus.decide("48.8566", "2.3522", "   ", false) as MapEntryFocus.FocusShared
        assertEquals(null, focus.label)
    }

    /**
     * Null Island is a real coordinate. Refusing `(0, 0)` would be inventing a
     * rule iOS does not have — and `0.0` is exactly what a defaulted argument
     * would parse to, so it is worth pinning which way this goes.
     */
    @Test
    fun `zero zero is a location, not a missing argument`() {
        assertTrue(SharedLocationFocus.decide("0", "0", null, false) is MapEntryFocus.FocusShared)
    }

    // ───────────────────────────────────────────────────────────────────
    // Malformed arguments
    // ───────────────────────────────────────────────────────────────────

    /**
     * A route is a string a caller wrote. Anything that is not a plausible
     * coordinate must degrade to a plain map, never to a pin at NaN — which
     * would propagate into every span and zoom computation downstream and paint
     * an empty preview with no explanation.
     */
    @Test
    fun `unparseable or impossible coordinates degrade to a plain map`() {
        val bad = listOf(
            "abc" to "2.35",
            "48.85" to "abc",
            "NaN" to "2.35",
            "48.85" to "Infinity",
            "91.0" to "2.35",      // past the pole
            "-90.5" to "2.35",
            "48.85" to "181.0",    // past the antimeridian
            "48.85" to "-180.5",
        )
        bad.forEach { (lat, lon) ->
            assertEquals(
                "lat=$lat lon=$lon must not focus anything",
                MapEntryFocus.ClearStaleFocus,
                SharedLocationFocus.decide(lat, lon, null, false)
            )
        }
    }

    @Test
    fun `the poles and the antimeridian are still valid`() {
        assertTrue(SharedLocationFocus.isPlausible(90.0, 180.0))
        assertTrue(SharedLocationFocus.isPlausible(-90.0, -180.0))
        assertFalse(SharedLocationFocus.isPlausible(Double.NaN, 0.0))
        assertFalse(SharedLocationFocus.isPlausible(0.0, Double.POSITIVE_INFINITY))
    }

    // ───────────────────────────────────────────────────────────────────
    // Identity of the pin we drop
    // ───────────────────────────────────────────────────────────────────

    /**
     * `clearSharedLocationFocus` only clears the pin IT dropped. That is only
     * safe if the id is a function of the coordinate, so the same shared point
     * is recognisable across visits and a POI the user picked never collides
     * with it.
     */
    @Test
    fun `the shared location poi id is derived from the coordinate`() {
        assertEquals(
            SharedLocationFocus.poiId(48.8566, 2.3522),
            SharedLocationFocus.poiId(48.8566, 2.3522)
        )
        assertTrue(
            SharedLocationFocus.poiId(48.8566, 2.3522) != SharedLocationFocus.poiId(48.8567, 2.3522)
        )
    }

    /**
     * `sameSpot` decides whether an active route belongs to the shared-location
     * visit that is ending. It must match a coordinate that round-tripped
     * through the nav argument (exact, `Double.toString` is lossless) and must
     * NOT match a different place the user routed to themselves — otherwise
     * leaving the map would silently delete their own route.
     */
    @Test
    fun `same spot matches a round-tripped coordinate but not a neighbouring one`() {
        val lat = 48.8566
        val lon = 2.3522
        val roundTripped = lat.toString().toDouble() to lon.toString().toDouble()
        assertTrue(SharedLocationFocus.sameSpot(lat, lon, roundTripped.first, roundTripped.second))

        // ~11 m away — a different place by any measure.
        assertFalse(SharedLocationFocus.sameSpot(lat, lon, lat + 0.0001, lon))
        assertFalse(SharedLocationFocus.sameSpot(lat, lon, lat, lon + 0.0001))
    }
}
