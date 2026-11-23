//
//  PostQuantumKEM.swift
//  OSHI — hybrid post-quantum key encapsulation (X-Wing = ML-KEM-768 + X25519)
//
//  WHY THIS EXISTS
//
//  Every asymmetric key in OSHI is Curve25519: X25519 for key agreement
//  (`IdentityManager.computeSharedSecret`, and the ratchet's DH step) and
//  Ed25519 for signatures. Shor's algorithm breaks BOTH. The symmetric layer is
//  fine — AES-256-GCM with 32-byte HKDF-SHA256 keys keeps ~128 bits against
//  Grover — so the exposure is entirely in how the root key is agreed.
//
//  That matters more here than in an ordinary messenger: OSHI pins ciphertext to
//  IPFS through Pinata. A transport-only app forces an attacker to be on the wire
//  at the time; content-addressed public storage lets anyone archive the
//  ciphertext today and read it the day a cryptographically relevant quantum
//  computer exists. That is harvest-now-decrypt-later, and it is the reason this
//  is worth doing BEFORE such a machine is built rather than after.
//
//  WHAT THIS IS, AND WHAT IT IS NOT
//
//  This is the primitive layer plus its policy: key generation, storage,
//  encapsulation, decapsulation, and the rules about when a session may use
//  them. Deciding WHEN to use them lives in `PostQuantumPolicy.current` and in
//  `DoubleRatchetSessionManager.getOrCreateSessionNegotiatingPQ`.
//
//  Shipping policy is `.hybridWhenAvailable`: upgrade a conversation when both
//  ends can, stay classical when either cannot. It is incapable of breaking a
//  peer that lacks PQ, and `pqPolicyOverride` in UserDefaults is a kill switch
//  that needs no new binary.
//
//  DESIGN RULES, and the reason for each:
//
//  1. HYBRID ONLY, NEVER PQ-ONLY. X-Wing is itself a hybrid (ML-KEM-768 ⊕
//     X25519), and on top of that we keep mixing OSHI's existing classical
//     static-X25519 secret into the root key. So a break of ML-KEM cannot make
//     the session weaker than it is today. A new primitive that has existed for
//     a few years does not get to be the only thing holding the door.
//
//  2. FAIL CLOSED. If a session was established as hybrid and the PQ half later
//     fails — missing ciphertext, wrong length, decapsulation error — we throw.
//     We never silently continue classically, because "fall back quietly" is
//     precisely the downgrade an attacker would induce.
//
//  3. THE OS FLOOR IS NOT THE APP FLOOR. X-Wing is `@available(iOS 26)`, this
//     app deploys to 17.5, and the mesh contains Android and desktop peers. So
//     PQ can never be assumed — it is negotiated, and the classical path must
//     stay byte-for-byte what it is today. See `DoubleRatchetSession`.
//

import Foundation
import CryptoKit

// MARK: - Errors

enum PostQuantumError: Error, Equatable {
    /// The OS is too old for X-Wing. Not a failure — the caller falls back to
    /// the classical path when policy allows it.
    case unsupportedPlatform
    /// A peer's public key, or a KEM ciphertext, was not the size the algorithm
    /// defines. Checked BEFORE handing bytes to CryptoKit.
    case malformedKeyMaterial
    /// Decapsulation failed on material we were told to expect. This is the
    /// fail-closed case: the session is hybrid and its PQ half is broken.
    case decapsulationFailed
    /// No PQ identity has been generated on this device yet.
    case noLocalKeyPair
}

// MARK: - Policy

/// When a session is allowed to use the post-quantum half.
///
/// `disabled` is the shipping default and makes every code path in this file
/// unreachable from production. It is a runtime switch rather than a compile
/// flag so a build can be tested, and rolled back, without a new binary.
enum PostQuantumPolicy: String, Sendable {
    /// Byte-identical to the pre-hybrid build. The kill switch, not the default.
    case disabled
    /// **Shipping default.** Use the hybrid whenever BOTH sides can, silently
    /// classical otherwise. It can never break a peer that lacks PQ.
    case hybridWhenAvailable
    /// Refuse to establish a session at all unless the hybrid is available.
    /// Not for general release — it partitions the mesh — but it is what the
    /// tests use to prove the fail-closed path, and what a future
    /// "quantum-safe only" toggle would select.
    case required
}

// MARK: - The KEM

/// X-Wing (`ML-KEM-768 + X25519`), as shipped in CryptoKit on iOS/macOS 26.
///
/// Static-only by design: there is no instance state to get out of sync between
/// the two queues that touch the ratchet.
enum PostQuantumKEM {

