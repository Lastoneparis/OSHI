//
//  RateAppTimingTests.swift
//  OSHITests
//
//  __RATE_TIMING_HARDENED_2026_08_19__
//
//  Proves the "when do we ask for a rating" policy against the SHIPPING
//  implementation via `@testable import OSHI`.
//
//  ──────────────────────────────────────────────────────────────────────────
//  WHAT IS REAL vs MODELLED vs NEEDS-DEVICE
//  ──────────────────────────────────────────────────────────────────────────
//  REAL app code exercised directly (not a copy):
//    • RateAppManager.decide(_:)               — the entire timing policy.
//    • RateAppManager.budgetAllows(_:…)        — quota + per-moment spacing.
//    • The three arming paths (welcome reply, first success, ten messages) on
//      the real singleton, with UserDefaults saved and restored.
//    • RateAppManager.Config                   — the shipping thresholds.
//  Both are pure functions of `RateSignals`, so every rule below is asserted on
//  the code that actually runs in the app, with no UI, no clock and no storage.
//
//  MODELLED: nothing. The tests build `RateSignals` values directly, which is
//  exactly what `RateAppManager.currentSignals()` hands to `decide` at runtime.
//
//  NEEDS-REAL-DEVICE (NOT asserted here):
//    • That `SKStoreReviewController` / `requestReview()` actually draws a
//      dialog. The API is silent when over Apple's 3/365-days ceiling — there is
//      no return value, no callback and no error. Nothing in a test (or in the
//      app) can observe it. What IS asserted here is that our own ledger never
//      lets us ATTEMPT more than `maxNativeRequestsPerYear` in a trailing year,
//      so we are always at or below Apple's ceiling.
//    • That UIKit actually presented the sheet. Covered in code by
//      `softPromptDidAppear()` (the only place the budget is spent) plus the
//      retract-watchdog; asserted at logic level here through `isUIBusy`.
//

import XCTest
@testable import OSHI

@MainActor
final class RateAppTimingTests: XCTestCase {

    // MARK: - Helpers

    private let now = Date(timeIntervalSince1970: 1_700_000_000)
    private func days(_ n: Double) -> TimeInterval { n * 86_400 }

    /// A user who has genuinely earned the ask: engaged, settled in, calm
    /// screen, no friction. Every test below mutates ONE field of this so the
    /// assertion names the rule it is testing and nothing else.
    private func happySignals() -> RateSignals {
        RateSignals(
            now: now,
            firstLaunchDate: now.addingTimeInterval(-days(30)),
            launchCount: 12,
            activeDays: 6,
            messagesDelivered: 25,
            completedCalls: 2,
            meshSuccesses: 1,
            pendingMilestone: true,
            currentVersion: "2.0",
            promptedVersion: nil,
            lastSoftPromptDate: nil,
            declinedDate: nil,
            nativeRequestDates: [],
            lastFrictionDate: nil,
            isCallActive: false,
            isUIBusy: false
        )
    }

    // MARK: - Baseline

    func testHappyPathPresents() {
        XCTAssertEqual(RateAppManager.decide(happySignals()), .present,
                       "A settled, engaged user on a calm screen must be asked")
    }

    // MARK: - Never a brand-new user

    func testFirstSessionIsNeverAsked() {
        var s = happySignals()
        s.launchCount = 1
        XCTAssertEqual(RateAppManager.decide(s), .block(.firstSession))
    }

    func testSecondSessionMayBeAsked() {
        // __EARLIER_ASK_2026_09_06__ minLaunchCount is now 2: the first session
        // is still never asked, the second may be once a milestone is armed.
        var s = happySignals()
        s.launchCount = 2
        XCTAssertNotEqual(RateAppManager.decide(s), .block(.firstSession))
    }

    // __WELCOME_REPLY_2026_09_07__
    func testAReplyToTheWelcomeMessageIsAskedOnDayOneFirstSession() {
        var s = happySignals()
        s.launchCount = 1
        s.firstLaunchDate = s.now.addingTimeInterval(-60)
        s.welcomeReply = true
        XCTAssertEqual(RateAppManager.decide(s), .present)
    }

