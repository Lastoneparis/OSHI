package com.oshi.messenger.service.map

import kotlin.math.abs

/**
 * What the Map destination must do with the `lat` / `lon` / `name` query
 * arguments it was opened with.
 *
 * ══ THE BUG THIS EXISTS TO KILL ══
 *
 * Tapping a location bubble in a conversation navigates to the Map **tab**
 * (`MainActivity.kt`, `onOpenLocationInMap`) with the shared coordinate in the
 * route: `map?lat=48.85&lon=2.35&name=…`. The destination's `LaunchedEffect`
 * then called `MapViewModel.selectPOI(...)`, which raises the POI detail sheet
 * — the one carrying the "Y aller" / Navigate button.
 *
 * Three things then conspired:
 *
 *   • `selectedPOI` lives in a `MapViewModel` scoped to the Map
 *     **NavBackStackEntry**, not to the visit. Nothing ever cleared it.
 *   • The bottom bar navigates with `popUpTo(start){saveState=true}` +
 *     `restoreState = true` + `launchSingleTop`, so leaving the Map tab SAVES
 *     that entry — ViewModel, arguments and all — and tapping "Carte" later
 *     RESTORES it.
 *   • The coordinate is a nav ARGUMENT, and an argument is not consumed by
 *     being read. The restored entry still carries `lat`/`lon`, so the effect
 *     re-selected the same POI on every return.
 *
 * Net effect, in the owner's words: "when I re-click on the Discussion menu it
 * sticks and still shows *Y aller* in the Carte view". The Map tab had silently
 * become a permanent viewer for one shared location.
 *
 * A manual "clear on exit" hung off the chat screen would have papered over the
 * ONE exit path that was reported. There are several — system Back, the tab
 * bar, the rail, a deep link — and they do not share a callback. The decision
 * therefore belongs to the map ENTRY, where every path converges, and it is
 * expressed here as pure data so it can be tested without a device.
 */
sealed interface MapEntryFocus {

    /**
     * Open the map focused on a shared coordinate, and mark the entry consumed
     * so the next arrival at the same entry takes the [ClearStaleFocus] branch.
     */
    data class FocusShared(
        val lat: Double,
        val lon: Double,
        val label: String?
    ) : MapEntryFocus

    /**
     * The map was entered as a plain tab — either it never carried a coordinate,
     * or it carried one that has already been shown. Any shared-location focus
     * left over from an earlier visit must go.
     */
    data object ClearStaleFocus : MapEntryFocus
}

object SharedLocationFocus {

    /**
     * Key under which the "this entry has already shown its coordinate" latch is
     * stored in the Map entry's `SavedStateHandle`.
     *
     * `SavedStateHandle` is the right home precisely because it is saved and
     * restored WITH the back-stack entry: the restored entry that still carries
     * the stale `lat`/`lon` also carries the latch that disarms them. A
     * `remember`, a ViewModel field or a screen-local flag would each come back
     * false on restore and re-arm the bug.
     */
    const val CONSUMED_KEY = "oshi.map.shared_location_consumed"

    /**
     * How close two coordinates must be to count as the same spot, in degrees.
     *
     * Used to decide whether an active route belongs to the shared-location
     * visit that is ending. 1e-6° is ≈ 0.11 m — tighter than any GPS fix, so it
     * only matches a coordinate that literally round-tripped through the nav
     * argument (`Double.toString` → `toDouble` is exact), never a nearby POI the
     * user picked themselves.
     */
    const val SAME_SPOT_EPSILON_DEGREES = 1e-6

    /**
     * The decision, as a pure function of the entry's arguments and its latch.
     *
     * @param latArg raw `lat` query argument, as it comes out of the Bundle.
     * @param lonArg raw `lon` query argument.
     * @param nameArg raw `name` query argument (already URL-decoded by the
     *   navigation library); blank means "no label".
     * @param alreadyConsumed value of the [CONSUMED_KEY] latch on this entry.
     */
    fun decide(
        latArg: String?,
        lonArg: String?,
        nameArg: String?,
        alreadyConsumed: Boolean
    ): MapEntryFocus {
        if (alreadyConsumed) return MapEntryFocus.ClearStaleFocus

        val lat = latArg?.trim()?.toDoubleOrNull() ?: return MapEntryFocus.ClearStaleFocus
        val lon = lonArg?.trim()?.toDoubleOrNull() ?: return MapEntryFocus.ClearStaleFocus
        if (!isPlausible(lat, lon)) return MapEntryFocus.ClearStaleFocus

        return MapEntryFocus.FocusShared(
            lat = lat,
            lon = lon,
            label = nameArg?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * Whether a coordinate pair can be a real place.
     *
     * `(0, 0)` is deliberately allowed — Null Island is a valid coordinate and
     * refusing it would be inventing a rule iOS does not have. NaN / infinity
     * are not: they come from a malformed argument and would poison every
     * downstream span computation.
     */
    fun isPlausible(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && abs(lat) <= 90.0 && abs(lon) <= 180.0

    /**
     * The POI id used for a shared location. Deterministic in the coordinate so
     * the same shared point selected twice is the same POI, and so
     * `clearSharedLocationFocus` can tell "the pin I put there" from "a pin the
     * user chose".
     */
    fun poiId(lat: Double, lon: Double): String = "location_${lat}_${lon}"

    /** Whether two coordinates are the same spot — see [SAME_SPOT_EPSILON_DEGREES]. */
    fun sameSpot(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Boolean =
        abs(lat1 - lat2) <= SAME_SPOT_EPSILON_DEGREES &&
            abs(lon1 - lon2) <= SAME_SPOT_EPSILON_DEGREES
}
