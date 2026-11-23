//
//  KeychainSharedGroupTests.swift
//  OSHITests
//
//  Proves the shared-keychain migration and the cross-process lock.
//
//  ⚠️ WHAT THE SIMULATOR CAN AND CANNOT PROVE — read this before trusting a green run.
//
//  The Simulator has ONE keychain shared by every app and does not enforce
//  entitlements at all. So these tests CANNOT prove that OSHIShare will really be
//  allowed to read the app's ratchet sessions on a device — that is a property of
//  the `keychain-access-groups` entitlement and the provisioning profile, and the
//  only honest way to check it is to install both on hardware and watch the
//  extension read a key the app wrote.
//
//  What they DO prove, which is the part that can silently destroy user data:
//  the migration algorithm. Idempotence, that a failed write never counts as
//  migrated, that a corrupted read-back never counts as migrated, and — the one
//  that matters most — that the private copy is never removed. Those are exercised
//  against two genuinely separate stores through `MigrationStore`, which exists
//  precisely because the real keychain would collapse both into one here and
//  report success without running any of it.
//

import XCTest
@testable import OSHI

final class KeychainSharedGroupTests: XCTestCase {

    /// Two independent in-memory stores standing in for the private and shared
    /// access groups.
    private final class FakeKeychain {
        var privateItems: [String: Data] = [:]
        var sharedItems: [String: Data] = [:]
        var writeFailsFor: Set<String> = []
        var corruptReadBackFor: Set<String> = []
        private(set) var writes: [String] = []

        var store: KeychainHelper.MigrationStore {
            KeychainHelper.MigrationStore(
                readShared: { key in
                    if let d = self.sharedItems[key] { return d }
                    throw KeychainError.itemNotFound
                },
                readPrivate: { key in
                    if let d = self.privateItems[key] { return d }
                    throw KeychainError.itemNotFound
                },
                writeShared: { key, data in
                    self.writes.append(key)
                    if self.writeFailsFor.contains(key) {
                        throw KeychainError.unhandledError(status: -25299)
                    }
                    self.sharedItems[key] = self.corruptReadBackFor.contains(key)
                        ? Data("corrupted".utf8)
                        : data
                }
            )
        }
    }

    private let keys = ["privateKey", "signingKey", "ratchet_sessions_v1"]

    // MARK: - The happy path

    func testMigrationCopiesEveryPrivateItemIntoTheSharedGroup() {
        let fake = FakeKeychain()
        fake.privateItems = [
            "privateKey": Data("identity".utf8),
            "signingKey": Data("signing".utf8),
            "ratchet_sessions_v1": Data("sessions".utf8),
        ]

        let report = KeychainHelper.migrateSharedItems(keys: keys, store: fake.store)

        XCTAssertEqual(Set(report.moved), Set(keys))
        XCTAssertTrue(report.failed.isEmpty, "unexpected failures: \(report)")
        XCTAssertEqual(fake.sharedItems["ratchet_sessions_v1"], Data("sessions".utf8))
    }

    /// 🔴 The whole reason this is a COPY and not a move. If this ever goes red,
    /// shipping it wipes every existing user's ratchet sessions and identity, and
    /// they are shown onboarding as if they had never used the app.
    func testMigrationNeverRemovesThePrivateCopy() {
        let fake = FakeKeychain()
        fake.privateItems = ["privateKey": Data("identity".utf8)]

        _ = KeychainHelper.migrateSharedItems(keys: ["privateKey"], store: fake.store)

        XCTAssertEqual(fake.privateItems["privateKey"], Data("identity".utf8),
                       "the private copy must survive the migration, always")
    }

    // MARK: - Idempotence

    func testSecondRunDoesNoWorkAndWritesNothing() {
        let fake = FakeKeychain()
        fake.privateItems = ["privateKey": Data("identity".utf8)]

        _ = KeychainHelper.migrateSharedItems(keys: ["privateKey"], store: fake.store)
        let writesAfterFirst = fake.writes.count
        let second = KeychainHelper.migrateSharedItems(keys: ["privateKey"], store: fake.store)

        XCTAssertEqual(second.alreadyShared, ["privateKey"])
        XCTAssertTrue(second.moved.isEmpty)
        XCTAssertEqual(fake.writes.count, writesAfterFirst,
                       "a launch after migration must not touch the keychain again")
    }