    func testTheWelcomeReplyWaivesOnlyTheNewUserGates() {
        // The per-version gate is gone — see `testTheVersionGateIsRetired` — so
        // what the welcome reply must still not waive is friction and the budget.
        var s = happySignals()
        s.welcomeReply = true
        s.lastFrictionDate = s.now.addingTimeInterval(-10)
        XCTAssertEqual(RateAppManager.decide(s), .block(.recentFriction))
        s = happySignals(); s.welcomeReply = true
        s.lastFrictionDate = s.now.addingTimeInterval(-10)
        XCTAssertEqual(RateAppManager.decide(s), .block(.recentFriction))
        s = happySignals(); s.welcomeReply = true
        s.nativeRequestDates = [s.now.addingTimeInterval(-days(1)), s.now.addingTimeInterval(-days(2)), s.now.addingTimeInterval(-days(3))]
        XCTAssertNotEqual(RateAppManager.decide(s), .present)
    }

    func testFreshInstallIsNeverAsked() {
        var s = happySignals()
        s.firstLaunchDate = s.now.addingTimeInterval(-days(0.5))
        XCTAssertEqual(RateAppManager.decide(s), .block(.tooSoonAfterInstall))
    }

    /// A restored identity on a new device arrives with an EMPTY UserDefaults:
    /// no first-launch date, no counters. That must read as "brand new", never
    /// as "installed at the epoch" (which a `Date()`-defaulting read would do).
    func testMissingInstallDateIsTreatedAsBrandNew() {
        var s = happySignals()
        s.firstLaunchDate = nil
        XCTAssertEqual(RateAppManager.decide(s), .block(.tooSoonAfterInstall))
    }

    // MARK: - The three moments (__RATING_THREE_MOMENTS_2026_11_15__)

    /// Moment 2 (first receipt / first completed call) waives the install age,
    /// but not the first session: only the welcome reply may ask in session 1.
    func testMomentTwoWaivesInstallAgeButNotTheFirstSession() {
        var s = happySignals()
        s.firstRunMilestone = true
        s.firstLaunchDate = s.now.addingTimeInterval(-60)
        s.launchCount = 2
        XCTAssertEqual(RateAppManager.decide(s), .present)
        s.launchCount = 1
        XCTAssertEqual(RateAppManager.decide(s), .block(.firstSession))
    }

    /// The early moments need a week since the previous ask, moment 3 three.
    func testEarlyMomentsNeedOnlyAWeekAfterThePreviousAsk() {
        for early in [\RateSignals.welcomeReply, \RateSignals.firstRunMilestone] {
            var s = happySignals()
            s[keyPath: early] = true
            s.nativeRequestDates = [s.now.addingTimeInterval(-days(5))]
            XCTAssertEqual(RateAppManager.decide(s), .block(.nativeRequestBackoff))
            s.nativeRequestDates = [s.now.addingTimeInterval(-days(8))]
            XCTAssertEqual(RateAppManager.decide(s), .present)
        }
        var ordinary = happySignals()
        ordinary.nativeRequestDates = [ordinary.now.addingTimeInterval(-days(8))]
        XCTAssertEqual(RateAppManager.decide(ordinary), .block(.nativeRequestBackoff),
                       "moment 3 keeps the three-week spacing")
    }

    func testBudgetAllowsIsPerMomentAndCappedAtThreeAYear() {
        let none: [Date] = []
        for m in [RateMoment.welcomeReply, .firstSuccess, .messages] {
            XCTAssertTrue(RateAppManager.budgetAllows(m, nativeRequestDates: none, now: now))
        }
        let eightDaysAgo = [now.addingTimeInterval(-days(8))]
        XCTAssertTrue(RateAppManager.budgetAllows(.firstSuccess, nativeRequestDates: eightDaysAgo, now: now))
        XCTAssertFalse(RateAppManager.budgetAllows(.messages, nativeRequestDates: eightDaysAgo, now: now))

        // Three asks inside 365 days: a fourth is refused, whatever the moment.
        let three = [now.addingTimeInterval(-days(330)),
                     now.addingTimeInterval(-days(322)),
                     now.addingTimeInterval(-days(300))]
        for m in [RateMoment.welcomeReply, .firstSuccess, .messages] {
            XCTAssertFalse(RateAppManager.budgetAllows(m, nativeRequestDates: three, now: now),
                           "a 4th ask inside 365 days (\(m))")
        }
        // Day 366 after the first: that slot has rolled out.
        let later = now.addingTimeInterval(days(36))
        XCTAssertTrue(RateAppManager.budgetAllows(.messages, nativeRequestDates: three, now: later))
    }

    func testNoMilestoneNeverPresents() {
        var s = happySignals()
        s.pendingMilestone = false
        XCTAssertEqual(RateAppManager.decide(s), .block(.noMilestone))
    }

    // MARK: - Never after friction

