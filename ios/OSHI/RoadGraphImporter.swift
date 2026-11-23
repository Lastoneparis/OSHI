//
//  RoadGraphImporter.swift
//  OSHI
//
//  Fetches OSM road data from the Overpass API and writes the per-region
//  road_graph.db SQLite file consumed by OfflineRouter.
//
//  This is intentionally separate from OfflineRoutingManager (which still
//  manages the legacy OSRM .tar.gz pipeline). Once the road graph is
//  imported, the router runs 100% offline.
//
//  Trigger: from MapRegionDownloadView after a region's tiles finish downloading,
//           or manually via a settings/debug action.
//

import Foundation
import SQLite3
import CoreLocation

final class RoadGraphImporter {
    static let shared = RoadGraphImporter()

    private static let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    /// Public Overpass mirror. Switching to a self-hosted instance is a one-line change.
    private static let overpassURL = URL(string: "https://overpass-api.de/api/interpreter")!

    private init() {}

    enum ImporterError: Error {
        case network(String)
        case badResponse
        case sqlite(String)
        case cancelled
    }

    // MARK: - Public API

    /// Import the road graph for a region. The region's bbox is used as the
    /// Overpass query area. Existing road_graph.db at the destination is overwritten.
    /// Progress callbacks deliver values in [0, 1] on the main queue.
    func importRoadGraph(
        region: MapRegion,
        progress: @escaping (Double) -> Void = { _ in },
        completion: @escaping (Result<Int, ImporterError>) -> Void
    ) {
        DispatchQueue.global(qos: .utility).async {
            do {
                progress(0.05)
                let osm = try self.fetchOverpass(bbox: region.bbox)
                progress(0.55)
                let edgeCount = try self.writeDatabase(osm: osm, regionId: region.id) { p in
                    progress(0.55 + 0.45 * p)
                }
                progress(1.0)
                DispatchQueue.main.async { completion(.success(edgeCount)) }
            } catch let e as ImporterError {
                DispatchQueue.main.async { completion(.failure(e)) }
            } catch {
                DispatchQueue.main.async {
                    completion(.failure(.network(error.localizedDescription)))
                }
            }
        }
    }

    // MARK: - Overpass fetch

    private struct OSMResult {
        // Map of OSM node id → (lat, lon)
        var nodes: [Int64: (Double, Double)] = [:]
        // List of OSM ways: list of node ids + tag dictionary.
        var ways: [(ids: [Int64], tags: [String: String])] = []
    }

    private func fetchOverpass(bbox: [Double]) throws -> OSMResult {
        guard bbox.count >= 4 else { throw ImporterError.badResponse }
        // Overpass bbox order is south,west,north,east.
        let south = bbox[1], west = bbox[0], north = bbox[3], east = bbox[2]

        let query = """
        [out:json][timeout:180];
        (
          way["highway"~"motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|pedestrian|footway|path|cycleway|track|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link"]
            (\(south),\(west),\(north),\(east));
        );
        (._;>;);
        out body;
        """

        var req = URLRequest(url: Self.overpassURL)
        req.httpMethod = "POST"
        req.timeoutInterval = 240
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        req.httpBody = "data=\(query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")".data(using: .utf8)

        let sema = DispatchSemaphore(value: 0)
        var resultData: Data?
        var resultErr: Error?
        URLSession.shared.dataTask(with: req) { data, _, error in
            resultData = data
            resultErr = error
            sema.signal()
        }.resume()
        sema.wait()

        if let e = resultErr { throw ImporterError.network(e.localizedDescription) }
        guard let data = resultData else { throw ImporterError.badResponse }

        guard let json = try? JSONSerialization.jsonObject(with: data, options: []) as? [String: Any],
              let elements = json["elements"] as? [[String: Any]] else {
            throw ImporterError.badResponse
        }

