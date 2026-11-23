//
//  TwoUserServerDeliveryTests.swift
//  OSHITests
//
//  LIVE end-to-end test: two users (Alice + Bob), each with their own real
//  X25519 identity, exchanging messages and a file THROUGH THE PRODUCTION VPS
//  (oshi-messenger.com) using the app's OWN crypto — `EnvelopeCrypto` and
//  `OSHICryptoV2` via `@testable import OSHI`. Nothing here re-implements the
//  crypto; a reimplementation would only prove that my copy agrees with itself.
//
//  It walks the exact contract the shipping client speaks:
//    send:    POST /api/pinata/pinning/pinJSONToIPFS   -> {IpfsHash}
//             POST /api/queue/{base64url(recipientKey)}/{cid}
//    receive: GET  /api/pending/{base64url(recipientKey)}?deviceId=…  -> [cid]
//             GET  /api/ipfs/proxy/{cid}                              -> envelope
//    ack:     DELETE /api/received/{base64url(key)}/{cid}?deviceId=…
//
//  ⚠️ These tests hit the REAL production server and need internet. They are
//  intentionally excluded from the default suite by an env guard so CI/offline
//  runs don't fail spuriously. Run them explicitly with:
//    OSHI_LIVE_SERVER_TESTS=1 xcodebuild test -project OSHI.xcodeproj \
//      -scheme Genesis -destination 'id=<sim-udid>' \
//      -only-testing:OSHITests/TwoUserServerDeliveryTests
//  and CHECK the "Executed N tests" line — a filter that matches nothing still
//  prints "** TEST SUCCEEDED **" while executing 0 tests.
//

import XCTest
import CryptoKit
@testable import OSHI

final class TwoUserServerDeliveryTests: XCTestCase {

    private let vps = "https://oshi-messenger.com"

    /// Live-network tests are opt-in: without the env var they skip rather than
    /// fail, so an offline/CI run of the full suite stays green and honest.
    private func requireLiveServer() throws {
        guard ProcessInfo.processInfo.environment["OSHI_LIVE_SERVER_TESTS"] == "1" else {
            throw XCTSkip("Set OSHI_LIVE_SERVER_TESTS=1 to run live-server tests")
        }
    }

    // MARK: - The client's URL-key encoding (mirrors MessageManager.base64urlEncode)

    private func base64url(_ b64: String) -> String {
        b64.replacingOccurrences(of: "+", with: "-")
           .replacingOccurrences(of: "/", with: "_")
           .replacingOccurrences(of: "=", with: "")
    }

    // MARK: - A user: a real X25519 identity, exactly like IdentityManager's

    private struct User {
        let priv: Curve25519.KeyAgreement.PrivateKey
        let pub: String          // base64, the public identity string peers exchange
        let deviceId: String

        init(deviceId: String) {
            self.init(priv: Curve25519.KeyAgreement.PrivateKey(), deviceId: deviceId)
        }

        /// A second device for an EXISTING identity: same keypair, new deviceId.
        /// Must reuse the private key — generating a fresh one here would silently
        /// turn the multi-device test into a two-different-users test that proves nothing.
        init(priv: Curve25519.KeyAgreement.PrivateKey, deviceId: String) {
            self.priv = priv
            self.pub = priv.publicKey.rawRepresentation.base64EncodedString()
            self.deviceId = deviceId
        }

