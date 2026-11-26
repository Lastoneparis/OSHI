//
//  AdvancedChatPrivacy.swift
//  OSHI
//
//  __ADVANCED_CHAT_PRIVACY_2026_11_26__ "Advanced chat privacy" (WhatsApp's name
//  for it), per conversation — one contact, or one group.
//
//  When ANY participant turns it on, for everyone in that conversation:
//    1. received media is never saved to Photos automatically, and "Save to
//       Photos" / "Save to Files" / share-out disappears for other people's media;
//    2. the conversation is left out of the message export;
//    3. on-device AI (smart replies, the AI shortcuts, scheduled-message
//       generation, group auto-translate) is given NOTHING from it;
//    4. the thread is covered while the screen is recorded or mirrored (iOS), and
//       the window is kept out of other apps' captures (Mac, `sharingType=none`);
//    5. forwarding stays allowed.
//
//  ══ WHAT THIS CAN AND CANNOT PROMISE ══
//
//  Same honesty rule as `OfflineOnlyConversation.swift`: the switch is ENFORCED
//  by every OSHI build that knows it, and REQUESTED of the others. It cannot stop
//  a camera pointed at the screen, a modified client, or an old build that has
//  never heard of it — the info sheet says so.
//
//  ══ WIRE (shared with Android, do not change) ══
//
//  `🛡ADV_PRIVACY🛡{"v":1,"on":true,"at":1761220000}` — declared ONCE in
//  `OSHIControlPayload.catalog`. `at` is integer Unix SECONDS (never `Date`'s
//  2001-based encoding). 1:1 → to the peer; group → the normal group fan-out.
//  Last writer wins by `at`; a peer's `at` is clamped to now + 300 s
//  (`OfflineOnlyStore.maxPeerClockSkew`) so a clock in the year 3000 cannot pin
//  the setting forever. No acknowledgement: the notice row in the thread IS the
//  record of who changed it and when.
//
//  ══ OLD CLIENTS ══
//
//  iOS 1.0.43 (on sale) and every build before this one render an unknown
//  sentinel as raw text. So the payload goes ONLY to a peer that provably reads
//  it (`AdvancedChatPrivacyPolicy.peerReads`): its bundle advertises
//  `RatchetHealSignal.Caps.advancedPrivacy`, or it has itself sent us one. The
//  local state, and our own notice row, do not depend on that: this device
//  enforces the switch either way.
//

import Foundation
import Combine

// MARK: - The wire

struct AdvancedPrivacyChange: Equatable {

    static let currentVersion = 1

    let version: Int
    let on: Bool
    /// Integer Unix seconds, as on the wire.
    let at: Int64

    init(on: Bool, at: Int64, version: Int = AdvancedPrivacyChange.currentVersion) {
        self.version = version
        self.on = on
        self.at = at
    }

    init(on: Bool, date: Date = Date()) {
        self.init(on: on, at: Int64(date.timeIntervalSince1970.rounded(.down)))
    }

    static var prefix: String { OSHIControlPayload.advancedPrivacyPrefix }

    static func isPayload(_ text: String) -> Bool {
        OSHIControlPayload.body(of: text, expecting: prefix) != nil
    }

    /// Written by hand rather than by `JSONEncoder`, so the three keys come out
    /// in the documented order and `at` is always an integer — Android parses
    /// it as a Long.
    func toMessageString() -> String {
        "\(Self.prefix){\"v\":\(version),\"on\":\(on ? "true" : "false"),\"at\":\(at)}"
    }

    /// nil for anything that is not a readable v1 payload. A fractional `at`
    /// (another client writing a Double) is accepted and floored; a missing or
    /// non-boolean `on` is rejected rather than guessed — guessing either way
    /// would silently flip someone else's decision.
    static func fromMessageString(_ text: String) -> AdvancedPrivacyChange? {
        guard let body = OSHIControlPayload.body(of: text, expecting: prefix),
              let data = body.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let v = (obj["v"] as? NSNumber)?.intValue,
              v >= 1, v <= currentVersion,
              let onNumber = obj["on"] as? NSNumber,
              CFGetTypeID(onNumber) == CFBooleanGetTypeID(),
              let atNumber = obj["at"] as? NSNumber
        else { return nil }
        let atDouble = atNumber.doubleValue
        guard atDouble.isFinite, atDouble >= 0, atDouble < 1e15 else { return nil }
        return AdvancedPrivacyChange(on: onNumber.boolValue, at: Int64(atDouble.rounded(.down)), version: v)
    }
}

