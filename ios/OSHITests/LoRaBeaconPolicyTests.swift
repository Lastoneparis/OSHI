import XCTest
import CryptoKit
@testable import OSHI

/// __LORA_POSITION_BEACON_2026_10_28__ the POLICY of the position beacon, and the proof
/// that iOS and Android answer the same way.
///
/// `LoRaPositionBeaconTests` covers the bytes a beacon puts on the air. This covers when
/// they are allowed to leave — the half that cannot be tested any other way, because every
/// branch is a rule about a clock, a radio and scarce airtime, and the alternative to a
/// pure function is standing outdoors with two radios and a stopwatch once per branch.
///
/// The rules are Meshtastic's device defaults, because this mesh is shared with its
/// clients: `position_broadcast_secs` = 900, smart-position suppression when stationary,
/// and a ceiling so a parked node still announces itself to a neighbour that joined later.
final class LoRaBeaconPolicyTests: XCTestCase {

    private let GENEVA = (lat: 46.2044, lon: 6.1432)
    private let NOW: Int64 = 1_793_200_000_000

    private func decide(enabled: Bool = true,
                        radioReady: Bool = true,
                        hasPermission: Bool = true,
                        lat: Double? = 46.2044,
                        lon: Double? = 6.1432,
                        nowMs: Int64? = nil,
                        lastSentAtMs: Int64 = 0,
                        lastLat: Double = 0,
                        lastLon: Double = 0) -> LoRaPositionBeacon.Decision {
        LoRaPositionBeacon.decide(enabled: enabled, radioReady: radioReady,
                                  hasPermission: hasPermission, lat: lat, lon: lon,
                                  nowMs: nowMs ?? NOW, lastSentAtMs: lastSentAtMs,
                                  lastLat: lastLat, lastLon: lastLon)
    }

    // MARK: the gates, in order

    func testTheSwitchIsCheckedBeforeAnythingElse() {
        // Not a style point. If `enabled` were checked after the authorization or the fix,
        // this would ask CoreLocation for a fine position on behalf of a feature the user
        // turned off — something a privacy label has to declare and a reviewer will find.
        XCTAssertEqual(.disabled,
                       decide(enabled: false, radioReady: false, hasPermission: false,
                              lat: nil, lon: nil))
    }

    func testNothingBeaconsWithoutARadioAPermissionOrAFix() {
        XCTAssertEqual(.noRadio, decide(radioReady: false))
        XCTAssertEqual(.noPermission, decide(hasPermission: false))
        XCTAssertEqual(.noFix, decide(lat: nil, lon: nil))
        XCTAssertEqual(.noFix, decide(lat: 46.2044, lon: nil))
    }

    func testAZeroZeroFixIsRefusedNotSent() {
        // 0,0 is what a receiver with no lock reports and what a simulator with no
        // location hands back. Beaconing it puts this node in the Gulf of Guinea on every
        // map that hears it, with nothing red anywhere.
        XCTAssertEqual(.noFix, decide(lat: 0, lon: 0))
        XCTAssertNotEqual(.noFix, decide(lat: 51.4779, lon: 0),
                          "0 longitude alone is Greenwich, a real place")
    }

    func testAnOutOfRangeCoordinateIsRefused() {
        XCTAssertEqual(.noFix, decide(lat: 91, lon: 6))
        XCTAssertEqual(.noFix, decide(lat: 46, lon: 181))
        XCTAssertEqual(.noFix, decide(lat: -90.1, lon: 6))
    }

    // MARK: airtime

    func testTheFirstBeaconOfASessionGoesOutImmediately() {
        XCTAssertEqual(.send, decide(lastSentAtMs: 0))
    }

    func testInsideTheIntervalNothingGoesOutHoweverFarWeMoved() {
        // Moving does NOT shorten the interval. One LongFast frame holds the channel for a
        // good fraction of a second and everyone in range hears it, so a phone in a car
        // must not be able to flood the mesh.
        XCTAssertEqual(.tooSoon,
                       decide(lastSentAtMs: NOW - LoRaPositionBeacon.intervalMs + 1,
                              lastLat: 48.8566, lastLon: 2.3522))   // 400 km away
    }

    func testPastTheIntervalANodeThatHasNotMovedStaysQuiet() {
        let lastSent = NOW - LoRaPositionBeacon.intervalMs - 1
        XCTAssertEqual(.stillHere,      // 30 m: the same place
                       decide(lastSentAtMs: lastSent, lastLat: 46.2041, lastLon: 6.1432))
        XCTAssertEqual(.send,           // 1 km: worth a frame
                       decide(lastSentAtMs: lastSent, lastLat: 46.2134, lastLon: 6.1432))
    }

