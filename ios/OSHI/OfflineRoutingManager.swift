//
//  OfflineRoutingManager.swift
//  OSHI
//
//  Manages downloading, storing, and querying offline routing data files.
//  Routing files are OSRM-processed archives downloaded from the server.
//  Actual OSRM routing engine integration comes in a later phase;
//  this file handles the complete download + storage + query infrastructure.
//

import Foundation
import Combine
import CoreLocation

final class OfflineRoutingManager: ObservableObject {

    static let shared = OfflineRoutingManager()

    // MARK: - Published State

    @Published var downloadProgress: [String: Double] = [:]   // regionId -> 0.0 ... 1.0
    @Published var isDownloading: [String: Bool] = [:]         // regionId -> true while active
    @Published var downloadError: [String: String] = [:]       // regionId -> error message

    // MARK: - Constants

    private static let routingBaseURL = "https://oshi-messenger.com/files/routing"
    private static let routingSubdirectory = "Maps/routing"

    // MARK: - Private

    private let fileManager = FileManager.default
    private var activeTasks: [String: URLSessionDataTask] = [:]
    private let taskLock = NSLock()

    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 600  // 10 min for large files
        config.httpAdditionalHeaders = ["User-Agent": "OSHI-Messenger/1.0"]
        return URLSession(configuration: config)
    }()

    private init() {
        ensureDirectoryExists(routingRootDirectory)
    }

    // MARK: - Directory Management

    private var routingRootDirectory: URL {
        let docs = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first!
        return docs.appendingPathComponent(Self.routingSubdirectory, isDirectory: true)
    }

    /// Directory for a specific region's routing data.
    private func routingDirectory(for regionId: String) -> URL {
        routingRootDirectory.appendingPathComponent(regionId, isDirectory: true)
    }

    private func ensureDirectoryExists(_ url: URL) {
        if !fileManager.fileExists(atPath: url.path) {
            try? fileManager.createDirectory(at: url, withIntermediateDirectories: true)
        }
    }

    // MARK: - Public API

    /// Whether offline routing files are available for the given region.
    func isRoutingAvailable(regionId: String) -> Bool {
        let dir = routingDirectory(for: regionId)
        let markerFile = dir.appendingPathComponent(".routing_ready")
        return fileManager.fileExists(atPath: markerFile.path)
    }

    /// Full file path to the routing data directory for a region.
    func routingFilePath(regionId: String) -> URL {
        routingDirectory(for: regionId)
    }

    /// Say, in the user's language, that this region has no offline navigation
    /// to download — instead of starting a fetch that cannot help.
    ///
    /// __ROUTING_ARCHIVE_NEVER_EXISTED_2026_10_28__ / 2026-11-02. The two strings
    /// this screen used to show here were English literals compiled into the
    /// binary ("Routing not available for this region", "Server returned invalid
    /// response"), so a French user read an English sentence next to a localized
    /// label — which is exactly how the report arrived: "an orange warning saying
    /// données d'itinéraire routing not available for this regions".
    func reportNoRoutingData(regionId: String) {
        DispatchQueue.main.async {
            self.isDownloading[regionId] = false
            self.downloadProgress.removeValue(forKey: regionId)
            self.downloadError[regionId] = "map.routing.unavailable.body".localized
        }
    }

    /// Download routing data for a region.
    /// The file is downloaded as a tar.gz from the server, then decompressed locally.
    /// The server today only ships country-level files for most countries
    /// (e.g. `fr-routing.tar.gz`, no `fr-idf-routing.tar.gz`). When the
    /// regional URL 404s, we automatically retry with the country-level
    /// fallback derived from the region id prefix (`fr-idf` → `fr`).
    /// Without this, every regional download produced
    /// "Server returned invalid response".
    func downloadRouting(region: MapRegion) {
        let regionId = region.id
        let urlString = "\(Self.routingBaseURL)/\(regionId)-routing.tar.gz"
        guard let url = URL(string: urlString) else {
            downloadError[regionId] = "Invalid URL"
            return
        }

        // Prevent duplicate downloads
        taskLock.lock()
        if activeTasks[regionId] != nil {
            taskLock.unlock()
            return
        }
        taskLock.unlock()

        // Reset state
        DispatchQueue.main.async {
            self.downloadProgress[regionId] = 0.0
            self.isDownloading[regionId] = true
            self.downloadError.removeValue(forKey: regionId)
        }

        OshiLog.maps.info("[OfflineRouting] Starting download for \(regionId) from \(urlString)")

        // Use a delegate-based approach for progress tracking
        let destDir = routingDirectory(for: regionId)
        ensureDirectoryExists(destDir)
        let tempFile = destDir.appendingPathComponent("routing.tar.gz")

        // Remove old temp file if exists
        try? fileManager.removeItem(at: tempFile)

        // Download with progress using URLSession observation
        let task = session.dataTask(with: url) { [weak self] data, response, error in
            guard let self = self else { return }

            defer {
                self.taskLock.lock()
                self.activeTasks.removeValue(forKey: regionId)
                self.taskLock.unlock()
            }

            if let error = error {
                DispatchQueue.main.async {
                    self.isDownloading[regionId] = false
                    self.downloadError[regionId] = error.localizedDescription
                    self.downloadProgress.removeValue(forKey: regionId)
                }
                OshiLog.maps.info("[OfflineRouting] Download failed for \(regionId): \(error.localizedDescription)")
                return
            }

            guard let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200,
                  let data = data, !data.isEmpty else {
                let httpStatus = (response as? HTTPURLResponse)?.statusCode ?? -1
                // Fallback: regional file missing → try the country-level
                // bundle. `fr-idf-routing.tar.gz` 404 → retry `fr-routing.tar.gz`.
                if httpStatus == 404,
                   let dash = regionId.firstIndex(of: "-"),
                   dash != regionId.startIndex {
                    let country = String(regionId[..<dash])
                    let fallbackURL = URL(string: "\(Self.routingBaseURL)/\(country)-routing.tar.gz")
                    if let fallbackURL = fallbackURL, fallbackURL != url {
                        OshiLog.maps.info("[OfflineRouting] \(regionId) returned 404 — retrying with country-level \(country)-routing.tar.gz")
                        self.taskLock.lock()
                        self.activeTasks.removeValue(forKey: regionId)
                        self.taskLock.unlock()
                        // Recurse with a synthesized country region. We don't
                        // mutate the region id stored locally — the file
                        // ends up in the same regionDirectory.
                        let countryRegion = MapRegion(
                            id: regionId,
                            name: region.name + " (country routing)",
                            country: region.country,
                            continent: region.continent,
                            bbox: region.bbox,
                            files: MapRegionFiles(
                                tiles: region.files.tiles,
                                routing: MapFile(url: fallbackURL.absoluteString,
                                                 size: region.files.routing?.size ?? 0,
                                                 sha256: region.files.routing?.sha256 ?? "",
                                                 shared: region.files.routing?.shared,
                                                 sharedSize: region.files.routing?.sharedSize),
                                poi: region.files.poi,
                                streets: region.files.streets,
                                roadgraph: region.files.roadgraph
                            ),
                            osmDate: region.osmDate
                        )
                        // Inline fetch from fallback URL using a fresh task —
                        // avoids re-running the 404 path.
                        let fallbackTask = self.session.dataTask(with: fallbackURL) { fbData, fbResp, fbErr in
                            defer {
                                self.taskLock.lock()
                                self.activeTasks.removeValue(forKey: regionId)
                                self.taskLock.unlock()
                            }
                            if let fbErr = fbErr {
                                DispatchQueue.main.async {
                                    self.isDownloading[regionId] = false
                                    self.downloadError[regionId] = fbErr.localizedDescription
                                    self.downloadProgress.removeValue(forKey: regionId)
                                }
                                return
                            }
                            guard let fbHTTP = fbResp as? HTTPURLResponse,
                                  fbHTTP.statusCode == 200,
                                  let fbData = fbData, !fbData.isEmpty else {
                                DispatchQueue.main.async {
                                    self.isDownloading[regionId] = false
                                    self.downloadError[regionId] = "map.routing.unavailable.body".localized
                                    self.downloadProgress.removeValue(forKey: regionId)
                                }
                                return
                            }
                            do {
                                try fbData.write(to: tempFile, options: .atomic)
                            } catch {
                                DispatchQueue.main.async {
                                    self.isDownloading[regionId] = false
                                    self.downloadError[regionId] = "Failed to write file: \(error.localizedDescription)"
                                    self.downloadProgress.removeValue(forKey: regionId)
                                }
                                return
                            }
                            self.decompressRoutingFile(tempFile: tempFile, destDir: destDir, regionId: regionId)
                        }
                        self.taskLock.lock()
                        self.activeTasks[regionId] = fallbackTask
                        self.taskLock.unlock()
                        fallbackTask.resume()
                        _ = countryRegion // silence unused
                        return
                    }
                }
                DispatchQueue.main.async {
                    self.isDownloading[regionId] = false
                    self.downloadError[regionId] = httpStatus == 404
                        ? "map.routing.unavailable.body".localized
                        : "map.regions.download_failed".localized
                    self.downloadProgress.removeValue(forKey: regionId)
                }
                return
            }

            // Write data to temp file
            do {
                try data.write(to: tempFile, options: .atomic)
            } catch {
                DispatchQueue.main.async {
                    self.isDownloading[regionId] = false
                    self.downloadError[regionId] = "Failed to write file: \(error.localizedDescription)"
                    self.downloadProgress.removeValue(forKey: regionId)
                }
                return
            }

            // Decompress tar.gz
            self.decompressRoutingFile(tempFile: tempFile, destDir: destDir, regionId: regionId)
        }

        taskLock.lock()
        activeTasks[regionId] = task
        taskLock.unlock()

        // Observe progress
        let observation = task.progress.observe(\.fractionCompleted) { [weak self] progress, _ in
            DispatchQueue.main.async {
                self?.downloadProgress[regionId] = progress.fractionCompleted
            }
        }
        // Store observation to keep it alive (will be released when task completes)
        objc_setAssociatedObject(task, "progressObservation", observation, .OBJC_ASSOCIATION_RETAIN)

        task.resume()
    }

    /// Delete all routing files for a region.
    func deleteRouting(regionId: String) {
        // Cancel active download if any
        taskLock.lock()
        if let task = activeTasks[regionId] {
            task.cancel()
            activeTasks.removeValue(forKey: regionId)
        }
        taskLock.unlock()

        let dir = routingDirectory(for: regionId)
        try? fileManager.removeItem(at: dir)

        DispatchQueue.main.async {
            self.downloadProgress.removeValue(forKey: regionId)
            self.isDownloading.removeValue(forKey: regionId)
            self.downloadError.removeValue(forKey: regionId)
        }

        OshiLog.maps.info("[OfflineRouting] Deleted routing data for \(regionId)")
    }

    /// Cancel an in-progress download.
    func cancelDownload(regionId: String) {
        taskLock.lock()
        if let task = activeTasks[regionId] {
            task.cancel()
            activeTasks.removeValue(forKey: regionId)
        }
        taskLock.unlock()

        DispatchQueue.main.async {
            self.isDownloading[regionId] = false
            self.downloadProgress.removeValue(forKey: regionId)
        }
    }

    /// Total disk space used by all routing files.
    func totalRoutingStorageUsed() -> Int64 {
        guard let contents = try? fileManager.contentsOfDirectory(atPath: routingRootDirectory.path) else {
            return 0
        }
        var total: Int64 = 0
        for regionId in contents {
            let dir = routingDirectory(for: regionId)
            total += directorySize(dir)
        }
        return total
    }

    /// List of region IDs that have routing downloaded.
    func downloadedRoutingRegions() -> [String] {
        guard let contents = try? fileManager.contentsOfDirectory(atPath: routingRootDirectory.path) else {
            return []
        }
        return contents.filter { isRoutingAvailable(regionId: $0) }
    }

    // MARK: - Decompression

    private func decompressRoutingFile(tempFile: URL, destDir: URL, regionId: String) {
        DispatchQueue.global(qos: .utility).async { [weak self] in
            guard let self = self else { return }

            // Use tar to decompress (available on iOS via Process alternative)
            // Since Process is not available on iOS, we use a simpler approach:
            // The routing files will be stored as-is and decompressed using built-in gzip
            do {
                // For now, just move the archive and create a marker file
                // Full OSRM integration will handle the actual decompression
                let archiveFile = destDir.appendingPathComponent("routing_data.tar.gz")

                // Move temp file to final location
                if self.fileManager.fileExists(atPath: archiveFile.path) {
                    try self.fileManager.removeItem(at: archiveFile)
                }
                try self.fileManager.moveItem(at: tempFile, to: archiveFile)

                // Create a marker file indicating routing data is ready
                let markerFile = destDir.appendingPathComponent(".routing_ready")
                let markerData = [
                    "region_id": regionId,
                    "download_date": ISO8601DateFormatter().string(from: Date()),
                    "status": "downloaded"
                ]
                let markerJSON = try JSONSerialization.data(withJSONObject: markerData)
                try markerJSON.write(to: markerFile)

                DispatchQueue.main.async {
                    self.isDownloading[regionId] = false
                    self.downloadProgress[regionId] = 1.0

                    // Update the OfflineMapManager metadata
                    self.updateRegionMetadataWithRouting(regionId: regionId)
                }

                OshiLog.maps.info("[OfflineRouting] Routing data ready for \(regionId)")

            } catch {
                // Clean up on failure
                try? self.fileManager.removeItem(at: destDir)
                self.ensureDirectoryExists(destDir)

                DispatchQueue.main.async {
                    self.isDownloading[regionId] = false
                    self.downloadError[regionId] = "Decompression failed: \(error.localizedDescription)"
                    self.downloadProgress.removeValue(forKey: regionId)
                }
                OshiLog.maps.info("[OfflineRouting] Decompression failed for \(regionId): \(error.localizedDescription)")
            }
        }
    }

    /// Update the OfflineMapManager's metadata to reflect that routing is now available.
    private func updateRegionMetadataWithRouting(regionId: String) {
        let mapManager = OfflineMapManager.shared
        if let index = mapManager.downloadedRegions.firstIndex(where: { $0.regionId == regionId }) {
            var meta = mapManager.downloadedRegions[index]
            meta = DownloadedRegionMetadata(
                regionId: meta.regionId,
                downloadDate: meta.downloadDate,
                version: meta.version,
                tilesSize: meta.tilesSize,
                routingSize: meta.routingSize,
                poiSize: meta.poiSize,
                hasRouting: true
            )
            mapManager.downloadedRegions[index] = meta
            // Re-save metadata to disk
            let dir = mapManager.regionPath(meta.regionId)
            let metaFile = dir.appendingPathComponent("metadata.json")
            if let data = try? JSONEncoder().encode(meta) {
                try? data.write(to: metaFile)
            }
        }
    }

    // MARK: - Helpers

    private func directorySize(_ url: URL) -> Int64 {
        guard let enumerator = fileManager.enumerator(at: url, includingPropertiesForKeys: [.fileSizeKey]) else {
            return 0
        }
        var total: Int64 = 0
        for case let fileURL as URL in enumerator {
            if let size = try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize {
                total += Int64(size)
            }
        }
        return total
    }
}