// MARK: - The rules (pure, unit-tested)

enum AdvancedChatPrivacyPolicy {

    struct State: Codable, Equatable {
        var on: Bool
        /// Unix seconds of the change that set this value (after clamping).
        var at: Int64
        /// Canonical public key of whoever made the change; nil for an
        /// unknown/legacy author.
        var by: String?
    }

    /// Last writer wins by `at`, with the peer's clock clamped to now + skew.
    ///
    /// Equal `at` with opposite values (two people toggling in the same second)
    /// resolves to ON: of the two outcomes, the one that protects is the one a
    /// participant cannot be surprised by. Returns nil when the change loses.
    static func resolve(existing: State?, incoming: AdvancedPrivacyChange, by author: String?, now: Date) -> State? {
        let at = clampedAt(incoming.at, now: now)
        if let existing {
            if at < existing.at { return nil }
            if at == existing.at {
                if existing.on == incoming.on { return nil }       // same decision, nothing new
                if existing.on && !incoming.on { return nil }      // tie → ON wins
            }
        }
        return State(on: incoming.on, at: at, by: author)
    }

    static func clampedAt(_ at: Int64, now: Date) -> Int64 {
        Int64(OfflineOnlyStore.clampedPeerTime(Double(at), now: now).rounded(.down))
    }

    // MARK: What the switch forbids

    /// May on-device AI see this conversation at all?
    static func allowsAI(protected: Bool) -> Bool { !protected }

    /// The context handed to an AI feature for this conversation: nothing at all
    /// when the conversation is protected.
    static func aiContext(_ context: String?, protected: Bool) -> String? {
        allowsAI(protected: protected) ? context : nil
    }

    /// May this conversation be written into a message export?
    static func allowsExport(protected: Bool) -> Bool { !protected }

    /// May THIS piece of media leave OSHI (Save to Photos / Files / share)?
    /// Your own media stays yours; other people's does not, while the switch is on.
    /// `isFromMe == nil` (the viewer does not know who sent it) is treated as
    /// someone else's.
    static func allowsSavingMedia(protected: Bool, isFromMe: Bool?) -> Bool {
        !protected || isFromMe == true
    }

    /// Automatic saving of received media to Photos. OSHI has no such feature on
    /// iOS today; this is the gate any future one must consult.
    static func allowsAutoSave(protected: Bool) -> Bool { !protected }

    /// Forwarding stays allowed, as in WhatsApp. Declared so the decision is
    /// written down rather than an omission.
    static func allowsForwarding(protected: Bool) -> Bool { true }

    // MARK: Who may receive the payload

    /// Does `peer` read `🛡ADV_PRIVACY🛡`? Unknown means no: a false "no" costs
    /// one peer not enforcing a switch it could not have enforced anyway; a false
    /// "yes" puts raw JSON in someone's thread (the 1.0.43 receipt bug, N11).
    static func peerReads(accountCaps: Int, sentUsOne: Bool) -> Bool {
        sentUsOne || RatchetHealSignal.Caps.has(accountCaps, RatchetHealSignal.Caps.advancedPrivacy)
    }

    static func peerReadsNow(_ peer: String) -> Bool {
        peerReads(accountCaps: RatchetHealSignal.Caps.of(peer: peer),
                  sentUsOne: Readers.contains(peer))
    }

    /// Peers that have sent us an `🛡ADV_PRIVACY🛡` payload. Bounded, persisted.
    enum Readers {
        private static let key = "oshi.advPrivacy.readers.v1"
        private static let cap = 2000
        nonisolated(unsafe) static var defaults: UserDefaults = .standard

        static func contains(_ peer: String) -> Bool {
            (defaults.stringArray(forKey: key) ?? []).contains(MessageManager.canonicalIdentity(peer))
        }

        static func note(_ peer: String) {
            let n = MessageManager.canonicalIdentity(peer)
            guard !n.isEmpty else { return }
            var list = defaults.stringArray(forKey: key) ?? []
            guard !list.contains(n) else { return }
            list.append(n)
            if list.count > cap { list.removeFirst(list.count - cap) }
            defaults.set(list, forKey: key)
        }
    }

    // MARK: Export

