//
//  MessageFormattingTests.swift
//  OSHITests
//
//  __TEXT_FORMATTING_2026_11_24__ `**bold**` / `__underline__`. The golden
//  vectors below are the SAME cases as the Android suite: the two platforms
//  must render one message identically, so any divergence is a bug.
//

import XCTest
import SwiftUI
@testable import OSHI

final class MessageFormattingTests: XCTestCase {

    private struct Golden {
        let input: String
        let text: String
        let bold: [NSRange]
        let underline: [NSRange]
    }

    private static func r(_ a: Int, _ b: Int) -> NSRange { NSRange(location: a, length: b - a) }

    /// Shared spec, table "Golden test vectors" — keep in sync with Android.
    private static let golden: [Golden] = [
        Golden(input: "hello", text: "hello", bold: [], underline: []),
        Golden(input: "**hi**", text: "hi", bold: [r(0, 2)], underline: []),
        Golden(input: "__hi__", text: "hi", bold: [], underline: [r(0, 2)]),
        Golden(input: "a **b** c", text: "a b c", bold: [r(2, 3)], underline: []),
        Golden(input: "**__x__**", text: "x", bold: [r(0, 1)], underline: [r(0, 1)]),
        Golden(input: "__**x**__", text: "x", bold: [r(0, 1)], underline: [r(0, 1)]),
        Golden(input: "**a** and __b__", text: "a and b", bold: [r(0, 1)], underline: [r(6, 7)]),
        Golden(input: "** a**", text: "** a**", bold: [], underline: []),
        Golden(input: "**a **", text: "**a **", bold: [], underline: []),
        Golden(input: "****", text: "****", bold: [], underline: []),
        Golden(input: "**a\nb**", text: "**a\nb**", bold: [], underline: []),
        Golden(input: "**unclosed", text: "**unclosed", bold: [], underline: []),
        Golden(input: "2**3 and 4**5", text: "23 and 45", bold: [r(1, 8)], underline: []),
        Golden(input: "https://x.com/a__b__c", text: "https://x.com/a__b__c", bold: [], underline: []),
        Golden(input: "snake_case_name", text: "snake_case_name", bold: [], underline: []),
    ]

    func testGoldenVectors() {
        for g in Self.golden {
            let out = MessageFormatting.parse(g.input)
            XCTAssertEqual(out.text, g.text, "text for \(g.input.debugDescription)")
            XCTAssertEqual(out.bold, g.bold, "bold for \(g.input.debugDescription)")
            XCTAssertEqual(out.underline, g.underline, "underline for \(g.input.debugDescription)")
        }
    }

    /// The bubble goes through `InlineMarkup` (this pass + the iOS single
    /// markers). For every golden vector it must show the same text and the
    /// same bold / underline runs.
    func testBubbleParserAgreesWithGoldenVectors() {
        for g in Self.golden {
            let (plain, spans) = InlineMarkup.parse(g.input)
            XCTAssertEqual(plain, g.text, "bubble text for \(g.input.debugDescription)")
            let bold = spans.filter { $0.kind == .bold }.map { NSRange(location: $0.location, length: $0.length) }
            let under = spans.filter { $0.kind == .underline }.map { NSRange(location: $0.location, length: $0.length) }
            XCTAssertEqual(bold, g.bold, "bubble bold for \(g.input.debugDescription)")
            XCTAssertEqual(under, g.underline, "bubble underline for \(g.input.debugDescription)")
            XCTAssertEqual(spans.count, g.bold.count + g.underline.count, g.input)
        }
    }

    func testStrippedForPlainSurfaces() {
        XCTAssertEqual(InlineMarkup.stripped("**Salut** __toi__"), "Salut toi")
        XCTAssertEqual(MessageFormatting.stripped("a **b** c"), "a b c")
        XCTAssertEqual(InlineMarkup.stripped("2 ** 3"), "2 ** 3")
    }

    func testUTF16OffsetsWithEmoji() {
        let out = MessageFormatting.parse("🙂 **été** __日本__")
        XCTAssertEqual(out.text, "🙂 été 日本")
        let ns = out.text as NSString
        XCTAssertEqual(out.bold.map { ns.substring(with: $0) }, ["été"])
        XCTAssertEqual(out.underline.map { ns.substring(with: $0) }, ["日本"])
    }

    func testBoldLinkKeepsTheURLIntact() {
        let out = MessageFormatting.parse("**https://x.com/a__b__c**")
        XCTAssertEqual(out.text, "https://x.com/a__b__c")
        XCTAssertEqual(out.bold, [NSRange(location: 0, length: 21)])
        XCTAssertTrue(out.underline.isEmpty)
        XCTAssertEqual(MessageFormatting.parse("see www.a.com/__x__ ok").text, "see www.a.com/__x__ ok")
    }

    /// Spec: only U+0020 counts for the no-edge-space rule (Android parity).
    func testOnlyASpaceBlocksTheEdges() {
        XCTAssertEqual(MessageFormatting.parse("**\ta**").text, "\ta")
        XCTAssertEqual(MessageFormatting.parse("** a**").text, "** a**")
    }

    /// The single-marker pass must not italicise inside a URL either.
    func testSingleMarkersSkipURLs() {
        let s = "https://x.com/a_b_c and https://x.com/_d_"
        XCTAssertEqual(InlineMarkup.parse(s).0, s)
        XCTAssertTrue(InlineMarkup.parse(s).1.isEmpty)
    }

    func testMentionInsideFormatting() {
        let out = MessageFormatting.parse("__@bob__ hi")
        XCTAssertEqual(out.text, "@bob hi")
        XCTAssertEqual(out.underline, [NSRange(location: 0, length: 4)])
    }

