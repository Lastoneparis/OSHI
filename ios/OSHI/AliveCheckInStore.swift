import Foundation
import UserNotifications

/// __ALIVE_CHECK_IN_2026_11_04__ Who has promised to check in, how often, and
/// when they last did.
///
/// One entry per conversation, holding the TWO directions separately — see
/// ``AliveCheckInEvent`` for why a shared interval would be the wrong shape.
///
/// Sealed at rest like `OfflineOnlyStore` and `PollStore`, and for the same
/// reason: "these are the people I have agreed to prove I am alive to" is about
/// as sensitive as a contact list gets, and `UserDefaults` rides out in iCloud
/// and Finder backups in cleartext.
///
/// Android: `service/AliveCheckInStore.kt`.
@MainActor
final class AliveCheckInStore: ObservableObject {

    static let shared = AliveCheckInStore()

    /// One conversation, both directions.
    struct Entry: Codable, Equatable {
        var mineEnabled: Bool = false
        var mineIntervalSeconds: Int = AliveCheckInInterval.default
        /// Local clock throughout — this half never travels, so there is no skew
        /// to correct.
        var mineLastConfirmedAt: Date? = nil

        var theirsEnabled: Bool = false
        var theirsIntervalSeconds: Int = 0
        /// What their newest confirmation vouches for, after
        /// ``AliveCheckInPolicy/confirmedAt(declared:received:)`` has clamped
        /// their clock.
        var theirsLastConfirmedAt: Date? = nil
        /// The newest event applied from the peer, on the PEER's clock, kept in
        /// the peer's own units because its only job is to be compared against
        /// the next payload from the same peer. Converting it to our clock would
        /// make that comparison meaningless.
        var theirsLastEventAtUnixSeconds: Double = 0

        var isIdle: Bool { !mineEnabled && !theirsEnabled }

        func myStatus(now: Date) -> AliveCheckInPolicy.Status {
            AliveCheckInPolicy.status(enabled: mineEnabled,
                                      lastConfirmedAt: mineLastConfirmedAt,
                                      intervalSeconds: mineIntervalSeconds,
                                      now: now)
        }

        func theirStatus(now: Date) -> AliveCheckInPolicy.Status {
            AliveCheckInPolicy.status(enabled: theirsEnabled,
                                      lastConfirmedAt: theirsLastConfirmedAt,
                                      intervalSeconds: theirsIntervalSeconds,
                                      now: now)
        }

        /// The one state a conversation ROW shows, out of two directions.
        ///
        /// Overdue wins over confirmed, in either direction. A row is one dot and
        /// one line, so it has to answer "is there anything to look at here?",
        /// and a silence on either side is the thing to look at. Showing the
        /// peer's green while our own deadline has passed would hide the only
        /// half the user can still do something about.
        func rowStatus(now: Date) -> AliveCheckInPolicy.Status {
            let mine = myStatus(now: now)
            let theirs = theirStatus(now: now)
            if mine == .overdue || theirs == .overdue { return .overdue }
            if mine == .confirmed || theirs == .confirmed { return .confirmed }
            return .neutral
        }

        /// The deadline the row counts down to: the nearest one still ahead, or
        /// the one most recently missed. Nil when there is nothing to count.
        func rowDueAt() -> Date? {
            var candidates: [Date] = []
            if mineEnabled, let last = mineLastConfirmedAt, mineIntervalSeconds > 0 {
                candidates.append(AliveCheckInPolicy.dueAt(lastConfirmedAt: last,
                                                           intervalSeconds: mineIntervalSeconds))
            }
            if theirsEnabled, let last = theirsLastConfirmedAt, theirsIntervalSeconds > 0 {
                candidates.append(AliveCheckInPolicy.dueAt(lastConfirmedAt: last,
                                                           intervalSeconds: theirsIntervalSeconds))
            }
            return candidates.min()
        }
    }

    @Published private(set) var entries: [String: Entry] = [:]

    private var saveTask: Task<Void, Never>?

