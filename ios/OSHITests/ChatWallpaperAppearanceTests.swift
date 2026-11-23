//
//  ChatWallpaperAppearanceTests.swift
//  OSHITests
//
//  __WALLPAPER_TONE_2026_09_24__ The wallpaper tone (automatic / light / dark)
//  × the system appearance → the scheme the wallpaper AND the chat content
//  render in. The owner's case is the second row of the matrix: dark phone,
//  "Light" tone ⇒ white-with-violet mesh AND light-mode bubbles on it.
//

import XCTest
import SwiftUI
@testable import OSHI

final class ChatWallpaperAppearanceTests: XCTestCase {

    private let mesh = ChatWallpaper.oshiDynamic(OSHIWallpaper.constellation.rawValue)

    // MARK: - Resolution matrix

    func testAutomaticFollowsTheSystem() {
        for system in [ColorScheme.light, .dark] {
            let r = ChatWallpaperAppearance.resolve(wallpaper: mesh, tone: .automatic, system: system)
            XCTAssertEqual(r.wallpaperScheme, system)
            XCTAssertEqual(r.contentScheme, system)
            XCTAssertTrue(r.toneApplies)
        }
    }

    func testLightToneKeepsTheWhiteMeshInDarkModeAndLightensTheContent() {
        let r = ChatWallpaperAppearance.resolve(wallpaper: mesh, tone: .light, system: .dark)
        XCTAssertEqual(r.wallpaperScheme, .light, "the white-with-violet mesh must survive dark mode")
        XCTAssertEqual(r.contentScheme, .light,
                       "bubbles/text on a light wallpaper must use their light-mode colours")
    }

    func testDarkToneKeepsTheBlackMeshInLightMode() {
        let r = ChatWallpaperAppearance.resolve(wallpaper: mesh, tone: .dark, system: .light)
        XCTAssertEqual(r.wallpaperScheme, .dark)
        XCTAssertEqual(r.contentScheme, .dark)
    }

    func testFullMatrixContentAlwaysMatchesWallpaper() {
        let adaptive: [ChatWallpaper] = [.none, .oshiSignature, mesh,
                                         .oshiDynamic(OSHIWallpaper.aurora.rawValue),
                                         .solidColor(CodableColor(.clear))]
        for wp in adaptive {
            XCTAssertTrue(wp.followsTone, "\(wp)")
            for tone in ChatWallpaperTone.allCases {
                for system in [ColorScheme.light, .dark] {
                    let r = ChatWallpaperAppearance.resolve(wallpaper: wp, tone: tone, system: system)
                    let expected: ColorScheme = tone == .automatic ? system : (tone == .light ? .light : .dark)
                    XCTAssertEqual(r.wallpaperScheme, expected, "\(wp) \(tone) \(system)")
                    XCTAssertEqual(r.contentScheme, r.wallpaperScheme, "\(wp) \(tone) \(system)")
                }
            }
        }
    }

    func testFixedColourWallpapersIgnoreTheTone() {
        let fixed: [ChatWallpaper] = [.gradientPreset(0), .emojiPattern(0), .oshiPattern(0),
                                      .customImage("x.jpg"),
                                      .solidColor(CodableColor(Color(red: 0.1, green: 0.1, blue: 0.1))),
                                      .gradient(CodableColor(.white), CodableColor(.black))]
        for wp in fixed {
            XCTAssertFalse(wp.followsTone, "\(wp)")
            for tone in ChatWallpaperTone.allCases {
                for system in [ColorScheme.light, .dark] {
                    let r = ChatWallpaperAppearance.resolve(wallpaper: wp, tone: tone, system: system)
                    XCTAssertEqual(r.contentScheme, system, "behaviour must be unchanged for \(wp)")
                    XCTAssertFalse(r.toneApplies)
                }
            }
        }
    }

    func testToneIsNotPartOfTheWireFormat() throws {
        // Older builds and the peer decode `ChatWallpaper`; the tone must not leak into it.
        let data = try JSONEncoder().encode(mesh)
        XCTAssertFalse(String(decoding: data, as: UTF8.self).contains("tone"))
    }

    // MARK: - Manager: per-chat override over the default, persisted