    func testTheMovementGateHasACeilingSoAParkedNodeIsNotInvisible() {
        // THE POINT OF maxSilenceMs. Smart suppression with no ceiling makes a node that
        // never moves disappear from the map of every neighbour that joined or restarted
        // since its last beacon — the map is built from what was heard, not from a registry.
        XCTAssertEqual(.send,
                       decide(lastSentAtMs: NOW - LoRaPositionBeacon.maxSilenceMs,
                              lastLat: GENEVA.lat, lastLon: GENEVA.lon))
        XCTAssertEqual(.stillHere,
                       decide(lastSentAtMs: NOW - LoRaPositionBeacon.maxSilenceMs + 1,
                              lastLat: GENEVA.lat, lastLon: GENEVA.lon),
                       "one millisecond under the ceiling is still the movement rule")
    }

    func testAClockThatWentBackwardsCannotMuteTheBeacon() {
        // An NTP step, a restored backup or a hand-set date all produce now < lastSent.
        // Treating that as "too soon" would silence the node until the clock caught up —
        // potentially hours, with the switch showing ON.
        XCTAssertEqual(.send, decide(lastSentAtMs: NOW + 86_400_000))
    }

    func testTheIntervalMatchesMeshtasticsOwnDeviceDefault() {
        XCTAssertEqual(15 * 60 * 1000, LoRaPositionBeacon.intervalMs,
                       "position_broadcast_secs defaults to 900 s in Meshtastic's config")
        XCTAssertGreaterThan(LoRaPositionBeacon.maxSilenceMs, LoRaPositionBeacon.intervalMs,
                             "the ceiling must be LONGER than the interval or the movement gate is dead code")
        XCTAssertLessThan(LoRaPositionBeacon.tickSeconds * 1000,
                          Double(LoRaPositionBeacon.intervalMs),
                          "the tick has to be shorter than the interval or a beacon is late by a tick")
    }

    // MARK: - The cross-platform contract

    private struct V {
        let enabled: Bool, ready: Bool, perm: Bool
        let lat: Double?, lon: Double?
        let now: Int64, lastSent: Int64, lastLat: Double, lastLon: Double
    }

    /// Kept identical, row for row, to `LoRaBeaconCrossPlatformTest.vectors()` on Android.
    private func vectors() -> [V] {
        let I = LoRaPositionBeacon.intervalMs
        let MAX = LoRaPositionBeacon.maxSilenceMs
        let N = NOW
        return [
            // the gates, in order
            V(enabled: false, ready: false, perm: false, lat: nil, lon: nil, now: N, lastSent: 0, lastLat: 0, lastLon: 0),
            V(enabled: true, ready: false, perm: true, lat: 46.2044, lon: 6.1432, now: N, lastSent: 0, lastLat: 0, lastLon: 0),
            V(enabled: true, ready: true, perm: false, lat: 46.2044, lon: 6.1432, now: N, lastSent: 0, lastLat: 0, lastLon: 0),
            V(enabled: true, ready: true, perm: true, lat: nil, lon: nil, now: N, lastSent: 0, lastLat: 0, lastLon: 0),
            V(enabled: true, ready: true, perm: true, lat: 0, lon: 0, now: N, lastSent: 0, lastLat: 0, lastLon: 0),
            V(enabled: true, ready: true, perm: true, lat: 91, lon: 6, now: N, lastSent: 0, lastLat: 0, lastLon: 0),
            V(enabled: true, ready: true, perm: true, lat: 46, lon: 181, now: N, lastSent: 0, lastLat: 0, lastLon: 0),
            V(enabled: true, ready: true, perm: true, lat: -90.0001, lon: 6, now: N, lastSent: 0, lastLat: 0, lastLon: 0),
            // Greenwich: longitude 0 is a real place and must NOT be read as "no lock"
            V(enabled: true, ready: true, perm: true, lat: 51.4779, lon: 0, now: N, lastSent: 0, lastLat: 0, lastLon: 0),
            V(enabled: true, ready: true, perm: true, lat: 46.2044, lon: 6.1432, now: N, lastSent: 0, lastLat: 0, lastLon: 0),
            // the interval
            V(enabled: true, ready: true, perm: true, lat: 46.2044, lon: 6.1432, now: N, lastSent: N - I + 1, lastLat: 48.8566, lastLon: 2.3522),
            V(enabled: true, ready: true, perm: true, lat: 46.2044, lon: 6.1432, now: N, lastSent: N - I, lastLat: 46.2044, lastLon: 6.1432),
            // the movement gate, either side of 100 m at this latitude (1° ≈ 111.2 km)
            V(enabled: true, ready: true, perm: true, lat: 46.2044, lon: 6.1432, now: N, lastSent: N - I - 1, lastLat: 46.2044 - 0.00089, lastLon: 6.1432),
            V(enabled: true, ready: true, perm: true, lat: 46.2044, lon: 6.1432, now: N, lastSent: N - I - 1, lastLat: 46.2044 - 0.00091, lastLon: 6.1432),
            V(enabled: true, ready: true, perm: true, lat: 46.2044, lon: 6.1432, now: N, lastSent: N - I - 1, lastLat: 46.2134, lastLon: 6.1432),
            // the ceiling
            V(enabled: true, ready: true, perm: true, lat: 46.2044, lon: 6.1432, now: N, lastSent: N - MAX, lastLat: 46.2044, lastLon: 6.1432),
            V(enabled: true, ready: true, perm: true, lat: 46.2044, lon: 6.1432, now: N, lastSent: N - MAX + 1, lastLat: 46.2044, lastLon: 6.1432),
            // a clock that went backwards
            V(enabled: true, ready: true, perm: true, lat: 46.2044, lon: 6.1432, now: N, lastSent: N + 86_400_000, lastLat: 46.2044, lastLon: 6.1432),
            // the switch wins over a live session
            V(enabled: false, ready: true, perm: true, lat: 46.2044, lon: 6.1432, now: N, lastSent: N - 1, lastLat: 46.2044, lastLon: 6.1432),
            // southern and eastern signs, which an sfixed32 bug would get wrong
            V(enabled: true, ready: true, perm: true, lat: -33.8688, lon: 151.2093, now: N, lastSent: N - I - 1, lastLat: -33.8688, lastLon: 151.2093),
            V(enabled: true, ready: true, perm: true, lat: -33.8688, lon: -70.6693, now: N, lastSent: N - I - 1, lastLat: -33.8688, lastLon: 151.2093),
        ]
    }

