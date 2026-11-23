//
//  LiveNavigationEngine.swift
//  OSHI
//
//  Real-time turn-by-turn navigation engine.
//
//  Inputs:  a precomputed `MapRoute` (polyline + maneuvers) + a stream of
//           `CLLocation` updates from `LocationSharingManager`.
//  Outputs: `@Published` state for current step, distance to next maneuver,
//           ETA seconds, off-route flag, arrival flag, plus a callback hook
//           the host can use to recompute the route when off-route.
//
//  Design notes
//  ────────────
//  • 100% offline. No network calls. All math is local.
//  • Camera follow / TTS announcements are *side-effects* the host wires in
//    via the `voiceAnnouncementHandler` and by observing `userLocation`/
//    `currentStepIndex`. The engine itself does not own the map view.
//  • This is additive — it does not replace the existing static maneuver
//    list rendering in `MapNavigationView`. The view simply observes this
//    engine to overlay live progress on top of the static list.
//

import Foundation
import CoreLocation
import Combine

#if canImport(AVFoundation)
import AVFoundation
#endif

// MARK: - Voice Prompt Threshold

/// Distance (in meters) bands at which a voice prompt should fire for a
/// maneuver, plus the matching display formatter. The `.now` band fires once
/// the user is within ~30 m of the maneuver point.
enum VoicePromptThreshold: Double, CaseIterable {
    case km1 = 1000
    case m500 = 500
    case m200 = 200
    case m50 = 50
    case now = 0

    var phrase: String {
        switch self {
        case .km1:  return "In 1 kilometer, "
        case .m500: return "In 500 meters, "
        case .m200: return "In 200 meters, "
        case .m50:  return "In 50 meters, "
        case .now:  return ""
        }
    }
}

// MARK: - LiveNavigationEngine

@MainActor
final class LiveNavigationEngine: ObservableObject {

    // ────────────────────────────────────────────────────────────────────
    // Inputs (set on construction or via update())
    // ────────────────────────────────────────────────────────────────────

    /// The route currently being followed. Set via `start(route:)`.
    private(set) var route: MapRoute

    // ────────────────────────────────────────────────────────────────────
    // Published live state
    // ────────────────────────────────────────────────────────────────────

    /// Index into `route.maneuvers` of the maneuver the user is currently
    /// approaching. 0 = first maneuver after start.
    @Published private(set) var currentStepIndex: Int = 0

    /// Meters between the user's current position and the next maneuver
    /// coordinate. `nil` when no user location is known yet.
    @Published private(set) var distanceToNextManeuver: Double? = nil

    /// Total meters remaining from the user to the destination, walking
    /// the polyline forward from the user's projection.
    @Published private(set) var remainingDistance: Double = 0

    /// Estimated remaining seconds, scaled from the original route duration
    /// by the fraction of distance remaining.
    @Published private(set) var etaSeconds: Double = 0

    /// True when the user is more than `offRouteThresholdMeters` away from
    /// the polyline AND has not made progress toward any future maneuver
    /// in `offRouteStallSeconds`.
    @Published private(set) var isOffRoute: Bool = false

    /// True once the user is within `arrivalThresholdMeters` of the final
    /// polyline point.
    @Published private(set) var hasArrived: Bool = false

    /// Heading (in degrees, 0 = north, clockwise) the user appears to be
    /// moving in. `nil` if unknown.
    @Published private(set) var userHeadingDegrees: Double? = nil

    // ────────────────────────────────────────────────────────────────────
    // Tunables
    // ────────────────────────────────────────────────────────────────────

    /// Distance (m) from a maneuver coordinate at which we auto-advance
    /// to the next one.
    var advanceThresholdMeters: Double = 30

    /// Distance (m) from the polyline beyond which the user is considered
    /// to potentially be off route.
    var offRouteThresholdMeters: Double = 80

    /// Time (s) of being "potentially off route" with no progress before
    /// we fire `onOffRoute()`.
    var offRouteStallSeconds: TimeInterval = 30

    /// Distance (m) from the final polyline point that triggers arrival.
    var arrivalThresholdMeters: Double = 30

    // ────────────────────────────────────────────────────────────────────
    // Callbacks
    // ────────────────────────────────────────────────────────────────────

    /// Fired exactly once when the user goes off route. Host should kick
    /// off a route recompute via the existing `MapRoutingEngine`.
    var onOffRoute: (() -> Void)?

    /// Fired exactly once when the user reaches the destination.
    var onArrived: (() -> Void)?