        var out = OSMResult()
        for el in elements {
            guard let type = el["type"] as? String else { continue }
            if type == "node" {
                if let id = el["id"] as? Int64,
                   let lat = el["lat"] as? Double,
                   let lon = el["lon"] as? Double {
                    out.nodes[id] = (lat, lon)
                } else if let id = (el["id"] as? Int).map(Int64.init),
                          let lat = el["lat"] as? Double,
                          let lon = el["lon"] as? Double {
                    out.nodes[id] = (lat, lon)
                }
            } else if type == "way" {
                let nds: [Int64] = (el["nodes"] as? [Any] ?? []).compactMap {
                    if let v = $0 as? Int64 { return v }
                    if let v = $0 as? Int { return Int64(v) }
                    return nil
                }
                let tags = (el["tags"] as? [String: String]) ?? [:]
                if nds.count >= 2 {
                    out.ways.append((nds, tags))
                }
            }
        }
        return out
    }

    // MARK: - Database writing

    private func writeDatabase(
        osm: OSMResult,
        regionId: String,
        progress: @escaping (Double) -> Void
    ) throws -> Int {
        // Identify junction nodes (used by 2+ ways) plus all way endpoints.
        var refCount: [Int64: Int] = [:]
        for w in osm.ways {
            for (i, id) in w.ids.enumerated() {
                let isEndpoint = (i == 0 || i == w.ids.count - 1)
                refCount[id, default: 0] += isEndpoint ? 1 : 0
            }
            for id in w.ids {
                refCount[id, default: 0] += 1
            }
        }
        // Anchor any node that is referenced by 2+ ways/endpoints (i.e. a junction).
        var anchorNodes = Set<Int64>()
        for (id, c) in refCount where c >= 2 {
            anchorNodes.insert(id)
        }

        let dbPath = MapStorageManager.shared.regionDirectory(for: regionId)
            .appendingPathComponent("road_graph.db").path

        // Ensure parent dir exists.
        try? FileManager.default.createDirectory(
            atPath: (dbPath as NSString).deletingLastPathComponent,
            withIntermediateDirectories: true
        )
        // Remove any existing file.
        try? FileManager.default.removeItem(atPath: dbPath)

        var db: OpaquePointer?
        guard sqlite3_open(dbPath, &db) == SQLITE_OK, let handle = db else {
            throw ImporterError.sqlite("open failed")
        }
        defer { sqlite3_close(handle) }

        // PRAGMAs for fast bulk insert.
        sqlite3_exec(handle, "PRAGMA journal_mode=OFF;", nil, nil, nil)
        sqlite3_exec(handle, "PRAGMA synchronous=OFF;", nil, nil, nil)
        sqlite3_exec(handle, "PRAGMA temp_store=MEMORY;", nil, nil, nil)

        let schema = """
        CREATE TABLE IF NOT EXISTS road_nodes(
            id INTEGER PRIMARY KEY,
            lat REAL,
            lon REAL
        );
        CREATE TABLE IF NOT EXISTS road_edges(
            from_id INTEGER,
            to_id INTEGER,
            weight_car REAL,
            weight_bike REAL,
            weight_foot REAL,
            polyline TEXT,
            highway TEXT,
            oneway INTEGER,
            PRIMARY KEY(from_id, to_id)
        );
        CREATE INDEX IF NOT EXISTS idx_road_edges_from ON road_edges(from_id);
        CREATE INDEX IF NOT EXISTS idx_road_nodes_geo ON road_nodes(lat, lon);
        """
        if sqlite3_exec(handle, schema, nil, nil, nil) != SQLITE_OK {
            throw ImporterError.sqlite("schema failed")
        }

        sqlite3_exec(handle, "BEGIN TRANSACTION;", nil, nil, nil)

        // Insert anchor nodes.
        var nodeStmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, "INSERT OR REPLACE INTO road_nodes(id, lat, lon) VALUES(?,?,?)", -1, &nodeStmt, nil) == SQLITE_OK else {
            throw ImporterError.sqlite("prepare nodes")
        }
        for id in anchorNodes {
            guard let coord = osm.nodes[id] else { continue }
            sqlite3_reset(nodeStmt)
            sqlite3_bind_int64(nodeStmt, 1, id)
            sqlite3_bind_double(nodeStmt, 2, coord.0)
            sqlite3_bind_double(nodeStmt, 3, coord.1)
            sqlite3_step(nodeStmt)
        }
        sqlite3_finalize(nodeStmt)

        // Insert edges. Each consecutive pair of anchor nodes within a way becomes one edge.
        var edgeStmt: OpaquePointer?
        guard sqlite3_prepare_v2(
            handle,
            """
            INSERT OR REPLACE INTO road_edges
            (from_id, to_id, weight_car, weight_bike, weight_foot, polyline, highway, oneway)
            VALUES(?,?,?,?,?,?,?,?)
            """,
            -1, &edgeStmt, nil
        ) == SQLITE_OK else {
            throw ImporterError.sqlite("prepare edges")
        }

        var edgeCount = 0
        let totalWays = max(osm.ways.count, 1)
        for (wIdx, way) in osm.ways.enumerated() {
            let highway = way.tags["highway"] ?? ""
            let onewayTag = way.tags["oneway"] ?? "no"
            let oneway: Int32 = (onewayTag == "yes" || onewayTag == "true" || onewayTag == "1") ? 1 : 0
            let isFootOnly = (highway == "footway" || highway == "path" || highway == "pedestrian" || highway == "steps")
            let isMotorwayOnly = (highway == "motorway" || highway == "motorway_link" || highway == "trunk" || highway == "trunk_link")

            // Walk through node ids; each contiguous span between two anchor nodes becomes an edge.
            var segment: [Int64] = []
            for nid in way.ids {
                segment.append(nid)
                if anchorNodes.contains(nid) && segment.count >= 2 && segment.first! != nid {
                    if let edge = makeEdge(
                        ids: segment, osm: osm, highway: highway,
                        oneway: oneway, isFootOnly: isFootOnly, isMotorwayOnly: isMotorwayOnly
                    ) {
                        try writeEdge(edgeStmt: edgeStmt, edge: edge)
                        edgeCount += 1
                        // Insert reverse direction unless oneway forbids it (cars only).
                        // Bike/foot ignore oneway (we still write the edge once with weight_foot/bike;
                        // the router consults oneway only for car mode).
                        if let rev = makeReverseEdge(edge: edge, oneway: oneway) {
                            try writeEdge(edgeStmt: edgeStmt, edge: rev)
                            edgeCount += 1
                        }
                    }
                    segment = [nid]
                }
            }
            if wIdx % 256 == 0 {
                progress(Double(wIdx) / Double(totalWays))
            }
        }
        sqlite3_finalize(edgeStmt)

        sqlite3_exec(handle, "COMMIT;", nil, nil, nil)
        return edgeCount
    }

    private struct EdgeRow {
        let fromId: Int64
        let toId: Int64
        let weightCar: Double
        let weightBike: Double
        let weightFoot: Double
        let polyline: [CLLocationCoordinate2D]
        let highway: String
        let oneway: Int32
    }

    private func makeEdge(
        ids: [Int64], osm: OSMResult,
        highway: String, oneway: Int32,
        isFootOnly: Bool, isMotorwayOnly: Bool
    ) -> EdgeRow? {
        guard ids.count >= 2 else { return nil }
        guard let _ = osm.nodes[ids.first!], let _ = osm.nodes[ids.last!] else { return nil }

        var coords: [CLLocationCoordinate2D] = []
        coords.reserveCapacity(ids.count)
        for id in ids {
            guard let c = osm.nodes[id] else { continue }
            coords.append(CLLocationCoordinate2D(latitude: c.0, longitude: c.1))
        }
        guard coords.count >= 2 else { return nil }

        // Compute haversine length
        var meters: Double = 0
        for i in 1..<coords.count {
            meters += haversine(
                lat1: coords[i-1].latitude, lon1: coords[i-1].longitude,
                lat2: coords[i].latitude, lon2: coords[i].longitude
            )
        }

        // Mode speeds (m/s) by highway class.
        let carMps = isFootOnly ? Double.greatestFiniteMagnitude : carSpeed(highway: highway)
        let bikeMps = isMotorwayOnly ? Double.greatestFiniteMagnitude : bikeSpeed(highway: highway)
        let footMps = isMotorwayOnly ? Double.greatestFiniteMagnitude : footSpeed(highway: highway)

        let weightCar = meters / max(carMps, 0.1)
        let weightBike = meters / max(bikeMps, 0.1)
        let weightFoot = meters / max(footMps, 0.1)

        return EdgeRow(
            fromId: ids.first!,
            toId: ids.last!,
            weightCar: weightCar,
            weightBike: weightBike,
            weightFoot: weightFoot,
            polyline: coords,
            highway: highway,
            oneway: oneway
        )
    }

    private func makeReverseEdge(edge: EdgeRow, oneway: Int32) -> EdgeRow? {
        // For cars we cannot use this reverse edge if the way is oneway,
        // but we still write it so bike/foot can use it. The router checks
        // oneway when expanding for car mode.
        let revPoly = Array(edge.polyline.reversed())
        // For cars, mark weight_car as +inf if oneway forbids reverse.
        let carWeight = (oneway == 1) ? Double.greatestFiniteMagnitude : edge.weightCar
        return EdgeRow(
            fromId: edge.toId,
            toId: edge.fromId,
            weightCar: carWeight,
            weightBike: edge.weightBike,
            weightFoot: edge.weightFoot,
            polyline: revPoly,
            highway: edge.highway,
            oneway: oneway
        )
    }

    private func writeEdge(edgeStmt: OpaquePointer?, edge: EdgeRow) throws {
        sqlite3_reset(edgeStmt)
        sqlite3_bind_int64(edgeStmt, 1, edge.fromId)
        sqlite3_bind_int64(edgeStmt, 2, edge.toId)
        sqlite3_bind_double(edgeStmt, 3, edge.weightCar.isFinite ? edge.weightCar : 1e12)
        sqlite3_bind_double(edgeStmt, 4, edge.weightBike.isFinite ? edge.weightBike : 1e12)
        sqlite3_bind_double(edgeStmt, 5, edge.weightFoot.isFinite ? edge.weightFoot : 1e12)
        let poly = OfflineRouter.encodePolyline(edge.polyline)
        sqlite3_bind_text(edgeStmt, 6, (poly as NSString).utf8String, -1, Self.SQLITE_TRANSIENT)
        sqlite3_bind_text(edgeStmt, 7, (edge.highway as NSString).utf8String, -1, Self.SQLITE_TRANSIENT)
        sqlite3_bind_int(edgeStmt, 8, edge.oneway)
        if sqlite3_step(edgeStmt) != SQLITE_DONE {
            // ignore: PK conflict can happen when a way visits the same junction pair twice.
        }
    }

    // MARK: - Speed tables (m/s)

    private func carSpeed(highway: String) -> Double {
        switch highway {
        case "motorway", "motorway_link":     return 33.3   // 120 km/h
        case "trunk", "trunk_link":           return 25.0   //  90 km/h
        case "primary", "primary_link":       return 20.0   //  70 km/h
        case "secondary", "secondary_link":   return 16.7   //  60 km/h
        case "tertiary", "tertiary_link":     return 13.9   //  50 km/h
        case "unclassified":                  return 11.1   //  40 km/h
        case "residential", "living_street":  return 8.3    //  30 km/h
        case "service":                       return 5.5    //  20 km/h
        default:                              return 5.5
        }
    }

    private func bikeSpeed(highway: String) -> Double {
        switch highway {
        case "cycleway":                      return 5.5    // 20 km/h
        case "primary", "primary_link",
             "secondary", "secondary_link":   return 4.4    // 16 km/h
        case "tertiary", "tertiary_link",
             "residential", "living_street",
             "unclassified", "service":       return 4.0    // 14 km/h
        case "footway", "path",
             "pedestrian", "track":           return 3.5    // 12 km/h
        default:                              return 3.5
        }
    }

    private func footSpeed(highway: String) -> Double {
        switch highway {
        case "footway", "path", "pedestrian", "steps", "residential", "living_street":
            return 1.4    // 5 km/h
        case "cycleway", "track", "service", "unclassified", "tertiary", "tertiary_link":
            return 1.3
        case "secondary", "secondary_link", "primary", "primary_link":
            return 1.2
        default:
            return 1.2
        }
    }

    private func haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double) -> Double {
        let R = 6_371_000.0
        let p1 = lat1 * .pi / 180.0
        let p2 = lat2 * .pi / 180.0
        let dp = (lat2 - lat1) * .pi / 180.0
        let dl = (lon2 - lon1) * .pi / 180.0
        let a = sin(dp/2) * sin(dp/2) + cos(p1) * cos(p2) * sin(dl/2) * sin(dl/2)
        let c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
}
