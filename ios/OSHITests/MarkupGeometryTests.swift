import XCTest
@testable import OSHI

/// __PHOTO_MARKUP_2026_11_08__
///
/// Same vectors as Android's `MarkupGeometryTest`, so a circle drawn round a
/// face lands in the same place on both phones. The drawing is not tested; this
/// is, because this is where it fails SILENTLY — a stroke recorded in view
/// coordinates lands elsewhere once flattened, and only on photos whose aspect
/// ratio differs from the box.
final class MarkupGeometryTests: XCTestCase {

    func testAWidePhotoLetterboxesVertically() {
        let f = MarkupGeometry.fit(imageW: 2000, imageH: 1000, viewW: 1000, viewH: 1000)
        XCTAssertEqual(f.scale, 0.5, accuracy: 1e-4)
        XCTAssertEqual(f.offsetX, 0, accuracy: 1e-4)
        XCTAssertEqual(f.offsetY, 250, accuracy: 1e-4)
    }

    func testATallPhotoLetterboxesHorizontally() {
        let f = MarkupGeometry.fit(imageW: 1000, imageH: 2000, viewW: 1000, viewH: 1000)
        XCTAssertEqual(f.offsetX, 250, accuracy: 1e-4)
        XCTAssertEqual(f.offsetY, 0, accuracy: 1e-4)
    }

    /// THE test: the centre of the view is the centre of the PHOTO.
    func testTheCentreOfTheViewIsTheCentreOfThePhoto() {
        let p = MarkupGeometry.toImage(x: 500, y: 500, imageW: 2000, imageH: 1000, viewW: 1000, viewH: 1000)
        XCTAssertNotNil(p)
        XCTAssertEqual(p!.x, 1000, accuracy: 1e-3)
        XCTAssertEqual(p!.y, 500, accuracy: 1e-3)
    }

    func testTheTopLeftOfThePhotoMapsToTheOrigin() {
        let p = MarkupGeometry.toImage(x: 0, y: 250, imageW: 2000, imageH: 1000, viewW: 1000, viewH: 1000)
        XCTAssertNotNil(p)
        XCTAssertEqual(p!.x, 0, accuracy: 1e-3)
        XCTAssertEqual(p!.y, 0, accuracy: 1e-3)
    }

    /// A touch in the letterbox belongs to no pixel; clamping would draw a mark
    /// along the edge that the user never made.
    func testATouchInTheLetterboxIsRefused() {
        XCTAssertNil(MarkupGeometry.toImage(x: 500, y: 10, imageW: 2000, imageH: 1000, viewW: 1000, viewH: 1000))
        XCTAssertNil(MarkupGeometry.toImage(x: 500, y: 990, imageW: 2000, imageH: 1000, viewW: 1000, viewH: 1000))
    }

    func testMappingIsConsistentAcrossAspectRatios() {
        for (iw, ih) in [(4000.0, 3000.0), (1080.0, 1920.0), (512.0, 512.0), (3000.0, 1000.0)] {
            let f = MarkupGeometry.fit(imageW: iw, imageH: ih, viewW: 900, viewH: 1200)
            let vx = f.offsetX + (iw / 2) * f.scale
            let vy = f.offsetY + (ih / 2) * f.scale
            let back = MarkupGeometry.toImage(x: vx, y: vy, imageW: iw, imageH: ih, viewW: 900, viewH: 1200)
            XCTAssertNotNil(back, "centre of \(iw)x\(ih) should be on the photo")
            XCTAssertEqual(back!.x, iw / 2, accuracy: 0.5)
            XCTAssertEqual(back!.y, ih / 2, accuracy: 0.5)
        }
    }

    /// A 6 pt pen on a photo shown at quarter size must flatten to 24 px, or
    /// the circle becomes a hairline nobody can see at full resolution.
    func testStrokeWidthScalesUpWithTheImage() {
        let w = MarkupGeometry.strokeWidthInImage(6, imageW: 4000, imageH: 3000, viewW: 1000, viewH: 750)
        XCTAssertEqual(w, 24, accuracy: 0.1)
    }

    func testDegenerateSizesAreSurvivable() {
        XCTAssertEqual(MarkupGeometry.fit(imageW: 0, imageH: 0, viewW: 100, viewH: 100).scale, 1, accuracy: 1e-4)
        XCTAssertEqual(MarkupGeometry.fit(imageW: 100, imageH: 100, viewW: 0, viewH: 0).scale, 1, accuracy: 1e-4)
        XCTAssertNil(MarkupGeometry.toImage(x: 5, y: 5, imageW: 0, imageH: 0, viewW: 100, viewH: 100))
    }
}
