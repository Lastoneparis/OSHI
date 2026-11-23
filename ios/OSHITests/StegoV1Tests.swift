//
//  StegoV1Tests.swift
//  STEGO-v1 "Disguise as photo" (docs/stego/STEGO-v1.md) + audit 2026-11-18
//  findings 3 (cover pull jitter) and 5 (Accept-Encoding identity).
//
//  The cross-platform vectors are the SAME files Android's StegoV1VectorTest
//  checks: docs/fixtures/stego/{vectors.json, android_v1.png, ios_v1.png}.
//

import XCTest
import SwiftUI
import CryptoKit
import ImageIO
import UniformTypeIdentifiers
@testable import OSHI

final class StegoV1Tests: XCTestCase {

    private func sha(_ s: String) -> Data { Data(SHA256.hash(data: Data(s.utf8))) }
    private func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
    private func unhex(_ s: String) -> Data {
        Data(stride(from: 0, to: s.count, by: 2).map { i -> UInt8 in
            let a = s.index(s.startIndex, offsetBy: i); return UInt8(s[a..<s.index(a, offsetBy: 2)], radix: 16)! })
    }
    private var fixtures: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("docs/fixtures/stego")
    }

    private let alice = Curve25519.KeyAgreement.PrivateKey()
    private let bob = Curve25519.KeyAgreement.PrivateKey()
    private let eve = Curve25519.KeyAgreement.PrivateKey()

    private func noisyCover(_ w: Int = 320, _ h: Int = 240) -> StegoV1.RgbImage {
        var px = [UInt8](repeating: 0, count: w * h * 3)
        for y in 0..<h { for x in 0..<w { let o = (y * w + x) * 3
            px[o] = UInt8(max(0, min(255, x * 255 / w + Int.random(in: -3...3))))
            px[o + 1] = UInt8(max(0, min(255, y * 255 / h + Int.random(in: -3...3))))
            px[o + 2] = UInt8(128 + Int.random(in: -4...4)) } }
        return StegoV1.RgbImage(width: w, height: h, rgb: px)
    }

    private func hide(_ cover: StegoV1.RgbImage, _ p: StegoV1.Payload, to: Curve25519.KeyAgreement.PrivateKey? = nil) throws -> StegoV1.RgbImage {
        try StegoV1.hide(cover: cover, payload: p, senderPriv: alice.rawRepresentation, senderPub: alice.publicKey.rawRepresentation,
                         recipientPub: (to ?? bob).publicKey.rawRepresentation)
    }
    private func reveal(_ img: StegoV1.RgbImage, as me: Curve25519.KeyAgreement.PrivateKey, contacts: [Curve25519.KeyAgreement.PrivateKey]) -> StegoV1.RevealResult {
        StegoV1.reveal(image: img, myPriv: me.rawRepresentation, myPub: me.publicKey.rawRepresentation,
                       contacts: contacts.map { $0.publicKey.rawRepresentation })
    }

    // MARK: Cross-platform vectors

    func testVectorsAndBothPlatformsPngsReveal() throws {
        let v = try JSONSerialization.jsonObject(with: Data(contentsOf: fixtures.appendingPathComponent("vectors.json"))) as! [String: Any]
        let sPriv = sha("OSHI-STEGO-v1 vector sender"), rPriv = sha("OSHI-STEGO-v1 vector recipient"), ePriv = sha("OSHI-STEGO-v1 vector ephemeral")
        let sPub = try StegoV1.publicKey(sPriv), rPub = try StegoV1.publicKey(rPriv), ePub = try StegoV1.publicKey(ePriv)
        let ssStatic = try StegoV1.dh(sPriv, rPub), ssEph = try StegoV1.dh(ePriv, rPub)
        let keys = StegoV1.sealKeys(ssEph: ssEph, ssStatic: ssStatic, e: ePub, s: sPub, r: rPub)
        XCTAssertEqual(hex(sPub), v["sender_pub"] as? String)
        XCTAssertEqual(hex(rPub), v["recipient_pub"] as? String)
        XCTAssertEqual(hex(ePub), v["ephemeral_pub"] as? String)
        XCTAssertEqual(hex(ssStatic), v["ss_static"] as? String)
        XCTAssertEqual(hex(StegoV1.orderKey(ssStatic: ssStatic)), v["k_order"] as? String)
        XCTAssertEqual(hex(keys.header), v["k_header"] as? String)
        XCTAssertEqual(hex(keys.body), v["k_body"] as? String)
        let W = v["width"] as! Int, H = v["height"] as! Int
        let order = StegoV1.SlotOrder(orderKey: StegoV1.orderKey(ssStatic: ssStatic), n: W * H * 3)
        XCTAssertEqual((0..<32).map { _ in order.next() }, v["first_slots"] as? [Int])
        let payload = StegoV1.Payload(text: v["payload_text"] as! String, createdAtMs: (v["payload_created_at_ms"] as! NSNumber).int64Value,
            file: StegoV1.Attachment(name: v["payload_file_name"] as! String, mime: v["payload_file_mime"] as! String, data: unhex(v["payload_file_hex"] as! String)))
        XCTAssertEqual(hex(try StegoV1.encodePayload(payload)), v["body_plain_hex"] as? String)
        var px = [UInt8](repeating: 0, count: W * H * 3)
        for y in 0..<H { for x in 0..<W { let o = (y * W + x) * 3
            px[o] = UInt8((x * 7 + y * 13) % 256); px[o + 1] = UInt8((x * 3 + y * 5 + 50) % 256); px[o + 2] = UInt8((x * 11 + y * 2 + 100) % 256) } }
        let out = try StegoV1.hide(cover: StegoV1.RgbImage(width: W, height: H, rgb: px), payload: payload, senderPriv: sPriv, senderPub: sPub,
                                   recipientPub: rPub, ephemeralPriv: ePriv, random: StegoV1.testByteSource())
        XCTAssertEqual(hex(Data(SHA256.hash(data: Data(out.rgb)))), v["embedded_rgb_sha256"] as? String)
        for name in ["android_v1.png", "ios_v1.png"] {
            let img = try StegoPng.decode(Data(contentsOf: fixtures.appendingPathComponent(name)))
            XCTAssertEqual(hex(Data(SHA256.hash(data: Data(img.rgb)))), v["embedded_rgb_sha256"] as? String, name)
            guard case .found(let sender, let p) = StegoV1.reveal(image: img, myPriv: rPriv, myPub: rPub,
                    contacts: [Curve25519.KeyAgreement.PrivateKey().publicKey.rawRepresentation, sPub]) else { return XCTFail(name) }
            XCTAssertEqual(sender, sPub); XCTAssertEqual(p, payload, name)
        }
    }

    /// Production path (CSPRNG fill, fresh ephemeral) from the OTHER platform:
    /// android_prod_v1.png was made by Android `StegoV1VectorTest.productionPathPngsCrossPlatforms`.
    func testAndroidProductionPngReveals() throws {
        let rPriv = sha("OSHI-STEGO-v1 vector recipient"), sPriv = sha("OSHI-STEGO-v1 vector sender")
        let img = try StegoPng.decode(Data(contentsOf: fixtures.appendingPathComponent("android_prod_v1.png")))
        guard case .found(_, let p) = StegoV1.reveal(image: img, myPriv: rPriv, myPub: try StegoV1.publicKey(rPriv),
                                                     contacts: [try StegoV1.publicKey(sPriv)]) else { return XCTFail() }
        XCTAssertTrue(p.text.hasPrefix("Android production path"))
    }

    // MARK: Behaviour

    func testRoundTripThroughPngWithFile() throws {
        let file = StegoV1.Attachment(name: "plan.pdf", mime: "application/pdf", data: Data((0..<3000).map { UInt8(truncatingIfNeeded: $0 * 31) }))
        let p = StegoV1.Payload(text: "Rendez-vous à 18h — porte B 🚪", createdAtMs: 1_763_460_000_000, file: file)
        let png = StegoPng.encode(try hide(noisyCover(), p))
        guard case .found(let s, let got) = reveal(try StegoPng.decode(png), as: bob, contacts: [eve, alice]) else { return XCTFail() }
        XCTAssertEqual(s, alice.publicKey.rawRepresentation); XCTAssertEqual(got, p)
    }

    func testWrongRecipientAndUnknownSenderSeeOnlyAPhoto() throws {
        let img = try hide(noisyCover(), StegoV1.Payload(text: "secret", createdAtMs: 1, file: nil))
        XCTAssertEqual(reveal(img, as: eve, contacts: [alice, bob]), .notForYou)
        XCTAssertEqual(reveal(img, as: bob, contacts: [eve]), .notForYou)
        XCTAssertEqual(reveal(noisyCover(), as: bob, contacts: [alice]), .notForYou)
    }

    func testSingleBodyBitFlipIsDamagedNeverWrongText() throws {
        let img = try hide(noisyCover(), StegoV1.Payload(text: String(repeating: "y", count: 500), createdAtMs: 1, file: nil))
        let order = StegoV1.SlotOrder(orderKey: StegoV1.orderKey(ssStatic: try StegoV1.dh(bob.rawRepresentation, alice.publicKey.rawRepresentation)), n: img.slots)
        for _ in 0..<(StegoV1.prefix * 8 + 100) { _ = order.next() }
        var copy = img; let slot = order.next(); copy.rgb[slot] ^= 1
        XCTAssertEqual(reveal(copy, as: bob, contacts: [alice]), .damaged(sender: alice.publicKey.rawRepresentation))
    }

    func testCapacityEnforced() throws {
        let small = noisyCover(40, 30)
        XCTAssertEqual(StegoV1.capacityBytes(slots: small.slots), 376)
        XCTAssertThrowsError(try hide(small, StegoV1.Payload(text: String(repeating: "z", count: 400), createdAtMs: 1, file: nil)))
        let exact = StegoV1.Payload(text: String(repeating: "z", count: 376 - 14), createdAtMs: 1, file: nil)
        guard case .found(_, let p) = reveal(try hide(small, exact), as: bob, contacts: [alice]) else { return XCTFail() }
        XCTAssertEqual(p, exact)
    }

    func testRandomFillHidesPayloadSize() throws {
        let cover = noisyCover(200, 150)
        let tiny = try hide(cover, StegoV1.Payload(text: "hi", createdAtMs: 1, file: nil))
        let big = try hide(cover, StegoV1.Payload(text: String(repeating: "w", count: StegoV1.capacityBytes(slots: cover.slots) - 14), createdAtMs: 1, file: nil))
        for img in [tiny, big] {
            let ones = Double(img.rgb.filter { $0 & 1 == 1 }.count) / Double(img.slots)
            let changed = Double(zip(img.rgb, cover.rgb).filter { $0 != $1 }.count) / Double(img.slots)
            XCTAssertEqual(ones, 0.5, accuracy: 0.01); XCTAssertEqual(changed, 0.5, accuracy: 0.01)
            XCTAssertTrue(zip(img.rgb, cover.rgb).allSatisfy { abs(Int($0) - Int($1)) <= 1 })
        }
    }

    /// A REAL JPEG round trip through ImageIO (quality 1.0 and 0.9): clean failure.
    func testRecompressedAsJpegFailsCleanly() throws {
        let img = try hide(noisyCover(), StegoV1.Payload(text: "survive?", createdAtMs: 1, file: nil))
        let cg = try XCTUnwrap(cgImage(img))
        for q in [1.0, 0.9] {
            let data = NSMutableData()
            let dst = try XCTUnwrap(CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil))
            CGImageDestinationAddImage(dst, cg, [kCGImageDestinationLossyCompressionQuality: q] as CFDictionary)
            XCTAssertTrue(CGImageDestinationFinalize(dst))
            let src = try XCTUnwrap(CGImageSourceCreateWithData(data, nil))
            let back = try XCTUnwrap(CGImageSourceCreateImageAtIndex(src, 0, nil))
            let rgb = try XCTUnwrap(PhotoDisguise.rgb(from: back, maxSide: 4096))
            XCTAssertEqual(reveal(rgb, as: bob, contacts: [alice]), .notForYou, "quality \(q)")
        }
    }

    private func cgImage(_ img: StegoV1.RgbImage) -> CGImage? { PhotoDisguise.uiImage(img)?.cgImage }

    func testOutputHasNoAncillaryChunksAndImageIOReadsSameSamples() throws {
        let cover = noisyCover(37, 23)
        let png = StegoPng.encode(cover)
        var types: [String] = []; var off = 8; let b = [UInt8](png)
        while off < b.count {
            let len = (Int(b[off]) << 24) | (Int(b[off + 1]) << 16) | (Int(b[off + 2]) << 8) | Int(b[off + 3])
            types.append(String(bytes: b[(off + 4)..<(off + 8)], encoding: .ascii)!); off += 12 + len
        }
        XCTAssertEqual(Set(types), ["IHDR", "IDAT", "IEND"])
        // ImageIO decodes our PNG to the same samples (no colour management on an untagged PNG).
        let src = try XCTUnwrap(CGImageSourceCreateWithData(png as CFData, nil))
        let cg = try XCTUnwrap(CGImageSourceCreateImageAtIndex(src, 0, nil))
        let viaImageIO = try XCTUnwrap(PhotoDisguise.rgb(from: cg, maxSide: 4096))
        XCTAssertEqual(viaImageIO.rgb.count, cover.rgb.count)
        XCTAssertEqual(try StegoPng.decode(png).rgb, cover.rgb)
    }

    // MARK: Audit finding 3 — cover pull jitter

    func testCoverPullGateJittersAndThrottlesTheOneSecondTimer() {
        var gate = CoverPullGate()
        var admitted: [Double] = []
        for t in 0..<3600 { if gate.admit(now: Date(timeIntervalSince1970: Double(t))) { admitted.append(Double(t)) } }
        let gaps = zip(admitted.dropFirst(), admitted).map { $0 - $1 }
        XCTAssertGreaterThan(gaps.count, 400); XCTAssertLessThan(gaps.count, 1200)   // ~one per 5–6 s, not 3600
        XCTAssertTrue(gaps.allSatisfy { $0 >= 3 && $0 <= 8 })
        XCTAssertGreaterThan(Set(gaps).count, 3, "gaps must not be a single constant period")
    }

    // MARK: Audit finding 5 — Accept-Encoding identity on cover requests

    final class CaptureProtocol: URLProtocol {
        nonisolated(unsafe) static var seen: [URLRequest] = []
        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() {
            Self.seen.append(request)
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 503, httpVersion: nil, headerFields: nil)!,
                                cacheStoragePolicy: .notAllowed)
            client?.urlProtocolDidFinishLoading(self)
        }
        override func stopLoading() {}
    }

    func testCoverRequestsAskForIdentityEncoding() async {
        let cfg = URLSessionConfiguration.ephemeral; cfg.protocolClasses = [CaptureProtocol.self]
        CaptureProtocol.seen = []
        var req = URLRequest(url: URL(string: "https://relay.invalid/v2/messages")!)
        req.httpMethod = "POST"; req.httpBody = Data("{}".utf8)
        _ = try? await CoverTransport.data(for: req, session: URLSession(configuration: cfg), selectedMode: "event")
        let outer = CaptureProtocol.seen.first
        XCTAssertNotNil(outer)
        XCTAssertTrue(CoverTransport.coverPaths.contains(outer?.url?.path ?? ""))
        XCTAssertEqual(outer?.value(forHTTPHeaderField: "Accept-Encoding"), "identity")
    }

    // MARK: Screenshots (only when STEGO_SHOTS_DIR is set; skipped in normal runs)

    @MainActor
    private func snapshot<V: View>(_ view: V, _ name: String, dark: Bool = true) async throws {
        guard let dir = ProcessInfo.processInfo.environment["STEGO_SHOTS_DIR"] ?? (try? String(contentsOfFile: "/tmp/stego_shots_dir").trimmingCharacters(in: .whitespacesAndNewlines))
        else { throw XCTSkip("STEGO_SHOTS_DIR not set") }
        let host = UIHostingController(rootView: view)
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { throw XCTSkip("no scene") }
        let window = UIWindow(windowScene: scene)
        window.windowLevel = .alert + 1
        window.overrideUserInterfaceStyle = dark ? .dark : .light
        window.rootViewController = host; window.makeKeyAndVisible()
        try await Task.sleep(nanoseconds: 2_500_000_000)
        let img = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
        try img.pngData()!.write(to: URL(fileURLWithPath: dir).appendingPathComponent(name + ".png"))
        window.isHidden = true
    }

    @MainActor
    func testZZScreenshots() async throws {
        try await snapshot(NavigationStack { PrivacyDisguiseSettingsView() }, "stego-ios-privacy-disguise")
        try await snapshot(DisguiseAsPhotoSheet(contactPublicKey: Curve25519.KeyAgreement.PrivateKey().publicKey.rawRepresentation.base64EncodedString(),
                                                contactName: "Camille", text: "Meet at 18h, gate B."), "stego-ios-disguise-sheet")
        let found = PhotoDisguise.Outcome.found(.init(senderPublicKey: "bbgNo8Qy…", senderName: "Camille",
            payload: StegoV1.Payload(text: "Made by the Android app for this iPhone. Plan B: library, 10am.", createdAtMs: 1_763_460_000_000, file: nil)))
        try await snapshot(NavigationStack { List { Section { RevealOutcomeView(outcome: found) } header: { Text("disguise.reveal.title".localized) } } }, "stego-ios-reveal")
        try await snapshot(NavigationStack { List { Section { RevealOutcomeView(outcome: .notForYou) } } }, "stego-ios-reveal-not-for-you")
    }
}

