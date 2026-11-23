//
//  RateAppManager.swift
//  OSHI
//
//  __RATE_OSHI_2026_07_05__ / __RATE_TIMING_HARDENED_2026_08_19__
//
//  HIG-compliant "Rate OSHI" flow. We ask at a genuinely positive,
//  non-intrusive moment — never on launch, never mid-conversation, never after
//  friction — using Apple's own review request and nothing else.
//
//  There is NO pre-ask. A sheet of ours that asked "Enjoying OSHI?" and sent
//  only the positive answers to Apple's dialog is a custom, sentiment-routing
//  review prompt, which guideline 1.1.7 does not allow — and a rating collected
//  only from people who said they were happy is not a rating. What replaced the
//  filtering is the moment itself, and the ask is held back after any friction.
//
//  ──────────────────────────────────────────────────────────────────────────
//  THE THREE MOMENTS (__RATING_THREE_MOMENTS_2026_11_15__)
//  ──────────────────────────────────────────────────────────────────────────
//  Apple's dialog is requested at exactly three moments, and nowhere else:
//
//   1. WELCOME REPLY — the user's first message in the welcome thread to the
//      developer. The early ask: it waives the session-count and install-age
//      gates, so it may fire on day 1, in the first session. Once per install.
//   2. FIRST SUCCESS — the first message that provably reached a real contact
//      (delivery receipt / mesh ACK, the welcome author excluded) or the first
//      completed call, whichever comes first. Waives the install-age gate only,
//      needs `daysAfterEarlyAsk` since a previous ask, and stays OWED until it is
//      actually delivered.
//   3. TEN MESSAGES SENT — `minMessagesSentForAsk`, at least
//      `daysAfterNativeRequest` (21 days) after the previous ask, every new-user
//      gate applying.
//
//  All three share one ledger: at most 3 attempts per rolling 365 days (Apple's
//  own cap). Every moment still waits for a calm screen (no call, nothing
//  presented, no keyboard up) and for the friction quiet period to pass. The
//  older "engagement milestone" arming (5 delivered + 2 active days, any call,
//  any mesh success) and the "first contact added" moment are retired: they were
//  extra moments beyond these three.
//
//  ──────────────────────────────────────────────────────────────────────────
//  WHY THE DECISION IS A PURE FUNCTION
//  ──────────────────────────────────────────────────────────────────────────
//  Everything that decides "ask now / do not ask" lives in
//  `RateAppManager.decide(_:)`, a *static* pure function of `RateSignals`
//  (counters, dates, friction stamp, presentation state). No UserDefaults, no
//  UIKit, no clock — the caller supplies all of it. That is what makes the
//  timing rules provable in OSHITests without launching the UI.
//
//  ──────────────────────────────────────────────────────────────────────────
//  THE TWO THINGS THAT USED TO BE WRONG
//  ──────────────────────────────────────────────────────────────────────────
//  1. THE BUDGET WAS SPENT AT DECISION TIME. `stampPromptShown()` ran *before*
//     the sheet going up. A SwiftUI root `.sheet` is silently refused
//     by UIKit when something else is already presented ("already presenting"),
//     so a prompt that was never seen still burned the 120-day cooldown and the
//     app-version gate. The stamp now happens in `deliverArmedRequest` — i.e.
//     only once the request has genuinely been handed to StoreKit, with the
//     screen verified clear — and a busy screen leaves the milestone armed.
//     (This defect came BACK in the August rewrite, which wrote the ledger
//     entry in `present(source:)` and then let the modifier return without
//     asking. Read `pendingNativeRequest` for the whole story.)
//     See [[rating-prompt-races-the-share-sheet]]: *decide* ≠ *present* ≠ *spend*.
//
//  2. "WE CALLED IT" WAS TREATED AS "THE USER SAW IT". `requestReview()` /
//     `SKStoreReviewController` do NOTHING, silently, when the OS is over its
//     3-per-365-days ceiling — no error, no callback, no return value. The old
//     code set `hasRated = true` the instant the user tapped "Love it!", i.e.
//     it recorded an impression it cannot observe. We now keep our own
//     conservative ledger (`nativeRequestDates`) and refuse to even *attempt*
//     more than `maxNativeRequestsPerYear` in a trailing 365 days, so our own
//     ceiling is always at or below Apple's. The ledger records ATTEMPTS, and
//     is named and commented as such — it is never read as "the user rated".
//
//  The budget is Apple's own shape: at most 3 attempts per rolling 365 days,
//  at least 21 days apart. It used to be 120 days apart AND once per app
//  version, which inside a 3-per-year ceiling meant the app asked almost
//  nobody.
//
//  AppReviewManager remains for the manual "Rate OSHI" Settings button and the
//  App Store update checker. It no longer auto-fires anything.
//

import Foundation
import SwiftUI
import StoreKit
#if canImport(UIKit)
import UIKit
#endif

// MARK: - Pure decision core

/// Everything the timing rules are allowed to look at. Deliberately a plain
/// value type with no I/O so the rules can be exercised as `decide(signals)`.
struct RateSignals: Equatable {
    var now: Date = Date()

    // Install / session shape
    var firstLaunchDate: Date?
    var launchCount: Int = 0
    var activeDays: Int = 0

    // Counters (support dumps; `messagesSent` drives moment 3)
    var messagesSent: Int = 0
    var messagesDelivered: Int = 0
    var completedCalls: Int = 0
    var meshSuccesses: Int = 0

    // Armed milestone + version/asking history
    var pendingMilestone: Bool = false
    var currentVersion: String = "0"
    var promptedVersion: String?
    var lastSoftPromptDate: Date?
    var declinedDate: Date?

    /// Dates on which we ASKED the OS to show the native dialog. Not proof the
    /// dialog was shown — the API is silent when over quota.
    var nativeRequestDates: [Date] = []

    // Friction: the timestamp of the most recent failed send / failed call /
    // decryption failure / error alert.
    var lastFrictionDate: Date?

    // Live presentation state
    var isCallActive: Bool = false
    /// True when ANY view controller is presented (sheet, full-screen cover,
    /// share sheet, alert, mail composer) or a transition is in flight.
    var isUIBusy: Bool = false

