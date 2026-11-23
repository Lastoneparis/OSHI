//
//  GroupEnvelopeV3.swift
//  OSHI — group messages with no group key (CV-002)
//
//  WHAT WAS WRONG
//
//  The legacy group path encrypted every message with
//  `SHA256(groupId.uppercased() + "oshi-group-envelope-key")`. That is a pure
//  function of the group id and a constant compiled into every copy of the app:
//  no secret, no distribution, nothing a member holds that a stranger does not.
//  Group ids travel in cleartext in the `group_update` objects pinned to public
//  IPFS, and the message pins carry a 4-character `hint` of the same id — so
//  collecting the pins was enough to derive the key and read the group.
//
//  WHAT REPLACES IT, AND WHY THIS SHAPE
//
//  `V2GroupSession` already had the right answer for the relay path: a group has
//  NO key. A send fans out one Double-Ratchet copy per member over that member's
//  1:1 session. This brings the same model to the offline/mesh path — which is
//  the one that cannot simply be retired, because it is how members the relay
//  cannot reach are reached at all.
//
//  The naive translation would encrypt the whole message once per member and pin
//  N objects, turning the Pinata bill into a function of group size. So the body
//  is encrypted ONCE under a fresh random content key, and only that 32-byte key
//  is wrapped per member:
//
//      body   = AES-GCM(contentKey, payload)          ← one copy
//      wraps  = [ ratchet_to_member_i(contentKey) ]   ← 32 bytes each, ratcheted
//
//  One pin per message, exactly as before, and the group key is gone. Because the
//  wraps ride the 1:1 ratchet, they inherit its forward secrecy AND its
//  post-quantum hybrid for free.
//
//  RECIPIENT PRIVACY. A wrap has to be findable by its recipient without being
//  readable by anyone else, so each carries a hint. The hint is
//  `SHA256(memberKey || salt)` with a fresh random salt per envelope, so the same
//  member produces a different hint in every message: the pin does not become a
//  membership list, and two pins cannot be linked to the same group by their
//  hints. The old scheme leaked 4 characters of the group id in the clear; this
//  leaks the member COUNT and nothing else.
//
//  INTEROPERABILITY. `EncryptedEnvelope.decryptGroup` checks `envelope.v ==
//  currentVersion` with a strict equality, so a client that predates this format
//  DROPS a v3 envelope rather than mis-decrypting it. That is a safe failure, but
//  it is still a failure — so v3 is only ever produced for a group when every
//  member is known to understand it. See `GroupEnvelopeV3.canUse`.
//

import Foundation
import CryptoKit

enum GroupEnvelopeV3 {

    /// The wire version, which is **4** even though the type is named V3.
    ///
    /// "V3" is the third GROUP envelope design (v1 legacy, v2 the derived group
    /// key, v3 this). The `v` field, however, is a namespace SHARED with the 1:1
    /// IPFS envelope — and `v: 3` was already taken there for
    /// `{v, envelope, nonce, hint, from, senderPublicKey}`
    /// (`MessageManager.uploadToIPFS`, `VPSClient` on Android).
    ///
    /// The two shapes are different enough that a strict decoder tells them
    /// apart, which is how this went unnoticed. Relying on that is the kind of
    /// accident that breaks the day someone adds a field or switches to a
    /// lenient decode — so the group envelope takes the next free number instead.
    static let version = 4

    enum Failure: Error, Equatable {
        /// No wrap in this envelope is addressed to us. Either we are not a
        /// member, or the sender built it before we joined.
        case noWrapForUs
        /// The wrap was found but the 1:1 ratchet could not open it — the usual
        /// cause is session desync, and the caller must heal rather than retry.
        case wrapUndecryptable
        case malformed
    }

    // MARK: - Wire format

    struct Wrap: Codable, Equatable {
        /// `SHA256(memberPublicKey || salt)`, first 16 bytes, base64. Fresh salt
        /// per envelope, so this is unlinkable across messages.
        let h: String
        /// A `DoubleRatchetMessage` (JSON, base64) whose plaintext is the
        /// base64 content key.
        let k: String
    }

    struct Sealed: Codable, Equatable {
        let v: Int
        /// Random per-envelope salt for the recipient hints.
        let salt: String
        /// AES-GCM(contentKey) over the encoded payload; ciphertext‖tag, base64.
        let body: String
        let nonce: String
        let wraps: [Wrap]
    }

    // MARK: - Hints

    static func hint(forMember memberPublicKey: String, salt: Data) -> String {
        var input = Data(memberPublicKey.utf8)
        input.append(salt)
        return Data(SHA256.hash(data: input).prefix(16)).base64EncodedString()
    }

    // MARK: - Sealing

