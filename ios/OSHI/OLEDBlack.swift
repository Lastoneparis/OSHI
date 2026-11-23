//
//  OLEDBlack.swift
//  OSHI
//
//  __OLED_BLACK_2026_11_16__ « Noir OLED » / "OLED black".
//
//  Asked by a user: "would it be possible to add an option for darker colours
//  for OLED screens?". An option UNDER the theme picker, default OFF, that
//  applies whenever the app is actually dark — the "Dark" theme, or "System"
//  while the phone is dark. It never changes a light screen.
//
//  HOW IT REACHES EVERY SCREEN WITHOUT TOUCHING 140 CALL SITES
//
//  1. In dark mode at the BASE interface level, iOS already paints
//     `systemBackground` / `systemGroupedBackground` #000000 and the secondary
//     surfaces #1C1C1E. What is grey in dark mode is (a) every SHEET, because a
//     presented sheet runs at the ELEVATED level where those same colours are
//     #1C1C1E / #2C2C2E, and (b) the app's own hand-picked surfaces (indigo chat
//     ground, violet blooms, received bubble #1E1B2E, launch gradient…).
//
//  2. (a) is handled by pinning `userInterfaceLevel = .base` on every window and
//     every presented controller while OLED black is on (`OLEDBlackWindows`).
//     Sheets then resolve the system colours exactly like a pushed screen: black
//     ground, #1C1C1E cards for separation.
//
//  3. (b) is handled by a custom UIKit trait, `OLEDBlackTrait`, bridged into the
//     SwiftUI environment as `\.oledBlack`. It is set on the windows, so it
//     reaches every hosting controller — sheets and full-screen covers included —
//     and a dynamic `UIColor { traits in … }` sees it when it resolves. The trait
//     carries the USER'S CHOICE; "is it active" is `choice && style == .dark`,
//     so flipping the phone between light and dark needs nothing extra.
//
//  Everything applies live: the setting is an @AppStorage the root scene
//  observes, and a trait change re-resolves every dynamic colour on screen.
//

import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

enum OLEDBlack {
    /// UserDefaults / @AppStorage key. Default OFF.
    static let storageKey = "oledBlackEnabled"
    static let defaultEnabled = false

    /// The stored choice (NOT whether it is currently active — see `isActive`).
    static var isEnabled: Bool {
        UserDefaults.standard.object(forKey: storageKey) as? Bool ?? defaultEnabled
    }

    /// Whether OLED black should paint right now, from the three inputs that
    /// decide it: the toggle, the app theme (`appTheme` AppStorage: "system" /
    /// "light" / "dark") and what the system is currently showing.
    static func isActive(enabled: Bool, appTheme: String, systemScheme: ColorScheme) -> Bool {
        guard enabled else { return false }
        switch appTheme {
        case "light": return false
        case "dark": return true
        default: return systemScheme == .dark
        }
    }

    #if canImport(UIKit)
    /// Active in a resolved trait collection: the choice is carried by the trait,
    /// the darkness by the interface style.
    static func isActive(in traits: UITraitCollection) -> Bool {
        traits.userInterfaceStyle == .dark && traits[OLEDBlackTrait.self]
    }

    // MARK: Palette (only used when active)

    /// The ground. True black: the pixels are OFF.
    static let backgroundUI = UIColor.black
    /// Received bubble / chips / quote surface on black — #1C1C1E, the system's
    /// own dark secondary surface. White text on it is 16.7:1.
    static let incomingBubbleUI = UIColor(red: 0x1C / 255, green: 0x1C / 255, blue: 0x1E / 255, alpha: 1)

    /// `base` in normal dark mode, `oled` when OLED black is active.
    static func dynamic(_ base: UIColor, oled: UIColor) -> UIColor {
        UIColor { traits in
            isActive(in: traits) ? oled : base.resolvedColor(with: traits)
        }
    }
    #endif
}

#if canImport(UIKit)
// MARK: - Trait + SwiftUI bridge