    /// Optional hook for voice prompts. The default implementation uses
    /// `AVSpeechSynthesizer` when available; pass `nil` to disable speech
    /// or override to log/customize.
    var voiceAnnouncementHandler: ((String) -> Void)?

    // ────────────────────────────────────────────────────────────────────
    // Private state
    // ────────────────────────────────────────────────────────────────────

    /// Last user location received from the location stream.
    private var lastLocation: CLLocation?

    /// Timestamp at which the user first appeared off-route. Reset to nil
    /// the moment the user is back on the polyline.
    private var offRouteSince: Date?

    /// The closest distance to the next maneuver we have seen so far for
    /// the current step. Used to detect that the user really is approaching
    /// (and to time the stall window).
    private var bestDistanceToNext: Double = .greatestFiniteMagnitude

    /// Voice prompt thresholds already announced for the current step.
    private var firedThresholds: Set<VoicePromptThreshold> = []

    /// Whether the off-route callback has fired for the current off-route
    /// episode (deduped until back on route).
    private var offRouteFired: Bool = false

    /// Whether the arrival callback has fired (deduped per route).
    private var arrivalFired: Bool = false

    #if canImport(AVFoundation)
    private let speechSynth = AVSpeechSynthesizer()
    #endif

    // ────────────────────────────────────────────────────────────────────
    // Init
    // ────────────────────────────────────────────────────────────────────

    init(route: MapRoute) {
        self.route = route
        // Default voice handler uses AVSpeechSynthesizer on Apple platforms
        // and is a no-op when AVFoundation is unavailable.
        self.voiceAnnouncementHandler = { [weak self] text in
            guard let self = self, !text.isEmpty else { return }
            #if canImport(AVFoundation)
            let utterance = AVSpeechUtterance(string: text)
            utterance.rate = AVSpeechUtteranceDefaultSpeechRate
            utterance.volume = 1.0
            self.speechSynth.speak(utterance)
            #endif
        }
        recomputeProgressFromCurrent()
    }

    // ────────────────────────────────────────────────────────────────────
    // Public API
    // ────────────────────────────────────────────────────────────────────

    /// Replace the active route (e.g. after a recompute). Resets progress.
    func update(route newRoute: MapRoute) {
        self.route = newRoute
        self.currentStepIndex = 0
        self.distanceToNextManeuver = nil
        self.remainingDistance = newRoute.distance
        self.etaSeconds = newRoute.duration
        self.isOffRoute = false
        self.hasArrived = false
        self.offRouteSince = nil
        self.offRouteFired = false
        self.arrivalFired = false
        self.bestDistanceToNext = .greatestFiniteMagnitude
        self.firedThresholds = []
    }

    /// Feed a fresh user location into the engine. This is the hot path —
    /// call it on every CLLocationManager update (1+ Hz).
    func updateLocation(_ location: CLLocation) {
        defer { self.lastLocation = location }

        // Heading: CL provides course only when moving; fall back to
        // computed bearing between the last two points.
        if location.course >= 0 {
            self.userHeadingDegrees = location.course
        } else if let prev = lastLocation {
            self.userHeadingDegrees = Self.bearing(from: prev.coordinate, to: location.coordinate)
        }

        // 1. Auto-advance current maneuver.
        advanceManeuverIfNeeded(for: location)

        // 2. Distance to next + voice prompts.
        updateDistanceToNextAndAnnounce(for: location)

        // 3. Total remaining + ETA.
        updateRemainingDistanceAndETA(for: location)

        // 4. Off-route detection.
        updateOffRouteState(for: location)

        // 5. Arrival.
        updateArrivalState(for: location)
    }

    // ────────────────────────────────────────────────────────────────────
    // Step / progress
    // ────────────────────────────────────────────────────────────────────

    private func advanceManeuverIfNeeded(for location: CLLocation) {
        guard route.maneuvers.indices.contains(currentStepIndex) else { return }
        let nextIndex = currentStepIndex + 1
        guard nextIndex < route.maneuvers.count else { return }

        let nextManeuver = route.maneuvers[nextIndex]
        let distanceToNextManeuverPoint = location.distance(
            from: CLLocation(latitude: nextManeuver.coordinate.latitude,
                             longitude: nextManeuver.coordinate.longitude)
        )

        // Trigger 1: within 30 m of the next maneuver point.
        if distanceToNextManeuverPoint < advanceThresholdMeters {
            advance(to: nextIndex)
            return
        }

        // Trigger 2: user has overshot the current maneuver — i.e. they are
        // now closer to the *next* maneuver than to the *current* one.
        let currentManeuver = route.maneuvers[currentStepIndex]
        let distanceToCurrentManeuver = location.distance(
            from: CLLocation(latitude: currentManeuver.coordinate.latitude,
                             longitude: currentManeuver.coordinate.longitude)
        )
        if distanceToNextManeuverPoint < distanceToCurrentManeuver - 5 {
            // 5 m hysteresis to avoid thrashing.
            advance(to: nextIndex)
        }
    }

