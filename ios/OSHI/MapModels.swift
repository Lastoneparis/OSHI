import Foundation
import CoreLocation
import MapKit

// MARK: - Region Models

struct MapRegion: Codable, Identifiable {
    let id: String           // e.g. "fr-idf"
    let name: String         // e.g. "Ile-de-France"
    let country: String      // e.g. "FR"
    let continent: String    // e.g. "europe"
    let bbox: [Double]       // [minLon, minLat, maxLon, maxLat]
    let files: MapRegionFiles
    let osmDate: String      // "2026-04-01"

    /// __REGION_ALIAS_2026_10_21__ The country's name in the USER's language.
    ///
    /// The manifest ships English names only (`"Morocco"`, `"Germany"`), the
    /// row renders that string raw, and the picker's filter matches
    /// `name` / `country` / `id`. So a French user searching "maroc" got
    /// **zero results** while the country sat right there, and "mar" returned
    /// only Marrakech — because `"Morocco"` does not contain `"mar"`.
    /// Measured against the live manifest on 2026-10-21; it was reported as
    /// "Morocco is missing from the app".
    ///
    /// Not French-specific, and not fixable with a hand-written alias table:
    /// it is every one of the app's locales against 258 regions.
    /// `Locale.localizedString(forRegionCode:)` is CLDR, which ships with the
    /// OS and already holds every country name in every language the device
    /// supports — the right table is the one Apple already maintains, and the
    /// only thing missing was asking it.
    ///
    /// `nil` when `country` is not a 2-letter code or CLDR has no name for it;
    /// callers fall back to the manifest name rather than guess.
    var localizedCountryName: String? {
        let code = country.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard code.count == 2, code.allSatisfy({ $0.isASCII && $0.isLetter }) else { return nil }
        guard let localized = Locale.current.localizedString(forRegionCode: code),
              !localized.isEmpty else { return nil }
        return localized
    }

    /// The label for this region's row.
    ///
    /// Country-level regions (no dash in the id) get the localized name.
    /// Sub-regions keep the manifest name on purpose: `ma-cas` is
    /// "Casablanca", and replacing that with "Maroc" would render all 23
    /// Moroccan cities as the same word.
    var displayName: String {
        guard !id.contains("-"), let localized = localizedCountryName else { return name }
        return localized
    }

    /// Every string the picker's filter should test.
    ///
    /// The localized country name is included for sub-regions too, so "maroc"
    /// surfaces Casablanca and Marrakech as well as Morocco itself — searching
    /// for a country should find everything inside it.
    var searchTerms: [String] {
        var terms = [name, country, id]
        if let localized = localizedCountryName { terms.append(localized) }
        return terms
    }

    /// __MAP_CONTINENT_NORMALISE_2026_10_14__ `continent` as a stable key.
    ///
    /// The picker used to switch on the raw string, and its default arm is
    /// "Asia / Oceania". The manifest carried two hyphenated one-offs —
    /// `south-america` (Uruguay) and `central-america` (Nicaragua) — so both
    /// countries were listed on the wrong continent. Android had normalised
    /// since the screen was written (`continentKey` in
    /// MapRegionDownloadScreen.kt); this is the same rule, on the model so it
    /// can be tested without a view.
    var continentGroupKey: String {
        continent
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .replacingOccurrences(of: "-", with: "_")
            .replacingOccurrences(of: " ", with: "_")
    }

    /// Which picker section this region belongs in, as a stable key:
    /// `europe`, `americas`, `africa`, `asia_oceania` or `middle_east`.
    ///
    /// This lived in `MapRegionDownloadView.continentFor` as a switch whose
    /// default arm is "Asia / Oceania", so any continent spelling it did not
    /// recognise filed the region under Asia — which is how Uruguay
    /// (`south-america`) and Nicaragua (`central-america`) came to be listed
    /// there. Moving the decision onto the model means it can be tested
    /// without standing up a view, and the view keeps only the mapping from
    /// key to localized title and icon.
    ///
    /// `americas` matters for real data today: the French DOM-TOM
    /// (Guadeloupe, Martinique, Guyane, Saint-Pierre-et-Miquelon,
    /// Saint-Barthelemy, Saint-Martin) are filed under that key.
    var continentSectionKey: String {
        switch continentGroupKey {
        case "europe":
            return "europe"
        case "north_america", "south_america", "americas",
             "central_america", "latin_america", "caribbean":
            return "americas"
        case "africa":
            return "africa"
        case "middle_east":
            return "middle_east"
        case "asia", "oceania", "asia_oceania", "australia":
            return "asia_oceania"
        default:
            // An unrecognised spelling from a future manifest. Asia/Oceania is
            // the widest bucket, so it is the least wrong place to put it, but
            // `continentSectionKeyIsExhaustiveForTheLiveManifest` in
            // OfflineMapManifestTests exists to catch a new key landing here.
            return "asia_oceania"
        }
    }

