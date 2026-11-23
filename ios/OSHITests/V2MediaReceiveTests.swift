//
//  V2MediaReceiveTests.swift
//  OSHITests
//
//  THE V2 MEDIA **RECEIVE** PATH.
//
//  Why this file exists: until 2026-08-12 the v2 bundle published the X25519
//  key-agreement key as its `signingKey` while the signed pre-key was signed
//  with a separate Ed25519 key, so `verifyPrekey` could never succeed,
//  `establishInitiatorSession` threw for every peer, and every media send fell
//  back to legacy IPFS. Consequence: the v2 media SEND *and* RECEIVE paths had
//  effectively never executed in production. Fixing the send routes real user
//  media through reception code nothing has exercised — so reception needs its
//  own evidence, not an assumption.
//
//  What is proven here, offline (no network):
//    1. `V2Client.downloadBlob` returns chunks in INDEX order even when the
//       per-chunk HTTP completions land in reverse order. The test asserts that
//       the completions really did arrive out of order — otherwise the ordering
//       assertion would be vacuous.
//    2. The downloaded array feeds `OSHICryptoV2.decryptFile` to byte-exact
//       plaintext: real AEAD chunks, scrambled network completions, real
//       decrypt. That is the whole reception contract in one test.
//    3. A file reassembled OUT OF ORDER fails to decrypt — which is what makes
//       (1) meaningful. The per-chunk nonce is `fileNonce || u32be(index)`, so
//       order is authenticated, and a misordered reassembly surfaces as "corrupt
//       media" rather than as an ordering error anyone could debug.
//    4. `chunkCount` arithmetic: a short array, a long array, and the `>= 1`
//       guard are all rejected rather than silently truncating a file.
//    5. The in-memory (`encryptFile`/`decryptFile`) and streaming
//       (`encryptFileStreaming`/`decryptFileStreaming`) paths produce and accept
//       identical bytes — the two halves of reception must never disagree, since
//       the sender picks its path by size and the receiver picks by call site.
//    6. `V2FileKeyMessage` survives JSON encode/decode with `mediaType`,
//       `isViewOnce` and `groupMessage` intact, including the older-payload shape
//       where the optional fields are absent.
//
//  The live end-to-end counterpart is V2LiveMediaRoundTripTests.swift.
//

import XCTest
import CryptoKit
@testable import OSHI

// MARK: - Stub transport (the injectable seam)

/// Shared, lock-guarded state for `V2ChunkStubProtocol`. `URLProtocol` instances
/// are created by URLSession, so configuration has to reach them statically.
final class V2ChunkStubState: @unchecked Sendable {
    private let lock = NSLock()
    private var _bodies: [Int: Data] = [:]
    private var _absent: Set<Int> = []
    private var _completionOrder: [Int] = []
    /// Per-index artificial latency. Reverse-ordered latencies are what force the
    /// completions to land in the opposite order to the requests.
    private var _delay: (Int) -> TimeInterval = { _ in 0 }

    func reset() {
        lock.lock(); defer { lock.unlock() }
        _bodies = [:]; _absent = []; _completionOrder = []; _delay = { _ in 0 }
    }

    func configure(bodies: [Int: Data], absent: Set<Int> = [], delay: @escaping (Int) -> TimeInterval) {
        lock.lock(); defer { lock.unlock() }
        _bodies = bodies; _absent = absent; _completionOrder = []; _delay = delay
    }

    func body(for index: Int) -> Data? {
        lock.lock(); defer { lock.unlock() }
        return _absent.contains(index) ? nil : _bodies[index]
    }

    func delay(for index: Int) -> TimeInterval {
        lock.lock(); defer { lock.unlock() }
        return _delay(index)
    }

    func recordCompletion(_ index: Int) {
        lock.lock(); defer { lock.unlock() }
        _completionOrder.append(index)
    }

    var completionOrder: [Int] {
        lock.lock(); defer { lock.unlock() }
        return _completionOrder
    }
}

