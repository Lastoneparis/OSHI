//
//  OfflineRoadGraphDownloader.swift
//  OSHI
//
//  Downloads the prebuilt road graph SQLite (per-country) that `OfflineRouter`
//  consumes for offline A* routing. Mirrors `OfflineStreetDatabase` /
//  `OfflineRoutingManager`: ObservableObject singleton with per-region
//  @Published progress/state, country-level fallback on 404, gzip decompression.
//
//  Server layout (managed by maps_pipeline/build_offline_assets.sh):
//      https://oshi-messenger.com/files/roadgraph/{country}-roadgraph.db.gz
//
//  Destination on device — matches the path `OfflineRouter.roadGraphPath`
//  already reads, so the router needs zero changes:
//      Documents/Maps/regions/{regionId}/road_graph.db
//

import Foundation
import Combine
import Compression
import CommonCrypto

final class OfflineRoadGraphDownloader: ObservableObject {

    static let shared = OfflineRoadGraphDownloader()

    /// Per-region download progress in [0, 1]. Observed by MapRegionDownloadView.
    @Published var downloadProgress: [String: Double] = [:]
    @Published var isDownloading: [String: Bool] = [:]
    @Published var downloadError: [String: String] = [:]

    /// Primary download host. Add fallback mirrors here when they go live.
    /// `mirror.oshi-messenger.com` is intentionally not in the list yet —
    /// it doesn't resolve, and a failed DNS would just stall every download.
    private static let baseURL = "https://oshi-messenger.com/files/roadgraph"

    /// Where we persist resume data so the next attempt picks up where we left
    /// off after a network drop / app kill, rather than re-downloading from 0%.
    private static let resumeDir = "OSHI_RoadGraph_Resume"

    private var activeTasks: [String: URLSessionDownloadTask] = [:]
    /// Regions whose install should abandon at the next output chunk. Guarded by
    /// `taskLock`, same as `activeTasks`.
    private var cancelledInstalls: Set<String> = []
    private let taskLock = NSLock()
    private let fileManager = FileManager.default

    /// Where a completed `.gz` is parked between "URLSession is about to delete
    /// its temp file" and "the streaming gunzip has finished with it".
    private static let stagingDir = "OSHI_RoadGraph_Staging"

    /// __RANGED_PARALLEL_2026_10_21__ Where a parallel ranged download
    /// accumulates. Deliberately NOT `stagingDir`: `sweepInterruptedInstalls`
    /// empties that directory on every launch, which would throw away a
    /// multi-GB partial download and its range sidecar — the exact progress
    /// the ranged path exists to preserve.
    private static let rangedDir = "OSHI_RoadGraph_Ranged"

    /// In-flight parallel downloads, so Cancel can stop one. Guarded by
    /// `taskLock`, same as `activeTasks`.
    private var rangedDownloads: [String: OfflineRangedDownload] = [:]

