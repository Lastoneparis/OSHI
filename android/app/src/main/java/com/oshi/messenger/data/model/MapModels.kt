package com.oshi.messenger.data.model

import com.oshi.messenger.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ═══════════════════════════════════════════════════════════════════════
// Offline Map Models — Android parity with iOS MapModels.swift
// ═══════════════════════════════════════════════════════════════════════

/**
 * Live manifest: `https://oshi-messenger.com/maps/v2/manifest.json`
 *
 * Measured 2026-08-13 (200, 292 726 B):
 *   {"version":1,"updated":"2026-08-13T02:24:38Z","regions":[234 × MapRegion]}
 *
 * Mirrors iOS `MapModels.swift:135-139` (`MapManifest{version, updated, regions}`).
 * The pre-2026-08 Android shape (`updated_at`, `min_lat`/`tile_url`/`poi_url`…)
 * shared ZERO fields with the server, and because every field carried a default
 * plus `ignoreUnknownKeys=true`, decoding SUCCEEDED into 234 all-zero regions —
 * the hardcoded fallback never fired and the tile downloader walked a 0,0 bbox.
 */
@Serializable
data class MapManifest(
    val version: Int = 1,
    val regions: List<MapRegion> = emptyList(),
    /** Server key is `updated`; `updated_at` was the (never-present) legacy name. */
    val updated: String = ""
) {
    /**
     * Regions that carry a usable bounding box. A region whose `bbox` is short
     * or degenerate (all zeros / zero area) can only produce garbage downloads,
     * so it is dropped before anything is cached — iOS gets the same guarantee
     * for free by making `bbox` non-optional and `center` bbox.count-guarded
     * (`MapModels.swift:16-24`).
     */
    val validRegions: List<MapRegion> get() = regions.filter { it.hasValidBBox }
}

/**
 * One region of the v2 manifest. Field-for-field with iOS `MapRegion`
 * (`MapModels.swift:7-14`) and with the bytes on the wire:
 *
 *   {"id":"fr-idf","name":"Ile-de-France","country":"FR","continent":"europe",
 *    "bbox":[1.44,48.12,3.56,49.24],"files":{…},"osmDate":"2026-04-29","rows":190364}
 */
