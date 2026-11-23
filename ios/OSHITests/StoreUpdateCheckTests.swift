//
//  StoreUpdateCheckTests.swift
//  OSHI — the "Update available" popup driven by the App Store lookup.
//
//  The assertions that matter most are the negative ones: a build NEWER than
//  the live one (App Review, TestFlight) or EQUAL to it never prompts, and no
//  failure of any kind produces a prompt.
//

import XCTest
@testable import OSHI

private final class CallCounter { var count = 0 }

@MainActor
final class StoreUpdateCheckTests: XCTestCase {

    private var suiteName: String!
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "StoreUpdateCheckTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    // MARK: - Version comparison

    func testNumericNotLexicographic() {
        XCTAssertEqual(StoreUpdateCheck.versionToAnnounce(installed: "1.0.9", live: "1.0.10"), "1.0.10")
        XCTAssertEqual(StoreUpdateCheck.versionToAnnounce(installed: "1.0.46", live: "1.0.47"), "1.0.47")
        XCTAssertEqual(StoreUpdateCheck.versionToAnnounce(installed: "1.0", live: "1.0.1"), "1.0.1")
        XCTAssertEqual(StoreUpdateCheck.versionToAnnounce(installed: "1.9.9", live: "2.0"), "2.0")
    }

    func testEqualNeverPrompts() {
        XCTAssertNil(StoreUpdateCheck.versionToAnnounce(installed: "1.0.43", live: "1.0.43"))
        XCTAssertNil(StoreUpdateCheck.versionToAnnounce(installed: "1.0", live: "1.0.0"))
    }

    func testReviewBuildNewerThanLiveNeverPrompts() {
        // App Review / TestFlight: the installed build is AHEAD of the store.
        XCTAssertNil(StoreUpdateCheck.versionToAnnounce(installed: "1.0.47", live: "1.0.43"))
        XCTAssertNil(StoreUpdateCheck.versionToAnnounce(installed: "1.0.10", live: "1.0.9"))
        XCTAssertNil(StoreUpdateCheck.versionToAnnounce(installed: "2.0", live: "1.99.99"))
    }

    func testUnreadableNeverPrompts() {
        XCTAssertNil(StoreUpdateCheck.versionToAnnounce(installed: "", live: "1.0.50"))
        XCTAssertNil(StoreUpdateCheck.versionToAnnounce(installed: "1.0.43", live: "1.0.50-beta"))
        XCTAssertNil(StoreUpdateCheck.versionToAnnounce(installed: "1.0.43", live: nil))
        XCTAssertNil(StoreUpdateCheck.versionToAnnounce(installed: "1.0.43", live: "latest"))
    }

    // MARK: - Lookup parsing

    private func json(_ s: String) -> Data { Data(s.utf8) }

