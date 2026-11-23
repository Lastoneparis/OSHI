//
//  OSHIWallpapers.swift
//  OSHI — the animated conversation backdrops.
//
//  Every one of these is DRAWN, not shipped as an image. The bundled artwork was
//  768×1408, which is upscaled and visibly soft on a 1206×2622 phone — that is
//  what "better quality wallpaper" actually means here. Vector drawing is sharp
//  at any size, costs a few hundred bytes instead of tens of kilobytes, adapts
//  to light and dark on its own, and can move.
//
//  The subjects are OSHI's own: a mesh of nodes, radio waves, contour lines from
//  the offline maps, a field of ciphertext. A backdrop that says something about
//  the product beats a generic gradient.
//
//  DESIGN RULE, applied to all of them: a chat wallpaper is read THROUGH. Every
//  pixel sits under text someone is trying to read. So opacities stay low, the
//  motion is slow enough to be felt rather than watched, and nothing is drawn
//  with hard contrast. If a backdrop is the first thing you notice, it is wrong.
//

import SwiftUI

// MARK: - Catalogue

enum OSHIWallpaper: Int, CaseIterable, Identifiable, Codable {
    case aurora = 0      // brand blooms, drifting
    case constellation   // a mesh of nodes and links — OSHI's own picture
    case signal          // concentric transmission rings
    case topography      // contour lines, from the offline maps
    case cipher          // a slow field of ciphertext
    case waves           // long LoRa wavefronts

    var id: Int { rawValue }

    var titleKey: String {
        switch self {
        case .aurora:       return "wp.aurora"
        case .constellation:return "wp.constellation"
        case .signal:       return "wp.signal"
        case .topography:   return "wp.topography"
        case .cipher:       return "wp.cipher"
        case .waves:        return "wp.waves"
        }
    }
}

// MARK: - Renderer

struct OSHIWallpaperView: View {
    let style: OSHIWallpaper
    /// Thumbnails render still: a grid of moving previews is noise, and the
    /// large preview above the picker already shows the motion.
    var animated: Bool = true

    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @State private var phase: CGFloat = 0

    /// Reduce Motion and Low Power Mode both freeze the backdrop on its first
    /// frame (__CHAT_POLISH_2026_09_24__: Low Power was ignored before).
    private var moves: Bool {
        animated && !reduceMotion && !ProcessInfo.processInfo.isLowPowerModeEnabled
    }

    private var brand: Color { Theme.brand }
    private var accent: Color { Color(red: 0.180, green: 0.320, blue: 0.960) }

    /// One knob for how present the artwork is. Dark mode can take more because
    /// the ink is lighter than the ground; light mode has to stay very quiet or
    /// it competes with black text.
    ///
    /// __CHAT_POLISH_2026_09_24__ 0.26 / 0.13 → 0.22 / 0.11: the new ground
    /// (glows + ramp) carries part of the presence the lines used to, so the
    /// lines themselves can step back. Same values as Android `inkFor`.
    private var ink: Double { scheme == .dark ? 0.22 : 0.11 }

    var body: some View {
        ZStack {
            OSHIWallpaperGround()
            if !reduceTransparency {
                content
                    .allowsHitTesting(false)
                    // 🔧 FIX (2026-08-07, "black band down the right edge of the
                    // default wallpaper"): this used to carry `.drawingGroup()`.
                    // That rasterises the subtree into an offscreen buffer sized
                    // to the view's bounds AT THAT POINT — which is the
                    // safe-area-inset bounds, because the `.ignoresSafeArea()`
                    // that expands them is applied further out, in
                    // ChatWallpaperView. The bitmap was therefore narrower than
                    // the area it had to fill, and the difference showed as an
                    // unpainted strip.
                    //
                    // Removing it also costs nothing: every style here is a
                    // single `Canvas` or a handful of gradients — already one
                    // layer — so the flattening pass had nothing to flatten and
                    // was pure overhead plus a texture upload per frame.
            }
        }
        .onAppear {
            guard moves else { return }
            withAnimation(.linear(duration: period).repeatForever(autoreverses: false)) {
                phase = 1
            }
        }
    }

    /// Slow on purpose. A backdrop that completes a cycle in under half a minute
    /// reads as an animation; these read as weather.
    private var period: Double {
        switch style {
        case .aurora:        return 40
        case .constellation: return 60
        case .signal:        return 26
        case .topography:    return 70
        case .cipher:        return 50
        case .waves:         return 34
        }
    }