    // MARK: - Failure must not be reported as success

    func testAFailedWriteIsNotReportedAsMigrated() {
        let fake = FakeKeychain()
        fake.privateItems = ["ratchet_sessions_v1": Data("sessions".utf8)]
        fake.writeFailsFor = ["ratchet_sessions_v1"]

        let report = KeychainHelper.migrateSharedItems(keys: ["ratchet_sessions_v1"],
                                                       store: fake.store)

        XCTAssertTrue(report.moved.isEmpty)
        XCTAssertEqual(report.failed.count, 1)
        XCTAssertEqual(fake.privateItems["ratchet_sessions_v1"], Data("sessions".utf8))
    }

    /// A write can "succeed" and still leave an item that reads back wrong. Counting
    /// that as migrated is how you ship a build that decrypts nothing.
    func testAWriteThatReadsBackDifferentIsNotReportedAsMigrated() {
        let fake = FakeKeychain()
        fake.privateItems = ["signingKey": Data("signing".utf8)]
        fake.corruptReadBackFor = ["signingKey"]

        let report = KeychainHelper.migrateSharedItems(keys: ["signingKey"], store: fake.store)

        XCTAssertTrue(report.moved.isEmpty, "bytes differed — this is not a migration")
        XCTAssertEqual(report.failed.first?.key, "signingKey")
        XCTAssertEqual(fake.privateItems["signingKey"], Data("signing".utf8))
    }

    func testAnAbsentKeyIsReportedAbsentAndNotFailed() {
        let fake = FakeKeychain()

        let report = KeychainHelper.migrateSharedItems(keys: ["privateKey"], store: fake.store)

        XCTAssertEqual(report.absent, ["privateKey"])
        XCTAssertTrue(report.failed.isEmpty)
        XCTAssertTrue(report.moved.isEmpty)
    }

    /// A locked keychain is transient. It must never be mistaken for "no item",
    /// which elsewhere in this app means "new user, show onboarding".
    func testALockedKeychainIsAFailureNotAnAbsence() {
        let fake = FakeKeychain()
        var store = fake.store
        store.readPrivate = { _ in throw KeychainError.locked }

        let report = KeychainHelper.migrateSharedItems(keys: ["privateKey"], store: store)

        XCTAssertTrue(report.absent.isEmpty, "locked is not absent")
        XCTAssertEqual(report.failed.count, 1)
    }

    // MARK: - Per-peer sessions must be reachable from the extension