/// Serves `GET /v2/blobs/{id}/chunk/{n}` out of `V2ChunkStubState`, with a
/// per-index delay so completions can be made to arrive out of order on purpose.
final class V2ChunkStubProtocol: URLProtocol, @unchecked Sendable {
    static let state = V2ChunkStubState()

    private static func chunkIndex(of url: URL?) -> Int? {
        guard let url else { return nil }
        let parts = url.path.split(separator: "/")
        guard parts.count >= 2, parts[parts.count - 2] == "chunk" else { return nil }
        return Int(parts[parts.count - 1])
    }

    override class func canInit(with request: URLRequest) -> Bool { chunkIndex(of: request.url) != nil }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url, let index = Self.chunkIndex(of: url) else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        let delay = Self.state.delay(for: index)
        DispatchQueue.global().asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self else { return }
            Self.state.recordCompletion(index)
            guard let body = Self.state.body(for: index) else {
                let resp = HTTPURLResponse(url: url, statusCode: 404,
                                           httpVersion: "HTTP/1.1",
                                           headerFields: ["Content-Type": "application/json"])!
                self.client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
                self.client?.urlProtocol(self, didLoad: Data(#"{"error":"chunk-not-found"}"#.utf8))
                self.client?.urlProtocolDidFinishLoading(self)
                return
            }
            let resp = HTTPURLResponse(url: url, statusCode: 200,
                                       httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": "application/octet-stream"])!
            self.client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
            self.client?.urlProtocol(self, didLoad: body)
            self.client?.urlProtocolDidFinishLoading(self)
        }
    }

    override func stopLoading() {}
}

/// An in-memory `V2CryptoProviding` — the client's only crypto dependency. Keeps
/// these tests off the Keychain and off the device's real identity entirely.
struct V2EphemeralCrypto: V2CryptoProviding {
    let identity: Curve25519.KeyAgreement.PrivateKey
    let signing: Curve25519.Signing.PrivateKey

    init(identity: Curve25519.KeyAgreement.PrivateKey = Curve25519.KeyAgreement.PrivateKey(),
         signing: Curve25519.Signing.PrivateKey = Curve25519.Signing.PrivateKey()) {
        self.identity = identity
        self.signing = signing
    }

    var userKey: String { identity.publicKey.rawRepresentation.base64EncodedString() }

    func signCanonical(_ message: Data) throws -> (signature: String, signingPublicKey: String) {
        (try signing.signature(for: message).base64EncodedString(),
         signing.publicKey.rawRepresentation.base64EncodedString())
    }
}

// MARK: - Tests

final class V2MediaReceiveTests: XCTestCase {

    private func stubbedClient() -> V2Client {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [V2ChunkStubProtocol.self]
        return V2Client(config: V2Client.Config(baseURL: URL(string: "https://stub.invalid")!,
                                                requestTimeout: 10,
                                                maxRetryAttempts: 1),
                        crypto: V2EphemeralCrypto(),
                        session: URLSession(configuration: cfg))
    }

    override func setUp() {
        super.setUp()
        V2ChunkStubProtocol.state.reset()
    }

    // MARK: 1 — chunk ORDER survives out-of-order completions

    /// `downloadBlob` runs 4 chunk fetches at a time and writes results BY INDEX.
    /// Force the completions to land in reverse order and require the returned
    /// array to still be in index order. The test also asserts the reversal
    /// actually happened — a stub that answered in request order would make the
    /// ordering assertion prove nothing.
    func testDownloadBlobReturnsChunksInIndexOrderWhenCompletionsFinishOutOfOrder() async throws {
        let chunkCount = 8
        // Distinguishable per index, and different LENGTHS too, so an off-by-one
        // or a rotation cannot accidentally compare equal.
        var bodies: [Int: Data] = [:]
        for n in 0..<chunkCount {
            bodies[n] = Data(repeating: UInt8(0xA0 + n), count: 64 + n)
        }
        // Later indices finish FIRST: index n waits (chunkCount - n) * 40 ms.
        V2ChunkStubProtocol.state.configure(bodies: bodies) { n in
            Double(chunkCount - n) * 0.04
        }

        let client = stubbedClient()
        let chunks = try await client.downloadBlob(blobId: "blob-order", chunkCount: chunkCount)

        XCTAssertEqual(chunks.count, chunkCount, "downloadBlob returned the wrong number of chunks")
        for n in 0..<chunkCount {
            XCTAssertEqual(chunks[n], bodies[n],
                           "chunk \(n) is not the bytes the server served for index \(n) — reassembly is misordered")
        }

        let order = V2ChunkStubProtocol.state.completionOrder
        XCTAssertEqual(order.count, chunkCount, "the stub did not serve every chunk")
        XCTAssertNotEqual(order, Array(0..<chunkCount),
                          "completions arrived in index order, so this test never exercised the race it exists for")
    }

