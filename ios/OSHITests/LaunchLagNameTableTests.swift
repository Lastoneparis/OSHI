//
//  LaunchLagNameTableTests.swift
//  OSHITests
//
//  __LAUNCH_LAG_2026_11_22__ `SharedNickname.Table` replaced a per-row JSON
//  decode + full-map scan in the conversation list (the main-thread cost that
//  stalled the cold launch). It must answer EXACTLY what the old path answered.
//

import XCTest
@testable import OSHI

final class LaunchLagNameTableTests: XCTestCase {

    private let std = "AbC+/xyz=="          // standard base64, padded
    private let url = "AbC-_xyz"            // base64url, unpadded: folds to "AbC+/xyz"

    /// Every map here has at most one non-empty value per folded key, so the
    /// old scan's answer does not depend on dictionary order: both must agree.
    func testTableAgreesWithTheMapScanOnEverySpelling() {
        let maps: [[String: String]] = [
            [:],
            [std: "Alice"],
            [url: "Alice"],
            [std: "", url: "Bob"],
            ["  \(std)  ": "Spaced"],
            ["other": "Carol"],
            [std: "Alice", "Zz+/": "Dan"],
        ]
        let probes = [std, url, "AbC+/xyz", " \(std)\n", "Zz-_", "Zz+/==", "other", "missing", "", "==="]
        for map in maps {
            let table = SharedNickname.Table(map)
            for key in probes {
                XCTAssertEqual(table.nickname(for: key), SharedNickname.nickname(for: key, in: map),
                               "map=\(map) key=\(key)")
            }
        }
    }

    func testExactSpellingBeatsAFoldedOne() {
        let table = SharedNickname.Table(["AbC+/xyz": "Exact", url: "Folded"])
        XCTAssertEqual(table.nickname(for: std), "Exact")
        XCTAssertEqual(table.nickname(for: url), "Exact")
    }

    func testEmptyValuesNeverWin() {
        let table = SharedNickname.Table([std: "", url: "Bob"])
        XCTAssertEqual(table.nickname(for: std), "Bob")
        XCTAssertNil(SharedNickname.Table([std: ""]).nickname(for: std))
    }

    func testMemoFollowsTheBytes() throws {
        let a = try JSONEncoder().encode([std: "Alice"])
        let b = try JSONEncoder().encode([std: "Alicia"])
        XCTAssertEqual(SharedNickname.table(a).nickname(for: url), "Alice")
        XCTAssertEqual(SharedNickname.table(b).nickname(for: url), "Alicia")
        XCTAssertEqual(SharedNickname.table(a).nickname(for: url), "Alice")
        XCTAssertNil(SharedNickname.table(nil).nickname(for: url))
        XCTAssertNil(SharedNickname.table(Data("not json".utf8)).nickname(for: url))
    }

    func testAliasMemoFollowsTheBytes() throws {
        let a = try JSONEncoder().encode(["k": "One"])
        let b = try JSONEncoder().encode(["k": "Two"])
        XCTAssertEqual(ContactAliasesMemo.map(a)["k"], "One")
        XCTAssertEqual(ContactAliasesMemo.map(b)["k"], "Two")
        XCTAssertEqual(ContactAliasesMemo.map(Data("x".utf8)), [:])
    }
}
