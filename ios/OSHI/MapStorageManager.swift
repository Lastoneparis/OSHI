//
//  MapStorageManager.swift
//  OSHI - Offline Map Storage Manager
//
//  Handles all file system operations for offline maps.
//  iOS 16+, SwiftUI
//

import Foundation

class MapStorageManager {
    static let shared = MapStorageManager()

    private let fileManager = FileManager.default

    /// Base directory: Documents/Maps/
    let mapsDirectory: URL

    private init() {
        let documents = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first!
        mapsDirectory = documents.appendingPathComponent("Maps", isDirectory: true)
        ensureDirectoryStructure()
    }

    // MARK: - Directory Helpers

    /// Region directory: Documents/Maps/regions/{regionId}/
    func regionDirectory(for regionId: String) -> URL {
        mapsDirectory
            .appendingPathComponent("regions", isDirectory: true)
            .appendingPathComponent(regionId, isDirectory: true)
    }

    /// Ensure base directories exist and are excluded from iCloud backup
    func ensureDirectoryStructure() {
        let regionsDir = mapsDirectory.appendingPathComponent("regions", isDirectory: true)

        for dir in [mapsDirectory, regionsDir] {
            if !fileManager.fileExists(atPath: dir.path) {
                do {
                    try fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
                    OshiLog.maps.info("[MapStorage] Created directory: \(dir.path)")
                } catch {
                    OshiLog.maps.info("[MapStorage] Failed to create directory \(dir.path): \(error)")
                }
            }
            excludeFromBackup(dir)
        }
    }

    // MARK: - Region Status

    /// Check if a region has any installed offline asset on disk.
    ///
    /// 🔧 FIX (2026-10-09): this tested for `tiles.mbtiles`, a file NOTHING in
    /// the app has ever written — raster tiles live in the shared cache at
    /// Documents/Maps/tiles and the MBTiles design was never built. So this
    /// returned false for every region and `downloadedRegionIds()` returned an
    /// empty array unconditionally. Its one caller,
    /// `OfflineRouter.roadGraphDB(forCoordinate:)`, uses it as the fallback
    /// candidate list for when the manifest is unavailable — i.e. exactly the
    /// cold-launch-in-airplane-mode case — so offline routing had no fallback
    /// at all. Now it answers the question the callers actually ask: is there
    /// anything installed for this region?
    func isRegionDownloaded(_ regionId: String) -> Bool {
        let dir = regionDirectory(for: regionId)
        for name in ["road_graph.db", "poi.db", "metadata.json"] {
            if fileManager.fileExists(atPath: dir.appendingPathComponent(name).path) {
                return true
            }
        }
        return false
    }

    /// List all region IDs with at least one installed offline asset.
    func downloadedRegionIds() -> [String] {
        let regionsDir = mapsDirectory.appendingPathComponent("regions", isDirectory: true)
        guard let contents = try? fileManager.contentsOfDirectory(atPath: regionsDir.path) else {
            return []
        }
        return contents.filter { isRegionDownloaded($0) }
    }

    // MARK: - Metadata

    /// Get metadata for a downloaded region
    func loadMetadata(for regionId: String) -> DownloadedRegionMetadata? {
        let metadataURL = regionDirectory(for: regionId).appendingPathComponent("metadata.json")
        guard let data = try? Data(contentsOf: metadataURL) else {
            OshiLog.maps.info("[MapStorage] No metadata found for region \(regionId)")
            return nil
        }
        do {
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601
            return try decoder.decode(DownloadedRegionMetadata.self, from: data)
        } catch {
            OshiLog.maps.info("[MapStorage] Failed to decode metadata for \(regionId): \(error)")
            return nil
        }
    }

    /// Save metadata after successful download
    func saveMetadata(_ metadata: DownloadedRegionMetadata) {
        let dir = regionDirectory(for: metadata.regionId)

        // Ensure region directory exists
        if !fileManager.fileExists(atPath: dir.path) {
            do {
                try fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
            } catch {
                OshiLog.maps.info("[MapStorage] Failed to create region directory: \(error)")
                return
            }
        }

        let metadataURL = dir.appendingPathComponent("metadata.json")
        do {
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .iso8601
            encoder.outputFormatting = .prettyPrinted
            let data = try encoder.encode(metadata)
            try data.write(to: metadataURL, options: .atomic)
            OshiLog.maps.info("[MapStorage] Saved metadata for \(metadata.regionId)")
        } catch {
            OshiLog.maps.info("[MapStorage] Failed to save metadata for \(metadata.regionId): \(error)")
        }
    }

    // MARK: - File Paths

    /// Path for the MBTiles file within a region
    func tilesPath(for regionId: String) -> URL {
        regionDirectory(for: regionId).appendingPathComponent("tiles.mbtiles")
    }