    @ViewBuilder
    private var content: some View {
        switch style {
        case .aurora:        aurora
        case .constellation: constellation
        case .signal:        signal
        case .topography:    topography
        case .cipher:        cipher
        case .waves:         waves
        }
    }

    // MARK: Styles

    private var aurora: some View {
        GeometryReader { geo in
            let d = max(geo.size.width, geo.size.height)
            let t = CGFloat(sin(Double(phase) * 2 * .pi))
            ZStack {
                bloom(brand, ink * 1.6, d * 0.9)
                    .offset(x: -d * 0.10 * t, y: d * 0.06 * t)
                bloom(accent, ink * 1.2, d * 0.8)
                    .offset(x: d * 0.12 * t, y: -d * 0.05 * t)
                bloom(Color(red: 0.85, green: 0.25, blue: 0.56), ink * 0.8, d * 0.7)
                    .offset(x: d * 0.06 * t, y: d * 0.10 * t)
            }
        }
    }

    /// OSHI's own picture: peers, and the links between them. Node positions come
    /// from a fixed pseudo-random sequence so the layout is stable across
    /// launches — a mesh that reshuffles every time you open a chat is jarring.
    private var constellation: some View {
        Canvas { ctx, size in
            let nodes = Self.meshNodes(in: size)
            let drift = CGFloat(sin(Double(phase) * 2 * .pi)) * 6

            // Links first, so nodes sit on top of them.
            //
            // __CHAT_POLISH_2026_09_24__ Links now FADE WITH LENGTH, in three
            // buckets (three strokes per frame, not one per link): short links
            // are the strongest, long ones barely there. That is what turns a
            // flat web into a field with depth, and it thins the long diagonal
            // strokes that used to cross right under the text.
            let linkMax = size.width * 0.30
            var near = Path(), mid = Path(), far = Path()
            for i in nodes.indices {
                for j in (i + 1)..<nodes.count {
                    let a = nodes[i], b = nodes[j]
                    let dx = a.x - b.x, dy = a.y - b.y
                    let dist = (dx * dx + dy * dy).squareRoot()
                    // Only near neighbours, or the field turns into a solid web.
                    guard dist < linkMax else { continue }
                    let from = CGPoint(x: a.x + drift, y: a.y)
                    let to = CGPoint(x: b.x - drift, y: b.y)
                    switch dist / linkMax {
                    case ..<0.45: near.move(to: from); near.addLine(to: to)
                    case ..<0.75: mid.move(to: from); mid.addLine(to: to)
                    default:      far.move(to: from); far.addLine(to: to)
                    }
                }
            }
            ctx.stroke(far, with: .color(brand.opacity(ink * 0.28)), lineWidth: 0.7)
            ctx.stroke(mid, with: .color(brand.opacity(ink * 0.45)), lineWidth: 0.85)
            ctx.stroke(near, with: .color(brand.opacity(ink * 0.62)), lineWidth: 1)

            for (idx, n) in nodes.enumerated() {
                let hub = idx % 5 == 0
                let r: CGFloat = hub ? 3.0 : 1.7
                let p = CGPoint(x: n.x + (idx % 2 == 0 ? drift : -drift), y: n.y)
                if hub {
                    // A soft halo around the hubs: a lit node, not a dot.
                    let h = r * 3.2
                    ctx.fill(Path(ellipseIn: CGRect(x: p.x - h, y: p.y - h, width: h * 2, height: h * 2)),
                             with: .radialGradient(Gradient(colors: [accent.opacity(ink * 0.55), .clear]),
                                                   center: p, startRadius: 0, endRadius: h))
                }
                ctx.fill(Path(ellipseIn: CGRect(x: p.x - r, y: p.y - r,
                                                width: r * 2, height: r * 2)),
                         with: .color(accent.opacity(ink * (hub ? 1.5 : 1.2))))
            }
        }
    }