    /// Moment 1 is the armed one. __WELCOME_REPLY_2026_09_07__ The user just
    /// wrote in the welcome thread: the first deliberate act in the app, so the
    /// install-age and session-count gates do not apply to it. Every other rule
    /// (Apple's ceiling, friction quiet, busy screen) still does. Read from the
    /// persisted armed moment, so a delivery hours later still knows it.
    var welcomeReply: Bool = false
    /// Moment 2 is the armed one (first delivery receipt from a real contact, or
    /// first completed call). Waives ONLY the install-age gate; the session gate,
    /// the native quota, the (shorter) spacing and the friction quiet period
    /// still apply.
    var firstRunMilestone: Bool = false
}

/// Why we are not asking. One reason per rule, so a test can assert the exact
/// gate that fired instead of "it returned false".
enum RateBlockReason: String, Equatable {
    case noMilestone
    case sameVersion
    case firstSession
    case tooSoonAfterInstall
    case declineBackoff
    case cooldown
    case nativeRequestBackoff
    case nativeQuotaExhausted
    case recentFriction
    case inCall
    case uiBusy
}

/// __RATING_THREE_MOMENTS_2026_11_15__ The only three moments that arm an ask.
enum RateMoment: String, Equatable {
    /// 1 — first message in the welcome thread. May fire in the first session.
    case welcomeReply
    /// 2 — first delivery receipt from a real contact, or first completed call.
    case firstSuccess
    /// 3 — ten messages sent, three weeks after the previous ask.
    case messages
}

enum RateDecision: Equatable {
    case present
    case block(RateBlockReason)

    var isPresent: Bool { self == .present }
}

/// Arms Apple's review request at the three moments and delivers it on a calm screen.
@MainActor
final class RateAppManager: ObservableObject {

    static let shared = RateAppManager()

    // MARK: - Tunable thresholds (all engagement gates in one place)
    // __EARLIER_ASK_2026_09_06__ Loosened on request: 8 ratings in the app's
    // lifetime with the previous gates (3 days, 3 launches, 10 delivered + 3
    // active days). Most people try OSHI with one friend on day one; the mesh
    // "it worked with no network" moment is the one they would rate, and it
    // needed a second active day. Apple's 3-per-year ceiling, the once-per-
    // version rule and the friction quiet period are unchanged.

    internal enum Config {
        // ── The three moments ───────────────────────────────────────────────
        /// Moment 3: messages sent before the ordinary ask becomes eligible.
        static let minMessagesSentForAsk = 10
        /// Moment 2 (call side): a call only counts once it lasted this long AND
        /// ended normally.
        static let minCallSeconds: TimeInterval = 60
        /// Spacing before an EARLY moment (1 or 2) when an ask already went out.
        /// Without it the day-1 welcome ask would cost moment 2 three weeks.
        static let daysAfterEarlyAsk: TimeInterval = 7

        // ── "Not a brand-new user" ──────────────────────────────────────────
        /// Never prompt before the app has been installed this many days.
        static let minDaysSinceInstall: TimeInterval = 1
        /// Sessions required. ≥3 ⇒ never the very first (or second) session.
        static let minLaunchCount = 2

        // ── Back-offs ───────────────────────────────────────────────────────
        /// Retired with the soft pre-ask. The only spacing that remains is
        /// [daysAfterNativeRequest], which is what Apple actually honours.
        static let daysBetweenSoftPrompts: TimeInterval = 0
        /// Retired with the soft pre-ask: there is no "Not really" button to
        /// decline any more, so nothing writes `declinedDate`. Kept at 0 so an
        /// install that still carries an old decline stamp is not held silent for
        /// six months over a question it was asked by a screen that no longer
        /// exists.
        static let daysAfterDecline: TimeInterval = 0
        /// Spacing before moment 3: three weeks, the portfolio-wide figure
        /// (Supernova `RatingPrompter.minDaysBetween`).
        ///
        /// 🔴 THIS WAS 120 DAYS, AND IT WAS NOT A SAFETY MARGIN — it was the
        /// reason the app asked almost nobody. Four months between asks inside a
        /// three-per-year budget means at most three asks will ever land, and any
        /// one of them that iOS silently dropped cost another four months of
        /// silence. Apple's own ceiling is three per 365 days with no stated
        /// spacing; three weeks sits comfortably under it and is what every other
        /// app in the portfolio uses.
        static let daysAfterNativeRequest: TimeInterval = 21
        /// Our own ceiling on native requests per trailing 365 days. Apple's is
        /// 3; staying at 3 means we never rely on their silent no-op.
        static let maxNativeRequestsPerYear = 3

        // ── Friction quarantine ─────────────────────────────────────────────
        /// No asking within this window of ANY failed send, failed/dropped
        /// call, decryption failure or error alert.
        static let frictionQuietSeconds: TimeInterval = 600

        // ── Presentation ────────────────────────────────────────────────────
        /// Re-checks when the UI is busy, ~1.5 s apart.
        /// How many times one occasion comes back and looks for a calm screen.
        /// The request stays armed either way — the next foreground, the
        /// conversation list, or the next launch picks it up again.
        static let busyRetryAttempts = 10
        static let busyRetryIntervalSeconds: TimeInterval = 1.2

        /// An armed native request older than this is dropped rather than fired
        /// at a screen that has nothing to do with the success that earned it
        /// (Supernova `RatingPrompter.armedLifetime`).
        static let armedRequestLifetime: TimeInterval = 24 * 3_600
    }

    // MARK: - Persisted keys (namespaced — do NOT collide with AppReviewManager)