    private static var fileURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("alive_check_ins.json")
    }

    private init() {
        load()
        // Re-arm on launch. A pending `UNNotificationRequest` survives a reboot
        // and an app termination, but not a reinstall and not a user who cleared
        // the shade, so the schedule is rebuilt from the state rather than
        // trusted to the notification centre.
        rescheduleAll()
    }

    /// Fold the base64 spellings, exactly as `OfflineOnlyStore` does. The same
    /// public key arrives base64url from one transport and standard base64 from
    /// another, and two entries for one person would mean a check-in confirmed
    /// on one row and overdue on the other.
    private func canonical(_ id: String) -> String { MessageManager.canonicalIdentity(id) }

    // MARK: Reading

    func entry(for conversationId: String) -> Entry? { entries[canonical(conversationId)] }

    func entryOrEmpty(for conversationId: String) -> Entry {
        entries[canonical(conversationId)] ?? Entry()
    }

    func isConfigured(_ conversationId: String) -> Bool {
        entry(for: conversationId).map { !$0.isIdle } ?? false
    }

    // MARK: Writing — our own half

    /// Start, or re-time, our own commitment. Returns the event to send.
    ///
    /// Enabling counts as a confirmation at the same instant, deliberately: a
    /// schedule that starts with no confirmation behind it starts OVERDUE, and
    /// the first thing the other side would see is a red dot caused by the user
    /// turning the feature on.
    func enableMine(_ conversationId: String,
                    intervalSeconds: Int,
                    now: Date = Date()) -> AliveCheckInEvent {
        let interval = AliveCheckInInterval.clamp(intervalSeconds)
        mutate(conversationId) {
            $0.mineEnabled = true
            $0.mineIntervalSeconds = interval
            $0.mineLastConfirmedAt = now
        }
        return AliveCheckInEvent.create(kind: .enabled, intervalSeconds: interval, at: now)
    }

    /// "I'm alive." Returns the event to send.
    ///
    /// Pressing the button is itself a commitment: someone who taps "I'm alive"
    /// in a conversation with no schedule means to start one, and refusing them
    /// a confirmation because of a flag they never saw would read as a dead
    /// button.
    func confirmMine(_ conversationId: String, now: Date = Date()) -> AliveCheckInEvent {
        let interval = AliveCheckInInterval.clamp(
            entry(for: conversationId)?.mineIntervalSeconds ?? AliveCheckInInterval.default)
        mutate(conversationId) {
            $0.mineEnabled = true
            $0.mineIntervalSeconds = interval
            $0.mineLastConfirmedAt = now
        }
        return AliveCheckInEvent.create(kind: .confirmed, intervalSeconds: interval, at: now)
    }

    /// Stop our own commitment. Returns the event to send.
    func disableMine(_ conversationId: String, now: Date = Date()) -> AliveCheckInEvent {
        mutate(conversationId) {
            $0.mineEnabled = false
            $0.mineLastConfirmedAt = nil
        }
        return AliveCheckInEvent.create(kind: .disabled, intervalSeconds: 0, at: now)
    }

    // MARK: Writing — the peer's half

    /// What happened when a peer's event was applied. The caller needs it to
    /// decide which notification to clear, and only the store can tell it,
    /// because only the store knows the state the event replaced.
    struct Applied {
        let accepted: Bool
        let event: AliveCheckInEvent
        let wasOverdue: Bool
        let isNowOverdue: Bool
        /// They answered after their deadline had already passed.
        var isRecovery: Bool { accepted && wasOverdue && !isNowOverdue && event.isConfirmation }
    }

    @discardableResult
    func applyIncoming(_ event: AliveCheckInEvent,
                       conversationId: String,
                       received: Date = Date()) -> Applied {
        let key = canonical(conversationId)
        let before = entries[key] ?? Entry()
        let wasOverdue = before.theirStatus(now: received) == .overdue

        guard AliveCheckInPolicy.accepts(eventAt: event.atUnixSeconds,
                                         lastAppliedAt: before.theirsLastEventAtUnixSeconds) else {
            return Applied(accepted: false, event: event,
                           wasOverdue: wasOverdue, isNowOverdue: wasOverdue)
        }

        var after = before
        after.theirsLastEventAtUnixSeconds = event.atUnixSeconds
        switch event.kind {
        case .disabled:
            after.theirsEnabled = false
            after.theirsIntervalSeconds = 0
            after.theirsLastConfirmedAt = nil
        case .enabled, .confirmed:
            after.theirsEnabled = true
            // A confirmation with no interval on it cannot describe a deadline.
            // Keep the one we already had rather than fall to zero, which would
            // read as "nothing is configured" and silently retire the schedule.
            if event.intervalSeconds > 0 {
                after.theirsIntervalSeconds = AliveCheckInInterval.clamp(event.intervalSeconds)
            }
            after.theirsLastConfirmedAt = AliveCheckInPolicy.confirmedAt(
                declared: Date(timeIntervalSince1970: event.atUnixSeconds),
                received: received)
        }

        entries[key] = after
        scheduleSave()
        reschedule(key)

        return Applied(accepted: true, event: event,
                       wasOverdue: wasOverdue,
                       isNowOverdue: after.theirStatus(now: received) == .overdue)
    }

    // MARK: Plumbing

    private func mutate(_ conversationId: String, _ transform: (inout Entry) -> Void) {
        let key = canonical(conversationId)
        var e = entries[key] ?? Entry()
        transform(&e)
        entries[key] = e
        scheduleSave()
        reschedule(key)
    }

    private struct Disk: Codable { let entries: [String: Entry] }

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
            let data = try JSONEncoder().encode(Disk(entries: entries))
            try AtRestFile.write(data, to: Self.fileURL)
        } catch {
            OshiLog.storage.info("[AliveCheckInStore] could not seal the state: \(error)")
        }
    }

    private func load() {
        guard let data = try? AtRestFile.read(Self.fileURL),
              let disk = try? JSONDecoder().decode(Disk.self, from: data) else { return }
        entries = disk.entries
        OshiLog.storage.infoPublic("[AliveCheckInStore] loaded \(disk.entries.count) conversation(s)")
    }

    // MARK: - The deadline alarms
    //
    // A check-in that is never confirmed produces NOTHING on the wire — that is
    // what "missed" means. So the deadline has to be noticed locally, by the
    // device that is waiting, with no help from a peer and none from a server.
    // Which is also why this works in the conditions the feature is for: no
    // relay, no push, mesh-only, airplane mode on one side.
    //
    // iOS can schedule a notification for an instant and show it with the app
    // not running, so there is no equivalent of Android's WorkManager sweep
    // here: one pending `UNNotificationRequest` per deadline, replaced whenever
    // the state moves. Duplicate suppression is inherent — a request identifier
    // can only be pending once, so the bookkeeping Android needs
    // (`claimTheirOverdueNotice`) has no counterpart.

    private func overdueId(_ key: String) -> String { "alive.overdue.\(key.hashValue)" }
    private func mineId(_ key: String) -> String { "alive.mine.\(key.hashValue)" }

    func rescheduleAll() {
        for key in entries.keys { reschedule(key) }
    }

    private func reschedule(_ key: String) {
        let centre = UNUserNotificationCenter.current()
        centre.removePendingNotificationRequests(withIdentifiers: [overdueId(key), mineId(key)])
        guard let e = entries[key], !e.isIdle else { return }
        let now = Date()
        let name = MessageManager.resolveContactAlias(key)
        let peerName = name.isEmpty ? "alive.unknown_peer".localized : name

        // Theirs: "no response from X". Never "X is in danger", never a guess at
        // why — see AliveCheckInPolicy.overdueIsNotProof.
        if e.theirsEnabled, let last = e.theirsLastConfirmedAt, e.theirsIntervalSeconds > 0 {
            let due = AliveCheckInPolicy.dueAt(lastConfirmedAt: last,
                                               intervalSeconds: e.theirsIntervalSeconds)
            if due > now {
                schedule(id: overdueId(key),
                         title: String(format: "alive.notification.overdue.title".localized, peerName),
                         // No elapsed time in the body, unlike Android's.
                         // This request fires AT the deadline, so nothing has
                         // elapsed yet; Android's equivalent is posted by a
                         // WorkManager wakeup that Doze can defer by hours, where
                         // "due 6h ago" is the useful half of the sentence.
                         body: "alive.notification.overdue.body".localized,
                         at: due,
                         category: nil,
                         conversationId: key)
            }
        }

        // Ours: a reminder, not an alarm. The person who set the schedule is the
        // one who can still act on it, and reminding them is the difference
        // between a missed check-in and a forgotten one.
        if e.mineEnabled, let last = e.mineLastConfirmedAt, e.mineIntervalSeconds > 0 {
            let due = AliveCheckInPolicy.dueAt(lastConfirmedAt: last,
                                               intervalSeconds: e.mineIntervalSeconds)
            if due > now {
                schedule(id: mineId(key),
                         title: "alive.notification.mine_due.title".localized,
                         body: String(format: "alive.notification.mine_due.body".localized, peerName),
                         at: due,
                         category: AliveCheckInStore.notificationCategory,
                         conversationId: key)
            }
        }
    }

    /// The category carrying the one-tap "I'm alive" action.
    ///
    /// Registered in `NotificationManager.setupNotificationCategories`, which is
    /// the SINGLE owner of the category set — `setNotificationCategories`
    /// replaces rather than adds, and a second call from here would erase the
    /// message, check-in and call categories
    /// (`__CALL_BANNER_CANNOT_ANSWER_2026_08_25__`).
    static let notificationCategory = "ALIVE_CHECKIN_DUE"
    static let confirmActionIdentifier = "ALIVE_CONFIRM_ACTION"
    /// Key the action handler reads to know which conversation to confirm in.
    static let conversationUserInfoKey = "aliveConversationId"

    private func schedule(id: String, title: String, body: String, at date: Date,
                          category: String?, conversationId: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        if let category { content.categoryIdentifier = category }
        // Both notifications open the conversation when tapped — which for the
        // overdue one is also the "contact this person" action it owes the
        // reader. `senderKey` is the extra the tap handler already routes on.
        content.userInfo = ["senderKey": conversationId,
                            Self.conversationUserInfoKey: conversationId]
        let interval = max(1, date.timeIntervalSinceNow)
        let request = UNNotificationRequest(
            identifier: id,
            content: content,
            trigger: UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false))
        UNUserNotificationCenter.current().add(request) { error in
            if let error {
                OshiLog.ui.info("[AliveCheckInStore] could not arm \(id): \(error.localizedDescription)")
            }
        }
    }

    /// Take a stale "no response from X" out of the shade.
    ///
    /// Called when X answers, or ends their schedule. No replacement banner: the
    /// check-in is `.rendered`, so the ordinary message notification already
    /// carries the news, and two banners for one event is the notification spam
    /// the design brief rules out. What would be wrong is leaving the old one up.
    func clearOverdueNotice(_ conversationId: String) {
        let key = canonical(conversationId)
        UNUserNotificationCenter.current()
            .removeDeliveredNotifications(withIdentifiers: [overdueId(key)])
    }
}

