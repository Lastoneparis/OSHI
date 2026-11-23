//
//  OfflineOnlyConversation.swift
//  OSHI
//
//  "This conversation never touches the internet."
//
//  A per-conversation switch — one contact, or one group — that takes the relay
//  away from everything OSHI sends there. What is left is the local mesh
//  (Bluetooth / Wi-Fi between nearby phones) and the LoRa radio.
//
//  ══ WHAT THIS CAN AND CANNOT PROMISE ══
//
//  It can promise one thing completely: nothing THIS device sends in that
//  conversation goes over the relay. That is a local decision, enforced on the
//  send path, and it holds whatever anyone else does.
//
//  It cannot promise the same of the other participants. There is no way to
//  make someone else's phone refuse the internet — a peer on an older build
//  does not know the request exists, and a peer who wants the relay will use
//  it. So the switch is enforced locally and REQUESTED of the others, and the
//  UI says which of them have confirmed. A number that is smaller than the
//  member count is the honest state of the conversation, not a bug to hide.
//
//  Saying "everyone is offline-only" when a silent member is still relaying
//  would be a privacy promise the app cannot keep, which is worse than no
//  promise at all. Hence `confirmedBy`, and hence the UI showing it.
//
//  ══ WIRE ══
//
//  `🔒OFFLINE_ONLY🔒{"v":1,"on":true,"at":1761220000}` — declared once in
//  `OSHIControlPayload.catalog`, like every other sentinel. `.rendered`, not
//  `.silent`: a change to how a conversation is routed is exactly the kind of
//  thing a reader should be able to see happen, and when, and by whom.
//
//  Timestamps are explicit Unix SECONDS. `Date`'s own encoding is seconds since
//  2001 on Apple platforms, and Android reads this JSON too — the same trap the
//  poll payload documents.
//
//  Android: `data/model/OfflineOnlyConversation.kt` and
//  `service/OfflineOnlyStore.kt`.
//

import Foundation
import Combine

// MARK: - The wire

/// One participant asking for a conversation to stop using the relay.
struct OfflineOnlyRequest: Codable, Equatable {

    static let currentVersion = 1

    let version: Int
    /// Turning it on, or back off. Both travel: a conversation that silently
    /// stayed restricted after someone lifted it would strand messages.
    let on: Bool
    let atUnix: Double
    /// An ANSWER to someone else's request, not a request of our own.
    ///
    /// Without this the two sides ping-pong for ever: an echo carrying a fresh
    /// timestamp reads as a new request, which clears the confirmations, which
    /// asks everyone again. An ack is never echoed, and it carries the `at` of
    /// the request it answers so a stale one cannot be counted for a later
    /// change.
    let isAck: Bool

    var at: Date { Date(timeIntervalSince1970: atUnix) }

    enum CodingKeys: String, CodingKey {
        case version = "v"
        case on
        case atUnix = "at"
        case isAck = "ack"
    }

    init(on: Bool, at: Date = Date(), isAck: Bool = false) {
        self.version = Self.currentVersion
        self.on = on
        self.atUnix = at.timeIntervalSince1970
        self.isAck = isAck
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = try c.decode(Int.self, forKey: .version)
        on = try c.decode(Bool.self, forKey: .on)
        atUnix = try c.decode(Double.self, forKey: .atUnix)
        // Absent on a plain request, and on every payload written before acks
        // existed. Absent means "this is a request", which is the safe reading:
        // the worst it costs is one confirmation that is not counted.
        isAck = (try? c.decodeIfPresent(Bool.self, forKey: .isAck)) as? Bool ?? false
    }

    /// The answer to send back for this request.
    func acknowledgement() -> OfflineOnlyRequest {
        OfflineOnlyRequest(on: on, at: at, isAck: true)
    }

    static var prefix: String { OSHIControlPayload.offlineOnlyPrefix }

    static func isRequest(_ text: String) -> Bool {
        OSHIControlPayload.body(of: text, expecting: prefix) != nil
    }

    func toMessageString() -> String {
        guard let data = try? JSONEncoder().encode(self),
              let json = String(data: data, encoding: .utf8) else {
            return Self.prefix
        }
        return Self.prefix + json
    }

    static func fromMessageString(_ text: String) -> OfflineOnlyRequest? {
        guard let body = OSHIControlPayload.body(of: text, expecting: prefix),
              let data = body.data(using: .utf8),
              let req = try? JSONDecoder().decode(OfflineOnlyRequest.self, from: data),
              req.version <= currentVersion else { return nil }
        return req
    }
}

// MARK: - The decision

/// Whether the relay may carry something, for one conversation.
///
/// Pure on purpose: the send path has to consult this from several places, and
/// a rule that needs a store, a network and a group manager to answer is a rule
/// nobody can test.
enum OfflineOnlyPolicy {

    /// `globalInternetAllowed` is `TransportPolicy.internetAllowedForSending` —
    /// the app-wide switch. The two compose the only way they can: either one
    /// saying no means no. A per-conversation switch may RESTRICT, never widen,
    /// so turning a conversation back to normal cannot quietly re-enable the
    /// internet for someone who turned it off for the whole app.
    static func internetAllowed(globalInternetAllowed: Bool,
                                conversationIsOfflineOnly: Bool) -> Bool {
        globalInternetAllowed && !conversationIsOfflineOnly
    }

