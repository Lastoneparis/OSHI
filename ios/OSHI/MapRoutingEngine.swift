//
//  MapRoutingEngine.swift
//  OSHI
//
//  Offline-first routing engine.
//  Currently uses Apple MKDirections as an online fallback.
//  Architecture ready for Valhalla offline graph integration.
//

import Foundation
import MapKit
import CoreLocation

final class MapRoutingEngine {
    static let shared = MapRoutingEngine()

    // MARK: - Offline Routing State

    /// Directory where downloaded routing graphs are stored per region.
    private let routingDataDirectory: URL = {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent("Maps/routing", isDirectory: true)
    }()

    private let offlineRoutingManager = OfflineRoutingManager.shared

    private init() {
        try? FileManager.default.createDirectory(at: routingDataDirectory, withIntermediateDirectories: true)
    }

    // MARK: - Public API

    /// Calculate a route between two coordinates.
    ///
    /// Priority:
    /// 1. TRUE offline routing via OfflineRouter (A* on per-region road_graph.db).
    /// 2. Online MKDirections (only when "Offline only" is OFF and network is available).
    /// 3. Straight-line fallback (only when "Offline only" is OFF — otherwise we
    ///    surface nil and let the UI display "Region not downloaded for routing").
    ///
    /// Reads the user-visible "Offline only" toggle from UserDefaults
    /// (`oshi.map.offlineOnly`). When ON, no network call is made for routing.
    func calculateRoute(
        from origin: CLLocationCoordinate2D,
        to destination: CLLocationCoordinate2D,
        mode: RouteMode
    ) async -> MapRoute? {
        let offlineOnly = UserDefaults.standard.bool(forKey: "oshi.map.offlineOnly")
        let isOffline = !NetworkReachabilityManager.shared.isConnected

        // 1. Try the true offline router first (always — it's free when graphs exist).
        if let offline = await OfflineRouter.shared.route(from: origin, to: destination, mode: mode) {
            return offline
        }

        // 2. If offline-only is ON, do not fall back to network.
        if offlineOnly {
            return nil
        }

        // 3. Online MKDirections.
        if !isOffline {
            if let onlineRoute = await calculateOnlineRoute(from: origin, to: destination, mode: mode) {
                return onlineRoute
            }
        }

        // 4. No engine could route this. Decline — do not invent a line.
        //
        // What used to be here FABRICATED a route: `calculateStraightLineRoute`
        // returned a two-point great-circle line at a flat 50 km/h whose FIRST
        // MANEUVER read "Offline routing active", so the UI drew it with a live
        // Start Navigation button and told the user offline routing had worked.
        // Android deleted its twin of this on 2026-08-13 (MapViewModel.kt:1706)
        // and left a note there that iOS still did it. This settles that note.
        //
        // Sub-regions (2026-10-14) turned this from a corner case into the
        // COMMON one: someone who downloads Auvergne-Rhone-Alpes and asks for
        // Paris has a good graph for where they stand and none for where they
        // are going. A straight line across 400 km of countryside is not a
        // degraded answer, it is a wrong one. The caller asks
        // `routeUnavailableReason(from:to:)` and shows that instead.
        return nil
    }

    // MARK: - Why routing declined

    /// Why a routing attempt produced nothing. Derived purely from the manifest
    /// and what is downloaded, so the UI can explain a `nil` route without
    /// re-running the search.
    enum RouteUnavailableReason {
        /// Nothing downloaded covers where the route starts.
        case noGraphForOrigin
        /// The origin is covered but the destination falls outside it — the
        /// normal outcome of a cross-region trip on a sub-region download.
        case destinationOutsideGraph
        /// Both ends are covered; no road path was found (disconnected, or the
        /// search hit its deadline).
        case noPathFound

        /// Short title, for an alert or banner.
        var title: String {
            NSLocalizedString("map.routing.unavailable.title",
                              comment: "Turn-by-turn routing isn't available for this area")
        }

