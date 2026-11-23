//
//  V2GroupSession.swift
//  OSHI — v2 group key / epoch manager (client-side E2E group state).
//
//  DEFENSIVE, PURELY ADDITIVE. Only ever exercised behind the v2 feature flag via
//  V2MessageRouter. It does NOT touch the existing IPFS / Pinata path or the
//  legacy MessageGroupManager.
//
//  MODEL — matches the tested server contract (relay_v2.js):
//    * The relay has ZERO knowledge of group membership. A group is pure E2E
//      client state: a set of `members` (userKeys), a set of `admins`, and a
//      monotonic `epoch` integer.
//    * Sending a group message FANS OUT: the client produces one Double-Ratchet
//      -encrypted copy PER member using that member's existing 1:1 session (via
//      the crypto core, reached through `V2GroupContext`), each wrapped in a
//      `V2.Envelope` with `type == .group` and the shared `groupId`. The batch is
//      handed to `V2Client.sendBatch`, which the relay queues per recipient.
//    * On add/remove the `epoch` is bumped. Because every send fans out over the
//      CURRENT membership only, a removed member is simply never included again
//      (excluded from future fan-outs) and a newly added member's first delivery
//      is a fresh message (no history is re-encrypted to them).
//
//  ADMIN CONTROL: add/remove are modelled as signed control messages
//  (`ControlMessage`). A control message is only applied if its signature
//  verifies against the sender's userKey (which *is* their base64 Ed25519 signing
//  public key) AND the sender is a current admin of the group. This is the
//  membership-integrity check the spec requires.
//
//  Namespacing: everything is nested under `V2GroupSession` (an `actor`, so its
//  mutable group table is race-free). The per-member crypto seam is the
//  `V2GroupContext` protocol, implemented by V2MessageRouter.
//
//  NOTE: no iOS runtime in the authoring environment; written to type-check
//  against the sibling files. Runtime verification happens on a Mac.
//

import Foundation

// MARK: - Crypto / transport seam

/// The capabilities `V2GroupSession` borrows from its owner (V2MessageRouter) to
/// perform per-member 1:1 crypto and to sign/verify group control. Class-bound &
/// Sendable so it can be held weakly by the group actor.
protocol V2GroupContext: AnyObject, Sendable {
    /// This device's v2 userKey (base64 Ed25519 signing pub). Used to exclude
    /// ourselves from fan-out and to stamp control messages / envelopes.
    var selfUserKey: String { get }

    /// Encrypt `plaintext` to a single member via their 1:1 ratchet session,
    /// establishing one (X3DH) if none exists yet. Returns the opaque wire pieces.
    func encryptForMember(_ memberUserKey: String,
                          plaintext: Data) async throws -> V2GroupSession.MemberCiphertext

    /// Decrypt an inbound 1:1 ratchet payload from `senderUserKey`, handling the
    /// X3DH first-message header when present.
    func decryptFromMember(_ senderUserKey: String,
                           header: String,
                           ciphertext: String,
                           x3dh: V2.X3DHHeader?) async throws -> Data

    /// Ed25519-sign arbitrary bytes with our identity signing key (for control).
    /// Returns the signature AND the base64 Ed25519 SIGNING public key that made
    /// it. The group `members`/`admins` sets hold X25519 `userKey` contact
    /// addresses, which are NOT signing keys, so control messages must carry the
    /// signing key explicitly for verification to be possible.
    func signControl(_ data: Data) throws -> (signature: Data, signerKeyB64: String)

    /// The Ed25519 SIGNING key we have *reliably* bound to the X25519 identity
    /// `userKey`, or `nil` if we have not authenticated one yet.
    ///
    /// This is the client-side identity binding group signer-binding needs: a
    /// control message claims admin `sender` (an X25519 address) but is signed by
    /// some Ed25519 key; only this mapping proves the two belong together. The
    /// binding is learned when we established the 1:1 session as the X3DH
    /// INITIATOR — there we fetched the peer's prekey bundle and verified their
    /// signed prekey against this signing key, so it is authenticated against
    /// `userKey`. On the RESPONDER path the peer's signing key is never delivered
    /// (the X3DH header carries only X25519 material), so no binding is returned
    /// there and the caller must fall back to TOFU-pinning rather than reject.
    func boundSigningKey(forIdentity userKey: String) -> Data?
}

// MARK: - V2GroupSession

