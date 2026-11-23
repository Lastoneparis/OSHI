//
//  LocationPingPickerView.swift
//  OSHI
//
//  __LOCATION_PING_2026_10_09__ « Possibilité de mettre des ping very user
//  friendly et d'envoyer le ping de location à quelqu'un par message. »
//
//  Until now a location message could only ever be YOUR OWN position:
//  `LocationShareSheet` offers "share current" and the live durations, and its
//  map is built with `interactionModes: []` — a picture, not a map. There was
//  no way to say "meet me HERE" about a place you are not standing in.
//
//  Nothing on the wire or in the receiving UI needed to change for this. A ping
//  is an ordinary one-shot `LocationMessage` (`isLive: false`) at a chosen
//  coordinate, so it travels through `sendLocationMessage` and renders in the
//  existing bubble on iOS, Android and desktop, including on builds that
//  predate this screen. That is the whole reason it is built this way.
//
//  The interaction is "drag the map under a fixed pin" rather than a long-press
//  drop. Long-press hides the action behind a gesture nobody is told about, and
//  it is hard to be precise with a thumb; a centred pin is always visible,
//  always the thing that will be sent, and reachable one-handed.
//

import SwiftUI
import MapKit
import CoreLocation

// `Theme.brand` and not `appBrandColor`: that name is declared `private` in
// several view files, so it is not visible from a new one.

struct LocationPingPickerView: View {
    /// Called with the ping the user confirmed. The caller sends it exactly as
    /// it sends a shared position — see `ChatView.sendLocationMessage`.
    let onSendPing: (LocationMessage) -> Void
    /// Shown in the title so a ping cannot go to the wrong person unnoticed —
    /// same reasoning as `LocationShareSheet.recipientName`.
    var recipientName: String = ""

    @Environment(\.dismiss) private var dismiss
    @StateObject private var locationManager = LocationSharingManager.shared

    @State private var camera: MapCameraPosition = .automatic
    /// The coordinate under the pin. Updated as the map settles.
    @State private var pinned: CLLocationCoordinate2D?
    @State private var address: String?
    @State private var isResolving = false
    /// Cancels the in-flight reverse geocode when the map moves again, so a
    /// slow answer for an old centre cannot overwrite a newer one.
    @State private var geocodeTask: Task<Void, Never>?

    private let geocoder = CLGeocoder()

