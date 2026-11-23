//
//  OfflineMapView.swift
//  OSHI
//
//  Main offline map view (Tab 4). Uses Apple MapKit as temporary renderer
//  until MapLibre Native is integrated. Full UI skeleton with search,
//  POI detail, routing mode selector, and turn-by-turn navigation overlay.
//

import SwiftUI
import MapKit
import CoreLocation
import Combine

// MARK: - Brand Color

private let brandColor = Theme.brand
private let brandUIColor = UIColor(red: 0.337, green: 0.086, blue: 0.925, alpha: 1.0)

// MARK: - Main View

struct OfflineMapView: View {
    @StateObject private var viewModel = OfflineMapViewModel()
    @ObservedObject private var reachability = NetworkReachabilityManager.shared
    @ObservedObject private var locationManager = LocationSharingManager.shared
    /// __LORA_NODES_ON_THE_MAP_2026_10_28__ observed so a position arriving over the
    /// radio REDRAWS the map. `mapAnnotations` is a computed property on the view model,
    /// and the view model does not publish when a different object's `nodes` change — so
    /// without this the pins would appear only when something else happened to trigger a
    /// re-render, which is the kind of "works when you scroll" bug nobody can reproduce.
    @ObservedObject private var meshtastic = MeshtasticManager.shared