    private enum Key {
        static let firstLaunchDate      = "RateApp_FirstLaunchDate"
        static let launchCount          = "RateApp_LaunchCount"
        static let messagesSent         = "RateApp_MessagesSent"
        static let messagesDelivered    = "RateApp_MessagesDelivered"
        static let completedCalls       = "RateApp_CompletedCalls"
        static let meshSuccesses        = "RateApp_MeshSuccesses"
        static let activeDays           = "RateApp_ActiveDays"          // [String yyyy-MM-dd]
        static let pendingMilestone     = "RateApp_PendingMilestone"
        static let lastSoftPromptDate   = "RateApp_LastSoftPromptDate"
        static let promptedVersion      = "RateApp_PromptedVersion"     // CFBundleShortVersionString
        static let declinedDate         = "RateApp_DeclinedDate"
        static let lastFrictionDate     = "RateApp_LastFrictionDate"
        static let nativeRequestDates   = "RateApp_NativeRequestDates"  // [Date] — ATTEMPTS, not impressions
        static let legacyHasRated       = "RateApp_HasRated"            // pre-2026-08-19; migrated in init
        // __EARLY_ASK_2026_10_09__ Each of these fires once per install, ever.
        /// When the pending native request was armed. Absent ⇒ none is armed.
        static let armedAt              = "RateApp_ArmedAt"
        static let askedOnFirstDelivery = "RateApp_AskedOnFirstDelivery" // moment 2 DELIVERED
        static let askedOnFirstContact  = "RateApp_AskedOnFirstContact"  // retired moment
        // __RATING_THREE_MOMENTS_2026_11_15__
        /// Which moment armed `pendingMilestone` (`RateMoment.rawValue`).
        static let armedMoment          = "RateApp_ArmedMoment"
        /// When `pendingMilestone` was armed; it expires like the native token.
        static let milestoneArmedAt     = "RateApp_MilestoneArmedAt"
        /// Moment 1 has been attempted (once per install, ever).
        static let askedOnWelcomeReply  = "RateApp_AskedOnWelcomeReply"
    }

    private let defaults = UserDefaults.standard

