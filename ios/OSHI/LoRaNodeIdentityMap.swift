//
//  LoRaNodeIdentityMap.swift
//  OSHI — which OSHI identity is sitting behind a Meshtastic node number.
//
//  WHY THIS EXISTS
//  ---------------
//  Two different kinds of packet arrive over LoRa, and only one of them carries
//  an identity:
//
//   • An OSHI envelope (PRIVATE_APP, portnum 256) is a `SecureMessage`. It
//     carries `senderPublicKey`, so it already lands in the right conversation.
//   • A RAW Meshtastic text packet (TEXT_MESSAGE_APP, portnum 1) — what the
//     stock Meshtastic app, or a bare node, sends — carries NOTHING but the
//     node number. There is no key in it, no name that means anything to OSHI,
//     nothing. `MeshtasticManager.handleInteropText` therefore had to invent a
//     synthetic sender key, "lora!<nodehex>", and the message opened a
//     conversation of its own next to the real one.
//
//  So the only way a raw text can reach the right conversation is a LEARNED
//  mapping: remember which public key a node has already proved it speaks for,
//  and route later raw text from that node to it. Nothing can be inferred from
//  the raw packet itself.
//
//  WHAT COUNTS AS PROOF
//  --------------------
//  Only a LoRa OSHI envelope that WE DECRYPTED. Decryption runs the Double
//  Ratchet over an X25519 secret computed from our private key and the claimed
//  sender key (`MessageManager.decryptMessage`), so a message that decrypts
//  could only have been produced by the holder of that key's private half.
//  The claimed `senderPublicKey` field on its own proves nothing and is
//  deliberately NOT enough to create a binding — anyone with a radio can put a
//  contact's public key (which is shared openly, by QR) into an envelope.
//
//  WHEN IN DOUBT, DON'T
//  --------------------
//  If a node has never proved an identity, raw text keeps going to the old
//  "lora!<nodehex>" conversation. If a node proves a SECOND, different identity
//  (one radio shared by two people, or a rotated key) the binding is marked
//  ambiguous and routing falls back to "lora!" for good. An extra conversation
//  is a nuisance; putting a stranger's message inside a friend's thread in an
//  encrypted messenger is a betrayal of the whole product.
//
//  PERSISTENCE
//  -----------
//  UserDefaults, JSON, keyed by the node number formatted "%08x".
//  ⚠️ Deliberately NOT `hashValue`: Swift salts `Hashable` per process, so a
//  hash-derived key silently addresses a different slot after every relaunch —
//  which for this map would mean the mapping evaporates at the exact moment it
//  is supposed to be useful (the next launch).
//

import Foundation

final class LoRaNodeIdentityMap {

    /// The app-wide map. Radio callbacks and `MessageManager` both reach it.
    static let shared = LoRaNodeIdentityMap()

    struct Binding: Codable, Equatable {
        /// The OSHI public key, stored EXACTLY as it arrived so it matches the
        /// conversation/contact keys elsewhere byte for byte. Comparison is
        /// normalized; storage is not.
        var publicKey: String
        var learnedAt: Date
        /// Set once a second, different identity has proved itself from this
        /// node. Routing is disabled while true — see the file header.
        var ambiguous: Bool
        /// __LORA_MANUAL_LINK_2026_10_28__ The user SAID this radio is that
        /// contact, rather than the ratchet having proved it.
        ///
        /// Kept as a separate fact from `ambiguous` because the two answer
        /// different questions: `ambiguous` is "the evidence contradicts
        /// itself", this is "there is no cryptographic evidence at all, and a
        /// person vouched instead". It is optional for MIGRATION — maps written
        /// before today decode with it nil, and nil means proved, which is what
        /// every existing binding is.
        var manual: Bool? = nil

        /// True when a person asserted this link rather than the ratchet.
        var isManual: Bool { manual == true }
    }

    enum LearnResult: Equatable {
        /// A new binding was created.
        case bound
        /// Same identity as we already had; nothing changed.
        case unchanged
        /// A different identity proved itself — the node is now ambiguous and
        /// no longer routes.
        case conflict
        /// Not a usable identity key (empty, too short, or a synthetic
        /// "lora!" key). Never stored.
        case rejected
        /// The node is already ambiguous; further evidence cannot un-ambiguate
        /// it.
        case ignoredAmbiguous
    }

    private static let storageKey = "oshi.lora.nodeIdentity.v1"

    private let defaults: UserDefaults
    private let lock = NSLock()
    private var bindings: [String: Binding]