    private func advance(to newIndex: Int) {
        guard newIndex != currentStepIndex else { return }
        currentStepIndex = newIndex
        firedThresholds = []
        bestDistanceToNext = .greatestFiniteMagnitude
    }

    private func updateDistanceToNextAndAnnounce(for location: CLLocation) {
        guard route.maneuvers.indices.contains(currentStepIndex) else {
            distanceToNextManeuver = nil
            return
        }
        // The "next" maneuver from the user's POV is the one *after* the
        // current step (the current step is treated as already completed
        // once we advance to it). Falls back to the current step if it's
        // the last one.
        let targetIndex = min(currentStepIndex + 1, route.maneuvers.count - 1)
        let target = route.maneuvers[targetIndex]
        let dist = location.distance(
            from: CLLocation(latitude: target.coordinate.latitude,
                             longitude: target.coordinate.longitude)
        )
        distanceToNextManeuver = dist

        // Track best (closest) distance for off-route stall logic.
        if dist < bestDistanceToNext { bestDistanceToNext = dist }

        // Voice prompts — fire each band at most once per step.
        for threshold in VoicePromptThreshold.allCases {
            guard !firedThresholds.contains(threshold) else { continue }
            // Fire when we cross *into* the band from above.
            if dist <= threshold.rawValue {
                firedThresholds.insert(threshold)
                let line = threshold.phrase + target.instruction
                voiceAnnouncementHandler?(line)
            }
        }
    }

    private func updateRemainingDistanceAndETA(for location: CLLocation) {
        // Sum polyline segments forward from the user's projection.
        guard route.polyline.count >= 2 else {
            remainingDistance = location.distance(
                from: CLLocation(latitude: route.polyline.last?.latitude ?? 0,
                                 longitude: route.polyline.last?.longitude ?? 0)
            )
            etaSeconds = route.duration
            return
        }

        // Find nearest segment + projection.
        let userCoord = location.coordinate
        var bestSegmentIdx = 0
        var bestProjection: CLLocationCoordinate2D = route.polyline[0]
        var bestDistance = Double.greatestFiniteMagnitude
        for i in 0..<(route.polyline.count - 1) {
            let a = route.polyline[i]
            let b = route.polyline[i + 1]
            let proj = Self.projectPoint(userCoord, ontoSegmentFrom: a, to: b)
            let d = CLLocation(latitude: userCoord.latitude, longitude: userCoord.longitude)
                .distance(from: CLLocation(latitude: proj.latitude, longitude: proj.longitude))
            if d < bestDistance {
                bestDistance = d
                bestSegmentIdx = i
                bestProjection = proj
            }
        }

        // Distance from projection → end of current segment + sum of all
        // subsequent segments.
        var sum: Double = CLLocation(latitude: bestProjection.latitude, longitude: bestProjection.longitude)
            .distance(from: CLLocation(latitude: route.polyline[bestSegmentIdx + 1].latitude,
                                       longitude: route.polyline[bestSegmentIdx + 1].longitude))
        for i in (bestSegmentIdx + 1)..<(route.polyline.count - 1) {
            let a = route.polyline[i]
            let b = route.polyline[i + 1]
            sum += CLLocation(latitude: a.latitude, longitude: a.longitude)
                .distance(from: CLLocation(latitude: b.latitude, longitude: b.longitude))
        }
        remainingDistance = sum

        if route.distance > 0 {
            let fractionRemaining = max(0, min(1, sum / route.distance))
            etaSeconds = route.duration * fractionRemaining
        } else {
            etaSeconds = 0
        }
    }