        /// Body text, or nil when there is nothing actionable to add.
        ///
        /// `noPathFound` deliberately returns nil: the region IS downloaded, so
        /// telling the user to download it would be wrong advice.
        var body: String? {
            switch self {
            case .noGraphForOrigin, .destinationOutsideGraph:
                return NSLocalizedString("map.routing.unavailable.body",
                                         comment: "Download the offline region containing both points")
            case .noPathFound:
                return nil
            }
        }
    }

    /// Explain why `calculateRoute` returned nil for this pair.
    func routeUnavailableReason(
        from origin: CLLocationCoordinate2D,
        to destination: CLLocationCoordinate2D
    ) -> RouteUnavailableReason {
        if !hasOfflineRouting(for: origin) { return .noGraphForOrigin }
        if !hasOfflineRouting(for: destination) { return .destinationOutsideGraph }
        return .noPathFound
    }

    /// Whether offline routing data covers the given coordinate.
    func hasOfflineRouting(for coordinate: CLLocationCoordinate2D) -> Bool {
        let regions = regionsContaining(coordinate)
        return regions.contains { offlineRoutingManager.isRoutingAvailable(regionId: $0) }
    }

    /// Returns identifiers of downloaded regions whose bounding box contains the coordinate.
    func regionsContaining(_ coordinate: CLLocationCoordinate2D) -> [String] {
        // Check against manifest regions
        guard let manifest = OfflineMapManager.shared.manifest else {
            // Fallback: check the routing directory directly
            return offlineRoutingManager.downloadedRoutingRegions()
        }
        return manifest.regions.filter { region in
            guard region.bbox.count >= 4 else { return false }
            return coordinate.latitude >= region.bbox[1] &&
                   coordinate.latitude <= region.bbox[3] &&
                   coordinate.longitude >= region.bbox[0] &&
                   coordinate.longitude <= region.bbox[2]
        }.map(\.id)
    }

    // MARK: - MKDirections Online Fallback

    private func calculateOnlineRoute(
        from origin: CLLocationCoordinate2D,
        to destination: CLLocationCoordinate2D,
        mode: RouteMode
    ) async -> MapRoute? {
        let request = MKDirections.Request()
        request.source = MKMapItem(placemark: MKPlacemark(coordinate: origin))
        request.destination = MKMapItem(placemark: MKPlacemark(coordinate: destination))
        request.transportType = mode.mkTransportType
        request.requestsAlternateRoutes = false

        let directions = MKDirections(request: request)

        do {
            let response = try await directions.calculate()
            guard let mkRoute = response.routes.first else { return nil }
            return parseRoute(mkRoute, mode: mode)
        } catch {
            OshiLog.maps.info("[MapRoutingEngine] MKDirections error: \(error.localizedDescription)")
            return nil
        }
    }

    /// Convert an MKRoute into the app's MapRoute model.
    private func parseRoute(_ mkRoute: MKRoute, mode: RouteMode) -> MapRoute {
        // Extract polyline coordinates
        let pointCount = mkRoute.polyline.pointCount
        let points = mkRoute.polyline.points()
        var coordinates: [CLLocationCoordinate2D] = []
        coordinates.reserveCapacity(pointCount)
        for i in 0..<pointCount {
            coordinates.append(points[i].coordinate)
        }

        // Convert steps to maneuvers
        var maneuvers: [RouteManeuver] = []
        for (index, step) in mkRoute.steps.enumerated() {
            // Skip steps with empty instructions (MKDirections often returns a blank first step)
            guard !step.instructions.isEmpty else { continue }

            let type = maneuverType(from: step, isFirst: index == 0, isLast: index == mkRoute.steps.count - 1)
            let maneuver = RouteManeuver(
                instruction: step.instructions,
                distance: step.distance,
                type: type,
                coordinate: step.polyline.coordinate
            )
            maneuvers.append(maneuver)
        }

        // Ensure we have a start maneuver
        if maneuvers.isEmpty || maneuvers.first?.type != .start {
            let startManeuver = RouteManeuver(
                instruction: NSLocalizedString("Start", comment: "Route start"),
                distance: maneuvers.first?.distance ?? 0,
                type: .start,
                coordinate: coordinates.first ?? CLLocationCoordinate2D()
            )
            maneuvers.insert(startManeuver, at: 0)
        }

        // Ensure we have a destination maneuver
        if maneuvers.last?.type != .destination {
            let destinationManeuver = RouteManeuver(
                instruction: NSLocalizedString("Arrive at destination", comment: "Route end"),
                distance: 0,
                type: .destination,
                coordinate: coordinates.last ?? CLLocationCoordinate2D()
            )
            maneuvers.append(destinationManeuver)
        }

        return MapRoute(
            distance: mkRoute.distance,
            duration: mkRoute.expectedTravelTime,
            polyline: coordinates,
            maneuvers: maneuvers,
            mode: mode
        )
    }

