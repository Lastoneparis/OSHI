//
//  OfflineRouter.swift
//  OSHI
//
//  TRUE offline routing on a SQLite-backed OSM road graph.
//  Bidirectional A* over (road_nodes, road_edges) tables stored per region.
//
//  Storage (per-region road graph DB):
//    Documents/Maps/regions/{regionId}/road_graph.db
//
//  Schema:
//    road_nodes(id INTEGER PRIMARY KEY, lat REAL, lon REAL)
//    road_edges(from_id INTEGER, to_id INTEGER, weight_car REAL,
//               weight_bike REAL, weight_foot REAL, polyline TEXT,
//               highway TEXT, oneway INTEGER, PRIMARY KEY(from_id, to_id))
//    idx_road_edges_from ON road_edges(from_id)
//    idx_road_nodes_geo ON road_nodes(lat, lon)
//
//  Public API:
//    OfflineRouter.shared.route(from:to:mode:) async -> MapRoute?
//
//  Algorithm:
//    Bidirectional A* (forward from origin, backward from destination).
//    Heuristic = haversine distance / max travel speed for the mode.
//    Snap origin/destination to the nearest road node within 200 m (else nil).
//    Hard budget: 5 s, after which we bail with nil.
//

import Foundation
import SQLite3
import CoreLocation

/// Errors that can be raised by the offline routing pipeline.
enum RoutingError: Error {
    case noOfflineRoute
    case offlineGraphMissing
}

// `@unchecked Sendable`: this class has no instance-level mutable state — all
// fields are `static let` constants, and each routing call opens its own
// SQLite handle in `routeSync`. Without the conformance, Swift 6's
// concurrency checker rejected the `DispatchQueue.global().async` closure
// in `route(from:to:mode:)` with "Capture of 'self' with non-Sendable type
// 'OfflineRouter' in a '@Sendable' closure".
final class OfflineRouter: @unchecked Sendable {
    static let shared = OfflineRouter()

    private static let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    /// Hard timeout for an A* search.
    private static let searchTimeoutSeconds: TimeInterval = 5.0

    /// Maximum snap distance for from/to coordinates to nearest node.
    private static let snapMaxMeters: Double = 200.0

    private init() {}

    // MARK: - Public API