    @State private var showDownloadView = false
    /// __ANATOLE_1311_IOS__ (N2) The basemap, chosen from the map itself as
    /// well as from Settings. Observed here so a change re-runs
    /// `MapKitContainerView.updateUIView`, which receives it as `basemap`.
    @AppStorage(MapBackground.preferenceKey) private var mapBackgroundRaw: String = MapBackground.oshi.rawValue
    @State private var showSearchView = false
    @State private var showAIChat = false

    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.oledBlackActive) private var oledBlack

    var presentedModally: Bool = false
    var focusCoordinate: CLLocationCoordinate2D? = nil

    // Contact location sharing — shows contact avatar on map
    var contactName: String? = nil
    var contactPublicKey: String? = nil
    var isLiveLocation: Bool = false
    var liveSessionId: String? = nil
    // When true, this map shows the CURRENT USER's own live share. The shared
    // marker tracks our own live position (we never receive our own update
    // messages), and the system blue user-location dot is hidden so only one
    // live marker is shown instead of a frozen pin + a moving blue dot.
    var isOwnLiveShare: Bool = false

    @State private var contactCoordinate: CLLocationCoordinate2D? = nil
    @State private var liveLocationTimer: Timer? = nil

    var body: some View {
        mapContent
            .onAppear {
                // Request location permission lazily on first Map tab open.
                // This triggers the iOS permission dialog if status is .notDetermined.
                // 📸 Skipped during App Store screenshot capture (-OSHIScreens) so
                // the permission alert doesn't cover the map — offline tiles render
                // fine without a live fix.
                #if DEBUG
                if !ProcessInfo.processInfo.arguments.contains("-OSHIScreens") {
                    locationManager.requestPermission()
                }
                #else
                locationManager.requestPermission()
                #endif
                OfflineMapManager.shared.fetchManifest()
                // Auto-download the region covering the user's location
                OfflineMapManager.shared.autoDownloadCurrentRegion()
                // If a focus coordinate was provided, center on it
                if let coord = focusCoordinate {
                    viewModel.skipAutoCenter = true
                    contactCoordinate = coord
                    viewModel.region = MKCoordinateRegion(
                        center: coord,
                        span: MKCoordinateSpan(latitudeDelta: 0.01, longitudeDelta: 0.01)
                    )
                } else {
                    // Try to center immediately (works if location is already known).
                    // If not yet available, the ViewModel's Combine subscription will
                    // center the map as soon as the first location update arrives.
                    viewModel.centerOnUser()

                    // Start FOREGROUND-ONLY continuous updates so the map can
                    // follow the user as they move while the map view is on
                    // screen. Crucially, `startForegroundLocationUpdates` does
                    // NOT set `allowsBackgroundLocationUpdates = true`, so iOS
                    // pauses updates as soon as the user backgrounds the app.
                    // Paired with `stopForegroundLocationUpdates` in onDisappear
                    // below — never a background location access from the map.
                    locationManager.startForegroundLocationUpdates()

                    // Fallback nudge for first-fix on devices where startUpdating
                    // hasn't fired the delegate yet (typical when permission was
                    // just granted): a one-shot `requestLocation()` delivers the
                    // first fix faster than waiting for the next delegate tick.
                    let status = locationManager.authorizationStatus
                    if (status == .authorizedWhenInUse || status == .authorizedAlways),
                       locationManager.currentLocation == nil {
                        locationManager.getCurrentLocation { _ in }
                    }
                }
                // Start live location updates if this is a live share
                if isLiveLocation, let sessionId = liveSessionId {
                    startLiveLocationUpdates(sessionId: sessionId)
                }
            }
            .onDisappear {
                // Stop foreground updates the moment the map view is no longer
                // visible. If the user tabs away or backgrounds the app, we
                // don't want location calls accumulating against the app.
                locationManager.stopForegroundLocationUpdates()
                liveLocationTimer?.invalidate()
                liveLocationTimer = nil
            }
            // 🔧 FIX (2026-06-03): stop the live-location refresh timer the
            // moment isLiveLocation flips to false. Without this, the parent
            // bubble's re-render (after the message's expiresAt has passed)
            // toggles isLiveLocation false but the 5s timer started in onAppear
            // kept pulling getLatestLiveCoordinate forever — the receiver's pin
            // kept refreshing from the last cached coordinate, appearing as
            // though the sender was still sharing.
            .onChange(of: isLiveLocation) { _, newValue in
                if !newValue {
                    liveLocationTimer?.invalidate()
                    liveLocationTimer = nil
                } else if let sessionId = liveSessionId, liveLocationTimer == nil {
                    // Coming back live (e.g. session extension): restart the
                    // timer if it wasn't running.
                    startLiveLocationUpdates(sessionId: sessionId)
                }
            }
            .onChange(of: viewModel.selectedRouteMode) { _, _ in
                viewModel.recalculateRoute()
            }
            .fullScreenCover(isPresented: $showDownloadView) {
                MapRegionDownloadView()
            }
            .fullScreenCover(isPresented: $showAIChat) {
                OfflineAIChatView()
            }
            // Routing declined — show why. Without this the refusal added in
            // calculateRouteToDestination would be silent, which is worse than
            // the fabricated route it replaced: the user would tap Directions
            // and simply get nothing.
            .alert(
                viewModel.routeError?.title ?? "",
                isPresented: Binding(
                    get: { viewModel.routeError != nil },
                    set: { if !$0 { viewModel.routeError = nil } }
                ),
                presenting: viewModel.routeError
            ) { _ in
                Button(NSLocalizedString("common.ok", comment: "Dismiss the alert")) {
                    viewModel.routeError = nil
                }
            } message: { reason in
                if let body = reason.body { Text(body) }
            }
    }

    // MARK: - Main Content (separated for reliable .fullScreenCover)

    private var mapContent: some View {
        ZStack {
            // Layer 0: Full-screen map
            mapLayer

            // Layer 2: GPS button (bottom-right)
            if !viewModel.isNavigating {
                gpsButton
            }

            // Layer 3: Bottom search bar + category chips (hidden when search overlay is open)
            if !viewModel.isNavigating && viewModel.selectedPOI == nil && viewModel.selectedDestination == nil && !showSearchView {
                bottomSearchOverlay
            }

            // Layer 4: Itinerary panel (when destination chosen or user opens itinerary)
            if (viewModel.selectedDestination != nil || viewModel.showItineraryPanel) && !viewModel.isNavigating {
                routeModeSelector
            }

            // Layer 5: Navigation overlay (when routing active)
            if viewModel.isNavigating {
                navigationOverlay
            }

            // Layer 6: POI detail sheet
            if viewModel.selectedPOI != nil {
                poiDetailSheet
            }

            // __LORA_NODE_TAP_2026_11_05__ Layer 6b: the radio card.
            if let node = viewModel.selectedLoRaNode {
                LoRaNodeMapCard(
                    node: node,
                    myLocation: locationManager.currentLocation?.coordinate,
                    onClose: {
                        withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) {
                            viewModel.selectedLoRaNode = nil
                        }
                    }
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            // Layer 7: Search expanded view
            if showSearchView {
                searchOverlay
            }

            // Layer 8: Top bar — placed last in ZStack = highest z-order.
            // Uses VStack+Spacer for top alignment. Spacer does NOT intercept taps.
            VStack(spacing: 0) {
                topBarContent
                Color.clear
                    .frame(maxHeight: .infinity)
                    .allowsHitTesting(false)
            }
        }
        .ignoresSafeArea(.all, edges: .top)
    }

    private var searchOverlay: some View {
        MapSearchOverlayView(
            isPresented: $showSearchView,
            searchResults: $viewModel.searchResults,
            searchText: $viewModel.searchText,
            selectedCategory: $viewModel.selectedCategory,
            categoryMarkers: $viewModel.categoryMarkers,
            onPOISelected: { poi in
                viewModel.selectPOI(poi)
                showSearchView = false
            },
            onSearchTextChanged: { query in
                viewModel.searchPlaces(query)
            },
            onCategorySelected: { category in
                viewModel.searchByMKCategory(category)
            }
        )
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    // MARK: - Map Layer

    private var mapLayer: some View {
        MapKitContainerView(
            region: $viewModel.region,
            userTrackingMode: $viewModel.userTrackingMode,
            annotations: viewModel.mapAnnotations,
            routePolyline: viewModel.activeRoutePolyline,
            // Hide the system blue dot for our own live share so only the single
            // live shared marker is shown (they'd otherwise overlap as two dots).
            showsUserLocation: !isOwnLiveShare,
            contactAnnotation: contactCoordinate != nil ? ContactAnnotationData(
                coordinate: contactCoordinate!,
                name: contactName ?? NSLocalizedString("map.marker.contact", comment: "Fallback name for contact pin"),
                publicKey: contactPublicKey,
                isLive: isLiveLocation
            ) : nil,
            onAnnotationTapped: { annotationId in
                // __LORA_NODE_TAP_2026_11_05__ A radio's pin was INERT. Node ids are
                // negative (`-(num + 1)`) so they cannot collide with POI row ids, and
                // this handler only ever looked in `searchResults` and
                // `categoryMarkers` — neither of which holds a node. `canShowCallout`
                // is false as well, so tapping a node pin did nothing whatsoever: no
                // name, no signal, no way to reach the person on the other end.
                //
                // Resolved FIRST and by sign, which is cheaper than a lookup and
                // cannot be confused with a POI by construction.
                if annotationId < 0 {
                    let num = UInt32(truncatingIfNeeded: -annotationId - 1)
                    if let node = MeshtasticManager.shared.nodes.first(where: { $0.num == num }) {
                        viewModel.selectPOI(nil)
                        withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                            viewModel.selectedLoRaNode = node
                        }
                    }
                    return
                }
                // Bug 2: also resolve taps on category markers (which can outlive
                // the searchResults list when the search overlay is dismissed).
                let poi = viewModel.searchResults.first(where: { $0.id == annotationId })
                    ?? viewModel.categoryMarkers.first(where: { $0.id == annotationId })
                if let poi = poi {
                    viewModel.selectedLoRaNode = nil
                    viewModel.selectPOI(poi)
                }
            },
            onLongPress: { coordinate in
                // Long press on map: reverse geocode and set as destination
                viewModel.handleLongPress(at: coordinate)
            },
            basemap: MapBackground(rawValue: mapBackgroundRaw) ?? .oshi
        )
        .ignoresSafeArea()
    }

    // MARK: - Live Location Updates

    private func startLiveLocationUpdates(sessionId: String) {
        liveLocationTimer = Timer.scheduledTimer(withTimeInterval: 5.0, repeats: true) { _ in
            // For our OWN live share we never receive our own update messages, so
            // track our live position directly. For a contact's share, follow the
            // latest coordinate fed in from their incoming update messages.
            let updatedCoord = self.isOwnLiveShare
                ? self.locationManager.currentLocation?.coordinate
                : LocationSharingManager.shared.getLatestLiveCoordinate(sessionId: sessionId)
            if let updatedCoord = updatedCoord {
                DispatchQueue.main.async {
                    self.contactCoordinate = updatedCoord
                    // Gently follow the live marker
                    self.viewModel.region = MKCoordinateRegion(
                        center: updatedCoord,
                        span: self.viewModel.region.span
                    )
                }
            }
        }
    }

    // MARK: - Top Bar

    private var topBarContent: some View {
        HStack(spacing: 12) {
            if presentedModally {
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundColor(primaryTextColor)
                        .frame(width: 44, height: 44)
                        .background(overlayBackgroundColor)
                        .clipShape(Circle())
                }
                .accessibilityLabel("common.back".localized)
            }

            Text("map.title".localized)
                .font(.scaled(20, weight: .bold))
                .foregroundColor(primaryTextColor)

            if !reachability.isConnected {
                offlineBadge
            }

            Spacer()

            // AI Chat button
            Button {
                showAIChat = true
            } label: {
                Image(systemName: "brain.filled.head.profile")
                    .font(.system(size: 18))
                    .foregroundColor(.white)
                    .frame(width: 44, height: 44)
                    .background(brandColor)
                    .clipShape(Circle())
            }
            .accessibilityLabel("map.ai_chat".localized)
            .accessibilityHint("map.ai_chat.hint".localized)

            Button {
                showDownloadView = true
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "arrow.down.circle.fill")
                        .font(.scaled(18))
                    Text("map.regions".localized)
                        .font(.scaled(14, weight: .semibold))
                }
                .foregroundColor(.white)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .frame(minHeight: 44)
                .background(brandColor)
                .cornerRadius(20)
            }
            .accessibilityHint("map.regions.hint".localized)
        }
        .frame(maxWidth: 700)
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 16)
        .padding(.top, topSafeAreaInset + 8)
        .padding(.bottom, 10)
        .background(
            LinearGradient(
                colors: [overlayBackgroundColor, overlayBackgroundColor.opacity(0)],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }

    private var offlineBadge: some View {
        HStack(spacing: 4) {
            Circle()
                .fill(Color.orange)
                .frame(width: 6, height: 6)
                .accessibilityHidden(true)
            Text("map.offline".localized)
                .font(.scaled(11, weight: .semibold))
                .foregroundColor(.orange)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Color.orange.opacity(0.15))
        .cornerRadius(10)
        .accessibilityElement(children: .combine)
    }

    // MARK: - Map type (N2)

    /// __ANATOLE_1311_IOS__ (N2) "choisir différents calques sur les cartes".
    /// OSHI's offline OpenStreetMap tiles, Apple's map, or Apple's satellite
    /// imagery — all drawn by MapKit or by OSHI itself, no third-party tile
    /// server. Each option says what it costs (Apple needs the network).
    private var mapLayerMenu: some View {
        Menu {
            Picker("map_layer_title".localized, selection: $mapBackgroundRaw) {
                ForEach(MapBackground.allCases, id: \.rawValue) { option in
                    Label {
                        Text(option.titleKey.localized)
                        Text(option.detailKey.localized)
                    } icon: {
                        Image(systemName: option.symbolName)
                    }
                    .tag(option.rawValue)
                }
            }
        } label: {
            Image(systemName: "square.3.layers.3d")
                .font(.system(size: 19, weight: .medium))
                .foregroundColor(primaryTextColor)
                .frame(width: 48, height: 48)
                .background(overlayBackgroundMaterial)
                .clipShape(Circle())
                .shadow(color: .black.opacity(0.15), radius: 6, y: 3)
        }
        .accessibilityLabel("map_layer_title".localized)
        .accessibilityIdentifier("map_layer_menu")
    }

    // MARK: - GPS Button

    private var gpsButton: some View {
        VStack {
            Spacer()
                .allowsHitTesting(false)
            HStack {
                Spacer()
                    .allowsHitTesting(false)
                VStack(spacing: 12) {
                mapLayerMenu
                Button {
                    #if !targetEnvironment(macCatalyst)
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    #endif
                    viewModel.centerOnUser()
                } label: {
                    Image(systemName: viewModel.userTrackingMode == .follow ? "location.fill" : "location")
                        .font(.system(size: 20, weight: .medium))
                        .foregroundColor(viewModel.userTrackingMode == .follow ? brandColor : primaryTextColor)
                        .frame(width: 48, height: 48)
                        .background(overlayBackgroundMaterial)
                        .clipShape(Circle())
                        .shadow(color: .black.opacity(0.15), radius: 6, y: 3)
                }
                .accessibilityLabel("map.center_on_me".localized)
                .accessibilityHint("map.center_on_me.hint".localized)
                }
            }
            .padding(.trailing, 16)
            .padding(.bottom, bottomSafeAreaInset + 160)
        }
    }

    // MARK: - Bottom Search

    private var bottomSearchOverlay: some View {
        VStack(spacing: 0) {
            Spacer()
                .allowsHitTesting(false)

            VStack(spacing: 12) {
                // Search bar + Itinerary button
                HStack(spacing: 10) {
                    Button {
                        withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                            showSearchView = true
                        }
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: "magnifyingglass")
                                .font(.scaled(16, weight: .medium))
                                .foregroundColor(.secondary)
                            Text("map.search_placeholder".localized)
                                .font(.scaled(15))
                                .foregroundColor(.secondary)
                            Spacer()
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                        .background(searchBarBackground)
                        .cornerRadius(12)
                    }
                    .accessibilityLabel("map.search_placeholder".localized)

                    // Itinerary button
                    Button {
                        withAnimation(.spring(response: 0.3)) {
                            viewModel.showItineraryPanel = true
                        }
                    } label: {
                        Image(systemName: "arrow.triangle.turn.up.right.diamond.fill")
                            .font(.system(size: 18, weight: .medium))
                            .foregroundColor(.white)
                            .frame(width: 44, height: 44)
                            .background(brandColor)
                            .cornerRadius(12)
                    }
                    .accessibilityLabel("map.itinerary.title".localized)
                    .accessibilityHint("map.itinerary.hint".localized)
                }

                // Category quick-filter chips (wrapping grid — 3 rows of 4)
                let columns = Array(repeating: GridItem(.flexible(), spacing: 6), count: 4)
                LazyVGrid(columns: columns, spacing: 6) {
                    ForEach(quickFilterCategories, id: \.id) { category in
                        CompactCategoryChip(
                            category: category,
                            isSelected: viewModel.selectedCategory?.id == category.id,
                            onTap: {
                                viewModel.toggleCategory(category)
                            }
                        )
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.top, 10)
            .padding(.bottom, 8)
            .background(
                overlayBackgroundMaterial
                    .cornerRadius(20, corners: [.topLeft, .topRight])
                    .shadow(color: .black.opacity(0.1), radius: 10, y: -3)
            )
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
        }
    }

    // MARK: - Route Mode Selector

    // MARK: - Itinerary Panel (Origin + Destination + Mode + Go)

    private var routeModeSelector: some View {
      GeometryReader { geo in
        VStack(spacing: 0) {
            Spacer()
                .allowsHitTesting(false)

            ScrollViewReader { proxy in
                ScrollView(.vertical, showsIndicators: false) {
                    VStack(spacing: 12) {
                        // Close button row
                        HStack {
                            Text("map.itinerary.title".localized)
                                .font(.scaled(17, weight: .bold))
                                .foregroundColor(primaryTextColor)
                            Spacer()
                            // Swap origin <-> destination
                            Button { viewModel.swapOriginDestination() } label: {
                                Image(systemName: "arrow.up.arrow.down")
                                    .font(.system(size: 16, weight: .semibold))
                                    .foregroundColor(brandColor)
                                    .frame(width: 44, height: 44)
                                    .background(secondaryBackground)
                                    .clipShape(Circle())
                            }
                            .accessibilityLabel("map.itinerary.swap".localized)
                            Button { viewModel.clearDestination() } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.system(size: 22))
                                    .foregroundColor(.secondary)
                                    .frame(width: 44, height: 44)
                            }
                            .accessibilityLabel("common.close".localized)
                        }

                        // Origin field
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(spacing: 10) {
                                Circle().fill(Color.green).frame(width: 10, height: 10)
                                TextField("map.itinerary.my_location".localized, text: $viewModel.originSearchText)
                                    .font(.scaled(15))
                                    .foregroundColor(primaryTextColor)
                                    .onChange(of: viewModel.originSearchText) { _, newValue in
                                        viewModel.editingField = .origin
                                        viewModel.searchItineraryField(newValue, field: .origin)
                                    }
                                    .submitLabel(.search)
                                    .onSubmit {
                                        if let first = viewModel.originSearchResults.first {
                                            viewModel.selectOrigin(first)
                                        }
                                    }
                                if viewModel.customOrigin != nil {
                                    Button { viewModel.useMyLocationAsOrigin() } label: {
                                        Image(systemName: "location.fill")
                                            .font(.caption)
                                            .foregroundColor(brandColor)
                                            .frame(width: 44, height: 44)
                                    }
                                    .accessibilityLabel("map.itinerary.my_location".localized)
                                }
                            }
                            .padding(10)
                            .background(searchBarBackground)
                            .cornerRadius(10)

                            // Origin search results (live autocomplete)
                            if !viewModel.originSearchResults.isEmpty && viewModel.editingField == .origin {
                                itinerarySearchResults(viewModel.originSearchResults) { poi in
                                    viewModel.selectOrigin(poi)
                                }
                                .id("originResults")
                            }
                        }

                        // Destination field
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(spacing: 10) {
                                Image(systemName: "mappin.circle.fill")
                                    .font(.scaled(12))
                                    .foregroundColor(.red)
                                TextField("map.itinerary.destination".localized, text: $viewModel.destinationSearchText)
                                    .font(.scaled(15))
                                    .foregroundColor(primaryTextColor)
                                    .onChange(of: viewModel.destinationSearchText) { _, newValue in
                                        viewModel.editingField = .destination
                                        viewModel.searchItineraryField(newValue, field: .destination)
                                    }
                                    .submitLabel(.search)
                                    .onSubmit {
                                        if let first = viewModel.destinationSearchResults.first {
                                            viewModel.selectDestinationFromSearch(first)
                                        }
                                    }
                            }
                            .padding(10)
                            .background(searchBarBackground)
                            .cornerRadius(10)

                            // Destination search results (live autocomplete)
                            if !viewModel.destinationSearchResults.isEmpty && viewModel.editingField == .destination {
                                itinerarySearchResults(viewModel.destinationSearchResults) { poi in
                                    viewModel.selectDestinationFromSearch(poi)
                                }
                                .id("destResults")
                            }
                        }

                        // Mode toggle
                        HStack(spacing: 0) {
                            ForEach(RouteMode.allCases, id: \.rawValue) { mode in
                                Button {
                                    viewModel.selectedRouteMode = mode
                                } label: {
                                    VStack(spacing: 3) {
                                        Image(systemName: mode.icon)
                                            .font(.scaled(16, weight: .medium))
                                        Text(mode.label)
                                            .font(.scaled(10, weight: .medium))
                                    }
                                    .foregroundColor(viewModel.selectedRouteMode == mode ? .white : primaryTextColor)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 8)
                                    .background(viewModel.selectedRouteMode == mode ? brandColor : Color.clear)
                                    .cornerRadius(8)
                                }
                            }
                        }
                        .padding(3)
                        .background(secondaryBackground)
                        .cornerRadius(11)

                        // Route info + Go button
                        HStack {
                            if let route = viewModel.activeRoute {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(route.durationFormatted)
                                        .font(.scaled(22, weight: .bold))
                                        .foregroundColor(primaryTextColor)
                                    Text(route.distanceFormatted)
                                        .font(.scaled(14))
                                        .foregroundColor(.secondary)
                                }
                            } else if viewModel.selectedDestination != nil {
                                HStack(spacing: 8) {
                                    ProgressView().tint(brandColor)
                                    Text("map.itinerary.calculating".localized)
                                        .font(.scaled(14))
                                        .foregroundColor(.secondary)
                                }
                            } else {
                                Text("map.itinerary.enter_destination".localized)
                                    .font(.scaled(14))
                                    .foregroundColor(.secondary)
                            }

                            Spacer()

                            if viewModel.activeRoute != nil {
                                Button {
                                    #if !targetEnvironment(macCatalyst)
                                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                    #endif
                                    viewModel.startNavigation()
                                } label: {
                                    HStack(spacing: 6) {
                                        Image(systemName: "arrow.triangle.turn.up.right.diamond.fill")
                                            .font(.scaled(15, weight: .semibold))
                                        Text("map.go".localized)
                                            .font(.scaled(17, weight: .bold))
                                    }
                                    .foregroundColor(.white)
                                    .padding(.horizontal, 24)
                                    .padding(.vertical, 12)
                                    .background(brandColor)
                                    .cornerRadius(12)
                                }
                    }
                }
            }
            .padding(16)
            .padding(.bottom, bottomSafeAreaInset + 100)
            }
            .frame(maxHeight: geo.size.height * 0.55)
            .onChange(of: viewModel.originSearchResults) { _, _ in
                if viewModel.editingField == .origin && !viewModel.originSearchResults.isEmpty {
                    withAnimation { proxy.scrollTo("originResults", anchor: .bottom) }
                }
            }
            .onChange(of: viewModel.destinationSearchResults) { _, _ in
                if viewModel.editingField == .destination && !viewModel.destinationSearchResults.isEmpty {
                    withAnimation { proxy.scrollTo("destResults", anchor: .bottom) }
                }
            }
            }
            .background(
                overlayBackgroundMaterial
                    .cornerRadius(20, corners: [.topLeft, .topRight])
                    .shadow(color: .black.opacity(0.12), radius: 10, y: -4)
            )
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
        }
      }
    }

    /// Search results dropdown for itinerary fields
    private func itinerarySearchResults(_ results: [MapPOI], onSelect: @escaping (MapPOI) -> Void) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(results.prefix(5)) { poi in
                    Button {
                        onSelect(poi)
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: poi.categoryIcon)
                                .font(.system(size: 14))
                                .foregroundColor(brandColor)
                                .frame(width: 24)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(poi.name)
                                    .font(.scaled(14, weight: .medium))
                                    .foregroundColor(primaryTextColor)
                                    .lineLimit(1)
                                if let address = poi.address {
                                    Text(address)
                                        .font(.scaled(12))
                                        .foregroundColor(.secondary)
                                        .lineLimit(1)
                                }
                            }
                            Spacer()
                        }
                        .padding(.vertical, 8)
                        .padding(.horizontal, 12)
                    }
                    Divider().padding(.leading, 46)
                }
            }
        }
        .frame(maxHeight: 200)
        .background(overlayBackgroundColor)
        .cornerRadius(10)
        .shadow(color: .black.opacity(0.1), radius: 4, y: 2)
    }

    // MARK: - Navigation Overlay

    private var navigationOverlay: some View {
        VStack(spacing: 0) {
            // Top maneuver banner
            if let maneuver = viewModel.currentManeuver {
                VStack(spacing: 6) {
                    HStack(spacing: 14) {
                        Image(systemName: maneuver.type.icon)
                            .font(.system(size: 28, weight: .bold))
                            .foregroundColor(.white)
                            .frame(width: 52, height: 52)
                            .background(brandColor)
                            .cornerRadius(12)
                            .accessibilityHidden(true)

                        VStack(alignment: .leading, spacing: 3) {
                            Text(maneuver.instruction)
                                .font(.scaled(17, weight: .semibold))
                                .foregroundColor(primaryTextColor)
                                .lineLimit(2)
                            if maneuver.distance > 0 {
                                Text(formatManeuverDistance(maneuver.distance))
                                    .font(.scaled(14))
                                    .foregroundColor(.secondary)
                            }
                        }

                        Spacer()
                    }
                    .padding(14)
                    .accessibilityElement(children: .combine)
                }
                .background(overlayBackgroundMaterial)
                .cornerRadius(16)
                .shadow(color: .black.opacity(0.12), radius: 8, y: 3)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 12)
                .padding(.top, topSafeAreaInset + 8)
            }

            Spacer()

            // Bottom: route info + end navigation
            HStack {
                if let route = viewModel.activeRoute {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(route.durationFormatted)
                            .font(.scaled(18, weight: .bold))
                            .foregroundColor(primaryTextColor)
                        Text(route.distanceFormatted)
                            .font(.scaled(13))
                            .foregroundColor(.secondary)
                    }
                }

                Spacer()

                Button {
                    #if !targetEnvironment(macCatalyst)
                    UIImpactFeedbackGenerator(style: .rigid).impactOccurred()
                    #endif
                    viewModel.endNavigation()
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "xmark")
                            .font(.scaled(14, weight: .bold))
                            .accessibilityHidden(true)
                        Text("map.end_navigation".localized)
                            .font(.scaled(15, weight: .semibold))
                    }
                    .foregroundColor(.white)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                    .background(Color.red)
                    .cornerRadius(12)
                }
            }
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 16)
            .padding(.top, 16)
            .padding(.bottom, bottomSafeAreaInset + 100)
            .background(
                overlayBackgroundMaterial
                    .cornerRadius(20, corners: [.topLeft, .topRight])
                    .shadow(color: .black.opacity(0.1), radius: 8, y: -3)
            )
        }
        .ignoresSafeArea(.all, edges: [.top])
    }

    // MARK: - POI Detail Sheet

    private var poiDetailSheet: some View {
        VStack(spacing: 0) {
            Spacer()

            if let poi = viewModel.selectedPOI {
                VStack(spacing: 0) {
                    // Drag handle
                    RoundedRectangle(cornerRadius: 2.5)
                        .fill(Color.secondary.opacity(0.3))
                        .frame(width: 36, height: 5)
                        .padding(.top, 10)
                        .padding(.bottom, 14)
                        .accessibilityHidden(true)

                    VStack(alignment: .leading, spacing: 14) {
                        // Header: icon + name + category
                        HStack(spacing: 12) {
                            Image(systemName: poi.categoryIcon)
                                .font(.system(size: 22, weight: .semibold))
                                .foregroundColor(.white)
                                .frame(width: 46, height: 46)
                                .background(brandColor)
                                .cornerRadius(12)
                                .accessibilityHidden(true)

                            VStack(alignment: .leading, spacing: 3) {
                                Text(poi.name)
                                    .font(.scaled(18, weight: .bold))
                                    .foregroundColor(primaryTextColor)
                                    .lineLimit(2)
                                Text(poi.category.capitalized)
                                    .font(.scaled(13))
                                    .foregroundColor(.secondary)
                            }
                            .accessibilityElement(children: .combine)

                            Spacer()

                            Button {
                                viewModel.selectedPOI = nil
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.system(size: 24))
                                    .foregroundColor(.secondary.opacity(0.6))
                                    .frame(width: 44, height: 44)
                            }
                            .accessibilityLabel("common.close".localized)
                        }

                        // Address
                        if let address = poi.address, !address.isEmpty {
                            HStack(spacing: 8) {
                                Image(systemName: "mappin.and.ellipse")
                                    .font(.system(size: 14))
                                    .foregroundColor(.secondary)
                                    .frame(width: 20)
                                    .accessibilityHidden(true)
                                Text(address)
                                    .font(.scaled(14))
                                    .foregroundColor(primaryTextColor)
                            }
                        }

                        // Phone (tappable)
                        if let phone = poi.phone, !phone.isEmpty {
                            Button {
                                callPhone(phone)
                            } label: {
                                HStack(spacing: 8) {
                                    Image(systemName: "phone.fill")
                                        .font(.system(size: 14))
                                        .foregroundColor(brandColor)
                                        .frame(width: 20)
                                        .accessibilityHidden(true)
                                    Text(phone)
                                        .font(.scaled(14))
                                        .foregroundColor(brandColor)
                                }
                            }
                            .accessibilityHint("map.poi.call.hint".localized)
                        }

                        // Website
                        if let website = poi.website, !website.isEmpty {
                            Button {
                                openWebsite(website)
                            } label: {
                                HStack(spacing: 8) {
                                    Image(systemName: "globe")
                                        .font(.system(size: 14))
                                        .foregroundColor(brandColor)
                                        .frame(width: 20)
                                        .accessibilityHidden(true)
                                    Text(website)
                                        .font(.scaled(14))
                                        .foregroundColor(brandColor)
                                        .lineLimit(1)
                                        .truncationMode(.middle)
                                }
                            }
                            .accessibilityHint("map.poi.website.hint".localized)
                        }

                        // Opening hours
                        if let hours = poi.openingHours, !hours.isEmpty {
                            HStack(alignment: .top, spacing: 8) {
                                Image(systemName: "clock")
                                    .font(.system(size: 14))
                                    .foregroundColor(.secondary)
                                    .frame(width: 20)
                                    .accessibilityHidden(true)
                                Text(hours)
                                    .font(.scaled(14))
                                    .foregroundColor(primaryTextColor)
                            }
                        }

                        // Action buttons
                        HStack(spacing: 10) {
                            Button {
                                #if !targetEnvironment(macCatalyst)
                                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                #endif
                                viewModel.navigateToPOI(poi)
                            } label: {
                                HStack(spacing: 6) {
                                    Image(systemName: "arrow.triangle.turn.up.right.diamond.fill")
                                        .font(.scaled(14, weight: .semibold))
                                        .accessibilityHidden(true)
                                    Text("map.navigate".localized)
                                        .font(.scaled(14, weight: .semibold))
                                }
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 13)
                                .background(brandColor)
                                .cornerRadius(12)
                            }

                            Button {
                                #if !targetEnvironment(macCatalyst)
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                #endif
                                viewModel.shareLocation(poi)
                            } label: {
                                HStack(spacing: 6) {
                                    Image(systemName: "paperplane.fill")
                                        .font(.scaled(14, weight: .semibold))
                                    Text("map.share".localized)
                                        .font(.scaled(14, weight: .semibold))
                                }
                                .foregroundColor(brandColor)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 13)
                                .background(brandColor.opacity(0.12))
                                .cornerRadius(12)
                            }
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.bottom, bottomSafeAreaInset + 100)
                }
                .background(overlayBackgroundMaterial)
                .cornerRadius(20, corners: [.topLeft, .topRight])
                .shadow(color: .black.opacity(0.15), radius: 12, y: -5)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .ignoresSafeArea(.all, edges: .bottom)
        .animation(.spring(response: 0.35, dampingFraction: 0.85), value: viewModel.selectedPOI != nil)
    }

    // MARK: - Helpers

    private var quickFilterCategories: [POICategory] {
        // Use compact "chip" labels via dedicated keys (`map.poi.chip.<id>`)
        // so we can keep the chip row tight ("Cafe" not "Cafés", "Market" not
        // "Supermarkets") while the search-grid screen uses the longer
        // `map.poi.<id>` plural labels.
        func chip(_ id: String, _ icon: String) -> POICategory {
            let key = "map.poi.chip.\(id)"
            let localized = NSLocalizedString(key, comment: "POI quick-filter chip label")
            // If the chip key is missing, fall back to the standard map.poi.<id>
            // (handled by POICategory.name).
            if localized == key {
                return POICategory(id: id, icon: icon)
            }
            return POICategory(id: id, name: localized, icon: icon)
        }
        return [
            chip("restaurant", "fork.knife"),
            chip("cafe", "cup.and.saucer.fill"),
            chip("supermarket", "cart.fill"),
            chip("fuel", "fuelpump.fill"),
            chip("pharmacy", "pills.fill"),
            chip("hospital", "cross.case.fill"),
            chip("hotel", "bed.double.fill"),
            chip("atm", "banknote.fill"),
            chip("parking", "p.square.fill"),
            chip("charging_station", "bolt.car.fill"),
            chip("bus_station", "bus.fill"),
            chip("train_station", "tram.fill"),
        ]
    }

    private var primaryTextColor: Color {
        colorScheme == .dark ? .white : .black
    }

    // __OLED_BLACK_2026_11_16__ OLED black: map chrome on black, fields on
    // the system near-black (#1C1C1E) so they still read as fields.
    private var overlayBackgroundColor: Color {
        oledBlack ? .black : colorScheme == .dark ? Color(white: 0.12) : .white
    }

    @ViewBuilder
    private var overlayBackgroundMaterial: some View {
        if oledBlack {
            Color.black.opacity(0.96)
        } else if colorScheme == .dark {
            Color(white: 0.12).opacity(0.96)
        } else {
            Color.white.opacity(0.96)
        }
    }

    private var searchBarBackground: Color {
        oledBlack ? Color(OLEDBlack.incomingBubbleUI) : colorScheme == .dark ? Color(white: 0.2) : Color(white: 0.94)
    }

    private var secondaryBackground: Color {
        oledBlack ? Color(OLEDBlack.incomingBubbleUI) : colorScheme == .dark ? Color(white: 0.18) : Color(white: 0.94)
    }

    private var topSafeAreaInset: CGFloat {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first?
            .windows
            .first(where: { $0.isKeyWindow })?
            .safeAreaInsets.top ?? 47
    }

    private var bottomSafeAreaInset: CGFloat {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first?
            .windows
            .first(where: { $0.isKeyWindow })?
            .safeAreaInsets.bottom ?? 34
    }

    private func formatManeuverDistance(_ meters: Double) -> String {
        if meters >= 1000 {
            let km = String(format: "%.1f km", meters / 1000)
            return String(format: NSLocalizedString("map.nav.in_distance", comment: "In <distance>"), km)
        }
        let m = "\(Int(meters)) m"
        return String(format: NSLocalizedString("map.nav.in_distance", comment: "In <distance>"), m)
    }

    private func callPhone(_ number: String) {
        let cleaned = number.replacingOccurrences(of: " ", with: "")
        if let url = URL(string: "tel://\(cleaned)") {
            UIApplication.shared.open(url)
        }
    }

    private func openWebsite(_ urlString: String) {
        var normalized = urlString
        if !normalized.hasPrefix("http://") && !normalized.hasPrefix("https://") {
            normalized = "https://\(normalized)"
        }
        if let url = URL(string: normalized) {
            UIApplication.shared.open(url)
        }
    }
}