    /// Armed by `present(source:)`, consumed by `deliverArmedRequest(request:)`.
    ///
    /// 🔧 THE DEFECT THIS SPLIT EXISTS FOR, twice over. First the "Love it!"
    /// button called `requestReview()` and THEN `dismiss()`, from inside the
    /// pre-ask sheet's own action: `\.requestReview` asked while another modal
    /// is still on screen is not queued by iOS — it is DISCARDED, with no error
    /// and no log — while the ledger entry had already been written. Then the
    /// August rewrite removed the sheet but kept writing the ledger entry at the
    /// decision, and the modifier's `guard !RatePresentationProbe.isBusy()`
    /// returned without asking. Both times a slot was spent on a dialog nobody
    /// could have seen.
    ///
    /// So: arming writes this flag and nothing else. The ledger entry is written
    /// in `deliverArmedRequest`, at the instant the request is actually made,
    /// with the screen verified clear. "Decide ≠ present ≠ spend."
    private var pendingNativeRequest = false

    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    private var currentVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
    }

    private init() {
        if defaults.object(forKey: Key.firstLaunchDate) == nil {
            defaults.set(Date(), forKey: Key.firstLaunchDate)
        }
        migrateLegacyHasRatedIfNeeded()
        dropUntypedLegacyMilestone()
    }

    /// A `pendingMilestone` left by an older build (armed by the retired
    /// engagement heuristic) has no moment attached. It is none of the three
    /// moments, so it is dropped rather than fired at some later foreground.
    private func dropUntypedLegacyMilestone() {
        guard defaults.bool(forKey: Key.pendingMilestone),
              defaults.string(forKey: Key.armedMoment) == nil else { return }
        defaults.set(false, forKey: Key.pendingMilestone)
        OshiLog.ui.info("📊 RateApp: dropped an untyped legacy milestone")
    }

    /// Old builds recorded a boolean "hasRated" the moment the user tapped
    /// "Love it!" — an impression we never observed. Convert it into what it
    /// actually was: one native REQUEST, on the day of the last soft prompt.
    private func migrateLegacyHasRatedIfNeeded() {
        guard defaults.bool(forKey: Key.legacyHasRated) else { return }
        guard (defaults.array(forKey: Key.nativeRequestDates) as? [Date])?.isEmpty ?? true else { return }
        let when = (defaults.object(forKey: Key.lastSoftPromptDate) as? Date) ?? Date()
        defaults.set([when], forKey: Key.nativeRequestDates)
        defaults.set(false, forKey: Key.legacyHasRated)
        OshiLog.ui.info("📊 RateApp: migrated legacy hasRated → 1 native request attempt")
    }

    // MARK: - Engagement recording (call sites are one-liners)

    /// Increments the launch counter and evaluates a *pending* milestone.
    /// Call from `sceneDidBecomeActive`. Never prompts on a first session
    /// (guarded by `minLaunchCount`) and only presents when a milestone was
    /// already reached in prior engagement — so it is never "a prompt on launch".
    func recordLaunchAndEvaluate() {
        let launches = defaults.integer(forKey: Key.launchCount) + 1
        defaults.set(launches, forKey: Key.launchCount)
        recordActiveDay()
        scheduleEvaluation(source: "foreground")
    }

    /// A message left the device. Moment 3 once `minMessagesSentForAsk` have
    /// gone out — `>=`, never `==`, so one dropped increment cannot close it.
    func recordMessageSent() {
        let sent = defaults.integer(forKey: Key.messagesSent) + 1
        defaults.set(sent, forKey: Key.messagesSent)
        recordActiveDay()
        if sent >= Config.minMessagesSentForAsk {
            arm(.messages, source: "messages=\(sent)")
        }
    }

    /// A delivery receipt came back: the message provably reached the peer.
    /// Moment 2, unless the peer is the welcome author — that thread had its
    /// own moment (1). Arms only — presenting here would be mid-conversation.
    func recordMessageDelivered(from peer: String? = nil) {
        if let peer, Self.isWelcomeAuthor(peer) { return }
        defaults.set(defaults.integer(forKey: Key.messagesDelivered) + 1, forKey: Key.messagesDelivered)
        recordActiveDay()
        armFirstSuccess(source: "first-delivery")
    }

    /// Adding a contact is activity, not a rating moment any more: it proves
    /// nothing worked yet, and it would be a fourth moment.
    func recordContactAdded() {
        recordActiveDay()
    }

    /// A message was carried by the mesh / LoRa path. Counted for support
    /// dumps; the receipt or mesh ACK that follows is what can arm moment 2.
    func recordMeshDeliverySucceeded() {
        defaults.set(defaults.integer(forKey: Key.meshSuccesses) + 1, forKey: Key.meshSuccesses)
        recordActiveDay()
    }

    /// A call that actually connected, lasted a non-trivial time and ended
    /// normally: moment 2, call side. A finished call is a natural pause.
    ///
    /// `endedNormally` must be false for missed / declined / dropped / network
    /// failures; those are friction, not success.
    func recordCallCompleted(durationSeconds: TimeInterval, endedNormally: Bool) {
        guard endedNormally, durationSeconds >= Config.minCallSeconds else {
            if !endedNormally { recordFriction(reason: "call-failed") }
            return
        }
        defaults.set(defaults.integer(forKey: Key.completedCalls) + 1, forKey: Key.completedCalls)
        recordActiveDay()
        armFirstSuccess(source: "first-call")
    }

    /// Moment 2 is OWED until it is actually delivered (`askedOnFirstDelivery`
    /// is written in `deliverArmedRequest`). A receipt inside the week after the
    /// welcome ask, or while another moment is still armed, simply does not arm,
    /// and the next receipt or completed call tries again.
    private func armFirstSuccess(source: String) {
        guard !defaults.bool(forKey: Key.askedOnFirstDelivery) else { return }
        arm(.firstSuccess, source: source)
    }

    /// The one arming path for all three moments. Spends nothing: it writes the
    /// milestone and its moment, and asks for an evaluation shortly after. The
    /// budget (quota + the moment's spacing) is checked here as well as at
    /// delivery, so a moment that cannot be asked now is not parked for weeks.
    @discardableResult
    private func arm(_ moment: RateMoment, source: String, now: Date = Date()) -> Bool {
        expireStaleMilestone(now: now)
        guard !defaults.bool(forKey: Key.pendingMilestone) else { return false }
        let ledger = (defaults.array(forKey: Key.nativeRequestDates) as? [Date]) ?? []
        guard Self.budgetAllows(moment, nativeRequestDates: ledger, now: now) else { return false }
        defaults.set(true, forKey: Key.pendingMilestone)
        defaults.set(moment.rawValue, forKey: Key.armedMoment)
        defaults.set(now, forKey: Key.milestoneArmedAt)
        OshiLog.ui.info("📊 RateApp: \(source) — moment \(moment.rawValue) armed, evaluating shortly")
        Task { @MainActor [weak self] in
            // Let the send animation / call teardown settle first; `isUIBusy`
            // covers whatever is still on screen after that.
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            self?.scheduleEvaluation(source: source)
        }
        return true
    }

    /// A milestone that found no calm, allowed moment within a day is dropped,
    /// like the native token, instead of firing out of context later.
    private func expireStaleMilestone(now: Date) {
        guard defaults.bool(forKey: Key.pendingMilestone),
              let armedAt = defaults.object(forKey: Key.milestoneArmedAt) as? Date,
              now.timeIntervalSince(armedAt) > Config.armedRequestLifetime else { return }
        defaults.set(false, forKey: Key.pendingMilestone)
        defaults.removeObject(forKey: Key.armedMoment)
        defaults.removeObject(forKey: Key.milestoneArmedAt)
        OshiLog.ui.info("📊 RateApp: armed moment expired (24 h) — dropped, nothing spent")
    }

    static func isWelcomeAuthor(_ key: String) -> Bool {
        MessageManager.canonicalIdentity(key)
            == MessageManager.canonicalIdentity(WelcomeMessageManager.authorPublicKey)
    }

    /// ANY moment the user is having a bad time: a failed send, a failed or
    /// dropped call, a decryption failure, an error alert. Stamps a quarantine
    /// window; `decide` refuses to ask inside it.
    func recordFriction(reason: String = "error") {
        defaults.set(Date(), forKey: Key.lastFrictionDate)
        // If the ask is on screen right now it is already too late to be
        // tasteful — but we must not also burn the budget on it.
        OshiLog.ui.info("🚧 RateApp: friction recorded (\(reason)) — quiet for \(Int(Config.frictionQuietSeconds))s")
    }

    // MARK: - Retired: the "Enjoying OSHI?" pre-ask
    //
    // `userLovesApp()`, `userDeclined()` and `softPromptDidAppear()` lived here.
    // They were the three outcomes of a sheet of ours that asked how the user
    // felt and sent only the positive answers to Apple's dialog — a custom,
    // sentiment-routing review prompt (guideline 1.1.7). The sheet went in
    // August 2026; these three went with it, along with `isShowingSoftPrompt`,
    // the presentation watchdog and `RateAppPromptView`.
    //
    // What replaced the filtering is not nothing: the ask is ARMED by a moment
    // that is itself the evidence — a delivery receipt, a completed call, a mesh
    // success — and it is held back after any friction. Sentiment is carried by
    // *which moment arms the ask*, never by asking the user for it.

    // MARK: - Budget

    /// Pure: may `moment` be asked now, as far as the ledger goes? The yearly
    /// ceiling for everyone; the spacing is a week for the early moments (1, 2)
    /// and three weeks for moment 3.
    static func budgetAllows(_ moment: RateMoment, nativeRequestDates: [Date], now: Date) -> Bool {
        let year: TimeInterval = 365 * 86_400
        let inWindow = nativeRequestDates.filter { now.timeIntervalSince($0) < year }
        if inWindow.count >= Config.maxNativeRequestsPerYear { return false }
        if let last = inWindow.max(),
           now.timeIntervalSince(last) < spacingDays(for: moment) * 86_400 { return false }
        return true
    }

    static func spacingDays(for moment: RateMoment) -> TimeInterval {
        moment == .messages ? Config.daysAfterNativeRequest : Config.daysAfterEarlyAsk
    }

    /// Pure: the whole timing policy. Order matters only for which reason is
    /// reported; every rule is independent and any one of them blocks.
    static func decide(_ s: RateSignals) -> RateDecision {
        // Only when a milestone was actually reached during real engagement.
        guard s.pendingMilestone else { return .block(.noMilestone) }

        // 🔴 THE "ONE ASK PER APP VERSION" GATE IS GONE.
        //
        // It read `promptedVersion == currentVersion` and blocked. On a monthly
        // release train that is a budget of roughly one ask per release, which is
        // a different and much smaller thing than Apple's three per 365 days —
        // and it was the gate that made the spacing below irrelevant. The
        // spacing (21 days) and the yearly ceiling (3) are the whole budget now.
        // `promptedVersion` is still written, for support dumps.

        // Never a brand-new user: N sessions AND M days since install — except
        // moment 1 (`welcomeReply`), which waives both, and moment 2
        // (`firstRunMilestone`), which waives only the install age.
        if !s.welcomeReply {
            if s.launchCount < Config.minLaunchCount { return .block(.firstSession) }
        }
        if !s.welcomeReply && !s.firstRunMilestone {
            if let first = s.firstLaunchDate,
               s.now.timeIntervalSince(first) < Config.minDaysSinceInstall * 86_400 {
                return .block(.tooSoonAfterInstall)
            }
            // No install date at all ⇒ treat as brand new, never ask.
            if s.firstLaunchDate == nil { return .block(.tooSoonAfterInstall) }
        }

        // Decline back-off.
        if let declined = s.declinedDate,
           s.now.timeIntervalSince(declined) < Config.daysAfterDecline * 86_400 {
            return .block(.declineBackoff)
        }

        // Cooldown between soft prompts that were actually seen (≥90 days).
        if let last = s.lastSoftPromptDate,
           s.now.timeIntervalSince(last) < Config.daysBetweenSoftPrompts * 86_400 {
            return .block(.cooldown)
        }

        // We already spent a native request recently. The OS may or may not have
        // drawn that dialog — we cannot tell — so we assume it did.
        // Moments 1 and 2 need a week since the previous ask; moment 3 three.
        let year: TimeInterval = 365 * 86_400
        let spacing = (s.welcomeReply || s.firstRunMilestone)
            ? Config.daysAfterEarlyAsk : Config.daysAfterNativeRequest
        if let lastNative = s.nativeRequestDates.max(),
           s.now.timeIntervalSince(lastNative) < spacing * 86_400 {
            return .block(.nativeRequestBackoff)
        }
        // Our own ceiling, kept at or below Apple's 3 / 365 days.
        let inWindow = s.nativeRequestDates.filter { s.now.timeIntervalSince($0) < year }.count
        if inWindow >= Config.maxNativeRequestsPerYear {
            return .block(.nativeQuotaExhausted)
        }

        // Never right after the user had a bad time.
        if let friction = s.lastFrictionDate,
           s.now.timeIntervalSince(friction) < Config.frictionQuietSeconds {
            return .block(.recentFriction)
        }

        // Never during a call.
        if s.isCallActive { return .block(.inCall) }

        // Never while anything else owns the screen — a sheet, a full-screen
        // cover, a share sheet, an alert. UIKit would refuse our sheet and the
        // user would see nothing.
        if s.isUIBusy { return .block(.uiBusy) }

        return .present
    }

    // MARK: - Signal gathering (the only place that touches storage/UIKit)

    /// Everything the rules need that comes from persisted state. No UIKit, no
    /// `VoiceCallManager` — safe to call on hot paths.
    func storedSignals(now: Date = Date()) -> RateSignals {
        RateSignals(
            now: now,
            firstLaunchDate: defaults.object(forKey: Key.firstLaunchDate) as? Date,
            launchCount: defaults.integer(forKey: Key.launchCount),
            activeDays: activeDayCount(),
            messagesSent: defaults.integer(forKey: Key.messagesSent),
            messagesDelivered: defaults.integer(forKey: Key.messagesDelivered),
            completedCalls: defaults.integer(forKey: Key.completedCalls),
            meshSuccesses: defaults.integer(forKey: Key.meshSuccesses),
            pendingMilestone: defaults.bool(forKey: Key.pendingMilestone),
            currentVersion: currentVersion,
            promptedVersion: defaults.string(forKey: Key.promptedVersion),
            lastSoftPromptDate: defaults.object(forKey: Key.lastSoftPromptDate) as? Date,
            declinedDate: defaults.object(forKey: Key.declinedDate) as? Date,
            nativeRequestDates: (defaults.array(forKey: Key.nativeRequestDates) as? [Date]) ?? [],
            lastFrictionDate: defaults.object(forKey: Key.lastFrictionDate) as? Date,
            isCallActive: false,
            isUIBusy: false,
            welcomeReply: armedMoment == .welcomeReply,
            firstRunMilestone: armedMoment == .firstSuccess
        )
    }

    /// The moment that armed the pending milestone, if any.
    private var armedMoment: RateMoment? {
        defaults.string(forKey: Key.armedMoment).flatMap(RateMoment.init(rawValue:))
    }

    /// `storedSignals()` plus the two live probes. Only the presentation
    /// decision needs these, and only it pays for them.
    func currentSignals(now: Date = Date()) -> RateSignals {
        var s = storedSignals(now: now)
        s.isCallActive = Self.isCallActive()
        // __CALL_RATING_2026_09_22__ The call-quality sheet and the App Store
        // ask are both triggered by a call ending, seconds apart. On iOS the
        // probe already sees the sheet as a presented controller; on macOS it
        // compiles to `false`, so the two managers agree through this flag
        // instead. Asking for five stars on top of "how was the call?" reads
        // as one broken screen.
        s.isUIBusy = RatePresentationProbe.isBusy() || CallRatingManager.shared.isPresenting
        return s
    }

    private static func isCallActive() -> Bool {
        let state = VoiceCallManager.shared.callState
        return state == .inCall || state == .ringing || state == .connecting
    }

    // MARK: - Presentation

    /// Evaluate, and if the only thing in the way is a busy screen, come back
    /// later instead of throwing the milestone away.
    /// __EARLIER_ASK_2026_09_06__ The conversation list is the natural pause
    /// after a message landed (mesh included) — evaluate an armed milestone
    /// there instead of waiting for the next foregrounding. Every timing rule
    /// in `decide` still applies; this only adds a moment to ask.
    func evaluatePendingMilestone(source: String) {
        scheduleEvaluation(source: source)
    }

    /// Moment 1. __WELCOME_REPLY_2026_09_07__ The user wrote their first message
    /// in the welcome thread. Once per install, EVER (written at the attempt: a
    /// later message there is no longer the first deliberate act). `decide`
    /// waives the install-age and session-count gates for it — it may fire in
    /// the very first session — and nothing else.
    func recordWelcomeReply() {
        recordActiveDay()
        guard !defaults.bool(forKey: Key.askedOnWelcomeReply) else { return }
        defaults.set(true, forKey: Key.askedOnWelcomeReply)
        arm(.welcomeReply, source: "welcome-reply")
    }

    private func scheduleEvaluation(source: String, attempt: Int = 0) {
        expireStaleMilestone(now: Date())
        // The moment's waivers come from the persisted armed moment, so a
        // foreground or a conversation-list pass hours later applies the same
        // rules as the pass right after the milestone.
        switch Self.decide(currentSignals()) {
        case .present:
            present(source: source)
        case .block(.uiBusy) where attempt < Config.busyRetryAttempts:
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(Config.busyRetryIntervalSeconds * 1_000_000_000))
                self?.scheduleEvaluation(source: source, attempt: attempt + 1)
            }
        case .block(let reason):
            OshiLog.ui.info("📊 RateApp: not asking (\(source)) — \(reason.rawValue)")
        }
    }

    /// 🔴 __NO_REVIEW_GATING_2026_08_22__ LA PRÉ-QUESTION EST SUPPRIMÉE.
    ///
    /// Le motif « Enjoying OSHI? » avec « Love it! » / « Not really » est un
    /// FILTRAGE D'AVIS: seul l'utilisateur qui se déclarait content voyait
    /// ensuite le dialogue d'Apple, et `userDeclined()` disait littéralement
    /// « never show the native prompt ». Les règles de l'App Store demandent
    /// d'utiliser l'API fournie et proscrivent les invitations maison; et une
    /// note recueillie seulement auprès des gens contents n'est pas une note.
    ///
    /// Ce qui reste — et qui était la partie soignée de ce fichier — ne bouge
    /// pas: les jalons mérités, le quota annuel, la sonde qui vérifie que
    /// l'écran est libre, le silence après une friction, jamais pendant un
    /// appel. On demande simplement Apple DIRECTEMENT, à tout le monde, au bon
    /// moment.
    ///
    /// ──────────────────────────────────────────────────────────────────────
    /// 🔴 ET CE QUE CETTE VERSION-LÀ A RECASSÉ: LE BUDGET ÉTAIT REDEVENU
    ///    DÉPENSÉ À LA DÉCISION.
    /// ──────────────────────────────────────────────────────────────────────
    /// Elle écrivait l'entrée dans `nativeRequestDates` ici, puis appelait
    /// `askNativeNow?()`. Ce bloc-là, dans le modificateur, finit par:
    ///
    ///     guard !RatePresentationProbe.isBusy() else { return }   // rien demandé
    ///
    /// soit: le jeton consommé, le créneau débité, le recul enclenché — et
    /// aucun dialogue. C'est exactement le défaut que l'en-tête de ce fichier
    /// décrit comme corrigé (« LE BUDGET ÉTAIT DÉPENSÉ AU MOMENT DE LA
    /// DÉCISION »), réintroduit par la réécriture du 22/08. En prime,
    /// `stampPromptShown()` n'était plus appelé par personne, donc
    /// `pendingMilestone` n'était jamais effacé.
    ///
    /// Donc: `present` ARME, et ne dépense rien. Le créneau est débité dans
    /// `deliverArmedRequest(request:)`, le seul endroit qui remet vraiment la
    /// demande à StoreKit, et seulement une fois l'écran libre.
    private func present(source: String) {
        if !pendingNativeRequest {
            pendingNativeRequest = true
            defaults.set(Date(), forKey: Key.armedAt)
            OshiLog.ui.info("📊 RateApp: demande native ARMÉE (source: \(source)) — en attente d'un écran libre")
        }
        // 🔴 TOUJOURS redemander une passe, même si le jeton était déjà armé.
        // Sinon un `guard !pendingNativeRequest else { return }` en tête fait
        // que le jeton posé avant que le modificateur ne soit monté — ou pendant
        // un appel — ne redemande plus jamais rien, et dort jusqu'à expiration.
        askNativeNow?()
    }

    /// Branché par `RateAppPromptModifier`, qui seul détient
    /// `@Environment(\.requestReview)`.
    var askNativeNow: (() -> Void)?

    /// Remet une demande armée à StoreKit, et seulement alors débite le créneau.
    ///
    /// C'est le pendant de `RatingPrompter.deliverArmedRequest` de Supernova.
    /// Le modificateur appelle ceci en boucle; un écran occupé renvoie `false`,
    /// ne change rien, et laisse la demande armée pour la prochaine occasion.
    ///
    /// - Returns: `true` uniquement si `request()` a réellement été appelé.
    @discardableResult
    func deliverArmedRequest(now: Date = Date(), request: () -> Void) -> Bool {
        guard pendingNativeRequest else { return false }

        // Une demande armée trop vieille n'a plus de rapport avec quoi que ce
        // soit que l'utilisateur ait en tête: on la jette au lieu de la sortir
        // sans cause visible au lancement suivant.
        if let armedAt = defaults.object(forKey: Key.armedAt) as? Date,
           now.timeIntervalSince(armedAt) > Config.armedRequestLifetime {
            clearArmedRequest()
            OshiLog.ui.info("📊 RateApp: demande armée expirée (24 h) — abandonnée, rien dépensé")
            return false
        }

        // Toutes les règles sont relues à la livraison: l'armement peut avoir
        // des heures, et un créneau a pu être dépensé entre-temps.
        guard case .present = Self.decide(currentSignals(now: now)) else { return false }

        // Moment 2 has had its ask only once it actually goes out.
        if armedMoment == .firstSuccess {
            defaults.set(true, forKey: Key.askedOnFirstDelivery)
        }
        clearArmedRequest()
        recordNativeRequestAttempt(now: now)
        stampPromptShown()
        request()
        return true
    }

    // MARK: - Explicit "Rate OSHI" button (owner, 18/11/2026)

    enum RateButtonAction: Equatable { case native, storePage }

    /// Pure: what the Settings "Rate OSHI" button does. Apple draws the native
    /// sheet at most 3 times per 365 days, so the button reads the SAME ledger
    /// as the automatic ask: fewer than `maxNativeRequestsPerYear` attempts
    /// (automatic + button) in the last 365 days → the native sheet; otherwise
    /// the write-review page, which always opens.
    static func rateButtonAction(nativeRequestDates: [Date], now: Date) -> RateButtonAction {
        let year: TimeInterval = 365 * 86_400
        let inWindow = nativeRequestDates.filter { now.timeIntervalSince($0) < year }
        return inWindow.count < Config.maxNativeRequestsPerYear ? .native : .storePage
    }

    /// The button tap. `.native` → `request()` (Apple's sheet) and one attempt
    /// written to the shared ledger, so it also counts toward the automatic
    /// ask's yearly cap and spacing; `.storePage` → `openStorePage()`.
    /// Automatic rules are untouched.
    @discardableResult
    func handleRateButton(now: Date = Date(), request: () -> Void, openStorePage: () -> Void) -> RateButtonAction {
        let ledger = (defaults.array(forKey: Key.nativeRequestDates) as? [Date]) ?? []
        let action = Self.rateButtonAction(nativeRequestDates: ledger, now: now)
        switch action {
        case .native:
            recordNativeRequestAttempt(now: now)
            request()
        case .storePage:
            openStorePage()
        }
        return action
    }

    /// Débite un créneau. Appelé depuis `deliverArmedRequest` et depuis le
    /// bouton « Noter OSHI » (`handleRateButton`), donc seulement quand la
    /// demande a réellement été remise au système.
    ///
    /// Ce registre compte des TENTATIVES. `requestReview` ne renvoie rien et ne
    /// signale rien: il ne fait silencieusement rien au-delà du quota d'iOS, sur
    /// TestFlight, ou si la personne a déjà noté. Rien ici ne doit jamais être
    /// lu comme « l'utilisateur a noté ».
    private func recordNativeRequestAttempt(now: Date) {
        var dates = (defaults.array(forKey: Key.nativeRequestDates) as? [Date]) ?? []
        dates.append(now)
        let cutoff = now.addingTimeInterval(-400 * 86_400)
        defaults.set(dates.filter { $0 > cutoff }, forKey: Key.nativeRequestDates)
        OshiLog.ui.info("⭐️ RateApp: dialogue natif demandé — tentative #\(dates.count) sur l'année")
    }

    private func clearArmedRequest() {
        pendingNativeRequest = false
        defaults.removeObject(forKey: Key.armedAt)
    }

    /// Y a-t-il une demande en attente de remise au système ?
    var hasArmedRequest: Bool { pendingNativeRequest }

    /// Clear the armed milestone, now that the ask has genuinely gone out.
    ///
    /// 🔴 NOBODY WAS CALLING THIS. Its only caller was `softPromptDidAppear()`,
    /// which was only called from the pre-ask sheet's `onAppear` — and that
    /// sheet was removed in August. So `pendingMilestone` was never cleared
    /// after an ask, and `promptedVersion` was never written. It is called from
    /// `deliverArmedRequest` now, which is the one place an ask actually
    /// happens.
    ///
    /// `promptedVersion` is written for support dumps only; the `sameVersion`
    /// gate it used to feed is gone — it capped the app at one ask per release,
    /// which on a monthly release train is a different and much smaller budget
    /// than Apple's three a year.
    private func stampPromptShown() {
        defaults.set(Date(), forKey: Key.lastSoftPromptDate)
        defaults.set(currentVersion, forKey: Key.promptedVersion)
        defaults.set(false, forKey: Key.pendingMilestone)
        defaults.removeObject(forKey: Key.armedMoment)
        defaults.removeObject(forKey: Key.milestoneArmedAt)
    }

    // MARK: - Active-day tracking

    private func recordActiveDay() {
        let today = Self.dayFormatter.string(from: Date())
        var days = defaults.stringArray(forKey: Key.activeDays) ?? []
        guard !days.contains(today) else { return }
        days.append(today)
        // Keep the set bounded — we only ever compare counts.
        if days.count > 14 { days = Array(days.suffix(14)) }
        defaults.set(days, forKey: Key.activeDays)
    }

    private func activeDayCount() -> Int {
        (defaults.stringArray(forKey: Key.activeDays) ?? []).count
    }

    // MARK: - Debug helpers

    #if DEBUG
    /// Wipe all rate state so the flow can be re-triggered in testing.
    func resetForTesting() {
        [Key.firstLaunchDate, Key.launchCount, Key.messagesSent, Key.messagesDelivered,
         Key.completedCalls, Key.meshSuccesses, Key.activeDays, Key.pendingMilestone,
         Key.lastSoftPromptDate, Key.promptedVersion, Key.declinedDate,
         Key.lastFrictionDate, Key.nativeRequestDates, Key.legacyHasRated,
         Key.armedAt, Key.askedOnFirstDelivery, Key.askedOnFirstContact,
         Key.armedMoment, Key.milestoneArmedAt, Key.askedOnWelcomeReply].forEach {
            defaults.removeObject(forKey: $0)
        }
        OshiLog.ui.info("📊 RateApp: reset for testing")
    }

    /// Drop any armed request, without asking and without spending a slot.
    ///
    /// Tests need this because the armed token lives on this singleton as well as
    /// on disk: a test that restores `UserDefaults` and walks away leaves
    /// `pendingNativeRequest` true in memory, and the next test in the suite
    /// starts with a token it never armed. That is exactly how two of these
    /// passed alone and failed in the full run.
    func clearArmedRequestForTesting() {
        clearArmedRequest()
    }

    /// Arm a request regardless of the engagement gates, so the native dialog
    /// can be walked on a device. The screen checks still apply — that is the
    /// part worth testing.
    func forceArmNativeRequest(moment: RateMoment = .messages) {
        defaults.set(true, forKey: Key.pendingMilestone)
        defaults.set(moment.rawValue, forKey: Key.armedMoment)
        defaults.set(Date(), forKey: Key.milestoneArmedAt)
        present(source: "debug")
    }

    /// Run the real arming path for one moment, without the delayed evaluation
    /// mattering (tests deliver explicitly). Returns whether it armed.
    @discardableResult
    func armForTesting(_ moment: RateMoment, now: Date = Date()) -> Bool {
        arm(moment, source: "test", now: now)
    }
    #endif
}