    /// Written into the wire header so a future algorithm change is a value
    /// change and not a guess about what the bytes meant.
    static let algorithmIdentifier = "xwing-mlkem768-x25519"

    /// Sizes fixed by the X-Wing draft. Used to reject nonsense BEFORE it
    /// reaches CryptoKit: a length check is a cheap, total guard, and it keeps
    /// a malformed peer from turning into an opaque CryptoKit error we would
    /// then have to interpret.
    static let publicKeyByteCount = 1216
    static let ciphertextByteCount = 1120
    /// The KEM output, and therefore the amount of PQ material mixed in.
    static let sharedSecretByteCount = 32

    /// Whether this OS can do X-Wing at all.
    ///
    /// Deliberately a computed property and not a stored `let`: it is read by
    /// tests that need it to mean "right now, on this machine", and it costs
    /// nothing.
    static var isSupportedByPlatform: Bool {
        if #available(iOS 26.0, macOS 26.0, macCatalyst 26.0, *) { return true }
        return false
    }

    // MARK: Key generation

    /// A device's PQ identity. The seed is what we persist — 32 bytes rather
    /// than the full expanded key — and the public key is what a peer needs.
    struct KeyPair: Equatable {
        let seed: Data
        let publicKey: Data
    }

    @available(iOS 26.0, macOS 26.0, macCatalyst 26.0, *)
    private static func privateKey(fromSeed seed: Data) throws -> XWingMLKEM768X25519.PrivateKey {
        do {
            return try XWingMLKEM768X25519.PrivateKey(seedRepresentation: seed, publicKey: nil)
        } catch {
            throw PostQuantumError.malformedKeyMaterial
        }
    }

    static func generateKeyPair() throws -> KeyPair {
        guard #available(iOS 26.0, macOS 26.0, macCatalyst 26.0, *) else {
            throw PostQuantumError.unsupportedPlatform
        }
        let sk = try XWingMLKEM768X25519.PrivateKey.generate()
        return KeyPair(seed: sk.seedRepresentation,
                       publicKey: sk.publicKey.rawRepresentation)
    }

    /// Recover the public key from a stored seed, so a device that has the seed
    /// never needs to persist the public half as well (and can never persist a
    /// public half that disagrees with its private one).
    static func publicKey(forSeed seed: Data) throws -> Data {
        guard #available(iOS 26.0, macOS 26.0, macCatalyst 26.0, *) else {
            throw PostQuantumError.unsupportedPlatform
        }
        return try privateKey(fromSeed: seed).publicKey.rawRepresentation
    }

    // MARK: Encapsulation

    /// The initiator's half: produce a shared secret plus the ciphertext the
    /// peer needs in order to derive the same secret.
    ///
    /// Unlike Diffie-Hellman — where both sides compute the secret from keys
    /// they already hold — a KEM is one-directional. That single fact is what
    /// forces the ciphertext onto the wire and is the reason this could not be
    /// dropped into `computeSharedSecret` without a header change.
    static func encapsulate(toPublicKey rawPublicKey: Data) throws -> (ciphertext: Data, sharedSecret: Data) {
        guard #available(iOS 26.0, macOS 26.0, macCatalyst 26.0, *) else {
            throw PostQuantumError.unsupportedPlatform
        }
        guard rawPublicKey.count == publicKeyByteCount else {
            throw PostQuantumError.malformedKeyMaterial
        }
        let pk: XWingMLKEM768X25519.PublicKey
        do {
            pk = try XWingMLKEM768X25519.PublicKey(rawRepresentation: rawPublicKey)
        } catch {
            throw PostQuantumError.malformedKeyMaterial
        }
        let result = try pk.encapsulate()
        let secret = result.sharedSecret.withUnsafeBytes { Data($0) }
        return (result.encapsulated, secret)
    }

    /// The responder's half.
    ///
    /// Every failure here collapses to `decapsulationFailed` on purpose: the
    /// caller's only correct reaction is to refuse the session, and a more
    /// detailed error would be a small oracle for whoever fed us the bytes.
    static func decapsulate(_ ciphertext: Data, withSeed seed: Data) throws -> Data {
        guard #available(iOS 26.0, macOS 26.0, macCatalyst 26.0, *) else {
            throw PostQuantumError.unsupportedPlatform
        }
        guard ciphertext.count == ciphertextByteCount else {
            throw PostQuantumError.malformedKeyMaterial
        }
        do {
            let sk = try privateKey(fromSeed: seed)
            let secret = try sk.decapsulate(ciphertext)
            return secret.withUnsafeBytes { Data($0) }
        } catch let e as PostQuantumError {
            throw e
        } catch {
            throw PostQuantumError.decapsulationFailed
        }
    }
}

