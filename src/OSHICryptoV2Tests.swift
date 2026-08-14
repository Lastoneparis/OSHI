//
//  OSHICryptoV2Tests.swift
//  OSHI — byte-parity tests for the CryptoKit port of `crypto_ref.js`.
//
//  ⚠️ 2026-07-16: this file was NOT a member of any target — it had never been
//  compiled, let alone run, so it was coverage in appearance only. It is now in
//  the OSHITests target (project.pbxproj, CA11CRYP* ids). Run it with:
//    xcodebuild test -project OSHI.xcodeproj -scheme Genesis \
//      -destination 'id=<sim-udid>' -only-testing:OSHITests/OSHICryptoV2Tests
//  and CHECK the "Executed N tests" line — a bad -only-testing filter still
//  prints "** TEST SUCCEEDED **" while executing 0 tests.
//
//  The fixed vectors below are inlined verbatim from `test_vectors.json`, so the
//  test needs no bundle resource. Coverage:
//    (A) FIXED VECTORS  — X3DH SK; one Double Ratchet RK/CKs/MK/KEY/NONCE/CT;
//                          one file chunk NONCE0/CT.
//    (B) SELF-CONSISTENCY — X3DH initiator/responder agree; Double Ratchet
//                          back-and-forth incl. one out-of-order delivery;
//                          5 MB file encrypt -> decrypt equals original.
//
//  Adjust the module name in the import below if this target's module is not
//  "OSHI".
//

import XCTest
import CryptoKit
@testable import OSHI

final class OSHICryptoV2Tests: XCTestCase {

    // MARK: - hex helpers

    private func hex(_ s: String) -> Data {
        var data = Data()
        var idx = s.startIndex
        while idx < s.endIndex {
            let next = s.index(idx, offsetBy: 2)
            data.append(UInt8(s[idx..<next], radix: 16)!)
            idx = next
        }
        return data
    }

    private func hexStr(_ d: Data) -> String {
        return d.map { String(format: "%02x", $0) }.joined()
    }

    // ======================================================================
    // (A) FIXED VECTORS — must match crypto_ref.js exactly.
    // ======================================================================

    func testX3DHVector() throws {
        // Inputs (test_vectors.json -> x3dh.inputs)
        let aIkPriv = hex("9075dca881d3bf1d61bb3d8887500ae4ab7e07533c7f2c23e918f87c49dde973")
        let aIkPub  = hex("ca548efd90c81b073386d49b0a46fd9fcd9793180fadc0ea3cbadcfd39962403")
        let aEkPriv = hex("40fdf08a94a157da384df3c5b985d12089c5195e41deef35aec01c0f64916a74")
        let aEkPub  = hex("f88ae69adedcc56713189704c703c7a2253fed76643a2ff3750ac5ffd5014475")
        let bIkPub  = hex("3854d658e7ee3f7e1e99569048a64ab6a4da2ebe8bed8f71b8190abba1ab5e05")
        let bSpkPub = hex("2ff3c27cbe727972e6d594336c8611311fa14740afaffda1e950e034944bc906")
        let bOpkPub = hex("417e827f8aa889a74248fa298f0bbed6d06a4616239297ea24266d4456613947")

        let expectedSK = "1e698eedf60da463caacead61efe258cc730a8556050c2841d24ebfc621c9223"

        let sk = try OSHICryptoV2.x3dhInitiator(
            ik: OSHICryptoV2.X25519Pair(priv: aIkPriv, pub: aIkPub),
            ek: OSHICryptoV2.X25519Pair(priv: aEkPriv, pub: aEkPub),
            theirIkPub: bIkPub,
            theirSpkPub: bSpkPub,
            theirOpkPub: bOpkPub
        )
        XCTAssertEqual(hexStr(sk), expectedSK, "X3DH SK mismatch vs reference vector")
    }