// MARK: - View Model

final class OfflineMapViewModel: ObservableObject {
    /// __THE_MAP_ALWAYS_OPENED_IN_PARIS_2026_11_07__ (Anatole N4: "every time it
    /// opens it is in Paris instead of our last position".)
    ///
    /// This was a bare Paris literal, and nothing ever persisted the region, so
    /// every launch started in Paris and stayed there until a fix arrived.
    /// Indoors, with location denied, or on a slow fix it never moved: the
    /// fallback was not a fallback, it was the default experience. Android had
    /// the same literal and the same omission, fixed in the same commit.
    ///
    /// Paris survives only as the genuinely-first-run value.
    @Published var region = OfflineMapViewModel.lastCenteredRegion()

    private static let centerLatKey = "oshi.map.lastCenterLat"
    private static let centerLonKey = "oshi.map.lastCenterLon"

    static func lastCenteredRegion() -> MKCoordinateRegion {
        let d = UserDefaults.standard
        let lat = d.object(forKey: centerLatKey) as? Double
        let lon = d.object(forKey: centerLonKey) as? Double
        let center = (lat != nil && lon != nil)
            ? CLLocationCoordinate2D(latitude: lat!, longitude: lon!)
            : CLLocationCoordinate2D(latitude: 48.8566, longitude: 2.3522)
        return MKCoordinateRegion(
            center: center,
            span: MKCoordinateSpan(latitudeDelta: 0.05, longitudeDelta: 0.05)
        )
    }

    /// Remember where the map is, so the next launch opens there.
    ///
    /// Only for a REAL fix or a deliberate recentre — never for the Paris seed,
    /// or the fallback would write itself back and become permanent. 0,0 is
    /// refused: Null Island is what a bad decode produces, and persisting it
    /// would move the user's map into the Atlantic for good.
    func rememberCenter(_ c: CLLocationCoordinate2D) {
        guard CLLocationCoordinate2DIsValid(c),
              !(c.latitude == 0 && c.longitude == 0) else { return }
        UserDefaults.standard.set(c.latitude, forKey: Self.centerLatKey)
        UserDefaults.standard.set(c.longitude, forKey: Self.centerLonKey)
    }
    @Published var userTrackingMode: MKUserTrackingMode = .none

    // Search
    @Published var searchText: String = ""
    @Published var selectedCategory: POICategory?
    @Published var searchResults: [MapPOI] = []

    /// Map markers dropped after a category tap or natural-language category
    /// match (e.g. typing "la poste" → POST_OFFICE pins around the user).
    /// Cleared when the user clears the search or switches category.
    /// Capped at 100 entries sorted by distance ascending — see
    /// `setCategoryMarkers(_:near:)`.
    @Published var categoryMarkers: [MapPOI] = []

    // POI selection
    @Published var selectedPOI: MapPOI?

    /// __LORA_NODE_TAP_2026_11_05__ The radio whose card is open, if any.
    ///
    /// A separate property from `selectedPOI` rather than a fake `MapPOI`: a node
    /// has no category, no address and no opening hours, and the POI card is built
    /// out of exactly those. Faking one would put "Antenna · Unknown category" on
    /// screen and give the card a "Directions" button for a thing that moves.
    @Published var selectedLoRaNode: MeshtasticNode?

    // Routing
    @Published var selectedDestination: MapPOI?
    @Published var customOrigin: MapPOI?  // nil = use current location
    @Published var selectedRouteMode: RouteMode = .car
    @Published var activeRoute: MapRoute?
    /// Why the last routing attempt produced nothing, or nil when it succeeded.
    /// Refusing to route is only honest if the refusal is visible.
    @Published var routeError: MapRoutingEngine.RouteUnavailableReason?
    @Published var activeRoutePolyline: [CLLocationCoordinate2D] = []
    @Published var showItineraryPanel: Bool = false
    @Published var originSearchText: String = ""
    @Published var destinationSearchText: String = ""
    @Published var originSearchResults: [MapPOI] = []
    @Published var destinationSearchResults: [MapPOI] = []
    @Published var editingField: ItineraryField = .destination

    enum ItineraryField { case origin, destination }

    // Navigation
    @Published var isNavigating: Bool = false
    @Published var currentManeuver: RouteManeuver?

