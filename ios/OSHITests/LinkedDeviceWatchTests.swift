//
//  LinkedDeviceWatchTests.swift
//  OSHITests
//

import XCTest
@testable import OSHI

/// [LinkedDeviceWatch] — the thing that makes a silent device link impossible.
///
/// The case that matters is `a device linked between two checks is reported`. Everything
/// else here exists to stop that one from being satisfied by a function that simply shouts
/// on every call, which would train users to dismiss the warning and leave the app worse
/// off than with no warning at all.
final class LinkedDeviceWatchTests: XCTestCase {

    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        // A private suite: these tests must not read or write the real app's baseline.
        defaults = UserDefaults(suiteName: "LinkedDeviceWatchTests")!
        defaults.removePersistentDomain(forName: "LinkedDeviceWatchTests")
    }

    // MARK: - The property the feature exists for

    func testADeviceLinkedBetweenTwoChecksIsReported() {
        let known: Set<String> = ["phone"]
        XCTAssertEqual(
            LinkedDeviceWatch.newlyLinked(fetched: ["phone", "unknown-laptop"], known: known),
            ["unknown-laptop"],
            "a session that was not there last time is exactly what the user must be told about"
        )
        XCTAssertEqual(
            LinkedDeviceWatch.classify(fetched: ["phone", "unknown-laptop"], known: known),
            .linked(["unknown-laptop"])
        )
    }

    func testSeveralLinkedAtOnceAreAllReported() {
        XCTAssertEqual(
            LinkedDeviceWatch.newlyLinked(fetched: ["a", "b", "c"], known: ["a"]),
            ["b", "c"],
            "reporting only the first would hide the second"
        )
    }

    // MARK: - The noise guards, which are what make the alert believable

    func testNothingIsReportedWhenNothingChanged() {
        XCTAssertEqual(LinkedDeviceWatch.classify(fetched: ["a", "b"], known: ["a", "b"]), .unchanged)
        XCTAssertEqual(LinkedDeviceWatch.newlyLinked(fetched: ["a", "b"], known: ["a", "b"]), [])
    }

    func testTheFirstCheckEverSeedsAndDoesNotAlarm() {
        // nil means "we have never looked". Treating the sessions a user already had as
        // newly linked would fire a security warning at everyone on upgrade day.
        XCTAssertEqual(LinkedDeviceWatch.newlyLinked(fetched: ["a", "b"], known: nil), [])
        XCTAssertEqual(LinkedDeviceWatch.classify(fetched: ["a", "b"], known: nil), .seeded(count: 2))
    }

    func testNilAndEmptyAreNotTheSameThing() {
        // `[]` means "last time there were none", so a device appearing IS news. Conflating
        // the two is how the first-run compromise would silently become a permanent one.
        XCTAssertEqual(LinkedDeviceWatch.newlyLinked(fetched: ["a"], known: []), ["a"])
        XCTAssertEqual(LinkedDeviceWatch.newlyLinked(fetched: ["a"], known: nil), [])
    }

    func testARevokedDeviceThatReturnsIsReportedAgain() {
        // Revoking removes it from the baseline; if it comes back, someone linked it again
        // and that is a new event, not a continuation of the old one.
        var known: Set<String> = ["a", "laptop"]
        known.remove("laptop")                                   // user revoked it
        XCTAssertEqual(LinkedDeviceWatch.newlyLinked(fetched: ["a", "laptop"], known: known), ["laptop"])
    }

    func testADisappearingDeviceIsNotReportedAsNew() {
        XCTAssertEqual(LinkedDeviceWatch.newlyLinked(fetched: ["a"], known: ["a", "b"]), [],
                       "a session going away is not a linking event")
    }

    // MARK: - Persistence

    func testTheBaselineSurvivesAndDistinguishesNeverLookedFromLookedAndFoundNone() {
        XCTAssertNil(LinkedDeviceWatch.loadKnown(defaults), "before any save, we have never looked")

        LinkedDeviceWatch.saveKnown([], defaults)
        XCTAssertEqual(LinkedDeviceWatch.loadKnown(defaults), [],
                       "having looked and found none must not read back as never having looked")

        LinkedDeviceWatch.saveKnown(["b", "a"], defaults)
        XCTAssertEqual(LinkedDeviceWatch.loadKnown(defaults), ["a", "b"])
    }

    func testResetMakesTheNextCheckSeedInsteadOfAlarming() {
        LinkedDeviceWatch.saveKnown(["old-identity-session"], defaults)
        LinkedDeviceWatch.reset(defaults)
        XCTAssertNil(LinkedDeviceWatch.loadKnown(defaults))
        XCTAssertEqual(
            LinkedDeviceWatch.newlyLinked(fetched: ["fresh"], known: LinkedDeviceWatch.loadKnown(defaults)),
            [],
            "after an account reset the previous identity's sessions must not be reported as new"
        )
    }

    // MARK: - The end-to-end shape the caller uses

    func testASilentLinkIsImpossibleAcrossTwoChecks() {
        // Check one: the user has a phone. Seeded, nothing claimed.
        var fetched = ["phone"]
        XCTAssertEqual(LinkedDeviceWatch.classify(fetched: fetched, known: LinkedDeviceWatch.loadKnown(defaults)),
                       .seeded(count: 1))
        LinkedDeviceWatch.saveKnown(fetched, defaults)

        // Between the two checks, somebody with the unlocked phone links a computer.
        fetched = ["phone", "investigators-laptop"]

        // Check two: it is reported. This is the whole feature.
        XCTAssertEqual(LinkedDeviceWatch.classify(fetched: fetched, known: LinkedDeviceWatch.loadKnown(defaults)),
                       .linked(["investigators-laptop"]))
    }
}
