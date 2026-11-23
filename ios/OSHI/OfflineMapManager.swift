import Foundation
import CommonCrypto
import Combine

class OfflineMapManager: NSObject, ObservableObject, URLSessionDownloadDelegate {

    static let shared = OfflineMapManager()

    private static let manifestURL = URL(string: "https://oshi-messenger.com/maps/v2/manifest.json")!

    // MARK: - Published State

    @Published var manifest: MapManifest?
    @Published var downloadStates: [String: RegionDownloadState] = [:]
    @Published var downloadedRegions: [DownloadedRegionMetadata] = []
    @Published var totalStorageUsed: Int = 0
    @Published var isLoadingManifest = false

    /// Identifier for the background URLSession that long-haul tile downloads
    /// are handed to. Exposed so AppDelegate can route
    /// `application(_:handleEventsForBackgroundURLSession:completionHandler:)`
    /// back to us when iOS relaunches the app to deliver completed tasks.
    static let backgroundSessionIdentifier = "com.oshi.maps.download"

    /// Stashed by AppDelegate's `handleEventsForBackgroundURLSession` so we can
    /// invoke it once `urlSessionDidFinishEvents(forBackgroundURLSession:)`
    /// fires. iOS holds the app alive until this is called.
    private var backgroundCompletionHandler: (() -> Void)?

    func handleBackgroundEvents(_ completionHandler: @escaping () -> Void) {
        backgroundCompletionHandler = completionHandler
    }

    // MARK: - Private

    private var backgroundSession: URLSession!
    private var activeDownloads: [URLSessionDownloadTask: DownloadContext] = [:]
    private var resumeData: [String: Data] = [:]
    private let fileManager = FileManager.default

    private struct DownloadContext {
        let regionId: String
        let fileType: String // "tiles", "routing", "poi"
        let expectedSHA256: String
        let expectedSize: Int
    }

    private var mapsRootDirectory: URL {
        let docs = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first!
        return docs.appendingPathComponent("Maps/regions", isDirectory: true)
    }

    // MARK: - Init

    /// Dedicated serial delegate queue for `backgroundSession`.
    ///
    /// 🔧 FIX (2026-10-09): this was `.main`. Every single tile delivery
    /// (`didFinishDownloadingTo`) does a `fileExists` + `createDirectory` +
    /// `moveItem` which iOS requires to happen SYNCHRONOUSLY before the
    /// delegate returns — so a 145 000-tile country download ran 145 000
    /// synchronous filesystem transactions on the main thread, interleaved
    /// with MapKit's own layout. That is the "the app stutters for the whole
    /// download" symptom. The move is now off-main; only the @Published
    /// progress update hops back to main (and is coalesced, see
    /// `publishTileProgress`).
    private let tileDelegateQueue: OperationQueue = {
        let q = OperationQueue()
        q.maxConcurrentOperationCount = 1   // URLSession requires serial
        q.qualityOfService = .utility
        q.name = "com.oshi.maps.tileDelegate"
        return q
    }()

    private override init() {
        super.init()
        let config = URLSessionConfiguration.background(withIdentifier: Self.backgroundSessionIdentifier)
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        // OSM tiles are tiny (~3-4KB). HTTP/2 multiplexing over a long-lived
        // connection helps a lot vs many short HTTP/1.1 transactions.
        config.httpMaximumConnectionsPerHost = 12
        backgroundSession = URLSession(configuration: config, delegate: self, delegateQueue: tileDelegateQueue)
        ensureDirectoryExists(mapsRootDirectory)
        excludeFromiCloudBackup(mapsRootDirectory)
        loadDownloadedRegions()
        loadCachedManifest()
    }

    // MARK: - Directory Setup

    private func ensureDirectoryExists(_ url: URL) {
        if !fileManager.fileExists(atPath: url.path) {
            try? fileManager.createDirectory(at: url, withIntermediateDirectories: true)
        }
    }

    private func excludeFromiCloudBackup(_ url: URL) {
        var resourceURL = url
        var resourceValues = URLResourceValues()
        resourceValues.isExcludedFromBackup = true
        try? resourceURL.setResourceValues(resourceValues)
    }

    private func regionDirectory(for regionId: String) -> URL {
        mapsRootDirectory.appendingPathComponent(regionId, isDirectory: true)
    }

    // MARK: - Manifest

    /// On-disk copy of the last manifest we successfully fetched.
    ///
    /// 🔧 FIX (2026-10-09): there was none. Every launch re-fetched the
    /// manifest and, on ANY failure (airplane mode, captive portal, a 502 from
    /// the tile host, a slow cellular link), fell back to
    /// `hardcodedManifest` — ~110 stale regions instead of the 234 the server
    /// publishes. From the user's seat that reads exactly like "the app lost
    /// the other countries": whole countries vanish from the regions list, and
    /// an already-downloaded region whose id isn't in the hardcoded list shows
    /// its raw id ("gb-eng-yh") with no name, flag or size. Caching the real
    /// manifest makes the catalogue survive offline, and the hardcoded list
    /// becomes what it was meant to be — a first-run-only emergency.
    private var manifestCacheURL: URL {
        let docs = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first!
        return docs.appendingPathComponent("Maps/manifest.json")
    }

    private func loadCachedManifest() {
        guard manifest == nil,
              let data = try? Data(contentsOf: manifestCacheURL),
              let decoded = try? JSONDecoder().decode(MapManifest.self, from: data) else { return }
        manifest = decoded
        OshiLog.maps.info("[Maps] loaded cached manifest (\(decoded.regions.count) regions) from disk")
        refreshDownloadStates()
    }

    private func cacheManifest(_ data: Data) {
        let url = manifestCacheURL
        ensureDirectoryExists(url.deletingLastPathComponent())
        try? data.write(to: url, options: .atomic)
    }