    /// Serial, off-main, low priority. Decompressing a country-scale graph is
    /// minutes of I/O; it must not run on the URLSession delegate queue (where
    /// it would block every other download's completion) and it must never run
    /// on main (docs/CALL_STACK_REFERENCE.md §1 — this codebase has already
    /// shipped a day's worth of 0x8BADF00D from exactly that).
    private let installQueue = DispatchQueue(label: "com.oshi.roadgraph.install", qos: .utility)

    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 60
        // Road graphs are big (LU pilot = 105 MB, FR may run to ~400 MB after
        // server-side polyline+graph-contraction optimisations). Give the
        // download up to an hour over cellular before the OS gives up.
        config.timeoutIntervalForResource = 3600
        config.httpAdditionalHeaders = ["User-Agent": "OSHI-Messenger/1.0"]
        return URLSession(configuration: config)
    }()

    private init() {}

    // MARK: - Public queries

    /// Whether a road_graph.db file exists for the region. Delegates to
    /// `OfflineRouter.hasGraph(forRegionId:)` so the two stay in sync.
    func isRoadGraphAvailable(regionId: String) -> Bool {
        OfflineRouter.shared.hasGraph(forRegionId: regionId)
    }

    // MARK: - Download

    /// Download the road graph for a region. Coalesces duplicate calls (when
    /// both the auto-download path in `OfflineMapManager.finalizeRegionDownload`
    /// and a manual UI tap fire for the same region).
    func downloadRoadGraph(region: MapRegion) {
        let regionId = region.id

        taskLock.lock()
        if activeTasks[regionId] != nil {
            taskLock.unlock()
            return
        }
        // A fresh request clears any standing cancellation from a previous one.
        cancelledInstalls.remove(regionId)
        taskLock.unlock()

        // Already downloaded → nothing to do.
        if isRoadGraphAvailable(regionId: regionId) {
            return
        }

        // Prefer the manifest entry. It carries three things the constructed URL
        // cannot: the resolved filename (for `in-mum` the manifest already points
        // at `in-roadgraph.db.gz`, so the constructed URL's guaranteed 404 and
        // retry are skipped), the compressed byte count (so the disk guard can
        // fire BEFORE we spend the bandwidth), and the SHA-256.
        //
        // ⚠️ `regenerate_manifest.py:100` hashes the `.db.gz` on disk, so
        // `MapFile.sha256` is the digest of the COMPRESSED artifact. Verified
        // against the live server 2026-07-28.
        let manifestFile = region.files.roadgraph
        let urlString = manifestFile?.url ?? "\(Self.baseURL)/\(regionId)-roadgraph.db.gz"

        // Pre-flight: a country-scale graph will not fit on most iPhones even
        // when streamed. Refusing here costs the user nothing; refusing at 95%
        // costs them the whole download and a full disk.
        if let compressed = manifestFile?.realSize, compressed > 0 {
            let needed = Int64(compressed)
                + Int64(Double(compressed) * GzipStreamDecompressor.roadGraphExpansionRatio)
                + GzipStreamDecompressor.diskHeadroom
            let free = MapStorageManager.shared.deviceFreeSpace()
            if free > 0 && free < needed {
                OshiLog.maps.info("[OfflineRoadGraph] refusing \(regionId): needs ~\(needed) bytes, \(free) free")
                finish(regionId: regionId,
                       error: GzipStreamError.insufficientDiskSpace(needed: needed, available: free)
                           .localizedDescription)
                return
            }
        }

        DispatchQueue.main.async {
            self.isDownloading[regionId] = true
            self.downloadProgress[regionId] = 0.0
            self.downloadError.removeValue(forKey: regionId)
        }

        // __RANGED_PARALLEL_2026_10_21__ One TCP connection is the limit on
        // these files, not the server: measured 2.23 MB/s on one stream and
        // 5.5 MB/s across four, to the same host, while its disk sat at 3-12%
        // utilisation. France's graph is 4.92 GiB and 89% of its progress bar,
        // so one stream is ~40 min of a bar that looks frozen. Only the
        // country-scale artifacts are worth splitting; everything else takes
        // the ordinary path with URLSession's own resume data.
        let declaredSize = (manifestFile?.realSize).flatMap { $0 > 0 ? Int64($0) : nil }
        if let total = declaredSize, OfflineRangedDownload.shouldUseParallel(totalBytes: total) {
            attemptRanged(
                regionId: regionId,
                urlString: urlString,
                expectedSHA256: manifestFile?.sha256,
                totalBytes: total
            )
        } else {
            attempt(
                regionId: regionId,
                urlString: urlString,
                expectedSHA256: manifestFile?.sha256,
                allowCountryFallback: true
            )
        }
    }

    /// Parallel ranged attempt, for artifacts large enough that one connection
    /// is the bottleneck.
    ///
    /// Falls back to `attempt` — the ordinary single-task download — only when
    /// the server will not honour `Range`. Any OTHER failure is reported as-is,
    /// because `OfflineRangedDownload` has already written a sidecar recording
    /// what each range committed: re-tapping Download then resumes, whereas
    /// falling through to the single path would discard those bytes and start
    /// from zero.
    private func attemptRanged(regionId: String,
                               urlString: String,
                               expectedSHA256: String?,
                               totalBytes: Int64) {
        guard let url = URL(string: urlString) else {
            finish(regionId: regionId, error: "Invalid URL")
            return
        }

        let docs = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let partial = docs
            .appendingPathComponent(Self.rangedDir, isDirectory: true)
            .appendingPathComponent("\(regionId).db.gz")

        let download = OfflineRangedDownload(
            url: url,
            destination: partial,
            totalBytes: totalBytes,
            progress: { [weak self] fraction in
                DispatchQueue.main.async { self?.downloadProgress[regionId] = fraction }
            },
            completion: { [weak self] result in
                guard let self = self else { return }
                self.taskLock.lock()
                self.rangedDownloads.removeValue(forKey: regionId)
                self.taskLock.unlock()

                switch result {
                case .failure(let error):
                    if let f = error as? OfflineRangedDownload.Failure,
                       case .rangeNotHonoured = f {
                        OshiLog.maps.info("[OfflineRoadGraph] \(regionId): server ignored Range — falling back to a single stream")
                        try? self.fileManager.removeItem(at: partial)
                        try? self.fileManager.removeItem(at: partial.appendingPathExtension("ranges"))
                        self.attempt(regionId: regionId,
                                     urlString: urlString,
                                     expectedSHA256: expectedSHA256,
                                     allowCountryFallback: true)
                    } else {
                        self.finish(regionId: regionId, error: error.localizedDescription)
                    }

                case .success(let file):
                    // Hand it to `install` exactly as the single path does, by
                    // moving it into the staging directory first — `install`
                    // owns and deletes whatever it is given.
                    let staging = docs.appendingPathComponent(Self.stagingDir, isDirectory: true)
                    do {
                        try self.fileManager.createDirectory(at: staging, withIntermediateDirectories: true)
                        let dest = staging.appendingPathComponent("\(regionId).db.gz")
                        try? self.fileManager.removeItem(at: dest)
                        try self.fileManager.moveItem(at: file, to: dest)
                        self.installQueue.async {
                            self.install(stagedGz: dest, regionId: regionId, expectedSHA256: expectedSHA256)
                        }
                    } catch {
                        self.finish(regionId: regionId,
                                    error: "Failed to install road graph: \(error.localizedDescription)")
                    }
                }
            }
        )

        taskLock.lock()
        rangedDownloads[regionId] = download
        taskLock.unlock()
        download.start()
    }

    /// Internal single attempt. Called once with the region URL; once more on
    /// 404 with the country-level URL (`fr-idf` → `fr`). Same pattern as
    /// `OfflineStreetDatabase.attemptDownload`.
    private func attempt(regionId: String,
                         urlString: String,
                         expectedSHA256: String?,
                         allowCountryFallback: Bool) {
        guard let url = URL(string: urlString) else {
            finish(regionId: regionId, error: "Invalid URL")
            return
        }

        // __MAP_STALLED_AT_24_PERCENT_2026_10_28__ Whether this attempt resumed decides
        // what we do with the resume state if it fails. See `resumeIsPoisoned`.
        let didResumeBox = UnsafeMutablePointer<Bool>.allocate(capacity: 1)
        didResumeBox.initialize(to: false)
        let task = OfflineDownloadResume.makeTask(
            session: session,
            url: url,
            namespace: Self.resumeDir,
            regionId: regionId,
            didResume: didResumeBox
        ) { [weak self] tempURL, response, error in
            let didResume = didResumeBox.pointee
            didResumeBox.deinitialize(count: 1)
            didResumeBox.deallocate()
            guard let self = self else { return }

            let httpStatus = (response as? HTTPURLResponse)?.statusCode ?? -1

            if httpStatus == 404 && allowCountryFallback,
               let dash = regionId.firstIndex(of: "-"),
               dash != regionId.startIndex {
                self.taskLock.lock()
                self.activeTasks.removeValue(forKey: regionId)
                self.taskLock.unlock()
                let country = String(regionId[..<dash])
                let fallbackURL = "\(Self.baseURL)/\(country)-roadgraph.db.gz"
                OshiLog.maps.info("[OfflineRoadGraph] \(regionId) returned 404 — retrying with country-level \(country)-roadgraph.db.gz")
                // The country file's SHA is not in this region's manifest entry,
                // so verification is skipped for the fallback — same placeholder
                // semantics as `OfflineMapManager.attemptPOIDownload`.
                self.attempt(regionId: regionId,
                             urlString: fallbackURL,
                             expectedSHA256: nil,
                             allowCountryFallback: false)
                return
            }

            if let error = error {
                // __MAP_STALLED_AT_24_PERCENT_2026_10_28__ A RESUMED attempt that failed
                // must not keep its resume state. The data embeds the server's validator
                // for the bytes already on disk; once the remote file is regenerated —
                // `fr-74-roadgraph.db.gz` was, on 2026-10-27 — that validator no longer
                // matches, every resume fails at once, the same poisoned blob is written
                // back, and the next attempt repeats it for ever. The component never
                // transfers a byte, and the region's ring just stops at whatever its other
                // components reached. Throwing the state away costs the bytes once; keeping
                // it costs the download permanently.
                //
                // A FRESH attempt that failed still keeps its data — that is the tunnel
                // case, and resuming it is the entire reason any of this is stored.
                if OfflineDownloadResume.resumeIsPoisoned(didResume: didResume) {
                    OshiLog.maps.info("[OfflineRoadGraph] \(regionId): a RESUMED attempt failed (\(error.localizedDescription)) — discarding resume state so the next try starts clean")
                    OfflineDownloadResume.clear(namespace: Self.resumeDir, regionId: regionId)
                } else if let resumeData = (error as NSError).userInfo[NSURLSessionDownloadTaskResumeData] as? Data {
                    // The OS handed us resume data (network drop, app suspend, user
                    // cancel): persist it so the next attempt picks up where we stopped.
                    OfflineDownloadResume.save(resumeData, namespace: Self.resumeDir, regionId: regionId)
                }
                self.finish(regionId: regionId, error: error.localizedDescription)
                return
            }
            guard let tempURL = tempURL else {
                self.finish(regionId: regionId, error: "Download failed")
                return
            }
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                self.finish(regionId: regionId, error: NSLocalizedString(
                    "map.regions.roadgraph.unavailable_for_country",
                    value: "Offline routing data is not yet available for this country.",
                    comment: "Soft error when neither the regional nor country-level roadgraph exists"
                ))
                return
            }

            // URLSession deletes `tempURL` the moment this handler returns, so
            // the file has to be parked somewhere stable *synchronously* before
            // the install is handed to another queue.
            let staged: URL
            do {
                staged = try self.stage(tempURL, regionId: regionId)
            } catch {
                self.finish(regionId: regionId,
                            error: "Failed to install road graph: \(error.localizedDescription)")
                return
            }

            // Everything below is minutes of I/O on a multi-GB file. Off the
            // delegate queue, off main, one region at a time.
            self.installQueue.async {
                self.install(stagedGz: staged, regionId: regionId, expectedSHA256: expectedSHA256)
            }
        }

        taskLock.lock()
        activeTasks[regionId] = task
        taskLock.unlock()

        let observation = task.progress.observe(\.fractionCompleted) { [weak self] prog, _ in
            DispatchQueue.main.async {
                self?.downloadProgress[regionId] = prog.fractionCompleted
            }
        }
        objc_setAssociatedObject(task, "progressObservation", observation, .OBJC_ASSOCIATION_RETAIN)

        task.resume()
    }

    /// Walk the resume-state directory and kick off `downloadRoadGraph` for
    /// every region that has saved progress. Call this on app launch so a
    /// download interrupted by the OS suspending the app, or a network drop,
    /// or a forced quit, automatically continues from the same byte offset
    /// without requiring the user to re-tap the download button. Matches the
    /// "auto-resume" pattern in `OfflineMapManager.resumeInterruptedDownloads`
    /// for tiles.
    func resumePendingDownloads(manifest: MapManifest?) {
        // An app kill mid-gunzip skips the decompressor's cleanup, so a `.part`
        // (and the staged `.gz` that produced it) can survive a launch. Neither
        // is at a path `hasGraph(forRegionId:)` reads, so nothing is mistaken for
        // a complete graph — but they are multi-GB, so sweep them. Do it off
        // main: it touches the filesystem.
        installQueue.async { [weak self] in self?.sweepInterruptedInstalls() }

        guard let manifest = manifest else { return }
        guard let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let resumeDirURL = docs.appendingPathComponent(Self.resumeDir, isDirectory: true)
        guard let contents = try? FileManager.default.contentsOfDirectory(atPath: resumeDirURL.path) else { return }
        for entry in contents where entry.hasSuffix(".resume") {
            let regionId = String(entry.dropLast(".resume".count))
            // Skip when the asset already landed since the last interruption
            // (e.g. user manually retried before app relaunch).
            if isRoadGraphAvailable(regionId: regionId) {
                OfflineDownloadResume.clear(namespace: Self.resumeDir, regionId: regionId)
                continue
            }
            guard let region = manifest.regions.first(where: { $0.id == regionId }) else { continue }
            OshiLog.maps.info("[OfflineRoadGraph] auto-resuming \(regionId) on launch")
            downloadRoadGraph(region: region)
        }
    }

    /// Cancel an in-flight download for the region. Safe to call when nothing is in flight.
    /// Captures resume data so the next `downloadRoadGraph` call continues
    /// from where the cancelled task left off rather than restarting at 0%.
    func cancelDownload(regionId: String) {
        taskLock.lock()
        let task = activeTasks[regionId]
        activeTasks.removeValue(forKey: regionId)
        // A parallel ranged download is not a URLSessionTask, so cancelling
        // `task` alone would leave four connections running.
        let ranged = rangedDownloads.removeValue(forKey: regionId)
        // Also stop an install that has already moved past the download stage —
        // otherwise Cancel leaves a multi-GB gunzip grinding away invisibly.
        cancelledInstalls.insert(regionId)
        taskLock.unlock()
        // Keeps its sidecar, so re-tapping Download resumes these bytes.
        ranged?.cancel()
        task?.cancel(byProducingResumeData: { data in
            if let data = data {
                OfflineDownloadResume.save(data, namespace: Self.resumeDir, regionId: regionId)
            }
        })
        DispatchQueue.main.async {
            self.isDownloading[regionId] = false
            self.downloadProgress.removeValue(forKey: regionId)
        }
    }

    /// Remove the downloaded road graph for a region, plus anything the install
    /// may have left staged. Missing any of these used to be invisible; a
    /// multi-GB `.gz` or `.part` orphaned in Documents is not.
    func deleteRoadGraph(regionId: String) {
        cancelDownload(regionId: regionId)
        let path = OfflineRouter.shared.roadGraphPath(for: regionId)
        try? fileManager.removeItem(at: path)
        try? fileManager.removeItem(at: path.appendingPathExtension("part"))
        let docs = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try? fileManager.removeItem(at: docs
            .appendingPathComponent(Self.stagingDir, isDirectory: true)
            .appendingPathComponent("\(regionId).db.gz"))
        // The ranged partial survives launch sweeps on purpose, so Delete is
        // the only thing that reclaims it — miss it and a cancelled France
        // leaves ~5 GB in Documents with nothing pointing at it.
        let ranged = docs
            .appendingPathComponent(Self.rangedDir, isDirectory: true)
            .appendingPathComponent("\(regionId).db.gz")
        try? fileManager.removeItem(at: ranged)
        try? fileManager.removeItem(at: ranged.appendingPathExtension("ranges"))
    }

    // MARK: - Internals

    private func finish(regionId: String, error: String?) {
        taskLock.lock()
        activeTasks.removeValue(forKey: regionId)
        taskLock.unlock()
        // Clear resume data only on success — keep it on failure so the next
        // attempt can pick up where this one died.
        if error == nil {
            OfflineDownloadResume.clear(namespace: Self.resumeDir, regionId: regionId)
        }
        DispatchQueue.main.async {
            self.isDownloading[regionId] = false
            if let error = error {
                self.downloadProgress.removeValue(forKey: regionId)
                self.downloadError[regionId] = error
            } else {
                self.downloadProgress[regionId] = 1.0
                self.downloadError.removeValue(forKey: regionId)
            }
        }
    }

    /// Delete `road_graph.db.part` files and staged `.gz` archives left behind
    /// when the app was killed mid-install. Never touches a completed
    /// `road_graph.db`.
    private func sweepInterruptedInstalls() {
        let docs = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]

        let staging = docs.appendingPathComponent(Self.stagingDir, isDirectory: true)
        if let entries = try? fileManager.contentsOfDirectory(at: staging, includingPropertiesForKeys: nil) {
            for entry in entries {
                OshiLog.maps.info("[OfflineRoadGraph] sweeping stale staged archive \(entry.lastPathComponent)")
                try? fileManager.removeItem(at: entry)
            }
        }

        let regions = docs.appendingPathComponent("Maps/regions", isDirectory: true)
        guard let regionDirs = try? fileManager.contentsOfDirectory(at: regions, includingPropertiesForKeys: nil) else { return }
        for dir in regionDirs {
            let part = dir.appendingPathComponent("road_graph.db.part")
            if fileManager.fileExists(atPath: part.path) {
                OshiLog.maps.info("[OfflineRoadGraph] sweeping interrupted install \(dir.lastPathComponent)/road_graph.db.part")
                try? fileManager.removeItem(at: part)
            }
        }
    }

    /// Move URLSession's temp file somewhere we own, before it is reclaimed.
    private func stage(_ tempURL: URL, regionId: String) throws -> URL {
        let docs = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dir = docs.appendingPathComponent(Self.stagingDir, isDirectory: true)
        try fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
        let dest = dir.appendingPathComponent("\(regionId).db.gz")
        try? fileManager.removeItem(at: dest)
        try fileManager.moveItem(at: tempURL, to: dest)
        return dest
    }

    /// Install a staged `.gz` into `road_graph.db`, streaming.
    ///
    /// 🔧 FIX (2026-07-28): this used to be
    ///
    ///     let gzData = try Data(contentsOf: tempURL)
    ///     let decompressed = try Self.decompressGzip(gzData)
    ///     try decompressed.write(to: destDB, options: .atomic)
    ///
    /// — peak RSS `compressed + decompressed`, with **61 of the 143 regions that
    /// advertise a road graph over 4 GB compressed** and the largest at 10.97 GiB.
    /// Against a jetsam limit of ~2–3 GiB that could not have worked for most of
    /// them. See docs/OFFLINE_MAP_REFERENCE.md §12.
    ///
    /// Must be called on `installQueue`, never on main.
    private func install(stagedGz: URL, regionId: String, expectedSHA256: String?) {
        defer { try? fileManager.removeItem(at: stagedGz) }

        let destDB = OfflineRouter.shared.roadGraphPath(for: regionId)
        do {
            let outcome = try GzipStreamDecompressor.decompress(
                from: stagedGz,
                to: destDB,
                shouldContinue: { [weak self] in
                    // The user tapping Cancel drops the task; stop unpacking too
                    // rather than spending minutes writing a graph nobody wants.
                    guard let self = self else { return false }
                    self.taskLock.lock(); defer { self.taskLock.unlock() }
                    return self.cancelledInstalls.contains(regionId) == false
                }
            )

            // Integrity. `MapFile.sha256` is the digest of the COMPRESSED
            // artifact (`regenerate_manifest.py:100` hashes the `.db.gz`), and
            // `outcome.compressedSHA256` is computed over every byte read from
            // that same file during the single decompression pass — so this
            // compares like with like, at no extra I/O.
            if let expected = expectedSHA256,
               !expected.isEmpty,
               !expected.allSatisfy({ $0 == "0" }),
               outcome.compressedSHA256.caseInsensitiveCompare(expected) != .orderedSame {
                try? fileManager.removeItem(at: destDB)
                OshiLog.maps.info("[OfflineRoadGraph] SHA-256 mismatch for \(regionId) — discarded (got \(outcome.compressedSHA256.prefix(16)), want \(expected.prefix(16)))")
                finish(regionId: regionId, error: NSLocalizedString(
                    "map.regions.roadgraph.checksum_mismatch",
                    value: "The downloaded routing data failed its integrity check and was discarded.",
                    comment: "Error when a road graph's SHA-256 does not match the manifest"))
                return
            }

            OshiLog.maps.info("[OfflineRoadGraph] installed \(regionId): \(outcome.compressedBytes) B gz → \(outcome.decompressedBytes) B db")
            finish(regionId: regionId, error: nil)
        } catch {
            // `GzipStreamDecompressor` removes its own `.part` on every failure
            // path, so nothing partial is left for `hasGraph(forRegionId:)`.
            let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            OshiLog.maps.info("[OfflineRoadGraph] install failed for \(regionId): \(message)")
            finish(regionId: regionId, error: message)
        }
    }
}

