//
//  GlassAppearance.swift
//  OSHI
//
//  __GLASS_ALL_THEMES_2026_11_21__ « Effet verre » / "Glass effect".
//
//  A user asked: "is the blurred / translucent look of the extra-dark mode
//  possible in the classic light and dark themes too?". It is: the crystal
//  glass that OLED black introduced (OLEDBlack.swift) is now an Appearance
//  option of its own, available in every theme.
//
//  DEFAULTS (same on Android and desktop): OFF in Light and Dark — an opt-in,
//  so nobody's look changes on update — and ON with OLED black, which has had
//  the glass since 16/11. The two are stored separately
//  (`glassEffectEnabled` for Light/Dark, `glassEffectOLEDEnabled` for OLED
//  black); the Appearance switch edits the one for the look currently shown.
//
//  ONE CHOICE, THREE LOOKS
//
//    .light  frosted white glass: translucent white surfaces that let the
//            lavender ground glow through, a bright rim catching the light at
//            the top, a soft indigo shadow underneath;
//    .dark   smoked glass on the classic indigo-dark ground: translucent
//            #1C1C1E / material deepened a little, white hairline rim, sheen;
//    .oled   the original crystal glass on true black — byte-for-byte the
//            16/11 look (OLEDGlass tokens are untouched).
//
//  The same view modifiers (`oledGlassCard`, `oledGlassBar`, `oledGlassEdge`,
//  `oledAwareBackground`, `oledGlassListRow`, `oledFocusGlow`, BubbleSurface)
//  now switch on `\.glassStyle` instead of `\.oledBlackActive`, so every surface
//  that was glass under OLED black is glass in Light and Dark as well — no call
//  site had to change. The names keep their `oled` prefix for history.
//
//  ACCESSIBILITY
//
//  • Reduce Transparency → no material, no translucency: the opaque surface
//    the app had before, plus the rim (the rim is separation, not decoration).
//  • Increase Contrast → the same opaque surfaces, and a stronger rim.
//  • Contrast: every translucent fill is sized so body text, the meta ink and
//    the quote inks keep WCAG AA (≥ 4.5:1) over the WORST ground a surface can
//    sit on (a black photo wallpaper under a light bubble, a white one under a
//    dark bubble) — GlassAppearanceTests.
//

import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

enum GlassEffect {
    /// UserDefaults / @AppStorage key for the classic Light and Dark themes.
    /// Default OFF: an opt-in, existing users keep their look on update.
    static let storageKey = "glassEffectEnabled"
    static let defaultEnabled = false
    /// Key for OLED black. Default ON: OLED black has been glass since 16/11.
    static let oledStorageKey = "glassEffectOLEDEnabled"
    static let oledDefaultEnabled = true

    static var isEnabled: Bool {
        UserDefaults.standard.object(forKey: storageKey) as? Bool ?? defaultEnabled
    }
    static var isOLEDEnabled: Bool {
        UserDefaults.standard.object(forKey: oledStorageKey) as? Bool ?? oledDefaultEnabled
    }

    /// Which glass to draw, from the inputs that decide it.
    static func style(enabled: Bool, oledEnabled: Bool, oledActive: Bool, scheme: ColorScheme) -> GlassStyle {
        if oledActive { return oledEnabled ? .oled : .none }
        guard enabled else { return .none }
        return scheme == .dark ? .dark : .light
    }

    /// Whether a DARK sheet should get the crystal rim right now.
    static var rimOnDarkSheets: Bool {
        OLEDBlack.isEnabled ? isOLEDEnabled : isEnabled
    }
}

enum GlassStyle: Equatable {
    case none, light, dark, oled
    var isGlass: Bool { self != .none }
}

// MARK: - Tokens (Light / Dark — OLED keeps `OLEDGlass`)

enum ClassicGlass {
    // Light: frosted white.
    /// White card / bubble over the ground. 0.80: over a BLACK photo wallpaper
    /// the meta ink #524D66 and quote accent #5616EC still clear 4.5:1.
    static let lightFillAlpha: Double = 0.80
    /// Rim of a light pane: white at the top (the lit edge) fading to a faint
    /// indigo shade at the bottom (the edge in shadow).
    static let lightRimTop: Double = 0.95
    static let lightRimBottomInk: Double = 0.10
    static let lightShadow: Double = 0.10
    /// The faint white wash added over a material so light glass reads milky,
    /// not grey.
    static let lightMilk: Double = 0.30

    /// A card (settings, peers, new-message…) only ever sits on a LIGHT ground
    /// in Light, so it can be clearer than a bubble: more of the lavender ground
    /// shows through. Body text on it: ≥ 15:1.
    static let lightCardAlpha: Double = 0.62

