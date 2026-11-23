import Foundation
import SQLite3
import CoreLocation

// MARK: - Street Result Model

struct StreetResult: Identifiable, Equatable {
    let id: Int64
    let name: String
    let city: String?
    let lat: Double
    let lon: Double
    let highwayType: String?

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    /// Convert to MapPOI for unified result display.
    func toMapPOI() -> MapPOI {
        let addressParts = [name, city].compactMap { $0 }.joined(separator: ", ")
        return MapPOI(
            id: id + 1_000_000_000, // offset to avoid collision with POI ids
            name: name,
            category: "street",
            subcategory: highwayType,
            coordinate: coordinate,
            address: addressParts.isEmpty ? nil : addressParts,
            phone: nil,
            website: nil,
            openingHours: nil
        )
    }

    static func == (lhs: StreetResult, rhs: StreetResult) -> Bool {
        lhs.id == rhs.id && lhs.name == rhs.name
    }
}

// MARK: - Address Match Model (house-number aware)

/// One hit from `OfflineStreetDatabase.searchAddress(...)`. Either an exact
/// `house_number` row from the index (`.exact`) or a linear interpolation
/// between the two nearest known numbers on the street (`.interpolated`).
struct StreetAddressMatch: Identifiable, Equatable {
    enum Confidence: String { case exact, interpolated }

    let id: String              // composite: "<regionId>:<streetId>:<houseNumber>"
    let houseNumber: String
    let streetName: String
    let city: String?
    let lat: Double
    let lon: Double
    let regionId: String
    let confidence: Confidence

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    /// Render as a MapPOI so the existing search-result list can show it.
    /// Uses category "address" + a 📍 prefix in the name to mark it visually.
    func toMapPOI() -> MapPOI {
        let displayName = "📍 \(houseNumber) \(streetName)"
        let address: String? = city.map { "\(houseNumber) \(streetName), \($0)" }
            ?? "\(houseNumber) \(streetName)"
        // Stable Int64 id from the composite string id, with the high bit set
        // so it can't collide with POI ids or street ids (which use small
        // positive values, optionally + 1_000_000_000).
        let raw = UInt64(bitPattern: Int64(id.hashValue))
        let hashId = Int64(bitPattern: (raw & 0x3FFF_FFFF_FFFF_FFFF) | 0x4000_0000_0000_0000)
        return MapPOI(
            id: hashId,
            name: displayName,
            category: "address",
            subcategory: confidence.rawValue,
            coordinate: coordinate,
            address: address,
            phone: nil,
            website: nil,
            openingHours: nil
        )
    }

    static func == (lhs: StreetAddressMatch, rhs: StreetAddressMatch) -> Bool {
        lhs.id == rhs.id
    }
}

// MARK: - Offline Street Database

final class OfflineStreetDatabase: ObservableObject {

    static let shared = OfflineStreetDatabase()

    /// Per-region download progress in [0, 1]. Published so the regions UI can
    /// observe the auto-download triggered after tiles finalize, without the
    /// user having to re-tap "Download street index".
    @Published var downloadProgress: [String: Double] = [:]
    @Published var isDownloading: [String: Bool] = [:]
    @Published var downloadError: [String: String] = [:]

    private let lock = NSLock()
    private var openDBs: [String: OpaquePointer] = [:]
    /// Track active download tasks so callers can cancel and so we don't kick
    /// off duplicate downloads when both the user button and the auto-download
    /// path fire for the same region.
    private var activeTasks: [String: URLSessionDownloadTask] = [:]
    private let taskLock = NSLock()

    private let baseURL = "https://oshi-messenger.com/files/streets"

    private init() {}

    deinit {
        lock.lock()
        defer { lock.unlock() }
        for (_, db) in openDBs {
            sqlite3_close(db)
        }
        openDBs.removeAll()
    }

    // MARK: - Street Index Availability

    /// Check if the street index for a region has been downloaded.
    func isStreetIndexAvailable(regionId: String) -> Bool {
        let dbPath = streetDBPath(regionId: regionId)
        return FileManager.default.fileExists(atPath: dbPath)
    }

