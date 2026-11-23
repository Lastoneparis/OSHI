import XCTest
@testable import OSHI

/// __PANIC_WIPE_2026_11_03__ The invariants of the panic wipe and its gesture.
///
/// Every assertion here is one that, if broken, produces a panic wipe that
/// LOOKS like it worked — the app returns to the first-run screen either way.
/// There is no user-visible difference between a wipe that erased the identity
/// and one that left the per-identity archives behind, until the app reopens.
///
/// Android mirror: `OSHI-Android/app/src/test/java/com/oshi/messenger/PanicWipePolicyTest.kt`.
final class PanicWipePolicyTests: XCTestCase {

    // MARK: - order

    func testRadiosAreStoppedBeforeAnythingIsDestroyed() {
        XCTAssertEqual(
            PanicWipePolicy.order.first, .stopRadios,
            "a wipe that destroys state under a live mesh session leaves this phone transmitting under keys that no longer exist"
        )
    }

    func testNotificationsAreClearedBeforeTheDatabase() {
        let notifications = PanicWipePolicy.order.firstIndex(of: .cancelNotifications)
        let database = PanicWipePolicy.order.firstIndex(of: .wipeDatabase)
        XCTAssertNotNil(notifications)
        XCTAssertNotNil(database)
        XCTAssertLessThan(notifications!, database!)
    }

    func testMediaGoesBeforeTheKeysThatProtectIt() {
        let media = PanicWipePolicy.order.firstIndex(of: .wipeMedia)!
        let keys = PanicWipePolicy.order.firstIndex(of: .wipeKeys)!
        XCTAssertLessThan(
            media, keys,
            "a decrypted file left on disk does not need our key to be recovered"
        )
    }

    func testPreferencesComeAfterKeysAndBeforeOnboarding() {
        let prefs = PanicWipePolicy.order.firstIndex(of: .wipePreferences)!
        XCTAssertGreaterThan(prefs, PanicWipePolicy.order.firstIndex(of: .wipeKeys)!)
        XCTAssertLessThan(prefs, PanicWipePolicy.order.firstIndex(of: .returnToOnboarding)!)
    }

    func testReturningToOnboardingIsLast() {
        XCTAssertEqual(PanicWipePolicy.order.last, .returnToOnboarding)
    }

    func testEveryStepIsInTheOrderExactlyOnce() {
        // A step declared and never run is a shape of bug this codebase has
        // shipped before: a catalog entry is not a feature.
        XCTAssertEqual(PanicWipePolicy.order.count, PanicWipePolicy.Step.allCases.count)
        XCTAssertEqual(Set(PanicWipePolicy.order), Set(PanicWipePolicy.Step.allCases))
    }

    // MARK: - network

    func testNoStepNeedsTheNetwork() {
        for step in PanicWipePolicy.Step.allCases {
            XCTAssertFalse(
                PanicWipePolicy.requiresNetwork(step),
                "\(step) would make the wipe defeatable by jamming the signal"
            )
        }
    }

    func testThePushUnregisterBudgetIsBoundedAndShort() {
        XCTAssertGreaterThan(PanicWipePolicy.pushUnregisterBudget, 0)
        XCTAssertLessThanOrEqual(
            PanicWipePolicy.pushUnregisterBudget, 1.0,
            "a panic wipe may not wait a noticeable time on a round trip"
        )
    }

    // MARK: - what must run

    func testTheFourDestructiveStepsMayNeverBeSkipped() {
        XCTAssertEqual(
            PanicWipePolicy.mustRun,
            [.wipeMedia, .wipeDatabase, .wipeKeys, .wipePreferences]
        )
    }

    func testAWipeMissingThePreferencesStepIsNotComplete() {
        // The step that writes the per-identity archives back to disk if it is
        // skipped, because disconnectWallet() has just created them.
        var completed = Set(PanicWipePolicy.order)
        completed.remove(.wipePreferences)
        XCTAssertFalse(PanicWipePolicy.isComplete(completed))
    }