// ==========================================================================
//  Streaming gunzip: file → file, in bounded chunks, with a disk-space guard.
//
//  WHY THIS EXISTS (measured 2026-07-28, see docs/OFFLINE_MAP_REFERENCE.md §12)
//  --------------------------------------------------------------------------
//  `OfflineRoadGraphDownloader` used to install a road graph like this:
//
//      let gzData = try Data(contentsOf: tempURL)          // whole .gz in RAM
//      let decompressed = try Self.decompressGzip(gzData)  // whole .db in RAM
//      try decompressed.write(to: destDB, options: .atomic)
//
//  Peak RSS is therefore `compressed + decompressed`, both resident at once.
//  The live manifest advertises a road graph for 143 regions; **61 of them are
//  over 4 GB compressed** and the largest, `in-roadgraph.db.gz`, is 10.97 GiB
//  compressed. Against an iOS jetsam limit of roughly 2–3 GiB, that path could
//  never have completed for most of the regions that advertise it. nginx runs
//  `access_log off`, so there is no server-side evidence that it ever did.
//
//  This type replaces both halves with a fixed-size pipeline:
//
//      file → 256 KiB read buffer → compression_stream → 1 MiB write buffer → file
//
//  Peak RSS is the sum of those buffers (~1.3 MiB) plus the SHA-256 context,
//  and is INDEPENDENT of the file size. Nothing is ever accumulated in `Data`.
//
//  INTEGRITY
//  ---------
//  `maps_pipeline/regenerate_manifest.py:100` computes `sha256_file(path)` where
//  `path` is the `.db.gz` on disk. **The manifest hash covers the COMPRESSED
//  artifact**, not the decompressed database. So this type hashes every byte it
//  reads from the source file — including the gzip header and trailer — and
//  returns that digest for the caller to compare against `MapFile.sha256`.
//  Hashing happens on the read path, so it costs one pass, not two.
//
//  On top of that, the gzip ISIZE trailer is always checked (it catches a
//  truncated stream for free), and CRC-32 of the decompressed bytes is checked
//  only when the caller has no expected SHA-256 to compare against — otherwise
//  it would be a redundant second pass over the same guarantee, and CRC-32 in
//  Swift is markedly slower than hardware-accelerated CC_SHA256.
//
//  THREADING
//  ---------
//  Synchronous and blocking by design. It must NEVER be called from the main
//  thread: a 28 GiB decompression on main is a guaranteed `0x8BADF00D`
//  (docs/CALL_STACK_REFERENCE.md §1). Callers dispatch to a utility queue.
//
// MARK: - Errors

