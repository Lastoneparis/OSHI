//
//  WatchSendDecision.swift
//  OSHI
//
//  __WATCH_REPLY_QUEUE_2026_08_24__
//

import Foundation
import WatchConnectivity

/// How one Watch→iPhone payload should travel.
enum WatchSendTransport: Equatable {
    /// `WCSession.sendMessage` — a live round-trip; the phone answers, so the
    /// wrist learns whether the message was accepted or refused, and why.
    case live

    /// `WCSession.transferUserInfo` — queued, guaranteed delivery. The system
    /// keeps the transfer across the app exiting, the watch locking and the
    /// phone being out of range, then wakes OSHI on the phone to take it. No
    /// answer comes back, so the wrist can promise delivery but not acceptance.
    case queued

    /// The session has not finished activating. Nothing may be handed to it yet
    /// — `transferUserInfo` on a non-activated session raises — so hold the
    /// payload and replay it from `activationDidCompleteWith`.
    case bufferUntilActivated
}

/// What to do after `sendMessage`'s `errorHandler` has already fired.
///
/// The distinction that matters is not "transient vs permanent" but **did the
/// phone already get it**. Re-sending something that arrived puts the user's
/// message on the recipient's screen twice, which is worse than one send whose
/// fate is uncertain.
enum WatchSendRecovery: Equatable {
    /// The payload provably never reached the phone. Hand it to the queue.
    case queue

    /// It may already be on the phone: the message went over, only the reply
    /// did not come back. Stop, and tell the truth rather than duplicate.
    case giveUpMayHaveArrived

    /// It can never arrive, whatever transport is used.
    case giveUpPermanent
}

enum WatchSendDecision {

    /// - Note: `isReachable` is *not* a precondition for delivery. It only says
    ///   whether a live round-trip is available right now. Treating it as a
    ///   precondition is what made every reply path on the wrist fail closed:
    ///   the compose screen refused to send, the notification's Reply button
    ///   dropped the text with nothing but a `print`, and the app came to the
    ///   foreground on the "iPhone not reachable" screen — which is the state
    ///   the user reported as "there is a Reply button but we cannot reply".
    static func transport(activationState: WCSessionActivationState,
                          isReachable: Bool) -> WatchSendTransport {
        switch activationState {
        case .activated:
            return isReachable ? .live : .queued
        case .inactive, .notActivated:
            return .bufferUntilActivated
        @unknown default:
            // A state this build has never heard of. Buffering costs a moment;
            // guessing `.live` costs the message.
            return .bufferUntilActivated
        }
    }

    static func recovery(from code: WCError.Code) -> WatchSendRecovery {
        switch code {
        // Delivered — or possibly delivered — and only the answer was lost.
        case .messageReplyFailed, .messageReplyTimedOut:
            return .giveUpMayHaveArrived

        // No transport can fix these: there is no counterpart, or the payload
        // itself is not carryable.
        case .sessionNotSupported,
             .sessionMissingDelegate,
             .deviceNotPaired,
             .watchAppNotInstalled,
             .companionAppNotInstalled,
             .watchOnlyApp,
             .invalidParameter,
             .payloadTooLarge,
             .payloadUnsupportedTypes,
             .insufficientSpace,
             .fileAccessDenied:
            return .giveUpPermanent

        // `.notReachable`, `.deliveryFailed`, `.sessionInactive`,
        // `.sessionNotActivated`, `.transferTimedOut`, `.genericError` and any
        // code a future watchOS adds.
        //
        // The default is `.queue`, and deliberately so: `sendMessage`'s
        // `errorHandler` firing at all means the round-trip did not complete,
        // and the only two codes that mean "it went over, the reply did not"
        // are named above. An unknown code that really did deliver would
        // duplicate one message; an unknown code treated as fatal loses every
        // message behind it. The two are not symmetric.
        default:
            return .queue
        }
    }
}

/// What became of one payload, as the screen that asked for it needs to know.
enum WatchSendOutcome {
    /// The phone answered. `response` carries its `success` / `error` keys.
    case sent([String: Any])
    /// Handed to `transferUserInfo`. It will arrive; nobody has accepted it yet.
    case queued
    /// It is not going. `reason` is user-facing.
    case failed(reason: String)
}