    func testNeverAsksRightAfterAnError() {
        var s = happySignals()
        s.lastFrictionDate = s.now.addingTimeInterval(-30)   // 30 s ago
        XCTAssertEqual(RateAppManager.decide(s), .block(.recentFriction),
                       "A failed send / failed call / decryption error must silence the ask")
    }

    func testFrictionQuarantineExpires() {
        var s = happySignals()
        s.lastFrictionDate = s.now.addingTimeInterval(-(RateAppManager.Config.frictionQuietSeconds + 1))
        XCTAssertEqual(RateAppManager.decide(s), .present)
    }

    func testFrictionQuarantineIsAtLeastFiveMinutes() {
        XCTAssertGreaterThanOrEqual(RateAppManager.Config.frictionQuietSeconds, 300,
                                    "A 'quiet after an error' window shorter than 5 min is decoration")
    }

    func testFrictionBlocksEvenAtTheVeryEdgeOfTheWindow() {
        var s = happySignals()
        s.lastFrictionDate = s.now.addingTimeInterval(-(RateAppManager.Config.frictionQuietSeconds - 1))
        XCTAssertEqual(RateAppManager.decide(s), .block(.recentFriction))
    }

    // MARK: - Never on top of something else

    func testNeverAsksDuringACall() {
        var s = happySignals()
        s.isCallActive = true
        XCTAssertEqual(RateAppManager.decide(s), .block(.inCall))
    }

    /// The share-sheet race: a SwiftUI root `.sheet` requested while anything is
    /// already presented is silently refused by UIKit. If we decided "present"
    /// here we would spend the cooldown on a prompt nobody ever saw.
    func testNeverAsksWhileAnythingIsPresented() {
        var s = happySignals()
        s.isUIBusy = true
        XCTAssertEqual(RateAppManager.decide(s), .block(.uiBusy),
                       "Share sheet / cover / alert on screen ⇒ do not even try")
    }

    // MARK: - Back-offs

    /// The 120-day cooldown went with the pre-ask it was spacing.
    ///
    /// `lastSoftPromptDate` was the date the "Enjoying OSHI?" sheet last reached
    /// the screen. There is no such sheet, so the only spacing left is the one
    /// that describes what Apple actually honours: `daysAfterNativeRequest`,
    /// pinned at 21 days by `testSpacingIsThreeWeeks`.
    ///
    /// Two cooldowns stacked on a 3-per-year ceiling was not caution, it was the
    /// reason the app asked almost nobody: 120 days apart AND once per app
    /// version AND 180 days after any "not really".
    func testTheSoftPromptCooldownIsRetiredWithTheSheetItSpaced() {
        XCTAssertEqual(RateAppManager.Config.daysBetweenSoftPrompts, 0)
        var s = happySignals()
        s.lastSoftPromptDate = s.now.addingTimeInterval(-days(1))
        XCTAssertEqual(RateAppManager.decide(s), .present,
                       "a recent stamp from the retired sheet must not block anything")
    }

    /// The decline back-off went with the "Not really" button.
    ///
    /// Nothing writes `declinedDate` any more: there is no button to decline
    /// with. An install that still carries a stamp from the retired sheet must
    /// not be held silent for six months over a question asked by a screen that
    /// no longer exists, so the back-off is zero and the stamp is inert.
    func testTheDeclineBackOffIsRetiredWithTheButtonThatSetIt() {
        XCTAssertEqual(RateAppManager.Config.daysAfterDecline, 0)
        var s = happySignals()
        s.declinedDate = s.now.addingTimeInterval(-days(1))
        XCTAssertEqual(RateAppManager.decide(s), .present,
                       "an old decline stamp must no longer block anything")
    }

    /// The per-app-version gate is retired.
    ///
    /// It read `promptedVersion == currentVersion` and blocked. On a monthly
    /// release train that is a budget of about one ask per release — a different
    /// and much smaller thing than Apple's three per 365 days — and it made the
    /// spacing rule below irrelevant. `promptedVersion` is still written, for
    /// support dumps.
    func testTheVersionGateIsRetired() {
        var s = happySignals()
        s.promptedVersion = s.currentVersion
        XCTAssertEqual(RateAppManager.decide(s), .present,
                       "having asked on this version is no longer a refusal")
    }

    /// The spacing between asks is the portfolio-wide 21 days, not 120.
    func testSpacingIsThreeWeeks() {
        var s = happySignals()
        s.nativeRequestDates = [s.now.addingTimeInterval(-days(20))]
        XCTAssertEqual(RateAppManager.decide(s), .block(.nativeRequestBackoff))

        s.nativeRequestDates = [s.now.addingTimeInterval(-days(22))]
        XCTAssertEqual(RateAppManager.decide(s), .present)
    }

