//
//  KeyChangeWatchTests.swift
//  OSHITests
//

import XCTest
@testable import OSHI

/// [KeyChangeWatch] — the thing that makes a silent identity-key change impossible.
///
/// The case that matters is `a key that changes between two messages is reported`. Everything
/// else here exists to stop that one from being satisfied by a function that simply shouts on
/// every call, which would train users to dismiss a warning whose only value is that they
/// believe it — and to stop it being satisfied by a function that can never fire at all, which
/// is the failure mode of every key-change check that lives on a screen nobody opens.
final class KeyChangeWatchTests: XCTestCase {

    private var defaults: UserDefaults!

    /// Two distinct 32-byte keys that share the first ten characters — the case the user
    /// cannot see, because the app renders a contact by the leading characters of their key.
    private let keyA = "AAAAAAAAAAmVh0SmMSdPbDJ2eUFoR0ZhM1JiN2RLOWc9"
    private let keyB = "AAAAAAAAAAzzz1TnNTePcEK3fVBpS0dGbjRTcjhlTDBo"

    override func setUp() {
        super.setUp()
        // A private suite: these tests must not read or write the real app's baseline.
        defaults = UserDefaults(suiteName: "KeyChangeWatchTests")!
        defaults.removePersistentDomain(forName: "KeyChangeWatchTests")
    }

    // MARK: - The property the feature exists for

    func testAKeyThatChangesBetweenTwoMessagesIsReported() {
        let address = KeyChangeWatch.address(forKey: keyA)
        let known = [address: KeyChangeWatch.fingerprint(of: keyA)!]

        XCTAssertEqual(
            KeyChangeWatch.classify(address: address, currentKey: keyB, known: known),
            .changed(previous: KeyChangeWatch.fingerprint(of: keyA)!,
                     current: KeyChangeWatch.fingerprint(of: keyB)!),
            "a different key behind a contact we have already seen is exactly what the user must be told about"
        )
    }

    func testTheTwoKeysAreIndistinguishableByAddressWhichIsWhyTheWatchIsNeeded() {
        // If these two keys produced different addresses, the watch would classify the second
        // as a brand-new contact and this whole type would be decoration.
        XCTAssertEqual(KeyChangeWatch.address(forKey: keyA), KeyChangeWatch.address(forKey: keyB))
        XCTAssertNotEqual(KeyChangeWatch.fingerprint(of: keyA), KeyChangeWatch.fingerprint(of: keyB))
    }

    func testOneContactChangingDoesNotImplicateTheOthers() {
        let other = "ZZZZZZZZZZother0000000000000000000000000000="
        let known = [
            KeyChangeWatch.address(forKey: keyA): KeyChangeWatch.fingerprint(of: keyA)!,
            KeyChangeWatch.address(forKey: other): KeyChangeWatch.fingerprint(of: other)!,
        ]
        XCTAssertEqual(KeyChangeWatch.classify(address: KeyChangeWatch.address(forKey: other),
                                               currentKey: other, known: known),
                       .unchanged,
                       "an unrelated contact must not be dragged into someone else's key change")
    }

    // MARK: - The noise guards, which are what make the alert believable

    func testNothingIsReportedWhenTheKeyIsTheSame() {
        let address = KeyChangeWatch.address(forKey: keyA)
        let known = [address: KeyChangeWatch.fingerprint(of: keyA)!]
        XCTAssertEqual(KeyChangeWatch.classify(address: address, currentKey: keyA, known: known), .unchanged)
    }

    func testTheFirstSightOfAContactDoesNotAlarm() {
        // No entry means "we have never seen this contact". Treating the contacts a user
        // already talks to as changed would fire a security warning for every conversation on
        // upgrade day.
        XCTAssertEqual(KeyChangeWatch.classify(address: "addr", currentKey: keyA, known: nil), .firstSight)
        XCTAssertEqual(KeyChangeWatch.classify(address: "addr", currentKey: keyA, known: [:]), .firstSight)
        XCTAssertEqual(KeyChangeWatch.classify(address: "addr", currentKey: keyA, known: ["someone-else": "ff"]),
                       .firstSight)
    }

    func testAMissingEntryAndAStoredFingerprintAreNotTheSameThing() {
        // A stored fingerprint means "this is the key we saw", so a different key IS news.
        // Conflating the two is how the first-sight compromise would silently become a
        // permanent one — nothing would ever be reported again.
        XCTAssertEqual(KeyChangeWatch.classify(address: "addr", currentKey: keyB, known: ["addr": "deadbeef"]),
                       .changed(previous: "deadbeef", current: KeyChangeWatch.fingerprint(of: keyB)!))
        XCTAssertEqual(KeyChangeWatch.classify(address: "addr", currentKey: keyB, known: [:]), .firstSight)
    }

    func testTheSameKeySpelledDifferentlyIsNotAKeyChange() {
        // base64 vs base64url vs padding. This app already normalises keys
        // (MessageManager.canonicalIdentity) precisely because one key arrives spelled several
        // ways; a warning that fires on a spelling difference is a warning users learn to
        // ignore.
        let padded = "q83vASNFZ4mrze/+3LqYdvDh0JgHZlUzESIzRFVmd4g="
        let urlSafe = "q83vASNFZ4mrze_-3LqYdvDh0JgHZlUzESIzRFVmd4g"
        XCTAssertEqual(KeyChangeWatch.fingerprint(of: padded), KeyChangeWatch.fingerprint(of: urlSafe))
        XCTAssertEqual(
            KeyChangeWatch.classify(address: "addr",
                                    currentKey: urlSafe,
                                    known: ["addr": KeyChangeWatch.fingerprint(of: padded)!]),
            .unchanged
        )
    }