@Serializable
data class MapRegion(
    val id: String,
    val name: String,
    val country: String = "",
    val continent: String = "",
    /** `[minLon, minLat, maxLon, maxLat]` — same order as iOS `MapModels.swift:12`. */
    val bbox: List<Double> = emptyList(),
    val files: MapRegionFiles = MapRegionFiles(),
    val osmDate: String = "",
    val rows: Long = 0L
) {
    /** A bbox is usable only when it has 4 finite members enclosing a non-zero area. */
    val hasValidBBox: Boolean
        get() = bbox.size >= 4 &&
            bbox.take(4).all { it.isFinite() } &&
            (bbox[2] != bbox[0]) && (bbox[3] != bbox[1])

    /**
     * __REGION_ALIAS_2026_10_21__ The country's name in the USER's language.
     *
     * The manifest ships English names only (`"Morocco"`, `"Germany"`), the row
     * renders that string raw, and [regionMatchesQuery] tests `name`/`country`/`id`.
     * So a French user searching "maroc" got **zero results** while the country
     * sat right there, and "mar" returned only Marrakech — `"Morocco"` does not
     * contain `"mar"`. Measured against the live manifest on 2026-10-21; it was
     * reported as "Morocco is missing from the app".
     *
     * Not French-specific and not fixable with a hand-written alias table: it is
     * every locale the app ships against 258 regions. `Locale.getDisplayCountry`
     * is CLDR, which ships with Android and already holds every country name in
     * every language the device supports — the right table is the one the
     * platform already maintains. iOS does the same via
     * `Locale.localizedString(forRegionCode:)` (`MapModels.swift`).
     *
     * `null` when [country] is not a 2-letter code, or when CLDR echoes the code
     * back instead of naming it; callers fall back to [name] rather than guess.
     */
    val localizedCountryName: String?
        get() {
            val code = country.trim().uppercase()
            if (code.length != 2 || !code.all { it in 'A'..'Z' }) return null
            val shown = java.util.Locale.Builder()
                .setRegion(code)
                .build()
                .getDisplayCountry(java.util.Locale.getDefault())
            // getDisplayCountry hands back the input code when it has no name
            // for it, which would make "MA" a search term twice over and label
            // a row "MA" instead of "Maroc".
            return shown.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
        }

    /**
     * The label for this region's row. Country-level regions (no dash in [id])
     * get the localized name; sub-regions keep the manifest name, because
     * `ma-cas` is "Casablanca" and calling it "Maroc" would render all 23
     * Moroccan cities as the same word.
     */
    val displayName: String
        get() = if (id.contains("-")) name else (localizedCountryName ?: name)

    val minLon: Double get() = if (bbox.size >= 4) minOf(bbox[0], bbox[2]) else 0.0
    val minLat: Double get() = if (bbox.size >= 4) minOf(bbox[1], bbox[3]) else 0.0
    val maxLon: Double get() = if (bbox.size >= 4) maxOf(bbox[0], bbox[2]) else 0.0
    val maxLat: Double get() = if (bbox.size >= 4) maxOf(bbox[1], bbox[3]) else 0.0

    val centerLat: Double get() = (minLat + maxLat) / 2.0
    val centerLon: Double get() = (minLon + maxLon) / 2.0

    fun contains(lat: Double, lon: Double): Boolean =
        hasValidBBox && lat in minLat..maxLat && lon in minLon..maxLon

    /** Country prefix used for country-level artifact fallbacks: `fr-idf` → `fr`. */
    val countryPrefix: String
        get() {
            val dash = id.indexOf('-')
            return if (dash > 0) id.substring(0, dash) else id
        }

    /**
     * Incremental bytes only — files flagged `shared: true` report `size: 0` so a
     * French sub-region isn't shown as ~5 GB just because it points at the shared
     * France artifact. Mirrors iOS `displaySize` (`MapModels.swift:26-42`).
     */
    /**
     * 🔧 P1 FIX (device walkthrough #2, 2026-08-13).
     *
     * This used to be `tiles + routing + poi` only — which produced the impossible
     * figures seen on device: **Algeria "8 MB"**, Angola "2 MB", Burkina Faso
     * "2 MB", for a card promising "the full map, thousands of points of interest
     * … and turn-by-turn navigation".
     *
     * Checked against the live manifest
     * (`https://oshi-messenger.com/maps/v2/manifest.json`, 254 regions). Algeria's
     * real row is:
     *
     * ```
     *   tiles     size = 0            ← legacy key, never a real file
     *   routing   size = 0            ← no OSRM tarball built for dz
     *   poi       size = 8 437 760    ← the ENTIRE "8 MB" the UI was showing
     *   streets   size = 1 430 805    ← omitted
     *   roadgraph size = 840 367 116  ← omitted (801 MB!)
     * ```
     *
     * `OfflineMapManager.downloadRegion` fetches streets and roadgraph too, so the
     * old sum under-reported the download by ~100×. Both are now counted.
     *
     * Two things still deliberately excluded:
     *  - `tiles`, because the server always reports `size: 0` for it: raster tiles
     *    are streamed from the /tiles proxy per zoom level, not shipped as one
     *    artifact, so there is no honest number to add.
     *  - `sharedSize`, because a shared artifact is downloaded once for a whole
     *    country; counting it per sub-region would over-report instead. It is
     *    surfaced separately by [sharedSizeFormatted].
     */
    val fileSize: Long
        get() = (files.tiles?.size ?: 0L) +
            (files.routing?.size ?: 0L) +
            (files.poi?.size ?: 0L) +
            (files.streets?.size ?: 0L) +
            (files.roadgraph?.size ?: 0L)

    /**
     * `false` when the manifest reports no measurable artifact for this region at
     * all. Callers should show "Size unavailable" rather than a confident "0 MB" —
     * see `R.string.offline_map_size_unknown`.
     */
    val hasKnownSize: Boolean get() = fileSize > 0L

    val fileSizeFormatted: String
        get() {
            val mb = fileSize / (1024.0 * 1024.0)
            // Locale.getDefault(): the bare String.format overload used the default
            // locale implicitly anyway, so this only makes the intent explicit and
            // keeps the decimal separator consistent with the rest of the row.
            return if (mb >= 1024) {
                String.format(java.util.Locale.getDefault(), "%.1f GB", mb / 1024.0)
            } else {
                String.format(java.util.Locale.getDefault(), "%.0f MB", mb)
            }
        }

    /** "+5.2 GB shared" transparency suffix — iOS `displaySharedSize` (`:48-62`). */
    val sharedSizeFormatted: String
        get() {
            val shared = (files.routing?.sharedSize ?: 0L) + (files.streets?.sharedSize ?: 0L) +
                (files.roadgraph?.sharedSize ?: 0L) + (files.poi?.sharedSize ?: 0L)
            if (shared <= 0L) return ""
            val mb = shared / (1024.0 * 1024.0)
            return if (mb >= 1024) String.format("+%.1f GB shared", mb / 1024.0)
            else String.format("+%.0f MB shared", mb)
        }
}

