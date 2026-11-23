//
//  OfflineFirstTileOverlay.swift
//  OSHI
//
//  Offline-first MKTileOverlay that:
//   1. Reads the local PNG cache at Documents/Maps/tiles/{z}/{x}/{y}.png FIRST.
//   2. Falls through to https://oshi-messenger.com/tiles/{z}/{x}/{y}.png on miss
//      (only if connectivity is available AND offline-only mode is OFF).
//   3. Caches every successful network response back to the same local path
//      so manually-zoomed tiles populate the cache for next time.
//
//  Offline-only mode is read from
//      UserDefaults.standard.bool(forKey: "oshi.map.offlineOnly")
//  and is owned by another agent's settings UI.
//

import Foundation
import MapKit
import UIKit
import CryptoKit

final class OfflineFirstTileOverlay: MKTileOverlay {

    // MARK: - Constants

    /// UserDefaults key for the "Offline only" preference (owned by settings UI).
    static let offlineOnlyKey = "oshi.map.offlineOnly"

    /// OSM tile template used as the network fallback.
    private static let onlineTemplate = "https://oshi-messenger.com/tiles/{z}/{x}/{y}.png"

    /// Sentinel scheme returned from `url(forTilePath:)` when offline-only is on
    /// AND the requested tile is not cached. MapKit's loader rejects unknown
    /// schemes, producing a "missing tile" placeholder rather than hitting the
    /// network.
    private static let bogusScheme = "bogus"

    // MARK: - Disk Layout

    /// Flat tile cache: Documents/Maps/tiles/{z}/{x}/{y}.png. Shared across all
    /// downloaded regions (OfflineMapManager writes here too).
    private let cacheDirectory: URL
    private let fileManager = FileManager.default

    /// Shared URLSession with OSM-required User-Agent.
    private let session: URLSession

    // MARK: - In-memory cache + off-thread I/O (ITEM 6 perf fix, 2026-06-25)

    /// In-memory tile cache keyed by "{z}/{x}/{y}". Avoids re-reading and
    /// re-decoding the same PNG from disk on every pan/zoom — MapKit re-requests
    /// the same tiles constantly while the user moves around a region, and the
    /// previous synchronous `Data(contentsOf:)` re-hit the disk each time.
    ///
    /// Stores the FINAL bytes MapKit should render for that exact tile path
    /// (already cropped/upscaled for overzoom), so a hit returns instantly with
    /// zero disk I/O and zero decode.
    private let memoryCache: NSCache<NSString, NSData> = {
        let c = NSCache<NSString, NSData>()
        // ~256×256 RGBA tiles are ~256 KB decoded but PNG bytes are far smaller;
        // cap by count so memory stays bounded across deep pans. 512 tiles is
        // several screenfuls at multiple zoom levels.
        c.countLimit = 512
        return c
    }()

    /// Concurrent queue for the blocking disk reads. MapKit drives
    /// `loadTile(at:result:)` on its own serial internal queue, so doing a
    /// synchronous `Data(contentsOf:)` there serialized every sibling tile
    /// request behind the slowest disk read. Hopping onto this concurrent queue
    /// lets independent tile reads proceed in parallel and frees MapKit's queue
    /// immediately. `.utility` keeps it off the main thread (new code is
    /// @MainActor by default in this project).
    private let ioQueue = DispatchQueue(label: "com.oshi.map.tileIO",
                                        qos: .utility,
                                        attributes: .concurrent)

    private static func cacheKey(z: Int, x: Int, y: Int) -> NSString {
        "\(z)/\(x)/\(y)" as NSString
    }

    /// In-memory cache lookup for the FINAL render bytes of a tile path.
    private func cachedInMemory(z: Int, x: Int, y: Int) -> Data? {
        memoryCache.object(forKey: Self.cacheKey(z: z, x: x, y: y)) as Data?
    }

    /// Store the FINAL render bytes for a tile path in the in-memory cache.
    private func storeInMemory(_ data: Data, z: Int, x: Int, y: Int) {
        memoryCache.setObject(data as NSData, forKey: Self.cacheKey(z: z, x: x, y: y))
    }

