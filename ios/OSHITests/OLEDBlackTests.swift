//
//  OLEDBlackTests.swift
//  OSHITests
//
//  __OLED_BLACK_2026_11_16__ « Noir OLED »: the setting's default, when it
//  applies, and what it paints (true black ground, readable received bubble,
//  text contrast ≥ WCAG AA, brand untouched).
//

import XCTest
import SwiftUI
import UIKit
@testable import OSHI

final class OLEDBlackTests: XCTestCase {

    private let key = OLEDBlack.storageKey
    private var saved: Any?

    override func setUp() {
        super.setUp()
        saved = UserDefaults.standard.object(forKey: key)
        UserDefaults.standard.removeObject(forKey: key)
    }

    override func tearDown() {
        if let saved { UserDefaults.standard.set(saved, forKey: key) }
        else { UserDefaults.standard.removeObject(forKey: key) }
        super.tearDown()
    }

    // MARK: - Setting

    func testDefaultIsOff() {
        XCTAssertFalse(OLEDBlack.defaultEnabled)
        XCTAssertFalse(OLEDBlack.isEnabled, "a fresh install must not turn OLED black on")
    }

    func testStorageKeyIsStable() {
        // Renaming the key would silently switch every user back off.
        XCTAssertEqual(OLEDBlack.storageKey, "oledBlackEnabled")
    }

    func testPersistsThroughUserDefaults() {
        UserDefaults.standard.set(true, forKey: key)
        XCTAssertTrue(OLEDBlack.isEnabled)
        UserDefaults.standard.set(false, forKey: key)
        XCTAssertFalse(OLEDBlack.isEnabled)
    }

    // MARK: - When it applies

    func testAppliesWithDarkThemeOrSystemDarkOnly() {
        // enabled × theme × system
        XCTAssertTrue(OLEDBlack.isActive(enabled: true, appTheme: "dark", systemScheme: .light))
        XCTAssertTrue(OLEDBlack.isActive(enabled: true, appTheme: "dark", systemScheme: .dark))
        XCTAssertTrue(OLEDBlack.isActive(enabled: true, appTheme: "system", systemScheme: .dark))
        XCTAssertFalse(OLEDBlack.isActive(enabled: true, appTheme: "system", systemScheme: .light))
        XCTAssertFalse(OLEDBlack.isActive(enabled: true, appTheme: "light", systemScheme: .dark),
                       "the Light theme is never painted black")
        for theme in ["system", "light", "dark"] {
            for scheme in [ColorScheme.light, .dark] {
                XCTAssertFalse(OLEDBlack.isActive(enabled: false, appTheme: theme, systemScheme: scheme))
            }
        }
    }

    func testTraitActivationNeedsChoiceAndDarkStyle() {
        XCTAssertTrue(OLEDBlack.isActive(in: traits(dark: true, oled: true)))
        XCTAssertFalse(OLEDBlack.isActive(in: traits(dark: false, oled: true)))
        XCTAssertFalse(OLEDBlack.isActive(in: traits(dark: true, oled: false)))
    }

    func testEnvironmentBridgesToTheTrait() {
        var traits = UITraitCollection.current.modifyingTraits { _ in }
        traits = traits.modifyingTraits { OLEDBlackEnvironmentKey.write(to: &$0, value: true) }
        XCTAssertTrue(OLEDBlackEnvironmentKey.read(from: traits))
        XCTAssertTrue(traits[OLEDBlackTrait.self])
        XCTAssertFalse(OLEDBlackTrait.defaultValue)
    }

    // MARK: - Palette

    func testSurfacesResolveToTrueBlackAndNearBlack() {
        let oled = traits(dark: true, oled: true)
        XCTAssertEqual(hex(OLEDBlack.backgroundUI.resolvedColor(with: oled)), 0x000000)
        XCTAssertEqual(hex(ChatPalette.incomingUI.resolvedColor(with: oled)), 0x1C1C1E)
        XCTAssertEqual(hex(ChatPalette.quoteSurfaceUI.resolvedColor(with: oled)), 0x1C1C1E)
        // The dynamic helper hands back black only when active.
        let d = OLEDBlack.dynamic(.systemTeal, oled: .black)
        XCTAssertEqual(hex(d.resolvedColor(with: oled)), 0x000000)
        XCTAssertNotEqual(hex(d.resolvedColor(with: traits(dark: true, oled: false))), 0x000000)
    }

    func testOffLeavesTheExistingPaletteUntouched() {
        XCTAssertEqual(hex(ChatPalette.incomingUI.resolvedColor(with: traits(dark: true, oled: false))),
                       hex(ChatPalette.incomingDark))
        XCTAssertEqual(hex(ChatPalette.incomingUI.resolvedColor(with: traits(dark: false, oled: true))),
                       hex(ChatPalette.incomingLight), "a light screen ignores the choice")
    }

