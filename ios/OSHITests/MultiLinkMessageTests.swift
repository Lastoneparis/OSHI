import XCTest
@testable import OSHI

/// Guards the "a message with many links" behaviour.
///
/// Reported with nine Play Store links in one message: tapping anywhere opened
/// the FIRST link, and neither the message nor an individual link could be
/// selected. Detection was never the problem — these tests pin that, so a
/// future regression is correctly blamed on the view rather than the detector.
final class MultiLinkMessageTests: XCTestCase {

    /// The reported message, trailing spaces and blank lines included: the
    /// whitespace is part of what was pasted and must not merge two links.
    private let manyLinks = """
    https://play.google.com/store/apps/details?id=com.forma.illustration  

    https://play.google.com/store/apps/details?id=com.moriceau.neorun2025

    https://play.google.com/store/apps/details?id=com.hugolab.picswipe 

    https://play.google.com/store/apps/details?id=com.moriceau.callscribe

    https://play.google.com/store/apps/details?id=com.hugomoriceau.neonchess

    https://play.google.com/store/apps/details?id=com.carnagestrike.game

    https://play.google.com/store/apps/details?id=com.hugomoriceau.cocotte

    https://play.google.com/store/apps/details?id=com.hugomoriceau.paylane

    https://play.google.com/store/apps/details?id=com.skygate.flighttracker
    """

    func testEveryLinkIsDetectedNotJustTheFirst() {
        let detector = try! NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
        let range = NSRange(manyLinks.startIndex..., in: manyLinks)
        let matches = detector.matches(in: manyLinks, options: [], range: range)
        XCTAssertEqual(matches.count, 9, "expected 9 links, found \(matches.count)")
    }

    /// Each link must resolve to its OWN url. The bug was that every tap opened
    /// link #1, so the distinctness of these is the property that matters.
    func testEachDetectedLinkHasItsOwnDistinctURL() {
        let detector = try! NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
        let range = NSRange(manyLinks.startIndex..., in: manyLinks)
        let urls = detector.matches(in: manyLinks, options: [], range: range).compactMap { $0.url?.absoluteString }
        XCTAssertEqual(urls.count, 9)
        XCTAssertEqual(Set(urls).count, 9, "links collapsed onto each other: \(urls)")
        XCTAssertTrue(urls[0].hasSuffix("com.forma.illustration"))
        XCTAssertTrue(urls[8].hasSuffix("com.skygate.flighttracker"))
    }

    /// `detectFirstURL` is what drives the PREVIEW CARD, and it is correct for
    /// that job — one card, for the first link. The bug was that its value was
    /// also used for taps on the body text. Pinned so the distinction stays
    /// visible: this returning the first link is intended.
    func testDetectFirstURLReturnsTheFirstAndIsOnlyForThePreview() {
        let first = manyLinks.detectFirstURL()
        XCTAssertNotNil(first)
        XCTAssertTrue(first!.contains("com.forma.illustration"),
                      "the preview card should preview the FIRST link, got \(first!)")
    }

    func testTrailingWhitespaceIsNotSwallowedIntoTheURL() {
        // The first and third lines in the report had trailing spaces. A URL
        // that absorbs one 404s when opened.
        let detector = try! NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
        let range = NSRange(manyLinks.startIndex..., in: manyLinks)
        for match in detector.matches(in: manyLinks, options: [], range: range) {
            let raw = (manyLinks as NSString).substring(with: match.range)
            XCTAssertEqual(raw, raw.trimmingCharacters(in: .whitespacesAndNewlines),
                           "a detected link carries whitespace: \(raw.debugDescription)")
        }
    }

    func testASingleLinkStillWorks() {
        let one = "look at https://example.com/page please"
        let detector = try! NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
        let range = NSRange(one.startIndex..., in: one)
        XCTAssertEqual(detector.matches(in: one, options: [], range: range).count, 1)
    }

    func testTextWithNoLinkDetectsNone() {
        let none = "no links here at all"
        let detector = try! NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
        let range = NSRange(none.startIndex..., in: none)
        XCTAssertEqual(detector.matches(in: none, options: [], range: range).count, 0)
        XCTAssertNil(none.detectFirstURL())
    }
}
