package com.oshi.messenger.service

import android.location.Location
import com.oshi.messenger.data.model.MapRoute
import com.oshi.messenger.data.model.RouteManeuver
import com.oshi.messenger.data.model.RoutePoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * LiveNavigationEngine — real-time turn-by-turn engine.
 *
 * Inputs:
 *   • A precomputed [MapRoute] (polyline + maneuvers).
 *   • A stream of [Location] updates (call [updateLocation] on each tick).
 *
 * Outputs (StateFlow):
 *   • [currentStepIndex]              — index of the maneuver the user is approaching.
 *   • [distanceToNextManeuver]        — meters to the next maneuver coordinate.
 *   • [remainingDistance]             — meters from user → destination (polyline-aware).
 *   • [etaSeconds]                    — projected seconds remaining.
 *   • [isOffRoute] / [hasArrived]     — discrete state flags.
 *   • [userHeadingDegrees]            — bearing in degrees (0 = north, CW).
 *   • [voicePrompt]                   — last fired voice prompt phrase (one-shot).
 *
 * Side-effects:
 *   • [onOffRoute] fires once when the user has been outside [offRouteThresholdMeters]
 *     of the polyline for [offRouteStallSeconds] seconds without progress.
 *   • [onArrived] fires once when the user is within [arrivalThresholdMeters] of the
 *     final polyline point.
 *
 * The host (the [com.oshi.messenger.ui.viewmodel.MapViewModel]) owns route recompute —
 * the engine only *signals* off-route. This keeps recompute logic + side-effects with
 * the existing search/route plumbing.
 *
 * 100% offline: no network calls.
 */
class LiveNavigationEngine(initialRoute: MapRoute) {

    // ───────────────────────────────────────────────────────────────────
    // Inputs
    // ───────────────────────────────────────────────────────────────────

    var route: MapRoute = initialRoute
        private set

    // ───────────────────────────────────────────────────────────────────
    // Tunables (Google-Maps-like defaults)
    // ───────────────────────────────────────────────────────────────────

    /** Distance (m) from a maneuver coordinate at which we auto-advance. */
    var advanceThresholdMeters: Double = 30.0

    /** Distance (m) from the polyline beyond which the user is potentially off route. */
    var offRouteThresholdMeters: Double = 80.0

    /** Stall window (s) before [onOffRoute] fires. */
    var offRouteStallSeconds: Long = 30L

    /** Distance (m) from the final polyline point that triggers arrival. */
    var arrivalThresholdMeters: Double = 30.0

    // ───────────────────────────────────────────────────────────────────
    // Callbacks
    // ───────────────────────────────────────────────────────────────────

    var onOffRoute: (() -> Unit)? = null
    var onArrived: (() -> Unit)? = null

    // ───────────────────────────────────────────────────────────────────
    // Published state
    // ───────────────────────────────────────────────────────────────────

    private val _currentStepIndex = MutableStateFlow(0)
    val currentStepIndex: StateFlow<Int> = _currentStepIndex.asStateFlow()

    private val _distanceToNextManeuver = MutableStateFlow<Double?>(null)
    val distanceToNextManeuver: StateFlow<Double?> = _distanceToNextManeuver.asStateFlow()

    private val _remainingDistance = MutableStateFlow(initialRoute.distance)
    val remainingDistance: StateFlow<Double> = _remainingDistance.asStateFlow()

    private val _etaSeconds = MutableStateFlow(initialRoute.duration)
    val etaSeconds: StateFlow<Double> = _etaSeconds.asStateFlow()

    private val _isOffRoute = MutableStateFlow(false)
    val isOffRoute: StateFlow<Boolean> = _isOffRoute.asStateFlow()

    private val _hasArrived = MutableStateFlow(false)
    val hasArrived: StateFlow<Boolean> = _hasArrived.asStateFlow()

    private val _userHeadingDegrees = MutableStateFlow<Float?>(null)
    val userHeadingDegrees: StateFlow<Float?> = _userHeadingDegrees.asStateFlow()

    /** One-shot voice prompt phrase. The host can observe + feed to TextToSpeech. */
    private val _voicePrompt = MutableStateFlow<String?>(null)
    val voicePrompt: StateFlow<String?> = _voicePrompt.asStateFlow()

    // ───────────────────────────────────────────────────────────────────
    // Private state
    // ───────────────────────────────────────────────────────────────────

    private var lastLocation: Location? = null
    private var offRouteSinceMs: Long? = null
    private var offRouteFired: Boolean = false
    private var arrivalFired: Boolean = false
    private val firedThresholds = HashSet<VoiceThreshold>()