    var center: CLLocationCoordinate2D {
        guard bbox.count >= 4 else {
            return CLLocationCoordinate2D(latitude: 0, longitude: 0)
        }
        return CLLocationCoordinate2D(
            latitude: (bbox[1] + bbox[3]) / 2,
            longitude: (bbox[0] + bbox[2]) / 2
        )
    }

    /// Number of raster tiles the bulk downloader will fetch for this region's
    /// bbox over `zoomRange` — the same enumeration
    /// `OfflineMapManager.downloadRegion` performs.
    // __TILE_BUDGET_2026_10_09__ How deep a region is pre-cached.
    //
    // Every region used to be fetched at z6-13 regardless of how big it was,
    // and z13 alone is ~75% of the bill: France is ~143 000 tiles over the
    // whole range, of which ~107 000 are z13. That is the single biggest cause
    // of "le téléchargement est lent pour tout le monde", and of the tile
    // server's load.
    //
    // The cap is derived from the ACTUAL tile count rather than from the
    // bbox's area in degrees, for two reasons: a degree of longitude is worth
    // very different numbers of tiles at Oslo and at Lagos, and counting tiles
    // is exactly what we are trying to bound. So: take the deepest zoom whose
    // cumulative count still fits the budget.
    //
    // A city-sized region therefore keeps its z13 street detail — it is cheap
    // — while a country stops at z12 or z11. Zooming past the cap is NOT a
    // grey tile any more: `OfflineFirstTileOverlay.cachedAncestorTile` crops
    // and upscales the deepest cached ancestor, so the cost of the cap is
    // sharpness, not a blank map. That fallback is what makes this safe, and
    // capping before it existed would have been the wrong order.
    static let defaultTileBudget = 40_000

    /// Deepest zoom this region is pre-cached at, given a tile budget.
    func maxDownloadZoom(budget: Int = MapRegion.defaultTileBudget,
                         floor: Int = 10,
                         ceiling: Int = 13) -> Int {
        var deepest = floor
        for z in floor...ceiling {
            // `floor` is a floor on purpose: a region so large that even z10
            // blows the budget is still worth having at z10, and refusing to
            // go below the floor keeps the map usable rather than empty.
            if estimatedTileCount(zoomRange: 6...z) <= budget { deepest = z }
            else { break }
        }
        return deepest
    }

    /// The range `OfflineMapManager.downloadRegion` uses when the caller does
    /// not ask for a specific one.
    var downloadZoomRange: ClosedRange<Int> { 6...maxDownloadZoom() }

    func estimatedTileCount(zoomRange: ClosedRange<Int>) -> Int {
        guard bbox.count >= 4 else { return 0 }
        let minLon = bbox[0], minLat = bbox[1], maxLon = bbox[2], maxLat = bbox[3]
        func yOf(_ lat: Double, _ n: Double) -> Int {
            let clamped = min(85.05112878, max(-85.05112878, lat))
            let rad = clamped * .pi / 180.0
            return Int(floor((1.0 - log(tan(rad) + 1.0 / cos(rad)) / .pi) / 2.0 * n))
        }
        var count = 0
        for z in zoomRange {
            let n = pow(2.0, Double(z))
            let xStart = max(0, Int(floor((minLon + 180.0) / 360.0 * n)))
            let xEnd = min(Int(n) - 1, Int(floor((maxLon + 180.0) / 360.0 * n)))
            let yStart = max(0, yOf(maxLat, n))
            let yEnd = min(Int(n) - 1, yOf(minLat, n))
            guard xEnd >= xStart, yEnd >= yStart else { continue }
            count += (xEnd - xStart + 1) * (yEnd - yStart + 1)
        }
        return count
    }