// MARK: - Damaged / hostile files (__STEGO_DAMAGED_PNG_2026_11_18__)
// BEGIN netem-stego-tests
extension StegoV1Tests {
    private func pngChunk(_ type: String, _ data: [UInt8]) -> [UInt8] {
        let t = Array(type.utf8)
        func be(_ v: UInt32) -> [UInt8] { [UInt8(v >> 24), UInt8((v >> 16) & 255), UInt8((v >> 8) & 255), UInt8(v & 255)] }
        return be(UInt32(data.count)) + t + data + be(StegoPng.crc32(t, data))
    }

    private func idatRange(_ png: [UInt8]) -> (offset: Int, length: Int) {
        var i = 8
        while i + 12 <= png.count {
            let n = (Int(png[i]) << 24) | (Int(png[i + 1]) << 16) | (Int(png[i + 2]) << 8) | Int(png[i + 3])
            if png[(i + 4)..<(i + 8)].elementsEqual(Array("IDAT".utf8)) { return (i, n) }
            i += 12 + n
        }
        return (0, 0)
    }

    /// A damaged copy either fails to decode (a thrown error, never a trap) or
    /// reveals nothing / "damaged" / the exact message — never other text.
    private func assertHarmless(_ bytes: [UInt8], _ name: String, expected: String) {
        guard let img = try? StegoPng.decode(Data(bytes)) else { return }
        if case .found(_, let p) = reveal(img, as: bob, contacts: [alice]) { XCTAssertEqual(p.text, expected, name) }
    }

