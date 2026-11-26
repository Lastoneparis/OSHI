//
//  CrossProcessLock.swift
//  OSHI
//
//  A mutual-exclusion lock that works BETWEEN PROCESSES, not just between threads.
//
//  Why this exists
//  ---------------
//  Once OSHIShare can read the ratchet sessions out of the shared keychain, two
//  separate processes can decrypt, encrypt and re-persist the same session. The
//  Double Ratchet does not tolerate that: every send advances the sending chain
//  and every receive advances the receiving chain, and the state is a single
//  serialized blob. Two processes doing read-modify-write on it concurrently means
//  the second write silently discards the first one's advance — and the peer's
//  next message then fails to authenticate, permanently, for that session.
//
//  That is the same failure the tampered-packet bug produced (see
//  `OSHICryptoV2.ratchetDecrypt`), reached by a different road. It is worth being
//  blunt: a shared keychain WITHOUT this lock is strictly worse than no sharing at
//  all, because it converts "the extension cannot send" into "the extension can
//  corrupt conversations".
//
//  Why a file lock and not `NSLock`, a semaphore, or `UserDefaults`
//  ---------------------------------------------------------------
//  `NSLock`, `DispatchSemaphore` and actor isolation are all per-process — they
//  are invisible to the other side and would give a false sense of safety.
//  `NSFileCoordinator` is the Apple-blessed answer but is designed around document
//  files, is asynchronous, and silently degrades to a no-op when a file presenter
//  is absent. `flock(2)` on a file inside the App Group container is the smallest
//  primitive that actually holds across processes, and the kernel releases it if
//  the holder is killed — which matters here, because iOS kills share extensions
//  without warning and a lock that survived its owner would deadlock the app.
//

import Foundation
import CryptoKit
import os.log

/// A named, advisory, cross-process lock backed by `flock(2)` on a file in the
/// shared App Group container.
final class CrossProcessLock {

    /// Must equal `AppGroupBridge.appGroupID`. Asserted in
    /// `KeychainSharedGroupTests.testLockAppGroupMatchesTheBridge` so the two
    /// copies cannot drift into a lock file nobody else can see.
    static let appGroupID = "group.Moriceau-lab.Genesis"

    /// Guards every read-modify-write of the Double Ratchet session store.
    ///
    /// Kept for the single-blob legacy store. The v2 store is per-peer, so it uses
    /// `forPeer(_:)` instead — locking every conversation behind one mutex would
    /// make a share-extension send block a completely unrelated chat.
    static let ratchetSessions = CrossProcessLock(name: "ratchet_sessions")

    /// One lock per peer, reused within the process.
    ///
    /// Reuse is not an optimisation, it is required: `flock` is held per open file
    /// description, so two INSTANCES in one process would each hold their own and
    /// neither would see the other. The per-instance `localQueue` is what closes
    /// that, and it only works if both callers share the instance.
    /// 🔴 FIX (2026-08-21) — C'ÉTAIT UN `NSCache`, ET C'EST UNE ERREUR DE TYPE.
    ///
    /// `NSCache` est conçu pour ÉVINCER: le système vide ses entrées quand il
    /// juge la mémoire tendue, sans prévenir. Or l'identité de cet objet est
    /// porteuse de correction, pas d'optimisation — c'est écrit au-dessus de
    /// `forPeer` et dans le test qui l'accompagne: `flock` s'applique par
    /// description de fichier ouverte, donc deux instances prennent chacune la
    /// leur et ne se voient pas, et la file série par instance est la SEULE chose
    /// qui sérialise les appelants d'un même processus.
    ///
    /// Une éviction rend donc possible ceci: deux envois simultanés vers le même
    /// contact obtiennent deux verrous différents, tous deux « réussissent », et
    /// écrivent le ratchet en concurrence. Exactement la classe de dégât que le
    /// verrou existe pour empêcher.
    ///
    /// Trouvé parce que `testTheSamePeerAlwaysGetsTheSameLockInstance` a échoué
    /// sur une machine sous forte pression mémoire — deux appels consécutifs,
    /// deux instances. Ce n'était pas un test instable: le test disait vrai.
    ///
    /// Un dictionnaire ne s'évince pas. La croissance est bornée par le nombre de
    /// contacts, et chaque entrée est un objet minuscule.
    private static var peerLocks: [String: CrossProcessLock] = [:]
    private static let peerLockGuard = NSLock()

