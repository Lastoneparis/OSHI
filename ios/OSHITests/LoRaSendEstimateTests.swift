//
//  LoRaSendEstimateTests.swift
//  OSHITests
//
//  Media-over-LoRa Phase 1 (2026-09-11): the pre-send confirmation shows an
//  airtime estimate, and the send path caps a message at 12 frames. These pin
//  the pure cost arithmetic those two rely on. The real end-to-end send is a
//  two-device LoRa test (no simulator can do radio); this covers the math the
//  UI and the .tooLarge gate compute from.
//

import XCTest
@testable import OSHI

final class LoRaSendEstimateTests: XCTestCase {

    // Chunking matches the send path: loRaChunk (180) bytes per frame.
    func testFrameCount() {
        XCTAssertEqual(MeshtasticManager.loRaFrameCount(wireBytes: 0), 1)   // never zero frames
        XCTAssertEqual(MeshtasticManager.loRaFrameCount(wireBytes: 1), 1)
        XCTAssertEqual(MeshtasticManager.loRaFrameCount(wireBytes: 180), 1)
        XCTAssertEqual(MeshtasticManager.loRaFrameCount(wireBytes: 181), 2)
        XCTAssertEqual(MeshtasticManager.loRaFrameCount(wireBytes: 180 * 12), 12)
        XCTAssertEqual(MeshtasticManager.loRaFrameCount(wireBytes: 180 * 12 + 1), 13)
        XCTAssertEqual(MeshtasticManager.loRaFrameCount(wireBytes: -5), 1)  // guarded
    }

    // ~2 s per frame, in whole seconds.
    func testAirtime() {
        XCTAssertEqual(MeshtasticManager.loRaAirtimeSeconds(frames: 0), 0)
        XCTAssertEqual(MeshtasticManager.loRaAirtimeSeconds(frames: 1), 2)
        XCTAssertEqual(MeshtasticManager.loRaAirtimeSeconds(frames: 12), 24)
    }

    // The compose-time estimate is internally consistent: seconds follow frames,
    // frames follow the conservative wire expansion, and `fits` mirrors the
    // 12-frame ceiling exactly.
    func testMediaEstimateConsistency() {
        for media in [0, 100, 500, 1_000, 1_136, 1_200, 5_000, 100_000] {
            let e = MeshtasticManager.loRaMediaEstimate(mediaBytes: media)
            XCTAssertEqual(e.seconds, e.frames * 2, "seconds must track frames (media=\(media))")
            XCTAssertEqual(e.fits, e.frames <= MeshtasticManager.loRaMaxFrames,
                           "fits must mirror the frame ceiling (media=\(media))")
        }
    }

    // A tiny thumbnail fits; a payload past the ceiling does not.
    func testMediaEstimateBoundary() {
        XCTAssertTrue(MeshtasticManager.loRaMediaEstimate(mediaBytes: 500).fits)
        XCTAssertFalse(MeshtasticManager.loRaMediaEstimate(mediaBytes: 100_000).fits)
    }

    // The estimate is never rosier than reality: its frame count is at least what
    // the same bytes would need at the raw wire size (wireFactor >= 1).
    func testEstimateIsConservative() {
        let media = 900
        let e = MeshtasticManager.loRaMediaEstimate(mediaBytes: media)
        XCTAssertGreaterThanOrEqual(e.frames, MeshtasticManager.loRaFrameCount(wireBytes: media))
    }
}
