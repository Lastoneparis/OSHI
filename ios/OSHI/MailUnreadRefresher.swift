//
//  MailUnreadRefresher.swift
//  OSHI
//
//  __MAIL_UNREAD_REFRESH_2026_11_22__ Keeps the unread-mail count true without the
//  user opening the Email screen.
//
//  THE BUG (owner, iOS 1.0.49, 22/11): « I have to go into the email view to see
//  that I received email; otherwise no email shows and no notification, even a few
//  minutes after opening the app on the messages view. It happens when I put my
//  phone in flight mode for a few hours and reconnect it. »
//
//  Why: outside MailView the mailbox was fetched exactly ONCE per process — the
//  `.task` of ChatsTabView, when it first mounted. Nothing ever asked again:
//    • returning to the foreground re-polled chats and re-summed the badge, but
//      the mail slice of that sum was the stale in-memory list;
//    • the network coming back (NWPathMonitor) retried chat sends, not mail;
//    • a mail push (`type: "mail"`) showed a banner and refreshed nothing;
//    • if that single launch fetch failed — launched while still offline — the
//      count stayed 0 for the life of the process.
//  And the push cannot be the reconciliation: APNs keeps ONE pending notification
//  per app while the phone is unreachable (coalesced), and a dev-signed build may
//  not receive production pushes at all. Mail that arrived during flight mode is
//  only learned by ASKING the server after reconnecting.
//
//  The fix: one owner for "is there unread mail", asked on every event that can
//  change the answer — launch, foreground, network restored, every 60 s while in
//  the foreground (exponential backoff on failure, 15 min cap), any mail push, a
//  notification tap, and a BGAppRefreshTask in the background. Each check first
//  asks `/mail/v1/inbox/status` (a fingerprint, no sealed blob) and downloads the
//  list only when that moved; without the route (not yet deployed) it downloads
//  the list. The list feeds `OSHIMailClient.messages`, whose didSet already
//  re-sums the app-icon badge, and which the Chats tab and Email segment observe.
//
//  When a check that ran in the BACKGROUND finds unread mail no banner has covered
//  yet, a local notification says « You have new mail » — never the sender or the
//  subject (zero-access mail, and the same rule as message banners: no content).
//

import Foundation
import UIKit
import UserNotifications
import BackgroundTasks

// MARK: - Pure policy (unit-tested)

enum MailRefreshTrigger: String, CaseIterable {
    case launch, foreground, networkRestored, periodic, push, backgroundFetch, notificationTap

    /// "Something may have changed on the server right now": bypasses the spacing
    /// gate and, if a check is already running, queues exactly one more after it
    /// (the running one may have read the server before the mail landed).
    var isUrgent: Bool {
        switch self {
        case .push, .networkRestored, .notificationTap: return true
        default: return false
        }
    }
}

/// Delay before the next periodic check: 60 s, doubling per consecutive failure,
/// capped at 15 min. Reset by any success (and by the network coming back, since
/// the failures were most likely "offline").
struct MailRefreshBackoff: Equatable {
    static let base: TimeInterval = 60
    static let cap: TimeInterval = 15 * 60

    private(set) var consecutiveFailures = 0

    var nextDelay: TimeInterval {
        min(Self.base * pow(2, Double(min(consecutiveFailures, 16))), Self.cap)
    }

    mutating func recordSuccess() { consecutiveFailures = 0 }
    mutating func recordFailure() { consecutiveFailures += 1 }
}

/// Whether a trigger starts a check now.
enum MailRefreshGate {
    /// Foreground/launch triggers closer together than this are one event (a
    /// didBecomeActive right after launch, a quick app-switcher flick).
    static let minimumSpacing: TimeInterval = 10

    enum Decision: Equatable { case run, queueAfterInFlight, skip }

    static func decide(_ trigger: MailRefreshTrigger, inFlight: Bool,
                       lastStartedAt: Date?, now: Date) -> Decision {
        if inFlight { return trigger.isUrgent ? .queueAfterInFlight : .skip }
        switch trigger {
        case .periodic, .backgroundFetch: return .run
        default: break
        }
        if trigger.isUrgent { return .run }
        guard let last = lastStartedAt else { return .run }
        return now.timeIntervalSince(last) >= minimumSpacing ? .run : .skip
    }
}