    /// Path for the routing data file within a region
    func routingPath(for regionId: String) -> URL {
        regionDirectory(for: regionId).appendingPathComponent("routing.osrm")
    }

    /// Path for the POI database within a region
    func poiPath(for regionId: String) -> URL {
        regionDirectory(for: regionId).appendingPathComponent("poi.db")
    }

    /// Path for the offline road graph database within a region.
    /// Schema:
    ///   road_nodes(id INTEGER PRIMARY KEY, lat REAL, lon REAL)
    ///   road_edges(from_id INTEGER, to_id INTEGER, weight_car REAL,
    ///              weight_bike REAL, weight_foot REAL, polyline TEXT,
    ///              highway TEXT, oneway INTEGER, PRIMARY KEY(from_id, to_id))
    /// Used by OfflineRouter for true offline turn-by-turn navigation.
    func roadGraphPath(for regionId: String) -> URL {
        regionDirectory(for: regionId).appendingPathComponent("road_graph.db")
    }

    /// Whether the per-region road graph DB exists and has at least 1 node.
    func isRoadGraphAvailable(for regionId: String) -> Bool {
        let path = roadGraphPath(for: regionId).path
        return fileManager.fileExists(atPath: path)
    }

    // MARK: - Delete

    /// Delete a region and all its files
    @discardableResult
    func deleteRegion(_ regionId: String) -> Bool {
        let dir = regionDirectory(for: regionId)
        guard fileManager.fileExists(atPath: dir.path) else {
            OshiLog.maps.info("[MapStorage] Region \(regionId) not found, nothing to delete")
            return false
        }
        do {
            try fileManager.removeItem(at: dir)
            OshiLog.maps.info("[MapStorage] Deleted region \(regionId)")
            return true
        } catch {
            OshiLog.maps.info("[MapStorage] Failed to delete region \(regionId): \(error)")
            return false
        }
    }

    // MARK: - Storage Info

    /// Total size of all downloaded regions in bytes
    func totalDownloadedSize() -> Int64 {
        let regionsDir = mapsDirectory.appendingPathComponent("regions", isDirectory: true)
        return directorySize(at: regionsDir)
    }

    /// Device free space in bytes
    func deviceFreeSpace() -> Int64 {
        do {
            let values = try mapsDirectory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            return values.volumeAvailableCapacityForImportantUsage ?? 0
        } catch {
            OshiLog.maps.info("[MapStorage] Failed to get free space: \(error)")
            return 0
        }
    }

    // MARK: - File Operations

    /// Move a downloaded temp file to the correct location
    @discardableResult
    func moveDownloadedFile(from tempURL: URL, to destinationURL: URL) -> Bool {
        // Ensure parent directory exists
        let parentDir = destinationURL.deletingLastPathComponent()
        if !fileManager.fileExists(atPath: parentDir.path) {
            do {
                try fileManager.createDirectory(at: parentDir, withIntermediateDirectories: true)
            } catch {
                OshiLog.maps.info("[MapStorage] Failed to create parent directory: \(error)")
                return false
            }
        }

        // Remove existing file if present
        if fileManager.fileExists(atPath: destinationURL.path) {
            do {
                try fileManager.removeItem(at: destinationURL)
            } catch {
                OshiLog.maps.info("[MapStorage] Failed to remove existing file at destination: \(error)")
                return false
            }
        }

        do {
            try fileManager.moveItem(at: tempURL, to: destinationURL)
            excludeFromBackup(destinationURL)
            OshiLog.maps.info("[MapStorage] Moved file to \(destinationURL.lastPathComponent)")
            return true
        } catch {
            OshiLog.maps.info("[MapStorage] Failed to move file: \(error)")
            return false
        }
    }

    // MARK: - Private Helpers

    private func excludeFromBackup(_ url: URL) {
        var mutableURL = url
        do {
            var resourceValues = URLResourceValues()
            resourceValues.isExcludedFromBackup = true
            try mutableURL.setResourceValues(resourceValues)
        } catch {
            OshiLog.maps.info("[MapStorage] Failed to exclude from backup: \(url.path) - \(error)")
        }
    }

    private func directorySize(at url: URL) -> Int64 {
        guard let enumerator = fileManager.enumerator(
            at: url,
            includingPropertiesForKeys: [.fileSizeKey],
            options: [.skipsHiddenFiles]
        ) else {
            return 0
        }

        var totalSize: Int64 = 0
        for case let fileURL as URL in enumerator {
            if let resourceValues = try? fileURL.resourceValues(forKeys: [.fileSizeKey]),
               let fileSize = resourceValues.fileSize {
                totalSize += Int64(fileSize)
            }
        }
        return totalSize
    }
}
