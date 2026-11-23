//
//  AddressLinkDetector.swift
//  OSHI
//
//  Detects addresses in text and provides navigation options
//  Opens Apple Maps, Google Maps, Waze, etc.
//

import SwiftUI
import MapKit
import CoreLocation

// MARK: - Brand Color
private let appBrandColor = Theme.brand

// MARK: - Address Detection Helper

class AddressLinkDetector {
    static let shared = AddressLinkDetector()
    
    private let geocoder = CLGeocoder()
    
    // Common address patterns (simplified - NSDataDetector handles complex cases)
    private let addressPatterns: [NSRegularExpression] = {
        let patterns = [
            // Street address patterns
            "\\d+\\s+[A-Za-zÀ-ÿ\\s]+(?:Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Drive|Dr|Lane|Ln|Way|Court|Ct|Place|Pl|Circle|Cir|Highway|Hwy|Route|Rt)\\.?(?:\\s*,?\\s*[A-Za-zÀ-ÿ\\s]+)?(?:\\s*,?\\s*\\d{5}(?:-\\d{4})?)?",
            // European style
            "(?:[A-Za-zÀ-ÿ\\s]+)\\s+\\d+[a-zA-Z]?(?:\\s*,\\s*\\d{4,5})?(?:\\s+[A-Za-zÀ-ÿ\\s]+)?",
            // Postal codes with city
            "\\d{4,5}\\s+[A-Za-zÀ-ÿ\\s]+,?\\s*[A-Za-zÀ-ÿ]+",
            // Swiss format: Street Name 123, 1234 City
            "[A-Za-zÀ-ÿ\\s]+\\s+\\d+[a-zA-Z]?,\\s*\\d{4}\\s+[A-Za-zÀ-ÿ\\s]+"
        ]
        return patterns.compactMap { try? NSRegularExpression(pattern: $0, options: .caseInsensitive) }
    }()
    
    // MARK: - Address Detection
    
    /// Detect addresses using NSDataDetector (most reliable)
    func detectAddresses(in text: String) -> [AddressMatch] {
        var matches: [AddressMatch] = []
        
        // Use NSDataDetector for reliable address detection
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.address.rawValue) else {
            return matches
        }
        
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        let results = detector.matches(in: text, options: [], range: range)
        
        for result in results {
            if let matchRange = Range(result.range, in: text) {
                let matchedText = String(text[matchRange])
                
                // Get address components if available
                var components: [String] = []
                if let addressComponents = result.addressComponents {
                    if let street = addressComponents[.street] {
                        components.append(street)
                    }
                    if let city = addressComponents[.city] {
                        components.append(city)
                    }
                    if let state = addressComponents[.state] {
                        components.append(state)
                    }
                    if let zip = addressComponents[.zip] {
                        components.append(zip)
                    }
                    if let country = addressComponents[.country] {
                        components.append(country)
                    }
                }
                
                let address = AddressMatch(
                    text: matchedText,
                    range: matchRange,
                    components: components.isEmpty ? nil : components
                )
                matches.append(address)
            }
        }
        