/// The user's OLED-black choice, as a UIKit trait. `affectsColorAppearance`
/// makes UIKit re-resolve dynamic colours when it flips.
struct OLEDBlackTrait: UITraitDefinition {
    static let defaultValue = false
    static let affectsColorAppearance = true
    static let name = "OSHIOLEDBlack"
}

/// `\.oledBlack` in SwiftUI is the same value as `OLEDBlackTrait` in UIKit,
/// both ways (iOS 17 `UITraitBridgedEnvironmentKey`).
struct OLEDBlackEnvironmentKey: EnvironmentKey, UITraitBridgedEnvironmentKey {
    static let defaultValue = false
    static func read(from traitCollection: UITraitCollection) -> Bool {
        traitCollection[OLEDBlackTrait.self]
    }
    static func write(to mutableTraits: inout UIMutableTraits, value: Bool) {
        mutableTraits[OLEDBlackTrait.self] = value
    }
}

extension EnvironmentValues {
    /// The user's OLED-black CHOICE. Combine with `colorScheme == .dark`
    /// (or use `oledBlackActive`) to know whether to paint black.
    var oledBlack: Bool {
        get { self[OLEDBlackEnvironmentKey.self] }
        set { self[OLEDBlackEnvironmentKey.self] = newValue }
    }

    /// OLED black is on AND this subtree is dark.
    var oledBlackActive: Bool { oledBlack && colorScheme == .dark }
}

// MARK: - Windows & presented controllers

/// Pushes the choice onto every window (trait) and pins the interface level to
/// `.base` on windows and presented controllers so sheets are black too.
enum OLEDBlackWindows {
    static func apply(enabled: Bool) {
        for scene in UIApplication.shared.connectedScenes {
            guard let windowScene = scene as? UIWindowScene else { continue }
            for window in windowScene.windows {
                apply(enabled: enabled, to: window)
            }
        }
    }

    static func apply(enabled: Bool, to window: UIWindow) {
        if enabled {
            window.traitOverrides[OLEDBlackTrait.self] = true
            window.traitOverrides.userInterfaceLevel = .base
        } else {
            window.traitOverrides.remove(OLEDBlackTrait.self)
            window.traitOverrides.remove(UITraitUserInterfaceLevel.self)
        }
        var vc = window.rootViewController?.presentedViewController
        while let presented = vc {
            applyLevel(enabled: enabled, to: presented)
            vc = presented.presentedViewController
        }
    }

    /// A presented sheet gets `.elevated` from its presentation controller,
    /// which a window override cannot beat — the controller's own override can.
    static func applyLevel(enabled: Bool, to vc: UIViewController) {
        if enabled {
            vc.traitOverrides.userInterfaceLevel = .base
        } else if vc.traitOverrides.contains(UITraitUserInterfaceLevel.self) {
            vc.traitOverrides.remove(UITraitUserInterfaceLevel.self)
        }
        applySheetRim(to: vc)
    }

    /// __OLED_GLASS_2026_11_16__ A black sheet on a black screen has no edge.
    /// The sheet keeps its #000 ground (pixels off) and gets the crystal rim:
    /// the gradient hairline along its rounded outline and a faint top sheen.
    /// Sheets only — not full-screen covers, popovers or alerts.
    /// __GLASS_ALL_THEMES_2026_11_21__ Follows the GLASS choice now, and shows on
    /// every dark sheet (OLED black or the classic #1C1C1E): the same crystal
    /// rim reads on both. A light sheet keeps the system's own edge.
    static func applySheetRim(to vc: UIViewController) {
        let enabled = GlassEffect.rimOnDarkSheets
        guard let container = vc.presentationController?.presentedView else { return }
        let existing = container.subviews.compactMap { $0 as? OLEDSheetRimView }
        let isSheet = vc.presentationController is UISheetPresentationController
            && !(vc is UIAlertController)
        let dark = container.traitCollection.userInterfaceStyle == .dark
        if enabled && isSheet && dark {
            let rim = existing.first ?? OLEDSheetRimView(frame: container.bounds)
            if rim.superview == nil {
                rim.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                container.addSubview(rim)
            }
            container.bringSubviewToFront(rim)
            rim.setNeedsLayout()
        } else {
            existing.forEach { $0.removeFromSuperview() }
        }
    }

