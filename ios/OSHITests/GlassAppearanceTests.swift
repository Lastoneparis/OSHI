//
//  GlassAppearanceTests.swift
//  OSHITests
//
//  __GLASS_ALL_THEMES_2026_11_21__ « Effet verre » in every theme: the
//  setting's default, which glass each theme gets, and that every translucent
//  surface keeps body text at WCAG AA (≥ 4.5:1) over the worst ground it can
//  sit on.
//

import XCTest
import SwiftUI
import UIKit
@testable import OSHI

final class GlassAppearanceTests: XCTestCase {

    private let keys = [GlassEffect.storageKey, GlassEffect.oledStorageKey]
    private var saved: [String: Any] = [:]

    override func setUp() {
        super.setUp()
        for k in keys {
            if let v = UserDefaults.standard.object(forKey: k) { saved[k] = v }
            UserDefaults.standard.removeObject(forKey: k)
        }
    }

    override func tearDown() {
        for k in keys {
            if let v = saved[k] { UserDefaults.standard.set(v, forKey: k) }
            else { UserDefaults.standard.removeObject(forKey: k) }
        }
        super.tearDown()
    }

    // MARK: - Setting

    /// Same defaults as Android / desktop: opt-in for Light and Dark (nobody's
    /// look changes on update), on for OLED black (glass since 16/11).
    func testDefaultsOffForLightDarkOnForOLED() {
        XCTAssertFalse(GlassEffect.defaultEnabled)
        XCTAssertFalse(GlassEffect.isEnabled, "an update must not change the Light / Dark look")
        XCTAssertTrue(GlassEffect.oledDefaultEnabled)
        XCTAssertTrue(GlassEffect.isOLEDEnabled, "OLED black keeps its glass")
        XCTAssertFalse(GlassEffectTrait.defaultValue)
        XCTAssertTrue(GlassEffectOLEDTrait.defaultValue)
        XCTAssertFalse(EnvironmentValues().glassEffect)
        XCTAssertTrue(EnvironmentValues().glassEffectOLED)
    }

    func testStorageKeysAreStable() {
        // Renaming a key would silently reset everyone's choice.
        XCTAssertEqual(GlassEffect.storageKey, "glassEffectEnabled")
        XCTAssertEqual(GlassEffect.oledStorageKey, "glassEffectOLEDEnabled")
        UserDefaults.standard.set(true, forKey: GlassEffect.storageKey)
        UserDefaults.standard.set(false, forKey: GlassEffect.oledStorageKey)
        XCTAssertTrue(GlassEffect.isEnabled)
        XCTAssertFalse(GlassEffect.isOLEDEnabled)
    }

    func testStyleFollowsThemeAndTheRightChoice() {
        XCTAssertEqual(GlassEffect.style(enabled: true, oledEnabled: false, oledActive: false, scheme: .light), .light)
        XCTAssertEqual(GlassEffect.style(enabled: true, oledEnabled: false, oledActive: false, scheme: .dark), .dark)
        XCTAssertEqual(GlassEffect.style(enabled: false, oledEnabled: true, oledActive: true, scheme: .dark), .oled,
                       "OLED black reads its own choice, not the Light/Dark one")
        XCTAssertEqual(GlassEffect.style(enabled: true, oledEnabled: false, oledActive: true, scheme: .dark), .none)
        for scheme in [ColorScheme.light, .dark] {
            XCTAssertEqual(GlassEffect.style(enabled: false, oledEnabled: true, oledActive: false, scheme: scheme), .none)
        }
    }

    func testEnvironmentResolvesTheLocalScheme() {
        var env = EnvironmentValues()
        XCTAssertEqual(env.glassStyle, .none, "fresh install, Light")
        env.colorScheme = .dark
        XCTAssertEqual(env.glassStyle, .none, "fresh install, Dark")
        env.oledBlack = true
        XCTAssertEqual(env.glassStyle, .oled, "fresh install, OLED black")
        env.glassEffect = true
        env.oledBlack = false
        XCTAssertEqual(env.glassStyle, .dark)
        env.colorScheme = .light
        XCTAssertEqual(env.glassStyle, .light)
        env.oledBlack = true
        XCTAssertEqual(env.glassStyle, .light, "OLED black never applies to a light subtree")
        env.colorScheme = .dark
        env.glassEffectOLED = false
        XCTAssertEqual(env.glassStyle, .none)
    }

    func testOLEDGlassTokensAreUntouched() {
        // The 16/11 OLED look must not move because the classic themes got glass.
        XCTAssertEqual(OLEDGlass.cheapFillAlpha, 0.92, accuracy: 0.001)
        XCTAssertEqual(OLEDGlass.smoke, 0.6, accuracy: 0.001)
        XCTAssertEqual(OLEDGlass.hairlineTop, 0.18, accuracy: 0.001)
    }

    // MARK: - Contrast (WCAG AA)

    /// Light: a received bubble / card is white at `lightFillAlpha` with a
    /// white sheen. The worst ground is a BLACK photo wallpaper.
    func testLightGlassKeepsAAOverTheWorstGround() {
        let light = traits(dark: false)
        let fill = UIColor.white.withAlphaComponent(CGFloat(ClassicGlass.lightFillAlpha))
        for ground in [UIColor.black, .white, UIColor(white: 0.5, alpha: 1), ChatPalette.groundLightBottom] {
            let surface = flatten(fill, on: ground)  // the sheen only lightens: worst case without it
            for (name, ink) in [("label", UIColor.label),
                                ("metaInk", ChatPalette.metaInkUI),
                                ("quoteBody", ChatPalette.quoteBodyUI),
                                ("quoteAccent", ChatPalette.quoteAccentUI)] {
                let c = ink.resolvedColor(with: light)
                XCTAssertGreaterThanOrEqual(contrast(flatten(c, on: surface), surface), 4.5,
                                            "\(name) on light glass over \(hex(ground))")
            }
        }
    }