/**
 * Every layer is OPTIONAL and degrades on its own — iOS `MapRegionFiles`
 * (`MapModels.swift:70-97`) after the 2026-07-28 fix. Measured key coverage over
 * the live 234 regions: tiles 234, routing 234, poi 234, roadgraph **155**,
 * streets 140. 79 regions (every `fr-*` and `de-*`) have NO roadgraph, and
 * `fr-roadgraph.db.gz` 404s — so `roadgraph == null` is a normal, expected state.
 */
@Serializable
data class MapRegionFiles(
    /**
     * LEGACY: never a real file. Its url is the `{z}/{x}/{y}` template and its
     * sha256 is 64 zeros; raster tiles come from the /tiles proxy. Kept because
     * the server always emits the key (iOS `MapModels.swift:79-82`).
     */
    val tiles: MapFile? = null,
    /** LEGACY OSRM `.tar.gz` (holds `{country}-latest.osrm`, NOT a routing.db). */
    val routing: MapFile? = null,
    val poi: MapFile? = null,
    val streets: MapFile? = null,
    /** A* road graph, single-member gzip of a SQLite db. Absent for 79/234 regions. */
    val roadgraph: MapFile? = null
)

/** iOS `MapFile` (`MapModels.swift:99-133`). */
@Serializable
data class MapFile(
    val url: String = "",
    val size: Long = 0L,
    val sha256: String = "",
    /** Shared with the country-level region (e.g. `fr-idf` → `fr-streets.db.gz`). */
    val shared: Boolean? = null,
    @SerialName("shared_size") val sharedSize: Long? = null
) {
    /** `size` for a regional file, `shared_size` for an inherited country file. */
    val realSize: Long get() = if (size > 0) size else (sharedSize ?: 0L)

    /**
     * The manifest's contract for "this artifact actually exists on the server":
     * `regenerate_manifest.py` emits `size: 0` with no `shared_size` for a layer
     * it could not resolve. iOS `MapFile.isAvailable` (`MapModels.swift:132`).
     */
    val isAvailable: Boolean get() = url.isNotBlank() && realSize > 0

    /**
     * A sha256 we can actually gate on. The server writes 64 zeros for the
     * placeholder `tiles` entry, and an empty string when it has no digest —
     * neither is evidence of integrity, so both read as "no hash". iOS applies
     * the same two exclusions at `OfflineRoadGraphDownloader.swift:406-409`.
     */
    val verifiableSha256: String?
        get() = sha256.takeIf { it.isNotBlank() && it.any { c -> c != '0' } }

    /** Human size of the bytes actually transferred (`realSize`, not `size`). */
    val sizeFormatted: String
        get() {
            if (realSize <= 0L) return ""
            val mb = realSize / (1024.0 * 1024.0)
            return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0)
            else String.format("%.0f MB", mb)
        }
}