    /// Install once: every later presentation is pinned to `.base` while the
    /// option is on. Swizzles `present(_:animated:completion:)` — the only hook
    /// UIKit gives on "a controller is about to be presented" (SwiftUI's
    /// `.sheet` goes through it too).
    static func installPresentationHook() {
        guard !hookInstalled else { return }
        hookInstalled = true
        let original = #selector(UIViewController.present(_:animated:completion:))
        let replacement = #selector(UIViewController.oshi_oledPresent(_:animated:completion:))
        guard let m1 = class_getInstanceMethod(UIViewController.self, original),
              let m2 = class_getInstanceMethod(UIViewController.self, replacement) else { return }
        method_exchangeImplementations(m1, m2)
    }
    private static var hookInstalled = false
}

extension UIViewController {
    @objc fileprivate func oshi_oledPresent(_ vc: UIViewController, animated: Bool,
                                            completion: (() -> Void)? = nil) {
        if OLEDBlack.isEnabled {
            OLEDBlackWindows.applyLevel(enabled: true, to: vc)
        }
        // Implementations are exchanged: this calls the ORIGINAL present.
        oshi_oledPresent(vc, animated: animated, completion: completion)
        if GlassEffect.rimOnDarkSheets {
            // The sheet's container exists once the transition has started.
            DispatchQueue.main.async {
                OLEDBlackWindows.applySheetRim(to: vc)
            }
        }
    }
}

/// The crystal rim of a sheet under OLED black: a 0.75 pt hairline, white 18 %
/// at the top to 4 % lower down, following the container's corner radius, and
/// a white 5 % → 0 sheen over the top 90 pt. Never takes touches.
final class OLEDSheetRimView: UIView {
    private let stroke = CAGradientLayer()
    private let strokeMask = CAShapeLayer()
    private let sheen = CAGradientLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear
        accessibilityElementsHidden = true
        stroke.colors = [OLEDGlass.hairlineTopUI.cgColor, OLEDGlass.hairlineBottomUI.cgColor,
                         UIColor.clear.cgColor]
        stroke.locations = [0, 0.35, 1]
        strokeMask.fillColor = UIColor.clear.cgColor
        strokeMask.strokeColor = UIColor.black.cgColor
        strokeMask.lineWidth = OLEDGlass.hairlineWidth * 2  // half is clipped by the container
        stroke.mask = strokeMask
        sheen.colors = [UIColor(white: 1, alpha: 0.05).cgColor, UIColor(white: 1, alpha: 0).cgColor]
        layer.addSublayer(sheen)
        layer.addSublayer(stroke)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let radius = max(superview?.layer.cornerRadius ?? 0, 10)
        stroke.frame = bounds
        strokeMask.frame = bounds
        strokeMask.path = UIBezierPath(roundedRect: bounds, cornerRadius: radius).cgPath
        sheen.frame = CGRect(x: 0, y: 0, width: bounds.width, height: min(90, bounds.height))
        sheen.cornerRadius = radius
        sheen.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
        CATransaction.commit()
    }
}
#endif

// MARK: - SwiftUI helpers

extension View {
    /// Paints `Color.black` behind a List/Form (hiding its own grouped
    /// background) when OLED black is active; no-op otherwise.
    func oledBlackListBackground() -> some View {
        modifier(OLEDBlackListBackground())
    }

    /// A plain black ground behind the view when OLED black is active.
    func oledBlackBackground() -> some View {
        modifier(OLEDBlackBackground())
    }