    /// Tiles this region will actually fetch — i.e. over `downloadZoomRange`.
    ///
    /// Deliberately the default: this number is what `displaySize` shows the
    /// user BEFORE they authorise the download, so it has to be the number
    /// they will really pay. A default of 6...13 here while the downloader
    /// capped at z12 would advertise four times the real size.
    func estimatedTileCount() -> Int { estimatedTileCount(zoomRange: downloadZoomRange) }

    /// Rough byte cost of this region's raster tiles. ~12 KB per OSM PNG tile,
    /// the same constant `OfflineMapManager.aggregateProgress` weights with.
    var estimatedTileBytes: Int { estimatedTileCount() * 12_000 }

    var displaySize: String {
        // INCREMENTAL size only — files marked `shared: true` in the manifest
        // (country-level fallbacks reused by sub-regions) report size=0 so
        // sub-regions don't all appear as ~5 GB just because they all share
        // the same France country file. The shared bytes are still
        // transferred on first download — see `displaySharedSize` for the
        // transparency label shown next to the incremental total.
        //
        // 🔧 FIX (2026-10-09): `files.tiles.size` is 0 for EVERY region in the
        // live manifest (tiles are streamed from the /tiles proxy; the tiles
        // artefact in the schema has never existed — see MapRegionFiles). The
        // tiles are also the overwhelming majority of a region's real cost, so
        // every row in the regions list advertised a download of "0 MB" or, at
        // best, the POI database alone — e.g. "38 MB" for France, whose tiles
        // are ~1.7 GB. Fall back to the enumerated tile estimate so the number
        // the user decides on is the number they will actually spend.
        //
        // 🔧 FIX (2026-10-14): the sum left out `streets` and `roadgraph`, which
        // is the same defect as the tiles one above and for the same reason —
        // `downloadRegion` fetches routing, streets, roadgraph and POI in
        // parallel with the tiles (OfflineMapManager.swift:329-344), so all of
        // them are part of what the user authorises with one tap.
        //
        // The road graph is now the DOMINANT term for the regions published on
        // 2026-10-14: Auvergne-Rhone-Alpes is 1052 MB of road graph against
        // ~23 MB of POI, so the row understated its own download by about a
        // gigabyte. `displaySharedSize` below has always counted all four
        // layers; only this incremental total was short, and short by the two
        // that matter most.
        //
        // Uses `size`, not `realSize`, so a layer a sub-region inherits from
        // its country still reports 0 here and is surfaced by
        // `displaySharedSize` instead -- that split is deliberate.
        let tilesSize: Int = (files.tiles?.size ?? 0) > 0 ? (files.tiles?.size ?? 0) : estimatedTileBytes
        let routingSize: Int = files.routing?.size ?? 0
        let poiSize: Int = files.poi?.size ?? 0
        let streetsSize: Int = files.streets?.size ?? 0
        let roadGraphSize: Int = files.roadgraph?.size ?? 0
        let total = Double(tilesSize + routingSize + poiSize + streetsSize + roadGraphSize)
        let mb = total / (1024.0 * 1024.0)
        if mb >= 1024.0 {
            return String(format: "%.1f GB", mb / 1024.0)
        }
        return String(format: "%.0f MB", mb)
    }

    /// Human label like "+5.2 GB shared with France" when this sub-region
    /// reuses one or more country-level assets that haven't been downloaded
    /// yet. Empty string when nothing is shared OR all shared files are
    /// already on disk. Caller can render it as a small grey suffix.
    var displaySharedSize: String {
        // Written as explicit locals rather than one chained expression: with
        // four optionals the Swift type-checker times out on the sum.
        let sharedRouting: Int = files.routing?.sharedSize ?? 0
        let sharedStreets: Int = files.streets?.sharedSize ?? 0
        let sharedRoadgraph: Int = files.roadgraph?.sharedSize ?? 0
        let sharedPOI: Int = files.poi?.sharedSize ?? 0
        let shared = sharedRouting + sharedStreets + sharedRoadgraph + sharedPOI
        guard shared > 0 else { return "" }
        let mb = Double(shared) / (1024.0 * 1024.0)
        if mb >= 1024.0 {
            return String(format: "+%.1f GB shared", mb / 1024.0)
        }
        return String(format: "+%.0f MB shared", mb)
    }

    var countryFlag: String {
        let base: UInt32 = 127397
        return country.uppercased().unicodeScalars.compactMap { UnicodeScalar(base + $0.value) }.map { String($0) }.joined()
    }
}