        return matches
    }
    
    /// Check if text contains an address
    func containsAddress(_ text: String) -> Bool {
        return !detectAddresses(in: text).isEmpty
    }
    
    // MARK: - Geocoding
    
    func geocodeAddress(_ address: String, completion: @escaping (CLLocationCoordinate2D?) -> Void) {
        geocoder.geocodeAddressString(address) { placemarks, error in
            if let location = placemarks?.first?.location?.coordinate {
                completion(location)
            } else {
                completion(nil)
            }
        }
    }
    
    // MARK: - Map App Options
    
    func getAvailableMapApps(for address: String, coordinate: CLLocationCoordinate2D? = nil) -> [MapAppOption] {
        var apps: [MapAppOption] = []
        let encodedAddress = address.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? address
        
        // Apple Maps (always available)
        if let coord = coordinate {
            let url = URL(string: "http://maps.apple.com/?daddr=\(coord.latitude),\(coord.longitude)")!
            apps.append(MapAppOption(name: "Apple Maps", icon: "map.fill", url: url, isInstalled: true))
        } else {
            let url = URL(string: "http://maps.apple.com/?daddr=\(encodedAddress)")!
            apps.append(MapAppOption(name: "Apple Maps", icon: "map.fill", url: url, isInstalled: true))
        }
        
        // Google Maps
        if let coord = coordinate {
            let googleURL = URL(string: "comgooglemaps://?daddr=\(coord.latitude),\(coord.longitude)&directionsmode=driving")!
            let isInstalled = UIApplication.shared.canOpenURL(URL(string: "comgooglemaps://")!)
            apps.append(MapAppOption(name: "Google Maps", icon: "globe", url: googleURL, isInstalled: isInstalled))
        } else {
            let googleURL = URL(string: "comgooglemaps://?daddr=\(encodedAddress)&directionsmode=driving")!
            let isInstalled = UIApplication.shared.canOpenURL(URL(string: "comgooglemaps://")!)
            apps.append(MapAppOption(name: "Google Maps", icon: "globe", url: googleURL, isInstalled: isInstalled))
        }
        
        // Waze
        if let coord = coordinate {
            let wazeURL = URL(string: "waze://?ll=\(coord.latitude),\(coord.longitude)&navigate=yes")!
            let isInstalled = UIApplication.shared.canOpenURL(URL(string: "waze://")!)
            apps.append(MapAppOption(name: "Waze", icon: "car.fill", url: wazeURL, isInstalled: isInstalled))
        } else {
            let wazeURL = URL(string: "waze://?q=\(encodedAddress)&navigate=yes")!
            let isInstalled = UIApplication.shared.canOpenURL(URL(string: "waze://")!)
            apps.append(MapAppOption(name: "Waze", icon: "car.fill", url: wazeURL, isInstalled: isInstalled))
        }
        
        // Citymapper
        if let coord = coordinate {
            let citymapperURL = URL(string: "citymapper://directions?endcoord=\(coord.latitude),\(coord.longitude)")!
            let isInstalled = UIApplication.shared.canOpenURL(URL(string: "citymapper://")!)
            apps.append(MapAppOption(name: "Citymapper", icon: "tram.fill", url: citymapperURL, isInstalled: isInstalled))
        }
        
        return apps
    }
}

// MARK: - Models

struct AddressMatch: Identifiable {
    let id = UUID()
    let text: String
    let range: Range<String.Index>
    let components: [String]?
    
    var displayText: String {
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

struct MapAppOption: Identifiable {
    let id = UUID()
    let name: String
    let icon: String
    let url: URL
    let isInstalled: Bool
}

// MARK: - Address Link View (Tappable Address)

struct AddressLinkText: View {
    let text: String
    let onAddressTap: (String) -> Void
    
    @State private var addresses: [AddressMatch] = []
    
    var body: some View {
        Group {
            if addresses.isEmpty {
                Text(text)
            } else {
                buildAttributedText()
            }
        }
        .onAppear {
            addresses = AddressLinkDetector.shared.detectAddresses(in: text)
        }
    }
    
    @ViewBuilder
    private func buildAttributedText() -> some View {
        let parts = splitTextWithAddresses()
        
        HStack(spacing: 0) {
            ForEach(Array(parts.enumerated()), id: \.offset) { _, part in
                if part.isAddress {
                    Button {
                        onAddressTap(part.text)
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "mappin.circle.fill")
                                .font(.caption)
                            Text(part.text)
                                .underline()
                        }
                        .foregroundColor(appBrandColor)
                    }
                } else {
                    Text(part.text)
                }
            }
        }
    }
    
    private func splitTextWithAddresses() -> [TextPart] {
        var parts: [TextPart] = []
        var currentIndex = text.startIndex
        
        for address in addresses.sorted(by: { $0.range.lowerBound < $1.range.lowerBound }) {
            // Add text before address
            if currentIndex < address.range.lowerBound {
                let beforeText = String(text[currentIndex..<address.range.lowerBound])
                parts.append(TextPart(text: beforeText, isAddress: false))
            }
            
            // Add address
            parts.append(TextPart(text: address.text, isAddress: true))
            currentIndex = address.range.upperBound
        }
        
        // Add remaining text
        if currentIndex < text.endIndex {
            let afterText = String(text[currentIndex..<text.endIndex])
            parts.append(TextPart(text: afterText, isAddress: false))
        }
        
        return parts
    }
    
