//
//  CheckInView.swift
//  OSHI
//
//  Accompagnement/Check-In Safety Feature UI Components
//

import SwiftUI
import MapKit
import CoreLocation

// MARK: - Layout
/// Cap content width on wide screens (iPad / landscape), centered, so the
/// check-in form & cards don't stretch edge-to-edge. SE-safe (max only).
private let checkInContentMaxWidth: CGFloat = 560

// MARK: - Check-In Sheet (Start a Check-In)

struct CheckInSheet: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var checkInManager = CheckInManager.shared
    @StateObject private var locationManager = LocationSharingManager.shared

    let contactPublicKey: String
    let contactName: String
    let onSend: (CheckInMessage) -> Void

    @State private var searchText: String = ""
    @State private var searchResults: [MKMapItem] = []
    @State private var selectedDestination: MKMapItem?
    @State private var selectedTransportMode: TransportMode = .driving
    @State private var extraTime: Int = 0
    @State private var calculatedETA: Date?
    @State private var isCalculatingETA: Bool = false
    @State private var showPermissionAlert: Bool = false

    @State private var region = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 48.8566, longitude: 2.3522),
        span: MKCoordinateSpan(latitudeDelta: 0.05, longitudeDelta: 0.05)
    )

    private let extraTimeOptions = [0, 15, 30, 60]

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 20) {
                    // Header
                    headerSection

                    // Destination Search
                    destinationSection

                    // Map Preview
                    if selectedDestination != nil {
                        mapPreviewSection
                    }

                    // Transport Mode
                    transportModeSection

                    // Extra Time
                    extraTimeSection

                    // ETA Display
                    if calculatedETA != nil || isCalculatingETA {
                        etaSection
                    }

                    // Start Button
                    startButton

                    Spacer(minLength: 20)
                }
                .padding()
                .frame(maxWidth: checkInContentMaxWidth)
                .frame(maxWidth: .infinity)
            }
            .navigationTitle(NSLocalizedString("checkin.title", comment: "Accompagnement"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button(NSLocalizedString("common.cancel", comment: "Cancel")) {
                        dismiss()
                    }
                }
            }
        }
        .onAppear {
            checkLocationPermission()
            if let location = locationManager.currentLocation {
                region.center = location.coordinate
            }
        }
        .alert(NSLocalizedString("checkin.permission.title", comment: "Location Required"),
               isPresented: $showPermissionAlert) {
            Button(NSLocalizedString("common.open_settings", comment: "Open Settings")) {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            Button(NSLocalizedString("common.cancel", comment: "Cancel"), role: .cancel) { }
        } message: {
            Text(NSLocalizedString("checkin.permission.message", comment: "OSHI needs location access to monitor your journey and notify your contact when you arrive."))
        }
    }

    // MARK: - Header Section

    private var headerSection: some View {
        VStack(spacing: 8) {
            Image(systemName: "shield.checkered")
                .font(.scaled(50))
                .foregroundColor(.blue)
                .accessibilityHidden(true)

            Text(NSLocalizedString("checkin.subtitle", comment: "Let someone know when you arrive safely"))
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(.vertical)
    }

    // MARK: - Destination Section

    private var destinationSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(NSLocalizedString("checkin.where_going", comment: "Where are you going?"))
                .font(.headline)

            // Search Field
            HStack {
                Image(systemName: "magnifyingglass")
                    .foregroundColor(.secondary)
                    .accessibilityHidden(true)

                TextField(NSLocalizedString("checkin.search_destination", comment: "Search destination..."), text: $searchText)
                    .textFieldStyle(PlainTextFieldStyle())
                    .onChange(of: searchText) { _, newValue in
                        searchDestination(query: newValue)
                    }

                if !searchText.isEmpty {
                    Button {
                        searchText = ""
                        searchResults = []
                        selectedDestination = nil
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(.secondary)
                    }
                    .accessibilityLabel("common.clear".localized)
                }
            }
            .padding()
            .background(Color(.systemGray6))
            .cornerRadius(12)

            // Search Results
            if !searchResults.isEmpty && selectedDestination == nil {
                VStack(spacing: 0) {
                    ForEach(searchResults.prefix(5), id: \.self) { item in
                        Button {
                            selectDestination(item)
                        } label: {
                            HStack {
                                Image(systemName: "mappin.circle.fill")
                                    .foregroundColor(.red)
                                    .accessibilityHidden(true)

                                VStack(alignment: .leading) {
                                    Text(item.name ?? "")
                                        .font(.body)
                                        .foregroundColor(.primary)

                                    if let address = item.placemark.title {
                                        Text(address)
                                            .font(.caption)
                                            .foregroundColor(.secondary)
                                            .lineLimit(1)
                                    }
                                }

                                Spacer()
                            }
                            .padding(.vertical, 10)
                            .padding(.horizontal)
                        }
                        .accessibilityElement(children: .combine)

                        if item != searchResults.prefix(5).last {
                            Divider()
                        }
                    }
                }
                .background(Color(.systemBackground))
                .cornerRadius(12)
                .shadow(color: .black.opacity(0.1), radius: 5, y: 2)
            }

            // Selected Destination
            if let destination = selectedDestination {
                HStack {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(.green)
                        .accessibilityHidden(true)

                    VStack(alignment: .leading) {
                        Text(destination.name ?? "")
                            .font(.body)
                            .fontWeight(.medium)

                        if let address = destination.placemark.title {
                            Text(address)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                    .accessibilityElement(children: .combine)

                    Spacer()

                    Button {
                        selectedDestination = nil
                        calculatedETA = nil
                    } label: {
                        Image(systemName: "xmark")
                            .foregroundColor(.secondary)
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel("checkin.remove_destination".localized)
                }
                .padding()
                .background(Color(.systemGray6))
                .cornerRadius(12)
            }
        }
    }

    // MARK: - Map Preview Section

    private var mapPreviewSection: some View {
        Map(position: .constant(.region(region))) {
            ForEach(mapAnnotations) { item in
                Annotation("", coordinate: item.coordinate) {
                    if item.isDestination {
                        Image(systemName: "mappin.circle.fill")
                            .font(.title)
                            .foregroundColor(.red)
                    } else {
                        Circle()
                            .fill(Color.blue)
                            .frame(width: 12, height: 12)
                            .overlay(
                                Circle()
                                    .stroke(Color.white, lineWidth: 2)
                            )
                    }
                }
            }
        }
        .frame(height: 180)
        .cornerRadius(12)
        .accessibilityElement()
        .accessibilityLabel("checkin.route_preview".localized)
    }

    private var mapAnnotations: [MapAnnotationItem] {
        var items: [MapAnnotationItem] = []

        if let currentLocation = locationManager.currentLocation {
            items.append(MapAnnotationItem(
                coordinate: currentLocation.coordinate,
                isDestination: false
            ))
        }

        if let destination = selectedDestination {
            items.append(MapAnnotationItem(
                coordinate: destination.placemark.coordinate,
                isDestination: true
            ))
        }

        return items
    }

    // MARK: - Transport Mode Section

    private var transportModeSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(NSLocalizedString("checkin.transport_mode", comment: "How are you getting there?"))
                .font(.headline)

            HStack(spacing: 12) {
                ForEach(TransportMode.allCases, id: \.self) { mode in
                    TransportModeButton(
                        mode: mode,
                        isSelected: selectedTransportMode == mode
                    ) {
                        selectedTransportMode = mode
                        recalculateETA()
                    }
                }
            }
        }
    }

    // MARK: - Extra Time Section

    private var extraTimeSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(NSLocalizedString("checkin.extra_time", comment: "Add extra time?"))
                .font(.headline)

            HStack(spacing: 12) {
                ForEach(extraTimeOptions, id: \.self) { minutes in
                    Button {
                        extraTime = minutes
                    } label: {
                        Text(minutes == 0 ? NSLocalizedString("checkin.none", comment: "None") : "+\(minutes) min")
                            .font(.subheadline)
                            .fontWeight(extraTime == minutes ? .semibold : .regular)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 10)
                            .background(extraTime == minutes ? Color.blue : Color(.systemGray6))
                            .foregroundColor(extraTime == minutes ? .white : .primary)
                            .cornerRadius(20)
                    }
                }
            }
        }
    }

    // MARK: - ETA Section

    private var etaSection: some View {
        HStack {
            Image(systemName: "clock")
                .foregroundColor(.blue)
                .accessibilityHidden(true)

            if isCalculatingETA {
                ProgressView()
                    .scaleEffect(0.8)
                Text(NSLocalizedString("checkin.calculating", comment: "Calculating..."))
                    .foregroundColor(.secondary)
            } else if let eta = calculatedETA {
                Text(etaText(for: eta))
                    .fontWeight(.medium)
            }

            Spacer()
        }
        .padding()
        .background(Color(.systemGray6))
        .cornerRadius(12)
        .accessibilityElement(children: .combine)
        .transition(.opacity)
    }

    // MARK: - Start Button

    // Helper to format ETA text
    private func etaText(for eta: Date) -> String {
        let effectiveETA = eta.addingTimeInterval(TimeInterval(extraTime * 60))
        let formatter = DateFormatter()
        formatter.timeStyle = .short
        let duration = effectiveETA.timeIntervalSince(Date())
        let minutes = Int(duration / 60)
        return String(format: NSLocalizedString("checkin.eta_format", comment: "ETA: %@ (%d min)"),
                     formatter.string(from: effectiveETA),
                     minutes)
    }

    private var startButton: some View {
        Button {
            #if !targetEnvironment(macCatalyst)
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            #endif
            startCheckIn()
        } label: {
            HStack {
                Image(systemName: "shield.checkered")
                    .accessibilityHidden(true)
                Text(NSLocalizedString("checkin.start", comment: "Start Check-In"))
                    .fontWeight(.semibold)
            }
            .frame(maxWidth: .infinity)
            .padding()
            .background(canStartCheckIn ? Color.blue : Color.gray)
            .foregroundColor(.white)
            .cornerRadius(12)
        }
        .disabled(!canStartCheckIn)
        .accessibilityLabel(NSLocalizedString("checkin.start", comment: "Start Check-In"))
    }

    private var canStartCheckIn: Bool {
        selectedDestination != nil && calculatedETA != nil && !isCalculatingETA
    }

    // MARK: - Helper Methods

    private func checkLocationPermission() {
        let status = CLLocationManager().authorizationStatus
        if status == .denied || status == .restricted {
            showPermissionAlert = true
        }
    }

    private func searchDestination(query: String) {
        guard query.count >= 3 else {
            searchResults = []
            return
        }

        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = query

        if let location = locationManager.currentLocation {
            request.region = MKCoordinateRegion(
                center: location.coordinate,
                latitudinalMeters: 50000,
                longitudinalMeters: 50000
            )
        }

        let search = MKLocalSearch(request: request)
        search.start { response, error in
            if let response = response {
                searchResults = response.mapItems
            }
        }
    }

    private func selectDestination(_ item: MKMapItem) {
        selectedDestination = item
        searchText = item.name ?? ""
        searchResults = []

        // Update map region
        region = MKCoordinateRegion(
            center: item.placemark.coordinate,
            span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)
        )

        recalculateETA()
    }

    private func recalculateETA() {
        guard let destination = selectedDestination else { return }

        isCalculatingETA = true
        calculatedETA = nil

        Task {
            let eta = await checkInManager.calculateETA(
                to: destination.placemark.coordinate,
                mode: selectedTransportMode
            )

            await MainActor.run {
                calculatedETA = eta
                isCalculatingETA = false
            }
        }
    }

    private func startCheckIn() {
        guard let destination = selectedDestination,
              calculatedETA != nil else { return }

        let address = destination.placemark.title ?? destination.name ?? ""

        Task {
            await checkInManager.startCheckIn(
                contactPublicKey: contactPublicKey,
                destination: destination.placemark.coordinate,
                destinationAddress: address,
                transportMode: selectedTransportMode,
                extraTimeMinutes: extraTime,
                onMessageSend: { message in
                    onSend(message)
                }
            )

            await MainActor.run {
                dismiss()
            }
        }
    }
}

