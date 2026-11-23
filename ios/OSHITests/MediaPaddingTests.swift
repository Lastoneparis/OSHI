//
//  MediaPaddingTests.swift
//  OSHITests
//
//  __MEDIA_PADDING_2026_11_10__ / __OHTTP_MEDIA_2026_11_10__
//  Media (v2 blob) padding and OHTTP for /v2/blobs*. The Padmé vectors and the two
//  byte-exact vectors come from docs/MEDIA_PADDING_AND_OHTTP_MEDIA_SPEC.md and are
//  asserted byte for byte by Android and desktop too: change them in all three or
//  in none.
//

import XCTest
import CryptoKit
@testable import OSHI

final class MediaPaddingTests: XCTestCase {

    private var suite: UserDefaults!
    private let suiteName = "MediaPaddingTests"
    private var work: URL!

    override func setUp() {
        super.setUp()
        suite = UserDefaults(suiteName: suiteName)
        suite.removePersistentDomain(forName: suiteName)
        V2MediaPaddingPeers.defaults = suite
        work = FileManager.default.temporaryDirectory.appendingPathComponent("mediapad-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
    }

    override func tearDown() {
        suite.removePersistentDomain(forName: suiteName)
        V2MediaPaddingPeers.defaults = .standard
        try? FileManager.default.removeItem(at: work)
        super.tearDown()
    }

    private func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
    private func sha(_ d: Data) -> String { hex(Data(SHA256.hash(data: d))) }
    private let vectorKey = Data((0x00...0x1f).map { UInt8($0) })
    private let vectorNonce = Data((0xa0...0xa7).map { UInt8($0) })
    private let C = 2_097_152

    // MARK: Padmé

    func testPadmeVectors() {
        let vectors: [(Int, Int)] = [
            (0, 4096), (1, 4096), (4096, 4096), (4097, 4352), (10000, 10240),
            (65537, 67584), (100000, 100352), (1000000, 1015808), (1048576, 1048576),
            (2097153, 2162688), (5243003, 5373952), (100000000, 100663296),
            (209715199, 209715200), (209715200, 209715200),
        ]
        for (l, p) in vectors {
            XCTAssertEqual(OSHICryptoV2.mediaPaddedSize(l), p, "L=\(l)")
        }
        // Never shrinks, never exceeds the ceiling for an in-range L, never pads past it.
        for l in stride(from: 0, through: 209_715_200, by: 999_983) {
            let p = OSHICryptoV2.mediaPaddedSize(l)
            XCTAssertGreaterThanOrEqual(p, l)
            XCTAssertLessThanOrEqual(p, 209_715_200)
        }
        XCTAssertEqual(OSHICryptoV2.mediaPaddedSize(209_715_201), 209_715_201, "over the cap: unchanged")
    }

    // MARK: Byte-exact cross-platform vectors

    func testByteExactVector1SmallText() throws {
        let data = Data("héllo 👋".utf8)
        XCTAssertEqual(data.count, 11)
        let meta = OSHICryptoV2.FileMeta(filename: "a.txt", mime: "text/plain")
        let enc = try OSHICryptoV2.encryptFile(bytes: data, chunkSize: C, meta: meta,
                                               reuseFileKey: vectorKey, reuseFileNonce: vectorNonce, padMedia: true)
        XCTAssertEqual(String(decoding: enc.infoJSON, as: UTF8.self),
                       #"{"filename":"a.txt","mime":"text/plain","size":11,"chunkCount":1,"chunkSize":2097152,"paddedSize":4096}"#)
        XCTAssertEqual(enc.chunks.count, 1)
        XCTAssertEqual(enc.chunks[0].count, 4112)
        XCTAssertEqual(sha(enc.chunks[0]), "467e96605b69cdd2047136dd6640282f4f7e54efc66c61bd1a95e6f4b244502e")
        XCTAssertEqual(enc.manifest.count, 119)
        XCTAssertEqual(sha(enc.manifest), "f66c94d1f2742ffc6742d4b4d97df162d7f89f4dea408c042d729be1cd3bed5d")
        print("MEDIA_PADDING_VECTOR1 chunk0=\(sha(enc.chunks[0])) manifest=\(sha(enc.manifest))")

        // The streaming encryptor yields the same bytes.
        let input = work.appendingPathComponent("v1.bin")
        try data.write(to: input)
        let s = try OSHICryptoV2.encryptFileStreaming(inputURL: input, outputDir: work.appendingPathComponent("v1c"),
                                                      chunkSize: C, meta: meta,
                                                      reuseFileKey: vectorKey, reuseFileNonce: vectorNonce, padMedia: true)
        XCTAssertEqual(s.chunkCount, 1)
        XCTAssertEqual(try Data(contentsOf: s.chunkURLs[0]), enc.chunks[0])
        XCTAssertEqual(s.manifest, enc.manifest)
        XCTAssertEqual(s.infoJSON, enc.infoJSON)
        XCTAssertEqual(s.size, 11)

        let (back, _) = try OSHICryptoV2.decryptFile(fileKey: vectorKey, fileNonce: vectorNonce,
                                                     chunks: enc.chunks, manifest: enc.manifest)
        XCTAssertEqual(back, data)
    }

    func testByteExactVector2TwoChunks() throws {
        let data = Data(repeating: UInt8(ascii: "a"), count: 3_145_733)
        let enc = try OSHICryptoV2.encryptFile(bytes: data, chunkSize: C, meta: OSHICryptoV2.FileMeta(),
                                               reuseFileKey: vectorKey, reuseFileNonce: vectorNonce, padMedia: true)
        XCTAssertEqual(OSHICryptoV2.mediaPaddedSize(data.count), 3_211_264)
        XCTAssertEqual(enc.chunks.map(\.count), [2_097_168, 1_114_128])
        let joined = enc.chunks[0] + enc.chunks[1]
        XCTAssertEqual(sha(joined), "a04c905db45c6098e7175d2ceea2b5f3793481f39900fcd011d7b9fe7f099df6")
        print("MEDIA_PADDING_VECTOR2 chunks=\(sha(joined))")

        let input = work.appendingPathComponent("v2.bin")
        try data.write(to: input)
        let s = try OSHICryptoV2.encryptFileStreaming(inputURL: input, outputDir: work.appendingPathComponent("v2c"),
                                                      chunkSize: C, reuseFileKey: vectorKey, reuseFileNonce: vectorNonce,
                                                      padMedia: true)
        XCTAssertEqual(try s.chunkURLs.map { try Data(contentsOf: $0) }, enc.chunks)
        XCTAssertEqual(s.manifest, enc.manifest)
    }

    // MARK: Round trips

    func testPaddedRoundTripsInMemoryAndStreamingCrossDecrypt() throws {
        let chunk = 1024
        for n in [0, 1, 1023, 1024, 1025, 4095, 4096, 4097, 5000, 9000, 70_000] {
            let data = Data((0..<n).map { UInt8(truncatingIfNeeded: $0 &* 31 &+ 7) })
            let p = OSHICryptoV2.mediaPaddedSize(n)
            let mem = try OSHICryptoV2.encryptFile(bytes: data, chunkSize: chunk, padMedia: true)
            XCTAssertEqual(mem.chunks.count, (p + chunk - 1) / chunk, "n=\(n)")
            XCTAssertEqual(mem.chunks.count, OSHICryptoV2.expectedChunkCount(plaintextSize: n, chunkSize: chunk, padded: true))
            XCTAssertEqual(mem.chunks.reduce(0) { $0 + $1.count - 16 }, p, "server sees P, not L")

            // in-memory → in-memory
            XCTAssertEqual(try OSHICryptoV2.decryptFile(fileKey: mem.fileKey, fileNonce: mem.fileNonce,
                                                        chunks: mem.chunks, manifest: mem.manifest).bytes, data)
            // in-memory → streaming
            let dir = work.appendingPathComponent("rt-\(n)")
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            var urls: [URL] = []
            for (i, c) in mem.chunks.enumerated() {
                let u = dir.appendingPathComponent("\(i).ct"); try c.write(to: u); urls.append(u)
            }
            let out = dir.appendingPathComponent("out.bin")
            let info = try OSHICryptoV2.decryptFileStreaming(fileKey: mem.fileKey, fileNonce: mem.fileNonce,
                                                             chunkURLs: urls, manifest: mem.manifest, outputURL: out)
            XCTAssertEqual(try Data(contentsOf: out), data, "n=\(n)")
            XCTAssertEqual((info["paddedSize"] as? NSNumber)?.intValue, p)

            // streaming → in-memory, same key/nonce ⇒ identical bytes
            let input = dir.appendingPathComponent("in.bin")
            try data.write(to: input)
            let st = try OSHICryptoV2.encryptFileStreaming(inputURL: input, outputDir: dir.appendingPathComponent("s"),
                                                           chunkSize: chunk, reuseFileKey: mem.fileKey,
                                                           reuseFileNonce: mem.fileNonce, padMedia: true)
            XCTAssertEqual(try st.chunkURLs.map { try Data(contentsOf: $0) }, mem.chunks, "n=\(n)")
            XCTAssertEqual(st.manifest, mem.manifest)
            XCTAssertEqual(try OSHICryptoV2.decryptFile(fileKey: st.fileKey, fileNonce: st.fileNonce,
                                                        chunks: try st.chunkURLs.map { try Data(contentsOf: $0) },
                                                        manifest: st.manifest).bytes, data)
        }
    }

    /// An unpadded file is byte-identical to the pre-padding format, and still decrypts.
    func testUnpaddedIsTheOldFormatAndStillDecrypts() throws {
        let data = Data("héllo 👋".utf8)
        let meta = OSHICryptoV2.FileMeta(filename: "a.txt", mime: "text/plain")
        let enc = try OSHICryptoV2.encryptFile(bytes: data, chunkSize: C, meta: meta,
                                               reuseFileKey: vectorKey, reuseFileNonce: vectorNonce)
        XCTAssertEqual(String(decoding: enc.infoJSON, as: UTF8.self),
                       #"{"filename":"a.txt","mime":"text/plain","size":11,"chunkCount":1,"chunkSize":2097152}"#)
        XCTAssertEqual(enc.chunks[0].count, 11 + 16)
        // An "old" manifest sealed by hand (exactly what pre-padding builds produce).
        let oldManifest = try OSHICryptoV2.aesGcmSeal(
            key: vectorKey, nonce: OSHICryptoV2.chunkNonce(vectorNonce, OSHICryptoV2.manifestCounter),
            plaintext: Data(#"{"filename":"a.txt","mime":"text/plain","size":11,"chunkCount":1,"chunkSize":2097152}"#.utf8),
            aad: Data())
        XCTAssertEqual(oldManifest, enc.manifest)
        XCTAssertEqual(try OSHICryptoV2.decryptFile(fileKey: vectorKey, fileNonce: vectorNonce,
                                                    chunks: enc.chunks, manifest: oldManifest).bytes, data)
        let u = work.appendingPathComponent("old.ct"); try enc.chunks[0].write(to: u)
        let out = work.appendingPathComponent("old.out")
        try OSHICryptoV2.decryptFileStreaming(fileKey: vectorKey, fileNonce: vectorNonce,
                                              chunkURLs: [u], manifest: oldManifest, outputURL: out)
        XCTAssertEqual(try Data(contentsOf: out), data)
        XCTAssertFalse(OSHICryptoV2.manifestIsPadded(fileKey: vectorKey, fileNonce: vectorNonce, manifest: oldManifest))
    }

    // MARK: Rejections

    /// Seal `plain` (already P bytes) as chunks + a manifest claiming (size, paddedSize).
    private func forge(_ plain: Data, size: Int, paddedSize: Int, chunkSize: Int = 1024) throws -> (chunks: [Data], manifest: Data) {
        var chunks: [Data] = []
        var off = 0; var i: UInt32 = 0
        while off < plain.count {
            let end = min(off + chunkSize, plain.count)
            chunks.append(try OSHICryptoV2.aesGcmSeal(key: vectorKey, nonce: OSHICryptoV2.chunkNonce(vectorNonce, i),
                                                      plaintext: plain.subdata(in: off..<end), aad: Data()))
            off = end; i += 1
        }
        let json = OSHICryptoV2.manifestJSON(filename: "x", mime: "y", size: size, chunkCount: chunks.count,
                                             chunkSize: chunkSize, paddedSize: paddedSize)
        let m = try OSHICryptoV2.aesGcmSeal(key: vectorKey, nonce: OSHICryptoV2.chunkNonce(vectorNonce, OSHICryptoV2.manifestCounter),
                                            plaintext: json, aad: Data())
        return (chunks, m)
    }

    private func assertRejected(_ chunks: [Data], _ manifest: Data, file: StaticString = #filePath, line: UInt = #line) throws {
        XCTAssertThrowsError(try OSHICryptoV2.decryptFile(fileKey: vectorKey, fileNonce: vectorNonce,
                                                          chunks: chunks, manifest: manifest), file: file, line: line)
        let dir = work.appendingPathComponent("rej-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var urls: [URL] = []
        for (i, c) in chunks.enumerated() { let u = dir.appendingPathComponent("\(i)"); try c.write(to: u); urls.append(u) }
        let out = dir.appendingPathComponent("out")
        XCTAssertThrowsError(try OSHICryptoV2.decryptFileStreaming(fileKey: vectorKey, fileNonce: vectorNonce,
                                                                   chunkURLs: urls, manifest: manifest, outputURL: out),
                             file: file, line: line)
        XCTAssertFalse(FileManager.default.fileExists(atPath: out.path), "no half-written attachment", file: file, line: line)
    }

    func testNonZeroPaddingIsRejected() throws {
        var plain = Data(repeating: 0x41, count: 100) + Data(count: 4096 - 100)
        plain[4000] = 1                                    // last chunk
        let a = try forge(plain, size: 100, paddedSize: 4096)
        try assertRejected(a.chunks, a.manifest)
        var plain2 = Data(repeating: 0x41, count: 100) + Data(count: 4096 - 100)
        plain2[100] = 0xFF                                 // first padding byte, same chunk as data
        let b = try forge(plain2, size: 100, paddedSize: 4096)
        try assertRejected(b.chunks, b.manifest)
        // Sanity: the same forge with clean zeros is accepted.
        let ok = try forge(Data(repeating: 0x41, count: 100) + Data(count: 3996), size: 100, paddedSize: 4096)
        XCTAssertEqual(try OSHICryptoV2.decryptFile(fileKey: vectorKey, fileNonce: vectorNonce,
                                                    chunks: ok.chunks, manifest: ok.manifest).bytes,
                       Data(repeating: 0x41, count: 100))
    }

    func testPaddedSizeBelowSizeOrWrongLengthIsRejected() throws {
        let plain = Data(repeating: 0x41, count: 4096)
        let below = try forge(plain, size: 4096, paddedSize: 4000)
        try assertRejected(below.chunks, below.manifest)
        // Reassembled length ≠ paddedSize.
        let short = try forge(Data(repeating: 0x41, count: 100) + Data(count: 2000), size: 100, paddedSize: 4096)
        try assertRejected(short.chunks, short.manifest)
    }

    // MARK: Gating (caps bit 8)

    func testCapabilityIsAdvertisedOnBit8() {
        XCTAssertEqual(RatchetHealSignal.Caps.mediaPadding, 8)
        XCTAssertEqual(V2MediaPaddingPeers.capabilityBit, 8)
        XCTAssertTrue(RatchetHealSignal.Caps.has(RatchetHealSignal.Caps.mine, RatchetHealSignal.Caps.mediaPadding))
        XCTAssertTrue(RatchetHealSignal.Caps.has(RatchetHealSignal.Caps.mine, RatchetHealSignal.Caps.padding))
    }

    func testGatingLearnsBit8LikeMessagePadding() {
        let peer = Data(repeating: 3, count: 32).base64EncodedString()
        XCTAssertFalse(V2MediaPaddingPeers.slotCapable(peer: peer, device: nil), "unknown ⇒ unpadded")
        V2MediaPaddingPeers.noteBundle(peer: peer, device: nil, caps: nil)
        XCTAssertFalse(V2MediaPaddingPeers.slotCapable(peer: peer, device: nil))
        V2MediaPaddingPeers.noteBundle(peer: peer, device: nil, caps: 7)
        XCTAssertFalse(V2MediaPaddingPeers.slotCapable(peer: peer, device: nil), "message padding (4) is not media padding (8)")
        V2MediaPaddingPeers.noteBundle(peer: peer, device: nil, caps: 15)
        XCTAssertTrue(V2MediaPaddingPeers.slotCapable(peer: peer, device: nil))
        V2MediaPaddingPeers.noteBundle(peer: peer, device: nil, caps: 7)
        XCTAssertFalse(V2MediaPaddingPeers.slotCapable(peer: peer, device: nil), "a bundle is exact (downgrade)")

        // Device list: upgrade only.
        V2MediaPaddingPeers.noteDeviceList(peer: peer, device: "devA", caps: 15)
        XCTAssertTrue(V2MediaPaddingPeers.slotCapable(peer: peer, device: "devA"))
        V2MediaPaddingPeers.noteDeviceList(peer: peer, device: "devA", caps: 7)
        XCTAssertTrue(V2MediaPaddingPeers.slotCapable(peer: peer, device: "devA"))
        XCTAssertFalse(V2MediaPaddingPeers.slotCapable(peer: peer, device: "devB"))

        // A new X3DH session resets; a padded FILE proves it again.
        V2MediaPaddingPeers.noteInbound(slot: peer + "#devA", paddedFile: false, carriedX3DH: true)
        XCTAssertFalse(V2MediaPaddingPeers.slotCapable(peer: peer, device: "devA"))
        V2MediaPaddingPeers.noteInbound(slot: peer + "#devA", paddedFile: false, carriedX3DH: false)
        XCTAssertFalse(V2MediaPaddingPeers.slotCapable(peer: peer, device: "devA"))
        V2MediaPaddingPeers.noteInbound(slot: peer + "#devA", paddedFile: true, carriedX3DH: false)
        XCTAssertTrue(V2MediaPaddingPeers.slotCapable(peer: peer, device: "devA"))
    }

    func testPaddingDecisionBindsAResume() {
        func rec(padded: Bool?, chunks: Int) -> V2SendResumeStore.Record {
            V2SendResumeStore.Record(msgId: "m", recipientKey: "r", plaintextSHA256: "d", blobId: "b",
                                     fileKeyB64: "k", fileNonceB64: "n", chunkCount: chunks, createdAt: Date(),
                                     mediaPadded: padded)
        }
        let L = 2_097_150              // 1 chunk unpadded, P = 2_097_152 → still 1 chunk
        let big = 4_194_000            // unpadded 2 chunks, P = 4_194_304 → 2 chunks
        let edge = 2_097_153           // unpadded 2 chunks; P = 2_162_688 → 2 chunks
        XCTAssertEqual(OSHICryptoV2.expectedChunkCount(plaintextSize: 100, chunkSize: 1024, padded: false), 1)
        XCTAssertEqual(OSHICryptoV2.expectedChunkCount(plaintextSize: 100, chunkSize: 1024, padded: true), 4)
        XCTAssertEqual(OSHICryptoV2.expectedChunkCount(plaintextSize: edge, chunkSize: C, padded: true), 2)

        // No record: fresh with the gate.
        XCTAssertEqual(V2SendResumeStore.resolveMediaPadding(record: nil, gate: true, plaintextSize: L), .fresh(pad: true))
        XCTAssertEqual(V2SendResumeStore.resolveMediaPadding(record: nil, gate: false, plaintextSize: L), .fresh(pad: false))
        // Old (pre-padding) record = unpadded: continues UNPADDED even if padding is now allowed.
        XCTAssertEqual(V2SendResumeStore.resolveMediaPadding(record: rec(padded: nil, chunks: 1), gate: true, plaintextSize: L),
                       .resume(pad: false))
        XCTAssertEqual(V2SendResumeStore.resolveMediaPadding(record: rec(padded: false, chunks: 2), gate: true, plaintextSize: big),
                       .resume(pad: false))
        // Padded record continues padded while allowed; abandoned when not.
        XCTAssertEqual(V2SendResumeStore.resolveMediaPadding(record: rec(padded: true, chunks: 2), gate: true, plaintextSize: big),
                       .resume(pad: true))
        XCTAssertEqual(V2SendResumeStore.resolveMediaPadding(record: rec(padded: true, chunks: 2), gate: false, plaintextSize: big),
                       .fresh(pad: false))
        // Chunk count must match the PADDED size: 100 B padded = 4 chunks of 1 KiB.
        XCTAssertEqual(V2SendResumeStore.resolveMediaPadding(record: rec(padded: true, chunks: 4), gate: true,
                                                             plaintextSize: 100, chunkSize: 1024), .resume(pad: true))
        XCTAssertEqual(V2SendResumeStore.resolveMediaPadding(record: rec(padded: true, chunks: 1), gate: true,
                                                             plaintextSize: 100, chunkSize: 1024), .fresh(pad: true))
    }

    /// Old records on disk (no `mediaPadded` key) still decode, as unpadded.
    func testOldResumeRecordDecodesAsUnpadded() throws {
        let json = #"{"msgId":"m","recipientKey":"r","plaintextSHA256":"d","blobId":"b","fileKeyB64":"k","fileNonceB64":"n","chunkCount":1,"createdAt":0}"#
        let r = try JSONDecoder().decode(V2SendResumeStore.Record.self, from: Data(json.utf8))
        XCTAssertNil(r.mediaPadded)
    }

    /// Mesh / LoRa / legacy IPFS never pad: only the v2 blob senders pass `padMedia:`.
    func testMediaPaddingOnlyOnTheV2BlobPath() throws {
        let osh = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("OSHI")
        let files = try FileManager.default.contentsOfDirectory(at: osh, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "swift" }
        XCTAssertGreaterThan(files.count, 50, "source tree not found at \(osh.path)")
        var callers: [String] = []
        for f in files where f.lastPathComponent != "OSHICryptoV2.swift" && !f.lastPathComponent.hasSuffix("Tests.swift") {
            let text = try String(contentsOf: f, encoding: .utf8)
            if text.contains("padMedia:") || text.contains("mediaPaddedSize(") { callers.append(f.lastPathComponent) }
        }
        XCTAssertEqual(callers, ["V2MessageRouter.swift"])
        let router = try String(contentsOf: osh.appendingPathComponent("V2MessageRouter.swift"), encoding: .utf8)
        XCTAssertEqual(router.components(separatedBy: "padMedia: padMedia").count - 1, 4,
                       "sendFile + sendGroupFile, in-memory + streaming")
    }

    // MARK: OHTTP for /v2/blobs*

    func testBlobPathsAreNowOHTTPRoutable() {
        for p in ["/v2/blobs", "/v2/blobs/abc/chunk/0", "/v2/blobs/abc/status", "/v2/blobs/abc/commit", "/v2/blobs/abc",
                  "/v2/messages", "/v2/keys/abc/count"] {
            XCTAssertTrue(OHTTPSettings.isRoutable(path: p), p)
        }
        XCTAssertFalse(OHTTPSettings.isRoutable(path: "/api/queue/x"))
        XCTAssertFalse(OHTTPSettings.isRoutable(path: "/ws/devsync"))
    }

    /// A full 2 MiB chunk PUT keeps its exact signed path/headers/body in the BHTTP
    /// request and fits the gateway's 2 MiB + 64 KiB request cap once encapsulated.
    func testFullChunkPutFitsTheGatewayCap() throws {
        guard #available(iOS 17.0, macOS 14.0, *) else { throw XCTSkip("HPKE needs iOS 17") }
        var req = URLRequest(url: URL(string: "https://oshi-messenger.com/v2/blobs/abc/chunk/3")!)
        req.httpMethod = "PUT"
        req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        req.setValue(String(repeating: "s", count: 120), forHTTPHeaderField: "x-oshi-signature")
        req.httpBody = Data(repeating: 0xAB, count: 2_097_152 + 16)
        let inner = try OHTTPTransport.innerRequest(for: req)
        XCTAssertEqual(inner.path, "/v2/blobs/abc/chunk/3")
        XCTAssertEqual(inner.method, "PUT")
        XCTAssertEqual(inner.content.count, 2_097_168)
        let cfg = try XCTUnwrap(OHTTPSettings.pinnedKeyConfig)
        let (enc, _) = try OHTTP.encapsulateRequest(BinaryHTTP.encode(inner), config: cfg)
        XCTAssertLessThanOrEqual(enc.count, 2 * 1024 * 1024 + 64 * 1024)
    }

    /// With the relay ON, a blob request goes to the RELAY (and fails there when the
    /// relay is down) — never direct; background sessions are bypassed.
    func testBlobRequestGoesThroughTheRelayWhenActive() async throws {
        guard #available(iOS 17.0, macOS 14.0, *) else { throw XCTSkip("HPKE needs iOS 17") }
        #if DEBUG
        let prevEnabled = UserDefaults.standard.object(forKey: OHTTPSettings.enabledKey)
        setenv("OSHI_OHTTP_RELAY_URL", "http://127.0.0.1:9/", 1)
        UserDefaults.standard.set(true, forKey: OHTTPSettings.enabledKey)
        defer {
            unsetenv("OSHI_OHTTP_RELAY_URL")
            if let prevEnabled { UserDefaults.standard.set(prevEnabled, forKey: OHTTPSettings.enabledKey) }
            else { UserDefaults.standard.removeObject(forKey: OHTTPSettings.enabledKey) }
            _ = OHTTPSettings.userEnabled
        }
        guard OHTTPSettings.isActive else { throw XCTSkip("environment override not visible to ProcessInfo") }
        XCTAssertTrue(V2Transport.blobsMustStayForeground, "no background URLSession while the relay is on")
        var req = URLRequest(url: URL(string: "https://oshi-messenger.com/v2/blobs/abc/chunk/0")!)
        req.timeoutInterval = 5
        do {
            _ = try await V2Transport.data(for: req, session: .shared)
            XCTFail("must fail at the (dead) relay, never reach the API host")
        } catch {
            // Reached the relay (connection refused on :9) — not a pathNotRoutable, not a direct 4xx.
            XCTAssertNotEqual(error as? OHTTPError, .pathNotRoutable("/v2/blobs/abc/chunk/0"))
            XCTAssertNotEqual(error as? OHTTPError, .notConfigured)
        }
        #else
        throw XCTSkip("DEBUG-only relay override")
        #endif
    }
}