    static func forPeer(_ peerUserKey: String) -> CrossProcessLock {
        // Peer keys are base64 and carry `+` `/` `=`. Hash rather than sanitise:
        // two different keys must never collapse onto one lock file.
        let digest = SHA256.hash(data: Data(peerUserKey.utf8))
        let name = "v2_session_" + digest.prefix(8).map { String(format: "%02x", $0) }.joined()

        peerLockGuard.lock()
        defer { peerLockGuard.unlock() }
        if let hit = peerLocks[name] { return hit }
        let made = CrossProcessLock(name: name)
        peerLocks[name] = made
        return made
    }

    /// Cheap probe: can a lock be taken at all right now?
    ///
    /// Exists because the failure mode this class chose — throw rather than proceed
    /// unlocked — turns an unavailable lock into an unsendable message. That is the
    /// right call, but it means "is the lock actually available?" has to be an
    /// answerable question at launch rather than a discovery during a send.
    static func probeAvailability() -> Result<Void, Error> {
        let probe = CrossProcessLock(name: "availability_probe")
        do {
            try probe.withLock(timeout: 1) { }
            return .success(())
        } catch {
            return .failure(error)
        }
    }

    private let name: String
    private let lockURL: URL?

    /// Serializes callers *within* this process before they ever reach `flock`.
    ///
    /// `flock` locks are held per open file description, not per thread: two
    /// threads in the SAME process sharing one descriptor would both believe they
    /// hold the lock. This queue closes that hole; `flock` closes the between-
    /// process one. Neither is sufficient alone.
    private let localQueue: DispatchQueue

    // MARK: - 0xdead10cc — never be suspended holding this lock
    //
    // 🔴 __DEAD10CC_2026_11_26__ 16 kills on the owner's iPhone (05/11 → 24/11,
    // builds 187 → 206), EVERY one `EXC_CRASH SIGKILL`, namespace RUNNINGBOARD,
    // code 0xdead10cc. In all 16 reports the only thread inside OSHI code is the
    // serial queue `com.oshi.xproclock.v2_session_<hash>` — i.e. THIS class's
    // `localQueue` — frozen in `KeychainHelper.save/load` (securityd XPC) under
    // `V2SessionStore.saveSession/loadSession` inside `withPeerSession`. iOS
    // suspended the app with the `flock` taken on `locks/v2_session_*.lock` in the
    // App Group container, and RunningBoard kills any process suspended while it
    // holds a file lock in a shared container (TN2151 / "0xdead10cc").
    //
    // The hold is normally milliseconds; what was missing is that NOTHING told the
    // system "do not suspend me for those milliseconds". The app is often running
    // in the background (pull timer, BLE/Multipeer, VoIP), so a suspension landing
    // in the middle of a ratchet save was a matter of time — the "app closes by
    // itself after a few hours".
    //
    // Fix, per Apple's guidance:
    //   1. Every hold runs under a background-execution assertion
    //      (`UIApplication.beginBackgroundTask` in the app — installed by the app
    //      delegate — `ProcessInfo.performExpiringActivity` in extensions). If the
    //      system will not grant one (we are already being suspended), the lock is
    //      NOT taken: `withLock` throws `.suspending`, exactly like a timeout, and
    //      callers already handle that (send fails visibly, receive stays parked).
    //   2. If the assertion EXPIRES while the lock is held, the expiration handler
    //      drops the `flock` itself (`LOCK_UN`) before the process is suspended, and
    //      marks the hold REVOKED. A revoked hold no longer serialises anything, so
    //      `throwIfHoldRevoked()` — called by `V2SessionStore.saveSession` right
    //      before it writes — refuses the write. The ratchet in the keychain stays at
    //      its pre-hold state: the message is retried, never written from a stale
    //      read. That keeps the cross-process guarantee the lock exists for.
    //   3. The descriptor is opened per hold and closed when it ends (unchanged);
    //      nothing in the App Group stays open between holds, so there is nothing
    //      to close on `didEnterBackground`.
    //
    // ⚠️ The `locks/*.lock` files themselves are left in place on purpose. They are
    // empty and hold nothing unless a descriptor is open on them. Deleting one is
    // NOT safe: a process that already opened the old inode would `flock` it while
    // the other process creates and locks a NEW inode at the same path — both would
    // "hold" the lock. One empty file per contact is the price of that guarantee.

    /// A background-execution assertion covering one lock hold.
    struct HoldAssertion {
        let end: () -> Void
    }

    /// Begins a background-execution assertion for a hold on `name`.
    ///
    /// - Returns: nil when the process cannot be kept running (it is being
    ///   suspended); the lock is then NOT taken.
    /// - Parameter onExpire: must be called (from any thread) if the assertion
    ///   expires before `end` is called. It drops the kernel lock.
    typealias AssertionProvider = (_ name: String, _ onExpire: @escaping () -> Void) -> HoldAssertion?

