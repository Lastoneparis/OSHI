//
//  BubbleMediaLayoutTests.swift
//  OSHITests
//
//  __BUBBLE_MEDIA_2026_11_21__ BUBBLE_MEDIA_SPEC §1 (media sizing) and §2
//  (bubble colours, measured): the pure layout function and every ink/surface
//  pair of the new Light / Dark / OLED bubble palette.
//

import XCTest
import SwiftUI
import UIKit
import ImageIO
import UniformTypeIdentifiers
@testable import OSHI

final class BubbleMediaLayoutTests: XCTestCase {

    private let L = BubbleMediaLayout.self

    // MARK: - Max width

    func testChatMaxWidthIs75PercentCappedAt420() {
        XCTAssertEqual(L.maxWidth(containerWidth: 402, channel: false), 301)   // iPhone 17 Pro
        XCTAssertEqual(L.maxWidth(containerWidth: 375, channel: false), 281)
        XCTAssertEqual(L.maxWidth(containerWidth: 1366, channel: false), 420)  // iPad / Mac
    }

    func testChannelMaxWidthIs90PercentCappedAt560() {
        XCTAssertEqual(L.maxWidth(containerWidth: 370, channel: true), 333)
        XCTAssertEqual(L.maxWidth(containerWidth: 1366, channel: true), 560)
    }

    func testInvalidContainerFallsBackToTheMinimum() {
        XCTAssertEqual(L.maxWidth(containerWidth: 0, channel: false), L.minWidth)
        XCTAssertEqual(L.maxWidth(containerWidth: .nan, channel: true), L.minWidth)
    }

    // MARK: - Display size

    func testLandscapeFillsTheMaxWidthAtItsRatio() {
        let r = L.displaySize(natural: CGSize(width: 1600, height: 1000), maxWidth: 300)
        XCTAssertEqual(r.size, CGSize(width: 300, height: 188))
        XCTAssertFalse(r.cropped)
    }

    /// THE side-gap case: a portrait used to be ~187 pt wide in a caption-wide
    /// bubble. It now takes the full bubble width, and is cropped to 1.3:1.
    func testPortraitTakesTheFullWidthAndIsCroppedTo1_3() {
        let r = L.displaySize(natural: CGSize(width: 1000, height: 1600), maxWidth: 300)
        XCTAssertEqual(r.size.width, 300, "the bubble width IS the picture width")
        XCTAssertEqual(r.size.height, 390)
        XCTAssertTrue(r.cropped)
    }

    func testRatioInsideTheRangeIsKeptExactly() {
        let r = L.displaySize(natural: CGSize(width: 1200, height: 1500), maxWidth: 300)   // 1.25
        XCTAssertEqual(r.size, CGSize(width: 300, height: 375))
        XCTAssertFalse(r.cropped)
    }

    func testPanoramaIsCroppedToHalfTheWidth() {
        let r = L.displaySize(natural: CGSize(width: 2400, height: 700), maxWidth: 300)
        XCTAssertEqual(r.size, CGSize(width: 300, height: 150))
        XCTAssertTrue(r.cropped)
    }

    func testSmallImageIsScaledUpToTheMinimumWidth() {
        let r = L.displaySize(natural: CGSize(width: 160, height: 120), maxWidth: 300)
        XCTAssertEqual(r.size, CGSize(width: 200, height: 150))
        XCTAssertFalse(r.cropped)
    }

    func testMidSizedImageKeepsItsOwnWidth() {
        let r = L.displaySize(natural: CGSize(width: 240, height: 240), maxWidth: 300)
        XCTAssertEqual(r.size, CGSize(width: 240, height: 240))
    }

    func testMaxWidthBelowTheMinimumWins() {
        let r = L.displaySize(natural: CGSize(width: 100, height: 100), maxWidth: 150)
        XCTAssertEqual(r.size, CGSize(width: 150, height: 150))
    }

    func testUnknownSizeGivesAStable4by3Placeholder() {
        for bad in [CGSize.zero, CGSize(width: -1, height: 5), CGSize(width: CGFloat.nan, height: 3),
                    CGSize(width: 10, height: CGFloat.infinity)] {
            let r = L.displaySize(natural: bad, maxWidth: 300)
            XCTAssertEqual(r.size, CGSize(width: 300, height: 225), "\(bad)")
            XCTAssertFalse(r.cropped)
        }
    }

    func testHeightAlwaysWithinTheClampForManyRatios() {
        for w in stride(from: 50.0, through: 5000, by: 370) {
            for h in stride(from: 50.0, through: 5000, by: 410) {
                let r = L.displaySize(natural: CGSize(width: w, height: h), maxWidth: 301).size
                XCTAssertLessThanOrEqual(r.width, 301)
                XCTAssertGreaterThanOrEqual(r.width, 200)
                XCTAssertLessThanOrEqual(r.height, (r.width * 1.3).rounded() + 0.5)
                XCTAssertGreaterThanOrEqual(r.height, (r.width * 0.5).rounded() - 0.5)
            }
        }
    }

    // MARK: - Header size

