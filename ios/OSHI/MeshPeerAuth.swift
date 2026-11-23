//
//  MeshPeerAuth.swift
//  OSHI
//
//  Proof-of-possession PEER AUTHENTICATION for the BLE / MultipeerConnectivity
//  transport mesh (iOS↔iOS via MPC, iOS↔Android via CrossPlatformMesh).
//
//  ── WHY THIS EXISTS ─────────────────────────────────────────────────────────
//  Both mesh transports learn a peer's X25519 identity key K by simply BELIEVING
//  the key the peer announces (TOFU). Nothing proves the announcing device holds
//  K's private half, so a nearby attacker can announce a contact's key and poison
//  routing / impersonate at the TRANSPORT layer.
//
//  THREAT MODEL — be precise: message CONTENT is already protected by the Double
//  Ratchet (an impostor who cannot decrypt cannot read or forge a message that
//  decrypts). This gap is therefore ROUTING-POISONING / DoS / metadata, NOT a
//  content-confidentiality break. Peer authentication here is DEFENSE-IN-DEPTH.
//
//  ── THE PROOF (DH challenge-response) ───────────────────────────────────────
//  The announced key K is the peer's X25519 identity (= their mesh address).
//    1. On learning K we send a random 32-byte nonce N (the CHALLENGE).
//    2. The peer replies tag = HMAC-SHA256( sharedSecret, N ) where
//         sharedSecret = DH(their_private, our_public).
//       By X25519 symmetry this equals DH(our_private, K), so ONLY a device that
//       holds K's private half can produce it.
//    3. We recompute HMAC( DH(our_private, K), N ) and compare constant-time.
//  A match sets a per-peer `verified` signal. No new crypto is invented — this
//  reuses the app's existing X25519 (CryptoKit / IdentityManager) and the shared
//  HMAC-SHA256 primitive in OSHICryptoV2.
//
//  ── ADDITIVE / ADVISORY ONLY (non-negotiable) ───────────────────────────────
//  The result is a `verified: Bool` SIGNAL and nothing more. We MUST NOT reject,
//  drop or refuse delivery to a peer that fails or ignores the challenge: Android
//  and older iOS builds do not implement it yet, and rejecting them would sever
//  the iOS↔Android mesh (a one-sided wire break — the same class of failure as
//  the v3 ratchet incident). TOFU delivery keeps working for everyone; a future
//  release can enforce once both platforms ship this.
//

import Foundation
import CryptoKit

/// Pure, dependency-light crypto for mesh peer authentication. Every function is
/// deterministic given its inputs (except `makeNonce`), which is what lets the
/// unit tests pin a fixed cross-platform vector that iOS and Android must agree on.
enum MeshPeerAuth {

    /// Challenge nonce size, in bytes. Matches the Android mirror.
    static let nonceLength = 32
    /// HMAC-SHA256 tag size, in bytes.
    static let tagLength = 32

    /// Fresh cryptographically-random challenge nonce.
    static func makeNonce() -> Data {
        var bytes = [UInt8](repeating: 0, count: nonceLength)
        _ = SecRandomCopyBytes(kSecRandomDefault, nonceLength, &bytes)
        return Data(bytes)
    }

    /// Raw X25519 shared secret (no hashing), matching CryptoKit's
    /// `sharedSecretFromKeyAgreement` bytes and BouncyCastle's `X25519Agreement`
    /// output on Android. `myPrivateKeyRaw` / `peerPublicKeyRaw` are 32-byte raw
    /// X25519 representations. Kept here (rather than only calling through
    /// IdentityManager) so the proof can be unit-tested with fixed keys.
    static func sharedSecret(myPrivateKeyRaw: Data, peerPublicKeyRaw: Data) throws -> Data {
        let priv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: myPrivateKeyRaw)
        let pub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerPublicKeyRaw)
        let secret = try priv.sharedSecretFromKeyAgreement(with: pub)
        return secret.withUnsafeBytes { Data($0) }
    }

    /// tag = HMAC-SHA256(sharedSecret, nonce). The shared secret is used directly
    /// as the HMAC key (no HKDF) so the wire value is trivially reproducible on
    /// both platforms and pinnable as a test vector.
    static func tag(sharedSecret: Data, nonce: Data) -> Data {
        return OSHICryptoV2.hmac(key: sharedSecret, data: nonce)
    }

    /// Constant-time verification of a received tag against the expected one.
    static func verify(tag received: Data, sharedSecret: Data, nonce: Data) -> Bool {
        let expected = tag(sharedSecret: sharedSecret, nonce: nonce)
        return constantTimeEquals(received, expected)
    }

    /// Length-checked, constant-time byte comparison. Avoids leaking, via timing,
    /// how many leading bytes of a forged tag happened to be correct.
    static func constantTimeEquals(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<a.count {
            diff |= a[i] ^ b[i]
        }
        return diff == 0
    }
}