    // Map annotations.
    // Includes both `searchResults` (current search list) and `categoryMarkers`
    // (Bug 2 — persists after the search overlay is dismissed). De-duplicated
    // by POI id; first occurrence wins.
    var mapAnnotations: [OfflineMapAnnotationItem] {
        var seen = Set<Int64>()
        var items: [OfflineMapAnnotationItem] = []
        for poi in searchResults {
            if seen.insert(poi.id).inserted {
                items.append(OfflineMapAnnotationItem(
                    id: poi.id,
                    coordinate: poi.coordinate,
                    title: poi.name,
                    icon: poi.categoryIcon,
                    isDestination: poi.id == selectedDestination?.id
                ))
            }
        }
        for poi in categoryMarkers {
            if seen.insert(poi.id).inserted {
                items.append(OfflineMapAnnotationItem(
                    id: poi.id,
                    coordinate: poi.coordinate,
                    title: poi.name,
                    icon: poi.categoryIcon,
                    isDestination: poi.id == selectedDestination?.id
                ))
            }
        }

        // __LORA_NODES_ON_THE_MAP_2026_10_28__ every radio we have heard a POSITION
        // from, as a pin — what Meshtastic shows and this map did not.
        //
        // The positions were already arriving: `MeshtasticManager.handlePosition`
        // (POSITION_APP, portnum 3) stores lat/lon on the node and has done since the
        // receive switch learned that portnum. They were visible only in the peers list,
        // as a distance. Nothing put them on the map, so a mesh of radios around you was
        // a list of names and metres instead of a picture.
        //
        // A node with NO fix is skipped rather than drawn at 0,0 — most bare boards (a
        // Heltec V3 has no GPS) never report one, and the Gulf of Guinea is the classic
        // place to find a map full of them.
        //
        // IDs are NEGATIVE. POI ids are positive row ids out of the region database and
        // the tap handler resolves an id against `searchResults` and `categoryMarkers`;
        // a node id that collided with a POI row would open the wrong card. Negative
        // keeps the two spaces disjoint without a second lookup table.
        for node in MeshtasticManager.shared.nodes {
            guard let lat = node.latitude, let lon = node.longitude else { continue }
            guard !(lat == 0 && lon == 0), lat >= -90, lat <= 90, lon >= -180, lon <= 180 else { continue }
            let label = node.longName.isEmpty
                ? (node.shortName.isEmpty ? String(format: "!%08x", node.num) : node.shortName)
                : node.longName
            items.append(OfflineMapAnnotationItem(
                id: -(Int64(node.num) + 1),
                coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon),
                title: label,
                icon: "antenna.radiowaves.left.and.right",
                isDestination: false,
                isLoRaNode: true
            ))
        }
        return items
    }

    private let locationManager = LocationSharingManager.shared
    private var cancellables = Set<AnyCancellable>()

    /// Tracks whether we have already auto-centered on the user's location.
    /// Reset to false each time the Map tab appears so we re-center on each visit.
    var hasCenteredOnUser = false

    /// Set to true when a focusCoordinate was supplied (e.g. viewing a contact's location).
    /// Prevents the auto-center subscription from overriding it.
    var skipAutoCenter = false

    init() {
        // If location is already available (permission previously granted), center immediately.
        centerOnUserIfAvailable()
        // Subscribe to future location updates so we can center the map
        // the moment the first location arrives (e.g. after permission prompt).
        observeLocationUpdates()
        observeLoRaNodes()
    }

    /// __LORA_NODES_WERE_DRAWN_ONCE_2026_11_05__ Redraw when a radio reports where
    /// it is.
    ///
    /// Reported from an iPhone with a radio attached: no nodes on the map at all,
    /// where the Meshtastic app shows them. The pins were being BUILT correctly —
    /// `mapAnnotations` has read `MeshtasticManager.shared.nodes` since
    /// 2026-10-28 — but `mapAnnotations` is a plain computed property on THIS
    /// object, and `nodes` is `@Published` on a different one that this object
    /// never subscribed to. SwiftUI only recomputes it when something republishes
    /// the map's own view model, so a position arriving over the air changed
    /// nothing on screen; a pin appeared only if the user happened to search or
    /// tap something afterwards.
    ///
    /// Android never had this: its map collects the same list as state
    /// (`collectAsState`), which is reactive by construction. That is why the
    /// report was iPhone-only.
    ///
    /// `objectWillChange.send()` rather than mirroring the array into a
    /// `@Published` of our own: `mapAnnotations` already reads the manager
    /// directly, so one copy of the list stays the truth and there is no second
    /// one to fall out of date.
    private func observeLoRaNodes() {
        MeshtasticManager.shared.$nodes
            // Positions arrive in bursts during the handshake; without this the
            // whole annotation set is rebuilt once per node in the dump.
            .removeDuplicates { $0.count == $1.count && $0 == $1 }
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &cancellables)
    }

    /// Observes `LocationSharingManager.currentLocation`. When the first non-nil
    /// location arrives and we haven't centered yet, smoothly move the map there.
    private func observeLocationUpdates() {
        locationManager.$currentLocation
            .compactMap { $0 }          // ignore nil
            .first()                    // only the first real location
            .receive(on: DispatchQueue.main)
            .sink { [weak self] location in
                guard let self = self, !self.hasCenteredOnUser, !self.skipAutoCenter else { return }
                self.hasCenteredOnUser = true
                self.rememberCenter(location.coordinate)
                withAnimation(.easeInOut(duration: 0.4)) {
                    self.region = MKCoordinateRegion(
                        center: location.coordinate,
                        span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)
                    )
                }
            }
            .store(in: &cancellables)
    }

    func centerOnUser() {
        userTrackingMode = .follow
        if let location = locationManager.currentLocation {
            hasCenteredOnUser = true
            rememberCenter(location.coordinate)
            withAnimation(.easeInOut(duration: 0.4)) {
                region = MKCoordinateRegion(
                    center: location.coordinate,
                    span: MKCoordinateSpan(latitudeDelta: 0.01, longitudeDelta: 0.01)
                )
            }
        }
        // If location is not yet available, the Combine subscription in
        // observeLocationUpdates() will center as soon as it arrives.
    }

    /// Clearing helper, so the node tap can close a POI card without knowing how
    /// one is built.
    func selectPOI(_ poi: MapPOI?) {
        guard let poi else { selectedPOI = nil; return }
        selectPOI(poi)
    }

    func selectPOI(_ poi: MapPOI) {
        withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
            selectedPOI = poi
        }
        // Center map on selected POI
        withAnimation(.easeInOut(duration: 0.3)) {
            region = MKCoordinateRegion(
                center: poi.coordinate,
                span: MKCoordinateSpan(latitudeDelta: 0.005, longitudeDelta: 0.005)
            )
        }
    }

    func navigateToPOI(_ poi: MapPOI) {
        selectedPOI = nil
        selectedDestination = poi
        destinationSearchText = poi.name
        showItineraryPanel = true
        calculateRouteToDestination(poi)
    }

    /// Open itinerary panel from search bar
    func openItinerary() {
        showItineraryPanel = true
    }

    /// Search for origin or destination address
    func searchItineraryField(_ query: String, field: ItineraryField) {
        guard !query.trimmingCharacters(in: .whitespaces).isEmpty else {
            if field == .origin { originSearchResults = [] }
            else { destinationSearchResults = [] }
            return
        }
        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = query
        request.region = region
        let search = MKLocalSearch(request: request)
        search.start { [weak self] response, _ in
            guard let self = self, let response = response else { return }
            let results = response.mapItems.enumerated().map { index, item in
                MapPOI(
                    id: Int64(index + 9000),
                    name: item.name ?? query,
                    category: self.categorizeMapItem(item),
                    subcategory: nil,
                    coordinate: item.placemark.coordinate,
                    address: self.formatAddress(item.placemark),
                    phone: nil, website: nil, openingHours: nil
                )
            }
            DispatchQueue.main.async {
                if field == .origin { self.originSearchResults = results }
                else { self.destinationSearchResults = results }
            }
        }
    }

    func selectOrigin(_ poi: MapPOI) {
        customOrigin = poi
        originSearchText = poi.name
        originSearchResults = []
        if selectedDestination != nil { recalculateRoute() }
    }

    func selectDestinationFromSearch(_ poi: MapPOI) {
        selectedDestination = poi
        destinationSearchText = poi.name
        destinationSearchResults = []
        calculateRouteToDestination(poi)
    }

    func useMyLocationAsOrigin() {
        customOrigin = nil
        originSearchText = ""
        originSearchResults = []
        if selectedDestination != nil { recalculateRoute() }
    }

    func swapOriginDestination() {
        let tmpOrigin = customOrigin
        let tmpDest = selectedDestination
        let tmpOriginText = originSearchText
        let tmpDestText = destinationSearchText
        customOrigin = tmpDest
        selectedDestination = tmpOrigin
        originSearchText = tmpDestText
        destinationSearchText = tmpOriginText
        if selectedDestination != nil { recalculateRoute() }
    }

    /// Handle long press on map: drop a pin and set as destination.
    /// Works fully offline — reverse geocoding is attempted but not required.
    func handleLongPress(at coordinate: CLLocationCoordinate2D) {
        let coordName = String(format: "%.4f, %.4f", coordinate.latitude, coordinate.longitude)

        // Create pin immediately (works offline)
        let droppedPin = MapPOI(
            id: Int64(Date().timeIntervalSince1970),
            name: coordName,
            category: "poi",
            subcategory: nil,
            coordinate: coordinate,
            address: nil,
            phone: nil,
            website: nil,
            openingHours: nil
        )
        self.searchResults = [droppedPin]
        self.navigateToPOI(droppedPin)

        // Try to reverse geocode for a better name (non-blocking, optional)
        let geocoder = CLGeocoder()
        let location = CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)
        geocoder.reverseGeocodeLocation(location) { [weak self] placemarks, _ in
            guard let self = self, let placemark = placemarks?.first else { return }
            let name = placemark.name ?? placemark.thoroughfare ?? coordName
            var addressParts: [String] = []
            if let thoroughfare = placemark.thoroughfare { addressParts.append(thoroughfare) }
            if let locality = placemark.locality { addressParts.append(locality) }
            if let postalCode = placemark.postalCode { addressParts.append(postalCode) }
            let address = addressParts.isEmpty ? nil : addressParts.joined(separator: ", ")

            DispatchQueue.main.async {
                let updated = MapPOI(
                    id: droppedPin.id,
                    name: name,
                    category: "poi",
                    subcategory: nil,
                    coordinate: coordinate,
                    address: address,
                    phone: nil,
                    website: nil,
                    openingHours: nil
                )
                self.searchResults = [updated]
                self.selectedDestination = updated
            }
        }
    }

    /// Re-run route calculation after the user changes transport mode.
    func recalculateRoute() {
        guard let dest = selectedDestination else { return }
        calculateRouteToDestination(dest)
    }

    private func calculateRouteToDestination(_ poi: MapPOI) {
        let origin = customOrigin?.coordinate
            ?? locationManager.currentLocation?.coordinate
            ?? CLLocationCoordinate2D(latitude: region.center.latitude, longitude: region.center.longitude)
        let destination = poi.coordinate
        let mode = selectedRouteMode

        // Clear previous route while calculating
        activeRoute = nil

        Task { @MainActor in
            if let route = await MapRoutingEngine.shared.calculateRoute(
                from: origin,
                to: destination,
                mode: mode
            ) {
                withAnimation {
                    activeRoute = route
                    activeRoutePolyline = route.polyline
                }
            } else {
                // No engine could route this. Say so — do not draw a line.
                //
                // `generatePlaceholderRoute` used to run here and FABRICATED a
                // route: origin -> an artificially offset midpoint -> destination,
                // great-circle distance, a flat 30 km/h ETA, and a three-step
                // itinerary ("Head towards X" / "Continue straight" / "Arrive at
                // X") emitted as the same MapRoute a real A* answer produces. The
                // bend in the middle made it look like a road. Nothing marked it
                // as an estimate, and Start Navigation went live on it.
                //
                // Android removed its twin on 2026-08-13 (MapViewModel.kt:1706).
                activeRoute = nil
                activeRoutePolyline = []
                routeError = MapRoutingEngine.shared.routeUnavailableReason(
                    from: origin, to: destination
                )
            }
        }
    }

    func clearDestination() {
        withAnimation {
            selectedDestination = nil
            customOrigin = nil
            activeRoute = nil
            activeRoutePolyline = []
            isNavigating = false
            currentManeuver = nil
            showItineraryPanel = false
            originSearchText = ""
            destinationSearchText = ""
            originSearchResults = []
            destinationSearchResults = []
            categoryMarkers = []
        }
    }

    func startNavigation() {
        guard activeRoute != nil else { return }
        withAnimation {
            isNavigating = true
            if let first = activeRoute?.maneuvers.first {
                currentManeuver = first
            }
        }
    }

    func endNavigation() {
        // Keep the route preview alive (selectedDestination + activeRoute +
        // polyline). Only stop the live nav state. The user lands back on the
        // DRIVE / WALK / BIKE selector with their origin + destination filled
        // and a re-enabled "Start" button — so they can pick a different mode
        // (e.g. switch from drive to walk) and resume without re-entering
        // the address. Tapping the X in the itinerary panel still calls
        // `clearDestination()` for full teardown.
        withAnimation {
            isNavigating = false
            currentManeuver = nil
            // selectedDestination / activeRoute / activeRoutePolyline stay
            // populated so the route preview UI shows again.
            showItineraryPanel = true
        }
    }

    func toggleCategory(_ category: POICategory) {
        if selectedCategory?.id == category.id {
            selectedCategory = nil
            searchResults = []
            categoryMarkers = []
        } else {
            // Hard-clear the previous category's results BEFORE kicking off
            // the new search. Without this, the old pins lingered on the
            // map until the new search returned, and a slow MKLocalSearch
            // (or an offline fallback racing) could leave both the old
            // and new categories visible at the same time — which is the
            // "previous POI still showing" symptom the user reported.
            // The user has to wait one network round-trip before the new
            // category appears alone, but at least the screen never shows
            // a stale category alongside the fresh one.
            selectedCategory = category
            searchResults = []
            categoryMarkers = []
            searchByMKCategory(category)
        }
    }

    // MARK: - MKLocalSearch (Primary Search Engine)

    /// Search for places using Apple's MKLocalSearch API (returns real POI data).
    /// Falls back to offline MapPOIDatabase if no online results.
    ///
    /// When the query matches a known category keyword (multilingual — "la poste",
    /// "hopital", "café"…), we ALSO run a category-filtered MKLocalSearch around
    /// the user and prefer those nearby hits. Fixes Bug 1: "la poste" used to
    /// rank far-away exact-name matches over the post office around the corner.
    func searchPlaces(_ query: String) {
        guard !query.trimmingCharacters(in: .whitespaces).isEmpty else {
            searchResults = []
            categoryMarkers = []
            return
        }

        // Bug 1: route category-keyword queries through the category-biased path.
        if let matchedCategory = Self.categoryFromQuery(query) {
            OshiLog.maps.info("[OfflineMapViewModel] Query '\(query)' → category \(matchedCategory.id) (nearby search)")
            self.selectedCategory = matchedCategory
            searchByMKCategory(matchedCategory)
            return
        }

        // House-number aware offline address search. Triggers when the query
        // starts with a digit (or a French ordinal prefix "bis/ter/quater")
        // followed by a digit. Runs against the offline street index — does
        // not block the MKLocalSearch network call below; results are merged
        // with the address hits pinned to the top.
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        let firstChar = trimmed.first
        let leadingDigit = firstChar.map { $0.isNumber } ?? false
        let leadingOrdinal = trimmed.range(of: #"^(?i)(bis|ter|quater)\s+\d"#,
                                           options: .regularExpression) != nil
        let addressHits: [StreetAddressMatch]
        if leadingDigit || leadingOrdinal {
            let userCoord = locationManager.currentLocation?.coordinate
            addressHits = OfflineStreetDatabase.shared.searchAddress(
                query: query,
                userLat: userCoord?.latitude,
                userLon: userCoord?.longitude,
                limit: 10
            )
        } else {
            addressHits = []
        }

        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = query
        request.region = region

        let search = MKLocalSearch(request: request)
        search.start { [weak self] response, error in
            guard let self = self else { return }

            if let response = response, !response.mapItems.isEmpty {
                DispatchQueue.main.async {
                    let mapped = response.mapItems.map { item -> MapPOI in
                        let name = item.name ?? NSLocalizedString("map.poi.unknown", comment: "Fallback name for unnamed POI")
                        let coord = item.placemark.coordinate
                        return MapPOI(
                            // 🛟 BUG FIX: Stable id derived from (name, coordinate)
                            // instead of `Int64(index)`. Sequential indices collide
                            // across category switches (Restaurant ids 0..9 then
                            // Pharmacy ids 0..4 → MapKit's id-based diff in
                            // updateUIView keeps stale pins because the new ids are
                            // already "present" in the existing set). Stable ids
                            // make the diff actually distinguish different POIs.
                            id: Self.stablePOIID(name: name, coord: coord),
                            name: name,
                            category: self.categorizeMapItem(item),
                            subcategory: item.pointOfInterestCategory?.rawValue,
                            coordinate: coord,
                            address: self.formatAddress(item.placemark),
                            phone: item.phoneNumber,
                            website: item.url?.absoluteString,
                            openingHours: nil
                        )
                    }
                    var merged: [MapPOI] = addressHits.map { $0.toMapPOI() }
                    merged.append(contentsOf: self.sortedByDistanceFromUser(mapped))
                    self.searchResults = merged
                    self.categoryMarkers = []  // free-text query — clear category pins
                }
            } else {
                // Fallback to offline database
                DispatchQueue.main.async {
                    self.searchOffline(query: query)
                    // Splice address hits to the top of the offline result list.
                    if !addressHits.isEmpty {
                        var merged: [MapPOI] = addressHits.map { $0.toMapPOI() }
                        var seen = Set<Int64>(merged.map { $0.id })
                        for poi in self.searchResults where seen.insert(poi.id).inserted {
                            merged.append(poi)
                        }
                        self.searchResults = merged
                    }
                    self.categoryMarkers = []
                }
            }
        }
    }

    // MARK: - Category Keyword Mapping (Bug 1)

    /// Multilingual keyword → POICategory id. Order matters: longer phrases
    /// must come before their single-word synonyms ("la poste" before "poste").
    private static let categoryKeywordMap: [(String, String)] = [
        // Post offices ("La Poste" is a CATEGORY in France, not a name)
        ("la poste", "post_office"), ("post office", "post_office"),
        ("poste", "post_office"), ("post", "post_office"),
        ("correos", "post_office"), ("ufficio postale", "post_office"),
        ("postamt", "post_office"), ("post amt", "post_office"),
        // Hospitals / ER
        ("hopital", "hospital"), ("hospital", "hospital"),
        ("urgences", "hospital"), ("emergency", "hospital"), ("er", "hospital"),
        ("krankenhaus", "hospital"), ("ospedale", "hospital"),
        // Pharmacy
        ("pharmacie", "pharmacy"), ("pharmacy", "pharmacy"),
        ("drugstore", "pharmacy"), ("apotheke", "pharmacy"),
        ("farmacia", "pharmacy"),
        // Cafe
        ("cafe", "cafe"), ("coffee", "cafe"), ("kaffee", "cafe"),
        ("caffetteria", "cafe"),
        // Restaurant
        ("restaurant", "restaurant"), ("resto", "restaurant"),
        ("ristorante", "restaurant"), ("restaurante", "restaurant"),
        // Bar / pub
        ("bar", "bar"), ("pub", "bar"),
        // Hotel
        ("hotel", "hotel"), ("hostel", "hotel"),
        // Market / supermarket / grocery — bare "market" is the most common
        // user query; covers outdoor markets + supermarkets + corner stores.
        ("market", "supermarket"), ("marche", "supermarket"),
        ("marché", "supermarket"), ("marketplace", "supermarket"),
        ("supermarche", "supermarket"), ("supermarché", "supermarket"),
        ("supermarket", "supermarket"),
        ("epicerie", "supermarket"), ("épicerie", "supermarket"),
        ("grocery", "supermarket"), ("groceries", "supermarket"),
        ("convenience", "supermarket"), ("convenience store", "supermarket"),
        ("supermercado", "supermarket"), ("supermercato", "supermarket"),
        ("mercato", "supermarket"), ("mercado", "supermarket"),
        ("markt", "supermarket"), ("supermarkt", "supermarket"),
        ("lebensmittel", "supermarket"),
        // Gas / fuel
        ("essence", "fuel"), ("gas station", "fuel"), ("gas", "fuel"),
        ("fuel", "fuel"), ("tankstelle", "fuel"), ("gasolinera", "fuel"),
        ("benzina", "fuel"),
        // ATM
        ("atm", "atm"), ("distributeur", "atm"), ("bancomat", "atm"),
        ("cajero", "atm"), ("geldautomat", "atm"),
        // Bank
        ("banque", "atm"), ("bank", "atm"), ("banco", "atm"), ("banca", "atm"),
        // Parking
        ("parking", "parking"), ("parc auto", "parking"),
        ("parcheggio", "parking"), ("estacionamiento", "parking"),
        ("parkplatz", "parking"),
        // Police
        ("police", "police"), ("commissariat", "police"),
        ("polizia", "police"), ("policia", "police"), ("polizei", "police"),
        // School
        ("ecole", "university"), ("school", "university"),
        ("scuola", "university"), ("escuela", "university"),
        ("schule", "university"),
        // Park / garden — no MK filter; falls through to plain MKLocalSearch.
        // EV charging
        ("borne de recharge", "charging_station"), ("ev charging", "charging_station"),
        ("ev charger", "charging_station"), ("charger", "charging_station"),
        ("ladestation", "charging_station"),
        // Airport
        ("aeroport", "airport"), ("airport", "airport"),
        ("aeroporto", "airport"), ("aeropuerto", "airport"),
        ("flughafen", "airport"),
        // Train station
        ("gare", "train_station"), ("train station", "train_station"),
        ("estacion", "train_station"), ("stazione", "train_station"),
        ("bahnhof", "train_station"),
        // Bus
        ("bus", "bus_station"), ("arret", "bus_station"),
        ("fermata", "bus_station"), ("parada", "bus_station"),
        ("bushaltestelle", "bus_station")
    ]

    /// Case-fold + strip diacritics + prefix match against the keyword table.
    /// Returns nil when no category match — caller should run regular text search.
    /// When the matched id isn't in `POICategory.all` (e.g. "post_office",
    /// "police", "place_of_worship" — categories we recognize but don't show
    /// in the grid), we synthesize a transient POICategory carrying just the
    /// id + display name, so downstream search code can still apply the right
    /// MKPointOfInterestCategory filter via `mkPointOfInterestCategory`.
    static func categoryFromQuery(_ query: String) -> POICategory? {
        let folded = foldString(query.trimmingCharacters(in: .whitespaces))
        guard !folded.isEmpty else { return nil }
        for (keyword, categoryId) in categoryKeywordMap {
            let k = foldString(keyword)
            if folded == k || folded.hasPrefix(k + " ") {
                if let cat = POICategory.all.first(where: { $0.id == categoryId }) {
                    return cat
                }
                // Synthetic POICategory for ids missing from the visible grid.
                return POICategory(
                    id: categoryId,
                    name: keyword.capitalized,
                    icon: "mappin.circle.fill"
                )
            }
        }
        return nil
    }

    /// Lower-case + Unicode NFD diacritic strip — used for keyword matching.
    private static func foldString(_ s: String) -> String {
        return s.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil)
    }

    /// Deterministic Int64 id derived from (name, lat, lon) so the same physical
    /// POI keeps the same id across MKLocalSearch responses. Replaces the
    /// previous `Int64(index)` pattern that produced colliding ids whenever the
    /// user switched categories — see updateUIView's id-based annotation diff.
    /// Quantizes coords to ~10 cm so floating-point jitter doesn't churn ids.
    static func stablePOIID(name: String, coord: CLLocationCoordinate2D) -> Int64 {
        let latQ = Int64((coord.latitude * 1_000_000).rounded())
        let lonQ = Int64((coord.longitude * 1_000_000).rounded())
        // FNV-1a 64-bit — process-stable, no per-launch randomization
        // unlike Swift's default Hasher.
        var h: UInt64 = 0xcbf29ce484222325
        let prime: UInt64 = 0x100000001b3
        func mix(_ b: UInt8) {
            h ^= UInt64(b)
            h = h &* prime
        }
        for byte in name.utf8 { mix(byte) }
        mix(0xFF) // separator
        for shift in stride(from: 0, through: 56, by: 8) {
            mix(UInt8(truncatingIfNeeded: latQ >> shift))
        }
        mix(0xFF)
        for shift in stride(from: 0, through: 56, by: 8) {
            mix(UInt8(truncatingIfNeeded: lonQ >> shift))
        }
        return Int64(bitPattern: h)
    }

    /// Replace `categoryMarkers` with `pois`, sorted by distance from the user
    /// when known, capped at 100 entries.
    private func setCategoryMarkers(_ pois: [MapPOI], near origin: CLLocationCoordinate2D?) {
        let sorted: [MapPOI]
        if let origin = origin {
            let userCL = CLLocation(latitude: origin.latitude, longitude: origin.longitude)
            sorted = pois.sorted { a, b in
                CLLocation(latitude: a.coordinate.latitude, longitude: a.coordinate.longitude).distance(from: userCL) <
                CLLocation(latitude: b.coordinate.latitude, longitude: b.coordinate.longitude).distance(from: userCL)
            }
        } else {
            sorted = pois
        }
        categoryMarkers = Array(sorted.prefix(100))
        OshiLog.maps.info("[OfflineMapViewModel] categoryMarkers: \(categoryMarkers.count) pins")
    }

    /// Sort an unordered POI list by distance from the user's current location,
    /// when known. Used to surface nearby category matches above far-away ones.
    private func sortedByDistanceFromUser(_ pois: [MapPOI]) -> [MapPOI] {
        guard let origin = locationManager.currentLocation?.coordinate else { return pois }
        let userCL = CLLocation(latitude: origin.latitude, longitude: origin.longitude)
        return pois.sorted { a, b in
            CLLocation(latitude: a.coordinate.latitude, longitude: a.coordinate.longitude).distance(from: userCL) <
            CLLocation(latitude: b.coordinate.latitude, longitude: b.coordinate.longitude).distance(from: userCL)
        }
    }

    /// Search by POICategory using MKPointOfInterestCategory filter.
    /// Bug 2 fix: also publishes `categoryMarkers` so the map drops icon pins
    /// at every result, Google-Maps-style. Sorted by distance, capped at 100.
    ///
    /// Bug A fix (2026-04-28): the localised category label (e.g. "Marché",
    /// "Café", "Pharmacie") is too narrow as a `naturalLanguageQuery` —
    /// Apple Maps interprets it as a NAME match and skips chain stores
    /// (Carrefour, Migros, Lidl don't have "market" in their name). We
    /// substitute a generic English query phrase that Apple Maps recognises
    /// as a category synonym. Combined with the MK POI filter below, this
    /// surfaces the full set of relevant venues.
    func searchByMKCategory(_ category: POICategory) {

        // STRATEGY: try the localized chip label FIRST as a plain natural-
        // language query without any filter. Apple Maps understands "Marché",
        // "Hôpital", "DAB", "Essence" etc. in the device locale and returns
        // dense results. The previous attempt used English overrides
        // ("supermarket grocery") + MKPointOfInterestFilter([.foodMarket])
        // which on iOS 17 falls back to `.store` (so broad xit ranks
        // unrelated venues higher) and on French locales returns very few
        // hits because the English query confuses Apple's locale-aware
        // ranker. The fallback chain below tries progressively broader
        // strategies and merges with offline POI in all cases.
        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = category.name
        request.region = region

        let userCoord = locationManager.currentLocation?.coordinate

        // ALWAYS query the offline SQLite POI db in parallel with Apple Maps —
        // the old "fall back only if MKLocalSearch returns 0" logic missed the
        // case where Apple Maps returns 1-2 venues even though the OSM data
        // has dozens. Users complained that Hôtel / Marché / Pharmacie / Hôpital /
        // DAB / Essence rendered almost nothing while Restaurant / Café /
        // Train worked fine. Apple's coverage is uneven; OSM is dense in those
        // categories. Merging both gives full coverage.
        let offlinePOIs = self.collectOfflinePOIsByCategory(category.id, near: userCoord, limit: 200)

        // Helper: map MKMapItems → MapPOI[].
        func mapItems(_ items: [MKMapItem]) -> [MapPOI] {
            items.map { item in
                let name = item.name ?? NSLocalizedString("map.poi.unknown", comment: "Fallback name for unnamed POI")
                let coord = item.placemark.coordinate
                return MapPOI(
                    id: Self.stablePOIID(name: name, coord: coord),
                    name: name,
                    category: self.categorizeMapItem(item),
                    subcategory: item.pointOfInterestCategory?.rawValue,
                    coordinate: coord,
                    address: self.formatAddress(item.placemark),
                    phone: item.phoneNumber,
                    website: item.url?.absoluteString,
                    openingHours: nil
                )
            }
        }

        // Fallback chain (multilingual keywords) used when the localized
        // chip label returns nothing. Apple Maps may not know "Marché" in
        // every region, but does know "supermarket". Likewise for DAB →
        // "atm cash machine", Essence → "gas station fuel", Bus → "bus stop".
        let englishFallbacks: [String: String] = [
            "supermarket":      "supermarket grocery store market",
            "cafe":             "cafe coffee shop",
            "pharmacy":         "pharmacy drugstore",
            "hospital":         "hospital medical center emergency",
            "atm":              "atm cash machine",
            "fuel":             "gas station fuel petrol",
            "hotel":            "hotel lodging",
            "restaurant":       "restaurant dining",
            "parking":          "parking lot garage",
            "charging_station": "ev charging station",
            "bus_station":      "bus stop station",
            "train_station":    "train station",
            "airport":          "airport",
            "police":           "police station",
            "post_office":      "post office",
            "school":           "school",
            "university":       "university college",
            "museum":           "museum",
            "park":             "park",
            "beach":            "beach",
            "place_of_worship": "church cathedral mosque temple",
            "gym":              "gym fitness",
            "cinema":           "cinema movie theater",
            "toilet":           "public toilet restroom",
            "bar":              "bar pub",
        ]

        // ALWAYS run BOTH the localized query AND the English keyword query
        // in parallel, then merge. The previous "fallback only on 0 results"
        // logic missed cases like FR "Marché" — Apple Maps interprets it
        // as "open-air market / farmer's market" and returns a few hits,
        // which suppressed the fallback even though the user wanted
        // Carrefour / Lidl / Monoprix / Auchan (the broad supermarket-chain
        // query). Running both in parallel guarantees max coverage in any
        // locale at the cost of one extra (parallel) MKLocalSearch call.
        let group = DispatchGroup()
        var combinedMK: [MapPOI] = []
        let mergeLock = NSLock()

        group.enter()
        MKLocalSearch(request: request).start { response, _ in
            let hits = mapItems(response?.mapItems ?? [])
            mergeLock.lock(); combinedMK.append(contentsOf: hits); mergeLock.unlock()
            group.leave()
        }

        if let fb = englishFallbacks[category.id], fb != category.name {
            let r2 = MKLocalSearch.Request()
            r2.naturalLanguageQuery = fb
            r2.region = request.region
            group.enter()
            MKLocalSearch(request: r2).start { response2, _ in
                let hits = mapItems(response2?.mapItems ?? [])
                mergeLock.lock(); combinedMK.append(contentsOf: hits); mergeLock.unlock()
                group.leave()
            }
        }

        // 🔧 FIX (2026-06-04): Third parallel branch — dedicated
        // MKLocalPointsOfInterestRequest with a category filter for ids where
        // Apple's category map is solid. This is the LIFELINE for the Bus chip:
        // the localized FR label "Gares routières" only matches long-distance
        // coach depots (Apple parses it as a name), and the "bus stop station"
        // english fallback hits a small subset. The dedicated category request
        // is the canonical way to ask Apple Maps for transit POIs in a region.
        //
        // Two important fixes vs the 2026-06-03 attempt:
        //
        // 1. **Use (center:radius:) with a clamped radius**, not
        //    (coordinateRegion:). Apple's request has a hard
        //    `pointsOfInterestRequestMaxRadius` = 50 km cap; when the user is
        //    zoomed to a city/country span the region's effective radius blows
        //    past 50 km and the request returns ZERO results silently. Clamp
        //    to 49 999 m centred on the user (or the map centre as fallback).
        //
        // 2. **For Bus specifically**, also run a NAME-keyword search using
        //    the localized chip label ("Bus", "Autobus", "バス", "公交"...) so
        //    we don't get drowned by train stations: `.publicTransport` is
        //    Apple's ONLY transit category, so a category-only request mixes
        //    buses with trams/trains. The name search disambiguates back to
        //    bus stops. Both branches still run; results are deduped by name+
        //    coord in publishMergedCategoryPOIs.
        if let mkCat = Self.mkPointOfInterestCategory(for: category.id) {
            // Effective radius from the current map span (latDelta -> metres),
            // halved (radius vs full span), capped at Apple's max - 1m.
            let centre = userCoord ?? request.region.center
            let latMetres = request.region.span.latitudeDelta * 111_000
            let radius = min(max(latMetres * 0.5, 2_000), 49_999) // [2km, 50km)
            let r3 = MKLocalPointsOfInterestRequest(center: centre, radius: radius)
            r3.pointOfInterestFilter = MKPointOfInterestFilter(including: [mkCat])
            group.enter()
            MKLocalSearch(request: r3).start { response3, _ in
                let hits = mapItems(response3?.mapItems ?? [])
                mergeLock.lock(); combinedMK.append(contentsOf: hits); mergeLock.unlock()
                group.leave()
            }
        }

        group.notify(queue: .main) { [weak self] in
            guard let self = self else { return }
            self.publishMergedCategoryPOIs(category: category, mkPOIs: combinedMK, offlinePOIs: offlinePOIs, userCoord: userCoord)
        }
    }

    /// Common tail of `searchByMKCategory` — dedup the MapKit + offline POI
    /// sets, sort by distance, publish to searchResults + categoryMarkers.
    /// Extracted so the MKPointOfInterestFilter retry path can share it.
    private func publishMergedCategoryPOIs(category: POICategory, mkPOIs: [MapPOI], offlinePOIs: [MapPOI], userCoord: CLLocationCoordinate2D?) {
        var seen = Set<Int64>()
        var merged: [MapPOI] = []
        for poi in mkPOIs where seen.insert(poi.id).inserted { merged.append(poi) }
        for poi in offlinePOIs where seen.insert(poi.id).inserted { merged.append(poi) }
        OshiLog.maps.info("[Category '\(category.id)'] mk=\(mkPOIs.count) offline=\(offlinePOIs.count) merged=\(merged.count)")
        let sorted = self.sortedByDistanceFromUser(merged)
        self.searchResults = sorted
        self.setCategoryMarkers(sorted, near: userCoord)
    }

    /// Walk every downloaded region's poi.db and return POIs whose `category`
    /// column matches the canonical id ("hotel", "supermarket", ...). Used
    /// from `searchByMKCategory` to merge offline OSM data alongside Apple
    /// Maps results so categories with weak Apple coverage (DAB, Marché,
    /// Pharmacie outside the US) still surface pins.
    private func collectOfflinePOIsByCategory(_ categoryId: String, near origin: CLLocationCoordinate2D?, limit: Int) -> [MapPOI] {
        guard let docsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return [] }
        let regionsDir = docsURL.appendingPathComponent("Maps/regions")
        guard let contents = try? FileManager.default.contentsOfDirectory(atPath: regionsDir.path) else { return [] }
        var merged: [MapPOI] = []
        var seen = Set<Int64>()
        for dir in contents {
            let poiPath = regionsDir.appendingPathComponent(dir).appendingPathComponent("poi.db").path
            guard FileManager.default.fileExists(atPath: poiPath) else { continue }
            let poiDB = MapPOIDatabase()
            guard poiDB.open(regionId: dir) else { continue }
            let hits = poiDB.searchByCategory(categoryId, near: origin, radiusKm: 50, limit: limit)
            poiDB.close()
            for poi in hits where seen.insert(poi.id).inserted { merged.append(poi) }
        }
        return merged
    }

    /// Offline category fallback: query EVERY downloaded region's `poi.db` by
    /// the canonical category id (matches the SQLite `category` column directly,
    /// not by name). Merges results across regions so a user with FR+CH+IT
    /// downloaded sees pins from all three.
    func searchOfflineByCategory(_ categoryId: String, near origin: CLLocationCoordinate2D?) {
        guard let docsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
            searchResults = []
            return
        }
        let regionsDir = docsURL.appendingPathComponent("Maps/regions")
        guard let contents = try? FileManager.default.contentsOfDirectory(atPath: regionsDir.path) else {
            searchResults = []
            return
        }

        var merged: [MapPOI] = []
        var seen = Set<Int64>()

        for dir in contents {
            let poiPath = regionsDir.appendingPathComponent(dir).appendingPathComponent("poi.db").path
            guard FileManager.default.fileExists(atPath: poiPath) else { continue }
            let poiDB = MapPOIDatabase()
            guard poiDB.open(regionId: dir) else { continue }
            let hits = poiDB.searchByCategory(categoryId, near: origin, radiusKm: 50, limit: 100)
            poiDB.close()
            for poi in hits {
                if seen.insert(poi.id).inserted {
                    merged.append(poi)
                }
            }
        }

        // Sort by distance from user when known, cap at 100.
        if let origin = origin {
            let userCL = CLLocation(latitude: origin.latitude, longitude: origin.longitude)
            merged.sort { a, b in
                CLLocation(latitude: a.coordinate.latitude, longitude: a.coordinate.longitude).distance(from: userCL) <
                CLLocation(latitude: b.coordinate.latitude, longitude: b.coordinate.longitude).distance(from: userCL)
            }
        }
        searchResults = Array(merged.prefix(100))
        OshiLog.maps.info("[OfflineMapViewModel] offline category '\(categoryId)' → \(searchResults.count) POIs across \(contents.count) regions")
    }

    /// Search specifically for streets/addresses and pin the result.
    func searchStreet(_ street: String) {
        guard !street.trimmingCharacters(in: .whitespaces).isEmpty else { return }

        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = street
        request.region = region
        request.resultTypes = .address

        let search = MKLocalSearch(request: request)
        search.start { [weak self] response, error in
            guard let self = self else { return }
            guard let response = response, let item = response.mapItems.first else { return }

            DispatchQueue.main.async {
                let poi = MapPOI(
                    id: Int64(0),
                    name: item.name ?? street,
                    category: "address",
                    subcategory: nil,
                    coordinate: item.placemark.coordinate,
                    address: self.formatAddress(item.placemark),
                    phone: nil,
                    website: nil,
                    openingHours: nil
                )
                self.searchResults = [poi]
                self.selectPOI(poi)
            }
        }
    }

    // MARK: - MKLocalSearch Helpers

    /// Forward mapping (our category id → MKPointOfInterestCategory) for
    /// ids where Apple's category set is solid. Used by `searchByMKCategory`
    /// to add a third parallel category-filtered search, which is the
    /// authoritative way to get e.g. local bus stops from Apple Maps
    /// (natural-language "Bus" / "Gares routières" only returns long-distance
    /// coach depots and misses the dense local-stop network).
    /// Intentionally narrow — categories with iOS 17+ filter quirks
    /// (e.g. .foodMarket → falls back to .store) are omitted.
    static func mkPointOfInterestCategory(for id: String) -> MKPointOfInterestCategory? {
        switch id {
        case "bus_station", "train_station": return .publicTransport
        case "restaurant":                   return .restaurant
        case "cafe":                         return .cafe
        case "fuel":                         return .gasStation
        case "hospital":                     return .hospital
        case "pharmacy":                     return .pharmacy
        case "hotel":                        return .hotel
        case "atm":                          return .atm
        case "parking":                      return .parking
        case "charging_station":             return .evCharger
        case "airport":                      return .airport
        case "park":                         return .park
        case "museum":                       return .museum
        case "police":                       return .police
        case "post_office":                  return .postOffice
        default:                             return nil
        }
    }

    /// Categorize an MKMapItem into our internal category string.
    func categorizeMapItem(_ item: MKMapItem) -> String {
        if let poiCategory = item.pointOfInterestCategory {
            switch poiCategory {
            case .restaurant: return "restaurant"
            case .cafe: return "cafe"
            case .nightlife: return "bar"
            case .gasStation: return "fuel"
            case .hospital: return "hospital"
            case .pharmacy: return "pharmacy"
            case .hotel: return "hotel"
            case .store: return "supermarket"
            case .atm, .bank: return "atm"
            case .parking: return "parking"
            case .police: return "police"
            case .school, .university: return "university"
            case .publicTransport: return "bus_station"
            case .airport: return "airport"
            case .theater, .movieTheater: return "cinema"
            case .fitnessCenter: return "gym"
            case .postOffice: return "post_office"
            case .evCharger: return "charging_station"
            case .restroom: return "toilet"
            default: return "poi"
            }
        }
        return "poi"
    }

    /// Format an MKPlacemark into a readable address string.
    func formatAddress(_ placemark: MKPlacemark) -> String? {
        var parts: [String] = []
        if let subThoroughfare = placemark.subThoroughfare {
            parts.append(subThoroughfare)
        }
        if let thoroughfare = placemark.thoroughfare {
            parts.append(thoroughfare)
        }
        if let locality = placemark.locality {
            if !parts.isEmpty {
                parts.append(", \(locality)")
            } else {
                parts.append(locality)
            }
        }
        if let postalCode = placemark.postalCode {
            parts.append(postalCode)
        }
        let result = parts.joined(separator: " ")
        return result.isEmpty ? nil : result
    }

    // MARK: - Offline Fallback

    private func searchOffline(query: String) {
        let poiDB = MapPOIDatabase()
        guard let docsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
            searchResults = []
            return
        }
        let regionsDir = docsURL.appendingPathComponent("Maps/regions")
        guard let contents = try? FileManager.default.contentsOfDirectory(atPath: regionsDir.path) else {
            searchResults = []
            return
        }

        var regionOpened = false
        var openedRegionId: String?
        for dir in contents {
            let poiPath = regionsDir.appendingPathComponent(dir).appendingPathComponent("poi.db").path
            if FileManager.default.fileExists(atPath: poiPath) {
                regionOpened = poiDB.open(regionId: dir)
                if regionOpened { openedRegionId = dir }
                break
            }
        }

        guard regionOpened else {
            searchResults = []
            return
        }

        let userCoord = locationManager.currentLocation?.coordinate

        // 1. Street index results (highest priority, if downloaded)
        var streetPOIs: [MapPOI] = []
        if let rId = openedRegionId, OfflineStreetDatabase.shared.isStreetIndexAvailable(regionId: rId) {
            let streets = OfflineStreetDatabase.shared.searchStreets(query: query, regionId: rId, limit: 20)
            streetPOIs = streets.map { $0.toMapPOI() }
        }

        // 2. POI address matches
        let addressResults = poiDB.searchByAddress(query: query, near: userCoord, radiusKm: 50, limit: 20)

        // 3. POI name matches (existing FTS5 search)
        let nameResults = poiDB.search(query: query, near: userCoord, radiusKm: 50, limit: 100)

        poiDB.close()

        // Merge and deduplicate: streets first, then address matches, then name matches
        var seen = Set<Int64>()
        var merged: [MapPOI] = []

        for poi in streetPOIs {
            if seen.insert(poi.id).inserted { merged.append(poi) }
        }
        for poi in addressResults {
            if seen.insert(poi.id).inserted { merged.append(poi) }
        }
        for poi in nameResults {
            if seen.insert(poi.id).inserted { merged.append(poi) }
        }

        searchResults = merged
    }

    func shareLocation(_ poi: MapPOI) {
        // Integrates with OSHI chat via LocationSharingManager.
        // Opens a share sheet or navigates to conversation picker.
        let coordinate = poi.coordinate
        let locationText = "\(poi.name)\n\(poi.address ?? "")\nhttps://maps.apple.com/?ll=\(coordinate.latitude),\(coordinate.longitude)"

        let activityVC = UIActivityViewController(
            activityItems: [locationText],
            applicationActivities: nil
        )

        if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
           let rootVC = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController {
            var topVC = rootVC
            while let presented = topVC.presentedViewController {
                topVC = presented
            }
            if let popover = activityVC.popoverPresentationController {
                popover.sourceView = topVC.view
                popover.sourceRect = CGRect(x: topVC.view.bounds.midX, y: topVC.view.bounds.midY, width: 0, height: 0)
            }
            topVC.present(activityVC, animated: true)
        }
    }

    // MARK: - Private

    private func centerOnUserIfAvailable() {
        if let location = locationManager.currentLocation {
            hasCenteredOnUser = true
            region = MKCoordinateRegion(
                center: location.coordinate,
                span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)
            )
        }
    }

    // generatePlaceholderSearchResults removed -- replaced by MKLocalSearch
}

