//
//  CrossProcessLockSuspensionTests.swift
//  OSHITests
//
//  🔴 __DEAD10CC_2026_11_26__ 16 RunningBoard kills (SIGKILL 0xdead10cc) on the
//  owner's iPhone, every one with the `com.oshi.xproclock.v2_session_*` queue
//  frozen inside a keychain call while holding the `flock`. These tests pin the
//  contract that removes that: a hold only exists under a background assertion,
//  an expired assertion drops the kernel lock before suspension, a revoked hold
//  refuses to persist, and nothing leaks a descriptor — while two processes
//  (modelled as two instances = two open file descriptions) still exclude.
//

import XCTest
@testable import OSHI

final class CrossProcessLockSuspensionTests: XCTestCase {

    private var savedProvider: CrossProcessLock.AssertionProvider!
    private var dir: URL!

    override func setUp() {
        super.setUp()
        savedProvider = CrossProcessLock.assertionProvider
        dir = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("oshi-dead10cc-tests-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDown() {
        CrossProcessLock.assertionProvider = savedProvider
        try? FileManager.default.removeItem(at: dir)
        super.tearDown()
    }

    /// Records begin/end and lets a test fire the expiration by hand.
    private final class FakeAssertions {
        let lock = NSLock()
        var refuse = false
        var begun = 0
        var ended = 0
        var pendingExpire: (() -> Void)?

        var active: Int { lock.lock(); defer { lock.unlock() }; return begun - ended }

        func provider(name: String, onExpire: @escaping () -> Void) -> CrossProcessLock.HoldAssertion? {
            lock.lock(); defer { lock.unlock() }
            if refuse { return nil }
            begun += 1
            pendingExpire = onExpire
            var done = false
            return CrossProcessLock.HoldAssertion {
                self.lock.lock(); defer { self.lock.unlock() }
                if !done { done = true; self.ended += 1 }
            }
        }

        func expire() {
            lock.lock(); let f = pendingExpire; lock.unlock()
            f?()
        }
    }

    private func install(_ fake: FakeAssertions) {
        CrossProcessLock.assertionProvider = { fake.provider(name: $0, onExpire: $1) }
    }

    private func pair() -> (CrossProcessLock, CrossProcessLock) {
        let name = "d10cc_\(UUID().uuidString)"
        return (CrossProcessLock(name: name, directory: dir), CrossProcessLock(name: name, directory: dir))
    }

    /// Descriptors of THIS process open on any file in the test lock directory.
    /// Scoped to our files: the host app opens sockets and files concurrently,
    /// so a raw descriptor count would be flaky.
    private func openLockDescriptors() -> Int { openLockDescriptorPaths().count }

    private func openLockDescriptorPaths() -> [String] {
        var found: [String] = []
        let prefix = dir.resolvingSymlinksInPath().path
        let altPrefix = dir.path
        var buf = [CChar](repeating: 0, count: Int(MAXPATHLEN))
        for fd in 0..<Int32(getdtablesize()) where fcntl(fd, F_GETFD) != -1 {
            if fcntl(fd, F_GETPATH, &buf) != -1 {
                let path = String(cString: buf)
                if path.hasPrefix(prefix) || path.hasPrefix(altPrefix) { found.append("fd\(fd)=\(path)") }
            }
        }
        return found
    }

    // MARK: - The hold runs under an assertion

    func testEveryHoldRunsInsideABackgroundAssertion() throws {
        let fake = FakeAssertions(); install(fake)
        let (lock, _) = pair()
        let activeInside = try lock.withLock { fake.active }
        XCTAssertEqual(activeInside, 1, "the body must run while the assertion is held")
        XCTAssertEqual(fake.active, 0, "the assertion must end with the hold")
        XCTAssertEqual(fake.begun, 1)
    }

    func testAssertionEndsEvenWhenTheBodyThrows() {
        let fake = FakeAssertions(); install(fake)
        let (lock, _) = pair()
        struct Boom: Error {}
        XCTAssertThrowsError(try lock.withLock { throw Boom() })
        XCTAssertEqual(fake.begun, 1)
        XCTAssertEqual(fake.active, 0)
    }

    func testAssertionEndsWhenTheLockTimesOut() throws {
        let fake = FakeAssertions(); install(fake)
        let (holder, contender) = pair()
        let held = expectation(description: "held"), release = expectation(description: "release")
        DispatchQueue.global().async {
            try? holder.withLock { held.fulfill(); _ = XCTWaiter.wait(for: [release], timeout: 3) }
        }
        wait(for: [held], timeout: 3)
        XCTAssertThrowsError(try contender.withLock(timeout: 0.2) { 1 }) { error in
            guard case CrossProcessLock.LockError.timedOut = error else { return XCTFail("\(error)") }
        }
        release.fulfill()
        let deadline = Date().addingTimeInterval(3)
        while fake.active != 0 && Date() < deadline { usleep(20_000) }
        XCTAssertEqual(fake.active, 0, "no assertion may outlive its hold or its failed attempt")
    }

    // MARK: - No assertion, no lock

    func testRefusedAssertionMeansTheLockIsNeverTaken() throws {
        let fake = FakeAssertions(); fake.refuse = true; install(fake)
        let (lock, other) = pair()
        var ran = false
        XCTAssertThrowsError(try lock.withLock { ran = true }) { error in
            guard case CrossProcessLock.LockError.suspending = error else { return XCTFail("\(error)") }
        }
        XCTAssertFalse(ran, "the body must not run without an assertion")
        // And the kernel lock is free for the other process.
        fake.refuse = false
        XCTAssertEqual(try other.withLock(timeout: 0.2) { 7 }, 7)
    }

    // MARK: - Expiration drops the kernel lock before suspension

    func testExpirationDuringTheHoldReleasesTheFlockAndRevokesTheHold() throws {
        let fake = FakeAssertions(); install(fake)
        let (holder, otherProcess) = pair()

        let inBody = expectation(description: "in body")
        let expired = expectation(description: "expired")
        let otherGotIn = expectation(description: "other process got the lock")
        let finish = expectation(description: "finish")
        var revokedSeenByHolder = false
        var holderError: Error?

        DispatchQueue.global().async {
            do {
                try holder.withLock {
                    inBody.fulfill()
                    _ = XCTWaiter.wait(for: [expired], timeout: 3)
                    do { try holder.throwIfHoldRevoked() } catch { revokedSeenByHolder = true }
                    _ = XCTWaiter.wait(for: [finish], timeout: 3)
                }
            } catch { holderError = error }
        }
        wait(for: [inBody], timeout: 3)

        // Another thread is NOT the holder: it must not be told the hold is revoked.
        fake.expire()
        XCTAssertNoThrow(try holder.throwIfHoldRevoked())
        expired.fulfill()

        // The kernel lock is gone while the holder thread is still inside `body`:
        // exactly what lets iOS suspend us without 0xdead10cc.
        DispatchQueue.global().async {
            if (try? otherProcess.withLock(timeout: 1) { true }) == true { otherGotIn.fulfill() }
        }
        wait(for: [otherGotIn], timeout: 3)

        finish.fulfill()
        let deadline = Date().addingTimeInterval(3)
        while fake.active != 0 && Date() < deadline { usleep(20_000) }
        XCTAssertTrue(revokedSeenByHolder, "the holder must learn its hold was revoked before writing")
        XCTAssertNil(holderError, "the body's own result is still returned")

        // The lock is not poisoned for the next hold.
        XCTAssertEqual(try holder.withLock { try holder.throwIfHoldRevoked(); return 3 }, 3)
    }

    func testExpirationWhileWaitingForTheLockGivesUpWithoutTakingIt() throws {
        let fake = FakeAssertions(); install(fake)
        let (holder, waiter) = pair()
        let held = expectation(description: "held"), release = expectation(description: "release")
        DispatchQueue.global().async {
            try? holder.withLock { held.fulfill(); _ = XCTWaiter.wait(for: [release], timeout: 5) }
        }
        wait(for: [held], timeout: 3)

        let waiterDone = expectation(description: "waiter done")
        var waiterError: Error?
        DispatchQueue.global().async {
            do { _ = try waiter.withLock(timeout: 3) { 1 } } catch { waiterError = error }
            waiterDone.fulfill()
        }
        usleep(100_000)
        fake.expire()   // the waiter's assertion is the latest one begun
        wait(for: [waiterDone], timeout: 2)
        release.fulfill()
        guard case CrossProcessLock.LockError.suspending? = waiterError as? CrossProcessLock.LockError else {
            return XCTFail("expected .suspending, got \(String(describing: waiterError))")
        }
    }

    /// A late expiration of a hold that already ENDED must not revoke the next one.
    func testStaleExpirationDoesNotRevokeALaterHold() throws {
        let fake = FakeAssertions(); install(fake)
        let (lock, _) = pair()
        try lock.withLock { }
        let stale = fake.pendingExpire
        try lock.withLock {
            stale?()
            XCTAssertNoThrow(try lock.throwIfHoldRevoked())
        }
    }

    // MARK: - Descriptors

    func testNoDescriptorLeaksAcrossSuccessFailureRefusalAndExpiration() throws {
        let fake = FakeAssertions(); install(fake)
        let (lock, _) = pair()
        struct Boom: Error {}
        _ = try lock.withLock { 0 }   // warm up (directory creation etc.)
        XCTAssertEqual(openLockDescriptorPaths(), [])
        var insideCount = -1
        _ = try lock.withLock { insideCount = openLockDescriptors() }
        XCTAssertEqual(insideCount, 1, "the probe must see the descriptor while held, or it proves nothing")
        for i in 0..<200 {
            switch i % 4 {
            case 0: _ = try lock.withLock { i }
            case 1: _ = try? lock.withLock { throw Boom() }
            case 2:
                fake.refuse = true
                _ = try? lock.withLock { i }
                fake.refuse = false
            default:
                _ = try lock.withLock { fake.expire(); return i }
            }
        }
        XCTAssertEqual(openLockDescriptorPaths(), [], "a lock descriptor outlived its hold")
    }

    // MARK: - Serialisation still holds (the reason the lock exists)

    func testTwoInstancesStillExcludeEachOtherUnderAssertions() throws {
        let fake = FakeAssertions(); install(fake)
        let (a, b) = pair()
        var shared = 0
        let guardQ = DispatchQueue(label: "g")
        let group = DispatchGroup()
        for i in 0..<40 {
            let lock = i % 2 == 0 ? a : b
            DispatchQueue.global().async(group: group) {
                // Retry on contention like a real caller would on the next tick.
                var done = false
                while !done {
                    done = (try? lock.withLock(timeout: 2) { () -> Bool in
                        let seen = guardQ.sync { shared }
                        usleep(300)
                        guardQ.sync { shared = seen + 1 }
                        return true
                    }) ?? false
                }
            }
        }
        group.wait()
        XCTAssertEqual(shared, 40, "an update was lost — the two 'processes' overlapped")
        XCTAssertEqual(fake.active, 0)
    }

    // MARK: - Real providers

    /// The extension default. Must grant in a running process and end cleanly.
    func testProcessInfoProviderGrantsAndEnds() {
        var expiredCalled = false
        let a = CrossProcessLock.expiringActivityAssertion(name: "test") { expiredCalled = true }
        XCTAssertNotNil(a, "a foreground process must get an expiring-activity assertion")
        a?.end()
        a?.end()   // idempotent
        XCTAssertFalse(expiredCalled)
    }

    /// The app provider, installed by the app delegate (the test host).
    func testAppProviderIsInstalledAndGrantsInTheForeground() throws {
        CrossProcessLock.assertionProvider = savedProvider
        let a = CrossProcessLockBackgroundTask.provider(name: "test") { }
        XCTAssertNotNil(a)
        a?.end(); a?.end()
        let (lock, _) = pair()
        XCTAssertEqual(try lock.withLock { 5 }, 5)
    }

    // MARK: - The store refuses to persist under a revoked hold

    func testSaveSessionRefusesUnderARevokedHold() throws {
        let fake = FakeAssertions(); install(fake)
        let peer = "dead10cc-peer-\(UUID().uuidString)"
        let lock = CrossProcessLock.forPeer(peer)
        let store = V2SessionStore()
        let state = OSHICryptoV2.RatchetState(
            DHs: OSHICryptoV2.generateX25519(), DHr: nil,
            RK: Data(repeating: 1, count: 32), CKs: nil, CKr: nil)
        let record = V2SessionStore.SessionRecord(peerUserKey: peer, role: .initiator, ratchet: .init(state))

        // `forPeer` uses the App Group container; skip where the host has none.
        do { try lock.withLock { } } catch CrossProcessLock.LockError.unavailable {
            throw XCTSkip("no App Group container in this host")
        }

        XCTAssertThrowsError(try store.withPeerSession(peer) {
            fake.expire()
            try store.saveSession(record)
        }) { error in
            guard case CrossProcessLock.LockError.revoked = error else { return XCTFail("\(error)") }
        }
        XCTAssertNil(try? store.loadSession(peerUserKey: peer), "nothing may be written under a revoked hold")
    }
}