/// Which unread mail has already been brought to the user's attention — by a
/// banner, or by being in the app while it was counted. Only the rest may
/// produce a local "new mail" banner. Persisted, capped.
struct MailArrivalLedger: Equatable {
    static let capacity = 500
    private(set) var known: [String]
    /// False until the first absorb: the very first check after installing this
    /// version must not announce mail that was already sitting there.
    private(set) var initialized: Bool

    init(known: [String] = [], initialized: Bool = false) {
        self.known = known
        self.initialized = initialized
    }

    func unannounced(_ unreadIds: [String]) -> [String] {
        guard initialized else { return [] }
        let k = Set(known)
        return unreadIds.filter { !k.contains($0) }
    }

    mutating func absorb(_ ids: [String]) {
        initialized = true
        var k = Set(known)
        for id in ids where k.insert(id).inserted { known.append(id) }
        if known.count > Self.capacity { known.removeFirst(known.count - Self.capacity) }
    }

    private static let idsKey = "mail.unread.announcedIds"
    private static let initKey = "mail.unread.ledgerInitialized"

    static func load(_ d: UserDefaults) -> MailArrivalLedger {
        MailArrivalLedger(known: d.stringArray(forKey: idsKey) ?? [], initialized: d.bool(forKey: initKey))
    }

    func save(_ d: UserDefaults) {
        d.set(known, forKey: Self.idsKey)
        d.set(initialized, forKey: Self.initKey)
    }
}

/// The Chats tab item's badge: people + groups + unread Inbox mail (the Email
/// segment lives inside that tab, so a mail-only unread must still mark it).
enum ChatsTabBadge {
    static func count(oneToOne: Int, groups: Int, mailInbox: Int) -> Int {
        max(0, oneToOne) + max(0, groups) + max(0, mailInbox)
    }
}

enum MailCheckOutcome: Equatable {
    case skippedNoIdentity, skippedOffline, skippedGate, queued
    case unchanged, refreshed, noMailbox, failed
}

// MARK: - Source abstraction (OSHIMailClient in the app, a fake in tests)

@MainActor
protocol MailUnreadSource: AnyObject {
    var canSign: Bool { get }
    var hasSyncedOnce: Bool { get }
    var localInboxFingerprint: String { get }
    var unreadInboxMessages: [OSHIMailMessage] { get }
    func fetchInboxStatus() async throws -> OSHIMailInboxStatus?
    @discardableResult func refreshInbox() async throws -> [OSHIMailMessage]
}

extension OSHIMailClient: MailUnreadSource {}

extension Notification.Name {
    /// Posted when a `type: "mail"` push reaches the app (foreground presentation,
    /// background wake or tap). Carries no content.
    static let oshiMailPushReceived = Notification.Name("OSHIMailPushReceived")
}

// MARK: - Refresher

@MainActor
final class MailUnreadRefresher {

    static let shared = MailUnreadRefresher(source: OSHIMailClient.shared, environment: .live)

    static let backgroundTaskIdentifier = "com.moriceau.oshi.mail-refresh"

    struct Environment {
        var isAppActive: @MainActor () -> Bool
        var isOnline: @MainActor () -> Bool
        var now: @MainActor () -> Date
        var defaults: UserDefaults
        /// Post the "new mail" banner for these ids (already filtered to unannounced).
        var announce: @MainActor (_ newUnreadIds: [String]) async -> Void
        var syncBadge: @MainActor () -> Void
        var sleep: @MainActor (_ seconds: TimeInterval) async throws -> Void

        static let live = Environment(
            isAppActive: { UIApplication.shared.applicationState == .active },
            isOnline: { NetworkReachabilityManager.shared.isConnected },
            now: { Date() },
            defaults: .standard,
            announce: { ids in await MailUnreadRefresher.postNewMailBanner(for: ids) },
            syncBadge: { MessageManager.shared?.syncAppBadge() },
            sleep: { s in try await Task.sleep(nanoseconds: UInt64(s * 1_000_000_000)) }
        )
    }