    var body: some View {
        NavigationView {
            ZStack(alignment: .bottom) {
                mapLayer
                controls
            }
            .navigationTitle(recipientName.isEmpty
                             ? "location.ping.title".localized
                             : "location.ping.title_to".localized(with: recipientName))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("common.cancel".localized) { dismiss() }
                }
            }
            .onAppear(perform: centreOnUserIfPossible)
            .onDisappear { geocodeTask?.cancel() }
        }
    }

    // MARK: - Map

    private var mapLayer: some View {
        Map(position: $camera)
            .mapStyle(.standard(elevation: .flat))
            .ignoresSafeArea(edges: .top)
            .onMapCameraChange(frequency: .onEnd) { context in
                // `.onEnd` rather than `.continuous`: a reverse geocode per
                // frame would hammer CLGeocoder, which rate-limits and then
                // starts returning errors for everyone in the process.
                let centre = context.region.center
                pinned = centre
                scheduleGeocode(for: centre)
            }
            .overlay(alignment: .center) { crosshair }
            // No MapReader / tap-to-place: the selection is whatever is under
            // the fixed centre pin, so a screen-point-to-coordinate conversion
            // is not needed and a second way to choose would just compete
            // with the pin.
            .mapControls { MapCompass() }
            .accessibilityLabel("location.ping.map_hint".localized)
            .accessibilityValue(address ?? "")
    }

    /// A pin whose TIP is the selected point, not its centre — the glyph is
    /// offset by half its height so what the user aims at is what gets sent.
    private var crosshair: some View {
        VStack(spacing: 0) {
            Image(systemName: "mappin.circle.fill")
                .font(.system(size: 36))
                .foregroundStyle(Theme.brand, .white)
                .shadow(color: .black.opacity(0.28), radius: 4, y: 2)
            Rectangle()
                .fill(Theme.brand)
                .frame(width: 2, height: 12)
            Circle()
                .fill(Theme.brand.opacity(0.35))
                .frame(width: 8, height: 8)
        }
        .offset(y: -28)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    // MARK: - Controls

    private var controls: some View {
        VStack(spacing: 12) {
            addressCard

            HStack(spacing: 10) {
                Button(action: centreOnUserIfPossible) {
                    Image(systemName: "location.fill")
                        .font(.headline)
                        .foregroundColor(Theme.brand)
                        .frame(width: 52, height: 52)
                        .background(.regularMaterial,
                                    in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .disabled(locationManager.currentLocation == nil)
                .opacity(locationManager.currentLocation == nil ? 0.5 : 1)
                .accessibilityLabel("location.ping.recentre".localized)

                Button(action: send) {
                    HStack(spacing: 8) {
                        Image(systemName: "paperplane.fill")
                        Text("location.ping.send".localized)
                            .font(.system(.headline, design: .rounded))
                    }
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(Theme.brand,
                                in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .disabled(pinned == nil)
                .opacity(pinned == nil ? 0.5 : 1)
                .accessibilityLabel("location.ping.send".localized)
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 16)
        .frame(maxWidth: 520)
    }

    private var addressCard: some View {
        HStack(spacing: 10) {
            Image(systemName: "mappin.and.ellipse")
                .foregroundColor(Theme.brand)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("location.ping.this_spot".localized)
                    .font(.caption)
                    .foregroundColor(.secondary)
                // The coordinate is the fallback, never a spinner on its own:
                // the user must be able to send a ping in a place with no
                // reverse-geocoding coverage, or offline.
                Text(address ?? coordinateLabel)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(2)
                    .truncationMode(.tail)
            }
            Spacer(minLength: 0)
            if isResolving {
                ProgressView().controlSize(.small)
            }
        }
        .padding(12)
        .background(.regularMaterial,
                    in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private var coordinateLabel: String {
        guard let pinned else { return "location.ping.move_map".localized }
        return String(format: "%.5f, %.5f", pinned.latitude, pinned.longitude)
    }

    // MARK: - Actions

    private func centreOnUserIfPossible() {
        guard let here = locationManager.currentLocation else { return }
        let region = MKCoordinateRegion(center: here.coordinate,
                                        latitudinalMeters: 600,
                                        longitudinalMeters: 600)
        withAnimation(.easeInOut(duration: 0.3)) { camera = .region(region) }
        pinned = here.coordinate
        scheduleGeocode(for: here.coordinate)
    }

    /// Debounced reverse geocode. The delay is what stops a pan across a city
    /// from queueing a dozen requests that all resolve after the user stopped.
    private func scheduleGeocode(for coordinate: CLLocationCoordinate2D) {
        geocodeTask?.cancel()
        isResolving = true
        geocodeTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 450_000_000)
            if Task.isCancelled { return }
            let placemarks = try? await geocoder.reverseGeocodeLocation(
                CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude))
            if Task.isCancelled { return }
            address = placemarks?.first.flatMap(Self.describe)
            isResolving = false
        }
    }

    /// A short, human line: street level when we have it, falling back through
    /// the administrative levels rather than showing an empty string.
    static func describe(_ placemark: CLPlacemark) -> String? {
        let street = [placemark.subThoroughfare, placemark.thoroughfare]
            .compactMap { $0 }
            .joined(separator: " ")
        let parts = [street.isEmpty ? placemark.name : street,
                     placemark.locality ?? placemark.subAdministrativeArea,
                     placemark.country]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        // De-duplicate: `name` is frequently the street again, or the locality.
        var seen = Set<String>()
        let unique = parts.filter { seen.insert($0).inserted }
        return unique.isEmpty ? nil : unique.joined(separator: ", ")
    }

    private func send() {
        guard let pinned else { return }
        // A plain one-shot location. `isLive: false` with no `sessionId` and no
        // `expiresAt` is exactly what "share current" produces, so every
        // existing receiver — including older builds — already understands it.
        let ping = LocationMessage(
            latitude: pinned.latitude,
            longitude: pinned.longitude,
            timestamp: Date(),
            isLive: false,
            expiresAt: nil,
            accuracy: nil,
            address: address,
            sessionId: nil,
            isUpdate: false,
            isStopped: false
        )
        onSendPing(ping)
        dismiss()
    }
}
