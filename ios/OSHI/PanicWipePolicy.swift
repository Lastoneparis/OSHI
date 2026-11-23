import Foundation

/// __PANIC_WIPE_2026_11_03__ "Make this phone stop being evidence."
///
/// A panic wipe is not account deletion with a shorter confirmation sheet. The
/// two differ on the only axis that matters when someone is reaching for it:
/// `AccountDeletionView` reaches the server FIRST and aborts if the request
/// fails, because wiping before `DELETE /v2/account` orphans the user's
/// server-side records for ever. A panic wipe accepts exactly that cost.
/// Orphaned rows on a relay are not what gets someone hurt at a demonstration,
/// and a wipe that can be defeated by jamming the signal is not a wipe.
///
/// So panic never waits for the network, never aborts, and never asks.
///
/// Pure and UIKit-free, so the order and the invariants are testable. The step
/// bodies live in `PanicWipe.swift`; what belongs here is the part that is easy
/// to get wrong and invisible when you do.
///
/// Android mirror: `util/PanicWipePolicy.kt`.
enum PanicWipePolicy {

    /// The steps of a panic wipe, in the order they must run. The order is not
    /// cosmetic — each position is load-bearing and asserted in
    /// `PanicWipePolicyTests`.
    enum Step: String, CaseIterable {
        /// First, always. While the radios are up this phone is still
        /// advertising a BLE identity, still relaying other people's mesh
        /// traffic and still answering LoRa. Wiping state under a live session
        /// leaves the device transmitting under keys that no longer exist.
        ///
        /// It is also the step that helps even if everything after it fails: a
        /// phone that has gone quiet has stopped adding to the record.
        case stopRadios

        /// Delivered notifications are a copy of the message list that no
        /// database wipe touches. Leave them and the previews are still
        /// readable on a locked screen — the surface the person panicking is
        /// most likely to be holding up to someone.
        case cancelNotifications

        /// Decrypted media and scratch files before the keys that protect
        /// them. A file whose key is gone but which was never deleted is still
        /// recoverable by a tool that does not need our key at all.
        case wipeMedia

        /// Messages, contacts, call records, group state.
        case wipeDatabase

        /// Identity key, ratchet state, prekeys — Keychain included.
        case wipeKeys

        /// Last of the destructive steps, and the one most easily forgotten.
        ///
        /// `IdentityManager.disconnectWallet()` ARCHIVES this identity's
        /// messages, groups, ratchet sessions, aliases and profile under
        /// per-identity `UserDefaults` keys so a later switch-back can restore
        /// them. That is right for switching identities and catastrophic for a
        /// panic wipe: the archives are the identity, written back to disk a
        /// moment after it was "erased". The vault entry goes too, or the
        /// identity is still offered on the switch screen.
        case wipePreferences

        /// The only feedback a panic wipe gives, and the right one: no toast to
        /// screenshot, no dialog to dismiss, a device that looks like a fresh
        /// install.
        case returnToOnboarding
    }

    /// The one true order.
    static let order: [Step] = [
        .stopRadios,
        .cancelNotifications,
        .wipeMedia,
        .wipeDatabase,
        .wipeKeys,
        .wipePreferences,
        .returnToOnboarding,
    ]

    /// Steps whose failure leaves the identity or its data RECOVERABLE, and
    /// which may never be skipped or made conditional on anything — least of
    /// all on a network call succeeding.
    static let mustRun: Set<Step> = [
        .wipeMedia,
        .wipeDatabase,
        .wipeKeys,
        .wipePreferences,
    ]

    /// No step of a panic wipe may depend on the network. A function rather
    /// than a comment so a step added later has to answer the question.
    static func requiresNetwork(_ step: Step) -> Bool {
        switch step {
        case .stopRadios, .cancelNotifications, .wipeMedia,
             .wipeDatabase, .wipeKeys, .wipePreferences, .returnToOnboarding:
            return false
        }
    }

    /// How long push de-registration may hold up the wipe.
    ///
    /// Worth attempting — the relay otherwise keeps pushing to a key this
    /// device no longer has, the "phantom ringing" symptom
    /// `disconnectWallet()` documents — but it is a network round trip, and a
    /// network round trip inside a panic wipe is an opportunity to hang. Best
    /// effort, hard ceiling, never a precondition.
    static let pushUnregisterBudget: TimeInterval = 0.5

    /// A wipe in flight swallows further triggers. There is no confirmation
    /// dialog to absorb a shaking hand, so the fourth and fifth taps of a
    /// frightened triple-tap arrive as fresh requests.
    static func shouldStart(alreadyRunning: Bool) -> Bool { !alreadyRunning }

    /// Did a wipe that reported these steps actually finish the job?
    static func isComplete(_ completed: Set<Step>) -> Bool {
        mustRun.isSubset(of: completed)
    }
}

/// __PANIC_WIPE_2026_11_03__ The gesture that fires `PanicWipePolicy`.
///
/// Three taps on the OSHI wordmark, and nothing else: no menu entry, no
/// settings row, no long press. A panic switch that has to be found in a menu
/// is not one, and a labelled button marked "erase everything" is a button an
/// officer can be asked to press.
///
/// Who uses what, so none of this reads as declared-and-never-called:
/// `MessagesListView` consumes `tapsRequired` and lets SwiftUI's native
/// `onTapGesture(count:)` do the timing, because it already does it correctly.
/// `window`, `retained` and `shouldTrigger` are the manual tap accounting
/// Android needs — Compose has no counted-tap gesture — and they live here so
/// the two platforms cannot drift on how many taps or how long a window, with
/// `PanicWipePolicyTests` holding both sides to it.
enum PanicGesture {

    /// Taps needed. Matches bitchat's own triple-tap, for muscle memory.
    static let tapsRequired: Int = 3

    /// __ANATOLE_1311_IOS__ (N6) The gesture is OPT-IN. Anatole: "intégrer un
    /// bouton dans les paramètres pour activer la suppression … afin d'éviter
    /// d'imposer cela et de surprendre l'utilisateur". Three stray taps on the
    /// title erased a phone with no confirmation; now that only happens for
    /// someone who turned it on in Settings › Security. Android's key has the
    /// same meaning (`SettingsViewModel.panicWipe`, off by default).
    static let enabledDefaultsKey = "oshi.panicWipe.logoTapEnabled"

    /// Off unless the user turned it on. Pure, so the default is tested.
    static func isArmed(_ defaults: UserDefaults = .standard) -> Bool {
        defaults.bool(forKey: enabledDefaultsKey)
    }

    /// How long the taps have to arrive in. Short enough that three separate
    /// visits to the screen cannot add up to a wipe; long enough to survive a
    /// cold phone, gloves, and a hand that is not steady.
    static let window: TimeInterval = 1.5

    /// Taps still inside the window at `now`, oldest first. Dropping the stale
    /// ones is what stops a tap from yesterday being the first of today's
    /// three.
    static func retained(_ taps: [TimeInterval], now: TimeInterval) -> [TimeInterval] {
        taps.filter { now - $0 < window }
    }

    /// `true` when `taps` — already filtered by `retained` — completes the
    /// gesture. The caller clears its tap list on `true`, so the tap after a
    /// wipe starts counting from nothing rather than firing again.
    static func shouldTrigger(_ taps: [TimeInterval]) -> Bool {
        taps.count >= tapsRequired
    }
}