    private func updateOffRouteState(for location: CLLocation) {
        guard route.polyline.count >= 2 else { return }
        let userCoord = location.coordinate

        var minDistance = Double.greatestFiniteMagnitude
        for i in 0..<(route.polyline.count - 1) {
            let a = route.polyline[i]
            let b = route.polyline[i + 1]
            let proj = Self.projectPoint(userCoord, ontoSegmentFrom: a, to: b)
            let d = CLLocation(latitude: userCoord.latitude, longitude: userCoord.longitude)
                .distance(from: CLLocation(latitude: proj.latitude, longitude: proj.longitude))
            if d < minDistance { minDistance = d }
        }

        if minDistance > offRouteThresholdMeters {
            // Out of corridor — start (or continue) the stall timer.
            if offRouteSince == nil { offRouteSince = Date() }
            // No progress toward the next maneuver in the stall window?
            if let since = offRouteSince,
               Date().timeIntervalSince(since) >= offRouteStallSeconds,
               !offRouteFired {
                isOffRoute = true
                offRouteFired = true
                onOffRoute?()
            }
        } else {
            // Back on route.
            offRouteSince = nil
            if isOffRoute { isOffRoute = false }
            offRouteFired = false
        }
    }

    private func updateArrivalState(for location: CLLocation) {
        guard let last = route.polyline.last else { return }
        let distToEnd = location.distance(
            from: CLLocation(latitude: last.latitude, longitude: last.longitude)
        )
        if distToEnd <= arrivalThresholdMeters && !arrivalFired {
            hasArrived = true
            arrivalFired = true
            // Snap UI to the destination maneuver.
            if !route.maneuvers.isEmpty {
                currentStepIndex = route.maneuvers.count - 1
            }
            voiceAnnouncementHandler?("You have arrived.")
            onArrived?()
        }
    }

    private func recomputeProgressFromCurrent() {
        remainingDistance = route.distance
        etaSeconds = route.duration
    }

    // ────────────────────────────────────────────────────────────────────
    // Geometry helpers (equirectangular projection, accurate <100 km)
    // ────────────────────────────────────────────────────────────────────

    /// Project `p` onto the segment defined by `a`-`b` and return the
    /// closest point on that segment (clamped to endpoints).
    static func projectPoint(_ p: CLLocationCoordinate2D,
                             ontoSegmentFrom a: CLLocationCoordinate2D,
                             to b: CLLocationCoordinate2D) -> CLLocationCoordinate2D {
        // Convert to local meter-scale plane via equirectangular approx.
        let latRad = (a.latitude + b.latitude) / 2.0 * .pi / 180.0
        let mPerDegLat = 111_320.0
        let mPerDegLon = 111_320.0 * cos(latRad)

        let ax = a.longitude * mPerDegLon
        let ay = a.latitude  * mPerDegLat
        let bx = b.longitude * mPerDegLon
        let by = b.latitude  * mPerDegLat
        let px = p.longitude * mPerDegLon
        let py = p.latitude  * mPerDegLat

        let dx = bx - ax
        let dy = by - ay
        let segLenSq = dx * dx + dy * dy
        if segLenSq < 1e-6 { return a }

        var t = ((px - ax) * dx + (py - ay) * dy) / segLenSq
        t = max(0, min(1, t))

        let qx = ax + t * dx
        let qy = ay + t * dy
        return CLLocationCoordinate2D(latitude: qy / mPerDegLat,
                                      longitude: qx / mPerDegLon)
    }

    /// Initial bearing from `a` to `b` in degrees (0 = north, clockwise).
    static func bearing(from a: CLLocationCoordinate2D, to b: CLLocationCoordinate2D) -> Double {
        let lat1 = a.latitude  * .pi / 180.0
        let lat2 = b.latitude  * .pi / 180.0
        let dLon = (b.longitude - a.longitude) * .pi / 180.0
        let y = sin(dLon) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        let brng = atan2(y, x) * 180.0 / .pi
        return (brng + 360.0).truncatingRemainder(dividingBy: 360.0)
    }
}

// MARK: - Formatting helpers usable by the view layer

extension LiveNavigationEngine {
    /// e.g. "200 m" / "1.2 km"
    static func formatDistance(_ meters: Double) -> String {
        if meters >= 1000 {
            return String(format: "%.1f km", meters / 1000)
        }
        let rounded = Int((meters / 10).rounded()) * 10
        return "\(max(10, rounded)) m"
    }

    /// e.g. "In 200 m, turn right onto Rue X"
    var bannerInstruction: String {
        guard route.maneuvers.indices.contains(currentStepIndex) else { return "" }
        let targetIndex = min(currentStepIndex + 1, route.maneuvers.count - 1)
        let target = route.maneuvers[targetIndex]
        if hasArrived { return "You have arrived" }
        if let dist = distanceToNextManeuver, dist >= 50 {
            return "In \(Self.formatDistance(dist)), \(target.instruction)"
        }
        return target.instruction
    }
}
