//
//  FuturisticUI.swift
//  OSHI — shared "futuristic" visual primitives: an Apple-Intelligence-style
//  animated iridescent gradient, a soft glow, and a glass surface. Kept tasteful
//  (subtle glow + spring motion, not gaudy) and Reduce-Motion aware.
//

import SwiftUI

// MARK: - Apple-Intelligence iridescent gradient

/// The Apple-Intelligence iridescent palette + a ready-made `AngularGradient`
/// ShapeStyle for tinting (`.foregroundStyle`, `.stroke`, `.fill`). This is the
/// non-animated form usable anywhere a ShapeStyle is required.
enum AIGradient {
    static let colors: [Color] = [
        Color(red: 0.40, green: 0.20, blue: 0.95),  // brand purple
        Color(red: 0.94, green: 0.28, blue: 0.56),  // pink
        Color(red: 0.98, green: 0.55, blue: 0.25),  // amber
        Color(red: 0.20, green: 0.75, blue: 0.95),  // cyan
        Color(red: 0.40, green: 0.20, blue: 0.95),  // loop back to purple
    ]
    /// A ShapeStyle you can pass to `.foregroundStyle`/`.stroke`/`.fill`.
    static let angular = AngularGradient(gradient: Gradient(colors: colors), center: .center)
}

/// The signature shifting rainbow used for Apple-Intelligence surfaces — a
/// rotating `AngularGradient` VIEW (paused under Reduce Motion). Use as a fill
/// via `.background`/`.overlay`; for ShapeStyle contexts use `AIGradient.angular`.
struct AIGlowGradient: View {
    var animated: Bool = true
    @State private var angle: Double = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        AngularGradient(gradient: Gradient(colors: AIGradient.colors),
                        center: .center,
                        angle: .degrees(angle))
            .onAppear {
                guard animated && !reduceMotion else { return }
                withAnimation(.linear(duration: 6).repeatForever(autoreverses: false)) {
                    angle = 360
                }
            }
    }
}

// MARK: - View modifiers

extension View {
    /// Wrap in an iridescent Apple-Intelligence glow ring + soft halo.
    func appleIntelligenceGlow(cornerRadius: CGFloat = 20, lineWidth: CGFloat = 2, glow: CGFloat = 14) -> some View {
        self
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .stroke(AIGradient.angular, lineWidth: lineWidth)
            )
            .background(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(AIGradient.angular)
                    .blur(radius: glow)
                    .opacity(0.35)
            )
    }

    /// A frosted-glass surface (ultra-thin material + hairline border) — the
    /// futuristic panel look. Dark-mode-safe (material adapts automatically).
    func glassSurface(cornerRadius: CGFloat = 18) -> some View {
        self
            // __OLED_GLASS_2026_11_16__ crystal glass (smoked + gradient rim)
            // under OLED black; the flat 12 % rim steps aside for it.
            .oledAwareBackground(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .stroke(Color.white.opacity(0.12), lineWidth: 0.75)
                    .oledHidden()
            )
    }

    /// Soft brand-colored glow behind a view (e.g. a send button, an icon).
    func neonGlow(_ color: Color, radius: CGFloat = 12, opacity: Double = 0.55) -> some View {
        self.shadow(color: color.opacity(opacity), radius: radius)
    }
}

// MARK: - Animated shimmer sweep (for "thinking" / active states)

/// A diagonal light sweep that loops across the content — a lively "processing"
/// cue for AI/active surfaces. Reduce-Motion turns it into a static highlight.
struct ShimmerSweep: ViewModifier {
    @State private var phase: CGFloat = -1
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.overlay(
            GeometryReader { geo in
                LinearGradient(
                    colors: [.clear, Color.white.opacity(0.35), .clear],
                    startPoint: .topLeading, endPoint: .bottomTrailing
                )
                .frame(width: geo.size.width * 1.4)
                .offset(x: reduceMotion ? 0 : phase * geo.size.width * 1.4)
                .onAppear {
                    guard !reduceMotion else { return }
                    withAnimation(.linear(duration: 1.6).repeatForever(autoreverses: false)) {
                        phase = 1
                    }
                }
            }
            .mask(content)
            .allowsHitTesting(false)
        )
    }
}

extension View {
    func shimmerSweep() -> some View { modifier(ShimmerSweep()) }
}