// MARK: - Presentation probe

/// Is anything else already owning the screen? A SwiftUI root `.sheet` is
/// silently refused by UIKit when a presentation is already in flight, so this
/// is the difference between "the user saw the ask" and "we burned it".
@MainActor
enum RatePresentationProbe {

    /// Injection seam. Production keeps `liveIsBusy`; tests point it at a
    /// stand-in so the real delivery path can be walked without a scene graph.
    ///
    /// 🔴 WITHOUT THIS, THE DELIVERY TESTS WERE UNRUNNABLE IN A FULL SUITE.
    /// `liveIsBusy` walks the host application's real window chain, and in a
    /// 1 404-test run that chain belongs to whatever ran before: a presented
    /// controller left behind by another suite, or no foreground-active scene at
    /// all, both read as "busy". The two tests that assert a slot is spent only
    /// when the request actually goes out therefore passed alone and failed in
    /// the suite — which is the worst possible shape for a regression test on a
    /// defect that has already been reintroduced once.
    static var provider: () -> Bool = { liveIsBusy() }

    static func isBusy() -> Bool { provider() }

    /// __RATING_THREE_MOMENTS_2026_11_15__ The keyboard is up: the user may be
    /// mid-typing (the welcome-reply ask is armed by a send, in the chat). The
    /// keyboard is not a presented controller, so it is tracked by notification.
    private(set) static var isKeyboardVisible = false
    private static var keyboardObservers: [NSObjectProtocol] = []