enum GzipStreamError: LocalizedError {
    /// Source does not start with the gzip magic `1f 8b`.
    case notGzip
    /// Malformed header, bad deflate stream, or a trailer that disagrees with
    /// what we produced.
    case corrupt(String)
    /// Refused to start (or continue) because the volume would not hold the
    /// decompressed result. Sizes in bytes.
    case insufficientDiskSpace(needed: Int64, available: Int64)
    /// The caller's `shouldContinue` closure returned false.
    case cancelled
    case ioFailure(String)

    var errorDescription: String? {
        switch self {
        case .notGzip:
            return NSLocalizedString(
                "map.regions.roadgraph.not_gzip",
                value: "The downloaded routing data is not in the expected format.",
                comment: "Error when a downloaded .db.gz does not start with the gzip magic bytes")
        case .corrupt(let detail):
            return String(format: NSLocalizedString(
                "map.regions.roadgraph.corrupt_format",
                value: "The downloaded routing data is damaged and was discarded (%@).",
                comment: "Error when a downloaded road graph fails its integrity check. %@ is a short technical detail."),
                detail)
        case .insufficientDiskSpace(let needed, let available):
            let f = ByteCountFormatter()
            f.countStyle = .file
            return String(format: NSLocalizedString(
                "map.regions.roadgraph.not_enough_space",
                value: "Not enough space to install offline routing for this area. It needs about %1$@ and only %2$@ is free. Try a smaller area, or free up space.",
                comment: "Error shown before installing a road graph when the device lacks disk space. %1$@ required, %2$@ available."),
                f.string(fromByteCount: needed), f.string(fromByteCount: available))
        case .cancelled:
            return NSLocalizedString(
                "map.regions.roadgraph.install_cancelled",
                value: "Installing offline routing was cancelled.",
                comment: "Error when road graph installation is cancelled mid-way")
        case .ioFailure(let detail):
            return String(format: NSLocalizedString(
                "map.regions.roadgraph.install_io_error",
                value: "Could not write the offline routing data (%@).",
                comment: "Error when writing the decompressed road graph to disk fails. %@ is the underlying reason."),
                detail)
        }
    }
}