    // Dark: smoked glass.
    /// #1C1C1E / the received surface at 0.96 — same reasoning as
    /// `OLEDGlass.cheapFillAlpha`: over a WHITE photo wallpaper secondary text
    /// keeps 4.5:1. __BUBBLE_MEDIA_2026_11_21__ 0.92 → 0.96: the received
    /// surface went from indigo #1E1B2E to the lighter neutral #22252B, which
    /// at 0.92 let secondary text fall to 4.2:1 over white.
    static let darkFillAlpha: Double = 0.96
    static let darkRimTop: Double = 0.16
    static let darkRimBottom: Double = 0.03
    static let darkSheen: Double = 0.06
    /// A dark card only ever sits on a DARK ground (black, #000 grouped, the
    /// indigo ramp), so it can let more of it through than a bubble.
    static let darkCardAlpha: Double = 0.66
    /// Smoke laid over the material in Dark: lighter than OLED's 0.6, the
    /// ground is indigo, not black.
    static let darkSmoke: Double = 0.22

    /// Indigo used for the shadowed edge and the drop shadow in Light — the
    /// brand's own ink rather than grey, so the glass looks tinted by the page.
    static let shadowInk = Color(red: 0x2A / 255, green: 0x1F / 255, blue: 0x4D / 255)

    static var lightRim: LinearGradient {
        LinearGradient(colors: [.white.opacity(lightRimTop), .white.opacity(0.35),
                                shadowInk.opacity(lightRimBottomInk)],
                       startPoint: .top, endPoint: .bottom)
    }
    static var darkRim: LinearGradient {
        LinearGradient(colors: [.white.opacity(darkRimTop), .white.opacity(darkRimBottom)],
                       startPoint: .top, endPoint: .bottom)
    }
    /// Increase Contrast: a plain, clearly visible edge.
    static func contrastRim(dark: Bool) -> Color {
        dark ? .white.opacity(0.35) : shadowInk.opacity(0.35)
    }
    static var darkSheenGradient: LinearGradient {
        LinearGradient(stops: [.init(color: .white.opacity(darkSheen), location: 0),
                               .init(color: .white.opacity(0), location: 0.4)],
                       startPoint: .top, endPoint: .bottom)
    }
    static var lightSheenGradient: LinearGradient {
        LinearGradient(stops: [.init(color: .white.opacity(0.55), location: 0),
                               .init(color: .white.opacity(0), location: 0.45)],
                       startPoint: .top, endPoint: .bottom)
    }
    /// Horizontal rim for a bar's top edge in Light: a hairline of shade with
    /// a white line of light just under it.
    static var lightBarShade: LinearGradient {
        LinearGradient(colors: [shadowInk.opacity(0.04), shadowInk.opacity(0.12), shadowInk.opacity(0.04)],
                       startPoint: .leading, endPoint: .trailing)
    }
    static var darkBarHairline: LinearGradient {
        LinearGradient(colors: [.white.opacity(darkRimBottom), .white.opacity(darkRimTop),
                                .white.opacity(darkRimBottom)],
                       startPoint: .leading, endPoint: .trailing)
    }

    /// The rim for a style (nil for none/oled, which draw their own).
    static func rim(_ style: GlassStyle, increasedContrast: Bool) -> AnyShapeStyle {
        switch style {
        case .light:
            return increasedContrast ? AnyShapeStyle(contrastRim(dark: false)) : AnyShapeStyle(lightRim)
        case .dark:
            return increasedContrast ? AnyShapeStyle(contrastRim(dark: true)) : AnyShapeStyle(darkRim)
        case .oled:
            return increasedContrast ? AnyShapeStyle(contrastRim(dark: true)) : AnyShapeStyle(OLEDGlass.hairline)
        case .none:
            return AnyShapeStyle(Color.clear)
        }
    }
}

#if canImport(UIKit)
// MARK: - Trait + SwiftUI bridge

/// The user's glass choices as UIKit traits (Light/Dark, and OLED black).
struct GlassEffectTrait: UITraitDefinition {
    static let defaultValue = GlassEffect.defaultEnabled
    static let affectsColorAppearance = true
    static let name = "OSHIGlassEffect"
}

struct GlassEffectOLEDTrait: UITraitDefinition {
    static let defaultValue = GlassEffect.oledDefaultEnabled
    static let affectsColorAppearance = true
    static let name = "OSHIGlassEffectOLED"
}

struct GlassEffectOLEDEnvironmentKey: EnvironmentKey, UITraitBridgedEnvironmentKey {
    static let defaultValue = GlassEffect.oledDefaultEnabled
    static func read(from traitCollection: UITraitCollection) -> Bool {
        traitCollection[GlassEffectOLEDTrait.self]
    }
    static func write(to mutableTraits: inout UIMutableTraits, value: Bool) {
        mutableTraits[GlassEffectOLEDTrait.self] = value
    }
}

struct GlassEffectEnvironmentKey: EnvironmentKey, UITraitBridgedEnvironmentKey {
    static let defaultValue = GlassEffect.defaultEnabled
    static func read(from traitCollection: UITraitCollection) -> Bool {
        traitCollection[GlassEffectTrait.self]
    }
    static func write(to mutableTraits: inout UIMutableTraits, value: Bool) {
        mutableTraits[GlassEffectTrait.self] = value
    }
}

