//
//  CallRatingManager.swift
//  OSHI
//
//  __CALL_RATING_2026_09_22__
//
//  After a call ends, ask the person how it went — the way WhatsApp does.
//
//  Why this exists: the server can measure a call (packets each way, which
//  transport carried them, how lopsided the two directions were) but it cannot
//  tell whether the person could actually HEAR. Measured Sep 15-22 on the live
//  server: calls that looked healthy in the relay counters were one-way in
//  practice, and calls that looked dead in the relay counters had in fact run
//  cleanly over TURN, which the relay never sees. The rating is the missing
//  half — it turns "450 packets one way, 0 back" into "…and they heard nothing".
//
//  What leaves the device: a star count, optional canned reasons, an optional
//  free-text note the person typed, the callId, and the platform/version. Never
//  the peer's identity, never anything from the conversation. The server joins
//  it to what it measured and relays the pair to the monitoring channel.
//

import Foundation
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

// MARK: - Model

/// A finished call waiting for a verdict.
struct PendingCallRating: Identifiable, Equatable {
    let id = UUID()
    let callId: String
    let peerName: String
    let durationSeconds: TimeInterval
    let wasVideo: Bool
    /// Ended on anything other than a normal hang-up. Those are asked about
    /// every time — they are exactly the calls worth hearing about.
    let endedAbnormally: Bool
    /// Read at call end, because the sheet outlives the call that produced it.
    /// Last, so the memberwise initialiser keeps its existing argument order.
    var evidence: CallRatingEvidence? = nil
}


/// __TX_VOICE_EVIDENCE_2026_10_29__ What THIS side knows about the call, sent with
/// the verdict. Mirrors `CallRatingManager.Evidence` on Android; the wire names must
/// match, because both land in one channel.
///
/// WHY: the monitoring channel's numbers are the RELAY's, and the relay can only see
/// that bytes moved. Call `97d98d39` was rated 1/5 `theyCouldNotHearMe` with 4257
/// frames forwarded in the complained-about direction over a proven UDP path for the
/// whole 1:25 — a flawless record of a call the person says was silent. The relay
/// cannot tell a microphone that captured silence from a peer that failed to play what
/// arrived, so the report could not name a side, and the complaint was unfalsifiable.
/// These five numbers decide it. See ServerPatches/call_rating_evidence/.
struct CallRatingEvidence: Equatable {
    /// Audio frames we encoded and handed to the transport.
    let txFrames: UInt64
    /// Audio frames we decoded.
    let rxFrames: UInt64
    /// 0…100 share of transmitted frames carrying voice; nil when never measured.
    let txVoicePercent: Int?
    /// 0…100 loudest transmitted frame; nil when never measured.
    let txPeakPercent: Int?
    /// The codec the send path actually used, e.g. "opus".
    let codec: String
    /// The peer's platform as this side resolved it: "ios", "android", "".
    let peerPlatform: String
}

/// __TX_VOICE_EVIDENCE_2026_10_29__ Was there a VOICE in what we transmitted?
///
/// Port of Android `CallTxVoiceEvidence`. The call screen already measures this (at
/// 20 Hz, via `VoiceCallManager.meterLevel`) and used to throw it away at hang-up.
///
/// Lives here rather than in its own file on purpose: the OSHI target is not a
/// synchronised group, so a new .swift under OSHI/ needs four hand-written pbxproj
/// entries and that file is edited concurrently by other work.
final class CallTxVoiceEvidence {

    /// −45 dBFS RMS on `meterLevel`'s scale. Comfortably above room noise (−55, where
    /// the meter reads 0) and well below ordinary speech (−30…−20) — which is what
    /// makes 0% mean a dead microphone rather than a quiet room.
    static let voiceFloorDbfs: Float = -45
    static let voiceFloorLevel: Float = min(1, max(0, (voiceFloorDbfs + 55) / 43))

    private var observed: UInt64 = 0
    private var aboveFloor: UInt64 = 0
    private var peak: Float = 0

    /// One capture frame's meter level. Only frames we actually transmit belong here:
    /// a muted frame is never sent, so counting it would dilute the very number this
    /// exists to report.
    func observe(_ level: Float) {
        guard !level.isNaN else { return }
        let clamped = min(1, max(0, level))
        observed += 1
        if clamped >= Self.voiceFloorLevel { aboveFloor += 1 }
        if clamped > peak { peak = clamped }
    }

    func reset() {
        observed = 0
        aboveFloor = 0
        peak = 0
    }

    /// nil when nothing was observed — no measurement is not a measurement of zero.
    func snapshot() -> (voicePercent: Int, peakPercent: Int)? {
        guard observed > 0 else { return nil }
        let voice = Int((Double(aboveFloor) * 100.0 / Double(observed)).rounded(.down))
        return (min(100, max(0, voice)), min(100, max(0, Int(peak * 100))))
    }
}

/// The canned complaints. Tapping one is far more likely than typing, and a
/// fixed vocabulary is what makes the channel greppable instead of prose.
enum CallRatingReason: String, CaseIterable, Identifiable {
    case couldNotHearThem
    case theyCouldNotHearMe
    case choppy
    case delay
    case echo
    case dropped

