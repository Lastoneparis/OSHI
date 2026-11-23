import Foundation

/// Two decisions about a call's audio session that are easy to get wrong and
/// impossible to see on a device.
///
/// __CALL_AUDIO_POLICY_2026_11_02__ Both come from the same report: "when I
/// start a call while listening to music, the music should pause" and "when the
/// screen switches off during a call, the audio should carry on".
///
/// They live here, free of AVFoundation and UIKit, because each is a rule about
/// a handful of booleans and a clock — and in `VoiceCallManager` that rule
/// would be buried in a 19,000-line file among a dozen audio-session paths,
/// provable only by holding two phones and playing Spotify.
enum CallAudioPolicy {

    // MARK: - Placing a call must silence whatever else is playing

    /// Whether dialling should take the audio session in order to interrupt
    /// another app's playback.
    ///
    /// WHY THIS IS A DECISION AT ALL: setting a category interrupts nobody —
    /// only ACTIVATING a non-mixing session does. Under CallKit the activation
    /// is supposed to come from `provider(_:didActivate:)`, but for an OUTGOING
    /// call that arrives when the call CONNECTS, so for the whole ringback
    /// window the session is passive and Music keeps playing over the ring.
    /// (Measured 2026-06-10, and the note survived the 2026-07-26 change that
    /// removed the activation for being "redundant" — it was not.)
    ///
    /// The guard matters as much as the rule: when nothing else is playing
    /// there is nothing to interrupt, so we leave the session alone and the
    /// CallKit handoff keeps the exact behaviour it has today. The fix cannot
    /// regress the quiet case because it does not run in it.
    static func shouldInterruptOtherAudio(otherAudioPlaying: Bool,
                                          callSessionAlreadyActive: Bool) -> Bool {
        guard otherAudioPlaying else { return false }
        // Already active and owned by the call: whoever activated it has
        // already interrupted the other app, and re-activating a live CallKit
        // session is the bounce that cost two earlier fixes.
        return !callSessionAlreadyActive
    }

    // MARK: - An interruption we are never told has ended

    enum Recovery: Equatable {
        case wait
        /// Resume, with the reason that unlatched it — logged, so a call log
        /// says WHY audio came back.
        case resume(String)
    }

    /// Never fight the first instants of an interruption: a restart there
    /// races the system taking the session and throws for nothing.
    static let minimumHold: TimeInterval = 1.5
    /// A latch that can only be released by a notification iOS does not
    /// guarantee is a permanent mute. This is how long we let it hold.
    static let maximumHold: TimeInterval = 20

    /// `AVAudioSession.interruptionNotification` with `.began` suspends our
    /// engine and suppresses transmission. `.ended` is the only thing that
    /// undoes it — and Apple does not promise to deliver `.ended` to an app
    /// that was in the background, which is exactly where a call goes when the
    /// screen switches off.
    ///
    /// The consequence in this codebase is not degraded audio but NO audio: the
    /// same flag gates `processAndSendAudio`, the engine-health watchdog AND
    /// `checkConnectionStatus`, so a missed `.ended` leaves the call silent in
    /// both directions with every watchdog deliberately muzzled, until someone
    /// hangs up.
    ///
    /// So the latch gets three more ways out, in order of how much they prove:
    ///  1. CallKit says the call's own audio session is live again — that is
    ///     the system telling us the interruption is over, and it is already
    ///     rebuilding the engine on it;
    ///  2. the interrupter has stopped and we are back in front of the user;
    ///  3. time. A failed resume costs one engine rebuild that the watchdog
    ///     retries; staying latched costs the rest of the call.
    static func recovery(interruptedFor: TimeInterval,
                         callKitOwnsSession: Bool,
                         callKitSessionActive: Bool,
                         otherAudioPlaying: Bool,
                         appIsForeground: Bool,
                         minimumHold: TimeInterval = CallAudioPolicy.minimumHold,
                         maximumHold: TimeInterval = CallAudioPolicy.maximumHold) -> Recovery {
        if interruptedFor < minimumHold { return .wait }
        if callKitOwnsSession && callKitSessionActive { return .resume("callkit-session-live") }
        if !otherAudioPlaying && appIsForeground { return .resume("interrupter-gone") }
        if interruptedFor >= maximumHold { return .resume("latch-timeout") }
        return .wait
    }
}