    /// Infer a ManeuverType from an MKRoute.Step's instruction text.
    private func maneuverType(from step: MKRoute.Step, isFirst: Bool, isLast: Bool) -> ManeuverType {
        if isFirst { return .start }
        if isLast { return .destination }

        let text = step.instructions.lowercased()

        // Roundabout
        if text.contains("roundabout") || text.contains("rond-point") || text.contains("rotonda") {
            return .roundabout
        }

        // U-turn
        if text.contains("u-turn") || text.contains("demi-tour") || text.contains("make a u") {
            return .uturn
        }

        // Merge
        if text.contains("merge") || text.contains("join") {
            return .merge
        }

        // Fork
        if text.contains("fork") || text.contains("keep left") || text.contains("keep right") ||
           text.contains("bifurcation") {
            return .fork
        }

        // Sharp turns
        if text.contains("sharp right") || text.contains("virage serr") && text.contains("droite") {
            return .sharpRight
        }
        if text.contains("sharp left") || text.contains("virage serr") && text.contains("gauche") {
            return .sharpLeft
        }

        // Slight turns
        if text.contains("slight right") || text.contains("bear right") || text.contains("l\u{e9}g\u{e8}rement") && text.contains("droite") {
            return .slightRight
        }
        if text.contains("slight left") || text.contains("bear left") || text.contains("l\u{e9}g\u{e8}rement") && text.contains("gauche") {
            return .slightLeft
        }

        // Standard turns (check after slight/sharp)
        if text.contains("turn right") || text.contains("right on") || text.contains("tournez \u{e0} droite") ||
           text.hasSuffix("right") || text.contains("droite") {
            return .right
        }
        if text.contains("turn left") || text.contains("left on") || text.contains("tournez \u{e0} gauche") ||
           text.hasSuffix("left") || text.contains("gauche") {
            return .left
        }

        // Straight / continue
        if text.contains("continue") || text.contains("straight") || text.contains("head") ||
           text.contains("follow") || text.contains("continuez") || text.contains("tout droit") {
            return .straight
        }

        return .straight
    }

    // MARK: - Offline Routing (delegates to OfflineRouter)

    /// Pure offline route via the SQLite road_graph.db produced by RoadGraphImporter.
    /// Implemented in OfflineRouter.swift (bidirectional A*). Kept here as a
    /// thin wrapper so callers that previously used MapRoutingEngine continue to work.
    private func calculateOfflineRoute(
        from origin: CLLocationCoordinate2D,
        to destination: CLLocationCoordinate2D,
        mode: RouteMode
    ) async -> MapRoute? {
        return await OfflineRouter.shared.route(from: origin, to: destination, mode: mode)
    }

}

// MARK: - RouteMode + MapKit Bridge

private extension RouteMode {
    var mkTransportType: MKDirectionsTransportType {
        switch self {
        case .car:  return .automobile
        case .walk: return .walking
        case .bike: return .walking  // MKDirections has no dedicated cycling type; walking is closer than driving
        }
    }
}