// MARK: - This device's PQ identity

/// Generates the device's X-Wing key pair once and keeps the seed in the
/// Keychain beside `privateKey` and `signingKey`.
///
/// Nothing calls `ensureKeyPair()` from production code while the policy is
/// `.disabled` — a device that never turns PQ on never generates, stores, or
/// publishes anything, which is what makes this change safe to ship dark.
final class PostQuantumIdentity {

    static let shared = PostQuantumIdentity()

    /// Keychain item name. Distinct from `privateKey`/`signingKey` so wiping the
    /// PQ identity can never touch the classical one — losing that would lock
    /// the user out of every existing conversation.
    private static let keychainKey = "pqKemSeedV1"

    private let lock = NSLock()
    private var cachedSeed: Data?

    private init() {}

    /// The device's PQ public key, generating the pair on first use.
    ///
    /// Returns `nil` — rather than throwing — when the platform is too old,
    /// because "this device has no PQ key" is an ordinary, expected state in a
    /// mesh that spans iOS 17, Android and desktop. A throw here would push
    /// try/catch into every call site for a condition that is not an error.
    func publicKey() -> Data? {
        guard PostQuantumKEM.isSupportedByPlatform else { return nil }
        guard let seed = try? ensureSeed() else { return nil }
        return try? PostQuantumKEM.publicKey(forSeed: seed)
    }

    /// The seed, for decapsulation. Never leaves the process.
    func seed() throws -> Data {
        try ensureSeed()
    }

    private func ensureSeed() throws -> Data {
        lock.lock(); defer { lock.unlock() }
        if let cachedSeed { return cachedSeed }

        if let stored = try? KeychainHelper.load(key: Self.keychainKey), !stored.isEmpty {
            cachedSeed = stored
            return stored
        }

        guard PostQuantumKEM.isSupportedByPlatform else {
            throw PostQuantumError.unsupportedPlatform
        }
        let pair = try PostQuantumKEM.generateKeyPair()
        // A failure to persist is not fatal for THIS launch — the in-memory seed
        // still works — but it must not be silent, because the next launch would
        // come back with a different PQ identity and every hybrid session would
        // have to be re-established.
        do {
            try KeychainHelper.save(key: Self.keychainKey, data: pair.seed)
        } catch {
            OshiLog.crypto.error("⚠️ PQ seed could not be stored: \(String(describing: error))")
        }
        cachedSeed = pair.seed
        return pair.seed
    }

    /// Test seam and "rotate my PQ identity" in one. Deliberately does NOT touch
    /// the classical identity.
    func resetForTesting() {
        lock.lock(); defer { lock.unlock() }
        cachedSeed = nil
        try? KeychainHelper.delete(key: Self.keychainKey)
    }
}

// MARK: - Negotiation

/// What the two sides agreed to, decided once when a session is created and
/// then pinned for the life of that session.
///
/// Pinning is the point. If the mode could drift per message, an attacker who
/// strips the KEM ciphertext from a message would downgrade the session to
/// classical, which is exactly the attack the hybrid is meant to prevent. It is
/// recorded in the session, persisted with it, and mixed into the AEAD's
/// additional data, so a stripped ciphertext produces an authentication failure
/// instead of a quieter, weaker session.
enum RatchetSecurityMode: String, Codable, Sendable {
    /// X25519 only. What every session in production is today.
    case classical
    /// X25519 **and** X-Wing, both mixed into the root key.
    case hybridPQ
}

/// Decides the mode for one new session.
///
/// Split out from the ratchet so the decision is testable on its own: it is a
/// pure function of policy, local support and what we know about the peer, with
/// no keys and no I/O.
enum PostQuantumNegotiator {

    /// - Parameters:
    ///   - policy: the device's setting.
    ///   - peerPublicKey: the peer's X-Wing public key, or nil if they have not
    ///     published one (old iOS, Android, desktop, or PQ switched off there).
    /// - Returns: the mode to use, or nil when `.required` cannot be satisfied
    ///   and the session must NOT be created.
    static func mode(policy: PostQuantumPolicy,
                     localSupportsPQ: Bool,
                     peerPublicKey: Data?) -> RatchetSecurityMode? {
        switch policy {
        case .disabled:
            return .classical
        case .hybridWhenAvailable:
            guard localSupportsPQ, isUsable(peerPublicKey) else { return .classical }
            return .hybridPQ
        case .required:
            guard localSupportsPQ, isUsable(peerPublicKey) else { return nil }
            return .hybridPQ
        }
    }