    /// Idempotent; called by the root modifier.
    static func startKeyboardTracking() {
        #if canImport(UIKit)
        guard keyboardObservers.isEmpty else { return }
        let center = NotificationCenter.default
        keyboardObservers.append(center.addObserver(
            forName: UIResponder.keyboardWillShowNotification, object: nil, queue: .main
        ) { _ in Task { @MainActor in isKeyboardVisible = true } })
        keyboardObservers.append(center.addObserver(
            forName: UIResponder.keyboardWillHideNotification, object: nil, queue: .main
        ) { _ in Task { @MainActor in isKeyboardVisible = false } })
        #endif
    }

    static func liveIsBusy() -> Bool {
        if isKeyboardVisible { return true }
        #if canImport(UIKit)
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard let scene = scenes.first(where: { $0.activationState == .foregroundActive }) else {
            // Not foreground-active ⇒ treat as busy; never ask into the void.
            return true
        }
        guard let window = scene.windows.first(where: { $0.isKeyWindow }) ?? scene.windows.first,
              var top = window.rootViewController else { return true }

        if top.isBeingPresented || top.isBeingDismissed { return true }
        // Anything presented at all — sheet, full-screen cover, share sheet,
        // alert, mail composer, the call UI — means "not now".
        while let presented = top.presentedViewController {
            if presented.isBeingDismissed { return true }
            top = presented
        }
        return top !== window.rootViewController
        #else
        return false
        #endif
    }
}