    func testParseLiveVersion() {
        let body = json(#"{"resultCount":1,"results":[{"kind":"software","version":"1.0.47","trackId":6753926350}]}"#)
        XCTAssertEqual(StoreUpdateCheck.parse(body), .live("1.0.47"))
    }

    func testParseEmptyResultsIsNotListed() {
        XCTAssertEqual(StoreUpdateCheck.parse(json(#"{"resultCount":0,"results":[]}"#)), .notListed)
    }

    func testParseMalformedIsUnusable() {
        XCTAssertEqual(StoreUpdateCheck.parse(json("not json")), .unusable)
        XCTAssertEqual(StoreUpdateCheck.parse(json("[]")), .unusable)
        XCTAssertEqual(StoreUpdateCheck.parse(json(#"{"resultCount":1}"#)), .unusable)
        XCTAssertEqual(StoreUpdateCheck.parse(Data()), .unusable)
        XCTAssertEqual(StoreUpdateCheck.parse(json(#"{"errorMessage":"Invalid value(s) for key(s): [country]"}"#)), .unusable)
        XCTAssertEqual(StoreUpdateCheck.parse(json(#"{"results":[{"version":42,"trackId":6753926350}]}"#)), .unusable)
        XCTAssertEqual(StoreUpdateCheck.parse(json(#"{"results":[{"trackId":6753926350}]}"#)), .unusable)
        XCTAssertEqual(StoreUpdateCheck.parse(json(#"{"results":[{"version":"v2","trackId":6753926350}]}"#)), .unusable)
    }

    func testParseOtherAppIsUnusable() {
        let body = json(#"{"resultCount":1,"results":[{"version":"9.0","trackId":1}]}"#)
        XCTAssertEqual(StoreUpdateCheck.parse(body), .unusable)
        XCTAssertEqual(StoreUpdateCheck.parse(body, expectedTrackID: nil), .live("9.0"))
    }

    // MARK: - Request

    func testLookupURLCarriesOnlyBundleAndCountry() throws {
        let url = try XCTUnwrap(StoreUpdateCheck.lookupURL(bundleID: "Moriceau-lab.Genesis", country: "FR"))
        let items = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems)
        XCTAssertEqual(url.host, "itunes.apple.com")
        XCTAssertEqual(url.path, "/lookup")
        XCTAssertEqual(Set(items.map(\.name)), ["bundleId", "country"])
        XCTAssertEqual(items.first { $0.name == "country" }?.value, "fr")
        let noCountry = try XCTUnwrap(StoreUpdateCheck.lookupURL(bundleID: "Moriceau-lab.Genesis", country: "419"))
        XCTAssertFalse(noCountry.absoluteString.contains("country"))
    }

    func testCountryStorefrontThenLocale() {
        XCTAssertEqual(StoreUpdateCheck.country(storefrontAlpha3: "FRA", localeRegion: "US"), "fr")
        XCTAssertEqual(StoreUpdateCheck.country(storefrontAlpha3: "che", localeRegion: nil), "ch")
        XCTAssertEqual(StoreUpdateCheck.country(storefrontAlpha3: nil, localeRegion: "DE"), "de")
        XCTAssertEqual(StoreUpdateCheck.country(storefrontAlpha3: "ZZZ", localeRegion: "JP"), "jp")
        XCTAssertNil(StoreUpdateCheck.country(storefrontAlpha3: nil, localeRegion: "419"))
        XCTAssertNil(StoreUpdateCheck.country(storefrontAlpha3: nil, localeRegion: nil))
    }

    func testCheckInterval() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        XCTAssertTrue(StoreUpdateCheck.isCheckDue(lastCheck: nil, now: now))
        XCTAssertFalse(StoreUpdateCheck.isCheckDue(lastCheck: now.addingTimeInterval(-11 * 3600), now: now))
        XCTAssertTrue(StoreUpdateCheck.isCheckDue(lastCheck: now.addingTimeInterval(-12 * 3600), now: now))
        XCTAssertTrue(StoreUpdateCheck.isCheckDue(lastCheck: now.addingTimeInterval(3600), now: now), "clock moved back")
    }

    // MARK: - Snooze

    func testSnoozeHidesThatVersionForThreeDays() {
        let snooze = StoreUpdateSnooze(defaults: defaults)
        let t0 = Date(timeIntervalSince1970: 1_800_000_000)
        snooze.snooze("1.0.47", now: t0)
        XCTAssertTrue(snooze.isSnoozed("1.0.47", now: t0))
        XCTAssertTrue(snooze.isSnoozed("1.0.47", now: t0.addingTimeInterval(3 * 86400 - 1)))
        XCTAssertFalse(snooze.isSnoozed("1.0.47", now: t0.addingTimeInterval(3 * 86400)))
    }

    func testSnoozeDoesNotHideANewerVersion() {
        let snooze = StoreUpdateSnooze(defaults: defaults)
        let t0 = Date(timeIntervalSince1970: 1_800_000_000)
        snooze.snooze("1.0.47", now: t0)
        XCTAssertFalse(snooze.isSnoozed("1.0.48", now: t0.addingTimeInterval(60)))
    }

    func testSnoozeSurvivesNoClockRollbackTrap() {
        let snooze = StoreUpdateSnooze(defaults: defaults)
        let t0 = Date(timeIntervalSince1970: 1_800_000_000)
        snooze.snooze("1.0.47", now: t0)
        // Clock set a year back: must not hide the update for a year.
        XCTAssertFalse(snooze.isSnoozed("1.0.47", now: t0.addingTimeInterval(-365 * 86400)))
    }

    // MARK: - Gating

    func testGating() {
        XCTAssertTrue(StoreUpdateCheck.mayPresent(.init()))
        XCTAssertFalse(StoreUpdateCheck.mayPresent(.init(mainUIVisible: false)), "onboarding / lock / splash")
        XCTAssertFalse(StoreUpdateCheck.mayPresent(.init(callActive: true)))
        XCTAssertFalse(StoreUpdateCheck.mayPresent(.init(otherUIPresented: true)))
        XCTAssertFalse(StoreUpdateCheck.mayPresent(.init(supportBannerShowing: true)))
    }

    // MARK: - Checker end to end (injected network)

    private func response(_ status: Int) -> HTTPURLResponse {
        HTTPURLResponse(url: URL(string: "https://itunes.apple.com/lookup")!, statusCode: status,
                        httpVersion: nil, headerFields: nil)!
    }

    private func makeChecker(installed: String, now: @escaping () -> Date,
                             body: Data, status: Int = 200,
                             calls: CallCounter? = nil,
                             requested: ((URL) -> Void)? = nil) -> StoreUpdateChecker {
        let resp = response(status)
        return StoreUpdateChecker(defaults: defaults, installedVersion: installed, bundleID: "Moriceau-lab.Genesis",
                                  now: now,
                                  fetch: { url in
                                      calls?.count += 1
                                      requested?(url)
                                      return (body, resp)
                                  },
                                  countryProvider: { "fr" })
    }

    private let liveBody = Data(#"{"resultCount":1,"results":[{"version":"1.0.47","trackId":6753926350}]}"#.utf8)

    func testCheckerAnnouncesNewerAndLaterSnoozes() async {
        var clock = Date(timeIntervalSince1970: 1_800_000_000)
        var requestedURL: URL?
        let checker = makeChecker(installed: "1.0.46", now: { clock }, body: liveBody,
                                  requested: { requestedURL = $0 })
        await checker.checkIfDue()
        XCTAssertEqual(checker.pendingAnnouncement, "1.0.47")
        XCTAssertEqual(requestedURL?.query, "bundleId=Moriceau-lab.Genesis&country=fr")
        checker.later()
        XCTAssertNil(checker.pendingAnnouncement)
        clock = clock.addingTimeInterval(3 * 86400 + 1)
        XCTAssertEqual(checker.pendingAnnouncement, "1.0.47")
    }

    func testCheckerReviewBuildSeesNothing() async {
        let checker = makeChecker(installed: "1.0.48", now: Date.init, body: liveBody)
        await checker.checkIfDue()
        XCTAssertNil(checker.pendingAnnouncement)
    }

    func testCheckerEqualSeesNothing() async {
        let checker = makeChecker(installed: "1.0.47", now: Date.init, body: liveBody)
        await checker.checkIfDue()
        XCTAssertNil(checker.pendingAnnouncement)
    }

    func testCheckerOncePerLaunchAndTwelveHourCache() async {
        let calls = CallCounter()
        let t0 = Date(timeIntervalSince1970: 1_800_000_000)
        let first = makeChecker(installed: "1.0.46", now: { t0 }, body: liveBody, calls: calls)
        await first.checkIfDue()
        await first.checkIfDue()
        XCTAssertEqual(calls.count, 1, "once per launch")

        // New launch 1 h later: cached answer, no network, still announced.
        let second = makeChecker(installed: "1.0.46", now: { t0.addingTimeInterval(3600) }, body: liveBody, calls: calls)
        await second.checkIfDue()
        XCTAssertEqual(calls.count, 1)
        XCTAssertEqual(second.pendingAnnouncement, "1.0.47")

        // New launch 13 h later: network again.
        let third = makeChecker(installed: "1.0.46", now: { t0.addingTimeInterval(13 * 3600) }, body: liveBody, calls: calls)
        await third.checkIfDue()
        XCTAssertEqual(calls.count, 2)
    }

    func testCheckerFailuresAreSilentAndKeepLastAnswer() async {
        let t0 = Date(timeIntervalSince1970: 1_800_000_000)
        let good = makeChecker(installed: "1.0.46", now: { t0 }, body: liveBody)
        await good.checkIfDue()
        XCTAssertEqual(good.pendingAnnouncement, "1.0.47")

        let later = t0.addingTimeInterval(13 * 3600)
        let malformed = makeChecker(installed: "1.0.46", now: { later }, body: Data("<html>".utf8))
        await malformed.checkIfDue()
        XCTAssertEqual(malformed.pendingAnnouncement, "1.0.47", "a garbage body does not erase a good answer")

        let serverError = makeChecker(installed: "1.0.46", now: { later }, body: liveBody, status: 503)
        await serverError.checkIfDue()
        XCTAssertEqual(serverError.pendingAnnouncement, "1.0.47")

        let offline = StoreUpdateChecker(defaults: defaults, installedVersion: "1.0.46", bundleID: "x",
                                         now: { later },
                                         fetch: { _ in throw URLError(.notConnectedToInternet) },
                                         countryProvider: { nil })
        await offline.checkIfDue()
        XCTAssertEqual(offline.pendingAnnouncement, "1.0.47")
    }

    func testCheckerNoResultsClearsAnnouncement() async {
        let t0 = Date(timeIntervalSince1970: 1_800_000_000)
        let good = makeChecker(installed: "1.0.46", now: { t0 }, body: liveBody)
        await good.checkIfDue()
        let gone = makeChecker(installed: "1.0.46", now: { t0.addingTimeInterval(13 * 3600) },
                               body: Data(#"{"resultCount":0,"results":[]}"#.utf8))
        await gone.checkIfDue()
        XCTAssertNil(gone.pendingAnnouncement)
    }

    func testFreshInstallWithNoNetworkShowsNothing() async {
        let checker = StoreUpdateChecker(defaults: defaults, installedVersion: "1.0.46", bundleID: "x",
                                         now: Date.init,
                                         fetch: { _ in throw URLError(.timedOut) },
                                         countryProvider: { nil })
        await checker.checkIfDue()
        XCTAssertNil(checker.pendingAnnouncement)
    }

    func testUpdateTappedHidesForThisLaunchOnly() async {
        let t0 = Date(timeIntervalSince1970: 1_800_000_000)
        let checker = makeChecker(installed: "1.0.46", now: { t0 }, body: liveBody)
        await checker.checkIfDue()
        checker.updateTapped()
        XCTAssertNil(checker.pendingAnnouncement)
        let nextLaunch = makeChecker(installed: "1.0.46", now: { t0.addingTimeInterval(60) }, body: liveBody)
        XCTAssertEqual(nextLaunch.pendingAnnouncement, "1.0.47")
    }

    // MARK: - Strings

    func testStringsExistInEveryLanguage() throws {
        let keys = ["upd_available_title", "upd_available_body", "upd_available_update", "upd_available_later"]
        let bundle = Bundle(for: StoreUpdateChecker.self)
        let lprojs = bundle.paths(forResourcesOfType: "lproj", inDirectory: nil)
            .filter { !$0.hasSuffix("Base.lproj") }
        XCTAssertGreaterThanOrEqual(lprojs.count, 34)
        for path in lprojs {
            let strings = try XCTUnwrap(NSDictionary(contentsOfFile: path + "/Localizable.strings") as? [String: String],
                                        path)
            for key in keys {
                let value = try XCTUnwrap(strings[key], "\(key) missing in \(path)")
                XCTAssertFalse(value.isEmpty)
            }
            XCTAssertTrue(strings["upd_available_body"]?.contains("%@") == true, path)
        }
    }
}
