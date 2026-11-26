//
//  GroupPendingDelivery.swift
//  OSHI
//
//  __GROUP_RESEND_2026_11_25__ Members a group message has NOT reached yet, kept on disk until
//  they are reached, leave the group, or 7 days pass. Same design as Android
//  `service/GroupPendingDelivery.kt` (GroupPendingDeliveryStore + GroupResendSchedule).
//
//  Before this, a group send that reached part of the group (relay 429 past the wait budget, a
//  network drop, a 5xx, a member whose bundle could not be fetched right now, the app killed in
//  the middle of the fan-out) left the other members without the message for good, and the
//  bubble showed the same tick as a message everybody got.
//
//  Lifecycle of an `Entry`:
//   * `begin` BEFORE the fan-out starts: every recipient is pending, due after schedule step 0,
//     so a process killed mid-send resumes on the next launch.
//   * `settle` after each attempt: reached members and members the server says have NO v2
//     bundle (they follow the "must update" spec, never this queue) leave the list. Empty list ⇒
//     the entry is gone and the bubble is a normal Sent. Otherwise the next attempt is scheduled
//     (`GroupResendSchedule.delay(afterAttempts:)`, never earlier than a relay `Retry-After`).
//   * `dropDeparted`: members no longer in the group are not owed the message.
//   * after `GroupResendSchedule.maxAge` the entry stays (the bubble keeps saying who is missing)
//     but is marked `gaveUp` and no longer retried automatically; "Retry now" still works.
//
//  At rest: Application Support, `completeUntilFirstUserAuthentication` (the resend loop must run
//  in the background after the first unlock), excluded from backup — the same protection as the
//  v2 pending-envelope ledger. It holds message ids, group ids and member public keys, no content.
//

import Foundation
import Combine

/// When the next automatic attempt runs: 1 min, 1 min, 5 min, 30 min, then every 3 h.
nonisolated enum GroupResendSchedule {
    /// Stop retrying automatically after a week (the relay keeps envelopes 30 days).
    static let maxAge: TimeInterval = 7 * 24 * 3600

    /// 0 = right after `begin` (only matters if the app dies mid-send); then 1, 5, 30 min, 3 h.
    static func delay(afterAttempts attempts: Int) -> TimeInterval {
        switch attempts {
        case ...1: return 60
        case 2: return 5 * 60
        case 3: return 30 * 60
        default: return 3 * 3600
        }
    }
}