    /// One member's seam to the ratchet. Injected rather than reached through a
    /// singleton so the tests can drive both ends of a conversation in one
    /// process — and so this file has no opinion about session storage.
    typealias WrapEncryptor = (_ memberPublicKey: String, _ contentKeyB64: String) throws -> Data
    typealias WrapDecryptor = (_ wrappedRatchetMessage: Data) throws -> String

    /// - Parameter members: every member the envelope must be readable by. The
    ///   sender is normally excluded by the caller; including them is harmless.
    static func seal(payload: Data,
                     members: [String],
                     encryptWrap: WrapEncryptor) throws -> Sealed {
        let contentKey = SymmetricKey(size: .bits256)
        let contentKeyB64 = contentKey.withUnsafeBytes { Data($0) }.base64EncodedString()

        let nonce = AES.GCM.Nonce()
        let sealedBody = try AES.GCM.seal(payload, using: contentKey, nonce: nonce)
        var combined = Data()
        combined.append(sealedBody.ciphertext)
        combined.append(sealedBody.tag)

        var salt = Data(count: 16)
        salt.withUnsafeMutableBytes { buf in
            _ = SecRandomCopyBytes(kSecRandomDefault, 16, buf.baseAddress!)
        }

        // A member whose ratchet we cannot reach is SKIPPED, not fatal: one
        // unreachable member must not stop the message for everyone else. They
        // simply cannot read this one, and the caller's heal path picks it up.
        var wraps: [Wrap] = []
        for member in members {
            do {
                let wrapped = try encryptWrap(member, contentKeyB64)
                wraps.append(Wrap(h: hint(forMember: member, salt: salt),
                                  k: wrapped.base64EncodedString()))
            } catch {
                OshiLog.crypto.info("⚠️ group v3: no wrap for \(member.prefix(8))… (\(String(describing: error)))")
            }
        }

        return Sealed(v: version,
                      salt: salt.base64EncodedString(),
                      body: combined.base64EncodedString(),
                      nonce: Data(nonce).base64EncodedString(),
                      wraps: wraps)
    }

    // MARK: - Opening

    static func open(_ envelope: Sealed,
                     asMember myPublicKey: String,
                     decryptWrap: WrapDecryptor) throws -> Data {
        guard envelope.v == version,
              let salt = Data(base64Encoded: envelope.salt),
              let combined = Data(base64Encoded: envelope.body),
              let nonceData = Data(base64Encoded: envelope.nonce),
              combined.count >= 16 else {
            throw Failure.malformed
        }

        let mine = hint(forMember: myPublicKey, salt: salt)
        guard let wrap = envelope.wraps.first(where: { $0.h == mine }),
              let wrappedData = Data(base64Encoded: wrap.k) else {
            throw Failure.noWrapForUs
        }

        let contentKeyB64: String
        do {
            contentKeyB64 = try decryptWrap(wrappedData)
        } catch {
            // Deliberately one error: the caller's only correct response is to
            // heal the 1:1 session, whatever the ratchet's internal complaint was.
            throw Failure.wrapUndecryptable
        }
        guard let keyBytes = Data(base64Encoded: contentKeyB64), keyBytes.count == 32 else {
            throw Failure.malformed
        }

        let ciphertext = combined.prefix(combined.count - 16)
        let tag = combined.suffix(16)
        let box = try AES.GCM.SealedBox(nonce: try AES.GCM.Nonce(data: nonceData),
                                        ciphertext: ciphertext,
                                        tag: tag)
        return try AES.GCM.open(box, using: SymmetricKey(data: keyBytes))
    }

    // MARK: - When it may be used

    /// v3 is produced only when EVERY member can read it.
    ///
    /// This is the whole interoperability contract in one function. A single
    /// member on an older build — or on an Android/desktop build that has not
    /// shipped v3 yet — keeps the entire group on the old format, because the
    /// alternative is that member silently losing every message. Groups migrate
    /// one at a time, on their own, as the last member updates.
    static func canUse(members: [String], capabilities: PeerCapabilityStore = .shared) -> Bool {
        guard !members.isEmpty else { return false }
        return members.allSatisfy { capabilities.supportsGroupV3($0) }
    }
}

// MARK: - What our peers can do

/// Per-peer capability flags, learned from the profile payload contacts already
/// exchange. Absent means "cannot", never "assume yes": the cost of guessing
/// wrong is a member who stops receiving group messages.
final class PeerCapabilityStore {

    static let shared = PeerCapabilityStore()

    private static let storeKey = "peerCapabilities_v1"
    private static let ratchetStoreKey = "peerCapabilitiesRatchetV3_v1"
    private let lock = NSLock()
    private var groupV3: Set<String> = []
    private var ratchetV3: Set<String> = []