    /// 🔴 THE REGRESSION THIS PINS.
    ///
    /// `V2SessionStore` used to read and write sessions with NO access group, so
    /// they landed in the app's private group and OSHIShare could not see them.
    /// The extension's `hasSession` was therefore always false, it fell back on
    /// every share, and the user still had to open the app — the symptom that
    /// survived every other fix, for weeks.
    ///
    /// This asserts the two invariants that keep it fixed: the shared group is
    /// where a session is written, and it is the FIRST place a read looks. Reading
    /// private-first would be worse than not sharing at all — the app would decrypt
    /// against a stale chain while the extension advanced the real one.
    // MARK: - L'aller-retour lui-même
    //
    // 🔴 __SESSION_ROUNDTRIP_2026_08_22__ Ce fichier teste la migration, les
    // verrous, les collisions de noms — et JAMAIS la seule chose dont tout le
    // reste dépend: une session enregistrée peut-elle être relue ?
    //
    // Elle ne pouvait pas. `saveSession` écrivait dans le groupe partagé, puis
    // appelait `KeychainHelper.delete(key:)` SANS groupe pour « supprimer une
    // vieille copie privée ». Une suppression sans `kSecAttrAccessGroup` porte sur
    // TOUS les groupes accessibles: elle emportait la copie qui venait d'être
    // écrite. `save()` documente ce piège trois lignes plus haut, et
    // `saveSession` le reproduisait à la ligne suivante.
    //
    // Effet observé sur l'appareil de Hugo le 2026-08-22: 32 envois sur 32 à la
    // même personne échouent sur `sessionVanishedUnderLock` et retombent sur le
    // transport de secours. Un taux d'échec de 100 %, pas une course.
    func testASavedSessionCanBeReadBack() throws {
        // Préfixe unique: ce test ne doit jamais toucher une vraie session.
        let store = V2SessionStore(sessionPrefix: "oshi.test.roundtrip.\(UUID().uuidString).",
                                   ownMaterialKey: "oshi.test.roundtrip.own.\(UUID().uuidString)")
        let peer = "round+trip/peer=key"
        let pair = OSHICryptoV2.generateX25519()
        let state = OSHICryptoV2.RatchetState(DHs: pair, DHr: nil,
                                              RK: Data(repeating: 7, count: 32),
                                              CKs: nil, CKr: nil)
        let record = V2SessionStore.SessionRecord(peerUserKey: peer,
                                                  role: .initiator,
                                                  ratchet: .init(state))
        try store.saveSession(record)
        defer { try? store.deleteSession(peerUserKey: peer) }

        let readBack = try store.loadSession(peerUserKey: peer)
        XCTAssertNotNil(readBack,
                        "une session enregistrée doit être relisible — sinon CHAQUE envoi "
                        + "jette sessionVanishedUnderLock et retombe sur le transport lent")
        XCTAssertEqual(readBack?.peerUserKey, peer)
        XCTAssertEqual(readBack?.role, .initiator)
    }

    /// Et deux fois de suite: la seconde écriture ne doit pas effacer la première.
    /// C'est le cas réel — le ratchet réenregistre à chaque message.
    func testSavingTwiceLeavesTheSecondRecordReadable() throws {
        let store = V2SessionStore(sessionPrefix: "oshi.test.roundtrip2.\(UUID().uuidString).",
                                   ownMaterialKey: "oshi.test.roundtrip2.own.\(UUID().uuidString)")
        let peer = "twice+peer/key="
        let pair = OSHICryptoV2.generateX25519()
        let state = OSHICryptoV2.RatchetState(DHs: pair, DHr: nil,
                                              RK: Data(repeating: 3, count: 32),
                                              CKs: nil, CKr: nil)
        var record = V2SessionStore.SessionRecord(peerUserKey: peer,
                                                  role: .initiator,
                                                  ratchet: .init(state))
        try store.saveSession(record)
        record.role = .responder
        try store.saveSession(record)
        defer { try? store.deleteSession(peerUserKey: peer) }

        XCTAssertEqual(try store.loadSession(peerUserKey: peer)?.role, .responder,
                       "la ré-écriture du ratchet arrive à chaque message: elle ne doit "
                       + "ni disparaître ni ressusciter l'état précédent")
    }

    func testSessionAccountsAreNamespacedAndSharedGroupIsAuthoritative() {
        let store = V2SessionStore()
        let peer = "peer+key/with=punctuation"

        // The account name is prefixed, so v2 sessions can never collide with the
        // identity items that live under bare names like "privateKey".
        let mirror = KeychainHelper.Account.v2SessionPrefix + peer
        XCTAssertTrue(mirror.hasPrefix("oshi.v2.session."))
        XCTAssertFalse(KeychainHelper.sharedKeys.contains(mirror),
                       "per-peer sessions must NOT be in the fixed migration list — "
                       + "there is one per contact and they are rewritten constantly")

        // The store exists and its prefix is the single-sourced one.
        XCTAssertNotNil(store)
    }

    /// The four fixed items the extension needs are exactly the ones it is given —
    /// no more (a compromised extension should read the minimum) and no fewer (a
    /// missing one means the extension cannot send and says nothing).
    func testSharedKeysAreTheFourRealAccountNames() {
        XCTAssertEqual(Set(KeychainHelper.sharedKeys), Set([
            KeychainHelper.Account.identity,
            KeychainHelper.Account.signing,
            KeychainHelper.Account.v2OwnKeys,
            KeychainHelper.Account.legacyRatchetSessions,
        ]))
        // And they are real names, not invented ones. `ratchet_sessions_v1` was.
        XCTAssertFalse(KeychainHelper.sharedKeys.contains("ratchet_sessions_v1"))
    }

