//
//  LinkedDeviceWatch.swift
//  OSHI
//
//  Notices when a device is linked to this account, so that linking one cannot be silent.
//

import Foundation

/// What turns "a device was linked" from something you could have found out into something
/// you are told.
///
/// ============================================================ THE ATTACK THIS ANSWERS
///
/// Netzpolitik published documents in 2026 showing Germany's Customs Office reading Signal,
/// WhatsApp and Telegram messages **without breaking any encryption**. The method is the
/// linked-device feature: with brief access to an unlocked phone, an investigator links a
/// computer they control to the account and receives everything from then on, in plaintext,
/// on their own machine. The crypto is untouched and works exactly as designed.
///
/// Every one of those apps already shows a list of linked devices, and OSHI does too
/// (`WebSessionManager.fetchActiveSessions`). A list is not a defence. **The method works
/// because nobody opens that screen.** The defence is the app telling you, unprompted, at
/// the moment it happens — so the window between "a device was linked" and "you know" is
/// seconds rather than never.
///
/// ============================================================ WHY IT IS ITS OWN TYPE
///
/// It is pure: sets in, set difference out, no network and no storage. That is what makes
/// the interesting cases testable — first run, a device that vanishes and returns, several
/// linked at once — none of which a person is going to reproduce by hand against a live
/// relay.
///
/// ============================================================ THE FIRST-RUN COMPROMISE,
/// STATED RATHER THAN HIDDEN
///
/// On the very first fetch there is no previous set to compare against, so [newlyLinked]
/// reports nothing and the caller seeds the baseline. The alternative — treating every
/// existing session as new — would fire a security alert at every user on upgrade day and
/// teach them to dismiss it, which is worse than not warning at all.
///
/// The cost is real and worth naming: a device linked **before** this feature first runs is
/// adopted into the baseline silently. That is one window, once, and it is why
/// [SeedOutcome] distinguishes a seed from a quiet check — the caller is expected to show
/// the existing sessions after a seed rather than say nothing.
enum LinkedDeviceWatch {

    /// The identifiers of sessions present now and absent from [known].
    ///
    /// - Parameters:
    ///   - fetched: session ids the server currently reports.
    ///   - known: the ids remembered from the previous check, or `nil` on the very first
    ///     one. `nil` and `[]` mean different things and must not be conflated: `nil` is
    ///     "we have never looked", `[]` is "last time there were none", and only the second
    ///     one makes an appearing device newsworthy.
    /// - Returns: the new ids, in the order the server gave them, or `[]` when seeding.
    static func newlyLinked(fetched: [String], known: Set<String>?) -> [String] {
        guard let known else { return [] }
        return fetched.filter { !known.contains($0) }
    }

    /// What a check amounted to, so the caller can say the right thing.
    enum SeedOutcome: Equatable {
        /// First ever check. Nothing is claimed about these sessions; show them, do not alarm.
        case seeded(count: Int)
        /// Nothing changed.
        case unchanged
        /// These appeared since last time. This is the one that must reach the user.
        case linked([String])
    }

    static func classify(fetched: [String], known: Set<String>?) -> SeedOutcome {
        guard known != nil else { return .seeded(count: fetched.count) }
        let new = newlyLinked(fetched: fetched, known: known)
        return new.isEmpty ? .unchanged : .linked(new)
    }

    // MARK: - Persistence

    /// Deliberately `UserDefaults` and not the Keychain: these are session identifiers the
    /// server hands out, not secrets, and a baseline that survives reinstall would be a
    /// baseline that cannot be reset when a user deliberately starts over.
    private static let key = "oshi.linkedDevices.known"

    static func loadKnown(_ defaults: UserDefaults = .standard) -> Set<String>? {
        guard let ids = defaults.array(forKey: key) as? [String] else { return nil }
        return Set(ids)
    }

    static func saveKnown(_ ids: [String], _ defaults: UserDefaults = .standard) {
        // Sorted so the stored value is stable for a given set — an array that reshuffles
        // on every write makes any future diffing of the defaults plist unreadable.
        defaults.set(ids.sorted(), forKey: key)
    }

    /// Forget the baseline. Used when the account is reset, so the next check seeds afresh
    /// rather than reporting every session of a previous identity as newly linked.
    static func reset(_ defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: key)
    }

    // MARK: - Per-device mailboxes (__PER_DEVICE_MAILBOX_2026_09_23__)
    //
    // The same rule for the account's registered DEVICES (`GET /v2/devices`, spec §3.7): a
    // separate baseline, the same first-run seeding. Devices this device approved itself are
    // added to the baseline at approval time, so only a device nobody here approved alerts.
    static let mailboxKey = "oshi.linkedDevices.mailbox.known"

    static func loadKnownMailbox(_ defaults: UserDefaults = .standard) -> Set<String>? {
        guard let ids = defaults.array(forKey: mailboxKey) as? [String] else { return nil }
        return Set(ids)
    }

    static func saveKnownMailbox(_ ids: [String], _ defaults: UserDefaults = .standard) {
        defaults.set(Array(Set(ids)).sorted(), forKey: mailboxKey)
    }

    /// Remember a device before it shows up in the list (we approved it, or it is us).
    static func noteKnownMailbox(_ id: String, _ defaults: UserDefaults = .standard) {
        // No baseline yet: the first check seeds silently anyway — do not create one here,
        // or that check would report every other device as new.
        guard var known = loadKnownMailbox(defaults) else { return }
        known.insert(id)
        saveKnownMailbox(Array(known), defaults)
    }
}
