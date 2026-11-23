//
//  MapNavigationView.swift
//  OSHI
//
//  Turn-by-turn navigation overlay displayed on top of the map
//  during active route guidance.
//

import SwiftUI
import CoreLocation
import Combine

// MARK: - Brand Color

private let brandColor = Theme.brand

// MARK: - Navigation View

struct MapNavigationView: View {
    let route: MapRoute
    @Binding var isNavigating: Bool
    var userLocation: CLLocationCoordinate2D?
    /// Optional callbacks the host (OfflineMapViewModel) wires in. The engine
    /// owns *detection*; recompute / arrival side-effects belong to the host.
    var onOffRoute: (() -> Void)? = nil
    var onArrived: (() -> Void)? = nil

    /// Live navigation engine — owns step tracking, distance-to-next, ETA,
    /// off-route + arrival detection, and voice prompts. We initialize with a
    /// placeholder route and call `engine.update(route:)` from `.onAppear`
    /// when the real route arrives. The previous holder pattern (a wrapper
    /// class around the engine) made Swift route property access through
    /// `@StateObject`'s `Wrapper.subscript(dynamicMember:)` — which returns
    /// `Binding<Subject>` instead of the underlying value, breaking every
    /// `engine.foo` read in the view body. Removing the holder lets
    /// SwiftUI access the engine's `@Published` properties directly.
    @StateObject private var engine: LiveNavigationEngine = LiveNavigationEngine(
        route: MapRoute(distance: 0, duration: 0, polyline: [], maneuvers: [], mode: .car)
    )

    /// Tracks the route signature we last bound to, so re-renders with the
    /// same route don't restart navigation state.
    @State private var lastRouteSignature: Int = 0

    @State private var showManeuverList: Bool = false
    @State private var listDragOffset: CGFloat = 0

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var currentManeuverIndex: Int { engine.currentStepIndex }

    private var currentManeuver: RouteManeuver? {
        guard route.maneuvers.indices.contains(currentManeuverIndex) else { return nil }
        return route.maneuvers[currentManeuverIndex]
    }

    private var nextManeuver: RouteManeuver? {
        let next = currentManeuverIndex + 1
        guard route.maneuvers.indices.contains(next) else { return nil }
        return route.maneuvers[next]
    }

    /// Remaining maneuvers including the current one.
    private var upcomingManeuvers: [RouteManeuver] {
        guard currentManeuverIndex < route.maneuvers.count else { return [] }
        return Array(route.maneuvers[currentManeuverIndex...])
    }

    var body: some View {
        ZStack(alignment: .top) {
            // Dim background only behind the maneuver list when expanded
            if showManeuverList {
                Color.black.opacity(0.3)
                    .ignoresSafeArea()
                    .onTapGesture { withAnimation(.spring(response: 0.35)) { showManeuverList = false } }
            }

            VStack(spacing: 0) {
                // Top instruction banner
                instructionBanner
                Spacer()
                // Bottom bar + expandable list
                bottomSection
            }
        }
        .onAppear { rebindEngine() }
        .onChange(of: route.distance) { _, _ in rebindEngine() }
        .onChange(of: route.maneuvers.count) { _, _ in rebindEngine() }
        .onChange(of: userLocation?.latitude) { _, _ in feedLocation() }
        .onChange(of: userLocation?.longitude) { _, _ in feedLocation() }
    }

    private func feedLocation() {
        guard let coord = userLocation else { return }
        let cl = CLLocation(
            coordinate: coord,
            altitude: 0,
            horizontalAccuracy: 5,
            verticalAccuracy: -1,
            course: -1,
            speed: -1,
            timestamp: Date()
        )
        engine.updateLocation(cl)
    }

    /// Push the current `route` into the engine and refresh the callbacks.
    /// Called from `.onAppear` and whenever the route's identity changes
    /// (different total distance or different maneuver count).
    private func routeSignature(_ r: MapRoute) -> Int {
        var hasher = Hasher()
        hasher.combine(r.maneuvers.count)
        hasher.combine(r.polyline.count)
        hasher.combine(r.distance)
        hasher.combine(r.duration)
        return hasher.finalize()
    }