    func testTextContrastMeetsWCAGAAOnBlackAndOnTheBubble() {
        let oled = traits(dark: true, oled: true)
        let black = UIColor.black
        let bubble = ChatPalette.incomingUI.resolvedColor(with: oled)
        for (name, ink) in [("label", UIColor.label),
                            ("secondaryLabel", UIColor.secondaryLabel),
                            ("metaInk", ChatPalette.metaInkUI),
                            ("quoteBody", ChatPalette.quoteBodyUI),
                            ("quoteAccent", ChatPalette.quoteAccentUI)] {
            let c = ink.resolvedColor(with: oled)
            // secondaryLabel is translucent; flatten it on the ground first.
            XCTAssertGreaterThanOrEqual(contrast(flatten(c, on: black), black), 4.5, "\(name) on black")
            XCTAssertGreaterThanOrEqual(contrast(flatten(c, on: bubble), bubble), 4.5, "\(name) on bubble")
        }
        // The received bubble must still be distinguishable from the ground.
        XCTAssertGreaterThan(contrast(bubble, black), 1.15)
    }

    // MARK: - Crystal glass (__OLED_GLASS_2026_11_16__)

    func testGlassTokensAreTheSpec() {
        XCTAssertEqual(OLEDGlass.hairlineTop, 0.18, accuracy: 0.001)
        XCTAssertEqual(OLEDGlass.hairlineBottom, 0.04, accuracy: 0.001)
        XCTAssertGreaterThanOrEqual(OLEDGlass.hairlineWidth, 0.5)
        XCTAssertLessThanOrEqual(OLEDGlass.hairlineWidth, 1.0)
        // Reduce Transparency falls back to the opaque #1C1C1E surface.
        XCTAssertEqual(hex(UIColor(OLEDGlass.solid)), 0x1C1C1E)
    }

    /// The received bubble is translucent glass under OLED black. On the black
    /// ground and on the WORST wallpaper (a white photo) every ink drawn on it
    /// must clear WCAG AA, sheen included.
    func testGlassBubbleKeepsAAOnTheWorstWallpaper() {
        let oled = traits(dark: true, oled: true)
        let glassFill = OLEDBlack.incomingBubbleUI.withAlphaComponent(CGFloat(OLEDGlass.cheapFillAlpha))
        let sheen = UIColor(white: 1, alpha: CGFloat(OLEDGlass.bubbleHighlight))
        for ground in [UIColor.black, UIColor.white] {
            let surface = flatten(sheen, on: flatten(glassFill, on: ground))
            for (name, ink) in [("label", UIColor.label),
                                ("secondaryLabel", UIColor.secondaryLabel),
                                ("metaInk", ChatPalette.metaInkUI),
                                ("quoteAccent", ChatPalette.quoteAccentUI)] {
                let c = ink.resolvedColor(with: oled)
                XCTAssertGreaterThanOrEqual(contrast(flatten(c, on: surface), surface), 4.5,
                                            "\(name) on glass bubble over \(ground == .black ? "black" : "white")")
            }
        }
    }

    func testBrandPurpleIsKept() {
        // The accent is not part of the OLED switch.
        XCTAssertEqual(hex(UIColor(Theme.brand)), hex(UIColor(red: 0.337, green: 0.086, blue: 0.925, alpha: 1)))
    }

    // MARK: - Helpers

    private func traits(dark: Bool, oled: Bool) -> UITraitCollection {
        UITraitCollection { t in
            t.userInterfaceStyle = dark ? .dark : .light
            t.userInterfaceLevel = .base
            t[OLEDBlackTrait.self] = oled
        }
    }

    private func rgba(_ c: UIColor) -> (CGFloat, CGFloat, CGFloat, CGFloat) {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        c.getRed(&r, green: &g, blue: &b, alpha: &a)
        return (r, g, b, a)
    }

    private func hex(_ c: UIColor) -> UInt32 {
        let (r, g, b, _) = rgba(c)
        func q(_ v: CGFloat) -> UInt32 { UInt32((max(0, min(1, v)) * 255).rounded()) }
        return q(r) << 16 | q(g) << 8 | q(b)
    }

    private func flatten(_ c: UIColor, on ground: UIColor) -> UIColor {
        let (r, g, b, a) = rgba(c), (R, G, B, _) = rgba(ground)
        return UIColor(red: r * a + R * (1 - a), green: g * a + G * (1 - a), blue: b * a + B * (1 - a), alpha: 1)
    }

    private func luminance(_ c: UIColor) -> CGFloat {
        let (r, g, b, _) = rgba(c)
        func lin(_ v: CGFloat) -> CGFloat { v <= 0.03928 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    }

    private func contrast(_ a: UIColor, _ b: UIColor) -> CGFloat {
        let la = luminance(a), lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}