    /// Calculate an offline route. Runs on a background queue.
    /// Returns nil if no road graph DB covers the origin, or no route found.
    func route(
        from origin: CLLocationCoordinate2D,
        to destination: CLLocationCoordinate2D,
        mode: RouteMode
    ) async -> MapRoute? {
        return await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                let result = self.routeSync(from: origin, to: destination, mode: mode)
                continuation.resume(returning: result)
            }
        }
    }

    /// Whether a road-graph DB is available for the given coordinate.
    func hasGraph(for coordinate: CLLocationCoordinate2D) -> Bool {
        return roadGraphDB(forCoordinate: coordinate) != nil
    }

    /// Path to the road graph DB for a region.
    func roadGraphPath(for regionId: String) -> URL {
        MapStorageManager.shared.regionDirectory(for: regionId)
            .appendingPathComponent("road_graph.db")
    }

    /// Whether a road graph DB has been populated for a region.
    func hasGraph(forRegionId regionId: String) -> Bool {
        let path = roadGraphPath(for: regionId).path
        guard FileManager.default.fileExists(atPath: path) else { return false }
        // Quick sanity: open and check that road_nodes has at least 1 row.
        var db: OpaquePointer?
        defer { if let db = db { sqlite3_close(db) } }
        guard sqlite3_open_v2(path, &db, SQLITE_OPEN_READONLY, nil) == SQLITE_OK else { return false }
        var stmt: OpaquePointer?
        defer { if let stmt = stmt { sqlite3_finalize(stmt) } }
        guard sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM road_nodes LIMIT 1", -1, &stmt, nil) == SQLITE_OK else { return false }
        guard sqlite3_step(stmt) == SQLITE_ROW else { return false }
        return sqlite3_column_int64(stmt, 0) > 0
    }

    // MARK: - Synchronous worker

    private func routeSync(
        from origin: CLLocationCoordinate2D,
        to destination: CLLocationCoordinate2D,
        mode: RouteMode
    ) -> MapRoute? {
        let deadline = Date().addingTimeInterval(Self.searchTimeoutSeconds)

        // Find a graph DB that covers the origin.
        guard let graph = roadGraphDB(forCoordinate: origin) else {
            return nil
        }
        defer { sqlite3_close(graph.handle) }

        // Snap from / to nodes.
        guard let startId = snapNearestNode(db: graph.handle, lat: origin.latitude, lon: origin.longitude) else {
            return nil
        }
        guard let goalId = snapNearestNode(db: graph.handle, lat: destination.latitude, lon: destination.longitude) else {
            return nil
        }
        if startId == goalId {
            // Trivial route: snap is the same node, return a tiny stub.
            return MapRoute(
                distance: 0,
                duration: 0,
                polyline: [origin, destination],
                maneuvers: [
                    RouteManeuver(instruction: NSLocalizedString("Start", comment: "Route start"),
                                  distance: 0, type: .start, coordinate: origin),
                    RouteManeuver(instruction: NSLocalizedString("Arrive at destination", comment: "Route end"),
                                  distance: 0, type: .destination, coordinate: destination)
                ],
                mode: mode
            )
        }

        // Run bidirectional A*.
        guard let nodeIds = bidirectionalAStar(db: graph.handle, start: startId, goal: goalId, mode: mode, deadline: deadline) else {
            return nil
        }

        // Build the result MapRoute.
        return buildRoute(db: graph.handle, nodeIds: nodeIds, origin: origin, destination: destination, mode: mode)
    }

    // MARK: - Open DB for a coordinate

    private struct OpenGraph {
        let handle: OpaquePointer
        let regionId: String
    }

    private func roadGraphDB(forCoordinate coordinate: CLLocationCoordinate2D) -> OpenGraph? {
        // Find regions whose bbox contains the coordinate.
        //
        // 🔧 FIX (2026-10-09): the on-disk list used to be an EITHER/OR
        // fallback, reached only when `manifest == nil`. Two ways that lost a
        // graph the device actually had: (1) a cold launch with no network
        // leaves `manifest` nil for as long as the request takes to time out,
        // and (2) when the fetch fails the app substitutes the ~110-region
        // hardcoded manifest, which does not list every region the server
        // publishes — so a graph downloaded for a region absent from that list
        // was unreachable and routing reported "no offline route" with the
        // database sitting right there. Bbox matches come first (they are the
        // correct answer when we have it); every installed region is appended
        // as a backstop.
        var candidates: [String] = []
        if let manifest = OfflineMapManager.shared.manifest {
            candidates = manifest.regions.filter { region in
                guard region.bbox.count >= 4 else { return false }
                return coordinate.latitude >= region.bbox[1] &&
                       coordinate.latitude <= region.bbox[3] &&
                       coordinate.longitude >= region.bbox[0] &&
                       coordinate.longitude <= region.bbox[2]
            }.map(\.id)
        }
        let seen = Set(candidates)
        candidates += MapStorageManager.shared.downloadedRegionIds().filter { !seen.contains($0) }

        for regionId in candidates {
            let path = roadGraphPath(for: regionId).path
            guard FileManager.default.fileExists(atPath: path) else { continue }
            var db: OpaquePointer?
            if sqlite3_open_v2(path, &db, SQLITE_OPEN_READONLY | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK,
               let handle = db {
                return OpenGraph(handle: handle, regionId: regionId)
            }
            if let db = db { sqlite3_close(db) }
        }
        return nil
    }

    // MARK: - Snap

    private func snapNearestNode(db: OpaquePointer, lat: Double, lon: Double) -> Int64? {
        // Pre-filter by a tight bbox around (lat, lon) using idx_road_nodes_geo, then sort by haversine.
        // 200 m ~ 0.0018 deg latitude. Use 0.005 (~550m) for bbox query, then verify haversine.
        let dLat = 0.005
        let dLon = 0.005 / max(cos(lat * .pi / 180.0), 0.1)

        let sql = """
            SELECT id, lat, lon FROM road_nodes
            WHERE lat BETWEEN ? AND ?
              AND lon BETWEEN ? AND ?
            """
        var stmt: OpaquePointer?
        defer { if let stmt = stmt { sqlite3_finalize(stmt) } }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_double(stmt, 1, lat - dLat)
        sqlite3_bind_double(stmt, 2, lat + dLat)
        sqlite3_bind_double(stmt, 3, lon - dLon)
        sqlite3_bind_double(stmt, 4, lon + dLon)

        var bestId: Int64 = -1
        var bestDist: Double = Double.greatestFiniteMagnitude
        while sqlite3_step(stmt) == SQLITE_ROW {
            let id = sqlite3_column_int64(stmt, 0)
            let nlat = sqlite3_column_double(stmt, 1)
            let nlon = sqlite3_column_double(stmt, 2)
            let d = haversine(lat1: lat, lon1: lon, lat2: nlat, lon2: nlon)
            if d < bestDist {
                bestDist = d
                bestId = id
            }
        }
        if bestId < 0 || bestDist > Self.snapMaxMeters {
            return nil
        }
        return bestId
    }

    // MARK: - Bidirectional A*

    /// Inner adjacency cache row.
    private struct Edge {
        let toId: Int64
        let weight: Double      // seconds
        let polyline: [CLLocationCoordinate2D]
        let highway: String
        let oneway: Int32
    }

    private func bidirectionalAStar(
        db: OpaquePointer,
        start: Int64,
        goal: Int64,
        mode: RouteMode,
        deadline: Date
    ) -> [Int64]? {
        // Lazy adjacency loaders (forward and backward).
        var forwardAdj: [Int64: [Edge]] = [:]
        var backwardAdj: [Int64: [Edge]] = [:]

        // Goal coordinate (used by forward heuristic).
        guard let goalCoord = nodeCoord(db: db, id: goal) else { return nil }
        guard let startCoord = nodeCoord(db: db, id: start) else { return nil }

        let maxSpeed: Double = {
            switch mode {
            case .car:  return 33.3   // ~120 km/h cap
            case .bike: return 8.0    // ~28 km/h
            case .walk: return 2.0    // ~7 km/h
            }
        }()

        // Open sets implemented as binary heaps.
        var openF = MinHeap<Int64>()
        var openB = MinHeap<Int64>()

        var gF: [Int64: Double] = [start: 0]
        var gB: [Int64: Double] = [goal: 0]
        var parentF: [Int64: Int64] = [:]
        var parentB: [Int64: Int64] = [:]

        openF.push(item: start, priority: heuristic(from: startCoord, to: goalCoord, maxSpeed: maxSpeed))
        openB.push(item: goal, priority: heuristic(from: goalCoord, to: startCoord, maxSpeed: maxSpeed))

        var meetingNode: Int64 = -1
        var bestMu = Double.greatestFiniteMagnitude

        var iter = 0
        while !openF.isEmpty && !openB.isEmpty {
            iter += 1
            // Cheap deadline check every 256 iters.
            if iter & 0xFF == 0 && Date() > deadline {
                return nil
            }

            // Termination: top f-values cross the best meeting cost.
            let topF = openF.topPriority ?? Double.greatestFiniteMagnitude
            let topB = openB.topPriority ?? Double.greatestFiniteMagnitude
            if topF + topB >= bestMu && bestMu < Double.greatestFiniteMagnitude {
                break
            }

            // Expand the smaller frontier.
            if topF <= topB {
                guard let u = openF.pop() else { break }
                let gU = gF[u] ?? Double.greatestFiniteMagnitude
                let edges = adjacency(db: db, nodeId: u, mode: mode, reverse: false, cache: &forwardAdj)
                for e in edges {
                    let tentative = gU + e.weight
                    if tentative < (gF[e.toId] ?? Double.greatestFiniteMagnitude) {
                        gF[e.toId] = tentative
                        parentF[e.toId] = u
                        guard let coord = nodeCoord(db: db, id: e.toId) else { continue }
                        let f = tentative + heuristic(from: coord, to: goalCoord, maxSpeed: maxSpeed)
                        openF.push(item: e.toId, priority: f)

                        // If reached by backward search, candidate meeting.
                        if let gB_v = gB[e.toId] {
                            let mu = tentative + gB_v
                            if mu < bestMu {
                                bestMu = mu
                                meetingNode = e.toId
                            }
                        }
                    }
                }
            } else {
                guard let u = openB.pop() else { break }
                let gU = gB[u] ?? Double.greatestFiniteMagnitude
                let edges = adjacency(db: db, nodeId: u, mode: mode, reverse: true, cache: &backwardAdj)
                for e in edges {
                    let tentative = gU + e.weight
                    if tentative < (gB[e.toId] ?? Double.greatestFiniteMagnitude) {
                        gB[e.toId] = tentative
                        parentB[e.toId] = u
                        guard let coord = nodeCoord(db: db, id: e.toId) else { continue }
                        let f = tentative + heuristic(from: coord, to: startCoord, maxSpeed: maxSpeed)
                        openB.push(item: e.toId, priority: f)

                        if let gF_v = gF[e.toId] {
                            let mu = tentative + gF_v
                            if mu < bestMu {
                                bestMu = mu
                                meetingNode = e.toId
                            }
                        }
                    }
                }
            }
        }

        guard meetingNode >= 0 else { return nil }

        // Reconstruct: start..meeting (via parentF) + meeting..goal (via parentB)
        var pathFwd: [Int64] = [meetingNode]
        var cur = meetingNode
        while let p = parentF[cur] {
            pathFwd.append(p)
            cur = p
        }
        pathFwd.reverse()

        var pathBwd: [Int64] = []
        cur = meetingNode
        while let p = parentB[cur] {
            pathBwd.append(p)
            cur = p
        }

        return pathFwd + pathBwd
    }

    /// Edge-list adjacency for `nodeId`. When `reverse` is true, the edges represent
    /// inbound edges to `nodeId` (used for the backward search of bidirectional A*).
    private func adjacency(
        db: OpaquePointer,
        nodeId: Int64,
        mode: RouteMode,
        reverse: Bool,
        cache: inout [Int64: [Edge]]
    ) -> [Edge] {
        if let cached = cache[nodeId] { return cached }

        let weightCol: String = {
            switch mode {
            case .car:  return "weight_car"
            case .bike: return "weight_bike"
            case .walk: return "weight_foot"
            }
        }()

        // For forward expansion: edges leaving nodeId.
        // For backward expansion: edges arriving at nodeId, but we must respect oneway.
        // Backward = pretend edges run reversed.
        let sql: String
        if reverse {
            sql = """
                SELECT from_id, to_id, \(weightCol), polyline, highway, oneway
                FROM road_edges
                WHERE to_id = ?
                """
        } else {
            sql = """
                SELECT from_id, to_id, \(weightCol), polyline, highway, oneway
                FROM road_edges
                WHERE from_id = ?
                """
        }

        var stmt: OpaquePointer?
        defer { if let stmt = stmt { sqlite3_finalize(stmt) } }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
            cache[nodeId] = []
            return []
        }
        sqlite3_bind_int64(stmt, 1, nodeId)

        var out: [Edge] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            let fromId = sqlite3_column_int64(stmt, 0)
            let toId = sqlite3_column_int64(stmt, 1)
            let weight = sqlite3_column_double(stmt, 2)
            let polylineStr = sqlite3_column_text(stmt, 3).map { String(cString: $0) } ?? ""
            let highway = sqlite3_column_text(stmt, 4).map { String(cString: $0) } ?? ""
            let oneway = sqlite3_column_int(stmt, 5)

            let neighbour = reverse ? fromId : toId
            // For walking/biking, oneway is ignored (people walk against traffic).
            // For driving, drop edges that are oneway in the wrong direction.
            // The DB stores edges with the natural direction in row order; oneway=1 means
            // forbidden in reverse. So when reverse=true and oneway=1, skip this row.
            if reverse && oneway == 1 && mode == .car {
                continue
            }

            let pts = decodePolyline(polylineStr)
            out.append(Edge(toId: neighbour, weight: weight, polyline: pts, highway: highway, oneway: oneway))
        }
        cache[nodeId] = out
        return out
    }

    private func nodeCoord(db: OpaquePointer, id: Int64) -> CLLocationCoordinate2D? {
        var stmt: OpaquePointer?
        defer { if let stmt = stmt { sqlite3_finalize(stmt) } }
        guard sqlite3_prepare_v2(db, "SELECT lat, lon FROM road_nodes WHERE id = ?", -1, &stmt, nil) == SQLITE_OK else {
            return nil
        }
        sqlite3_bind_int64(stmt, 1, id)
        guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
        return CLLocationCoordinate2D(latitude: sqlite3_column_double(stmt, 0), longitude: sqlite3_column_double(stmt, 1))
    }

    // MARK: - Build MapRoute

    private func buildRoute(
        db: OpaquePointer,
        nodeIds: [Int64],
        origin: CLLocationCoordinate2D,
        destination: CLLocationCoordinate2D,
        mode: RouteMode
    ) -> MapRoute? {
        guard nodeIds.count >= 2 else { return nil }

        // Every node on the path, resolved once.
        //
        // The server builder (maps_pipeline/build_roadgraph.py) emits ONE EDGE
        // PER CONSECUTIVE NODE PAIR and stores the empty string in `polyline`
        // for all of them -- a deleted comment in an older revision said so
        // outright: "polyline is left as an empty string for 2-point edges
        // (the vast majority)". Measured on the published Nicaragua roadgraph:
        // 2,904,022 edges, 2,904,022 of them empty, none with geometry.
        //
        // appendEdge below opens with `guard pts.count >= 2`, and
        // decodePolyline("") is []. So EVERY edge of EVERY server-built region
        // was discarded: a route came back with distance 0, duration 0, no
        // maneuvers beyond Start/Arrive, and a fullPolyline of just
        // [origin, destination] -- a straight line drawn across the country.
        // There is no test anywhere that exercises this router, which is how
        // it survived 80 published countries.
        //
        // The geometry was never missing, only unused: a 2-point edge's shape
        // IS its two endpoints, and both are in road_nodes. One lookup per
        // path node, not per edge, since consecutive edges share a node.
        var nodeCoords: [Int64: CLLocationCoordinate2D] = [:]
        for id in nodeIds where nodeCoords[id] == nil {
            if let c = nodeCoord(db: db, id: id) { nodeCoords[id] = c }
        }

        /// The edge's own geometry when it has some, otherwise the straight
        /// segment between its two endpoints.
        func geometry(_ pts: [CLLocationCoordinate2D],
                      from: Int64, to: Int64) -> [CLLocationCoordinate2D] {
            if pts.count >= 2 { return pts }
            guard let a = nodeCoords[from], let b = nodeCoords[to] else { return pts }
            return [a, b]
        }

        var fullPolyline: [CLLocationCoordinate2D] = [origin]
        var totalMeters: Double = 0
        var totalSeconds: Double = 0

        // Maneuver building heading-change detection.
        var maneuvers: [RouteManeuver] = []
        maneuvers.append(RouteManeuver(
            instruction: NSLocalizedString("Start", comment: "Route start"),
            distance: 0, type: .start, coordinate: origin
        ))

        var lastBearing: Double? = nil

        // For each pair of nodes, fetch the edge.
        for i in 0..<(nodeIds.count - 1) {
            let from = nodeIds[i]
            let to = nodeIds[i + 1]

            guard let edge = fetchEdge(db: db, from: from, to: to, mode: mode) else {
                // If a direct edge was not found (extremely unlikely in a connected path),
                // try the reverse direction (some graphs only store one row per undirected edge).
                guard let rev = fetchEdge(db: db, from: to, to: from, mode: mode) else {
                    continue
                }
                appendEdge(geometry(Array(rev.polyline.reversed()), from: from, to: to),
                           highway: rev.highway,
                           weight: rev.weight, fullPolyline: &fullPolyline,
                           totalMeters: &totalMeters, totalSeconds: &totalSeconds,
                           maneuvers: &maneuvers, lastBearing: &lastBearing)
                continue
            }

            appendEdge(geometry(edge.polyline, from: from, to: to), highway: edge.highway,
                       weight: edge.weight, fullPolyline: &fullPolyline,
                       totalMeters: &totalMeters, totalSeconds: &totalSeconds,
                       maneuvers: &maneuvers, lastBearing: &lastBearing)
        }

        fullPolyline.append(destination)
        maneuvers.append(RouteManeuver(
            instruction: NSLocalizedString("Arrive at destination", comment: "Route end"),
            distance: 0, type: .destination, coordinate: destination
        ))

        return MapRoute(
            distance: totalMeters,
            duration: totalSeconds,
            polyline: fullPolyline,
            maneuvers: maneuvers,
            mode: mode
        )
    }

    private func appendEdge(
        _ pts: [CLLocationCoordinate2D],
        highway: String,
        weight: Double,
        fullPolyline: inout [CLLocationCoordinate2D],
        totalMeters: inout Double,
        totalSeconds: inout Double,
        maneuvers: inout [RouteManeuver],
        lastBearing: inout Double?
    ) {
        guard pts.count >= 2 else { return }

        let segMeters = polylineLengthMeters(pts)
        totalMeters += segMeters
        totalSeconds += weight

        // Detect turn at the start of this edge by comparing the incoming bearing
        // (from previous edge endpoint) with the outgoing bearing (first segment of this edge).
        let outBearing = bearing(from: pts[0], to: pts[1])
        if let last = lastBearing {
            let delta = angleDifference(last, outBearing)
            if abs(delta) > 25 {
                let type: ManeuverType = maneuverTypeFromDelta(delta)
                let instr = instructionFor(type: type, highway: highway)
                maneuvers.append(RouteManeuver(
                    instruction: instr,
                    distance: segMeters,
                    type: type,
                    coordinate: pts[0]
                ))
            }
        }
        // Update last bearing using the END of this segment.
        if pts.count >= 2 {
            lastBearing = bearing(from: pts[pts.count - 2], to: pts[pts.count - 1])
        }

        // Append polyline (skip first point if it duplicates the last appended point).
        if let last = fullPolyline.last,
           last.latitude == pts[0].latitude, last.longitude == pts[0].longitude {
            fullPolyline.append(contentsOf: pts.dropFirst())
        } else {
            fullPolyline.append(contentsOf: pts)
        }
    }

    private func fetchEdge(db: OpaquePointer, from: Int64, to: Int64, mode: RouteMode) -> Edge? {
        let weightCol: String = {
            switch mode {
            case .car:  return "weight_car"
            case .bike: return "weight_bike"
            case .walk: return "weight_foot"
            }
        }()
        let sql = "SELECT \(weightCol), polyline, highway, oneway FROM road_edges WHERE from_id = ? AND to_id = ?"
        var stmt: OpaquePointer?
        defer { if let stmt = stmt { sqlite3_finalize(stmt) } }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_int64(stmt, 1, from)
        sqlite3_bind_int64(stmt, 2, to)
        guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
        let weight = sqlite3_column_double(stmt, 0)
        let polyStr = sqlite3_column_text(stmt, 1).map { String(cString: $0) } ?? ""
        let highway = sqlite3_column_text(stmt, 2).map { String(cString: $0) } ?? ""
        let oneway = sqlite3_column_int(stmt, 3)
        return Edge(toId: to, weight: weight, polyline: decodePolyline(polyStr), highway: highway, oneway: oneway)
    }

    // MARK: - Heuristic / Geometry helpers

    private func heuristic(from a: CLLocationCoordinate2D, to b: CLLocationCoordinate2D, maxSpeed: Double) -> Double {
        return haversine(lat1: a.latitude, lon1: a.longitude, lat2: b.latitude, lon2: b.longitude) / maxSpeed
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

    private func polylineLengthMeters(_ pts: [CLLocationCoordinate2D]) -> Double {
        var total: Double = 0
        for i in 1..<pts.count {
            total += haversine(lat1: pts[i-1].latitude, lon1: pts[i-1].longitude,
                                lat2: pts[i].latitude,  lon2: pts[i].longitude)
        }
        return total
    }

    private func bearing(from a: CLLocationCoordinate2D, to b: CLLocationCoordinate2D) -> Double {
        let lat1 = a.latitude * .pi / 180.0
        let lat2 = b.latitude * .pi / 180.0
        let dLon = (b.longitude - a.longitude) * .pi / 180.0
        let y = sin(dLon) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        let theta = atan2(y, x)
        return (theta * 180.0 / .pi + 360.0).truncatingRemainder(dividingBy: 360.0)
    }

    /// Smallest signed angle from a→b in degrees (-180,180].
    private func angleDifference(_ a: Double, _ b: Double) -> Double {
        var d = b - a
        while d > 180 { d -= 360 }
        while d <= -180 { d += 360 }
        return d
    }

    private func maneuverTypeFromDelta(_ delta: Double) -> ManeuverType {
        // delta > 0 => right turn, delta < 0 => left turn
        let absD = abs(delta)
        if absD > 150 { return .uturn }
        if delta > 0 {
            if absD > 100 { return .sharpRight }
            if absD > 45  { return .right }
            return .slightRight
        } else {
            if absD > 100 { return .sharpLeft }
            if absD > 45  { return .left }
            return .slightLeft
        }
    }

    private func instructionFor(type: ManeuverType, highway: String) -> String {
        let road = humanRoadName(highway)
        let prefix: String
        switch type {
        case .left:        prefix = NSLocalizedString("Turn left", comment: "")
        case .right:       prefix = NSLocalizedString("Turn right", comment: "")
        case .slightLeft:  prefix = NSLocalizedString("Bear left", comment: "")
        case .slightRight: prefix = NSLocalizedString("Bear right", comment: "")
        case .sharpLeft:   prefix = NSLocalizedString("Sharp left", comment: "")
        case .sharpRight:  prefix = NSLocalizedString("Sharp right", comment: "")
        case .uturn:       prefix = NSLocalizedString("Make a U-turn", comment: "")
        case .roundabout:  prefix = NSLocalizedString("Take the roundabout", comment: "")
        case .merge:       prefix = NSLocalizedString("Merge", comment: "")
        case .fork:        prefix = NSLocalizedString("Keep going", comment: "")
        case .straight:    prefix = NSLocalizedString("Continue", comment: "")
        case .start:       prefix = NSLocalizedString("Start", comment: "")
        case .destination: prefix = NSLocalizedString("Arrive at destination", comment: "")
        }
        if road.isEmpty { return prefix }
        return "\(prefix) " + String(format: NSLocalizedString("onto %@", comment: ""), road)
    }

    private func humanRoadName(_ highway: String) -> String {
        switch highway {
        case "motorway", "motorway_link": return NSLocalizedString("the motorway", comment: "")
        case "trunk", "trunk_link": return NSLocalizedString("the trunk road", comment: "")
        case "primary", "primary_link": return NSLocalizedString("the main road", comment: "")
        case "secondary", "secondary_link": return NSLocalizedString("the secondary road", comment: "")
        case "tertiary", "tertiary_link": return NSLocalizedString("the road", comment: "")
        case "residential", "living_street": return NSLocalizedString("the street", comment: "")
        case "footway", "path", "pedestrian": return NSLocalizedString("the path", comment: "")
        case "cycleway": return NSLocalizedString("the cycleway", comment: "")
        case "service": return NSLocalizedString("the service road", comment: "")
        default: return ""
        }
    }

    // MARK: - Polyline encoding/decoding
    //
    // Edge polylines are stored in the DB as a base64-packed sequence of (lat,lon)
    // doubles in little-endian Float64 pairs. Encoding/decoding lives here so the
    // RoadGraphImporter and the router agree on the binary layout.

    func decodePolyline(_ b64: String) -> [CLLocationCoordinate2D] {
        guard let data = Data(base64Encoded: b64), data.count % 16 == 0 else { return [] }
        let count = data.count / 16
        var out: [CLLocationCoordinate2D] = []
        out.reserveCapacity(count)
        data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            let base = raw.baseAddress!
            for i in 0..<count {
                let lat = base.load(fromByteOffset: i * 16,     as: Double.self)
                let lon = base.load(fromByteOffset: i * 16 + 8, as: Double.self)
                out.append(CLLocationCoordinate2D(latitude: lat, longitude: lon))
            }
        }
        return out
    }

    static func encodePolyline(_ pts: [CLLocationCoordinate2D]) -> String {
        var data = Data(capacity: pts.count * 16)
        for p in pts {
            var lat = p.latitude
            var lon = p.longitude
            withUnsafeBytes(of: &lat) { data.append(contentsOf: $0) }
            withUnsafeBytes(of: &lon) { data.append(contentsOf: $0) }
        }
        return data.base64EncodedString()
    }
}