    func testDoubleRatchetVector() throws {
        // Inputs (test_vectors.json -> double_ratchet.inputs)
        let SK = hex("591df6ebb3bbeed6e2d2db39a6e8197fe3b9b6bc1196abcf5e9b174d07a55e88")
        let aRatchetPriv = hex("f0d6f7b0631bbdf92528320af8673fee53bde46120ab3da0b36d6490ef245760")
        let aRatchetPub  = hex("5206c6092f1d552164eb33ebe66c7d943737b5de5d9f238073e59cbb97b05e6e")
        let bRatchetPub  = hex("cb6a5743bff9c4fb0239d57895c7f5214a40562da2e806728d7f3ff316c41518")
        let ad = hex("6f7368692d61642d7632")
        let pt = hex("68656c6c6f206f73686920646f75626c652072617463686574")

        // Expected outputs
        let eRK    = "bfc6c4f0408338cf0368387a2e4493a5432b220a1039114ff52783389afaa67f"
        let eCKs   = "bbe73b2fd3d6a904b66539137da48d606da192491802cc6900b9a197f666aa7b"
        let eMK    = "3f87018c970f224b743b0c431894ba4774b7b4ec31412b728cd2fdd7a03f3cd1"
        let eKEY   = "88e46d8088fa5014d18ee333b94c8def8840fd98bdf4e9f063751ba2897734f5"
        let eNONCE = "8bd0f0abfa88825abc68fc14"
        let eCT    = "804127cba25e2ec4e966150459b39a0a4f4e81c044c8d8defd83420006e3267838de6f4715d059c1e6"

        // Reproduce the reference's derivation directly from the primitives.
        let alicePair = OSHICryptoV2.X25519Pair(priv: aRatchetPriv, pub: aRatchetPub)
        let dhOut = try OSHICryptoV2.dh(alicePair, bRatchetPub)
        let (rk, cks) = OSHICryptoV2.kdfRK(SK, dhOut)
        XCTAssertEqual(hexStr(rk), eRK, "KDF_RK root key mismatch")
        XCTAssertEqual(hexStr(cks), eCKs, "KDF_RK chain key mismatch")

        let (_, mk) = OSHICryptoV2.kdfCK(cks)
        XCTAssertEqual(hexStr(mk), eMK, "KDF_CK message key mismatch")

        let (key, nonce) = OSHICryptoV2.deriveMsgKey(mk)
        XCTAssertEqual(hexStr(key), eKEY, "message AEAD key mismatch")
        XCTAssertEqual(hexStr(nonce), eNONCE, "message AEAD nonce mismatch")

        let header = OSHICryptoV2.Header(dh: aRatchetPub, pn: 0, n: 0)
        let aad = ad + OSHICryptoV2.encodeHeader(header)
        let ct = try OSHICryptoV2.aesGcmSeal(key: key, nonce: nonce, plaintext: pt, aad: aad)
        XCTAssertEqual(hexStr(ct), eCT, "ratchet ciphertext (ct||tag) mismatch")
    }

    func testFileChunkVector() throws {
        // Inputs (test_vectors.json -> file_chunk.inputs)
        let fileKey = hex("4eb8b5d38eb2262b1893c228e3773473b4fa5a7a082e74de32d0fe99e033e1f5")
        let fileNonce = hex("9122e59770302611")
        let pt = hex("4f5348492066696c65206368756e6b207a65726f207061796c6f61642030313233343536373839")

        let eNONCE0 = "9122e5977030261100000000"
        let eCT = "60db9693ed70c16f04514ca550ebdbf48f71df2687cec9b89d1682238d42fd7ebe96f1db8f0e23ef26bdaa6b5e0f17b2ae923d7847cf9e"

        let nonce0 = OSHICryptoV2.chunkNonce(fileNonce, 0)
        XCTAssertEqual(hexStr(nonce0), eNONCE0, "chunk-0 nonce mismatch")

        let ct = try OSHICryptoV2.aesGcmSeal(key: fileKey, nonce: nonce0, plaintext: pt, aad: Data())
        XCTAssertEqual(hexStr(ct), eCT, "file chunk ciphertext (ct||tag) mismatch")
    }

    // ======================================================================
    // (B) SELF-CONSISTENCY round-trips.
    // ======================================================================

