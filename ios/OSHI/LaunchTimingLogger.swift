//
//  LaunchTimingLogger.swift
//  OSHI — Diagnostic tool for the "app freezes on launch / view transition"
//  reports. Records timestamps at every checkpoint in the boot pipeline
//  (app init, @StateObject inits, splash dismiss, ContentView appear,
//  ChatView appears, etc.) and emits a single line per checkpoint to
//  OshiLog.app + a rolling tail log under Documents/launch_timing.log.
//
//  When a user reports a freeze, the most recent N entries reveal which
//  step blocked the main thread — no guessing.
//

import Foundation
import os

enum LaunchTimingLogger {
    /// Process-monotonic clock. First call to `mark` from any thread
    /// triggers static-init at that moment, so the absolute "0.000s"
    /// reference is roughly app-process start.
    private static let startedAt = Date()

    /// Last checkpoint, for delta-from-previous in the log line.
    /// Protected by `lock`.
    nonisolated(unsafe) private static var lastCheckpoint: Date = Date()

    private static let lock = NSLock()

    /// Maximum lines kept in the tail log file (rolling).
    private static let maxLines = 200

    /// Hoisted: `ISO8601DateFormatter()` is expensive to build (it spins up an
    /// NSDateFormatter under the hood). Allocating one PER CHECKPOINT on the main
    /// thread meant this logger inflated the very launch it was measuring.
    private static let stamp = ISO8601DateFormatter()

    /// Every tail-file append runs here. Open/seek/write/close + a stat + an
    /// occasional whole-file rewrite is real synchronous I/O; on the main thread it
    /// showed up as the thing it was supposed to be observing.
    private static let ioQueue = DispatchQueue(label: "com.oshi.launchtiming.io", qos: .utility)

    /// Mark a checkpoint. `event` is a short stable identifier
    /// (e.g. "MessageManager.init.start", "ContentView.onAppear").
    /// `detail` is optional free-form context. Safe to call from any
    /// thread — does not touch SwiftUI state.
    static func mark(_ event: String, detail: String = "") {
        let now = Date()
        lock.lock()
        let sinceStart = now.timeIntervalSince(startedAt)
        let sinceLast = now.timeIntervalSince(lastCheckpoint)
        lastCheckpoint = now
        lock.unlock()

        let line = String(
            format: "⏱ %.3fs  Δ%.3fs  %@%@",
            sinceStart,
            sinceLast,
            event,
            detail.isEmpty ? "" : "  | \(detail)"
        )

        // Event names and timings only — no user content — so public: a
        // device console / sysdiagnose shows the numbers instead of <private>.
        OshiLog.app.infoPublic(line)
        signposter.emitEvent("LaunchMark", "\(event, privacy: .public)")
        appendToTailFile(line)
    }

    // MARK: - Signposts (__LAUNCH_LAG_2026_11_22__)
    //
    // Points of Interest, so a plain Time Profiler / App Launch trace on a
    // device shows the launch phases on the timeline with no extra setup:
    //
    //   xcrun xctrace record --device <udid> --template 'App Launch' \
    //       --launch -- Moriceau-lab.Genesis
    //
    // Interval "Launch": `App.init` → the splash mascot has finished (the main
    // UI is what is on screen). Events "LaunchMark": every `mark`.

    static let signposter = OSSignposter(subsystem: "app.oshi", category: .pointsOfInterest)
    nonisolated(unsafe) private static var launchState: OSSignpostIntervalState?

    /// Call once, as early as possible (App.init).
    static func beginLaunch() {
        lock.lock(); defer { lock.unlock() }
        guard launchState == nil else { return }
        _ = startedAt  // pin the reference clock to process start
        launchState = signposter.beginInterval("Launch")
    }

    /// The splash is gone: what the user sees is the app.
    static func endLaunch() {
        lock.lock()
        let state = launchState
        launchState = nil
        lock.unlock()
        guard let state else { return }
        signposter.endInterval("Launch", state)
        mark("Launch.splashEnded")
    }

    /// Wrap a synchronous block so its start/end (and duration) get logged.
    /// Use sparingly — only on suspected hotspots.
    @discardableResult
    static func measure<T>(_ event: String, _ block: () throws -> T) rethrows -> T {
        mark("\(event).start")
        let t0 = Date()
        let result = try block()
        let dur = Date().timeIntervalSince(t0)
        mark("\(event).end", detail: String(format: "took=%.3fs", dur))
        return result
    }

    /// Dump the on-disk size of every store that is decoded during launch. Launch
    /// cost on the media-bearing stores is proportional to these numbers, so a
    /// freeze report becomes "which file grew" instead of guesswork. Runs its own
    /// stats off the main thread.
    static func markStoreSizes() {
        let names = [
            "messages.json",
            "groupMessages_v2.json",
            "pending_decryption.json",
            "message_retry_queue.json",
            "archived_messages.json",
            "contact_presence.json"
        ]
        ioQueue.async {
            guard let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
            let fm = FileManager.default
            var parts: [String] = []
            var total = 0
            for name in names {
                // FileStorage writes `Documents/<key>.json` directly; a missing file
                // is a legitimate "user has none of these yet", not an error.
                let url = docs.appendingPathComponent(name)
                let size = (try? fm.attributesOfItem(atPath: url.path))
                    .flatMap { $0[.size] as? Int } ?? 0
                total += size
                parts.append("\(name)=\(ByteCountFormatter.string(fromByteCount: Int64(size), countStyle: .file))")
            }
            let detail = parts.joined(separator: "  ")
            let line = String(format: "📦 store sizes (total %@)  %@",
                              ByteCountFormatter.string(fromByteCount: Int64(total), countStyle: .file),
                              detail)
            OshiLog.app.info("\(line)")
            writeTail("\(stamp.string(from: Date()))  \(line)\n")
        }
    }

    // MARK: - Tail file

    private static func tailFileURL() -> URL? {
        guard let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return nil
        }
        return docs.appendingPathComponent("launch_timing.log")
    }

    private static func appendToTailFile(_ line: String) {
        let payload = "\(stamp.string(from: Date()))  \(line)\n"
        ioQueue.async { writeTail(payload) }
    }

    private static func writeTail(_ payload: String) {
        guard let url = tailFileURL() else { return }
        if FileManager.default.fileExists(atPath: url.path) {
            if let handle = try? FileHandle(forWritingTo: url) {
                _ = try? handle.seekToEnd()
                try? handle.write(contentsOf: Data(payload.utf8))
                try? handle.close()
            }
            // Roll to last `maxLines` lines if the file grew past 64 KB.
            if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
               let size = attrs[.size] as? Int,
               size > 64 * 1024 {
                if let data = try? Data(contentsOf: url),
                   let s = String(data: data, encoding: .utf8) {
                    let lines = s.split(separator: "\n").suffix(maxLines)
                    let trimmed = lines.joined(separator: "\n") + "\n"
                    _ = try? trimmed.data(using: .utf8)?.write(to: url, options: .atomic)
                }
            }
        } else {
            _ = try? payload.data(using: .utf8)?.write(to: url, options: .atomic)
        }
    }
}