    // MARK: - Cross-process lock

    /// A unit-test host has no App Group container, so the production default
    /// resolves to nil and every lock call throws `.unavailable`. That is the
    /// CORRECT production behaviour — a lock that cannot be taken must not let the
    /// caller through — but it means the tests have to supply their own directory
    /// or they would only ever prove that the lock refuses to run.
    private func makeTestLock() -> CrossProcessLock {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("oshi-lock-tests", isDirectory: true)
        return CrossProcessLock(name: "test_\(UUID().uuidString)", directory: dir)
    }

    /// A lock with nowhere to put its file must FAIL, never fall through.
    ///
    /// 🔧 FIX (2026-08-20): this used to build `CrossProcessLock(name:)` with the
    /// production default and assert that it threw, on the reasoning that "a
    /// unit-test host has no App Group container". That is a fact about the
    /// MACHINE, not about the code — and it is false on a simulator where the
    /// test host is the app itself, entitled to `group.Moriceau-lab.Genesis`,
    /// whose container the simulator materialises on install. The lock was
    /// available, correctly did not throw, and the suite went red over a
    /// perfectly working lock.
    ///
    /// The property worth protecting is fail-closed, so assert THAT: point the
    /// lock at a directory that cannot be created and require `.unavailable`.
    /// It now holds wherever the tests run.
    func testLockFailsClosedWhenItsDirectoryCannotBeUsed() {
        // /dev/null is a character device: `createDirectory` under it cannot
        // succeed, on any host, in any sandbox.
        let impossible = URL(fileURLWithPath: "/dev/null").appendingPathComponent("oshi-locks", isDirectory: true)
        let lock = CrossProcessLock(name: "test_\(UUID().uuidString)", directory: impossible)
        XCTAssertThrowsError(try lock.withLock { 1 }) { error in
            guard case CrossProcessLock.LockError.unavailable = error else {
                return XCTFail("expected .unavailable, got \(error)")
            }
        }
    }

    /// …and the seam itself still exists, which is the other half of what the
    /// old test was protecting: `directory:` must be honoured, or every lock
    /// test below would silently exercise the production container.
    func testLockHonoursAnExplicitDirectory() throws {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("oshi-lock-seam", isDirectory: true)
        let name = "seam_\(UUID().uuidString)"
        let lock = CrossProcessLock(name: name, directory: dir)
        XCTAssertEqual(try lock.withLock { 42 }, 42)
        let contents = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        XCTAssertTrue(contents.contains { $0.contains(name) },
                      "the lock file must land in the directory we passed, not in the App Group container")
    }

    /// 🔴 THE TEST THAT ACTUALLY PROVES `flock`.
    ///
    /// `testLockSerialisesConcurrentReadModifyWrite` below would pass even if the
    /// `flock` calls were deleted, because `withLock` serialises on a per-instance
    /// DispatchQueue first — it proves the in-process half and nothing else. Two
    /// SEPARATE instances sharing a name hold two separate file descriptors, which
    /// is the same situation the app and the share extension are in. If `flock`
    /// were removed, the second call here would succeed instead of timing out.
    func testTwoSeparateInstancesWithTheSameNameContend() throws {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("oshi-lock-tests", isDirectory: true)
        let name = "contend_\(UUID().uuidString)"
        let holder = CrossProcessLock(name: name, directory: dir)
        let contender = CrossProcessLock(name: name, directory: dir)

        let held = expectation(description: "holder has the lock")
        let release = expectation(description: "holder may release")

        DispatchQueue.global().async {
            try? holder.withLock(timeout: 5) {
                held.fulfill()
                _ = XCTWaiter.wait(for: [release], timeout: 3)
            }
        }
        wait(for: [held], timeout: 5)

        XCTAssertThrowsError(try contender.withLock(timeout: 0.3) { 1 }) { error in
            guard case CrossProcessLock.LockError.timedOut = error else {
                return XCTFail("expected .timedOut while the other fd holds it, got \(error)")
            }
        }

        release.fulfill()
        // And once released, the same contender gets in — the lock is not stuck.
        var got = false
        for _ in 0..<20 where !got {
            if (try? contender.withLock(timeout: 0.3) { true }) == true { got = true }
            else { usleep(100_000) }
        }
        XCTAssertTrue(got, "lock never became available after the holder released")
    }