    /// The export snapshot without the protected conversations. 1:1 messages are
    /// filed under the OTHER party's key; group messages under the group's
    /// uuidString — the two keys the store uses.
    static func filterForExport(_ s: MessageExportV2.Snapshot,
                                isProtected: (String) -> Bool) -> MessageExportV2.Snapshot {
        let me = MessageManager.canonicalIdentity(s.myKey)
        let direct = s.direct.filter { m in
            let sender = MessageManager.canonicalIdentity(m.senderPublicKey.isEmpty ? m.senderAddress : m.senderPublicKey)
            let peer = sender == me
                ? MessageManager.canonicalIdentity(m.recipientPublicKey.isEmpty ? m.recipientAddress : m.recipientPublicKey)
                : sender
            return !isProtected(peer)
        }
        var out = s
        out.direct = direct
        out.groups = s.groups.filter { !isProtected($0.message.groupId.uuidString) }
        return out
    }
}

// MARK: - The store

/// Which conversations are protected. Sealed at rest like `OfflineOnlyStore`.
@MainActor
final class AdvancedChatPrivacyStore: ObservableObject {

    static let shared = AdvancedChatPrivacyStore()

    typealias State = AdvancedChatPrivacyPolicy.State

    /// conversation id → state. The contact's canonical public key for a 1:1,
    /// the group's uuidString for a group.
    @Published private(set) var states: [String: State] = [:]

    private var saveTask: Task<Void, Never>?
    private let persist: Bool

    private static var fileURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("advanced_chat_privacy.json")
    }

    private init() {
        persist = true
        load()
    }

    /// An in-memory store for tests: never reads or writes the sealed file.
    init(inMemory: Bool) {
        persist = !inMemory
        if persist { load() }
    }

    // MARK: Reading

    func isOn(_ conversationId: String) -> Bool {
        states[Self.canonical(conversationId)]?.on ?? false
    }

    func state(_ conversationId: String) -> State? {
        states[Self.canonical(conversationId)]
    }

    // MARK: Writing

    /// Apply a change (ours or a peer's). Returns true when it took effect.
    @discardableResult
    func apply(_ change: AdvancedPrivacyChange, by author: String?, for conversationId: String,
               now: Date = Date()) -> Bool {
        let key = Self.canonical(conversationId)
        guard let next = AdvancedChatPrivacyPolicy.resolve(existing: states[key], incoming: change,
                                                           by: author.map(Self.canonical), now: now)
        else { return false }
        states[key] = next
        Self.mirror.set(key, next.on)
        scheduleSave()
        return true
    }

    /// Thread-safe read for code that is not on the main actor (the export
    /// snapshot is taken on main, but the group fan-out and the AI helpers may
    /// not be). Mirrors `states` exactly.
    nonisolated static func isOnSnapshot(_ conversationId: String) -> Bool {
        mirror.get(canonical(conversationId))
    }

    nonisolated static func canonical(_ id: String) -> String {
        // Group ids are uuidStrings and pass through unchanged.
        if UUID(uuidString: id) != nil { return id.uppercased() }
        return MessageManager.canonicalIdentity(id)
    }

    // MARK: Plumbing

    private final class Mirror: @unchecked Sendable {
        private let lock = NSLock()
        private var on: [String: Bool] = [:]
        func set(_ k: String, _ v: Bool) { lock.lock(); on[k] = v; lock.unlock() }
        func get(_ k: String) -> Bool { lock.lock(); defer { lock.unlock() }; return on[k] ?? false }
        func replace(_ all: [String: Bool]) { lock.lock(); on = all; lock.unlock() }
    }
    nonisolated private static let mirror = Mirror()

    private struct Disk: Codable { let states: [String: State] }

    private func scheduleSave() {
        guard persist else { return }
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled else { return }
            self?.save()
        }
    }

    private func save() {
        do {
            let data = try JSONEncoder().encode(Disk(states: states))
            try AtRestFile.write(data, to: Self.fileURL)
        } catch {
            OshiLog.storage.info("[AdvancedChatPrivacyStore] could not seal the state: \(error)")
        }
    }

    private func load() {
        guard let data = try? AtRestFile.read(Self.fileURL),
              let disk = try? JSONDecoder().decode(Disk.self, from: data) else { return }
        states = disk.states
        Self.mirror.replace(disk.states.mapValues(\.on))
        OshiLog.storage.infoPublic("[AdvancedChatPrivacyStore] loaded \(disk.states.count) conversation(s)")
    }
}

