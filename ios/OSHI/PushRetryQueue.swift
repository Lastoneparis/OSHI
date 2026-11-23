//
//  PushRetryQueue.swift
//  OSHI
//
//  Created 2026-06-07 as part of the v1.0.19 push-reliability pass.
//
//  Problem this solves: when MessageManager.notifyVPS calls
//  VoIPPushManager.requestPushNotification and the HTTP POST to
//  /push/send-push fails (5 s timeout because the outbound HTTP/2
//  connection is held hostage by a giant MultiDeviceSync upload, or
//  because the bg task expired mid-flight, or because the network blipped),
//  the catch block just logged "⚠️ Push notification failed" and dropped
//  the request on the floor. The recipient then never got a notification
//  and only saw the message when they manually opened the app to poll.
//
//  PushRetryQueue persists the failed push payload on disk and retries
//  it on every foregrounding + periodic ticks. Bounded retry count.
//

import Foundation
import UIKit

/// On-disk queue of failed push requests, retried on foregrounding.
@MainActor
final class PushRetryQueue {
    static let shared = PushRetryQueue()

    struct Entry: Codable, Equatable {
        let recipientPublicKey: String
        let type: String
        let title: String
        let body: String
        let additionalData: [String: String]
        var attempts: Int
        var firstQueuedAt: Date
        var lastAttemptAt: Date?

        /// Identity for dedup: same recipient + messageId means same push.
        var dedupKey: String {
            let mid = additionalData["messageId"] ?? ""
            return "\(recipientPublicKey)|\(type)|\(mid)"
        }
    }

    /// What actually puts a push on the wire. Production passes the real
    /// `VoIPPushManager` call; a test passes a closure and never touches the network.
    typealias Sender = (Entry) async throws -> Void

    private var entries: [Entry] = []
    private let maxAttempts = 5
    private let backoffSeconds: TimeInterval = 30   // min gap between retries per entry

    /// __STALE_PUSH_2026_08_18__ Age at which a queued push is worthless.
    ///
    /// A push says "wake up, there is something new". It does NOT carry the
    /// message — the relay does, and the app polls on open. So once the message
    /// is minutes old the push has no job left: the recipient either already has
    /// it, or will the moment they open the app.
    ///
    /// Without this bound, `firstQueuedAt` was written and never read, and a push
    /// that failed once was retried on EVERY foregrounding until it burned its 5
    /// attempts — firing a banner for a message the recipient had already read.
    /// That is a notification with nothing behind it, which is precisely the
    /// complaint: "I get notifications from the other phone for nothing."
    ///
    /// 10 minutes: comfortably longer than a network blip or a backgrounded send
    /// finishing late, far shorter than "I opened the app the next morning".
    private let maxAgeSeconds: TimeInterval = 600
    private let storeURL: URL
    private let send: Sender
    /// Reads the wall clock. `Date.init` in production — every call site below still
    /// asks for a FRESH `Date()` exactly where it used to, so `shared` is unchanged.
    /// A test substitutes a clock it can move, which is the only way to exercise the
    /// 30 s backoff and the 10 min staleness rule without sleeping for real.
    private let now: () -> Date
    private var isRetrying = false

    /// __PUSH_RETRY_SEAM_2026_09_12__ The designated init: a store URL, a sender and
    /// a clock. Nothing here reaches for Application Support, the network, or
    /// `NotificationCenter` — those three are what made this class untestable, and
    /// they now live in the `shared` convenience init below and nowhere else.
    init(
        storeURL: URL,
        now: @escaping () -> Date = Date.init,
        sender: Sender? = nil
    ) {
        self.storeURL = storeURL
        self.now = now
        self.send = sender ?? { e in
            try await VoIPPushManager.requestPushNotification(
                to: e.recipientPublicKey,
                type: e.type,
                title: e.title,
                body: e.body,
                additionalData: e.additionalData
            )
        }
        loadFromDisk()
    }