// MARK: - The three verbs, on the send path

extension MessageManager {

    /// __ALIVE_CHECK_IN_2026_11_04__ "I'm alive."
    ///
    /// The local state is written FIRST and the send follows, in that order and
    /// deliberately: the countdown and the deadline are computed from the local
    /// half, and that half is true the moment the user presses the button. A
    /// send that failed must not leave someone who DID check in with a deadline
    /// that never moved — that is the one outcome that turns a safety feature
    /// into a false alarm about its own owner.
    ///
    /// Sent through `sendMessage` like a poll and like every other sentinel the
    /// catalog marks `.rendered`, so the event becomes a line in the thread on
    /// both sides. Mirrors `ChatViewModel.confirmAlive` in Kotlin.
    @MainActor
    func confirmAlive(forContact publicKey: String,
                      identityManager: IdentityManager,
                      meshManager: MeshNetworkManager) {
        let event = AliveCheckInStore.shared.confirmMine(publicKey)
        sendMessage(content: event.messageString,
                    recipientAddress: publicKey,
                    identityManager: identityManager,
                    meshManager: meshManager,
                    skipLocalMessage: false)
        OshiLog.messaging.infoPublic("💓 alive confirmed in a 1:1")
    }

    /// Start, or re-time, our own commitment in this conversation.
    @MainActor
    func setAliveCheckIn(intervalSeconds: Int,
                         forContact publicKey: String,
                         identityManager: IdentityManager,
                         meshManager: MeshNetworkManager) {
        let event = AliveCheckInStore.shared.enableMine(publicKey, intervalSeconds: intervalSeconds)
        sendMessage(content: event.messageString,
                    recipientAddress: publicKey,
                    identityManager: identityManager,
                    meshManager: meshManager,
                    skipLocalMessage: false)
        OshiLog.messaging.infoPublic("💓 alive check-in armed in a 1:1")
    }

    /// Stop our own commitment, and say so.
    ///
    /// The "off" event travels for the same reason the offline-only switch's
    /// does: a schedule that went quiet without a word would leave the other
    /// side counting down to a deadline nobody intends to meet, and the first
    /// thing they would see is a red dot for a check-in that was deliberately
    /// ended.
    @MainActor
    func stopAliveCheckIn(forContact publicKey: String,
                          identityManager: IdentityManager,
                          meshManager: MeshNetworkManager) {
        let event = AliveCheckInStore.shared.disableMine(publicKey)
        sendMessage(content: event.messageString,
                    recipientAddress: publicKey,
                    identityManager: identityManager,
                    meshManager: meshManager,
                    skipLocalMessage: false)
        OshiLog.messaging.infoPublic("💓 alive check-in stopped in a 1:1")
    }
}
