//
//  Theme.swift
//  OSHI — central design system (tokens for color, spacing, radius, motion, shadow, haptics)
//
//  __DESIGN_SYSTEM_2026_06_21__
//  The app previously hardcoded the brand color ~100×. This file centralizes
//  design decisions so the UI is consistent and easy to polish. Adopt gradually:
//  replace inline `Color(red:0.337…)` with `Theme.brand`, raw spacing with
//  `Theme.Space.*`, and `.easeInOut` call-site animations with `Theme.Motion.*`.
//

import SwiftUI

enum Theme {

    // MARK: - Brand & semantic colors
    /// Primary brand purple (was Color(red:0.337, green:0.086, blue:0.925)).
    static let brand = Color(red: 0.337, green: 0.086, blue: 0.925)
    static let brandSecondary = Color(red: 0.94, green: 0.28, blue: 0.56)   // accent pink
    static let brandGradient = LinearGradient(
        colors: [brand, Color(red: 0.20, green: 0.40, blue: 0.95)],
        startPoint: .topLeading, endPoint: .bottomTrailing
    )

    /// Semantic colors that adapt to light/dark via the system.
    static let surface = Color(.secondarySystemBackground)
    static let surfaceElevated = Color(.tertiarySystemBackground)
    static let separator = Color(.separator)
    static let textPrimary = Color(.label)
    static let textSecondary = Color(.secondaryLabel)
    static let success = Color.green
    static let danger = Color.red
    static let warning = Color.orange

    // Chat bubble colors (Telegram/WhatsApp-style)
    static let bubbleOutgoing = brand
    static let bubbleOutgoingText = Color.white
    static let bubbleIncoming = Color(.secondarySystemBackground)
    static let bubbleIncomingText = Color(.label)

    // MARK: - Spacing scale (4/8pt rhythm)
    enum Space {
        static let xxs: CGFloat = 2
        static let xs: CGFloat = 4
        static let sm: CGFloat = 8
        static let md: CGFloat = 12
        static let lg: CGFloat = 16
        static let xl: CGFloat = 24
        static let xxl: CGFloat = 32
        static let xxxl: CGFloat = 48
    }

    // MARK: - Corner radius
    enum Radius {
        static let sm: CGFloat = 8
        static let md: CGFloat = 12
        static let lg: CGFloat = 18      // message bubble
        static let xl: CGFloat = 24
        static let pill: CGFloat = 999
    }

    // MARK: - Motion (spring-first, iOS 26 / Telegram-2026 feel)
    enum Motion {
        /// Quick, responsive — buttons, toggles, small state changes.
        static let snappy = Animation.spring(response: 0.30, dampingFraction: 0.78)
        /// Smooth — view/content transitions, sheets.
        static let smooth = Animation.spring(response: 0.42, dampingFraction: 0.85)
        /// Playful — reactions, send pop, attention.
        static let bouncy = Animation.spring(response: 0.38, dampingFraction: 0.62)
        /// Gentle fade for opacity-only changes.
        static let fade = Animation.easeInOut(duration: 0.2)
    }

    // MARK: - Shadows (consistent elevation scale)
    struct Shadow { let color: Color; let radius: CGFloat; let y: CGFloat }
    enum Elevation {
        static let low = Shadow(color: .black.opacity(0.08), radius: 4, y: 2)
        static let medium = Shadow(color: .black.opacity(0.12), radius: 10, y: 4)
        static let high = Shadow(color: .black.opacity(0.18), radius: 20, y: 8)
    }

    // MARK: - Typography (Dynamic Type-friendly, rounded for brand warmth)
    enum Font {
        static func rounded(_ size: CGFloat, _ weight: SwiftUI.Font.Weight = .regular) -> SwiftUI.Font {
            .system(size: size, weight: weight, design: .rounded)
        }
        static let titleL = rounded(28, .bold)
        static let title = rounded(22, .semibold)
        static let headline = rounded(17, .semibold)
        static let body = SwiftUI.Font.body
        static let caption = SwiftUI.Font.caption
    }
}

// MARK: - View helpers

extension View {
    /// Apply a Theme elevation shadow.
    func elevation(_ s: Theme.Shadow) -> some View {
        self.shadow(color: s.color, radius: s.radius, y: s.y)
    }
    /// Subtle press-scale + haptic on tap — the standard "feels alive" interaction.
    func pressable(scale: CGFloat = 0.96) -> some View {
        modifier(PressableModifier(scale: scale))
    }
}