// MARK: - Map Annotation Item

struct OfflineMapAnnotationItem: Identifiable {
    let id: Int64
    let coordinate: CLLocationCoordinate2D
    let title: String
    let icon: String
    let isDestination: Bool
    /// __LORA_NODES_ON_THE_MAP_2026_10_28__ a Meshtastic radio heard over LoRa, not a POI.
    var isLoRaNode: Bool = false
}

// MARK: - OSM Caching Tile Overlay
//
// The cache-first tile overlay is implemented in `OfflineFirstTileOverlay.swift`
// (class `OfflineFirstTileOverlay`). The historical `OSMCachingTileOverlay`
// name is preserved as a typealias so the existing Coordinator and call sites
// below compile unchanged. The new class adds:
//   - Honoring the `oshi.map.offlineOnly` UserDefaults preference (no network
//     access when on, regardless of connectivity).
//   - `url(forTilePath:)` that returns a `file://` URL when the local PNG
//     exists, allowing MapKit's loader to bypass the network entirely.
//
// Cache-population behavior is unchanged: every successful network fetch is
// written back to Documents/Maps/tiles/{z}/{x}/{y}.png.

typealias OSMCachingTileOverlay = OfflineFirstTileOverlay

// MARK: - MapKit Container (UIViewRepresentable for iOS 16+)

