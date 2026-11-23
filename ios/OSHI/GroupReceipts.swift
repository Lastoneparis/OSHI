//
//  GroupReceipts.swift
//  OSHI — who, in a group, actually received and read one message.
//
//  __GROUP_RECEIPTS_2026_11_02__ (N8) Asked for as: "on a long press on your
//  own message in a group, a button to see who has read it and who has
//  received it."
//
//  WHAT WAS THERE BEFORE: nothing. `GroupMessage.deliveryStatus` carries the
//  SENDER's own view — pending, sent, failed — and its own comment says so in
//  as many words: "Groups have no per-recipient delivered/read receipts, so
//  `.delivered` is [unused]". The 1:1 receipts are no help either, because
//  `📖READ_RECEIPT📖` carries no message id at all: it means "everything in
//  this conversation is read", which cannot answer a question about one
//  message among many, let alone about one member among several.
//
//  THE SHAPE, AND WHY
//
//  * The receipt goes to the SENDER ONLY, as an ordinary 1:1 control message —
//    never fanned out to the group. Fanning out would cost N² messages per
//    group message, and would tell every member when every other member read
//    it, which nobody asked for and which is a far bigger disclosure than the
//    feature needs.
//  * It names the message, so the answer is per-message rather than
//    per-conversation. That is the whole difference from the 1:1 receipt.
//  * Two kinds, not one. "Received" and "read" answer different questions —
//    "did it get there" and "did they look" — and the request names both.
//  * READ is suppressed when the user has read receipts turned off. Delivery
//    is not: it reports that a device accepted bytes, which the sender can
//    already infer from the relay, whereas "read" is about a person.
//
//  Deliberately NOT a new transport. It rides the existing control-sentinel
//  lane, which is already silent, already filtered out of the message list,
//  already excluded from delivery-receipt ping-pong, and already carried by
//  every path a 1:1 message can take — v2, mesh and radio alike.
//

import Foundation

enum GroupReceipts {

    /// The sentinel. Registered in `OSHIControlPayload.catalog` as `.silent`;
    /// adding it anywhere else would make it render as a message.
    static let prefix = "📑GROUP_RECEIPT📑"

    enum Kind: String, Codable {
        /// The message reached this member's device and was decrypted.
        case received = "d"
        /// The member actually opened the thread and saw it.
        case read = "r"
    }

    // MARK: - Wire

    /// `📑GROUP_RECEIPT📑{"g":"<uuid>","m":"<msgId>","k":"d"|"r","t":<epoch>}`
    ///
    /// One message per receipt rather than a batch: a batch would need its own
    /// size bound and partial-failure story, and these are small and rare
    /// enough that the simple form is the honest trade.
    static func encode(groupId: UUID, msgId: String, kind: Kind, at: Date = Date()) -> String? {
        let body: [String: Any] = [
            "g": groupId.uuidString,
            "m": msgId,
            "k": kind.rawValue,
            "t": Int(at.timeIntervalSince1970),
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: body),
              let json = String(data: data, encoding: .utf8) else { return nil }
        return prefix + json
    }

    struct Parsed: Equatable {
        let groupId: UUID
        let msgId: String
        let kind: Kind
        let at: Date
    }

    /// nil for anything that is not one of ours, or that is malformed.
    ///
    /// Strict about the message id: it is used as a dictionary key that a
    /// remote peer supplies, so an empty or absurd one is refused rather than
    /// stored. 128 is far above any id this app mints (a UUID string is 36).
    static func parse(_ text: String) -> Parsed? {
        guard text.hasPrefix(prefix) else { return nil }
        let json = String(text.dropFirst(prefix.count))
        guard let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let g = obj["g"] as? String, let groupId = UUID(uuidString: g),
              let msgId = obj["m"] as? String, !msgId.isEmpty, msgId.count <= 128,
              let k = obj["k"] as? String, let kind = Kind(rawValue: k) else { return nil }
        let ts = (obj["t"] as? Double) ?? Date().timeIntervalSince1970
        return Parsed(groupId: groupId, msgId: msgId, kind: kind, at: Date(timeIntervalSince1970: ts))
    }

    // MARK: - Store