    var id: String { rawValue }

    var localizedTitle: String {
        switch self {
        case .couldNotHearThem:
            return NSLocalizedString("call.rating.reason.could_not_hear_them",
                                     comment: "Call quality complaint: the other person's audio never arrived")
        case .theyCouldNotHearMe:
            return NSLocalizedString("call.rating.reason.they_could_not_hear_me",
                                     comment: "Call quality complaint: my audio never reached the other person")
        case .choppy:
            return NSLocalizedString("call.rating.reason.choppy",
                                     comment: "Call quality complaint: audio was robotic, broken up or distorted")
        case .delay:
            return NSLocalizedString("call.rating.reason.delay",
                                     comment: "Call quality complaint: a long lag between speaking and being heard")
        case .echo:
            return NSLocalizedString("call.rating.reason.echo",
                                     comment: "Call quality complaint: hearing your own voice back")
        case .dropped:
            return NSLocalizedString("call.rating.reason.dropped",
                                     comment: "Call quality complaint: the call ended by itself")
        }
    }

    var symbolName: String {
        switch self {
        case .couldNotHearThem:   return "ear.trianglebadge.exclamationmark"
        case .theyCouldNotHearMe: return "mic.slash"
        case .choppy:             return "waveform.badge.exclamationmark"
        case .delay:              return "clock.badge.exclamationmark"
        case .echo:               return "speaker.wave.3"
        case .dropped:            return "phone.down"
        }
    }
}

// MARK: - Manager

@MainActor
final class CallRatingManager: ObservableObject {

    static let shared = CallRatingManager()

    /// Non-nil while the sheet should be on screen.
    @Published var pending: PendingCallRating?

    /// Read by `RateAppManager` so the App Store ask never lands on top of this
    /// one. `RatePresentationProbe` already catches it on iOS by seeing a
    /// presented view controller; on macOS there is no such probe, so the two
    /// managers agree through this flag instead.
    var isPresenting: Bool { pending != nil }

    private enum Config {
        /// Below this there was no conversation to judge. Deliberately far
        /// shorter than `RateAppManager`'s 60s: a 9-second call that failed is
        /// the single most useful thing a person can report.
        static let minSeconds: TimeInterval = 8
        /// Normal calls are sampled — being asked after every call is the
        /// fastest way to train someone to dismiss the sheet unread.
        static let sampleEveryNthGoodCall = 3
        /// Quiet period after any ask, so a bad afternoon is not a dozen sheets.
        static let cooldownAfterAsk: TimeInterval = 20 * 60 * 60
        /// A call that ended abnormally overrides the sample, but still waits
        /// this long after the previous ask.
        static let cooldownAfterAskAbnormal: TimeInterval = 3 * 60 * 60
        /// Hard ceiling regardless of everything above.
        static let maxAsksPerWeek = 5
        /// Let the call UI finish dismissing before the sheet rises, and stay
        /// ahead of `RateAppManager`'s own 1.5s evaluation so its busy-probe
        /// sees this sheet and stands down.
        static let presentDelay: TimeInterval = 0.6
    }

    private enum Key {
        static let goodCallCounter = "CallRating_GoodCallCounter"
        static let lastAskDate     = "CallRating_LastAskDate"
        static let askDates        = "CallRating_AskDates"       // [Date], trimmed to a week
        static let lastRatedCallId = "CallRating_LastRatedCallId"
    }

    private let defaults = UserDefaults.standard
    private var submittedCallIds = Set<String>()

    private init() {}

    // MARK: Deciding whether to ask

