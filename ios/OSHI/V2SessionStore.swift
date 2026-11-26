//
//  V2SessionStore.swift
//  OSHI — persistent state for the v2 end-to-end (X3DH + Double Ratchet) stack.
//
//  DEFENSIVE, PURELY ADDITIVE. This file introduces NO behaviour on its own; it
//  is a Keychain-backed persistence layer for the v2 crypto core (OSHICryptoV2)
//  and is only ever exercised when the v2 feature flag is enabled and the
//  V2MessageRouter drives it. It does NOT touch, reference, or alter the existing
//  IPFS / Pinata path or the legacy DoubleRatchetSessionManager.
//
//  WHAT IT PERSISTS
//    1. Per-peer Double Ratchet session state (the mutable OSHICryptoV2.RatchetState),
//       plus the X3DH role and the peer's public identity material, keyed by the
//       peer's userKey.
//    2. THIS device's own long-lived v2 key material (X25519 identity + signed
//       pre-key + the pool of one-time pre-keys), used to answer inbound X3DH.
//
//  All state is Codable → Data → KeychainHelper. Data fields serialise as base64
//  inside JSON, so nothing here force-unwraps or loses bytes. Every read that can
//  legitimately be "absent" returns nil (KeychainError.itemNotFound) rather than
//  throwing; genuinely exceptional Keychain errors (e.g. `.locked`) propagate so
//  the caller does not mistake a transient lock for "no session".
//
//  Namespacing: everything lives under the `V2SessionStore` type (nested Codable
//  models) so there is zero clash with existing module types. RatchetState is
//  bridged to/from a private `SerializedRatchet` — the crypto core's live class is
//  never itself made Codable.
//
//  NOTE: there is no iOS runtime in the authoring environment; this was written to
//  type-check against the sibling files. Runtime verification happens on a Mac.
//

import Foundation