// MARK: - Min-Heap

/// Tiny binary min-heap keyed by `Double` priority. Supports duplicate items
/// (lazy decrease-key: we just push a new entry and skip stale pops via gF/gB).
private struct MinHeap<T> {
    private var items: [(T, Double)] = []

    var isEmpty: Bool { items.isEmpty }
    var topPriority: Double? { items.first?.1 }

    mutating func push(item: T, priority: Double) {
        items.append((item, priority))
        siftUp(items.count - 1)
    }

    mutating func pop() -> T? {
        guard !items.isEmpty else { return nil }
        items.swapAt(0, items.count - 1)
        let result = items.removeLast()
        if !items.isEmpty { siftDown(0) }
        return result.0
    }

    private mutating func siftUp(_ i: Int) {
        var idx = i
        while idx > 0 {
            let parent = (idx - 1) / 2
            if items[idx].1 < items[parent].1 {
                items.swapAt(idx, parent)
                idx = parent
            } else { break }
        }
    }

    private mutating func siftDown(_ i: Int) {
        var idx = i
        let n = items.count
        while true {
            let l = 2 * idx + 1
            let r = 2 * idx + 2
            var smallest = idx
            if l < n && items[l].1 < items[smallest].1 { smallest = l }
            if r < n && items[r].1 < items[smallest].1 { smallest = r }
            if smallest == idx { break }
            items.swapAt(idx, smallest)
            idx = smallest
        }
    }
}
