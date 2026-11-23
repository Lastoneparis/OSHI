//
//  OfflineDownloadResume.swift
//  OSHI
//
//  Shared helper to persist URLSessionDownloadTask resume data across app
//  kills / network drops, so the next attempt at the same regional asset
//  picks up where the user left off instead of starting from 0%.
//
//  All three offline-asset singletons use this (street, roadgraph, routing).
//

import Foundation

/// Per-singleton namespace so resume-data files from different downloaders
/// don't collide. Caller passes its own key (e.g. "roadgraph", "streets").
struct OfflineDownloadResume {

    private static let fileManager = FileManager.default

    /// Documents/<namespace>/{regionId}.resume — chosen so the data survives
    /// app updates (Documents persists across App Store re-installs as long
    /// as the app isn't fully uninstalled) and a kill mid-download.
    private static func resumeFile(namespace: String, regionId: String) -> URL? {
        guard let docs = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return nil
        }
        let dir = docs.appendingPathComponent(namespace, isDirectory: true)
        if !fileManager.fileExists(atPath: dir.path) {
            try? fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir.appendingPathComponent("\(regionId).resume")
    }

    /// Save resume data emitted by `task.cancel(byProducingResumeData:)`.
    /// Safe to call from any thread.
    static func save(_ data: Data, namespace: String, regionId: String) {
        guard let path = resumeFile(namespace: namespace, regionId: regionId) else { return }
        try? data.write(to: path, options: .atomic)
        OshiLog.maps.info("[Resume] saved \(data.count) bytes for \(namespace)/\(regionId)")
    }

    /// Load resume data for the next download attempt. Returns nil when
    /// there's no saved data (fresh download).
    static func load(namespace: String, regionId: String) -> Data? {
        guard let path = resumeFile(namespace: namespace, regionId: regionId),
              fileManager.fileExists(atPath: path.path) else { return nil }
        return try? Data(contentsOf: path)
    }

    /// Clear resume data — call once the download has completed successfully
    /// (or when the user deletes the region) so we don't try to resume into
    /// an obsolete asset on the next attempt.
    static func clear(namespace: String, regionId: String) {
        guard let path = resumeFile(namespace: namespace, regionId: regionId) else { return }
        try? fileManager.removeItem(at: path)
    }

    /// Helper used by all three downloaders to start a task that automatically
    /// resumes when possible. Pass `session`, `url`, `namespace`, `regionId`,
    /// and a completion that gets the final URL/response/error from URLSession.
    ///
    /// Resume behavior:
    ///   - if `load(namespace:regionId:)` returns Data → `session.downloadTask(withResumeData:)`
    ///   - else → `session.downloadTask(with: url)`
    /// On cancel / network error, the caller is responsible for capturing
    /// the resume data via `task.cancel(byProducingResumeData:)` and feeding
    /// it to `save(...)`.
    static func makeTask(
        session: URLSession,
        url: URL,
        namespace: String,
        regionId: String,
        completion: @escaping (URL?, URLResponse?, Error?) -> Void
    ) -> URLSessionDownloadTask {
        makeTask(session: session, url: url, namespace: namespace, regionId: regionId,
                 didResume: nil, completion: completion)
    }

    /// As above, but tells the caller whether it actually resumed.
    ///
    /// __MAP_STALLED_AT_24_PERCENT_2026_10_28__ The caller needs to know, because the
    /// right response to a FAILED resume is the opposite of the right response to a failed
    /// fresh download: see [resumeIsPoisoned].
    static func makeTask(
        session: URLSession,
        url: URL,
        namespace: String,
        regionId: String,
        didResume: UnsafeMutablePointer<Bool>?,
        completion: @escaping (URL?, URLResponse?, Error?) -> Void
    ) -> URLSessionDownloadTask {
        if let resumeData = load(namespace: namespace, regionId: regionId) {
            OshiLog.maps.info("[Resume] resuming \(namespace)/\(regionId) (\(resumeData.count) bytes of state)")
            didResume?.pointee = true
            return session.downloadTask(withResumeData: resumeData, completionHandler: completion)
        }
        didResume?.pointee = false
        return session.downloadTask(with: url, completionHandler: completion)
    }

    /// Must the stored resume state be thrown away rather than kept for the next attempt?
    ///
    /// __MAP_STALLED_AT_24_PERCENT_2026_10_28__ THE BUG THIS EXISTS FOR, and it is not
    /// hypothetical — it is what a user hit on `fr-74`.
    ///
    /// The failure path used to be the same for both kinds of attempt: save the resume data
    /// and report the error. That is right for a fresh download interrupted by a tunnel,
    /// and catastrophic for a resume that FAILED BECAUSE IT COULD NOT BE RESUMED. Resume
    /// data embeds the server's validator for the bytes already on disk; when the remote
    /// file is regenerated — `fr-74-roadgraph.db.gz` was, on 2026-10-27 — that validator no
    /// longer matches, so every resume fails instantly, re-saves the same poisoned blob,
    /// and the next attempt repeats it. Forever. The component never transfers a byte, and
    /// because nothing read `downloadError`, the region's ring simply stopped at the
    /// percentage its other components had already reached.
    ///
    /// So: **a resumed attempt that failed never gets to keep its resume data.** One retry
    /// from scratch costs the bytes once; an unresumable blob costs the feature permanently.
    /// A FRESH attempt that failed keeps its data as before — that is the tunnel case, and
    /// resuming it is the whole point of storing any of this.
    static func resumeIsPoisoned(didResume: Bool) -> Bool { didResume }
}
