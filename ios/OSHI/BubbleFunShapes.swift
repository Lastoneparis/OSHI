//
//  BubbleFunShapes.swift
//  OSHI
//
//  Playful bubble silhouettes — comic-book burst, heart, car, football, tennis
//  ball, cat, dog.
//
//  THE RULE THAT MAKES THESE USABLE
//  --------------------------------
//  A bubble is a container for text someone has to READ. A literal car-shaped or
//  cat-shaped text container is unreadable: the glyphs get pushed into wheels and
//  ears, the line lengths jump around, and a long message either overflows the
//  silhouette or shrinks to nothing.
//
//  So none of these shapes contain the text. Every one of them is built the same
//  way: a rectangular READING CORE that behaves exactly like the ordinary bubble,
//  plus a silhouette drawn AROUND it — wheels below, ears above, a heart's lobes
//  outside the text box. The message is always laid out in a plain rounded
//  rectangle; the personality lives in the parts no glyph ever touches.
//
//  That is also why each shape declares `coreInsets`: how much room its
//  decoration needs on each edge. The bubble adds that padding, so a cat bubble
//  is a normal bubble that happens to have ears, not a normal bubble with its
//  first line hidden behind one.
//
//  Consequence worth stating: these are for SHORT messages. A 300-word paragraph
//  in a heart bubble is a very tall heart. The shapes stay legible because the
//  core grows and the decoration scales with it, but nobody should pretend a
//  football is a good container for an essay.
//

import SwiftUI
import CoreGraphics

// MARK: - Silhouette

/// The decorative outline drawn around a bubble's reading core.
enum BubbleSilhouette: Int, CaseIterable, Identifiable, Codable, Sendable {
    case comic = 0
    case heart
    case car
    case football
    case tennis
    case cat
    case dog

    var id: Int { rawValue }

    /// Extra room the decoration needs, on top of the normal text padding.
    ///
    /// These are the numbers that keep the text readable, so they are declared
    /// next to the drawing rather than guessed at the call site.
    var coreInsets: EdgeInsets {
        switch self {
        case .comic:    return EdgeInsets(top: 10, leading: 12, bottom: 10, trailing: 12)
        case .heart:    return EdgeInsets(top: 26, leading: 18, bottom: 30, trailing: 18)
        case .car:      return EdgeInsets(top: 16, leading: 10, bottom: 16, trailing: 10)
        case .football: return EdgeInsets(top: 14, leading: 14, bottom: 14, trailing: 14)
        case .tennis:   return EdgeInsets(top: 14, leading: 14, bottom: 14, trailing: 14)
        case .cat:      return EdgeInsets(top: 22, leading: 10, bottom: 12, trailing: 10)
        case .dog:      return EdgeInsets(top: 20, leading: 16, bottom: 12, trailing: 16)
        }
    }

    var titleKey: String {
        switch self {
        case .comic:    return "bubble.shape.comic"
        case .heart:    return "bubble.shape.heart"
        case .car:      return "bubble.shape.car"
        case .football: return "bubble.shape.football"
        case .tennis:   return "bubble.shape.tennis"
        case .cat:      return "bubble.shape.cat"
        case .dog:      return "bubble.shape.dog"
        }
    }

    /// One emoji for the picker row, so the setting is recognisable before it is
    /// read. Not a substitute for the localized name — it sits beside it.
    var sample: String {
        switch self {
        case .comic:    return "💥"
        case .heart:    return "❤️"
        case .car:      return "🚗"
        case .football: return "⚽️"
        case .tennis:   return "🎾"
        case .cat:      return "🐱"
        case .dog:      return "🐶"
        }
    }
}

// MARK: - The shapes

/// Comic-book burst: the jagged "POW!" balloon.
///
/// The spikes are generated on a circle inscribed in the rect and pushed in/out
/// alternately, which is why the text core is inset — the inner radius is what
/// the message actually gets.
struct ComicBurstShape: Shape {
    var spikes: Int = 16
    var depth: CGFloat = 0.12

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let cx = rect.midX, cy = rect.midY
        let rx = rect.width / 2, ry = rect.height / 2
        let steps = max(8, spikes * 2)
        for i in 0..<steps {
            let angle: CGFloat = (CGFloat(i) / CGFloat(steps)) * 2 * .pi - .pi / 2
            // Alternate between the full radius and a pulled-in one.
            let pull: CGFloat = (i % 2 == 0) ? 1.0 : (1.0 - depth)
            let p = CGPoint(x: cx + CoreGraphics.cos(angle) * rx * pull,
                            y: cy + CoreGraphics.sin(angle) * ry * pull)
            if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
        }
        path.closeSubpath()
        return path
    }
}