    func testHugeIhdrDimensionsAreRefusedNotTrapped() throws {
        let png = [UInt8](StegoPng.encode(noisyCover(32, 24)))
        let rest = Array(png[(8 + 25)...])
        let huge = Array(png[0..<8]) + pngChunk("IHDR", [255, 255, 255, 255, 255, 255, 255, 255, 8, 2, 0, 0, 0]) + rest
        XCTAssertThrowsError(try StegoPng.decode(Data(huge)))
        let wide = Array(png[0..<8]) + pngChunk("IHDR", [0, 255, 255, 255, 0, 0, 0, 1, 8, 2, 0, 0, 0]) + rest
        XCTAssertThrowsError(try StegoPng.decode(Data(wide)))
        XCTAssertEqual(StegoPng.declaredSize(Data(huge))?.width, 0xFFFF_FFFF)
        XCTAssertEqual(StegoPng.declaredSize(Data(png))?.width, 32)
        XCTAssertNil(StegoPng.declaredSize(Data([1, 2, 3])))
    }

    func testDamagedPngNeverCrashesNorLies() throws {
        let png = [UInt8](StegoPng.encode(try hide(noisyCover(), StegoV1.Payload(text: "x", createdAtMs: 1, file: nil))))
        let (o, n) = idatRange(png)
        var variants: [String: [UInt8]] = [:]
        var b = png; b[o + 8 + n / 2] ^= 0x10; variants["idat mid flip"] = b
        b = png; b[o + 8 + 3] ^= 0xFF; variants["idat start flip"] = b
        b = png; b[o + 8 + n - 4] ^= 0x01; variants["adler flip"] = b
        variants["5-byte IHDR"] = Array(png[0..<8]) + pngChunk("IHDR", [0, 0, 1, 0, 0]) + Array(png[(8 + 25)...])
        variants["empty IDAT"] = Array(png[0..<(8 + 25)]) + pngChunk("IDAT", []) + pngChunk("IEND", [])
        for cut in [9, 20, 33, 40, png.count / 4, png.count / 2, png.count - 13] { variants["cut \(cut)"] = Array(png[0..<cut]) }
        for (name, bytes) in variants { assertHarmless(bytes, name, expected: "x") }
    }