    /// `material` with glass off. With glass (any theme): the glass bar
    /// (`OLEDGlassBar`) — frosted, deepened toward black, with a lit hairline
    /// on its top edge. Rectangle-shaped call sites are BARS (composer, banner).
    func oledAwareBackground(_ material: Material) -> some View {
        modifier(OLEDGlassBar(fallback: AnyShapeStyle(material), material: material, edge: .top))
    }

    /// `material` in `shape` normally; a crystal-glass card under OLED black.
    func oledAwareBackground<S: Shape>(_ material: Material, in shape: S) -> some View {
        modifier(OLEDGlassCard(fallback: AnyShapeStyle(material), shape: AnyShape(shape), blur: true))
    }

    /// `fallback` normally; a crystal-glass bar under OLED black.
    func oledGlassBar<F: ShapeStyle>(fallback: F, edge: VerticalEdge = .top) -> some View {
        modifier(OLEDGlassBar(fallback: AnyShapeStyle(fallback), material: .ultraThinMaterial, edge: edge))
    }

    /// `fallback` in `shape` normally; a crystal-glass card under OLED black.
    /// `blur: false` is the cheap variant (tinted fill + hairline + highlight,
    /// no backdrop blur) for anything that repeats inside a scrolling list.
    func oledGlassCard<S: Shape, F: ShapeStyle>(_ shape: S, fallback: F, blur: Bool = false) -> some View {
        modifier(OLEDGlassCard(fallback: AnyShapeStyle(fallback), shape: AnyShape(shape), blur: blur))
    }

    /// Adds only the glass EDGE (hairline + top highlight) on top of whatever
    /// surface is already there, when OLED black is active. Cheapest variant.
    func oledGlassEdge<S: Shape>(_ shape: S) -> some View {
        modifier(OLEDGlassEdge(shape: AnyShape(shape)))
    }

    /// A faint brand-purple halo under OLED black only (send button, selected
    /// states): on a black ground the glow is what says "this is the action".
    func oledFocusGlow(_ color: Color = Theme.brand, radius: CGFloat = 10) -> some View {
        modifier(OLEDFocusGlow(color: color, radius: radius))
    }
}

// MARK: - Crystal glass (OLED black)
//
// __OLED_GLASS_2026_11_16__ "Make OLED black beautiful, like crystal glass."
// The GROUND stays #000 (pixels off); what floats on it becomes glass:
//   • fill — a frosted material deepened toward black (bars, sheets, cards), or,
//     for anything repeated in a scrolling list (bubbles, chips, rows), a cheap
//     translucent #1C1C1E with NO backdrop blur: the 1:1 thread is an eager
//     stack, a blur per row would re-render the backdrop for every bubble;
//   • hairline — 0.75 pt, white 18 % at the top fading to 4 % at the bottom,
//     the lit edge of a pane of glass;
//   • highlight — white 7 % → 0 over the top third, under the content;
//   • glow — brand purple, ONLY on the focus (send, selected).
// Reduce Transparency: no material, no translucency — solid #1C1C1E with the
// hairline (the edge is separation, not decoration).
enum OLEDGlass {
    static let hairlineTop: Double = 0.18
    static let hairlineBottom: Double = 0.04
    static let hairlineWidth: CGFloat = 0.75
    static let highlight: Double = 0.07
    /// Black laid over the material so the glass reads as smoked crystal, not
    /// as the mid-grey slab a material alone makes over #000.
    /// 0.6: measured, 0.38 left the composer a #1F-#13 grey slab over black.
    static let smoke: Double = 0.6
    /// Opacity of the cheap (no-blur) fill, #1C1C1E. 0.92, not lower: over a
    /// user's WHITE photo wallpaper (the worst ground a received bubble can sit
    /// on) secondary text on the glass bubble must still clear 4.5:1 —
    /// `OLEDBlackTests.testGlassBubbleKeepsAAOnTheWorstWallpaper`.
    static let cheapFillAlpha: Double = 0.92
    /// The sheen INSIDE a received bubble — lower than a card's, same reason.
    static let bubbleHighlight: Double = 0.05
    static var solid: Color { Color(OLEDBlack.incomingBubbleUI) }