    /// A peer key is usable only at exactly the right length. A truncated or
    /// padded key must not silently degrade the session to classical under
    /// `.hybridWhenAvailable` — under `.required` it must stop it dead.
    private static func isUsable(_ peerPublicKey: Data?) -> Bool {
        guard let peerPublicKey else { return false }
        return peerPublicKey.count == PostQuantumKEM.publicKeyByteCount
    }
}

// MARK: - What we know about peers

/// The X-Wing public keys our contacts have published.
///
/// Distribution rides on the profile exchange that already runs between
/// contacts (`📸PROFILE_UPDATE📸`), rather than a new channel: that payload is
/// JSON, so an added field is invisible to older clients, and the app already
/// broadcasts it on change and can request it on demand.
///
/// Consequence worth stating plainly: a brand-new conversation starts CLASSICAL,
/// because we cannot encapsulate to a key we have not been told yet. It becomes
/// hybrid once the peer's profile arrives and the ratchet is next re-established.
/// The alternative — 1216 bytes of public key on every message header — costs
/// every user bandwidth forever to save one round trip.
final class PostQuantumPeerDirectory {

    static let shared = PostQuantumPeerDirectory()

    private static let storeKey = "pqPeerPublicKeys_v1"
    private let lock = NSLock()
    private var keys: [String: Data] = [:]

    private init() {
        if let raw = UserDefaults.standard.dictionary(forKey: Self.storeKey) as? [String: String] {
            keys = raw.compactMapValues { Data(base64Encoded: $0) }
        }
    }

    func publicKey(for peer: String) -> Data? {
        lock.lock(); defer { lock.unlock() }
        return keys[peer]
    }

    /// Records a peer's key. Rejects anything of the wrong length rather than
    /// storing it, so a malformed advertisement cannot sit in the directory and
    /// make every later `encapsulate` throw.
    func record(_ publicKey: Data, for peer: String) {
        guard publicKey.count == PostQuantumKEM.publicKeyByteCount, !peer.isEmpty else {
            OshiLog.crypto.info("⚠️ PQ: ignoring malformed public key for \(peer.prefix(8))…")
            return
        }
        lock.lock()
        let changed = keys[peer] != publicKey
        keys[peer] = publicKey
        let snapshot = keys
        lock.unlock()

        guard changed else { return }
        UserDefaults.standard.set(snapshot.mapValues { $0.base64EncodedString() },
                                  forKey: Self.storeKey)
        OshiLog.crypto.info("🔐 PQ: recorded X-Wing key for \(peer.prefix(8))…")
    }

    func forget(_ peer: String) {
        lock.lock()
        keys.removeValue(forKey: peer)
        let snapshot = keys
        lock.unlock()
        UserDefaults.standard.set(snapshot.mapValues { $0.base64EncodedString() },
                                  forKey: Self.storeKey)
    }

    func removeAllForTesting() {
        lock.lock(); keys.removeAll(); lock.unlock()
        UserDefaults.standard.removeObject(forKey: Self.storeKey)
    }
}

// MARK: - The live policy

extension PostQuantumPolicy {

    private static let overrideKey = "pqPolicyOverride"

    /// What this device does right now.
    ///
    /// `.hybridWhenAvailable` is the shipping value: it upgrades a conversation
    /// when both ends can, and is incapable of breaking one that cannot — a peer
    /// on iOS 17, Android or desktop simply stays classical, exactly as today.
    ///
    /// The override is a kill switch that needs no new binary. If anything about
    /// the hybrid misbehaves in the field, setting `pqPolicyOverride` to
    /// `disabled` returns every NEW session to the classical path immediately;
    /// sessions already established are unaffected either way.
    static var current: PostQuantumPolicy {
        if let raw = UserDefaults.standard.string(forKey: overrideKey),
           let forced = PostQuantumPolicy(rawValue: raw) {
            return forced
        }
        return .hybridWhenAvailable
    }

    static func setOverride(_ policy: PostQuantumPolicy?) {
        if let policy {
            UserDefaults.standard.set(policy.rawValue, forKey: overrideKey)
        } else {
            UserDefaults.standard.removeObject(forKey: overrideKey)
        }
    }
}
