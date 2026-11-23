//
//  MeshNameAndDistanceTests.swift
//  OSHITests
//
//  Two small pure functions that both answer a question the UI used to get
//  wrong, in opposite directions: one counted the wrong unit, the other printed
//  the wrong one.
//

import XCTest
@testable import OSHI

final class MeshShortNameTests: XCTestCase {

    /// __SHORT_NAME_IS_BYTES_2026_10_21__ THE property.
    ///
    /// Meshtastic caps `User.short_name` at four BYTES. The old code sent
    /// `Data(shortName.prefix(4).utf8)` — `prefix(4)` counts Characters, so a
    /// single emoji already fills the budget and four of them are sixteen
    /// bytes. The node then stores a truncated prefix that cuts a UTF-8
    /// sequence in half, and the name renders as a replacement glyph in every
    /// other client. The doc comment on that line literally said "4
    /// characters", which is how the mistake survived.
    func testTheBudgetIsFourBytesNotFourCharacters() {
        for raw in ["TEA", "TEAM", "🚀", "🚀🚀", "🚀🚀🚀🚀", "é", "ééé", "日本語", "ABCDEFGH", ""] {
            let out = MeshtasticManager.clampShortName(raw)
            XCTAssertLessThanOrEqual(out.utf8.count, 4,
                                     "\"\(raw)\" produced \(out.utf8.count) bytes: \"\(out)\"")
        }
    }

    /// And it must never split a character — a half-emoji is worse than a
    /// shorter name, because it is unreadable rather than merely terse.
    func testACharacterIsNeverCutInHalf() {
        XCTAssertEqual(MeshtasticManager.clampShortName("🚀🚀"), "🚀")
        XCTAssertEqual(MeshtasticManager.clampShortName("🚀A"), "🚀")
        // Round-tripping through UTF-8 must give back exactly what we wrote.
        let out = MeshtasticManager.clampShortName("🚀🚀🚀")
        XCTAssertEqual(String(decoding: Array(out.utf8), as: UTF8.self), out)
    }

    /// What people will actually type. Three or four Latin letters fit whole;
    /// that is the case the feature was asked for.
    func testThreeOrFourLettersFitUntouched() {
        XCTAssertEqual(MeshtasticManager.clampShortName("TEA"), "TEA")
        XCTAssertEqual(MeshtasticManager.clampShortName("TEB"), "TEB")
        XCTAssertEqual(MeshtasticManager.clampShortName("TEAM"), "TEAM")
        XCTAssertEqual(MeshtasticManager.clampShortName("TEAMS"), "TEAM")
    }

    /// Accented Latin is two bytes each, so "ééé" is six and only two survive.
    /// Counting characters would have let all three through and overflowed.
    func testTwoByteCharactersCountDouble() {
        XCTAssertEqual(MeshtasticManager.clampShortName("ééé"), "éé")
        XCTAssertEqual(MeshtasticManager.clampShortName("éé").utf8.count, 4)
    }

    func testEmptyStaysEmpty() {
        XCTAssertEqual(MeshtasticManager.clampShortName(""), "")
    }
}

final class MeshDistanceTests: XCTestCase {

    /// __DISTANCE_FOLLOWS_THE_LOCALE_2026_10_21__ The mesh printed every
    /// distance in m/km, hard-coded, in two separate functions. For a reader in
    /// the US or the UK "4.2 km" is not information, it is a conversion to do
    /// in your head.
    func testMetricUsesMetresBelowAKilometre() {
        XCTAssertEqual(MeshDistance.format(km: 0.3, imperial: false), "300 m")
        XCTAssertEqual(MeshDistance.format(km: 0.999, imperial: false), "999 m")
        XCTAssertEqual(MeshDistance.format(km: 1.0, imperial: false), "1.0 km")
        XCTAssertEqual(MeshDistance.format(km: 4.25, imperial: false), "4.2 km")
    }

    /// The imperial cut is at 0.1 mile, not at one mile: below that a decimal
    /// mile reads as noise, and feet are what people pace out.
    func testImperialUsesFeetBelowATenthOfAMile() {
        XCTAssertTrue(MeshDistance.format(km: 0.05, imperial: true).hasSuffix(" ft"),
                      MeshDistance.format(km: 0.05, imperial: true))
        XCTAssertTrue(MeshDistance.format(km: 4.0, imperial: true).hasSuffix(" mi"))
        XCTAssertEqual(MeshDistance.format(km: 1.609344, imperial: true), "1.0 mi")
    }

