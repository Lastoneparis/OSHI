import SwiftUI
import UIKit

/// __PHOTO_MARKUP_2026_11_08__
///
/// Where a finger on screen lands in the IMAGE.
///
/// Pulled out of the view and kept pure for the same reason as Android's
/// `MarkupGeometry`, which this mirrors rule for rule: the photo is shown
/// scaled and letterboxed, so a stroke recorded in view coordinates is drawn
/// somewhere else once flattened into the full-resolution image. A circle round
/// a face becomes a circle round a shoulder — and only on photos whose aspect
/// ratio differs from the box, which is exactly the bug that survives a quick
/// test on one picture.
enum MarkupGeometry {

    struct Fitted {
        let offsetX: CGFloat
        let offsetY: CGFloat
        let scale: CGFloat
    }

    /// `ContentScale.Fit` / `.scaledToFit`, restated so it can be tested.
    static func fit(imageW: CGFloat, imageH: CGFloat, viewW: CGFloat, viewH: CGFloat) -> Fitted {
        guard imageW > 0, imageH > 0, viewW > 0, viewH > 0 else {
            return Fitted(offsetX: 0, offsetY: 0, scale: 1)
        }
        let scale = min(viewW / imageW, viewH / imageH)
        return Fitted(
            offsetX: (viewW - imageW * scale) / 2,
            offsetY: (viewH - imageH * scale) / 2,
            scale: scale
        )
    }

    /// A view point to an image point, or nil when the touch is in the
    /// letterbox. Nil rather than clamped: a stroke pinned to the edge is a
    /// mark the user never made.
    static func toImage(
        x: CGFloat, y: CGFloat,
        imageW: CGFloat, imageH: CGFloat,
        viewW: CGFloat, viewH: CGFloat
    ) -> CGPoint? {
        let f = fit(imageW: imageW, imageH: imageH, viewW: viewW, viewH: viewH)
        guard f.scale > 0 else { return nil }
        let ix = (x - f.offsetX) / f.scale
        let iy = (y - f.offsetY) / f.scale
        guard ix >= 0, iy >= 0, ix <= imageW, iy <= imageH else { return nil }
        return CGPoint(x: ix, y: iy)
    }

    /// A pen chosen on screen, in image pixels — or a 6 pt stroke on a 4000 px
    /// photo flattens to a hairline nobody can see.
    static func strokeWidthInImage(
        _ viewWidth: CGFloat,
        imageW: CGFloat, imageH: CGFloat,
        viewW: CGFloat, viewH: CGFloat
    ) -> CGFloat {
        let f = fit(imageW: imageW, imageH: imageH, viewW: viewW, viewH: viewH)
        return f.scale > 0 ? viewWidth / f.scale : viewWidth
    }
}

private struct MarkupStroke: Identifiable {
    let id = UUID()
    let color: Color
    let widthInImage: CGFloat
    var points: [CGPoint]   // IMAGE space
}

/// Draw on a photo before sending it.
///
/// This sheet IS the confirm step on iOS, which had none — a picked image was
/// sent the instant it was chosen. Making markup its own extra step would have
/// added two taps to every photo; folding the confirm into it adds one, and
/// drawing stays entirely optional: Send with no strokes sends the original.
struct PhotoMarkupView: View {
    let image: UIImage
    let onCancel: () -> Void
    let onSend: (Data) -> Void

    @State private var strokes: [MarkupStroke] = []
    @State private var penColor: Color = Self.pens[0]

    /// Bright on a photograph, and still distinguishable from each other thin.
    static let pens: [Color] = [
        Color(red: 1.00, green: 0.23, blue: 0.19),
        Color(red: 1.00, green: 0.80, blue: 0.00),
        Color(red: 0.20, green: 0.78, blue: 0.35),
        Color(red: 0.04, green: 0.52, blue: 1.00),
        .white,
        .black,
    ]

    private let penWidthOnScreen: CGFloat = 5

