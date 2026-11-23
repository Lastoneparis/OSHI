//
//  KeyChangeWatch.swift
//  OSHI
//
//  Notices when the identity key behind a contact changes, so that it cannot be silent.
//

import Foundation
import CryptoKit

/// What turns "this contact's key changed" from something you could have found out into
/// something you are told.
///
/// ============================================================ THE ATTACK THIS ANSWERS
///
/// A key change has exactly two explanations and they are indistinguishable from inside the
/// app: either the contact reinstalled and holds a new key, or somebody is standing between
/// you and them holding a key of their own. The server cannot tell you which. The crypto
/// cannot tell you which — a machine-in-the-middle session is a *correctly* encrypted
/// session, to the wrong party. **Only the two humans can tell them apart, by comparing a
/// safety number out of band.** So the one thing the app owes them is the news that there is
/// something to compare.
///
/// OSHI already lets a user compare a safety number by hand (`SafetyNumber.generate`), and
/// Android already has the comparison logic. Neither detects anything, because both run only
/// when somebody opens the safety-number screen. **That is the same structural hole as an
/// unnoticed linked device** (see `LinkedDeviceWatch`): the defence exists, is correct, and is
/// never reached, because the attack's whole premise is that the user has no reason to go
/// looking. A check that fires only when you suspect something is not a check.
///
/// This type is therefore driven from the message path, not from a screen: every message that
/// decrypts reports its sender's key here, whether or not anybody is looking.
///
/// ============================================================ WHAT AN "ADDRESS" IS HERE,
/// AND WHY THE DISTINCTION IS THE WHOLE FEATURE
///
/// In OSHI a contact's full identity key *is* their routing identity, so a wholesale key
/// change also changes the address and shows up as a different contact — visible, and not
/// this type's problem. The invisible case is the one where the **address the user recognises
/// stays the same while the key behind it does not**: the app threads conversations and
/// renders senders by a truncated key (`MessageManager` builds `senderAddress` as
/// `String(senderPublicKey.prefix(10))`, other paths use 8), so two different 44-character
/// keys sharing a short prefix are one indistinguishable contact on screen. Sixty bits is a
/// grindable target, and the result is a conversation the user believes is with someone they
/// already verified.
///
/// [classify] therefore takes the address and the key *separately*, and never derives one
/// from the other. [address(forKey:)] offers the truncation the message pipeline uses, so the
/// alarm is scoped to exactly the case the user cannot see; a caller with a genuinely stable
/// contact identifier should pass that instead and gets a stricter watch for free.
///
/// ============================================================ WHY IT IS ITS OWN TYPE
///
/// It is pure: a dictionary in, an outcome out, no network, no UIKit and no clock. That is
/// what makes the interesting cases testable — the first message ever from someone, a key
/// that changes between two messages, the same key arriving spelled differently — none of
/// which anybody is going to reproduce by hand against a live relay and a second device.
///
/// ============================================================ THE FIRST-SIGHT COMPROMISE,
/// STATED RATHER THAN HIDDEN
///
/// The first time a contact is seen there is nothing to compare against, so [classify]
/// returns [Outcome.firstSight] and the caller records the baseline. The alternative —
/// treating every contact as changed on the first run — would fire a security alert for every
/// conversation a user has on upgrade day and teach them that this alert means nothing, which
/// leaves the app worse off than with no alert at all. The believability of the warning *is*
/// the feature.
///
/// The cost is real and worth naming: a contact whose key was already substituted before this
/// code first runs is adopted into the baseline silently. That is one window, once, and it is
/// why [Outcome.firstSight] is a distinct case rather than folded into `unchanged` — a caller
/// that wants to say "new contact, verify them" on first sight can.
enum KeyChangeWatch {

    // MARK: - Identity keys, normalised

    /// Fold the spellings of one key onto one string.
    ///
    /// This is not cosmetic, it is the difference between a warning that means something and
    /// one that does not. The same key reaches this app as base64 and as base64url, padded
    /// and unpadded — which is why `MessageManager.canonicalIdentity` exists at all.
    /// Fingerprinting the raw string would report a **key change** every time a peer's
    /// transport happened to spell the same key differently: a false alarm, on a warning whose
    /// only value is that the user believes it.
    static func canonicalKey(_ key: String) -> String {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        var s = trimmed
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while s.hasSuffix("=") { s.removeLast() }
        return s
    }