// MARK: - Decompressor

enum GzipStreamDecompressor {

    /// Bytes pulled from the source file per iteration. Also the size of the
    /// staging buffer handed to `compression_stream_process` as input.
    static let inputBufferSize = 256 * 1024

    /// Bytes `compression_stream_process` may produce per iteration before we
    /// flush them to the destination.
    static let outputBufferSize = 1024 * 1024

    /// Free space we refuse to consume, so a road graph install cannot be the
    /// thing that makes the device unusable. iOS starts misbehaving badly below
    /// a few hundred MB.
    static let diskHeadroom: Int64 = 300 * 1024 * 1024

    /// How often (in decompressed bytes) the running disk-space check fires.
    /// The pre-flight estimate can be wrong; this one cannot.
    static let diskCheckInterval: Int64 = 64 * 1024 * 1024

    /// Observed compression ratio for the road-graph SQLite files produced by
    /// `maps_pipeline/build_offline_assets.sh` (`gzip -f` over a SQLite DB).
    /// Used only to size the pre-flight disk guard — the running guard is what
    /// actually protects the volume, so an inaccurate ratio degrades the error
    /// message, not the safety.
    ///
    /// Measured 2026-07-28 against the live `lu-roadgraph.db.gz`:
    /// 109 914 707 B compressed → 290 451 456 B decompressed = **2.642**.
    /// Only consulted above 4 GiB — below that the gzip ISIZE trailer is exact
    /// and is used instead (see `estimatedDecompressedSize`).
    static let roadGraphExpansionRatio: Double = 2.65