    // MARK: - Apple's 3-per-365-days ceiling

    /// The OS silently does nothing when over quota, so "we called it" can never
    /// be read as "the user saw it". We keep our own ledger of ATTEMPTS and stop
    /// at Apple's number, which guarantees we are never above it.
    func testOwnCeilingIsNotAboveApples() {
        XCTAssertLessThanOrEqual(RateAppManager.Config.maxNativeRequestsPerYear, 3,
                                 "Apple shows the dialog at most 3 times per 365 days")
    }

    func testRecentNativeRequestBacksOff() {
        var s = happySignals()
        s.nativeRequestDates = [s.now.addingTimeInterval(-days(10))]
        XCTAssertEqual(RateAppManager.decide(s), .block(.nativeRequestBackoff))
    }

    func testThreeNativeRequestsInAYearExhaustTheQuota() {
        var s = happySignals()
        // Three attempts spread across the year, the most recent one already
        // outside the per-request back-off — only the yearly ledger is left.
        let spacing = RateAppManager.Config.daysAfterNativeRequest
        s.nativeRequestDates = [
            s.now.addingTimeInterval(-days(spacing * 3 + 1)),
            s.now.addingTimeInterval(-days(spacing * 2 + 1)),
            s.now.addingTimeInterval(-days(spacing * 1 + 1))
        ]
        XCTAssertEqual(RateAppManager.decide(s), .block(.nativeQuotaExhausted),
                       "Never ATTEMPT a 4th native prompt inside 365 days")
    }

    func testAttemptsOlderThanAYearDoNotCountAgainstTheQuota() {
        var s = happySignals()
        s.nativeRequestDates = [
            s.now.addingTimeInterval(-days(400)),
            s.now.addingTimeInterval(-days(500)),
            s.now.addingTimeInterval(-days(600))
        ]
        XCTAssertEqual(RateAppManager.decide(s), .present)
    }

    /// What keeps the app inside Apple's three-per-year is the CEILING, not the
    /// spacing.
    ///
    /// This used to assert that the spacing alone was long enough that a fourth
    /// attempt could not fit in a year — true at 120 days, and the reason the
    /// spacing was 120 days. At 21 days a fourth attempt fits comfortably by the
    /// calendar, and is refused by `maxNativeRequestsPerYear` instead. So the
    /// property worth pinning is that the rolling window actually counts.
    func testTheYearlyCeilingIsWhatStopsAFourthAttempt() {
        var s = happySignals()
        // Three attempts, all well past the 21-day spacing, all inside the year.
        s.nativeRequestDates = [
            s.now.addingTimeInterval(-days(300)),
            s.now.addingTimeInterval(-days(200)),
            s.now.addingTimeInterval(-days(100))
        ]
        XCTAssertEqual(RateAppManager.decide(s), .block(.nativeQuotaExhausted),
                       "the spacing is satisfied; the ceiling has to be what refuses this")
    }

    // MARK: - Persistence across relaunch