    /// The real contract, end to end offline: real `encryptFile` output, served
    /// back through a transport whose completions are scrambled, reassembled by
    /// `downloadBlob`, and opened by `decryptFile`. If reassembly were appended
    /// rather than indexed, this fails as a decrypt error.
    func testScrambledDownloadStillDecryptsByteExact() async throws {
        let original = Data((0..<(9 * 1024)).map { _ in UInt8.random(in: 0...255) })
        let enc = try OSHICryptoV2.encryptFile(bytes: original, chunkSize: 1024)
        XCTAssertEqual(enc.chunks.count, 9, "expected a genuinely multi-chunk file")

        var bodies: [Int: Data] = [:]
        for (n, c) in enc.chunks.enumerated() { bodies[n] = c }
        let total = enc.chunks.count
        V2ChunkStubProtocol.state.configure(bodies: bodies) { n in Double(total - n) * 0.03 }

        let client = stubbedClient()
        let fetched = try await client.downloadBlob(blobId: "blob-aead", chunkCount: total)
        let (out, info) = try OSHICryptoV2.decryptFile(fileKey: enc.fileKey,
                                                       fileNonce: enc.fileNonce,
                                                       chunks: fetched,
                                                       manifest: enc.manifest)
        XCTAssertEqual(out, original, "media did not survive an out-of-order download byte-exact")
        XCTAssertEqual((info["chunkCount"] as? NSNumber)?.intValue, total)
        XCTAssertEqual((info["size"] as? NSNumber)?.intValue, original.count)

        let order = V2ChunkStubProtocol.state.completionOrder
        XCTAssertNotEqual(order, Array(0..<total),
                          "completions arrived in order — the scramble this test depends on did not happen")
    }

    /// A missing chunk must fail the download rather than yield a short file.
    func testDownloadBlobFailsWhenAChunkIsMissing() async throws {
        var bodies: [Int: Data] = [:]
        for n in 0..<4 { bodies[n] = Data(repeating: UInt8(n), count: 32) }
        V2ChunkStubProtocol.state.configure(bodies: bodies, absent: [2]) { _ in 0 }

        let client = stubbedClient()
        do {
            _ = try await client.downloadBlob(blobId: "blob-gap", chunkCount: 4)
            XCTFail("a 404 on chunk 2 produced a file instead of an error")
        } catch {
            // Any thrown error is correct; silently returning bytes is not.
        }
    }

    func testDownloadBlobRejectsNonPositiveChunkCount() async {
        let client = stubbedClient()
        do {
            _ = try await client.downloadBlob(blobId: "blob-zero", chunkCount: 0)
            XCTFail("chunkCount 0 was accepted")
        } catch {}
    }

    // MARK: 2 — a multi-chunk file round-trips byte-exact