    func testX3DHBothSidesAgree() throws {
        // Fresh keys for both parties.
        let aIk = OSHICryptoV2.generateX25519()
        let aEk = OSHICryptoV2.generateX25519()
        let bIk = OSHICryptoV2.generateX25519()
        let bSpk = OSHICryptoV2.generateX25519()
        let bOpk = OSHICryptoV2.generateX25519()

        // With one-time prekey.
        let skA = try OSHICryptoV2.x3dhInitiator(
            ik: aIk, ek: aEk,
            theirIkPub: bIk.pub, theirSpkPub: bSpk.pub, theirOpkPub: bOpk.pub
        )
        let skB = try OSHICryptoV2.x3dhResponder(
            ik: bIk, spk: bSpk, opk: bOpk,
            theirIkPub: aIk.pub, theirEkPub: aEk.pub
        )
        XCTAssertEqual(skA, skB, "X3DH (with OPK) sides disagree")

        // Without one-time prekey.
        let skA2 = try OSHICryptoV2.x3dhInitiator(
            ik: aIk, ek: aEk,
            theirIkPub: bIk.pub, theirSpkPub: bSpk.pub, theirOpkPub: nil
        )
        let skB2 = try OSHICryptoV2.x3dhResponder(
            ik: bIk, spk: bSpk, opk: nil,
            theirIkPub: aIk.pub, theirEkPub: aEk.pub
        )
        XCTAssertEqual(skA2, skB2, "X3DH (no OPK) sides disagree")
        XCTAssertNotEqual(skA, skA2, "OPK should change the derived secret")

        // Signed-prekey sign/verify interoperates.
        let bIdSign = OSHICryptoV2.generateEd25519()
        let sig = try OSHICryptoV2.signPrekey(bIdSign, bSpk.pub)
        XCTAssertTrue(OSHICryptoV2.verifyPrekey(bIdSign.pub, bSpk.pub, sig), "prekey signature must verify")
    }

    func testDoubleRatchetRoundTripWithOutOfOrder() throws {
        let SK = OSHICryptoV2.randomBytes(32)
        let bobPair = OSHICryptoV2.generateX25519()
        let ad = Data("oshi-ad".utf8)

        let alice = try OSHICryptoV2.ratchetInitAlice(SK: SK, bobRatchetPub: bobPair.pub)
        let bob = OSHICryptoV2.ratchetInitBob(SK: SK, bobRatchetPair: bobPair)

        // Alice -> Bob, first message triggers Bob's DH ratchet.
        let m1 = Data("message one".utf8)
        let e1 = try OSHICryptoV2.ratchetEncrypt(alice, plaintext: m1, associatedData: ad)
        let d1 = try OSHICryptoV2.ratchetDecrypt(bob, header: e1.header, ciphertext: e1.ciphertext, associatedData: ad)
        XCTAssertEqual(d1, m1, "A->B message 1 round trip failed")

        // Bob -> Alice reply.
        let r1 = Data("reply one".utf8)
        let er1 = try OSHICryptoV2.ratchetEncrypt(bob, plaintext: r1, associatedData: ad)
        let dr1 = try OSHICryptoV2.ratchetDecrypt(alice, header: er1.header, ciphertext: er1.ciphertext, associatedData: ad)
        XCTAssertEqual(dr1, r1, "B->A reply round trip failed")

        // Alice -> Bob two more messages; deliver OUT OF ORDER (m3 before m2).
        let m2 = Data("message two".utf8)
        let m3 = Data("message three".utf8)
        let e2 = try OSHICryptoV2.ratchetEncrypt(alice, plaintext: m2, associatedData: ad)
        let e3 = try OSHICryptoV2.ratchetEncrypt(alice, plaintext: m3, associatedData: ad)

        let d3 = try OSHICryptoV2.ratchetDecrypt(bob, header: e3.header, ciphertext: e3.ciphertext, associatedData: ad)
        XCTAssertEqual(d3, m3, "out-of-order: m3 (arrived first) failed")

        let d2 = try OSHICryptoV2.ratchetDecrypt(bob, header: e2.header, ciphertext: e2.ciphertext, associatedData: ad)
        XCTAssertEqual(d2, m2, "out-of-order: m2 (from skipped store) failed")

        // Tampered ciphertext must throw.
        var tampered = e1.ciphertext
        tampered[tampered.startIndex] ^= 0xff
        let freshBob = OSHICryptoV2.ratchetInitBob(SK: SK, bobRatchetPair: bobPair)
        XCTAssertThrowsError(
            try OSHICryptoV2.ratchetDecrypt(freshBob, header: e1.header, ciphertext: tampered, associatedData: ad),
            "tampered ciphertext must fail authentication"
        )
    }