    /// The counters live in `UserDefaults` under the `RateApp_*` namespace, so
    /// they survive relaunch. This asserts the round-trip on the real keys the
    /// manager reads, then restores whatever was there before.
    func testCountersRoundTripThroughUserDefaults() {
        let d = UserDefaults.standard
        let keys = ["RateApp_FirstLaunchDate", "RateApp_LaunchCount",
                    "RateApp_MessagesDelivered", "RateApp_CompletedCalls",
                    "RateApp_MeshSuccesses", "RateApp_ActiveDays",
                    "RateApp_PendingMilestone", "RateApp_LastFrictionDate",
                    "RateApp_NativeRequestDates", "RateApp_LastSoftPromptDate",
                    "RateApp_PromptedVersion", "RateApp_DeclinedDate"]
        let saved = keys.map { ($0, d.object(forKey: $0)) }
        // The screen is stubbed clear: `liveIsBusy()` walks the host app's real
        // window chain, which in a full suite run belongs to whatever ran before.
        let savedProbe = RatePresentationProbe.provider
        RatePresentationProbe.provider = { false }
        defer {
            RatePresentationProbe.provider = savedProbe
            for (k, v) in saved {
                if let v { d.set(v, forKey: k) } else { d.removeObject(forKey: k) }
            }
        }

        let installed = Date().addingTimeInterval(-days(40))
        let friction  = Date().addingTimeInterval(-120)
        d.set(installed, forKey: "RateApp_FirstLaunchDate")
        d.set(9, forKey: "RateApp_LaunchCount")
        d.set(41, forKey: "RateApp_MessagesDelivered")
        d.set(3, forKey: "RateApp_CompletedCalls")
        d.set(2, forKey: "RateApp_MeshSuccesses")
        d.set(["2026-08-17", "2026-08-18", "2026-08-19"], forKey: "RateApp_ActiveDays")
        d.set(true, forKey: "RateApp_PendingMilestone")
        d.set(friction, forKey: "RateApp_LastFrictionDate")
        d.removeObject(forKey: "RateApp_NativeRequestDates")
        d.removeObject(forKey: "RateApp_LastSoftPromptDate")
        d.removeObject(forKey: "RateApp_PromptedVersion")
        d.removeObject(forKey: "RateApp_DeclinedDate")

        // `storedSignals()` (not `currentSignals()`) on purpose: this asserts the
        // PERSISTED half, and it must not drag the UIKit scene graph or
        // VoiceCallManager into a unit test.
        let s = RateAppManager.shared.storedSignals()
        XCTAssertEqual(s.launchCount, 9)
        XCTAssertEqual(s.messagesDelivered, 41)
        XCTAssertEqual(s.completedCalls, 3)
        XCTAssertEqual(s.meshSuccesses, 2)
        XCTAssertEqual(s.activeDays, 3)
        XCTAssertTrue(s.pendingMilestone)
        XCTAssertEqual(s.firstLaunchDate?.timeIntervalSince1970 ?? 0,
                       installed.timeIntervalSince1970, accuracy: 1)
        XCTAssertEqual(s.lastFrictionDate?.timeIntervalSince1970 ?? 0,
                       friction.timeIntervalSince1970, accuracy: 1)

        // And the friction stamp that was just read back must block the ask.
        // `storedSignals()` reports isUIBusy/isCallActive as false, so this is
        // purely the persisted-state decision.
        XCTAssertFalse(s.isUIBusy)
        XCTAssertFalse(s.isCallActive)
        XCTAssertEqual(RateAppManager.decide(s), .block(.recentFriction))
    }

    // MARK: - The five-star ask is ARMED, not fired under the sheet

    // ──────────────────────────────────────────────────────────────────────
    // ARM ≠ SPEND, the defect this file has now watched being introduced twice
    // ──────────────────────────────────────────────────────────────────────
    //
    // 2026-08-20: `RateAppPromptView`'s "Love it!" button called
    // `requestReview()` and then `dismiss()`, i.e. it asked StoreKit while the
    // pre-ask sheet was still the presented controller. iOS DISCARDS such a
    // request — no error, no queueing, no log — so the one button whose entire
    // purpose was to collect five stars did nothing, while the year's ledger was
    // debited all the same.
    //
    // 2026-08-22: the pre-ask was removed (good — it was a sentiment-routing
    // custom review prompt) but `present(source:)` kept writing the ledger entry
    // itself, and the modifier it then called ended in
    //
    //     guard !RatePresentationProbe.isBusy() else { return }
    //
    // so a busy screen consumed the token, debited the slot and started the
    // back-off without asking anything. Same defect, different line.
    //
    // The dialog itself is unobservable (see the header). What IS observable, and
    // what these assert, is the contract: a milestone ARMS one request and writes
    // nothing to the ledger; `deliverArmedRequest` is the only thing that spends
    // a slot, and only when it actually calls through.

    /// A helper that runs a block with the review state saved and restored, so a
    /// test run can never spend the real yearly budget.
    private func withCleanReviewState(_ body: (RateAppManager, UserDefaults) -> Void) {
        let d = UserDefaults.standard
        let keys = ["RateApp_NativeRequestDates", "RateApp_DeclinedDate",
                    "RateApp_PendingMilestone", "RateApp_ArmedAt",
                    "RateApp_LastFrictionDate", "RateApp_LastSoftPromptDate",
                    "RateApp_PromptedVersion", "RateApp_FirstLaunchDate",
                    "RateApp_LaunchCount", "RateApp_MessagesDelivered",
                    "RateApp_ActiveDays", "RateApp_MessagesSent",
                    "RateApp_CompletedCalls", "RateApp_ArmedMoment",
                    "RateApp_MilestoneArmedAt", "RateApp_AskedOnWelcomeReply",
                    "RateApp_AskedOnFirstDelivery"]
        let saved = keys.map { ($0, d.object(forKey: $0)) }
        // The screen is stubbed clear: `liveIsBusy()` walks the host app's real
        // window chain, which in a full suite run belongs to whatever ran before.
        let savedProbe = RatePresentationProbe.provider
        RatePresentationProbe.provider = { false }
        defer {
            RatePresentationProbe.provider = savedProbe
            // 🔴 CLEAR THE TOKEN FIRST, THEN RESTORE. The armed request lives on
            // the singleton as well as on disk, so restoring the keys and walking
            // away leaves `pendingNativeRequest` true in memory and hands the
            // next test in the suite a token it never armed. That is how two of
            // these passed on their own and failed in the full run.
            RateAppManager.shared.clearArmedRequestForTesting()
            for (k, v) in saved {
                if let v { d.set(v, forKey: k) } else { d.removeObject(forKey: k) }
            }
        }
        RateAppManager.shared.clearArmedRequestForTesting()
        keys.forEach { d.removeObject(forKey: $0) }
        body(RateAppManager.shared, d)
    }