    struct TextPart {
        let text: String
        let isAddress: Bool
    }
}

// MARK: - Map App Picker Sheet

struct MapAppPickerSheet: View {
    let address: String
    @Binding var isPresented: Bool
    
    @State private var coordinate: CLLocationCoordinate2D?
    @State private var isLoading = true
    @State private var mapApps: [MapAppOption] = []
    
    var body: some View {
        NavigationView {
            VStack(spacing: 16) {
                // Address display with map preview
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Image(systemName: "mappin.circle.fill")
                            .foregroundColor(appBrandColor)
                            .font(.title2)
                        
                        Text(address)
                            .font(.headline)
                            .lineLimit(3)
                        
                        Spacer()
                    }
                    .padding()
                    .background(Color(.systemGray6))
                    .cornerRadius(12)
                    
                    // Map preview if we have coordinates
                    if let coord = coordinate {
                        Map(coordinateRegion: .constant(MKCoordinateRegion(
                            center: coord,
                            span: MKCoordinateSpan(latitudeDelta: 0.01, longitudeDelta: 0.01)
                        )), annotationItems: [SimpleAnnotation(coordinate: coord)]) { item in
                            MapMarker(coordinate: item.coordinate, tint: Color(appBrandColor))
                        }
                        .frame(height: 150)
                        .cornerRadius(12)
                    }
                }
                .padding(.horizontal)
                
                if isLoading {
                    ProgressView("location.updating".localized)
                        .padding()
                } else {
                    // Map app options
                    List {
                        Section(header: Text("location.open_in_maps".localized)) {
                            ForEach(mapApps.filter { $0.isInstalled }) { app in
                                Button {
                                    UIApplication.shared.open(app.url)
                                    isPresented = false
                                } label: {
                                    HStack(spacing: 16) {
                                        Image(systemName: app.icon)
                                            .foregroundColor(appBrandColor)
                                            .frame(width: 24)
                                        
                                        Text(app.name)
                                            .foregroundColor(.primary)
                                        
                                        Spacer()
                                        
                                        Image(systemName: "arrow.up.right.square")
                                            .font(.caption)
                                            .foregroundColor(.secondary)
                                    }
                                    .padding(.vertical, 4)
                                }
                            }
                        }
                        
                        // Show not installed apps
                        let notInstalled = mapApps.filter { !$0.isInstalled }
                        if !notInstalled.isEmpty {
                            Section(header: Text("maps.not_installed".localized)) {
                                ForEach(notInstalled) { app in
                                    HStack(spacing: 16) {
                                        Image(systemName: app.icon)
                                            .foregroundColor(.secondary)
                                            .frame(width: 24)
                                        
                                        Text(app.name)
                                            .foregroundColor(.secondary)
                                        
                                        Spacer()
                                    }
                                    .padding(.vertical, 4)
                                }
                            }
                        }
                    }
                    .listStyle(.insetGrouped)
                }
                
                Spacer()
            }
            .navigationTitle("location.directions".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("common.cancel".localized) {
                        isPresented = false
                    }
                }
                
                // Copy address button
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        UIPasteboard.general.string = address
                    } label: {
                        Image(systemName: "doc.on.doc")
                    }
                }
            }
            .onAppear {
                loadMapApps()
            }
        }
    }
    
    private func loadMapApps() {
        // Try to geocode the address for better accuracy
        AddressLinkDetector.shared.geocodeAddress(address) { coord in
            DispatchQueue.main.async {
                self.coordinate = coord
                self.mapApps = AddressLinkDetector.shared.getAvailableMapApps(for: address, coordinate: coord)
                self.isLoading = false
            }
        }
    }
}

struct SimpleAnnotation: Identifiable {
    let id = UUID()
    let coordinate: CLLocationCoordinate2D
}

// MARK: - URL Scheme Configuration

/*
 Add these URL schemes to Info.plist under LSApplicationQueriesSchemes to detect installed apps:
 
 <key>LSApplicationQueriesSchemes</key>
 <array>
     <string>comgooglemaps</string>
     <string>waze</string>
     <string>citymapper</string>
 </array>
*/