    func testRandomByteDamageNeverCrashesNorLies() throws {
        let png = [UInt8](StegoPng.encode(try hide(noisyCover(64, 48), StegoV1.Payload(text: "bonjour", createdAtMs: 2, file: nil))))
        var g = SystemRandomNumberGenerator()
        for n in 0..<400 {
            var b = png
            for _ in 0..<Int.random(in: 1...4, using: &g) { b[Int.random(in: 8..<b.count, using: &g)] = UInt8.random(in: 0...255, using: &g) }
            assertHarmless(b, "mutation \(n)", expected: "bonjour")
        }
    }
}
// END netem-stego-tests

// MARK: - Cover transport on bad networks (__COVER_RETRY_2026_11_18__, __COVER_REDIRECT_2026_11_18__)
// BEGIN netem-cover-tests
extension StegoV1Tests {
    func testCoverRetryScheduleBacksOffLikeAndroidAndGivesUpAfterAnHour() {
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        var s = CoverRetrySchedule(firstFailure: t0)
        XCTAssertEqual(s.decision(now: t0.addingTimeInterval(9)), .wait)
        var at = t0, gaps: [TimeInterval] = []
        while true {
            var probe = at
            while s.decision(now: probe) == .wait { probe = probe.addingTimeInterval(1) }
            if s.decision(now: probe) == .giveUp { break }
            gaps.append(probe.timeIntervalSince(at)); s.recordAttempt(now: probe); at = probe
        }
        XCTAssertEqual(Array(gaps.prefix(7)), [10, 30, 60, 120, 300, 600, 600])   // Android CoverOutbox.BACKOFF_MS
        XCTAssertLessThanOrEqual(at.timeIntervalSince(t0), CoverRetrySchedule.giveUpAfter)
        XCTAssertEqual(s.decision(now: t0.addingTimeInterval(3600)), .giveUp)
    }