    func testAMessageWithNoKeyIsNotAChangeAndCannotOverwriteTheBaseline() {
        let known = ["addr": KeyChangeWatch.fingerprint(of: keyA)!]
        XCTAssertEqual(KeyChangeWatch.classify(address: "addr", currentKey: "", known: known), .unchanged)
        XCTAssertEqual(KeyChangeWatch.classify(address: "addr", currentKey: "   ", known: known), .unchanged)
        XCTAssertNil(KeyChangeWatch.fingerprint(of: ""))

        // And the baseline survives it, or the next real message would read as substituted.
        KeyChangeWatch.saveKnown(known, defaults)
        KeyChangeWatch.remember(address: "addr", currentKey: "", defaults)
        XCTAssertEqual(KeyChangeWatch.loadKnown(defaults)?["addr"], KeyChangeWatch.fingerprint(of: keyA))
    }

    // MARK: - Persistence

    func testTheBaselineSurvivesAndDistinguishesNeverSeenFromSeen() {
        XCTAssertNil(KeyChangeWatch.loadKnown(defaults), "before any save, we have never looked")

        KeyChangeWatch.saveKnown([:], defaults)
        XCTAssertEqual(KeyChangeWatch.loadKnown(defaults), [:],
                       "having looked and stored nothing must not read back as never having looked")

        KeyChangeWatch.remember(address: "addr", currentKey: keyA, defaults)
        XCTAssertEqual(KeyChangeWatch.loadKnown(defaults),
                       ["addr": KeyChangeWatch.fingerprint(of: keyA)!])

        // A stored baseline is what makes a later key newsworthy, across a full round-trip.
        XCTAssertEqual(
            KeyChangeWatch.classify(address: "addr", currentKey: keyB, known: KeyChangeWatch.loadKnown(defaults)),
            .changed(previous: KeyChangeWatch.fingerprint(of: keyA)!,
                     current: KeyChangeWatch.fingerprint(of: keyB)!)
        )
    }

    func testTheStoredBaselineIsADigestAndNotTheKey() {
        KeyChangeWatch.remember(address: "addr", currentKey: keyA, defaults)
        let stored = KeyChangeWatch.loadKnown(defaults)!["addr"]!
        XCTAssertNotEqual(stored, keyA)
        XCTAssertEqual(stored.count, 32, "16 bytes, hex")
    }

    func testForgettingOneContactReSeedsOnlyThatContact() {
        KeyChangeWatch.remember(address: "a", currentKey: keyA, defaults)
        KeyChangeWatch.remember(address: "b", currentKey: keyB, defaults)
        KeyChangeWatch.forget(address: "a", defaults)

        let known = KeyChangeWatch.loadKnown(defaults)
        XCTAssertEqual(KeyChangeWatch.classify(address: "a", currentKey: keyB, known: known), .firstSight,
                       "a contact the user re-verified out of band starts over")
        XCTAssertEqual(KeyChangeWatch.classify(address: "b", currentKey: keyA, known: known),
                       .changed(previous: KeyChangeWatch.fingerprint(of: keyB)!,
                                current: KeyChangeWatch.fingerprint(of: keyA)!),
                       "and nobody else does")
    }

    func testResetMakesTheNextMessageSeedInsteadOfAlarming() {
        KeyChangeWatch.remember(address: "addr", currentKey: keyA, defaults)
        KeyChangeWatch.reset(defaults)
        XCTAssertNil(KeyChangeWatch.loadKnown(defaults))
        XCTAssertEqual(
            KeyChangeWatch.classify(address: "addr", currentKey: keyB, known: KeyChangeWatch.loadKnown(defaults)),
            .firstSight,
            "after an identity reset the previous identity's contacts must not be reported as changed"
        )
    }

    // MARK: - The alert a view is handed

    func testTheSameChangeRaisedTwiceIsOneBanner() {
        let one = KeyChangeWatch.Alert(address: "addr", previous: "old", current: "new", currentKey: keyB)
        let two = KeyChangeWatch.Alert(address: "addr", previous: "old", current: "new", currentKey: keyB)
        XCTAssertEqual(one.id, two.id)
        XCTAssertEqual(one, two)
    }

    // MARK: - The end-to-end shape the caller uses

    func testASilentKeyChangeIsImpossibleAcrossTwoMessages() {
        // Message one, from a contact we have never seen. Seeded, nothing claimed.
        let address = KeyChangeWatch.address(forKey: keyA)
        XCTAssertEqual(
            KeyChangeWatch.classify(address: address, currentKey: keyA, known: KeyChangeWatch.loadKnown(defaults)),
            .firstSight
        )
        KeyChangeWatch.remember(address: address, currentKey: keyA, defaults)

        // Between the two messages, the key behind that same on-screen contact is replaced.
        // Message two: it is reported. This is the whole feature.
        XCTAssertEqual(
            KeyChangeWatch.classify(address: address, currentKey: keyB, known: KeyChangeWatch.loadKnown(defaults)),
            .changed(previous: KeyChangeWatch.fingerprint(of: keyA)!,
                     current: KeyChangeWatch.fingerprint(of: keyB)!)
        )

        // Acknowledged out of band: the new key becomes the baseline, and the banner does not
        // come back for a key the user has already looked at.
        KeyChangeWatch.remember(address: address, currentKey: keyB, defaults)
        XCTAssertEqual(
            KeyChangeWatch.classify(address: address, currentKey: keyB, known: KeyChangeWatch.loadKnown(defaults)),
            .unchanged
        )
    }
}