extension EnvironmentValues {
    /// The user's glass CHOICE for Light / Dark (Settings › Appearance).
    var glassEffect: Bool {
        get { self[GlassEffectEnvironmentKey.self] }
        set { self[GlassEffectEnvironmentKey.self] = newValue }
    }

    /// The user's glass CHOICE for OLED black.
    var glassEffectOLED: Bool {
        get { self[GlassEffectOLEDEnvironmentKey.self] }
        set { self[GlassEffectOLEDEnvironmentKey.self] = newValue }
    }

    /// The glass to draw in this subtree: follows the LOCAL colour scheme, so
    /// a surface forced light inside a dark screen gets light glass.
    var glassStyle: GlassStyle {
        GlassEffect.style(enabled: glassEffect, oledEnabled: glassEffectOLED,
                          oledActive: oledBlackActive, scheme: colorScheme)
    }
}

enum GlassEffectWindows {
    static func apply(enabled: Bool, oledEnabled: Bool) {
        for scene in UIApplication.shared.connectedScenes {
            guard let windowScene = scene as? UIWindowScene else { continue }
            for window in windowScene.windows {
                window.traitOverrides[GlassEffectTrait.self] = enabled
                window.traitOverrides[GlassEffectOLEDTrait.self] = oledEnabled
                var vc = window.rootViewController?.presentedViewController
                while let presented = vc {
                    OLEDBlackWindows.applySheetRim(to: presented)
                    vc = presented.presentedViewController
                }
            }
        }
    }
}
#endif

// MARK: - Grounds

extension View {
    /// __GLASS_ALL_THEMES_2026_11_21__ The page under glass. With glass on in
    /// Light / Dark, the OSHI ground (lavender ramp / indigo ramp with two wide
    /// glows, the chat's own backdrop) replaces the flat system colour, so the
    /// translucent cards have something to be translucent OVER. Glass off or
    /// OLED black: `fallback`, exactly as before.
    func glassAwareGround(_ fallback: Color) -> some View {
        modifier(GlassAwareGround(fallback: fallback, list: false))
    }

    /// A plain-List row whose own background steps aside under Light / Dark
    /// glass so the ground shows through (chat list).
    /// `selectedTint`: the wide layout's "open in the detail pane" mark; it wins
    /// over the clear glass row so the selection stays visible.
    func glassClearListRow(selectedTint: Color? = nil) -> some View {
        modifier(GlassClearListRow(selectedTint: selectedTint))
    }

    /// Same for a `List` / `Form`: hides its own grouped background first.
    func glassAwareListGround(_ fallback: Color? = nil) -> some View {
        modifier(GlassAwareGround(fallback: fallback, list: true))
    }

    /// A `List` that lets the ground of an ANCESTOR show through under Light /
    /// Dark glass (the chat list: one ground for the segment strip and the
    /// rows, no seam between two ramps).
    func glassSeeThroughList() -> some View {
        modifier(GlassSeeThroughList())
    }

    /// The root of a NavigationStack whose ancestor paints the ground: clears
    /// the stack's own opaque container on iOS 18+; on iOS 17 (no API for it)
    /// the root paints the ground itself.
    func glassNavigationSeeThrough() -> some View {
        modifier(GlassNavigationSeeThrough())
    }
}

private struct GlassAwareGround: ViewModifier {
    let fallback: Color?
    let list: Bool
    @Environment(\.glassStyle) private var style

    func body(content: Content) -> some View {
        switch style {
        case .light, .dark:
            content
                .scrollContentBackground(list ? .hidden : .automatic)
                .background(OSHIWallpaperGround().ignoresSafeArea())
        default:
            if let fallback {
                content.background(fallback)
            } else {
                content
            }
        }
    }
}

private struct GlassClearListRow: ViewModifier {
    var selectedTint: Color? = nil
    @Environment(\.glassStyle) private var style
    func body(content: Content) -> some View {
        if let selectedTint {
            content.listRowBackground(selectedTint)
        } else if style == .light || style == .dark {
            content.listRowBackground(Color.clear)
        } else {
            content
        }
    }
}

private struct GlassSeeThroughList: ViewModifier {
    @Environment(\.glassStyle) private var style
    func body(content: Content) -> some View {
        if style == .light || style == .dark {
            content.scrollContentBackground(.hidden)
        } else {
            content
        }
    }
}

private struct GlassNavigationSeeThrough: ViewModifier {
    @Environment(\.glassStyle) private var style
    func body(content: Content) -> some View {
        if style == .light || style == .dark {
            if #available(iOS 18.0, macCatalyst 18.0, *) {
                content.containerBackground(Color.clear, for: .navigation)
            } else {
                content.background(OSHIWallpaperGround().ignoresSafeArea())
            }
        } else {
            content
        }
    }
}