    func fetchManifest() {
        isLoadingManifest = true
        // __MAPS_MANIFEST_REVALIDATE_2026_10_14__ Must not come from the HTTP
        // cache. `URLSession.shared` uses the shared `URLCache` and honours
        // Cache-Control, and the server was serving this file — it matches
        // nginx's generic `\.(json|js|css|…)$` rule — as
        // `max-age=2592000, public, immutable`. `immutable` means the client
        // does not even send a conditional request, so a region added by the
        // map pipeline stayed invisible to an installed app for up to 30 days.
        // Measured 2026-10-14: Oman, Bahrain, Kuwait, Qatar, Mauritius and the
        // French sub-regions were all published that day.
        //
        // The server header is being fixed too, but the catalogue is the one
        // request whose freshness the app should not delegate: revalidating
        // costs a 304 on a ~345 KB file, once per launch.
        //
        // `reloadRevalidatingCacheData` and not `reloadIgnoringLocalCacheData`
        // so an unchanged manifest is still a 304 rather than a full re-send.
        // Offline is unaffected: a failed fetch keeps whatever
        // `loadCachedManifest()` put in `manifest`, and only a first run with
        // no disk copy falls back to the hardcoded list.
        var request = URLRequest(url: Self.manifestURL)
        request.cachePolicy = .reloadRevalidatingCacheData
        let task = URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.isLoadingManifest = false

                if let data = data,
                   let httpResponse = response as? HTTPURLResponse,
                   httpResponse.statusCode == 200,
                   let decoded = try? JSONDecoder().decode(MapManifest.self, from: data) {
                    self.manifest = decoded
                    self.cacheManifest(data)
                } else if self.manifest != nil {
                    // Keep whatever we already have (disk cache or an earlier
                    // successful fetch). Replacing a 234-region catalogue with
                    // the 110-region hardcoded one because one request timed
                    // out is strictly worse than keeping the cached truth.
                    OshiLog.maps.info("[Maps] manifest fetch failed — keeping the \(self.manifest?.regions.count ?? 0) regions already loaded")
                } else {
                    self.manifest = Self.hardcodedManifest
                }
                self.refreshDownloadStates()
                // Resume any region downloads that were in flight when the
                // app was last killed. Free re-tries — already-downloaded
                // tiles are skipped by the per-tile cache filter.
                self.resumeInterruptedDownloads()
                // Also auto-resume single-file downloads (street index, road
                // graph) whose URLSessionDownloadTask handed us resumeData
                // before the interruption. These resume from the exact byte
                // offset where they stopped, not from 0%.
                OfflineStreetDatabase.shared.resumePendingDownloads(manifest: self.manifest)
                OfflineRoadGraphDownloader.shared.resumePendingDownloads(manifest: self.manifest)
            }
        }
        task.resume()
    }

    /// Auto-download the region containing the user's current GPS location (if not already downloaded)
    func autoDownloadCurrentRegion() {
        // Wait for manifest + location to be available
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { [weak self] in
            guard let self = self, let manifest = self.manifest else { return }
            guard let location = LocationSharingManager.shared.currentLocation else { return }

            let lat = location.coordinate.latitude
            let lon = location.coordinate.longitude

            // Find the best matching region (prefer full country over city)
            let matching = manifest.regions.filter { region in
                guard region.bbox.count >= 4 else { return false }
                return lat >= region.bbox[1] && lat <= region.bbox[3] &&
                       lon >= region.bbox[0] && lon <= region.bbox[2]
            }

            // Prefer full country (short ID without dash) over city region
            let bestMatch = matching.first(where: { !$0.id.contains("-") }) ?? matching.first

            guard let region = bestMatch else { return }

            // Skip if already downloaded or downloading
            if let state = self.downloadStates[region.id] {
                switch state {
                case .downloaded, .downloading, .updateAvailable:
                    return
                default:
                    break
                }
            }
            if self.downloadedRegions.contains(where: { $0.regionId == region.id }) {
                return
            }

            OshiLog.maps.info("[Maps] Auto-preloading region \(region.name) for current location (overview zooms only)")
            // Overview-only pre-cache, silent — does not touch downloadStates
            // or downloadedRegions, so the regions UI still shows the explicit
            // "Download" button for the full 6...13 bulk fetch afterwards.
            self.downloadRegion(region, zoomRange: 6...10, silent: true)
        }
    }

    func refreshDownloadStates() {
        guard let manifest = manifest else { return }
        for region in manifest.regions {
            if let meta = downloadedRegions.first(where: { $0.regionId == region.id }) {
                // Region is downloaded — show as downloaded
                downloadStates[region.id] = .downloaded(date: meta.downloadDate)
            } else if case .downloading = downloadStates[region.id] {
                // keep current progress
            } else if case .failed = downloadStates[region.id] {
                // keep error
            } else {
                downloadStates[region.id] = .notDownloaded
            }
        }
    }

    private func iso8601DateString(from date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.timeZone = TimeZone(identifier: "UTC")
        return formatter.string(from: date)
    }

    // MARK: - Tile Cache Directory

    /// Flat tile cache: Documents/Maps/tiles/{z}/{x}/{y}.png — shared across all regions
    private var tilesCacheDirectory: URL {
        let docs = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first!
        return docs.appendingPathComponent("Maps/tiles", isDirectory: true)
    }

    // MARK: - Download Region (OSM tiles)

    /// Active tile download tasks per region (for cancellation). Thread-safe via tileDownloadLock.
    private var activeTileDownloads: [String: Bool] = [:]  // regionId -> cancelled
    private let tileDownloadLock = NSLock()

    /// URLSession for tile downloads — fetches via oshi-messenger.com/tiles
    /// proxy which forwards to OSM with OSM-policy-compliant cache + rate-limit.
    private lazy var tileSession: URLSession = {
        let config = URLSessionConfiguration.default
        config.httpAdditionalHeaders = [
            "User-Agent": "OSHI-Messenger/1.0 (iOS; https://oshi-messenger.com)",
            "Referer": "https://oshi-messenger.com/",
        ]
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 30
        return URLSession(configuration: config)
    }()

    /// Download region = pre-cache OSM tiles for zoom levels covering the bounding box.
    /// Respects OSM usage policy: max 2 concurrent downloads, 100ms delay between requests.
    ///
    /// `zoomRange` controls how deep the pre-cache goes. `nil` — the normal
    /// case — means `region.downloadZoomRange`, which caps the depth by TILE
    /// BUDGET rather than fetching z6-13 for everything
    /// (__TILE_BUDGET_2026_10_09__ in `MapModels.swift` explains the rule and
    /// why it is counted in tiles and not in degrees).
    ///
    /// What that changes in practice: a country-sized bbox like France was
    /// ~143k tiles, of which ~107k were z13 alone — hours at the policy rate,
    /// and the main reason the download felt slow for everyone. It now stops
    /// at the deepest zoom that fits the budget, and zooming past that shows
    /// an upscaled ancestor instead of the grey placeholder it used to.
    /// A city-sized region is unaffected: z13 is cheap there and it keeps it.
    ///
    /// The auto preload path on map open still passes its own tighter range
    /// (6...10) so it completes in minutes, doesn't hijack the download UI,
    /// and leaves the explicit-download path available afterwards.
    /// `silent` skips all UI state mutation and metadata persistence — the
    /// download just primes the on-disk tile cache. Used by the auto-preload
    /// path so the regions UI keeps showing the explicit "Download" button
    /// for the full bulk fetch even after the overview tiles have landed.
    func downloadRegion(_ region: MapRegion, zoomRange: ClosedRange<Int>? = nil, silent: Bool = false) {
        guard region.bbox.count >= 4 else { return }
        // Resolved here rather than as a default argument because a default
        // cannot reference another parameter.
        let zoomRange = zoomRange ?? region.downloadZoomRange

        // Kick off streets / roadgraph / routing IN PARALLEL with tiles, not
        // after — the previous "wait for tiles to finalize then auto-DL"
        // chain left the user staring at a "Download street index" button
        // for half an hour while the tiles bulk download ran. Each downloader
        // coalesces duplicate calls, so calling them here AND from
        // finalizeRegionDownload (legacy belt-and-braces) is safe.
        if !silent {
            // __ROUTING_ARCHIVE_NEVER_EXISTED_2026_10_28__ The `routing` component is NOT
            // fetched, and that is a fix rather than a regression.
            //
            // Reported from an iPhone 17: downloading Switzerland reached 45% and then
            // showed an orange "Routing data" failure. Measured against the live server
            // the same day: `https://oshi-messenger.com/files/routing/ch-routing.tar.gz`
            // answers **404**, and so does `fr-routing.tar.gz` — the archive has never
            // existed for ANY region, while `maps/v2/manifest.json` advertises a
            // `routing` URL for every one of them.
            //
            // Nothing consumes it even when present. `OfflineRoutingManager`'s own header
            // says so: "Routing files are OSRM-processed archives … Actual OSRM routing
            // engine integration comes in a LATER PHASE; this file handles the complete
            // download + storage + query infrastructure." That phase never shipped. The
            // routing a user actually gets is the ROAD GRAPH, downloaded just below and
            // consumed by `MapRoutingEngine`.
            //
            // So the only thing this call ever produced was a guaranteed 404 recorded as
            // a user-visible component failure, on top of a map that had downloaded
            // perfectly. The download and the deletion path are left intact for anyone
            // who publishes the archives later; what is removed is the automatic attempt.

            if !OfflineStreetDatabase.shared.isStreetIndexAvailable(regionId: region.id) {
                OfflineStreetDatabase.shared.downloadStreetIndex(region: region, progress: { _ in }, completion: { _ in })
            }
            if !OfflineRoadGraphDownloader.shared.isRoadGraphAvailable(regionId: region.id) {
                OfflineRoadGraphDownloader.shared.downloadRoadGraph(region: region)
            }
            // 🔧 FIX (2026-10-09): POI was the one layer still chained BEHIND
            // the tile bulk download (`finalizeRegionDownload`), so offline
            // search stayed empty for the hours the tiles took — and never
            // arrived at all if the user cancelled. `downloadPOIDatabase`
            // coalesces duplicate calls, so starting it here is free.
            downloadPOIDatabase(region: region)
        }

        let minLon = region.bbox[0]
        let minLat = region.bbox[1]
        let maxLon = region.bbox[2]
        let maxLat = region.bbox[3]
        let cacheDir = tilesCacheDirectory
        ensureDirectoryExists(cacheDir)

        // Move EVERYTHING off the main thread immediately — including tile-coord
        // computation and the per-tile fileExists pass that filters already-cached
        // tiles. Both run in O(N) where N can be 100k+ for full country zoom 6-13;
        // doing them on main was a major contributor to the "map freezes for ~20s
        // after open" symptom because autoDownloadCurrentRegion fires 2s after
        // first render, right when MapKit is also synchronously laying out tiles.
        if !silent {
            // Show "downloading 0%" immediately so the user gets feedback before
            // the off-main computation finishes. Without this the regions UI sits
            // on the cloud-download icon for several seconds before flipping to
            // the spinner, looking unresponsive.
            downloadStates[region.id] = .downloading(progress: 0.0)
            // Start the 10 s watchdog so the "99 % stuck on iOS retry" tail
            // gets resolved within 30 s rather than 10 minutes.
            ensureWatchdogTimerRunning()
            // Persist intent so a kill / crash mid-download leaves a marker we
            // can use on next launch to auto-resume. The per-tile cache filter
            // above already makes the resume "free" — it only fetches tiles
            // missing from disk. Cleared in finalizeRegionDownload / cancel.
            persistInterruptedRegion(region)
        }

        DispatchQueue.global(qos: .utility).async { [weak self] in
            guard let self = self else { return }

            var tileCoords: [(z: Int, x: Int, y: Int)] = []
            for z in zoomRange {
                let n = pow(2.0, Double(z))
                let xMin = Int(floor((minLon + 180.0) / 360.0 * n))
                let xMax = Int(floor((maxLon + 180.0) / 360.0 * n))
                let yMin = Int(floor((1.0 - log(tan(maxLat * .pi / 180.0) + 1.0 / cos(maxLat * .pi / 180.0)) / .pi) / 2.0 * n))
                let yMax = Int(floor((1.0 - log(tan(minLat * .pi / 180.0) + 1.0 / cos(minLat * .pi / 180.0)) / .pi) / 2.0 * n))
                let xStart = max(0, xMin)
                let xEnd = min(Int(n) - 1, xMax)
                let yStart = max(0, yMin)
                let yEnd = min(Int(n) - 1, yMax)
                for x in xStart...xEnd {
                    for y in yStart...yEnd {
                        tileCoords.append((z: z, x: x, y: y))
                    }
                }
            }
            // Filter out tiles already on disk. fileExists is O(path-depth) so
            // for nested z/x/y trees this is the slowest per-tile op.
            let tilesInBBox = tileCoords.count
            tileCoords = tileCoords.filter { tile in
                let path = cacheDir
                    .appendingPathComponent("\(tile.z)")
                    .appendingPathComponent("\(tile.x)")
                    .appendingPathComponent("\(tile.y).png")
                return !self.fileManager.fileExists(atPath: path.path)
            }
            // Tiles already cached from an earlier run / an overlapping region.
            // Counting them makes a resumed download pick up at the percentage
            // the user left it at instead of restarting the ring at 0 %.
            let alreadyOnDisk = tilesInBBox - tileCoords.count

            let totalTiles = tileCoords.count
            if totalTiles == 0 {
                if !silent {
                    DispatchQueue.main.async {
                        self.downloadStates[region.id] = .downloaded(date: Date())
                        self.finalizeRegionDownload(region.id)
                    }
                }
                return
            }

            self.tileDownloadLock.lock()
            self.activeTileDownloads[region.id] = false
            self.tileDownloadLock.unlock()
            OshiLog.maps.info("[Maps] Downloading \(totalTiles) OSM tiles for \(region.name) (zoom \(zoomRange.lowerBound)-\(zoomRange.upperBound)), \(alreadyOnDisk) already cached")

            // Persist totals + already-on-disk count so progress survives a
            // kill: the delegate computes completed = totalTiles - remaining
            // (where remaining is recomputed from the on-disk cache) and the
            // user sees their progress where they left off rather than 0%.
            UserDefaults.standard.set(tilesInBBox, forKey: "oshi_tile_total_\(region.id)")

            if silent {
                // Silent (auto-preload) path: small, short — keep using the
                // foreground rate-limited dataTask so we don't pollute the
                // background session with throwaway tasks. The whole silent
                // preload is zoom 6-10 only and finishes in seconds.
                self.downloadTilesForeground(
                    tileCoords: tileCoords,
                    cacheDir: cacheDir,
                    region: region,
                    totalTiles: totalTiles,
                    silent: true
                )
            } else {
                // Long-haul user-initiated path: hand every tile to
                // URLSessionConfiguration.background. iOS will keep them
                // running while the app is suspended and even relaunch us
                // (urlSession(_:downloadTask:didFinishDownloadingTo:) will
                // route by taskDescription) when the OS chooses.
                self.submitTilesToBackgroundSession(
                    tileCoords: tileCoords,
                    region: region,
                    tilesInBBox: tilesInBBox,
                    alreadyOnDisk: alreadyOnDisk
                )
            }
        }
    }

    /// Foreground rate-limited tile downloader. Used only for silent / preview
    /// pre-cache (zooms 6-10), where the whole batch is small enough to finish
    /// before the user backgrounds the app.
    private func downloadTilesForeground(
        tileCoords: [(z: Int, x: Int, y: Int)],
        cacheDir: URL,
        region: MapRegion,
        totalTiles: Int,
        silent: Bool
    ) {
        let semaphore = DispatchSemaphore(value: 6)
        var completed = 0
        let progressLock = NSLock()

        for tile in tileCoords {
            self.tileDownloadLock.lock()
            let cancelled = self.activeTileDownloads[region.id] == true
            self.tileDownloadLock.unlock()
            if cancelled { return }

            semaphore.wait()
            Thread.sleep(forTimeInterval: 0.025)

            let urlString = "https://oshi-messenger.com/tiles/\(tile.z)/\(tile.x)/\(tile.y).png"
            guard let url = URL(string: urlString) else {
                semaphore.signal()
                continue
            }

            self.tileSession.dataTask(with: url) { [weak self] data, response, _ in
                defer { semaphore.signal() }
                guard let self = self else { return }

                if let data = data,
                   let httpResponse = response as? HTTPURLResponse,
                   httpResponse.statusCode == 200,
                   OfflineFirstTileOverlay.isUsableTile(data) {
                    let tilePath = cacheDir
                        .appendingPathComponent("\(tile.z)")
                        .appendingPathComponent("\(tile.x)")
                        .appendingPathComponent("\(tile.y).png")
                    let dir = tilePath.deletingLastPathComponent()
                    if !self.fileManager.fileExists(atPath: dir.path) {
                        try? self.fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
                    }
                    try? data.write(to: tilePath, options: .atomic)
                }

                progressLock.lock()
                completed += 1
                let progress = Double(completed) / Double(totalTiles)
                let isFinished = completed == totalTiles
                progressLock.unlock()

                if !silent {
                    DispatchQueue.main.async {
                        self.downloadStates[region.id] = .downloading(progress: progress)
                    }
                }

                if isFinished {
                    DispatchQueue.main.async {
                        self.tileDownloadLock.lock()
                        self.activeTileDownloads.removeValue(forKey: region.id)
                        self.tileDownloadLock.unlock()
                        if !silent {
                            self.finalizeRegionDownload(region.id)
                        }
                    }
                }
            }.resume()
        }
    }

    /// Submit every tile to the shared `backgroundSession` so iOS owns the
    /// download lifecycle. This is the "survives backgrounding & relaunch"
    /// path: tasks keep running when the app is suspended; when one finishes
    /// while the app is killed iOS relaunches us and replays the delegate
    /// callbacks. The routing back to the right region/tile-on-disk is
    /// encoded in `taskDescription` so we don't depend on in-memory state
    /// that doesn't survive a kill.
    /// Live bookkeeping for one region's tile bulk download.
    ///
    /// 🔧 FIX (2026-10-09) — this type, and the windowed submission below, are
    /// the fix for "le téléchargement de la carte est lent pour tout le monde".
    /// The previous implementation submitted EVERY tile to the background
    /// session in one burst (≈145 000 `URLSessionDownloadTask`s for France at
    /// zoom 6-13) and then, on every single tile completion, called
    /// `backgroundSession.getAllTasks` — an XPC round-trip to `nsurlsessiond`
    /// that materialises the whole outstanding task list — on the MAIN thread.
    /// That is O(N²) in the number of tiles: ~10^10 task objects crossing the
    /// process boundary for one country, which is why a download that should
    /// be bandwidth-bound took hours and pinned the UI while it ran.
    ///
    /// Nothing was gained by the burst: a background session only runs
    /// `httpMaximumConnectionsPerHost` transfers at a time regardless, so a
    /// bounded window of outstanding tasks downloads at exactly the same rate
    /// with none of the bookkeeping. Progress is now counted locally
    /// (`completed`) instead of being derived from `getAllTasks`.
    private final class TileJob {
        let region: MapRegion
        /// Tiles not yet handed to URLSession, in reverse order (popLast).
        var pending: [(z: Int, x: Int, y: Int)]
        /// How many tiles this run has to fetch (pending.count at creation).
        let toFetch: Int
        /// Tiles of the bbox that were already cached when the run started.
        let alreadyOnDisk: Int
        /// Every tile of the bbox, cached or not — the progress denominator.
        let tilesInBBox: Int
        /// Tasks handed to URLSession that have not reported completion yet.
        var outstanding = 0
        /// Completions seen (success OR failure) for tiles of this run.
        var completed = 0
        /// Tiles actually written to the cache.
        var succeeded = 0
        /// Last time `completed` moved — drives the stall watchdog.
        var lastProgressAt = Date()

        init(region: MapRegion, pending: [(z: Int, x: Int, y: Int)], alreadyOnDisk: Int, tilesInBBox: Int) {
            self.region = region
            self.pending = pending.reversed()
            self.toFetch = pending.count
            self.alreadyOnDisk = alreadyOnDisk
            self.tilesInBBox = max(tilesInBBox, pending.count)
        }

        var fraction: Double {
            guard tilesInBBox > 0 else { return 0 }
            return min(1.0, Double(alreadyOnDisk + completed) / Double(tilesInBBox))
        }

        var isFinished: Bool { completed >= toFetch }
    }

    /// Active jobs by region id. Guarded by `tileDownloadLock`.
    private var tileJobs: [String: TileJob] = [:]

    /// How many tile tasks may be outstanding per region. Comfortably above
    /// `httpMaximumConnectionsPerHost` so the pipe never starves while iOS
    /// rotates connections, and small enough that `nsurlsessiond` keeps a
    /// trivial task table.
    private static let maxOutstandingTilesPerRegion = 120

    private func submitTilesToBackgroundSession(
        tileCoords: [(z: Int, x: Int, y: Int)],
        region: MapRegion,
        tilesInBBox: Int,
        alreadyOnDisk: Int
    ) {
        // Drop any tile tasks left over from a previous run of THIS region
        // before queueing new ones.
        //
        // 🔧 FIX (2026-10-09): background-session tasks outlive the process, so
        // `resumeInterruptedDownloads` used to submit a second task for every
        // tile that was still queued from before the kill — the outstanding
        // task count doubled on each launch, and `done = total - stillPending`
        // went NEGATIVE (clamped to 0), which is the "progress jumped back to
        // 0 %" report. Cancelling first costs nothing: an unfinished tile is
        // ~3 KB, and the ones that DID land are already filtered out by the
        // on-disk pass in `downloadRegion`.
        let prefix = "tile|\(region.id)|"
        backgroundSession.getAllTasks { [weak self] tasks in
            guard let self = self else { return }
            for task in tasks where (task.taskDescription ?? "").hasPrefix(prefix) {
                task.cancel()
            }

            self.tileDownloadLock.lock()
            if self.activeTileDownloads[region.id] == true {
                self.tileDownloadLock.unlock()
                return
            }
            let job = TileJob(region: region,
                              pending: tileCoords,
                              alreadyOnDisk: alreadyOnDisk,
                              tilesInBBox: tilesInBBox)
            self.tileJobs[region.id] = job
            let batch = self.dequeueTilesLocked(job: job)
            self.tileDownloadLock.unlock()

            self.resume(tiles: batch, regionId: region.id)
            self.publishTileProgress(regionId: region.id, fraction: job.fraction, force: true)
        }
    }

    /// Pull as many tiles as the window allows. MUST be called with
    /// `tileDownloadLock` held; the returned tiles are counted as outstanding.
    private func dequeueTilesLocked(job: TileJob) -> [(z: Int, x: Int, y: Int)] {
        var batch: [(z: Int, x: Int, y: Int)] = []
        while job.outstanding < Self.maxOutstandingTilesPerRegion, let tile = job.pending.popLast() {
            job.outstanding += 1
            batch.append(tile)
        }
        return batch
    }

    private func resume(tiles: [(z: Int, x: Int, y: Int)], regionId: String) {
        for tile in tiles {
            let urlString = "https://oshi-messenger.com/tiles/\(tile.z)/\(tile.x)/\(tile.y).png"
            guard let url = URL(string: urlString) else {
                // Can't happen for integer coords, but never leave the window
                // permanently short if it ever did.
                tileTaskCompleted(regionId: regionId, success: false)
                continue
            }
            let task = backgroundSession.downloadTask(with: url)
            // "tile|<regionId>|<z>|<x>|<y>" — survives app kill via background session.
            task.taskDescription = "tile|\(regionId)|\(tile.z)|\(tile.x)|\(tile.y)"
            task.resume()
        }
    }

    /// One tile task reported in. Refills the submission window and publishes
    /// progress. Called on the session's (off-main) delegate queue.
    private func tileTaskCompleted(regionId: String, success: Bool) {
        tileDownloadLock.lock()
        guard let job = tileJobs[regionId] else {
            tileDownloadLock.unlock()
            // A straggler delivered by iOS for a run we no longer track (app
            // was killed and relaunched). The tile is on disk either way; there
            // is simply no ring to move.
            return
        }
        if job.outstanding > 0 { job.outstanding -= 1 }
        job.completed += 1
        if success { job.succeeded += 1 }
        job.lastProgressAt = Date()

        let batch = dequeueTilesLocked(job: job)
        let fraction = job.fraction
        let finished = job.isFinished
        let succeeded = job.succeeded
        let toFetch = job.toFetch
        let alreadyOnDisk = job.alreadyOnDisk
        if finished { tileJobs.removeValue(forKey: regionId) }
        tileDownloadLock.unlock()

        resume(tiles: batch, regionId: regionId)

        if finished {
            // A run where essentially nothing landed must NOT read as a
            // complete offline region.
            //
            // 🔧 FIX (2026-10-09): progress used to be `total - stillPending`,
            // so a region whose every tile came back 404/5xx reached
            // `stillPending == 0` and was stamped `.downloaded` with a green
            // checkmark and an empty cache on disk. The user then opened a
            // blank map that the app insisted was downloaded.
            let usable = succeeded + alreadyOnDisk
            let needed = max(1, (alreadyOnDisk + toFetch) / 2)
            DispatchQueue.main.async {
                self.stuckAt99Since.removeValue(forKey: regionId)
                UserDefaults.standard.removeObject(forKey: "oshi_tile_total_\(regionId)")
                if usable < needed {
                    OshiLog.maps.info("[Maps] \(regionId): only \(usable)/\(alreadyOnDisk + toFetch) tiles usable — marking failed instead of downloaded")
                    self.markRegionFailed(regionId, error: NSLocalizedString(
                        "map.regions.download.failed_soft",
                        value: "Map tiles could not be downloaded. Check your connection and try again.",
                        comment: "Shown when a region's tile download finished with almost nothing fetched"
                    ))
                } else {
                    self.downloadStates[regionId] = .downloaded(date: Date())
                    self.finalizeRegionDownload(regionId)
                }
            }
        } else {
            publishTileProgress(regionId: regionId, fraction: fraction, force: false)
        }
    }

    /// Last time we pushed a tile progress value to the UI, per region.
    /// Coalesced: at 120 concurrent tiles the raw completion rate is far above
    /// the display refresh rate, and each push is a `@Published` mutation that
    /// re-renders the whole regions list.
    private var lastTileProgressPublish: [String: Date] = [:]

    private func publishTileProgress(regionId: String, fraction: Double, force: Bool) {
        tileDownloadLock.lock()
        let last = lastTileProgressPublish[regionId]
        let due = force || last == nil || Date().timeIntervalSince(last!) > 0.25
        if due { lastTileProgressPublish[regionId] = Date() }
        tileDownloadLock.unlock()
        guard due else { return }

        DispatchQueue.main.async {
            if case .downloaded = self.downloadStates[regionId] { return }
            self.downloadStates[regionId] = .downloading(progress: fraction)
        }
    }

    // MARK: - Interrupted Download Resume
    //
    // Persists a list of region IDs whose download was started but never
    // finished. Cleared when finalizeRegionDownload runs or the user cancels.
    // App startup checks this list and auto-resumes — the per-tile cache
    // filter at the top of downloadRegion makes resume effectively free
    // (already-downloaded tiles are skipped).

    private static let interruptedKey = "oshi_interrupted_map_downloads"

    private func persistInterruptedRegion(_ region: MapRegion) {
        var current = UserDefaults.standard.stringArray(forKey: Self.interruptedKey) ?? []
        if !current.contains(region.id) {
            current.append(region.id)
            UserDefaults.standard.set(current, forKey: Self.interruptedKey)
        }
    }

    private func clearInterruptedRegion(_ regionId: String) {
        var current = UserDefaults.standard.stringArray(forKey: Self.interruptedKey) ?? []
        current.removeAll { $0 == regionId }
        UserDefaults.standard.set(current, forKey: Self.interruptedKey)
    }

    /// Resume any region downloads that were in progress when the app was
    /// last killed. Called from fetchManifest's success handler so the
    /// manifest is loaded before we try to look the region up.
    func resumeInterruptedDownloads() {
        guard let manifest = manifest else { return }
        let interrupted = UserDefaults.standard.stringArray(forKey: Self.interruptedKey) ?? []
        for regionId in interrupted {
            if let region = manifest.regions.first(where: { $0.id == regionId }),
               !isRegionDownloaded(regionId) {
                OshiLog.maps.info("[Maps] Resuming interrupted download for \(region.name)")
                downloadRegion(region)
            } else {
                clearInterruptedRegion(regionId)
            }
        }
    }

    /// Pending download queues (legacy, kept for compatibility)
    private var pendingDownloadQueues: [String: [(url: String, type: String, sha256: String, size: Int)]] = [:]

    /// Track bytes already completed for previously finished files in a region
    private var completedBytesPerRegion: [String: Int64] = [:]

    /// Legacy method kept for compatibility
    func startNextFileDownload(for regionId: String) {
        // No-op: replaced by OSM tile download system
    }

    // MARK: - POI Database Download
    //
    // 🔧 FIX (2026-07-28): iOS had NEVER downloaded poi.db.
    //
    // The `.bin` file-queue system that used to fetch tiles/routing/poi was
    // replaced by the OSM tile downloader in `fe9492b`, which turned
    // `startNextFileDownload` into a no-op and left `activeDownloads` /
    // `DownloadContext` permanently empty. `25aefaa` then "fixed the offline
    // POI download path" by pinning the on-disk extension to `.db` inside
    // `urlSession(_:downloadTask:didFinishDownloadingTo:)` — a handler only
    // reachable via `activeDownloads`, so it never ran. Net effect: the server
    // has been publishing 6.3 GB of per-region POI databases that no iOS device
    // ever received, and every offline POI/category search silently fell
    // through to the online Overpass path.
    //
    // The code below adds no new mechanism. It uses the same single-file
    // download machinery as its siblings (`OfflineStreetDatabase`,
    // `OfflineRoadGraphDownloader`): a resumable task from
    // `OfflineDownloadResume`, country-level retry on 404, install at the path
    // `MapPOIDatabase` already opens. Unlike those two, POI is NOT gzipped —
    // the server publishes the raw SQLite — so it is moved into place directly.
    //
    // See docs/OFFLINE_MAP_REFERENCE.md §2.

    /// Per-region POI download progress in [0, 1]. Observed by the regions UI
    /// and folded into `aggregateProgress`.
    @Published var poiDownloadProgress: [String: Double] = [:]

    /// __MAP_STALLED_AT_24_PERCENT_2026_10_28__ Why POI stopped, or nothing.
    ///
    /// The POI path had NO error channel: every failure removed the progress entry and
    /// returned, so a region whose POI died looked exactly like one whose POI had not
    /// started. Routing and streets have published one since they were written and the
    /// region screen reads both; this is the missing third, and `downloadError` on
    /// `OfflineRoadGraphDownloader` is the missing fourth READER.
    @Published var poiDownloadError: [String: String] = [:]

    private var activePOITasks: [String: URLSessionDownloadTask] = [:]
    private let poiTaskLock = NSLock()
    private static let poiResumeDir = "OSHI_POI_Resume"

    /// Whether `poi.db` is already installed for this region.
    func isPOIDatabaseAvailable(regionId: String) -> Bool {
        fileManager.fileExists(atPath: poiPath(for: regionId).path)
    }

    /// Download the region's POI SQLite from the URL the manifest advertises.
    ///
    /// This is the ONE place that honours `region.files.poi.url`. The manifest
    /// generator resolves region → country fallback server-side and reports
    /// `size`/`shared_size` accordingly, so `isAvailable` is an exact answer to
    /// "does this file exist" and we never fire a request known to 404.
    func downloadPOIDatabase(region: MapRegion) {
        let regionId = region.id

        guard let poi = region.files.poi else {
            OshiLog.maps.info("[Maps] \(regionId) advertises no POI database — skipping (map + routing unaffected)")
            return
        }
        // Server says the asset was never built. Skipping beats a guaranteed 404.
        guard poi.isAvailable else {
            OshiLog.maps.info("[Maps] \(regionId) POI database not built on server (size 0) — skipping")
            return
        }
        guard !isPOIDatabaseAvailable(regionId: regionId) else { return }

        // POI does NOT share the road graph's in-memory-decompression defect
        // (checked 2026-07-28): all 234 manifest entries point at a plain `.db`,
        // the install is a `moveItem`, and `sha256OfFile` already streams through
        // a 64 KB buffer. What it did share was the absence of a space check —
        // the largest POI database is 329 MB (`de/poi.db`).
        let poiSize = Int64(poi.realSize)
        if poiSize > 0 {
            let free = MapStorageManager.shared.deviceFreeSpace()
            let needed = poiSize + GzipStreamDecompressor.diskHeadroom
            if free > 0 && free < needed {
                OshiLog.maps.info("[Maps] skipping POI for \(regionId): needs \(needed) bytes, \(free) free")
                return
            }
        }

        poiTaskLock.lock()
        if activePOITasks[regionId] != nil {
            poiTaskLock.unlock()
            return
        }
        poiTaskLock.unlock()

        attemptPOIDownload(
            regionId: regionId,
            urlString: poi.url,
            expectedSHA256: poi.sha256,
            allowCountryFallback: Self.poiCountryFallbackEnabled
        )
    }

    /// Whether a sub-region with no POI database of its own may fall back to
    /// the country-level file. Defaults to FALSE: the country databases are far
    /// larger than the regional ones (de/poi.db is 329 MB, fr/poi.db 132 MB),
    /// so silently pulling one because the user tapped "Berlin" is a real
    /// cellular-data and storage surprise. The server-side resolver already
    /// points sub-region entries at the country file where that is the intent,
    /// and reports it via `shared`/`shared_size` so the UI can disclose the
    /// cost up front. Flip `oshi.map.poiCountryFallback` to true to also retry
    /// client-side on an unexpected 404.
    static var poiCountryFallbackEnabled: Bool {
        UserDefaults.standard.bool(forKey: "oshi.map.poiCountryFallback")
    }

    private func attemptPOIDownload(regionId: String,
                                    urlString: String,
                                    expectedSHA256: String,
                                    allowCountryFallback: Bool) {
        guard let url = URL(string: urlString) else {
            OshiLog.maps.info("[Maps] POI download for \(regionId): invalid URL \(urlString)")
            return
        }

        DispatchQueue.main.async {
            self.poiDownloadProgress[regionId] = 0.0
            self.poiDownloadError.removeValue(forKey: regionId)
        }

        OshiLog.maps.info("[Maps] Downloading POI database for \(regionId) from \(urlString)")

        // __MAP_STALLED_AT_24_PERCENT_2026_10_28__ see OfflineDownloadResume.resumeIsPoisoned
        let didResumeBox = UnsafeMutablePointer<Bool>.allocate(capacity: 1)
        didResumeBox.initialize(to: false)
        let task = OfflineDownloadResume.makeTask(
            session: poiSession,
            url: url,
            namespace: Self.poiResumeDir,
            regionId: regionId,
            didResume: didResumeBox
        ) { [weak self] tempURL, response, error in
            let didResume = didResumeBox.pointee
            didResumeBox.deinitialize(count: 1)
            didResumeBox.deallocate()
            guard let self = self else { return }
            let httpStatus = (response as? HTTPURLResponse)?.statusCode ?? -1

            // Country-level retry, mirroring OfflineRoadGraphDownloader.attempt.
            if httpStatus == 404, allowCountryFallback,
               let dash = regionId.firstIndex(of: "-"), dash != regionId.startIndex {
                self.poiTaskLock.lock()
                self.activePOITasks.removeValue(forKey: regionId)
                self.poiTaskLock.unlock()
                let country = String(regionId[..<dash])
                OshiLog.maps.info("[Maps] POI \(regionId) returned 404 — retrying with country-level \(country)")
                // The country file's SHA is not known here, so verification is
                // skipped for the fallback (placeholder semantics, same as the
                // hardcoded manifest path below).
                self.attemptPOIDownload(
                    regionId: regionId,
                    urlString: "https://oshi-messenger.com/maps/v2/\(country)/poi.db",
                    expectedSHA256: String(repeating: "0", count: 64),
                    allowCountryFallback: false
                )
                return
            }

            defer {
                self.poiTaskLock.lock()
                self.activePOITasks.removeValue(forKey: regionId)
                self.poiTaskLock.unlock()
            }

            if let error = error {
                // A resumed attempt that failed must not keep its state — the remote
                // file may have been regenerated, in which case every future resume fails
                // instantly against a validator that no longer matches.
                if OfflineDownloadResume.resumeIsPoisoned(didResume: didResume) {
                    OfflineDownloadResume.clear(namespace: Self.poiResumeDir, regionId: regionId)
                } else if let resumeData = (error as NSError).userInfo[NSURLSessionDownloadTaskResumeData] as? Data {
                    OfflineDownloadResume.save(resumeData, namespace: Self.poiResumeDir, regionId: regionId)
                }
                OshiLog.maps.info("[Maps] POI download failed for \(regionId): \(error.localizedDescription)")
                DispatchQueue.main.async {
                    self.poiDownloadProgress.removeValue(forKey: regionId)
                    self.poiDownloadError[regionId] = error.localizedDescription
                }
                return
            }
            guard let tempURL = tempURL, (200..<300).contains(httpStatus) else {
                OshiLog.maps.info("[Maps] POI download for \(regionId) returned HTTP \(httpStatus) — offline POI unavailable for this region")
                DispatchQueue.main.async {
                    self.poiDownloadProgress.removeValue(forKey: regionId)
                    self.poiDownloadError[regionId] = "HTTP \(httpStatus)"
                }
                return
            }

            let destDir = self.regionDirectory(for: regionId)
            self.ensureDirectoryExists(destDir)
            let destFile = self.poiPath(for: regionId)

            do {
                if self.fileManager.fileExists(atPath: destFile.path) {
                    try self.fileManager.removeItem(at: destFile)
                }
                try self.fileManager.moveItem(at: tempURL, to: destFile)
            } catch {
                OshiLog.maps.info("[Maps] POI install failed for \(regionId): \(error.localizedDescription)")
                DispatchQueue.main.async {
                    self.poiDownloadProgress.removeValue(forKey: regionId)
                    self.poiDownloadError[regionId] = error.localizedDescription
                }
                return
            }

            // Verify SHA-256 (skipped for the all-zero placeholder used by the
            // hardcoded manifest and the country fallback above).
            let isPlaceholder = expectedSHA256.allSatisfy { $0 == "0" }
            if !isPlaceholder, self.sha256OfFile(at: destFile) != expectedSHA256 {
                try? self.fileManager.removeItem(at: destFile)
                OshiLog.maps.info("[Maps] POI SHA-256 mismatch for \(regionId) — discarded")
                DispatchQueue.main.async { self.poiDownloadProgress.removeValue(forKey: regionId) }
                return
            }

            OfflineDownloadResume.clear(namespace: Self.poiResumeDir, regionId: regionId)
            OshiLog.maps.info("[Maps] POI database ready for \(regionId)")
            DispatchQueue.main.async {
                self.poiDownloadProgress[regionId] = 1.0
                self.recalculateStorage()
            }
        }

        poiTaskLock.lock()
        activePOITasks[regionId] = task
        poiTaskLock.unlock()

        let observation = task.progress.observe(\.fractionCompleted) { [weak self] prog, _ in
            DispatchQueue.main.async {
                self?.poiDownloadProgress[regionId] = prog.fractionCompleted
            }
        }
        objc_setAssociatedObject(task, "poiProgressObservation", observation, .OBJC_ASSOCIATION_RETAIN)

        task.resume()
    }

    /// Foreground session for the POI database. Separate from `backgroundSession`
    /// (which is keyed to per-tile tasks by `taskDescription`) and sized like
    /// the roadgraph session — a country POI DB can reach ~330 MB.
    private lazy var poiSession: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 3600
        config.httpAdditionalHeaders = ["User-Agent": "OSHI-Messenger/1.0"]
        return URLSession(configuration: config)
    }()

    func cancelDownload(regionId: String) {
        // Signal the foreground tile download loop to stop (thread-safe write)
        // and drop the windowed job so no further tiles are submitted.
        //
        // 🔧 FIX (2026-10-09): Cancel used to only flip `activeTileDownloads`
        // and cancel the tasks iOS currently held. With every tile already
        // submitted, "the tasks iOS currently held" was all of them, so the
        // cancel took a getAllTasks pass over ~145 000 tasks; and because the
        // flag was never cleared the region could not be restarted without
        // going through the full re-enumeration. Dropping the job stops the
        // refill at the source.
        tileDownloadLock.lock()
        activeTileDownloads[regionId] = true
        tileJobs.removeValue(forKey: regionId)
        lastTileProgressPublish.removeValue(forKey: regionId)
        tileDownloadLock.unlock()

        // Cancel any background-session tile tasks belonging to this region.
        // These are the long-haul downloads handled by submitTilesToBackgroundSession.
        let prefix = "tile|\(regionId)|"
        backgroundSession.getAllTasks { tasks in
            for task in tasks where (task.taskDescription ?? "").hasPrefix(prefix) {
                task.cancel()
            }
        }

        // Also cancel legacy file-style downloads if any
        let tasksToCancel = activeDownloads.filter { $0.value.regionId == regionId }
        for (task, context) in tasksToCancel {
            task.cancel(byProducingResumeData: { [weak self] data in
                if let data = data {
                    self?.resumeData["\(context.regionId)_\(context.fileType)"] = data
                }
            })
            activeDownloads.removeValue(forKey: task)
        }
        downloadStates[regionId] = .notDownloaded
        clearInterruptedRegion(regionId)
        UserDefaults.standard.removeObject(forKey: "oshi_tile_total_\(regionId)")
        // Reset the monotonic floor so a fresh download starts at 0 %, not at
        // whatever the last attempt reached.
        aggregateProgressFloor.removeValue(forKey: regionId)
        stuckAt99Since.removeValue(forKey: regionId)
    }

    // MARK: - Delete Region

    func deleteRegion(regionId: String) {
        let dir = regionDirectory(for: regionId)
        try? fileManager.removeItem(at: dir)
        downloadedRegions.removeAll { $0.regionId == regionId }
        downloadStates[regionId] = .notDownloaded

        // Also delete routing files for this region
        OfflineRoutingManager.shared.deleteRouting(regionId: regionId)
        aggregateProgressFloor.removeValue(forKey: regionId)

        // 🔧 FIX (2026-10-09): free the TILES too.
        //
        // The map imagery — by far the biggest part of a region, hundreds of MB
        // to several GB — lives in the shared cache at Documents/Maps/tiles,
        // not under regions/<id>/. Deleting a region removed only poi.db and
        // road_graph.db, so "Delete" freed a few MB of a multi-GB region and
        // the cache grew without bound forever (there is no eviction anywhere
        // else in the app either). Users ended up with gigabytes of Documents
        // that nothing in the UI accounted for and nothing could reclaim.
        //
        // Tiles inside the bbox of ANOTHER still-downloaded region are kept.
        // When a region's bbox is unknown (not in the manifest) we keep its
        // tiles — erring towards keeping data the user may still need.
        purgeTilesExclusiveToRegion(regionId)
        recalculateStorage()
    }

    /// Remove cached tiles that belong to `regionId`'s bbox and to no other
    /// downloaded region. Runs off the main thread.
    private func purgeTilesExclusiveToRegion(_ regionId: String) {
        guard let manifest = manifest,
              let region = manifest.regions.first(where: { $0.id == regionId }),
              region.bbox.count >= 4 else { return }
        // The stated rule is "when another region's bbox is unknown, keep its
        // tiles". A `compactMap` does NOT implement that: it silently drops the
        // unknown region from the keep-set, so a tile belonging ONLY to that
        // region gets deleted if it happens to fall inside the bbox being
        // removed — the opposite of the promise, and the user is left with
        // holes in a region they never asked to delete.
        //
        // Deleting cached imagery is not catastrophic (it re-downloads), but it
        // must not be silent and it must not contradict the comment above it.
        // So: if we cannot account for every downloaded region, purge nothing
        // and say why. The old behaviour — delete frees no tiles — is the safe
        // side to fail to.
        var keepBoxes: [[Double]] = []
        for meta in downloadedRegions where meta.regionId != regionId {
            guard let other = manifest.regions.first(where: { $0.id == meta.regionId }),
                  other.bbox.count >= 4 else {
                OshiLog.maps.info("[Maps] skipping tile reclamation for \(regionId): no bbox for still-downloaded region \(meta.regionId)")
                return
            }
            keepBoxes.append(other.bbox)
        }
        let cacheDir = tilesCacheDirectory
        let bbox = region.bbox

        // Walk what is actually on disk rather than the bbox's coordinate
        // space: a country bbox spans ~4·10^8 possible tiles at z19, while the
        // cache holds at most a few hundred thousand files.
        DispatchQueue.global(qos: .utility).async { [weak self] in
            guard let self = self else { return }
            var removed = 0
            guard let e = self.fileManager.enumerator(at: cacheDir,
                                                      includingPropertiesForKeys: nil,
                                                      options: [.skipsHiddenFiles]) else { return }
            for case let url as URL in e {
                guard url.pathExtension == "png" else { continue }
                let comps = url.pathComponents
                guard comps.count >= 3,
                      let y = Int(url.deletingPathExtension().lastPathComponent),
                      let x = Int(comps[comps.count - 2]),
                      let z = Int(comps[comps.count - 3]) else { continue }
                guard Self.tile(z: z, x: x, y: y, intersects: bbox) else { continue }
                if keepBoxes.contains(where: { Self.tile(z: z, x: x, y: y, intersects: $0) }) { continue }
                try? self.fileManager.removeItem(at: url)
                removed += 1
            }
            OshiLog.maps.info("[Maps] deleted \(removed) cached tiles exclusive to \(regionId)")
            DispatchQueue.main.async {
                self.tileCacheMeasuredAt = nil
                self.recalculateStorage()
            }
        }
    }

    // MARK: - Tile geometry helpers

    static func tileXRange(minLon: Double, maxLon: Double, n: Double) -> ClosedRange<Int> {
        let xMin = max(0, Int(floor((minLon + 180.0) / 360.0 * n)))
        let xMax = min(Int(n) - 1, Int(floor((maxLon + 180.0) / 360.0 * n)))
        return xMin...max(xMin, xMax)
    }

    static func tileYRange(minLat: Double, maxLat: Double, n: Double) -> ClosedRange<Int> {
        func yOf(_ lat: Double) -> Int {
            let clamped = min(85.05112878, max(-85.05112878, lat))
            let rad = clamped * .pi / 180.0
            return Int(floor((1.0 - log(tan(rad) + 1.0 / cos(rad)) / .pi) / 2.0 * n))
        }
        let yMin = max(0, yOf(maxLat))
        let yMax = min(Int(n) - 1, yOf(minLat))
        return yMin...max(yMin, yMax)
    }

    /// Whether tile z/x/y overlaps a [minLon, minLat, maxLon, maxLat] box.
    static func tile(z: Int, x: Int, y: Int, intersects bbox: [Double]) -> Bool {
        guard bbox.count >= 4 else { return false }
        let n = pow(2.0, Double(z))
        let xr = tileXRange(minLon: bbox[0], maxLon: bbox[2], n: n)
        let yr = tileYRange(minLat: bbox[1], maxLat: bbox[3], n: n)
        return xr.contains(x) && yr.contains(y)
    }

    // MARK: - Storage

    /// Bytes held by the shared tile cache (Documents/Maps/tiles). Recomputed
    /// off-main, at most every 20 s — the cache can hold 100 000+ files.
    @Published var tileCacheBytes: Int = 0
    private var tileCacheMeasuredAt: Date?
    private var isMeasuringTileCache = false

    func recalculateStorage() {
        // 🔧 FIX (2026-10-09): this summed `DownloadedRegionMetadata.totalSize`,
        // whose `tilesSize` comes from `region.files.tiles?.size` — a field the
        // live manifest always reports as 0 (raster tiles are streamed from the
        // /tiles proxy, there is no tiles artefact). So the "Storage used"
        // footer showed the POI database size alone: "38 MB used" next to 4 GB
        // of tiles actually sitting in Documents. Measure the cache instead.
        // Per-region assets from metadata, tiles measured once from the shared
        // cache (they are shared between regions, so summing a per-region
        // estimate would double-count every overlap).
        let assetBytes = downloadedRegions.reduce(0) { $0 + $1.routingSize + $1.poiSize }
        totalStorageUsed = assetBytes + tileCacheBytes
        measureTileCacheIfStale()
    }

    private func measureTileCacheIfStale() {
        if let at = tileCacheMeasuredAt, Date().timeIntervalSince(at) < 20 { return }
        if isMeasuringTileCache { return }
        isMeasuringTileCache = true
        let dir = tilesCacheDirectory
        DispatchQueue.global(qos: .utility).async { [weak self] in
            guard let self = self else { return }
            var bytes = 0
            if let e = self.fileManager.enumerator(at: dir,
                                                   includingPropertiesForKeys: [.fileSizeKey],
                                                   options: [.skipsHiddenFiles]) {
                for case let url as URL in e {
                    if let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize {
                        bytes += size
                    }
                }
            }
            DispatchQueue.main.async {
                self.isMeasuringTileCache = false
                self.tileCacheMeasuredAt = Date()
                if self.tileCacheBytes != bytes {
                    self.tileCacheBytes = bytes
                    self.totalStorageUsed =
                        self.downloadedRegions.reduce(0) { $0 + $1.routingSize + $1.poiSize } + bytes
                }
            }
        }
    }

    var storageUsedFormatted: String {
        let mb = Double(totalStorageUsed) / (1024.0 * 1024.0)
        if mb >= 1024.0 {
            return String(format: "%.1f GB", mb / 1024.0)
        }
        return String(format: "%.0f MB", mb)
    }

    // MARK: - Persistence

    private func loadDownloadedRegions() {
        let regionsDir = mapsRootDirectory
        guard let contents = try? fileManager.contentsOfDirectory(atPath: regionsDir.path) else { return }
        var loaded: [DownloadedRegionMetadata] = []
        for regionId in contents {
            let metaFile = regionsDir.appendingPathComponent(regionId).appendingPathComponent("metadata.json")
            if let data = try? Data(contentsOf: metaFile),
               let meta = try? JSONDecoder().decode(DownloadedRegionMetadata.self, from: data) {
                loaded.append(meta)
            }
        }
        downloadedRegions = loaded
        recalculateStorage()
    }

    private func saveMetadata(_ meta: DownloadedRegionMetadata) {
        let dir = regionDirectory(for: meta.regionId)
        ensureDirectoryExists(dir)
        let metaFile = dir.appendingPathComponent("metadata.json")
        if let data = try? JSONEncoder().encode(meta) {
            try? data.write(to: metaFile)
        }
    }

    // MARK: - SHA-256 Verification

    private func sha256OfFile(at url: URL) -> String? {
        guard let stream = InputStream(url: url) else { return nil }
        stream.open()
        defer { stream.close() }

        var context = CC_SHA256_CTX()
        CC_SHA256_Init(&context)

        let bufferSize = 65_536
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
        defer { buffer.deallocate() }

        while stream.hasBytesAvailable {
            let read = stream.read(buffer, maxLength: bufferSize)
            if read <= 0 { break }
            CC_SHA256_Update(&context, buffer, CC_LONG(read))
        }

        var digest = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        CC_SHA256_Final(&digest, &context)

        return digest.map { String(format: "%02x", $0) }.joined()
    }

    // MARK: - Tile download (background-session) routing

    /// Move the freshly-downloaded tile from URLSession's temp location into
    /// the persistent tile cache, then update progress for the region. iOS
    /// invalidates `location` once this delegate returns, so the move must
    /// happen synchronously on this thread.
    private func handleTileDownloadFinished(
        _ task: URLSessionDownloadTask,
        location: URL,
        desc: String
    ) {
        // desc = "tile|<regionId>|<z>|<x>|<y>"
        let parts = desc.split(separator: "|", maxSplits: 5, omittingEmptySubsequences: false)
        guard parts.count >= 5,
              let z = Int(parts[2]),
              let x = Int(parts[3]),
              let y = Int(parts[4]) else { return }
        let regionId = String(parts[1])

        // Skip writing if HTTP returned a non-200 (404, 5xx). URLSession still
        // calls didFinishDownloadingTo with the response body, so the response
        // status is the only safe gate.
        if let http = task.response as? HTTPURLResponse, http.statusCode != 200 {
            return
        }

        // 🔧 FIX (2026-10-09): accept the body only if it really is a PNG.
        // The tile proxy answers 200 with an HTML body when it rate-limits or
        // when its upstream errors, and that body used to be moved into the
        // cache under a `.png` name. Once there it was indistinguishable from a
        // real tile: the download filter skipped it forever (the file exists)
        // and the overlay handed the bytes to MapKit, which rendered nothing —
        // a permanent hole in the offline map that only a reinstall cleared.
        guard Self.looksLikePNG(at: location) else {
            OshiLog.maps.info("[Maps] \(regionId) tile \(z)/\(x)/\(y) came back 200 but is not a PNG — discarding")
            return
        }
        // __POISONED_TILE_PURGE_2026_11_16__ OSM's "Access blocked" picture is
        // a valid PNG served with 200 — never save it as a tile.
        guard !PoisonedTile.fileMatches(at: location) else {
            OshiLog.maps.info("[Maps] \(regionId) tile \(z)/\(x)/\(y) is the OSM 'Access blocked' picture — discarding")
            return
        }

        let tilePath = tilesCacheDirectory
            .appendingPathComponent("\(z)")
            .appendingPathComponent("\(x)")
            .appendingPathComponent("\(y).png")
        let dir = tilePath.deletingLastPathComponent()
        if !fileManager.fileExists(atPath: dir.path) {
            try? fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        // Tile may already exist if a previous run also wrote it (resume case).
        if fileManager.fileExists(atPath: tilePath.path) {
            try? fileManager.removeItem(at: tilePath)
        }
        do {
            try fileManager.moveItem(at: location, to: tilePath)
        } catch {
            return
        }

        // Counted in `didCompleteWithError`, which is the one callback
        // guaranteed to fire exactly once per task (a 404 fires BOTH, an error
        // fires only the latter). Recording the success here and consuming it
        // there keeps `completed` and `succeeded` consistent.
        tileDownloadLock.lock()
        tileTaskSucceeded.insert(task.taskIdentifier)
        tileDownloadLock.unlock()
    }

    /// Task identifiers whose tile actually landed in the cache. Written by
    /// `handleTileDownloadFinished`, consumed by `didCompleteWithError`.
    private var tileTaskSucceeded: Set<Int> = []

    /// Cheap PNG sniff on the URLSession temp file: the 8-byte signature.
    /// Reads only the header, never the whole tile.
    private static func looksLikePNG(at url: URL) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }
        guard let head = try? handle.read(upToCount: 8), head.count == 8 else { return false }
        return Array(head) == [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]
    }

    /// First moment we observed a region at >= 99 % completion. Used by the
    /// watchdog below — if iOS sits in retry-with-backoff on the last 1 % of
    /// tiles for too long, we finalize anyway rather than leaving the user
    /// staring at "99 %" for 10 minutes.
    private var stuckAt99Since: [String: Date] = [:]

    /// Single timer that periodically re-evaluates progress for every active
    /// download. The per-tile completion callback already calls
    /// `recomputeTileProgress`, but when the trailing 1 % is stuck in iOS
    /// retry-with-backoff *nothing* completes, so the watchdog can never
    /// trip from that callback alone. This 10 s tick covers the gap.
    private var watchdogTimer: Timer?

    /// Per-region monotonic high-water mark of `aggregateProgress`. Without
    /// it, the displayed % can briefly drop when `totalTiles` switches from
    /// "not yet computed (0)" to the real count, or when a previously-fast
    /// component (streets) finishes and the weight redistribution pulls the
    /// weighted average down. UX-wise the ring should NEVER move backwards.
    private var aggregateProgressFloor: [String: Double] = [:]

    /// Aggregate progress in [0, 1] for a region's download — weighted by the
    /// expected byte cost of each component (tiles + routing + streets +
    /// roadgraph + POI). The progress ring shown to the user reads this
    /// value, not just the tile sub-progress.
    func aggregateProgress(for region: MapRegion) -> Double {
        let regionId = region.id

        // Tile contribution. We don't know the exact byte total of OSM tiles,
        // so estimate ~12 KB per tile. totalTiles is persisted at submit time
        // by `downloadRegion` AFTER the off-main-thread tile-coord computation
        // completes — there's a brief window at the start where it's 0.
        // In that window we EXCLUDE tiles from the weighted average rather
        // than treating them as "100 % done" (which artificially inflated
        // progress, only to crash back down once the real count arrived).
        let totalTiles = UserDefaults.standard.integer(forKey: "oshi_tile_total_\(regionId)")
        let haveTileCount = totalTiles > 0
        let tileWeight: Double = haveTileCount ? Double(totalTiles) * 12_000 : 0
        var tileProgress: Double = 0.0
        if let state = downloadStates[regionId], case .downloading(let p) = state {
            tileProgress = p
        } else if isRegionDownloaded(regionId) {
            tileProgress = 1.0
        }

        // For shared sub-region files the manifest stores size=0 + shared_size,
        // so add shared_size into the byte weight: the user IS downloading
        // those bytes even if the file is "shared" with a country sibling.
        func weight(_ f: MapFile?) -> Double {
            guard let f = f else { return 0 }
            return Double(f.size + (f.sharedSize ?? 0))
        }

        // __ROUTING_ARCHIVE_NEVER_EXISTED_2026_10_28__ routing carries NO weight.
        //
        // It is never fetched (see `startRegionDownload`), so its progress is pinned at
        // 0 forever — and leaving it in the denominator caps the bar below 100% by
        // exactly its share. That is not hypothetical: 270 regions in the live manifest
        // advertise a routing archive, every one of them 404, and 13 of those give it a
        // `shared_size` — 4,336,256,243 bytes for the German states, 2,798,959,226 for
        // London and the South East. For those the phantom component is the LARGEST term
        // in the sum, so the download would have stalled around a third and never
        // finished, with every byte of the actual map already on disk.
        let streetsWeight = weight(region.files.streets)
        let roadgraphWeight = weight(region.files.roadgraph)
        let poiWeight = weight(region.files.poi)

        let streetsP = OfflineStreetDatabase.shared.isStreetIndexAvailable(regionId: regionId)
            ? 1.0
            : (OfflineStreetDatabase.shared.downloadProgress[regionId] ?? 0.0)
        let roadgraphP = OfflineRoadGraphDownloader.shared.isRoadGraphAvailable(regionId: regionId)
            ? 1.0
            : (OfflineRoadGraphDownloader.shared.downloadProgress[regionId] ?? 0.0)
        // 🔧 FIX (2026-07-28): was `isRegionDownloaded(regionId)`, i.e. the TILE
        // state — so the ring counted POI as 100 % complete the moment tiles
        // landed, while poi.db had in fact never been downloaded at all (see
        // `downloadPOIDatabase`). Report the real POI state.
        let poiP = isPOIDatabaseAvailable(regionId: regionId)
            ? 1.0
            : (poiDownloadProgress[regionId] ?? 0.0)

        let totalWeight = tileWeight + streetsWeight + roadgraphWeight + poiWeight
        let combined: Double
        if totalWeight > 0 {
            combined = (tileProgress * tileWeight
                + streetsP * streetsWeight
                + roadgraphP * roadgraphWeight
                + poiP * poiWeight) / totalWeight
        } else {
            combined = tileProgress
        }
        let bounded = min(1.0, max(0.0, combined))

        // Monotonic floor: ring never moves backwards. Reset when region
        // is removed/restarted from notDownloaded.
        if let state = downloadStates[regionId], case .notDownloaded = state {
            aggregateProgressFloor.removeValue(forKey: regionId)
            return bounded
        }
        let floor = aggregateProgressFloor[regionId] ?? 0.0
        let next = max(floor, bounded)
        aggregateProgressFloor[regionId] = next
        return next
    }

    /// __MAP_STALLED_AT_24_PERCENT_2026_10_28__ Which part of this region's download has
    /// given up — the reader whose absence froze a ring at 24% with nothing to show for it.
    ///
    /// ORDERED BY WEIGHT, heaviest first, so the component actually holding the number back
    /// is the one named. For a French département the roadgraph is ~77% of the bytes, which
    /// is why it leads: telling someone their POI database failed while 272 MB of road graph
    /// is the real blocker would be true and useless.
    func componentFailure(for region: MapRegion) -> (component: String, message: String)? {
        let id = region.id
        let components: [MapComponentFailure.Component] = [
            .init(id: "map.component.roadgraph",
                  available: OfflineRoadGraphDownloader.shared.isRoadGraphAvailable(regionId: id),
                  downloading: OfflineRoadGraphDownloader.shared.isDownloading[id] == true,
                  error: OfflineRoadGraphDownloader.shared.downloadError[id]),
            .init(id: "map.component.streets",
                  available: OfflineStreetDatabase.shared.isStreetIndexAvailable(regionId: id),
                  downloading: OfflineStreetDatabase.shared.downloadProgress[id] != nil,
                  error: OfflineStreetDatabase.shared.downloadError[id]),
            // __ROUTING_ARCHIVE_NEVER_EXISTED_2026_10_28__ not listed: a component the
            // server has never served and no code reads cannot be a failure the user is
            // asked to do something about. See `startRegionDownload`.
            .init(id: "map.component.poi",
                  available: isPOIDatabaseAvailable(regionId: id),
                  downloading: poiDownloadProgress[id] != nil,
                  error: poiDownloadError[id]),
        ]
        guard let failed = MapComponentFailure.first(in: components), let message = failed.error else {
            return nil
        }
        return (failed.id, message)
    }

    private func ensureWatchdogTimerRunning() {
        if watchdogTimer != nil { return }
        DispatchQueue.main.async {
            self.watchdogTimer = Timer.scheduledTimer(withTimeInterval: 10.0, repeats: true) { [weak self] _ in
                guard let self = self else { return }
                var anyActive = false
                for (regionId, state) in self.downloadStates {
                    if case .downloading = state {
                        anyActive = true
                        self.checkTileDownloadStall(regionId: regionId)
                    }
                }
                if !anyActive {
                    self.watchdogTimer?.invalidate()
                    self.watchdogTimer = nil
                }
            }
        }
    }

    /// Stall watchdog, run from the 10 s timer only.
    ///
    /// The trailing tiles of a bulk download can sit in iOS retry-with-backoff
    /// for 5-10 minutes (the tile proxy rate-limits, iOS waits), during which
    /// *nothing* completes — so this can't be driven from the completion
    /// callback. Two conditions end a download here:
    ///
    ///  • ≥ 99 % fetched and nothing has moved for 30 s → accept it. The
    ///    handful of missing tiles get fetched on demand when the user pans
    ///    over them; they are not lost.
    ///  • nothing at all has moved for 5 minutes → the run is dead (no
    ///    connectivity, server down). Report it instead of leaving the ring
    ///    spinning at 4 % forever, which is what used to happen: there was no
    ///    failure path for tiles at all.
    ///
    /// 🔧 FIX (2026-10-09): this replaces `recomputeTileProgress`, which called
    /// `backgroundSession.getAllTasks` on EVERY tile completion. See `TileJob`.
    private func checkTileDownloadStall(regionId: String) {
        tileDownloadLock.lock()
        guard let job = tileJobs[regionId] else {
            tileDownloadLock.unlock()
            return
        }
        let fraction = job.fraction
        let idleFor = Date().timeIntervalSince(job.lastProgressAt)
        let remaining = job.toFetch - job.completed
        tileDownloadLock.unlock()

        // Keep the ring honest even while the tail retries.
        publishTileProgress(regionId: regionId, fraction: fraction, force: true)

        let giveUpBecauseNearlyDone = fraction >= 0.99 && idleFor > 30
        let giveUpBecauseDead = idleFor > 600
        guard remaining > 0, giveUpBecauseNearlyDone || giveUpBecauseDead else {
            if idleFor <= 30 { stuckAt99Since.removeValue(forKey: regionId) }
            return
        }

        OshiLog.maps.info("[Maps] watchdog: \(regionId) at \(Int(fraction * 100))% idle for \(Int(idleFor))s with \(remaining) tiles outstanding — ending the run")

        tileDownloadLock.lock()
        tileJobs.removeValue(forKey: regionId)
        tileDownloadLock.unlock()

        let prefix = "tile|\(regionId)|"
        backgroundSession.getAllTasks { tasks in
            for task in tasks where (task.taskDescription ?? "").hasPrefix(prefix) {
                task.cancel()
            }
        }

        DispatchQueue.main.async {
            self.stuckAt99Since.removeValue(forKey: regionId)
            UserDefaults.standard.removeObject(forKey: "oshi_tile_total_\(regionId)")
            if giveUpBecauseNearlyDone {
                self.downloadStates[regionId] = .downloaded(date: Date())
                self.finalizeRegionDownload(regionId)
            } else {
                self.markRegionFailed(regionId, error: NSLocalizedString(
                    "map.regions.download.stalled",
                    value: "The map download stopped making progress. Check your connection and try again.",
                    comment: "Shown when no tile has completed for several minutes"
                ))
            }
        }
    }

    // MARK: - URLSessionDownloadDelegate

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        // Tile-style tasks: "tile|<regionId>|<z>|<x>|<y>"
        // These are handed to the background session by submitTilesToBackgroundSession.
        // They do NOT need an entry in `activeDownloads` — all the routing info
        // is in taskDescription, so even after a kill+relaunch we know what to do.
        if let desc = downloadTask.taskDescription, desc.hasPrefix("tile|") {
            handleTileDownloadFinished(downloadTask, location: location, desc: desc)
            return
        }

        guard let context = activeDownloads[downloadTask] else { return }
        activeDownloads.removeValue(forKey: downloadTask)

        let destDir = regionDirectory(for: context.regionId)
        ensureDirectoryExists(destDir)
        // 🆕 The POI download is a SQLite database — saving it as `poi.bin` while the rest
        // of the app reads `poi.db` left every device with no offline POI even after a
        // successful download. Pin the on-disk extension to `.db` for the POI fileType so
        // `MapPOIDatabase.openDb(...)` can find it.
        let onDiskExt = (context.fileType == "poi") ? ".db" : ".bin"
        let destFile = destDir.appendingPathComponent("\(context.fileType)\(onDiskExt)")

        do {
            if fileManager.fileExists(atPath: destFile.path) {
                try fileManager.removeItem(at: destFile)
            }
            try fileManager.moveItem(at: location, to: destFile)
        } catch {
            markRegionFailed(context.regionId, error: "File move failed: \(error.localizedDescription)")
            return
        }

        // Verify SHA-256 (skip if placeholder hash from hardcoded manifest)
        let isPlaceholder = context.expectedSHA256.allSatisfy({ $0 == "0" })
        if !isPlaceholder {
            guard let hash = sha256OfFile(at: destFile) else {
                try? fileManager.removeItem(at: destFile)
                markRegionFailed(context.regionId, error: "SHA-256 verification failed for \(context.fileType)")
                return
            }
            if hash != context.expectedSHA256 {
                try? fileManager.removeItem(at: destFile)
                markRegionFailed(context.regionId, error: "SHA-256 mismatch for \(context.fileType)")
                return
            }
        }

        OshiLog.maps.info("[Maps] Completed \(context.fileType) for \(context.regionId)")

        // Accumulate completed bytes for accurate progress tracking
        completedBytesPerRegion[context.regionId, default: 0] += Int64(context.expectedSize)

        // Start next file in sequential queue
        startNextFileDownload(for: context.regionId)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let context = activeDownloads[downloadTask] else { return }

        guard let region = manifest?.regions.first(where: { $0.id == context.regionId }) else { return }
        let totalRegionSize = Int64((region.files.tiles?.size ?? 0)
            + (region.files.routing?.size ?? 0)
            + (region.files.poi?.size ?? 0))
        guard totalRegionSize > 0 else { return }

        // Include bytes from previously completed files + current file progress
        let previouslyCompleted = completedBytesPerRegion[context.regionId] ?? 0
        let totalWritten = previouslyCompleted + totalBytesWritten

        let progress = min(1.0, Double(totalWritten) / Double(totalRegionSize))
        downloadStates[context.regionId] = .downloading(progress: progress)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        // Tile tasks: this is the ONE callback guaranteed to fire exactly once
        // per task (`didFinishDownloadingTo` does not fire for a transport
        // error), so it is where the window is refilled and progress counted.
        if let desc = task.taskDescription, desc.hasPrefix("tile|") {
            let parts = desc.split(separator: "|", maxSplits: 5, omittingEmptySubsequences: false)
            guard parts.count >= 2 else { return }
            let regionId = String(parts[1])
            tileDownloadLock.lock()
            let success = tileTaskSucceeded.remove(task.taskIdentifier) != nil
            tileDownloadLock.unlock()
            // A task we cancelled ourselves is never a task of the CURRENT job:
            // every cancel site (user cancel, watchdog, the stale-task sweep at
            // submit time) drops the job first. Ignoring it keeps a cancelled
            // previous generation from decrementing the new run's window.
            if (error as NSError?)?.code == NSURLErrorCancelled { return }
            tileTaskCompleted(regionId: regionId, success: success)
            return
        }

        guard let downloadTask = task as? URLSessionDownloadTask,
              let context = activeDownloads[downloadTask] else { return }

        if let error = error {
            let nsError = error as NSError
            if nsError.code == NSURLErrorCancelled, let data = nsError.userInfo[NSURLSessionDownloadTaskResumeData] as? Data {
                resumeData["\(context.regionId)_\(context.fileType)"] = data
            }
            activeDownloads.removeValue(forKey: downloadTask)
            let remaining = activeDownloads.values.filter { $0.regionId == context.regionId }
            if remaining.isEmpty && nsError.code != NSURLErrorCancelled {
                markRegionFailed(context.regionId, error: error.localizedDescription)
            }
        }
    }

    func urlSession(_ session: URLSession, didBecomeInvalidWithError error: Error?) {
        // Session invalidated; re-create if needed on next download
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        // iOS relaunched the app to deliver completed background tasks; once
        // we've drained them, call the system-supplied completion handler so
        // iOS can suspend us again. Without this the app stays alive and
        // burns battery for nothing.
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            let handler = self.backgroundCompletionHandler
            self.backgroundCompletionHandler = nil
            handler?()
        }
    }

    // MARK: - Finalization

    private func finalizeRegionDownload(_ regionId: String) {
        completedBytesPerRegion.removeValue(forKey: regionId)
        guard let region = manifest?.regions.first(where: { $0.id == regionId }) else { return }

        let meta = DownloadedRegionMetadata(
            regionId: regionId,
            downloadDate: Date(),
            version: manifest?.version ?? 1,
            // 🔧 FIX (2026-10-09): `files.tiles.size` is 0 in every manifest
            // entry, so a downloaded region's card read "38 MB" for France.
            // Record the enumerated tile estimate. The aggregate "Storage
            // used" figure deliberately does NOT sum this field — tiles are
            // shared between regions and are measured from disk instead.
            tilesSize: (region.files.tiles?.size ?? 0) > 0
                ? (region.files.tiles?.size ?? 0)
                : region.estimatedTileBytes,
            routingSize: region.files.routing?.realSize ?? 0,
            // `realSize`, not `size`: a sub-region inheriting the country POI
            // database reports size 0 + shared_size, and those bytes are on
            // this device's disk just the same. Using `size` under-reported
            // storage for every shared region.
            poiSize: region.files.poi?.realSize ?? 0
        )
        saveMetadata(meta)

        downloadedRegions.removeAll { $0.regionId == regionId }
        downloadedRegions.append(meta)
        downloadStates[regionId] = .downloaded(date: meta.downloadDate)
        recalculateStorage()
        clearInterruptedRegion(regionId)

        // Auto-download routing + street index + road graph after tiles complete.
        // road_graph.db is what OfflineRouter actually uses for A* — without it
        // routing falls back to the Overpass API (online, rate-limited).
        // OfflineRoadGraphDownloader publishes its own progress so the regions
        // UI shows the download just like streets/routing do.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
            // __ROUTING_ARCHIVE_NEVER_EXISTED_2026_10_28__ / completed 2026-11-02.
            //
            // The 28 Oct change removed the automatic routing fetch from
            // `startRegionDownload` — and missed THIS second call, which runs
            // after the tiles finish. So the orange "Routing data — Routing not
            // available for this region" was still reachable, and was still
            // being reported from a phone a week later. A fix that closes one of
            // two doors closes neither.
            //
            // Re-measured against the live manifest today: all 272 regions ship a
            // `roadgraph`, and `MapRoutingEngine` reads the ROAD GRAPH — the OSRM
            // `*-routing.tar.gz` has no consumer in this app and never had one
            // ("Actual OSRM routing engine integration comes in a LATER PHASE").
            // Fetching it would be hundreds of megabytes per region that nothing
            // opens: `ch-routing.tar.gz` alone is 522 MB. The road graph is
            // downloaded a few lines below, which is the navigation the user
            // actually gets.
            if !OfflineStreetDatabase.shared.isStreetIndexAvailable(regionId: regionId) {
                OfflineStreetDatabase.shared.downloadStreetIndex(region: region, progress: { _ in }, completion: { _ in })
            }
            if !OfflineRoadGraphDownloader.shared.isRoadGraphAvailable(regionId: regionId) {
                OfflineRoadGraphDownloader.shared.downloadRoadGraph(region: region)
            }
            // 🔧 FIX (2026-07-28): the POI database was the one layer nothing
            // ever fetched — see `downloadPOIDatabase`. Without it every
            // "supermarkets near me" / offline search fell through to the
            // online Overpass API, which is exactly what an offline map is
            // supposed to make unnecessary.
            self.downloadPOIDatabase(region: region)
        }
    }

    private func markRegionFailed(_ regionId: String, error: String) {
        for (task, ctx) in activeDownloads where ctx.regionId == regionId {
            task.cancel()
            activeDownloads.removeValue(forKey: task)
        }
        completedBytesPerRegion.removeValue(forKey: regionId)
        pendingDownloadQueues.removeValue(forKey: regionId)
        downloadStates[regionId] = .failed(error: error)
    }

    // MARK: - Queries

    func isRegionDownloaded(_ regionId: String) -> Bool {
        downloadedRegions.contains { $0.regionId == regionId }
    }

    func regionPath(_ regionId: String) -> URL {
        regionDirectory(for: regionId)
    }

    func tilesPath(for regionId: String) -> URL {
        regionDirectory(for: regionId).appendingPathComponent("tiles.bin")
    }

    func routingPath(for regionId: String) -> URL {
        regionDirectory(for: regionId).appendingPathComponent("routing.bin")
    }

    func poiPath(for regionId: String) -> URL {
        regionDirectory(for: regionId).appendingPathComponent("poi.db")
    }

    // MARK: - Hardcoded Fallback Manifest

    static let hardcodedManifest: MapManifest = {
        let base = "https://oshi-messenger.com/maps/v2"
        let date = "2026-04-01"
        let placeholder = "0000000000000000000000000000000000000000000000000000000000000000"

        func region(_ id: String, _ name: String, _ country: String, _ continent: String, _ bbox: [Double], tiles: Int, routing: Int, poi: Int) -> MapRegion {
            MapRegion(
                id: id, name: name, country: country, continent: continent, bbox: bbox,
                files: MapRegionFiles(
                    // 🔧 FIX (2026-07-28): tiles.bin / routing.bin have never
                    // existed on the server and nothing fetches them — nil
                    // rather than advertise a guaranteed 404. The POI URL was
                    // also wrong here (`poi.bin`; the server publishes
                    // `poi.db`), so even the fallback manifest pointed at a
                    // 404. Sizes below are rough estimates, kept only so the
                    // regions list can show an approximate download size when
                    // the real manifest is unreachable.
                    tiles: nil,
                    routing: nil,
                    poi: MapFile(url: "\(base)/\(id)/poi.db", size: poi, sha256: placeholder, shared: nil, sharedSize: nil),
                    streets: nil,
                    roadgraph: nil
                ),
                osmDate: date
            )
        }

        return MapManifest(version: 1, updated: date, regions: [
            // France
            region("fr-idf", "Ile-de-France", "FR", "europe", [1.44, 48.12, 3.56, 49.24], tiles: 420_000_000, routing: 85_000_000, poi: 38_000_000),
            region("fr-paca", "Provence-Alpes-Cote d'Azur", "FR", "europe", [4.23, 43.07, 7.72, 45.13], tiles: 310_000_000, routing: 68_000_000, poi: 28_000_000),
            region("fr-ara", "Auvergne-Rhone-Alpes", "FR", "europe", [2.06, 44.26, 7.19, 46.80], tiles: 380_000_000, routing: 78_000_000, poi: 32_000_000),
            region("fr-occ", "Occitanie", "FR", "europe", [-0.33, 42.33, 4.85, 45.05], tiles: 340_000_000, routing: 72_000_000, poi: 30_000_000),
            region("fr-naq", "Nouvelle-Aquitaine", "FR", "europe", [-1.79, 44.01, 2.61, 47.09], tiles: 360_000_000, routing: 74_000_000, poi: 29_000_000),
            region("fr-bre", "Bretagne", "FR", "europe", [-5.14, 47.28, -1.01, 48.90], tiles: 220_000_000, routing: 48_000_000, poi: 18_000_000),
            region("fr-nor", "Normandie", "FR", "europe", [-1.95, 48.18, 1.80, 49.73], tiles: 200_000_000, routing: 44_000_000, poi: 16_000_000),
            region("fr-hdf", "Hauts-de-France", "FR", "europe", [1.38, 48.83, 4.25, 51.09], tiles: 260_000_000, routing: 56_000_000, poi: 22_000_000),
            region("fr-ges", "Grand Est", "FR", "europe", [3.38, 47.42, 8.23, 50.17], tiles: 290_000_000, routing: 62_000_000, poi: 24_000_000),
            region("fr-pdl", "Pays de la Loire", "FR", "europe", [-2.61, 46.27, 0.92, 48.57], tiles: 230_000_000, routing: 50_000_000, poi: 19_000_000),
            region("fr-bfc", "Bourgogne-Franche-Comte", "FR", "europe", [2.84, 46.16, 7.14, 48.40], tiles: 250_000_000, routing: 54_000_000, poi: 20_000_000),
            region("fr-cvl", "Centre-Val de Loire", "FR", "europe", [0.05, 46.35, 3.13, 48.94], tiles: 240_000_000, routing: 52_000_000, poi: 18_000_000),
            region("fr-cor", "Corse", "FR", "europe", [8.57, 41.38, 9.57, 43.03], tiles: 80_000_000, routing: 18_000_000, poi: 6_000_000),

            // Germany
            region("de-by", "Bayern", "DE", "europe", [8.97, 47.27, 13.84, 50.56], tiles: 480_000_000, routing: 95_000_000, poi: 42_000_000),
            region("de-nw", "Nordrhein-Westfalen", "DE", "europe", [5.87, 50.32, 9.46, 52.53], tiles: 420_000_000, routing: 88_000_000, poi: 38_000_000),
            region("de-bw", "Baden-Wurttemberg", "DE", "europe", [7.51, 47.53, 10.50, 49.79], tiles: 380_000_000, routing: 80_000_000, poi: 34_000_000),
            region("de-ni", "Niedersachsen", "DE", "europe", [6.65, 51.30, 11.60, 53.89], tiles: 360_000_000, routing: 76_000_000, poi: 30_000_000),
            region("de-he", "Hessen", "DE", "europe", [7.77, 49.39, 10.24, 51.66], tiles: 280_000_000, routing: 60_000_000, poi: 24_000_000),
            region("de-be", "Berlin", "DE", "europe", [13.09, 52.34, 13.76, 52.68], tiles: 120_000_000, routing: 28_000_000, poi: 18_000_000),

            // United Kingdom
            region("gb-eng-lon", "Greater London", "GB", "europe", [-0.51, 51.29, 0.33, 51.69], tiles: 350_000_000, routing: 72_000_000, poi: 40_000_000),
            region("gb-eng-se", "South East England", "GB", "europe", [-1.96, 50.59, 1.42, 52.08], tiles: 380_000_000, routing: 78_000_000, poi: 32_000_000),
            region("gb-eng-nw", "North West England", "GB", "europe", [-3.06, 53.06, -2.00, 54.46], tiles: 260_000_000, routing: 56_000_000, poi: 22_000_000),
            region("gb-sct", "Scotland", "GB", "europe", [-7.69, 54.63, -0.72, 60.86], tiles: 320_000_000, routing: 68_000_000, poi: 20_000_000),

            // Spain
            region("es-md", "Madrid", "ES", "europe", [-4.58, 39.88, -3.05, 41.17], tiles: 280_000_000, routing: 58_000_000, poi: 26_000_000),
            region("es-ct", "Cataluna", "ES", "europe", [0.16, 40.52, 3.33, 42.86], tiles: 320_000_000, routing: 66_000_000, poi: 28_000_000),
            region("es-an", "Andalucia", "ES", "europe", [-7.52, 36.00, -1.63, 38.73], tiles: 360_000_000, routing: 74_000_000, poi: 30_000_000),
            region("es-vc", "Valencia", "ES", "europe", [-1.53, 37.84, 0.69, 40.79], tiles: 240_000_000, routing: 50_000_000, poi: 20_000_000),

            // Italy
            region("it-laz", "Lazio", "IT", "europe", [11.45, 41.18, 14.03, 42.84], tiles: 300_000_000, routing: 62_000_000, poi: 28_000_000),
            region("it-lom", "Lombardia", "IT", "europe", [8.50, 44.68, 11.43, 46.64], tiles: 340_000_000, routing: 70_000_000, poi: 30_000_000),
            region("it-cam", "Campania", "IT", "europe", [13.76, 39.99, 15.81, 41.51], tiles: 220_000_000, routing: 46_000_000, poi: 20_000_000),
            region("it-tos", "Toscana", "IT", "europe", [9.69, 42.24, 12.37, 44.47], tiles: 280_000_000, routing: 58_000_000, poi: 24_000_000),

            // ── Full European Countries ──

            // Western Europe
            region("fr", "France", "FR", "europe", [-5.14, 41.33, 9.57, 51.09], tiles: 2_800_000_000, routing: 580_000_000, poi: 240_000_000),
            region("de", "Germany", "DE", "europe", [5.87, 47.27, 15.04, 55.06], tiles: 2_600_000_000, routing: 540_000_000, poi: 220_000_000),
            region("gb", "United Kingdom", "GB", "europe", [-8.17, 49.96, 1.75, 60.86], tiles: 2_200_000_000, routing: 460_000_000, poi: 200_000_000),
            region("es", "Spain", "ES", "europe", [-9.30, 36.00, 4.33, 43.79], tiles: 2_400_000_000, routing: 500_000_000, poi: 210_000_000),
            region("it", "Italy", "IT", "europe", [6.63, 36.65, 18.52, 47.09], tiles: 2_200_000_000, routing: 460_000_000, poi: 200_000_000),
            region("pt", "Portugal", "PT", "europe", [-9.50, 36.96, -6.19, 42.15], tiles: 680_000_000, routing: 140_000_000, poi: 60_000_000),
            region("ie", "Ireland", "IE", "europe", [-10.48, 51.42, -5.99, 55.38], tiles: 520_000_000, routing: 108_000_000, poi: 46_000_000),

            // Benelux / Alpine
            region("nl", "Netherlands", "NL", "europe", [3.36, 50.75, 7.21, 53.47], tiles: 320_000_000, routing: 66_000_000, poi: 30_000_000),
            region("be", "Belgium", "BE", "europe", [2.54, 49.50, 6.41, 51.51], tiles: 240_000_000, routing: 52_000_000, poi: 22_000_000),
            region("lu", "Luxembourg", "LU", "europe", [5.73, 49.45, 6.53, 50.18], tiles: 60_000_000, routing: 12_000_000, poi: 5_000_000),
            region("ch", "Switzerland", "CH", "europe", [5.96, 45.82, 10.49, 47.81], tiles: 260_000_000, routing: 56_000_000, poi: 24_000_000),
            region("at", "Austria", "AT", "europe", [9.53, 46.37, 17.16, 49.02], tiles: 300_000_000, routing: 64_000_000, poi: 26_000_000),
            region("li", "Liechtenstein", "LI", "europe", [9.47, 47.05, 9.64, 47.27], tiles: 20_000_000, routing: 4_000_000, poi: 2_000_000),
            region("mc", "Monaco", "MC", "europe", [7.41, 43.72, 7.44, 43.75], tiles: 10_000_000, routing: 2_000_000, poi: 1_000_000),
            region("ad", "Andorra", "AD", "europe", [1.41, 42.43, 1.79, 42.66], tiles: 30_000_000, routing: 6_000_000, poi: 3_000_000),

            // Nordics
            region("se", "Sweden", "SE", "europe", [11.11, 55.34, 24.17, 69.06], tiles: 1_800_000_000, routing: 380_000_000, poi: 160_000_000),
            region("no", "Norway", "NO", "europe", [4.65, 57.97, 31.08, 71.18], tiles: 1_600_000_000, routing: 340_000_000, poi: 140_000_000),
            region("dk", "Denmark", "DK", "europe", [8.07, 54.56, 15.19, 57.75], tiles: 220_000_000, routing: 48_000_000, poi: 18_000_000),
            region("fi", "Finland", "FI", "europe", [20.55, 59.81, 31.59, 70.09], tiles: 1_400_000_000, routing: 300_000_000, poi: 120_000_000),
            region("is", "Iceland", "IS", "europe", [-24.53, 63.39, -13.50, 66.53], tiles: 400_000_000, routing: 80_000_000, poi: 20_000_000),

            // Central Europe
            region("pl", "Poland", "PL", "europe", [14.12, 49.00, 24.15, 54.84], tiles: 1_400_000_000, routing: 300_000_000, poi: 120_000_000),
            region("cz", "Czech Republic", "CZ", "europe", [12.09, 48.55, 18.86, 51.06], tiles: 600_000_000, routing: 124_000_000, poi: 52_000_000),
            region("sk", "Slovakia", "SK", "europe", [16.85, 47.73, 22.57, 49.61], tiles: 380_000_000, routing: 78_000_000, poi: 32_000_000),
            region("hu", "Hungary", "HU", "europe", [16.11, 45.74, 22.90, 48.58], tiles: 520_000_000, routing: 108_000_000, poi: 44_000_000),
            region("si", "Slovenia", "SI", "europe", [13.38, 45.42, 16.60, 46.88], tiles: 180_000_000, routing: 38_000_000, poi: 16_000_000),

            // Southeast Europe
            region("hr", "Croatia", "HR", "europe", [13.49, 42.39, 19.43, 46.55], tiles: 400_000_000, routing: 82_000_000, poi: 34_000_000),
            region("rs", "Serbia", "RS", "europe", [18.84, 42.23, 23.01, 46.19], tiles: 440_000_000, routing: 90_000_000, poi: 36_000_000),
            region("ba", "Bosnia and Herzegovina", "BA", "europe", [15.73, 42.56, 19.62, 45.28], tiles: 300_000_000, routing: 62_000_000, poi: 24_000_000),
            region("me", "Montenegro", "ME", "europe", [18.46, 41.85, 20.36, 43.57], tiles: 120_000_000, routing: 24_000_000, poi: 10_000_000),
            region("mk", "North Macedonia", "MK", "europe", [20.45, 40.85, 23.03, 42.37], tiles: 160_000_000, routing: 34_000_000, poi: 14_000_000),
            region("al", "Albania", "AL", "europe", [19.26, 39.64, 21.06, 42.66], tiles: 180_000_000, routing: 38_000_000, poi: 14_000_000),
            region("xk", "Kosovo", "XK", "europe", [20.01, 41.86, 21.79, 43.27], tiles: 100_000_000, routing: 20_000_000, poi: 8_000_000),
            region("gr", "Greece", "GR", "europe", [19.37, 34.80, 29.65, 41.75], tiles: 800_000_000, routing: 166_000_000, poi: 68_000_000),
            region("bg", "Bulgaria", "BG", "europe", [22.36, 41.24, 28.61, 44.21], tiles: 520_000_000, routing: 108_000_000, poi: 44_000_000),
            region("ro", "Romania", "RO", "europe", [20.26, 43.62, 29.69, 48.27], tiles: 800_000_000, routing: 166_000_000, poi: 68_000_000),

            // Baltics
            region("ee", "Estonia", "EE", "europe", [21.83, 57.52, 28.21, 59.67], tiles: 240_000_000, routing: 50_000_000, poi: 20_000_000),
            region("lv", "Latvia", "LV", "europe", [20.97, 55.67, 28.24, 58.08], tiles: 260_000_000, routing: 54_000_000, poi: 22_000_000),
            region("lt", "Lithuania", "LT", "europe", [20.93, 53.90, 26.84, 56.45], tiles: 280_000_000, routing: 58_000_000, poi: 24_000_000),

            // Other European
            region("cy", "Cyprus", "CY", "europe", [32.27, 34.57, 34.60, 35.70], tiles: 100_000_000, routing: 20_000_000, poi: 8_000_000),
            region("mt", "Malta", "MT", "europe", [14.18, 35.81, 14.57, 36.08], tiles: 30_000_000, routing: 6_000_000, poi: 4_000_000),

            // Eastern Europe
            region("ua", "Ukraine", "UA", "europe", [22.14, 44.39, 40.23, 52.38], tiles: 1_800_000_000, routing: 380_000_000, poi: 140_000_000),
            region("md", "Moldova", "MD", "europe", [26.62, 45.47, 30.16, 48.49], tiles: 200_000_000, routing: 42_000_000, poi: 16_000_000),
            region("by", "Belarus", "BY", "europe", [23.18, 51.26, 32.77, 56.17], tiles: 600_000_000, routing: 124_000_000, poi: 48_000_000),

            // USA
            region("us-ny", "New York", "US", "north_america", [-74.26, 40.50, -73.70, 40.92], tiles: 380_000_000, routing: 78_000_000, poi: 42_000_000),
            region("us-ca-la", "Los Angeles", "US", "north_america", [-118.67, 33.70, -117.65, 34.34], tiles: 360_000_000, routing: 74_000_000, poi: 38_000_000),
            region("us-ca-sf", "San Francisco Bay", "US", "north_america", [-122.52, 37.21, -121.73, 37.93], tiles: 280_000_000, routing: 58_000_000, poi: 30_000_000),
            region("us-il", "Chicago", "US", "north_america", [-88.26, 41.47, -87.52, 42.15], tiles: 300_000_000, routing: 62_000_000, poi: 32_000_000),
            region("us-tx-da", "Dallas-Fort Worth", "US", "north_america", [-97.50, 32.55, -96.46, 33.12], tiles: 240_000_000, routing: 50_000_000, poi: 24_000_000),
            region("us-fl-mi", "Miami", "US", "north_america", [-80.87, 25.51, -80.12, 26.21], tiles: 200_000_000, routing: 42_000_000, poi: 20_000_000),

            // Japan / South Korea
            region("jp-to", "Tokyo", "JP", "asia", [138.94, 35.22, 140.07, 36.10], tiles: 420_000_000, routing: 86_000_000, poi: 44_000_000),
            region("jp-os", "Osaka", "JP", "asia", [135.10, 34.30, 135.80, 35.00], tiles: 280_000_000, routing: 58_000_000, poi: 30_000_000),
            region("kr-se", "Seoul", "KR", "asia", [126.76, 37.43, 127.18, 37.70], tiles: 340_000_000, routing: 70_000_000, poi: 36_000_000),

            // Oceania
            region("au-syd", "Sydney", "AU", "oceania", [150.52, -34.17, 151.34, -33.58], tiles: 260_000_000, routing: 54_000_000, poi: 24_000_000),
            region("au-mel", "Melbourne", "AU", "oceania", [144.59, -38.08, 145.51, -37.51], tiles: 240_000_000, routing: 50_000_000, poi: 22_000_000),

            // South America
            region("br-sp", "Sao Paulo", "BR", "south_america", [-47.00, -24.01, -46.37, -23.36], tiles: 340_000_000, routing: 70_000_000, poi: 32_000_000),
            region("co", "Colombia", "CO", "south_america", [-79.00, -4.23, -66.87, 12.46], tiles: 1_200_000_000, routing: 250_000_000, poi: 100_000_000),
            region("mx", "Mexico", "MX", "north_america", [-118.40, 14.53, -86.71, 32.72], tiles: 2_000_000_000, routing: 420_000_000, poi: 180_000_000),

            // Africa
            region("ma", "Morocco", "MA", "africa", [-13.17, 27.66, -1.01, 35.92], tiles: 600_000_000, routing: 124_000_000, poi: 50_000_000),
            region("eg", "Egypt", "EG", "africa", [24.70, 22.00, 36.87, 31.67], tiles: 500_000_000, routing: 104_000_000, poi: 42_000_000),
            region("tn", "Tunisia", "TN", "africa", [7.52, 30.23, 11.60, 37.54], tiles: 200_000_000, routing: 42_000_000, poi: 16_000_000),
            region("dz", "Algeria", "DZ", "africa", [-8.68, 18.97, 11.99, 37.09], tiles: 1_200_000_000, routing: 250_000_000, poi: 90_000_000),
            region("za", "South Africa", "ZA", "africa", [16.45, -34.83, 32.89, -22.13], tiles: 1_400_000_000, routing: 290_000_000, poi: 120_000_000),
            region("ng", "Nigeria", "NG", "africa", [2.66, 4.27, 14.68, 13.89], tiles: 900_000_000, routing: 190_000_000, poi: 80_000_000),
            region("ke", "Kenya", "KE", "africa", [33.91, -4.68, 41.91, 5.51], tiles: 700_000_000, routing: 150_000_000, poi: 60_000_000),

            // Middle East
            region("ae", "United Arab Emirates", "AE", "asia", [51.58, 22.63, 56.38, 26.08], tiles: 300_000_000, routing: 62_000_000, poi: 26_000_000),
            region("sa", "Saudi Arabia", "SA", "middle_east", [34.50, 16.34, 55.67, 32.16], tiles: 1_400_000_000, routing: 290_000_000, poi: 110_000_000),
            region("il", "Israel", "IL", "middle_east", [34.27, 29.50, 35.90, 33.34], tiles: 240_000_000, routing: 52_000_000, poi: 22_000_000),
            region("tr", "Turkey", "TR", "europe", [26.04, 35.82, 44.79, 42.10], tiles: 2_000_000_000, routing: 420_000_000, poi: 170_000_000),

            // Asia
            region("in", "India", "IN", "asia", [68.18, 6.75, 97.40, 35.50], tiles: 3_000_000_000, routing: 620_000_000, poi: 260_000_000),
            region("jp", "Japan", "JP", "asia", [122.93, 24.05, 153.98, 45.52], tiles: 2_400_000_000, routing: 500_000_000, poi: 200_000_000),
            region("kr", "South Korea", "KR", "asia", [125.07, 33.19, 129.58, 38.61], tiles: 1_200_000_000, routing: 250_000_000, poi: 100_000_000),
            region("cn", "China", "CN", "asia", [73.55, 18.16, 134.77, 53.55], tiles: 8_000_000_000, routing: 1_600_000_000, poi: 600_000_000),
            region("hk", "Hong Kong", "HK", "asia", [113.83, 22.15, 114.41, 22.56], tiles: 100_000_000, routing: 22_000_000, poi: 12_000_000),
            region("tw", "Taiwan", "TW", "asia", [120.04, 21.89, 122.00, 25.30], tiles: 400_000_000, routing: 84_000_000, poi: 36_000_000),
            region("sg", "Singapore", "SG", "asia", [103.61, 1.16, 104.05, 1.47], tiles: 80_000_000, routing: 18_000_000, poi: 14_000_000),
            region("my", "Malaysia", "MY", "asia", [99.64, 0.85, 119.27, 7.36], tiles: 900_000_000, routing: 190_000_000, poi: 80_000_000),
            region("th", "Thailand", "TH", "asia", [97.34, 5.61, 105.64, 20.46], tiles: 1_100_000_000, routing: 230_000_000, poi: 90_000_000),
            region("vn", "Vietnam", "VN", "asia", [102.14, 8.55, 109.47, 23.39], tiles: 800_000_000, routing: 170_000_000, poi: 70_000_000),
            region("ph", "Philippines", "PH", "asia", [116.93, 4.59, 126.61, 21.12], tiles: 700_000_000, routing: 150_000_000, poi: 60_000_000),
            region("id", "Indonesia", "ID", "asia", [95.01, -10.93, 141.02, 5.91], tiles: 1_800_000_000, routing: 380_000_000, poi: 150_000_000),

            // Americas
            region("us", "United States", "US", "north_america", [-125.00, 24.40, -66.93, 49.39], tiles: 8_000_000_000, routing: 1_700_000_000, poi: 720_000_000),
            region("ca", "Canada", "CA", "north_america", [-141.00, 41.68, -52.62, 83.11], tiles: 2_800_000_000, routing: 580_000_000, poi: 240_000_000),
            region("br", "Brazil", "BR", "south_america", [-73.99, -33.75, -28.84, 5.27], tiles: 3_400_000_000, routing: 700_000_000, poi: 290_000_000),
            region("ar", "Argentina", "AR", "south_america", [-73.59, -55.06, -53.64, -21.78], tiles: 1_400_000_000, routing: 290_000_000, poi: 120_000_000),
            region("cl", "Chile", "CL", "south_america", [-75.65, -55.92, -66.42, -17.50], tiles: 700_000_000, routing: 150_000_000, poi: 60_000_000),
            region("pe", "Peru", "PE", "south_america", [-81.32, -18.35, -68.65, -0.04], tiles: 800_000_000, routing: 170_000_000, poi: 70_000_000),

            // Russia + Oceania
            region("ru", "Russia", "RU", "europe", [19.64, 41.18, 180.00, 81.86], tiles: 9_000_000_000, routing: 1_900_000_000, poi: 800_000_000),
            region("nz", "New Zealand", "NZ", "oceania", [166.43, -47.29, 178.55, -34.39], tiles: 500_000_000, routing: 104_000_000, poi: 42_000_000),

            // Oceania
            region("au", "Australia", "AU", "oceania", [113.34, -43.63, 153.64, -10.67], tiles: 2_400_000_000, routing: 500_000_000, poi: 200_000_000),
        ])
    }()
}