    private func d(_ v: Double?) -> String {
        guard let v else { return "na" }
        return String(format: "%.7f", v)
    }

    private func b(_ v: Bool) -> String { v ? "1" : "0" }

    private func transcript() -> String {
        vectors().map { v in
            let decision = LoRaPositionBeacon.decide(
                enabled: v.enabled, radioReady: v.ready, hasPermission: v.perm,
                lat: v.lat, lon: v.lon, nowMs: v.now, lastSentAtMs: v.lastSent,
                lastLat: v.lastLat, lastLon: v.lastLon)
            return "\(b(v.enabled))\(b(v.ready))\(b(v.perm))|\(d(v.lat))|\(d(v.lon))|"
                + "\(v.now)|\(v.lastSent)|\(d(v.lastLat))|\(d(v.lastLon))=>\(decision.rawValue)"
        }.joined(separator: "\n")
    }

    /// SHA-256 of the transcript. The SAME literal appears in Android's
    /// `LoRaBeaconCrossPlatformTest.POLICY_DIGEST`.
    private let policyDigest = "28bbeb82edc14c3c8aff1afa9792a18077b9a0b70e6ca88481a21fd7d6774f1d"

    /// The SAME table of inputs on both platforms, reduced to ONE digest.
    ///
    /// Why this and not a pair of tests with matching names: `golden-file-beats-shared-test-names`
    /// is a lesson this repository has already paid for — two cores with identically named
    /// tests produced "Donateur 310" on Android and "253" on iOS, and nothing was red. A
    /// test name is a claim about intent; it says nothing about the value that came out.
    ///
    /// Each row is formatted with its inputs AND the decision they produced, so:
    ///
    ///  - a rule that changes on one platform only ⇒ the digests move apart, one goes red;
    ///  - an INPUT edited on one platform only ⇒ the same, because the inputs are hashed;
    ///  - a rule that changes on purpose ⇒ both go red together and the constant is updated
    ///    once, deliberately, in two files in the same commit.
    func testThePolicyDigestMatchesTheOneAndroidComputes() {
        let t = transcript()
        XCTAssertEqual(21, t.split(separator: "\n").count,
                       "the table must not lose or gain rows silently")
        let hex = SHA256.hash(data: Data(t.utf8)).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(policyDigest, hex,
                       "iOS and Android disagree about the beacon policy, OR a rule changed "
                       + "on purpose. In the second case update BOTH this constant and "
                       + "LoRaBeaconCrossPlatformTest.POLICY_DIGEST, in the same commit.\n" + t)
    }
}