    private var signal: some View {
        GeometryReader { geo in
            let d = max(geo.size.width, geo.size.height)
            ZStack {
                ForEach(0..<5, id: \.self) { i in
                    // Each ring expands and fades on its own offset, so the set
                    // reads as continuous transmission rather than a pulse.
                    let local = (phase + CGFloat(i) / 5).truncatingRemainder(dividingBy: 1)
                    Circle()
                        .stroke(brand.opacity(ink * Double(1 - local) * 1.4), lineWidth: 1.4)
                        .frame(width: d * (0.15 + local * 1.15))
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
    }

    /// Contour lines — a nod to the offline maps, and the quietest of the set.
    private var topography: some View {
        Canvas { ctx, size in
            let shift = CGFloat(sin(Double(phase) * 2 * .pi)) * 10
            for band in 0..<14 {
                var path = Path()
                let baseY = size.height * (CGFloat(band) / 13)
                let amp = 16 + CGFloat((band * 7) % 22)
                let wl = 120 + CGFloat((band * 31) % 90)
                path.move(to: CGPoint(x: -20, y: baseY))
                var x: CGFloat = -20
                while x <= size.width + 20 {
                    let y = baseY + sin((x + shift * CGFloat(band % 3 + 1)) / wl) * amp
                    path.addLine(to: CGPoint(x: x, y: y))
                    x += 6
                }
                ctx.stroke(path,
                           with: .color(brand.opacity(ink * (band % 3 == 0 ? 0.8 : 0.45))),
                           lineWidth: band % 3 == 0 ? 1.2 : 0.8)
            }
        }
    }

    /// A field of ciphertext, drifting upward. Glyphs come from a fixed sequence
    /// so it never renders anything that could be mistaken for real content.
    private var cipher: some View {
        Canvas { ctx, size in
            let glyphs = Array("0123456789ABCDEF")
            let cell: CGFloat = 26
            let cols = Int(size.width / cell) + 1
            let rows = Int(size.height / cell) + 2
            let rise = phase * cell * 2

            for r in 0..<rows {
                for c in 0..<cols {
                    // Sparse: a full grid is a texture, a scattered one is a hint.
                    let seed = (r * 31 + c * 17) % 100
                    guard seed % 7 == 0 else { continue }
                    let ch = glyphs[(r * 13 + c * 7) % glyphs.count]
                    let y = CGFloat(r) * cell - rise
                    let text = Text(String(ch))
                        .font(.scaled(12, weight: .medium, design: .monospaced))
                        .foregroundColor(brand.opacity(ink * (seed % 3 == 0 ? 1.1 : 0.6)))
                    ctx.draw(text, at: CGPoint(x: CGFloat(c) * cell + cell / 2,
                                               y: y.truncatingRemainder(dividingBy: size.height + cell)))
                }
            }
        }
    }

    /// Long wavefronts — LoRa, the thing that makes OSHI work with no network.
    private var waves: some View {
        Canvas { ctx, size in
            let shift = phase * size.width
            for i in 0..<7 {
                var path = Path()
                let baseY = size.height * (0.18 + CGFloat(i) * 0.12)
                let amp = 20 + CGFloat(i) * 5
                let wl = size.width / (1.1 + CGFloat(i) * 0.25)
                path.move(to: CGPoint(x: -40, y: baseY))
                var x: CGFloat = -40
                while x <= size.width + 40 {
                    let y = baseY + sin((x + shift * (0.4 + CGFloat(i) * 0.1)) / wl * 2 * .pi) * amp
                    path.addLine(to: CGPoint(x: x, y: y))
                    x += 8
                }
                ctx.stroke(path,
                           with: .linearGradient(
                               Gradient(colors: [brand.opacity(ink * 0.2),
                                                 accent.opacity(ink),
                                                 brand.opacity(ink * 0.2)]),
                               startPoint: .zero,
                               endPoint: CGPoint(x: size.width, y: 0)),
                           lineWidth: 1.6)
            }
        }
    }

    // MARK: Helpers

    private func bloom(_ c: Color, _ o: Double, _ r: CGFloat) -> some View {
        RadialGradient(colors: [c.opacity(o), .clear],
                       center: .center, startRadius: 0, endRadius: r)
    }

    /// Stable node layout. A deterministic generator, NOT `Double.random`, so the
    /// mesh is identical every launch and on every device.
    private static func meshNodes(in size: CGSize) -> [CGPoint] {
        var pts: [CGPoint] = []
        var seed: UInt64 = 0x5EED
        func next() -> CGFloat {
            seed = seed &* 6364136223846793005 &+ 1442695040888963407
            return CGFloat((seed >> 33) % 10_000) / 10_000
        }
        for _ in 0..<46 {
            pts.append(CGPoint(x: next() * size.width, y: next() * size.height))
        }
        return pts
    }
}