    private func rebindEngine() {
        let sig = routeSignature(route)
        if sig != lastRouteSignature {
            lastRouteSignature = sig
            engine.update(route: route)
        }
        engine.onOffRoute = onOffRoute
        engine.onArrived = onArrived
    }

    // MARK: - Instruction Banner

    private var instructionBanner: some View {
        // The "next" maneuver from the user's POV is the one *after* the
        // current step (current is treated as completed once we've advanced).
        let targetIndex = min(currentManeuverIndex + 1, max(route.maneuvers.count - 1, 0))
        let bannerManeuver: RouteManeuver? = route.maneuvers.indices.contains(targetIndex)
            ? route.maneuvers[targetIndex] : currentManeuver
        return VStack(spacing: 0) {
            if engine.hasArrived {
                HStack(spacing: 16) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 28, weight: .bold))
                        .foregroundColor(.white)
                        .frame(width: 52, height: 52)
                        .background(Color.green.opacity(0.9))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("map.nav.arrived".localized)
                            .font(.scaled(26, weight: .bold, design: .rounded))
                            .foregroundColor(.white)
                        Text("map.nav.arrived_message".localized)
                            .font(.scaled(15, weight: .medium))
                            .foregroundColor(.white.opacity(0.9))
                    }
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .accessibilityElement(children: .combine)
            } else if engine.isOffRoute {
                HStack(spacing: 16) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 28, weight: .bold))
                        .foregroundColor(.white)
                        .frame(width: 52, height: 52)
                        .background(Color.orange.opacity(0.9))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("map.nav.off_route".localized)
                            .font(.scaled(22, weight: .bold, design: .rounded))
                            .foregroundColor(.white)
                        Text("map.nav.recalculating".localized)
                            .font(.scaled(14, weight: .medium))
                            .foregroundColor(.white.opacity(0.9))
                    }
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .accessibilityElement(children: .combine)
            } else if let maneuver = bannerManeuver {
                HStack(spacing: 16) {
                    // Maneuver icon
                    Image(systemName: maneuver.type.icon)
                        .font(.system(size: 28, weight: .bold))
                        .foregroundColor(.white)
                        .frame(width: 52, height: 52)
                        .background(brandColor.opacity(0.9))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .accessibilityHidden(true)

                    VStack(alignment: .leading, spacing: 4) {
                        // Live distance to next maneuver from the engine.
                        Text(distanceToCurrentManeuver)
                            .font(.scaled(26, weight: .bold, design: .rounded))
                            .foregroundColor(.white)

                        // Instruction
                        Text(maneuver.instruction)
                            .font(.scaled(15, weight: .medium))
                            .foregroundColor(.white.opacity(0.9))
                            .lineLimit(2)
                    }

                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .accessibilityElement(children: .combine)

                // Next maneuver preview (the one *after* the banner maneuver).
                let afterIndex = targetIndex + 1
                if route.maneuvers.indices.contains(afterIndex) {
                    let next = route.maneuvers[afterIndex]
                    HStack(spacing: 10) {
                        Text("map.nav.then".localized)
                            .font(.caption)
                            .foregroundColor(.white.opacity(0.6))
                        Image(systemName: next.type.icon)
                            .font(.scaled(14, weight: .semibold))
                            .foregroundColor(.white.opacity(0.7))
                            .accessibilityHidden(true)
                        Text(next.instruction)
                            .font(.caption)
                            .foregroundColor(.white.opacity(0.7))
                            .lineLimit(1)
                        Spacer()
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 10)
                    .padding(.top, 2)
                    .accessibilityElement(children: .combine)
                }
            }
        }
        .frame(maxWidth: 700)
        .frame(maxWidth: .infinity)
        .background(
            LinearGradient(
                colors: [Color.black.opacity(0.92), Color.black.opacity(0.82)],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }

    // MARK: - Bottom Section

    private var bottomSection: some View {
        VStack(spacing: 0) {
            if showManeuverList {
                maneuverListView
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
            routeSummaryBar
        }
    }

    // MARK: - Route Summary Bar

    private var routeSummaryBar: some View {
        HStack(spacing: 0) {
            // Remaining distance
            VStack(alignment: .leading, spacing: 2) {
                Text(remainingDistance)
                    .font(.scaled(20, weight: .bold, design: .rounded))
                    .foregroundColor(.white)
                Text(estimatedArrival)
                    .font(.scaled(13, weight: .medium))
                    .foregroundColor(.white.opacity(0.7))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)

            // Remaining time
            VStack(spacing: 2) {
                Text(remainingTime)
                    .font(.scaled(20, weight: .bold, design: .rounded))
                    .foregroundColor(.white)
                Text("map.nav.remaining".localized)
                    .font(.scaled(11))
                    .foregroundColor(.white.opacity(0.6))
            }
            .accessibilityElement(children: .combine)

            Spacer()

            // Expand maneuver list
            Button {
                withAnimation(.spring(response: 0.35)) { showManeuverList.toggle() }
            } label: {
                Image(systemName: showManeuverList ? "chevron.down.circle.fill" : "list.bullet.circle.fill")
                    .font(.system(size: 28))
                    .foregroundColor(.white.opacity(0.8))
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .padding(.trailing, 12)
            .accessibilityLabel("map.nav.steps".localized)

            // End navigation
            Button {
                #if !targetEnvironment(macCatalyst)
                UIImpactFeedbackGenerator(style: .rigid).impactOccurred()
                #endif
                withAnimation { isNavigating = false }
            } label: {
                Text("map.end_navigation".localized)
                    .font(.scaled(16, weight: .bold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 10)
                    .frame(minHeight: 44)
                    .background(Color.red)
                    .clipShape(Capsule())
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: 700)
        .frame(maxWidth: .infinity)
        .background(Color.black.opacity(0.88))
    }

    // MARK: - Maneuver List

    private var maneuverListView: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 0) {
                    // ForEach over the index range avoids the destructured-tuple
                    // closure form, which SwiftUI was matching against its
                    // Binding-list overload (resulting in "Generic parameter C
                    // could not be inferred" + "Cannot convert Array to
                    // Binding<C>" errors).
                    ForEach(route.maneuvers.indices, id: \.self) { index in
                        maneuverRow(route.maneuvers[index], index: index)
                            .id(index)
                    }
                }
            }
            .frame(maxHeight: 320)
            .frame(maxWidth: 700)
            .frame(maxWidth: .infinity)
            .background(Color.black.opacity(0.85))
            .onAppear {
                if reduceMotion {
                    proxy.scrollTo(currentManeuverIndex, anchor: .top)
                } else {
                    withAnimation {
                        proxy.scrollTo(currentManeuverIndex, anchor: .top)
                    }
                }
            }
        }
    }

    private func maneuverRow(_ maneuver: RouteManeuver, index: Int) -> some View {
        let isCurrent = index == currentManeuverIndex
        let isPast = index < currentManeuverIndex

        return HStack(spacing: 14) {
            // Icon
            ZStack {
                Circle()
                    .fill(isCurrent ? brandColor : (isPast ? Color.white.opacity(0.15) : Color.white.opacity(0.1)))
                    .frame(width: 36, height: 36)
                Image(systemName: maneuver.type.icon)
                    .font(.scaled(15, weight: .semibold))
                    .foregroundColor(isCurrent ? .white : (isPast ? .white.opacity(0.4) : .white.opacity(0.7)))
            }
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(maneuver.instruction)
                    .font(.system(size: 14, weight: isCurrent ? .semibold : .regular))
                    .foregroundColor(isPast ? .white.opacity(0.4) : .white)
                    .lineLimit(2)

                if maneuver.distance > 0 {
                    Text(formatDistance(maneuver.distance))
                        .font(.scaled(12))
                        .foregroundColor(isPast ? .white.opacity(0.3) : .white.opacity(0.5))
                }
            }

            Spacer()

            if isCurrent {
                Image(systemName: "location.fill")
                    .font(.scaled(12))
                    .foregroundColor(brandColor)
                    .accessibilityHidden(true)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(isCurrent ? brandColor.opacity(0.12) : Color.clear)
        .accessibilityElement(children: .combine)
    }

    // MARK: - Computed Strings (live from engine)

    /// Live distance from the user to the *next* maneuver, formatted.
    private var distanceToCurrentManeuver: String {
        if let dist = engine.distanceToNextManeuver {
            return formatDistance(dist)
        }
        // Fallback before the first location update arrives.
        if let m = currentManeuver { return formatDistance(m.distance) }
        return "--"
    }

    /// Total remaining distance to destination (engine-projected onto polyline).
    private var remainingDistance: String {
        let live = engine.remainingDistance
        if live > 0 { return formatDistance(live) }
        return route.distanceFormatted
    }

    /// Remaining time projected from engine progress.
    private var remainingTime: String {
        let secs = engine.etaSeconds
        let total = secs > 0 ? secs : route.duration
        let mins = Int(total / 60)
        if mins >= 60 {
            return "\(mins / 60)h \(mins % 60)min"
        }
        return "\(max(1, mins)) min"
    }

    /// Estimated time of arrival, derived from engine ETA.
    private var estimatedArrival: String {
        let secs = engine.etaSeconds > 0 ? engine.etaSeconds : route.duration
        let eta = Date().addingTimeInterval(secs)
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return String(format: NSLocalizedString("map.nav.eta_format", comment: "ETA <hh:mm>"), formatter.string(from: eta))
    }

    // MARK: - Formatting

    private func formatDistance(_ meters: Double) -> String {
        if meters >= 1000 {
            return String(format: "%.1f km", meters / 1000)
        }
        let rounded = Int((meters / 10).rounded()) * 10
        return "\(max(10, rounded)) m"
    }
}

// NavigationEngineHolder removed — the engine is now used directly via
// `@StateObject private var engine: LiveNavigationEngine` and rebound via
// `engine.update(route:)` in the view's `rebindEngine()` helper. The holder
// pattern was forcing Swift to route property access through SwiftUI's
// `ObservedObject.Wrapper` dynamic-member subscript, which returns
// `Binding<…>` instead of the underlying value.

// MARK: - Preview

#if DEBUG
struct MapNavigationView_Previews: PreviewProvider {
    static var previews: some View {
        let sampleRoute = MapRoute(
            distance: 4250,
            duration: 720,
            polyline: [
                CLLocationCoordinate2D(latitude: 48.8566, longitude: 2.3522),
                CLLocationCoordinate2D(latitude: 48.8606, longitude: 2.3376)
            ],
            maneuvers: [
                RouteManeuver(
                    instruction: "Start on Rue de Rivoli",
                    distance: 350,
                    type: .start,
                    coordinate: CLLocationCoordinate2D(latitude: 48.8566, longitude: 2.3522)
                ),
                RouteManeuver(
                    instruction: "Turn right on Boulevard de Sebastopol",
                    distance: 800,
                    type: .right,
                    coordinate: CLLocationCoordinate2D(latitude: 48.8580, longitude: 2.3490)
                ),
                RouteManeuver(
                    instruction: "Continue straight on Rue Reaumur",
                    distance: 1200,
                    type: .straight,
                    coordinate: CLLocationCoordinate2D(latitude: 48.8590, longitude: 2.3450)
                ),
                RouteManeuver(
                    instruction: "Turn left on Rue du Louvre",
                    distance: 600,
                    type: .left,
                    coordinate: CLLocationCoordinate2D(latitude: 48.8600, longitude: 2.3410)
                ),
                RouteManeuver(
                    instruction: "Arrive at destination",
                    distance: 0,
                    type: .destination,
                    coordinate: CLLocationCoordinate2D(latitude: 48.8606, longitude: 2.3376)
                ),
            ],
            mode: .car
        )

        ZStack {
            Color.gray.ignoresSafeArea()
            MapNavigationView(
                route: sampleRoute,
                isNavigating: .constant(true),
                userLocation: CLLocationCoordinate2D(latitude: 48.8570, longitude: 2.3510)
            )
        }
        .preferredColorScheme(.dark)
    }
}
#endif
