//
//  WelcomeMessageTests.swift
//  OSHITests
//
//  The welcome conversation is seeded from `connectWallet`, which also runs when
//  someone RESTORES an identity from a seed phrase and when they switch between
//  identities. Two rules decide whether a greeting is correct or an annoyance,
//  and both are easy to get wrong in a way no one notices until users complain:
//
//    * a restore is not a new user — they already have their history;
//    * it happens ONCE per identity, never again on relaunch.
//
//  Both are pure UserDefaults bookkeeping, so they can be pinned directly.
//

import XCTest
@testable import OSHI

@MainActor
final class WelcomeMessageTests: XCTestCase {

    /// Unique per test so runs cannot contaminate each other — the seeded flag
    /// lives in UserDefaults and would otherwise persist across the suite.
    private func freshKey() -> String {
        "TESTKEY_\(UUID().uuidString)_padding_to_look_like_a_real_public_key="
    }

    private func clear(_ key: String) {
        UserDefaults.standard.removeObject(
            forKey: "oshi.welcome.seeded." + String(key.prefix(24)))
    }

    // MARK: The two rules

    /// A seed-phrase import is a RESTORE. Greeting them would drop a "welcome to
    /// OSHI" into a conversation list they have been using for months.
    func testRestoringAnIdentityIsNotGreeted() {
        let key = freshKey()
        defer { clear(key) }

        WelcomeMessageManager.shared.seedIfNeeded(for: key, isRestoredIdentity: true)

        XCTAssertFalse(WelcomeMessageManager.hasSeeded(for: key),
                       "a restored identity was marked as greeted")
    }

    /// And a genuinely new identity is.
    func testNewIdentityIsGreetedOnce() {
        let key = freshKey()
        defer { clear(key) }

        XCTAssertFalse(WelcomeMessageManager.hasSeeded(for: key))
        WelcomeMessageManager.shared.seedIfNeeded(for: key, isRestoredIdentity: false)
        XCTAssertTrue(WelcomeMessageManager.hasSeeded(for: key))
    }

    /// `connectWallet` is not the only thing that can run twice — relaunches,
    /// identity switches and retries all reach here. The flag must make every
    /// call after the first a no-op.
    func testSeedingIsIdempotent() {
        let key = freshKey()
        defer { clear(key) }

        for _ in 0..<5 {
            WelcomeMessageManager.shared.seedIfNeeded(for: key, isRestoredIdentity: false)
        }
        XCTAssertTrue(WelcomeMessageManager.hasSeeded(for: key))
    }

    /// A second identity on the same device is a different person as far as this
    /// is concerned, so the flag has to be per-key and not global.
    func testTwoIdentitiesAreTrackedSeparately() {
        let a = freshKey(), b = freshKey()
        defer { clear(a); clear(b) }

        WelcomeMessageManager.shared.seedIfNeeded(for: a, isRestoredIdentity: false)
        XCTAssertTrue(WelcomeMessageManager.hasSeeded(for: a))
        XCTAssertFalse(WelcomeMessageManager.hasSeeded(for: b),
                       "the flag is global, so a second identity would never be greeted")
    }

    /// Defensive: an empty key must not consume the flag namespace or seed.
    func testEmptyKeyIsIgnored() {
        WelcomeMessageManager.shared.seedIfNeeded(for: "", isRestoredIdentity: false)
        XCTAssertFalse(WelcomeMessageManager.hasSeeded(for: ""))
    }

    // MARK: Content

    /// The greeting and the card caption must actually resolve. A missing key
    /// renders as the raw identifier — "welcome.message.body" — which is the
    /// kind of thing that ships and sits in front of every new user.
    func testGreetingStringsResolveAndAreNotRawKeys() {
        for key in ["welcome.message.body", "welcome.message.card_caption"] {
            let value = key.localized
            XCTAssertFalse(value.isEmpty, "\(key) resolved to empty")
            XCTAssertNotEqual(value, key, "\(key) is missing — the raw key would be shown")
        }
    }

    /// The contact card points at a real, non-placeholder key, or the one action
    /// in the whole greeting goes nowhere.
    func testAuthorContactIsConfigured() {
        XCTAssertFalse(WelcomeMessageManager.authorPublicKey.isEmpty)
        XCTAssertGreaterThan(WelcomeMessageManager.authorPublicKey.count, 20,
                             "author key looks like a placeholder")
        XCTAssertFalse(WelcomeMessageManager.authorAlias.isEmpty)
    }
}