    /// A short, fixed-size stand-in for a key, or `nil` when there is no key to speak of.
    ///
    /// Stored instead of the key itself: the baseline is a durable list of who you talk to,
    /// so it holds digests rather than a ready-made contact directory. 16 bytes — finding a
    /// second key with the same fingerprint is a 2^128 problem, i.e. not the cheapest attack
    /// on this feature by a wide margin.
    ///
    /// `nil` for an empty or whitespace-only key is deliberate and load-bearing: a message
    /// that carries no sender key must never be able to *overwrite* a real baseline, because
    /// the next real message would then read as a change. See [classify].
    static func fingerprint(of key: String) -> String? {
        let canonical = canonicalKey(key)
        guard !canonical.isEmpty, let data = canonical.data(using: .utf8) else { return nil }
        return SHA256.hash(data: data).prefix(16).map { String(format: "%02x", $0) }.joined()
    }

    /// The handle the rest of the app threads a conversation by: the leading characters of
    /// the canonical key, matching `MessageManager`'s `senderAddress`.
    ///
    /// Deliberately the *short* form. Keying the baseline by the full key would make this
    /// whole type vacuous — a changed key would simply be an address never seen before, so
    /// every change would classify as [Outcome.firstSight] and nothing would ever be
    /// reported. A security check that cannot fail is the bug it is meant to catch.
    static func address(forKey key: String) -> String {
        String(canonicalKey(key).prefix(addressLength))
    }

    /// Matches `MessageManager.swift`'s `String(senderPublicKey.prefix(10))`.
    static let addressLength = 10

    // MARK: - The judgement

    enum Outcome: Equatable {
        /// Never seen this contact. Nothing is claimed; record the baseline, do not alarm.
        case firstSight
        /// Same key as last time, or nothing usable to compare. Say nothing.
        case unchanged
        /// The key behind this address is not the one recorded. This is the one that must
        /// reach the user. Values are fingerprints, not keys.
        case changed(previous: String, current: String)
    }

    /// - Parameters:
    ///   - address: the stable handle for the contact (see [address(forKey:)]).
    ///   - currentKey: the identity key just observed, in any spelling.
    ///   - known: the remembered baselines, or `nil` before anything was ever stored.
    ///     A missing entry and a stored fingerprint mean different things and must not be
    ///     conflated: no entry is "we have never seen this contact", a stored fingerprint is
    ///     "this is the key we saw", and only the second one makes a different key
    ///     newsworthy. `nil` for the whole map is simply the case where that is true of every
    ///     contact.
    static func classify(address: String, currentKey: String, known: [String: String]?) -> Outcome {
        // No usable key means no evidence, in either direction. Not a change — and, crucially,
        // the caller must not record it either, or the real key returns looking substituted.
        guard let current = fingerprint(of: currentKey) else { return .unchanged }
        guard let previous = known?[address] else { return .firstSight }
        return previous == current ? .unchanged : .changed(previous: previous, current: current)
    }

    /// A change, in the shape a view needs. Carries `currentKey` so that acknowledging the
    /// warning can adopt the new key as the baseline without going back to the message.
    struct Alert: Equatable, Identifiable {
        let address: String
        let previous: String
        let current: String
        let currentKey: String

        /// Stable per (contact, new key): re-raising the same alert must not stack up banners.
        var id: String { "\(address):\(current)" }
    }

    // MARK: - Persistence

    /// Deliberately `UserDefaults` and not the Keychain: these are digests of public keys, not
    /// secrets, and a baseline that survived reinstall would be a baseline the user cannot
    /// clear when they deliberately start over.
    private static let storageKey = "oshi.keyChange.known"

    /// `nil` when nothing was ever stored — never `[:]`, so that "never looked" survives the
    /// round-trip through defaults exactly as it exists in memory.
    static func loadKnown(_ defaults: UserDefaults = .standard) -> [String: String]? {
        defaults.dictionary(forKey: storageKey) as? [String: String]
    }

    static func saveKnown(_ known: [String: String], _ defaults: UserDefaults = .standard) {
        defaults.set(known, forKey: storageKey)
    }

    /// Record `currentKey` as the key for `address`. A key with no fingerprint is ignored
    /// rather than stored, for the reason given in [fingerprint(of:)].
    static func remember(address: String, currentKey: String, _ defaults: UserDefaults = .standard) {
        guard let fp = fingerprint(of: currentKey) else { return }
        var known = loadKnown(defaults) ?? [:]
        known[address] = fp
        saveKnown(known, defaults)
    }

    /// Forget one contact, so their next message is a first sight again. Used when the user
    /// accepts a change or re-verifies out of band.
    static func forget(address: String, _ defaults: UserDefaults = .standard) {
        guard var known = loadKnown(defaults) else { return }
        known.removeValue(forKey: address)
        saveKnown(known, defaults)
    }

    /// Forget every baseline. Used when the identity is reset, so the next message from each
    /// contact seeds afresh instead of reporting a previous identity's contacts as changed.
    static func reset(_ defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: storageKey)
    }
}