    /// The production instance: same Application Support path, same real sender,
    /// same real clock, same foregrounding observer as before the seam existed.
    private convenience init() {
        let dir = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first!
            .appendingPathComponent("oshi", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        self.init(storeURL: dir.appendingPathComponent("push_retry_queue.json"))

        // Drain on every foregrounding. The lifecycle delegate registers this
        // observer at app launch (see SecureWeb3MessengerApp). Periodic ticks
        // happen via a 30 s timer registered in foreground.
        //
        // Deliberately NOT in the designated init: a test instance that drained
        // itself on an unrelated app notification would be a race, not a fixture.
        NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in await self?.drain() }
        }
    }

    /// Enqueue a failed push request for later retry. Idempotent on dedupKey.
    func enqueue(
        recipientPublicKey: String,
        type: String,
        title: String,
        body: String,
        additionalData: [String: String]
    ) {
        let entry = Entry(
            recipientPublicKey: recipientPublicKey,
            type: type,
            title: title,
            body: body,
            additionalData: additionalData,
            attempts: 0,
            firstQueuedAt: self.now(),
            lastAttemptAt: nil
        )
        if entries.contains(where: { $0.dedupKey == entry.dedupKey }) {
            return  // already queued
        }
        entries.append(entry)
        saveToDisk()
        OshiLog.messaging.info("🔁 PushRetryQueue: enqueued push for \(recipientPublicKey.prefix(12))... (queue size: \(entries.count))")
    }

    /// Retry all pending entries with bounded concurrency and backoff.
    func drain() async {
        guard !isRetrying else { return }
        isRetrying = true
        defer { isRetrying = false }

        if entries.isEmpty { return }
        OshiLog.messaging.info("🔁 PushRetryQueue: draining \(entries.count) pending push(es)")

        let now = self.now()

        // __DRAIN_DOES_NOT_CLOBBER_ENQUEUES_2026_09_12__ Remember exactly which
        // entries this drain owns.
        //
        // The loop below `await`s the network, and this class is @MainActor — so the
        // main actor is FREE during that suspension, and `enqueue(...)` (called from
        // every push-failure catch block in MessageManager/MessageManager+V2) can
        // append a new entry and persist it while we are still in flight. The tail
        // used to be a flat `entries = newEntries`, rebuilt only from the snapshot
        // this loop started with, so that just-queued push was silently discarded —
        // the recipient was never woken for it, which is precisely the failure this
        // queue exists to prevent. `isRetrying` guards a re-entrant *drain*, not a
        // concurrent *enqueue*.
        let drainedKeys = Set(entries.map { $0.dedupKey })

        var newEntries: [Entry] = []
        for var e in entries {
            // Honor backoff
            if let last = e.lastAttemptAt, now.timeIntervalSince(last) < backoffSeconds {
                newEntries.append(e)
                continue
            }
            // Age first: an old push is dropped even if it has attempts left.
            let age = now.timeIntervalSince(e.firstQueuedAt)
            if age > maxAgeSeconds {
                OshiLog.messaging.info("🔁 PushRetryQueue: dropping STALE push for \(e.recipientPublicKey.prefix(12))... (queued \(Int(age))s ago — the message is no longer news)")
                continue  // drop
            }
            if e.attempts >= maxAttempts {
                OshiLog.messaging.info("🔁 PushRetryQueue: giving up on \(e.recipientPublicKey.prefix(12))... after \(e.attempts) attempts")
                continue  // drop
            }
            e.attempts += 1
            e.lastAttemptAt = self.now()

            do {
                try await send(e)
                OshiLog.messaging.info("🔁 PushRetryQueue: ✅ retry success for \(e.recipientPublicKey.prefix(12))... (attempt \(e.attempts))")
                // success → don't re-enqueue
            } catch {
                OshiLog.messaging.info("🔁 PushRetryQueue: ⚠️ retry \(e.attempts) failed: \(error.localizedDescription)")
                newEntries.append(e)
            }
        }
        // Rebuilt entries, PLUS anything that arrived while we were suspended.
        // `enqueue` refuses a dedupKey already present, and every key we drained was
        // present for the whole drain, so the filter can only ever pick up genuinely
        // new pushes — never a duplicate of one we just handled.
        entries = newEntries + entries.filter { !drainedKeys.contains($0.dedupKey) }
        saveToDisk()
    }

    // MARK: - Persistence

    private func loadFromDisk() {
        guard let data = try? AtRestFile.read(storeURL),  // __PLAINTEXT_LEFTOVERS_2026_09_24__
              let decoded = try? JSONDecoder().decode([Entry].self, from: data) else {
            return
        }
        entries = decoded
        if !entries.isEmpty {
            OshiLog.messaging.info("🔁 PushRetryQueue: loaded \(entries.count) pending push(es) from disk")
        }
    }

    private func saveToDisk() {
        guard let data = try? JSONEncoder().encode(entries) else { return }
        try? AtRestFile.write(data, to: storeURL)  // __PLAINTEXT_LEFTOVERS_2026_09_24__ chiffré au repos (ancien clair lu puis rechiffré)
    }

    // MARK: - Diagnostics

    /// Count of pending entries — exposed for the diagnostic UI.
    var pendingCount: Int { entries.count }

    /// Read-only view of the queue. Tests assert on `attempts` / `dedupKey`; giving
    /// them a copy keeps `entries` the only writer of its own state.
    var pending: [Entry] { entries }
}