    /** Voice prompt distance bands. */
    private enum class VoiceThreshold(val meters: Double, val phrase: String) {
        KM1(1000.0, "In 1 kilometer, "),
        M500(500.0, "In 500 meters, "),
        M200(200.0, "In 200 meters, "),
        M50(50.0, "In 50 meters, "),
        NOW(0.0, "")
    }

    // ───────────────────────────────────────────────────────────────────
    // Public API
    // ───────────────────────────────────────────────────────────────────

    /** Replace the route (e.g. after a recompute). Resets all progress. */
    fun replaceRoute(newRoute: MapRoute) {
        route = newRoute
        _currentStepIndex.value = 0
        _distanceToNextManeuver.value = null
        _remainingDistance.value = newRoute.distance
        _etaSeconds.value = newRoute.duration
        _isOffRoute.value = false
        _hasArrived.value = false
        offRouteSinceMs = null
        offRouteFired = false
        arrivalFired = false
        firedThresholds.clear()
        _voicePrompt.value = null
    }

    /** Feed a fresh location update. Hot path — call on each LocationCallback tick. */
    fun updateLocation(location: Location) {
        // Heading: prefer hardware bearing, fallback to computed bearing.
        if (location.hasBearing()) {
            _userHeadingDegrees.value = location.bearing
        } else {
            lastLocation?.let { prev ->
                _userHeadingDegrees.value = bearing(
                    prev.latitude, prev.longitude,
                    location.latitude, location.longitude
                )
            }
        }

        advanceManeuverIfNeeded(location)
        updateDistanceToNextAndAnnounce(location)
        updateRemainingDistanceAndETA(location)
        updateOffRouteState(location)
        updateArrivalState(location)

        lastLocation = location
    }

    /** Called by the host once a voice prompt has been spoken — clears the flow. */
    fun consumeVoicePrompt() {
        _voicePrompt.value = null
    }

    // ───────────────────────────────────────────────────────────────────
    // Step / progress
    // ───────────────────────────────────────────────────────────────────

    private fun advanceManeuverIfNeeded(loc: Location) {
        val maneuvers = route.maneuvers
        if (maneuvers.isEmpty()) return
        val current = _currentStepIndex.value
        val nextIdx = current + 1
        if (nextIdx >= maneuvers.size) return

        val next = maneuvers[nextIdx]
        val distToNext = haversine(loc.latitude, loc.longitude, next.lat, next.lon)
        if (distToNext < advanceThresholdMeters) {
            advanceTo(nextIdx)
            return
        }
        if (current in maneuvers.indices) {
            val curr = maneuvers[current]
            val distToCurr = haversine(loc.latitude, loc.longitude, curr.lat, curr.lon)
            if (distToNext < distToCurr - 5.0) {
                advanceTo(nextIdx)
            }
        }
    }

    private fun advanceTo(newIndex: Int) {
        if (newIndex == _currentStepIndex.value) return
        _currentStepIndex.value = newIndex
        firedThresholds.clear()
    }

    private fun updateDistanceToNextAndAnnounce(loc: Location) {
        val maneuvers = route.maneuvers
        if (maneuvers.isEmpty()) {
            _distanceToNextManeuver.value = null
            return
        }
        val current = _currentStepIndex.value
        val targetIdx = min(current + 1, maneuvers.size - 1)
        val target = maneuvers[targetIdx]
        val dist = haversine(loc.latitude, loc.longitude, target.lat, target.lon)
        _distanceToNextManeuver.value = dist

        for (band in VoiceThreshold.values()) {
            if (band in firedThresholds) continue
            if (dist <= band.meters) {
                firedThresholds.add(band)
                _voicePrompt.value = band.phrase + target.instruction
            }
        }
    }

