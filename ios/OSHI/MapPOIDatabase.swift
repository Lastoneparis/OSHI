import Foundation
import SQLite3
import CoreLocation

// MARK: - Offline POI Search Engine (SQLite3 + FTS5)

final class MapPOIDatabase {

    // MARK: - Properties

    private var db: OpaquePointer?
    private(set) var currentRegionId: String?
    private let lock = NSLock()

    private static let earthRadiusKm: Double = 6371.0

    // MARK: - Lifecycle

    deinit {
        close()
    }

    // MARK: - Open / Close

    /// Opens the POI database for the given region.
    /// Database file is expected at Documents/Maps/regions/{regionId}/poi.db
    func open(regionId: String) -> Bool {
        close()

        guard let documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
            OshiLog.maps.info("[MapPOIDatabase] Could not resolve Documents directory")
            return false
        }

        let dbPath = documentsURL
            .appendingPathComponent("Maps")
            .appendingPathComponent("regions")
            .appendingPathComponent(regionId)
            .appendingPathComponent("poi.db")
            .path

        guard FileManager.default.fileExists(atPath: dbPath) else {
            OshiLog.maps.info("[MapPOIDatabase] Database not found at \(dbPath)")
            return false
        }

        let flags = SQLITE_OPEN_READONLY | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(dbPath, &db, flags, nil) == SQLITE_OK, db != nil else {
            let msg = db.flatMap { String(cString: sqlite3_errmsg($0)) } ?? "unknown"
            OshiLog.maps.info("[MapPOIDatabase] Failed to open: \(msg)")
            db = nil
            return false
        }

        // Register custom Haversine function for distance queries
        registerHaversineFunction()