    func testMultiChunkFileRoundTripsByteExact() throws {
        for size in [1, 1023, 1024, 1025, 4096, 10_000] {
            let original = Data((0..<size).map { _ in UInt8.random(in: 0...255) })
            let enc = try OSHICryptoV2.encryptFile(bytes: original, chunkSize: 1024)
            XCTAssertEqual(enc.chunks.count, Int(ceil(Double(size) / 1024.0)),
                           "chunk count is wrong for a \(size)-byte file")
            let (out, _) = try OSHICryptoV2.decryptFile(fileKey: enc.fileKey,
                                                        fileNonce: enc.fileNonce,
                                                        chunks: enc.chunks,
                                                        manifest: enc.manifest)
            XCTAssertEqual(out, original, "a \(size)-byte file did not round-trip byte-exact")
        }
    }

    // MARK: 3 — order is AUTHENTICATED (so the ordering test above matters)

    /// Swap two chunks and require the decrypt to fail. Without this, "the
    /// download came back in order" would be an untested claim: nothing else
    /// proves that a misordered reassembly is detectable at all.
    func testOutOfOrderReassemblyFailsToDecrypt() throws {
        let original = Data((0..<5000).map { _ in UInt8.random(in: 0...255) })
        let enc = try OSHICryptoV2.encryptFile(bytes: original, chunkSize: 1024)
        XCTAssertEqual(enc.chunks.count, 5)

        var swapped = enc.chunks
        swapped.swapAt(0, 1)
        XCTAssertThrowsError(try OSHICryptoV2.decryptFile(fileKey: enc.fileKey,
                                                          fileNonce: enc.fileNonce,
                                                          chunks: swapped,
                                                          manifest: enc.manifest),
                             "two swapped chunks decrypted — chunk order is NOT authenticated")

        var reversed = enc.chunks
        reversed.reverse()
        XCTAssertThrowsError(try OSHICryptoV2.decryptFile(fileKey: enc.fileKey,
                                                          fileNonce: enc.fileNonce,
                                                          chunks: reversed,
                                                          manifest: enc.manifest),
                             "a fully reversed chunk array decrypted")

        // Rotation by one: every chunk is present, every chunk is intact, only
        // the positions moved. This is exactly what an `append`-based reassembly
        // under concurrency would produce.
        var rotated = enc.chunks
        rotated.append(rotated.removeFirst())
        XCTAssertThrowsError(try OSHICryptoV2.decryptFile(fileKey: enc.fileKey,
                                                          fileNonce: enc.fileNonce,
                                                          chunks: rotated,
                                                          manifest: enc.manifest),
                             "a rotated chunk array decrypted")
    }

    /// The manifest's `chunkCount` is the receiver's only cross-check that it got
    /// the whole file; a short or long array must be rejected before decryption.
    func testChunkCountMismatchIsRejected() throws {
        let original = Data((0..<5000).map { _ in UInt8.random(in: 0...255) })
        let enc = try OSHICryptoV2.encryptFile(bytes: original, chunkSize: 1024)

        var short = enc.chunks
        short.removeLast()
        XCTAssertThrowsError(try OSHICryptoV2.decryptFile(fileKey: enc.fileKey,
                                                          fileNonce: enc.fileNonce,
                                                          chunks: short,
                                                          manifest: enc.manifest),
                             "a truncated file decrypted")

        var long = enc.chunks
        long.append(enc.chunks[0])
        XCTAssertThrowsError(try OSHICryptoV2.decryptFile(fileKey: enc.fileKey,
                                                          fileNonce: enc.fileNonce,
                                                          chunks: long,
                                                          manifest: enc.manifest),
                             "an over-long chunk array decrypted")
    }

    /// A tampered chunk must not open — the per-chunk tag is the only integrity
    /// check reception has, since the transport hands back opaque bytes.
    func testTamperedChunkFailsToDecrypt() throws {
        let original = Data((0..<3000).map { _ in UInt8.random(in: 0...255) })
        let enc = try OSHICryptoV2.encryptFile(bytes: original, chunkSize: 1024)
        var tampered = enc.chunks
        tampered[1][tampered[1].startIndex] ^= 0x01
        XCTAssertThrowsError(try OSHICryptoV2.decryptFile(fileKey: enc.fileKey,
                                                          fileNonce: enc.fileNonce,
                                                          chunks: tampered,
                                                          manifest: enc.manifest))
    }

