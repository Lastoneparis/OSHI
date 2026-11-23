import SwiftUI
import CoreLocation
import MapKit

// MARK: - Map Search View (Offline POI Search)

struct MapSearchView: View {

    // MARK: - Bindings

    @Binding var selectedPOI: MapPOI?
    @Binding var isPresented: Bool

    // MARK: - State

    @StateObject private var locationHelper = SearchLocationHelper()
    @State private var searchText = ""
    @State private var results: [MapPOI] = []
    @State private var isSearching = false
    @State private var activeCategory: POICategory?
    @State private var regionId: String?

    // MARK: - Database

    private let poiDB = MapPOIDatabase()

    // MARK: - Constants

    private static let brandColor = Theme.brand
    private let categoryColumns = Array(repeating: GridItem(.flexible(), spacing: 12), count: 3)

    // MARK: - Body

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                searchBar
                Divider()

                if searchText.isEmpty && activeCategory == nil {
                    categoryGrid
                } else {
                    if activeCategory != nil {
                        // Back button to return to category grid
                        Button {
                            activeCategory = nil
                            results = []
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "chevron.left")
                                Text("map.search.all_categories".localized)
                            }
                            .font(.subheadline.weight(.medium))
                            .foregroundColor(Self.brandColor)
                        }
                        .padding(.horizontal, 16)
                        .padding(.top, 8)
                    }
                    resultsList
                }
            }
            .navigationTitle("map.search.places_title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("cancel".localized) {
                        isPresented = false
                    }
                    .foregroundColor(Self.brandColor)
                }
            }
        }
        .onAppear {
            resolveRegion()
            // 📸 Skip the location-permission prompt during App Store screenshot
            // capture (-OSHIScreens) — the alert would cover the map. The offline
            // tiles and POI catalog render fine without a live fix. DEBUG-only:
            // a launch argument a shipping build honours is a feature hidden from
            // review (guideline 5.6), and here it would also suppress the
            // permission prompt for a real user.
            #if DEBUG
            if !ProcessInfo.processInfo.arguments.contains("-OSHIScreens") {
                locationHelper.requestLocation()
            }
            #else
            locationHelper.requestLocation()
            #endif
        }
        .onDisappear {
            poiDB.close()
        }
    }

    // MARK: - Search Bar

    private var searchBar: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .foregroundColor(.secondary)
                .accessibilityHidden(true)

            TextField("map.search.placeholder_short".localized, text: $searchText)
                .textFieldStyle(.plain)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .onChange(of: searchText) { _, _ in
                    performSearch()
                }

            if !searchText.isEmpty {
                Button {
                    searchText = ""
                    results = []
                    activeCategory = nil
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(.secondary)
                }
                .accessibilityLabel("common.clear".localized)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Color(.systemGray6))
        .cornerRadius(10)
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    // MARK: - Category Grid

    private var categoryGrid: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("map.search.categories".localized)
                    .font(.title3.weight(.semibold))
                    .padding(.horizontal, 16)
                    .padding(.top, 8)

                LazyVGrid(columns: categoryColumns, spacing: 12) {
                    ForEach(POICategory.all) { category in
                        CategoryCard(category: category, brandColor: Self.brandColor)
                            .onTapGesture {
                                #if !targetEnvironment(macCatalyst)
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                #endif
                                activeCategory = category
                                searchByCategory(category)
                            }
                            .accessibilityElement(children: .combine)
                            .accessibilityAddTraits(.isButton)
                    }
                }
                .padding(.horizontal, 16)
            }
            .frame(maxWidth: 700)
            .frame(maxWidth: .infinity)
        }
    }

    // MARK: - Results List

    @ViewBuilder
    private var resultsList: some View {
            if isSearching {
                VStack(spacing: 12) {
                    Spacer()
                    ProgressView()
                        .tint(Self.brandColor)
                    Text("map.search.searching".localized)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                    Spacer()
                }
                .frame(maxWidth: .infinity)
                .accessibilityElement(children: .combine)
            } else if results.isEmpty && (!searchText.isEmpty || activeCategory != nil) {
                VStack(spacing: 12) {
                    Spacer()
                    Image(systemName: "map.fill")
                        .font(.scaled(40))
                        .foregroundColor(.secondary.opacity(0.5))
                        .accessibilityHidden(true)
                    Text("map.no_results".localized)
                        .font(.headline)
                        .foregroundColor(.secondary)
                    Text("map.search.no_results_desc".localized)
                        .font(.subheadline)
                        .foregroundColor(.secondary.opacity(0.7))
                    Spacer()
                }
                .frame(maxWidth: .infinity)
                .accessibilityElement(children: .combine)
            } else {
                List {
                    ForEach(sortedResults) { poi in
                        POIResultRow(
                            poi: poi,
                            userLocation: locationHelper.lastLocation,
                            brandColor: Self.brandColor
                        )
                        .contentShape(Rectangle())
                        .onTapGesture {
                            #if !targetEnvironment(macCatalyst)
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            #endif
                            selectedPOI = poi
                            isPresented = false
                        }
                        .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
                    }
                }
                .listStyle(.plain)
                .frame(maxWidth: 700)
                .frame(maxWidth: .infinity)
            }
    }

    // MARK: - Sorted Results

    private var sortedResults: [MapPOI] {
        guard let userLoc = locationHelper.lastLocation else { return results }
        let userCL = CLLocation(latitude: userLoc.latitude, longitude: userLoc.longitude)
        return results.sorted { a, b in
            let distA = CLLocation(latitude: a.coordinate.latitude, longitude: a.coordinate.longitude).distance(from: userCL)
            let distB = CLLocation(latitude: b.coordinate.latitude, longitude: b.coordinate.longitude).distance(from: userCL)
            return distA < distB
        }
    }

    // MARK: - Search Logic

    /// Build an MKCoordinateRegion centered on the user's location (or a default).
    private var searchRegion: MKCoordinateRegion {
        let center = locationHelper.lastLocation
            ?? CLLocationCoordinate2D(latitude: 48.8566, longitude: 2.3522) // Paris fallback
        return MKCoordinateRegion(
            center: center,
            span: MKCoordinateSpan(latitudeDelta: 0.05, longitudeDelta: 0.05)
        )
    }

    private func resolveRegion() {
        // Try to find a downloaded region for offline fallback.
        guard let docsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let regionsDir = docsURL.appendingPathComponent("Maps/regions")
        guard let contents = try? FileManager.default.contentsOfDirectory(atPath: regionsDir.path) else { return }

        for dir in contents {
            let poiPath = regionsDir.appendingPathComponent(dir).appendingPathComponent("poi.db").path
            if FileManager.default.fileExists(atPath: poiPath) {
                regionId = dir
                _ = poiDB.open(regionId: dir)
                return
            }
        }
    }

    private func performSearch() {
        activeCategory = nil

        guard !searchText.trimmingCharacters(in: .whitespaces).isEmpty else {
            results = []
            return
        }

        isSearching = true

        // Primary: MKLocalSearch (real Apple Maps data)
        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = searchText
        request.region = searchRegion

        let search = MKLocalSearch(request: request)
        search.start { response, error in
            if let response = response, !response.mapItems.isEmpty {
                DispatchQueue.main.async {
                    self.results = response.mapItems.enumerated().map { index, item in
                        MapPOI(
                            id: Int64(index),
                            name: item.name ?? NSLocalizedString("map.poi.unknown", comment: "Fallback name for unnamed POI"),
                            category: self.categorizeMapItem(item),
                            subcategory: item.pointOfInterestCategory?.rawValue,
                            coordinate: item.placemark.coordinate,
                            address: self.formatAddress(item.placemark),
                            phone: item.phoneNumber,
                            website: item.url?.absoluteString,
                            openingHours: nil
                        )
                    }
                    self.isSearching = false
                }
            } else {
                // Fallback: offline database
                self.searchOffline(query: self.searchText)
            }
        }
    }

    private func searchByCategory(_ category: POICategory) {
        isSearching = true

        // Bug A fix (2026-04-28): the localised category label (e.g. "Marché",
        // "Café", "Pharmacie") is too narrow as a `naturalLanguageQuery` —
        // Apple Maps interprets it as a NAME match and skips chain stores
        // (Carrefour, Migros, Lidl don't have "market" in their name). Use
        // generic English category synonyms so MKLocalSearch routes the query
        // through its category index. The MK POI filter below pins the
        // result set to the correct venue type.
        let mkQueryOverrides: [String: String] = [
            "supermarket": "supermarket grocery",
            "cafe": "cafe coffee",
            "pharmacy": "pharmacy drugstore",
            "hospital": "hospital medical center",
            "bank": "bank atm",
            "atm": "atm cash machine",
            "fuel": "gas station fuel",
            "hotel": "hotel lodging",
            "restaurant": "restaurant",
            "parking": "parking lot garage",
            "charging_station": "ev charging station",
            "bus_station": "bus station stop",
            "train_station": "train station",
            "airport": "airport",
            "police": "police station",
            "post_office": "post office",
            "school": "school",
            "university": "university college",
            "museum": "museum",
            "park": "park garden",
            "beach": "beach",
            "place_of_worship": "church mosque temple",
            "gym": "gym fitness center",
            "cinema": "cinema movie theater",
            "toilet": "public toilet restroom",
            "bar": "bar pub"
        ]

        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = mkQueryOverrides[category.id] ?? category.name
        request.region = searchRegion

        // Apply MKPointOfInterestCategory filter when available
        if let mkCategory = category.mkPointOfInterestCategory {
            request.pointOfInterestFilter = MKPointOfInterestFilter(including: [mkCategory])
        }

        let search = MKLocalSearch(request: request)
        search.start { response, error in
            if let response = response, !response.mapItems.isEmpty {
                DispatchQueue.main.async {
                    self.results = response.mapItems.enumerated().map { index, item in
                        MapPOI(
                            id: Int64(index),
                            name: item.name ?? NSLocalizedString("map.poi.unknown", comment: "Fallback name for unnamed POI"),
                            category: self.categorizeMapItem(item),
                            subcategory: item.pointOfInterestCategory?.rawValue,
                            coordinate: item.placemark.coordinate,
                            address: self.formatAddress(item.placemark),
                            phone: item.phoneNumber,
                            website: item.url?.absoluteString,
                            openingHours: nil
                        )
                    }
                    self.isSearching = false
                }
            } else {
                self.searchOffline(query: category.name)
            }
        }
    }

    // MARK: - Offline Fallback

    private func searchOffline(query: String) {
        guard regionId != nil else {
            DispatchQueue.main.async {
                self.results = []
                self.isSearching = false
            }
            return
        }

        DispatchQueue.global(qos: .userInitiated).async {
            let found = self.poiDB.search(
                query: query,
                near: self.locationHelper.lastLocation,
                radiusKm: 50,
                limit: 100
            )
            DispatchQueue.main.async {
                self.results = found
                self.isSearching = false
            }
        }
    }

    // MARK: - MKLocalSearch Helpers

    private func categorizeMapItem(_ item: MKMapItem) -> String {
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

    private func formatAddress(_ placemark: MKPlacemark) -> String? {
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
}

// MARK: - Category Card

private struct CategoryCard: View {
    let category: POICategory
    let brandColor: Color

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: category.icon)
                .font(.system(size: 24))
                .foregroundColor(brandColor)
                .frame(width: 48, height: 48)
                .background(brandColor.opacity(0.1))
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .accessibilityHidden(true)

            Text(category.name)
                .font(.caption)
                .fontWeight(.medium)
                .foregroundColor(.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(Color(.systemBackground))
        .cornerRadius(12)
        .shadow(color: .black.opacity(0.06), radius: 4, x: 0, y: 2)
    }
}