    /// Lazily-rendered placeholder tile (gray with "Offline" caption) returned
    /// when both the cache and the network refuse to provide a tile.
    private lazy var placeholderTileData: Data = {
        let size = CGSize(width: 256, height: 256)
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { ctx in
            UIColor(white: 0.88, alpha: 1.0).setFill()
            ctx.fill(CGRect(origin: .zero, size: size))

            let text = "Offline" as NSString
            let attrs: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 16, weight: .medium),
                .foregroundColor: UIColor(white: 0.55, alpha: 1.0)
            ]
            let textSize = text.size(withAttributes: attrs)
            let textRect = CGRect(
                x: (size.width - textSize.width) / 2,
                y: (size.height - textSize.height) / 2,
                width: textSize.width,
                height: textSize.height
            )
            text.draw(in: textRect, withAttributes: attrs)
        }
        return image.pngData() ?? Data()
    }()

    /// 🔧 FIX (2026-06-02): a SECOND placeholder, no "Offline" text — just a
    /// subtle gray hatch — returned when the device IS online but a specific
    /// tile failed to fetch (server 404 for a missing tile, transient 5xx,
    /// timeout). The single `placeholderTileData` above was used for both
    /// cases, so users on a perfectly fine connection saw "Offline" labels
    /// scattered across the map every time they zoomed into an area whose
    /// tiles weren't cached and the server didn't have them. This one reads as
    /// "tile loading / unavailable" instead of "you have no internet".
    private lazy var unavailableTileData: Data = {
        let size = CGSize(width: 256, height: 256)
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { ctx in
            UIColor(white: 0.92, alpha: 1.0).setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            // Faint diagonal hatch so users perceive "tile not loaded" without
            // panicking about being offline.
            UIColor(white: 0.84, alpha: 1.0).setStroke()
            let cg = ctx.cgContext
            cg.setLineWidth(0.6)
            let step: CGFloat = 16
            var x: CGFloat = -size.height
            while x < size.width {
                cg.move(to: CGPoint(x: x, y: 0))
                cg.addLine(to: CGPoint(x: x + size.height, y: size.height))
                x += step
            }
            cg.strokePath()
        }
        return image.pngData() ?? Data()
    }()

    // MARK: - Init

    /// - Parameter regionsDir: Documents/Maps/regions (currently unused — the
    ///   shared cache lives at Documents/Maps/tiles per OfflineMapManager's
    ///   on-disk layout). Accepted for forward compatibility with per-region
    ///   tile directories.
    init(regionsDir: URL? = nil) {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        self.cacheDirectory = docs.appendingPathComponent("Maps/tiles", isDirectory: true)

        let config = URLSessionConfiguration.default
        config.httpAdditionalHeaders = [
            "User-Agent": "OSHI-Messenger/1.0 (iOS; https://oshi-messenger.com)",
            "Referer": "https://oshi-messenger.com/",
        ]
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 30
        self.session = URLSession(configuration: config)

        super.init(urlTemplate: OfflineFirstTileOverlay.onlineTemplate)
        self.canReplaceMapContent = true
        self.minimumZ = 1
        // OSM tiles only exist to z=19. We advertise a deeper maximumZ so MapKit
        // keeps requesting tiles when the user zooms past z=19 instead of giving
        // up and leaving the (content-replaced) base map blank — which showed
        // only the floating MapKit labels and no map. loadTile() serves those
        // z>19 requests by cropping+upscaling the z=19 parent tile.
        self.maximumZ = 22
        self.tileSize = CGSize(width: 256, height: 256)

        // Ensure cache root exists (cheap, idempotent).
        if !fileManager.fileExists(atPath: cacheDirectory.path) {
            try? fileManager.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
        }

        // __POISONED_TILE_PURGE_2026_11_16__ the launch purge deleted blocked
        // tiles from disk: drop anything this overlay derived in memory.
        purgeObserver = NotificationCenter.default.addObserver(
            forName: PoisonedTile.purgedNotification, object: nil, queue: nil
        ) { [weak self] _ in
            self?.memoryCache.removeAllObjects()
        }
    }

    private var purgeObserver: NSObjectProtocol?

    deinit {
        if let purgeObserver { NotificationCenter.default.removeObserver(purgeObserver) }
    }

    // MARK: - Helpers

    /// File path for a cached tile: Documents/Maps/tiles/{z}/{x}/{y}.png
    private func cachedTileURL(z: Int, x: Int, y: Int) -> URL {
        return cacheDirectory
            .appendingPathComponent("\(z)")
            .appendingPathComponent("\(x)")
            .appendingPathComponent("\(y).png")
    }

    /// Read the offline-only preference (owned by settings UI agent).
    private var offlineOnlyEnabled: Bool {
        UserDefaults.standard.bool(forKey: OfflineFirstTileOverlay.offlineOnlyKey)
    }

    /// Atomically write tile data into the cache directory (creates intermediate
    /// dirs if missing). Non-PNG bodies are dropped — see `isPNG`.
    private func writeTileToCache(_ data: Data, z: Int, x: Int, y: Int) {
        guard Self.isUsableTile(data) else { return }
        let dest = cachedTileURL(z: z, x: x, y: y)
        let dir = dest.deletingLastPathComponent()
        if !fileManager.fileExists(atPath: dir.path) {
            try? fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        try? data.write(to: dest, options: .atomic)
    }

    /// 🔧 FIX (2026-10-09): the cache was trusted blindly — any file at
    /// `{z}/{x}/{y}.png` was handed to MapKit as a tile. The tile proxy answers
    /// 200 with an HTML body when it rate-limits, and a 0-byte file survives an
    /// interrupted write, so the cache could hold entries that render as
    /// nothing. Because both the overlay and the bulk downloader treat "file
    /// exists" as "tile cached", such an entry was never retried: a permanent
    /// blank square in the offline map. An 8-byte signature check is enough.
    static func isPNG(_ data: Data) -> Bool {
        guard data.count > 8 else { return false }
        return data.prefix(8).elementsEqual([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
    }

    /// __POISONED_TILE_PURGE_2026_11_16__ A PNG that is a real map tile: the
    /// PNG signature AND not OpenStreetMap's "Access blocked" picture, which
    /// the proxy served with HTTP 200 from 13/11/2026 (see `PoisonedTile`).
    /// Every read and write of the tile cache goes through this, so the
    /// blocked picture can neither be saved again nor be handed to MapKit
    /// (nor, therefore, enter `memoryCache`) if the launch purge has not
    /// reached it yet.
    static func isUsableTile(_ data: Data) -> Bool {
        isPNG(data) && !PoisonedTile.matches(data)
    }

    /// Read a cached tile, treating a corrupt/empty entry as a miss (and
    /// deleting it so the next pass re-fetches it).
    private func readCachedTile(z: Int, x: Int, y: Int) -> Data? {
        let url = cachedTileURL(z: z, x: x, y: y)
        guard let data = try? Data(contentsOf: url) else { return nil }
        if Self.isUsableTile(data) { return data }
        try? fileManager.removeItem(at: url)
        return nil
    }

    /// Deepest cached ANCESTOR of a tile, within `maxLevels` levels, cropped
    /// and upscaled to stand in for the requested tile.
    ///
    /// 🔧 FIX (2026-10-09): this is the fix for the headline offline bug. A
    /// region download caches zooms 6-13 only, and `fetchTileOrParent`'s
    /// climb-to-the-parent fallback ran *only after a failed network fetch* —
    /// so with no connectivity (the entire point of an offline map) every
    /// request above z13 returned the grey placeholder. Zooming in to street
    /// level on a downloaded region showed a blank grey grid, which is what
    /// "la map offline a des bugs" looks like in practice. Now any uncached
    /// tile is satisfied from the deepest cached ancestor, exactly as the
    /// online path already did.
    /// The nearest ancestor of this tile that is ALREADY IN MEMORY, cropped to
    /// the area this tile covers and upscaled — or nil if no ancestor is
    /// resident.
    ///
    /// The in-memory twin of [cachedAncestorTile]. That one reads the disk and so
    /// must be called off MapKit's loader queue; this one touches only the
    /// `NSCache` and can therefore run inside `loadTile` and return on the same
    /// call. That is the whole point: a synchronous answer is the difference
    /// between a soft tile and a black rectangle.
    private func inMemoryAncestorTile(z: Int, x: Int, y: Int, maxLevels: Int = 4) -> Data? {
        guard z > 0 else { return nil }
        for level in 1...maxLevels {
            let ancestorZ = z - level
            guard ancestorZ >= minimumZ else { return nil }
            let parentX = x >> level
            let parentY = y >> level
            guard let parentData = cachedInMemory(z: ancestorZ, x: parentX, y: parentY) else { continue }
            // Same crop maths as the overzoom path: which sub-square of the
            // ancestor this tile occupies.
            let offsetX = x - (parentX << level)
            let offsetY = y - (parentY << level)
            if let cropped = overzoomTile(from: parentData, diff: level, offsetX: offsetX, offsetY: offsetY) {
                return cropped
            }
        }
        return nil
    }

    private func cachedAncestorTile(z: Int, x: Int, y: Int, maxLevels: Int = 6) -> Data? {
        var level = 1
        while level <= maxLevels && z - level >= 1 {
            let pz = z - level
            let px = x >> level
            let py = y >> level
            if let parent = readCachedTile(z: pz, x: px, y: py) {
                let diff = level
                let offsetX = x - (px << diff)
                let offsetY = y - (py << diff)
                return overzoomTile(from: parent, diff: diff, offsetX: offsetX, offsetY: offsetY)
            }
            level += 1
        }
        return nil
    }

    /// 🔧 FIX (2026-06-02): When a specific tile fetch fails (server doesn't
    /// have that exact z/x/y, transient 5xx, etc.), recurse to the PARENT tile
    /// at z-1 and crop+upscale the matching sub-region — so the user always
    /// sees MAP content when online, not an "Offline" / blank placeholder.
    /// Capped at 4 parent levels so the worst case is bounded.
    /// `originalZ/X/Y` is the tile MapKit actually asked for; `z/x/y` is the
    /// current attempt (`originalZ` on first call, then climbs up).
    private func fetchTileOrParent(z: Int, x: Int, y: Int,
                                   depth: Int = 0,
                                   originalZ: Int, originalX: Int, originalY: Int,
                                   result: @escaping (Data) -> Void) {
        // Disk read on the concurrent I/O queue so it doesn't block MapKit's
        // serial loader queue (and sibling tiles can read in parallel).
        ioQueue.async { [weak self] in
            guard let self = self else { return }
            if let data = self.readCachedTile(z: z, x: x, y: y) {
                let final = self.cropToOriginalIfNeeded(parentData: data, parentZ: z, parentX: x, parentY: y,
                                                        originalZ: originalZ, originalX: originalX, originalY: originalY)
                // Remember the FINAL bytes for the path MapKit actually asked for
                // so the next pan over this tile is a pure in-memory hit.
                self.storeInMemory(final, z: originalZ, x: originalX, y: originalY)
                result(final)
                return
            }
            guard let url = URL(string: "https://oshi-messenger.com/tiles/\(z)/\(x)/\(y).png") else {
                result(self.unavailableTileData); return
            }
            self.session.dataTask(with: url) { [weak self] data, response, _ in
                guard let self = self else { return }
                if let data = data, Self.isUsableTile(data),
                   (response as? HTTPURLResponse)?.statusCode == 200 {
                    self.writeTileToCache(data, z: z, x: x, y: y)
                    let final = self.cropToOriginalIfNeeded(parentData: data, parentZ: z, parentX: x, parentY: y,
                                                            originalZ: originalZ, originalX: originalX, originalY: originalY)
                    self.storeInMemory(final, z: originalZ, x: originalX, y: originalY)
                    result(final)
                    return
                }
                // Fetch failed. A cached ancestor beats another round-trip.
                if let fromCache = self.cachedAncestorTile(z: originalZ, x: originalX, y: originalY) {
                    self.storeInMemory(fromCache, z: originalZ, x: originalX, y: originalY)
                    result(fromCache)
                    return
                }
                // Try the parent tile over the network if we're not too deep.
                if z > 1 && depth < 4 {
                    self.fetchTileOrParent(z: z - 1, x: x >> 1, y: y >> 1,
                                           depth: depth + 1,
                                           originalZ: originalZ, originalX: originalX, originalY: originalY,
                                           result: result)
                } else {
                    result(self.unavailableTileData)
                }
            }.resume()
        }
    }

    /// If `parentZ` equals the original requested z, return data as-is. Otherwise
    /// crop+upscale the sub-region that corresponds to the original tile.
    private func cropToOriginalIfNeeded(parentData: Data, parentZ: Int, parentX: Int, parentY: Int,
                                        originalZ: Int, originalX: Int, originalY: Int) -> Data {
        if parentZ == originalZ && parentX == originalX && parentY == originalY {
            return parentData
        }
        let diff = originalZ - parentZ
        let offsetX = originalX - (parentX << diff)
        let offsetY = originalY - (parentY << diff)
        return overzoomTile(from: parentData, diff: diff, offsetX: offsetX, offsetY: offsetY) ?? parentData
    }

    /// Crop the sub-region of a z=19 parent PNG that corresponds to an overzoom
    /// child tile and upscale it to a full 256×256 tile. `diff` is (childZ - 19),
    /// `offsetX/offsetY` are the child's position within the parent (0 ..< 2^diff).
    /// Returns nil if decoding fails.
    private func overzoomTile(from parentData: Data, diff: Int, offsetX: Int, offsetY: Int) -> Data? {
        guard diff > 0,
              let parent = UIImage(data: parentData),
              let cg = parent.cgImage else { return parentData }

        let subPerSide = 1 << diff                       // tiles per side within parent
        let pxW = CGFloat(cg.width) / CGFloat(subPerSide)
        let pxH = CGFloat(cg.height) / CGFloat(subPerSide)
        let cropRect = CGRect(
            x: CGFloat(offsetX) * pxW,
            y: CGFloat(offsetY) * pxH,
            width: pxW,
            height: pxH
        ).integral

        guard let sub = cg.cropping(to: cropRect) else { return parentData }

        let outSize = CGSize(width: 256, height: 256)
        let renderer = UIGraphicsImageRenderer(size: outSize)
        let image = renderer.image { ctx in
            // Nearest-ish upscale is fine; MapKit also smooths. Draw the crop to
            // fill the full tile.
            UIImage(cgImage: sub).draw(in: CGRect(origin: .zero, size: outSize))
        }
        return image.pngData()
    }

    // MARK: - MKTileOverlay overrides

    /// Returns a `file://` URL when the local PNG exists. Otherwise returns the
    /// online template URL — unless offline-only is on, in which case returns a
    /// `bogus://` URL whose load will fail and trigger a placeholder.
    ///
    /// NOTE: MapKit calls `loadTile(at:result:)` which fully drives the I/O —
    /// `url(forTilePath:)` is consulted only when `loadTile` defers to `super`.
    /// Both paths are kept consistent: cache-first, then network (or bogus).
    override func url(forTilePath path: MKTileOverlayPath) -> URL {
        let cached = cachedTileURL(z: path.z, x: path.x, y: path.y)
        if fileManager.fileExists(atPath: cached.path) {
            return cached
        }
        if offlineOnlyEnabled {
            // Forces MKTileOverlayRenderer to render nothing — the map shows
            // the placeholder "missing tile" pattern. This is preferable to
            // loading a known-bad URL.
            return URL(string: "\(OfflineFirstTileOverlay.bogusScheme)://offline-only/\(path.z)/\(path.x)/\(path.y)")!
        }
        let urlString = "https://oshi-messenger.com/tiles/\(path.z)/\(path.x)/\(path.y).png"
        return URL(string: urlString) ?? cached
    }

    /// Cache-first tile loader.
    ///
    /// 1. Local PNG hit → return immediately.
    /// 2. Miss + offline-only enabled → return placeholder (no network).
    /// 3. Miss + online → fetch via OSM, cache the response, return data.
    /// 4. Network failure → return placeholder.
    /// 5. Overzoom (z > 19) → resolve to z=19 parent (cache-first, then fetch).
    override func loadTile(at path: MKTileOverlayPath, result: @escaping (Data?, Error?) -> Void) {
        // 0. In-memory cache hit — return instantly with no disk I/O or decode.
        //    This is the hot path while the user pans/zooms within a region:
        //    MapKit re-requests the same tile paths repeatedly. Keyed by the
        //    EXACT requested z/x/y (final render bytes), including overzoom
        //    children, so a hit is render-ready.
        if let memData = cachedInMemory(z: path.z, x: path.x, y: path.y) {
            result(memData, nil)
            return
        }

        // __ZOOM_WENT_BLACK_BETWEEN_TILES_2026_11_05__ Draw SOMETHING on this
        // pass, synchronously.
        //
        // Reported from a device: black rectangles while zooming in and out,
        // "like if it was loading". It was loading. Every fallback below this
        // line is correct and every one of them is asynchronous — a disk read on
        // `ioQueue`, or a network fetch with a 15 s timeout. Until one of them
        // calls `result`, MapKit has nothing for that rect, and because
        // `canReplaceMapContent` is true there is no Apple base map underneath to
        // show through. Nothing means black.
        //
        // A zoom is the worst case by construction: changing z invalidates every
        // visible tile path at once, so the whole screen is waiting on I/O
        // simultaneously.
        //
        // The parent tile for the rect being drawn is almost always ALREADY in
        // memory — it is what the user was looking at a moment ago. Cropping and
        // upscaling it costs a CGImage draw and no I/O, so it can be returned on
        // this call. The result is soft for one frame and then sharpens when the
        // real tile lands, which is what every map does and reads as fast rather
        // than broken.
        //
        // Four levels up: 16x magnification is as blurry as is worth showing, and
        // the loop is bounded so a deep miss cannot walk the whole pyramid.
        if let ancestor = inMemoryAncestorTile(z: path.z, x: path.x, y: path.y) {
            // Deliberately NOT stored in the memory cache: these bytes are a
            // stand-in, and caching them under the child's key would make the
            // blurry version a permanent hit that the sharp tile never replaces.
            result(ancestor, nil)
            // No `return` — the real tile is still fetched below and MapKit
            // redraws when it arrives.
        }

        // Overzoom: derive the requested z>19 tile from its z=19 parent by
        // cropping the parent to the sub-region this child covers, then upscaling
        // to 256×256. Returning the whole parent (the previous behaviour) drew the
        // entire parent into each small child rect — geographically wrong/repeated.
        if path.z > 19 {
            let diff = path.z - 19
            let parentX = path.x >> diff
            let parentY = path.y >> diff
            let offsetX = path.x - (parentX << diff)   // 0 ..< 2^diff
            let offsetY = path.y - (parentY << diff)

            // Emits the cropped/upscaled child for THIS path, caching the final
            // bytes in memory so the next pass over the same overzoom tile is a
            // pure memory hit.
            let emit: (Data?) -> Void = { [weak self] parentData in
                guard let self = self else { return }
                if let parentData = parentData,
                   let cropped = self.overzoomTile(from: parentData, diff: diff, offsetX: offsetX, offsetY: offsetY) {
                    self.storeInMemory(cropped, z: path.z, x: path.x, y: path.y)
                    result(cropped, nil)
                } else {
                    result(self.placeholderTileData, nil)
                }
            }

            // Disk read off MapKit's loader queue so it doesn't serialize siblings.
            ioQueue.async { [weak self] in
                guard let self = self else { return }
                if let data = self.readCachedTile(z: 19, x: parentX, y: parentY) {
                    emit(data)
                    return
                }
                // No z19 parent cached. Offline, climb further: a downloaded
                // region only ever holds z6-13, so z19 is never on disk and
                // this branch used to ALWAYS end in the placeholder.
                let offlineFallback: () -> Void = {
                    if let synthesised = self.cachedAncestorTile(z: path.z, x: path.x, y: path.y) {
                        self.storeInMemory(synthesised, z: path.z, x: path.x, y: path.y)
                        result(synthesised, nil)
                    } else {
                        result(self.placeholderTileData, nil)
                    }
                }
                if self.offlineOnlyEnabled {
                    offlineFallback()
                    return
                }
                let isOnline = NetworkReachabilityManager.shared.isConnected
                guard isOnline else { offlineFallback(); return }
                let parentURL = URL(string: "https://oshi-messenger.com/tiles/19/\(parentX)/\(parentY).png")!
                self.session.dataTask(with: parentURL) { [weak self] data, response, _ in
                    guard let self = self,
                          let data = data,
                          (response as? HTTPURLResponse)?.statusCode == 200 else {
                        // 🔧 We're ONLINE (checked above) — the fetch itself failed
                        // (404 for a missing tile, 5xx, timeout). Prefer a cached
                        // ancestor; only then the no-text "unavailable" placeholder
                        // so users don't see "Offline" labels on a normal connection.
                        guard let self = self else { result(Data(), nil); return }
                        if let synthesised = self.cachedAncestorTile(z: path.z, x: path.x, y: path.y) {
                            self.storeInMemory(synthesised, z: path.z, x: path.x, y: path.y)
                            result(synthesised, nil)
                        } else {
                            result(self.unavailableTileData, nil)
                        }
                        return
                    }
                    guard Self.isUsableTile(data) else {
                        result(self.unavailableTileData, nil); return
                    }
                    self.writeTileToCache(data, z: 19, x: parentX, y: parentY)
                    emit(data)
                }.resume()
            }
            return
        }

        // 1. Local cache hit — read off MapKit's serial queue. Populate the
        //    in-memory cache so subsequent requests skip the disk entirely.
        ioQueue.async { [weak self] in
            guard let self = self else { return }
            if let data = self.readCachedTile(z: path.z, x: path.x, y: path.y) {
                self.storeInMemory(data, z: path.z, x: path.x, y: path.y)
                result(data, nil)
                return
            }

            // 2. Offline-only: never touch the network — but DO synthesise the
            //    tile from the deepest cached ancestor before giving up. A
            //    region download caches z6-13; without this every request
            //    deeper than 13 rendered the grey "Offline" placeholder on a
            //    region the user had fully downloaded.
            if self.offlineOnlyEnabled {
                if let synthesised = self.cachedAncestorTile(z: path.z, x: path.x, y: path.y) {
                    self.storeInMemory(synthesised, z: path.z, x: path.x, y: path.y)
                    result(synthesised, nil)
                } else {
                    result(self.placeholderTileData, nil)
                }
                return
            }

            // 3. Connectivity gate. Same ancestor fallback: offline is exactly
            //    when the offline map has to carry the whole experience.
            let isOnline = NetworkReachabilityManager.shared.isConnected
            guard isOnline else {
                if let synthesised = self.cachedAncestorTile(z: path.z, x: path.x, y: path.y) {
                    self.storeInMemory(synthesised, z: path.z, x: path.x, y: path.y)
                    result(synthesised, nil)
                } else {
                    result(self.placeholderTileData, nil)
                }
                return
            }

            // 4. Fetch via the helper — handles cache + network + automatic parent
            //    fallback (crop+upscale) so the user ALWAYS sees map content when
            //    online, even for tiles the server doesn't have at that z/x/y. The
            //    helper also stores the final bytes in the in-memory cache.
            self.fetchTileOrParent(z: path.z, x: path.x, y: path.y,
                                   originalZ: path.z, originalX: path.x, originalY: path.y) { data in
                result(data, nil)
            }
        }
    }
}

// MARK: - __POISONED_TILE_PURGE_2026_11_16__ OSM "Access blocked" tiles

/// From 13/11/2026 OpenStreetMap blocked the tile proxy
/// (`oshi-messenger.com/tiles`) and answered every request with its
/// "Access blocked" picture — with HTTP 200, so the app saved it as a real
/// tile. The server now renders its own tiles, but phones still hold those
/// pictures in `Documents/Maps/tiles/{z}/{x}/{y}.png` and read the cache
/// BEFORE the network, so the map stayed broken where it had been browsed or
/// downloaded since 13/11. `deleteRegion` does not clear the shared cache, so
/// deleting and re-downloading a region did not help either.
///
/// The picture is identical for every tile: exactly 6 725 bytes, one SHA-256.
/// The size is checked first so only same-size files are ever hashed.
enum PoisonedTile {
    static let size = 6725
    static let sha256Hex = "109722facb0459991ce2c9983b3ae40232e84be7273aaa454071b50049e3d88b"

    /// UserDefaults key holding the app version the purge last ran for.
    static let purgedVersionKey = "oshi.map.poisonedTilePurge.version"

    /// Posted on the main queue when a purge deleted something, so live
    /// overlays drop their in-memory tiles.
    static let purgedNotification = Notification.Name("oshi.map.poisonedTilesPurged")

    /// Exact match: same size AND same SHA-256. Any other 6 725-byte file is
    /// a real tile and is kept.
    static func matches(_ data: Data, size: Int = PoisonedTile.size,
                        sha256Hex: String = PoisonedTile.sha256Hex) -> Bool {
        guard data.count == size else { return false }
        let hex = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        return hex == sha256Hex
    }

    /// Same test on a file, reading it only when its size already matches.
    static func fileMatches(at url: URL, size: Int = PoisonedTile.size,
                            sha256Hex: String = PoisonedTile.sha256Hex) -> Bool {
        let attrs = try? FileManager.default.attributesOfItem(atPath: url.path)
        guard (attrs?[.size] as? NSNumber)?.intValue == size,
              let data = try? Data(contentsOf: url) else { return false }
        return matches(data, size: size, sha256Hex: sha256Hex)
    }

    /// Deletes every file under `root` (recursively: the shared tile cache and
    /// any per-region folder) that is the blocked picture. Returns how many
    /// were deleted. Synchronous — call it off the main thread.
    @discardableResult
    static func purge(root: URL, size: Int = PoisonedTile.size,
                      sha256Hex: String = PoisonedTile.sha256Hex) -> Int {
        let fm = FileManager.default
        let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey]
        guard let walker = fm.enumerator(at: root, includingPropertiesForKeys: keys,
                                         options: [], errorHandler: { _, _ in true }) else { return 0 }
        var deleted = 0
        for case let url as URL in walker {
            guard let values = try? url.resourceValues(forKeys: Set(keys)),
                  values.isRegularFile == true, values.fileSize == size,
                  let data = try? Data(contentsOf: url),
                  matches(data, size: size, sha256Hex: sha256Hex) else { continue }
            if (try? fm.removeItem(at: url)) != nil { deleted += 1 }
        }
        return deleted
    }

    /// Launch hook: once per app version (marketing version + build), in the
    /// background, purge `Documents/Maps`. Running again on each new version
    /// is cheap (only 6 725-byte files are read) and catches anything an
    /// older build saved in between.
    static func purgeAtLaunchIfNeeded(defaults: UserDefaults = .standard) {
        let info = Bundle.main.infoDictionary
        let version = "\(info?["CFBundleShortVersionString"] as? String ?? "?")(\(info?["CFBundleVersion"] as? String ?? "?"))"
        guard defaults.string(forKey: purgedVersionKey) != version else { return }
        DispatchQueue.global(qos: .utility).async {
            guard let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
            let maps = docs.appendingPathComponent("Maps", isDirectory: true)
            let started = Date()
            let n = FileManager.default.fileExists(atPath: maps.path) ? purge(root: maps) : 0
            defaults.set(version, forKey: purgedVersionKey)
            OshiLog.maps.info("[Maps] Poisoned-tile purge \(version): deleted \(n) blocked tiles in \(Int(Date().timeIntervalSince(started) * 1000)) ms")
            if n > 0 {
                DispatchQueue.main.async {
                    NotificationCenter.default.post(name: purgedNotification, object: nil)
                }
            }
        }
    }
}
