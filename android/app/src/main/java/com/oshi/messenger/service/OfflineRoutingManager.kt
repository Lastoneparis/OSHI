package com.oshi.messenger.service

import android.util.Log
import com.oshi.messenger.data.model.*
import com.oshi.messenger.service.map.OfflineRouter
import com.oshi.messenger.service.map.OfflineRoutingManager as MapOfflineRoutingManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.osmdroid.util.GeoPoint
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OfflineRoutingManager — download/state façade for the offline road graph.
 *
 * ══ WHAT CHANGED 2026-08-13 ══
 *
 * This class used to own a second, Android-only manifest at
 * `https://oshi-messenger.com/maps/v2/routing/manifest.json`. Probed against the
 * live server: **404**. So `useFallbackRoutingManifest()` ran on every launch and
 * installed 27 hardcoded country entries pointing at
 * `/maps/v2/routing/{c}-routing.bin` — also **404** (probed).
 *
 * Two things broke off that:
 *   1. `synthesizeMapRegion` handed the road-graph importer a region whose bbox
 *      was `0,0,0,0` whenever no fallback entry matched, so Overpass was queried
 *      for the Gulf of Guinea.
 *   2. `findRegionForLocation` searched those 27 **country** ids (`fr`, `de`, …)
 *      while the graph is stored under the real **region** id (`fr-idf`), so
 *      `calculateRoute` bailed even when a graph existed.
 *
 * Both manifests are gone. The single source of truth is now the v2 map manifest
 * (`MapManifest.regions[].files.roadgraph` + `bbox`), which is where iOS reads it
 * from too (`OfflineRoadGraphDownloader.swift:111-112`).
 *
 * The region list is read from the manifest OfflineMapManager already caches on
 * disk, and refreshed by [setRegions] when a live fetch lands — a direct
 * dependency on OfflineMapManager would be a DI cycle (it depends on this class).
 */
