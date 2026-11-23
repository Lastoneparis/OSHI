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
            let fd = open(lockURL.path, O_RDWR | O_CREAT, 0o644)
            guard fd >= 0 else {
                throw LockError.unavailable("open(\(lockURL.lastPathComponent)) errno=\(errno)")
            }
            defer { close(fd) }   // closing also drops the flock

            let deadline = Date().addingTimeInterval(timeout)
            var acquired = false
            while true {
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

            defer { flock(fd, LOCK_UN) }
            return try body()
        }
    }
}