/// Keychain-backed persistence for v2 sessions + our own v2 key material.
///
/// Stateless aside from its key prefix, so it is safe to share one instance or
/// create per call. Marked `@unchecked Sendable` because it holds only immutable
/// `String` configuration and delegates all storage to the (thread-safe) Security
/// framework via `KeychainHelper`.
nonisolated final class V2SessionStore: @unchecked Sendable {

    // MARK: - Keychain key layout

    /// Namespaced so v2 items never collide with any existing Keychain account
    /// (e.g. the "signingKey" item shared with VoIPPushManager / V2KeychainCrypto).
    private let sessionPrefix: String
    private let ownMaterialKey: String

    // Names come from `KeychainHelper.Account` so the shared-group list and the
    // writers cannot drift apart — that drift already shipped once, with a key
    // name that existed nowhere.
    init(sessionPrefix: String = KeychainHelper.Account.v2SessionPrefix,
         ownMaterialKey: String = KeychainHelper.Account.v2OwnKeys) {
        self.sessionPrefix = sessionPrefix
        self.ownMaterialKey = ownMaterialKey
    }

    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    private func sessionAccount(_ peerUserKey: String) -> String {
        // The peer userKey is base64 and may contain +/=; those are valid in a
        // Keychain account string, but we still prefix to isolate the namespace.
        sessionPrefix + peerUserKey
    }

    // MARK: - Nested models

    /// Which side of the X3DH handshake this device played for a given peer.
    enum Role: String, Codable, Sendable {
        case initiator   // we fetched their bundle and sent the first message
        case responder   // we answered their X3DH first message
    }

    /// A raw key pair (X25519 or Ed25519), stored as two `Data` blobs.
    struct KeyPairData: Codable, Sendable, Equatable {
        var priv: Data
        var pub: Data

        init(priv: Data, pub: Data) {
            self.priv = priv
            self.pub = pub
        }

        /// Bridge from the crypto core's X25519 pair.
        init(_ pair: OSHICryptoV2.X25519Pair) {
            self.priv = pair.priv
            self.pub = pair.pub
        }

        /// Bridge back to the crypto core's X25519 pair.
        var x25519: OSHICryptoV2.X25519Pair {
            OSHICryptoV2.X25519Pair(priv: priv, pub: pub)
        }
    }

    /// A one-time pre-key we published and may later consume when answering X3DH.
    struct OneTimePreKeyEntry: Codable, Sendable, Equatable {
        var keyId: String
        var pair: KeyPairData
    }

    /// THIS device's long-lived v2 key material. The Ed25519 signing identity is
    /// NOT duplicated here — it already lives in the shared "signingKey" Keychain
    /// item (see V2KeychainCrypto); we only record its base64 public form for
    /// convenience so a bundle can be rebuilt without re-loading it.
    struct OwnKeyMaterial: Codable, Sendable, Equatable {
        /// X25519 identity key pair (the X3DH "IK").
        var identity: KeyPairData
        /// X25519 signed pre-key pair (the X3DH "SPK"); also our initial ratchet
        /// key pair when we act as responder.
        var signedPreKey: KeyPairData
        var signedPreKeyId: String
        /// Ed25519 signature over `signedPreKey.pub`, so peers can verify it.
        var signedPreKeySignature: Data
        /// base64 Ed25519 signing public key (== our v2 userKey).
        var signingPublicKeyB64: String
        /// Consumable one-time pre-key pool.
        var oneTimePreKeys: [OneTimePreKeyEntry]
        var createdAt: Date

        init(identity: KeyPairData,
             signedPreKey: KeyPairData,
             signedPreKeyId: String,
             signedPreKeySignature: Data,
             signingPublicKeyB64: String,
             oneTimePreKeys: [OneTimePreKeyEntry],
             createdAt: Date = Date()) {
            self.identity = identity
            self.signedPreKey = signedPreKey
            self.signedPreKeyId = signedPreKeyId
            self.signedPreKeySignature = signedPreKeySignature
            self.signingPublicKeyB64 = signingPublicKeyB64
            self.oneTimePreKeys = oneTimePreKeys
            self.createdAt = createdAt
        }
    }

    /// Codable mirror of the crypto core's mutable `RatchetState` class. Field
    /// names track `OSHICryptoV2.RatchetState` one-for-one.
    struct SerializedRatchet: Codable, Sendable, Equatable {
        var DHsPriv: Data
        var DHsPub: Data
        var DHr: Data?
        var RK: Data
        var CKs: Data?
        var CKr: Data?
        var Ns: UInt32
        var Nr: UInt32
        var PN: UInt32
        var MKSKIPPED: [String: Data]

        init(_ s: OSHICryptoV2.RatchetState) {
            self.DHsPriv = s.DHs.priv
            self.DHsPub = s.DHs.pub
            self.DHr = s.DHr
            self.RK = s.RK
            self.CKs = s.CKs
            self.CKr = s.CKr
            self.Ns = s.Ns
            self.Nr = s.Nr
            self.PN = s.PN
            self.MKSKIPPED = s.MKSKIPPED
        }

        /// Rebuild the live crypto-core state.
        func toRatchetState() -> OSHICryptoV2.RatchetState {
            let state = OSHICryptoV2.RatchetState(
                DHs: OSHICryptoV2.X25519Pair(priv: DHsPriv, pub: DHsPub),
                DHr: DHr,
                RK: RK,
                CKs: CKs,
                CKr: CKr
            )
            state.Ns = Ns
            state.Nr = Nr
            state.PN = PN
            state.MKSKIPPED = MKSKIPPED
            return state
        }
    }

    /// A persisted 1:1 session for one peer.
    struct SessionRecord: Codable, Sendable, Equatable {
        var peerUserKey: String
        var role: Role
        var ratchet: SerializedRatchet
        /// Peer's X25519 identity public key (from their bundle / X3DH header).
        var peerIdentityKey: Data?
        /// Peer's Ed25519 signing public key (== their userKey, decoded).
        var peerSigningKey: Data?
        /// For an initiator: the X3DH header that MUST ride on the first outbound
        /// message and be cleared afterwards. `nil` once the handshake is
        /// consumed or when we are the responder.
        var pendingInitiatorHeader: V2.X3DHHeader?
        var establishedAt: Date

        init(peerUserKey: String,
             role: Role,
             ratchet: SerializedRatchet,
             peerIdentityKey: Data? = nil,
             peerSigningKey: Data? = nil,
             pendingInitiatorHeader: V2.X3DHHeader? = nil,
             establishedAt: Date = Date()) {
            self.peerUserKey = peerUserKey
            self.role = role
            self.ratchet = ratchet
            self.peerIdentityKey = peerIdentityKey
            self.peerSigningKey = peerSigningKey
            self.pendingInitiatorHeader = pendingInitiatorHeader
            self.establishedAt = establishedAt
        }
    }

    // MARK: - Cross-process serialisation

    /// Runs `body` holding the cross-process lock for THIS peer's session.
    ///
    /// Every read-modify-write of a ratchet must go through here once OSHIShare can
    /// reach the same Keychain items. `loadSession` → mutate → `saveSession` from
    /// two processes at once means the second write discards the first one's
    /// advance, and the peer's next message then fails to authenticate — for good.
    ///
    /// ⚠️ NOT reentrant. `body` must not call `withPeerSession` again for the same
    /// peer; `flock` plus the per-instance serial queue would deadlock, not fail.
    /// Keep the block to exactly one load/mutate/save.
    ///
    /// Timeout is 5 s: long enough to outlast the other side's Keychain I/O, short
    /// enough that a send fails visibly instead of hanging a UI. See
    /// `CrossProcessLock.withLock` for why this THROWS rather than proceeding
    /// unlocked.
    func withPeerSession<T>(_ peerUserKey: String,
                            timeout: TimeInterval = 5,
                            _ body: () throws -> T) throws -> T {
        try CrossProcessLock.forPeer(peerUserKey).withLock(timeout: timeout, body)
    }

    // MARK: - Session persistence

    // 🔴 __SESSIONS_MUST_BE_SHARED_2026_08_18__
    //
    // These used to read and write WITHOUT an access group, i.e. into whatever
    // group the process defaults to. In the app that is the app's private group;
    // OSHIShare cannot reach it. So the extension's `hasSession` was ALWAYS false,
    // it fell back on every single share, and the user still had to open OSHI —
    // exactly the symptom that survived every other fix.
    //
    // The earlier note said per-peer sessions would be "migrated lazily on first
    // access". That migration was described and never written. It is written here.
    //
    // The shared group is AUTHORITATIVE. A plain group-less load would search every
    // reachable group and return an unspecified one of the two copies — which, on a
    // ratchet, means silently decrypting against a stale chain. So the order is
    // always: shared first, private only as a fallback, and a hit in private is
    // copied up before it is used.

    /// Persist (create or replace) a peer session.
    func saveSession(_ record: SessionRecord) throws {
        // 🔴 __DEAD10CC_2026_11_26__ If this thread is inside a `withPeerSession`
        // hold whose background time expired, the `flock` has already been dropped
        // (so iOS could suspend us without a 0xdead10cc kill). What `body` read under
        // it may be stale by now: refuse the write, leave the stored ratchet at its
        // pre-hold state, and let the caller retry. No-op outside a revoked hold.
        try CrossProcessLock.forPeer(record.peerUserKey).throwIfHoldRevoked()
        let data = try encoder.encode(record)
        let account = sessionAccount(record.peerUserKey)
        // Shared group so both processes advance the SAME record.
        try KeychainHelper.save(key: account, data: data,
                                accessGroup: KeychainHelper.sharedAccessGroup)
        // Best-effort: drop a stale private copy so a group-less reader elsewhere
        // cannot resurrect an older chain state. Never fatal — the shared write
        // above is the one that matters.
        //
        // 🔴 __GROUPLESS_DELETE_2026_08_22__ Ceci était `delete(key: account)`,
        // SANS groupe — donc une suppression portant sur tous les groupes
        // accessibles, y compris le partagé qu'on vient d'écrire deux lignes plus
        // haut. Aucune session n'a jamais survécu à son propre enregistrement:
        // `encrypt1to1` reprenait le verrou, ne trouvait plus rien et levait
        // `sessionVanishedUnderLock`. Sur l'appareil de Hugo, 32 envois sur 32.
        KeychainHelper.deletePrivateCopy(key: account)
    }

    /// Load a peer session, or `nil` if none has been established yet.
    ///
    /// Lazily copies a pre-existing private session into the shared group the
    /// first time it is read, so upgrading users do not have to re-establish
    /// every conversation for the share extension to work.
    func loadSession(peerUserKey: String) throws -> SessionRecord? {
        let account = sessionAccount(peerUserKey)

        if let data = try? KeychainHelper.load(key: account,
                                               accessGroup: KeychainHelper.sharedAccessGroup) {
            return try decoder.decode(SessionRecord.self, from: data)
        }

        // Not in the shared group. Either it is genuinely absent, or it predates
        // sharing and still sits in the private one.
        do {
            let data = try KeychainHelper.load(key: account)
            let record = try decoder.decode(SessionRecord.self, from: data)
            // Copy up, then remove the private original — but only AFTER the
            // shared write succeeded. A locked Keychain must never cost someone
            // their session.
            if (try? KeychainHelper.save(key: account, data: data,
                                         accessGroup: KeychainHelper.sharedAccessGroup)) != nil,
               (try? KeychainHelper.load(key: account,
                                         accessGroup: KeychainHelper.sharedAccessGroup)) != nil {
                // 🔴 __GROUPLESS_DELETE_2026_08_22__ Même défaut que dans
                // `saveSession`: la relecture de vérification juste au-dessus
                // confirmait la copie partagée, et la ligne suivante la
                // supprimait avec la privée. La migration détruisait ce qu'elle
                // venait de vérifier.
                KeychainHelper.deletePrivateCopy(key: account)
            }
            return record
        } catch KeychainError.itemNotFound {
            return nil
        }
    }

    /// Copy EVERY per-peer session that still lives in the private group up into
    /// the shared one. Safe to call on every launch; cheap when there is nothing
    /// to do.
    ///
    /// 🔧 FIX (2026-08-20). `loadSession` migrates one peer at a time, on demand —
    /// and only the APP can perform that migration, because the fallback read is
    /// group-less and a group-less read from OSHIShare searches the EXTENSION's
    /// groups, which never contained the private copy. So after an upgrade the
    /// first share to a given contact still fell back to "open OSHI", once per
    /// contact, until the app happened to load that session for its own reasons.
    ///
    /// Reported on the device as: "the photo only reaches the recipient when I
    /// open OSHI — the share screen flashes for a fraction of a second and sends."
    /// That flash is the app draining the queue the extension left behind.
    ///
    /// Migration is expressed by simply CALLING `loadSession` for each account, so
    /// there is exactly one copy-up rule in this file and no second one to drift
    /// from it.
    ///
    /// - Returns: (migrated, alreadyShared, failed) — logged, never asserted. A
    ///   locked keychain at launch legitimately migrates nothing.
    @discardableResult
    func migrateAllPeerSessionsToSharedGroup() -> (migrated: Int, alreadyShared: Int, failed: Int) {
        let accounts = KeychainHelper.allAccounts(prefix: sessionPrefix)
        var migrated = 0, alreadyShared = 0, failed = 0
        for account in accounts {
            let peer = String(account.dropFirst(sessionPrefix.count))
            let wasShared = (try? KeychainHelper.load(key: account,
                                                      accessGroup: KeychainHelper.sharedAccessGroup)) != nil
            if wasShared { alreadyShared += 1; continue }
            // The copy-up happens inside loadSession, and only if the read-back
            // verifies — see there.
            guard (try? loadSession(peerUserKey: peer)) ?? nil != nil,
                  (try? KeychainHelper.load(key: account,
                                            accessGroup: KeychainHelper.sharedAccessGroup)) != nil else {
                failed += 1
                continue
            }
            migrated += 1
        }
        return (migrated, alreadyShared, failed)
    }

    /// Does a session already exist for this peer? (Never throws — a locked or
    /// otherwise unreadable item is reported as "not present" for gating.)
    func hasSession(peerUserKey: String) -> Bool {
        if let inner = try? loadSession(peerUserKey: peerUserKey), inner != nil {
            return true
        }
        return false
    }

    /// Delete a peer session (idempotent). Removes BOTH copies — a session that
    /// survives in one group is a session that comes back.
    func deleteSession(peerUserKey: String) throws {
        let account = sessionAccount(peerUserKey)
        try? KeychainHelper.delete(key: account, accessGroup: KeychainHelper.sharedAccessGroup)
        try KeychainHelper.delete(key: account)
    }

    // MARK: - Own key material persistence

    /// Same rule as the per-peer sessions: the shared group is authoritative, or
    /// the extension holds key material the app has already rotated past.
    ///
    /// The launch migration copies the pre-existing item up; this makes sure every
    /// SUBSEQUENT write lands there too, instead of quietly going back to private
    /// and leaving the extension with a snapshot that ages.
    func saveOwnKeyMaterial(_ material: OwnKeyMaterial) throws {
        let data = try encoder.encode(material)
        try KeychainHelper.save(key: ownMaterialKey, data: data,
                                accessGroup: KeychainHelper.sharedAccessGroup)
        // 🔴 __GROUPLESS_DELETE_2026_08_22__ Troisième occurrence, et la plus
        // lourde: ici c'est le matériel de clés X3DH de l'appareil. Une
        // suppression sans groupe l'effaçait à chaque écriture, donc
        // `ensureOwnKeyMaterial` en régénérait — et une identité qui change sous
        // les pieds des pairs invalide les sessions dans l'AUTRE sens aussi.
        KeychainHelper.deletePrivateCopy(key: ownMaterialKey)
    }

    func loadOwnKeyMaterial() throws -> OwnKeyMaterial? {
        if let data = try? KeychainHelper.load(key: ownMaterialKey,
                                               accessGroup: KeychainHelper.sharedAccessGroup) {
            return try decoder.decode(OwnKeyMaterial.self, from: data)
        }
        do {
            let data = try KeychainHelper.load(key: ownMaterialKey)
            return try decoder.decode(OwnKeyMaterial.self, from: data)
        } catch KeychainError.itemNotFound {
            return nil
        }
    }

    func deleteOwnKeyMaterial() throws {
        try KeychainHelper.delete(key: ownMaterialKey)
    }

    /// Consume (remove) a one-time pre-key by id after it has been used to answer
    /// an inbound X3DH, then persist the shrunken pool. No-op if absent.
    @discardableResult
    func consumeOneTimePreKey(keyId: String) throws -> OneTimePreKeyEntry? {
        guard var material = try loadOwnKeyMaterial() else { return nil }
        guard let idx = material.oneTimePreKeys.firstIndex(where: { $0.keyId == keyId }) else {
            return nil
        }
        let removed = material.oneTimePreKeys.remove(at: idx)
        try saveOwnKeyMaterial(material)
        return removed
    }

    /// Append freshly generated one-time pre-keys to the local pool (refill),
    /// capping the retained pool at `cap` most-recent entries and persisting.
    /// The private halves must be kept until a peer that popped the matching
    /// public key actually sends their first message; `cap` bounds unbounded
    /// growth from popped-but-never-used keys (in-flight windows are short, so
    /// a generous cap never drops a key that is still answerable in practice).
    func appendOneTimePreKeys(_ entries: [OneTimePreKeyEntry], cap: Int = 200) throws {
        guard var material = try loadOwnKeyMaterial() else { return }
        material.oneTimePreKeys.append(contentsOf: entries)
        if material.oneTimePreKeys.count > cap {
            material.oneTimePreKeys.removeFirst(material.oneTimePreKeys.count - cap)
        }
        try saveOwnKeyMaterial(material)
    }
}