    private static let providerGuard = NSLock()
    private static var _assertionProvider: AssertionProvider = CrossProcessLock.expiringActivityAssertion

    /// The app replaces this at launch with a `UIApplication.beginBackgroundTask`
    /// provider (extensions cannot reference `UIApplication.shared`, so this file
    /// cannot). Extensions keep the `ProcessInfo` default. Tests inject fakes.
    static var assertionProvider: AssertionProvider {
        get { providerGuard.lock(); defer { providerGuard.unlock() }; return _assertionProvider }
        set { providerGuard.lock(); _assertionProvider = newValue; providerGuard.unlock() }
    }

    /// Default provider, valid in the app AND in extensions:
    /// `ProcessInfo.performExpiringActivity`. Its block runs on a system queue;
    /// the assertion lasts as long as that block has not returned, so the block
    /// parks until `end` (or expiration) releases it.
    static func expiringActivityAssertion(name: String,
                                          onExpire: @escaping () -> Void) -> HoldAssertion? {
        final class Activity {
            let lock = NSLock()
            var granted = false
            var finished = false      // `end` called, or caller gave up
            var expired = false
            let started = DispatchSemaphore(value: 0)
            let release = DispatchSemaphore(value: 0)
        }
        let a = Activity()
        ProcessInfo.processInfo.performExpiringActivity(withReason: "OSHI lock \(name)") { expired in
            if expired {
                a.lock.lock()
                let wasGranted = a.granted && !a.finished
                a.expired = true
                a.lock.unlock()
                if wasGranted { onExpire() }
                a.started.signal()   // a caller still waiting to start must give up
                a.release.signal()   // let the parked first invocation return
                return
            }
            a.lock.lock()
            if a.finished || a.expired { a.lock.unlock(); return }
            a.granted = true
            a.lock.unlock()
            a.started.signal()
            a.release.wait()   // hold the assertion until `end`
        }
        _ = a.started.wait(timeout: .now() + 2)
        a.lock.lock()
        let ok = a.granted && !a.expired
        if !ok { a.finished = true }
        a.lock.unlock()
        guard ok else {
            a.release.signal()
            return nil
        }
        return HoldAssertion {
            a.lock.lock()
            let first = !a.finished
            a.finished = true
            a.lock.unlock()
            if first { a.release.signal() }
        }
    }

    // State of the CURRENT hold, readable from the expiration handler while the
    // holder thread is busy inside `body` (so it cannot live on `localQueue`).
    private let stateGuard = NSLock()
    private var heldFD: Int32 = -1          // set once `flock` succeeded
    private var holdRevoked = false
    private var holderThread: pthread_t?
    /// Bumped per hold, so a late expiration of an ENDED hold's assertion can
    /// never revoke the next one.
    private var holdGeneration: UInt64 = 0

    /// Called by the assertion's expiration handler, on any thread.
    private func revokeCurrentHold(generation: UInt64) {
        stateGuard.lock()
        defer { stateGuard.unlock() }
        // No hold in progress, or a different one.
        guard holderThread != nil, holdGeneration == generation else { return }
        holdRevoked = true
        if heldFD >= 0 {
            // Drop the kernel lock NOW, before the system suspends us. The fd
            // itself is closed by the holder thread; closing it here could close
            // a recycled descriptor number.
            flock(heldFD, LOCK_UN)
            os_log("🔒 CrossProcessLock '%{public}@' revoked: background time expired during the hold",
                   log: .default, type: .error, name)
        }
    }

    /// Throws `.revoked` when the calling thread is inside a hold of this lock
    /// whose background time expired — the `flock` has been dropped, so a write
    /// based on what was read under it may race the other process. Callers that
    /// persist state read under the lock call this right before the write.
    func throwIfHoldRevoked() throws {
        stateGuard.lock()
        defer { stateGuard.unlock() }
        if holdRevoked, let holder = holderThread, pthread_equal(holder, pthread_self()) != 0 {
            throw LockError.revoked
        }
    }

    /// - Parameter directory: where the lock file lives. Defaults to the shared
    ///   App Group container, which is the only place two processes can both see.
    ///
    ///   Overridable for one reason: a unit-test host has NO App Group container
    ///   (`containerURL(forSecurityApplicationGroupIdentifier:)` returns nil), so
    ///   without this seam every lock test fails with `.unavailable` and proves
    ///   nothing about locking. Production callers must never pass this — a lock
    ///   file outside the shared container is invisible to the other process and
    ///   would be a lock in name only.
    init(name: String, directory: URL? = nil) {
        self.name = name
        self.localQueue = DispatchQueue(label: "com.oshi.xproclock.\(name)")
        let base = directory ?? FileManager.default
            // Literal rather than `AppGroupBridge.appGroupID`: this file also builds
            // into OSHIShare, and AppGroupBridge imports UIKit for thumbnailing —
            // an extension should not link that to learn one string. Kept in sync
            // by the test below, which asserts the two are identical.
            .containerURL(forSecurityApplicationGroupIdentifier: CrossProcessLock.appGroupID)?
            .appendingPathComponent("locks", isDirectory: true)
        self.lockURL = base?.appendingPathComponent("\(name).lock")
    }