    /// What one member did with one message.
    struct MemberState: Codable, Equatable {
        var receivedAt: Date?
        var readAt: Date?
    }

    /// Per-message, per-member state for OUR OWN outgoing group messages.
    ///
    /// Only our own: a receipt is addressed to the sender, so this device
    /// never learns anything about a message somebody else sent, and the
    /// store cannot grow on traffic we merely observed.
    final class Store {
        static let shared = Store()

        private let defaultsKey = "oshi.group.receipts.v1"
        private let lock = NSLock()
        /// "<groupUUID>|<msgId>" → member public key → state
        private var table: [String: [String: MemberState]] = [:]

        /// How many messages to remember receipts for. A group screen only
        /// ever asks about something on screen, and an unbounded dictionary
        /// written from the network is a leak with a tidy name.
        private let maxMessages = 500

        private init() { load() }

        static func key(_ groupId: UUID, _ msgId: String) -> String {
            groupId.uuidString + "|" + msgId
        }

        func record(groupId: UUID, msgId: String, member: String, kind: Kind, at: Date) {
            lock.lock(); defer { lock.unlock() }
            let k = Self.key(groupId, msgId)
            var forMsg = table[k] ?? [:]
            var st = forMsg[member] ?? MemberState()
            switch kind {
            case .received:
                // Never move an existing stamp later: a duplicate receipt from a
                // retry must not make delivery look more recent than it was.
                if st.receivedAt == nil || at < st.receivedAt! { st.receivedAt = at }
            case .read:
                if st.readAt == nil || at < st.readAt! { st.readAt = at }
                // Reading implies receiving. A read receipt can arrive without
                // its delivery twin — the delivery one can be lost, and on an
                // old build it was never sent at all — and showing someone as
                // "read but not delivered" would be nonsense on the screen.
                if st.receivedAt == nil { st.receivedAt = at }
            }
            forMsg[member] = st
            table[k] = forMsg
            trimLocked()
            saveLocked()
        }

        func states(groupId: UUID, msgId: String) -> [String: MemberState] {
            lock.lock(); defer { lock.unlock() }
            return table[Self.key(groupId, msgId)] ?? [:]
        }

        func forget(groupId: UUID) {
            lock.lock(); defer { lock.unlock() }
            let p = groupId.uuidString + "|"
            for k in table.keys where k.hasPrefix(p) { table.removeValue(forKey: k) }
            saveLocked()
        }

        /// Test seam: the singleton reads real UserDefaults, so a test needs a
        /// way back to empty.
        func resetForTesting() {
            lock.lock(); defer { lock.unlock() }
            table = [:]
            saveLocked()
        }

        private func trimLocked() {
            guard table.count > maxMessages else { return }
            // Drop the entries whose newest stamp is oldest. Sorting a 500-entry
            // dictionary is cheap and happens only at the boundary.
            let ranked = table.map { (key: $0.key, newest: $0.value.values.compactMap { s in
                [s.receivedAt, s.readAt].compactMap { $0 }.max()
            }.max() ?? Date.distantPast) }
            for entry in ranked.sorted(by: { $0.newest < $1.newest }).prefix(table.count - maxMessages) {
                table.removeValue(forKey: entry.key)
            }
        }

        private func saveLocked() {
            guard let data = try? JSONEncoder().encode(table) else { return }
            UserDefaults.standard.set(data, forKey: defaultsKey)
        }

        private func load() {
            guard let data = UserDefaults.standard.data(forKey: defaultsKey),
                  let decoded = try? JSONDecoder().decode([String: [String: MemberState]].self, from: data)
            else { return }
            table = decoded
        }
    }

    // MARK: - Sending

    /// Whether a READ receipt may leave this device.
    ///
    /// The same switch the 1:1 read receipt obeys (`readReceiptsEnabled`,
    /// Settings). Default true, matching the existing behaviour — this does
    /// not quietly turn a disclosure on for anyone.
    ///
    /// DELIVERY is NOT gated by it, and that asymmetry is the point: "received"
    /// says a device accepted bytes, which the sender's own relay already
    /// tells them; "read" says a person looked, which is the thing somebody
    /// might reasonably want to withhold.
    static var readReceiptsAllowed: Bool {
        UserDefaults.standard.object(forKey: "readReceiptsEnabled") as? Bool ?? true
    }