    /// Called from the call managers the moment a call finishes.
    /// - Parameter wasFault: the call ended because something BROKE — the network
    ///   errored, the peer dropped, or the silence watchdog killed it. Distinct
    ///   from `!endedNormally`, which is also true for a declined call and for
    ///   one answered on another device: those are not faults and asking "how
    ///   did that sound?" about them is nonsense.
    func callDidEnd(callId: String,
                    peerName: String,
                    durationSeconds: TimeInterval,
                    wasVideo: Bool,
                    endedNormally: Bool,
                    wasFault: Bool = false,
                    evidence: CallRatingEvidence? = nil) {
        guard !callId.isEmpty else { return }
        // __SHORT_FAILURES_COULD_NEVER_BE_REPORTED_2026_11_07__
        //
        // THE BUG THIS CLOSES: `shouldAsk` says "a call that dropped or errored
        // is always worth asking about", and this floor sat ABOVE it, so that
        // promise was void for any such call shorter than 8 s — which is most
        // of them. Measured on the relay over 06-07/11: 17 calls, 8 of them
        // 0-4 s, and ONE rating came back in total. The calls that produced no
        // feedback are the same ones the relay scored `relay-never-delivered`:
        // the single most diagnostic thing a person could have told us was the
        // one thing the client refused to ask.
        //
        // The floor still applies to calls that merely ENDED short — a mis-tap,
        // a quick "call you back" — because being asked about those is how you
        // train someone to dismiss the sheet unread. It is a sampling rule, not
        // a fault rule, and it no longer pretends to be both.
        guard wasFault || durationSeconds >= Config.minSeconds else { return }
        // One verdict per call, however many times the end is reported. The
        // call managers can fire their end path more than once (CallKit and the
        // signalling layer both report it) and two sheets for one call reads
        // like a bug to the person looking at it.
        guard defaults.string(forKey: Key.lastRatedCallId) != callId else { return }
        guard !submittedCallIds.contains(callId) else { return }
        guard pending == nil else { return }

        let abnormal = !endedNormally
        guard shouldAsk(abnormal: abnormal) else { return }

        defaults.set(callId, forKey: Key.lastRatedCallId)
        noteAsk()

        let item = PendingCallRating(callId: callId,
                                     peerName: peerName,
                                     durationSeconds: durationSeconds,
                                     wasVideo: wasVideo,
                                     endedAbnormally: abnormal,
                                     evidence: evidence)
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Config.presentDelay * 1_000_000_000))
            guard let self, self.pending == nil else { return }
            self.pending = item
        }
    }

    private func shouldAsk(abnormal: Bool) -> Bool {
        let now = Date()

        // Weekly ceiling first — it outranks every reason to ask.
        let recent = askDatesWithinLastWeek(now: now)
        if recent.count >= Config.maxAsksPerWeek { return false }

        if let last = defaults.object(forKey: Key.lastAskDate) as? Date {
            let cooldown = abnormal ? Config.cooldownAfterAskAbnormal : Config.cooldownAfterAsk
            if now.timeIntervalSince(last) < cooldown { return false }
        }

        // A call that dropped or errored is always worth asking about.
        if abnormal { return true }

        let n = defaults.integer(forKey: Key.goodCallCounter) + 1
        defaults.set(n, forKey: Key.goodCallCounter)
        guard n % Config.sampleEveryNthGoodCall == 0 else { return false }
        return true
    }

    private func askDatesWithinLastWeek(now: Date) -> [Date] {
        let all = (defaults.array(forKey: Key.askDates) as? [Date]) ?? []
        let cutoff = now.addingTimeInterval(-7 * 24 * 60 * 60)
        return all.filter { $0 > cutoff }
    }

    private func noteAsk() {
        let now = Date()
        defaults.set(now, forKey: Key.lastAskDate)
        var recent = askDatesWithinLastWeek(now: now)
        recent.append(now)
        defaults.set(recent, forKey: Key.askDates)
    }

    // MARK: Answering

    func dismiss() {
        pending = nil
    }

    /// Send the verdict. Fire-and-forget by design: a rating is never worth an
    /// error message, a retry queue or a spinner in the person's way.
    func submit(rating: Int, reasons: Set<CallRatingReason>, comment: String) {
        guard let item = pending else { return }
        pending = nil
        submittedCallIds.insert(item.callId)

        let clamped = max(1, min(5, rating))
        let trimmed = comment.trimmingCharacters(in: .whitespacesAndNewlines)
        let myKey = MessageManager.sharedIdentityManager?.publicKey ?? ""
        guard !myKey.isEmpty else { return }

        var body: [String: Any] = [
            "callId": item.callId,
            "sender": myKey,
            "rating": clamped,
            "platform": Self.platformName,
            "appVersion": Self.appVersion
        ]
        if !reasons.isEmpty {
            body["reasons"] = reasons.map { $0.rawValue }.sorted()
        }
        if !trimmed.isEmpty {
            body["comment"] = String(trimmed.prefix(300))
        }
        if let e = item.evidence {
            var ev: [String: Any] = [
                "txFrames": e.txFrames,
                "rxFrames": e.rxFrames,
                "codec": e.codec,
                "peer": e.peerPlatform
            ]
            // Absent, not zero: a level that was never measured and a microphone that
            // stayed silent are the two answers this field exists to separate.
            if let v = e.txVoicePercent { ev["txVoicePct"] = v }
            if let p = e.txPeakPercent { ev["txPeakPct"] = p }
            body["evidence"] = ev
        }

        guard let url = URL(string: "https://oshi-messenger.com/voip/call-rating"),
              let payload = try? JSONSerialization.data(withJSONObject: body) else { return }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = payload
        request.timeoutInterval = 15
        // __CALL_PUSH_AUTH_IOS_2026_09_23__ call_server route: signed like the others
        // (claimed identity = body `sender`).
        CallPushSigner.sign(&request)

        URLSession.shared.dataTask(with: request) { _, response, error in
            if let error {
                print("📞⭐️ Call rating not sent: \(error.localizedDescription)")
            } else if let http = response as? HTTPURLResponse {
                print("📞⭐️ Call rating sent for \(item.callId.prefix(8)): HTTP \(http.statusCode)")
            }
        }.resume()
    }

    // MARK: Environment

    static var platformName: String {
        #if os(macOS)
        return "macOS"
        #elseif os(watchOS)
        return "watchOS"
        #elseif targetEnvironment(macCatalyst)
        return "macCatalyst"
        #else
        return UIDevice.current.userInterfaceIdiom == .pad ? "iPadOS" : "iOS"
        #endif
    }

    static var appVersion: String {
        let short = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(short) (\(build))"
    }
}
