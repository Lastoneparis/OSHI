//
//  PreviewScalingTests.swift
//  OSHITests
//
//  Reported 2026-08-27: "quand je clique sur partager ma vidéo, l'autre la voit
//  mais je ne vois plus que je partage — je devrais me voir aussi."
//
//  The peer seeing the video is the load-bearing half of that sentence: frames
//  WERE being captured and encoded. The device log agrees — "42 image(s)
//  capturée(s) — caméra vivante". So the camera was alive and the failure was
//  downstream of it, in the self-preview branch, which is the code the
//  GPU→CPU readback reduction had just changed.
//
//  `CIContext.createCGImage` returning nil raises nothing. The thumbnail keeps
//  its last frame, or stays empty, and NOTHING says so — a preview failure is
//  indistinguishable from a dead camera. That is why the scaling decision is
//  extracted and tested here rather than left inline: an extent that comes back
//  empty, fractional or inverted still compiles, still runs, and still looks
//  like "there is no preview in this app".
//

import XCTest
import CoreImage
@testable import OSHI

final class PreviewScalingTests: XCTestCase {

    private func image(_ w: Int, _ h: Int) -> CIImage {
        CIImage(color: .gray).cropped(to: CGRect(x: 0, y: 0, width: w, height: h))
    }

    /// The real capture size on every device this ships to.
    func testScalesTheRealCaptureSizeToTheThumbnailBudget() {
        let out = VideoCallManager.previewScaled(image(1280, 720))
        XCTAssertEqual(out.extent.width, 512, accuracy: 1)
        XCTAssertEqual(out.extent.height, 288, accuracy: 1)
    }

    /// Portrait capture — the long edge is the height, and the budget applies to
    /// whichever edge is longer.
    func testPortraitIsScaledOnItsLongEdgeToo() {
        let out = VideoCallManager.previewScaled(image(720, 1280))
        XCTAssertEqual(out.extent.height, 512, accuracy: 1)
        XCTAssertEqual(out.extent.width, 288, accuracy: 1)
    }

    /// THE ONE THAT MATTERS. Whatever the camera hands us, the result must be a
    /// finite, non-empty, integral rectangle — anything else makes
    /// `createCGImage` return nil and the thumbnail silently freezes.
    func testEveryPlausibleCaptureSizeYieldsADrawableExtent() {
        let sizes = [(1280, 720), (720, 1280), (1920, 1080), (1080, 1920),
                     (640, 480), (480, 640), (3840, 2160), (1000, 562),
                     (512, 288), (100, 100), (1, 1)]
        for (w, h) in sizes {
            let out = VideoCallManager.previewScaled(image(w, h))
            let e = out.extent
            XCTAssertTrue(e.width.isFinite && e.height.isFinite, "non-finite extent for \(w)x\(h)")
            XCTAssertGreaterThanOrEqual(e.width, 1, "empty width for \(w)x\(h)")
            XCTAssertGreaterThanOrEqual(e.height, 1, "empty height for \(w)x\(h)")
            XCTAssertEqual(e.width, e.width.rounded(), accuracy: 0.001,
                           "fractional width for \(w)x\(h)")
            XCTAssertEqual(e.height, e.height.rounded(), accuracy: 0.001,
                           "fractional height for \(w)x\(h)")
        }
    }

    /// The view decides which way to rotate the thumbnail by comparing width to
    /// height (`localImage.width > localImage.height`). A scale that inverted
    /// that would flip the selfie on its side.
    func testOrientationIsPreserved() {
        XCTAssertGreaterThan(VideoCallManager.previewScaled(image(1280, 720)).extent.width,
                             VideoCallManager.previewScaled(image(1280, 720)).extent.height)
        XCTAssertLessThan(VideoCallManager.previewScaled(image(720, 1280)).extent.width,
                          VideoCallManager.previewScaled(image(720, 1280)).extent.height)
    }

    /// Never upscale: a frame already smaller than the budget is handed back
    /// untouched, not blown up into a blurry readback that costs MORE.
    func testNeverUpscales() {
        let small = image(320, 180)
        let out = VideoCallManager.previewScaled(small)
        XCTAssertEqual(out.extent.width, 320, accuracy: 1)
        XCTAssertEqual(out.extent.height, 180, accuracy: 1)
    }

    /// An infinite extent would produce a ZERO scale factor, hence a degenerate
    /// image, hence a nil CGImage, hence a frozen thumbnail with no error line.
    /// `CIImage(color:)` is infinite by construction, so this is not theoretical.
    func testInfiniteExtentIsLeftAloneRatherThanCollapsed() {
        let out = VideoCallManager.previewScaled(CIImage(color: .red))
        XCTAssertFalse(out.extent.isEmpty, "collapsed an infinite extent to nothing")
    }

    /// The control: the budget is a real boundary. If `previewTargetLongEdge`
    /// ever exceeded the capture size, every test above would pass while the
    /// reduction did nothing at all — and the 55 MB/s readback would be back
    /// without a single test turning red.
    func testTheBudgetIsActuallyBelowTheCaptureSize() {
        XCTAssertLessThan(VideoCallManager.previewTargetLongEdge, 1280,
                          "the budget no longer reduces the real capture size")
        let full = image(1280, 720).extent
        let cut = VideoCallManager.previewScaled(image(1280, 720)).extent
        XCTAssertLessThan(cut.width * cut.height, full.width * full.height / 4,
                          "the readback is no longer meaningfully smaller")
    }
}