    /// Dark: the indigo received bubble and the #1C1C1E card at
    /// `darkFillAlpha` with the sheen. The worst ground is a WHITE wallpaper.
    func testDarkGlassKeepsAAOverTheWorstGround() {
        let dark = traits(dark: true)
        let sheen = UIColor(white: 1, alpha: CGFloat(max(OLEDGlass.bubbleHighlight, ClassicGlass.darkSheen)))
        for base in [ChatPalette.incomingDark, UIColor(red: 0x1C / 255, green: 0x1C / 255, blue: 0x1E / 255, alpha: 1)] {
            let fill = base.withAlphaComponent(CGFloat(ClassicGlass.darkFillAlpha))
            for ground in [UIColor.black, .white, ChatPalette.groundDarkTop] {
                let surface = flatten(sheen, on: flatten(fill, on: ground))
                for (name, ink) in [("label", UIColor.label),
                                    ("secondaryLabel", UIColor.secondaryLabel),
                                    ("metaInk", ChatPalette.metaInkUI),
                                    ("quoteAccent", ChatPalette.quoteAccentUI)] {
                    let c = ink.resolvedColor(with: dark)
                    XCTAssertGreaterThanOrEqual(contrast(flatten(c, on: surface), surface), 4.5,
                                                "\(name) on dark glass \(hex(base)) over \(hex(ground))")
                }
            }
        }
    }

    /// Light glass must stay readable on the OSHI ground the settings and the
    /// chat list sit on when glass is on — and a card must still be told
    /// apart from it (the rim and the shadow do most of that work).
    func testLightGroundKeepsBodyTextAA() {
        let light = traits(dark: false)
        for ground in [ChatPalette.groundLightTop, ChatPalette.groundLightBottom] {
            XCTAssertGreaterThanOrEqual(contrast(UIColor.label.resolvedColor(with: light), ground), 4.5)
        }
        for ground in [ChatPalette.groundDarkTop, ChatPalette.groundDarkBottom] {
            XCTAssertGreaterThanOrEqual(contrast(UIColor.label.resolvedColor(with: traits(dark: true)), ground), 4.5)
            let sec = flatten(UIColor.secondaryLabel.resolvedColor(with: traits(dark: true)), on: ground)
            XCTAssertGreaterThanOrEqual(contrast(sec, ground), 4.5, "row previews on the dark ground")
        }
    }

    /// Cards are clearer than bubbles because they only ever sit on a ground
    /// of their own theme: light grounds in Light, dark grounds in Dark.
    func testCardsKeepAAOnTheirGrounds() {
        let light = traits(dark: false), dark = traits(dark: true)
        let lightCard = UIColor.secondarySystemGroupedBackground.resolvedColor(with: light)
            .withAlphaComponent(CGFloat(ClassicGlass.lightCardAlpha))
        for ground in [UIColor.systemGroupedBackground.resolvedColor(with: light),
                       ChatPalette.groundLightTop, ChatPalette.groundLightBottom] {
            let surface = flatten(lightCard, on: ground)
            XCTAssertGreaterThanOrEqual(contrast(UIColor.label.resolvedColor(with: light), surface), 4.5)
        }
        let darkCard = UIColor.secondarySystemGroupedBackground.resolvedColor(with: dark)
            .withAlphaComponent(CGFloat(ClassicGlass.darkCardAlpha))
        let sheen = UIColor(white: 1, alpha: CGFloat(ClassicGlass.darkSheen))
        for ground in [UIColor.black, ChatPalette.groundDarkTop, ChatPalette.groundDarkBottom] {
            let surface = flatten(sheen, on: flatten(darkCard, on: ground))
            for ink in [UIColor.label, .secondaryLabel] {
                let c = flatten(ink.resolvedColor(with: dark), on: surface)
                XCTAssertGreaterThanOrEqual(contrast(c, surface), 4.5)
            }
        }
    }

    func testIncreaseContrastRimIsVisible() {
        // Increase Contrast swaps the fading rim for a solid 35 % edge.
        let rimDark = UIColor(ClassicGlass.contrastRim(dark: true))
        XCTAssertEqual(rgba(rimDark).3, 0.35, accuracy: 0.01)
        let rimLight = UIColor(ClassicGlass.contrastRim(dark: false))
        XCTAssertEqual(rgba(rimLight).3, 0.35, accuracy: 0.01)
    }

    // MARK: - Helpers

    private func traits(dark: Bool) -> UITraitCollection {
        UITraitCollection { t in
            t.userInterfaceStyle = dark ? .dark : .light
            t.userInterfaceLevel = .base
        }
    }

    private func rgba(_ c: UIColor) -> (CGFloat, CGFloat, CGFloat, CGFloat) {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        c.getRed(&r, green: &g, blue: &b, alpha: &a)
        return (r, g, b, a)
    }

    private func hex(_ c: UIColor) -> String {
        let (r, g, b, _) = rgba(c)
        func q(_ v: CGFloat) -> Int { Int((max(0, min(1, v)) * 255).rounded()) }
        return String(format: "#%02X%02X%02X", q(r), q(g), q(b))
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