    /// - Parameter defaults: injected so tests get their own suite instead of
    ///   scribbling on the user's real map.
    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: Self.storageKey),
           let decoded = try? JSONDecoder().decode([String: Binding].self, from: data) {
            bindings = decoded
        } else {
            bindings = [:]
        }
    }

    // MARK: Keys

    /// The persisted key for a node number.
    ///
    /// A fixed 8-digit hex rendering of the node number: stable across launches,
    /// across devices and across OS versions. See the header for why this is not
    /// a hash.
    static func nodeKey(_ num: UInt32) -> String {
        String(format: "%08x", num)
    }

    /// The conversation key raw text has always used when the node is unknown,
    /// and still uses. Existing "lora!…" conversations keep working precisely
    /// because this never changed.
    static func fallbackConversationKey(_ num: UInt32) -> String {
        "lora!" + nodeKey(num)
    }

    /// True for the synthetic node-keyed sender ids this file produces.
    static func isFallbackConversationKey(_ key: String) -> Bool {
        key.hasPrefix("lora!")
    }

    /// The node number back out of a `lora!<nodehex>` key — the inverse of
    /// [fallbackConversationKey].
    ///
    /// __LORA_INTEROP_REPLY_2026_10_28__ This exists because the thread is the
    /// ONLY record of which radio the text came from: the conversation is keyed
    /// on the node, not on a public key, so answering it means reading the node
    /// back out of the key. Strict on purpose — anything that is not exactly
    /// `lora!` + eight hex digits returns nil rather than a guess, because the
    /// result is used as a transmit destination. Android mirror:
    /// `LoRaNodeIdentityMap.kt`, `nodeNumFromFallbackKey`.
    static func nodeNumber(fromFallbackKey key: String) -> UInt32? {
        guard isFallbackConversationKey(key) else { return nil }
        let hex = key.dropFirst("lora!".count)
        guard hex.count == 8, hex.allSatisfy(\.isHexDigit) else { return nil }
        return UInt32(hex, radix: 16)
    }

    /// A cheap sanity filter so a synthetic key, an empty string or a truncated
    /// fragment can never become a binding. Real OSHI identity keys are base64
    /// Curve25519 (44 chars); 16 is a floor, not a format check.
    static func looksLikeIdentityKey(_ key: String) -> Bool {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 16 else { return false }
        guard !isFallbackConversationKey(trimmed) else { return false }
        guard !trimmed.contains("!") else { return false }
        return true
    }

    // MARK: Read

    /// The identity this node has proved, or nil when it has proved none — or
    /// has proved more than one.
    ///
    /// nil is the signal to keep using `fallbackConversationKey`. It must never
    /// be papered over with a best guess.
    func publicKey(forNode num: UInt32) -> String? {
        lock.lock(); defer { lock.unlock() }
        guard let b = bindings[Self.nodeKey(num)], !b.ambiguous, !b.publicKey.isEmpty else { return nil }
        return b.publicKey
    }

    /// THE ROUTING DECISION for a raw Meshtastic text packet: the conversation
    /// key it must be filed under.
    ///
    /// The proved identity when there is one, the historical "lora!<nodehex>"
    /// key when there is not. This is the function `handleInteropText` calls —
    /// there is no second copy of this rule anywhere.
    /// 🔧 (2026-08-10) DEFAULT OFF, and that is the honest setting today.
    ///
    /// Binding a node to an identity can be made sound — and has been. What
    /// cannot be fixed at this layer is that a raw Meshtastic text packet is
    /// UNAUTHENTICATED. Its `from` field is an unsigned integer on a channel
    /// whose key is public, so anyone in radio range can transmit a packet
    /// claiming any node number, including one this map has legitimately bound.
    /// Filing that into a contact's end-to-end-encrypted conversation renders
    /// unverified plaintext identically to a message the ratchet authenticated.
    ///
    /// This does NOT affect OSHI-to-OSHI over LoRa, which is what the app itself
    /// sends: those go out as OSHI chunks (portnum 256) carrying a real signed
    /// envelope, and they already route to the right conversation without this
    /// map. Only interop with the stock Meshtastic app depends on the switch.
    ///
    /// __LORA_PLAINTEXT_IS_NOT_THE_SAME_AS_LORA_2026_11_04__ DEFAULT ON, and
    /// the condition this comment set has been met rather than waived.
    ///
    /// Raw interop text is now stored as `DeliveryMethod.loraPlaintext`,
    /// distinct from the `.lora` an OSHI envelope gets, and the badge draws it
    /// amber as "LoRa · unverified" in all 34 languages. So the packet can be
    /// filed in the contact's thread without the UI claiming anything the
    /// ratchet did not prove: the reader sees it where they expect it AND sees
    /// that it is unauthenticated.
    ///
    /// `object(forKey:) == nil` is the test rather than `bool(forKey:)`,
    /// because `bool(forKey:)` returns false for an ABSENT key and would hold
    /// the new default off for everyone who has never touched the setting —
    /// which is everyone. A user who has set it keeps their choice.
    ///
    /// Unchanged: an ambiguous binding still resolves to nil and still falls
    /// back to `lora!<nodehex>`. An unresolved identity is not an excuse to
    /// guess one, and a manual link is still honoured regardless.
    static var adoptRawTextIntoContactThread: Bool =
        UserDefaults.standard.object(forKey: "oshi.lora.adoptRawText") == nil
            ? true
            : UserDefaults.standard.bool(forKey: "oshi.lora.adoptRawText")

    func conversationKey(forNode num: UInt32) -> String {
        // __LORA_MANUAL_LINK_2026_10_28__ A link the USER made is honoured even
        // while `adoptRawTextIntoContactThread` is off, and that is not a hole
        // in the switch — it is the difference the switch is about. That flag
        // governs whether the APP may infer an identity from a learned binding
        // and file unauthenticated text under it. A manual link is not an
        // inference: a person named this radio, for one node, by hand, after
        // being told what it does. Reported three times from the field as
        // "I have two conversations with the same person and I want one".
        //
        // The inverse still holds absolutely: with no binding of either kind,
        // the fallback thread is used, and nothing is ever guessed.
        if let manual = manualPublicKey(forNode: num) { return manual }
        guard Self.adoptRawTextIntoContactThread else {
            return Self.fallbackConversationKey(num)
        }
        return publicKey(forNode: num) ?? Self.fallbackConversationKey(num)
    }

    /// The identity a PERSON attached to this node, or nil. Ambiguity still
    /// wins: a node whose evidence contradicted itself routes nowhere, even if
    /// someone later vouched for it.
    func manualPublicKey(forNode num: UInt32) -> String? {
        lock.lock(); defer { lock.unlock() }
        guard let b = bindings[Self.nodeKey(num)],
              b.isManual, !b.ambiguous, !b.publicKey.isEmpty else { return nil }
        return b.publicKey
    }

    /// The raw record, ambiguity included — for diagnostics and tests.
    func binding(forNode num: UInt32) -> Binding? {
        lock.lock(); defer { lock.unlock() }
        return bindings[Self.nodeKey(num)]
    }

    /// Which node, if any, is bound to this identity. Used to keep one identity
    /// from being spread over several nodes silently.
    func node(forPublicKey key: String) -> UInt32? {
        let wanted = Self.normalized(key)
        lock.lock(); defer { lock.unlock() }
        for (hex, b) in bindings where !b.ambiguous && Self.normalized(b.publicKey) == wanted {
            return UInt32(hex, radix: 16)
        }
        return nil
    }

    var count: Int {
        lock.lock(); defer { lock.unlock() }
        return bindings.count
    }

    // MARK: Write

    /// __LORA_MANUAL_LINK_2026_10_28__ The USER says this radio is this
    /// contact. Not a proof, and never called from a packet path.
    ///
    /// This is the answer to "I have one person and two conversations": a radio
    /// that only ever sends stock Meshtastic text can never prove an OSHI
    /// identity, because proof means an OSHI envelope that decrypted, and there
    /// is no envelope on that lane. Without this, such a thread stays separate
    /// for ever, which is correct for a stranger's radio and useless for your
    /// own Mac.
    ///
    /// WHAT THE USER IS TAKING ON, stated plainly because the UI has to say it:
    /// a raw text packet's `from` field is an unsigned integer. The firmware
    /// does encrypt a unicast to a node whose public key it holds (observed on
    /// the air: `pkiEncrypted: true`), but **this app cannot see that flag** —
    /// `handleMeshPacket` reads the hop count and nothing else of the envelope —
    /// so OSHI cannot tell such a packet from one sent with the public channel
    /// key. Linking therefore means: text claiming this node number will appear
    /// in that contact's conversation without the ratchet having vouched for
    /// it. That is a reasonable trade for your own radio and a bad one for
    /// someone else's, which is why it is per-node, explicit, and reversible.
    ///
    /// Refuses the same two things `learn` refuses, for the same reasons: a key
    /// that is not an identity key, and a node whose evidence already
    /// contradicted itself. Overwrites a previous MANUAL link (the user may
    /// correct themselves) but never a PROVED one — the ratchet outranks a
    /// person's recollection, and silently replacing proof with assertion is
    /// how a conversation acquires the wrong name.
    @discardableResult
    func link(node num: UInt32, toIdentity publicKey: String) -> LearnResult {
        let trimmed = publicKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.looksLikeIdentityKey(trimmed) else { return .rejected }

        lock.lock()
        let key = Self.nodeKey(num)
        let result: LearnResult
        if let existing = bindings[key] {
            if existing.ambiguous {
                result = .ignoredAmbiguous
            } else if !existing.isManual {
                // Proved already, and to someone else: refuse rather than
                // overwrite. Same identity: nothing to do.
                result = Self.normalized(existing.publicKey) == Self.normalized(trimmed)
                    ? .unchanged : .conflict
            } else if Self.normalized(existing.publicKey) == Self.normalized(trimmed) {
                result = .unchanged
            } else {
                bindings[key] = Binding(publicKey: trimmed, learnedAt: Date(),
                                        ambiguous: false, manual: true)
                result = .bound
            }
        } else {
            bindings[key] = Binding(publicKey: trimmed, learnedAt: Date(),
                                    ambiguous: false, manual: true)
            result = .bound
        }
        let snapshot = bindings
        lock.unlock()
        if result == .bound { persist(snapshot) }
        return result
    }

    /// Undo a manual link — the thread goes back to `lora!<nodehex>`.
    ///
    /// Only ever removes a MANUAL binding. A proved one is evidence, and a
    /// button that quietly discarded evidence would make the map depend on who
    /// pressed what last.
    @discardableResult
    func unlink(node num: UInt32) -> Bool {
        lock.lock()
        let key = Self.nodeKey(num)
        guard let existing = bindings[key], existing.isManual else { lock.unlock(); return false }
        bindings.removeValue(forKey: key)
        let snapshot = bindings
        lock.unlock()
        persist(snapshot)
        return true
    }

    /// Record that `num` has CRYPTOGRAPHICALLY PROVED it speaks for `publicKey`.
    ///
    /// Call this only from a path where the proof exists — today that is a LoRa
    /// OSHI envelope that decrypted. Calling it from anywhere that merely read a
    /// claimed sender field would hand an attacker the ability to file their
    /// messages under someone else's name.
    @discardableResult
    func learn(node num: UInt32, publicKey: String) -> LearnResult {
        let trimmed = publicKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.looksLikeIdentityKey(trimmed) else { return .rejected }

        lock.lock()
        let key = Self.nodeKey(num)
        let existing = bindings[key]
        let result: LearnResult
        if let existing {
            if existing.ambiguous {
                result = .ignoredAmbiguous
            } else if Self.normalized(existing.publicKey) == Self.normalized(trimmed) {
                result = .unchanged
            } else {
                bindings[key] = Binding(publicKey: existing.publicKey,
                                        learnedAt: existing.learnedAt,
                                        ambiguous: true)
                result = .conflict
            }
        } else if let (otherKey, otherBinding) = bindings.first(where: {
            $0.key != key && !$0.value.ambiguous
                && Self.normalized($0.value.publicKey) == Self.normalized(trimmed)
        }) {
            // 🔧 FIX (2026-08-10): the conflict rule only ran one way — one node
            // claiming two identities. The INVERSE was explicitly allowed, and it
            // is the direction an attacker uses: bind their own node to a
            // contact's already-bound identity and every raw text they transmit
            // is filed in that contact's conversation. Nothing about a second
            // node arriving under an existing identity was suspicious to the old
            // rule; it simply became a second valid route into the thread.
            //
            // Mark BOTH ambiguous. Which of the two is the impostor is not
            // knowable here, and picking one would be a coin flip on whose
            // messages get attributed to a real contact. Both fall back to their
            // own `lora!` threads — today's behaviour, and recoverable via
            // `forget(node:)`.
            bindings[otherKey] = Binding(publicKey: otherBinding.publicKey,
                                         learnedAt: otherBinding.learnedAt,
                                         ambiguous: true)
            bindings[key] = Binding(publicKey: trimmed, learnedAt: Date(), ambiguous: true)
            result = .conflict
        } else {
            bindings[key] = Binding(publicKey: trimmed, learnedAt: Date(), ambiguous: false)
            result = .bound
        }
        let snapshot = bindings
        lock.unlock()

        if result == .bound || result == .conflict { persist(snapshot) }
        return result
    }

    /// Drop what we know about a node. The only way out of `ambiguous`, and the
    /// honest response to a user saying "that isn't them".
    func forget(node num: UInt32) {
        lock.lock()
        bindings.removeValue(forKey: Self.nodeKey(num))
        let snapshot = bindings
        lock.unlock()
        persist(snapshot)
    }

    func removeAll() {
        lock.lock()
        bindings.removeAll()
        let snapshot = bindings
        lock.unlock()
        persist(snapshot)
    }

    // MARK: Internals

    /// Fold base64url/padding variants before comparing, so the same key spelled
    /// two ways does not read as a conflict and lock a node out of routing.
    /// Same folding as `MeshtasticManager.normalizeKey` / `BlockedContactsManager`.
    private static func normalized(_ key: String) -> String {
        MeshtasticManager.normalizeKey(key)
    }

    private func persist(_ snapshot: [String: Binding]) {
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        defaults.set(data, forKey: Self.storageKey)
    }
}
