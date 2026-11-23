package com.oshi.messenger.service

import com.oshi.messenger.data.model.TransportMode
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How long the journey should take — the number the whole safety feature is
 * measured against.
 *
 * ══ WHAT THIS REPLACES ══
 *
 * `CheckInSheet` armed every session with
 * `val eta = System.currentTimeMillis() + (30 * 60 * 1000)` and the comment
 * "For demo, use current time + 30 min as ETA". Thirty minutes for a two-minute
 * walk means a real emergency is announced 43 minutes late; thirty minutes for an
 * hour's drive means a **false** emergency to a contact while the user is on the
 * motorway. iOS never had this: it asks MapKit for a route and refuses to start
 * without one (`OSHI/CheckInView.swift:473-474`, `CheckInManager.swift:197-225`).
 *
 * ══ WHY AN ESTIMATOR AND NOT A ROUTER ══
 *
 * The app's router (`OfflineRoutingManager.calculateRoute`) needs a downloaded road
 * graph for the region and refuses — correctly — rather than fabricating a straight
 * line when it has none. A check-in must be armable in a city whose map has not been
 * downloaded, so the deadline cannot depend on it. What this does instead is honest
 * about being an estimate: a great-circle distance, inflated by a street-network
 * detour factor, divided by a **deliberately pessimistic** speed, and then offered to
 * the user as a pre-selected duration they can change. The user's choice is what is
 * stored; this only picks the default.
 *
 * ══ WHY THE SPEEDS ARE LOW ══
 *
 * The asymmetry of the two failure modes drives every constant here. Over-estimating
 * speed produces a deadline that is too early, and an early deadline is a false
 * alarm — the expensive direction. Under-estimating produces a late deadline, and a
 * late deadline is covered by the user's own "I've arrived" button, which now exists
 * on the notification, on the banner and in the warning dialog. So every constant is
 * rounded toward "slower than you probably are".
 */
object CheckInEtaEstimator {

    /** Metres per second. ~4.5 km/h — a loaded, tired walker, not a race. */
    const val WALKING_MPS = 1.25

    /** ~30 km/h door to door: urban driving including parking and the last 200 m. */
    const val DRIVING_MPS = 8.3

    /** ~20 km/h including the wait on the platform, which is most of a short trip. */
    const val TRANSIT_MPS = 5.5

    /**
     * Straight line → street distance. 1.35 is the usual urban-grid figure; it is
     * applied to the great-circle distance because that is all we can compute
     * without a road graph.
     */
    const val DETOUR_FACTOR = 1.35

    /** No journey is armed for less than this: GPS noise alone spans a few minutes. */
    const val MIN_MINUTES = 5

    /** Above this the estimate is not credible; the user picks explicitly. */
    const val MAX_MINUTES = 12 * 60

    /** The durations offered in the sheet. The estimate snaps to one of these. */
    val OFFERED_MINUTES: List<Int> = listOf(10, 15, 30, 45, 60, 90, 120, 180)

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Haversine distance in metres. Pure; no `android.location.Location`. */
    fun greatCircleMetres(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
    ): Double {
        val dLat = Math.toRadians(toLat - fromLat)
        val dLon = Math.toRadians(toLon - fromLon)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(fromLat)) * cos(Math.toRadians(toLat)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    fun speedMps(mode: TransportMode): Double = when (mode) {
        TransportMode.walking -> WALKING_MPS
        TransportMode.driving -> DRIVING_MPS
        TransportMode.transit -> TRANSIT_MPS
    }

    /**
     * Minutes for [straightLineMetres] of great-circle separation, clamped to
     * [MIN_MINUTES]..[MAX_MINUTES]. A negative or non-finite distance yields
     * [MIN_MINUTES] rather than throwing: a bad coordinate must not be able to
     * prevent a safety session from arming.
     */
    fun estimateMinutes(straightLineMetres: Double, mode: TransportMode): Int {
        if (!straightLineMetres.isFinite() || straightLineMetres <= 0.0) return MIN_MINUTES
        val seconds = (straightLineMetres * DETOUR_FACTOR) / speedMps(mode)
        val minutes = (seconds / 60.0).roundToInt()
        return minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
    }

    fun estimateMinutes(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        mode: TransportMode,
    ): Int = estimateMinutes(greatCircleMetres(fromLat, fromLon, toLat, toLon), mode)

    /**
     * The entry in [OFFERED_MINUTES] the sheet should pre-select for [minutes].
     *
     * Rounds **up**, never down: a pre-selected duration shorter than the estimate is
     * a deadline that expires while the user is still travelling, which is the false
     * alarm. Above the last offer, the last offer is used and the user is expected to
     * add slack with the existing "extra time" chips.
     */
    fun snapToOffered(minutes: Int): Int =
        OFFERED_MINUTES.firstOrNull { it >= minutes } ?: OFFERED_MINUTES.last()
}
