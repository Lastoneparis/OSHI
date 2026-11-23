//
//  CallReconnectState.swift
//  OSHI — who currently believes the call is reconnecting.
//
//  Extracted from VoiceCallManager so the rule can actually be tested. The bug
//  it replaces was not in any one line; it was in the SHAPE of the state:
//
//    Two independent sources set `connectionQuality = .reconnecting` — the
//    media-silence watchdog, and the VPS latency probe. Only the watchdog also
//    set `isPlayingConnectionLossBeep`, and all three clear paths were gated on
//    that same flag. A single failed probe therefore armed a state nothing
//    could disarm, and the badge stayed up for the rest of the call over
//    perfectly good audio.
//
//  The fix is to stop treating "is the beep playing" as a proxy for "does
//  anyone still think we are reconnecting". Each source owns a NAMED reason,
//  arms it, and is the one that releases it. The badge is derived — it is on
//  exactly while at least one reason is held — so it cannot get stuck unless a
//  source genuinely never releases.
//
//  Adding a third source cannot resurrect the original bug: `Reason` is an
//  enum, so a new source must pick a case, and picking one obliges it to
//  release the same case.
//

import Foundation

/// The set of reasons currently claiming the call is reconnecting.
///
/// A value type on purpose: it holds no timers, no queues and no side effects,
/// so the rule can be exercised directly in tests rather than inferred from a
/// live call. `VoiceCallManager` owns exactly one and only ever touches it on
/// the main queue.
struct CallReconnectState: Equatable {

    enum Reason: CaseIterable {
        /// No inbound audio for `connectionLossThreshold`.
        case mediaSilence
        /// The VPS latency probe failed repeatedly.
        case probeFailed
    }

    private(set) var reasons: Set<Reason> = []

    /// True while anything still claims the call is reconnecting. This is what
    /// the badge is derived from — never a separately-stored flag, because two
    /// representations of one fact is how the original bug happened.
    var isReconnecting: Bool { !reasons.isEmpty }

    var isHeld: (Reason) -> Bool { { self.reasons.contains($0) } }

    /// - Returns: true when this call turned the badge ON (no reason was held
    ///   before). Callers use it to log the transition rather than every arm.
    @discardableResult
    mutating func arm(_ reason: Reason) -> Bool {
        let wasIdle = reasons.isEmpty
        reasons.insert(reason)
        return wasIdle
    }

    /// - Returns: true when this call turned the badge OFF — i.e. this was the
    ///   last reason held. Deliberately NOT true when other reasons remain: a
    ///   real media outage must not be cleared by a probe that happens to
    ///   recover a moment earlier.
    @discardableResult
    mutating func disarm(_ reason: Reason) -> Bool {
        guard reasons.remove(reason) != nil else { return false }
        return reasons.isEmpty
    }

    /// Everything is settled — a demonstrably recovered transport, or teardown.
    /// - Returns: true when the badge was on and is now off.
    @discardableResult
    mutating func clearAll() -> Bool {
        let wasReconnecting = isReconnecting
        reasons.removeAll()
        return wasReconnecting
    }
}