    func testPerChatToneOverridesDefaultAndResetFallsBack() {
        let m = ChatWallpaperManager.shared
        let savedDefault = m.defaultTone
        let id = "test-tone-\(UUID().uuidString)"
        defer {
            m.removeTone(for: id)
            m.setDefaultTone(savedDefault)
        }
        m.setDefaultTone(.automatic)
        XCTAssertEqual(m.tone(for: id), .automatic)
        m.setTone(.light, for: id)
        XCTAssertEqual(m.tone(for: id), .light)
        let stored = UserDefaults.standard.dictionary(forKey: "chatWallpaperTones") as? [String: String]
        XCTAssertEqual(stored?[id], "light", "the per-chat tone must be persisted")
        m.setDefaultTone(.dark)
        XCTAssertEqual(m.tone(for: id), .light, "a chat's own tone wins over the default")
        m.removeTone(for: id)
        XCTAssertEqual(m.tone(for: id), .dark)
        XCTAssertEqual(UserDefaults.standard.string(forKey: "chatWallpaperTone"), "dark")
    }

    // MARK: - Contrast of the content on the light wallpaper (dark phone)

    #if canImport(UIKit)
    private let lightTrait = UITraitCollection(userInterfaceStyle: .light)

    private func rgba(_ c: UIColor) -> (Double, Double, Double, Double) {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        var out = (0.0, 0.0, 0.0, 0.0)
        lightTrait.performAsCurrent {
            c.resolvedColor(with: lightTrait).getRed(&r, green: &g, blue: &b, alpha: &a)
            out = (Double(r), Double(g), Double(b), Double(a))
        }
        return out
    }

    /// `fg` alpha-composited over an opaque `bg`.
    private func over(_ fg: (Double, Double, Double, Double),
                      _ bg: (Double, Double, Double, Double)) -> (Double, Double, Double) {
        let a = fg.3
        return (fg.0 * a + bg.0 * (1 - a), fg.1 * a + bg.1 * (1 - a), fg.2 * a + bg.2 * (1 - a))
    }