    func testCoverRetryScheduleKickBringsTheRetryForwardButNeverStorms() {
        let t0 = Date(timeIntervalSince1970: 2_000_000)
        var s = CoverRetrySchedule(firstFailure: t0)
        for k in 0..<4 { s.recordAttempt(now: t0.addingTimeInterval(Double(k))) }   // now on a 300 s backoff
        let last = t0.addingTimeInterval(3)
        XCTAssertEqual(s.decision(now: last.addingTimeInterval(4), kicked: true), .wait)     // < minSpacing
        XCTAssertEqual(s.decision(now: last.addingTimeInterval(5), kicked: true), .attempt)
        XCTAssertEqual(s.decision(now: last.addingTimeInterval(60), kicked: false), .wait)
    }

    func testCoverReplyRelabelledByAMiddleboxStillDecodesButAPortalPageDoesNot() async throws {
        let inner: [String: Any] = ["v": 1, "status": 200, "headers": ["content-type": "application/json"],
                                    "body": Data(#"{"accepted":["m1"]}"#.utf8).base64EncodedString()]
        let cfg = URLSessionConfiguration.ephemeral; cfg.protocolClasses = [ReplyProtocol.self]
        let session = URLSession(configuration: cfg)
        var req = URLRequest(url: URL(string: "https://relay.invalid/v2/messages")!)
        req.httpMethod = "POST"; req.httpBody = Data("{}".utf8)
        ReplyProtocol.reply = (try CoverTransport.encode(inner, mode: "event").body, "text/plain; charset=utf-8")
        let (data, response) = try await CoverTransport.data(for: req, session: session, selectedMode: "event")
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        XCTAssertEqual(String(data: data, encoding: .utf8), #"{"accepted":["m1"]}"#)
        ReplyProtocol.reply = (Data("<html><form>Accept terms</form></html>".utf8), "text/html")
        do { _ = try await CoverTransport.data(for: req, session: session, selectedMode: "event"); XCTFail("portal page accepted") } catch {}
    }

    func testCoverTimeoutGrowsWithThePaddedBodyAndIsBounded() {
        XCTAssertEqual(CoverTransport.coverTimeout(outerBytes: 4096), 16)
        XCTAssertGreaterThanOrEqual(CoverTransport.coverTimeout(outerBytes: 350_000), 90)
        XCTAssertEqual(CoverTransport.coverTimeout(outerBytes: 6 * 1024 * 1024), 600)
    }

    func testNoV2OrCoverRequestFollowsARedirect() {
        for path in CoverTransport.coverPaths + ["/v2/messages", "/v2/blobs/x/chunk/0", "/v2/keys/abc"] {
            XCTAssertFalse(OshiSPKIPinningDelegate.followsRedirect(fromPath: path), path)
        }
        XCTAssertTrue(OshiSPKIPinningDelegate.followsRedirect(fromPath: "/files/streets/x.db.gz"))
    }
}
/// Answers every request with `reply` (body, Content-Type) — a relay or a middlebox.
final class ReplyProtocol: URLProtocol {
    nonisolated(unsafe) static var reply: (Data, String) = (Data(), "application/json")
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let (body, type) = Self.reply
        let r = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": type])!
        client?.urlProtocol(self, didReceive: r, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body); client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
// END netem-cover-tests

// MARK: - __COVER_WAITING_BUBBLE_2026_11_18__ / __RESEND_KEEPS_ORDER_2026_11_18__ / __COVER_RESIGN_2026_11_18__

/// The three bad-network leftovers of the netem pass (2026-11-18):
///  • a message cover mode is holding reads "Waiting for disguised connection", not "failed";
///  • a message resent after a block keeps the date it was written, and the receiver files
///    it by that date — so a burst resent in retry order still reads in writing order;
///  • an app-level retry of a signed request is signed AGAIN (a fresh timestamp), so a
///    server that refuses replays never sees the same signature twice from us.
@MainActor
final class CoverResendTests: XCTestCase {

    // MARK: waiting bubble

    func testOnlyAFailedQueuedRowInCoverModeIsWaiting() {
        XCTAssertTrue(CoverWaitingState.isWaiting(failed: true, coverOn: true, queued: true, gaveUp: false))
        // In flight: every send is queued before the network (pre-flight enqueue) — "sending", not "waiting".
        XCTAssertFalse(CoverWaitingState.isWaiting(failed: false, coverOn: true, queued: true, gaveUp: false))
        // Cover off: a failure is a failure (tap to retry).
        XCTAssertFalse(CoverWaitingState.isWaiting(failed: true, coverOn: false, queued: true, gaveUp: false))
        // Out of the queue, or given up after an hour: tap to retry.
        XCTAssertFalse(CoverWaitingState.isWaiting(failed: true, coverOn: true, queued: false, gaveUp: false))
        XCTAssertFalse(CoverWaitingState.isWaiting(failed: true, coverOn: true, queued: true, gaveUp: true))
    }

    func testManagerReportsWaitingOnlyWhileCoverHoldsTheMessage() async throws {
        let defaults = UserDefaults.standard
        let previous = defaults.object(forKey: "covertChannelEnabled")
        let previousShared = MessageManager.shared
        let previousIdentity = MessageManager.sharedIdentityManager
        // Same setup as MessageRetryQueueTests: `init` lands the persisted queue off
        // the main actor a moment later; let it land before touching the queue.
        let manager = MessageManager()
        try? await Task.sleep(nanoseconds: 300_000_000)
        let id = "cover-waiting-\(UUID().uuidString)"
        manager.addToRetryQueue(messageId: id, content: "hi", recipientKey: "peer")

        defaults.set(true, forKey: "covertChannelEnabled")
        XCTAssertTrue(manager.isWaitingForCover(id, status: .failed))
        XCTAssertFalse(manager.isWaitingForCover(id, status: .pending))
        XCTAssertFalse(manager.isWaitingForCover(id, status: .sent))
        manager.permanentlyFailedMessageIds.insert(id)
        XCTAssertFalse(manager.isWaitingForCover(id, status: .failed), "given up ⇒ tap to retry")
        manager.permanentlyFailedMessageIds.remove(id)
        defaults.set(false, forKey: "covertChannelEnabled")
        XCTAssertFalse(manager.isWaitingForCover(id, status: .failed))

        manager.removeFromRetryQueue(messageId: id)
        if let previous { defaults.set(previous, forKey: "covertChannelEnabled") }
        else { defaults.removeObject(forKey: "covertChannelEnabled") }
        MessageManager.shared = previousShared
        MessageManager.sharedIdentityManager = previousIdentity
    }

    func testWaitingStringIsLocalizedEverywhere() throws {
        let bundle = Bundle(for: MessageManager.self)
        let locs = bundle.localizations.filter { $0 != "Base" }
        XCTAssertGreaterThanOrEqual(locs.count, 34)
        for loc in locs {
            guard let path = bundle.path(forResource: loc, ofType: "lproj"), let b = Bundle(path: path) else { continue }
            let s = b.localizedString(forKey: "cover.waiting_bubble", value: "∅", table: nil)
            XCTAssertNotEqual(s, "∅", loc)
            XCTAssertFalse(s.lowercased().contains("fail"), loc)
        }
    }

    /// __COVER_MODE_NO_RECURSION_2026_11_18__ Reading `mode` must not post a defaults
    /// change once the App Group mirror is current: CovertChannelReceiver's observer
    /// answers every change by reading `mode` again (stack overflow at launch).
    func testReadingCoverModeTwiceDoesNotPostADefaultsChange() {
        let defaults = UserDefaults.standard
        let previous = defaults.object(forKey: "covertChannelEnabled")
        defer {
            if let previous { defaults.set(previous, forKey: "covertChannelEnabled") }
            else { defaults.removeObject(forKey: "covertChannelEnabled") }
        }
        defaults.set(true, forKey: "covertChannelEnabled")
        XCTAssertEqual(CoverTransport.mode, "event")      // mirrors once
        var posted = 0
        let token = NotificationCenter.default.addObserver(forName: UserDefaults.didChangeNotification,
                                                           object: nil, queue: nil) { _ in posted += 1 }
        defer { NotificationCenter.default.removeObserver(token) }
        for _ in 0..<5 { XCTAssertEqual(CoverTransport.mode, "event") }
        XCTAssertEqual(posted, 0)
    }

    // MARK: resend order

    func testEnvelopeTimestampIsTheDateTheMessageWasWritten() {
        let written = Date(timeIntervalSince1970: 1_763_000_000.123)
        let retriedAt = written.addingTimeInterval(600)   // after a 10-minute block
        XCTAssertEqual(V2EnvelopeTimestamp.ms(original: written, now: retriedAt), 1_763_000_000_123)
        XCTAssertEqual(V2EnvelopeTimestamp.ms(original: nil, now: retriedAt),
                       Int64(retriedAt.timeIntervalSince1970 * 1000))
    }

    func testReceiverFilesResentMessagesByTheirOwnDateNotArrivalOrder() {
        let t0 = Date(timeIntervalSince1970: 1_763_000_000)
        func msg(_ id: String, _ dt: TimeInterval) -> SecureMessage {
            SecureMessage(id: id, senderAddress: "peer", recipientAddress: "me",
                          encryptedContent: EncryptedMessage(ciphertext: "", signature: "", senderPublicKey: "peer",
                                                             timestamp: t0.addingTimeInterval(dt)),
                          timestamp: t0.addingTimeInterval(dt), isRead: false, deliveryStatus: .delivered,
                          senderPublicKey: "peer", recipientPublicKey: "me", plaintextContent: id, deliveryMethod: .ipfs)
        }
        // Written m1 (text), m2 (photo), m3 (text) during a block; the photo's upload finished last,
        // so the receiver's store got them in the order m1, m3, m2.
        let arrived = [msg("m1", 0), msg("m3", 20), msg("m2", 10)]
        XCTAssertEqual(ChatThreadPipeline.sortedByTimestamp(arrived).map(\.id), ["m1", "m2", "m3"])
        let thread = ChatThreadPipeline.threadMessages(from: arrived, peerKey: "peer", myKey: "me")
        XCTAssertEqual(thread.map(\.id), ["m1", "m2", "m3"])
    }

    // MARK: app-level retry re-signs

    func testAnAppLevelRetrySignsTheRequestAgain() async throws {
        // The direct path: this pins the V2 client's retry, not the cover carrier.
        let defaults = UserDefaults.standard
        let previous = defaults.object(forKey: "covertChannelEnabled")
        defaults.set(false, forKey: "covertChannelEnabled")
        defer {
            if let previous { defaults.set(previous, forKey: "covertChannelEnabled") }
            else { defaults.removeObject(forKey: "covertChannelEnabled") }
        }
        ResignProtocol.reset()
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [ResignProtocol.self]
        let client = V2Client(config: .init(baseURL: URL(string: "https://relay.invalid")!, requestTimeout: 10, maxRetryAttempts: 3),
                              crypto: V2EphemeralCrypto(), session: URLSession(configuration: cfg))
        let env = V2.Envelope(msgId: "resign-1", from: "a", to: "b", type: .oneToOne, header: "h", ciphertext: "c", ts: 1)
        let accepted = try await client.sendEnvelope(env)
        XCTAssertEqual(accepted, ["resign-1"])
        let seen = ResignProtocol.signatures()
        XCTAssertEqual(seen.count, 2, "one 503, one retry")
        XCTAssertEqual(Set(seen).count, 2, "the retry must carry a NEW signature (fresh timestamp), never a replay")
    }
}

/// First `/v2/messages` POST → 503, the next → 200 {"accepted":[msgId]}; records each signature.
final class ResignProtocol: URLProtocol {
    nonisolated(unsafe) private static var seen: [String] = []
    private static let lock = NSLock()
    static func reset() { lock.lock(); seen = []; lock.unlock() }
    static func signatures() -> [String] { lock.lock(); defer { lock.unlock() }; return seen }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock()
        Self.seen.append((request.value(forHTTPHeaderField: "x-oshi-signature") ?? "") + "|" + (request.value(forHTTPHeaderField: "x-oshi-timestamp") ?? ""))
        let first = Self.seen.count == 1
        Self.lock.unlock()
        let status = first ? 503 : 200
        let body = first ? Data(#"{"error":"busy"}"#.utf8) : Data(#"{"accepted":["resign-1"]}"#.utf8)
        let r = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: r, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body); client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

// MARK: - __RESTRICTED_NETWORK_HINT_2026_11_18__ RN-HINT v1 vectors (docs/RESTRICTED_NETWORK_HINT.md §6)

final class RestrictedNetworkHintTests: XCTestCase {
    private func utf8(_ hex: String) -> String {
        let bytes = hex.split(separator: " ").map { UInt8($0, radix: 16)! }
        return String(decoding: bytes, as: UTF8.self)
    }

    func testMarkerBytes() {
        XCTAssertEqual(Array(RestrictedNetworkHint.marker.utf8), [0xE2, 0x81, 0xA3, 0xE2, 0x81, 0xA4, 0xE2, 0x81, 0xA3])
    }

    func testSpecVectors() {
        var r = RestrictedNetworkHint.ingest(utf8("48 65 6c 6c 6f"))
        XCTAssertEqual(r.plaintext, "Hello"); XCTAssertFalse(r.flagged)
        r = RestrictedNetworkHint.ingest(utf8("48 65 6c 6c 6f e2 81 a3 e2 81 a4 e2 81 a3"))
        XCTAssertEqual(r.plaintext, "Hello"); XCTAssertTrue(r.flagged)
        let middle = utf8("48 65 e2 81 a3 e2 81 a4 e2 81 a3 6c 6c 6f")
        r = RestrictedNetworkHint.ingest(middle)
        XCTAssertEqual(r.plaintext, middle); XCTAssertFalse(r.flagged)
        XCTAssertEqual(middle.unicodeScalars.count, 8)
        let two = utf8("48 69 e2 81 a3 e2 81 a4")
        r = RestrictedNetworkHint.ingest(two)
        XCTAssertEqual(r.plaintext, two); XCTAssertFalse(r.flagged)
    }

    func testReplyVectorAndroidEscapesAndRawCharacters() throws {
        let android = #"💬REPLY💬{"content":"On my way⁣⁤⁣","replyTo":{"originalMessageId":"m-1","originalSenderKey":"k","originalText":"Where are you?","originalTimestamp":785152800}}"#
        let r = RestrictedNetworkHint.ingest(android)
        XCTAssertTrue(r.flagged)
        let parsed = try XCTUnwrap(MessageWithReply.fromMessageString(r.plaintext))
        XCTAssertEqual(parsed.content, "On my way")
        XCTAssertEqual(parsed.replyTo?.originalMessageId, "m-1")
        XCTAssertFalse(r.plaintext.unicodeScalars.contains { $0.value == 0x2063 })
        // iOS JSONEncoder spelling (raw characters) decodes the same.
        let reply = try XCTUnwrap(parsed.replyTo)
        let ios = RestrictedNetworkHint.outgoing(text: "On my way", replyTo: reply, coverOn: true, allowed: true)
        XCTAssertTrue(ios.hasPrefix(MessageWithReply.replyPrefix))
        XCTAssertTrue(ios.hasSuffix("}"), "never after the closing brace")
        let back = RestrictedNetworkHint.ingest(ios)
        XCTAssertTrue(back.flagged)
        XCTAssertEqual(MessageWithReply.fromMessageString(back.plaintext)?.content, "On my way")
    }

    func testSenderRoundTripOnlyWithCoverAndPermission() {
        XCTAssertEqual(RestrictedNetworkHint.outgoing(text: "Hi", replyTo: nil, coverOn: false, allowed: true), "Hi")
        XCTAssertEqual(RestrictedNetworkHint.outgoing(text: "Hi", replyTo: nil, coverOn: true, allowed: false), "Hi")
        let tagged = RestrictedNetworkHint.outgoing(text: "Hi", replyTo: nil, coverOn: true, allowed: true)
        XCTAssertEqual(Array(tagged.utf8), [0x48, 0x69, 0xE2, 0x81, 0xA3, 0xE2, 0x81, 0xA4, 0xE2, 0x81, 0xA3])
        // Pasted text that already ends with the marker: no double marker.
        XCTAssertEqual(RestrictedNetworkHint.tag(tagged), tagged)
        XCTAssertEqual(RestrictedNetworkHint.ingest(tagged).plaintext, "Hi")
        // Default ON.
        let d = UserDefaults(suiteName: "rn-hint-test-\(UUID().uuidString)")!
        XCTAssertTrue(RestrictedNetworkHint.senderAllows(d))
        d.set(false, forKey: RestrictedNetworkHint.settingKey)
        XCTAssertFalse(RestrictedNetworkHint.senderAllows(d))
    }

    func testFlagStoreRemembersReceivedIds() {
        let d = UserDefaults(suiteName: "rn-hint-store-\(UUID().uuidString)")!
        RestrictedNetworkHintStore.resetCacheForTests()
        XCTAssertFalse(RestrictedNetworkHintStore.contains("m1", defaults: d))
        RestrictedNetworkHintStore.insert("m1", defaults: d)
        XCTAssertTrue(RestrictedNetworkHintStore.contains("m1", defaults: d))
        RestrictedNetworkHintStore.resetCacheForTests()
        XCTAssertTrue(RestrictedNetworkHintStore.contains("m1", defaults: d))
        RestrictedNetworkHintStore.resetCacheForTests()
    }

    /// A revealed "Disguise as photo" message is flagged locally (no marker):
    /// its text must stay exactly what the sender hid, and a marker-free text is
    /// never "flagged" by the wire codec.
    func testRevealedPhotoFlagIsLocalNotTheMarker() {
        XCTAssertFalse(RestrictedNetworkHint.ingest("hidden text").flagged)
    }

    func testEveryLocaleCarriesTheHintStrings() {
        let bundle = Bundle(for: MessageManager.self)
        for loc in bundle.localizations where loc != "Base" {
            guard let p = bundle.path(forResource: loc, ofType: "lproj"), let b = Bundle(path: p) else { continue }
            for k in ["rn_hint.chip", "rn_hint.explain", "rn_hint.setting_title", "rn_hint.setting_caption"] {
                XCTAssertNotEqual(b.localizedString(forKey: k, value: "∅", table: nil), "∅", "\(loc) \(k)")
            }
        }
    }
}