        currentRegionId = regionId
        return true
    }

    /// Closes the current database connection.
    func close() {
        lock.lock()
        defer { lock.unlock() }
        if let db = db {
            sqlite3_close(db)
        }
        db = nil
        currentRegionId = nil
    }

    // MARK: - Search by Text Query

    /// Searches POIs by text using FTS5, optionally filtered/sorted by proximity.
    /// - Parameters:
    ///   - query: Search text (matched against name, category, subcategory, address).
    ///   - near: Optional coordinate for distance filtering and sorting.
    ///   - radiusKm: Max distance in km when `near` is set. Default 10.
    ///   - limit: Max results. Default 50.
    func search(query: String, near: CLLocationCoordinate2D? = nil, radiusKm: Double = 10.0, limit: Int = 50) -> [MapPOI] {
        guard db != nil else {
            OshiLog.maps.info("[MapPOIDatabase] No database open")
            return []
        }

        let ftsQuery = sanitizeFTSQuery(query)
        guard !ftsQuery.isEmpty else { return [] }

        if let coord = near {
            let bbox = boundingBox(center: coord, radiusKm: radiusKm)

            let sql = """
                SELECT p.id, p.name, p.category, p.subcategory, p.lat, p.lon,
                       p.address, p.phone, p.website, p.opening_hours,
                       haversine(p.lat, p.lon, ?1, ?2) AS dist_km
                FROM pois p
                INNER JOIN pois_fts f ON p.id = f.rowid
                WHERE f.pois_fts MATCH ?3
                  AND p.lat BETWEEN ?4 AND ?5
                  AND p.lon BETWEEN ?6 AND ?7
                  AND dist_km <= ?8
                ORDER BY dist_km ASC
                LIMIT ?9
                """

            return execPOIQuery(sql: sql, binder: { stmt in
                sqlite3_bind_double(stmt, 1, coord.latitude)
                sqlite3_bind_double(stmt, 2, coord.longitude)
                sqlite3_bind_text(stmt, 3, (ftsQuery as NSString).utf8String, -1, nil)
                sqlite3_bind_double(stmt, 4, bbox.minLat)
                sqlite3_bind_double(stmt, 5, bbox.maxLat)
                sqlite3_bind_double(stmt, 6, bbox.minLon)
                sqlite3_bind_double(stmt, 7, bbox.maxLon)
                sqlite3_bind_double(stmt, 8, radiusKm)
                sqlite3_bind_int(stmt, 9, Int32(limit))
            })
        } else {
            let sql = """
                SELECT p.id, p.name, p.category, p.subcategory, p.lat, p.lon,
                       p.address, p.phone, p.website, p.opening_hours,
                       0.0 AS dist_km
                FROM pois p
                INNER JOIN pois_fts f ON p.id = f.rowid
                WHERE f.pois_fts MATCH ?1
                ORDER BY rank
                LIMIT ?2
                """

            return execPOIQuery(sql: sql, binder: { stmt in
                sqlite3_bind_text(stmt, 1, (ftsQuery as NSString).utf8String, -1, nil)
                sqlite3_bind_int(stmt, 2, Int32(limit))
            })
        }
    }

    // MARK: - Search by Address

    /// Searches POIs by address using LIKE on the regular `pois` table.
    /// Returns POIs whose `address` column contains the search query.
    /// - Parameters:
    ///   - query: Search text matched against the address column.
    ///   - near: Optional coordinate for distance sorting.
    ///   - radiusKm: Max distance in km when `near` is set. Default 50.
    ///   - limit: Max results. Default 20.
    func searchByAddress(query: String, near: CLLocationCoordinate2D? = nil, radiusKm: Double = 50.0, limit: Int = 20) -> [MapPOI] {
        guard db != nil else {
            OshiLog.maps.info("[MapPOIDatabase] No database open")
            return []
        }

        let likePattern = "%\(query)%"

        if let coord = near {
            let bbox = boundingBox(center: coord, radiusKm: radiusKm)

            let sql = """
                SELECT id, name, category, subcategory, lat, lon,
                       address, phone, website, opening_hours,
                       haversine(lat, lon, ?1, ?2) AS dist_km
                FROM pois
                WHERE address LIKE ?3 COLLATE NOCASE
                  AND lat BETWEEN ?4 AND ?5
                  AND lon BETWEEN ?6 AND ?7
                  AND dist_km <= ?8
                ORDER BY dist_km ASC
                LIMIT ?9
                """

            return execPOIQuery(sql: sql, binder: { stmt in
                sqlite3_bind_double(stmt, 1, coord.latitude)
                sqlite3_bind_double(stmt, 2, coord.longitude)
                sqlite3_bind_text(stmt, 3, (likePattern as NSString).utf8String, -1, nil)
                sqlite3_bind_double(stmt, 4, bbox.minLat)
                sqlite3_bind_double(stmt, 5, bbox.maxLat)
                sqlite3_bind_double(stmt, 6, bbox.minLon)
                sqlite3_bind_double(stmt, 7, bbox.maxLon)
                sqlite3_bind_double(stmt, 8, radiusKm)
                sqlite3_bind_int(stmt, 9, Int32(limit))
            })
        } else {
            let sql = """
                SELECT id, name, category, subcategory, lat, lon,
                       address, phone, website, opening_hours,
                       0.0 AS dist_km
                FROM pois
                WHERE address LIKE ?1 COLLATE NOCASE
                ORDER BY name ASC
                LIMIT ?2
                """

            return execPOIQuery(sql: sql, binder: { stmt in
                sqlite3_bind_text(stmt, 1, (likePattern as NSString).utf8String, -1, nil)
                sqlite3_bind_int(stmt, 2, Int32(limit))
            })
        }
    }

    // MARK: - Search by Category

    /// Searches POIs by category, optionally filtered/sorted by proximity.
    func searchByCategory(_ category: String, near: CLLocationCoordinate2D? = nil, radiusKm: Double = 10.0, limit: Int = 50) -> [MapPOI] {
        guard db != nil else {
            OshiLog.maps.info("[MapPOIDatabase] No database open")
            return []
        }

        if let coord = near {
            let bbox = boundingBox(center: coord, radiusKm: radiusKm)

            let sql = """
                SELECT id, name, category, subcategory, lat, lon,
                       address, phone, website, opening_hours,
                       haversine(lat, lon, ?1, ?2) AS dist_km
                FROM pois
                WHERE category = ?3
                  AND lat BETWEEN ?4 AND ?5
                  AND lon BETWEEN ?6 AND ?7
                  AND dist_km <= ?8
                ORDER BY dist_km ASC
                LIMIT ?9
                """

            return execPOIQuery(sql: sql, binder: { stmt in
                sqlite3_bind_double(stmt, 1, coord.latitude)
                sqlite3_bind_double(stmt, 2, coord.longitude)
                sqlite3_bind_text(stmt, 3, (category as NSString).utf8String, -1, nil)
                sqlite3_bind_double(stmt, 4, bbox.minLat)
                sqlite3_bind_double(stmt, 5, bbox.maxLat)
                sqlite3_bind_double(stmt, 6, bbox.minLon)
                sqlite3_bind_double(stmt, 7, bbox.maxLon)
                sqlite3_bind_double(stmt, 8, radiusKm)
                sqlite3_bind_int(stmt, 9, Int32(limit))
            })
        } else {
            let sql = """
                SELECT id, name, category, subcategory, lat, lon,
                       address, phone, website, opening_hours,
                       0.0 AS dist_km
                FROM pois
                WHERE category = ?1
                ORDER BY name ASC
                LIMIT ?2
                """

            return execPOIQuery(sql: sql, binder: { stmt in
                sqlite3_bind_text(stmt, 1, (category as NSString).utf8String, -1, nil)
                sqlite3_bind_int(stmt, 2, Int32(limit))
            })
        }
    }

    // MARK: - POI Count

    /// Returns the total number of POIs in a region's database.
    /// Opens the database temporarily if not already open for that region.
    func poiCount(regionId: String) -> Int {
        let wasOtherRegion = (currentRegionId != regionId)
        let previousRegion = currentRegionId

        if wasOtherRegion {
            guard open(regionId: regionId) else { return 0 }
        }

        guard let db = db else { return 0 }

        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }

        guard sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM pois", -1, &stmt, nil) == SQLITE_OK else {
            OshiLog.maps.info("[MapPOIDatabase] Count query failed")
            return 0
        }

        var count = 0
        if sqlite3_step(stmt) == SQLITE_ROW {
            count = Int(sqlite3_column_int64(stmt, 0))
        }

        // Restore previous region if we switched
        if wasOtherRegion, let prev = previousRegion {
            _ = open(regionId: prev)
        }

        return count
    }

    // MARK: - Custom SQL Function: Haversine

    /// Registers a custom `haversine(lat1, lon1, lat2, lon2)` SQL function
    /// that returns the great-circle distance in kilometers.
    private func registerHaversineFunction() {
        guard let db = db else { return }

        let haversineImpl: @convention(c) (OpaquePointer?, Int32, UnsafeMutablePointer<OpaquePointer?>?) -> Void = { context, argc, argv in
            guard argc == 4, let argv = argv else {
                sqlite3_result_null(context)
                return
            }

            let lat1 = sqlite3_value_double(argv[0]) * .pi / 180.0
            let lon1 = sqlite3_value_double(argv[1]) * .pi / 180.0
            let lat2 = sqlite3_value_double(argv[2]) * .pi / 180.0
            let lon2 = sqlite3_value_double(argv[3]) * .pi / 180.0

            let dlat = lat2 - lat1
            let dlon = lon2 - lon1

            let a = sin(dlat / 2) * sin(dlat / 2) +
                    cos(lat1) * cos(lat2) *
                    sin(dlon / 2) * sin(dlon / 2)
            let c = 2 * atan2(sqrt(a), sqrt(1 - a))

            sqlite3_result_double(context, 6371.0 * c)
        }

        sqlite3_create_function(db, "haversine", 4, SQLITE_UTF8 | SQLITE_DETERMINISTIC, nil, haversineImpl, nil, nil)
    }

    // MARK: - Query Execution

    /// Prepares, binds, and executes a POI query, returning results as [MapPOI].
    /// The query must SELECT columns in this order:
    /// id, name, category, subcategory, lat, lon, address, phone, website, opening_hours, dist_km
    private func execPOIQuery(sql: String, binder: (OpaquePointer) -> Void) -> [MapPOI] {
        lock.lock()
        defer { lock.unlock() }
        guard let db = db else { return [] }

        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }

        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
            let msg = String(cString: sqlite3_errmsg(db))
            OshiLog.maps.info("[MapPOIDatabase] SQL prepare error: \(msg)")
            return []
        }

        binder(stmt!)

        var results: [MapPOI] = []

        while sqlite3_step(stmt) == SQLITE_ROW {
            let poi = MapPOI(
                id: sqlite3_column_int64(stmt, 0),
                name: columnText(stmt, col: 1) ?? "",
                category: columnText(stmt, col: 2) ?? "",
                subcategory: columnText(stmt, col: 3),
                coordinate: CLLocationCoordinate2D(
                    latitude: sqlite3_column_double(stmt, 4),
                    longitude: sqlite3_column_double(stmt, 5)
                ),
                address: columnText(stmt, col: 6),
                phone: columnText(stmt, col: 7),
                website: columnText(stmt, col: 8),
                openingHours: columnText(stmt, col: 9)
            )
            results.append(poi)
        }

        return results
    }

    /// Safely reads a TEXT column as an optional String.
    private func columnText(_ stmt: OpaquePointer?, col: Int32) -> String? {
        guard let raw = sqlite3_column_text(stmt, col) else { return nil }
        return String(cString: raw)
    }

    // MARK: - FTS Query Sanitization

    /// Prepares user input for FTS5 MATCH: quotes each token and appends * for prefix matching.
    private func sanitizeFTSQuery(_ query: String) -> String {
        let tokens = query
            .components(separatedBy: .whitespacesAndNewlines)
            .map { $0.trimmingCharacters(in: .punctuationCharacters) }
            .filter { !$0.isEmpty }

        guard !tokens.isEmpty else { return "" }
        return tokens.map { "\"\($0)\"*" }.joined(separator: " ")
    }

    // MARK: - Bounding Box

    private func boundingBox(center: CLLocationCoordinate2D, radiusKm: Double) -> (minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) {
        let latDelta = radiusKm / 111.32
        let lonDelta = radiusKm / (111.32 * cos(center.latitude * .pi / 180.0))
        return (
            minLat: center.latitude - latDelta,
            maxLat: center.latitude + latDelta,
            minLon: center.longitude - lonDelta,
            maxLon: center.longitude + lonDelta
        )
    }
}