    private let source: MailUnreadSource
    private let env: Environment
    private let center: NotificationCenter

    private(set) var backoff = MailRefreshBackoff()
    private(set) var inFlight = false
    private(set) var lastStartedAt: Date?
    private(set) var noMailbox = false
    private(set) var statusRouteMissing = false
    private(set) var ledger: MailArrivalLedger
    private(set) var checksStarted = 0
    private var queuedTrigger: MailRefreshTrigger?
    private var periodicTask: Task<Void, Never>?
    private var observers: [NSObjectProtocol] = []
    private(set) var isObserving = false

    init(source: MailUnreadSource, environment: Environment, center: NotificationCenter = .default) {
        self.source = source
        self.env = environment
        self.center = center
        self.ledger = MailArrivalLedger.load(environment.defaults)
    }

    var isPeriodicRunning: Bool { periodicTask != nil }

    // MARK: Wiring

    /// Idempotent. Called from the app root and from ChatsTabView, whichever mounts
    /// first with the identity.
    func start(identity: IdentityManager) {
        OSHIMailClient.shared.configure(identity: identity)
        guard !isObserving else { return }
        startObserving()
        Task { await trigger(.launch) }
    }

    func startObserving() {
        guard !isObserving else { return }
        isObserving = true
        func on(_ name: Notification.Name, _ body: @escaping @MainActor (MailUnreadRefresher) -> Void) {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                guard let self else { return }
                MainActor.assumeIsolated { body(self) }
            })
        }
        on(UIApplication.didBecomeActiveNotification) { r in
            r.startPeriodic()
            Task { await r.trigger(.foreground) }
        }
        on(UIApplication.didEnterBackgroundNotification) { r in
            r.stopPeriodic()
            r.scheduleBackgroundFetch()
        }
        on(.networkBecameReachable) { r in
            r.backoff.recordSuccess()
            Task { await r.trigger(.networkRestored) }
        }
        on(.oshiMailPushReceived) { r in
            Task { await r.trigger(.push) }
        }
        if env.isAppActive() { startPeriodic() }
    }

    func stopObserving() {
        observers.forEach(center.removeObserver)
        observers.removeAll()
        isObserving = false
        stopPeriodic()
    }

    func startPeriodic() {
        guard periodicTask == nil else { return }
        periodicTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                let delay = self.noMailbox ? MailRefreshBackoff.cap : self.backoff.nextDelay
                do { try await self.env.sleep(delay) } catch { return }
                if Task.isCancelled { return }
                await self.trigger(.periodic)
            }
        }
    }

    func stopPeriodic() {
        periodicTask?.cancel()
        periodicTask = nil
    }

    // MARK: Check

    @discardableResult
    func trigger(_ trigger: MailRefreshTrigger) async -> MailCheckOutcome {
        switch MailRefreshGate.decide(trigger, inFlight: inFlight, lastStartedAt: lastStartedAt, now: env.now()) {
        case .skip:
            return .skippedGate
        case .queueAfterInFlight:
            queuedTrigger = trigger
            return .queued
        case .run:
            break
        }
        // A periodic tick with no mailbox has nothing to watch; an explicit event
        // (foreground, push…) still asks, since the mailbox may just have been made.
        if trigger == .periodic && noMailbox { return .noMailbox }
        guard source.canSign else { return .skippedNoIdentity }
        guard env.isOnline() else { return .skippedOffline }

        inFlight = true
        lastStartedAt = env.now()
        checksStarted += 1
        let outcome = await check()
        inFlight = false

        if let again = queuedTrigger {
            queuedTrigger = nil
            OshiLog.app.info("📧 mail refresh: re-running for queued \(again.rawValue)")
            return await self.trigger(again)
        }
        return outcome
    }

    private func check() async -> MailCheckOutcome {
        do {
            var mustList = true
            if !statusRouteMissing, source.hasSyncedOnce {
                if let status = try await source.fetchInboxStatus() {
                    mustList = status.fingerprint != source.localInboxFingerprint
                } else {
                    statusRouteMissing = true   // route not deployed: list every time
                }
            }
            if mustList { try await source.refreshInbox() }
            noMailbox = false
            backoff.recordSuccess()
            env.syncBadge()
            await reconcileArrivals()
            return mustList ? .refreshed : .unchanged
        } catch OSHIMailError.noMailbox {
            noMailbox = true
            backoff.recordSuccess()
            return .noMailbox
        } catch {
            backoff.recordFailure()
            OshiLog.app.info("📧 mail refresh failed (\(self.backoff.consecutiveFailures)× → next in \(Int(self.backoff.nextDelay))s)")
            return .failed
        }
    }

    /// In the foreground the count is on screen (tab badge, Email segment, icon), so
    /// everything currently unread counts as seen-by-the-user. In the background,
    /// unread mail nothing has announced yet gets one generic banner.
    private func reconcileArrivals() async {
        let unread = source.unreadInboxMessages.map(\.id)
        if env.isAppActive() || !ledger.initialized {
            ledger.absorb(unread)
        } else {
            let fresh = ledger.unannounced(unread)
            if !fresh.isEmpty { await env.announce(fresh) }
            ledger.absorb(fresh)
        }
        ledger.save(env.defaults)
    }

    // MARK: Background refresh

    /// Registered from `didFinishLaunchingWithOptions` (BGTaskScheduler requires it
    /// before launch ends). The identifier is in Genesis/Info.plist.
    static func registerBackgroundTask() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: backgroundTaskIdentifier, using: .main) { task in
            guard let task = task as? BGAppRefreshTask else { return }
            MainActor.assumeIsolated {
                MailUnreadRefresher.shared.handleBackgroundTask(task)
            }
        }
    }

    private func handleBackgroundTask(_ task: BGAppRefreshTask) {
        scheduleBackgroundFetch()
        let work = Task { @MainActor in
            let outcome = await self.trigger(.backgroundFetch)
            task.setTaskCompleted(success: outcome != .failed)
        }
        task.expirationHandler = {
            work.cancel()
            task.setTaskCompleted(success: false)
        }
    }

    func scheduleBackgroundFetch() {
        guard !noMailbox, source.canSign else { return }
        let request = BGAppRefreshTaskRequest(identifier: Self.backgroundTaskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do { try BGTaskScheduler.shared.submit(request) }
        catch { OshiLog.app.info("📧 mail refresh: BG task not scheduled: \(error.localizedDescription)") }
    }

    // MARK: Banner

    /// Generic, content-free: no sender, no subject — whatever the preview setting
    /// says (the mailbox is zero-access; the server's own mail push says the same
    /// words). Skipped when notifications are off in OSHI or in iOS, and for mail a
    /// server push already announced (still in Notification Centre).
    static func postNewMailBanner(for ids: [String]) async {
        guard UserDefaults.standard.object(forKey: "notificationsEnabled") as? Bool ?? true else { return }
        let nc = UNUserNotificationCenter.current()
        let settings = await nc.notificationSettings()
        guard settings.authorizationStatus == .authorized
                || settings.authorizationStatus == .provisional
                || settings.authorizationStatus == .ephemeral else { return }
        let delivered = await nc.deliveredNotifications()
        let covered = Set(delivered.compactMap { n -> String? in
            let u = n.request.content.userInfo
            guard (u["type"] as? String) == "mail" else { return nil }
            return (u["messageId"] as? String) ?? (u["mailId"] as? String)
        })
        let uncovered = ids.filter { !covered.contains($0) }
        guard !uncovered.isEmpty else { return }

        let content = UNMutableNotificationContent()
        content.title = "OSHI Mail"
        content.body = "mail.notif.new".localized
        content.sound = .default
        content.threadIdentifier = "oshi.mail"
        // One new message opens it; several open the Inbox ("" = Inbox).
        content.userInfo = ["type": "mail", "messageId": uncovered.count == 1 ? uncovered[0] : ""]
        let req = UNNotificationRequest(identifier: "oshi.mail.unread", content: content, trigger: nil)
        try? await nc.add(req)
    }
}
