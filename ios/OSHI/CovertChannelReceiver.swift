//
//  CovertChannelReceiver.swift
//  OSHI — authenticated V2 cover receive lifecycle
//
//  This class deliberately has no analytics, pixel, DNS, config, text, or image
//  carrier fallback. V2 owns encryption, authorization, durable receipts and
//  acknowledgements; legacy carrier polling was fingerprintable and unaudited.
//

import Foundation

final class CovertChannelReceiver {
    static let shared = CovertChannelReceiver()

    private let pollIntervalActive: TimeInterval = 15
    private let pollIntervalBackground: TimeInterval = 60
    private var pollTimer: Timer?
    private weak var messageManager: MessageManager?
    private var settingsObserver: NSObjectProtocol?

    private init() {
        settingsObserver = NotificationCenter.default.addMainThreadObserver(
            forName: UserDefaults.didChangeNotification
        ) { [weak self] _ in
            guard let self else { return }
            let enabled = UserDefaults.standard.bool(forKey: "covertChannelEnabled")
            if enabled && self.pollTimer == nil {
                self.startPolling()
            } else if !enabled && self.pollTimer != nil {
                self.stopPolling()
            }
        }
    }

    deinit {
        if let settingsObserver { NotificationCenter.default.removeObserver(settingsObserver) }
    }

    /// Retains the established app lifecycle API. Identity is authenticated by
    /// V2Client when each pull is constructed, not by a parallel carrier relay.
    func configure(identityManager: IdentityManager, messageManager: MessageManager) {
        _ = identityManager
        self.messageManager = messageManager
    }

    func startPolling() {
        // __COVER_MODE_NO_RECURSION_2026_11_18__ `pollTimer` first: reading `mode`
        // can post a defaults change that re-enters here through the observer.
        guard pollTimer == nil,
              UserDefaults.standard.bool(forKey: "covertChannelEnabled"),
              CoverTransport.mode != nil,
              pollTimer == nil else { return }
        poll()
        scheduleNext(base: pollIntervalActive)
    }

    func stopPolling() {
        pollTimer?.invalidate()
        pollTimer = nil
    }

    func setActivePolling(_ active: Bool) {
        stopPolling()
        guard UserDefaults.standard.bool(forKey: "covertChannelEnabled"),
              CoverTransport.mode != nil else { return }
        poll()
        scheduleNext(base: active ? pollIntervalActive : pollIntervalBackground)
    }

    /// One-shot timer re-armed with a jittered delay (uniform ±50 % around the
    /// base, mean unchanged) instead of a repeating 15 s / 60 s timer, whose
    /// exact period was visible to any passive observer as the gap between
    /// cover requests. Mirrors Android `PollBackoff.coverJitteredMs`.
    private func scheduleNext(base: TimeInterval) {
        pollTimer = Timer.scheduledTimer(withTimeInterval: Self.jittered(base), repeats: false) { [weak self] _ in
            guard let self, self.pollTimer != nil else { return }
            self.poll()
            self.scheduleNext(base: base)
        }
    }

    static let jitterFraction = 0.5
    static func jittered(_ base: TimeInterval) -> TimeInterval {
        base * Double.random(in: (1 - jitterFraction)...(1 + jitterFraction))
    }

    private func poll() {
        guard CoverTransport.mode != nil else { return }
        messageManager?.v2PullTick(force: true)   // already jittered by scheduleNext
    }
}