    var body: some View {
        VStack(spacing: 0) {
            GeometryReader { geo in
                let viewW = geo.size.width
                let viewH = geo.size.height
                let f = MarkupGeometry.fit(
                    imageW: image.size.width, imageH: image.size.height,
                    viewW: viewW, viewH: viewH
                )
                ZStack {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                    // Strokes are stored in image space and projected back out
                    // here, so the preview and the flatten read the same data
                    // rather than being two parallel attempts at it.
                    Canvas { ctx, _ in
                        for s in strokes where s.points.count > 1 {
                            var path = Path()
                            path.move(to: project(s.points[0], f))
                            for p in s.points.dropFirst() { path.addLine(to: project(p, f)) }
                            ctx.stroke(
                                path,
                                with: .color(s.color),
                                style: StrokeStyle(
                                    lineWidth: s.widthInImage * f.scale,
                                    lineCap: .round, lineJoin: .round
                                )
                            )
                        }
                    }
                }
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { v in
                            guard let p = MarkupGeometry.toImage(
                                x: v.location.x, y: v.location.y,
                                imageW: image.size.width, imageH: image.size.height,
                                viewW: viewW, viewH: viewH
                            ) else { return }
                            if v.translation == .zero || strokes.isEmpty {
                                strokes.append(MarkupStroke(
                                    color: penColor,
                                    widthInImage: MarkupGeometry.strokeWidthInImage(
                                        penWidthOnScreen,
                                        imageW: image.size.width, imageH: image.size.height,
                                        viewW: viewW, viewH: viewH
                                    ),
                                    points: [p]
                                ))
                            } else {
                                strokes[strokes.count - 1].points.append(p)
                            }
                        }
                )
            }

            HStack(spacing: 14) {
                ForEach(Array(Self.pens.enumerated()), id: \.offset) { _, c in
                    Circle()
                        .fill(c)
                        .frame(width: c == penColor ? 30 : 24, height: c == penColor ? 30 : 24)
                        .overlay(Circle().stroke(.white.opacity(c == penColor ? 1 : 0.4),
                                                 lineWidth: c == penColor ? 2 : 1))
                        .onTapGesture { penColor = c }
                }
                Spacer()
                Button("markup.undo".localized) {
                    if !strokes.isEmpty { strokes.removeLast() }
                }
                .disabled(strokes.isEmpty)
                .foregroundColor(.white)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)

            HStack {
                Button("common.cancel".localized, action: onCancel)
                    .foregroundColor(.white)
                Spacer()
                Button("chat.send".localized) {
                    onSend(flattened())
                }
                .fontWeight(.semibold)
                .foregroundColor(.white)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 12)
        }
        .background(Color.black.ignoresSafeArea())
    }

    private func project(_ p: CGPoint, _ f: MarkupGeometry.Fitted) -> CGPoint {
        CGPoint(x: f.offsetX + p.x * f.scale, y: f.offsetY + p.y * f.scale)
    }

    /// Burn the strokes in at FULL resolution.
    ///
    /// PNG when anything was drawn — a thin bright line over a photograph is
    /// exactly what JPEG ringing destroys, and the mark exists to be legible.
    /// Untouched photos keep their original JPEG so sending a plain picture
    /// does not silently triple its size.
    private func flattened() -> Data {
        guard !strokes.isEmpty else {
            return image.jpegData(compressionQuality: 0.9) ?? Data()
        }
        let renderer = UIGraphicsImageRenderer(size: image.size)
        let out = renderer.image { ctx in
            image.draw(at: .zero)
            let cg = ctx.cgContext
            cg.setLineCap(.round)
            cg.setLineJoin(.round)
            for s in strokes where s.points.count > 1 {
                cg.setStrokeColor(UIColor(s.color).cgColor)
                cg.setLineWidth(s.widthInImage)
                cg.beginPath()
                cg.move(to: s.points[0])
                for p in s.points.dropFirst() { cg.addLine(to: p) }
                cg.strokePath()
            }
        }
        return out.pngData() ?? (image.jpegData(compressionQuality: 0.9) ?? Data())
    }
}

/// __PHOTO_MARKUP_2026_11_08__ `UIImage` is not `Identifiable`, which
/// `.sheet(item:)` requires. Wrapping it also makes the invalid state —
/// a markup sheet with no photo — unrepresentable rather than guarded.
struct MarkupSubject: Identifiable {
    let id = UUID()
    let image: UIImage
}
