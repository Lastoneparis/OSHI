//
//  OfflineRangedDownload.swift
//  OSHI
//
//  __RANGED_PARALLEL_2026_10_21__
//
//  Parallel ranged download for the large map artifacts.
//
//  WHY: a single TCP connection is what limits these downloads, not the
//  server. Measured 2026-10-21 against the live host while the box was
//  otherwise idle (disk 3-12% utilised, nginx has no `limit_rate`, the only
//  other heavy process was reading 0 MB/s from page cache):
//
//      1 stream  from a home connection      2.23 MB/s
//      4 streams from the same connection    1.4 MB/s each = 5.5 MB/s
//      1 stream  from another datacenter     3.45 MB/s
//
//  So the server will serve ~5.5 MB/s and one connection simply will not pull
//  it. `fr-roadgraph.db.gz` is 4.92 GiB, which is ~40 min on one stream and
//  ~15 on four. It is also 89% of France's progress bar, so a user watching
//  that bar sees it reach 5% in seconds (tiles + POI + streets) and then
//  apparently freeze for the rest of the hour.
//
//  HOW: `Content-Length` comes from the manifest and the server advertises
//  `Accept-Ranges: bytes`, so the file is split into N contiguous ranges and
//  each is streamed straight into its own offset of ONE pre-allocated file.
//  There is deliberately no concatenation pass: separate part files would have
//  to be joined afterwards, and for France that would mean holding 4.92 GiB
//  twice on a device that was already asked for 11 GiB of headroom.
//
//  Resume survives an app kill through a small sidecar recording how many
//  bytes each part has committed, so a resumed download re-requests only the
//  remainder of each range.
//
//  This class never decides policy. It reports failure for ANY reason --
//  including a server that ignores `Range` and answers 200 -- and the caller
//  falls back to the ordinary single-task download, which still has
//  URLSession's own resume data. A ranged download that cannot work must cost
//  the user nothing beyond one HTTP response.
//

import Foundation

final class OfflineRangedDownload: NSObject {

    // MARK: - Tuning

    /// Below this, one connection is fine and the extra machinery is not worth
    /// its risk. Chosen so the metro-sized graphs (`us-il` 0.21 GiB,
    /// `ma` 1.74 GiB before contraction) behave differently from the handful of
    /// country-scale ones that actually hurt.
    static let minimumSizeForParallel: Int64 = 256 * 1024 * 1024

    /// Four, because four is what was measured to give ~2.5x. More connections
    /// against one host is both rude and, past the bandwidth-delay product,
    /// pointless.
    static let connectionCount = 4

    /// Written to disk every time a part commits this much, so a kill loses at
    /// most this many bytes per part rather than the whole range.
    private static let sidecarInterval: Int64 = 8 * 1024 * 1024

    // MARK: - State

    private struct Part {
        let index: Int
        let start: Int64
        let end: Int64          // inclusive
        var committed: Int64    // bytes already on disk for this part
        var handle: FileHandle?

        var total: Int64 { end - start + 1 }
        var isComplete: Bool { committed >= total }
    }

    private let url: URL
    private let destination: URL
    private let totalBytes: Int64
    private let sidecarURL: URL
    private let progress: (Double) -> Void
    private let completion: (Result<URL, Error>) -> Void

    private var parts: [Part] = []
    private var taskToPart: [Int: Int] = [:]
    private let lock = NSLock()
    private var finished = false
    private var failure: Error?
    private var sinceLastSidecar: Int64 = 0
    private var outstanding = 0

    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 60
        // Each connection carries at most totalBytes/connectionCount, but a
        // slow link can still take a long time over cellular. The per-request
        // timeout above is what detects a dead connection; this one only has
        // to not fire on a healthy slow one.
        config.timeoutIntervalForResource = 7200
        config.httpAdditionalHeaders = ["User-Agent": "OSHI-Messenger/1.0"]
        config.httpMaximumConnectionsPerHost = Self.connectionCount
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    // MARK: - Init

