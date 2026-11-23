import Foundation
import CoreLocation

/// __LORA_POSITION_BEACON_2026_10_28__ The CALLER of `MeshtasticManager.broadcastPosition`
/// — what makes "share my position over LoRa, the way Meshtastic does" something a person
/// can actually switch on.
///
/// Until this existed, both phones could encode and transmit a `Position` frame and neither
/// ever did: `grep broadcastPosition` found the function, its unit test, and no non-test
/// caller on either platform. A send path with no caller is a capability on paper; the
/// receive half (the orange pins `OfflineMapView` draws, `handlePosition`) had nothing of
/// ours to show.
///
/// ## Why the policy is a pure function
///
/// `decide` takes every input as a plain value and returns a `Decision`. It needs no radio,
/// no GPS, no clock and no `CLLocationManager`, which is the only way the airtime rules
/// below are checkable at all — the alternative is standing outdoors with two radios and a
/// stopwatch once per branch. `tick` does nothing but gather those values and obey.
///
/// It is also the half that has to agree with Android *exactly*, and shared test NAMES do
/// not prove two cores answer the same way. Both platforms run the same ordered vector
/// table through `decide` and compare one digest — see `LoRaBeaconPolicyTests`.
///
/// ## The airtime rules, and where they come from
///
/// LoRa is not a network with spare capacity. One LongFast frame holds the channel for a
/// good fraction of a second and every node in range hears it, so an eager beacon costs
/// everyone else their messages. Meshtastic's device defaults are the reference:
/// `position_broadcast_secs` is 900 (15 min) and "smart position" suppresses a beacon that
/// has not moved. This mirrors both, plus a ceiling:
///
///  - `intervalMs` — 15 minutes, the floor between two beacons.
///  - `minMoveM` — under 100 m is the same place, not worth a frame…
///  - `maxSilenceMs` — …but after an hour a stationary node beacons anyway, because a
///    neighbour that joined or restarted since has no other way to learn it is there.
///    Smart suppression with no ceiling makes a parked node invisible.
///
/// ## What this deliberately does NOT do
///
/// It holds no location subscription and asks for no background mode, so it beacons only
/// while the app is in the foreground. Beaconing from the background needs `always`
/// authorization and a declared background mode — a product decision, not a porting gap.
/// The switch's footer says so in all 34 languages.
///
/// It never beacons without `MeshtasticManager.positionBeaconKey`, false on a fresh install
/// and persisted. `broadcastPosition`'s own header says what that switch exposes: a public
/// cleartext broadcast on a channel whose key is published in Meshtastic's source, to every
/// radio in range — tens of kilometres, to people the owner has never met and cannot list.
final class LoRaPositionBeacon {

    static let shared = LoRaPositionBeacon()

    /// The floor between two beacons. Meshtastic's `position_broadcast_secs` default.
    static let intervalMs: Int64 = 15 * 60 * 1000

    /// Below this, a new fix is the same place and does not deserve airtime.
    static let minMoveM: Double = 100

    /// __DESKTOP_LORA_WRITE_ONCE_2026_10_29__ Effectively never. A fixed position is
    /// written into the node's CONFIGURATION; the firmware then announces on its own
    /// schedule (at most every six hours for a pinned node). Re-sending the same
    /// coordinates on a timer tells the radio nothing it does not know and writes its
    /// FLASH, so the ceiling is a year: a position needs writing when it CHANGES.
    static let maxSilenceMs: Int64 = 365 * 24 * 60 * 60 * 1000

    /// How often `tick` asks the questions. NOT how often it beacons.
    static let tickSeconds: TimeInterval = 60

    /// Why a tick did or did not put a frame on the air.
    ///
    /// An enum rather than a `Bool` so the reason survives to the log and could be shown:
    /// "my position isn't sharing" has six causes and five of them are the user's to fix.
    /// The raw values are the Android `Decision` names, because the cross-platform check
    /// compares them as strings.
    enum Decision: String {
        /// Beacon now.
        case send = "SEND"
        /// The switch is off. The default, and the only state a fresh install has.
        case disabled = "DISABLED"
        /// No radio attached, or attached and not finished setting up.
        case noRadio = "NO_RADIO"
        /// Location not authorized — nothing to send.
        case noPermission = "NO_PERMISSION"
        /// No fix, or a 0/0 that means "no lock".
        case noFix = "NO_FIX"
        /// Inside `intervalMs` of the last beacon.
        case tooSoon = "TOO_SOON"
        /// Past the interval, moved under `minMoveM`, not yet `maxSilenceMs`.
        case stillHere = "STILL_HERE"
    }

    private static let earthRadiusM = 6_371_000.0

    /// Haversine metres. Pure; no `CLLocation.distance(from:)`, so the vector table above
    /// runs identically on a machine with no CoreLocation and matches Android's
    /// `CheckInEtaEstimator.greatCircleMetres` term for term.
    static func greatCircleMetres(_ fromLat: Double, _ fromLon: Double,
                                  _ toLat: Double, _ toLon: Double) -> Double {
        let dLat = (toLat - fromLat) * .pi / 180
        let dLon = (toLon - fromLon) * .pi / 180
        let a = sin(dLat / 2) * sin(dLat / 2)
            + cos(fromLat * .pi / 180) * cos(toLat * .pi / 180) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * earthRadiusM * asin(min(1.0, sqrt(a)))
    }