/// Heart. Two lobes and a point, sized so the text core sits across the widest
/// band rather than in the narrowing tip.
struct HeartShape: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        let w = rect.width, h = rect.height
        let topY = rect.minY + h * 0.28
        p.move(to: CGPoint(x: rect.midX, y: rect.maxY))
        p.addCurve(to: CGPoint(x: rect.minX, y: topY),
                   control1: CGPoint(x: rect.midX - w * 0.42, y: rect.maxY - h * 0.32),
                   control2: CGPoint(x: rect.minX, y: rect.minY + h * 0.62))
        p.addArc(center: CGPoint(x: rect.minX + w * 0.25, y: topY),
                 radius: w * 0.25, startAngle: .degrees(180), endAngle: .degrees(0),
                 clockwise: false)
        p.addArc(center: CGPoint(x: rect.minX + w * 0.75, y: topY),
                 radius: w * 0.25, startAngle: .degrees(180), endAngle: .degrees(0),
                 clockwise: false)
        p.addCurve(to: CGPoint(x: rect.midX, y: rect.maxY),
                   control1: CGPoint(x: rect.maxX, y: rect.minY + h * 0.62),
                   control2: CGPoint(x: rect.midX + w * 0.42, y: rect.maxY - h * 0.32))
        p.closeSubpath()
        return p
    }
}

/// Car: a body with a cabin above and two wheels below. The reading core is the
/// body, which is why `coreInsets` reserves the top and bottom bands.
struct CarShape: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        let bodyTop = rect.minY + rect.height * 0.30
        let bodyBottom = rect.maxY - rect.height * 0.16
        let body = CGRect(x: rect.minX, y: bodyTop,
                          width: rect.width, height: bodyBottom - bodyTop)
        p.addRoundedRect(in: body, cornerSize: CGSize(width: 16, height: 16),
                         style: .continuous)

        // Cabin — a smaller roof block centred on the body.
        let cabin = CGRect(x: rect.minX + rect.width * 0.20, y: rect.minY,
                           width: rect.width * 0.60, height: (bodyTop - rect.minY) * 1.35)
        p.addRoundedRect(in: cabin, cornerSize: CGSize(width: 12, height: 12),
                         style: .continuous)

        // Wheels.
        let r = min(rect.height * 0.16, rect.width * 0.12)
        for cx in [rect.minX + rect.width * 0.24, rect.maxX - rect.width * 0.24] {
            p.addEllipse(in: CGRect(x: cx - r, y: bodyBottom - r * 0.55,
                                    width: r * 2, height: r * 2))
        }
        return p
    }
}

/// A ball. `seam` only changes the decoration drawn on top, not this outline —
/// football and tennis share the silhouette and differ in their markings.
struct BallShape: Shape {
    func path(in rect: CGRect) -> Path {
        // A capsule, not a circle: a circle wastes horizontal room and forces the
        // text into a tiny square. This still reads as a ball once the markings
        // are on it, and it holds a sentence.
        Capsule(style: .continuous).path(in: rect)
    }
}

/// Cat: rounded head with two triangular ears. Ears live entirely above the
/// reading core.
struct CatShape: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        let earH = rect.height * 0.18
        let head = CGRect(x: rect.minX, y: rect.minY + earH,
                          width: rect.width, height: rect.height - earH)
        p.addRoundedRect(in: head, cornerSize: CGSize(width: 22, height: 22),
                         style: .continuous)

        for cx in [rect.minX + rect.width * 0.22, rect.maxX - rect.width * 0.22] {
            let half = rect.width * 0.10
            p.move(to: CGPoint(x: cx - half, y: rect.minY + earH + 2))
            p.addLine(to: CGPoint(x: cx, y: rect.minY))
            p.addLine(to: CGPoint(x: cx + half, y: rect.minY + earH + 2))
            p.closeSubpath()
        }
        return p
    }
}