struct MapRegionFiles: Codable {
    /// 🔧 FIX (2026-07-28): was non-optional. A region whose manifest entry was
    /// missing ANY ONE of tiles/routing/poi made `JSONDecoder` throw for the
    /// WHOLE `MapManifest` — 233 healthy regions died because one was partial,
    /// and the app silently dropped to `hardcodedManifest` (~60 stale regions).
    /// Every layer is now optional and degrades on its own: no tiles entry
    /// still renders (tiles stream from the /tiles proxy, never from this URL),
    /// no POI entry still routes, no roadgraph entry still shows the map.
    ///
    /// LEGACY: `tiles` has never corresponded to a real file. No code path
    /// fetches `tiles.url` — raster tiles come from the
    /// `oshi-messenger.com/tiles/{z}/{x}/{y}.png` proxy. It is kept in the
    /// schema only because shipped builds require the key to be present.
    let tiles: MapFile?
    /// LEGACY: the OSRM/GraphHopper `.tar.gz` consumed by `OfflineRoutingManager`.
    /// `OfflineRouter` (the engine that actually produces turn-by-turn) reads
    /// `roadgraph`, not this. Optional since 2026-07-28.
    let routing: MapFile?
    /// Per-region POI SQLite. Optional since 2026-07-28 — 56 of 234 regions
    /// have no POI database on the server.
    let poi: MapFile?
    /// Optional, added 2026-05-12 — present only when the server has produced
    /// a streets DB resolvable for this region (regional or country-fallback).
    let streets: MapFile?
    /// Optional, added 2026-05-12 — A* road graph SQLite. Same shape as
    /// streets: regional when available, falls back to country.
    let roadgraph: MapFile?
}

struct MapFile: Codable {
    let url: String
    let size: Int
    let sha256: String
    /// When true, this file is shared with the country-level region (e.g. a
    /// French sub-region pointing at fr-streets.db.gz). Lets the UI show
    /// "shared with France" instead of double-counting the bytes on every
    /// sub-region's displayed size.
    let shared: Bool?
    /// Real on-disk byte count when `shared == true`. The top-level `size`
    /// field is 0 in that case so sub-regions show only their incremental
    /// cost; `sharedSize` carries the truth for transparency labels.
    let sharedSize: Int?

    enum CodingKeys: String, CodingKey {
        case url
        case size
        case sha256
        case shared
        case sharedSize = "shared_size"
    }

    /// Bytes this file really occupies on the server: `size` for a regional
    /// file, `shared_size` for a country-level file inherited by a sub-region
    /// (which reports `size: 0` so the UI shows only incremental cost).
    var realSize: Int { size > 0 ? size : (sharedSize ?? 0) }

    /// 🔧 FIX (2026-07-28): the manifest's contract for "this asset actually
    /// exists on the server". `regenerate_manifest.py` emits `size: 0` with no
    /// `shared_size` for a layer it could not resolve, so the client can skip
    /// it instead of firing a request that is known to 404. Before this, the
    /// app had no way to tell "0 bytes because shared" from "0 bytes because
    /// the file was never built".
    var isAvailable: Bool { realSize > 0 }
}

extension String {
    /// __REGION_ALIAS_2026_10_21__ The form the region picker compares in.
    ///
    /// Diacritic-insensitive as well as case-insensitive, because the moment
    /// region names are localized the filter has to survive the accents those
    /// names carry: a user typing "cote" must still find "Côte d'Ivoire", and
    /// "espagne" must match "Espagne" typed without the keyboard's accents.
    /// Folding both sides of the comparison is what makes that work in every
    /// language rather than just the ones without diacritics.
    var regionSearchFolded: String {
        folding(options: [.diacriticInsensitive, .caseInsensitive, .widthInsensitive],
                locale: .current)
    }
}

struct MapManifest: Codable {
    let version: Int
    let updated: String
    let regions: [MapRegion]
}

// MARK: - Download State

enum RegionDownloadState: Equatable {
    case notDownloaded
    case downloading(progress: Double)  // 0.0 to 1.0
    case downloaded(date: Date)
    case updateAvailable(localDate: Date, remoteDate: String)
    case failed(error: String)
}

struct DownloadedRegionMetadata: Codable {
    let regionId: String
    let downloadDate: Date
    let version: Int
    let tilesSize: Int
    let routingSize: Int
    let poiSize: Int
    let hasRouting: Bool
    var totalSize: Int { tilesSize + routingSize + poiSize }

