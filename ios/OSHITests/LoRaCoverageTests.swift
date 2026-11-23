import XCTest
@testable import OSHI

final class LoRaCoverageTests: XCTestCase {

    /// THE test. A relayed node's RSSI belongs to the relay, not to it, so no
    /// ring may be drawn — a circle built from someone else's measurement would
    /// be confidently wrong.
    func testRelayedNodeGetsNoRing() {
        XCTAssertNil(LoRaCoverage.radiusMeters(rssi: -70, hopsAway: 1))
        XCTAssertNil(LoRaCoverage.radiusMeters(rssi: -70, hopsAway: 3))
        XCTAssertEqual(LoRaCoverage.fillOpacity(rssi: -70, hopsAway: 1), 0)
    }

    func testDirectNodeGetsARing() {
        XCTAssertNotNil(LoRaCoverage.radiusMeters(rssi: -70, hopsAway: 0))
        XCTAssertNotNil(LoRaCoverage.radiusMeters(rssi: -70, hopsAway: nil))
    }

    func testNeverHeardGetsNoRing() {
        XCTAssertNil(LoRaCoverage.radiusMeters(rssi: nil, hopsAway: 0))
    }

    func testSignalBelowTheUsableFloorGetsNoRing() {
        XCTAssertNil(LoRaCoverage.radiusMeters(rssi: -140, hopsAway: 0))
    }

    func testImplausiblePositiveRSSIIsRejected() {
        // RSSI is negative by definition; a positive value is a parse error and
        // must not become a map annotation.
        XCTAssertNil(LoRaCoverage.radiusMeters(rssi: 20, hopsAway: 0))
    }

    /// A weaker link means a bigger, vaguer ring — the ring expresses
    /// uncertainty about reach, so it must grow as signal falls.
    func testWeakerSignalGivesALargerRing() {
        let strong = LoRaCoverage.radiusMeters(rssi: -40, hopsAway: 0)!
        let medium = LoRaCoverage.radiusMeters(rssi: -85, hopsAway: 0)!
        let weak = LoRaCoverage.radiusMeters(rssi: -120, hopsAway: 0)!
        XCTAssertLessThan(strong, medium)
        XCTAssertLessThan(medium, weak)
    }

    func testRadiusStaysWithinWhatWeWillClaim() {
        for rssi in stride(from: -125, through: -30, by: 1) {
            let radius = LoRaCoverage.radiusMeters(rssi: rssi, hopsAway: 0)!
            XCTAssertGreaterThanOrEqual(radius, LoRaCoverage.minRadius)
            XCTAssertLessThanOrEqual(radius, LoRaCoverage.maxRadius)
        }
    }

    /// A large speculative ring must be fainter than a small confident one, or
    /// the least trustworthy drawing on the map would be the loudest.
    func testWiderRingsAreFainter() {
        let tight = LoRaCoverage.fillOpacity(rssi: -35, hopsAway: 0)
        let wide = LoRaCoverage.fillOpacity(rssi: -120, hopsAway: 0)
        XCTAssertGreaterThan(tight, wide)
        XCTAssertGreaterThan(wide, 0)
    }

    /// The Android file must agree to the centimetre. These numbers were
    /// computed from the formula independently of both implementations, so the
    /// two platforms are pinned to the same third-party arithmetic rather than
    /// to each other — which is what stops one of them drifting and the other
    /// being "corrected" to match it.
    func testRadiiMatchTheAndroidImplementation() {
        XCTAssertEqual(LoRaCoverage.radiusMeters(rssi: -30, hopsAway: 0)!, 150.0, accuracy: 0.01)
        XCTAssertEqual(LoRaCoverage.radiusMeters(rssi: -70, hopsAway: 0)!, 1009.8338, accuracy: 0.01)
        XCTAssertEqual(LoRaCoverage.radiusMeters(rssi: -120, hopsAway: 0)!, 4502.9086, accuracy: 0.01)
        XCTAssertEqual(LoRaCoverage.fillOpacity(rssi: -70, hopsAway: 0), 0.1423, accuracy: 0.0001)
    }

    func testScalingIsNotLinear() {
        // Path loss is logarithmic, so equal dB steps must NOT give equal radius
        // steps — a linear mapping made every mid-strength node look alike.
        let a = LoRaCoverage.radiusMeters(rssi: -50, hopsAway: 0)!
        let b = LoRaCoverage.radiusMeters(rssi: -70, hopsAway: 0)!
        let c = LoRaCoverage.radiusMeters(rssi: -90, hopsAway: 0)!
        XCTAssertNotEqual(b - a, c - b, accuracy: 1)
    }
}
