//
//  LoRaPositionBeaconTests.swift
//  OSHITests
//
//  __LORA_POSITION_BEACON_2026_10_28__ the bytes a POSITION_APP beacon puts on the air.
//

import XCTest
@testable import OSHI

/// The beacon is what lets other radios draw this node on their map, and its encoding is
/// not ours to choose: `Position.latitude_i`/`longitude_i` are **sfixed32 scaled by 1e-7**,
/// on portnum 3. Get either wrong and the packet is still a valid frame — it simply puts
/// the node somewhere else in the world, on somebody else's screen, with nothing red.
///
/// These call `MeshtasticManager.positionPayload`, the SHIPPED encoder, and read it back
/// through `parsePosition`, the SHIPPED decoder every inbound position already goes
/// through. A test that re-derived the arithmetic would agree with itself whatever the
/// sender did.
final class LoRaPositionBeaconTests: XCTestCase {

    func testPositionPortnumIsThree() {
        XCTAssertEqual(MeshtasticManager.positionPortnum, 3,
                       "portnum 3 is POSITION_APP in Meshtastic's portnums.proto")
    }

    func testBeaconRoundTripsThroughTheReceiveParser() {
        let lat = 46.20440, lon = 6.14320          // Geneva
        let payload = MeshtasticManager.positionPayload(latitude: lat, longitude: lon,
                                                        timeSec: 1_793_200_000)
        let parsed = MeshtasticManager.parsePosition(payload)
        XCTAssertNotNil(parsed.lat, "the parser must read what the beacon writes")
        XCTAssertEqual(parsed.lat!, lat, accuracy: 1e-6)
        XCTAssertEqual(parsed.lon!, lon, accuracy: 1e-6)
    }

    func testSouthernAndWesternPointKeepsItsSign() {
        // sfixed32 is SIGNED and little-endian. Read unsigned, Ushuaia lands in Siberia —
        // the failure a northern-hemisphere fixture would never catch.
        let lat = -54.80190, lon = -68.30300        // Ushuaia
        let parsed = MeshtasticManager.parsePosition(
            MeshtasticManager.positionPayload(latitude: lat, longitude: lon, timeSec: 1))
        XCTAssertNotNil(parsed.lat)
        XCTAssertLessThan(parsed.lat!, 0, "latitude must stay negative")
        XCTAssertLessThan(parsed.lon!, 0, "longitude must stay negative")
        XCTAssertEqual(parsed.lat!, lat, accuracy: 1e-6)
        XCTAssertEqual(parsed.lon!, lon, accuracy: 1e-6)
    }

    /// A node with no fix reports 0/0. Broadcasting that would put this node in the Gulf
    /// of Guinea on every map that heard it, which is the defect the RECEIVE path already
    /// guards against — the sender must not create it either.
    func testZeroFixIsRefusedBySend() {
        XCTAssertFalse(MeshtasticManager.shared.broadcastPosition(latitude: 0, longitude: 0),
                       "a 0/0 fix must never go on the air")
        XCTAssertFalse(MeshtasticManager.shared.broadcastPosition(latitude: 91, longitude: 0),
                       "a position off the planet must never go on the air")
    }
}