    static var hairline: LinearGradient {
        LinearGradient(colors: [.white.opacity(hairlineTop), .white.opacity(hairlineBottom)],
                       startPoint: .top, endPoint: .bottom)
    }
    /// Horizontal hairline for a bar edge: brightest in the middle, gone at
    /// the screen edges, like light caught along a glass rim.
    static var barHairline: LinearGradient {
        LinearGradient(colors: [.white.opacity(hairlineBottom), .white.opacity(hairlineTop),
                                .white.opacity(hairlineBottom)],
                       startPoint: .leading, endPoint: .trailing)
    }
    static var topHighlight: LinearGradient { sheen(highlight) }
    static var bubbleSheen: LinearGradient { sheen(bubbleHighlight) }
    private static func sheen(_ alpha: Double) -> LinearGradient {
        LinearGradient(stops: [.init(color: .white.opacity(alpha), location: 0),
                               .init(color: .white.opacity(0), location: 0.4)],
                       startPoint: .top, endPoint: .bottom)
    }

    #if canImport(UIKit)
    /// UIKit twin of the hairline, for sheets (see `OLEDGlassSheetEdge`).
    static let hairlineTopUI = UIColor(white: 1, alpha: CGFloat(hairlineTop))
    static let hairlineBottomUI = UIColor(white: 1, alpha: CGFloat(hairlineBottom))
    #endif
}

// __GLASS_ALL_THEMES_2026_11_21__ Every modifier below switches on
// `\.glassStyle` (GlassAppearance.swift): `.oled` is the 16/11 crystal glass,
// unchanged; `.light` / `.dark` are its classic-theme siblings; `.none` (the
// option off) is the surface the app had before glass existed.

private struct OLEDGlassCard: ViewModifier {
    let fallback: AnyShapeStyle
    let shape: AnyShape
    let blur: Bool
    @Environment(\.glassStyle) private var style
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast

    private var solid: Bool { reduceTransparency || contrast == .increased }

    func body(content: Content) -> some View {
        switch style {
        case .oled:
            content
                .background {
                    ZStack {
                        if solid {
                            shape.fill(OLEDGlass.solid)
                        } else if blur {
                            shape.fill(.ultraThinMaterial)
                            shape.fill(Color.black.opacity(OLEDGlass.smoke))
                        } else {
                            shape.fill(OLEDGlass.solid.opacity(OLEDGlass.cheapFillAlpha))
                        }
                        if !solid {
                            shape.fill(OLEDGlass.topHighlight)
                        }
                    }
                    .environment(\.colorScheme, .dark)
                }
                .overlay(shape.stroke(ClassicGlass.rim(.oled, increasedContrast: contrast == .increased),
                                      lineWidth: OLEDGlass.hairlineWidth)
                            .allowsHitTesting(false))
        case .light, .dark:
            let dark = style == .dark
            content
                .background {
                    ZStack {
                        if solid {
                            shape.fill(fallback)
                        } else if blur {
                            ClassicGlassPane(shape: shape, dark: dark)
                        } else {
                            shape.fill(fallback.opacity(dark ? ClassicGlass.darkCardAlpha
                                                             : ClassicGlass.lightCardAlpha))
                            shape.fill(dark ? AnyShapeStyle(ClassicGlass.darkSheenGradient)
                                            : AnyShapeStyle(ClassicGlass.lightSheenGradient))
                        }
                    }
                    .shadow(color: dark || solid ? .clear : ClassicGlass.shadowInk.opacity(ClassicGlass.lightShadow),
                            radius: 14, x: 0, y: 6)
                }
                .overlay(shape.stroke(ClassicGlass.rim(style, increasedContrast: contrast == .increased),
                                      lineWidth: contrast == .increased ? 1 : OLEDGlass.hairlineWidth)
                            .allowsHitTesting(false))
        case .none:
            content.background(fallback, in: shape)
        }
    }
}

