//
//  WatchBridgeClient.swift
//  OSHI Watch
//
//  __APPLE_WATCH_2027__ The watch half of the bridge (see
//  OSHI/WatchBridgeProtocol.swift for the security model).
//
//  * `WatchBridgeSource` — where answers come from: the paired iPhone over
//    WatchConnectivity (`PhoneBridgeSource`), or, in DEBUG builds only, canned
//    data for the simulator and screenshots (`FakeBridgeSource`, launched with
//    `-OSHIWatchFake`, the twin of Wear's `-PwearFakeData`).
//  * `WatchStore` — what the screens draw. IN MEMORY ONLY: no conversation,
//    message, name or key is ever written to the watch's disk. The only file
//    is the widget's count + three names (`WatchWidgetSnapshotStore`).
//
//  Nothing is queued: a send the phone did not accept is reported as not sent
//  ("try again on your phone"), never parked in a WatchConnectivity queue that
//  the system would persist on the wrist.
//

import Foundation
import Combine
import SwiftUI
import WatchConnectivity
import WatchKit
import WidgetKit

// MARK: - Source

nonisolated protocol WatchBridgeSource: AnyObject {
    /// nil = the phone could not be reached (not paired / out of range / no answer).
    func request(_ req: WatchRequest) async -> WatchResponse?
}

/// The paired iPhone.
nonisolated final class PhoneBridgeSource: NSObject, WatchBridgeSource, WCSessionDelegate {
    static let shared = PhoneBridgeSource()

    private var session: WCSession? { WCSession.isSupported() ? WCSession.default : nil }
    private var activationWaiters: [CheckedContinuation<Void, Never>] = []
    private let lock = NSLock()
    /// Background refresh tasks to complete once the context has landed.
    private var refreshTasks: [WKWatchConnectivityRefreshBackgroundTask] = []

    func activate() {
        guard let session else { return }
        if session.delegate == nil { session.delegate = self }
        if session.activationState != .activated { session.activate() }
    }

    var isReachable: Bool { session?.isReachable ?? false }

    private func waitUntilActivated(timeout: TimeInterval) async -> Bool {
        guard let session else { return false }
        activate()
        if session.activationState == .activated { return true }
        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
                    self.lock.lock(); self.activationWaiters.append(c); self.lock.unlock()
                    // Activation may have completed between the check and here.
                    if session.activationState == .activated { self.resumeWaiters() }
                }
            }
            group.addTask { try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000)) }
            await group.next()
            group.cancelAll()
            self.resumeWaiters()   // releases the continuation if the timeout won
        }
        return session.activationState == .activated
    }

    private func resumeWaiters() {
        lock.lock(); let waiters = activationWaiters; activationWaiters.removeAll(); lock.unlock()
        waiters.forEach { $0.resume() }
    }

    func request(_ req: WatchRequest) async -> WatchResponse? {
        guard await waitUntilActivated(timeout: 6), let session, session.isReachable else { return nil }
        let payload = req.encode()
        return await withCheckedContinuation { (c: CheckedContinuation<WatchResponse?, Never>) in
            let once = Once()
            session.sendMessage(payload, replyHandler: { reply in
                once.run { c.resume(returning: WatchResponse.decode(reply) ?? .status(req.rid, .error)) }
            }, errorHandler: { error in
                let code = (error as? WCError)?.code
                // Never reached the phone → "phone not reachable"; reached it
                // but no usable answer → "something went wrong".
                let unreachable = code == .notReachable || code == .deviceNotPaired
                    || code == .watchAppNotInstalled || code == .companionAppNotInstalled
                    || code == .sessionNotActivated
                once.run { c.resume(returning: unreachable ? nil : .status(req.rid, .error)) }
            })
            DispatchQueue.main.asyncAfter(deadline: .now() + 15) {
                once.run { c.resume(returning: .status(req.rid, .error)) }
            }
        }
    }

    /// Legacy `type`-keyed messages (calls). Live only.
    func sendLegacy(_ message: [String: Any], completion: @escaping (Bool) -> Void) {
        guard let session, session.activationState == .activated, session.isReachable else {
            completion(false); return
        }
        session.sendMessage(message, replyHandler: { r in
            completion(r["success"] as? Bool ?? false)
        }, errorHandler: { _ in completion(false) })
    }

    func handle(_ task: WKWatchConnectivityRefreshBackgroundTask) {
        lock.lock(); refreshTasks.append(task); lock.unlock()
        activate()
        completeRefreshTasksIfDone()
    }

    private func completeRefreshTasksIfDone() {
        guard let session, session.activationState == .activated, !session.hasContentPending else { return }
        lock.lock(); let tasks = refreshTasks; refreshTasks.removeAll(); lock.unlock()
        tasks.forEach { $0.setTaskCompletedWithSnapshot(false) }
    }

    // MARK: WCSessionDelegate (callbacks arrive on a background queue)

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        resumeWaiters()
        let context = session.receivedApplicationContext
        DispatchQueue.main.async {
            WatchStore.shared.reachabilityChanged(session.isReachable)
            WatchStore.shared.applyContext(context)
        }
        completeRefreshTasksIfDone()
    }

    func sessionReachabilityDidChange(_ session: WCSession) {
        let reachable = session.isReachable
        DispatchQueue.main.async { WatchStore.shared.reachabilityChanged(reachable) }
    }

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        DispatchQueue.main.async { WatchStore.shared.applyContext(applicationContext) }
        completeRefreshTasksIfDone()
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        DispatchQueue.main.async { WatchCallManager.shared.handlePhoneMessage(message) }
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any],
                 replyHandler: @escaping ([String: Any]) -> Void) {
        DispatchQueue.main.async { WatchCallManager.shared.handlePhoneMessage(message) }
        replyHandler(["received": true])
    }

    func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        // An older phone build queued message content here. Not shown, not kept.
        completeRefreshTasksIfDone()
    }
}