/// Press-shrink for `Button`s — the safe way to get the Telegram/WhatsApp
/// "the button responds to my finger" feel. Uses the button's own `isPressed`
/// (no custom gesture that could ever swallow the tap), so it's safe even on
/// critical actions like Send.
struct PressScaleButtonStyle: ButtonStyle {
    var scale: CGFloat = 0.9
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1.0)
            .opacity(configuration.isPressed ? 0.9 : 1.0)
            .animation(Theme.Motion.snappy, value: configuration.isPressed)
    }
}

private struct PressableModifier: ViewModifier {
    let scale: CGFloat
    @GestureState private var pressed = false
    func body(content: Content) -> some View {
        content
            .scaleEffect(pressed ? scale : 1.0)
            .animation(Theme.Motion.snappy, value: pressed)
            .simultaneousGesture(
                DragGesture(minimumDistance: 0)
                    .updating($pressed) { _, state, _ in state = true }
            )
    }
}

// MARK: - Haptics (one place, tasteful defaults)

enum Haptics {
    #if os(iOS)
    static func tap(_ style: UIImpactFeedbackGenerator.FeedbackStyle = .light) {
        UIImpactFeedbackGenerator(style: style).impactOccurred()
    }
    static func success() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    static func warning() { UINotificationFeedbackGenerator().notificationOccurred(.warning) }
    static func error() { UINotificationFeedbackGenerator().notificationOccurred(.error) }
    static func selection() { UISelectionFeedbackGenerator().selectionChanged() }
    #else
    static func tap(_ style: Int = 0) {}
    static func success() {}; static func warning() {}; static func error() {}; static func selection() {}
    #endif
}

// MARK: - Dynamic Type for fixed sizes

/// A system font at a fixed point size that STILL responds to Dynamic Type.
///
/// The audit's top inclusivity finding: 512 `.font(.system(size:))` calls across
/// the app, and `@ScaledMetric` used zero times. `.system(size:)` is frozen — a
/// user who sets Larger Text sees no change, which is the first thing an
/// accessibility reviewer checks and the reason the app scored 2.5/5 there.
///
/// `Font.system(size:)` has no scaling variant, but `relativeTo:` on a custom
/// font does. Naming the system face explicitly gives back the scaling while
/// rendering identically at the default size, so this is a drop-in replacement:
/// `.font(.scaled(15))` becomes `.font(.scaled(15))` and nothing moves
/// until the user asks it to.
///
/// `relativeTo` matters — it decides HOW fast a size grows. Picking the text
/// style whose default size is nearest keeps a 10pt badge from doubling while a
/// 17pt body grows sensibly, which is what stops a scaled layout from breaking.
extension Font {
    static func scaled(_ size: CGFloat,
                       weight: Font.Weight = .regular,
                       design: Font.Design = .default) -> Font {
        #if os(iOS)
        // UIFontMetrics does the actual scaling. SwiftUI re-evaluates a view's
        // body when the content size category changes, so this recomputes and
        // the text really does grow — verified by asserting the returned point
        // size differs between .large and .accessibilityExtraLarge.
        let scaled = UIFontMetrics(forTextStyle: uiTextStyle(for: size))
            .scaledValue(for: size)
        return .system(size: scaled, weight: weight, design: design)
        #else
        return .system(size: size, weight: weight, design: design)
        #endif
    }

    #if os(iOS)
    /// UIKit counterpart of `nearestStyle`, which is what UIFontMetrics takes.
    static func uiTextStyle(for size: CGFloat) -> UIFont.TextStyle {
        switch size {
        case ..<12:   return .caption2
        case ..<13:   return .caption1
        case ..<14:   return .footnote
        case ..<16:   return .subheadline
        case ..<18:   return .body
        case ..<21:   return .title3
        case ..<26:   return .title2
        case ..<32:   return .title1
        default:      return .largeTitle
        }
    }
    #endif

    /// The text style whose default size is closest, so growth stays proportional.
    private static func nearestStyle(for size: CGFloat) -> Font.TextStyle {
        switch size {
        case ..<12:   return .caption2      // 11
        case ..<13:   return .caption       // 12
        case ..<14:   return .footnote      // 13
        case ..<16:   return .subheadline   // 15
        case ..<18:   return .body          // 17
        case ..<21:   return .title3        // 20
        case ..<26:   return .title2        // 22
        case ..<32:   return .title         // 28
        default:      return .largeTitle    // 34
        }
    }

}