    /// A span must never mix its two halves. The unit comes from the FAR end,
    /// which is the existing rule and the reason `span` exists at all.
    func testASpanNeverMixesUnits() {
        for imperial in [true, false] {
            for (lo, hi) in [(0.3, 0.9), (0.3, 4.0), (1.2, 4.8), (0.02, 0.08)] {
                let s = MeshDistance.span(minKm: lo, maxKm: hi, imperial: imperial)
                let units = ["m", "km", "ft", "mi"].filter { s.hasSuffix(" \($0)") }
                XCTAssertEqual(units.count, 1, "\"\(s)\" does not end in exactly one unit")
                XCTAssertTrue(s.contains("–"), "\"\(s)\" is not a span")
            }
        }
    }

    /// A span that straddles the cut takes the far end's unit, so a 300 m–4 km
    /// band reads in km rather than printing "300–4.0".
    func testASpanStraddlingTheCutFollowsTheFarEnd() {
        XCTAssertEqual(MeshDistance.span(minKm: 0.3, maxKm: 4.0, imperial: false), "0.3–4.0 km")
        XCTAssertEqual(MeshDistance.span(minKm: 0.3, maxKm: 0.9, imperial: false), "300–900 m")
    }

    /// Metric and imperial must disagree — otherwise the switch does nothing
    /// and the whole change is decorative.
    func testTheTwoSystemsActuallyDiffer() {
        XCTAssertNotEqual(MeshDistance.format(km: 4.0, imperial: true),
                          MeshDistance.format(km: 4.0, imperial: false))
        XCTAssertNotEqual(MeshDistance.span(minKm: 1.2, maxKm: 4.8, imperial: true),
                          MeshDistance.span(minKm: 1.2, maxKm: 4.8, imperial: false))
    }
}

/// __LORA_POSITION_LIVE_2026_10_27__ Where a GPS node says it is.
///
/// Its own suite: these cover the Meshtastic `Position` wire format, not the
/// short-name budget or the distance formatter the two suites above own.
final class MeshPositionTests: XCTestCase {

    /// Build a Meshtastic `Position` payload: field 1 `latitude_i` and field 2
    /// `longitude_i`, both **sfixed32** (wire type 5), degrees scaled by 1e7.
    private func positionPayload(latE7: Int32, lonE7: Int32) -> Data {
        var d = Data()
        for (field, value) in [(1, latE7), (2, lonE7)] {
            d.append(UInt8(field << 3 | 5))                       // tag | wire 5
            var le = UInt32(bitPattern: value).littleEndian
            withUnsafeBytes(of: &le) { d.append(contentsOf: $0) }
        }
        return d
    }

    /// Chamonix, Haute-Savoie — both coordinates positive, the easy case.
    func testAPositionIsReadInDegrees() {
        let p = MeshtasticManager.parsePosition(positionPayload(latE7: 458_923_000,
                                                                lonE7: 68_694_000))
        XCTAssertEqual(p.lat ?? 0, 45.8923, accuracy: 0.0001)
        XCTAssertEqual(p.lon ?? 0, 6.8694, accuracy: 0.0001)
    }

    /// THE bug this parser exists to avoid. `latitude_i`/`longitude_i` are
    /// SIGNED; read them unsigned and everything south of the equator or west
    /// of Greenwich lands near the north pole. Ushuaia, at -54.80, -68.30.
    func testSouthAndWestCoordinatesStaySouthAndWest() {
        let p = MeshtasticManager.parsePosition(positionPayload(latE7: -548_018_000,
                                                                lonE7: -683_026_000))
        XCTAssertEqual(p.lat ?? 0, -54.8018, accuracy: 0.0001)
        XCTAssertEqual(p.lon ?? 0, -68.3026, accuracy: 0.0001)
        XCTAssertLessThan(p.lat ?? 0, 0, "a southern latitude was read as northern")
        XCTAssertLessThan(p.lon ?? 0, 0, "a western longitude was read as eastern")
    }

    /// A node with no fix reports 0/0, and a Position with no coordinates at
    /// all must not read as the Gulf of Guinea. The parser reports what it
    /// found; `handlePosition` is what refuses 0/0.
    func testAPositionWithNoCoordinatesYieldsNothing() {
        let p = MeshtasticManager.parsePosition(Data())
        XCTAssertNil(p.lat)
        XCTAssertNil(p.lon)
    }

    /// Fields other than lat/lon (altitude, time, sats) must be skipped, not
    /// mistaken for coordinates — a Position from a real node carries several.
    func testUnrelatedPositionFieldsAreIgnored() {
        var d = positionPayload(latE7: 458_923_000, lonE7: 68_694_000)
        d.append(UInt8(3 << 3 | 0))                 // field 3 altitude, varint
        d.append(UInt8(200))                        // 2-byte varint
        d.append(UInt8(0x0B))
        let p = MeshtasticManager.parsePosition(d)
        XCTAssertEqual(p.lat ?? 0, 45.8923, accuracy: 0.0001)
        XCTAssertEqual(p.lon ?? 0, 6.8694, accuracy: 0.0001)
    }
}