// MARK: - POI Result Row

private struct POIResultRow: View {
    let poi: MapPOI
    let userLocation: CLLocationCoordinate2D?
    let brandColor: Color

    var body: some View {
        HStack(spacing: 12) {
            // Category icon
            Image(systemName: poi.categoryIcon)
                .font(.system(size: 18))
                .foregroundColor(.white)
                .frame(width: 36, height: 36)
                .background(brandColor)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .accessibilityHidden(true)

            // Text content
            VStack(alignment: .leading, spacing: 3) {
                Text(poi.name)
                    .font(.body.weight(.semibold))
                    .foregroundColor(.primary)
                    .lineLimit(1)

                HStack(spacing: 4) {
                    Text(poi.category.replacingOccurrences(of: "_", with: " ").capitalized)
                        .font(.caption)
                        .foregroundColor(.secondary)

                    if let sub = poi.subcategory, !sub.isEmpty {
                        Text("--")
                            .font(.caption)
                            .foregroundColor(.secondary.opacity(0.5))
                        Text(sub.capitalized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }

                if let address = poi.address, !address.isEmpty {
                    Text(address)
                        .font(.caption)
                        .foregroundColor(.secondary.opacity(0.8))
                        .lineLimit(1)
                }
            }

            Spacer()

            // Distance badge
            if let dist = formattedDistance {
                Text(dist)
                    .font(.caption.weight(.medium))
                    .foregroundColor(brandColor)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(brandColor.opacity(0.1))
                    .cornerRadius(6)
            }

            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundColor(.secondary.opacity(0.5))
                .accessibilityHidden(true)
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }

    private var formattedDistance: String? {
        guard let userLoc = userLocation else { return nil }
        let from = CLLocation(latitude: userLoc.latitude, longitude: userLoc.longitude)
        let to = CLLocation(latitude: poi.coordinate.latitude, longitude: poi.coordinate.longitude)
        let meters = from.distance(from: to)

        if meters < 1000 {
            return "\(Int(meters)) m"
        } else {
            return String(format: "%.1f km", meters / 1000.0)
        }
    }
}

// MARK: - Location Helper

private final class SearchLocationHelper: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var lastLocation: CLLocationCoordinate2D?

    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    func requestLocation() {
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            manager.requestLocation()
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        default:
            break
        }
    }

    // MARK: - CLLocationManagerDelegate

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        if let loc = locations.last {
            lastLocation = loc.coordinate
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        OshiLog.maps.info("[MapSearch] Location error: \(error.localizedDescription)")
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways {
            manager.requestLocation()
        }
    }
}