    struct Outcome {
        /// Bytes written to the destination.
        let decompressedBytes: Int64
        /// Bytes read from the source (i.e. the compressed artifact size).
        let compressedBytes: Int64
        /// Lowercase hex SHA-256 of the **compressed** source file, which is
        /// what `MapFile.sha256` declares.
        let compressedSHA256: String
    }

    // MARK: Public

    /// Decompress the gzip file at `source` into `destination`, streaming.
    ///
    /// The destination is written through a sibling `.part` file and moved into
    /// place only after the stream ends cleanly and the trailer verifies, so an
    /// interrupted install can never be mistaken for a finished graph. This
    /// mirrors the staging convention already used for blob chunks in
    /// `V2Client.swift:1014`.
    ///
    /// - Parameters:
    ///   - source: the downloaded `.gz`. Not modified or deleted.
    ///   - destination: final path. Any existing file (or stale `.part`) is removed.
    ///   - freeSpace: injectable free-space probe, in bytes. Defaults to the
    ///     volume holding `destination`. Overridden in tests to prove the guard fires.
    ///   - shouldContinue: polled once per output chunk; return false to abort.
    ///   - progress: called with cumulative decompressed bytes, at most once per
    ///     output chunk. Called on the calling thread.
    /// - Returns: byte counts and the SHA-256 of the compressed input.
    @discardableResult
    static func decompress(
        from source: URL,
        to destination: URL,
        freeSpace: (() -> Int64)? = nil,
        shouldContinue: () -> Bool = { true },
        progress: ((Int64) -> Void)? = nil
    ) throws -> Outcome {

        let fm = FileManager.default
        let partURL = destination.appendingPathExtension("part")

        let probe: () -> Int64 = freeSpace ?? {
            let dir = destination.deletingLastPathComponent()
            let values = try? dir.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            return Int64(values?.volumeAvailableCapacityForImportantUsage ?? 0)
        }

        let compressedSize = (try? fm.attributesOfItem(atPath: source.path)[.size] as? Int64) ?? 0

        // ── Pre-flight disk guard ────────────────────────────────────────────
        // Refuse before writing a single byte. A country-scale graph will not
        // fit on most iPhones even when streamed, and finding that out at 90%
        // means the user has already spent the bandwidth AND filled the device.
        let estimate = estimatedDecompressedSize(ofGzipAt: source, compressedSize: compressedSize)
        let available = probe()
        if available > 0, available < estimate + diskHeadroom {
            throw GzipStreamError.insufficientDiskSpace(needed: estimate + diskHeadroom,
                                                        available: available)
        }

        // ── Staging ──────────────────────────────────────────────────────────
        try? fm.removeItem(at: partURL)
        try fm.createDirectory(at: destination.deletingLastPathComponent(),
                               withIntermediateDirectories: true)
        guard fm.createFile(atPath: partURL.path, contents: nil) else {
            throw GzipStreamError.ioFailure("could not create \(partURL.lastPathComponent)")
        }

        // Anything that leaves this function without succeeding must not leave a
        // partial file behind that `OfflineRouter.hasGraph(forRegionId:)` might
        // later open.
        var succeeded = false
        defer { if !succeeded { try? fm.removeItem(at: partURL) } }

        // ⚠️⚠️ POSIX file descriptors, NOT `FileHandle`. Measured 2026-07-28 on a
        // 1 GiB file, `/usr/bin/time -l`, peak memory footprint:
        //
        //      FileHandle.read(upToCount: 256 KiB) in a loop  → 1030.7 MB
        //      read(2) into a fixed buffer in a loop          →     1.8 MB
        //      InputStream.read(_:maxLength:)                 →     1.8 MB
        //
        // `FileHandle` charges the whole file to `phys_footprint` — the exact
        // quantity iOS jetsam kills on — so streaming through it would have
        // reproduced the very bug this change exists to fix, while *looking*
        // like a fix. Do not "simplify" this back to FileHandle.
        let inFD = open(source.path, O_RDONLY)
        guard inFD >= 0 else {
            throw GzipStreamError.ioFailure("could not open \(source.lastPathComponent)")
        }

        let outFD = open(partURL.path, O_WRONLY | O_CREAT | O_TRUNC, 0o644)
        guard outFD >= 0 else {
            close(inFD)
            throw GzipStreamError.ioFailure("could not open \(partURL.lastPathComponent) for writing")
        }
        var outClosed = false
        defer { if !outClosed { close(outFD) } }

        // ⚠️ The read side lives in `GzipSourceReader`, not in local functions
        // capturing local `var`s. That is not a style choice: the closure-capture
        // version crashed the Swift 6.2.4 optimiser outright
        // ("Found a leak due to a consuming post-dominance failure",
        // CopyPropagation) — a *Release-only* failure that a Debug build compiles
        // happily. Keep the state in a class.
        let reader = GzipSourceReader(fd: inFD, capacity: inputBufferSize)
        defer { reader.close() }

        // ── Fixed-size output buffer. Together with the reader's input buffer
        //    this is the entire memory cost of the operation. ─────────────────
        let dstBuf = UnsafeMutablePointer<UInt8>.allocate(capacity: outputBufferSize)
        defer { dstBuf.deallocate() }

        var totalOut: Int64 = 0
        var lastDiskCheck: Int64 = 0
        var crc = CRC32()

        // ── The gzip trailer is read from the END of the file, not from the
        //    stream. That is not an optimisation, it is a correctness fix
        //    (measured 2026-07-28): libcompression's raw-DEFLATE decoder
        //    *consumes* the 8 trailer bytes as ordinary input and reports END
        //    with `src_size == 0`, so they are simply gone by the time you look
        //    for them. Reading them from a known offset is unambiguous, costs
        //    one 8-byte positioned read, and is what lets the CRC and length be
        //    checked at all.
        //
        //    This makes the implementation deliberately single-member. The
        //    pipeline that produces these files uses `gzip -f`
        //    (`maps_pipeline/build_offline_assets.sh:234`), which emits exactly
        //    one member. Concatenated streams are rejected below rather than
        //    silently truncated.
        guard let trailer = readTrailer(at: source) else {
            throw GzipStreamError.corrupt("missing trailer")
        }

        // ── Header ──────────────────────────────────────────────────────────
        let magic = try reader.readExact(2)
        guard magic.count == 2, magic[0] == 0x1f, magic[1] == 0x8b else {
            throw GzipStreamError.notGzip
        }
        let fixed = try reader.readExact(8)   // CM, FLG, MTIME(4), XFL, OS
        guard fixed.count == 8 else { throw GzipStreamError.corrupt("truncated header") }
        guard fixed[0] == 8 else { throw GzipStreamError.corrupt("unsupported compression method") }
        let headerFlags = fixed[1]
        if headerFlags & 0x04 != 0 {                     // FEXTRA
            let xlenBytes = try reader.readExact(2)
            guard xlenBytes.count == 2 else { throw GzipStreamError.corrupt("truncated FEXTRA") }
            let xlen = Int(xlenBytes[0]) | (Int(xlenBytes[1]) << 8)
            guard try reader.readExact(xlen).count == xlen else {
                throw GzipStreamError.corrupt("truncated FEXTRA")
            }
        }
        if headerFlags & 0x08 != 0 { try reader.skipZeroTerminated() }   // FNAME
        if headerFlags & 0x10 != 0 { try reader.skipZeroTerminated() }   // FCOMMENT
        if headerFlags & 0x02 != 0 {                                     // FHCRC
            guard try reader.readExact(2).count == 2 else {
                throw GzipStreamError.corrupt("truncated FHCRC")
            }
        }

        // ── Deflate body ────────────────────────────────────────────────────
        // COMPRESSION_ZLIB in libcompression is *raw* DEFLATE (RFC 1951) — the
        // gzip wrapper we just consumed is exactly what it does not want.
        var stream = compression_stream(
            dst_ptr: dstBuf, dst_size: outputBufferSize,
            src_ptr: reader.buffer, src_size: 0,
            state: nil)
        guard compression_stream_init(&stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB)
                == COMPRESSION_STATUS_OK else {
            throw GzipStreamError.corrupt("decoder init failed")
        }
        defer { compression_stream_destroy(&stream) }

        body: while true {
            guard shouldContinue() else { throw GzipStreamError.cancelled }

            if reader.isBufferDrained && !reader.isAtEOF {
                _ = try reader.refill()
            }

            stream.src_ptr = UnsafePointer(reader.buffer + reader.offset)
            stream.src_size = reader.available
            stream.dst_ptr = dstBuf
            stream.dst_size = outputBufferSize

            let flags: Int32 = reader.isAtEOF ? Int32(COMPRESSION_STREAM_FINALIZE.rawValue) : 0
            let status = compression_stream_process(&stream, flags)

            let produced = outputBufferSize - stream.dst_size
            reader.consume(to: stream.src_size)

            if produced > 0 {
                // write(2) may write short; loop until the chunk is out.
                var written = 0
                while written < produced {
                    let n = write(outFD, dstBuf + written, produced - written)
                    if n <= 0 {
                        if errno == EINTR { continue }
                        throw GzipStreamError.ioFailure(String(cString: strerror(errno)))
                    }
                    written += n
                }
                crc.update(dstBuf, produced)
                totalOut += Int64(produced)
                progress?(totalOut)

                // ── Running disk guard ───────────────────────────────────────
                // The pre-flight estimate is an estimate. This is not: if the
                // volume is actually running out, stop now and unwind, rather
                // than filling the device and failing at write().
                if totalOut - lastDiskCheck >= diskCheckInterval {
                    lastDiskCheck = totalOut
                    let free = probe()
                    if free > 0 && free < diskHeadroom {
                        throw GzipStreamError.insufficientDiskSpace(needed: diskHeadroom,
                                                                    available: free)
                    }
                }
            }

            switch status {
            case COMPRESSION_STATUS_OK:
                // No forward progress and no input left → truncated stream.
                if produced == 0 && stream.src_size == 0 && reader.isAtEOF {
                    throw GzipStreamError.corrupt("truncated deflate stream")
                }
                continue body
            case COMPRESSION_STATUS_END:
                break body
            default:
                throw GzipStreamError.corrupt("deflate error")
            }
        }

        // ── Integrity of the decompressed bytes ─────────────────────────────
        // ISIZE is the decompressed length mod 2^32 — it costs nothing and it
        // catches the realistic failure, a stream that stopped early. CRC-32 is
        // the content check; it is the ONLY content check on the country-level
        // fallback path, where the caller has no manifest SHA-256 to compare.
        guard UInt32(truncatingIfNeeded: totalOut) == trailer.isize else {
            throw GzipStreamError.corrupt("size mismatch")
        }
        guard crc.checksum == trailer.crc else {
            throw GzipStreamError.corrupt("CRC mismatch")
        }

        // Anything beyond the 8-byte trailer means a concatenated / multi-member
        // archive, which this single-member implementation would silently
        // truncate. Refuse instead. (`gzip -f` never produces one; `pigz -i` and
        // `cat a.gz b.gz` do.)
        let leftover = (compressedSize - reader.totalBytesRead) + Int64(reader.available)
        guard leftover <= 8 else {
            throw GzipStreamError.corrupt("unexpected trailing data (\(leftover) bytes)")
        }

        // Drain anything left so the SHA-256 covers the whole file, byte for byte.
        try reader.drain()

        guard fsync(outFD) == 0 else {
            throw GzipStreamError.ioFailure(String(cString: strerror(errno)))
        }
        close(outFD)
        outClosed = true

        let hex = reader.finalizeSHA256()

        // Atomic publish: the graph appears at its final path only now.
        try? fm.removeItem(at: destination)
        do { try fm.moveItem(at: partURL, to: destination) }
        catch { throw GzipStreamError.ioFailure(error.localizedDescription) }

        succeeded = true
        return Outcome(decompressedBytes: totalOut,
                       compressedBytes: reader.totalBytesRead,
                       compressedSHA256: hex)
    }

