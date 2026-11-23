package com.oshi.messenger.ui.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.oshi.messenger.data.model.*
import com.oshi.messenger.service.MapPOIDatabase
import com.oshi.messenger.service.OfflineMapManager
import com.oshi.messenger.service.OfflineRoutingManager
import com.oshi.messenger.service.OfflineStreetDatabase
import com.oshi.messenger.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.inject.Inject

/**
 * MapViewModel — connects map UI screens to OfflineMapManager and POI search.
 * Manages user location, search state, route state, and region downloads.
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val offlineMapManager: OfflineMapManager,
    private val poiDatabase: MapPOIDatabase,
    private val offlineRoutingManager: OfflineRoutingManager,
    private val offlineStreetDatabase: OfflineStreetDatabase
) : ViewModel() {

    /**
     * The user's "Offline maps only" switch, read from the one place that now owns it.
     *
     * iOS gates tiles, search AND routing on the same `oshi.map.offlineOnly`
     * (`OSHI/MapRoutingEngine.swift:42`, `OSHI/OfflineFirstTileOverlay.swift:13`,
     * `OSHI/OfflineMapView.swift:2267`). On Android search checked nothing at all, so
     * a user who had asked for offline-only still sent every query — and a viewbox
     * centred on their own position — to Nominatim and Overpass.
     */
    private fun isOfflineOnly(): Boolean =
        com.oshi.messenger.service.map.OshiOfflineTileProvider.isOfflineOnly(context)

    /**
     * One online geocoder result plus the geocoder's own relevance score.
     * Public so the JVM unit tests can build fixtures without touching Nominatim.
     */
    data class OnlineHit(val poi: MapPOI, val importance: Double)

    companion object {
        // __THE_MAP_ALWAYS_OPENED_IN_PARIS_2026_11_07__
        private const val MAP_CENTER_PREFS = "oshi_map_center"
        private const val PREF_CENTER_LAT = "last_center_lat"
        private const val PREF_CENTER_LON = "last_center_lon"
        // __THE_MAP_DECIDED_WHAT_YOU_SAW_2026_11_07__
        private const val PREF_SHOW_POIS = "show_pois"
        private const val TAG = "MapViewModel"

        /**
         * 🔧 P1 FIX (device walkthrough #2, 2026-08-13).
         *
         * On device, searching **"Paris"** returned three rows: *"Paris, France"*
         * twice — a visible duplicate — and *"Bali, Indonesia"*, unrelated.
         *
         * Both symptoms had the same cause: `searchNominatim`'s output was assigned
         * to `_searchResults` **raw**. The haversine sort a few lines above only
         * touched the *offline* list, so the online list kept Nominatim's own order
         * and every dedupe in the file keys on `MapPOI.id` — which is minted from
         * `osm_id`. Nominatim models a city as several distinct OSM objects (the
         * `place=city` node, the admin `relation`, boundary ways…), so the "same"
         * place arrives with different ids, survives every id-based dedupe, and
         * renders as identical rows because the subtitle is rebuilt from
         * `city, country`.
         *
         * This function is the missing step, and is pure so it can be unit-tested:
         *
         *  1. **Dedupe on what the user sees** — normalised name + normalised
         *     address, not on `id`. First occurrence wins.
         *  2. **Rank.** Exact name match first, then prefix, then substring, then
         *     the rest; ties broken by proximity to the user when a location is
         *     known, and finally by Nominatim's own `importance`. That is what
         *     pushes a business called "Paris" in Bali below the city of Paris
         *     instead of letting arrival order decide.
         *
         * Deliberately does NOT drop the low-ranked rows: Nominatim returned them
         * for a reason, and silently hiding matches is its own failure mode. They
         * are demoted, not censored.
         */
        @JvmStatic
        fun refineOnlineHits(
            query: String,
            hits: List<OnlineHit>,
            userLat: Double?,
            userLon: Double?
        ): List<MapPOI> {
            val q = normalizeForDedup(query)

            // 1. Dedupe on the rendered identity, preserving arrival order.
            val seen = LinkedHashMap<String, OnlineHit>()
            for (hit in hits) {
                val key = normalizeForDedup(hit.poi.name) + "|" + normalizeForDedup(hit.poi.address)
                if (!seen.containsKey(key)) seen[key] = hit
            }

            // 2. Rank. Lower rank value = better.
            fun matchRank(name: String): Int {
                val n = normalizeForDedup(name)
                return when {
                    q.isEmpty() -> 3
                    n == q -> 0
                    n.startsWith(q) -> 1
                    n.contains(q) -> 2
                    else -> 3
                }
            }

            return seen.values.sortedWith(
                compareBy<OnlineHit> { matchRank(it.poi.name) }
                    .thenBy {
                        if (userLat != null && userLon != null) {
                            haversineKm(userLat, userLon, it.poi.lat, it.poi.lon)
                        } else {
                            0.0
                        }
                    }
                    // Nominatim's importance is "higher is better", so negate it.
                    .thenBy { -it.importance }
            ).map { it.poi }
        }

        /** Case-folded, diacritic-stripped, whitespace-collapsed. Pure. */
        internal fun normalizeForDedup(value: String): String =
            java.text.Normalizer.normalize(value.trim().lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("\\s+"), " ")

        /** Great-circle distance in km. Pure — unit tested. */
        internal fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0088
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
            return 2 * r * kotlin.math.asin(kotlin.math.min(1.0, kotlin.math.sqrt(a)))
        }

        /**
         * Multilingual keyword → category map for natural-language search dispatch.
         * Order matters: longer / more-specific phrases must precede their single-word
         * synonyms so "la poste" matches POST_OFFICE before "poste" alone could
         * be matched as a generic word inside another phrase.
         *
         * Folded (case + diacritics stripped) at lookup time — see [foldForCategoryMatch].
         */
        private val CATEGORY_KEYWORDS: List<Pair<String, POICategory>> = listOf(
            // Post offices — France's "La Poste" is a CATEGORY, not a name.
            "la poste" to POICategory.POST_OFFICE,
            "post office" to POICategory.POST_OFFICE,
            "poste" to POICategory.POST_OFFICE,
            "post" to POICategory.POST_OFFICE,
            "correos" to POICategory.POST_OFFICE,
            "ufficio postale" to POICategory.POST_OFFICE,
            "post amt" to POICategory.POST_OFFICE,
            "postamt" to POICategory.POST_OFFICE,
            "correio" to POICategory.POST_OFFICE,         // PT
            "郵便局" to POICategory.POST_OFFICE,          // JA "yuubinkyoku"
            "yubinkyoku" to POICategory.POST_OFFICE,
            "우체국" to POICategory.POST_OFFICE,          // KO "ucheguk"
            "邮局" to POICategory.POST_OFFICE,            // ZH-CN

            // Hospitals / ER
            "hopital" to POICategory.HOSPITAL,
            "hôpital" to POICategory.HOSPITAL,
            "hospital" to POICategory.HOSPITAL,
            "clinic" to POICategory.HOSPITAL,
            "clinique" to POICategory.HOSPITAL,
            "clinica" to POICategory.HOSPITAL,
            "urgences" to POICategory.HOSPITAL,
            "emergency" to POICategory.HOSPITAL,
            "emergency room" to POICategory.HOSPITAL,
            "er" to POICategory.HOSPITAL,
            "a&e" to POICategory.HOSPITAL,
            "krankenhaus" to POICategory.HOSPITAL,
            "klinik" to POICategory.HOSPITAL,
            "ospedale" to POICategory.HOSPITAL,
            "pronto soccorso" to POICategory.HOSPITAL,
            "病院" to POICategory.HOSPITAL,               // JA byouin
            "byouin" to POICategory.HOSPITAL,
            "병원" to POICategory.HOSPITAL,               // KO
            "医院" to POICategory.HOSPITAL,               // ZH

            // Pharmacy
            "pharmacie" to POICategory.PHARMACY,
            "pharmacy" to POICategory.PHARMACY,
            "drugstore" to POICategory.PHARMACY,
            "chemist" to POICategory.PHARMACY,
            "apotheke" to POICategory.PHARMACY,
            "farmacia" to POICategory.PHARMACY,
            "farmácia" to POICategory.PHARMACY,
            "薬局" to POICategory.PHARMACY,               // JA yakkyoku
            "yakkyoku" to POICategory.PHARMACY,
            "약국" to POICategory.PHARMACY,               // KO
            "药店" to POICategory.PHARMACY,               // ZH

            // Cafe
            "cafe" to POICategory.CAFE,
            "café" to POICategory.CAFE,
            "coffee" to POICategory.CAFE,
            "coffee shop" to POICategory.CAFE,
            "kaffee" to POICategory.CAFE,
            "caffe" to POICategory.CAFE,
            "caffè" to POICategory.CAFE,
            "caffetteria" to POICategory.CAFE,
            "cafeteria" to POICategory.CAFE,
            "cafetería" to POICategory.CAFE,
            "salon de the" to POICategory.CAFE,
            "salon de thé" to POICategory.CAFE,
            "tea house" to POICategory.CAFE,
            "tea room" to POICategory.CAFE,
            "カフェ" to POICategory.CAFE,                 // JA
            "喫茶店" to POICategory.CAFE,                 // JA kissaten
            "kissaten" to POICategory.CAFE,
            "카페" to POICategory.CAFE,                   // KO
            "咖啡" to POICategory.CAFE,                   // ZH
            "咖啡馆" to POICategory.CAFE,                 // ZH

            // Restaurant
            "restaurant" to POICategory.RESTAURANT,
            "resto" to POICategory.RESTAURANT,
            "brasserie" to POICategory.RESTAURANT,
            "bistro" to POICategory.RESTAURANT,
            "ristorante" to POICategory.RESTAURANT,
            "trattoria" to POICategory.RESTAURANT,
            "restaurante" to POICategory.RESTAURANT,
            "comedor" to POICategory.RESTAURANT,
            "diner" to POICategory.RESTAURANT,
            "eatery" to POICategory.RESTAURANT,
            "fast food" to POICategory.RESTAURANT,
            "fastfood" to POICategory.RESTAURANT,
            "gaststätte" to POICategory.RESTAURANT,
            "gaststaette" to POICategory.RESTAURANT,
            "レストラン" to POICategory.RESTAURANT,       // JA
            "食堂" to POICategory.RESTAURANT,             // JA shokudou / ZH
            "shokudou" to POICategory.RESTAURANT,
            "식당" to POICategory.RESTAURANT,             // KO
            "음식점" to POICategory.RESTAURANT,           // KO
            "餐厅" to POICategory.RESTAURANT,             // ZH-CN
            "餐廳" to POICategory.RESTAURANT,             // ZH-TW

            // Bar / pub
            "bar" to POICategory.BAR,
            "pub" to POICategory.BAR,
            "nightclub" to POICategory.BAR,
            "boite de nuit" to POICategory.BAR,
            "boîte de nuit" to POICategory.BAR,
            "kneipe" to POICategory.BAR,
            "biergarten" to POICategory.BAR,
            "taverna" to POICategory.BAR,
            "izakaya" to POICategory.BAR,
            "居酒屋" to POICategory.BAR,                  // JA izakaya
            "술집" to POICategory.BAR,                    // KO
            "酒吧" to POICategory.BAR,                    // ZH

            // Hotel
            "hotel" to POICategory.HOTEL,
            "hôtel" to POICategory.HOTEL,
            "hostel" to POICategory.HOTEL,
            "auberge" to POICategory.HOTEL,
            "auberge de jeunesse" to POICategory.HOTEL,
            "albergo" to POICategory.HOTEL,
            "ostello" to POICategory.HOTEL,
            "pousada" to POICategory.HOTEL,
            "guest house" to POICategory.HOTEL,
            "guesthouse" to POICategory.HOTEL,
            "motel" to POICategory.HOTEL,
            "ホテル" to POICategory.HOTEL,                // JA
            "旅館" to POICategory.HOTEL,                  // JA ryokan / ZH
            "ryokan" to POICategory.HOTEL,
            "호텔" to POICategory.HOTEL,                  // KO
            "酒店" to POICategory.HOTEL,                  // ZH
            "饭店" to POICategory.HOTEL,                  // ZH

            // Supermarket / grocery / market — bare "market" is the most
            // common user query; covers OSM marketplace + supermarket together,
            // plus typed shops (bakery/butcher/greengrocer) which users expect
            // to see when searching "market" or "épicerie".
            "market" to POICategory.SUPERMARKET,
            "marche" to POICategory.SUPERMARKET,        // FR (no accent)
            "marché" to POICategory.SUPERMARKET,        // FR (with accent)
            "marketplace" to POICategory.SUPERMARKET,
            "farmers market" to POICategory.SUPERMARKET,
            "supermarche" to POICategory.SUPERMARKET,
            "supermarché" to POICategory.SUPERMARKET,
            "supermarket" to POICategory.SUPERMARKET,
            "hypermarche" to POICategory.SUPERMARKET,
            "hypermarché" to POICategory.SUPERMARKET,
            "epicerie" to POICategory.SUPERMARKET,
            "épicerie" to POICategory.SUPERMARKET,
            "superette" to POICategory.SUPERMARKET,
            "supérette" to POICategory.SUPERMARKET,
            "grocery" to POICategory.SUPERMARKET,
            "grocery store" to POICategory.SUPERMARKET,
            "groceries" to POICategory.SUPERMARKET,
            "convenience" to POICategory.SUPERMARKET,   // OSM "convenience"
            "convenience store" to POICategory.SUPERMARKET,
            "minimart" to POICategory.SUPERMARKET,
            "corner shop" to POICategory.SUPERMARKET,
            "corner store" to POICategory.SUPERMARKET,
            "supermercado" to POICategory.SUPERMARKET,
            "supermercato" to POICategory.SUPERMARKET,
            "alimentari" to POICategory.SUPERMARKET,    // IT corner shop
            "mercato" to POICategory.SUPERMARKET,        // IT
            "mercado" to POICategory.SUPERMARKET,        // ES/PT
            "markt" to POICategory.SUPERMARKET,          // DE/NL
            "supermarkt" to POICategory.SUPERMARKET,
            "lebensmittel" to POICategory.SUPERMARKET,   // DE
            "bäckerei" to POICategory.SUPERMARKET,       // DE bakery
            "baeckerei" to POICategory.SUPERMARKET,
            "bakery" to POICategory.SUPERMARKET,
            "boulangerie" to POICategory.SUPERMARKET,
            "panaderia" to POICategory.SUPERMARKET,
            "panadería" to POICategory.SUPERMARKET,
            "panetteria" to POICategory.SUPERMARKET,
            "boucherie" to POICategory.SUPERMARKET,
            "butcher" to POICategory.SUPERMARKET,
            "butchery" to POICategory.SUPERMARKET,
            "metzgerei" to POICategory.SUPERMARKET,
            "macelleria" to POICategory.SUPERMARKET,
            "carniceria" to POICategory.SUPERMARKET,
            "carnicería" to POICategory.SUPERMARKET,
            "primeur" to POICategory.SUPERMARKET,
            "greengrocer" to POICategory.SUPERMARKET,
            "fruit shop" to POICategory.SUPERMARKET,
            "verduleria" to POICategory.SUPERMARKET,
            "ortofrutta" to POICategory.SUPERMARKET,
            "general store" to POICategory.SUPERMARKET,
            "deli" to POICategory.SUPERMARKET,
            "delicatessen" to POICategory.SUPERMARKET,
            "fromagerie" to POICategory.SUPERMARKET,
            "スーパー" to POICategory.SUPERMARKET,        // JA
            "スーパーマーケット" to POICategory.SUPERMARKET,
            "コンビニ" to POICategory.SUPERMARKET,        // JA convenience
            "市場" to POICategory.SUPERMARKET,            // JA ichiba / ZH
            "ichiba" to POICategory.SUPERMARKET,
            "슈퍼" to POICategory.SUPERMARKET,            // KO
            "슈퍼마켓" to POICategory.SUPERMARKET,        // KO
            "편의점" to POICategory.SUPERMARKET,          // KO convenience
            "超市" to POICategory.SUPERMARKET,            // ZH
            "便利店" to POICategory.SUPERMARKET,          // ZH

            // Gas / fuel
            "essence" to POICategory.GAS_STATION,
            "station service" to POICategory.GAS_STATION,
            "station-service" to POICategory.GAS_STATION,
            "gas" to POICategory.GAS_STATION,
            "fuel" to POICategory.GAS_STATION,
            "petrol" to POICategory.GAS_STATION,
            "petrol station" to POICategory.GAS_STATION,
            "gas station" to POICategory.GAS_STATION,
            "tankstelle" to POICategory.GAS_STATION,
            "gasolinera" to POICategory.GAS_STATION,
            "estacion de servicio" to POICategory.GAS_STATION,
            "benzina" to POICategory.GAS_STATION,
            "distributore" to POICategory.GAS_STATION,
            "ガソリンスタンド" to POICategory.GAS_STATION,// JA
            "주유소" to POICategory.GAS_STATION,          // KO
            "加油站" to POICategory.GAS_STATION,          // ZH

            // ATM
            "atm" to POICategory.ATM,
            "distributeur" to POICategory.ATM,
            "distributeur de billets" to POICategory.ATM,
            "guichet automatique" to POICategory.ATM,
            "bancomat" to POICategory.ATM,
            "cajero" to POICategory.ATM,
            "cajero automatico" to POICategory.ATM,
            "cajero automático" to POICategory.ATM,
            "geldautomat" to POICategory.ATM,
            "cash machine" to POICategory.ATM,
            "cashpoint" to POICategory.ATM,
            "atm機" to POICategory.ATM,
            "현금인출기" to POICategory.ATM,              // KO
            "自動取款機" to POICategory.ATM,              // ZH-TW
            "取款机" to POICategory.ATM,                  // ZH-CN

            // Bank
            "banque" to POICategory.BANK,
            "bank" to POICategory.BANK,
            "banco" to POICategory.BANK,
            "banca" to POICategory.BANK,
            "sparkasse" to POICategory.BANK,             // DE savings bank
            "credit union" to POICategory.BANK,
            "銀行" to POICategory.BANK,                   // JA ginkou / ZH
            "ginkou" to POICategory.BANK,
            "은행" to POICategory.BANK,                   // KO
            "银行" to POICategory.BANK,                   // ZH-CN

            // Parking
            "parking" to POICategory.PARKING,
            "parc auto" to POICategory.PARKING,
            "parc de stationnement" to POICategory.PARKING,
            "stationnement" to POICategory.PARKING,
            "parcheggio" to POICategory.PARKING,
            "estacionamiento" to POICategory.PARKING,
            "aparcamiento" to POICategory.PARKING,
            "parkplatz" to POICategory.PARKING,
            "parkhaus" to POICategory.PARKING,
            "car park" to POICategory.PARKING,
            "garage" to POICategory.PARKING,
            "駐車場" to POICategory.PARKING,              // JA
            "주차장" to POICategory.PARKING,              // KO
            "停车场" to POICategory.PARKING,              // ZH

            // Police
            "police" to POICategory.POLICE,
            "police station" to POICategory.POLICE,
            "commissariat" to POICategory.POLICE,
            "gendarmerie" to POICategory.POLICE,
            "polizia" to POICategory.POLICE,
            "carabinieri" to POICategory.POLICE,
            "policia" to POICategory.POLICE,
            "policía" to POICategory.POLICE,
            "guardia civil" to POICategory.POLICE,
            "polizei" to POICategory.POLICE,
            "polizeiwache" to POICategory.POLICE,
            "警察" to POICategory.POLICE,                 // JA / ZH
            "交番" to POICategory.POLICE,                 // JA koban
            "koban" to POICategory.POLICE,
            "경찰서" to POICategory.POLICE,               // KO
            "派出所" to POICategory.POLICE,               // ZH

            // School / university
            "ecole" to POICategory.SCHOOL,
            "école" to POICategory.SCHOOL,
            "lycee" to POICategory.SCHOOL,
            "lycée" to POICategory.SCHOOL,
            "college" to POICategory.SCHOOL,
            "collège" to POICategory.SCHOOL,
            "school" to POICategory.SCHOOL,
            "high school" to POICategory.SCHOOL,
            "kindergarten" to POICategory.SCHOOL,
            "university" to POICategory.SCHOOL,
            "universite" to POICategory.SCHOOL,
            "université" to POICategory.SCHOOL,
            "scuola" to POICategory.SCHOOL,
            "università" to POICategory.SCHOOL,
            "escuela" to POICategory.SCHOOL,
            "colegio" to POICategory.SCHOOL,
            "universidad" to POICategory.SCHOOL,
            "schule" to POICategory.SCHOOL,
            "gymnasium" to POICategory.SCHOOL,
            "universität" to POICategory.SCHOOL,
            "学校" to POICategory.SCHOOL,                 // JA / ZH gakkou
            "gakkou" to POICategory.SCHOOL,
            "大学" to POICategory.SCHOOL,                 // JA daigaku / ZH
            "학교" to POICategory.SCHOOL,                 // KO
            "대학교" to POICategory.SCHOOL,               // KO

            // Museum
            "musee" to POICategory.MUSEUM,
            "musée" to POICategory.MUSEUM,
            "museum" to POICategory.MUSEUM,
            "museo" to POICategory.MUSEUM,
            "gallery" to POICategory.MUSEUM,
            "galerie" to POICategory.MUSEUM,
            "art gallery" to POICategory.MUSEUM,
            "galleria" to POICategory.MUSEUM,
            "kunstgalerie" to POICategory.MUSEUM,
            "博物館" to POICategory.MUSEUM,               // JA / ZH
            "美術館" to POICategory.MUSEUM,               // JA bijutsukan
            "박물관" to POICategory.MUSEUM,               // KO
            "미술관" to POICategory.MUSEUM,               // KO

            // Park / garden
            "parc" to POICategory.PARK,
            "park" to POICategory.PARK,
            "parque" to POICategory.PARK,
            "parco" to POICategory.PARK,
            "garden" to POICategory.PARK,
            "gardens" to POICategory.PARK,
            "jardin" to POICategory.PARK,
            "jardín" to POICategory.PARK,
            "giardino" to POICategory.PARK,
            "jardim" to POICategory.PARK,
            "garten" to POICategory.PARK,
            "公園" to POICategory.PARK,                   // JA kouen / ZH
            "kouen" to POICategory.PARK,
            "공원" to POICategory.PARK,                   // KO

            // Beach
            "plage" to POICategory.BEACH,
            "beach" to POICategory.BEACH,
            "playa" to POICategory.BEACH,
            "praia" to POICategory.BEACH,
            "spiaggia" to POICategory.BEACH,
            "strand" to POICategory.BEACH,
            "海岸" to POICategory.BEACH,                  // JA / ZH
            "ビーチ" to POICategory.BEACH,                // JA
            "해변" to POICategory.BEACH,                  // KO
            "海滩" to POICategory.BEACH,                  // ZH

            // Airport
            "aeroport" to POICategory.AIRPORT,
            "aéroport" to POICategory.AIRPORT,
            "airport" to POICategory.AIRPORT,
            "aeroporto" to POICategory.AIRPORT,
            "aeropuerto" to POICategory.AIRPORT,
            "flughafen" to POICategory.AIRPORT,
            "空港" to POICategory.AIRPORT,                // JA kuukou
            "공항" to POICategory.AIRPORT,                // KO
            "机场" to POICategory.AIRPORT,                // ZH-CN
            "機場" to POICategory.AIRPORT,                // ZH-TW

            // Train station
            "gare" to POICategory.TRAIN_STATION,
            "gare ferroviaire" to POICategory.TRAIN_STATION,
            "train station" to POICategory.TRAIN_STATION,
            "railway station" to POICategory.TRAIN_STATION,
            "metro" to POICategory.TRAIN_STATION,
            "métro" to POICategory.TRAIN_STATION,
            "subway" to POICategory.TRAIN_STATION,
            "estacion" to POICategory.TRAIN_STATION,
            "estación" to POICategory.TRAIN_STATION,
            "estacion de tren" to POICategory.TRAIN_STATION,
            "estação" to POICategory.TRAIN_STATION,
            "stazione" to POICategory.TRAIN_STATION,
            "stazione ferroviaria" to POICategory.TRAIN_STATION,
            "bahnhof" to POICategory.TRAIN_STATION,
            "hauptbahnhof" to POICategory.TRAIN_STATION,
            "u-bahn" to POICategory.TRAIN_STATION,
            "s-bahn" to POICategory.TRAIN_STATION,
            "駅" to POICategory.TRAIN_STATION,            // JA eki
            "eki" to POICategory.TRAIN_STATION,
            "鉄道駅" to POICategory.TRAIN_STATION,        // JA
            "역" to POICategory.TRAIN_STATION,            // KO yeok
            "지하철역" to POICategory.TRAIN_STATION,      // KO
            "火车站" to POICategory.TRAIN_STATION,        // ZH-CN
            "車站" to POICategory.TRAIN_STATION,          // ZH-TW

            // Bus
            "bus" to POICategory.BUS_STATION,
            "bus stop" to POICategory.BUS_STATION,
            "bus station" to POICategory.BUS_STATION,
            "arret" to POICategory.BUS_STATION,
            "arrêt" to POICategory.BUS_STATION,
            "arret de bus" to POICategory.BUS_STATION,
            "arrêt de bus" to POICategory.BUS_STATION,
            "gare routiere" to POICategory.BUS_STATION,
            "gare routière" to POICategory.BUS_STATION,
            "fermata" to POICategory.BUS_STATION,
            "fermata autobus" to POICategory.BUS_STATION,
            "parada" to POICategory.BUS_STATION,
            "parada de autobus" to POICategory.BUS_STATION,
            "bushaltestelle" to POICategory.BUS_STATION,
            "busbahnhof" to POICategory.BUS_STATION,
            "バス停" to POICategory.BUS_STATION,             // JA basutei
            "버스정류장" to POICategory.BUS_STATION,         // KO
            "公交车站" to POICategory.BUS_STATION,           // ZH

            // EV charging
            "borne de recharge" to POICategory.EV_CHARGING,
            "borne electrique" to POICategory.EV_CHARGING,
            "borne électrique" to POICategory.EV_CHARGING,
            "recharge electrique" to POICategory.EV_CHARGING,
            "recharge électrique" to POICategory.EV_CHARGING,
            "ev charging" to POICategory.EV_CHARGING,
            "ev charger" to POICategory.EV_CHARGING,
            "ev station" to POICategory.EV_CHARGING,
            "charging station" to POICategory.EV_CHARGING,
            "charger" to POICategory.EV_CHARGING,
            "ladestation" to POICategory.EV_CHARGING,
            "ladesäule" to POICategory.EV_CHARGING,
            "ricarica" to POICategory.EV_CHARGING,
            "estacion de carga" to POICategory.EV_CHARGING,
            "estación de carga" to POICategory.EV_CHARGING,
            "punto de carga" to POICategory.EV_CHARGING,
            "充電スタンド" to POICategory.EV_CHARGING,    // JA
            "충전소" to POICategory.EV_CHARGING,          // KO
            "充电站" to POICategory.EV_CHARGING,          // ZH

            // Embassy / consulate
            "ambassade" to POICategory.EMBASSY,
            "embassy" to POICategory.EMBASSY,
            "consulate" to POICategory.EMBASSY,
            "consulat" to POICategory.EMBASSY,
            "embajada" to POICategory.EMBASSY,
            "consulado" to POICategory.EMBASSY,
            "ambasciata" to POICategory.EMBASSY,
            "consolato" to POICategory.EMBASSY,
            "botschaft" to POICategory.EMBASSY,
            "konsulat" to POICategory.EMBASSY,
            "大使館" to POICategory.EMBASSY,              // JA / ZH
            "領事館" to POICategory.EMBASSY,              // JA / ZH
            "대사관" to POICategory.EMBASSY,              // KO
            "영사관" to POICategory.EMBASSY,              // KO

            // Place of worship
            "eglise" to POICategory.PLACE_OF_WORSHIP,
            "église" to POICategory.PLACE_OF_WORSHIP,
            "cathedrale" to POICategory.PLACE_OF_WORSHIP,
            "cathédrale" to POICategory.PLACE_OF_WORSHIP,
            "chapelle" to POICategory.PLACE_OF_WORSHIP,
            "church" to POICategory.PLACE_OF_WORSHIP,
            "cathedral" to POICategory.PLACE_OF_WORSHIP,
            "chapel" to POICategory.PLACE_OF_WORSHIP,
            "mosque" to POICategory.PLACE_OF_WORSHIP,
            "mosquee" to POICategory.PLACE_OF_WORSHIP,
            "mosquée" to POICategory.PLACE_OF_WORSHIP,
            "synagogue" to POICategory.PLACE_OF_WORSHIP,
            "temple" to POICategory.PLACE_OF_WORSHIP,
            "shrine" to POICategory.PLACE_OF_WORSHIP,
            "monastery" to POICategory.PLACE_OF_WORSHIP,
            "moschea" to POICategory.PLACE_OF_WORSHIP,
            "mezquita" to POICategory.PLACE_OF_WORSHIP,
            "kirche" to POICategory.PLACE_OF_WORSHIP,
            "dom" to POICategory.PLACE_OF_WORSHIP,
            "kapelle" to POICategory.PLACE_OF_WORSHIP,
            "moschee" to POICategory.PLACE_OF_WORSHIP,
            "iglesia" to POICategory.PLACE_OF_WORSHIP,
            "catedral" to POICategory.PLACE_OF_WORSHIP,
            "chiesa" to POICategory.PLACE_OF_WORSHIP,
            "duomo" to POICategory.PLACE_OF_WORSHIP,
            "教会" to POICategory.PLACE_OF_WORSHIP,       // JA / ZH
            "神社" to POICategory.PLACE_OF_WORSHIP,       // JA jinja
            "jinja" to POICategory.PLACE_OF_WORSHIP,
            "寺" to POICategory.PLACE_OF_WORSHIP,         // JA / ZH otera
            "otera" to POICategory.PLACE_OF_WORSHIP,
            "교회" to POICategory.PLACE_OF_WORSHIP,       // KO
            "성당" to POICategory.PLACE_OF_WORSHIP,       // KO
            "사찰" to POICategory.PLACE_OF_WORSHIP,       // KO
            "教堂" to POICategory.PLACE_OF_WORSHIP,       // ZH
            "寺庙" to POICategory.PLACE_OF_WORSHIP        // ZH
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Forwarded state from OfflineMapManager
    // ═══════════════════════════════════════════════════════════════

    val regions = offlineMapManager.regions
    val downloadProgress = offlineMapManager.downloadProgress
    val isLoadingManifest = offlineMapManager.isLoadingManifest
    val totalStorageUsed = offlineMapManager.totalStorageUsed

    // ═══════════════════════════════════════════════════════════════
    // Location state
    // ═══════════════════════════════════════════════════════════════

    private val _userLocation = MutableStateFlow<Location?>(null)
    val userLocation: StateFlow<Location?> = _userLocation.asStateFlow()

    /**
     * __THE_MAP_ALWAYS_OPENED_IN_PARIS_2026_11_07__ (Anatole N4: "every time it
     * opens it is in Paris instead of our last position".)
     *
     * These were `MutableStateFlow(48.8566)` / `(2.3522)` — the Paris fallback —
     * held in memory ONLY. Nothing anywhere persisted the centre or the last
     * known location, so every launch started in Paris and stayed there until a
     * GPS fix arrived. Indoors, with location denied, or on a slow fix it never
     * moved: the fallback was not a fallback, it was the default experience.
     *
     * Seeded now from the last place the map was actually centred. Paris
     * survives only as the genuinely-first-run value — the app has to open
     * somewhere before it has ever had a location.
     */
    private val mapPrefs by lazy {
        context.getSharedPreferences(MAP_CENTER_PREFS, Context.MODE_PRIVATE)
    }

    private val _mapCenterLat = MutableStateFlow(
        mapPrefs.getFloat(PREF_CENTER_LAT, 48.8566f).toDouble()
    )
    val mapCenterLat: StateFlow<Double> = _mapCenterLat.asStateFlow()

    private val _mapCenterLon = MutableStateFlow(
        mapPrefs.getFloat(PREF_CENTER_LON, 2.3522f).toDouble()
    )
    val mapCenterLon: StateFlow<Double> = _mapCenterLon.asStateFlow()

    /**
     * Remember where the map is, so the next launch opens there.
     *
     * Called only for a REAL fix or a deliberate recentre — never for the Paris
     * seed, or the fallback would write itself back and become permanent.
     *
     * 0,0 is refused: Null Island is what a bad decode produces, and persisting
     * it would move the user's map into the Atlantic for good.
     */
    /**
     * __THE_MAP_DECIDED_WHAT_YOU_SAW_2026_11_07__ (Anatole N4: "an enormous
     * number of points appear, forming a line".)
     *
     * The map drops up to fifty offline points of interest around you the
     * moment a GPS fix lands, with no way to say no. Whether or not their
     * positions turn out to be right — that question is still open with him —
     * "a lot of points appear and I did not ask for them" is answered by being
     * able to turn them off. POI data follows roads, so fifty of them do draw
     * a line, which is exactly what he described.
     *
     * Defaults to ON, so nobody loses a feature they were using; the choice is
     * remembered, because a setting you must re-make every launch is not one.
     */
    private val _showPOIs = MutableStateFlow(mapPrefs.getBoolean(PREF_SHOW_POIS, true))
    val showPOIs: StateFlow<Boolean> = _showPOIs.asStateFlow()

    fun toggleShowPOIs() {
        val next = !_showPOIs.value
        _showPOIs.value = next
        mapPrefs.edit().putBoolean(PREF_SHOW_POIS, next).apply()
    }

    private fun rememberMapCenter(lat: Double, lon: Double) {
        if (lat == 0.0 && lon == 0.0) return
        if (lat.isNaN() || lon.isNaN()) return
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        mapPrefs.edit()
            .putFloat(PREF_CENTER_LAT, lat.toFloat())
            .putFloat(PREF_CENTER_LON, lon.toFloat())
            .apply()
    }

    private val _mapZoom = MutableStateFlow(12f)
    val mapZoom: StateFlow<Float> = _mapZoom.asStateFlow()

    /** True once the first real user location has been obtained and the map has been centered on it. */
    private val _hasInitialLocation = MutableStateFlow(false)
    val hasInitialLocation: StateFlow<Boolean> = _hasInitialLocation.asStateFlow()

    // ── GPS Follow mode ──
    /** When true, every new userLocation update also recenters the map and the map auto-rotates to user heading. */
    private val _followUserMode = MutableStateFlow(false)
    val followUserMode: StateFlow<Boolean> = _followUserMode.asStateFlow()

    /** Compass heading in degrees (0-360, 0 = north). null when unavailable. */
    private val _userBearing = MutableStateFlow<Float?>(null)
    val userBearing: StateFlow<Float?> = _userBearing.asStateFlow()

    fun toggleFollowUserMode() {
        _followUserMode.value = !_followUserMode.value
    }

    fun setUserBearing(bearing: Float?) {
        _userBearing.value = bearing
    }

    // ═══════════════════════════════════════════════════════════════
    // Search state
    // ═══════════════════════════════════════════════════════════════

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<MapPOI>>(emptyList())
    val searchResults: StateFlow<List<MapPOI>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _addressResults = MutableStateFlow<List<MapPOI>>(emptyList())
    val addressResults: StateFlow<List<MapPOI>> = _addressResults.asStateFlow()

    private val _streetResults = MutableStateFlow<List<StreetResult>>(emptyList())
    val streetResults: StateFlow<List<StreetResult>> = _streetResults.asStateFlow()

    /**
     * Why the last POI search came back empty — `null` while it found something,
     * or while no search has run.
     *
     * 22 of the 254 live regions ship **no POI artifact at all** (`us`, `jp`,
     * `br`, `ca`, `ru`, `id` and 16 metros). They download, report success, and
     * then answer every place query with nothing, permanently. Before this flow
     * existed that was rendered as "No results found — check your connection",
     * which is false on all three counts: there is no connection problem, trying
     * again will never help, and the region will never have that data.
     */
    private val _poiEmptyReason =
        MutableStateFlow<com.oshi.messenger.service.map.PoiCoverage.EmptyResultReason?>(null)
    val poiEmptyReason: StateFlow<com.oshi.messenger.service.map.PoiCoverage.EmptyResultReason?> =
        _poiEmptyReason.asStateFlow()

    /**
     * Display names of the downloaded regions that ship no places database.
     * Named so the message can point at the actual region rather than gesture
     * vaguely at "some regions".
     */
    private val _regionsWithoutPoiData = MutableStateFlow<List<String>>(emptyList())
    val regionsWithoutPoiData: StateFlow<List<String>> = _regionsWithoutPoiData.asStateFlow()

    /**
     * Recompute [poiEmptyReason] / [regionsWithoutPoiData] from the manifest and
     * what is on disk. Call with the merged result count of a finished search.
     */
    private fun refreshPoiCoverage(totalResults: Int) {
        _regionsWithoutPoiData.value =
            offlineMapManager.downloadedRegionsWithoutPoiData().map { it.name }
        _poiEmptyReason.value =
            if (totalResults > 0) null else offlineMapManager.classifyEmptyPoiResult()
    }

    private val _selectedPOI = MutableStateFlow<MapPOI?>(null)
    val selectedPOI: StateFlow<MapPOI?> = _selectedPOI.asStateFlow()

    /**
     * (N8) Set by a tap on an empty area of the map (not a pin), consumed
     * once by [ItineraryScreen] — which shares this same view model instance
     * with the map route (see MainActivity's "itinerary" composable) — to
     * prefill the destination. A `Pair` rather than two separate flows so a
     * reader can never see a stale lat with a fresh lon.
     */
    private val _pendingItineraryDestination = MutableStateFlow<Pair<Double, Double>?>(null)
    val pendingItineraryDestination: StateFlow<Pair<Double, Double>?> = _pendingItineraryDestination.asStateFlow()

    fun requestItineraryTo(lat: Double, lon: Double) {
        _pendingItineraryDestination.value = lat to lon
    }

    /** Consumed once the destination has been read, so re-opening Itinerary later does not replay a stale tap. */
    fun consumePendingItineraryDestination() {
        _pendingItineraryDestination.value = null
    }

    private val _selectedCategory = MutableStateFlow<POICategory?>(null)
    val selectedCategory: StateFlow<POICategory?> = _selectedCategory.asStateFlow()

    /**
     * Markers rendered on the main map after a category search (icon-tap or
     * natural-language category match like "la poste"). Cleared when the user
     * clears the search or selects a different category. Capped at 100 entries
     * sorted by haversine distance ascending — see [setCategoryMarkers].
     */
    private val _categoryMarkers = MutableStateFlow<List<MapPOI>>(emptyList())
    val categoryMarkers: StateFlow<List<MapPOI>> = _categoryMarkers.asStateFlow()

    // ═══════════════════════════════════════════════════════════════
    // Route / Navigation state
    // ═══════════════════════════════════════════════════════════════

    private val _activeRoute = MutableStateFlow<MapRoute?>(null)
    val activeRoute: StateFlow<MapRoute?> = _activeRoute.asStateFlow()

    private val _routeMode = MutableStateFlow(RouteMode.DRIVING)
    val routeMode: StateFlow<RouteMode> = _routeMode.asStateFlow()

    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating.asStateFlow()

    private val _currentManeuverIndex = MutableStateFlow(0)
    val currentManeuverIndex: StateFlow<Int> = _currentManeuverIndex.asStateFlow()

    // ═══════════════════════════════════════════════════════════════
    // Routing download state (forwarded from OfflineRoutingManager)
    // ═══════════════════════════════════════════════════════════════

    val routingDownloadProgress = offlineRoutingManager.downloadProgress
    val totalRoutingStorage = offlineRoutingManager.totalRoutingStorage

    /**
     * Soft errors from the road-graph installer, keyed by region id. `null` is
     * normal: only 155 of the server's 234 regions have a road graph at all.
     */
    val roadGraphErrors = offlineRoutingManager.roadGraphErrors

    // ═══════════════════════════════════════════════════════════════
    // Street index download state (forwarded from OfflineStreetDatabase)
    // ═══════════════════════════════════════════════════════════════

    val streetDownloadProgress = offlineStreetDatabase.downloadProgress

    // ═══════════════════════════════════════════════════════════════
    // Visible POIs on map
    // ═══════════════════════════════════════════════════════════════

    private val _visiblePOIs = MutableStateFlow<List<MapPOI>>(emptyList())
    val visiblePOIs: StateFlow<List<MapPOI>> = _visiblePOIs.asStateFlow()

    // ═══════════════════════════════════════════════════════════════
    // Region download screen
    // ═══════════════════════════════════════════════════════════════

    val regionsByContinent: StateFlow<Map<String, List<MapRegion>>> =
        regions.map { list ->
            list.groupBy { it.continent.ifBlank { "Other" } }.toSortedMap()
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    init {
        requestUserLocation()

        // Follow mode: recenter the map on every userLocation update while enabled.
        viewModelScope.launch {
            _userLocation.collect { loc ->
                if (loc != null && _followUserMode.value) {
                    _mapCenterLat.value = loc.latitude
                    _mapCenterLon.value = loc.longitude
                    rememberMapCenter(loc.latitude, loc.longitude)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Location
    // ═══════════════════════════════════════════════════════════════

    fun requestUserLocation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Location permission not granted")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fusedClient = LocationServices.getFusedLocationProviderClient(context)
                val cancellationToken = CancellationTokenSource()
                val location = fusedClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellationToken.token
                ).await()

                if (location != null) {
                    _userLocation.value = location
                    // A place opened from a chat keeps the centre (MapOpener).
                    if (_selectedPOI.value == null) {
                        _mapCenterLat.value = location.latitude
                        _mapCenterLon.value = location.longitude
                        rememberMapCenter(location.latitude, location.longitude)
                    }
                    _hasInitialLocation.value = true
                    Log.d(TAG, "User location: ${location.latitude}, ${location.longitude}")

                    // Load nearby POIs
                    loadNearbyPOIs(location.latitude, location.longitude)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get location: ${e.message}")
            }
        }
    }

    fun centerOnLocation() {
        val loc = _userLocation.value ?: return
        _mapCenterLat.value = loc.latitude
        _mapCenterLon.value = loc.longitude
        _mapZoom.value = 15f
        rememberMapCenter(loc.latitude, loc.longitude)
    }

    fun updateMapCenter(lat: Double, lon: Double) {
        _mapCenterLat.value = lat
        _mapCenterLon.value = lon
    }

    fun updateMapZoom(zoom: Float) {
        _mapZoom.value = zoom
    }

    // ═══════════════════════════════════════════════════════════════
    // Search
    // ═══════════════════════════════════════════════════════════════

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.length >= 2) {
            performSearch(query)
        } else if (query.isEmpty()) {
            _searchResults.value = emptyList()
            _poiEmptyReason.value = null
        }
    }

    fun performSearch(query: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isSearching.value = true
            try {
                // 1. Search street index (if available) — highest priority
                val streets = offlineStreetDatabase.searchAllStreets(query, limit = 20)
                _streetResults.value = streets

                val loc = _userLocation.value

                // 1a. House-number aware address search. Triggers when the
                //     query starts with a digit (or the French ordinal prefixes
                //     "bis/ter/quater" + digit). Results are exposed via the
                //     dedicated `_addressResults` flow so the UI can pin them
                //     to the top of the result list with a "📍" icon. Does NOT
                //     touch the category dispatch or the FTS branches below.
                val trimmed = query.trim()
                val leadingDigit = trimmed.firstOrNull()?.isDigit() == true
                val leadingOrdinal = Regex("^(?i)(bis|ter|quater)\\s+\\d").containsMatchIn(trimmed)
                val addressHits: List<AddressMatch> = if (leadingDigit || leadingOrdinal) {
                    offlineStreetDatabase.searchAddress(
                        query = query,
                        userLat = loc?.latitude,
                        userLon = loc?.longitude,
                        limit = 10
                    )
                } else emptyList()

                // 1b. Multilingual category-keyword detection. If the user typed a known
                //     category synonym ("la poste", "hospital", "café"…), run a nearby
                //     category search FIRST and merge those at the top — Google-Maps style.
                val matchedCategory = categoryFromQuery(query)
                val categoryHits: List<MapPOI> = if (matchedCategory != null) {
                    Log.d(TAG, "Query '$query' → category ${matchedCategory.name} (nearby search)")
                    _selectedCategory.value = matchedCategory
                    poiDatabase.searchByCategoryNearby(
                        category = matchedCategory,
                        userLat = loc?.latitude,
                        userLon = loc?.longitude,
                        radiusKm = 25.0,
                        limit = 50
                    )
                } else emptyList()

                // 2. Search POI names — pass user location so accent-folded fallback fires when
                //    FTS misses (e.g. "la poste") and so results come back sorted by proximity.
                val nameResults = poiDatabase.searchTextNearby(
                    query = query,
                    userLat = loc?.latitude,
                    userLon = loc?.longitude,
                    limit = 30
                )

                // 3. Search POI addresses
                val addrResults = poiDatabase.searchByAddress(query, limit = 20)

                // Merge: category hits first (sorted by distance), then FTS name hits, deduped.
                val seenIds = LinkedHashSet<String>()
                val merged = mutableListOf<MapPOI>()
                for (p in categoryHits) if (seenIds.add(p.id)) merged.add(p)
                for (p in nameResults) if (seenIds.add(p.id)) merged.add(p)

                // Deduplicate address results (remove those already in merged set)
                val uniqueAddrResults = addrResults.filter { it.id !in seenIds }

                // Sort merged list by haversine distance from user when possible.
                val sortedMerged = if (loc != null) {
                    merged.sortedBy {
                        val dLat = Math.toRadians(it.lat - loc.latitude)
                        val dLon = Math.toRadians(it.lon - loc.longitude)
                        val a = Math.sin(dLat / 2).let { v -> v * v } +
                                Math.cos(Math.toRadians(loc.latitude)) *
                                Math.cos(Math.toRadians(it.lat)) *
                                Math.sin(dLon / 2).let { v -> v * v }
                        2 * 6371.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
                    }
                } else merged

                // House-number address hits go first in the address flow,
                // followed by deduped POI-address (free-text) hits.
                val addressHitPOIs = addressHits.map { it.toMapPOI() }
                val addressIdSet = addressHitPOIs.map { it.id }.toHashSet()
                val finalAddrResults = addressHitPOIs +
                    uniqueAddrResults.filter { it.id !in addressIdSet }

                // Surface address hits at the top of the main result list too,
                // so a query like "12 rue de Rivoli" lands the building first.
                val resultsWithAddress = if (addressHitPOIs.isNotEmpty()) {
                    val topIds = addressHitPOIs.map { it.id }.toHashSet()
                    addressHitPOIs + sortedMerged.filter { it.id !in topIds }
                } else sortedMerged

                _searchResults.value = resultsWithAddress
                _addressResults.value = finalAddrResults

                // Drop category-search markers on the map when a category was detected.
                if (matchedCategory != null) {
                    setCategoryMarkers(categoryHits, loc?.latitude, loc?.longitude)
                } else {
                    _categoryMarkers.value = emptyList()
                }

                // 4. If all offline results empty → Nominatim fallback. Pass the detected
                //    category so we can use bounded=1 for tight local results.
                if (sortedMerged.isEmpty() && uniqueAddrResults.isEmpty() && streets.isEmpty() && addressHits.isEmpty()) {
                    val onlineResults = searchNominatim(query, category = matchedCategory)
                    _searchResults.value = onlineResults
                    if (matchedCategory != null) {
                        setCategoryMarkers(onlineResults, loc?.latitude, loc?.longitude)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Search failed: ${e.message}")
                _searchResults.value = emptyList()
                _addressResults.value = emptyList()
                _streetResults.value = emptyList()
            } finally {
                // Classify the emptiness before the spinner stops, so the screen
                // never renders "no results" without a reason attached.
                refreshPoiCoverage(
                    _searchResults.value.size + _addressResults.value.size + _streetResults.value.size
                )
                _isSearching.value = false
            }
        }
    }

    /**
     * Map the raw user query to a [POICategory] using a multilingual keyword table
     * (FR/EN/ES/IT/DE). Case-insensitive, accent-folded. Returns null when the query
     * is plain free text and the regular FTS path should run.
     *
     * Matching rule: prefix match on the folded query against each keyword. So
     * "la poste rue de rivoli" still matches the POST_OFFICE entry.
     */
    private fun categoryFromQuery(query: String): POICategory? {
        val folded = foldForCategoryMatch(query.trim())
        if (folded.isEmpty()) return null
        // Multi-word keywords (e.g. "la poste") must be tried before single-word
        // ones — CATEGORY_KEYWORDS is already declared in length-priority order.
        for ((keyword, category) in CATEGORY_KEYWORDS) {
            val k = foldForCategoryMatch(keyword)
            if (folded == k || folded.startsWith("$k ")) return category
        }
        return null
    }

    private fun foldForCategoryMatch(s: String): String {
        val nfd = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        val sb = StringBuilder(nfd.length)
        for (c in nfd) {
            if (Character.getType(c).toByte() != Character.NON_SPACING_MARK) sb.append(c)
        }
        return sb.toString().lowercase().trim()
    }

    /**
     * Set the on-map category-marker list. Caps at 100 entries (sorted by distance
     * from user when location is known) to avoid map clutter.
     */
    private fun setCategoryMarkers(pois: List<MapPOI>, userLat: Double?, userLon: Double?) {
        val sorted = if (userLat != null && userLon != null) {
            pois.sortedBy {
                val dLat = Math.toRadians(it.lat - userLat)
                val dLon = Math.toRadians(it.lon - userLon)
                val a = Math.sin(dLat / 2).let { v -> v * v } +
                        Math.cos(Math.toRadians(userLat)) *
                        Math.cos(Math.toRadians(it.lat)) *
                        Math.sin(dLon / 2).let { v -> v * v }
                2 * 6371.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            }
        } else pois
        _categoryMarkers.value = sorted.take(100)
        Log.d(TAG, "categoryMarkers: ${_categoryMarkers.value.size} pins")
    }

    /**
     * Online geocoding fallback using OpenStreetMap Nominatim API.
     * Called when the offline POI database returns no results.
     *
     * When [category] is non-null, the search is constrained to the user's
     * viewbox (`bounded=1`) so we don't drown in far-away exact-name matches —
     * essential for category words like "la poste" that share a name with
     * thousands of unrelated places.
     */
    private fun searchNominatim(query: String, limit: Int = 20, category: POICategory? = null): List<MapPOI> {
        if (isOfflineOnly()) {
            Log.d(TAG, "Offline-only: skipping Nominatim")
            return emptyList()
        }
        return try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val userLat = _userLocation.value?.latitude
            val userLon = _userLocation.value?.longitude

            var url = "https://nominatim.openstreetmap.org/search" +
                    "?q=$encodedQuery&format=json&addressdetails=1&limit=$limit"

            // Bias results towards user's current location if available.
            // Use bounded=1 when we know we're after a category — keeps the
            // post-office around the corner ahead of "La Poste" tourist spots
            // 800 km away.
            if (userLat != null && userLon != null) {
                val bounded = if (category != null) 1 else 0
                url += "&viewbox=${userLon - 0.5},${userLat + 0.5},${userLon + 0.5},${userLat - 0.5}" +
                        "&bounded=$bounded"
            }

            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            // A bare app token is not a contactable UA; reuse the one the tile stack
            // was fixed to send (`OshiTileSource.kt:60`).
            connection.setRequestProperty(
                "User-Agent",
                com.oshi.messenger.service.map.OshiTileSource.USER_AGENT
            )
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val jsonArray = org.json.JSONArray(response)
            val results = mutableListOf<OnlineHit>()

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val displayName = item.optString("display_name", "")
                val name = item.optString("name", "").ifBlank {
                    // Use first part of display_name if name is empty
                    displayName.split(",").firstOrNull()?.trim() ?: "Unknown"
                }
                val lat = item.optDouble("lat", 0.0)
                val lon = item.optDouble("lon", 0.0)
                val type = item.optString("type", "")
                val osmCategory = item.optString("category", "")
                val category = nominatimCategoryToPOI(osmCategory, type)

                // Build address from address details
                val address = if (item.has("address")) {
                    val addr = item.getJSONObject("address")
                    buildList {
                        addr.optString("road", "").takeIf { it.isNotBlank() }?.let { add(it) }
                        addr.optString("house_number", "").takeIf { it.isNotBlank() }?.let { add(it) }
                        addr.optString("city", "").ifBlank {
                            addr.optString("town", "").ifBlank {
                                addr.optString("village", "")
                            }
                        }.takeIf { it.isNotBlank() }?.let { add(it) }
                        addr.optString("country", "").takeIf { it.isNotBlank() }?.let { add(it) }
                    }.joinToString(", ")
                } else {
                    displayName.split(",").drop(1).joinToString(",").trim()
                }

                results.add(
                    OnlineHit(
                        poi = MapPOI(
                            id = "nominatim_${item.optString("osm_id", "$i")}",
                            name = name,
                            category = category,
                            lat = lat,
                            lon = lon,
                            address = address,
                            regionId = "online"
                        ),
                        // Nominatim's own relevance signal. Absent on some rows,
                        // hence the explicit default.
                        importance = item.optDouble("importance", 0.0)
                    )
                )
            }

            // 🔧 P1 FIX (device walkthrough #2, 2026-08-13): searching "Paris"
            // returned "Paris, France" TWICE plus "Bali, Indonesia", all badged
            // "Other". The raw list was assigned straight to `_searchResults` in
            // Nominatim's own order with no dedupe and no ranking. See
            // [refineOnlineHits].
            val refined = refineOnlineHits(query, results, userLat, userLon)
            Log.d(
                TAG,
                "Nominatim returned ${results.size} raw / ${refined.size} refined for '$query'"
            )
            refined
        } catch (e: Exception) {
            Log.w(TAG, "Nominatim search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Online OSM POI fallback via the Overpass API — the proper way to query
     * OpenStreetMap by tag. Replaces the old Nominatim-fallback for category
     * tile search (Bug B fix, 2026-04-28). Nominatim is a geocoder; Overpass
     * is the OSM tag index, so chain stores (Carrefour, Migros, Lidl…) come
     * back correctly.
     *
     * Returns up to 200 POIs within [radiusKm] of (lat, lon). Empty list on
     * error / rate limit / unmapped category — caller should still merge with
     * the offline `poiDatabase` results.
     */
    private fun searchOverpassNearby(
        category: POICategory,
        lat: Double,
        lon: Double,
        radiusKm: Double = 10.0
    ): List<MapPOI> {
        val radiusMeters = (radiusKm * 1000).toInt()
        // Per-category Overpass tag query. Each entry expands `R`, `LAT`, `LON`
        // at format-time and is wrapped in a union `( ... );` block. Covers
        // every POICategory (audit pass, Bug B fix). Categories without a
        // useful OSM tag (OTHER) return empty so the helper is a no-op.
        val tagQuery: String? = when (category) {
            POICategory.SUPERMARKET ->
                """node[shop~"^(supermarket|convenience|greengrocer|butcher|bakery|deli|dairy|cheese|farm|seafood|general)$"](around:R,LAT,LON);
                   node[amenity=marketplace](around:R,LAT,LON);"""
            POICategory.CAFE ->
                """node[amenity~"^(cafe|coffee_shop)$"](around:R,LAT,LON);"""
            POICategory.RESTAURANT ->
                """node[amenity~"^(restaurant|fast_food|food_court|biergarten|ice_cream)$"](around:R,LAT,LON);"""
            POICategory.BAR ->
                """node[amenity~"^(bar|pub|nightclub)$"](around:R,LAT,LON);"""
            POICategory.PHARMACY ->
                """node[amenity=pharmacy](around:R,LAT,LON);"""
            POICategory.HOSPITAL ->
                """node[amenity~"^(hospital|clinic|doctors)$"](around:R,LAT,LON);"""
            POICategory.HOTEL ->
                """node[tourism~"^(hotel|guest_house|hostel|motel|alpine_hut)$"](around:R,LAT,LON);"""
            POICategory.GAS_STATION ->
                """node[amenity=fuel](around:R,LAT,LON);"""
            POICategory.ATM ->
                """node[amenity=atm](around:R,LAT,LON);"""
            POICategory.BANK ->
                """node[amenity~"^(bank|credit_union|bureau_de_change)$"](around:R,LAT,LON);"""
            POICategory.PARKING ->
                """node[amenity~"^(parking|parking_entrance)$"](around:R,LAT,LON);"""
            POICategory.EV_CHARGING ->
                """node[amenity=charging_station](around:R,LAT,LON);"""
            POICategory.BUS_STATION ->
                """node[highway=bus_stop](around:R,LAT,LON);
                   node[amenity=bus_station](around:R,LAT,LON);"""
            POICategory.TRAIN_STATION ->
                """node[railway~"^(station|halt|tram_stop|subway_entrance)$"](around:R,LAT,LON);"""
            POICategory.AIRPORT ->
                """node[aeroway~"^(aerodrome|airport)$"](around:R,LAT,LON);"""
            POICategory.POLICE ->
                """node[amenity=police](around:R,LAT,LON);"""
            POICategory.POST_OFFICE ->
                """node[amenity=post_office](around:R,LAT,LON);"""
            POICategory.MUSEUM ->
                """node[tourism~"^(museum|gallery)$"](around:R,LAT,LON);"""
            POICategory.PARK ->
                """node[leisure~"^(park|garden|playground)$"](around:R,LAT,LON);"""
            POICategory.BEACH ->
                """node[natural=beach](around:R,LAT,LON);
                   node[leisure=beach_resort](around:R,LAT,LON);"""
            POICategory.PLACE_OF_WORSHIP ->
                """node[amenity=place_of_worship](around:R,LAT,LON);"""
            POICategory.SCHOOL ->
                """node[amenity~"^(school|kindergarten|college|university)$"](around:R,LAT,LON);"""
            POICategory.EMBASSY ->
                """node[amenity~"^(embassy|consulate)$"](around:R,LAT,LON);
                   node[diplomatic](around:R,LAT,LON);"""
            POICategory.UNIVERSITY ->
                """node[amenity~"^(university|college)$"](around:R,LAT,LON);"""
            // Categories the live poi.db carries that had no enum case before
            // 2026-08-13 (gym 3 242, toilet 2 710, cinema 948 rows in fr-idf).
            POICategory.GYM ->
                """node[leisure~"^(fitness_centre|sports_centre)$"](around:R,LAT,LON);"""
            POICategory.TOILET ->
                """node[amenity=toilets](around:R,LAT,LON);"""
            POICategory.CINEMA ->
                """node[amenity=cinema](around:R,LAT,LON);"""
            POICategory.OTHER -> null
        }
        if (tagQuery == null) return emptyList()

        val expanded = tagQuery
            .replace("R", radiusMeters.toString())
            .replace("LAT", lat.toString())
            .replace("LON", lon.toString())
            // Strip the verbatim newlines/indent from triple-quoted strings.
            .replace(Regex("\\s+"), " ")
            .trim()

        val ql = "[out:json][timeout:25];($expanded);out body 200;"

        if (isOfflineOnly()) {
            Log.d(TAG, "Offline-only: skipping Overpass")
            return emptyList()
        }

        return try {
            val url = java.net.URL("https://overpass-api.de/api/interpreter")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty(
                "User-Agent",
                com.oshi.messenger.service.map.OshiTileSource.USER_AGENT
            )
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = "data=" + java.net.URLEncoder.encode(ql, "UTF-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val root = JSONObject(response)
            val elements = root.optJSONArray("elements") ?: return emptyList()
            val results = mutableListOf<MapPOI>()
            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i) ?: continue
                val elLat = el.optDouble("lat", Double.NaN)
                val elLon = el.optDouble("lon", Double.NaN)
                if (elLat.isNaN() || elLon.isNaN()) continue
                val tags = el.optJSONObject("tags") ?: JSONObject()
                val name = tags.optString("name", "").ifBlank {
                    // Sub-tag lookups for unnamed nodes — surface a friendly
                    // fallback ("Pharmacy", "ATM"…) so the pin isn't blank.
                    tags.optString("brand", "").ifBlank {
                        tags.optString("operator", "").ifBlank {
                            category.displayName
                        }
                    }
                }
                val osmId = el.optLong("id", i.toLong())
                results.add(
                    MapPOI(
                        id = "overpass_$osmId",
                        name = name,
                        category = category,
                        lat = elLat,
                        lon = elLon,
                        address = listOfNotNull(
                            tags.optString("addr:housenumber", "").ifBlank { null },
                            tags.optString("addr:street", "").ifBlank { null },
                            tags.optString("addr:city", "").ifBlank { null }
                        ).joinToString(" "),
                        regionId = "overpass"
                    )
                )
            }
            Log.d(TAG, "Overpass(${category.name}) returned ${results.size} hits within ${radiusKm}km")
            results
        } catch (e: Exception) {
            Log.w(TAG, "Overpass query failed (${category.name}): ${e.message}")
            emptyList()
        }
    }

    /** Map Nominatim OSM category/type to our POICategory enum.
     *
     * Mirrors iOS `categorizeMapItem` plus the explicit OSM-tag table the
     * iOS keyword fallback uses. We accept the broadest set of synonyms so
     * a single category tap drops a maximum number of pins (parity with the
     * iOS map screen, which previously surfaced more POIs than Android).
     */
    private fun nominatimCategoryToPOI(osmCategory: String, osmType: String): POICategory {
        return when {
            // Food & drink
            osmType == "restaurant" || osmType == "fast_food" ||
            osmType == "food_court" || osmType == "biergarten" ||
            osmType == "ice_cream" -> POICategory.RESTAURANT
            osmType == "cafe" -> POICategory.CAFE
            osmType == "bar" || osmType == "pub" || osmType == "nightclub" -> POICategory.BAR

            // Lodging
            osmType == "hotel" || osmType == "hostel" || osmType == "motel" ||
            osmType == "guest_house" || osmType == "apartment" ||
            osmType == "chalet" || osmType == "alpine_hut" -> POICategory.HOTEL

            // Health
            osmType == "hospital" || osmType == "clinic" ||
            osmType == "doctors" || osmType == "dentist" ||
            osmType == "veterinary" -> POICategory.HOSPITAL
            osmType == "pharmacy" || osmType == "chemist" -> POICategory.PHARMACY

            // Fuel & EV
            osmType == "fuel" -> POICategory.GAS_STATION
            osmType == "charging_station" -> POICategory.EV_CHARGING

            // Money
            osmType == "atm" -> POICategory.ATM
            osmType == "bank" || osmType == "credit_union" ||
            osmType == "bureau_de_change" -> POICategory.BANK

            // Grocery / marketplace family — covers OSM `amenity=marketplace`,
            // shop=supermarket / convenience / general / greengrocer / butcher /
            // bakery / deli / dairy / cheese / pastry / wine / alcohol / beverages /
            // farm. iOS surfaces all of these when the user searches "market"
            // or taps the supermarket chip.
            osmType == "supermarket" || osmType == "convenience" ||
            osmType == "marketplace" || osmType == "greengrocer" ||
            osmType == "butcher" || osmType == "bakery" ||
            osmType == "general" || osmType == "deli" ||
            osmType == "dairy" || osmType == "cheese" ||
            osmType == "pastry" || osmType == "wine" ||
            osmType == "alcohol" || osmType == "beverages" ||
            osmType == "farm" || osmType == "seafood" ||
            osmType == "confectionery" -> POICategory.SUPERMARKET

            // Parking — OSM uses both `amenity=parking` and a few sub-tags.
            osmType == "parking" || osmType == "parking_entrance" ||
            osmType == "parking_space" || osmType == "bicycle_parking" ||
            osmType == "motorcycle_parking" -> POICategory.PARKING

            // Public services
            osmType == "police" -> POICategory.POLICE
            osmType == "post_office" || osmType == "post_box" -> POICategory.POST_OFFICE
            osmType == "embassy" || osmType == "consulate" -> POICategory.EMBASSY

            // Education
            osmType == "school" || osmType == "university" ||
            osmType == "college" || osmType == "kindergarten" -> POICategory.SCHOOL

            // Culture
            osmType == "museum" || osmType == "gallery" ||
            osmType == "arts_centre" -> POICategory.MUSEUM

            // Outdoors
            osmType == "park" || osmType == "garden" ||
            osmType == "playground" || osmType == "nature_reserve" ||
            osmType == "dog_park" -> POICategory.PARK
            osmType == "beach" || osmType == "beach_resort" -> POICategory.BEACH

            // Travel
            osmType == "aerodrome" || osmType == "airport" ||
            osmType == "terminal" -> POICategory.AIRPORT
            (osmType == "station" && osmCategory == "railway") ||
            osmType == "halt" || osmType == "subway_entrance" ||
            osmType == "tram_stop" -> POICategory.TRAIN_STATION
            osmType == "bus_stop" || osmType == "bus_station" ||
            osmType == "platform" -> POICategory.BUS_STATION

            // Place of worship
            osmCategory == "place_of_worship" || osmType == "place_of_worship" ||
            osmType == "church" || osmType == "mosque" ||
            osmType == "synagogue" || osmType == "temple" ||
            osmType == "shrine" || osmType == "monastery" -> POICategory.PLACE_OF_WORSHIP

            else -> POICategory.OTHER
        }
    }

    fun searchByCategory(category: POICategory) {
        _selectedCategory.value = category
        viewModelScope.launch(Dispatchers.IO) {
            _isSearching.value = true
            try {
                // Bias the search around the user's current location when we have it,
                // so "Café" returns the cafés you can actually walk to, not random hits
                // from the other end of a downloaded region.
                val loc = _userLocation.value
                val results = poiDatabase.searchByCategoryNearby(
                    category = category,
                    userLat = loc?.latitude,
                    userLon = loc?.longitude,
                    radiusKm = 25.0,
                    limit = 50
                )

                // Bug B fix (2026-04-28): Nominatim is a geocoder, not a POI tag
                // index — searching it for "Supermarket" returns near-zero hits.
                // Use Overpass API (OSM's proper POI tag index) for the online
                // category fallback. Falls back to Nominatim only if Overpass
                // fails (network blocked, rate limited).
                val finalResults = if (results.isEmpty()) {
                    val overpass = if (loc != null) {
                        searchOverpassNearby(category, loc.latitude, loc.longitude, radiusKm = 10.0)
                    } else emptyList()
                    if (overpass.isNotEmpty()) overpass
                    else searchNominatim(category.displayName, category = category)
                } else {
                    results
                }
                _searchResults.value = finalResults

                // Drop pins on the map for these results — Bug 2 fix.
                setCategoryMarkers(finalResults, loc?.latitude, loc?.longitude)
            } catch (e: Exception) {
                Log.e(TAG, "Category search failed: ${e.message}")
            } finally {
                // Same honesty rule as the free-text path: an empty category
                // result must arrive with a reason, not just a shrug.
                refreshPoiCoverage(_searchResults.value.size)
                _isSearching.value = false
            }
        }
    }

    /**
     * Multi-select category toggle. Matches iOS behavior — tapping a category
     * icon ADDS that category's nearby POIs to the on-map marker layer. Tapping
     * the same category again REMOVES its markers. The categories already
     * displayed are preserved.
     */
    private val _activeCategoryFilters = MutableStateFlow<Set<POICategory>>(emptySet())
    val activeCategoryFilters: StateFlow<Set<POICategory>> = _activeCategoryFilters.asStateFlow()

    fun toggleCategoryMarkers(category: POICategory) {
        val active = _activeCategoryFilters.value
        if (category in active) {
            // Remove this category's markers from the map
            _activeCategoryFilters.value = active - category
            _categoryMarkers.value = _categoryMarkers.value.filter { it.category != category }
            if (_activeCategoryFilters.value.isEmpty()) {
                _selectedCategory.value = null
            }
            return
        }
        // Add this category's markers
        _activeCategoryFilters.value = active + category
        _selectedCategory.value = category
        viewModelScope.launch(Dispatchers.IO) {
            _isSearching.value = true
            try {
                val loc = _userLocation.value
                // Match iOS: pull a large local set (50 km / 300 cap) AND always merge with the
                // online provider so users see pins in regions whose offline data is sparse or
                // hasn't been downloaded. Previously we only fell back to Nominatim when local
                // returned zero — leading to "lots of POIs missing" in well-covered areas.
                val localResults = poiDatabase.searchByCategoryNearby(
                    category = category,
                    userLat = loc?.latitude,
                    userLon = loc?.longitude,
                    radiusKm = 50.0,
                    limit = 300
                )
                // Bug B fix (2026-04-28): Nominatim is a geocoder, not a POI tag
                // search engine — for "Supermarket" it returns ~0 results because
                // chain stores like Carrefour / Migros / Lidl don't contain the
                // word "supermarket" in their name. Switched to Overpass API
                // which queries OSM tags (shop=supermarket, amenity=marketplace,
                // etc.) and returns the full set of relevant venues.
                val onlineResults = try {
                    if (loc != null) {
                        searchOverpassNearby(category, loc.latitude, loc.longitude, radiusKm = 10.0)
                    } else emptyList()
                } catch (e: Exception) {
                    Log.w(TAG, "Overpass merge failed: ${e.message}"); emptyList()
                }
                // Merge with local (prefer local for the same id since local is richer).
                val seenIds = localResults.map { it.id }.toHashSet()
                val finalResults = localResults + onlineResults.filter { seenIds.add(it.id) }
                _searchResults.value = finalResults

                // ADD to existing markers (don't wipe). Dedupe by id and cap at 500 (was 200 — too tight).
                val existing = _categoryMarkers.value
                val seen = existing.map { it.id }.toHashSet()
                val combined = existing + finalResults.filter { seen.add(it.id) }
                val sorted = if (loc != null) {
                    combined.sortedBy {
                        val dLat = Math.toRadians(it.lat - loc.latitude)
                        val dLon = Math.toRadians(it.lon - loc.longitude)
                        val a = Math.sin(dLat / 2).let { v -> v * v } +
                                Math.cos(Math.toRadians(loc.latitude)) *
                                Math.cos(Math.toRadians(it.lat)) *
                                Math.sin(dLon / 2).let { v -> v * v }
                        2 * 6371.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
                    }
                } else combined
                _categoryMarkers.value = sorted.take(500)
                Log.d(TAG, "toggleCategoryMarkers(+${category.name}): ${_categoryMarkers.value.size} pins, active=${_activeCategoryFilters.value.size}")
            } catch (e: Exception) {
                Log.e(TAG, "Toggle category failed: ${e.message}")
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _addressResults.value = emptyList()
        _streetResults.value = emptyList()
        _selectedCategory.value = null
        _categoryMarkers.value = emptyList()
        _activeCategoryFilters.value = emptySet()
        // No search is running, so there is no emptiness to explain.
        _poiEmptyReason.value = null
    }

    fun selectPOI(poi: MapPOI?) {
        _selectedPOI.value = poi
        if (poi != null) {
            _mapCenterLat.value = poi.lat
            _mapCenterLon.value = poi.lon
            _mapZoom.value = 16f
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // A location shared in a conversation, viewed on the map
    // ═══════════════════════════════════════════════════════════════
    //
    // Tapping a location bubble opens the Map TAB focused on the shared point,
    // which raises the POI sheet carrying "Y aller" / Navigate. That focus is a
    // property of one VISIT, but it was being stored in `_selectedPOI` — which
    // belongs to a ViewModel scoped to the Map back-stack entry, an entry the
    // bottom bar deliberately saves and restores. Nothing cleared it, so the
    // Map tab stayed stuck on that one shared location with "Y aller" showing,
    // forever. See `SharedLocationFocus` for the full mechanism.
    //
    // The pair below makes the episode explicit: what we put there, and whether
    // a route belongs to it. Clearing is then surgical — a POI the USER picked,
    // or a route the USER planned, is never touched.

    /** The POI this ViewModel dropped for a shared location, if any. */
    private var sharedFocusPoi: MapPOI? = null

    /** Whether [_activeRoute] was computed toward [sharedFocusPoi]. */
    private var routeTargetsSharedFocus = false

    /** Open the map on a location shared in a conversation. */
    fun focusSharedLocation(poi: MapPOI) {
        sharedFocusPoi = poi
        routeTargetsSharedFocus = false
        selectPOI(poi)
    }

    /**
     * End the shared-location episode: drop the pin we dropped, and the route we
     * planned toward it.
     *
     * A no-op unless this ViewModel actually opened a shared location, and even
     * then it only clears state that is still OURS — if the user has since
     * selected a different POI, or routed somewhere else, that survives.
     */
    fun clearSharedLocationFocus() {
        val focused = sharedFocusPoi ?: return
        sharedFocusPoi = null

        if (_selectedPOI.value?.id == focused.id) {
            _selectedPOI.value = null
        }
        if (routeTargetsSharedFocus) {
            _activeRoute.value = null
            _isNavigating.value = false
            _currentManeuverIndex.value = 0
        }
        routeTargetsSharedFocus = false
        _routeError.value = null
    }

    // ═══════════════════════════════════════════════════════════════
    // POIs on map
    // ═══════════════════════════════════════════════════════════════

    fun loadNearbyPOIs(lat: Double, lon: Double, radiusKm: Double = 5.0) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pois = poiDatabase.searchNearby(lat, lon, radiusKm)
                _visiblePOIs.value = pois
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load nearby POIs: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Route / Navigation
    // ═══════════════════════════════════════════════════════════════

    fun setRouteMode(mode: RouteMode) {
        _routeMode.value = mode
    }

    fun calculateRoute(destLat: Double, destLon: Double) {
        val origin = _userLocation.value ?: return
        calculateRouteFromTo(origin.latitude, origin.longitude, destLat, destLon)
    }

    fun calculateRouteFromTo(originLat: Double, originLon: Double, destLat: Double, destLon: Double) {
        // Remember whether this route belongs to the shared-location episode, so
        // leaving the map can retire it without touching a route the user
        // planned for themselves.
        routeTargetsSharedFocus = sharedFocusPoi?.let {
            com.oshi.messenger.service.map.SharedLocationFocus.sameSpot(it.lat, it.lon, destLat, destLon)
        } ?: false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Priority 1: Offline routing (if routing data downloaded for this region)
                val offlineRoute = offlineRoutingManager.calculateRoute(
                    originLat, originLon, destLat, destLon, _routeMode.value
                )
                if (offlineRoute != null) {
                    Log.d(TAG, "Using offline route")
                    _routeError.value = null
                    _activeRoute.value = offlineRoute
                    return@launch
                }

                // Honor the user's "Use offline routing only" setting (iOS parity).
                // When on, we never reach the online OSRM call below — the route
                // either came from offline above or stays unset (showing a
                // straight-line fallback that's clearly local).
                if (!isOfflineOnly()) {
                    // Priority 2: Online OSRM
                    val osrmProfile = when (_routeMode.value) {
                        RouteMode.DRIVING -> "car"
                        RouteMode.WALKING -> "foot"
                        RouteMode.CYCLING -> "bike"
                    }

                    val osrmRoute = fetchOSRMRoute(originLat, originLon, destLat, destLon, osrmProfile)
                    if (osrmRoute != null) {
                        _routeError.value = null
                        _activeRoute.value = osrmRoute
                        return@launch
                    }
                } else {
                    Log.d(TAG, "Offline-only routing enabled; skipping OSRM")
                }

                // 🔧 P1 FIX (device walkthrough #2, 2026-08-13).
                //
                // What used to be here was a "Priority 3: straight-line fallback"
                // that FABRICATED a route: two points, a great-circle
                // `Location.distanceBetween`, a flat 50 km/h, and a three-step
                // itinerary ("Start navigation" → "Continue to destination
                // (8990.9 km)" → "You have arrived"). It was emitted as the same
                // `MapRoute` type as a real OSRM answer, so the UI drew it with a
                // live "Start Navigation" button. On device that produced an
                // 8 991 km "drive" with a 179 h 40 min ETA and three instructions.
                // That is not a degraded route; it is a made-up one.
                //
                // The app already knows perfectly well whether it can route: the
                // offline path above consults `findRegionForLocation` +
                // `OfflineRouter.hasGraph()` (OfflineRoutingManager.kt:200-215) and
                // returns null when there is no graph, and OSRM returning null means
                // the online engine could not route it either. Reaching this point
                // means BOTH engines declined — so we decline too, and say why,
                // rather than inventing a line.
                //
                // (iOS is only partly better here: `MapRoutingEngine.swift:69,274-291`
                // does still draw a straight line, but it labels the maneuver with a
                // real `hasOfflineRouting(for:)` check and emits two steps, never a
                // fake "Continue to destination" middle instruction. We take the
                // honest end of that: no route at all, with a stated reason.)
                Log.w(
                    TAG,
                    "No route: offline graph unavailable and OSRM declined " +
                        "($originLat,$originLon → $destLat,$destLon, mode=${_routeMode.value})"
                )
                _activeRoute.value = null
                _routeError.value = context.getString(R.string.offline_map_routing_unavailable)
            } catch (e: Exception) {
                Log.e(TAG, "Route calculation failed: ${e.message}")
                _activeRoute.value = null
                _routeError.value = context.getString(R.string.offline_map_routing_unavailable)
            }
        }
    }

    /**
     * Why the last routing attempt produced nothing, or null when it succeeded.
     *
     * Added with the fix above: refusing to route is only honest if the refusal is
     * visible. Cleared on the next successful calculation and by
     * [acknowledgeRouteError].
     */
    private val _routeError = MutableStateFlow<String?>(null)
    val routeError: StateFlow<String?> = _routeError.asStateFlow()

    fun acknowledgeRouteError() { _routeError.value = null }

    private fun fetchOSRMRoute(
        originLat: Double, originLon: Double,
        destLat: Double, destLon: Double,
        profile: String
    ): MapRoute? {
        return try {
            val url = "https://router.project-osrm.org/route/v1/$profile/" +
                    "$originLon,$originLat;$destLon,$destLat" +
                    "?overview=full&geometries=geojson&steps=true"

            // __ROUTE_REQUEST_HAD_NO_TIMEOUT_2026_11_02__ Reported as "routes do
            // not work on Android, and they are slow".
            //
            // This was `URL(url).readText()`. That opens a `URLConnection` with
            // Java's DEFAULT timeouts, and the default is **0, meaning wait for
            // ever** — on both connect and read. The server itself is fine
            // (measured today: three calls, 0.23-0.33 s), so the failure is not
            // where it looks: on a phone that drops to a weak signal, loses the
            // network mid-request, or sits behind a captive portal, this call
            // never returns AND never throws. Nothing cancels it, so the map
            // simply shows no route, for ever, with no error — which reads
            // exactly as "routing does not work, and it is slow".
            //
            // Bounded instead. Connect and read are separate deadlines because
            // they fail differently: a dead host hangs on connect, an
            // overloaded one accepts and then stalls. Both are well under the
            // patience of someone waiting for a route.
            //
            // `router.project-osrm.org` is OSRM's PUBLIC DEMO server, which asks
            // not to be used for production and rate-limits accordingly. It is
            // only ever reached when the offline road graph for this area is
            // missing, so the real fix for a user is to download the region —
            // but a demo server saying 429 must still fail fast and visibly,
            // not hang.
            val conn = (URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 12_000
                requestMethod = "GET"
                // The demo server rejects requests with no agent string.
                setRequestProperty("User-Agent", "OSHI-Messenger/Android")
            }
            val response = try {
                if (conn.responseCode !in 200..299) {
                    Log.w(TAG, "OSRM declined: HTTP ${conn.responseCode}")
                    return null
                }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
            val json = JSONObject(response)

            if (json.getString("code") != "Ok") return null

            val routeJson = json.getJSONArray("routes").getJSONObject(0)
            val distance = routeJson.getDouble("distance")
            val duration = routeJson.getDouble("duration")

            // Parse geometry points
            val geometry = routeJson.getJSONObject("geometry")
            val coordinates = geometry.getJSONArray("coordinates")
            val points = mutableListOf<RoutePoint>()
            for (i in 0 until coordinates.length()) {
                val coord = coordinates.getJSONArray(i)
                points.add(RoutePoint(lat = coord.getDouble(1), lon = coord.getDouble(0)))
            }

            // Parse maneuvers from steps
            val legs = routeJson.getJSONArray("legs")
            val maneuvers = mutableListOf<RouteManeuver>()
            for (legIdx in 0 until legs.length()) {
                val steps = legs.getJSONObject(legIdx).getJSONArray("steps")
                for (stepIdx in 0 until steps.length()) {
                    val step = steps.getJSONObject(stepIdx)
                    val maneuver = step.getJSONObject("maneuver")
                    val instruction = buildStepInstruction(step, maneuver)
                    val stepDistance = step.getDouble("distance")
                    val type = maneuver.getString("type")
                    val location = maneuver.getJSONArray("location")
                    maneuvers.add(
                        RouteManeuver(
                            instruction = instruction,
                            distance = stepDistance,
                            type = type + (if (maneuver.has("modifier")) "-${maneuver.getString("modifier")}" else ""),
                            lat = location.getDouble(1),
                            lon = location.getDouble(0)
                        )
                    )
                }
            }

            MapRoute(
                points = points,
                distance = distance,
                duration = duration,
                mode = _routeMode.value,
                maneuvers = maneuvers
            )
        } catch (e: Exception) {
            Log.w(TAG, "OSRM fetch failed: ${e.message}")
            null
        }
    }

    private fun buildStepInstruction(step: JSONObject, maneuver: JSONObject): String {
        val type = maneuver.getString("type")
        val modifier = if (maneuver.has("modifier")) maneuver.getString("modifier") else ""
        val name = if (step.has("name") && step.getString("name").isNotBlank()) step.getString("name") else ""

        return when (type) {
            "depart" -> if (name.isNotBlank()) context.getString(R.string.nav_head_on, name) else context.getString(R.string.nav_start)
            "arrive" -> if (name.isNotBlank()) context.getString(R.string.nav_arrived_at, name) else context.getString(R.string.nav_arrived)
            "turn" -> if (name.isNotBlank()) context.getString(R.string.nav_turn_onto, modifier, name) else context.getString(R.string.nav_turn, modifier)
            "new name" -> if (name.isNotBlank()) context.getString(R.string.nav_continue_on, name) else context.getString(R.string.nav_continue_straight)
            "merge" -> if (name.isNotBlank()) context.getString(R.string.nav_merge_onto, modifier, name) else context.getString(R.string.nav_merge, modifier)
            "fork" -> if (name.isNotBlank()) context.getString(R.string.nav_fork_onto, modifier, name) else context.getString(R.string.nav_fork, modifier)
            "roundabout" -> {
                val hasExit = maneuver.has("exit")
                val exitNum = if (hasExit) maneuver.getInt("exit") else 0
                when {
                    hasExit && name.isNotBlank() -> context.getString(R.string.nav_roundabout_exit_then, exitNum, name)
                    hasExit -> context.getString(R.string.nav_roundabout_exit, exitNum)
                    name.isNotBlank() -> context.getString(R.string.nav_roundabout_then, name)
                    else -> context.getString(R.string.nav_roundabout)
                }
            }
            "rotary" -> if (name.isNotBlank()) context.getString(R.string.nav_rotary_exit, name) else context.getString(R.string.nav_rotary)
            "end of road" -> if (name.isNotBlank()) context.getString(R.string.nav_turn_onto, modifier, name) else context.getString(R.string.nav_turn, modifier)
            "continue" -> if (name.isNotBlank()) context.getString(R.string.nav_continue_on, name) else context.getString(R.string.nav_continue_straight)
            else -> if (name.isNotBlank()) "$type on $name" else type.replaceFirstChar { it.uppercase() }
        }
    }

    fun startNavigation() {
        if (_activeRoute.value != null) {
            _isNavigating.value = true
            _currentManeuverIndex.value = 0
        }
    }

    fun stopNavigation() {
        _isNavigating.value = false
        _activeRoute.value = null
        _currentManeuverIndex.value = 0
    }

    fun nextManeuver() {
        val route = _activeRoute.value ?: return
        if (_currentManeuverIndex.value < route.maneuvers.size - 1) {
            _currentManeuverIndex.value += 1
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Region download management
    // ═══════════════════════════════════════════════════════════════

    fun downloadRegion(region: MapRegion) {
        offlineMapManager.downloadRegion(region)
    }

    fun cancelRegionDownload(regionId: String) {
        offlineMapManager.cancelDownload(regionId)
    }

    fun deleteRegion(regionId: String) {
        offlineMapManager.deleteRegion(regionId)
    }

    fun getRegionState(regionId: String): RegionDownloadState {
        return offlineMapManager.getRegionState(regionId)
    }

    fun refreshManifest() {
        viewModelScope.launch {
            offlineMapManager.refreshManifest()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Routing download management
    // ═══════════════════════════════════════════════════════════════

    fun downloadRouting(regionId: String) {
        offlineRoutingManager.downloadRouting(regionId)
    }

    fun deleteRouting(regionId: String) {
        offlineRoutingManager.deleteRouting(regionId)
    }

    fun getRoutingState(regionId: String): RoutingDownloadState {
        return offlineRoutingManager.getRoutingState(regionId)
    }

    /**
     * Whether the SERVER has a road graph to offer for this region.
     *
     * Previously answered by looking the region up in a hardcoded fallback
     * manifest of 27 country ids whose `.bin` URLs all 404 — so this was `true`
     * for a handful of countries and `false` for every real region id, entirely
     * independently of what the server actually has. It now reads
     * `files.roadgraph` from the v2 manifest.
     */
    fun hasRoutingAvailable(regionId: String): Boolean {
        return offlineRoutingManager.hasRoadGraphArtifact(regionId)
    }

    /** Human size of the road-graph artifact, or "" when there is none. */
    fun getRoutingSizeFormatted(regionId: String): String {
        return offlineRoutingManager.getRoadGraphFile(regionId)?.sizeFormatted ?: ""
    }

    // ═══════════════════════════════════════════════════════════════
    // Street index management
    // ═══════════════════════════════════════════════════════════════

    fun downloadStreetIndex(regionId: String) {
        offlineStreetDatabase.downloadIndex(regionId)
    }

    fun deleteStreetIndex(regionId: String) {
        offlineStreetDatabase.deleteIndex(regionId)
    }

    fun getStreetState(regionId: String): StreetDownloadState {
        return offlineStreetDatabase.getStreetState(regionId)
    }

    fun hasStreetIndex(regionId: String): Boolean {
        return offlineStreetDatabase.isAvailable(regionId)
    }

    fun getStreetIndexSizeFormatted(regionId: String): String {
        return offlineStreetDatabase.getIndexSizeFormatted(regionId)
    }

    // ═══════════════════════════════════════════════════════════════
    // Contact live location tracking
    // ═══════════════════════════════════════════════════════════════

    fun getLatestLiveCoordinate(sessionId: String): Pair<Double, Double>? {
        return com.oshi.messenger.network.location.LocationSharingManager.getTrackedLiveCoordinate(sessionId)
    }

    // ═══════════════════════════════════════════════════════════════
    // Shared contact location data (set before navigating to map)
    // ═══════════════════════════════════════════════════════════════

    private val _sharedContactName = MutableStateFlow<String?>(null)
    val sharedContactName: StateFlow<String?> = _sharedContactName.asStateFlow()

    private val _sharedContactPicture = MutableStateFlow<ByteArray?>(null)
    val sharedContactPicture: StateFlow<ByteArray?> = _sharedContactPicture.asStateFlow()

    private val _sharedLocationIsLive = MutableStateFlow(false)
    val sharedLocationIsLive: StateFlow<Boolean> = _sharedLocationIsLive.asStateFlow()

    private val _sharedLocationSessionId = MutableStateFlow<String?>(null)
    val sharedLocationSessionId: StateFlow<String?> = _sharedLocationSessionId.asStateFlow()

    fun setSharedContactLocation(
        contactName: String?,
        profilePicture: ByteArray?,
        isLive: Boolean,
        sessionId: String?
    ) {
        _sharedContactName.value = contactName
        _sharedContactPicture.value = profilePicture
        _sharedLocationIsLive.value = isLive
        _sharedLocationSessionId.value = sessionId
    }

    fun clearSharedContactLocation() {
        _sharedContactName.value = null
        _sharedContactPicture.value = null
        _sharedLocationIsLive.value = false
        _sharedLocationSessionId.value = null
    }
}
