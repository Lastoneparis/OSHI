//
//  DynamicTypeTests.swift
//  OSHITests
//
//  `Font.scaled(_:)` exists to fix the audit's top inclusivity finding: 512
//  `.font(.system(size:))` calls, which are FROZEN — a user who turns on Larger
//  Text sees no change.
//
//  The first implementation of the helper compiled, read correctly, and scaled
//  nothing: its `with(relativeTo:)` returned `self`. That is exactly the failure
//  this file exists to catch — a fix that looks right at every call site and
//  does nothing at runtime.
//

import XCTest
import SwiftUI
@testable import OSHI

final class DynamicTypeTests: XCTestCase {

    #if os(iOS)

    /// 🔧 (2026-08-21) Catalyst satisfait `os(iOS)` mais n'implémente PAS les
    /// catégories de taille de contenu iOS: `UIFontMetrics.scaledValue(for:
    /// compatibleWith:)` y renvoie la même valeur pour `.large` et pour
    /// `.accessibilityExtraLarge`. Ces tests échouaient donc sur Catalyst en
    /// affirmant « la taille est gelée » — vrai sur macOS, et sans rapport avec
    /// le code testé.
    ///
    /// Ça comptait parce que Catalyst est le REPLI quand les simulateurs de
    /// cette machine sont saturés: une suite qui y est rouge pour des raisons de
    /// plateforme rend le repli inutilisable au moment où on en a besoin.
    ///
    /// L'assertion iOS est intégralement préservée — c'est bien elle qui a
    /// attrapé le helper décoratif décrit en tête de fichier.
    override func setUpWithError() throws {
        #if targetEnvironment(macCatalyst)
        throw XCTSkip("Les catégories de taille de contenu iOS ne sont pas implémentées sur Catalyst.")
        #endif
    }

    /// Point size at a given accessibility setting, straight from the same
    /// metrics `Font.scaled` uses.
    private func size(_ pt: CGFloat, at category: UIContentSizeCategory) -> CGFloat {
        UIFontMetrics(forTextStyle: Font.uiTextStyle(for: pt))
            .scaledValue(for: pt,
                         compatibleWith: UITraitCollection(preferredContentSizeCategory: category))
    }

    /// THE test. If this fails the helper is decorative.
    func testTextActuallyGrowsWithLargerText() {
        for pt in [11, 13, 15, 17, 20, 28] as [CGFloat] {
            let normal = size(pt, at: .large)
            let large = size(pt, at: .accessibilityExtraLarge)
            XCTAssertGreaterThan(large, normal,
                                 "\(pt)pt did not grow — the size is frozen")
        }
    }

    /// And shrinks, for users who prefer smaller text.
    func testTextShrinksWithSmallerSetting() {
        let normal = size(17, at: .large)
        let small = size(17, at: .extraSmall)
        XCTAssertLessThan(small, normal)
    }

    /// At the default setting the scaled size must equal the value written at
    /// the call site — otherwise adopting the helper silently re-lays-out every
    /// screen for users who changed nothing.
    func testDefaultSettingIsUnchanged() {
        for pt in [10, 12, 15, 17, 22, 34] as [CGFloat] {
            XCTAssertEqual(size(pt, at: .large), pt, accuracy: 0.5,
                           "\(pt)pt moved at the default content size")
        }
    }

    /// Growth must stay proportional. A badge glyph that scales like a title
    /// bursts out of the circle it sits in — which is how a well-meant
    /// accessibility change breaks a layout.
    func testSmallTextDoesNotOutgrowLargeText() {
        let smallGrowth = size(11, at: .accessibilityExtraLarge) / size(11, at: .large)
        let bodyGrowth = size(17, at: .accessibilityExtraLarge) / size(17, at: .large)
        XCTAssertLessThan(smallGrowth, bodyGrowth * 1.35,
                          "small text grows disproportionately faster than body")
    }

    /// The style mapping should pick a neighbouring style, not an arbitrary one.
    func testStyleMappingPicksSomethingNear() {
        XCTAssertEqual(Font.uiTextStyle(for: 11), .caption2)
        XCTAssertEqual(Font.uiTextStyle(for: 13), .footnote)
        XCTAssertEqual(Font.uiTextStyle(for: 17), .body)
        XCTAssertEqual(Font.uiTextStyle(for: 28), .title1)
    }

    #endif
}

// MARK: - The in-app banner must stay on the screen
//
// Reported 2026-10-12: "when I am in a conversation and receive a long message,
// the internal notification doesn't display well, the message overflows the
// screen left and right."
//
// `DynamicIslandNotificationView` clamps the black capsule with
// `.frame(maxWidth: 360)`, but the text column carried
// `.fixedSize(horizontal: true, vertical: false)` with `.lineLimit(1)`.
// `fixedSize` tells SwiftUI to give the child its IDEAL width and never
// propose less, so a one-line Text asks for the whole message on a single
// line. The HStack then grows past the frame; a frame does not clip, and the
// banner is centre-aligned, so the text ran off BOTH edges.
//
// Measured on an iPhone 17 simulator (402 x 874 pt) with the real
// `Font.scaled` metrics, as the width the child demands inside a 360pt frame:
//
//      message        shipped      fixed
//      2 chars         126.0       126.0
//      33 chars        276.7       276.7
//      154 chars      1002.7       352.0     <- 321 pt past EACH edge
//      488 chars      3037.3       358.3     <- 1339 pt past EACH edge
//
// Which is why only LONG messages showed it: short ones never reach 360.

extension DynamicTypeTests {

    func testTheIslandBannerDoesNotForceItsTextColumnToIdealWidth() throws {
        let here = URL(fileURLWithPath: #filePath)
        let src = here.deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("OSHI/DynamicIslandNotification.swift")
        guard let text = try? String(contentsOf: src, encoding: .utf8) else {
            throw XCTSkip("source not reachable from the test host")
        }
        let body = text.split(separator: "\n", omittingEmptySubsequences: false)
            .filter { let t = $0.trimmingCharacters(in: .whitespaces)
                      return !t.hasPrefix("//") && !t.hasPrefix("///") && !t.hasPrefix("*") }
            .joined(separator: "\n")

        XCTAssertFalse(body.contains("fixedSize(horizontal: true"),
                       "fixedSize(horizontal: true) makes the preview demand its full single-line width and run off both screen edges")
        XCTAssertTrue(body.contains("maxWidth: expanded ? 360 : islandWidth"),
                      "the capsule must stay clamped")
        XCTAssertTrue(body.contains("truncationMode(.tail)"),
                      "a clamped preview has to truncate rather than overflow")
        XCTAssertTrue(body.contains(".padding(.horizontal, 16)"),
                      "the banner needs a side gutter so the capsule never touches the screen edge")
    }

    /// The preview handed to SwiftUI must be bounded too: a pasted message can be
    /// 16 KB (see `oshi-long-text-split-was-the-lora-budget`), and two truncated
    /// lines never need more than a couple of hundred characters.
    func testTheIslandBannerClampsThePreviewItIsGiven() throws {
        let here = URL(fileURLWithPath: #filePath)
        let src = here.deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("OSHI/DynamicIslandNotification.swift")
        guard let text = try? String(contentsOf: src, encoding: .utf8) else {
            throw XCTSkip("source not reachable from the test host")
        }
        XCTAssertTrue(text.contains("preview.count > 200"),
                      "showIncoming must clamp the preview before it reaches the layout engine")
    }
}