    /// What the UI shows under the switch for a group.
    ///
    /// `confirmed` counts OTHER members who echoed the request back; we are
    /// never in it, because our own compliance is not in question. The count is
    /// deliberately out of `otherMemberCount` rather than the whole group for
    /// the same reason.
    static func confirmationSummary(confirmed: Int, otherMemberCount: Int) -> (confirmed: Int, total: Int) {
        (max(0, min(confirmed, otherMemberCount)), max(0, otherMemberCount))
    }
}

// MARK: - The store

/// Which conversations are restricted, and who has confirmed.
///
/// Sealed at rest like `PollStore`, for the same reason: the list of people you
/// have chosen to speak to only off-grid is user content, and `UserDefaults`
/// rides along in iCloud and Finder backups in cleartext.
@MainActor
final class OfflineOnlyStore: ObservableObject {

    static let shared = OfflineOnlyStore()

    /// conversation id -> state. The id is the contact's canonical public key
    /// for a 1:1, and the group's uuidString for a group — the same two kinds
    /// of key every other per-conversation thing in this app uses.
    @Published private(set) var states: [String: State] = [:]

    struct State: Codable, Equatable {
        var on: Bool
        var changedAtUnix: Double
        /// Canonical public keys of the OTHER participants who have echoed the
        /// request. Never contains us.
        var confirmedBy: Set<String>
    }

    private var saveTask: Task<Void, Never>?

    private static var fileURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("offline_only_conversations.json")
    }

    private init() { load() }

    // MARK: Reading

    func isOfflineOnly(_ conversationId: String) -> Bool {
        states[canonical(conversationId)]?.on ?? false
    }

    func confirmedCount(_ conversationId: String) -> Int {
        states[canonical(conversationId)]?.confirmedBy.count ?? 0
    }

    // MARK: Writing

    /// We turned it on or off. Clears the confirmations: a new request has to be
    /// answered again, and carrying the old set forward would show a group as
    /// agreed when nobody has seen the latest change.
    func set(_ on: Bool, for conversationId: String, at: Date = Date()) {
        let key = canonical(conversationId)
        states[key] = State(on: on, changedAtUnix: at.timeIntervalSince1970, confirmedBy: [])
        scheduleSave()
    }

    /// A participant echoed the request back.
    ///
    /// Only counted while the conversation is actually restricted: a stale
    /// confirmation arriving after someone lifted the restriction must not
    /// resurrect it, and must not inflate the count of a later one.
    /// Does this acknowledgement answer the request we are actually waiting on?
    ///
    /// Matching on `on` alone would count an ack for a previous, opposite state
    /// after a quick off-then-on. The timestamp pins it to one specific change;
    /// the tolerance is there because the value makes a round trip through JSON
    /// and back on another platform.
    func answersCurrentRequest(_ req: OfflineOnlyRequest, for conversationId: String) -> Bool {
        guard let s = states[canonical(conversationId)], s.on, req.on == s.on else { return false }
        return abs(req.atUnix - s.changedAtUnix) < 1.0
    }

    func noteConfirmation(from publicKey: String, for conversationId: String) {
        let key = canonical(conversationId)
        guard var s = states[key], s.on else { return }
        let who = canonical(publicKey)
        guard !s.confirmedBy.contains(who) else { return }
        s.confirmedBy.insert(who)
        states[key] = s
        scheduleSave()
    }

    /// Someone else asked for this conversation to be restricted. We honour it
    /// locally — that is the whole point of the request — and the caller echoes
    /// a confirmation back so their side can show us as agreed.
    func applyIncoming(_ req: OfflineOnlyRequest, for conversationId: String, now: Date = Date()) {
        let key = canonical(conversationId)
        // __OFFLINE_ONLY_FUTURE_AT_2026_11_20__ `at` is the peer's clock, unbounded.
        // A request stamped in the year 3000 would outrank every later change
        // ("older than what we have") forever. Clamp to now + 5 min (clock skew).
        let at = Self.clampedPeerTime(req.atUnix, now: now)
        if let existing = states[key], existing.changedAtUnix > at {
            // Older than what we already have. Mesh delivery is not ordered, so
            // this is ordinary, not an error.
            return
        }
        states[key] = State(on: req.on, changedAtUnix: at, confirmedBy: [])
        scheduleSave()
    }

    /// Largest clock skew accepted from a peer's `at`.
    nonisolated static let maxPeerClockSkew: TimeInterval = 300

    nonisolated static func clampedPeerTime(_ atUnix: Double, now: Date) -> Double {
        let limit = now.timeIntervalSince1970 + maxPeerClockSkew
        guard atUnix.isFinite else { return now.timeIntervalSince1970 }
        return min(atUnix, limit)
    }

    // MARK: Plumbing

    /// Public keys arrive in both base64 spellings; fold them so a conversation
    /// cannot end up with two entries that disagree. Group ids are uuidStrings
    /// and pass through unchanged.
    private func canonical(_ id: String) -> String {
        MessageManager.canonicalIdentity(id)
    }

    private struct Disk: Codable { let states: [String: State] }

    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled else { return }
            await self?.save()
        }
    }

    private func save() {
        do {
            let data = try JSONEncoder().encode(Disk(states: states))
            try AtRestFile.write(data, to: Self.fileURL)
        } catch {
            OshiLog.storage.info("[OfflineOnlyStore] could not seal the state: \(error)")
        }
    }

    private func load() {
        guard let data = try? AtRestFile.read(Self.fileURL),
              let disk = try? JSONDecoder().decode(Disk.self, from: data) else { return }
        states = disk.states
        OshiLog.storage.infoPublic("[OfflineOnlyStore] loaded \(disk.states.count) conversation(s)")
    }
}