    /// - Parameters:
    ///   - url: the artifact. Must be a host that honours `Range`.
    ///   - destination: final path for the complete file. Created/extended here.
    ///   - totalBytes: authoritative size, from the manifest (NOT from a HEAD —
    ///     a HEAD is one more round trip and the manifest already carries it).
    init(url: URL,
         destination: URL,
         totalBytes: Int64,
         progress: @escaping (Double) -> Void,
         completion: @escaping (Result<URL, Error>) -> Void) {
        self.url = url
        self.destination = destination
        self.totalBytes = totalBytes
        self.sidecarURL = destination.appendingPathExtension("ranges")
        self.progress = progress
        self.completion = completion
        super.init()
    }

    // MARK: - Public

    /// True when this artifact is worth splitting. Caller checks before
    /// constructing, so the ordinary path stays the default.
    static func shouldUseParallel(totalBytes: Int64) -> Bool {
        totalBytes >= minimumSizeForParallel
    }

    func start() {
        do {
            try prepareParts()
        } catch {
            report(.failure(error))
            return
        }

        // Everything already on disk from a previous run.
        if parts.allSatisfy({ $0.isComplete }) {
            closeHandles()
            try? FileManager.default.removeItem(at: sidecarURL)
            report(.success(destination))
            return
        }

        lock.lock()
        outstanding = parts.filter { !$0.isComplete }.count
        lock.unlock()

        for part in parts where !part.isComplete {
            launch(part: part)
        }
    }

    func cancel() {
        lock.lock()
        let alreadyFinished = finished
        finished = true
        lock.unlock()
        guard !alreadyFinished else { return }
        session.invalidateAndCancel()
        writeSidecar()
        closeHandles()
    }

    // MARK: - Setup

    private func prepareParts() throws {
        let fm = FileManager.default
        try fm.createDirectory(at: destination.deletingLastPathComponent(),
                               withIntermediateDirectories: true)

        // Pre-allocate so four writers can seek into disjoint regions without
        // ever extending the file concurrently.
        if !fm.fileExists(atPath: destination.path) {
            guard fm.createFile(atPath: destination.path, contents: nil) else {
                throw Failure.cannotCreateFile(destination.lastPathComponent)
            }
        }
        let handle = try FileHandle(forWritingTo: destination)
        try handle.truncate(atOffset: UInt64(totalBytes))
        try handle.close()

        let committedByIndex = loadSidecar()

        let n = Int64(Self.connectionCount)
        let base = totalBytes / n
        var built: [Part] = []
        for i in 0..<Self.connectionCount {
            let start = Int64(i) * base
            let end = (i == Self.connectionCount - 1) ? totalBytes - 1 : start + base - 1
            let committed = min(committedByIndex[i] ?? 0, end - start + 1)
            built.append(Part(index: i, start: start, end: end,
                              committed: max(0, committed), handle: nil))
        }
        parts = built

        // One writing handle per part. Each keeps its own file offset, so the
        // four never contend on a shared cursor.
        for i in parts.indices {
            let h = try FileHandle(forWritingTo: destination)
            try h.seek(toOffset: UInt64(parts[i].start + parts[i].committed))
            parts[i].handle = h
        }
    }

    private func launch(part: Part) {
        var request = URLRequest(url: url)
        let from = part.start + part.committed
        request.setValue("bytes=\(from)-\(part.end)", forHTTPHeaderField: "Range")
        request.cachePolicy = .reloadIgnoringLocalCacheData

        let task = session.dataTask(with: request)
        lock.lock()
        taskToPart[task.taskIdentifier] = part.index
        lock.unlock()
        task.resume()
    }

    // MARK: - Sidecar

    private func loadSidecar() -> [Int: Int64] {
        guard let data = try? Data(contentsOf: sidecarURL),
              let raw = try? JSONDecoder().decode([String: Int64].self, from: data)
        else { return [:] }
        // A sidecar written for a different size (the artifact was rebuilt
        // server-side) must not be trusted: its offsets describe another file.
        guard raw["total"] == totalBytes else { return [:] }
        var out: [Int: Int64] = [:]
        for (k, v) in raw where k != "total" {
            if let i = Int(k) { out[i] = v }
        }
        return out
    }

