//
//  MailUnreadRefresherTests.swift
//  OSHITests
//
//  __MAIL_UNREAD_REFRESH_2026_11_22__ The unread-mail count must follow the server
//  without the Email screen being opened: after flight mode, on foreground, on
//  network return, every minute while open, and on a mail push.
//

import XCTest
@testable import OSHI

@MainActor
private final class FakeMailSource: MailUnreadSource {
    var canSign = true
    var hasSyncedOnce = false
    var serverRows: [OSHIMailMessage] = []
    var localRows: [OSHIMailMessage] = []
    var statusRouteDeployed = true
    var failNext: Error?
    var listCalls = 0
    var statusCalls = 0
    var duringList: (() async -> Void)?

    var localInboxFingerprint: String {
        OSHIMailInboxStatus.fingerprint(localRows.map { ($0.id, $0.seen, $0.folder) })
    }
    var unreadInboxMessages: [OSHIMailMessage] {
        localRows.filter { $0.folder == "inbox" && !$0.seen }
    }

    func fetchInboxStatus() async throws -> OSHIMailInboxStatus? {
        statusCalls += 1
        if let e = failNext { failNext = nil; throw e }
        guard statusRouteDeployed else { return nil }
        return OSHIMailInboxStatus(
            inboxUnread: serverRows.filter { $0.folder == "inbox" && !$0.seen }.count,
            inboxTotal: serverRows.count, latestInboxId: serverRows.last?.id, latestInboxAt: nil,
            fingerprint: OSHIMailInboxStatus.fingerprint(serverRows.map { ($0.id, $0.seen, $0.folder) }))
    }

    func refreshInbox() async throws -> [OSHIMailMessage] {
        listCalls += 1
        if let hook = duringList { duringList = nil; await hook() }
        if let e = failNext { failNext = nil; throw e }
        localRows = serverRows
        hasSyncedOnce = true
        return localRows
    }
}

private func mail(_ id: String, seen: Bool = false, folder: String = "inbox") -> OSHIMailMessage {
    OSHIMailMessage(id: id, receivedAt: Date(), bytes: 1, seen: seen, folder: folder,
                    deliveredTo: "me", envelope: nil)
}

@MainActor
final class MailUnreadRefresherTests: XCTestCase {

    private var source: FakeMailSource!
    private var center: NotificationCenter!
    private var defaults: UserDefaults!
    private var suiteName: String!
    private var now = Date(timeIntervalSince1970: 1_000_000)
    private var active = true
    private var online = true
    private var announced: [[String]] = []
    private var badgeSyncs = 0
    private var sleeps: [TimeInterval] = []
    private var sleepBudget = 0