    func testFileEncryptDecrypt5MB() throws {
        let size = 5 * 1024 * 1024
        let original = OSHICryptoV2.randomBytes(size)
        let meta = OSHICryptoV2.FileMeta(filename: "photo.jpg", mime: "image/jpeg")

        let enc = try OSHICryptoV2.encryptFile(bytes: original, chunkSize: 1024 * 1024, meta: meta)
        XCTAssertEqual(enc.chunks.count, 5, "expected 5 chunks for 5 MB @ 1 MB chunkSize")

        let (bytes, info) = try OSHICryptoV2.decryptFile(
            fileKey: enc.fileKey,
            fileNonce: enc.fileNonce,
            chunks: enc.chunks,
            manifest: enc.manifest
        )
        XCTAssertEqual(bytes, original, "file round trip produced different bytes")
        XCTAssertEqual((info["size"] as? NSNumber)?.intValue, size)
        XCTAssertEqual(info["filename"] as? String, "photo.jpg")
        XCTAssertEqual(info["mime"] as? String, "image/jpeg")

        // A flipped byte in any chunk must fail the tag.
        var badChunks = enc.chunks
        badChunks[2][badChunks[2].startIndex] ^= 0x01
        XCTAssertThrowsError(
            try OSHICryptoV2.decryptFile(
                fileKey: enc.fileKey, fileNonce: enc.fileNonce, chunks: badChunks, manifest: enc.manifest
            ),
            "corrupted chunk must fail authentication"
        )
    }

    // MARK: - Streaming file crypto
    //
    // The streaming path exists so a large attachment doesn't spike ~2x its size
    // in RAM. It is only safe to ship if it is WIRE-IDENTICAL to the in-memory
    // path, otherwise peers on different builds can't read each other's files.
    // These tests cross-decrypt each path with the other, in both directions.

    private func makeTempDir() throws -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("oshi-stream-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// streaming encrypt -> IN-MEMORY decrypt. Proves a file written by a new
    /// build is readable by the existing decryptFile (i.e. by older peers).
    func testStreamingEncryptIsReadableByInMemoryDecrypt() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }

        let size = 5 * 1024 * 1024 + 12345          // deliberately not chunk-aligned
        let original = OSHICryptoV2.randomBytes(size)
        let input = dir.appendingPathComponent("in.bin")
        try original.write(to: input)

        let meta = OSHICryptoV2.FileMeta(filename: "photo.jpg", mime: "image/jpeg")
        let enc = try OSHICryptoV2.encryptFileStreaming(
            inputURL: input,
            outputDir: dir.appendingPathComponent("chunks"),
            chunkSize: 1024 * 1024,
            meta: meta
        )
        XCTAssertEqual(enc.chunkCount, 6, "5 MB + remainder @ 1 MB chunkSize = 6 chunks")
        XCTAssertEqual(enc.size, size)