    /// Enough demonstrated engagement that `decide` has no reason to refuse.
    private func plantEngagement(_ d: UserDefaults) {
        d.set(Date().addingTimeInterval(-10 * 86_400), forKey: "RateApp_FirstLaunchDate")
        d.set(9, forKey: "RateApp_LaunchCount")
        d.set(true, forKey: "RateApp_PendingMilestone")
        d.set(20, forKey: "RateApp_MessagesDelivered")
        d.set(["2026-10-01", "2026-10-02", "2026-10-03"], forKey: "RateApp_ActiveDays")
    }

    /// Arming must write a token and NOTHING to the yearly ledger.
    func testArmingSpendsNoBudget() {
        withCleanReviewState { m, d in
            plantEngagement(d)
            m.forceArmNativeRequest()

            XCTAssertTrue(m.hasArmedRequest, "the milestone must leave a token armed")
            XCTAssertNil(d.array(forKey: "RateApp_NativeRequestDates"),
                         "arming must not debit the yearly ledger")
        }
    }

    /// Delivery on a calm screen asks once, debits exactly one slot, and clears
    /// both the token and the armed milestone.
    func testDeliveryOnACalmScreenAsksOnceAndSpendsOneSlot() {
        withCleanReviewState { m, d in
            plantEngagement(d)
            m.forceArmNativeRequest()

            var asked = 0
            XCTAssertTrue(m.deliverArmedRequest(request: { asked += 1 }))
            XCTAssertEqual(asked, 1)
            XCTAssertEqual((d.array(forKey: "RateApp_NativeRequestDates") as? [Date])?.count, 1,
                           "the attempt is written to the yearly ledger exactly once")
            XCTAssertFalse(m.hasArmedRequest, "the token is consumed")
            XCTAssertFalse(d.bool(forKey: "RateApp_PendingMilestone"),
                           "the armed milestone must be cleared — stampPromptShown had no caller at all")

            XCTAssertFalse(m.deliverArmedRequest(request: { asked += 1 }),
                           "and it cannot fire twice")
            XCTAssertEqual(asked, 1)
        }
    }

    /// THE regression. A refusal at delivery time must change nothing: no slot
    /// spent, no back-off started, and the request still armed for later.
    func testARefusedDeliverySpendsNothingAndStaysArmed() {
        withCleanReviewState { m, d in
            plantEngagement(d)
            // A friction stamp is the one refusal a test can plant without a UI
            // stack: `decide` blocks on it for ten minutes.
            d.set(Date(), forKey: "RateApp_LastFrictionDate")
            m.forceArmNativeRequest()

            var asked = 0
            XCTAssertFalse(m.deliverArmedRequest(request: { asked += 1 }))
            XCTAssertEqual(asked, 0)
            XCTAssertNil(d.array(forKey: "RateApp_NativeRequestDates"),
                         "a request that was never made must not spend a slot")
            XCTAssertTrue(m.hasArmedRequest, "and must stay armed for a calmer moment")

            // The quiet period passes; the same token now goes out.
            d.set(Date().addingTimeInterval(-RateAppManager.Config.frictionQuietSeconds - 60),
                  forKey: "RateApp_LastFrictionDate")
            XCTAssertTrue(m.deliverArmedRequest(request: { asked += 1 }))
            XCTAssertEqual(asked, 1)
        }
    }