    func testAWipeMissingOnlyANonDestructiveStepIsComplete() {
        var completed = Set(PanicWipePolicy.order)
        completed.remove(.cancelNotifications)
        XCTAssertTrue(PanicWipePolicy.isComplete(completed))
    }

    func testASecondTriggerWhileOneIsRunningIsSwallowed() {
        XCTAssertTrue(PanicWipePolicy.shouldStart(alreadyRunning: false))
        XCTAssertFalse(
            PanicWipePolicy.shouldStart(alreadyRunning: true),
            "two wipes over one store is a crash, not a wipe"
        )
    }

    // MARK: - gesture

    func testThreeTapsInsideTheWindowFire() {
        let t: TimeInterval = 10_000
        let taps = PanicGesture.retained([t, t + 0.2, t + 0.4], now: t + 0.4)
        XCTAssertTrue(PanicGesture.shouldTrigger(taps))
    }

    func testTwoTapsDoNotFire() {
        let t: TimeInterval = 10_000
        XCTAssertFalse(PanicGesture.shouldTrigger(PanicGesture.retained([t, t + 0.2], now: t + 0.2)))
    }

    func testThreeDeliberateTapsSpreadBeyondTheWindowDoNotFire() {
        // Someone tapping the wordmark once each time they open the screen must
        // never accumulate a wipe.
        let first: TimeInterval = 0
        let second: TimeInterval = 5
        let third: TimeInterval = 10
        let afterSecond = PanicGesture.retained([first, second], now: second)
        XCTAssertEqual(afterSecond, [second])
        let afterThird = PanicGesture.retained(afterSecond + [third], now: third)
        XCTAssertEqual(afterThird, [third])
        XCTAssertFalse(PanicGesture.shouldTrigger(afterThird))
    }

    func testATapExactlyAtTheWindowEdgeIsAlreadyStale() {
        let t: TimeInterval = 0
        XCTAssertEqual(PanicGesture.retained([t], now: t + PanicGesture.window), [])
    }

    func testTheGestureMatchesBitchatsThreeTaps() {
        XCTAssertEqual(PanicGesture.tapsRequired, 3)
    }
}

/// __PANIC_WIPE_MISSED_APP_GROUP_2026_11_20__ The App Group container (contact
/// and group snapshots for the extensions) and its defaults suite are emptied.
final class PanicWipeAppGroupTests: XCTestCase {
    func testAppGroupSnapshotsAndDefaultsAreWiped() throws {
        let fm = FileManager.default
        let root = fm.temporaryDirectory.appendingPathComponent("panic-appgroup-\(UUID().uuidString)")
        defer { try? fm.removeItem(at: root) }
        try fm.createDirectory(at: root.appendingPathComponent("share_avatars"), withIntermediateDirectories: true)
        try fm.createDirectory(at: root.appendingPathComponent("Library/Caches"), withIntermediateDirectories: true)
        try fm.createDirectory(at: root.appendingPathComponent("Library/Preferences"), withIntermediateDirectories: true)
        try Data("[{\"alias\":\"Maya\"}]".utf8).write(to: root.appendingPathComponent("contacts_snapshot.json"))
        try Data("[]".utf8).write(to: root.appendingPathComponent("groups_snapshot.json"))
        try Data("x".utf8).write(to: root.appendingPathComponent("share_avatars/a.jpg"))
        try Data("x".utf8).write(to: root.appendingPathComponent("Library/Caches/c"))

        let suite = "PanicWipeAppGroupTests"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.set(false, forKey: "notificationPreview")
        defaults.set("secret", forKey: "something")

        PanicWipe.wipeAppGroup(container: root, defaults: defaults)

        let left = try fm.contentsOfDirectory(atPath: root.path)
        XCTAssertEqual(left, ["Library"])
        XCTAssertEqual(try fm.contentsOfDirectory(atPath: root.appendingPathComponent("Library").path), ["Preferences"])
        XCTAssertNil(defaults.object(forKey: "something"))
        XCTAssertNil(defaults.object(forKey: "notificationPreview"))
        defaults.removePersistentDomain(forName: suite)
    }

    func testMissingContainerIsANoOp() {
        PanicWipe.wipeAppGroup(container: nil, defaults: nil)
    }
}