struct MapKitContainerView: UIViewRepresentable {
    @Binding var region: MKCoordinateRegion
    @Binding var userTrackingMode: MKUserTrackingMode
    let annotations: [OfflineMapAnnotationItem]
    let routePolyline: [CLLocationCoordinate2D]
    var showsUserLocation: Bool = true
    var contactAnnotation: ContactAnnotationData? = nil
    let onAnnotationTapped: (Int64) -> Void
    var onLongPress: ((CLLocationCoordinate2D) -> Void)?
    /// __ANATOLE_1311_IOS__ (N2) Passed in (not only read from defaults) so a
    /// change from the map's own layer menu is an INPUT change and reaches
    /// `updateUIView` immediately.
    var basemap: MapBackground = MapBackground.current

    func makeUIView(context: Context) -> MKMapView {
        let mapView = MKMapView()
        mapView.delegate = context.coordinator
        mapView.showsUserLocation = showsUserLocation
        mapView.showsCompass = false
        mapView.showsScale = true
        mapView.setRegion(region, animated: false)

        // Limit max zoom to OSM z=19 equivalent (~300m center distance prevents blank tiles)
        let zoomRange = MKMapView.CameraZoomRange(minCenterCoordinateDistance: 300)
        mapView.setCameraZoomRange(zoomRange, animated: false)

        mapView.register(
            MKMarkerAnnotationView.self,
            forAnnotationViewWithReuseIdentifier: "POIPin"
        )
        mapView.register(
            MKAnnotationView.self,
            forAnnotationViewWithReuseIdentifier: "ContactPin"
        )
        // Long press to drop a pin
        let longPress = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleLongPress(_:)))
        longPress.minimumPressDuration = 0.5
        mapView.addGestureRecognizer(longPress)

        // __MAP_BACKGROUND_CHOICE_2026_11_05__ OSHI's own tiles, or Apple's.
        //
        // Reported: "I click settings and select Apple Plan or Google Maps and
        // the map still shows exactly the same." True, and it was working as
        // designed — `mapOpenPreference` reads "Open locations with" and governs
        // which APP opens a shared place, never the map tab. But the
        // expectation behind the report is reasonable, and Apple's map is free
        // here: this is already an MKMapView with OSM painted over it, so NOT
        // adding the overlay reveals Apple's own tiles with no extra code.
        //
        // See `MapBackground` for why Google cannot be an option.
        if basemap == .oshi {
            let tileOverlay = OSMCachingTileOverlay()
            context.coordinator.osmTileOverlay = tileOverlay
            mapView.addOverlay(tileOverlay, level: .aboveRoads)
        }
        Self.applyBasemapStyle(basemap, to: mapView)

        // __ZOOM_WENT_BLACK_BETWEEN_TILES_2026_11_05__ What shows through a gap.
        //
        // `canReplaceMapContent` means MapKit draws no base map under the OSM
        // tiles, so anything not yet drawn is the map view's own background —
        // black by default, which reads as a hole in the world. OSM's land tone
        // makes the same gap read as paper the ink has not reached yet.
        //
        // This is the floor under the tile-level fix, not a substitute for it:
        // it costs nothing and it is what remains visible if a tile genuinely
        // cannot be produced.
        mapView.backgroundColor = UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(red: 0.16, green: 0.17, blue: 0.19, alpha: 1)   // dark-map land
                : UIColor(red: 0.95, green: 0.94, blue: 0.91, alpha: 1)   // OSM #F2EFE9
        }

        return mapView
    }

    /// __LORA_REACH_RINGS_2026_11_05__ Brings the map's reach rings in line with
    /// the radios currently known, adding, updating and removing as needed.
    ///
    /// A ring appears only when `LoRaCoverage` returns a radius, which excludes
    /// relayed nodes and nodes never heard directly — see that file for why a
    /// relayed node must NOT get one.
    private func syncLoRaCoverage(on mapView: MKMapView, coordinator: Coordinator) {
        var wanted: [UInt32: (CLLocationCoordinate2D, Double, Double)] = [:]
        for node in MeshtasticManager.shared.nodes {
            guard let latitude = node.latitude, let longitude = node.longitude,
                  latitude != 0 || longitude != 0,
                  let radius = LoRaCoverage.radiusMeters(rssi: node.rssi, hopsAway: node.hopsAway)
            else { continue }
            let opacity = LoRaCoverage.fillOpacity(rssi: node.rssi, hopsAway: node.hopsAway)
            wanted[node.num] = (CLLocationCoordinate2D(latitude: latitude, longitude: longitude), radius, opacity)
        }

        // Remove rings for radios that are gone, went quiet, or became relayed.
        for (nodeNum, circle) in coordinator.loRaCoverageCircles where wanted[nodeNum] == nil {
            mapView.removeOverlay(circle)
            coordinator.loRaCoverageCircles.removeValue(forKey: nodeNum)
        }

        for (nodeNum, (coordinate, radius, opacity)) in wanted {
            if let existing = coordinator.loRaCoverageCircles[nodeNum] {
                // Unchanged within a metre and a hair of opacity: leave it
                // alone, or the ring flickers on every position report.
                let samePlace = abs(existing.coordinate.latitude - coordinate.latitude) < 0.000_01
                    && abs(existing.coordinate.longitude - coordinate.longitude) < 0.000_01
                if samePlace, abs(existing.radius - radius) < 1, abs(existing.fillOpacity - opacity) < 0.005 {
                    continue
                }
                mapView.removeOverlay(existing)
            }
            let circle = LoRaCoverageCircle(center: coordinate, radius: radius)
            circle.fillOpacity = opacity
            circle.nodeNum = nodeNum
            coordinator.loRaCoverageCircles[nodeNum] = circle
            // BELOW the tiles' label level so a ring never sits on top of a
            // street name, and below the pins so it never swallows a tap.
            mapView.insertOverlay(circle, at: 0, level: .aboveRoads)
        }
    }

    /// __ANATOLE_1311_IOS__ (N2) Standard or satellite for Apple's basemap.
    /// Only reassigned when the KIND changes: a fresh configuration on every
    /// `updateUIView` would make MapKit reload its tiles each time.
    static func applyBasemapStyle(_ background: MapBackground, to mapView: MKMapView) {
        let wantsHybrid = background == .appleSatellite
        let isHybrid = mapView.preferredConfiguration is MKHybridMapConfiguration
        guard wantsHybrid != isHybrid else { return }
        mapView.preferredConfiguration = wantsHybrid ? MKHybridMapConfiguration() : MKStandardMapConfiguration()
    }

    func updateUIView(_ mapView: MKMapView, context: Context) {
        // Update region only when significantly different to avoid fight with user gestures
        let currentCenter = mapView.region.center
        let latDiff = abs(currentCenter.latitude - region.center.latitude)
        let lonDiff = abs(currentCenter.longitude - region.center.longitude)
        if latDiff > 0.0001 || lonDiff > 0.0001 {
            mapView.setRegion(region, animated: true)
        }

        // Update user tracking mode
        if mapView.userTrackingMode != userTrackingMode {
            mapView.setUserTrackingMode(userTrackingMode, animated: true)
        }

        // __LORA_REACH_RINGS_2026_11_05__ Reach rings for radios we hear
        // DIRECTLY and that have reported a position.
        //
        // Diffed by node number rather than cleared and rebuilt: a radio
        // reports every few seconds, and removing then re-adding its circle
        // each time makes the ring blink. Only a changed radius or opacity
        // replaces one.
        syncLoRaCoverage(on: mapView, coordinator: context.coordinator)

        // __MAP_BACKGROUND_CHOICE_2026_11_05__ Follow the preference live, so
        // switching it in Settings changes the map behind the user rather than
        // on next launch.
        switch basemap {
        case .oshi:
            if context.coordinator.osmTileOverlay == nil {
                let tileOverlay = OSMCachingTileOverlay()
                context.coordinator.osmTileOverlay = tileOverlay
                mapView.addOverlay(tileOverlay, level: .aboveRoads)
            }
        case .apple, .appleSatellite:
            if let existing = context.coordinator.osmTileOverlay {
                mapView.removeOverlay(existing)
                context.coordinator.osmTileOverlay = nil
            }
        }
        Self.applyBasemapStyle(basemap, to: mapView)

        // Sync annotations
        let existingIDs = Set(mapView.annotations.compactMap { ($0 as? POIAnnotation)?.poiId })
        let newIDs = Set(annotations.map { $0.id })

        // Remove stale
        let toRemove = mapView.annotations.filter { annotation in
            guard let poiAnnotation = annotation as? POIAnnotation else { return false }
            return !newIDs.contains(poiAnnotation.poiId)
        }
        if !toRemove.isEmpty {
            mapView.removeAnnotations(toRemove)
        }

        // Add new
        let toAdd = annotations.filter { !existingIDs.contains($0.id) }
        let newAnnotations = toAdd.map { item -> POIAnnotation in
            let annotation = POIAnnotation()
            annotation.poiId = item.id
            annotation.coordinate = item.coordinate
            annotation.title = item.title
            annotation.iconName = item.icon
            annotation.isDestination = item.isDestination
            annotation.isLoRaNode = item.isLoRaNode
            return annotation
        }
        if !newAnnotations.isEmpty {
            mapView.addAnnotations(newAnnotations)
        }

        // Sync contact annotation (shared location with profile picture)
        let existingContact = mapView.annotations.first(where: { $0 is ContactAnnotation }) as? ContactAnnotation
        if let contactData = contactAnnotation {
            if let existing = existingContact {
                // Update position for live location
                UIView.animate(withDuration: 0.5) {
                    existing.coordinate = contactData.coordinate
                }
            } else {
                // Create new contact annotation
                let annotation = ContactAnnotation()
                annotation.coordinate = contactData.coordinate
                annotation.contactName = contactData.name
                annotation.contactPublicKey = contactData.publicKey
                annotation.isLive = contactData.isLive
                annotation.title = contactData.name
                // Load profile picture
                if let key = contactData.publicKey {
                    annotation.profileImage = ContactPhotoManager.shared.getPhoto(for: key)
                }
                mapView.addAnnotation(annotation)
            }
        } else if let existing = existingContact {
            mapView.removeAnnotation(existing)
        }

        // Sync route polyline overlay
        let existingOverlays = mapView.overlays.filter { $0 is MKPolyline }
        if routePolyline.count >= 2 {
            // Remove old polylines
            mapView.removeOverlays(existingOverlays)
            let coords = routePolyline
            let polyline = MKPolyline(coordinates: coords, count: coords.count)
            mapView.addOverlay(polyline, level: .aboveRoads)
        } else if !existingOverlays.isEmpty {
            mapView.removeOverlays(existingOverlays)
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    final class Coordinator: NSObject, MKMapViewDelegate {
        private let parent: MapKitContainerView

        /// Reference to the OSM caching tile overlay.
        var osmTileOverlay: OSMCachingTileOverlay?

        /// __LORA_REACH_RINGS_2026_11_05__ The reach rings currently on the map,
        /// by node number, so they can be diffed rather than torn down and
        /// rebuilt on every position update — a radio reporting every few
        /// seconds would otherwise flicker its own ring.
        var loRaCoverageCircles: [UInt32: LoRaCoverageCircle] = [:]

        init(_ parent: MapKitContainerView) {
            self.parent = parent
        }

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            // Contact location annotation — show profile picture
            if let contactAnnotation = annotation as? ContactAnnotation {
                let view = mapView.dequeueReusableAnnotationView(
                    withIdentifier: "ContactPin",
                    for: annotation
                )
                view.displayPriority = .required
                view.canShowCallout = true

                let size: CGFloat = 48
                let borderWidth: CGFloat = 3

                let renderer = UIGraphicsImageRenderer(size: CGSize(width: size + borderWidth * 2, height: size + borderWidth * 2 + 12))
                let image = renderer.image { ctx in
                    let context = ctx.cgContext

                    // White border circle
                    context.setFillColor(UIColor.white.cgColor)
                    context.fillEllipse(in: CGRect(x: 0, y: 0, width: size + borderWidth * 2, height: size + borderWidth * 2))

                    // Live location: green pulsing border
                    if contactAnnotation.isLive {
                        context.setStrokeColor(UIColor.systemGreen.cgColor)
                        context.setLineWidth(2.5)
                        context.strokeEllipse(in: CGRect(x: 0.5, y: 0.5, width: size + borderWidth * 2 - 1, height: size + borderWidth * 2 - 1))
                    }

                    // Profile picture or initials
                    let clipRect = CGRect(x: borderWidth, y: borderWidth, width: size, height: size)
                    context.saveGState()
                    context.addEllipse(in: clipRect)
                    context.clip()

                    if let profileImage = contactAnnotation.profileImage {
                        profileImage.draw(in: clipRect)
                    } else {
                        // Gradient background with initials
                        let colors = [brandUIColor.cgColor, UIColor.systemBlue.cgColor]
                        let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors as CFArray, locations: nil)!
                        context.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: size, y: size), options: [])

                        // Draw initials
                        let initials = String(contactAnnotation.contactName.prefix(2)).uppercased()
                        let attributes: [NSAttributedString.Key: Any] = [
                            .font: UIFont.boldSystemFont(ofSize: size * 0.38),
                            .foregroundColor: UIColor.white
                        ]
                        let textSize = (initials as NSString).size(withAttributes: attributes)
                        let textRect = CGRect(
                            x: borderWidth + (size - textSize.width) / 2,
                            y: borderWidth + (size - textSize.height) / 2,
                            width: textSize.width,
                            height: textSize.height
                        )
                        (initials as NSString).draw(in: textRect, withAttributes: attributes)
                    }
                    context.restoreGState()

                    // Small triangle pointer at bottom
                    let triangleY = size + borderWidth * 2
                    context.setFillColor(UIColor.white.cgColor)
                    context.move(to: CGPoint(x: (size + borderWidth * 2) / 2 - 6, y: triangleY - 1))
                    context.addLine(to: CGPoint(x: (size + borderWidth * 2) / 2, y: triangleY + 10))
                    context.addLine(to: CGPoint(x: (size + borderWidth * 2) / 2 + 6, y: triangleY - 1))
                    context.fillPath()
                }

                view.image = image
                view.centerOffset = CGPoint(x: 0, y: -(size + borderWidth * 2 + 12) / 2)
                return view
            }

            guard let poiAnnotation = annotation as? POIAnnotation else { return nil }

            let view = mapView.dequeueReusableAnnotationView(
                withIdentifier: "POIPin",
                for: annotation
            ) as! MKMarkerAnnotationView

            view.glyphImage = UIImage(systemName: poiAnnotation.iconName ?? "mappin.circle.fill")
            // __LORA_NODES_ON_THE_MAP_2026_10_28__ orange for a radio: it is neither a
            // destination (red) nor a place you can walk into (brand), and a mesh node
            // that looked like a POI would be tapped expecting opening hours.
            view.markerTintColor = poiAnnotation.isDestination ? .systemRed
                : (poiAnnotation.isLoRaNode ? .systemOrange : brandUIColor)
            view.displayPriority = poiAnnotation.isDestination ? .required : .defaultHigh
            view.canShowCallout = false
            return view
        }

        func mapView(_ mapView: MKMapView, didSelect annotation: MKAnnotation) {
            guard let poiAnnotation = annotation as? POIAnnotation else { return }
            parent.onAnnotationTapped(poiAnnotation.poiId)
            mapView.deselectAnnotation(annotation, animated: false)
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            if let tileOverlay = overlay as? OSMCachingTileOverlay {
                return MKTileOverlayRenderer(overlay: tileOverlay)
            }
            if let polyline = overlay as? MKPolyline {
                let renderer = MKPolylineRenderer(polyline: polyline)
                renderer.strokeColor = brandUIColor
                renderer.lineWidth = 5
                renderer.lineCap = .round
                renderer.lineJoin = .round
                return renderer
            }
            // __LORA_REACH_RINGS_2026_11_05__ The estimated reach of a radio we
            // hear directly. See `LoRaCoverage` for why there is no ring at all
            // for a relayed node, and why this one is drawn soft.
            if let coverage = overlay as? LoRaCoverageCircle {
                let renderer = MKCircleRenderer(circle: coverage)
                renderer.fillColor = brandUIColor.withAlphaComponent(CGFloat(coverage.fillOpacity))
                // A DASHED, thin edge rather than a solid stroke. A crisp circle
                // asserts "inside this line you are reachable", and somebody
                // standing just inside it in a dead zone would have trusted a
                // line we invented from one RSSI reading. The dashes read as
                // "about this far", which is all we actually know.
                renderer.strokeColor = brandUIColor.withAlphaComponent(0.45)
                renderer.lineWidth = 1
                renderer.lineDashPattern = [4, 6]
                return renderer
            }
            return MKOverlayRenderer(overlay: overlay)
        }

        func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
            DispatchQueue.main.async {
                self.parent.region = mapView.region
            }
        }

        func mapView(_ mapView: MKMapView, didChange mode: MKUserTrackingMode, animated: Bool) {
            DispatchQueue.main.async {
                self.parent.userTrackingMode = mode
            }
        }

        @objc func handleLongPress(_ gesture: UILongPressGestureRecognizer) {
            guard gesture.state == .began,
                  let mapView = gesture.view as? MKMapView else { return }
            let point = gesture.location(in: mapView)
            let coordinate = mapView.convert(point, toCoordinateFrom: mapView)
            parent.onLongPress?(coordinate)
        }
    }
}