extension View {
    /// Attach once near the app root. Hands Apple's own review request to
    /// StoreKit whenever `RateAppManager` has one armed and the screen is calm.
    /// There is no question of ours before it.
    func rateAppPrompt() -> some View {
        modifier(RateAppPromptModifier())
    }
}

private struct RateAppPromptModifier: ViewModifier {
    @ObservedObject private var manager = RateAppManager.shared
    @Environment(\.requestReview) private var requestReview
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        // __NO_REVIEW_GATING_2026_08_22__ Plus de feuille intermédiaire: le
        // gestionnaire arme, ce modificateur demande — il est le seul à détenir
        // `@Environment(\.requestReview)`.
        content
            .onAppear {
                RatePresentationProbe.startKeyboardTracking()
                manager.askNativeNow = { deliverArmedRequest(after: Self.delayAfterArm) }
                // Une demande armée lors d'une session précédente: l'app est
                // tuée par le système en permanence, donc le jeton vit sur le
                // disque et sort au premier moment calme du lancement suivant.
                deliverArmedRequest(after: Self.delayOnLaunch)
            }
            .onChange(of: scenePhase) { _, phase in
                // Le point de reprise. Ce qui couvrait l'écran à la tentative
                // précédente — un appel, une feuille, la demande
                // d'autorisation, « comment était l'appel ? » — a disparu au
                // retour au premier plan, et une demande armée ne coûte rien
                // en attendant.
                guard phase == .active else { return }
                deliverArmedRequest(after: Self.delayOnForeground)
            }
    }

    /// Après le jalon qui l'a armée: laisser le moment se poser d'abord.
    private static let delayAfterArm: TimeInterval = 1.2
    /// À chaque retour au premier plan.
    private static let delayOnForeground: TimeInterval = 0.8
    /// Au lancement à froid, pour une demande armée lors d'une session passée.
    private static let delayOnLaunch: TimeInterval = 2.0

    /// Remet une demande armée à StoreKit dès que l'écran est libre.
    ///
    /// 🔴 L'ANCIENNE VERSION N'ESSAYAIT QU'UNE FOIS, 0,6 s PLUS TARD, et sur un
    /// écran occupé elle écrivait « skipping the native ask » et s'arrêtait là —
    /// alors que le créneau avait déjà été débité et le jeton consommé. Une
    /// tentative qui tombe sur un écran occupé ne change maintenant rien du
    /// tout: elle renvoie `false`, la demande reste armée, et on revient.
    ///
    /// `requestReview` est SILENCIEUSEMENT ignoré quand un autre écran est
    /// présenté. C'est ce qui rendait la demande invisible et faisait croire
    /// qu'elle avait eu lieu.
    private func deliverArmedRequest(after delay: TimeInterval) {
        guard manager.hasArmedRequest else { return }
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(delay))
            for attempt in 0..<RateAppManager.Config.busyRetryAttempts {
                if attempt > 0 {
                    try? await Task.sleep(for: .seconds(RateAppManager.Config.busyRetryIntervalSeconds))
                }
                guard manager.hasArmedRequest else { return }
                if manager.deliverArmedRequest(request: { requestReview() }) { return }
            }
            OshiLog.ui.info("⭐️ RateApp: écran occupé — demande toujours armée pour la prochaine occasion")
        }
    }
}