    /// Single markers still work and land on the right characters when mixed
    /// with the double ones (the double ranges are shifted by removed singles).
    func testMixedWithLegacySingleMarkers() {
        let (plain, spans) = InlineMarkup.parse("_it_ then **bold** and __u__")
        XCTAssertEqual(plain, "it then bold and u")
        let ns = plain as NSString
        func text(_ k: InlineMarkup.Kind) -> [String] {
            spans.filter { $0.kind == k }.map { ns.substring(with: NSRange(location: $0.location, length: $0.length)) }
        }
        XCTAssertEqual(text(.italic), ["it"])
        XCTAssertEqual(text(.bold), ["bold"])
        XCTAssertEqual(text(.underline), ["u"])
    }

    /// The composed bubble carries bold as a strong-emphasis intent and
    /// underline as an underline style, and keeps links tappable.
    func testComposedBubbleStylesAndLinks() {
        LinkDetection.resetCache()
        let raw = "**gras** __souligné__ https://oshi.app"
        let plain = InlineMarkup.parse(raw).0
        XCTAssertEqual(plain, "gras souligné https://oshi.app")
        let result = LinkDetection.scan(plain: plain)
        let a = LinkDetection.compose(text: raw, textColor: .primary, linkColor: .blue, detected: result)
        XCTAssertEqual(String(a.characters), plain)
        let boldRun = a.runs.first { String(a[$0.range].characters) == "gras" }
        XCTAssertEqual(boldRun?.inlinePresentationIntent, .stronglyEmphasized)
        let underRun = a.runs.first { String(a[$0.range].characters) == "souligné" }
        XCTAssertNotNil(underRun?.underlineStyle)
        XCTAssertTrue(a.runs.contains { $0.link != nil }, "link lost")
    }

    func testLongInputIsFast() {
        let text = String(repeating: "**a** __b__ ** c ", count: 3_000)
        let started = Date()
        _ = InlineMarkup.parse(text)
        XCTAssertLessThan(Date().timeIntervalSince(started), 1.0)
    }

    // MARK: - Composer actions

    func testWrapSelectionKeepsInnerTextSelected() {
        let e = ComposerFormatting.apply(.bold, to: "say hello now", selection: NSRange(location: 4, length: 5))
        XCTAssertEqual(e.text, "say **hello** now")
        XCTAssertEqual(e.selection, NSRange(location: 6, length: 5))
        let u = ComposerFormatting.apply(.underline, to: "say hello now", selection: NSRange(location: 4, length: 5))
        XCTAssertEqual(u.text, "say __hello__ now")
        XCTAssertEqual(u.selection, NSRange(location: 6, length: 5))
        XCTAssertEqual(MessageFormatting.parse(e.text).bold, [NSRange(location: 4, length: 5)])
    }

    func testInsertPairWithoutSelection() {
        let e = ComposerFormatting.apply(.bold, to: "hi ", selection: NSRange(location: 3, length: 0))
        XCTAssertEqual(e.text, "hi ****")
        XCTAssertEqual(e.selection, NSRange(location: 5, length: 0))
        let empty = ComposerFormatting.apply(.underline, to: "", selection: NSRange(location: 0, length: 0))
        XCTAssertEqual(empty.text, "____")
        XCTAssertEqual(empty.selection, NSRange(location: 2, length: 0))
        let mid = ComposerFormatting.apply(.bold, to: "ab", selection: NSRange(location: 1, length: 0))
        XCTAssertEqual(mid.text, "a****b")
        XCTAssertEqual(mid.selection, NSRange(location: 3, length: 0))
    }

    /// A selection with surrounding spaces would make an invalid span; the
    /// spaces stay outside the markers.
    func testWrapTrimsWhitespaceOutOfTheMarkers() {
        let e = ComposerFormatting.apply(.bold, to: "a hello b", selection: NSRange(location: 1, length: 7))
        XCTAssertEqual(e.text, "a **hello** b")
        XCTAssertEqual(e.selection, NSRange(location: 4, length: 5))
    }

    func testWrapAgainUnwraps() {
        let on = ComposerFormatting.apply(.bold, to: "x hello", selection: NSRange(location: 2, length: 5))
        let off = ComposerFormatting.apply(.bold, to: on.text, selection: on.selection)
        XCTAssertEqual(off.text, "x hello")
        XCTAssertEqual(off.selection, NSRange(location: 2, length: 5))
    }

    func testNestedWrapRendersBoth() {
        let b = ComposerFormatting.apply(.bold, to: "word", selection: NSRange(location: 0, length: 4))
        let bu = ComposerFormatting.apply(.underline, to: b.text, selection: b.selection)
        XCTAssertEqual(bu.text, "**__word__**")
        let out = MessageFormatting.parse(bu.text)
        XCTAssertEqual(out.text, "word")
        XCTAssertEqual(out.bold, [NSRange(location: 0, length: 4)])
        XCTAssertEqual(out.underline, [NSRange(location: 0, length: 4)])
    }

    func testOutOfRangeSelectionIsClamped() {
        let e = ComposerFormatting.apply(.bold, to: "ab", selection: NSRange(location: 10, length: 5))
        XCTAssertEqual(e.text, "ab****")
        XCTAssertEqual(e.selection, NSRange(location: 4, length: 0))
    }

    func testWhitespaceOnlySelectionInsertsPair() {
        let e = ComposerFormatting.apply(.underline, to: "a  b", selection: NSRange(location: 1, length: 2))
        XCTAssertEqual(e.text, "a  ____b")
        XCTAssertEqual(e.selection, NSRange(location: 5, length: 0))
    }
}