    private init() {
        groupV3 = Set(UserDefaults.standard.stringArray(forKey: Self.storeKey) ?? [])
        ratchetV3 = Set(UserDefaults.standard.stringArray(forKey: Self.ratchetStoreKey) ?? [])
    }

    func supportsGroupV3(_ peer: String) -> Bool {
        lock.lock()
        let known = groupV3.contains(peer)
        lock.unlock()
        if known { return true }
        // __TWO_CAPABILITY_STORES_2026_10_19__ Second source: what the SERVER
        // publishes.
        //
        // This set is learned from a profile payload, and a peer only sends its
        // profile once the user has messaged it (`sendProfileToContact` withholds
        // it otherwise, deliberately — it carries a photo and a nickname). So a
        // brand-new device was unreachable in groups until it happened to send a
        // profile, and nothing made it send one: added to a roster, it stayed
        // invisible. Measured — a Mac publishing `caps: 3` on `/v2/keys`, added to
        // four groups from an iPhone, had NO relay queue at all.
        //
        // `RatchetHealSignal.Caps` already persists the `caps` bitmask from every
        // prekey bundle we fetch, which is authoritative and leaks nothing. Read
        // it here rather than pushing from the fetch site: that site is compiled
        // into OSHIShare, where this file is not, and pulling the group machinery
        // into a share extension to carry one bit is the wrong trade.
        return (RatchetHealSignal.Caps.of(peer: peer) & RatchetHealSignal.Caps.groupV2) != 0
    }

    /// CV-010: this peer can hold a session whose DH ratchet actually turns.
    func supportsRatchetV3(_ peer: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return ratchetV3.contains(peer)
    }

    func record(ratchetV3 supported: Bool, for peer: String) {
        guard !peer.isEmpty else { return }
        lock.lock()
        let changed = supported ? ratchetV3.insert(peer).inserted
                                : (ratchetV3.remove(peer) != nil)
        let snapshot = ratchetV3
        lock.unlock()
        guard changed else { return }
        UserDefaults.standard.set(Array(snapshot), forKey: Self.ratchetStoreKey)
    }

    func record(groupV3 supported: Bool, for peer: String) {
        guard !peer.isEmpty else { return }
        lock.lock()
        let changed = supported ? groupV3.insert(peer).inserted
                                : (groupV3.remove(peer) != nil)
        let snapshot = groupV3
        lock.unlock()
        guard changed else { return }
        UserDefaults.standard.set(Array(snapshot), forKey: Self.storeKey)
    }

    func removeAllForTesting() {
        lock.lock(); groupV3.removeAll(); ratchetV3.removeAll(); lock.unlock()
        UserDefaults.standard.removeObject(forKey: Self.storeKey)
        UserDefaults.standard.removeObject(forKey: Self.ratchetStoreKey)
    }
}

// MARK: - The live seam to the 1:1 ratchet

extension GroupEnvelopeV3 {

    /// Wraps the content key for each member using our real 1:1 session.
    ///
    /// The legacy path can always build a session with any member whose public
    /// key we know, because its shared secret comes from STATIC X25519 identity
    /// keys — no handshake, no round trip. That is what makes this workable
    /// offline: a member we have never messaged directly still gets a wrap.
    ///
    /// It goes through `getOrCreateSessionNegotiatingPQ`, so a group wrap to a
    /// post-quantum-capable member is itself post-quantum protected.
    static func liveEncryptor(ourPublicKey: String,
                              identity: IdentityManager) -> WrapEncryptor {
        return { member, contentKeyB64 in
            let shared = try identity.computeSharedSecret(with: member)
            let session = DoubleRatchetSessionManager.shared.getOrCreateSessionNegotiatingPQ(
                with: member, ourPublicKey: ourPublicKey, sharedSecret: shared)
            return try JSONEncoder().encode(try session.encrypt(contentKeyB64))
        }
    }

    /// Unwraps with the session we hold for the SENDER.
    static func liveDecryptor(senderPublicKey: String,
                              ourPublicKey: String,
                              identity: IdentityManager) -> WrapDecryptor {
        return { data in
            let msg = try JSONDecoder().decode(DoubleRatchetMessage.self, from: data)
            let shared = try identity.computeSharedSecret(with: senderPublicKey)
            let session = DoubleRatchetSessionManager.shared.getOrCreateSessionNegotiatingPQ(
                with: senderPublicKey, ourPublicKey: ourPublicKey,
                sharedSecret: shared, incomingHeader: msg.header)
            return try session.decrypt(msg)
        }
    }

    /// Decode a pinned object back into an envelope. The encoder side lives on
    /// `Sealed` just below, so the two cannot drift apart.
    static func fromPinned(_ data: Data) -> Sealed? {
        try? JSONDecoder().decode(Sealed.self, from: data)
    }
}