    /// Best-effort decompressed size, for the pre-flight disk guard only.
    ///
    /// gzip's ISIZE trailer is the true size **mod 2^32**: exact below 4 GiB,
    /// ambiguous above it — and the graphs that matter here are far above it.
    /// So ISIZE is used to pick the right 4 GiB bucket around a ratio-based
    /// estimate, rather than trusted on its own.
    static func estimatedDecompressedSize(ofGzipAt url: URL, compressedSize: Int64) -> Int64 {
        let ratioEstimate = Int64(Double(compressedSize) * roadGraphExpansionRatio)
        guard let isize = readISize(at: url) else { return ratioEstimate }

        let wrap: Int64 = 1 << 32
        if ratioEstimate < wrap {
            // Below 4 GiB the trailer is exact; prefer it, but never let a
            // wrapped-looking value make the guard smaller than physics allows.
            return max(Int64(isize), ratioEstimate)
        }
        let buckets = ((ratioEstimate - Int64(isize)) + wrap / 2) / wrap
        return Int64(isize) + max(0, buckets) * wrap
    }

    /// The 8-byte gzip trailer: CRC-32 then ISIZE, both little-endian.
    /// Read by position from the end of the file — see the note in `decompress`
    /// about why it cannot be read from the stream.
    static func readTrailer(at url: URL) -> (crc: UInt32, isize: UInt32)? {
        guard let h = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? h.close() }
        guard let end = try? h.seekToEnd(), end >= 18 else { return nil }
        try? h.seek(toOffset: end - 8)
        guard let tail = try? h.read(upToCount: 8), tail.count == 8 else { return nil }
        let b = [UInt8](tail)
        func le(_ i: Int) -> UInt32 {
            UInt32(b[i]) | (UInt32(b[i + 1]) << 8) | (UInt32(b[i + 2]) << 16) | (UInt32(b[i + 3]) << 24)
        }
        return (crc: le(0), isize: le(4))
    }

    /// Last 4 bytes of a gzip file = ISIZE.
    private static func readISize(at url: URL) -> UInt32? {
        readTrailer(at: url)?.isize
    }

}