    // MARK: 4 — whole-file and streaming paths must not disagree

    /// The sender picks its encrypt path by SIZE and the receiver picks its
    /// decrypt path by CALL SITE (`downloadAndDecryptFile` is in-memory,
    /// `downloadAndDecryptFileToDisk` is streaming). All four combinations have
    /// to agree or media breaks for one size class only — the hardest kind of
    /// bug to see in a bug report.
    func testStreamingAndInMemoryPathsAreInterchangeable() throws {
        let original = Data((0..<7777).map { _ in UInt8.random(in: 0...255) })
        let meta = OSHICryptoV2.FileMeta(filename: "photo \"1\".jpg", mime: "image/jpeg")

        let work = FileManager.default.temporaryDirectory
            .appendingPathComponent("v2recv-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: work) }

        let plainURL = work.appendingPathComponent("plain.bin")
        try original.write(to: plainURL)

        // Same key material on both sides so the ciphertext is directly comparable.
        let inMem = try OSHICryptoV2.encryptFile(bytes: original, chunkSize: 1024, meta: meta)
        let streamed = try OSHICryptoV2.encryptFileStreaming(inputURL: plainURL,
                                                             outputDir: work.appendingPathComponent("c1"),
                                                             chunkSize: 1024,
                                                             meta: meta,
                                                             reuseFileKey: inMem.fileKey,
                                                             reuseFileNonce: inMem.fileNonce)
        XCTAssertEqual(streamed.chunkCount, inMem.chunks.count, "the two encrypt paths disagree on chunk count")
        XCTAssertEqual(streamed.manifest, inMem.manifest, "the two encrypt paths produced different manifests")
        XCTAssertEqual(streamed.infoJSON, inMem.infoJSON, "the two encrypt paths produced different manifest JSON")
        for (n, url) in streamed.chunkURLs.enumerated() {
            XCTAssertEqual(try Data(contentsOf: url), inMem.chunks[n],
                           "streaming chunk \(n) differs from the in-memory one")
        }

        // Cross-decrypt A: streaming decrypt over IN-MEMORY chunks written to disk.
        let dir = work.appendingPathComponent("c2", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var urls: [URL] = []
        for (n, c) in inMem.chunks.enumerated() {
            let u = dir.appendingPathComponent("\(n).chunk")
            try c.write(to: u)
            urls.append(u)
        }
        let outURL = work.appendingPathComponent("out.bin")
        let info = try OSHICryptoV2.decryptFileStreaming(fileKey: inMem.fileKey,
                                                         fileNonce: inMem.fileNonce,
                                                         chunkURLs: urls,
                                                         manifest: inMem.manifest,
                                                         outputURL: outURL)
        XCTAssertEqual(try Data(contentsOf: outURL), original,
                       "streaming decrypt of in-memory chunks lost bytes")
        XCTAssertEqual(info["filename"] as? String, "photo \"1\".jpg",
                       "manifest filename escaping did not survive the round trip")

        // Cross-decrypt B: in-memory decrypt over STREAMING chunks read back.
        let streamedChunks = try streamed.chunkURLs.map { try Data(contentsOf: $0) }
        let (back, _) = try OSHICryptoV2.decryptFile(fileKey: streamed.fileKey,
                                                     fileNonce: streamed.fileNonce,
                                                     chunks: streamedChunks,
                                                     manifest: streamed.manifest)
        XCTAssertEqual(back, original, "in-memory decrypt of streaming chunks lost bytes")
    }

    /// Streaming decrypt must refuse a misordered chunk list too, and must NOT
    /// leave a half-written output file behind that looks like real media.
    func testStreamingDecryptRejectsMisorderAndLeavesNoPartialFile() throws {
        let original = Data((0..<5000).map { _ in UInt8.random(in: 0...255) })
        let enc = try OSHICryptoV2.encryptFile(bytes: original, chunkSize: 1024)

        let work = FileManager.default.temporaryDirectory
            .appendingPathComponent("v2recv-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: work) }

        var urls: [URL] = []
        for (n, c) in enc.chunks.enumerated() {
            let u = work.appendingPathComponent("\(n).chunk")
            try c.write(to: u)
            urls.append(u)
        }
        urls.swapAt(1, 3)

        let outURL = work.appendingPathComponent("out.bin")
        XCTAssertThrowsError(try OSHICryptoV2.decryptFileStreaming(fileKey: enc.fileKey,
                                                                   fileNonce: enc.fileNonce,
                                                                   chunkURLs: urls,
                                                                   manifest: enc.manifest,
                                                                   outputURL: outURL),
                             "streaming decrypt accepted misordered chunks")
        XCTAssertFalse(FileManager.default.fileExists(atPath: outURL.path),
                       "a partial plaintext file survived a failed decrypt — it would be shown as media")
    }

    // MARK: 5 — the key message that carries all of this

    func testV2FileKeyMessageRoundTripsWithAllFields() throws {
        let key = V2FileKeyMessage(
            kind: V2FileKeyMessage.kindValue,
            blobId: "blob_abc123",
            fileKey: Data(repeating: 7, count: 32).base64EncodedString(),
            fileNonce: Data(repeating: 9, count: 8).base64EncodedString(),
            chunkCount: 3,
            manifest: Data(repeating: 11, count: 48).base64EncodedString(),
            filename: "vidéo \"finale\".mp4",
            mime: "video/mp4",
            mediaType: MediaManager.MediaType.video.rawValue,
            isViewOnce: true,
            groupMessage: Data("{\"id\":\"g1\"}".utf8).base64EncodedString())

        let data = try JSONEncoder().encode(key)
        let back = try JSONDecoder().decode(V2FileKeyMessage.self, from: data)
        XCTAssertEqual(back, key, "the file-key message did not survive JSON encode/decode")
        XCTAssertEqual(back.mediaType, MediaManager.MediaType.video.rawValue)
        XCTAssertEqual(MediaManager.MediaType(rawValue: back.mediaType ?? ""), .video,
                       "mediaType no longer maps back to a MediaType — the bubble would render as a generic file")
        XCTAssertEqual(back.isViewOnce, true)
        XCTAssertEqual(back.groupMessage, key.groupMessage)
        XCTAssertEqual(back.chunkCount, 3)
        XCTAssertEqual(back.filename, "vidéo \"finale\".mp4")
    }

    /// A payload written by an older build has no `mediaType` / `isViewOnce` /
    /// `groupMessage`. It must still decode — otherwise every in-flight message
    /// from an older peer becomes an undecryptable envelope.
    func testV2FileKeyMessageDecodesLegacyPayloadWithoutOptionalFields() throws {
        let json = """
        {"kind":"v2file","blobId":"b1","fileKey":"AAAA","fileNonce":"BBBB",\
        "chunkCount":2,"manifest":"CCCC","filename":"a.bin","mime":"application/octet-stream"}
        """
        let back = try JSONDecoder().decode(V2FileKeyMessage.self, from: Data(json.utf8))
        XCTAssertEqual(back.kind, V2FileKeyMessage.kindValue)
        XCTAssertNil(back.mediaType)
        XCTAssertNil(back.isViewOnce)
        XCTAssertNil(back.groupMessage)
        XCTAssertEqual(back.chunkCount, 2)
    }

    /// A plain text payload must NOT be mistaken for a file-key message: the
    /// discriminator is the only thing separating "show a photo" from "show text".
    func testPlainJSONTextIsNotMistakenForAFileKeyMessage() throws {
        let notAFile = Data(#"{"kind":"something-else","blobId":"x"}"#.utf8)
        let decoded = try? JSONDecoder().decode(V2FileKeyMessage.self, from: notAFile)
        // Either it fails to decode (missing required fields) or the kind differs;
        // both are rejections. What must never happen is a `v2file` verdict.
        XCTAssertNotEqual(decoded?.kind, V2FileKeyMessage.kindValue)
    }
}
