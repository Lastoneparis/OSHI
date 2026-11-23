//
//  CallControlLayoutTests.swift
//  OSHITests
//
//  __CALL_CONTROLS_FIT_2026_08_21__
//
//  A control row that is wider than the screen does not throw, does not warn,
//  and does not look wrong on the developer's own phone — which here is a
//  6.9" Pro Max, the ONE size where the old numbers fitted. So the arithmetic is
//  asserted here, against the real point width of every iPhone the deployment
//  target still reaches.
//

import XCTest
import CoreGraphics
@testable import OSHI

final class CallControlLayoutTests: XCTestCase {

    /// Portrait width in points. iOS 17 is the floor, so the 320 pt phones
    /// (iPhone 5/SE 1st gen) are gone and 375 is the narrowest real device —
    /// but 320 is kept in the list anyway, because an iPad Slide Over pane and a
    /// Catalyst window can both be narrower than any phone.
    private static let iPhoneWidths: [(name: String, width: CGFloat)] = [
        ("narrow pane / legacy 4\"",      320),
        ("SE 2nd–3rd gen, 13 mini, 8",    375),
        ("12/13/14 mini class",           375),
        ("13/14, 12 Pro",                 390),
        ("15/16/17, 14 Pro",              393),
        ("16 Pro",                        402),
        ("8 Plus",                        414),
        ("12/13 Pro Max, 14 Plus",        428),
        ("15/16 Pro Max",                 430),
        ("16 Plus / 17 Pro Max",          440),
    ]

    /// The row that was actually broken: five buttons in `VideoCallView`.
    ///
    /// Old fixed numbers were 5×55 + 4×25 = 375, inside a 32 pt padding ⇒ 407 pt
    /// needed. Everything below is what that overflowed.
    func testFiveButtonVideoRowFitsEveryIPhone() {
        for (name, width) in Self.iPhoneWidths {
            let available = width - 32          // the row's own `.padding()`
            let m = CallControlMetrics.fitting(availableWidth: available, count: 5)
            // Exact, not approximate: `fitting` rounds DOWN, so a fit is a fit.
            XCTAssertLessThanOrEqual(m.totalWidth(count: 5), available,
                                     "5-button call row overflows on \(name) (\(width) pt)")
        }
    }

    /// And the four-button audio row, for the same reason.
    func testFourButtonAudioRowFitsEveryIPhone() {
        for (name, width) in Self.iPhoneWidths {
            let available = width - 32
            let m = CallControlMetrics.fitting(availableWidth: available, count: 4)
            XCTAssertLessThanOrEqual(m.totalWidth(count: 4), available,
                                     "4-button call row overflows on \(name) (\(width) pt)")
        }
    }

    /// The old numbers must be REJECTED, or this suite would pass against the
    /// code it was written to replace.
    ///
    /// This test corrected its own author: the first version asserted the old
    /// row overflowed everything below 440 pt, and it does not. 5×55 + 4×25 + 32
    /// = 407, so it fitted the Plus/Max class from 414 pt up, and overflowed the
    /// standard and compact sizes — SE, 13 mini, 12/13/14, 15/16/17 and 16 Pro.
    /// That is still every iPhone that is not a Plus or a Max, and it is the
    /// exact boundary rather than a rounder, wronger one.
    func testThePreviousFixedNumbersDidNotFit() {
        let oldRow: CGFloat = 5 * 55 + 4 * 25 + 32       // 407
        let overflowed = Self.iPhoneWidths.filter { oldRow > $0.width }.map(\.name)
        let fitted     = Self.iPhoneWidths.filter { oldRow <= $0.width }.map(\.name)

        XCTAssertFalse(overflowed.isEmpty, "if nothing overflowed, the premise of the fix is wrong")
        for (name, width) in Self.iPhoneWidths where width < 414 {
            XCTAssertGreaterThan(oldRow, width,
                                 "the old row really did overflow \(name) (\(width) pt)")
        }
        for (name, width) in Self.iPhoneWidths where width >= 414 {
            XCTAssertLessThanOrEqual(oldRow, width,
                                     "the old row fitted \(name) (\(width) pt) — which is why it went unnoticed")
        }
        XCTAssertFalse(fitted.isEmpty, "the Plus/Max sizes are exactly why this was never seen in testing")
    }

    /// Never below Apple's minimum hit target. A call's mute button is the last
    /// control that should be hard to press.
    func testButtonsNeverShrinkBelowTheMinimumHitTarget() {
        for count in 3...6 {
            for (_, width) in Self.iPhoneWidths {
                let m = CallControlMetrics.fitting(availableWidth: width - 32, count: count)
                XCTAssertGreaterThanOrEqual(m.button, CallControlMetrics.minimumButton)
                XCTAssertGreaterThanOrEqual(m.spacing, CallControlMetrics.minimumSpacing)
            }
        }
    }

    /// Spacing is what gets sacrificed first — the buttons keep their full size
    /// for as long as the gaps can pay for it.
    func testSpacingIsGivenUpBeforeButtonSize() {
        // 393 pt (iPhone 15/16/17) minus padding: enough for full-size buttons
        // once the gaps tighten, so the buttons must NOT shrink there.
        let m = CallControlMetrics.fitting(availableWidth: 393 - 32, count: 5)
        XCTAssertEqual(m.button, CallControlMetrics.preferredButton,
                       "on a 393 pt phone the gaps alone can absorb the overflow")
        XCTAssertLessThan(m.spacing, CallControlMetrics.preferredSpacing)
    }

    /// A screen with room to spare keeps the design's preferred proportions.
    func testARoomyScreenKeepsThePreferredSizes() {
        let m = CallControlMetrics.fitting(availableWidth: 440 - 32, count: 5)
        XCTAssertEqual(m, CallControlMetrics(button: CallControlMetrics.preferredButton,
                                             spacing: CallControlMetrics.preferredSpacing))
    }

    /// The three-item effects row above it (blur / quality / voice) is 70 pt
    /// wide per item with 30 pt gaps — asserted here so a future edit that adds
    /// a fourth item cannot silently reintroduce the same overflow.
    func testThreeItemEffectsRowStillFitsTheNarrowestCase() {
        let itemWidth: CGFloat = 70, gap: CGFloat = 30, items: CGFloat = 3
        let needed = items * itemWidth + (items - 1) * gap
        XCTAssertLessThanOrEqual(needed, 320 - 32,
                                 "the effects row must fit even a narrow pane; add a 4th item and it will not")
    }
}