/// Resumes a continuation exactly once whichever of reply / error / timeout
/// comes first.
nonisolated private final class Once {
    private var done = false
    private let lock = NSLock()
    func run(_ body: () -> Void) {
        lock.lock()
        let first = !done
        done = true
        lock.unlock()
        if first { body() }
    }
}

// MARK: - Store

/// What a screen is showing.
enum BridgeState: Equatable {
    case loading
    case ready
    /// Phone answered with a refusal (disabled / locked / unavailable / …).
    case status(WatchStatus)
    /// Phone not reachable.
    case noPhone
}

struct ThreadModel: Equatable {
    var state: BridgeState = .loading
    var title: String = ""
    var readOnly = false
    var messages: [WatchMessage] = []
}

final class WatchStore: ObservableObject {
    static let shared = WatchStore()

    @Published private(set) var listState: BridgeState = .loading
    @Published private(set) var conversations: [WatchConversation] = []
    @Published private(set) var threads: [String: ThreadModel] = [:]
    @Published private(set) var reachable = false
    /// Conversation to open (notification tap).
    @Published var pendingOpen: WatchConversationRef?

    private(set) var source: WatchBridgeSource = PhoneBridgeSource.shared

    private init() {}

    func use(_ source: WatchBridgeSource) { self.source = source }

    func reachabilityChanged(_ reachable: Bool) {
        let cameBack = reachable && !self.reachable
        self.reachable = reachable
        if cameBack, listState == .noPhone { Task { await refreshList() } }
    }

    private func newRid() -> String { UUID().uuidString }

    // MARK: List

    @MainActor
    func refreshList() async {
        if conversations.isEmpty, listState != .loading { listState = .loading }
        guard let r = await source.request(WatchRequest(rid: newRid(), op: .list)) else {
            listState = .noPhone
            return
        }
        guard r.status == .ok else {
            listState = .status(r.status)
            conversations = []          // nothing stays once the phone says no
            threads = [:]
            writeWidget(.make(status: r.status, unread: 0, names: []))
            return
        }
        conversations = r.convs
        listState = .ready
        let names = r.convs
            .sorted { ($0.unread > 0 ? 1 : 0, $0.ts) > ($1.unread > 0 ? 1 : 0, $1.ts) }
            .prefix(WatchBridge.maxSnapshotNames).map(\.name)
        writeWidget(.make(status: .ok, unread: r.convs.reduce(0) { $0 + $1.unread }, names: Array(names)))
    }