    /// Backward-compatible decoder: defaults hasRouting to false when missing from JSON.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        regionId = try container.decode(String.self, forKey: .regionId)
        downloadDate = try container.decode(Date.self, forKey: .downloadDate)
        version = try container.decode(Int.self, forKey: .version)
        tilesSize = try container.decode(Int.self, forKey: .tilesSize)
        routingSize = try container.decode(Int.self, forKey: .routingSize)
        poiSize = try container.decode(Int.self, forKey: .poiSize)
        hasRouting = try container.decodeIfPresent(Bool.self, forKey: .hasRouting) ?? false
    }

    init(regionId: String, downloadDate: Date, version: Int, tilesSize: Int, routingSize: Int, poiSize: Int, hasRouting: Bool = false) {
        self.regionId = regionId
        self.downloadDate = downloadDate
        self.version = version
        self.tilesSize = tilesSize
        self.routingSize = routingSize
        self.poiSize = poiSize
        self.hasRouting = hasRouting
    }
}

// MARK: - POI Models

struct MapPOI: Identifiable, Equatable {
    let id: Int64
    let name: String
    let category: String
    let subcategory: String?
    let coordinate: CLLocationCoordinate2D
    let address: String?
    let phone: String?
    let website: String?
    let openingHours: String?

    static func == (lhs: MapPOI, rhs: MapPOI) -> Bool {
        lhs.id == rhs.id
    }

    var categoryIcon: String {
        switch category {
        case "restaurant": return "fork.knife"
        case "cafe": return "cup.and.saucer.fill"
        case "bar": return "wineglass.fill"
        case "fuel": return "fuelpump.fill"
        case "hospital": return "cross.case.fill"
        case "pharmacy": return "pills.fill"
        case "hotel": return "bed.double.fill"
        case "supermarket": return "cart.fill"
        case "atm", "bank": return "banknote.fill"
        case "parking": return "p.square.fill"
        case "police": return "shield.fill"
        case "school", "university": return "graduationcap.fill"
        case "place_of_worship": return "building.columns.fill"
        case "bus_station", "train_station": return "tram.fill"
        case "airport": return "airplane"
        case "cinema": return "film.fill"
        case "gym", "sports": return "figure.run"
        case "post_office": return "envelope.fill"
        case "toilet": return "drop.triangle.fill"
        case "charging_station": return "bolt.car.fill"
        case "wifi": return "wifi"
        case "street": return "road.lanes"
        case "address": return "signpost.right.fill"
        default: return "mappin.circle.fill"
        }
    }
}

// MARK: - POI Categories for browsing

struct POICategory: Identifiable {
    let id: String
    /// User-visible category label. When the optional `nameOverride` is non-nil,
    /// it's used as-is (already-localized literal). Otherwise we look up
    /// `"map.poi.\(id)"` in `Localizable.strings`, falling back to the id
    /// capitalized so a missing key never shows raw English keys.
    private let nameOverride: String?
    let icon: String

    init(id: String, name: String? = nil, icon: String) {
        self.id = id
        self.nameOverride = name
        self.icon = icon
    }

    /// Localized display name. Looks up `map.poi.<id>` in Localizable.strings
    /// for every category id in the app. Falls back to the override (legacy
    /// callers passing a literal label) or to the id capitalized.
    var name: String {
        if let override = nameOverride { return override }
        let key = "map.poi.\(id)"
        let localized = NSLocalizedString(key, comment: "POI category display name")
        if localized != key { return localized }
        return id.replacingOccurrences(of: "_", with: " ").capitalized
    }

    static let all: [POICategory] = [
        POICategory(id: "restaurant", icon: "fork.knife"),
        POICategory(id: "cafe", icon: "cup.and.saucer.fill"),
        POICategory(id: "fuel", icon: "fuelpump.fill"),
        POICategory(id: "hospital", icon: "cross.case.fill"),
        POICategory(id: "pharmacy", icon: "pills.fill"),
        POICategory(id: "hotel", icon: "bed.double.fill"),
        POICategory(id: "supermarket", icon: "cart.fill"),
        POICategory(id: "atm", icon: "banknote.fill"),
        POICategory(id: "parking", icon: "p.square.fill"),
        POICategory(id: "charging_station", icon: "bolt.car.fill"),
        POICategory(id: "bus_station", icon: "bus.fill"),
        POICategory(id: "train_station", icon: "tram.fill"),
    ]