// MARK: - POI Annotation

final class POIAnnotation: MKPointAnnotation {
    var poiId: Int64 = 0
    var iconName: String?
    var isDestination: Bool = false
    /// __LORA_NODES_ON_THE_MAP_2026_10_28__ a radio heard over LoRa, tinted apart from
    /// the POIs so a mesh node is not mistaken for a shop.
    var isLoRaNode: Bool = false
}

// MARK: - Contact Location Annotation (shows profile picture on map)

struct ContactAnnotationData {
    let coordinate: CLLocationCoordinate2D
    let name: String
    let publicKey: String?
    let isLive: Bool
}

final class ContactAnnotation: MKPointAnnotation {
    var contactName: String = ""
    var contactPublicKey: String?
    var isLive: Bool = false
    var profileImage: UIImage?
}

// MARK: - Category Chip Button

struct CategoryChipButton: View {
    let category: POICategory
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 5) {
                Image(systemName: category.icon)
                    .font(.scaled(13, weight: .medium))
                Text(category.name)
                    .font(.scaled(13, weight: .medium))
            }
            .foregroundColor(isSelected ? .white : Theme.brand)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(
                isSelected
                    ? Theme.brand
                    : Theme.brand.opacity(0.1)
            )
            .cornerRadius(20)
        }
    }
}

// MARK: - Compact Category Chip (for grid layout)

struct CompactCategoryChip: View {
    let category: POICategory
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 3) {
                Image(systemName: category.icon)
                    .font(.scaled(16, weight: .medium))
                Text(category.name)
                    .font(.scaled(10, weight: .medium))
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .foregroundColor(isSelected ? .white : Theme.brand)
            .padding(.vertical, 8)
            .background(
                isSelected
                    ? Theme.brand
                    : Theme.brand.opacity(0.1)
            )
            .cornerRadius(10)
        }
    }
}

// MARK: - Search Overlay View

struct MapSearchOverlayView: View {
    @Binding var isPresented: Bool
    @Binding var searchResults: [MapPOI]
    @Binding var searchText: String
    @Binding var selectedCategory: POICategory?
    /// Bug 2: also clear the persistent map-marker layer when the user clears
    /// the search or deselects the active category. Optional binding so older
    /// call sites can omit it.
    var categoryMarkers: Binding<[MapPOI]>? = nil
    let onPOISelected: (MapPOI) -> Void
    var onSearchTextChanged: ((String) -> Void)?
    var onCategorySelected: ((POICategory) -> Void)?