@Serializable
data class MapPOI(
    val id: String = "",
    val name: String = "",
    val category: POICategory = POICategory.OTHER,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val address: String = "",
    val phone: String = "",
    val website: String = "",
    /** OSM sub-tag (`station`, `bus_stop`, …) — server column `subcategory`. */
    val subcategory: String = "",
    /** Server column `opening_hours`. */
    val openingHours: String = "",
    /**
     * NOT a server column. The `pois` table has no `region_id`; this is stamped
     * by the caller, which always knows which region's db it queried.
     */
    @SerialName("region_id") val regionId: String = "",
    /** NOT a server column either — derived (subcategory, synthetic markers). */
    val tags: List<String> = emptyList()
)

/**
 * POI category enum.
 *
 * `displayName` is kept (English fallback) for legacy callers and any
 * non-Composable code that lacks a `Context`. Compose / Activity code should
 * prefer `displayNameRes` and call `stringResource(category.displayNameRes)`
 * so the UI honors the user's locale (16 languages supported).
 */
@Serializable
enum class POICategory(
    val displayName: String,
    val icon: String,
    val displayNameRes: Int,
    /** Compact "chip"-style label resource for tight UI rows. */
    val chipLabelRes: Int,
    /**
     * The literal string the server writes into `pois.category`.
     *
     * This is the ONLY value that may be bound into a SQL `WHERE category = ?`.
     * The enum *name* is not it: `POICategory.BUS_STATION.name.lowercase()` used
     * to produce `bus_stop`, which matches 0 of the 37 725 `bus_station` rows in
     * the live `fr-idf/poi.db`; `GAS_STATION` likewise never matched `fuel`, and
     * `EV_CHARGING` never matched `charging_station`.
     *
     * Vocabulary measured 2026-08-13 from the live db:
     *   parking 55 564 · bus_station 37 725 · restaurant 24 161 · supermarket
     *   15 232 · school 12 174 · park 4 390 · cafe 4 061 · bar 3 709 · bank 3 481
     *   · place_of_worship 3 375 · pharmacy 3 309 · gym 3 242 · hotel 3 059 ·
     *   train_station 2 775 · toilet 2 710 · charging_station 2 633 · hospital
     *   2 055 · atm 1 778 · post_office 1 461 · museum 1 002 · cinema 948 ·
     *   fuel 852 · police 633 · airport 35
     */
    val serverKey: String,
) {
    @SerialName("restaurant") RESTAURANT("Restaurant", "restaurant", R.string.map_poi_restaurant, R.string.map_poi_chip_restaurant, "restaurant"),
    @SerialName("cafe") CAFE("Cafe", "local_cafe", R.string.map_poi_cafe, R.string.map_poi_chip_cafe, "cafe"),
    @SerialName("bar") BAR("Bar", "local_bar", R.string.map_poi_bar, R.string.map_poi_bar, "bar"),
    @SerialName("hotel") HOTEL("Hotel", "hotel", R.string.map_poi_hotel, R.string.map_poi_chip_hotel, "hotel"),
    @SerialName("hospital") HOSPITAL("Hospital", "local_hospital", R.string.map_poi_hospital, R.string.map_poi_chip_hospital, "hospital"),
    @SerialName("pharmacy") PHARMACY("Pharmacy", "medication", R.string.map_poi_pharmacy, R.string.map_poi_chip_pharmacy, "pharmacy"),
    @SerialName("gas_station") GAS_STATION("Gas Station", "local_gas_station", R.string.map_poi_fuel, R.string.map_poi_chip_fuel, "fuel"),
    @SerialName("atm") ATM("ATM", "atm", R.string.map_poi_atm, R.string.map_poi_chip_atm, "atm"),
    @SerialName("bank") BANK("Bank", "account_balance", R.string.map_poi_bank, R.string.map_poi_bank, "bank"),
    @SerialName("supermarket") SUPERMARKET("Supermarket", "shopping_cart", R.string.map_poi_supermarket, R.string.map_poi_chip_supermarket, "supermarket"),
    @SerialName("parking") PARKING("Parking", "local_parking", R.string.map_poi_parking, R.string.map_poi_chip_parking, "parking"),
    @SerialName("police") POLICE("Police", "local_police", R.string.map_poi_police, R.string.map_poi_police, "police"),
    @SerialName("post_office") POST_OFFICE("Post Office", "local_post_office", R.string.map_poi_post_office, R.string.map_poi_post_office, "post_office"),
    @SerialName("school") SCHOOL("School", "school", R.string.map_poi_school, R.string.map_poi_school, "school"),
    @SerialName("university") UNIVERSITY("University", "school", R.string.map_poi_university, R.string.map_poi_university, "university"),
    @SerialName("museum") MUSEUM("Museum", "museum", R.string.map_poi_museum, R.string.map_poi_museum, "museum"),
    @SerialName("park") PARK("Park", "park", R.string.map_poi_park, R.string.map_poi_park, "park"),
    @SerialName("beach") BEACH("Beach", "beach_access", R.string.map_poi_beach, R.string.map_poi_beach, "beach"),
    @SerialName("airport") AIRPORT("Airport", "flight", R.string.map_poi_airport, R.string.map_poi_airport, "airport"),
    @SerialName("train_station") TRAIN_STATION("Train Station", "train", R.string.map_poi_train_station, R.string.map_poi_chip_train_station, "train_station"),
    @SerialName("bus_stop") BUS_STATION("Bus Station", "directions_bus", R.string.map_poi_bus_station, R.string.map_poi_chip_bus_station, "bus_station"),
    @SerialName("ev_charging") EV_CHARGING("EV Charging", "ev_station", R.string.map_poi_charging_station, R.string.map_poi_chip_charging_station, "charging_station"),
    @SerialName("gym") GYM("Gym", "fitness_center", R.string.map_poi_gym, R.string.map_poi_chip_gym, "gym"),
    @SerialName("toilet") TOILET("Toilets", "wc", R.string.map_poi_toilet, R.string.map_poi_chip_toilet, "toilet"),
    @SerialName("cinema") CINEMA("Cinema", "movie", R.string.map_poi_cinema, R.string.map_poi_chip_cinema, "cinema"),
    @SerialName("embassy") EMBASSY("Embassy", "account_balance", R.string.map_poi_embassy, R.string.map_poi_embassy, "embassy"),
    @SerialName("place_of_worship") PLACE_OF_WORSHIP("Place of Worship", "church", R.string.map_poi_place_of_worship, R.string.map_poi_place_of_worship, "place_of_worship"),
    @SerialName("other") OTHER("Other", "place", R.string.map_poi_other, R.string.map_poi_other, "other");

    companion object {
        private val byServerKey: Map<String, POICategory> =
            entries.associateBy { it.serverKey }

        /**
         * Resolve a raw `pois.category` value. Matches on [serverKey] first (the
         * server vocabulary), then on the enum name as a courtesy for rows written
         * by older Android builds. Unknown → [OTHER], never an exception.
         */
        fun fromServerKey(raw: String?): POICategory {
            val key = raw?.trim()?.lowercase().orEmpty()
            if (key.isEmpty()) return OTHER
            byServerKey[key]?.let { return it }
            return entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: OTHER
        }
    }
}

