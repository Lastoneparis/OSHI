//
//  MediaDecodeCacheTests.swift
//  OSHITests
//
//  Guards the fix for "when I open the app all my previous pictures are black and
//  take a long time to appear".
//
//  The bug was never decryption — the plaintext bytes are already on the message.
//  It was the JPEG DECODE, called from inside a view BODY, so an 800pt image was
//  re-decoded on the main thread on every re-render: every scroll, every delivery
//  tick, every unrelated state change.
//

import XCTest
@testable import OSHI

final class MediaDecodeCacheTests: XCTestCase {

    /// A tiny real JPEG, so the decoder has something valid to chew on.
    private func sampleJPEG(side: CGFloat = 64) -> Data {
        let size = CGSize(width: side, height: side)
        let image = UIGraphicsImageRenderer(size: size).image { ctx in
            UIColor.systemPurple.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
        }
        return image.jpegData(compressionQuality: 0.9)!
    }

    @MainActor
    func testSecondDecodeOfTheSameImageIsAHit() {
        let cache = MediaAttachmentCache.shared
        let id = "decode-" + UUID().uuidString
        let data = sampleJPEG()

        XCTAssertNil(cache.image(for: id + "@800"), "precondition: nothing cached yet")

        let first = cache.decodedImage(from: data, id: id, maxDimension: 800)
        XCTAssertNotNil(first, "the sample must decode at all")

        // The point of the fix: the SAME UIImage instance comes back, i.e. no
        // second decode happened. Comparing pixels would pass even if it decoded
        // again, which is exactly the bug.
        let second = cache.decodedImage(from: data, id: id, maxDimension: 800)
        XCTAssertTrue(first === second, "a second read must not decode again")
    }

    /// 🔴 The key shape matters as much as the cache.
    ///
    /// The same photo is drawn at 400pt in a reply quote and 1920pt full screen.
    /// If the key ignored the size, opening the viewer would be handed the small
    /// image — which looks like a broken picture, not like a cache hit.
    @MainActor
    func testDifferentSizesDoNotShareAnEntry() {
        let cache = MediaAttachmentCache.shared
        let id = "sizes-" + UUID().uuidString
        let data = sampleJPEG(side: 256)

        let small = cache.decodedImage(from: data, id: id, maxDimension: 64)
        let large = cache.decodedImage(from: data, id: id, maxDimension: 1920)

        XCTAssertNotNil(small)
        XCTAssertNotNil(large)
        XCTAssertFalse(small === large, "400pt and 1920pt must be separate entries")
    }

    /// Surfaces with no message id (previews, one-offs) must still render rather
    /// than all colliding on one shared key.
    @MainActor
    func testNilIdStillDecodesAndDoesNotPoisonTheCache() {
        let cache = MediaAttachmentCache.shared
        let a = cache.decodedImage(from: sampleJPEG(side: 32), id: nil, maxDimension: 200)
        let b = cache.decodedImage(from: sampleJPEG(side: 128), id: nil, maxDimension: 200)
        XCTAssertNotNil(a)
        XCTAssertNotNil(b)
        XCTAssertFalse(a === b, "two different pictures must not share an id-less entry")
    }

    /// The pre-warm has to fill the SAME key the bubble later reads, or it is a
    /// cache that is always warm and never hit.
    @MainActor
    func testWarmFillsTheKeyTheBubbleReads() {
        let cache = MediaAttachmentCache.shared
        let id = "warm-" + UUID().uuidString
        let data = sampleJPEG(side: 128)

        cache.warm([(id: id, data: data)], limit: 4, maxDimension: 800)

        let filled = expectation(description: "pre-warm populated the entry")
        // 🔧 FIX (2026-08-21): this was `for _ in 0..<40` at 50 ms — a 2 s poll
        // budget inside a `timeout: 4`. The two never agreed, so the timeout was
        // decoration: the LOOP gave up first, at 2 s, and the expectation then
        // failed at 4 s for a reason the message did not describe.
        //
        // And 2 s is a wall-clock guess about a DETACHED decode on a machine that
        // routinely runs a dozen parallel builds. Observed here: the same test
        // passing in 1.15 s and failing at 6.08 s in consecutive runs, load
        // average ~300. That is the scheduler being measured, not the cache.
        //
        // The loop now outlasts the timeout, so the timeout is the single thing
        // that decides — and it is generous enough that only a warm that never
        // fills the key can trip it. The assertion is unchanged: if `warm` writes
        // a different key than the bubble reads, this still fails.
        Task { @MainActor in
            while true {
                if cache.image(for: id + "@800") != nil { filled.fulfill(); return }
                try? await Task.sleep(nanoseconds: 50_000_000)
            }
        }
        wait(for: [filled], timeout: 30)
    }
}
