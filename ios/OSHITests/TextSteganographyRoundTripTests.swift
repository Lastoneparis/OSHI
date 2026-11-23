//
//  TextSteganographyRoundTripTests.swift
//  OSHITests
//
//  Guards the one property text steganography must have and silently lacked:
//  what embed() hides, extract() must give back. The default zero-width method
//  was broken because extractZeroWidth iterated Swift `Character`s (grapheme
//  clusters) and the zero-width joiner/non-joiner bits fuse with their visible
//  neighbours — so every hidden bit was dropped and nothing round-tripped. No
//  test existed to catch it. These exercise the real public API end to end
//  (encrypt → embed → extract → decrypt → decode) and fail on the old code.
//

import XCTest
@testable import OSHI

final class TextSteganographyRoundTripTests: XCTestCase {

    private let stego = TextSteganography.shared
    private let key = Data("round-trip-test-key-32-bytes!!!!".utf8)
    private let cover = "Just saw the new menu at that cafe downtown — the pastries look amazing honestly, you should try one this weekend if you get a free afternoon."

    private func fragment(_ body: String) -> CovertFragment {
        let payload = Data(body.utf8)
        return CovertFragment(messageId: "msg-\(body.count)",
                              fragmentIndex: 0,
                              totalFragments: 1,
                              channel: .textSteganography,
                              payload: payload,
                              checksum: Data(payload.prefix(4)))
    }

    func testZeroWidthRoundTrips() throws {
        for body in ["hi",
                     "Meet at 9pm by the fountain.",
                     "clé 🔑 secret ✅ message",
                     String(repeating: "x", count: 200)] {
            let original = fragment(body)
            let data = try JSONEncoder().encode(original)
            let stegoText = try XCTUnwrap(
                stego.embed(data: data, in: cover, secretKey: key, method: .zeroWidth),
                "embed returned nil for \(body.utf8.count) bytes")
            let recovered = try stego.extract(from: stegoText, secretKey: key, method: .zeroWidth)
            XCTAssertEqual(recovered.payload, original.payload,
                           "payload must round-trip (\(body.utf8.count) B)")
            XCTAssertEqual(recovered.messageId, original.messageId)
        }
    }

    func testAutoDetectRoundTrips() throws {
        let original = fragment("auto-detect has to find the zero-width data")
        let data = try JSONEncoder().encode(original)
        let stegoText = try XCTUnwrap(
            stego.embed(data: data, in: cover, secretKey: key, method: .zeroWidth))
        // method: nil → the extractor auto-detects; it tries zero-width first.
        let recovered = try stego.extract(from: stegoText, secretKey: key, method: nil)
        XCTAssertEqual(recovered.payload, original.payload)
    }

    func testVisibleTextLooksUnchanged() throws {
        // The carrier's visible characters must be untouched — only invisible
        // zero-width scalars are added.
        let data = try JSONEncoder().encode(fragment("invisible"))
        let stegoText = try XCTUnwrap(
            stego.embed(data: data, in: cover, secretKey: key, method: .zeroWidth))
        let zeroWidths: Set<Unicode.Scalar> = ["\u{200B}", "\u{200C}", "\u{200D}", "\u{FEFF}"]
        let visible = String(String.UnicodeScalarView(stegoText.unicodeScalars.filter { !zeroWidths.contains($0) }))
        XCTAssertEqual(visible, cover, "stego text must read identically once the zero-width scalars are stripped")
    }

    func testUnicodeSanitizationFailsClosedInsteadOfReturningAlteredPayload() throws {
        let original = fragment("lossy text transformations must not leak altered data")
        let data = try JSONEncoder().encode(original)
        let carrier = try XCTUnwrap(stego.embed(data: data, in: cover, secretKey: key, method: .zeroWidth))
        let stripped = String(String.UnicodeScalarView(carrier.unicodeScalars.filter {
            !["\u{200B}", "\u{200C}", "\u{200D}", "\u{FEFF}"].contains($0)
        }))

        XCTAssertThrowsError(try stego.extract(from: stripped, secretKey: key, method: .zeroWidth))
    }
}