    @FocusState private var isSearchFocused: Bool
    @State private var searchTask: DispatchWorkItem?
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.oledBlackActive) private var oledBlack

    var body: some View {
        GeometryReader { geo in
        VStack(spacing: 0) {
            // Background tap to dismiss
            Color.black.opacity(0.3)
                .onTapGesture {
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                        isPresented = false
                    }
                }

            // Search panel — takes up to 60% of screen
            VStack(spacing: 0) {
                // Drag handle
                RoundedRectangle(cornerRadius: 2.5)
                    .fill(Color.secondary.opacity(0.3))
                    .frame(width: 36, height: 5)
                    .padding(.top, 10)
                    .padding(.bottom, 12)

                // Search field
                HStack(spacing: 10) {
                    Image(systemName: "magnifyingglass")
                        .font(.scaled(16, weight: .medium))
                        .foregroundColor(.secondary)

                    TextField("map.search_placeholder".localized, text: $searchText)
                        .font(.scaled(16))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused($isSearchFocused)
                        .submitLabel(.search)
                        .onChange(of: searchText) { _, newValue in
                            // Debounce: cancel previous pending search
                            searchTask?.cancel()
                            selectedCategory = nil

                            guard !newValue.trimmingCharacters(in: .whitespaces).isEmpty else {
                                searchResults = []
                                return
                            }

                            let task = DispatchWorkItem {
                                onSearchTextChanged?(newValue)
                            }
                            searchTask = task
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4, execute: task)
                        }

                    if !searchText.isEmpty {
                        Button {
                            searchText = ""
                            searchResults = []
                            selectedCategory = nil
                            categoryMarkers?.wrappedValue = []
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.scaled(18))
                                .foregroundColor(.secondary)
                        }
                        .accessibilityLabel("common.clear".localized)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 11)
                .background(oledBlack ? Color(OLEDBlack.incomingBubbleUI) : colorScheme == .dark ? Color(white: 0.2) : Color(white: 0.94))
                .cornerRadius(12)
                .padding(.horizontal, 16)

                // Category chips
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(POICategory.all) { category in
                            CategoryChipButton(
                                category: category,
                                isSelected: selectedCategory?.id == category.id,
                                onTap: {
                                    if selectedCategory?.id == category.id {
                                        selectedCategory = nil
                                        searchResults = []
                                        categoryMarkers?.wrappedValue = []
                                    } else {
                                        selectedCategory = category
                                        searchText = ""
                                        // categoryMarkers will be replaced by
                                        // onCategorySelected → searchByMKCategory.
                                        onCategorySelected?(category)
                                    }
                                }
                            )
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }

                Divider()

                // Results list
                if searchResults.isEmpty {
                    VStack(spacing: 12) {
                        Spacer()
                        Image(systemName: "map.fill")
                            .font(.scaled(36))
                            .foregroundColor(.secondary.opacity(0.4))
                            .accessibilityHidden(true)
                        Text("map.search.empty_title".localized)
                            .font(.scaled(15))
                            .foregroundColor(.secondary)
                        Text("map.search.empty_desc".localized)
                            .font(.scaled(13))
                            .foregroundColor(.secondary.opacity(0.6))
                        Spacer()
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 200)
                    .accessibilityElement(children: .combine)
                } else {
                    let streetResults = searchResults.filter { $0.category == "street" || $0.category == "address" }
                    let placeResults = searchResults.filter { $0.category != "street" && $0.category != "address" }

                    ScrollView {
                        LazyVStack(spacing: 0) {
                            // Streets & Addresses section
                            if !streetResults.isEmpty {
                                searchSectionHeader(
                                    icon: "road.lanes",
                                    title: "map.search.section.streets_addresses".localized
                                )
                                ForEach(streetResults) { poi in
                                    searchResultRow(poi: poi, iconOverride: "road.lanes")
                                    if poi.id != streetResults.last?.id {
                                        Divider().padding(.leading, 64)
                                    }
                                }
                                if !placeResults.isEmpty {
                                    Divider().padding(.vertical, 4)
                                }
                            }

                            // Places section
                            if !placeResults.isEmpty {
                                if !streetResults.isEmpty {
                                    searchSectionHeader(
                                        icon: "mappin.circle.fill",
                                        title: "map.search.section.places".localized
                                    )
                                }
                                ForEach(placeResults) { poi in
                                    searchResultRow(poi: poi)
                                    if poi.id != placeResults.last?.id {
                                        Divider().padding(.leading, 64)
                                    }
                                }
                            }
                        }
                    }
                    .frame(maxHeight: 300)
                }
            }
            .background(
                (oledBlack ? Color.black : colorScheme == .dark ? Color(white: 0.12) : Color.white)
                    .cornerRadius(20, corners: [.topLeft, .topRight])
            )
            .frame(maxHeight: geo.size.height * 0.55)
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
        }
        } // GeometryReader
        .ignoresSafeArea(.all, edges: [.top])
        .onAppear {
            isSearchFocused = true
        }
    }

    // MARK: - Search Result Helpers

    private func searchSectionHeader(icon: String, title: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: icon)
                .font(.scaled(12, weight: .semibold))
                .foregroundColor(brandColor)
            Text(title)
                .font(.scaled(12, weight: .semibold))
                .foregroundColor(.secondary)
                .textCase(.uppercase)
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 4)
    }

    private func searchResultRow(poi: MapPOI, iconOverride: String? = nil) -> some View {
        Button {
            onPOISelected(poi)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: iconOverride ?? poi.categoryIcon)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(.white)
                    .frame(width: 36, height: 36)
                    .background(poi.category == "street" ? Color.orange : brandColor)
                    .cornerRadius(8)

                VStack(alignment: .leading, spacing: 2) {
                    Text(poi.name)
                        .font(.scaled(15, weight: .medium))
                        .foregroundColor(colorScheme == .dark ? .white : .black)
                    if let address = poi.address, !address.isEmpty {
                        Text(address)
                            .font(.scaled(12))
                            .foregroundColor(.secondary)
                            .lineLimit(1)
                    }
                    if let sub = poi.subcategory, !sub.isEmpty, poi.category == "street" {
                        Text(sub.replacingOccurrences(of: "_", with: " ").capitalized)
                            .font(.scaled(10))
                            .foregroundColor(.secondary.opacity(0.7))
                    }
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.scaled(13, weight: .medium))
                    .foregroundColor(.secondary.opacity(0.5))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
        }
    }
}

// MARK: - Region Download Placeholder

struct MapRegionDownloadPlaceholderView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Spacer()

                Image(systemName: "arrow.down.circle.fill")
                    .font(.scaled(56))
                    .foregroundColor(brandColor)

                Text("map.download_regions".localized)
                    .font(.scaled(22, weight: .bold))

                Text("map.regions.placeholder.message".localized)
                    .font(.scaled(15))
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)

                VStack(spacing: 12) {
                    ForEach(sampleRegions, id: \.0) { name, size, flag in
                        HStack(spacing: 12) {
                            Text(flag)
                                .font(.scaled(24))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(name)
                                    .font(.scaled(15, weight: .medium))
                                Text(size)
                                    .font(.scaled(12))
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            Image(systemName: "arrow.down.circle")
                                .font(.scaled(22))
                                .foregroundColor(brandColor)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                        .background(
                            (colorScheme == .dark ? Color(white: 0.15) : Color(white: 0.96))
                                .cornerRadius(12)
                        )
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 10)

                Spacer()
            }
            .navigationTitle("offline_maps_regions".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("common.close".localized) {
                        dismiss()
                    }
                    .foregroundColor(brandColor)
                }
            }
        }
    }

    private var sampleRegions: [(String, String, String)] {
        [
            ("Ile-de-France", "250 MB", "\u{1F1EB}\u{1F1F7}"),
            ("Suisse", "120 MB", "\u{1F1E8}\u{1F1ED}"),
            ("New York Metro", "150 MB", "\u{1F1FA}\u{1F1F8}"),
            ("London", "150 MB", "\u{1F1EC}\u{1F1E7}"),
        ]
    }
}

// MARK: - Corner Radius Extension

private extension View {
    func cornerRadius(_ radius: CGFloat, corners: UIRectCorner) -> some View {
        clipShape(RoundedCornerShape(radius: radius, corners: corners))
    }
}

private struct RoundedCornerShape: Shape {
    let radius: CGFloat
    let corners: UIRectCorner

    func path(in rect: CGRect) -> Path {
        let path = UIBezierPath(
            roundedRect: rect,
            byRoundingCorners: corners,
            cornerRadii: CGSize(width: radius, height: radius)
        )
        return Path(path.cgPath)
    }
}

// MARK: - Preview

#if DEBUG
struct OfflineMapView_Previews: PreviewProvider {
    static var previews: some View {
        OfflineMapView()
            .preferredColorScheme(.dark)

        OfflineMapView()
            .preferredColorScheme(.light)
    }
}
#endif

// MARK: - LoRa node card
//
// __LORA_NODE_TAP_2026_11_05__ What a radio's pin shows when you tap it.
//
// Until now it showed nothing: node ids are negative so they cannot collide with
// POI row ids, and the map's tap handler only looked for POIs. With
// `canShowCallout` false as well, a node pin was a dot you could not interrogate
// — the map could tell you a radio was THERE and nothing else about it.
//
// WHAT IT SHOWS, AND WHY EACH FIELD EARNS ITS PLACE
//
// Meshtastic's own node view is the reference, and the fields it shows are the
// ones that change what you would do next:
//
//   • last heard   — the single most important number on the card. A node heard
//                    two minutes ago is a person you can reach; the same node
//                    nine hours ago is a pin on a map. Shown first, and in words.
//   • signal       — as BARS, with the dBm underneath for whoever wants it.
//                    "-78 dBm" means nothing to most people and four bars do;
//                    `MeshtasticNode.signalBars` already draws that line, and the
//                    0 case ("heard of, never heard") is a real state, not a gap.
//   • hops         — direct or relayed. It is the difference between "in range"
//                    and "someone else is carrying this for me".
//   • distance     — only when BOTH sides have a fix, and never as "0 km" when
//                    one of them does not.
//
// Battery is deliberately absent: `MeshtasticNode` does not carry one, and a
// field invented for visual balance is a field that lies.
private struct LoRaNodeMapCard: View {
    let node: MeshtasticNode
    let myLocation: CLLocationCoordinate2D?
    let onClose: () -> Void

    private var displayName: String {
        let long = node.longName.trimmingCharacters(in: .whitespacesAndNewlines)
        if !long.isEmpty { return long }
        let short = node.shortName.trimmingCharacters(in: .whitespacesAndNewlines)
        if !short.isEmpty { return short }
        return String(format: "!%08x", node.num)
    }

    /// Always available, always true, and the thing two operators read to each
    /// other out loud.
    private var nodeId: String { String(format: "!%08x", node.num) }

    private var heard: String {
        let s = Date().timeIntervalSince(node.lastHeard)
        if s < 60 { return "heard just now" }
        if s < 3600 { return "heard \(Int(s / 60)) min ago" }
        if s < 86400 { return "heard \(Int(s / 3600)) h ago" }
        return "heard \(Int(s / 86400)) d ago"
    }

    /// Green only while the node is recent enough to act on. The boundary is the
    /// point past which "it is there" stops implying "you can reach it".
    private var isFresh: Bool { Date().timeIntervalSince(node.lastHeard) < 900 }

    private var distance: String? {
        guard let me = myLocation, let lat = node.latitude, let lon = node.longitude else { return nil }
        let d = CLLocation(latitude: me.latitude, longitude: me.longitude)
            .distance(from: CLLocation(latitude: lat, longitude: lon))
        return d < 1000 ? String(format: "%.0f m away", d) : String(format: "%.1f km away", d / 1000)
    }

    var body: some View {
        VStack {
            Spacer()
            VStack(spacing: 0) {
                RoundedRectangle(cornerRadius: 2.5)
                    .fill(Color.secondary.opacity(0.3))
                    .frame(width: 36, height: 5)
                    .padding(.top, 10)
                    .padding(.bottom, 14)
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 12) {
                        Image(systemName: "antenna.radiowaves.left.and.right")
                            .font(.system(size: 22, weight: .semibold))
                            .foregroundColor(.white)
                            .frame(width: 46, height: 46)
                            .background(Color.orange)
                            .cornerRadius(12)
                            .accessibilityHidden(true)

                        VStack(alignment: .leading, spacing: 3) {
                            Text(displayName)
                                .font(.scaled(18, weight: .bold))
                                .lineLimit(2)
                            Text(nodeId)
                                .font(.system(.footnote, design: .monospaced))
                                .foregroundColor(.secondary)
                        }
                        .accessibilityElement(children: .combine)

                        Spacer()

                        Button(action: onClose) {
                            Image(systemName: "xmark.circle.fill")
                                .font(.system(size: 24))
                                .foregroundColor(.secondary.opacity(0.6))
                                .frame(width: 44, height: 44)
                        }
                        .accessibilityLabel("Close")
                    }

                    // Freshness first: it decides whether anything else matters.
                    HStack(spacing: 6) {
                        Circle()
                            .fill(isFresh ? Color.green : Color.secondary.opacity(0.5))
                            .frame(width: 8, height: 8)
                        Text(heard)
                            .font(.scaled(14, weight: isFresh ? .semibold : .regular))
                            .foregroundColor(isFresh ? .primary : .secondary)
                    }
                    .accessibilityElement(children: .combine)

                    HStack(spacing: 18) {
                        // Signal, as bars. The 0 case is "we know this radio
                        // exists but have never heard a packet from it" — a
                        // NodeInfo can arrive without one — so it reads as a row
                        // of empty bars rather than as one bar.
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(spacing: 2) {
                                ForEach(1...4, id: \.self) { bar in
                                    Capsule()
                                        .fill(bar <= node.signalBars ? Color.green : Color.secondary.opacity(0.25))
                                        .frame(width: 4, height: CGFloat(5 + bar * 3))
                                }
                            }
                            .frame(height: 17, alignment: .bottom)
                            Text(node.rssi.map { "\($0) dBm" } ?? "not heard yet")
                                .font(.scaled(11))
                                .foregroundColor(.secondary)
                        }
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(node.rssi == nil
                            ? "Signal unknown, no packet heard yet"
                            : "Signal \(node.signalBars) of 4, \(node.rssi ?? 0) dBm")

                        if let hops = node.hopsAway {
                            statBlock(hops == 0 ? "Direct" : "\(hops) hop\(hops == 1 ? "" : "s")",
                                      caption: hops == 0 ? "in range" : "relayed")
                        }
                        if let distance {
                            statBlock(distance, caption: "from you")
                        }
                        Spacer()
                    }

                    // __MESSAGE_THE_NODE_FROM_THE_MAP_2026_11_07__
                    //
                    // The card told you a radio's name, signal, hop count and
                    // distance, and then offered exactly one action: Close. You
                    // could see a node two streets away and had no way to say
                    // anything to it without leaving the map and hunting for a
                    // thread that might not exist yet.
                    //
                    // WHERE THIS OPENS. `conversationKey(forNode:)`, not
                    // `fallbackConversationKey`: when the radio has PROVED an
                    // OSHI identity this routes to that contact's real thread,
                    // so the message is ratchet-encrypted like any other. Only
                    // an unproved radio lands in the cleartext `lora!` thread,
                    // which the chat already labels as interop. Sending the same
                    // tap down an encrypted or a plaintext lane depending on
                    // what is true is the whole point of that function.
                    //
                    // HOPS come free: a unicast is addressed to the node number
                    // and Meshtastic routes it through the mesh, so a relayed
                    // node is reachable exactly like a direct one. That is why
                    // this is offered whatever `hopsAway` says.
                    //
                    // Offered with no radio attached too: the thread and its
                    // history are worth reading either way, and the send path
                    // already refuses with "no radio attached" rather than
                    // failing silently.
                    Button {
                        let key = LoRaNodeIdentityMap.shared.conversationKey(forNode: node.num)
                        onClose()
                        NotificationCenter.default.post(
                            name: NSNotification.Name("OpenChat"),
                            object: nil,
                            userInfo: ["peerPublicKey": key]
                        )
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: "bubble.left.and.bubble.right.fill")
                            Text("lora.node.message".localized)
                                .font(.scaled(15, weight: .semibold))
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Color.orange)
                        .foregroundColor(.white)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .accessibilityLabel("lora.node.message".localized)
                }
                .padding(.horizontal, 18)
                .padding(.bottom, 20)
            }
            .background(.regularMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .padding(.horizontal, 10)
            .padding(.bottom, 10)
            .shadow(color: .black.opacity(0.18), radius: 18, y: 6)
        }
    }

    @ViewBuilder
    private func statBlock(_ value: String, caption: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(value).font(.scaled(14, weight: .semibold))
            Text(caption).font(.scaled(11)).foregroundColor(.secondary)
        }
        .accessibilityElement(children: .combine)
    }
}
