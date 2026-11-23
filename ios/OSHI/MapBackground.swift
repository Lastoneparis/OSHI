import Foundation
import SwiftUI

/// MapBackground — which tiles the Map tab draws.
///
/// WHY THIS IS A SECOND SETTING AND NOT THE EXISTING ONE
/// -----------------------------------------------------
/// Reported: "I click settings and select Apple Plan or Google Maps and the map
/// still shows exactly the same." The report is accurate and the old setting
/// was behaving as designed: `mapOpenPreference` is labelled "Open locations
/// with" and decides which APP opens a shared place — a location bubble, a
/// check-in, an address in a chat. It was never wired to the map tab, and
/// `MapOpener`'s comment says so.
///
/// Overloading that one key would have broken a real combination: someone who
/// wants shared pins to open in Apple Maps (because that is where their
/// transit directions are) but wants OSHI's OFFLINE map in the app, because
/// offline is the entire point of this app's map. Those are two independent
/// choices and they now have two independent settings.
///
/// WHY THERE IS NO GOOGLE OPTION
/// -----------------------------
/// Not an omission. Google's terms forbid consuming their tiles outside their
/// own SDK, so a Google basemap cannot be drawn in an MKMapView at all. Listing
/// "Google Maps" here would be a control that either does nothing or does
/// something we are not permitted to do. Google remains available in "Open
/// locations with", where it works properly: it hands the place to the Google
/// Maps app.
///
/// WHAT APPLE COSTS
/// ---------------
/// Apple's basemap needs the network. It is NOT available in a downloaded
/// region, so choosing it trades offline capability for Apple's cartography and
/// labels. That is a real trade and the settings row says so out loud rather
/// than discovering it in a dead zone. `.oshi` stays the default for exactly
/// this reason.
enum MapBackground: String, CaseIterable {
    /// OSHI's own OpenStreetMap tiles, cached on device — works offline.
    case oshi
    /// Apple's basemap, drawn by MapKit itself — needs the network.
    case apple
    /// __ANATOLE_1311_IOS__ (N2) Apple's satellite imagery with road and place
    /// labels (`MKHybridMapConfiguration`). Same terms as `.apple`: drawn by
    /// MapKit, no third-party tiles, needs the network.
    case appleSatellite

    static let preferenceKey = "oshi.map.background"

    /// The current choice. Defaults to `.oshi`: it is the only one that works
    /// offline, and a messenger whose selling point is working without
    /// infrastructure should not ship a map that needs some.
    static var current: MapBackground {
        guard let raw = UserDefaults.standard.string(forKey: preferenceKey),
              let value = MapBackground(rawValue: raw) else { return .oshi }
        return value
    }

    var titleKey: String {
        switch self {
        case .oshi: return "map_background_oshi"
        case .apple: return "map_background_apple"
        case .appleSatellite: return "map_background_apple_satellite"
        }
    }

    /// True when MapKit draws the whole map (no OSHI tile overlay).
    var usesAppleBasemap: Bool { self != .oshi }

    var detailKey: String {
        switch self {
        case .oshi: return "map_background_oshi_detail"
        case .apple: return "map_background_apple_detail"
        case .appleSatellite: return "map_background_apple_satellite_detail"
        }
    }

    var symbolName: String {
        switch self {
        case .oshi: return "square.stack.3d.down.right"
        case .apple: return "apple.logo"
        case .appleSatellite: return "globe.europe.africa.fill"
        }
    }
}
