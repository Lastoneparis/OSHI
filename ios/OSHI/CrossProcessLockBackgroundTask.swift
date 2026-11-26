//
//  CrossProcessLockBackgroundTask.swift
//  OSHI (app target only — extensions cannot reference `UIApplication.shared`)
//
//  🔴 __DEAD10CC_2026_11_26__ The app's assertion provider for `CrossProcessLock`.
//  Every hold of a lock file in the App Group container runs inside a
//  `beginBackgroundTask`, so iOS does not suspend the app with the `flock` taken —
//  which RunningBoard punishes with SIGKILL 0xdead10cc (16 kills on the owner's
//  iPhone, 05/11 → 24/11, all inside `withPeerSession`). See the long note in
//  `CrossProcessLock.swift`.
//

import UIKit

enum CrossProcessLockBackgroundTask {

    /// Installed from `didFinishLaunchingWithOptions`, before anything can take a
    /// ratchet lock.
    static func install() {
        CrossProcessLock.assertionProvider = provider
    }

    /// `beginBackgroundTask` is documented as callable from any thread; the
    /// expiration handler runs on the main thread.
    static func provider(name: String, onExpire: @escaping () -> Void) -> CrossProcessLock.HoldAssertion? {
        final class Task {
            let lock = NSLock()
            var id: UIBackgroundTaskIdentifier = .invalid
            var ended = false
            var expiredEarly = false

            /// Ends the task exactly once. Returns true if THIS call ended it.
            @discardableResult
            func end() -> Bool {
                lock.lock()
                guard !ended else { lock.unlock(); return false }
                ended = true
                let id = self.id
                lock.unlock()
                if id != .invalid { UIApplication.shared.endBackgroundTask(id) }
                return true
            }
        }
        let task = Task()
        let id = UIApplication.shared.beginBackgroundTask(withName: "oshi.xproclock.\(name)") {
            task.lock.lock()
            let alreadyEnded = task.ended
            let assigned = task.id != .invalid
            if !assigned { task.expiredEarly = true }
            task.lock.unlock()
            // Drop the kernel lock BEFORE ending the task: once the task ends the
            // app may be suspended immediately.
            if !alreadyEnded { onExpire() }
            task.end()
        }
        // `.invalid` = the app is already being suspended. Refuse the hold.
        guard id != .invalid else { return nil }
        task.lock.lock()
        task.id = id
        let early = task.expiredEarly
        task.lock.unlock()
        if early {
            // Expired before we even stored the id: end it and refuse.
            task.lock.lock(); task.ended = true; task.lock.unlock()
            UIApplication.shared.endBackgroundTask(id)
            return nil
        }
        return CrossProcessLock.HoldAssertion { task.end() }
    }
}