// MARK: - The gate the send path actually calls

extension TransportPolicy {

    /// __OFFLINE_ONLY_CONVERSATION_2026_10_23__ May the relay carry something
    /// for THIS conversation?
    ///
    /// The app-wide switch alone was never enough: it is all-or-nothing, and
    /// the person who wants one conversation kept off the internet does not
    /// want the other forty to stop working. Every gate on the send path that
    /// knows who it is sending to should ask this instead.
    ///
    /// `@MainActor` because the store is, and the store is because it is
    /// `@Published` — the UI has to redraw the moment the switch moves. The
    /// send path is async everywhere it matters, so the hop costs nothing.
    ///
    /// `conversationId` is the contact's public key for a 1:1 and the group's
    /// `uuidString` for a group, the same two kinds of key every other
    /// per-conversation thing in this app is filed under.
    @MainActor
    static func internetAllowedForSending(inConversation conversationId: String) -> Bool {
        OfflineOnlyPolicy.internetAllowed(
            globalInternetAllowed: internetAllowedForSending,
            conversationIsOfflineOnly: OfflineOnlyStore.shared.isOfflineOnly(conversationId))
    }
}

// MARK: - Sending and answering

extension MessageManager {

    /// __OFFLINE_ONLY_CONVERSATION_2026_10_23__ Ask the other side of a 1:1 to
    /// stop using the relay here too, and record our own state first.
    ///
    /// Our state is set BEFORE the request goes out, deliberately: the request
    /// itself must travel under the new rule. Setting it afterwards would send
    /// the one message that asks for the relay to stop over the relay.
    @MainActor
    func setOfflineOnly(_ on: Bool,
                        forContact publicKey: String,
                        identityManager: IdentityManager,
                        meshManager: MeshNetworkManager) {
        let store = OfflineOnlyStore.shared
        store.set(on, for: publicKey)
        let req = OfflineOnlyRequest(on: on)
        sendMessage(content: req.toMessageString(),
                    recipientAddress: publicKey,
                    identityManager: identityManager,
                    meshManager: meshManager,
                    skipLocalMessage: false)
        OshiLog.messaging.infoPublic("🔒 offline-only \(on ? "on" : "off") requested for a 1:1")
    }

    /// A request or an answer arrived in a 1:1.
    ///
    /// Returns true when the payload was an ANSWER, which the caller must not
    /// store: an ack is marked `.rendered` like the request it answers, so
    /// letting it through would print "this conversation is now offline only" a
    /// second time in the other person's thread. The request itself is allowed
    /// to fall through and be rendered — seeing when a conversation was taken
    /// off the relay, and by whom, is the point of it being rendered at all.
    /// The receive sites have no managers in scope — they are deep inside
    /// `receiveMessage`, which is handed an envelope and nothing else. The
    /// shared references `DeliveryReceiptManager` already answers receipts
    /// through are the same ones this needs, for the same reason: an answer
    /// that only works when a view happens to be on screen is not an answer.
    @MainActor
    @discardableResult
    func handleOfflineOnlyPayload(_ text: String, from senderPublicKey: String) -> Bool {
        guard let req = OfflineOnlyRequest.fromMessageString(text) else { return false }
        let identityManager = MessageManager.sharedIdentityManager
        let meshManager = MessageManager.sharedMeshManager
        let store = OfflineOnlyStore.shared
        let conversationId = senderPublicKey

        if req.isAck {
            if store.answersCurrentRequest(req, for: conversationId) {
                store.noteConfirmation(from: senderPublicKey, for: conversationId)
                OshiLog.messaging.infoPublic("🔒 offline-only confirmed by a peer")
            }
            return true    // swallow it: an answer is not a line in the thread
        }

        store.applyIncoming(req, for: conversationId)
        // Answer once, and never echo an answer — see `isAck`. Without both
        // managers there is nobody to send through; the request is still
        // honoured locally, which is the half that actually protects anything.
        if let identityManager, let meshManager {
            let ack = req.acknowledgement()
            sendMessage(content: ack.toMessageString(),
                        recipientAddress: senderPublicKey,
                        identityManager: identityManager,
                        meshManager: meshManager,
                        skipLocalMessage: true)
        } else {
            OshiLog.messaging.info("🔒 offline-only honoured locally; no manager to answer with yet")
        }
        return false       // let the request render
    }
}