        // Feed the streamed chunks to the ORIGINAL in-memory decryptor.
        let chunks = try enc.chunkURLs.map { try Data(contentsOf: $0) }
        let (bytes, info) = try OSHICryptoV2.decryptFile(
            fileKey: enc.fileKey, fileNonce: enc.fileNonce, chunks: chunks, manifest: enc.manifest
        )
        XCTAssertEqual(bytes, original, "streamed ciphertext must decrypt to the original bytes")
        XCTAssertEqual((info["size"] as? NSNumber)?.intValue, size)
        XCTAssertEqual(info["filename"] as? String, "photo.jpg")
        XCTAssertEqual(info["mime"] as? String, "image/jpeg")
    }

    /// IN-MEMORY encrypt -> streaming decrypt. Proves a new build can read files
    /// produced by every build already in the field.
    func testInMemoryEncryptIsReadableByStreamingDecrypt() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }

        let size = 3 * 1024 * 1024 + 777
        let original = OSHICryptoV2.randomBytes(size)
        let meta = OSHICryptoV2.FileMeta(filename: "doc.pdf", mime: "application/pdf")
        let enc = try OSHICryptoV2.encryptFile(bytes: original, chunkSize: 1024 * 1024, meta: meta)

        // Spill the in-memory chunks to disk and read them back the streaming way.
        var urls: [URL] = []
        for (i, c) in enc.chunks.enumerated() {
            let u = dir.appendingPathComponent("\(i).chunk")
            try c.write(to: u)
            urls.append(u)
        }
        let out = dir.appendingPathComponent("out.bin")
        let info = try OSHICryptoV2.decryptFileStreaming(
            fileKey: enc.fileKey, fileNonce: enc.fileNonce,
            chunkURLs: urls, manifest: enc.manifest, outputURL: out
        )
        XCTAssertEqual(try Data(contentsOf: out), original, "streaming decrypt must reproduce the original bytes")
        XCTAssertEqual((info["size"] as? NSNumber)?.intValue, size)
        XCTAssertEqual(info["filename"] as? String, "doc.pdf")
    }

    /// Full streaming round trip, plus the guarantees the transport relies on.
    func testStreamingRoundTripAndFailureModes() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }

        let size = 2 * 1024 * 1024 * 3
        let original = OSHICryptoV2.randomBytes(size)
        let input = dir.appendingPathComponent("in.bin")
        try original.write(to: input)

        let enc = try OSHICryptoV2.encryptFileStreaming(
            inputURL: input, outputDir: dir.appendingPathComponent("c"), chunkSize: 2 * 1024 * 1024
        )
        let out = dir.appendingPathComponent("out.bin")
        try OSHICryptoV2.decryptFileStreaming(
            fileKey: enc.fileKey, fileNonce: enc.fileNonce,
            chunkURLs: enc.chunkURLs, manifest: enc.manifest, outputURL: out
        )
        XCTAssertEqual(try Data(contentsOf: out), original)

        // A corrupted chunk must fail the tag AND leave no partial file behind —
        // a half-written attachment must never look like a real one.
        var bad = try Data(contentsOf: enc.chunkURLs[1])
        bad[bad.startIndex] ^= 0x01
        try bad.write(to: enc.chunkURLs[1])
        let out2 = dir.appendingPathComponent("out2.bin")
        XCTAssertThrowsError(
            try OSHICryptoV2.decryptFileStreaming(
                fileKey: enc.fileKey, fileNonce: enc.fileNonce,
                chunkURLs: enc.chunkURLs, manifest: enc.manifest, outputURL: out2
            ),
            "corrupted chunk must fail authentication"
        )
        XCTAssertFalse(FileManager.default.fileExists(atPath: out2.path),
                       "failed decrypt must not leave a partial output file")
    }

    /// An empty file must produce 0 chunks, exactly like the in-memory path.
    func testStreamingEmptyFileMatchesInMemory() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }

        let input = dir.appendingPathComponent("empty.bin")
        try Data().write(to: input)
        let enc = try OSHICryptoV2.encryptFileStreaming(
            inputURL: input, outputDir: dir.appendingPathComponent("c")
        )
        XCTAssertEqual(enc.chunkCount, 0)
        XCTAssertEqual(enc.size, 0)
        XCTAssertEqual(try OSHICryptoV2.encryptFile(bytes: Data()).chunks.count, 0,
                       "in-memory path also yields 0 chunks for an empty file")

        let out = dir.appendingPathComponent("out.bin")
        try OSHICryptoV2.decryptFileStreaming(
            fileKey: enc.fileKey, fileNonce: enc.fileNonce,
            chunkURLs: enc.chunkURLs, manifest: enc.manifest, outputURL: out
        )
        XCTAssertEqual(try Data(contentsOf: out).count, 0)
    }
}