// MARK: - Buffered source

/// The read half of the streaming gunzip: one fixed-size buffer over a
/// `FileHandle`, hashing every byte it pulls off disk so the SHA-256 of the
/// compressed artifact comes free with the single decompression pass.
///
/// This is a class rather than a set of local functions closing over local
/// `var`s because the closure version miscompiled: Swift 6.2.4's CopyPropagation
/// pass aborts with "Found a leak due to a consuming post-dominance failure" on
/// the `-O` build only. Debug compiles it fine, which is exactly the kind of
/// difference that ships.
private final class GzipSourceReader {

    let buffer: UnsafeMutablePointer<UInt8>

    private let fd: Int32
    private let capacity: Int
    private var filled = 0
    private var context = CC_SHA256_CTX()
    private var closed = false

    /// Bytes of `buffer` already handed to the decoder.
    private(set) var offset = 0
    private(set) var isAtEOF = false
    private(set) var totalBytesRead: Int64 = 0

    init(fd: Int32, capacity: Int) {
        self.fd = fd
        self.capacity = capacity
        self.buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: capacity)
        CC_SHA256_Init(&context)
    }

    deinit {
        buffer.deallocate()
    }

    /// Unconsumed bytes currently in the buffer.
    var available: Int { filled - offset }

    var isBufferDrained: Bool { offset == filled }

    func close() {
        guard !closed else { return }
        closed = true
        _ = Darwin.close(fd)
    }

    /// Pull the next chunk off disk straight into `buffer`, hashing it on the
    /// way past. No intermediate `Data`, so nothing here scales with file size.
    @discardableResult
    func refill() throws -> Bool {
        guard !isAtEOF else { return false }
        var n = 0
        while true {
            n = read(fd, buffer, capacity)
            if n < 0 && errno == EINTR { continue }
            break
        }
        if n < 0 { throw GzipStreamError.ioFailure(String(cString: strerror(errno))) }
        guard n > 0 else {
            isAtEOF = true
            filled = 0
            offset = 0
            return false
        }
        _ = CC_SHA256_Update(&context, buffer, CC_LONG(n))
        totalBytesRead += Int64(n)
        filled = n
        offset = 0
        return true
    }

    /// Mark bytes as consumed, given the decoder's *remaining* `src_size`.
    func consume(to remaining: Int) {
        offset = filled - remaining
    }

    /// Exactly `count` bytes, refilling as needed. Returns fewer only at EOF.
    /// Used for the fixed-size header and trailer fields, never in the hot loop.
    func readExact(_ count: Int) throws -> [UInt8] {
        var out = [UInt8]()
        out.reserveCapacity(count)
        while out.count < count {
            if isBufferDrained {
                guard try refill() else { break }
            }
            let take = min(count - out.count, available)
            out.append(contentsOf: UnsafeBufferPointer(start: buffer + offset, count: take))
            offset += take
        }
        return out
    }

    /// Skip a NUL-terminated gzip header string (FNAME / FCOMMENT).
    func skipZeroTerminated() throws {
        while true {
            let b = try readExact(1)
            guard b.count == 1 else { throw GzipStreamError.corrupt("truncated header string") }
            if b[0] == 0 { return }
        }
    }

    /// Read to EOF so the digest covers the whole file, byte for byte.
    func drain() throws {
        while try refill() { offset = filled }
    }

    func finalizeSHA256() -> String {
        var digest = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        CC_SHA256_Final(&digest, &context)
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}

// MARK: - CRC-32

/// Table-driven CRC-32 (the gzip polynomial). Only used on the decompressed
/// bytes, one pass, alongside the write.
struct CRC32 {
    private static let table: [UInt32] = (0..<256).map { i -> UInt32 in
        var c = UInt32(i)
        for _ in 0..<8 { c = (c & 1) != 0 ? (0xEDB8_8320 ^ (c >> 1)) : (c >> 1) }
        return c
    }

    private var state: UInt32 = 0xFFFF_FFFF

    mutating func update(_ bytes: UnsafePointer<UInt8>, _ count: Int) {
        var c = state
        CRC32.table.withUnsafeBufferPointer { t in
            for i in 0..<count {
                c = t[Int((c ^ UInt32(bytes[i])) & 0xFF)] ^ (c >> 8)
            }
        }
        state = c
    }

    var checksum: UInt32 { state ^ 0xFFFF_FFFF }
}