/// Client-side E2E group manager. Persists group metadata in the Keychain and
/// fans messages out over per-member 1:1 sessions.
actor V2GroupSession {

    // MARK: Errors

    enum GroupError: Error, LocalizedError {
        case noSuchGroup(String)
        case notAdmin(sender: String, groupId: String)
        case badControlSignature
        case malformedSenderKey
        case notAMember(sender: String, groupId: String)
        case contextUnavailable
        case staleControlEpoch(groupId: String, epoch: Int, current: Int)
        case duplicateControl(groupId: String)
        case signerBindingMismatch(sender: String, groupId: String)

        var errorDescription: String? {
            switch self {
            case .noSuchGroup(let g): return "V2GroupSession: no such group \(g)"
            case .notAdmin(let s, let g): return "V2GroupSession: \(s) is not an admin of \(g)"
            case .badControlSignature: return "V2GroupSession: control-message signature invalid"
            case .malformedSenderKey: return "V2GroupSession: sender userKey is not valid base64"
            case .notAMember(let s, let g): return "V2GroupSession: \(s) is not a member of \(g)"
            case .contextUnavailable: return "V2GroupSession: crypto context not attached"
            case .staleControlEpoch(let g, let e, let c): return "V2GroupSession: stale/replayed control for \(g) (epoch \(e) <= current \(c))"
            case .duplicateControl(let g): return "V2GroupSession: duplicate control message for \(g)"
            case .signerBindingMismatch(let s, let g): return "V2GroupSession: control for \(g) claims admin \(s) but is signed by a key not bound to that identity (forgery)"
            }
        }
    }

    // MARK: Models

    /// Opaque per-member ciphertext produced by `V2GroupContext.encryptForMember`.
    struct MemberCiphertext: Sendable, Equatable {
        var header: String            // base64 Double-Ratchet header
        var ciphertext: String        // base64 AEAD ciphertext
        var x3dh: V2.X3DHHeader?       // present only on the first message
    }

    /// Persisted group metadata. `members`/`admins` are userKeys (base64 Ed25519).
    struct Group: Codable, Sendable, Equatable {
        var groupId: String
        var members: [String]
        var admins: [String]
        var epoch: Int
        var createdAt: Date

        func isMember(_ u: String) -> Bool { members.contains(u) }
        func isAdmin(_ u: String) -> Bool { admins.contains(u) }
    }

    /// A signed membership-control message. Distributed out-of-band or as an
    /// ordinary group message payload; applied only after signature + admin checks.
    struct ControlMessage: Codable, Sendable, Equatable {
        enum Action: String, Codable, Sendable { case add, remove }
        var action: Action
        var groupId: String
        var target: String        // userKey being added/removed
        var sender: String        // admin identity userKey (X25519 contact address)
        /// Base64 Ed25519 SIGNING public key that produced `signatureB64`.
        ///
        /// The group's `members`/`admins` sets hold X25519 `userKey` contact
        /// addresses — NOT signing keys — so the old code that verified the
        /// signature against `sender` could never succeed and group control was
        /// dead. The verifying key is now carried explicitly and the signature is
        /// checked against IT. Optional so control messages produced by older
        /// builds still DECODE (they simply fail verification, as they always did).
        ///
        /// NOTE (residual, deferred): this does not by itself prove `signerKeyB64`
        /// belongs to `sender`; that binding lives in the identity/prekey bundle
        /// and touches the user-visible safety number (out of scope per the CSPN
        /// task — "safety-number signing-key inclusion" is explicitly deferred).
        var signerKeyB64: String?
        var epoch: Int            // group epoch AFTER the change the sender intends
        var signatureB64: String  // Ed25519 over `canonicalBytes`

        enum CodingKeys: String, CodingKey {
            case action, groupId, target, sender, signerKeyB64, epoch, signatureB64
        }

        init(action: Action, groupId: String, target: String, sender: String,
             signerKeyB64: String?, epoch: Int, signatureB64: String) {
            self.action = action
            self.groupId = groupId
            self.target = target
            self.sender = sender
            self.signerKeyB64 = signerKeyB64
            self.epoch = epoch
            self.signatureB64 = signatureB64
        }

        /// Tolerant of older messages that predate `signerKeyB64` (decode never
        /// throws for a missing key — verification later rejects such a message).
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            self.action = try c.decode(Action.self, forKey: .action)
            self.groupId = try c.decode(String.self, forKey: .groupId)
            self.target = try c.decode(String.self, forKey: .target)
            self.sender = try c.decode(String.self, forKey: .sender)
            self.signerKeyB64 = try c.decodeIfPresent(String.self, forKey: .signerKeyB64)
            self.epoch = try c.decode(Int.self, forKey: .epoch)
            self.signatureB64 = try c.decode(String.self, forKey: .signatureB64)
        }

        /// Deterministic bytes that are signed / verified. Field order is fixed so
        /// both peers hash identical input.
        static func canonicalBytes(action: Action,
                                   groupId: String,
                                   target: String,
                                   sender: String,
                                   epoch: Int) -> Data {
            let s = "v2ctrl\n\(action.rawValue)\n\(groupId)\n\(target)\n\(sender)\n\(epoch)"
            return Data(s.utf8)
        }

        var canonicalBytes: Data {
            Self.canonicalBytes(action: action, groupId: groupId,
                                target: target, sender: sender, epoch: epoch)
        }
    }

    // MARK: State

    /// Crypto/transport seam; set once by the owner after construction.
    private weak var context: V2GroupContext?

    private let keyPrefix: String
    private var cache: [String: Group] = [:]

    /// Signatures of control messages already applied, per group. Used to reject
    /// re-delivered / duplicated control messages (replay hardening). Bounded in
    /// practice by the number of distinct membership changes per group.
    private var seenControlSignatures: [String: Set<String>] = [:]

    /// TOFU pins: X25519 sender identity → the base64 Ed25519 SIGNING key first
    /// seen signing a control message for that sender. Used only when the crypto
    /// context has no *authenticated* binding for the sender yet (we never held an
    /// initiator session with them). The first signing key is trusted-on-first-use
    /// and pinned; a later DIFFERENT key for the same sender is a signer-binding
    /// violation. Persisted so the pin survives relaunch (an in-memory-only pin
    /// would forget the trusted key across launches and re-TOFU an attacker's key).
    /// `nil` until first loaded from the Keychain.
    private var signerPinsCache: [String: String]? = nil
    private var signerPinsAccount: String { keyPrefix + "signerpins" }

    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(keyPrefix: String = "oshi.v2.group.") {
        self.keyPrefix = keyPrefix
    }

    /// Attach the crypto/transport context (V2MessageRouter). Must be called
    /// before any encrypt/decrypt/control operation.
    func attach(context: V2GroupContext) {
        self.context = context
    }

    // MARK: Persistence

    private func account(_ groupId: String) -> String { keyPrefix + groupId }

    private func persist(_ group: Group) throws {
        cache[group.groupId] = group
        let data = try encoder.encode(group)
        try KeychainHelper.save(key: account(group.groupId), data: data)
    }

    /// Load a group from cache or Keychain, or `nil` if unknown.
    func group(_ groupId: String) throws -> Group? {
        if let g = cache[groupId] { return g }
        do {
            let data = try KeychainHelper.load(key: account(groupId))
            let g = try decoder.decode(Group.self, from: data)
            cache[groupId] = g
            return g
        } catch KeychainError.itemNotFound {
            return nil
        }
    }

    private func require(_ groupId: String) throws -> Group {
        guard let g = try group(groupId) else { throw GroupError.noSuchGroup(groupId) }
        return g
    }

    private func requireContext() throws -> V2GroupContext {
        guard let ctx = context else { throw GroupError.contextUnavailable }
        return ctx
    }

    // MARK: Signer-key TOFU pins

    private func loadSignerPins() -> [String: String] {
        if let p = signerPinsCache { return p }
        let loaded: [String: String]
        if let data = try? KeychainHelper.load(key: signerPinsAccount),
           let decoded = try? decoder.decode([String: String].self, from: data) {
            loaded = decoded
        } else {
            loaded = [:]
        }
        signerPinsCache = loaded
        return loaded
    }

    private func pinSignerKey(sender: String, signerKeyB64: String) {
        var pins = loadSignerPins()
        guard pins[sender] != signerKeyB64 else { return }
        pins[sender] = signerKeyB64
        signerPinsCache = pins
        if let data = try? encoder.encode(pins) {
            try? KeychainHelper.save(key: signerPinsAccount, data: data)
        }
    }

    // MARK: Group lifecycle

    /// Create a new group. The local user is always a member and (by default) an
    /// admin. `groupId` is generated if not supplied. Epoch starts at 0.
    @discardableResult
    func createGroup(members: [String],
                     admins: [String]? = nil,
                     groupId: String = UUID().uuidString) throws -> Group {
        let ctx = try requireContext()
        var uniqueMembers = members
        if !uniqueMembers.contains(ctx.selfUserKey) { uniqueMembers.insert(ctx.selfUserKey, at: 0) }
        let adminSet = admins ?? [ctx.selfUserKey]
        let group = Group(groupId: groupId,
                          members: dedupe(uniqueMembers),
                          admins: dedupe(adminSet),
                          epoch: 0,
                          createdAt: Date())
        try persist(group)
        return group
    }

    // MARK: Signed membership control (admin-gated)

    /// Build a signed control message for an add/remove the LOCAL user is
    /// performing. The local user must be a current admin. This also applies the
    /// change locally (bumping the epoch) and returns the signed message so the
    /// caller can distribute it to peers.
    @discardableResult
    func makeAddMember(_ target: String, groupId: String) async throws -> ControlMessage {
        try await makeControl(.add, target: target, groupId: groupId)
    }

    @discardableResult
    func makeRemoveMember(_ target: String, groupId: String) async throws -> ControlMessage {
        try await makeControl(.remove, target: target, groupId: groupId)
    }

    private func makeControl(_ action: ControlMessage.Action,
                             target: String,
                             groupId: String) async throws -> ControlMessage {
        let ctx = try requireContext()
        var g = try require(groupId)
        guard g.isAdmin(ctx.selfUserKey) else {
            throw GroupError.notAdmin(sender: ctx.selfUserKey, groupId: groupId)
        }
        let newEpoch = g.epoch + 1
        let bytes = ControlMessage.canonicalBytes(action: action,
                                                  groupId: groupId,
                                                  target: target,
                                                  sender: ctx.selfUserKey,
                                                  epoch: newEpoch)
        let (sig, signerKeyB64) = try ctx.signControl(bytes)
        let ctrl = ControlMessage(action: action,
                                  groupId: groupId,
                                  target: target,
                                  sender: ctx.selfUserKey,
                                  signerKeyB64: signerKeyB64,
                                  epoch: newEpoch,
                                  signatureB64: sig.base64EncodedString())
        // Apply locally and remember our own signature so a reflected copy is
        // treated as a duplicate rather than re-applied.
        applyChange(&g, action: action, target: target, newEpoch: newEpoch)
        try persist(g)
        seenControlSignatures[groupId, default: []].insert(ctrl.signatureB64)
        return ctrl
    }

    /// Apply an inbound signed control message. Enforces BOTH:
    ///   (1) the signature verifies against the sender's userKey, and
    ///   (2) the sender is a current admin of the group.
    /// Only then is the membership change applied and the epoch advanced.
    func applyControlMessage(_ ctrl: ControlMessage) throws {
        var g = try require(ctrl.groupId)

        // (1) Verify the Ed25519 signature against the SIGNING key carried in the
        // message. Verifying against `sender` (an X25519 contact address) — the
        // old behaviour — could never succeed, so control was non-functional. A
        // legacy message without a `signerKeyB64` is rejected here (it never
        // worked anyway) rather than crashing the decode.
        guard let signerB64 = ctrl.signerKeyB64,
              let signerPub = Data(base64Encoded: signerB64) else {
            throw GroupError.badControlSignature
        }
        guard let sig = Data(base64Encoded: ctrl.signatureB64),
              OSHICryptoV2.verify(signerPub, ctrl.canonicalBytes, sig) else {
            throw GroupError.badControlSignature
        }

        // (1b) SIGNER-BINDING. The signature proves the bytes were signed by
        // `signerB64`, NOT that `signerB64` belongs to the claimed admin
        // `ctrl.sender` (an X25519 identity address). Bind them, or an attacker
        // forges a control naming an admin `sender` yet signed with the attacker's
        // OWN key — the signature verifies and the admin gate passes, since the
        // attacker's X25519 identity need only be an admin, or worse the attacker
        // simply names a real admin they are not. Preference order:
        //   (a) an AUTHENTICATED binding from the crypto context (learned when we
        //       fetched + verified the sender's prekey bundle as X3DH initiator);
        //       if present and it disagrees, this is a forgery → reject.
        //   (b) otherwise TOFU: pin the first signing key seen for this sender and
        //       reject any later change. We do NOT hard-fail merely because no
        //       binding is known — that first sighting is exactly how a legitimate
        //       group learns the admin's key, so hard-failing would break real
        //       groups on first contact.
        let ctx = try requireContext()
        if let bound = ctx.boundSigningKey(forIdentity: ctrl.sender) {
            guard bound.base64EncodedString() == signerB64 else {
                throw GroupError.signerBindingMismatch(sender: ctrl.sender, groupId: ctrl.groupId)
            }
        } else {
            let pins = loadSignerPins()
            if let pinned = pins[ctrl.sender] {
                guard pinned == signerB64 else {
                    throw GroupError.signerBindingMismatch(sender: ctrl.sender, groupId: ctrl.groupId)
                }
            } else {
                pinSignerKey(sender: ctrl.sender, signerKeyB64: signerB64)
            }
        }

        // (2) Admin gate — the sender identity must currently be an admin.
        guard g.isAdmin(ctrl.sender) else {
            throw GroupError.notAdmin(sender: ctrl.sender, groupId: ctrl.groupId)
        }

        // (3) Replay / ordering: the epoch must STRICTLY advance the group.
        // Re-delivered or stale control messages (ctrl.epoch <= current) are
        // rejected — this binds the change to the current group state.
        guard ctrl.epoch > g.epoch else {
            throw GroupError.staleControlEpoch(groupId: ctrl.groupId, epoch: ctrl.epoch, current: g.epoch)
        }

        // (4) De-duplicate: a signature uniquely identifies a signed control
        // message, so an exact re-delivery (even one carrying a fresh epoch by
        // mutation) is dropped before it mutates membership.
        if seenControlSignatures[ctrl.groupId]?.contains(ctrl.signatureB64) == true {
            throw GroupError.duplicateControl(groupId: ctrl.groupId)
        }

        applyChange(&g, action: ctrl.action, target: ctrl.target, newEpoch: ctrl.epoch)
        try persist(g)
        seenControlSignatures[ctrl.groupId, default: []].insert(ctrl.signatureB64)
    }

    /// Convenience wrappers for callers that already hold admin rights locally.
    @discardableResult
    func addMember(_ target: String, groupId: String) async throws -> ControlMessage {
        try await makeAddMember(target, groupId: groupId)
    }

    @discardableResult
    func removeMember(_ target: String, groupId: String) async throws -> ControlMessage {
        try await makeRemoveMember(target, groupId: groupId)
    }

    private func applyChange(_ g: inout Group,
                             action: ControlMessage.Action,
                             target: String,
                             newEpoch: Int) {
        switch action {
        case .add:
            if !g.members.contains(target) { g.members.append(target) }
        case .remove:
            g.members.removeAll { $0 == target }
            g.admins.removeAll { $0 == target }
        }
        g.epoch = newEpoch
    }

    // MARK: Fan-out send / receive

    /// Produce the fan-out envelopes for a group message: one ratchet-encrypted
    /// copy per member (excluding ourselves), each tagged with `groupId` and
    /// `type == .group`, ready for `V2Client.sendBatch`.
    func encryptGroupMessage(groupId: String, plaintext: Data) async throws -> [V2.Envelope] {
        let ctx = try requireContext()
        let g = try require(groupId)
        var envelopes: [V2.Envelope] = []
        envelopes.reserveCapacity(g.members.count)
        for member in g.members where member != ctx.selfUserKey {
            let mc = try await ctx.encryptForMember(member, plaintext: plaintext)
            let env = V2.Envelope(
                msgId: UUID().uuidString,
                from: ctx.selfUserKey,
                to: member,
                type: .group,
                groupId: groupId,
                x3dh: mc.x3dh,
                header: mc.header,
                ciphertext: mc.ciphertext
            )
            envelopes.append(env)
        }
        return envelopes
    }

    /// Decrypt an inbound group envelope to its plaintext bytes, delegating the
    /// actual 1:1 ratchet decryption to the crypto context. Records the sender's
    /// membership implicitly (does not auto-add unknown senders — membership is
    /// governed exclusively by signed control messages).
    func decryptIncomingGroup(_ envelope: V2.ReceivedEnvelope) async throws -> Data {
        let ctx = try requireContext()

        // Membership gate. A group envelope is just a 1:1 ratchet payload tagged
        // with a `groupId`; the relay does not police membership. Without this
        // check ANY peer — including a REMOVED member — could inject into the
        // group merely by stamping its id. Require a KNOWN group whose CURRENT
        // membership includes the sender before accepting the plaintext.
        guard let gid = envelope.groupId else {
            throw GroupError.notAMember(sender: envelope.from, groupId: "<none>")
        }
        let g = try require(gid)
        guard g.isMember(envelope.from) else {
            throw GroupError.notAMember(sender: envelope.from, groupId: gid)
        }

        return try await ctx.decryptFromMember(envelope.from,
                                               header: envelope.header,
                                               ciphertext: envelope.ciphertext,
                                               x3dh: envelope.x3dh)
    }

    // MARK: Helpers

    private func dedupe(_ arr: [String]) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for x in arr where !seen.contains(x) { seen.insert(x); out.append(x) }
        return out
    }
}