// MARK: - Transport Mode Button

struct TransportModeButton: View {
    let mode: TransportMode
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: mode.icon)
                    .font(.title2)
                    .accessibilityHidden(true)

                Text(mode.localizedName)
                    .font(.caption)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(isSelected ? Color.blue : Color(.systemGray6))
            .foregroundColor(isSelected ? .white : .primary)
            .cornerRadius(12)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(mode.localizedName)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

// MARK: - Map Annotation Item

struct MapAnnotationItem: Identifiable {
    let id = UUID()
    let coordinate: CLLocationCoordinate2D
    let isDestination: Bool
}

// MARK: - Check-In Active Banner

struct CheckInActiveBanner: View {
    @ObservedObject var checkInManager = CheckInManager.shared

    var body: some View {
        if let session = checkInManager.activeSession, session.status == .active || session.status == .expired {
            VStack(spacing: 12) {
                HStack {
                    Image(systemName: session.status == .expired ? "exclamationmark.triangle.fill" : "shield.checkered")
                        .foregroundColor(session.status == .expired ? .orange : .blue)
                        .accessibilityHidden(true)

                    VStack(alignment: .leading, spacing: 2) {
                        Text(NSLocalizedString("checkin.active", comment: "Check-In Active"))
                            .font(.subheadline)
                            .fontWeight(.semibold)

                        Text("\(session.destinationAddress) • \(session.formattedRemainingTime)")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    .accessibilityElement(children: .combine)

                    Spacer()

                    if let distance = checkInManager.distanceToDestination {
                        Text(formatDistance(distance))
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }

                HStack(spacing: 12) {
                    Button {
                        #if !targetEnvironment(macCatalyst)
                        UINotificationFeedbackGenerator().notificationOccurred(.success)
                        #endif
                        checkInManager.stopCheckIn(reason: .arrived)
                    } label: {
                        HStack {
                            Image(systemName: "checkmark.circle.fill")
                                .accessibilityHidden(true)
                            Text(NSLocalizedString("checkin.arrived", comment: "I've Arrived"))
                        }
                        .font(.caption)
                        .fontWeight(.medium)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(Color.green)
                        .foregroundColor(.white)
                        .cornerRadius(20)
                    }
                    .accessibilityLabel(NSLocalizedString("checkin.arrived", comment: "I've Arrived"))

                    Button {
                        #if !targetEnvironment(macCatalyst)
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        #endif
                        checkInManager.extendTime(minutes: 15)
                    } label: {
                        HStack {
                            Image(systemName: "clock.arrow.circlepath")
                                .accessibilityHidden(true)
                            Text(NSLocalizedString("checkin.extend_15", comment: "+15 min"))
                        }
                        .font(.caption)
                        .fontWeight(.medium)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(Color.orange)
                        .foregroundColor(.white)
                        .cornerRadius(20)
                    }
                    .accessibilityLabel(NSLocalizedString("checkin.extend_15", comment: "+15 min"))

                    Button {
                        #if !targetEnvironment(macCatalyst)
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        #endif
                        checkInManager.stopCheckIn(reason: .cancelled)
                    } label: {
                        HStack {
                            Image(systemName: "xmark")
                                .accessibilityHidden(true)
                            Text(NSLocalizedString("checkin.stop", comment: "Stop"))
                        }
                        .font(.caption)
                        .fontWeight(.medium)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(Color(.systemGray5))
                        .foregroundColor(.primary)
                        .cornerRadius(20)
                    }
                    .accessibilityLabel(NSLocalizedString("checkin.stop", comment: "Stop"))
                }
            }
            .padding()
            .background(Color(.systemBackground))
            .cornerRadius(16)
            .shadow(color: .black.opacity(0.1), radius: 5, y: 2)
            .frame(maxWidth: checkInContentMaxWidth)
            .frame(maxWidth: .infinity)
            .padding(.horizontal)
        }
    }

    private func formatDistance(_ meters: CLLocationDistance) -> String {
        if meters >= 1000 {
            return String(format: "%.1f km", meters / 1000)
        } else {
            return String(format: "%.0f m", meters)
        }
    }
}

// MARK: - Check-In Message Bubble

struct CheckInMessageBubble: View {
    let message: CheckInMessage
    let isFromCurrentUser: Bool

    // 🔧 FIX (2026-06-04): use Map(position:) with a @State camera so the
    // mini-map RE-CENTERS as the message gets in-place updates carrying a
    // newer coord. With Map(initialPosition:) the camera was anchored to
    // the very first coord forever and a walking friend drifted off-screen
    // within minutes.
    @State private var cameraPosition: MapCameraPosition = .automatic

    var body: some View {
        VStack(alignment: isFromCurrentUser ? .trailing : .leading, spacing: 8) {
            HStack {
                Image(systemName: message.displayIcon)
                    .foregroundColor(iconColor)
                    .accessibilityHidden(true)

                Text(message.displayTitle)
                    .font(.subheadline)
                    .fontWeight(.semibold)
            }

            if let address = message.destinationAddress {
                HStack {
                    Image(systemName: "mappin.circle")
                        .foregroundColor(.secondary)
                        .accessibilityHidden(true)
                    Text(address)
                        .font(.caption)
                }
                .accessibilityElement(children: .combine)
            }

            if let mode = message.transportMode {
                HStack {
                    Text(mode.emoji)
                        .accessibilityHidden(true)
                    Text(mode.localizedName)
                        .font(.caption)
                }
                .accessibilityElement(children: .combine)
            }

            if let eta = message.estimatedArrival {
                HStack {
                    Image(systemName: "clock")
                        .foregroundColor(.secondary)
                        .accessibilityHidden(true)
                    Text(formatETA(eta))
                        .font(.caption)
                }
                .accessibilityElement(children: .combine)
            }

            // 🔧 FIX (2026-06-04): show the sender's current position on a
            // mini map for `.update` and `.warning` bubbles too, not just
            // `.emergency`. Previously the receiver had no way to see where
            // their contact actually was between `started` and `arrived` —
            // they only saw a grey icon every 5 min. Skips `.started` (the
            // destination is shown there, not the live position) and
            // `.arrived/.cancelled` (no movement to track).
            //
            // Map uses (position: $cameraPosition) and re-centers via
            // .onChange(of:) so in-place message updates move the camera too.
            // .allowsHitTesting(false) prevents the Map from eating vertical
            // pan gestures inside the chat ScrollView — taps still hit the
            // overlaid onTapGesture for "open in Maps".
            if (message.type == .update || message.type == .warning),
               let liveCoord = message.currentLocation {
                Map(position: $cameraPosition) {
                    Annotation("", coordinate: liveCoord) {
                        ZStack {
                            Circle().fill(iconColor.opacity(0.3)).frame(width: 32, height: 32)
                            Circle().fill(iconColor).frame(width: 14, height: 14)
                                .overlay(Circle().stroke(Color.white, lineWidth: 2))
                        }
                    }
                }
                .frame(height: 110)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .allowsHitTesting(false)
                .overlay(
                    Color.clear
                        .contentShape(Rectangle())
                        .onTapGesture { openInMaps(coordinate: liveCoord) }
                        .accessibilityElement()
                        .accessibilityAddTraits(.isButton)
                        .accessibilityLabel("checkin.live_location_map".localized)
                        .accessibilityHint("checkin.open_map".localized)
                )
                .onAppear {
                    cameraPosition = .region(MKCoordinateRegion(
                        center: liveCoord,
                        span: MKCoordinateSpan(latitudeDelta: 0.005, longitudeDelta: 0.005)
                    ))
                }
                .onChange(of: liveCoord.latitude) { _, newLat in
                    cameraPosition = .region(MKCoordinateRegion(
                        center: CLLocationCoordinate2D(latitude: newLat, longitude: liveCoord.longitude),
                        span: MKCoordinateSpan(latitudeDelta: 0.005, longitudeDelta: 0.005)
                    ))
                }
                .onChange(of: liveCoord.longitude) { _, newLng in
                    cameraPosition = .region(MKCoordinateRegion(
                        center: CLLocationCoordinate2D(latitude: liveCoord.latitude, longitude: newLng),
                        span: MKCoordinateSpan(latitudeDelta: 0.005, longitudeDelta: 0.005)
                    ))
                }
            }

            // Emergency Data
            if let emergency = message.emergencyData {
                VStack(alignment: .leading, spacing: 6) {
                    Divider()

                    HStack {
                        Image(systemName: "battery.100")
                            .foregroundColor(.secondary)
                            .accessibilityHidden(true)
                        Text(emergency.formattedBattery)
                            .font(.caption)
                    }
                    .accessibilityElement(children: .combine)

                    HStack {
                        Image(systemName: "wifi")
                            .foregroundColor(.secondary)
                            .accessibilityHidden(true)
                        Text(emergency.networkType)
                            .font(.caption)
                    }
                    .accessibilityElement(children: .combine)

                    HStack {
                        Image(systemName: "clock")
                            .foregroundColor(.secondary)
                            .accessibilityHidden(true)
                        Text(formatLastUpdate(emergency.lastLocationTime))
                            .font(.caption)
                    }
                    .accessibilityElement(children: .combine)

                    // Map Link
                    if let location = message.currentLocation {
                        Button {
                            #if !targetEnvironment(macCatalyst)
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            #endif
                            openInMaps(coordinate: location)
                        } label: {
                            HStack {
                                Image(systemName: "map")
                                    .accessibilityHidden(true)
                                Text(NSLocalizedString("checkin.open_map", comment: "Open in Maps"))
                            }
                            .font(.caption)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(Color.blue)
                            .foregroundColor(.white)
                            .cornerRadius(16)
                        }
                        .accessibilityLabel(NSLocalizedString("checkin.open_map", comment: "Open in Maps"))
                    }
                }
            }

            // Timestamp
            Text(formatTime(message.timestamp))
                .font(.caption2)
                .foregroundColor(.secondary)
        }
        .padding(12)
        .background(bubbleBackground)
        .cornerRadius(16)
        .frame(maxWidth: 280, alignment: isFromCurrentUser ? .trailing : .leading)
    }

    private var iconColor: Color {
        switch message.type {
        case .started: return .blue
        case .update: return .gray
        case .arrived: return .green
        case .extended: return .orange
        case .cancelled: return .gray
        case .warning: return .orange
        case .emergency: return .red
        }
    }

    private var bubbleBackground: Color {
        if message.type == .emergency {
            return Color.red.opacity(0.1)
        }
        return isFromCurrentUser ? Color.blue.opacity(0.1) : Color(.systemGray6)
    }

    private func formatTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }

    private func formatETA(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.timeStyle = .short
        return String(format: NSLocalizedString("checkin.eta", comment: "ETA: %@"), formatter.string(from: date))
    }

    private func formatLastUpdate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .none
        formatter.timeStyle = .short
        return String(format: NSLocalizedString("checkin.last_update", comment: "Last update: %@"), formatter.string(from: date))
    }

    private func openInMaps(coordinate: CLLocationCoordinate2D) {
        // Settings › Maps (see MapOpener) — was hard-wired to Apple Maps.
        MapOpener.open(coordinate: coordinate,
                       name: NSLocalizedString("checkin.last_location", comment: "Last Known Location"))
    }
}

// MARK: - Check-In Warning Alert

struct CheckInWarningAlert: View {
    @ObservedObject var checkInManager = CheckInManager.shared

    var body: some View {
        EmptyView()
            .alert(
                NSLocalizedString("checkin.warning.title", comment: "Are you okay?"),
                isPresented: $checkInManager.showWarningAlert
            ) {
                Button(NSLocalizedString("checkin.im_safe", comment: "I'm Safe")) {
                    checkInManager.confirmSafe()
                }

                Button(NSLocalizedString("checkin.extend_time", comment: "Extend Time")) {
                    checkInManager.extendTime(minutes: 30)
                }

                Button(NSLocalizedString("checkin.arrived", comment: "I've Arrived")) {
                    checkInManager.stopCheckIn(reason: .arrived)
                }
            } message: {
                Text(NSLocalizedString("checkin.warning.message", comment: "You haven't arrived at your destination yet. Your contact will be notified if you don't respond."))
            }
    }
}