    /// Caller holds no lock; this takes it.
    private func writeSidecar() {
        lock.lock()
        var raw: [String: Int64] = ["total": totalBytes]
        for p in parts { raw[String(p.index)] = p.committed }
        lock.unlock()
        guard let data = try? JSONEncoder().encode(raw) else { return }
        try? data.write(to: sidecarURL, options: .atomic)
    }

    // MARK: - Completion

    private func closeHandles() {
        lock.lock()
        for i in parts.indices {
            try? parts[i].handle?.close()
            parts[i].handle = nil
        }
        lock.unlock()
    }

    private func report(_ result: Result<URL, Error>) {
        lock.lock()
        if finished { lock.unlock(); return }
        finished = true
        lock.unlock()
        session.finishTasksAndInvalidate()
        completion(result)
    }

    enum Failure: LocalizedError {
        case cannotCreateFile(String)
        case rangeNotHonoured(Int)
        case shortRead(part: Int, got: Int64, want: Int64)

        var errorDescription: String? {
            switch self {
            case .cannotCreateFile(let name):
                return "Could not create \(name)"
            case .rangeNotHonoured(let status):
                // 200 here means the server sent the WHOLE file for a ranged
                // request. Falling back is the only safe response: writing a
                // full body into one part's offset would corrupt the file.
                return "Server did not honour Range (HTTP \(status))"
            case .shortRead(let part, let got, let want):
                return "Range \(part) ended early (\(got) of \(want) bytes)"
            }
        }
    }
}

// MARK: - URLSessionDataDelegate

extension OfflineRangedDownload: URLSessionDataDelegate {

    func urlSession(_ session: URLSession,
                    dataTask: URLSessionDataTask,
                    didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse else {
            completionHandler(.cancel)
            report(.failure(Failure.rangeNotHonoured(-1)))
            return
        }
        // 206 is the only acceptable answer. A 200 means Range was ignored and
        // the body is the entire artifact.
        guard http.statusCode == 206 else {
            completionHandler(.cancel)
            report(.failure(Failure.rangeNotHonoured(http.statusCode)))
            return
        }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        guard !finished, let idx = taskToPart[dataTask.taskIdentifier] else {
            lock.unlock()
            return
        }
        // Never write past this part's range: a misbehaving server that sends
        // more than it was asked for must not be allowed to scribble into the
        // next part's bytes.
        let room = parts[idx].total - parts[idx].committed
        guard room > 0 else { lock.unlock(); return }
        let slice = data.count <= Int(room) ? data : data.prefix(Int(room))
        let handle = parts[idx].handle
        parts[idx].committed += Int64(slice.count)
        sinceLastSidecar += Int64(slice.count)
        let done = parts.reduce(Int64(0)) { $0 + $1.committed }
        let shouldPersist = sinceLastSidecar >= Self.sidecarInterval
        if shouldPersist { sinceLastSidecar = 0 }
        lock.unlock()

        do {
            try handle?.write(contentsOf: slice)
        } catch {
            report(.failure(error))
            return
        }

        if shouldPersist { writeSidecar() }
        progress(Double(done) / Double(totalBytes))
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        let idx = taskToPart[task.taskIdentifier]
        if let e = error, failure == nil { failure = e }
        lock.unlock()

        if let error = error {
            // A dropped connection is not fatal on its own — the bytes already
            // committed are on disk and the sidecar records them, so a retry
            // resumes. But this attempt is over.
            writeSidecar()
            report(.failure(error))
            return
        }

        guard let idx = idx else { return }

        lock.lock()
        let short = !parts[idx].isComplete
        let got = parts[idx].committed
        let want = parts[idx].total
        outstanding -= 1
        let remaining = outstanding
        lock.unlock()

        if short {
            writeSidecar()
            report(.failure(Failure.shortRead(part: idx, got: got, want: want)))
            return
        }

        if remaining == 0 {
            writeSidecar()
            closeHandles()
            try? FileManager.default.removeItem(at: sidecarURL)
            report(.success(destination))
        }
    }
}