    /// Tell `sender` what we did with their group message.
    ///
    /// Addressed to the sender ALONE as an ordinary 1:1 control message, so it
    /// costs one message rather than one per member, and tells nobody else
    /// when we read something.
    @MainActor
    static func send(kind: Kind, groupId: UUID, msgId: String, to sender: String) {
        guard kind != .read || readReceiptsAllowed else { return }
        guard !sender.isEmpty, sender != "SYSTEM", !sender.hasPrefix("bot:") else { return }
        // __GROUP_RECEIPT_IOS_1043_2026_11_13__ Never to a build that would show
        // the sentinel as a chat bubble (iOS 1.0.43, the one on sale). See
        // `peerReadsReceipts`.
        guard peerReadsReceiptsNow(sender) else {
            OshiLog.messaging.info("📑 receipt to \(sender.prefix(8))… withheld: peer not known to read receipts")
            return
        }
        guard let identityManager = MessageManager.sharedIdentityManager,
              let meshManager = MessageManager.sharedMeshManager,
              sender != identityManager.publicKey,
              let payload = encode(groupId: groupId, msgId: msgId, kind: kind) else { return }
        MessageManager.shared?.sendMessage(
            content: payload,
            recipientAddress: sender,
            identityManager: identityManager,
            meshManager: meshManager,
            skipLocalMessage: true
        )
    }

    // MARK: - Who may receive one

    /// __GROUP_RECEIPT_IOS_1043_2026_11_13__ (tester report N11: "on iOS, group
    /// messages are not decrypted and show as group receipt").
    ///
    /// A receipt is a 1:1 message to the SENDER of a group message. iOS 1.0.43
    /// (git 4befbea0, the build on sale) predates `📑GROUP_RECEIPT📑`: it is not
    /// in that build's `OSHIControlPayload.catalog`, so `isSilent` is false,
    /// `ChatView.computeFilteredMessages` keeps it and `displaySummary` treats
    /// it as prose. Every receipt sent to an iPhone on 1.0.43 became a raw
    /// `📑GROUP_RECEIPT📑{"g":…}` bubble in its 1:1 thread with the member that
    /// sent it, plus an in-app banner of the same text.
    ///
    /// So a receipt goes ONLY to a peer that provably reads it. Unknown means
    /// no: a false "no" costs one blank row in the sheet, a false "yes" puts
    /// junk in someone's conversation. Evidence, any one suffices:
    ///  1. the peer has itself sent us a group receipt;
    ///  2. its account bundle `caps` carries `Caps.padding` — every build that
    ///     publishes it (2026-11-09, 705a7fd6, all three platforms) descends
    ///     from the receipts commits of 2026-11-02. 1.0.43 publishes caps = 1;
    ///  3. its account padding slot (`V2PaddingPeers`) says it pads.
    /// Mirrors Android `GroupReceipts.peerReadsReceipts`.
    static func peerReadsReceipts(accountCaps: Int, accountSlotPads: Bool, sentUsAReceipt: Bool) -> Bool {
        if sentUsAReceipt { return true }
        if RatchetHealSignal.Caps.has(accountCaps, RatchetHealSignal.Caps.padding) { return true }
        return accountSlotPads
    }

    static func peerReadsReceiptsNow(_ peer: String) -> Bool {
        peerReadsReceipts(accountCaps: RatchetHealSignal.Caps.of(peer: peer),
                          accountSlotPads: V2PaddingPeers.slotCapable(peer: peer, device: nil),
                          sentUsAReceipt: Readers.contains(peer))
    }

    /// Peers that have sent us a group receipt (evidence 1). Bounded, persisted.
    enum Readers {
        private static let key = "oshi.group.receipts.readers.v1"
        private static let cap = 1000
        nonisolated(unsafe) static var defaults: UserDefaults = .standard

        static func normalized(_ peer: String) -> String {
            peer.trimmingCharacters(in: .whitespaces)
                .replacingOccurrences(of: "-", with: "+")
                .replacingOccurrences(of: "_", with: "/")
                .replacingOccurrences(of: "=", with: "")
        }