    // MARK: Thread

    @MainActor
    func loadThread(_ ref: WatchConversationRef) async {
        var model = threads[ref.id] ?? ThreadModel(title: ref.name)
        if model.messages.isEmpty { model.state = .loading }
        threads[ref.id] = model
        guard let r = await source.request(WatchRequest(rid: newRid(), op: .thread, kind: ref.kind, cid: ref.cid)) else {
            threads[ref.id]?.state = .noPhone
            return
        }
        guard r.status == .ok else {
            threads[ref.id] = ThreadModel(state: .status(r.status), title: model.title)
            if r.status == .disabled || r.status == .locked { await refreshList() }
            return
        }
        threads[ref.id] = ThreadModel(state: .ready, title: r.title ?? model.title,
                                      readOnly: r.ro ?? false, messages: r.msgs)
        // Opening it on the wrist reads it — the same as on the phone.
        if let i = conversations.firstIndex(where: { $0.id == ref.id }), conversations[i].unread > 0 {
            _ = await source.request(WatchRequest(rid: newRid(), op: .markRead, kind: ref.kind, cid: ref.cid))
            let c = conversations[i]
            var read = WatchConversation(kind: c.kind, cid: c.cid, name: c.name, preview: c.preview,
                                         unread: 0, ts: c.ts, akey: c.akey)
            read.mine = c.mine; read.ro = c.ro; read.pmedia = c.pmedia; read.pvo = c.pvo
            conversations[i] = read
        }
    }

    // MARK: Actions

    /// nil = sent. Otherwise the status to explain (`.error` = not sent).
    @MainActor
    func send(_ text: String, to ref: WatchConversationRef, replyTo: String? = nil) async -> WatchStatus? {
        let req = WatchRequest(rid: newRid(), op: .send, kind: ref.kind, cid: ref.cid,
                               text: String(text.prefix(WatchBridge.maxSendChars)), mid: replyTo)
        guard req.isWellFormed else { return .invalid }
        guard let r = await source.request(req) else { return .unavailable }
        if r.status == .ok {
            await loadThread(ref)
            return nil
        }
        return r.status
    }

    @MainActor
    func react(_ emoji: String, to messageId: String, in ref: WatchConversationRef) async -> WatchStatus? {
        let req = WatchRequest(rid: newRid(), op: .react, kind: ref.kind, cid: ref.cid, mid: messageId, emoji: emoji)
        guard req.isWellFormed else { return .invalid }
        guard let r = await source.request(req) else { return .unavailable }
        if r.status == .ok {
            await loadThread(ref)
            return nil
        }
        return r.status
    }

    /// From the notification's "Mark Read": goes through the phone's gates too.
    @MainActor
    func markRead(_ ref: WatchConversationRef) async {
        _ = await source.request(WatchRequest(rid: newRid(), op: .markRead, kind: ref.kind, cid: ref.cid))
    }

    // MARK: Widget

    func applyContext(_ context: [String: Any]) {
        guard let snap = WatchBridgeSnapshot.decode(context) else { return }
        writeWidget(snap)
        if snap.status != .ok {
            // The phone turned the bridge off / locked: drop what is on screen now.
            conversations = []
            threads = [:]
            listState = .status(snap.status)
        } else if case .status = listState {
            Task { await refreshList() }
        }
    }

    private func writeWidget(_ snap: WatchBridgeSnapshot) {
        if WatchWidgetSnapshotStore.write(snap) {
            WidgetCenter.shared.reloadTimelines(ofKind: WatchWidgetSnapshotStore.widgetKind)
        }
    }
}

/// Enough to open a thread (from a row or a notification).
struct WatchConversationRef: Hashable, Identifiable {
    let kind: WatchConvKind
    let cid: String
    var name: String
    var akey: String

    var id: String { kind.rawValue + ":" + cid }

    init(kind: WatchConvKind, cid: String, name: String, akey: String? = nil) {
        self.kind = kind; self.cid = cid; self.name = name; self.akey = akey ?? cid
    }

    init(_ c: WatchConversation) {
        self.init(kind: c.kind, cid: c.cid, name: c.name, akey: c.akey)
    }
}