    override func setUp() {
        super.setUp()
        source = FakeMailSource()
        center = NotificationCenter()
        suiteName = "MailUnreadRefresherTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        now = Date(timeIntervalSince1970: 1_000_000)
        active = true; online = true
        announced = []; badgeSyncs = 0; sleeps = []; sleepBudget = 0
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    private func makeRefresher() -> MailUnreadRefresher {
        let env = MailUnreadRefresher.Environment(
            isAppActive: { [unowned self] in self.active },
            isOnline: { [unowned self] in self.online },
            now: { [unowned self] in self.now },
            defaults: defaults,
            announce: { [unowned self] ids in self.announced.append(ids) },
            syncBadge: { [unowned self] in self.badgeSyncs += 1 },
            sleep: { [unowned self] s in
                await MainActor.run { self.sleeps.append(s) }
                let left = await MainActor.run { () -> Int in self.sleepBudget -= 1; return self.sleepBudget }
                if left < 0 { throw CancellationError() }
                await Task.yield()
            })
        return MailUnreadRefresher(source: source, environment: env, center: center)
    }

    private func waitUntil(_ cond: @escaping () -> Bool, file: StaticString = #filePath, line: UInt = #line) async {
        for _ in 0..<200 {
            if cond() { return }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        XCTFail("condition never became true", file: file, line: line)
    }

    // MARK: Pure policy

    func testBackoffDoublesFromOneMinuteAndCapsAtFifteen() {
        var b = MailRefreshBackoff()
        XCTAssertEqual(b.nextDelay, 60)
        var seen: [TimeInterval] = []
        for _ in 0..<6 { b.recordFailure(); seen.append(b.nextDelay) }
        XCTAssertEqual(seen, [120, 240, 480, 900, 900, 900])
        b.recordSuccess()
        XCTAssertEqual(b.nextDelay, 60)
    }

    func testGateSpacesForegroundButNeverAPushOrNetworkReturn() {
        let t0 = Date(timeIntervalSince1970: 0)
        XCTAssertEqual(MailRefreshGate.decide(.foreground, inFlight: false, lastStartedAt: nil, now: t0), .run)
        XCTAssertEqual(MailRefreshGate.decide(.foreground, inFlight: false, lastStartedAt: t0, now: t0.addingTimeInterval(3)), .skip)
        XCTAssertEqual(MailRefreshGate.decide(.foreground, inFlight: false, lastStartedAt: t0, now: t0.addingTimeInterval(11)), .run)
        XCTAssertEqual(MailRefreshGate.decide(.push, inFlight: false, lastStartedAt: t0, now: t0.addingTimeInterval(1)), .run)
        XCTAssertEqual(MailRefreshGate.decide(.networkRestored, inFlight: false, lastStartedAt: t0, now: t0), .run)
        XCTAssertEqual(MailRefreshGate.decide(.periodic, inFlight: false, lastStartedAt: t0, now: t0), .run)
        XCTAssertEqual(MailRefreshGate.decide(.push, inFlight: true, lastStartedAt: t0, now: t0), .queueAfterInFlight)
        XCTAssertEqual(MailRefreshGate.decide(.foreground, inFlight: true, lastStartedAt: t0, now: t0), .skip)
    }

    func testLedgerAnnouncesNothingBeforeItsFirstAbsorbThenOnlyNewIds() {
        var l = MailArrivalLedger()
        XCTAssertEqual(l.unannounced(["a", "b"]), [], "first run after install must not announce old mail")
        l.absorb(["a", "b"])
        XCTAssertEqual(l.unannounced(["a", "b", "c"]), ["c"])
        l.absorb(["c", "c"])
        XCTAssertEqual(l.known, ["a", "b", "c"])
        for i in 0..<(MailArrivalLedger.capacity + 10) { l.absorb(["x\(i)"]) }
        XCTAssertEqual(l.known.count, MailArrivalLedger.capacity)
        XCTAssertFalse(l.known.contains("a"), "oldest ids are dropped first")
    }

    func testLedgerPersists() {
        var l = MailArrivalLedger()
        l.absorb(["a"])
        l.save(defaults)
        XCTAssertEqual(MailArrivalLedger.load(defaults), l)
    }

    func testFingerprintMatchesServerVector() {
        // Same vector as ServerPatches/mail_inbox_status/inbox_status.test.js
        // ("aa:0:inbox\nbb:1:inbox"), order-independent.
        let fp = OSHIMailInboxStatus.fingerprint([("bb", true, "inbox"), ("aa", false, "inbox")])
        XCTAssertEqual(fp, "8a684663e8774d6fe79fd0ec5d084e30f7a4728595c129ad4c2ab6e4f0f79c67")
    }

    func testChatsTabBadgeCountsMail() {
        XCTAssertEqual(ChatsTabBadge.count(oneToOne: 0, groups: 0, mailInbox: 3), 3)
        XCTAssertEqual(ChatsTabBadge.count(oneToOne: 2, groups: 1, mailInbox: 3), 6)
        XCTAssertEqual(ChatsTabBadge.count(oneToOne: -1, groups: 0, mailInbox: 0), 0)
    }

    func testClientUnreadCountsInboxUnseenOnly() {
        #if DEBUG
        let client = OSHIMailClient()
        client.installDebugFixtures(
            account: try! JSONDecoder().decode(OSHIMailAccount.self, from: Data(Self.accountJSON.utf8)),
            messages: [mail("1"), mail("2", seen: true), mail("3", folder: "archive"), mail("4")],
            bodies: [:])
        XCTAssertEqual(client.unreadInboxCount, 2)
        XCTAssertEqual(Set(client.unreadInboxMessages.map(\.id)), ["1", "4"])
        #endif
    }

    // MARK: Checks

    func testFirstCheckListsWithoutAskingStatus() async {
        source.serverRows = [mail("a")]
        let r = makeRefresher()
        let outcome = await r.trigger(.launch)
        XCTAssertEqual(outcome, .refreshed)
        XCTAssertEqual(source.statusCalls, 0)
        XCTAssertEqual(source.listCalls, 1)
        XCTAssertEqual(source.unreadInboxMessages.count, 1)
        XCTAssertEqual(badgeSyncs, 1)
    }

    func testUnchangedFingerprintSkipsTheListChangedOneFetchesIt() async {
        source.serverRows = [mail("a")]
        let r = makeRefresher()
        await r.trigger(.launch)
        let o1 = await r.trigger(.push)
        XCTAssertEqual(o1, .unchanged)
        XCTAssertEqual(source.listCalls, 1)
        // Mail arrives while the phone sits on the Messages view.
        source.serverRows.append(mail("b"))
        let o2 = await r.trigger(.periodic)
        XCTAssertEqual(o2, .refreshed)
        XCTAssertEqual(source.listCalls, 2)
        XCTAssertEqual(source.unreadInboxMessages.count, 2)
        // Read on another device: the fingerprint moves too.
        source.serverRows[0] = mail("a", seen: true)
        _ = await r.trigger(.periodic)
        XCTAssertEqual(source.unreadInboxMessages.map(\.id), ["b"])
    }

    func testMissingStatusRouteFallsBackToListingEveryTime() async {
        source.statusRouteDeployed = false
        let r = makeRefresher()
        await r.trigger(.launch)
        await r.trigger(.push)
        await r.trigger(.push)
        XCTAssertEqual(source.statusCalls, 1, "a 404 not-found is remembered")
        XCTAssertEqual(source.listCalls, 3)
        XCTAssertTrue(r.statusRouteMissing)
    }

    func testFlightModeThenReconnect() async {
        source.serverRows = [mail("a")]
        let r = makeRefresher()
        // Launched (or foregrounded) still offline: nothing asked, nothing counted as failure.
        online = false
        let o1 = await r.trigger(.launch)
        XCTAssertEqual(o1, .skippedOffline)
        XCTAssertEqual(source.listCalls, 0)
        // A request that fails on the way down backs off…
        online = true
        source.failNext = OSHIMailError.transport(URLError(.notConnectedToInternet))
        let o2 = await r.trigger(.foreground)
        XCTAssertEqual(o2, .failed)
        XCTAssertEqual(r.backoff.consecutiveFailures, 1)
        // …and the network coming back asks at once and resets the backoff.
        r.startObserving()
        center.post(name: .networkBecameReachable, object: nil)
        await waitUntil { self.source.listCalls >= 2 && !r.inFlight }
        XCTAssertEqual(source.unreadInboxMessages.map(\.id), ["a"])
        XCTAssertEqual(r.backoff.consecutiveFailures, 0)
        r.stopObserving()
    }

    func testForegroundIsSpacedButAMailPushIsNot() async {
        let r = makeRefresher()
        await r.trigger(.launch)
        now += 2
        let fg = await r.trigger(.foreground)
        XCTAssertEqual(fg, .skippedGate)
        let push = await r.trigger(.push)
        XCTAssertNotEqual(push, .skippedGate)
        now += 20
        let fg2 = await r.trigger(.foreground)
        XCTAssertNotEqual(fg2, .skippedGate)
    }

    func testPushDuringACheckRunsOneMoreAfterIt() async {
        source.serverRows = [mail("a")]
        let r = makeRefresher()
        source.duringList = { [unowned self] in
            // The mail lands after the in-flight request read the server.
            self.source.serverRows.append(mail("b"))
            let o = await r.trigger(.push)
            XCTAssertEqual(o, .queued)
        }
        await r.trigger(.launch)
        XCTAssertEqual(r.checksStarted, 2, "the queued push re-ran")
        XCTAssertEqual(Set(source.unreadInboxMessages.map(\.id)), ["a", "b"])
    }

    func testNoMailboxStopsPeriodicButNotExplicitEvents() async {
        source.failNext = OSHIMailError.noMailbox
        let r = makeRefresher()
        let o = await r.trigger(.launch)
        XCTAssertEqual(o, .noMailbox)
        XCTAssertTrue(r.noMailbox)
        XCTAssertEqual(r.backoff.consecutiveFailures, 0, "no mailbox is not a failure")
        let p = await r.trigger(.periodic)
        XCTAssertEqual(p, .noMailbox)
        XCTAssertEqual(source.listCalls, 1)
        now += 60
        source.serverRows = [mail("a")]
        let fg = await r.trigger(.foreground)
        XCTAssertEqual(fg, .refreshed, "a mailbox made since is picked up")
        XCTAssertFalse(r.noMailbox)
    }

    func testNoIdentityAsksNothing() async {
        source.canSign = false
        let r = makeRefresher()
        let o = await r.trigger(.launch)
        XCTAssertEqual(o, .skippedNoIdentity)
        XCTAssertEqual(source.listCalls + source.statusCalls, 0)
    }

    // MARK: Lifecycle

    func testBecomingActiveChecksAndStartsTheMinuteTimer() async {
        source.serverRows = [mail("a")]
        let r = makeRefresher()
        active = false
        r.startObserving()
        XCTAssertFalse(r.isPeriodicRunning, "no timer while in the background")
        sleepBudget = 0
        active = true
        center.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        await waitUntil { self.source.listCalls >= 1 }
        await waitUntil { !self.sleeps.isEmpty }
        XCTAssertEqual(sleeps.first, 60)
        center.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        XCTAssertFalse(r.isPeriodicRunning)
        r.stopObserving()
    }

    func testPeriodicDelaysFollowTheBackoff() async {
        let r = makeRefresher()
        await r.trigger(.launch)
        source.failNext = OSHIMailError.transport(URLError(.timedOut))
        sleepBudget = 2
        r.startPeriodic()
        await waitUntil { self.sleeps.count >= 3 }
        // tick 1 after 60 s fails (status call throws) → next 120 s; tick 2 succeeds → 60 s.
        XCTAssertEqual(Array(sleeps.prefix(3)), [60, 120, 60])
        r.stopPeriodic()
    }

    func testMailPushNotificationTriggersACheck() async {
        let r = makeRefresher()
        r.startObserving()
        center.post(name: .oshiMailPushReceived, object: nil)
        await waitUntil { self.source.listCalls >= 1 }
        r.stopObserving()
    }

    // MARK: Banner

    func testBackgroundCheckAnnouncesOnlyMailNothingCoveredYet() async {
        source.serverRows = [mail("old")]
        let r = makeRefresher()
        // First ever check (in the background): absorbs silently.
        active = false
        await r.trigger(.backgroundFetch)
        XCTAssertEqual(announced, [])
        // New mail found by a background check: one generic banner for it.
        source.serverRows.append(mail("new1"))
        await r.trigger(.backgroundFetch)
        XCTAssertEqual(announced, [["new1"]])
        // Same state again: nothing more.
        await r.trigger(.backgroundFetch)
        XCTAssertEqual(announced.count, 1)
        // In the foreground new mail is counted on screen, never bannered locally…
        active = true
        source.serverRows.append(mail("new2"))
        await r.trigger(.push)
        XCTAssertEqual(announced.count, 1)
        // …and is not re-announced once back in the background.
        active = false
        await r.trigger(.backgroundFetch)
        XCTAssertEqual(announced.count, 1)
    }

    func testLedgerSurvivesARelaunch() async {
        source.serverRows = [mail("a")]
        await makeRefresher().trigger(.launch)
        active = false
        let relaunched = makeRefresher()
        await relaunched.trigger(.backgroundFetch)
        XCTAssertEqual(announced, [], "mail seen before the relaunch is not news")
    }

    private static let accountJSON = """
    {"address":"t@mail.oshi-messenger.com","localpart":"t","aliases":[],"aliasLimit":10,"createdAt":"2026-11-22T00:00:00Z",
     "storage":{"used":0,"quota":1,"remaining":1,"messages":0},
     "sending":{"sent":0,"quota":150,"remaining":150,"month":"2026-11"},
     "drive":{"used":0,"quota":1,"remaining":1,"files":0}}
    """
}

// MARK: - __MAIL_OFFLINE_NOT_SETUP_2026_11_25__
//
// Reported: "When I don't have network the mail shows a box to input a name even when
// I have a mailbox." `account` was memory-only, so a cold start offline fell through
// to the claim-an-address form. The account is now cached per identity and only a
// server `no-mailbox` may clear it or lead to the setup form.

private final class MemoryAccountStore: OSHIMailAccountStore {
    var items: [String: OSHIMailAccount] = [:]
    func load(scope: String) -> OSHIMailAccount? { items[scope] }
    func save(_ account: OSHIMailAccount, scope: String) { items[scope] = account }
    func clear(scope: String) { items[scope] = nil }
}

@MainActor
final class MailOfflineAccountTests: XCTestCase {

    private func account(_ local: String = "me") -> OSHIMailAccount {
        let json = """
        {"address":"\(local)@mail.oshi-messenger.com","localpart":"\(local)","aliases":[],"aliasLimit":5,
         "createdAt":"2026-01-01T00:00:00Z",
         "storage":{"used":0,"quota":1,"remaining":1,"messages":0},
         "sending":{"sent":0,"quota":150,"remaining":150,"month":"2026-11"},
         "drive":{"used":0,"quota":1,"remaining":1,"files":0}}
        """
        return try! JSONDecoder().decode(OSHIMailAccount.self, from: Data(json.utf8))
    }

    // Decision: setup vs unreachable vs mailbox

    func testOfflineWithoutCachedAccountIsUnreachableNotSetup() {
        XCTAssertEqual(MailScreenState.resolve(isLoading: false, hasAccount: false, confirmedNoMailbox: false),
                       .unreachable)
    }

    func testOnlyAConfirmedNoMailboxShowsSetup() {
        XCTAssertEqual(MailScreenState.resolve(isLoading: false, hasAccount: false, confirmedNoMailbox: true),
                       .setup)
    }

    func testKnownAccountShowsMailboxEvenWhileLoadingOrAfterFailure() {
        XCTAssertEqual(MailScreenState.resolve(isLoading: true, hasAccount: true, confirmedNoMailbox: false), .mailbox)
        XCTAssertEqual(MailScreenState.resolve(isLoading: false, hasAccount: true, confirmedNoMailbox: false), .mailbox)
    }

    func testLoadingWithoutAccountIsLoading() {
        XCTAssertEqual(MailScreenState.resolve(isLoading: true, hasAccount: false, confirmedNoMailbox: false), .loading)
    }

    // Cache seed / clear

    func testColdStartSeedsAccountFromCache() {
        let store = MemoryAccountStore()
        store.items["idA"] = account("alice")
        let client = OSHIMailClient(accountStore: store)
        XCTAssertNil(client.account)
        client.seedAccountFromCache(scope: "idA")
        XCTAssertEqual(client.account?.address, "alice@mail.oshi-messenger.com")
    }

    func testSuccessfulFetchIsCachedForTheIdentity() {
        let store = MemoryAccountStore()
        let client = OSHIMailClient(accountStore: store)
        client.seedAccountFromCache(scope: "idA")
        client.applyAccountFetch(.success(account("alice")))
        XCTAssertEqual(store.items["idA"]?.address, "alice@mail.oshi-messenger.com")
    }

    func testOfflineFailureKeepsCachedAccountAndMarksStale() {
        let store = MemoryAccountStore()
        store.items["idA"] = account("alice")
        let client = OSHIMailClient(accountStore: store)
        client.seedAccountFromCache(scope: "idA")
        client.applyAccountFetch(.failure(OSHIMailError.transport(URLError(.notConnectedToInternet))))
        client.applyAccountFetch(.failure(OSHIMailError.server(status: 503, code: "unavailable")))
        XCTAssertNotNil(client.account, "offline/5xx must not forget the mailbox")
        XCTAssertNotNil(store.items["idA"])
        XCTAssertTrue(client.lastRefreshFailed, "the badge must read 'could not refresh', not 'up to date'")
    }

    func testNoMailboxClearsAccountAndCache() {
        let store = MemoryAccountStore()
        store.items["idA"] = account("alice")
        let client = OSHIMailClient(accountStore: store)
        client.seedAccountFromCache(scope: "idA")
        client.applyAccountFetch(.failure(OSHIMailError.noMailbox))
        XCTAssertNil(client.account)
        XCTAssertNil(store.items["idA"])
    }

    func testAnotherIdentityNeverSeesTheCachedAccount() {
        let store = MemoryAccountStore()
        store.items["idA"] = account("alice")
        let client = OSHIMailClient(accountStore: store)
        client.seedAccountFromCache(scope: "idA")
        XCTAssertNotNil(client.account)
        client.seedAccountFromCache(scope: "idB")
        XCTAssertNil(client.account, "switching identity drops the previous identity's account")
        client.applyAccountFetch(.failure(OSHIMailError.noMailbox))
        XCTAssertNotNil(store.items["idA"], "idB's no-mailbox must not clear idA's cache")
    }

    func testKeychainStoreRoundTripIsScopedPerIdentity() {
        let store = OSHIMailKeychainAccountStore()
        let a = "test-scope-A-\(UUID().uuidString)", b = "test-scope-B-\(UUID().uuidString)"
        defer { store.clear(scope: a); store.clear(scope: b) }
        XCTAssertNotEqual(OSHIMailKeychainAccountStore.itemAccount(scope: a),
                          OSHIMailKeychainAccountStore.itemAccount(scope: b))
        store.save(account("alice"), scope: a)
        XCTAssertEqual(store.load(scope: a)?.address, "alice@mail.oshi-messenger.com")
        XCTAssertNil(store.load(scope: b))
        store.clear(scope: a)
        XCTAssertNil(store.load(scope: a))
    }
}