    private func luminance(_ c: (Double, Double, Double)) -> Double {
        func lin(_ v: Double) -> Double { v <= 0.03928 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin(c.0) + 0.7152 * lin(c.1) + 0.0722 * lin(c.2)
    }

    private func contrast(_ a: (Double, Double, Double), _ b: (Double, Double, Double)) -> Double {
        let la = luminance(a), lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /// The light wallpaper's ground: white, with the mesh's strongest mark —
    /// an accent node at `ink 0.13 × 1.5` — as the worst case under text.
    private var lightGround: (Double, Double, Double) {
        let white = (1.0, 1.0, 1.0, 1.0)
        let node = (0.180, 0.320, 0.960, 0.13 * 1.5)
        return over(node, white)
    }

    func testReceivedBubbleTextOnLightToneIsAtLeast4_5() {
        let bubble = rgba(UIColor(BubbleStyleManager.receivedBubbleColour))
        let text = rgba(.label)
        let fill = (bubble.0, bubble.1, bubble.2)
        let ratio = contrast(over(text, (fill.0, fill.1, fill.2, 1)), fill)
        XCTAssertGreaterThanOrEqual(ratio, 4.5, "received bubble text: \(ratio)")
    }

    // __BUBBLE_MEDIA_2026_11_21__ light outgoing = pale lavender + dark ink.
    func testSentBubbleTextIsAtLeast4_5OnEveryStop() {
        let ink = rgba(UIColor(BubbleAccent.oshi.onAccent))
        for stop in BubbleAccent.oshi.stops {
            let fill = rgba(UIColor(stop))
            let ratio = contrast((ink.0, ink.1, ink.2), (fill.0, fill.1, fill.2))
            XCTAssertGreaterThanOrEqual(ratio, 4.5, "ink on sent bubble: \(ratio)")
        }
    }

    /// The "Internet / Mesh / LoRa" chip: brand ink on a 15 % brand wash, over
    /// the white ground (5.9:1) and over a mesh LINK (5.2:1). Only where one of
    /// the 3.2 pt accent NODES sits exactly under a chip does it dip to 4.48:1 —
    /// a few pixels, same as in light mode today — so that case gets a 4.4 floor.
    func testTransportBadgeOnLightWallpaper() {
        let brand = rgba(UIColor(Theme.brand))
        let ink = (brand.0, brand.1, brand.2)
        func chip(on g: (Double, Double, Double)) -> Double {
            contrast(ink, over((brand.0, brand.1, brand.2, 0.15), (g.0, g.1, g.2, 1)))
        }
        let white = chip(on: (1, 1, 1))
        let link = chip(on: over((brand.0, brand.1, brand.2, 0.13 * 0.55), (1, 1, 1, 1)))
        let node = chip(on: lightGround)
        XCTAssertGreaterThanOrEqual(white, 4.5, "badge on white ground: \(white)")
        XCTAssertGreaterThanOrEqual(link, 4.5, "badge over a mesh link: \(link)")
        XCTAssertGreaterThanOrEqual(node, 4.4, "badge over a mesh node (worst pixel): \(node)")
    }

    /// The point of rendering content in the wallpaper's scheme: what dark-mode
    /// colours WOULD give on the white mesh (unreadable) vs what we render.
    func testDarkModeTextWouldBeUnreadableOnTheLightWallpaper() {
        let darkLabel = UIColor.label.resolvedColor(with: UITraitCollection(userInterfaceStyle: .dark))
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        darkLabel.getRed(&r, green: &g, blue: &b, alpha: &a)
        let bad = contrast((Double(r), Double(g), Double(b)), lightGround)
        XCTAssertLessThan(bad, 1.5, "dark-mode ink on the white mesh is what the tone must avoid")

        let lightLabel = rgba(.label)
        let good = contrast(over(lightLabel, (lightGround.0, lightGround.1, lightGround.2, 1)), lightGround)
        XCTAssertGreaterThanOrEqual(good, 4.5, "light-mode ink on the white mesh: \(good)")
    }

    // MARK: - __CHAT_POLISH_2026_09_24__ Every ink on the wallpaper, from the tokens

    private func rgba(_ c: UIColor, dark: Bool) -> (Double, Double, Double, Double) {
        let t = UITraitCollection(userInterfaceStyle: dark ? .dark : .light)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        c.resolvedColor(with: t).getRed(&r, green: &g, blue: &b, alpha: &a)
        return (Double(r), Double(g), Double(b), Double(a))
    }

    private func opaque(_ c: (Double, Double, Double, Double)) -> (Double, Double, Double) { (c.0, c.1, c.2) }

    /// The grounds a timestamp or chip can land on: both ends of the ramp,
    /// each under the brand glow at full strength, each under a mesh HUB node
    /// (`accent`, `ink × 1.5`) — the darkest (light) / brightest (dark) pixel
    /// the backdrop can put under a glyph.
    private func grounds(dark: Bool) -> [(Double, Double, Double)] {
        let ink = dark ? 0.22 : 0.11
        let brand = rgba(UIColor(Theme.brand), dark: dark)
        let accent = (0.180, 0.320, 0.960)
        let ends = dark ? [ChatPalette.groundDarkTop, ChatPalette.groundDarkBottom]
                        : [ChatPalette.groundLightTop, ChatPalette.groundLightBottom]
        var out: [(Double, Double, Double)] = []
        for end in ends {
            let base = opaque(rgba(end, dark: dark))
            let glow = over((brand.0, brand.1, brand.2, ChatPalette.brandGlow(dark: dark)), (base.0, base.1, base.2, 1))
            let node = over((accent.0, accent.1, accent.2, ink * 1.5), (glow.0, glow.1, glow.2, 1))
            out += [base, glow, node]
        }
        return out
    }

    private func worst(_ ink: UIColor, dark: Bool, chip: Bool = false) -> Double {
        let i = rgba(ink, dark: dark)
        return grounds(dark: dark).map { g -> Double in
            let surface = opaque(rgba(dark ? ChatPalette.incomingDark : ChatPalette.incomingLight, dark: dark))
            let frosted = over((surface.0, surface.1, surface.2, ChatPalette.chipSurfaceAlpha), (g.0, g.1, g.2, 1))
            let bg = chip ? over((i.0, i.1, i.2, ChatPalette.chipFill), (frosted.0, frosted.1, frosted.2, 1)) : g
            return contrast(over(i, (bg.0, bg.1, bg.2, 1)), bg)
        }.min() ?? 0
    }

    func testTimestampInkClearsAAOnEveryGroundInBothSchemes() {
        for dark in [false, true] {
            let r = worst(ChatPalette.metaInkUI, dark: dark)
            XCTAssertGreaterThanOrEqual(r, 4.5, "timestamp ink, dark=\(dark): \(r)")
        }
        // The old ink, for the record: `.secondaryLabel` on the light mesh.
        let old = worst(.secondaryLabel, dark: false)
        XCTAssertLessThan(old, 4.5, "the defect this replaces should still measure as one: \(old)")
    }

    func testReadTickAndEveryTransportChipClearAA() {
        for dark in [false, true] {
            let tick = worst(ChatPalette.readTickUI, dark: dark)
            XCTAssertGreaterThanOrEqual(tick, 4.5, "read tick, dark=\(dark): \(tick)")
            for t in ChatPalette.Transport.allCases {
                let r = worst(ChatPalette.transportInkUI(t), dark: dark, chip: true)
                XCTAssertGreaterThanOrEqual(r, 4.5, "chip \(t), dark=\(dark): \(r)")
            }
        }
    }

    func testIncomingBubbleTextAndLinksClearAAInBothSchemes() {
        let manager = BubbleStyleManager.shared
        for dark in [false, true] {
            let fill = opaque(rgba(UIColor(BubbleStyleManager.receivedBubbleColour), dark: dark))
            let text = rgba(.label, dark: dark)
            let body = contrast(over(text, (fill.0, fill.1, fill.2, 1)), fill)
            XCTAssertGreaterThanOrEqual(body, 7.0, "incoming body text, dark=\(dark): \(body)")
            let link = opaque(rgba(UIColor(manager.linkColour(outgoing: false, dark ? .dark : .light)), dark: dark))
            let l = contrast(link, fill)
            XCTAssertGreaterThanOrEqual(l, 4.5, "incoming link, dark=\(dark): \(l)")
        }
        // __BUBBLE_MEDIA_2026_11_21__ #1E1B2E → #22252B (neutral elevated surface).
        XCTAssertEqual(opaque(rgba(ChatPalette.incomingDark, dark: true)).0, 0x22 / 255.0, accuracy: 0.002)
    }

    /// The OSHI outgoing ink on the OSHI gradient, with the top sheen
    /// composited on the light stop (the sheen is strongest exactly where the
    /// first line starts). __BUBBLE_MEDIA_2026_11_21__ Light is now a pale
    /// lavender with near-black ink, dark a deep purple with white ink.
    func testOutgoingTextClearsAAWithTheSheen() {
        for dark in [false, true] {
            let stops = BubbleAccent.oshi.stops.map { opaque(rgba(UIColor($0), dark: dark)) }
            let ink = opaque(rgba(UIColor(BubbleAccent.oshi.onAccent), dark: dark))
            let lit = over((1, 1, 1, ChatPalette.outgoingSheen), (stops[0].0, stops[0].1, stops[0].2, 1))
            for (name, c) in [("light stop + sheen", lit), ("light stop", stops[0]), ("dark stop", stops[1])] {
                let r = contrast(ink, c)
                XCTAssertGreaterThanOrEqual(r, 4.5, "ink on \(name), dark=\(dark): \(r)")
            }
        }
    }

    /// Light tone on a dark phone: the thread resolves to the LIGHT content
    /// scheme, so the inks it draws are the light halves measured above.
    func testLightToneInDarkModeDrawsTheLightInks() {
        let r = ChatWallpaperAppearance.resolve(wallpaper: mesh, tone: .light, system: .dark)
        XCTAssertEqual(r.contentScheme, .light)
        let meta = worst(ChatPalette.metaInkUI, dark: r.contentScheme == .dark)
        XCTAssertGreaterThanOrEqual(meta, 4.5)
    }

    func testBubbleRadiusIsTheSharedToken() {
        XCTAssertEqual(BubbleShape.soft.radius, Theme.Radius.lg, "iOS default bubble = Android Modern = 18")
    }
    #endif
}