nonisolated final class GroupPendingDeliveryStore: @unchecked Sendable {

    struct Entry: Codable, Equatable, Sendable {
        let msgId: String
        let groupId: String
        /// Recipients this message is meant for (members with no v2 bundle are taken out).
        var total: Int
        var pending: [String]
        var attempts: Int
        var firstAt: Date
        var nextAt: Date
        var gaveUp: Bool = false
        /// A local bot's reply: the wire copy names the bot (`botAuthor`), rebuilt on resend.
        var botAuthor: String? = nil

        var delivered: Int { max(0, total - pending.count) }
    }

    static let maxEntries = 500

    private let fileURL: URL
    private let canon: (String) -> String
    private let lock = NSLock()
    private var cache: [String: Entry]?
    /// Insertion order, so the bound drops the oldest first.
    private var order: [String] = []

    /// Same folding as `MessageManager.canonicalIdentity` (base64url / padding spellings of one
    /// key compare equal), nonisolated so the store can be used from any thread.
    static func canonical(_ key: String) -> String {
        if key.count % 4 == 0, !key.contains("-"), !key.contains("_") { return key }
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return key }
        var s = trimmed.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while s.hasSuffix("=") { s.removeLast() }
        while s.count % 4 != 0 { s += "=" }
        guard Data(base64Encoded: s) != nil else { return key }
        return s
    }

    init(fileURL: URL, canon: @escaping (String) -> String = GroupPendingDeliveryStore.canonical) {
        self.fileURL = fileURL
        self.canon = canon
    }

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("oshi_group_pending_delivery.json")
    }

    // MARK: Lifecycle

    @discardableResult
    func begin(msgId: String, groupId: String, recipients: [String], now: Date = Date(),
               botAuthor: String? = nil) -> Entry? {
        lock.lock(); defer { lock.unlock() }
        var seen = Set<String>()
        let list = recipients.filter { !$0.isEmpty && seen.insert(canon($0)).inserted }
        guard !list.isEmpty else { return nil }
        var all = load()
        let next = now.addingTimeInterval(GroupResendSchedule.delay(afterAttempts: 0))
        let e: Entry
        if var existing = all[msgId] {
            existing.nextAt = next
            e = existing
        } else {
            e = Entry(msgId: msgId, groupId: groupId, total: list.count, pending: list, attempts: 0,
                      firstAt: now, nextAt: next, botAuthor: botAuthor)
            order.append(msgId)
        }
        all[msgId] = e
        save(all)
        return e
    }

    /// One attempt ended. Returns the entry still pending, or nil when nobody is left (removed).
    @discardableResult
    func settle(msgId: String, reached: [String], noBundle: [String], now: Date = Date(),
                retryAfter: TimeInterval? = nil) -> Entry? {
        lock.lock(); defer { lock.unlock() }
        var all = load()
        guard var e = all[msgId] else { return nil }
        let gone = Set((reached + noBundle).map(canon))
        let noBundleSet = Set(noBundle.map(canon))
        let noBundleOwed = e.pending.filter { noBundleSet.contains(canon($0)) }.count
        let left = e.pending.filter { !gone.contains(canon($0)) }
        if left.isEmpty {
            all.removeValue(forKey: msgId)
            save(all)
            return nil
        }
        e.attempts += 1
        let wait = max(GroupResendSchedule.delay(afterAttempts: e.attempts), retryAfter ?? 0)
        e.total = max(e.total - noBundleOwed, left.count)
        e.pending = left
        e.nextAt = now.addingTimeInterval(wait)
        e.gaveUp = now.timeIntervalSince(e.firstAt) >= GroupResendSchedule.maxAge
        all[msgId] = e
        save(all)
        return e
    }

    /// Members who left `groupId` are not owed anything. Returns the msgIds that completed.
    @discardableResult
    func dropDeparted(groupId: String, members: [String]) -> [String] {
        lock.lock(); defer { lock.unlock() }
        let keep = Set(members.map(canon))
        var all = load()
        var done: [String] = []
        var changed = false
        for (id, var e) in all where e.groupId.caseInsensitiveCompare(groupId) == .orderedSame {
            let left = e.pending.filter { keep.contains(canon($0)) }
            guard left.count != e.pending.count else { continue }
            changed = true
            if left.isEmpty {
                all.removeValue(forKey: id); done.append(id)
            } else {
                e.total = max(e.total - (e.pending.count - left.count), left.count)
                e.pending = left
                all[id] = e
            }
        }
        if changed { save(all) }
        return done
    }

    /// Entries whose next attempt is due (not given up), oldest first.
    func due(now: Date = Date()) -> [Entry] {
        lock.lock(); defer { lock.unlock() }
        return load().values.filter { !$0.gaveUp && $0.nextAt <= now }.sorted { $0.firstAt < $1.firstAt }
    }

    /// "Retry now" (one message, given up or not) / connectivity came back (every live entry).
    func makeDue(msgId: String? = nil, now: Date = Date()) {
        lock.lock(); defer { lock.unlock() }
        var all = load()
        var changed = false
        for (id, var e) in all {
            if let msgId, id != msgId { continue }
            if e.gaveUp && msgId == nil { continue }
            if e.gaveUp { e.firstAt = now }   // a manual retry restarts the 7 days
            e.gaveUp = false
            e.nextAt = now
            all[id] = e
            changed = true
        }
        if changed { save(all) }
    }

    func get(_ msgId: String) -> Entry? {
        lock.lock(); defer { lock.unlock() }
        return load()[msgId]
    }

    func all() -> [String: Entry] {
        lock.lock(); defer { lock.unlock() }
        return load()
    }

    func remove(_ msgId: String) {
        lock.lock(); defer { lock.unlock() }
        var all = load()
        if all.removeValue(forKey: msgId) != nil { save(all) }
    }

    func removeGroup(_ groupId: String) {
        lock.lock(); defer { lock.unlock() }
        let all = load()
        let kept = all.filter { $0.value.groupId.caseInsensitiveCompare(groupId) != .orderedSame }
        if kept.count != all.count { save(kept) }
    }

    // MARK: Persistence (call with `lock` held)

    private struct Disk: Codable { var entries: [Entry] }

    private func load() -> [String: Entry] {
        if let cache { return cache }
        var out: [String: Entry] = [:]
        order = []
        // A corrupt queue degrades to "nothing pending", never to a crash at launch.
        if let data = try? Data(contentsOf: fileURL), let disk = try? JSONDecoder().decode(Disk.self, from: data) {
            for e in disk.entries where out[e.msgId] == nil {
                out[e.msgId] = e
                order.append(e.msgId)
            }
        }
        cache = out
        return out
    }

    private func save(_ allIn: [String: Entry]) {
        var all = allIn
        order = order.filter { all[$0] != nil }
        for id in all.keys where !order.contains(id) { order.append(id) }
        while all.count > Self.maxEntries, let oldest = order.first {
            order.removeFirst(); all.removeValue(forKey: oldest)
        }
        cache = all
        let entries = order.compactMap { all[$0] }
        guard let data = try? JSONEncoder().encode(Disk(entries: entries)) else { return }
        try? data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        var url = fileURL
        var vals = URLResourceValues(); vals.isExcludedFromBackup = true
        try? url.setResourceValues(vals)
    }
}

/// What the bubble shows for an own group message that some members have not received.
nonisolated enum GroupPartialDelivery {
    /// "3/10" — the compact form next to the clock.
    static func compact(_ e: GroupPendingDeliveryStore.Entry) -> String { "\(e.delivered)/\(e.total)" }

    /// "Sent to 3 of 10".
    static func title(_ e: GroupPendingDeliveryStore.Entry) -> String {
        String(format: "group.sent_partial".localized, e.delivered, e.total)
    }

    /// The explanation under the title: still retrying, or given up after 7 days.
    static func body(_ e: GroupPendingDeliveryStore.Entry) -> String {
        String(format: (e.gaveUp ? "group.sent_partial.gave_up" : "group.sent_partial.body").localized, e.pending.count)
    }
}

/// The published view of the store, for the bubbles. Updated by `MessageGroupManager` after
/// every change; absent msgId = everybody got it (normal Sent).
@MainActor
final class GroupPendingDeliveryCenter: ObservableObject {
    static let shared = GroupPendingDeliveryCenter()
    @Published private(set) var entries: [String: GroupPendingDeliveryStore.Entry] = [:]

    func publish(_ all: [String: GroupPendingDeliveryStore.Entry]) {
        if all != entries { entries = all }
    }

    func entry(for messageId: UUID) -> GroupPendingDeliveryStore.Entry? {
        entries[messageId.uuidString]
    }
}