@Singleton
class OfflineRoutingManager @Inject constructor(
    private val context: android.content.Context,
    private val storageManager: MapStorageManager,
    private val mapRoutingManager: MapOfflineRoutingManager,
    private val router: OfflineRouter
) {
    companion object {
        private const val TAG = "OfflineRoutingManager"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ═══════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════

    /** v2 manifest regions, the only routing source of truth. */
    private val _regions = MutableStateFlow<List<MapRegion>>(emptyList())
    val regions: StateFlow<List<MapRegion>> = _regions.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, RoutingDownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, RoutingDownloadProgress>> = _downloadProgress.asStateFlow()

    /** Soft errors forwarded from the installer (e.g. no roadgraph for a country). */
    val roadGraphErrors: StateFlow<Map<String, MapOfflineRoutingManager.RoadGraphError>> =
        mapRoutingManager.errors

    private val _totalRoutingStorage = MutableStateFlow(0L)
    val totalRoutingStorage: StateFlow<Long> = _totalRoutingStorage.asStateFlow()

    init {
        scope.launch {
            loadRegionsFromCachedManifest()
            refreshRoutingStorage()
            refreshRoutingStates()
        }
    }

    /** Called by OfflineMapManager whenever it has a fresh, validated region list. */
    fun setRegions(regions: List<MapRegion>) {
        if (regions.isEmpty()) return
        _regions.value = regions
    }

    private fun loadRegionsFromCachedManifest() {
        try {
            val file = File(storageManager.mapsRoot, "manifest.json")
            if (!file.exists()) return
            val manifest = json.decodeFromString<MapManifest>(file.readText())
            _regions.value = manifest.validRegions
            Log.d(TAG, "Loaded ${manifest.validRegions.size} regions from cached v2 manifest")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cached v2 manifest: ${e.message}")
        }
    }

    private fun regionById(regionId: String): MapRegion? =
        _regions.value.firstOrNull { it.id == regionId }

    // ═══════════════════════════════════════════════════════════════
    // Availability
    // ═══════════════════════════════════════════════════════════════

    /** True when a usable road graph is installed on disk for [regionId]. */
    fun isRoutingAvailable(regionId: String): Boolean =
        mapRoutingManager.isRoutingAvailable(regionId)

    fun getRoutingState(regionId: String): RoutingDownloadState {
        val progress = _downloadProgress.value[regionId]
        if (progress != null) return progress.state
        return if (isRoutingAvailable(regionId)) {
            RoutingDownloadState.DOWNLOADED
        } else {
            RoutingDownloadState.NOT_DOWNLOADED
        }
    }

    /**
     * The road-graph artifact the server advertises for this region, or null.
     *
     * `null` is a normal answer: only 155 of the live manifest's 234 regions have
     * a `roadgraph` entry (measured 2026-08-13). The UI must render "offline
     * routing not available for this country" rather than a Download button —
     * iOS's `map.regions.roadgraph.unavailable_for_country`.
     */
    fun getRoadGraphFile(regionId: String): MapFile? =
        regionById(regionId)?.files?.roadgraph?.takeIf { it.isAvailable }

    /** Whether the server has a road graph to offer for this region at all. */
    fun hasRoadGraphArtifact(regionId: String): Boolean = getRoadGraphFile(regionId) != null

    // ═══════════════════════════════════════════════════════════════
    // Download / delete
    // ═══════════════════════════════════════════════════════════════

    fun downloadRouting(regionId: String) {
        scope.launch {
            updateProgress(regionId, RoutingDownloadProgress(regionId, 0f, RoutingDownloadState.DOWNLOADING))
            try {
                val region = regionById(regionId)
                if (region == null) {
                    // Without a manifest entry we have no bbox and no artifact
                    // URL. Guessing was what produced the zero-bbox Overpass
                    // query; refusing is the honest answer.
                    Log.w(TAG, "No v2 manifest entry for $regionId — cannot install a road graph")
                    updateProgress(
                        regionId,
                        RoutingDownloadProgress(regionId, 0f, RoutingDownloadState.ERROR, "unknown region")
                    )
                    return@launch
                }

                mapRoutingManager.downloadRouting(region)

                val ok = mapRoutingManager.isRoutingAvailable(regionId)
                // __MAP_SPACE_GUARD_2026_10_21__ Carry the REASON into the
                // progress, not just the ERROR state. The screen renders
                // `routingProgress.error` and otherwise falls back to a generic
                // "download failed" — which for a refusal on free space sends
                // the user back to retry forever instead of telling them the one
                // thing that would change the outcome. `roadGraphErrors` has
                // always been exposed and never rendered by anything.
                val reason = if (ok) null else when (mapRoutingManager.errors.value[regionId]) {
                    MapOfflineRoutingManager.RoadGraphError.INSUFFICIENT_SPACE ->
                        context.getString(com.oshi.messenger.R.string.map_regions_insufficient_space)
                    else -> null
                }
                updateProgress(
                    regionId,
                    RoutingDownloadProgress(
                        regionId, if (ok) 1f else 0f,
                        if (ok) RoutingDownloadState.DOWNLOADED else RoutingDownloadState.ERROR,
                        reason
                    )
                )
                refreshRoutingStorage()
                Log.d(TAG, "Routing pipeline finished for $regionId (graph available=$ok)")
            } catch (e: CancellationException) {
                routingRegionDir(regionId).deleteRecursively()
                updateProgress(regionId, RoutingDownloadProgress(regionId, 0f, RoutingDownloadState.NOT_DOWNLOADED))
                Log.d(TAG, "Routing download cancelled for $regionId")
            } catch (e: Exception) {
                Log.e(TAG, "Routing download failed for $regionId: ${e.message}")
                updateProgress(regionId, RoutingDownloadProgress(regionId, 0f, RoutingDownloadState.ERROR, e.message))
            }
        }
    }

    fun deleteRouting(regionId: String) {
        scope.launch {
            val dir = routingRegionDir(regionId)
            if (dir.exists()) dir.deleteRecursively()
            updateProgress(regionId, RoutingDownloadProgress(regionId, 0f, RoutingDownloadState.NOT_DOWNLOADED))
            refreshRoutingStorage()
            Log.d(TAG, "Deleted routing for $regionId")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Offline route calculation
    // ═══════════════════════════════════════════════════════════════

    fun calculateRoute(
        originLat: Double, originLon: Double,
        destLat: Double, destLon: Double,
        mode: RouteMode
    ): MapRoute? {
        val originRegion = findRegionForLocation(originLat, originLon)
        val destRegion = findRegionForLocation(destLat, destLon)

        if (originRegion == null || destRegion == null) {
            Log.d(TAG, "No routing data for origin or destination location")
            return null
        }
        // We only support a single road-graph DB per route (must cover both ends).
        if (originRegion != destRegion) {
            Log.d(TAG, "Origin/destination span different routing regions — skipping offline")
            return null
        }
        if (!router.hasGraph(originRegion)) {
            Log.d(TAG, "Routing graph DB not available for $originRegion")
            return null
        }

        // Run the bidirectional A* search synchronously on the calling thread —
        // callers (MapViewModel) already invoke us inside Dispatchers.IO.
        return runBlocking {
            router.route(
                from = GeoPoint(originLat, originLon),
                to = GeoPoint(destLat, destLon),
                mode = mode,
                regionId = originRegion
            )
        }
    }

    /**
     * Which INSTALLED road graph covers this point.
     *
     * Resolved against the v2 manifest bbox list and keyed by the real region id,
     * which is the id the graph is stored under. The previous implementation
     * searched the fallback routing manifest's 27 country ids, so a graph
     * installed as `fr-idf` was looked up as `fr` and never found.
     *
     * Smallest matching bbox wins, so a city graph beats the country graph that
     * contains it.
     */
    internal fun findRegionForLocation(lat: Double, lon: Double): String? =
        _regions.value
            .asSequence()
            .filter { it.contains(lat, lon) && isRoutingAvailable(it.id) }
            .minByOrNull { (it.maxLat - it.minLat) * (it.maxLon - it.minLon) }
            ?.id

    // ═══════════════════════════════════════════════════════════════
    // Directory helpers
    // ═══════════════════════════════════════════════════════════════

    /** Matches `OfflineRouter.roadGraphPath`: filesDir/Maps/routing/{regionId}/. */
    private fun routingRegionDir(regionId: String): File =
        router.roadGraphPath(regionId).parentFile ?: File(storageManager.mapsRoot, "routing/$regionId")

    private fun routingRoot(): File =
        File(storageManager.mapsRoot, "routing").also { it.mkdirs() }

    fun totalRoutingStorageUsed(): Long {
        val root = routingRoot()
        if (!root.exists()) return 0L
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Region IDs whose road graph is installed on disk. */
    fun listDownloadedRoutingRegions(): List<String> {
        val root = routingRoot()
        if (!root.exists()) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory && File(it, OfflineRouter.DB_FILENAME).length() > 0 }
            ?.map { it.name }
            ?: emptyList()
    }

    // ═══════════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════════

    private fun updateProgress(regionId: String, progress: RoutingDownloadProgress) {
        _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
            put(regionId, progress)
        }
    }

    private fun refreshRoutingStorage() {
        _totalRoutingStorage.value = totalRoutingStorageUsed()
    }

    private fun refreshRoutingStates() {
        val downloaded = listDownloadedRoutingRegions()
        val progressMap = _downloadProgress.value.toMutableMap()
        for (id in downloaded) {
            if (progressMap[id]?.state != RoutingDownloadState.DOWNLOADING) {
                progressMap[id] = RoutingDownloadProgress(id, 1f, RoutingDownloadState.DOWNLOADED)
            }
        }
        _downloadProgress.value = progressMap
    }
}