        static func contains(_ peer: String) -> Bool {
            (defaults.stringArray(forKey: key) ?? []).contains(normalized(peer))
        }

        static func note(_ peer: String) {
            let n = normalized(peer)
            var list = defaults.stringArray(forKey: key) ?? []
            guard !list.contains(n) else { return }
            list.append(n)
            if list.count > cap { list.removeFirst(list.count - cap) }
            defaults.set(list, forKey: key)
        }
    }

    // MARK: - Presentation

    /// One row of the "who has seen this" sheet.
    struct Row: Equatable {
        let memberKey: String
        let receivedAt: Date?
        let readAt: Date?
        var hasRead: Bool { readAt != nil }
        var hasReceived: Bool { receivedAt != nil }
    }

    /// Rows for every member except the sender, read first, then delivered,
    /// then pending — the order the question is asked in.
    ///
    /// Members with NO receipt are included deliberately. "Nothing is known
    /// about this person" is the answer the user most needs, and a list that
    /// quietly omitted them would read as everyone having received it.
    static func rows(groupMembers: [String], sender: String,
                     states: [String: MemberState]) -> [Row] {
        let others = groupMembers.filter { $0 != sender }
        let rows = others.map { Row(memberKey: $0,
                                    receivedAt: states[$0]?.receivedAt,
                                    readAt: states[$0]?.readAt) }
        return rows.sorted { a, b in
            if a.hasRead != b.hasRead { return a.hasRead }
            if a.hasReceived != b.hasReceived { return a.hasReceived }
            return a.memberKey < b.memberKey
        }
    }
}

// MARK: - The sheet

import SwiftUI

/// "Who has seen this" for one of your own group messages.
///
/// __GROUP_RECEIPTS_2026_11_02__ (N8) Three sections, in the order the question
/// is actually asked: read, then delivered-but-not-read, then nothing known.
///
/// The third section is the one that matters and the easy one to leave out. A
/// member nobody has heard from is not "delivered" — and a list that silently
/// omitted them would read as everyone having received the message, which is
/// the opposite of the truth and worse than showing no sheet at all.
struct GroupReceiptsSheet: View {
    let groupId: UUID
    let msgId: String
    let members: [String]
    let sender: String
    /// Resolves a public key to whatever the user calls that person.
    let displayName: (String) -> String
    let onDismiss: () -> Void

    private var rows: [GroupReceipts.Row] {
        GroupReceipts.rows(groupMembers: members, sender: sender,
                           states: GroupReceipts.Store.shared.states(groupId: groupId, msgId: msgId))
    }

    var body: some View {
        NavigationView {
            List {
                section(title: "group.receipts.read".localized,
                        icon: "checkmark.circle.fill",
                        tint: .blue,
                        rows: rows.filter { $0.hasRead },
                        stamp: { $0.readAt })

                section(title: "group.receipts.delivered".localized,
                        icon: "checkmark.circle",
                        tint: .secondary,
                        rows: rows.filter { !$0.hasRead && $0.hasReceived },
                        stamp: { $0.receivedAt })

                section(title: "group.receipts.pending".localized,
                        icon: "clock",
                        tint: .secondary,
                        rows: rows.filter { !$0.hasReceived },
                        stamp: { _ in nil })
            }
            .listStyle(.insetGrouped)
            .navigationTitle("group.receipts.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("peers_done".localized) { onDismiss() }
                }
            }
        }
    }

    @ViewBuilder
    private func section(title: String, icon: String, tint: Color,
                         rows: [GroupReceipts.Row],
                         stamp: @escaping (GroupReceipts.Row) -> Date?) -> some View {
        // An empty section is omitted rather than shown empty: three headers
        // with nothing under two of them reads as a broken screen.
        if !rows.isEmpty {
            Section(header: Text("\(title) (\(rows.count))")) {
                ForEach(rows, id: \.memberKey) { row in
                    HStack {
                        Image(systemName: icon).foregroundColor(tint)
                        Text(displayName(row.memberKey)).lineLimit(1)
                        Spacer()
                        if let at = stamp(row) {
                            Text(at, style: .time)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                }
            }
        }
    }
}