    /// An armed request older than a day is dropped rather than fired at a screen
    /// that has nothing to do with the success that earned it.
    func testAnArmedRequestExpiresAfterADay() {
        withCleanReviewState { m, d in
            plantEngagement(d)
            m.forceArmNativeRequest()

            var asked = 0
            let tomorrow = Date().addingTimeInterval(RateAppManager.Config.armedRequestLifetime + 60)
            XCTAssertFalse(m.deliverArmedRequest(now: tomorrow, request: { asked += 1 }))
            XCTAssertEqual(asked, 0)
            XCTAssertFalse(m.hasArmedRequest)
            XCTAssertNil(d.array(forKey: "RateApp_NativeRequestDates"))
        }
    }

    // MARK: - The three moments, end to end on the real singleton

    private let friend = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    /// Moment 1 on a brand-new install, first session: armed, then delivered.
    func testWelcomeReplyAsksInTheFirstSession() {
        withCleanReviewState { m, d in
            d.set(Date().addingTimeInterval(-120), forKey: "RateApp_FirstLaunchDate")
            d.set(1, forKey: "RateApp_LaunchCount")

            m.recordWelcomeReply()
            XCTAssertTrue(d.bool(forKey: "RateApp_PendingMilestone"))
            XCTAssertEqual(d.string(forKey: "RateApp_ArmedMoment"), "welcomeReply")

            m.evaluatePendingMilestone(source: "test")
            var asked = 0
            XCTAssertTrue(m.deliverArmedRequest(request: { asked += 1 }),
                          "the welcome reply must not be blocked as a first session")
            XCTAssertEqual(asked, 1)

            // Once per install.
            m.recordWelcomeReply()
            XCTAssertFalse(d.bool(forKey: "RateApp_PendingMilestone"))
        }
    }

    /// Ten messages arm moment 3; nine do not.
    func testTenMessagesArmMomentThree() {
        withCleanReviewState { m, d in
            plantEngagement(d)
            d.set(false, forKey: "RateApp_PendingMilestone")
            for _ in 0..<(RateAppManager.Config.minMessagesSentForAsk - 1) { m.recordMessageSent() }
            XCTAssertFalse(d.bool(forKey: "RateApp_PendingMilestone"))
            m.recordMessageSent()
            XCTAssertTrue(d.bool(forKey: "RateApp_PendingMilestone"))
            XCTAssertEqual(d.string(forKey: "RateApp_ArmedMoment"), "messages")
        }
    }

    /// Moment 2 is owed: a receipt inside the week after the welcome ask does not
    /// arm and is not lost; the next receipt after the week does.
    func testFirstSuccessIsOwedUntilItCanBeAsked() {
        withCleanReviewState { m, d in
            plantEngagement(d)
            d.set(false, forKey: "RateApp_PendingMilestone")
            d.set([Date().addingTimeInterval(-days(2))], forKey: "RateApp_NativeRequestDates")

            m.recordMessageDelivered(from: friend)
            XCTAssertFalse(d.bool(forKey: "RateApp_PendingMilestone"))
            XCTAssertFalse(d.bool(forKey: "RateApp_AskedOnFirstDelivery"), "still owed")

            d.set([Date().addingTimeInterval(-days(8))], forKey: "RateApp_NativeRequestDates")
            m.recordMessageDelivered(from: friend)
            XCTAssertEqual(d.string(forKey: "RateApp_ArmedMoment"), "firstSuccess")

            m.evaluatePendingMilestone(source: "test")
            var asked = 0
            XCTAssertTrue(m.deliverArmedRequest(request: { asked += 1 }))
            XCTAssertTrue(d.bool(forKey: "RateApp_AskedOnFirstDelivery"))
        }
    }

    /// A receipt from the welcome author is moment 1's thread, not moment 2.
    func testAReceiptFromTheWelcomeAuthorIsNotMomentTwo() {
        withCleanReviewState { m, d in
            plantEngagement(d)
            d.set(false, forKey: "RateApp_PendingMilestone")
            m.recordMessageDelivered(from: WelcomeMessageManager.authorPublicKey)
            XCTAssertFalse(d.bool(forKey: "RateApp_PendingMilestone"))
        }
    }

    /// A completed call is moment 2 as well.
    func testACompletedCallArmsMomentTwo() {
        withCleanReviewState { m, d in
            plantEngagement(d)
            d.set(false, forKey: "RateApp_PendingMilestone")
            m.recordCallCompleted(durationSeconds: RateAppManager.Config.minCallSeconds + 1,
                                  endedNormally: true)
            XCTAssertEqual(d.string(forKey: "RateApp_ArmedMoment"), "firstSuccess")
        }
    }