@Serializable
data class MapRoute(
    val points: List<RoutePoint> = emptyList(),
    val distance: Double = 0.0, // meters
    val duration: Double = 0.0, // seconds
    val mode: RouteMode = RouteMode.DRIVING,
    val maneuvers: List<RouteManeuver> = emptyList()
) {
    val distanceFormatted: String
        get() {
            return if (distance >= 1000) {
                String.format("%.1f km", distance / 1000.0)
            } else {
                String.format("%.0f m", distance)
            }
        }

    val durationFormatted: String
        get() {
            val totalMinutes = (duration / 60).toInt()
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return if (hours > 0) {
                "${hours}h ${minutes}min"
            } else {
                "${minutes} min"
            }
        }
}

@Serializable
data class RoutePoint(
    val lat: Double,
    val lon: Double
)

@Serializable
data class RouteManeuver(
    val instruction: String = "",
    val distance: Double = 0.0,
    val type: String = "", // "turn-left", "turn-right", "continue", "arrive"
    val lat: Double = 0.0,
    val lon: Double = 0.0
)

@Serializable
enum class RouteMode {
    @SerialName("driving") DRIVING,
    @SerialName("walking") WALKING,
    @SerialName("cycling") CYCLING
}

enum class RegionDownloadState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    UPDATE_AVAILABLE,
    ERROR
}