        /// Same static-static ECDH as IdentityManager.computeSharedSecret(with:)
        /// — raw 32-byte X25519 output, un-HKDF'd (EnvelopeCrypto.deriveKey salts it).
        func sharedSecret(with peerPub: String) throws -> Data {
            let raw = Data(base64Encoded: peerPub)!
            let pk = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: raw)
            return try priv.sharedSecretFromKeyAgreement(with: pk).withUnsafeBytes { Data($0) }
        }
    }

    // MARK: - HTTP helpers

    @discardableResult
    private func http(_ method: String, _ url: String, body: Data? = nil,
                      contentType: String? = nil) async throws -> (Int, Data) {
        var r = URLRequest(url: URL(string: url)!)
        r.httpMethod = method
        r.timeoutInterval = 60
        if let body { r.httpBody = body }
        if let contentType { r.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        let (d, resp) = try await URLSession.shared.data(for: r)
        return ((resp as? HTTPURLResponse)?.statusCode ?? -1, d)
    }

    /// POST /api/pinata/pinning/pinJSONToIPFS -> CID
    private func pinJSON(_ obj: Data) async throws -> String {
        let (code, data) = try await http("POST", "\(vps)/api/pinata/pinning/pinJSONToIPFS",
                                          body: obj, contentType: "application/json")
        XCTAssertEqual(code, 200, "pinJSON failed: \(String(data: data, encoding: .utf8) ?? "")")
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        return json["IpfsHash"] as! String
    }

    /// POST /api/pinata/pinning/pinFileToIPFS (multipart) -> CID
    private func pinFile(_ bytes: Data, filename: String) async throws -> String {
        let boundary = "Boundary-\(UUID().uuidString)"
        var body = Data()
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"\(filename)\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: application/octet-stream\r\n\r\n".data(using: .utf8)!)
        body.append(bytes)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        let (code, data) = try await http("POST", "\(vps)/api/pinata/pinning/pinFileToIPFS",
                                          body: body,
                                          contentType: "multipart/form-data; boundary=\(boundary)")
        XCTAssertEqual(code, 200, "pinFile failed: \(String(data: data, encoding: .utf8) ?? "")")
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        return json["IpfsHash"] as! String
    }

    private func queue(cid: String, to recipientPub: String) async throws {
        let (code, _) = try await http("POST", "\(vps)/api/queue/\(base64url(recipientPub))/\(cid)")
        XCTAssertEqual(code, 200, "enqueue failed")
    }

    private func pending(for user: User) async throws -> [String] {
        let (code, data) = try await http(
            "GET", "\(vps)/api/pending/\(base64url(user.pub))?deviceId=\(user.deviceId)")
        XCTAssertEqual(code, 200, "pending failed")
        return (try? JSONSerialization.jsonObject(with: data) as? [String]) as? [String] ?? []
    }

    /// GET /api/ipfs/proxy/{cid} — the ONLY fetch route left after the dead
    /// public-gateway fallbacks were removed (2026-07-17). No safety net: if this
    /// route regresses, this test fails, which is the point.
    private func fetchBlob(_ cid: String) async throws -> Data {
        let (code, data) = try await http("GET", "\(vps)/api/ipfs/proxy/\(cid)")
        XCTAssertEqual(code, 200, "proxy fetch failed for \(cid)")
        return data
    }

    private func ack(cid: String, as user: User) async throws {
        let (code, _) = try await http(
            "DELETE", "\(vps)/api/received/\(base64url(user.pub))/\(cid)?deviceId=\(user.deviceId)")
        XCTAssertEqual(code, 200, "ack failed")
    }

    // MARK: - One real message, one direction, end to end

    /// Encrypt with the app's EnvelopeCrypto, ship it through the VPS, and decrypt
    /// on the far side. Returns the plaintext the recipient actually recovered.
    private func deliver(from sender: User, to recipient: User, content: String) async throws -> String {
        let secret = try sender.sharedSecret(with: recipient.pub)
        let payload = MessagePayload.forMessage(id: UUID().uuidString,
                                                senderKey: sender.pub,
                                                content: content)
        let envelope = try EnvelopeCrypto.encrypt(payload: payload, sharedSecret: secret)

        let cid = try await pinJSON(try JSONEncoder().encode(envelope))
        try await queue(cid: cid, to: recipient.pub)

        let inbox = try await pending(for: recipient)
        XCTAssertTrue(inbox.contains(cid), "recipient's queue is missing the cid we just sent")

        let blob = try await fetchBlob(cid)
        let received = try JSONDecoder().decode(EncryptedEnvelope.self, from: blob)

        // The recipient derives the SAME secret from their own private key.
        let recipientSecret = try recipient.sharedSecret(with: sender.pub)
        XCTAssertEqual(secret, recipientSecret, "ECDH disagreed between the two peers")

        let out = try EnvelopeCrypto.decrypt(envelope: received, sharedSecret: recipientSecret)

        try await ack(cid: cid, as: recipient)
        let after = try await pending(for: recipient)
        XCTAssertFalse(after.contains(cid), "cid still pending after ack — would redeliver forever")

        return out.content
    }

    // MARK: - Tests

    /// Alice -> Bob and Bob -> Alice, both decrypting to the exact text sent.
    func testTwoUsersExchangeMessagesBothDirections() async throws {
        try requireLiveServer()
        let alice = User(deviceId: "alice-\(UUID().uuidString.prefix(8))")
        let bob   = User(deviceId: "bob-\(UUID().uuidString.prefix(8))")

        // NOTE: XCTAssert* take an @autoclosure, which cannot contain `await` —
        // every awaited value must be bound to a local first.
        let aliceSays = "Alice → Bob: приве́т 🕵️ \(UUID().uuidString)"
        let bobGot = try await deliver(from: alice, to: bob, content: aliceSays)
        XCTAssertEqual(bobGot, aliceSays, "Bob did not recover Alice's plaintext")

        let bobSays = "Bob → Alice: reply ✅ \(UUID().uuidString)"
        let aliceGot = try await deliver(from: bob, to: alice, content: bobSays)
        XCTAssertEqual(aliceGot, bobSays, "Alice did not recover Bob's plaintext")
    }

    /// A third party holding the ciphertext must NOT be able to read it.
    /// Without this, "it round-tripped" would also pass for plaintext.
    func testEavesdropperCannotDecrypt() async throws {
        try requireLiveServer()
        let alice = User(deviceId: "a-\(UUID().uuidString.prefix(8))")
        let bob   = User(deviceId: "b-\(UUID().uuidString.prefix(8))")
        let eve   = User(deviceId: "e-\(UUID().uuidString.prefix(8))")

        let secret = try alice.sharedSecret(with: bob.pub)
        let payload = MessagePayload.forMessage(id: UUID().uuidString,
                                                senderKey: alice.pub,
                                                content: "secret-\(UUID().uuidString)")
        let envelope = try EnvelopeCrypto.encrypt(payload: payload, sharedSecret: secret)
        let cid = try await pinJSON(try JSONEncoder().encode(envelope))
        let blob = try await fetchBlob(cid)
        let received = try JSONDecoder().decode(EncryptedEnvelope.self, from: blob)

        // Eve pulls the blob off the server and tries her own ECDH with Alice.
        let eveSecret = try eve.sharedSecret(with: alice.pub)
        XCTAssertThrowsError(try EnvelopeCrypto.decrypt(envelope: received, sharedSecret: eveSecret),
                            "an eavesdropper decrypted the envelope — this is not E2EE")
    }

    /// A real file: encrypt with the app's file crypto, ship the ciphertext through
    /// the VPS, pull it back, decrypt, and require byte-for-byte equality.
    func testFileTransferRoundTripsByteExact() async throws {
        try requireLiveServer()
        let original = Data((0..<(3 * 1024 * 1024)).map { _ in UInt8.random(in: 0...255) })
        let enc = try OSHICryptoV2.encryptFile(bytes: original)

        // Ship every chunk + the manifest through the server, as the app would.
        var cids: [String] = []
        for (i, chunk) in enc.chunks.enumerated() {
            cids.append(try await pinFile(chunk, filename: "chunk\(i).bin"))
        }
        let manifestCid = try await pinFile(enc.manifest, filename: "manifest.bin")

        var fetched: [Data] = []
        for cid in cids { fetched.append(try await fetchBlob(cid)) }
        let manifestBack = try await fetchBlob(manifestCid)

        XCTAssertEqual(fetched, enc.chunks, "a chunk came back from the server altered")
        XCTAssertEqual(manifestBack, enc.manifest, "manifest came back altered")

        let (out, _) = try OSHICryptoV2.decryptFile(fileKey: enc.fileKey,
                                                    fileNonce: enc.fileNonce,
                                                    chunks: fetched,
                                                    manifest: manifestBack)
        XCTAssertEqual(SHA256.hash(data: out).map { $0 }, SHA256.hash(data: original).map { $0 },
                       "file did not survive the round trip byte-exact")
    }

    /// Two devices on the SAME account each get their own copy: one device acking
    /// must not delete the message out from under the other.
    func testMultiDeviceAckIsPerDevice() async throws {
        try requireLiveServer()
        let alice  = User(deviceId: "sender")
        let bobP   = User(deviceId: "bob-phone")
        // Same identity/public key as bobP, different deviceId — Bob's second device.
        let bobPad = User(priv: bobP.priv, deviceId: "bob-pad")

        let secret = try alice.sharedSecret(with: bobP.pub)
        let payload = MessagePayload.forMessage(id: UUID().uuidString,
                                                senderKey: alice.pub, content: "multi-device")
        let envelope = try EnvelopeCrypto.encrypt(payload: payload, sharedSecret: secret)
        let cid = try await pinJSON(try JSONEncoder().encode(envelope))
        try await queue(cid: cid, to: bobP.pub)

        let phoneSees = try await pending(for: bobP).contains(cid)
        let padSees = try await pending(for: bobPad).contains(cid)
        XCTAssertTrue(phoneSees, "phone never saw it")
        XCTAssertTrue(padSees, "pad never saw it")

        try await ack(cid: cid, as: bobP)
        let phoneAfter = try await pending(for: bobP).contains(cid)
        let padAfter = try await pending(for: bobPad).contains(cid)
        XCTAssertFalse(phoneAfter, "phone still pending after ack")
        XCTAssertTrue(padAfter,
                      "pad LOST the message because the phone acked it — multi-device data loss")

        try await ack(cid: cid, as: bobPad)
        let padFinal = try await pending(for: bobPad).contains(cid)
        XCTAssertFalse(padFinal, "pad still pending after ack")
    }
}