    private fun updateRemainingDistanceAndETA(loc: Location) {
        val pts = route.points
        if (pts.size < 2) {
            _remainingDistance.value = haversine(
                loc.latitude, loc.longitude,
                pts.lastOrNull()?.lat ?: loc.latitude,
                pts.lastOrNull()?.lon ?: loc.longitude
            )
            _etaSeconds.value = route.duration
            return
        }

        // Find nearest segment + projection.
        var bestIdx = 0
        var bestProj = pts[0]
        var bestDist = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val proj = projectPoint(loc.latitude, loc.longitude, a, b)
            val d = haversine(loc.latitude, loc.longitude, proj.lat, proj.lon)
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
                bestProj = proj
            }
        }

        // Sum from projection forward to end.
        var sum = haversine(bestProj.lat, bestProj.lon, pts[bestIdx + 1].lat, pts[bestIdx + 1].lon)
        for (i in bestIdx + 1 until pts.size - 1) {
            sum += haversine(pts[i].lat, pts[i].lon, pts[i + 1].lat, pts[i + 1].lon)
        }
        _remainingDistance.value = sum

        if (route.distance > 0.0) {
            val frac = max(0.0, min(1.0, sum / route.distance))
            _etaSeconds.value = route.duration * frac
        } else {
            _etaSeconds.value = 0.0
        }
    }

    private fun updateOffRouteState(loc: Location) {
        val pts = route.points
        if (pts.size < 2) return

        var minDist = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val proj = projectPoint(loc.latitude, loc.longitude, pts[i], pts[i + 1])
            val d = haversine(loc.latitude, loc.longitude, proj.lat, proj.lon)
            if (d < minDist) minDist = d
        }

        val now = System.currentTimeMillis()
        if (minDist > offRouteThresholdMeters) {
            if (offRouteSinceMs == null) offRouteSinceMs = now
            val sinceMs = now - (offRouteSinceMs ?: now)
            if (sinceMs >= offRouteStallSeconds * 1000 && !offRouteFired) {
                _isOffRoute.value = true
                offRouteFired = true
                onOffRoute?.invoke()
            }
        } else {
            offRouteSinceMs = null
            if (_isOffRoute.value) _isOffRoute.value = false
            offRouteFired = false
        }
    }

    private fun updateArrivalState(loc: Location) {
        val last = route.points.lastOrNull() ?: return
        val d = haversine(loc.latitude, loc.longitude, last.lat, last.lon)
        if (d <= arrivalThresholdMeters && !arrivalFired) {
            _hasArrived.value = true
            arrivalFired = true
            if (route.maneuvers.isNotEmpty()) {
                _currentStepIndex.value = route.maneuvers.size - 1
            }
            _voicePrompt.value = "You have arrived."
            onArrived?.invoke()
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // Geometry
    // ───────────────────────────────────────────────────────────────────

    /** Haversine great-circle distance in meters. */
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLam = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dPhi / 2).let { it * it } +
                Math.cos(phi1) * Math.cos(phi2) *
                Math.sin(dLam / 2).let { it * it }
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    /** Project (pLat,pLon) onto segment a→b, clamped to endpoints. */
    private fun projectPoint(
        pLat: Double, pLon: Double,
        a: RoutePoint, b: RoutePoint
    ): RoutePoint {
        val midLatRad = Math.toRadians((a.lat + b.lat) / 2.0)
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(midLatRad)

        val ax = a.lon * mPerDegLon
        val ay = a.lat * mPerDegLat
        val bx = b.lon * mPerDegLon
        val by = b.lat * mPerDegLat
        val px = pLon * mPerDegLon
        val py = pLat * mPerDegLat

        val dx = bx - ax
        val dy = by - ay
        val segLenSq = dx * dx + dy * dy
        if (segLenSq < 1e-6) return a

        var t = ((px - ax) * dx + (py - ay) * dy) / segLenSq
        t = max(0.0, min(1.0, t))

        val qx = ax + t * dx
        val qy = ay + t * dy
        return RoutePoint(lat = qy / mPerDegLat, lon = qx / mPerDegLon)
    }

    /** Initial bearing (degrees, 0=N, CW). */
    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLam = Math.toRadians(lon2 - lon1)
        val y = Math.sin(dLam) * Math.cos(phi2)
        val x = Math.cos(phi1) * Math.sin(phi2) -
                Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLam)
        val deg = Math.toDegrees(Math.atan2(y, x))
        return ((deg + 360.0) % 360.0).toFloat()
    }

    // ───────────────────────────────────────────────────────────────────
    // Formatting helpers (used by view layer)
    // ───────────────────────────────────────────────────────────────────

    companion object {
        fun formatDistance(meters: Double): String =
            if (meters >= 1000.0) String.format("%.1f km", meters / 1000.0)
            else String.format("%.0f m", meters)
    }

    /** Banner string: "In 200 m, turn right onto Rue X" */
    fun bannerInstruction(): String {
        if (_hasArrived.value) return "You have arrived"
        val maneuvers = route.maneuvers
        if (maneuvers.isEmpty()) return ""
        val targetIdx = min(_currentStepIndex.value + 1, maneuvers.size - 1)
        val target = maneuvers[targetIdx]
        val dist = _distanceToNextManeuver.value
        return if (dist != null && dist >= 50.0) {
            "In ${formatDistance(dist)}, ${target.instruction}"
        } else {
            target.instruction
        }
    }
}