/// Dog: rounded head with two floppy ears on the sides. The ears sit OUTSIDE the
/// core horizontally, which is what `coreInsets` reserves on leading/trailing.
struct DogShape: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        let earW = rect.width * 0.13
        let head = CGRect(x: rect.minX + earW * 0.55, y: rect.minY + rect.height * 0.10,
                          width: rect.width - earW * 1.10,
                          height: rect.height - rect.height * 0.10)
        p.addRoundedRect(in: head, cornerSize: CGSize(width: 24, height: 24),
                         style: .continuous)

        let earH = rect.height * 0.52
        for x in [rect.minX, rect.maxX - earW] {
            p.addRoundedRect(in: CGRect(x: x, y: rect.minY + rect.height * 0.16,
                                        width: earW, height: earH),
                             cornerSize: CGSize(width: earW * 0.6, height: earW * 0.6),
                             style: .continuous)
        }
        return p
    }
}

// MARK: - Markings

/// The details that turn a silhouette into a recognisable thing: a football's
/// pentagon, a tennis ball's seam, a cat's whiskers.
///
/// Drawn as a low-opacity OVERLAY, never behind the text, and always
/// `.allowsHitTesting(false)` so nothing here can eat a tap on the bubble.
struct BubbleMarkings: View {
    let silhouette: BubbleSilhouette
    let ink: Color

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            switch silhouette {
            case .football:
                // One pentagon at each end, where no text is.
                ForEach([0.10, 0.90], id: \.self) { fx in
                    Path { p in
                        let c = CGPoint(x: w * fx, y: h / 2)
                        let r = min(w, h) * 0.16
                        for i in 0..<5 {
                            let a: CGFloat = (CGFloat(i) / 5) * 2 * .pi - .pi / 2
                            let px: CGFloat = c.x + CoreGraphics.cos(a) * r
                            let py: CGFloat = c.y + CoreGraphics.sin(a) * r
                            let pt = CGPoint(x: px, y: py)
                            if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
                        }
                        p.closeSubpath()
                    }
                    .fill(ink.opacity(0.35))
                }
            case .tennis:
                // The seam: two arcs curving in from the ends.
                ForEach([0.0, 1.0], id: \.self) { side in
                    Path { p in
                        let x = side == 0 ? w * 0.06 : w * 0.94
                        let bend: CGFloat = side == 0 ? w * 0.16 : -w * 0.16
                        p.move(to: CGPoint(x: x, y: h * 0.06))
                        p.addQuadCurve(to: CGPoint(x: x, y: h * 0.94),
                                       control: CGPoint(x: x + bend, y: h / 2))
                    }
                    .stroke(ink.opacity(0.45), lineWidth: 2)
                }
            case .cat:
                // Whiskers, low and to the sides — clear of the text core.
                ForEach(0..<3, id: \.self) { i in
                    // Split out: the inline arithmetic here was enough to make the
                    // type-checker give up ("unable to type-check in reasonable
                    // time"), which is a compile error, not a warning.
                    let step: CGFloat = CGFloat(i) * 0.07
                    let frac: CGFloat = 0.74 + step
                    let y: CGFloat = h * frac
                    Path { p in
                        p.move(to: CGPoint(x: w * 0.06, y: y))
                        p.addLine(to: CGPoint(x: w * 0.20, y: y - h * 0.02))
                        p.move(to: CGPoint(x: w * 0.94, y: y))
                        p.addLine(to: CGPoint(x: w * 0.80, y: y - h * 0.02))
                    }
                    .stroke(ink.opacity(0.30), lineWidth: 1.5)
                }
            case .car:
                // Two headlights at the front of the body.
                Path { p in
                    p.addEllipse(in: CGRect(x: w * 0.02, y: h * 0.52,
                                            width: w * 0.05, height: h * 0.10))
                    p.addEllipse(in: CGRect(x: w * 0.93, y: h * 0.52,
                                            width: w * 0.05, height: h * 0.10))
                }
                .fill(ink.opacity(0.40))
            case .comic, .heart, .dog:
                // These read on their outline alone. Adding marks to a heart just
                // makes it a busy heart.
                EmptyView()
            }
        }
        .allowsHitTesting(false)
    }
}

// MARK: - Applying it

extension BubbleSilhouette {
    /// The outline for this silhouette, type-erased so a `switch` can return one.
    @ViewBuilder
    func filled(with style: some ShapeStyle) -> some View {
        switch self {
        case .comic:    ComicBurstShape().fill(style)
        case .heart:    HeartShape().fill(style)
        case .car:      CarShape().fill(style)
        case .football: BallShape().fill(style)
        case .tennis:   BallShape().fill(style)
        case .cat:      CatShape().fill(style)
        case .dog:      DogShape().fill(style)
        }
    }
}