    /// Returns the size of the street index file in bytes, or 0 if not downloaded.
    func streetIndexSize(regionId: String) -> Int {
        let path = streetDBPath(regionId: regionId)
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: path),
              let size = attrs[.size] as? Int else { return 0 }
        return size
    }

    // MARK: - Download Street Index

    /// Downloads and decompresses the street index for a given region.
    /// - Parameters:
    ///   - region: The MapRegion to download streets for.
    ///   - progress: Progress callback (0.0 to 1.0). Kept for API back-compat;
    ///     callers are encouraged to observe the published `downloadProgress`
    ///     dictionary instead so the UI auto-updates without a re-tap.
    ///   - completion: Called on main thread with success/failure.
    ///
    /// The server today only ships country-level streets DBs for most
    /// countries (e.g. `fr-streets.db.gz`, no `fr-idf-streets.db.gz`). When
    /// the regional URL 404s, we automatically retry with the country-level
    /// fallback derived from the region id prefix (`fr-idf` → `fr`). Mirrors
    /// the existing fallback in `OfflineRoutingManager.downloadRouting`.
    func downloadStreetIndex(
        region: MapRegion,
        progress: @escaping (Double) -> Void = { _ in },
        completion: @escaping (Result<Void, Error>) -> Void
    ) {
        let regionId = region.id

        // Coalesce: if a download is already in flight for this region, don't
        // kick off a second one. The auto-download from finalizeRegionDownload
        // and a manual button tap can race; without this we'd open two
        // URLSession tasks writing the same file.
        taskLock.lock()
        if activeTasks[regionId] != nil {
            taskLock.unlock()
            return
        }
        taskLock.unlock()

        DispatchQueue.main.async {
            self.isDownloading[regionId] = true
            self.downloadProgress[regionId] = 0.0
            self.downloadError.removeValue(forKey: regionId)
        }

        attemptDownload(
            regionId: regionId,
            urlString: "\(baseURL)/\(regionId)-streets.db.gz",
            allowCountryFallback: true,
            progress: progress,
            completion: completion
        )
    }

    /// Internal: a single download attempt for one URL. Called twice in the
    /// fallback path — first with the region-specific URL, then (on 404) with
    /// the country-level URL derived from the region id.
    private func attemptDownload(
        regionId: String,
        urlString: String,
        allowCountryFallback: Bool,
        progress: @escaping (Double) -> Void,
        completion: @escaping (Result<Void, Error>) -> Void
    ) {
        guard let url = URL(string: urlString) else {
            finishDownload(regionId: regionId, result: .failure(StreetDBError.invalidURL), userCompletion: completion)
            return
        }

        let destDir = streetDBDirectory(regionId: regionId)
        try? FileManager.default.createDirectory(atPath: destDir, withIntermediateDirectories: true)

        let destPath = streetDBPath(regionId: regionId)
        let gzPath = destPath + ".gz"

        let session = URLSession.shared
        // __MAP_STALLED_AT_24_PERCENT_2026_10_28__ see OfflineDownloadResume.resumeIsPoisoned
        let didResumeBox = UnsafeMutablePointer<Bool>.allocate(capacity: 1)
        didResumeBox.initialize(to: false)
        let task = OfflineDownloadResume.makeTask(
            session: session,
            url: url,
            namespace: "OSHI_Streets_Resume",
            regionId: regionId,
            didResume: didResumeBox
        ) { [weak self] tempURL, response, error in
            let didResume = didResumeBox.pointee
            didResumeBox.deinitialize(count: 1)
            didResumeBox.deallocate()
            guard let self = self else { return }
            // Persist resume data on error so the next attempt picks up where this one
            // stopped — UNLESS this attempt was itself a resume that failed, in which case
            // the stored state is what broke it and keeping it loops for ever.
            if OfflineDownloadResume.resumeIsPoisoned(didResume: didResume), error != nil {
                OfflineDownloadResume.clear(namespace: "OSHI_Streets_Resume", regionId: regionId)
            } else if let nsError = error as NSError?,
               let resumeData = nsError.userInfo[NSURLSessionDownloadTaskResumeData] as? Data {
                OfflineDownloadResume.save(resumeData, namespace: "OSHI_Streets_Resume", regionId: regionId)
            }

            // Country-level fallback: regional file missing → retry with
            // {country}-streets.db.gz once.
            let httpStatus = (response as? HTTPURLResponse)?.statusCode ?? -1
            let is404 = httpStatus == 404
            if is404 && allowCountryFallback,
               let dash = regionId.firstIndex(of: "-"),
               dash != regionId.startIndex {
                self.taskLock.lock()
                self.activeTasks.removeValue(forKey: regionId)
                self.taskLock.unlock()
                let country = String(regionId[..<dash])
                let fallbackURL = "\(self.baseURL)/\(country)-streets.db.gz"
                OshiLog.maps.info("[OfflineStreetDatabase] \(regionId) returned 404 — retrying with country-level \(country)-streets.db.gz")
                self.attemptDownload(
                    regionId: regionId,
                    urlString: fallbackURL,
                    allowCountryFallback: false,
                    progress: progress,
                    completion: completion
                )
                return
            }

            if let error = error {
                self.finishDownload(regionId: regionId, result: .failure(error), userCompletion: completion)
                return
            }

            guard let tempURL = tempURL else {
                self.finishDownload(regionId: regionId, result: .failure(StreetDBError.downloadFailed), userCompletion: completion)
                return
            }

            // Non-2xx HTTP without a fallback path → surface as soft error.
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                self.finishDownload(
                    regionId: regionId,
                    result: .failure(StreetDBError.unavailableForCountry),
                    userCompletion: completion
                )
                return
            }

            do {
                if FileManager.default.fileExists(atPath: gzPath) {
                    try FileManager.default.removeItem(atPath: gzPath)
                }
                try FileManager.default.moveItem(at: tempURL, to: URL(fileURLWithPath: gzPath))

                let gzURL = URL(fileURLWithPath: gzPath)
                let destURL = URL(fileURLWithPath: destPath)
                if Self.isGzip(at: gzURL) {
                    // Streamed for the same reason the road graph is
                    // (`GzipStreamDecompressor`, added 2026-07-28): loading the
                    // archive into `Data` charges every byte to `phys_footprint`,
                    // the quantity iOS jetsam kills on. Street indexes are
                    // smaller than road graphs but not small — `fr-streets.db.gz`
                    // is 78 MB compressed — and this path also inherits the
                    // decompressor's `.part` staging and disk-space guards, so a
                    // half-written index can never be opened as a real one.
                    try GzipStreamDecompressor.decompress(from: gzURL, to: destURL)
                    try? FileManager.default.removeItem(at: gzURL)
                } else {
                    // Not gzip at all: a bare SQLite published under a `.gz`
                    // name. Preserved behaviour — but remove any previous copy
                    // first, because `moveItem` throws onto an existing file
                    // where the old `Data.write` silently replaced it.
                    if FileManager.default.fileExists(atPath: destPath) {
                        try FileManager.default.removeItem(at: destURL)
                    }
                    try FileManager.default.moveItem(at: gzURL, to: destURL)
                }

                self.finishDownload(regionId: regionId, result: .success(()), userCompletion: completion)
            } catch {
                self.finishDownload(regionId: regionId, result: .failure(error), userCompletion: completion)
            }
        }

        self.taskLock.lock()
        self.activeTasks[regionId] = task
        self.taskLock.unlock()

        let observation = task.progress.observe(\.fractionCompleted) { [weak self] prog, _ in
            DispatchQueue.main.async {
                self?.downloadProgress[regionId] = prog.fractionCompleted
                progress(prog.fractionCompleted)
            }
        }
        objc_setAssociatedObject(task, "progressObservation", observation, .OBJC_ASSOCIATION_RETAIN)

        task.resume()
    }

    /// Common terminal step for any download attempt — clears the active-task
    /// entry, updates published state, then invokes the user completion on
    /// the main queue. Centralised so success and every failure path agree
    /// on the cleanup.
    private func finishDownload(
        regionId: String,
        result: Result<Void, Error>,
        userCompletion: @escaping (Result<Void, Error>) -> Void
    ) {
        taskLock.lock()
        activeTasks.removeValue(forKey: regionId)
        taskLock.unlock()
        // Drop the resume marker on success — keep it on failure so the next
        // attempt continues from where it stopped (network drop, app suspend,
        // server 5xx) instead of restarting at 0%.
        if case .success = result {
            OfflineDownloadResume.clear(namespace: "OSHI_Streets_Resume", regionId: regionId)
        }
        DispatchQueue.main.async {
            self.isDownloading[regionId] = false
            switch result {
            case .success:
                self.downloadProgress[regionId] = 1.0
                self.downloadError.removeValue(forKey: regionId)
            case .failure(let err):
                self.downloadProgress.removeValue(forKey: regionId)
                self.downloadError[regionId] = err.localizedDescription
            }
            userCompletion(result)
        }
    }

    // MARK: - Auto-resume

    /// Resume any street-index downloads that were interrupted (OS suspended
    /// the app, network dropped, user forced quit). Walks the resume-state
    /// directory and re-triggers `downloadStreetIndex` for every region with
    /// saved progress — the `attemptDownload` helper will pick up the
    /// resumeData via `OfflineDownloadResume.load` and continue from the same
    /// byte offset rather than restarting at 0%.
    ///
    /// Called from `OfflineMapManager.fetchManifest`'s success handler so the
    /// manifest is loaded before we look up regions.
    func resumePendingDownloads(manifest: MapManifest?) {
        guard let manifest = manifest else { return }
        guard let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let resumeDirURL = docs.appendingPathComponent("OSHI_Streets_Resume", isDirectory: true)
        guard let contents = try? FileManager.default.contentsOfDirectory(atPath: resumeDirURL.path) else { return }
        for entry in contents where entry.hasSuffix(".resume") {
            let regionId = String(entry.dropLast(".resume".count))
            if isStreetIndexAvailable(regionId: regionId) {
                OfflineDownloadResume.clear(namespace: "OSHI_Streets_Resume", regionId: regionId)
                continue
            }
            guard let region = manifest.regions.first(where: { $0.id == regionId }) else { continue }
            OshiLog.maps.info("[OfflineStreetDatabase] auto-resuming \(regionId) on launch")
            downloadStreetIndex(region: region, progress: { _ in }, completion: { _ in })
        }
    }

    // MARK: - Search Streets

    /// Search street names using FTS5 in the downloaded street index.
    /// - Parameters:
    ///   - query: User search text.
    ///   - regionId: The region to search in.
    ///   - limit: Max results. Default 20.
    /// - Returns: Matching streets sorted by relevance.
    func searchStreets(query: String, regionId: String, limit: Int = 20) -> [StreetResult] {
        guard let db = openDB(regionId: regionId) else { return [] }

        let ftsQuery = sanitizeFTSQuery(query)
        guard !ftsQuery.isEmpty else { return [] }

        // Try FTS5 first
        let ftsSQL = """
            SELECT s.id, s.name, s.city, s.lat, s.lon, s.highway_type
            FROM streets s
            INNER JOIN streets_fts f ON s.id = f.rowid
            WHERE f.streets_fts MATCH ?1
            ORDER BY rank
            LIMIT ?2
            """

        var results = execStreetQuery(db: db, sql: ftsSQL, binder: { stmt in
            sqlite3_bind_text(stmt, 1, (ftsQuery as NSString).utf8String, -1, nil)
            sqlite3_bind_int(stmt, 2, Int32(limit))
        })

        // Fallback to LIKE if FTS5 returns nothing (e.g., FTS table doesn't exist)
        if results.isEmpty {
            let likeSQL = """
                SELECT id, name, city, lat, lon, highway_type
                FROM streets
                WHERE name LIKE ?1 COLLATE NOCASE
                ORDER BY name ASC
                LIMIT ?2
                """
            let likePattern = "%\(query)%"
            results = execStreetQuery(db: db, sql: likeSQL, binder: { stmt in
                sqlite3_bind_text(stmt, 1, (likePattern as NSString).utf8String, -1, nil)
                sqlite3_bind_int(stmt, 2, Int32(limit))
            })
        }

        return results
    }

    // MARK: - Address Search (with house numbers)

    /// Parse a leading house number from a query like "12 rue de Rivoli",
    /// "100A Main Street", "Bis 14 avenue Foch". Returns the matched
    /// number token (e.g. "12", "100A", "Bis 14") and the remaining street
    /// portion. Returns nil when no leading number is detected.
    ///
    /// Regex: `^\s*((?:bis|ter|quater)\s*)?\d+\s*[A-Za-z]?\b`
    /// — accepts an optional French prefix word, mandatory digits, optional
    ///   trailing letter ("A", "bis"-style suffixes already covered).
    static func parseHouseNumber(from rawQuery: String) -> (number: String, street: String)? {
        let q = rawQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return nil }
        let pattern = #"^\s*((?:bis|ter|quater)\s+)?(\d+)\s*([A-Za-z])?\b\s*"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return nil
        }
        let nsRange = NSRange(q.startIndex..<q.endIndex, in: q)
        guard let match = regex.firstMatch(in: q, options: [], range: nsRange),
              match.range.length > 0,
              let fullRange = Range(match.range, in: q) else {
            return nil
        }
        // Reconstruct a clean number token: "<prefix? ><digits><letter?>"
        var numberToken = ""
        if match.numberOfRanges >= 2,
           let prefRange = Range(match.range(at: 1), in: q),
           !prefRange.isEmpty {
            numberToken += q[prefRange].trimmingCharacters(in: .whitespaces) + " "
        }
        if match.numberOfRanges >= 3, let digitRange = Range(match.range(at: 2), in: q) {
            numberToken += String(q[digitRange])
        }
        if match.numberOfRanges >= 4,
           let letterRange = Range(match.range(at: 3), in: q),
           !letterRange.isEmpty {
            numberToken += String(q[letterRange]).uppercased()
        }
        let street = String(q[fullRange.upperBound...]).trimmingCharacters(in: .whitespaces)
        guard !street.isEmpty, !numberToken.isEmpty else { return nil }
        return (numberToken, street)
    }

    /// Numerical part of a house-number token (digits only). Used for
    /// "nearest known number" comparison during interpolation. "100A" → 100,
    /// "Bis 14" → 14.
    private static func numericPart(of token: String) -> Int? {
        let digits = token.compactMap { $0.isNumber ? $0 : nil }
        let s = String(digits)
        return Int(s)
    }

    /// Search the offline index for a free-text address. Parses an optional
    /// leading house number; if absent, this method returns no results
    /// (use `searchStreets` for plain street search).
    ///
    /// Algorithm:
    ///   1. Parse "<number> <street>" from the query.
    ///   2. Find candidate streets by FTS against the street part.
    ///   3. For each candidate, check `street_house_numbers` for an exact
    ///      house-number match → `.exact` hit at that lat/lon.
    ///   4. If not found AND the street has ≥2 known numbers, linearly
    ///      interpolate along the street polyline between the two nearest
    ///      neighbours (numerical comparison) → `.interpolated` hit.
    ///   5. Sort all hits by distance from the user when location is known.
    func searchAddress(
        query: String,
        userLat: Double? = nil,
        userLon: Double? = nil,
        regionId: String? = nil,
        limit: Int = 20
    ) -> [StreetAddressMatch] {
        guard let parsed = Self.parseHouseNumber(from: query) else { return [] }

        // Resolve which region(s) to search.
        let regionIds: [String]
        if let r = regionId {
            regionIds = [r]
        } else {
            regionIds = listAvailableRegionIds()
        }
        guard !regionIds.isEmpty else { return [] }

        let targetNumeric = Self.numericPart(of: parsed.number)

        var matches: [StreetAddressMatch] = []
        for rId in regionIds {
            guard let db = openDB(regionId: rId) else { continue }
            let streets = searchStreets(query: parsed.street, regionId: rId, limit: 10)
            for street in streets {
                // 1. Exact match
                if let exact = lookupExactHouseNumber(
                    db: db,
                    streetId: street.id,
                    number: parsed.number
                ) {
                    matches.append(StreetAddressMatch(
                        id: "\(rId):\(street.id):\(parsed.number)",
                        houseNumber: parsed.number,
                        streetName: street.name,
                        city: street.city,
                        lat: exact.lat,
                        lon: exact.lon,
                        regionId: rId,
                        confidence: .exact
                    ))
                    continue
                }
                // 2. Interpolation
                if let target = targetNumeric,
                   let interp = interpolateHouseNumber(
                       db: db,
                       streetId: street.id,
                       targetNumber: target
                   ) {
                    matches.append(StreetAddressMatch(
                        id: "\(rId):\(street.id):\(parsed.number)",
                        houseNumber: parsed.number,
                        streetName: street.name,
                        city: street.city,
                        lat: interp.lat,
                        lon: interp.lon,
                        regionId: rId,
                        confidence: .interpolated
                    ))
                }
            }
        }

        // Exact matches always before interpolated; within each group, sort
        // by haversine distance from the user when location is known.
        matches.sort { a, b in
            if a.confidence != b.confidence {
                return a.confidence == .exact
            }
            if let uLat = userLat, let uLon = userLon {
                return haversineMeters(uLat, uLon, a.lat, a.lon) <
                       haversineMeters(uLat, uLon, b.lat, b.lon)
            }
            return false
        }
        return Array(matches.prefix(limit))
    }

    /// Enumerate region ids that currently have a downloaded street DB on disk.
    private func listAvailableRegionIds() -> [String] {
        guard let docsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return []
        }
        let streetsRoot = docsURL.appendingPathComponent("Maps").appendingPathComponent("streets")
        guard let contents = try? FileManager.default.contentsOfDirectory(atPath: streetsRoot.path) else {
            return []
        }
        return contents.filter { isStreetIndexAvailable(regionId: $0) }
    }

    /// Look up an exact `house_number` row for a given street id.
    private func lookupExactHouseNumber(
        db: OpaquePointer,
        streetId: Int64,
        number: String
    ) -> (lat: Double, lon: Double)? {
        let sql = "SELECT lat, lon FROM street_house_numbers WHERE street_id = ?1 AND house_number = ?2 COLLATE NOCASE LIMIT 1"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_int64(stmt, 1, streetId)
        sqlite3_bind_text(stmt, 2, (number as NSString).utf8String, -1, nil)
        if sqlite3_step(stmt) == SQLITE_ROW {
            return (sqlite3_column_double(stmt, 0), sqlite3_column_double(stmt, 1))
        }
        return nil
    }

    /// Linear interpolation between the two nearest known numerical
    /// `house_number`s on a street. Returns nil when there are fewer than two
    /// known numbers (we don't extrapolate from a single anchor — too noisy).
    ///
    /// Algorithm:
    ///   1. Pull every (number_i, lat_i, lon_i) for the street where the
    ///      numerical part of `house_number` is parseable.
    ///   2. Pick `lo` = greatest known number ≤ target, `hi` = smallest > target.
    ///      If only one side exists, fall back to the two closest by absolute
    ///      delta (covers extrapolation past the highest known number).
    ///   3. Compute t = (target - lo.n) / (hi.n - lo.n) and lerp lat/lon.
    private func interpolateHouseNumber(
        db: OpaquePointer,
        streetId: Int64,
        targetNumber: Int
    ) -> (lat: Double, lon: Double)? {
        let sql = "SELECT house_number, lat, lon FROM street_house_numbers WHERE street_id = ?1"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_int64(stmt, 1, streetId)

        var anchors: [(n: Int, lat: Double, lon: Double)] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            let raw = columnText(stmt, col: 0) ?? ""
            guard let n = Self.numericPart(of: raw) else { continue }
            anchors.append((n,
                            sqlite3_column_double(stmt, 1),
                            sqlite3_column_double(stmt, 2)))
        }
        guard anchors.count >= 2 else { return nil }
        anchors.sort { $0.n < $1.n }

        let lo = anchors.last(where: { $0.n <= targetNumber })
        let hi = anchors.first(where: { $0.n > targetNumber })

        let a: (n: Int, lat: Double, lon: Double)
        let b: (n: Int, lat: Double, lon: Double)
        if let lo = lo, let hi = hi {
            a = lo; b = hi
        } else {
            // Extrapolation past one end: pick the two closest anchors
            let sorted = anchors.sorted { abs($0.n - targetNumber) < abs($1.n - targetNumber) }
            a = sorted[0]; b = sorted[1]
        }
        guard a.n != b.n else { return (a.lat, a.lon) }
        let t = Double(targetNumber - a.n) / Double(b.n - a.n)
        let lat = a.lat + (b.lat - a.lat) * t
        let lon = a.lon + (b.lon - a.lon) * t
        return (lat, lon)
    }

    /// Haversine distance in meters between two (lat,lon) points.
    private func haversineMeters(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let R = 6_371_000.0
        let phi1 = lat1 * .pi / 180.0
        let phi2 = lat2 * .pi / 180.0
        let dPhi = (lat2 - lat1) * .pi / 180.0
        let dLam = (lon2 - lon1) * .pi / 180.0
        let a = sin(dPhi / 2) * sin(dPhi / 2) + cos(phi1) * cos(phi2) * sin(dLam / 2) * sin(dLam / 2)
        let c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    // MARK: - House-Number Population (for downloader / tooling)

    /// Insert (or replace) a batch of house-number anchors for a region. Called
    /// by the OSM region downloader pipeline once it has parsed `addr:housenumber`
    /// tags from the OSM XML/PBF for this region. Idempotent — safe to call
    /// repeatedly. Requires the DB to have been opened RW (default in `openDB`).
    ///
    /// Each entry: `(streetId, houseNumber, lat, lon)`.
    @discardableResult
    func insertHouseNumbers(
        regionId: String,
        entries: [(streetId: Int64, houseNumber: String, lat: Double, lon: Double)]
    ) -> Bool {
        guard !entries.isEmpty, let db = openDB(regionId: regionId) else { return false }
        sqlite3_exec(db, "BEGIN", nil, nil, nil)
        let sql = "INSERT OR REPLACE INTO street_house_numbers(street_id, house_number, lat, lon) VALUES (?1, ?2, ?3, ?4)"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
            sqlite3_exec(db, "ROLLBACK", nil, nil, nil)
            return false
        }
        for entry in entries {
            sqlite3_reset(stmt)
            sqlite3_bind_int64(stmt, 1, entry.streetId)
            sqlite3_bind_text(stmt, 2, (entry.houseNumber as NSString).utf8String, -1, nil)
            sqlite3_bind_double(stmt, 3, entry.lat)
            sqlite3_bind_double(stmt, 4, entry.lon)
            if sqlite3_step(stmt) != SQLITE_DONE {
                let msg = String(cString: sqlite3_errmsg(db))
                OshiLog.maps.info("[OfflineStreetDatabase] insert house# error: \(msg)")
            }
        }
        sqlite3_exec(db, "COMMIT", nil, nil, nil)
        return true
    }

    // MARK: - Delete Street Index

    /// Removes the downloaded street index for a region.
    func deleteStreetIndex(regionId: String) {
        // Close DB if open
        lock.lock()
        if let db = openDBs[regionId] {
            sqlite3_close(db)
            openDBs.removeValue(forKey: regionId)
        }
        lock.unlock()

        let dir = streetDBDirectory(regionId: regionId)
        try? FileManager.default.removeItem(atPath: dir)
    }

    // MARK: - Private Helpers

    private func streetDBDirectory(regionId: String) -> String {
        guard let docsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return ""
        }
        return docsURL
            .appendingPathComponent("Maps")
            .appendingPathComponent("streets")
            .appendingPathComponent(regionId)
            .path
    }

    private func streetDBPath(regionId: String) -> String {
        return (streetDBDirectory(regionId: regionId) as NSString).appendingPathComponent("streets.db")
    }

    private func openDB(regionId: String) -> OpaquePointer? {
        lock.lock()
        defer { lock.unlock() }

        if let existing = openDBs[regionId] {
            return existing
        }

        let path = streetDBPath(regionId: regionId)
        guard FileManager.default.fileExists(atPath: path) else {
            OshiLog.maps.info("[OfflineStreetDatabase] No street DB at \(path)")
            return nil
        }

        var db: OpaquePointer?
        // Try READWRITE first so the house_number migration can run. If the
        // file is read-only (rare — bundled / locked), fall back to READONLY.
        let rwFlags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX
        var opened = sqlite3_open_v2(path, &db, rwFlags, nil) == SQLITE_OK && db != nil
        if !opened {
            if db != nil { sqlite3_close(db) }
            db = nil
            let roFlags = SQLITE_OPEN_READONLY | SQLITE_OPEN_FULLMUTEX
            opened = sqlite3_open_v2(path, &db, roFlags, nil) == SQLITE_OK && db != nil
        }
        guard opened else {
            let msg = db.flatMap { String(cString: sqlite3_errmsg($0)) } ?? "unknown"
            OshiLog.maps.info("[OfflineStreetDatabase] Failed to open: \(msg)")
            return nil
        }

        runHouseNumberMigration(db: db!)
        openDBs[regionId] = db
        return db
    }

    /// Idempotent `street_house_numbers` schema migration. Safe to run on every
    /// open; CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS are no-ops
    /// when the table already exists. On a read-only DB this silently fails —
    /// queries will simply return zero rows in that case, which is fine.
    private func runHouseNumberMigration(db: OpaquePointer) {
        let stmts = [
            """
            CREATE TABLE IF NOT EXISTS street_house_numbers(
                street_id INTEGER NOT NULL,
                house_number TEXT NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                PRIMARY KEY(street_id, house_number)
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_house_numbers_street ON street_house_numbers(street_id)"
        ]
        for sql in stmts {
            if sqlite3_exec(db, sql, nil, nil, nil) != SQLITE_OK {
                let msg = String(cString: sqlite3_errmsg(db))
                // Read-only DB will fail here — that's expected and harmless.
                OshiLog.maps.info("[OfflineStreetDatabase] migration note: \(msg)")
            }
        }
    }

    private func execStreetQuery(db: OpaquePointer, sql: String, binder: (OpaquePointer) -> Void) -> [StreetResult] {
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }

        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
            let msg = String(cString: sqlite3_errmsg(db))
            OshiLog.maps.info("[OfflineStreetDatabase] SQL prepare error: \(msg)")
            return []
        }

        binder(stmt!)

        var results: [StreetResult] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            let street = StreetResult(
                id: sqlite3_column_int64(stmt, 0),
                name: columnText(stmt, col: 1) ?? "",
                city: columnText(stmt, col: 2),
                lat: sqlite3_column_double(stmt, 3),
                lon: sqlite3_column_double(stmt, 4),
                highwayType: columnText(stmt, col: 5)
            )
            results.append(street)
        }
        return results
    }

    private func columnText(_ stmt: OpaquePointer?, col: Int32) -> String? {
        guard let raw = sqlite3_column_text(stmt, col) else { return nil }
        return String(cString: raw)
    }

    /// Prepares user input for FTS5 MATCH: quotes each token and appends * for prefix matching.
    private func sanitizeFTSQuery(_ query: String) -> String {
        let tokens = query
            .components(separatedBy: .whitespacesAndNewlines)
            .map { $0.trimmingCharacters(in: .punctuationCharacters) }
            .filter { !$0.isEmpty }
        guard !tokens.isEmpty else { return "" }
        return tokens.map { "\"\($0)\"*" }.joined(separator: " ")
    }

    /// Is the file at `url` a gzip archive?
    ///
    /// Two bytes, so this is not the memory hazard the old whole-file
    /// `decompressGzip(data:)` was — that one is gone; decompression is
    /// `GzipStreamDecompressor`'s job now.
    static func isGzip(at url: URL) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }
        guard let magic = try? handle.read(upToCount: 2), magic.count == 2 else { return false }
        return magic[magic.startIndex] == 0x1f && magic[magic.index(after: magic.startIndex)] == 0x8b
    }

    enum StreetDBError: LocalizedError {
        case invalidURL
        case downloadFailed
        case decompressionFailed
        case unavailableForCountry

        var errorDescription: String? {
            switch self {
            case .invalidURL: return "Invalid street index URL"
            case .downloadFailed: return "Street index download failed"
            case .decompressionFailed: return "Failed to decompress street index"
            case .unavailableForCountry:
                return NSLocalizedString(
                    "map.regions.street_index.unavailable_for_country",
                    value: "Street index is not yet available for this country.",
                    comment: "Soft error when neither the regional nor the country-level streets file exists server-side"
                )
            }
        }
    }
}