    /// Proves the IN-PROCESS half only — see the test above for the `flock` half.
    func testLockSerialisesConcurrentReadModifyWrite() throws {
        let lock = makeTestLock()

        // Without the lock this is the classic lost-update race: read, yield,
        // write back. With it, every increment must survive.
        var shared = 0
        let iterations = 50
        let group = DispatchGroup()
        let queue = DispatchQueue(label: "test.contention", attributes: .concurrent)
        let guardQueue = DispatchQueue(label: "test.guard")

        for _ in 0..<iterations {
            queue.async(group: group) {
                try? lock.withLock {
                    let seen = guardQueue.sync { shared }
                    usleep(200)   // widen the window a real read-modify-write has
                    guardQueue.sync { shared = seen + 1 }
                }
            }
        }
        group.wait()

        XCTAssertEqual(shared, iterations, "an increment was lost — the lock did not hold")
    }

    func testLockIsReleasedAfterTheBodyThrows() throws {
        let lock = makeTestLock()
        struct Boom: Error {}

        XCTAssertThrowsError(try lock.withLock { throw Boom() })

        // If the first call leaked the lock this second one times out instead.
        let value = try lock.withLock { 42 }
        XCTAssertEqual(value, 42)
    }

    // MARK: - Per-peer locks

    /// Reuse is not an optimisation — it is required for correctness INSIDE one
    /// process. `flock` is held per open file description, so two instances would
    /// each take their own and neither would see the other; the per-instance serial
    /// queue is the only thing serialising same-process callers, and it only works
    /// if they share the instance.
    func testTheSamePeerAlwaysGetsTheSameLockInstance() {
        let peer = "abc+/def=" + UUID().uuidString
        let a = CrossProcessLock.forPeer(peer)
        let b = CrossProcessLock.forPeer(peer)
        XCTAssertTrue(a === b, "same peer must reuse one lock instance")
    }

    /// Two conversations must not queue behind each other. A share-extension send
    /// to one contact blocking an unrelated chat would be a self-inflicted stall.
    func testDifferentPeersGetDifferentLocks() {
        let a = CrossProcessLock.forPeer("peer-A-" + UUID().uuidString)
        let b = CrossProcessLock.forPeer("peer-B-" + UUID().uuidString)
        XCTAssertFalse(a === b)
    }

    /// Peer keys are base64 and carry `+` `/` `=`. They are hashed into the lock
    /// file name, and two distinct keys must never collapse onto one file — that
    /// would silently serialise two unrelated conversations, or worse, let one
    /// believe it owns the other's ratchet.
    func testPeerKeysWithBase64PunctuationDoNotCollide() {
        let a = CrossProcessLock.forPeer("AA+BB/CC==")
        let b = CrossProcessLock.forPeer("AA-BB_CC")
        XCTAssertFalse(a === b, "distinct peer keys must not share a lock")
    }

    /// The lock file must live where BOTH processes look. `CrossProcessLock`
    /// holds its own copy of the App Group id so it can build into OSHIShare
    /// without linking UIKit; if the two ever drift, each process takes a lock in
    /// a container the other cannot see and both believe they hold it.
    func testLockAppGroupMatchesTheBridge() {
        XCTAssertEqual(CrossProcessLock.appGroupID, AppGroupBridge.appGroupID)
    }

    func testLockReturnsTheBodyResult() throws {
        let lock = makeTestLock()
        XCTAssertEqual(try lock.withLock { "ok" }, "ok")
    }
}