    /// Maps our internal category ID to Apple's MKPointOfInterestCategory.
    /// Covers both the visible grid categories and the extended set used by
    /// the natural-language keyword matcher (e.g. "post_office", "police",
    /// "place_of_worship") — see categoryKeywordMap in OfflineMapView.
    var mkPointOfInterestCategory: MKPointOfInterestCategory? {
        switch id {
        case "restaurant": return .restaurant
        case "cafe": return .cafe
        case "bar": return .nightlife
        case "fuel": return .gasStation
        case "hospital": return .hospital
        case "pharmacy": return .pharmacy
        case "hotel": return .hotel
        case "supermarket":
            // iOS 18 added `.foodMarket` — the precise supermarket / grocery
            // / market category. Carrefour, Intermarché, Migros, supérette,
            // marketplace stalls all bind to this. The old `.store` filter
            // was too broad and pulled florists and clothing stores when
            // the user tapped the "Market" POI category.
            if #available(iOS 18.0, *) { return .foodMarket }
            return .store
        case "atm": return .atm
        case "bank":
            // iOS 18 introduced `.bank`. Pre-18 we fall back to `.atm` which
            // surfaces the same money-related venues without touching ATMs
            // alone (the natural-language "bank atm" query covers branches).
            if #available(iOS 18.0, *) { return .bank }
            return .atm
        case "parking": return .parking
        case "charging_station": return .evCharger
        case "bus_station", "train_station": return .publicTransport
        case "airport": return .airport
        case "police": return .police
        case "post_office": return .postOffice
        case "university", "school": return .university
        case "museum": return .museum
        case "park": return .park
        case "beach": return .beach
        case "place_of_worship": return nil  // No MK category — relies on natural-language query.
        case "gym":
            if #available(iOS 18.0, *) { return .fitnessCenter }
            return nil
        case "cinema":
            if #available(iOS 18.0, *) { return .movieTheater }
            return .theater
        case "toilet":
            if #available(iOS 18.0, *) { return .restroom }
            return nil
        default: return nil
        }
    }
}

// MARK: - Route Models

struct MapRoute {
    let distance: Double        // meters
    let duration: Double        // seconds
    let polyline: [CLLocationCoordinate2D]
    let maneuvers: [RouteManeuver]
    let mode: RouteMode

    var distanceFormatted: String {
        if distance >= 1000 {
            return String(format: "%.1f km", distance / 1000)
        }
        return "\(Int(distance)) m"
    }

    var durationFormatted: String {
        let mins = Int(duration / 60)
        if mins >= 60 {
            return "\(mins / 60)h \(mins % 60)min"
        }
        return "\(mins) min"
    }
}

struct RouteManeuver {
    let instruction: String
    let distance: Double         // meters to next maneuver
    let type: ManeuverType
    let coordinate: CLLocationCoordinate2D
}

enum ManeuverType: String {
    case start, destination
    case straight, slightRight, right, sharpRight
    case slightLeft, left, sharpLeft
    case uturn, roundabout, merge, fork

    var icon: String {
        switch self {
        case .start: return "location.fill"
        case .destination: return "flag.fill"
        case .straight: return "arrow.up"
        case .slightRight: return "arrow.up.right"
        case .right: return "arrow.turn.up.right"
        case .sharpRight: return "arrow.turn.right.down"
        case .slightLeft: return "arrow.up.left"
        case .left: return "arrow.turn.up.left"
        case .sharpLeft: return "arrow.turn.left.down"
        case .uturn: return "arrow.uturn.down"
        case .roundabout: return "arrow.triangle.capsulepath"
        case .merge: return "arrow.merge"
        case .fork: return "arrow.triangle.branch"
        }
    }
}

enum RouteMode: String, CaseIterable {
    case car = "car"
    case walk = "walk"
    case bike = "bike"

    var icon: String {
        switch self {
        case .car: return "car.fill"
        case .walk: return "figure.walk"
        case .bike: return "bicycle"
        }
    }

    var label: String {
        switch self {
        case .car: return NSLocalizedString("map.route_car", comment: "Drive route mode")
        case .walk: return NSLocalizedString("map.route_walk", comment: "Walk route mode")
        case .bike: return NSLocalizedString("map.route_bike", comment: "Bike route mode")
        }
    }
}