@Serializable
data class DownloadedRegionMetadata(
    val regionId: String,
    val version: Int,
    val downloadedAt: Long = System.currentTimeMillis(),
    val sizeBytes: Long = 0L,
    val tileCount: Int = 0,
    val poiCount: Int = 0
)

data class RegionDownloadProgress(
    val regionId: String,
    val progress: Float = 0f, // 0.0 to 1.0
    val state: RegionDownloadState = RegionDownloadState.NOT_DOWNLOADED,
    val error: String? = null
)

// ═══════════════════════════════════════════════════════════════════════
// Offline Routing Models
// ═══════════════════════════════════════════════════════════════════════

// NOTE (2026-08-13): `RoutingManifest` / `RoutingFileEntry` are DELETED.
// They modelled `https://oshi-messenger.com/maps/v2/routing/manifest.json`, an
// Android-only URL that has never existed — it 404s (probed), so every launch
// fell through to a hardcoded fallback whose 27 `/maps/v2/routing/{c}-routing.bin`
// entries ALSO 404 (probed). Worse, `findRegionForLocation` keyed off those 27
// country ids while the graph is stored under the real region id (`fr-idf`), so
// offline routing could never resolve a region even if a graph existed.
// The single source of truth is now `MapManifest.regions[].files.roadgraph`.

@Serializable
data class RoutingRegionMeta(
    val regionId: String,
    val version: Int = 1,
    val downloadedAt: Long = System.currentTimeMillis(),
    val sizeBytes: Long = 0L,
    val profiles: List<String> = emptyList()
)

enum class RoutingDownloadState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    ERROR
}

data class RoutingDownloadProgress(
    val regionId: String,
    val progress: Float = 0f,
    val state: RoutingDownloadState = RoutingDownloadState.NOT_DOWNLOADED,
    val error: String? = null
)

// ═══════════════════════════════════════════════════════════════════════
// Offline Street Index Models
// ═══════════════════════════════════════════════════════════════════════

data class StreetResult(
    val id: Long = 0L,
    val name: String,
    val city: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val highwayType: String = "" // residential, primary, secondary, etc.
)

enum class StreetDownloadState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    ERROR
}

data class StreetDownloadProgress(
    val regionId: String,
    val progress: Float = 0f,
    val state: StreetDownloadState = StreetDownloadState.NOT_DOWNLOADED,
    val error: String? = null
)

// ═══════════════════════════════════════════════════════════════════════
// Offline Address Match (house-number aware) — parity with iOS AddressMatch
// ═══════════════════════════════════════════════════════════════════════

enum class AddressMatchConfidence { EXACT, INTERPOLATED }

data class AddressMatch(
    val houseNumber: String,
    val streetName: String,
    val city: String = "",
    val lat: Double,
    val lon: Double,
    val regionId: String,
    val confidence: AddressMatchConfidence
) {
    /** Stable string id for set-deduplication. */
    val id: String get() = "$regionId:$streetName:$houseNumber"

    /** Render this match as a MapPOI so existing search-list code can show it. */
    fun toMapPOI(): MapPOI = MapPOI(
        id = id,
        name = "📍 $houseNumber $streetName",
        category = POICategory.OTHER,
        lat = lat,
        lon = lon,
        address = if (city.isNotBlank()) "$houseNumber $streetName, $city"
                  else "$houseNumber $streetName",
        regionId = regionId,
        tags = listOf("address", confidence.name.lowercase())
    )
}