// MARK: - 1:1 — sending and receiving

extension MessageManager {

    /// Turn advanced chat privacy on or off in a 1:1.
    ///
    /// The local state and our own notice row are written first and always; the
    /// payload only goes to a peer that reads it (see the file header). Returns
    /// whether the peer was sent the change.
    @MainActor
    @discardableResult
    func setAdvancedChatPrivacy(_ on: Bool,
                                forContact publicKey: String,
                                identityManager: IdentityManager,
                                meshManager: MeshNetworkManager,
                                now: Date = Date()) -> Bool {
        let me = identityManager.publicKey
        let change = AdvancedPrivacyChange(on: on, date: now)
        AdvancedChatPrivacyStore.shared.apply(change, by: me, for: publicKey, now: now)

        let peer = MessageManager.canonicalIdentity(publicKey)
        let content = change.toMessageString()
        let row = SecureMessage(
            id: UUID().uuidString,
            senderAddress: me,
            recipientAddress: peer,
            encryptedContent: EncryptedMessage(ciphertext: "", signature: "", senderPublicKey: me, timestamp: now),
            timestamp: now,
            isRead: true,
            deliveryStatus: .sent,
            senderPublicKey: me,
            recipientPublicKey: peer,
            plaintextContent: content,
            deliveryMethod: nil
        )
        addMessage(row)

        guard AdvancedChatPrivacyPolicy.peerReadsNow(publicKey) else {
            OshiLog.messaging.infoPublic("🛡 advanced privacy \(on ? "on" : "off") — kept local: peer not known to read it")
            return false
        }
        _ = sendMessage(content: content,
                    recipientAddress: publicKey,
                    identityManager: identityManager,
                    meshManager: meshManager,
                    skipLocalMessage: true)
        OshiLog.messaging.infoPublic("🛡 advanced privacy \(on ? "on" : "off") sent to a 1:1 peer")
        return true
    }

    /// A peer's change arrived in a 1:1. The message itself is NOT swallowed: it
    /// is the notice row in the thread. Safe to call more than once for the same
    /// payload (a second application is a no-op).
    @MainActor
    func handleAdvancedPrivacyPayload(_ text: String, from senderPublicKey: String) {
        guard let change = AdvancedPrivacyChange.fromMessageString(text) else { return }
        AdvancedChatPrivacyPolicy.Readers.note(senderPublicKey)
        if AdvancedChatPrivacyStore.shared.apply(change, by: senderPublicKey, for: senderPublicKey) {
            OshiLog.messaging.infoPublic("🛡 advanced privacy \(change.on ? "on" : "off") applied from a 1:1 peer")
        }
    }
}

// MARK: - Groups — sending and receiving

extension MessageGroupManager {

    /// Any member may turn it on or off. Goes through the normal group send, so
    /// it is stored locally as our notice row; `broadcastGroupMessageViaRelay`
    /// leaves out the members that do not read it.
    @discardableResult
    func setAdvancedChatPrivacy(_ on: Bool, groupId: UUID, senderPublicKey: String, now: Date = Date()) -> MessageSendOutcome {
        let change = AdvancedPrivacyChange(on: on, date: now)
        AdvancedChatPrivacyStore.shared.apply(change, by: senderPublicKey, for: groupId.uuidString, now: now)
        return sendMessage(to: groupId, content: change.toMessageString(), senderPublicKey: senderPublicKey)
    }

    /// Called from `receiveGroupMessage` and the sent-copy path. Returns true
    /// when `text` was an advanced-privacy payload.
    @discardableResult
    func applyIncomingAdvancedPrivacy(_ text: String, from sender: String, groupId: UUID) -> Bool {
        guard let change = AdvancedPrivacyChange.fromMessageString(text) else { return false }
        AdvancedChatPrivacyPolicy.Readers.note(sender)
        AdvancedChatPrivacyStore.shared.apply(change, by: sender, for: groupId.uuidString)
        return true
    }

    /// The members of `group` that do NOT provably read the payload — for the
    /// "N members will not apply it" line under the switch.
    func advancedPrivacyNonReaders(_ group: MessageGroup, me: String) -> [String] {
        let meC = MessageManager.canonicalIdentity(me)
        return group.members.map(\.publicKey).filter {
            MessageManager.canonicalIdentity($0) != meC && !AdvancedChatPrivacyPolicy.peerReadsNow($0)
        }
    }
}
