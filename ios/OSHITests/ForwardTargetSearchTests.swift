import XCTest
@testable import OSHI

/// Mirror of `ForwardTargetSearchTest.kt`. The two files assert the same
/// vectors on purpose: this is the screen where a wrong row sends a private
/// message to the wrong person, so the platforms must not drift on what
/// matches.
final class ForwardTargetSearchTests: XCTestCase {

    private let key = "AbCdEf0123456789+/xyzABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnop"

    func testEmptyQueryMatchesEverything() {
        XCTAssertTrue(ForwardTargetSearch.matches(query: "", name: "Anatole", publicKey: key))
        XCTAssertTrue(ForwardTargetSearch.matches(query: "   ", name: "Anatole", publicKey: key))
    }

    func testAliasMatchesCaseInsensitivelyAndMidWord() {
        XCTAssertTrue(ForwardTargetSearch.matches(query: "nat", name: "Anatole", publicKey: key))
        XCTAssertTrue(ForwardTargetSearch.matches(query: "ANAT", name: "Anatole", publicKey: key))
        XCTAssertTrue(ForwardTargetSearch.matches(query: "anatole", name: "Anatole", publicKey: key))
    }

    /// The reported failure: the whole key in hand, zero results.
    func testFullPastedKeyFindsTheContact() {
        XCTAssertTrue(ForwardTargetSearch.matches(query: key, name: "Anatole", publicKey: key))
    }

    func testKeyPrefixFindsTheContact() {
        XCTAssertTrue(ForwardTargetSearch.matches(query: String(key.prefix(8)), name: "Anatole", publicKey: key))
        XCTAssertTrue(ForwardTargetSearch.matches(query: String(key.prefix(20)), name: "Anatole", publicKey: key))
    }

    func testBase64URLSpellingOfTheSameKeyStillMatches() {
        let urlSpelling = key.replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_") + "=="
        XCTAssertTrue(ForwardTargetSearch.matches(query: urlSpelling, name: "Anatole", publicKey: key))
        XCTAssertTrue(ForwardTargetSearch.matches(query: String(urlSpelling.prefix(24)), name: "Anatole", publicKey: key))
    }

    /// THE safety test. Base64 case is significant, so a case-insensitive key
    /// match could surface a different contact under a typed prefix.
    func testKeyMatchingIsCaseSensitive() {
        XCTAssertFalse(ForwardTargetSearch.matches(query: String(key.prefix(12)).lowercased(), name: "Someone", publicKey: key))
        XCTAssertFalse(ForwardTargetSearch.matches(query: String(key.prefix(12)).uppercased(), name: "Someone", publicKey: key))
    }

    func testKeyMatchingIsPrefixNotSubstring() {
        let start = key.index(key.startIndex, offsetBy: 20)
        let end = key.index(key.startIndex, offsetBy: 32)
        XCTAssertFalse(ForwardTargetSearch.matches(query: String(key[start..<end]), name: "Someone", publicKey: key))
    }

    func testVeryShortQueryIsAliasOnly() {
        XCTAssertFalse(ForwardTargetSearch.matches(query: "Ab", name: "Someone", publicKey: key))
        XCTAssertTrue(ForwardTargetSearch.matches(query: "Ab", name: "Abigail", publicKey: key))
    }

    func testGroupWithNoKeyMatchesByNameOnly() {
        XCTAssertTrue(ForwardTargetSearch.matches(query: "manif", name: "Manifestation", publicKey: nil))
        XCTAssertFalse(ForwardTargetSearch.matches(query: key, name: "Manifestation", publicKey: nil))
    }

    func testNonMatchingQueryMatchesNothing() {
        XCTAssertFalse(ForwardTargetSearch.matches(query: "zzzz", name: "Anatole", publicKey: key))
    }

    // MARK: - Ranking

    func testRankIsStableWithNoHistory() {
        // No forwarding history must leave the caller's order untouched, or the
        // picker would reshuffle for a user who has never forwarded anything.
        let input = ["a", "b", "c"]
        XCTAssertEqual(ForwardAffinityStore.rank(input) { $0 }, input)
    }

    func testNormalizationIsSharedBetweenSearchAndRanking() {
        // Both must fold the same way, or a contact tallied under one spelling
        // would never be found under the other.
        let urlSpelling = key.replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_") + "=="
        XCTAssertEqual(ForwardTargetSearch.normalizeKey(urlSpelling),
                       ForwardTargetSearch.normalizeKey(key))
    }
}