    /// Three asks already in the trailing year: nothing arms, whatever the moment.
    func testAFourthAskInsideAYearIsRefused() {
        withCleanReviewState { m, d in
            plantEngagement(d)
            d.set(false, forKey: "RateApp_PendingMilestone")
            d.set([Date().addingTimeInterval(-days(100)),
                   Date().addingTimeInterval(-days(70)),
                   Date().addingTimeInterval(-days(40))], forKey: "RateApp_NativeRequestDates")
            m.recordWelcomeReply()
            m.recordMessageDelivered(from: friend)
            for _ in 0..<20 { m.recordMessageSent() }
            XCTAssertFalse(d.bool(forKey: "RateApp_PendingMilestone"))
            var asked = 0
            XCTAssertFalse(m.deliverArmedRequest(request: { asked += 1 }))
            XCTAssertEqual(asked, 0)
        }
    }

    /// The budget is Apple's own shape, and the same figure as every other app in
    /// the portfolio: three weeks apart, three per rolling year.
    func testTheBudgetMatchesTheRestOfThePortfolio() {
        XCTAssertEqual(RateAppManager.Config.maxNativeRequestsPerYear, 3)
        XCTAssertEqual(RateAppManager.Config.daysAfterNativeRequest, 21,
                       "120 days inside a 3-per-year ceiling is why the app asked almost nobody")
    }

    /// Nothing in the shipped sources may reinstate the pre-ask.
    ///
    /// Guideline 1.1.7 disallows custom review prompts, and a rating collected
    /// only from the people who said they were happy is not a rating.
    func testNoSentimentPrePromptSurvivesInTheSources() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()      // OSHITests
            .deletingLastPathComponent()      // repository root
            .appendingPathComponent("OSHI")
        let manager = try String(
            contentsOf: root.appendingPathComponent("RateAppManager.swift"), encoding: .utf8)
        // Strip comments: the file names the retired pre-ask on purpose, to record
        // why it is gone.
        let code = manager
            .split(separator: "\n", omittingEmptySubsequences: false)
            .map { line -> String in
                guard let slashes = line.range(of: "//") else { return String(line) }
                return String(line[line.startIndex..<slashes.lowerBound])
            }
            .joined(separator: "\n")

        for banned in ["RateAppPromptView", "isShowingSoftPrompt", "userLovesApp", "userDeclined"] {
            XCTAssertFalse(code.contains(banned), "\(banned) is the retired sentiment pre-prompt")
        }
        XCTAssertTrue(code.contains("requestReview()"), "the prompt must be Apple's own")
    }

    // MARK: - Explicit "Rate OSHI" button (native first, write-review once spent)

    func testRateButtonIsNativeUnderThreeRequestsInTheYear() {
        let now = Date()
        XCTAssertEqual(RateAppManager.rateButtonAction(nativeRequestDates: [], now: now), .native)
        let two = [now.addingTimeInterval(-days(1)), now.addingTimeInterval(-days(40))]
        XCTAssertEqual(RateAppManager.rateButtonAction(nativeRequestDates: two, now: now), .native)
        let three = two + [now.addingTimeInterval(-days(300))]
        XCTAssertEqual(RateAppManager.rateButtonAction(nativeRequestDates: three, now: now), .storePage)
        // A request older than 365 days no longer counts.
        let oneExpired = two + [now.addingTimeInterval(-days(366))]
        XCTAssertEqual(RateAppManager.rateButtonAction(nativeRequestDates: oneExpired, now: now), .native)
    }

    #if DEBUG
    func testRateButtonWritesTheSharedLedgerAndFallsBackOnceSpent() {
        let manager = RateAppManager.shared
        manager.resetForTesting()
        defer { manager.resetForTesting() }
        let now = Date()
        var native = 0, store = 0
        for i in 0..<3 {
            XCTAssertEqual(manager.handleRateButton(now: now.addingTimeInterval(Double(i)),
                                                    request: { native += 1 }, openStorePage: { store += 1 }), .native)
        }
        XCTAssertEqual(manager.storedSignals(now: now).nativeRequestDates.count, 3,
                       "button requests share the automatic ask's ledger")
        // The automatic ask sees them too: the year is spent.
        XCTAssertFalse(RateAppManager.budgetAllows(.welcomeReply,
                                                   nativeRequestDates: manager.storedSignals(now: now).nativeRequestDates,
                                                   now: now.addingTimeInterval(days(30))))
        XCTAssertEqual(manager.handleRateButton(now: now.addingTimeInterval(10),
                                                request: { native += 1 }, openStorePage: { store += 1 }), .storePage)
        XCTAssertEqual(native, 3)
        XCTAssertEqual(store, 1)
    }
    #endif
}