    private func jpeg(width: Int, height: Int, orientation: Int?) throws -> Data {
        let img = UIGraphicsImageRenderer(size: CGSize(width: width, height: height),
                                          format: { let f = UIGraphicsImageRendererFormat(); f.scale = 1; return f }())
            .image { ctx in UIColor.red.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: width, height: height)) }
        let out = NSMutableData()
        let dest = try XCTUnwrap(CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil))
        var props: [CFString: Any] = [:]
        if let orientation { props[kCGImagePropertyOrientation] = orientation }
        CGImageDestinationAddImage(dest, try XCTUnwrap(img.cgImage), props as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(dest))
        return out as Data
    }

    func testPixelSizeReadsTheHeader() throws {
        XCTAssertEqual(L.pixelSize(of: try jpeg(width: 64, height: 40, orientation: nil)), CGSize(width: 64, height: 40))
    }

    /// A phone portrait is stored landscape + "rotate 90°": the bubble must be
    /// sized for what is SHOWN.
    func testPixelSizeAppliesExifRotation() throws {
        XCTAssertEqual(L.pixelSize(of: try jpeg(width: 64, height: 40, orientation: 6)), CGSize(width: 40, height: 64))
    }

    func testPixelSizeOfGarbageIsNil() {
        XCTAssertNil(L.pixelSize(of: Data([0, 1, 2, 3])))
        XCTAssertNil(L.pixelSize(of: Data()))
    }

    // MARK: - Source guards (the live group path)

    private static func source(_ rel: String) throws -> String {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        return try String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)
    }

    func testGroupMediaBubbleIsSizedByTheLayoutNotByAFitFrame() throws {
        let src = try Self.source("OSHI/GroupViews.swift")
        let start = try XCTUnwrap(src.range(of: "private func mediaWithCaptionBubble("))
        let body = String(src[start.lowerBound...].prefix(9000))
        XCTAssertTrue(body.contains("BubbleMediaLayout.displaySize"))
        XCTAssertTrue(body.contains(".frame(width: isVisual ? layout.size.width : nil)"),
                      "the bubble must be exactly the picture's width")
        let photo = try XCTUnwrap(src.range(of: "fileprivate struct LazyGroupPhotoView"))
        XCTAssertTrue(src[photo.lowerBound...].prefix(2500).contains(".scaledToFill()"))
    }
}

// MARK: - Colours

final class BubblePaletteContrastTests: XCTestCase {

    private func rgb(_ c: UIColor, dark: Bool, oled: Bool = false) -> (Double, Double, Double, Double) {
        var traits = UITraitCollection(userInterfaceStyle: dark ? .dark : .light)
        if oled {
            traits = traits.modifyingTraits { $0[OLEDBlackTrait.self] = true }
        }
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        c.resolvedColor(with: traits).getRed(&r, green: &g, blue: &b, alpha: &a)
        return (Double(r), Double(g), Double(b), Double(a))
    }

    private func over(_ fg: (Double, Double, Double, Double), _ bg: (Double, Double, Double, Double)) -> (Double, Double, Double, Double) {
        let a = fg.3
        return (fg.0 * a + bg.0 * (1 - a), fg.1 * a + bg.1 * (1 - a), fg.2 * a + bg.2 * (1 - a), 1)
    }