    /// The whole policy, as a value. Mirrors Android `LoRaPositionBeacon.decide`.
    ///
    /// - Parameter lastSentAtMs: 0 when nothing has been beaconed this session.
    static func decide(enabled: Bool,
                       radioReady: Bool,
                       hasPermission: Bool,
                       lat: Double?,
                       lon: Double?,
                       nowMs: Int64,
                       lastSentAtMs: Int64,
                       lastLat: Double,
                       lastLon: Double,
                       intervalMs: Int64 = LoRaPositionBeacon.intervalMs,
                       minMoveM: Double = LoRaPositionBeacon.minMoveM,
                       maxSilenceMs: Int64 = LoRaPositionBeacon.maxSilenceMs) -> Decision {
        // Order matters. The switch is checked FIRST so that turning it off stops the
        // authorization and location questions too: asking for a fine fix on behalf of a
        // feature the user disabled is something a privacy label has to declare.
        guard enabled else { return .disabled }
        guard radioReady else { return .noRadio }
        guard hasPermission else { return .noPermission }
        guard let lat, let lon else { return .noFix }
        // 0/0 is what a receiver with no lock reports and what a simulator with no
        // location hands back. Sending it puts this node in the Gulf of Guinea on every
        // map that hears it — `broadcastPosition` refuses it too; this gate is the only
        // one that can say WHY.
        guard !(lat == 0 && lon == 0) else { return .noFix }
        guard lat >= -90, lat <= 90, lon >= -180, lon <= 180 else { return .noFix }

        guard lastSentAtMs > 0 else { return .send }
        let since = nowMs - lastSentAtMs
        // A clock that went backwards (NTP step, restored backup, hand-set date) must not
        // be able to mute the beacon until the clock catches up.
        guard since >= 0 else { return .send }
        if since >= maxSilenceMs { return .send }
        if since < intervalMs { return .tooSoon }
        let moved = greatCircleMetres(lastLat, lastLon, lat, lon)
        return moved < minMoveM ? .stillHere : .send
    }

    // MARK: - The loop

    private var timer: Timer?
    private var lastSentAtMs: Int64 = 0
    private var lastLat: Double = 0
    private var lastLon: Double = 0

    /// The last `decide` answer, for the log and for anything that wants to explain itself.
    private(set) var lastDecision: Decision = .disabled

    private var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// Start the loop. Idempotent; called from the app's startup.
    ///
    /// The timer runs whatever the switch says, because `decide` returning `.disabled` is
    /// how "off" is implemented — and because a timer that is torn down and rebuilt on a
    /// `UserDefaults` change is one more thing that can be left in the wrong state. Nothing
    /// is transmitted and no location is requested while the switch is off.
    func install() {
        guard timer == nil else { return }
        // `.common` so the beacon keeps ticking while a scroll or a menu tracking loop is
        // running — the default run-loop mode stops for both, which on macOS means a
        // beacon that stops whenever a menu is open.
        let t = Timer(timeInterval: Self.tickSeconds, repeats: true) { [weak self] _ in
            self?.tick()
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
        tick()
    }

    /// Turning the switch ON beacons AT ONCE rather than up to a tick later.
    ///
    /// Without this the switch looks broken for a minute: the person flips it, watches the
    /// other phone's map and nothing appears. The counter is cleared on the same
    /// transition, so the first beacon of a session is never suppressed as `.tooSoon` by a
    /// position sent before the switch was last turned off.
    func switchTurnedOn() {
        lastSentAtMs = 0
        tick()
    }

    /// One pass. The cheap gates are evaluated BEFORE CoreLocation is touched, by calling
    /// `decide` with a nil fix: that answers `.noFix` only once everything else has passed,
    /// which is exactly when asking for a fix is justified.
    func tick(radio: MeshtasticManager = .shared,
              location: LocationSharingManager = .shared) {
        let enabled = UserDefaults.standard.bool(forKey: MeshtasticManager.positionBeaconKey)
        let authorized = location.authorizationStatus == .authorizedAlways
            || location.authorizationStatus == .authorizedWhenInUse
        let gate = Self.decide(enabled: enabled, radioReady: radio.isReady,
                               hasPermission: authorized, lat: nil, lon: nil,
                               nowMs: nowMs, lastSentAtMs: lastSentAtMs,
                               lastLat: lastLat, lastLon: lastLon)
        guard gate == .noFix else { lastDecision = gate; return }

        location.getCurrentLocation { [weak self] result in
            guard let self else { return }
            let coord = try? result.get().coordinate
            let d = Self.decide(enabled: enabled, radioReady: radio.isReady,
                                hasPermission: true,
                                lat: coord?.latitude, lon: coord?.longitude,
                                nowMs: self.nowMs, lastSentAtMs: self.lastSentAtMs,
                                lastLat: self.lastLat, lastLon: self.lastLon)
            self.lastDecision = d
            guard d == .send, let coord else { return }
            // __LORA_FIXED_POSITION_2026_10_28__ The ADMIN path, not `broadcastPosition`.
            // A client POSITION_APP broadcast is accepted by the radio and reaches nobody —
            // measured over the air with two radios. The interval below is no longer about
            // airtime: this writes the node's CONFIGURATION, so the floor is what keeps a
            // timer from rewriting flash.
            if radio.setFixedPosition(latitude: coord.latitude, longitude: coord.longitude) {
                self.lastSentAtMs = self.nowMs
                self.lastLat = coord.latitude
                self.lastLon = coord.longitude
            }
        }
    }

    /// Forget what was last beaconed. For tests.
    func resetForTest() {
        lastSentAtMs = 0; lastLat = 0; lastLon = 0; lastDecision = .disabled
    }
}