    enum LockError: Error {
        /// No App Group container, or the lock file could not be opened. The
        /// caller must decide; this type never silently proceeds unlocked.
        case unavailable(String)
        /// Another process held the lock for longer than the caller allowed.
        case timedOut(after: TimeInterval)
        /// The system would not keep the process running for the hold (it is
        /// being suspended). Taking the lock now is what produced 0xdead10cc.
        case suspending
        /// Background time expired during the hold; the `flock` was dropped so
        /// the process could be suspended without being killed. Anything read
        /// under the lock must not be written back.
        case revoked
    }

    /// Runs `body` while holding the lock exclusively across every process that
    /// uses the same `name`.
    ///
    /// - Parameter timeout: how long to keep trying. A share extension has about
    ///   30 seconds total, and the app may be mid-sync, so the default is short
    ///   enough to fail visibly rather than hang a UI.
    ///
    /// - Important: this THROWS rather than proceeding unlocked when the lock
    ///   cannot be taken. Falling through on failure would reintroduce exactly the
    ///   concurrent read-modify-write this class exists to prevent, and would do it
    ///   only under contention — i.e. only in the situation that matters, and never
    ///   in testing.
    func withLock<T>(timeout: TimeInterval = 8, _ body: () throws -> T) throws -> T {
        try localQueue.sync {
            guard let lockURL else {
                throw LockError.unavailable("no App Group container for \(name)")
            }

            try? FileManager.default.createDirectory(
                at: lockURL.deletingLastPathComponent(),
                withIntermediateDirectories: true)

            // O_CREAT so the first caller on a fresh install makes the file.
            // O_CLOEXEC: never let a lock descriptor outlive the hold in a child.
            let fd = open(lockURL.path, O_RDWR | O_CREAT | O_CLOEXEC, 0o644)
            guard fd >= 0 else {
                throw LockError.unavailable("open(\(lockURL.lastPathComponent)) errno=\(errno)")
            }
            defer { close(fd) }   // closing also drops the flock — runs LAST

            // 🔴 __DEAD10CC_2026_11_26__ Keep the process running for the whole
            // hold, BEFORE the lock exists. No assertion, no lock.
            stateGuard.lock()
            holdGeneration &+= 1
            let generation = holdGeneration
            holderThread = pthread_self()
            holdRevoked = false
            heldFD = -1
            stateGuard.unlock()
            defer {
                stateGuard.lock()
                holderThread = nil
                heldFD = -1
                holdRevoked = false
                stateGuard.unlock()
            }

            guard let assertion = CrossProcessLock.assertionProvider(name, { [weak self] in
                self?.revokeCurrentHold(generation: generation)
            }) else {
                os_log("🔒 CrossProcessLock '%{public}@' refused: process is being suspended",
                       log: .default, type: .error, name)
                throw LockError.suspending
            }
            defer { assertion.end() }   // after LOCK_UN (defers run in reverse)

            let deadline = Date().addingTimeInterval(timeout)
            var acquired = false
            while true {
                stateGuard.lock()
                let revokedWhileWaiting = holdRevoked
                stateGuard.unlock()
                if revokedWhileWaiting { throw LockError.suspending }

                if flock(fd, LOCK_EX | LOCK_NB) == 0 { acquired = true; break }
                if errno != EWOULDBLOCK {
                    throw LockError.unavailable("flock errno=\(errno)")
                }
                if Date() >= deadline { break }
                // 25 ms: short enough that an uncontended hand-off is imperceptible,
                // long enough not to spin a core while the other side does I/O.
                usleep(25_000)
            }

            guard acquired else {
                os_log("🔒 CrossProcessLock '%{public}@' timed out after %{public}.1fs",
                       log: .default, type: .error, name, timeout)
                throw LockError.timedOut(after: timeout)
            }

            stateGuard.lock()
            if holdRevoked {
                // Expired between the successful flock and here: give it back.
                flock(fd, LOCK_UN)
                stateGuard.unlock()
                throw LockError.suspending
            }
            heldFD = fd
            stateGuard.unlock()

            defer {
                stateGuard.lock()
                heldFD = -1
                stateGuard.unlock()
                flock(fd, LOCK_UN)
            }
            return try body()
        }
    }
}