extension GroupEnvelopeV3.Sealed {
    var asPinnedDictionary: [String: Any] {
        [
            "v": v,
            "salt": salt,
            "body": body,
            "nonce": nonce,
            "wraps": wraps.map { ["h": $0.h, "k": $0.k] }
        ]
    }
}

// MARK: - Fitting a size-constrained transport (LoRa)

extension GroupEnvelopeV3 {

    /// The budget one message has on the radio: `MeshtasticManager.loRaChunk`
    /// (180) × 12 frames. Duplicated as a constant here rather than imported so
    /// this file has no dependency on the transport layer; the test pins the two
    /// together.
    static let loRaMessageCeiling = 180 * 12

    /// Seal for a transport that refuses large messages.
    ///
    /// A combined envelope carries one wrap per member (~545 bytes each), so it
    /// grows with the group while the radio ceiling does not: measured, a group
    /// of 3 fits in 1798 bytes and a group of 5 needs 2862 and is refused. Text
    /// splitting cannot rescue that — above a handful of members the WRAPS alone
    /// exceed the budget, whatever the body says.
    ///
    /// So when the combined envelope does not fit, this returns one envelope PER
    /// RECIPIENT instead. Each is self-contained (body + that member's single
    /// wrap, ~705 bytes), which costs airtime but has a second advantage on a
    /// lossy radio: losing one member's copy does not affect anyone else's, where
    /// losing a shared body would have cost everyone the message.
    ///
    /// Each per-recipient envelope has its OWN content key, so the bodies differ
    /// and the copies cannot be correlated by ciphertext either.
    ///
    /// - Returns: one envelope addressed to everyone, or one per member. Empty
    ///   only when no member could be wrapped at all.
    /// - Note: a single-recipient envelope that STILL exceeds `maxBytes` is
    ///   returned regardless — the caller's text splitter owns that case, and
    ///   silently dropping the message here would be worse than letting the
    ///   transport refuse it visibly.
    static func sealFitting(payload: Data,
                            members: [String],
                            maxBytes: Int,
                            encryptWrap: WrapEncryptor) throws -> [Sealed] {
        let combined = try seal(payload: payload, members: members, encryptWrap: encryptWrap)
        guard !combined.wraps.isEmpty else { return [] }

        if encodedSize(combined) <= maxBytes { return [combined] }

        var perRecipient: [Sealed] = []
        for member in members {
            let one = try seal(payload: payload, members: [member], encryptWrap: encryptWrap)
            // A member with no reachable session produced no wrap; skip them
            // rather than pin an envelope nobody can open.
            if one.wraps.isEmpty { continue }
            perRecipient.append(one)
        }
        return perRecipient
    }

    /// Bytes this envelope occupies once encoded — the number the transport
    /// actually measures.
    static func encodedSize(_ envelope: Sealed) -> Int {
        (try? JSONEncoder().encode(envelope).count) ?? Int.max
    }
}

// MARK: - Opening an envelope whose sender we do not know

extension GroupEnvelopeV3 {

    /// Open a v3 envelope arriving on a path with no transport-level sender —
    /// IPFS, or the relay.
    ///
    /// The mesh hands us `senderPublicKey`; a pinned object does not, and the
    /// envelope does not name its sender on purpose. The handle is the
    /// `sessionId` carried in our own wrap's ratchet header: it names exactly the
    /// 1:1 session able to open that wrap, so no trial decryption and no contact
    /// scan is needed.
    ///
    /// Returns nil when no wrap is addressed to us — the ordinary case for a
    /// group we are not in, which must stay quiet rather than log an error.
    static func openWithoutKnownSender(_ envelope: Sealed,
                                       asMember myPublicKey: String,
                                       sessions: DoubleRatchetSessionManager = .shared) throws -> Data? {
        guard envelope.v == version,
              let salt = Data(base64Encoded: envelope.salt) else { return nil }

        let mine = hint(forMember: myPublicKey, salt: salt)
        guard let wrap = envelope.wraps.first(where: { $0.h == mine }),
              let wrapped = Data(base64Encoded: wrap.k),
              let ratchet = try? JSONDecoder().decode(DoubleRatchetMessage.self, from: wrapped) else {
            return nil
        }
        guard let session = sessions.session(withId: ratchet.header.sessionId) else {
            // We are addressed but hold no such session — a reinstall, or a heal
            // that has not landed yet. Distinguishable from "not for us", and the
            // caller must not treat it as a malformed envelope.
            throw Failure.wrapUndecryptable
        }
        return try open(envelope, asMember: myPublicKey) { _ in try session.decrypt(ratchet) }
    }
}
