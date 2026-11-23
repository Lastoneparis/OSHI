//
//  MeshPeerAuthTests.swift
//  OSHITests
//
//  Pure-crypto tests for the mesh proof-of-possession challenge-response
//  (see OSHI/MeshPeerAuth.swift). These cover the wire-invisible invariants:
//
//    1. Round-trip: a peer holding K's private half produces a tag we accept.
//    2. Wrong key fails: a peer using a DIFFERENT private key cannot forge a
//       tag we accept — this is the whole security property.
//    3. Cross-platform vector: a FIXED (keys + nonce) → tag must match the exact
//       bytes the Android JVM test also pins (MeshPeerAuthTest.kt). If iOS and
//       Android ever disagree on X25519/HMAC framing, one of these two tests
//       goes red instead of the mismatch only surfacing on two physical devices.
//
//  NOTE: this exercises the crypto only. LIVE mesh verification needs two
//  physical devices — BLE / MultipeerConnectivity does not run in the simulator.
//

import XCTest
import CryptoKit
@testable import OSHI

final class MeshPeerAuthTests: XCTestCase {

    // Derive a raw X25519 public key from a fixed raw private key (test helper).
    private func pub(from priv: Data) throws -> Data {
        let key = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: priv)
        return key.publicKey.rawRepresentation
    }

    func testChallengeResponseRoundTrip() throws {
        let alicePriv = Data(SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) })
        let bobPriv = Data(SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) })
        let alicePub = try pub(from: alicePriv)
        let bobPub = try pub(from: bobPriv)

        // Alice challenges Bob with a nonce; Bob answers with his DH(priv, alicePub).
        let nonce = MeshPeerAuth.makeNonce()
        XCTAssertEqual(nonce.count, MeshPeerAuth.nonceLength)

        let bobSecret = try MeshPeerAuth.sharedSecret(myPrivateKeyRaw: bobPriv, peerPublicKeyRaw: alicePub)
        let tag = MeshPeerAuth.tag(sharedSecret: bobSecret, nonce: nonce)

        // Alice verifies with DH(alicePriv, bobPub) — symmetry means it matches.
        let aliceSecret = try MeshPeerAuth.sharedSecret(myPrivateKeyRaw: alicePriv, peerPublicKeyRaw: bobPub)
        XCTAssertEqual(aliceSecret, bobSecret, "X25519 DH must be symmetric")
        XCTAssertTrue(MeshPeerAuth.verify(tag: tag, sharedSecret: aliceSecret, nonce: nonce))
    }

    func testWrongKeyFailsVerification() throws {
        let alicePriv = Data(SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) })
        let bobPriv = Data(SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) })
        let malloryPriv = Data(SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) })
        let alicePub = try pub(from: alicePriv)
        let bobPub = try pub(from: bobPriv)

        let nonce = MeshPeerAuth.makeNonce()

        // Mallory announces Bob's key but signs with her own private key.
        let mallorySecret = try MeshPeerAuth.sharedSecret(myPrivateKeyRaw: malloryPriv, peerPublicKeyRaw: alicePub)
        let forgedTag = MeshPeerAuth.tag(sharedSecret: mallorySecret, nonce: nonce)

        // Alice verifies against the key Mallory CLAIMED (Bob's). Must fail.
        let aliceSecret = try MeshPeerAuth.sharedSecret(myPrivateKeyRaw: alicePriv, peerPublicKeyRaw: bobPub)
        XCTAssertFalse(MeshPeerAuth.verify(tag: forgedTag, sharedSecret: aliceSecret, nonce: nonce),
                       "A peer not holding the announced key's private half must not verify")
    }

    /// FIXED cross-platform vector. Must stay byte-identical to Android's
    /// MeshPeerAuthTest.kt `testCrossPlatformVector`.
    func testCrossPlatformVector() throws {
        // A_priv = 01..20, B_priv = 21..40, nonce = 0xAB * 32.
        let aPriv = Data((1...32).map { UInt8($0) })
        let bPub = Data(hex: "5869aff450549732cbaaed5e5df9b30a6da31cb0e5742bad5ad4a1a768f1a67b")
        let nonce = Data(repeating: 0xAB, count: 32)
        let expectedShared = "a84dc7c3c8f058b1b2dc4cd1e9b5dc0a7987f88b6a9564cde3391fc421159e77"
        let expectedTag = "d31d2641e11190fd13c4ae33c41eaad7356164f3dd4a602eb5cf36a9ade6ee1b"

        let secret = try MeshPeerAuth.sharedSecret(myPrivateKeyRaw: aPriv, peerPublicKeyRaw: bPub)
        XCTAssertEqual(secret.map { String(format: "%02x", $0) }.joined(), expectedShared)

        let tag = MeshPeerAuth.tag(sharedSecret: secret, nonce: nonce)
        XCTAssertEqual(tag.map { String(format: "%02x", $0) }.joined(), expectedTag)
    }

    func testConstantTimeEqualsLengthMismatch() {
        XCTAssertFalse(MeshPeerAuth.constantTimeEquals(Data([1, 2, 3]), Data([1, 2])))
        XCTAssertTrue(MeshPeerAuth.constantTimeEquals(Data([1, 2, 3]), Data([1, 2, 3])))
    }
}