    private func lum(_ c: (Double, Double, Double, Double)) -> Double {
        func ch(_ v: Double) -> Double { v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(c.0) + 0.7152 * ch(c.1) + 0.0722 * ch(c.2)
    }

    private func contrast(_ a: (Double, Double, Double, Double), _ b: (Double, Double, Double, Double)) -> Double {
        let (x, y) = (lum(a), lum(b))
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    private func hex(_ c: (Double, Double, Double, Double)) -> UInt32 {
        (UInt32((c.0 * 255).rounded()) << 16) | (UInt32((c.1 * 255).rounded()) << 8) | UInt32((c.2 * 255).rounded())
    }

    // Spec values.

    func testTheSpecValuesAreTheOnesShipped() {
        XCTAssertEqual(hex(rgb(ChatPalette.incomingUI, dark: false)), 0xFFFFFF)
        XCTAssertEqual(hex(rgb(ChatPalette.incomingUI, dark: true)), 0x22252B)
        XCTAssertEqual(hex(rgb(ChatPalette.incomingUI, dark: true, oled: true)), 0x1C1C1E)
        XCTAssertEqual(hex(rgb(ChatPalette.outgoingTopUI, dark: false)), 0xEEE7FF)
        XCTAssertEqual(hex(rgb(ChatPalette.outgoingBottomUI, dark: false)), 0xE5DBFF)
        XCTAssertEqual(hex(rgb(ChatPalette.outgoingTopUI, dark: true)), 0x5B47B3)
        XCTAssertEqual(hex(rgb(ChatPalette.outgoingBottomUI, dark: true)), 0x4A3B8F)
        XCTAssertEqual(hex(rgb(ChatPalette.groundDarkTop, dark: true)), 0x17181C)
        XCTAssertEqual(hex(rgb(ChatPalette.groundDarkBottom, dark: true)), 0x0F1115)
    }

    /// Body text ≥ 4.5:1 on MY bubble at both gradient stops and the solid group
    /// colour, with the top sheen composited where the first line sits.
    func testOutgoingBodyTextClearsAA() {
        for dark in [false, true] {
            let ink = rgb(ChatPalette.outgoingInkUI, dark: dark)
            for surface in [ChatPalette.outgoingTopUI, ChatPalette.outgoingBottomUI, ChatPalette.outgoingSolidUI] {
                let s = rgb(surface, dark: dark)
                let lit = over((1, 1, 1, ChatPalette.outgoingSheen), s)
                for bg in [s, lit] {
                    let c = contrast(ink, bg)
                    XCTAssertGreaterThanOrEqual(c, 4.5, "outgoing ink dark=\(dark) on \(String(hex(bg), radix: 16)): \(c)")
                }
                let link = rgb(ChatPalette.outgoingAccentInkUI, dark: dark)
                XCTAssertGreaterThanOrEqual(contrast(link, s), 4.5, "outgoing link/icon dark=\(dark)")
                let meta = over(rgb(ChatPalette.outgoingSecondaryInkUI, dark: dark), s)
                XCTAssertGreaterThanOrEqual(contrast(meta, s), 4.5, "outgoing time/size dark=\(dark)")
            }
        }
    }

    func testIncomingBodyTextAndTimeClearAAInLightDarkAndOLED() {
        for (dark, oled) in [(false, false), (true, false), (true, true)] {
            let surface = rgb(ChatPalette.incomingUI, dark: dark, oled: oled)
            let body = rgb(.label, dark: dark)
            XCTAssertGreaterThanOrEqual(contrast(over(body, surface), surface), 7, "body dark=\(dark) oled=\(oled)")
            XCTAssertGreaterThanOrEqual(contrast(rgb(ChatPalette.metaInkUI, dark: dark), surface), 4.5,
                                        "in-bubble time dark=\(dark) oled=\(oled)")
            XCTAssertGreaterThanOrEqual(contrast(rgb(ChatPalette.quoteAccentUI, dark: dark), surface), 4.5,
                                        "link / comments-bar accent dark=\(dark) oled=\(oled)")
        }
    }

    /// Timestamps / ticks on the wallpaper ≥ 3:1 (we hold them to 4.5).
    func testMetaInkOnTheNewGroundsClearsAA() {
        for dark in [false, true] {
            let ends = dark ? [ChatPalette.groundDarkTop, ChatPalette.groundDarkBottom]
                            : [ChatPalette.groundLightTop, ChatPalette.groundLightBottom]
            for g in ends {
                let c = contrast(rgb(ChatPalette.metaInkUI, dark: dark), rgb(g, dark: dark))
                XCTAssertGreaterThanOrEqual(c, 4.5, "meta on ground dark=\(dark): \(c)")
            }
        }
    }

    /// Group sender names sit on the INCOMING surface: every hue ≥ 4.5:1 in
    /// light (white), dark (#22252B) and OLED (#1C1C1E).
    func testSenderNameHuesClearAAOnTheIncomingSurface() {
        for (dark, oled) in [(false, false), (true, false), (true, true)] {
            let surface = rgb(ChatPalette.incomingUI, dark: dark, oled: oled)
            let palette = dark ? AvatarIdentity.nameColorsDark : AvatarIdentity.nameColorsLight
            for (i, c) in palette.enumerated() {
                let r = contrast(rgb(UIColor(c), dark: dark), surface)
                XCTAssertGreaterThanOrEqual(r, 4.5, "name hue \(i) dark=\(dark) oled=\(oled): \(r)")
            }
        }
    }

    /// The time pill: white on black 55 % stays ≥ 4.5:1 over a pure WHITE photo.
    func testTimePillReadsOverAWhitePhoto() {
        let pill = over((0, 0, 0, MediaTimePill.backdropOpacity), (1, 1, 1, 1))
        XCTAssertGreaterThanOrEqual(contrast((1, 1, 1, 1), pill), 4.5)
        XCTAssertGreaterThanOrEqual(contrast(rgb(ChatPalette.rgb(0x86EFAC), dark: true), pill), 3.0, "read ticks")
    }

    /// Dark bubbles are never pure black outside OLED (spec §2).
    func testDarkBubblesAreNotBlackOutsideOLED() {
        for c in [ChatPalette.incomingUI, ChatPalette.outgoingTopUI, ChatPalette.outgoingBottomUI] {
            XCTAssertGreaterThan(lum(rgb(c, dark: true)), lum((0.05, 0.05, 0.05, 1)))
        }
    }

    /// Only the OSHI accent moves to dark ink in light; the other five accents
    /// keep their saturated stops and white ink.
    func testOnlyTheOSHIAccentUsesDarkInk() {
        XCTAssertEqual(hex(rgb(UIColor(BubbleAccent.oshi.onAccent), dark: false)), 0x1C1631)
        XCTAssertEqual(hex(rgb(UIColor(BubbleAccent.oshi.onAccent), dark: true)), 0xFFFFFF)
        for a in BubbleAccent.allCases where a != .oshi {
            XCTAssertEqual(hex(rgb(UIColor(a.onAccent), dark: false)), 0xFFFFFF, "\(a)")
        }
    }
}