/// A floating pane of real glass in Light / Dark: Liquid Glass on iOS 26
/// (the system's own refraction + specular edge), a material deepened toward
/// the theme before that.
private struct ClassicGlassPane: View {
    let shape: AnyShape
    let dark: Bool

    var body: some View {
        if #available(iOS 26.0, macCatalyst 26.0, *) {
            Color.clear.glassEffect(.regular, in: shape)
        } else {
            ZStack {
                shape.fill(.ultraThinMaterial)
                shape.fill(dark ? Color.black.opacity(ClassicGlass.darkSmoke)
                                : Color.white.opacity(ClassicGlass.lightMilk))
                shape.fill(dark ? AnyShapeStyle(ClassicGlass.darkSheenGradient)
                                : AnyShapeStyle(ClassicGlass.lightSheenGradient))
            }
        }
    }
}

private struct OLEDGlassBar: ViewModifier {
    let fallback: AnyShapeStyle
    let material: Material
    let edge: VerticalEdge
    @Environment(\.glassStyle) private var style
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast

    private var solid: Bool { reduceTransparency || contrast == .increased }

    func body(content: Content) -> some View {
        switch style {
        case .oled:
            content
                .background {
                    ZStack {
                        if solid {
                            OLEDGlass.solid
                        } else {
                            Rectangle().fill(material)
                            Color.black.opacity(OLEDGlass.smoke)
                            OLEDGlass.topHighlight.opacity(edge == .top ? 0.8 : 0)
                        }
                    }
                    .environment(\.colorScheme, .dark)
                    .ignoresSafeArea(edges: edge == .top ? .bottom : .top)
                }
                .overlay(alignment: edge == .top ? .top : .bottom) {
                    Rectangle().fill(OLEDGlass.barHairline)
                        .frame(height: OLEDGlass.hairlineWidth)
                        .allowsHitTesting(false)
                }
        case .light, .dark:
            let dark = style == .dark
            content
                .background {
                    ZStack {
                        if solid {
                            Rectangle().fill(fallback)
                        } else {
                            Rectangle().fill(material)
                            if dark {
                                Color.black.opacity(ClassicGlass.darkSmoke)
                                ClassicGlass.darkSheenGradient.opacity(edge == .top ? 1 : 0)
                            } else {
                                Color.white.opacity(ClassicGlass.lightMilk)
                                ClassicGlass.lightSheenGradient.opacity(edge == .top ? 0.8 : 0)
                            }
                        }
                    }
                    .ignoresSafeArea(edges: edge == .top ? .bottom : .top)
                }
                .overlay(alignment: edge == .top ? .top : .bottom) {
                    VStack(spacing: 0) {
                        if dark {
                            Rectangle().fill(contrast == .increased
                                             ? AnyShapeStyle(ClassicGlass.contrastRim(dark: true))
                                             : AnyShapeStyle(ClassicGlass.darkBarHairline))
                                .frame(height: OLEDGlass.hairlineWidth)
                        } else {
                            Rectangle().fill(contrast == .increased
                                             ? AnyShapeStyle(ClassicGlass.contrastRim(dark: false))
                                             : AnyShapeStyle(ClassicGlass.lightBarShade))
                                .frame(height: 0.5)
                            Rectangle().fill(Color.white.opacity(solid ? 0 : 0.85))
                                .frame(height: 1)
                        }
                    }
                    .allowsHitTesting(false)
                }
        case .none:
            content.background(fallback)
        }
    }
}

private struct OLEDGlassEdge: ViewModifier {
    let shape: AnyShape
    @Environment(\.glassStyle) private var style
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast

    func body(content: Content) -> some View {
        switch style {
        case .none:
            content
        case .oled, .dark:
            content
                .overlay {
                    ZStack {
                        if !reduceTransparency && contrast != .increased {
                            shape.fill(style == .oled ? OLEDGlass.topHighlight : ClassicGlass.darkSheenGradient)
                        }
                        shape.stroke(ClassicGlass.rim(style, increasedContrast: contrast == .increased),
                                     lineWidth: OLEDGlass.hairlineWidth)
                    }
                    .allowsHitTesting(false)
                }
        case .light:
            // Light: the rim only. A white sheen laid OVER the content would
            // wash the glyphs (the edge is an overlay); the rim alone already
            // reads as the lit edge of a pane.
            content
                .overlay {
                    shape.stroke(ClassicGlass.rim(.light, increasedContrast: contrast == .increased),
                                 lineWidth: contrast == .increased ? 1 : OLEDGlass.hairlineWidth)
                        .allowsHitTesting(false)
                }
        }
    }
}

private struct OLEDFocusGlow: ViewModifier {
    let color: Color
    let radius: CGFloat
    @Environment(\.glassStyle) private var style
    func body(content: Content) -> some View {
        switch style {
        case .oled, .dark:
            content.shadow(color: color.opacity(style == .oled ? 0.45 : 0.40), radius: radius)
        case .light:
            content.shadow(color: color.opacity(0.28), radius: radius * 0.8, y: 2)
        case .none:
            content
        }
    }
}

private struct OLEDBlackListBackground: ViewModifier {
    @Environment(\.oledBlackActive) private var active
    func body(content: Content) -> some View {
        if active {
            content
                .scrollContentBackground(.hidden)
                .background(Color.black.ignoresSafeArea())
        } else {
            content
        }
    }
}

private struct OLEDBlackBackground: ViewModifier {
    @Environment(\.oledBlackActive) private var active
    func body(content: Content) -> some View {
        content.background(active ? Color.black.ignoresSafeArea() : nil)
    }
}

/// The received-bubble glass edge for surfaces that are not `BubbleSurface`
/// (group text bubble): hairline + sheen on the incoming side only.
struct OLEDGlassIncomingEdge: ViewModifier {
    let isMine: Bool
    let radius: CGFloat
    func body(content: Content) -> some View {
        if isMine {
            content
        } else {
            content.oledGlassEdge(RoundedRectangle(cornerRadius: radius, style: .continuous))
        }
    }
}

// MARK: - List sections

extension View {
    /// __OLED_GLASS_2026_11_16__ The FIRST row of an inset-grouped List section,
    /// under OLED black: the section's #1C1C1E card catches the light along its
    /// top edge (horizontal hairline fading toward the rounded corners, so it
    /// never has to match the system's corner radius) with a faint sheen below.
    /// SwiftUI gives no section-level surface, so the rim lives on row one.
    func oledGlassListRow(first: Bool = true) -> some View {
        modifier(OLEDGlassListRow(first: first))
    }
}

private struct OLEDGlassListRow: ViewModifier {
    let first: Bool
    @Environment(\.glassStyle) private var style
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    func body(content: Content) -> some View {
        if first && (style == .oled || style == .dark) {
            content.listRowBackground(
                ZStack(alignment: .top) {
                    if style == .oled { OLEDGlass.solid } else { Color(.secondarySystemGroupedBackground) }
                    if !reduceTransparency {
                        if style == .oled { OLEDGlass.topHighlight } else { ClassicGlass.darkSheenGradient }
                    }
                    Rectangle().fill(style == .oled ? OLEDGlass.barHairline : ClassicGlass.darkBarHairline)
                        .frame(height: OLEDGlass.hairlineWidth)
                }
            )
        } else {
            content
        }
    }
}

extension View {
    /// Hidden while glass is drawn (an old flat rim the glass rim replaces).
    func oledHidden() -> some View { modifier(OLEDHidden()) }
}

private struct OLEDHidden: ViewModifier {
    @Environment(\.glassStyle) private var style
    func body(content: Content) -> some View { content.opacity(style.isGlass ? 0 : 1) }
}
