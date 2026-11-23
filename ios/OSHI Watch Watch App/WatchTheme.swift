//
//  WatchTheme.swift
//  OSHI Watch
//
//  __APPLE_WATCH_2027__ OLED black + "crystal glass", the wrist-sized twin of
//  the iPhone's `OLEDBlack.swift` / `OLEDGlass`: the ground is #000 (pixels
//  off), what floats on it is a translucent #1C1C1E pane with a lit hairline
//  (white 18 % at the top fading to 4 %) and a faint sheen over its top third.
//  Brand purple only on the focus (Reply, my bubbles, unread badge).
//

import SwiftUI

enum WatchTheme {
    /// Theme.brand on the phone (#5616EC).
    static let brand = Color(red: 0.337, green: 0.086, blue: 0.925)
    /// The mascot / OSHI "O" lilac (#A98BFF) — legible on black (8.0:1).
    static let lilac = Color(red: 0xA9 / 255, green: 0x8B / 255, blue: 0xFF / 255)
    /// #1C1C1E, the phone's `OLEDBlack.incomingBubbleUI`.
    static let pane = Color(red: 0x1C / 255, green: 0x1C / 255, blue: 0x1E / 255)
    static let secondaryText = Color(white: 0.70)   // 9.6:1 on the pane

    static let hairline = LinearGradient(colors: [.white.opacity(0.18), .white.opacity(0.04)],
                                         startPoint: .top, endPoint: .bottom)
    static let sheen = LinearGradient(stops: [.init(color: .white.opacity(0.07), location: 0),
                                              .init(color: .white.opacity(0), location: 0.4)],
                                      startPoint: .top, endPoint: .bottom)
    static let mineFill = LinearGradient(colors: [Color(red: 0.42, green: 0.20, blue: 0.98), brand],
                                         startPoint: .topLeading, endPoint: .bottomTrailing)
}

/// The glass pane. Reduce Transparency: solid pane, hairline kept (it is
/// separation, not decoration).
struct GlassBackground<S: InsettableShape>: ViewModifier {
    let shape: S
    var tint: AnyShapeStyle? = nil
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    func body(content: Content) -> some View {
        content
            .background {
                ZStack {
                    if let tint {
                        shape.fill(tint)
                    } else {
                        shape.fill(WatchTheme.pane.opacity(reduceTransparency ? 1 : 0.92))
                    }
                    if !reduceTransparency { shape.fill(WatchTheme.sheen) }
                }
            }
            .overlay(shape.strokeBorder(WatchTheme.hairline, lineWidth: 0.75).allowsHitTesting(false))
    }
}

extension View {
    func glass<S: InsettableShape>(_ shape: S, tint: AnyShapeStyle? = nil) -> some View {
        modifier(GlassBackground(shape: shape, tint: tint))
    }
}

/// Localized string with an English default, so a key missing from one of
/// the 34 `.lproj` reads as English, never as the raw key.
func L(_ key: String, _ english: String) -> String {
    NSLocalizedString(key, tableName: nil, bundle: .main, value: english, comment: "")
}

/// The OSHI "O" mark (wire "⭕"): SAME geometry as the phone's
/// `ReactionOSHIO.svg` / Android `ic_reaction_oshi_o.xml` — ring r 27 / r 15
/// about (54,50), even-odd, plus the speech-bubble tail — in the SVG's
/// 64.137-unit viewBox starting at (21.932, 20.327).
struct OSHIOMark: Shape {
    /// The ring (even-odd) and the tail are separate paths, as in the SVG, so
    /// their overlap is never punched out.
    var tail = false

    func path(in rect: CGRect) -> Path {
        let s = min(rect.width, rect.height) / 64.137
        let ox = rect.midX - 32.0685 * s, oy = rect.midY - 32.0685 * s
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            CGPoint(x: ox + (x - 21.932) * s, y: oy + (y - 20.327) * s)
        }
        var path = Path()
        if tail {
            path.move(to: p(49.31, 76.59))
            path.addQuadCurve(to: p(37.10, 81.79), control: p(43.80, 81.38))
            path.addQuadCurve(to: p(33.32, 67.36), control: p(39.84, 68.12))
            path.closeSubpath()
            return path
        }
        path.addEllipse(in: CGRect(origin: p(27, 23), size: CGSize(width: 54 * s, height: 54 * s)))
        path.addEllipse(in: CGRect(origin: p(39, 35), size: CGSize(width: 30 * s, height: 30 * s)))
        return path
    }
}

struct OSHIOMarkView: View {
    var size: CGFloat = 28
    var body: some View {
        ZStack {
            OSHIOMark().fill(WatchTheme.lilac, style: FillStyle(eoFill: true))
            OSHIOMark(tail: true).fill(WatchTheme.lilac)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
