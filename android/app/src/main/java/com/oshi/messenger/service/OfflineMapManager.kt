package com.oshi.messenger.service

import android.content.Context
import android.util.Log
import com.oshi.messenger.data.model.*
import com.oshi.messenger.service.map.MapArtifactVerifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.config.Configuration
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * OfflineMapManager — singleton handling manifest fetch, region download/delete,
 * download progress tracking, and SHA-256 verification.
 *
 * Matches iOS OfflineMapManager feature-for-feature.
 */
@Singleton
class OfflineMapManager @Inject constructor(
    private val context: Context,
    private val storageManager: MapStorageManager,
    private val poiDatabase: MapPOIDatabase,
    private val offlineRoutingManager: OfflineRoutingManager,
    private val offlineStreetDatabase: OfflineStreetDatabase
) {
    companion object {
        private const val TAG = "OfflineMapManager"
        private const val MANIFEST_URL = "https://oshi-messenger.com/maps/v2/manifest.json"
        private const val OSM_TILE_URL = "https://oshi-messenger.com/tiles"
        private const val MIN_DOWNLOAD_ZOOM = 6
        private const val MAX_DOWNLOAD_ZOOM = 13
        private const val USER_AGENT = "OSHI-Messenger/1.4 (Android; https://oshi-messenger.com)"
        private const val REFERER = "https://oshi-messenger.com/"

        /**
         * Above this share of failed tiles the region is incomplete, not
         * "downloaded". Previously tile 404s were counted and then discarded, so
         * a region with zero usable tiles still reported DOWNLOADED.
         */
        private const val MAX_TILE_FAILURE_RATIO = 0.10f

        /** __MAP_TILE_PARALLEL_2026_09_28__ concurrent tile requests (the server throttles OSM misses). */
        private const val TILE_PARALLELISM = 5
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Per-region download Jobs so the UI can cancel one stuck/long download
    // without taking down unrelated work running on `scope`.
    private val activeDownloadJobs = mutableMapOf<String, Job>()
    private val activeDownloadJobsLock = Any()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .build()
            chain.proceed(request)
        }
        .build()

    // ═══════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════

    private val _manifest = MutableStateFlow<MapManifest?>(null)
    val manifest: StateFlow<MapManifest?> = _manifest.asStateFlow()

    private val _regions = MutableStateFlow<List<MapRegion>>(emptyList())
    val regions: StateFlow<List<MapRegion>> = _regions.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, RegionDownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, RegionDownloadProgress>> = _downloadProgress.asStateFlow()

    private val _isLoadingManifest = MutableStateFlow(false)
    val isLoadingManifest: StateFlow<Boolean> = _isLoadingManifest.asStateFlow()

    private val _totalStorageUsed = MutableStateFlow(0L)
    val totalStorageUsed: StateFlow<Long> = _totalStorageUsed.asStateFlow()

    init {
        // Load cached manifest on init, then try remote
        scope.launch {
            loadCachedManifest()
            refreshManifest()
            refreshStorageUsage()
            refreshDownloadStates()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Manifest
    // ═══════════════════════════════════════════════════════════════

    /** Fetch manifest from server, fall back to hardcoded */
    suspend fun refreshManifest() {
        _isLoadingManifest.value = true
        try {
            val request = Request.Builder().url(MANIFEST_URL).build()
            val response = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }
            if (response.isSuccessful) {
                val body = response.body?.string() ?: throw Exception("Empty response")
                val m = json.decodeFromString<MapManifest>(body)
                // A decode that "succeeds" is NOT proof the payload was the
                // manifest. Until 2026-08-13 the model shared zero fields with the
                // server and, with every field defaulted, produced 234 regions at
                // bbox 0,0,0,0 — which `useFallbackManifest` could never rescue
                // because `regions` was non-empty. Gate on usable bboxes instead.
                val usable = m.validRegions
                if (usable.isEmpty()) {
                    Log.w(TAG, "Manifest decoded but no region carries a usable bbox — keeping fallback")
                    useFallbackManifest()
                    return
                }
                if (usable.size < m.regions.size) {
                    Log.w(TAG, "Dropped ${m.regions.size - usable.size} regions with an unusable bbox")
                }
                _manifest.value = m
                _regions.value = usable
                offlineRoutingManager.setRegions(usable)
                offlineStreetDatabase.setRegions(usable)
                cacheManifest(body)
                Log.d(TAG, "Fetched manifest: ${usable.size} regions, v${m.version}, updated ${m.updated}")
            } else {
                Log.w(TAG, "Manifest fetch failed: ${response.code}")
                useFallbackManifest()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Manifest fetch error: ${e.message}")
            useFallbackManifest()
        } finally {
            _isLoadingManifest.value = false
        }
    }

    private fun loadCachedManifest() {
        try {
            val file = File(storageManager.mapsRoot, "manifest.json")
            if (file.exists()) {
                val m = json.decodeFromString<MapManifest>(file.readText())
                val usable = m.validRegions
                if (usable.isEmpty()) {
                    Log.w(TAG, "Cached manifest has no region with a usable bbox — ignoring it")
                    return
                }
                _manifest.value = m
                _regions.value = usable
                offlineRoutingManager.setRegions(usable)
                offlineStreetDatabase.setRegions(usable)
                Log.d(TAG, "Loaded cached manifest: ${usable.size} regions")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cached manifest: ${e.message}")
        }
    }

    private fun cacheManifest(jsonStr: String) {
        try {
            File(storageManager.mapsRoot, "manifest.json").writeText(jsonStr)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache manifest: ${e.message}")
        }
    }

    private fun useFallbackManifest() {
        // Guard on *usable* regions, not merely non-empty ones: the old check let
        // a manifest of 234 zero-bbox regions block the fallback forever.
        if (_manifest.value?.validRegions?.isNotEmpty() == true) return
        val fallback = buildFallbackManifest()
        _manifest.value = fallback
        _regions.value = fallback.validRegions
        offlineRoutingManager.setRegions(fallback.validRegions)
        offlineStreetDatabase.setRegions(fallback.validRegions)
        Log.d(TAG, "Using fallback manifest: ${fallback.validRegions.size} regions")
    }

    // ═══════════════════════════════════════════════════════════════
    // Download
    // ═══════════════════════════════════════════════════════════════

    /** Download a region's tiles directly from OSM tile server and POIs */
    fun downloadRegion(region: MapRegion) {
        Log.d(TAG, "downloadRegion called: ${region.id} (${region.name}), bbox: ${region.bbox}")
        // Hard refusal. `calculateTileCoordinates(0,0,0,0,6,13)` returns exactly
        // one tile per zoom — 8 tiles in the Gulf of Guinea — and the old
        // `totalTiles == 0` guard therefore never fired, so the region was marked
        // DOWNLOADED over ocean.
        if (!region.hasValidBBox) {
            Log.e(TAG, "Refusing ${region.id}: bbox ${region.bbox} is unusable")
            updateProgress(
                region.id,
                RegionDownloadProgress(region.id, 0f, RegionDownloadState.ERROR, "Invalid region bounds")
            )
            return
        }
        // If the user re-taps Download while one is already running for this
        // region (or hits cancel then re-downloads quickly), tear down the
        // previous Job first so we don't end up with two coroutines racing on
        // the same tile cache.
        synchronized(activeDownloadJobsLock) {
            activeDownloadJobs[region.id]?.cancel()
        }
        val job = scope.launch {
            val regionId = region.id
            Log.d(TAG, "Coroutine started for $regionId")
            updateProgress(regionId, RegionDownloadProgress(regionId, 0f, RegionDownloadState.DOWNLOADING))

            try {
                // Step 1: Download OSM tiles for zoom levels 6-13 (90% of progress)
                val tileCoords = calculateTileCoordinates(
                    region.minLat, region.maxLat,
                    region.minLon, region.maxLon,
                    MIN_DOWNLOAD_ZOOM, MAX_DOWNLOAD_ZOOM
                )
                val totalTiles = tileCoords.size
                Log.d(TAG, "Region $regionId: $totalTiles tiles to download (zoom $MIN_DOWNLOAD_ZOOM-$MAX_DOWNLOAD_ZOOM)")

                if (totalTiles == 0) {
                    updateProgress(regionId, RegionDownloadProgress(regionId, 0f, RegionDownloadState.ERROR, "No tiles to download"))
                    return@launch
                }

                // OSMDroid tile cache directory (Mapnik tile source)
                val osmTileCache = getOsmDroidTileCacheDir()
                // __MAP_TILE_PARALLEL_2026_09_28__ Tiles used to be fetched one at a time, so a region
                // crawled at one round trip per ~35 KB tile (20-50 KB/s far from the server). Our
                // /tiles proxy now serves cached tiles without limit and alone throttles what it asks
                // OSM for (2 r/s, server side), so a few parallel requests are safe for the OSM tile
                // policy and multiply the speed on everything already cached.
                val downloadedCount = java.util.concurrent.atomic.AtomicInteger(0)
                val errorCount = java.util.concurrent.atomic.AtomicInteger(0)
                val queue = kotlinx.coroutines.channels.Channel<TileCoord>(capacity = 64)

                fun reportProgress() {
                    updateProgress(regionId, RegionDownloadProgress(
                        regionId, (downloadedCount.get().toFloat() / totalTiles) * 0.9f,
                        RegionDownloadState.DOWNLOADING
                    ))
                }

                coroutineScope {
                    launch {
                        for (coord in tileCoords) {
                            ensureActive()
                            queue.send(coord)
                        }
                        queue.close()
                    }
                    repeat(TILE_PARALLELISM) {
                        launch {
                            for ((z, x, y) in queue) {
                                ensureActive() // support cancellation

                                // OSMDroid stores tiles as: {cache}/Mapnik/{z}/{x}/{y}.png.tile
                                val tileDir = File(osmTileCache, "Mapnik/$z/$x")
                                tileDir.mkdirs()
                                val tileFile = File(tileDir, "$y.png.tile")

                                // A saved OSM "Access blocked" picture (13/11/2026 proxy
                                // incident) counts as MISSING, so a re-download refetches it.
                                if (tileFile.exists() && tileFile.length() > 0 &&
                                    !com.oshi.messenger.service.map.PoisonedTilePurge.isPoisonedFile(tileFile)
                                ) {
                                    // Already cached (a resumed region): skip
                                    if (downloadedCount.incrementAndGet() % 50 == 0) reportProgress()
                                    continue
                                }

                                // __MAP_TILE_RETRY_2026_09_28__ A transient error (timeout, 5xx, 429,
                                // reset) used to count as a lost tile at once; past 10% the whole region
                                // failed ("failed to download N of N", user report 28/09). Retry twice
                                // with backoff; a 404 is final. Tiles already on disk are skipped on the
                                // next run, so a failed region resumes where it stopped.
                                val url = "$OSM_TILE_URL/$z/$x/$y.png"
                                var attempt = 0
                                while (true) {
                                    try {
                                        downloadTileFile(url, tileFile)
                                        downloadedCount.incrementAndGet()
                                        break
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        attempt++
                                        if (attempt >= 3 || e.message == "HTTP 404") {
                                            errorCount.incrementAndGet()
                                            tileFile.delete()   // never keep a truncated tile as "cached"
                                            Log.w(TAG, "Failed tile $z/$x/$y after $attempt attempt(s): ${e.message}")
                                            break
                                        }
                                        delay(1_000L * attempt * attempt)
                                    }
                                }
                                if (downloadedCount.get() % 10 == 0) reportProgress()
                            }
                        }
                    }
                }
                reportProgress()
                val downloaded = downloadedCount.get()
                val errors = errorCount.get()

                Log.d(TAG, "Region $regionId: downloaded $downloaded/$totalTiles tiles ($errors errors)")

                // Also save tile list in region dir for tracking
                storageManager.tilesDir(regionId).mkdirs()

                updateProgress(regionId, RegionDownloadProgress(regionId, 0.9f, RegionDownloadState.DOWNLOADING))

                // Too many tiles missing means the map will be full of holes, and
                // calling that DOWNLOADED is the lie the old code told: 404s were
                // counted into `errors` and then ignored.
                if (totalTiles > 0 && errors.toFloat() / totalTiles > MAX_TILE_FAILURE_RATIO) {
                    throw Exception("Tile download incomplete: $errors of $totalTiles failed")
                }

                // Step 2: POI database — the SQLite the server builds per region.
                // The URL comes from the manifest (`files.poi.url`); the old code
                // read a `poi_url` field that does not exist on the wire, so it was
                // always blank and this whole step was silently skipped.
                val poiFile = region.files.poi
                var poiVerified = false
                if (poiFile != null && poiFile.isAvailable) {
                    try {
                        val poisDb = storageManager.poisDbFile(regionId)
                        // Staged beside the destination and RESUMED, not
                        // restarted. downloadFile() opened a plain GET straight
                        // onto the final path after deleting it, so an
                        // interrupted POI download threw away everything it had
                        // and began again from zero -- up to 330 MB per region
                        // (de is 329 MB). Same defect the road graph had, and
                        // the same server support fixes it: Accept-Ranges and a
                        // real 206 (verified 2026-10-13).
                        val poiStaged = File(poisDb.parentFile, poisDb.name + ".part")
                        when (
                            val got = com.oshi.messenger.service.map.ResumableArtifactDownload.fetch(
                                client = httpClient,
                                url = poiFile.url,
                                staged = poiStaged,
                                label = "$regionId/poi",
                                // __MAP_SPACE_GUARD_2026_10_21__ poi.db is used
                                // exactly as downloaded, so expansionRatio stays
                                // 0.0 — charging it a 2.65x unpack it never
                                // performs would refuse installable regions.
                                expectedBytes = poiFile.realSize,
                            ) { p ->
                                updateProgress(
                                    regionId,
                                    RegionDownloadProgress(regionId, 0.9f + p * 0.05f, RegionDownloadState.DOWNLOADING)
                                )
                            }
                        ) {
                            is com.oshi.messenger.service.map.ResumableArtifactDownload.Outcome.Complete -> Unit
                            is com.oshi.messenger.service.map.ResumableArtifactDownload.Outcome.NotAvailable ->
                                throw Exception("POI database not available for $regionId")
                            is com.oshi.messenger.service.map.ResumableArtifactDownload.Outcome.InsufficientSpace ->
                                throw Exception(
                                    "POI database for $regionId needs ~${got.neededBytes} B, " +
                                        "${got.availableBytes} B free — not downloaded"
                                )
                            is com.oshi.messenger.service.map.ResumableArtifactDownload.Outcome.Failed ->
                                // The partial stays on disk; the next attempt
                                // continues from it.
                                throw Exception("POI download incomplete for $regionId (kept=${got.partialKept})")
                        }
                        // Install only once the whole body is here, so a
                        // truncated file never occupies the path that
                        // hasPoiArtifact/open() would read.
                        if (poisDb.exists()) poisDb.delete()
                        if (!poiStaged.renameTo(poisDb)) {
                            com.oshi.messenger.service.map.ResumableArtifactDownload.clear(poiStaged)
                            throw Exception("Could not install POI database for $regionId")
                        }
                        com.oshi.messenger.service.map.ResumableArtifactDownload.clear(poiStaged)
                        // INTEGRITY GATE. A truncated poi.db opens fine and then
                        // returns nothing; without this it reported "ready".
                        val verdict = MapArtifactVerifier.verifyOrDelete(
                            file = poisDb,
                            expectedSha256 = poiFile.verifiableSha256,
                            expectedSize = poiFile.realSize,
                            strict = true,
                        )
                        poiVerified = verdict.isVerified
                        if (!poiVerified) {
                            throw Exception("POI database failed integrity check ($verdict)")
                        }
                        Log.d(TAG, "POI db for $regionId: ${poisDb.length() / 1024} KB, $verdict")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A region with no searchable POIs is not "downloaded".
                        throw Exception("POI download failed for $regionId: ${e.message}", e)
                    }
                }

                updateProgress(regionId, RegionDownloadProgress(regionId, 0.95f, RegionDownloadState.DOWNLOADING))

                // Step 3: Save metadata
                val metadata = DownloadedRegionMetadata(
                    regionId = regionId,
                    version = _manifest.value?.version ?: 1,
                    downloadedAt = System.currentTimeMillis(),
                    sizeBytes = storageManager.regionStorageUsed(regionId),
                    tileCount = downloaded,
                    poiCount = if (poiVerified) poiDatabase.poiCount(regionId) else 0
                )
                storageManager.saveMetadata(metadata)

                updateProgress(regionId, RegionDownloadProgress(regionId, 1f, RegionDownloadState.DOWNLOADED))
                refreshStorageUsage()
                Log.d(TAG, "Downloaded region $regionId successfully ($downloaded tiles)")

                // Auto-download the street index. Routing is only auto-fetched when
                // the manifest actually advertises a road graph — 79 of 234 regions
                // (every fr-* and de-*) have none, and firing the download anyway
                // only produced a guaranteed 404 and a spurious ERROR badge.
                if (!offlineRoutingManager.isRoutingAvailable(regionId) &&
                    offlineRoutingManager.hasRoadGraphArtifact(regionId)
                ) {
                    Log.d(TAG, "Auto-downloading road graph for $regionId")
                    offlineRoutingManager.downloadRouting(regionId)
                }
                if (!offlineStreetDatabase.isAvailable(regionId)) {
                    Log.d(TAG, "Auto-downloading street index for $regionId")
                    offlineStreetDatabase.downloadIndex(regionId)
                }

            } catch (e: CancellationException) {
                storageManager.deleteRegion(regionId)
                updateProgress(regionId, RegionDownloadProgress(regionId, 0f, RegionDownloadState.NOT_DOWNLOADED))
                Log.d(TAG, "Download cancelled for $regionId")
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for $regionId: ${e.message}")
                updateProgress(regionId, RegionDownloadProgress(regionId, 0f, RegionDownloadState.ERROR, e.message))
            } finally {
                synchronized(activeDownloadJobsLock) {
                    activeDownloadJobs.remove(regionId)
                }
            }
        }
        synchronized(activeDownloadJobsLock) {
            activeDownloadJobs[region.id] = job
        }
    }

    /** Cancel an in-progress download. UI calls this when the user taps the
     *  download progress indicator. Safe to call when no download is active. */
    fun cancelDownload(regionId: String) {
        synchronized(activeDownloadJobsLock) {
            activeDownloadJobs[regionId]?.cancel()
        }
    }

    /** Delete a downloaded region */
    fun deleteRegion(regionId: String) {
        scope.launch {
            storageManager.deleteRegion(regionId)
            updateProgress(regionId, RegionDownloadProgress(regionId, 0f, RegionDownloadState.NOT_DOWNLOADED))
            refreshStorageUsage()
            Log.d(TAG, "Deleted region $regionId")
        }
    }

    /** Get download state for a region */
    fun getRegionState(regionId: String): RegionDownloadState {
        val progress = _downloadProgress.value[regionId]
        if (progress != null) return progress.state
        return if (storageManager.isRegionDownloaded(regionId)) {
            RegionDownloadState.DOWNLOADED
        } else {
            RegionDownloadState.NOT_DOWNLOADED
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // POI coverage — 22 of 254 live regions ship no places database
    // ═══════════════════════════════════════════════════════════════

    /** Region ids present on disk. Thin pass-through so callers need no storage handle. */
    fun downloadedRegionIds(): List<String> = storageManager.listDownloadedRegionIds()

    /**
     * The downloaded regions whose manifest entry carries no POI artifact — they
     * will never answer a place query, no matter how long the user waits or how
     * many times they retry. See [com.oshi.messenger.service.map.PoiCoverage].
     */
    fun downloadedRegionsWithoutPoiData(): List<MapRegion> =
        com.oshi.messenger.service.map.PoiCoverage.downloadedRegionsWithoutPoiArtifact(
            regions = _regions.value,
            downloadedRegionIds = downloadedRegionIds()
        )

    /**
     * Why an *empty* POI result set is empty. Only meaningful when the result set
     * really is empty — it says nothing about a search that found something.
     */
    fun classifyEmptyPoiResult(): com.oshi.messenger.service.map.PoiCoverage.EmptyResultReason =
        com.oshi.messenger.service.map.PoiCoverage.classifyEmptyResult(
            regions = _regions.value,
            downloadedRegionIds = downloadedRegionIds()
        )

    /** Get regions grouped by continent */
    fun regionsByContinent(): Map<String, List<MapRegion>> {
        return _regions.value.groupBy { it.continent.ifBlank { "Other" } }
            .toSortedMap()
    }

    // ═══════════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════
    // OSM Tile Coordinate Calculation (Slippy Map)
    // ═══════════════════════════════════════════════════════════════

    /**
     * OSM slippy map tile coordinates.
     * https://wiki.openstreetmap.org/wiki/Slippy_map_tilenames
     */
    private data class TileCoord(val z: Int, val x: Int, val y: Int)

    /** Convert lat/lon bounding box to tile coordinates for zoom levels minZ..maxZ */
    private fun calculateTileCoordinates(
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double,
        minZoom: Int, maxZoom: Int
    ): List<TileCoord> {
        val tiles = mutableListOf<TileCoord>()
        for (z in minZoom..maxZoom) {
            val n = 1 shl z // 2^z
            // Convert lon to tile X
            val xMin = lonToTileX(minLon, n)
            val xMax = lonToTileX(maxLon, n)
            // Convert lat to tile Y (note: higher lat = lower Y number)
            val yMin = latToTileY(maxLat, n) // maxLat -> smaller Y
            val yMax = latToTileY(minLat, n) // minLat -> larger Y

            for (x in xMin..xMax) {
                for (y in yMin..yMax) {
                    if (x in 0 until n && y in 0 until n) {
                        tiles.add(TileCoord(z, x, y))
                    }
                }
            }
        }
        return tiles
    }

    /** Longitude to tile X coordinate */
    private fun lonToTileX(lon: Double, n: Int): Int {
        return ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    /** Latitude to tile Y coordinate */
    private fun latToTileY(lat: Double, n: Int): Int {
        val latRad = Math.toRadians(lat)
        return ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n)
            .toInt().coerceIn(0, n - 1)
    }

    /**
     * Get the OSMDroid tile cache directory.
     * OSMDroid by default caches to: {externalCacheDir or filesDir}/osmdroid/tiles/
     */
    private fun getOsmDroidTileCacheDir(): File {
        // Use the same path OSMDroid uses internally
        val osmConfig = Configuration.getInstance()
        val basePath = osmConfig.osmdroidBasePath
            ?: File(context.filesDir, "osmdroid")
        val tileCacheDir = osmConfig.osmdroidTileCache
            ?: File(basePath, "tiles")
        tileCacheDir.mkdirs()
        return tileCacheDir
    }

    // ═══════════════════════════════════════════════════════════════
    // File Download Helpers
    // ═══════════════════════════════════════════════════════════════

    /** Download a single tile file from OSM */
    private suspend fun downloadTileFile(url: String, destination: File) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}")
            }

            val body = response.body ?: throw Exception("Empty body")
            destination.parentFile?.mkdirs()
            FileOutputStream(destination).use { output ->
                body.byteStream().use { input ->
                    input.copyTo(output, 8192)
                }
            }
            // Never keep the "Access blocked" picture as a tile (retried, then counted failed).
            if (com.oshi.messenger.service.map.PoisonedTilePurge.isPoisonedFile(destination)) {
                destination.delete()
                throw Exception("Blocked placeholder tile")
            }
        }
    }

    /** Download a file with progress reporting (used for POI data) */
    private suspend fun downloadFile(
        url: String,
        destination: File,
        onProgress: (Float) -> Unit = {}
    ) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw Exception("Download failed: HTTP ${response.code}")
            }

            val body = response.body ?: throw Exception("Empty response body")
            val contentLength = body.contentLength()
            var bytesRead = 0L

            destination.parentFile?.mkdirs()
            FileOutputStream(destination).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (contentLength > 0) {
                            onProgress(bytesRead.toFloat() / contentLength.toFloat())
                        }
                    }
                }
            }
        }
    }

    private fun updateProgress(regionId: String, progress: RegionDownloadProgress) {
        // Atomic: tile workers and other regions update this map concurrently.
        _downloadProgress.update { it + (regionId to progress) }
    }

    private fun refreshStorageUsage() {
        _totalStorageUsed.value = storageManager.totalStorageUsed()
    }

    private fun refreshDownloadStates() {
        val downloaded = storageManager.listDownloadedRegionIds()
        val progressMap = _downloadProgress.value.toMutableMap()
        for (id in downloaded) {
            if (progressMap[id]?.state != RegionDownloadState.DOWNLOADING) {
                progressMap[id] = RegionDownloadProgress(id, 1f, RegionDownloadState.DOWNLOADED)
            }
        }
        _downloadProgress.value = progressMap
    }

    private fun countTiles(regionId: String): Int {
        val tilesDir = storageManager.tilesDir(regionId)
        if (!tilesDir.exists()) return 0
        return tilesDir.walkTopDown().filter { it.isFile }.count()
    }

    // ═══════════════════════════════════════════════════════════════
    // Hardcoded Fallback Manifest (~50 regions, same as iOS)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Hardcoded fallback, used ONLY when the live manifest cannot be fetched or
     * decoded — never when it merely looks odd. Same role as iOS's
     * `hardcodedManifest` (`OfflineMapManager.swift:97-104`).
     *
     * bboxes are `[minLon, minLat, maxLon, maxLat]`, matching the wire order.
     * `files.poi` points at the same `/maps/v2/{id}/poi.db` URLs the live server
     * really serves; `size` is a coarse estimate so the size label is not blank,
     * and there is no sha256 — so [MapArtifactVerifier] reports NO_HASH and the
     * download is refused rather than silently trusted.
     */
    private fun fb(
        id: String, name: String, continent: String, country: String,
        minLon: Double, minLat: Double, maxLon: Double, maxLat: Double,
        approxBytes: Long
    ): MapRegion = MapRegion(
        id = id,
        name = name,
        country = country,
        continent = continent,
        bbox = listOf(minLon, minLat, maxLon, maxLat),
        files = MapRegionFiles(
            poi = MapFile(
                url = "https://oshi-messenger.com/maps/v2/$id/poi.db",
                size = approxBytes,
                sha256 = ""
            )
        )
    )

    private fun buildFallbackManifest(): MapManifest {
        val regions = listOf(
            fb("fr-idf", "Ile-de-France", "Europe", "France", 1.4, 48.1, 3.6, 49.2, 180_000_000),
            fb("fr-paca", "Provence-Alpes-Cote d'Azur", "Europe", "France", 4.2, 43.0, 7.7, 44.5, 150_000_000),
            fb("fr-ara", "Auvergne-Rhone-Alpes", "Europe", "France", 2.1, 44.1, 7.2, 46.8, 200_000_000),
            fb("fr-occ", "Occitanie", "Europe", "France", 0.1, 42.3, 4.8, 44.9, 170_000_000),
            fb("fr-naq", "Nouvelle-Aquitaine", "Europe", "France", -1.8, 44.0, 2.6, 47.1, 190_000_000),
            fb("uk-london", "Greater London", "Europe", "United Kingdom", -0.51, 51.28, 0.33, 51.69, 120_000_000),
            fb("uk-southeast", "South East England", "Europe", "United Kingdom", -1.9, 50.7, 1.4, 51.9, 160_000_000),
            fb("de-berlin", "Berlin-Brandenburg", "Europe", "Germany", 12.9, 52.0, 13.8, 52.8, 100_000_000),
            fb("de-bayern", "Bayern", "Europe", "Germany", 8.9, 47.3, 13.8, 50.6, 250_000_000),
            fb("de-nrw", "Nordrhein-Westfalen", "Europe", "Germany", 5.9, 50.3, 9.5, 52.5, 180_000_000),
            fb("es-madrid", "Madrid", "Europe", "Spain", -4.6, 39.9, -3.1, 41.2, 90_000_000),
            fb("es-catalonia", "Catalonia", "Europe", "Spain", 0.2, 40.5, 3.3, 42.9, 140_000_000),
            fb("es-andalusia", "Andalusia", "Europe", "Spain", -7.5, 36.0, -1.6, 38.7, 180_000_000),
            fb("it-lazio", "Lazio", "Europe", "Italy", 11.4, 41.0, 14.0, 42.8, 110_000_000),
            fb("it-lombardy", "Lombardy", "Europe", "Italy", 8.5, 45.0, 11.4, 46.6, 160_000_000),
            fb("nl-randstad", "Randstad", "Europe", "Netherlands", 4.2, 51.8, 5.2, 52.5, 80_000_000),
            fb("be-brussels", "Brussels", "Europe", "Belgium", 4.2, 50.7, 4.5, 50.9, 40_000_000),
            fb("ch-zurich", "Zurich", "Europe", "Switzerland", 8.4, 47.2, 8.7, 47.6, 50_000_000),
            fb("pt-lisbon", "Lisbon", "Europe", "Portugal", -9.5, 38.6, -8.8, 39.0, 60_000_000),
            fb("at-vienna", "Vienna", "Europe", "Austria", 16.2, 48.1, 16.6, 48.3, 55_000_000),
            fb("pl-warsaw", "Warsaw", "Europe", "Poland", 20.8, 52.0, 21.2, 52.4, 60_000_000),
            fb("se-stockholm", "Stockholm", "Europe", "Sweden", 17.8, 59.2, 18.2, 59.5, 50_000_000),
            fb("no-oslo", "Oslo", "Europe", "Norway", 10.6, 59.8, 10.9, 60.0, 45_000_000),
            fb("dk-copenhagen", "Copenhagen", "Europe", "Denmark", 12.4, 55.6, 12.7, 55.8, 45_000_000),
            fb("gr-athens", "Athens", "Europe", "Greece", 23.5, 37.8, 23.9, 38.1, 55_000_000),
            fb("fr", "France", "Europe", "France", -5.1, 41.3, 9.6, 51.1, 2_800_000_000),
            fb("de", "Germany", "Europe", "Germany", 5.9, 47.3, 15.0, 55.1, 2_600_000_000),
            fb("gb", "United Kingdom", "Europe", "United Kingdom", -8.2, 50.0, 1.8, 60.9, 2_200_000_000),
            fb("es", "Spain", "Europe", "Spain", -9.3, 36.0, 4.3, 43.8, 2_400_000_000),
            fb("it", "Italy", "Europe", "Italy", 6.6, 36.7, 18.5, 47.1, 2_200_000_000),
            fb("pt", "Portugal", "Europe", "Portugal", -9.5, 37.0, -6.2, 42.2, 680_000_000),
            fb("ie", "Ireland", "Europe", "Ireland", -10.5, 51.4, -6.0, 55.4, 520_000_000),
            fb("nl", "Netherlands", "Europe", "Netherlands", 3.4, 50.8, 7.2, 53.5, 320_000_000),
            fb("be", "Belgium", "Europe", "Belgium", 2.5, 49.5, 6.4, 51.5, 240_000_000),
            fb("lu", "Luxembourg", "Europe", "Luxembourg", 5.7, 49.5, 6.5, 50.2, 60_000_000),
            fb("ch", "Switzerland", "Europe", "Switzerland", 6.0, 45.8, 10.5, 47.8, 260_000_000),
            fb("at", "Austria", "Europe", "Austria", 9.5, 46.4, 17.2, 49.0, 300_000_000),
            fb("se", "Sweden", "Europe", "Sweden", 11.1, 55.3, 24.2, 69.1, 1_800_000_000),
            fb("no", "Norway", "Europe", "Norway", 4.7, 58.0, 31.1, 71.2, 1_600_000_000),
            fb("dk", "Denmark", "Europe", "Denmark", 8.1, 54.6, 15.2, 57.8, 220_000_000),
            fb("fi", "Finland", "Europe", "Finland", 20.6, 59.8, 31.6, 70.1, 1_400_000_000),
            fb("is", "Iceland", "Europe", "Iceland", -24.5, 63.4, -13.5, 66.5, 400_000_000),
            fb("pl", "Poland", "Europe", "Poland", 14.1, 49.0, 24.2, 54.8, 1_400_000_000),
            fb("cz", "Czech Republic", "Europe", "Czech Republic", 12.1, 48.6, 18.9, 51.1, 600_000_000),
            fb("sk", "Slovakia", "Europe", "Slovakia", 16.9, 47.7, 22.6, 49.6, 380_000_000),
            fb("hu", "Hungary", "Europe", "Hungary", 16.1, 45.7, 22.9, 48.6, 520_000_000),
            fb("si", "Slovenia", "Europe", "Slovenia", 13.4, 45.4, 16.6, 46.9, 180_000_000),
            fb("hr", "Croatia", "Europe", "Croatia", 13.5, 42.4, 19.4, 46.6, 400_000_000),
            fb("rs", "Serbia", "Europe", "Serbia", 18.8, 42.2, 23.0, 46.2, 440_000_000),
            fb("ba", "Bosnia and Herzegovina", "Europe", "Bosnia and Herzegovina", 15.7, 42.6, 19.6, 45.3, 300_000_000),
            fb("me", "Montenegro", "Europe", "Montenegro", 18.5, 41.9, 20.4, 43.6, 120_000_000),
            fb("mk", "North Macedonia", "Europe", "North Macedonia", 20.5, 40.9, 23.0, 42.4, 160_000_000),
            fb("al", "Albania", "Europe", "Albania", 19.3, 39.6, 21.1, 42.7, 180_000_000),
            fb("xk", "Kosovo", "Europe", "Kosovo", 20.0, 41.9, 21.8, 43.3, 100_000_000),
            fb("gr", "Greece", "Europe", "Greece", 19.4, 34.8, 29.7, 41.8, 800_000_000),
            fb("bg", "Bulgaria", "Europe", "Bulgaria", 22.4, 41.2, 28.6, 44.2, 520_000_000),
            fb("ro", "Romania", "Europe", "Romania", 20.3, 43.6, 29.7, 48.3, 800_000_000),
            fb("ee", "Estonia", "Europe", "Estonia", 21.8, 57.5, 28.2, 59.7, 240_000_000),
            fb("lv", "Latvia", "Europe", "Latvia", 21.0, 55.7, 28.2, 58.1, 260_000_000),
            fb("lt", "Lithuania", "Europe", "Lithuania", 20.9, 53.9, 26.8, 56.5, 280_000_000),
            fb("cy", "Cyprus", "Europe", "Cyprus", 32.3, 34.6, 34.6, 35.7, 100_000_000),
            fb("mt", "Malta", "Europe", "Malta", 14.2, 35.8, 14.6, 36.1, 30_000_000),
            fb("ua", "Ukraine", "Europe", "Ukraine", 22.1, 44.4, 40.2, 52.4, 1_800_000_000),
            fb("md", "Moldova", "Europe", "Moldova", 26.6, 45.5, 30.2, 48.5, 200_000_000),
            fb("by", "Belarus", "Europe", "Belarus", 23.2, 51.3, 32.8, 56.2, 600_000_000),
            fb("ad", "Andorra", "Europe", "Andorra", 1.4, 42.4, 1.8, 42.7, 30_000_000),
            fb("li", "Liechtenstein", "Europe", "Liechtenstein", 9.5, 47.1, 9.6, 47.3, 20_000_000),
            fb("mc", "Monaco", "Europe", "Monaco", 7.4, 43.7, 7.4, 43.8, 10_000_000),
            fb("us-nyc", "New York City", "North America", "United States", -74.3, 40.5, -73.7, 40.9, 200_000_000),
            fb("us-la", "Los Angeles", "North America", "United States", -118.7, 33.7, -118.0, 34.3, 220_000_000),
            fb("us-chicago", "Chicago", "North America", "United States", -87.9, 41.6, -87.5, 42.1, 130_000_000),
            fb("us-sf", "San Francisco Bay", "North America", "United States", -122.5, 37.2, -121.8, 37.9, 150_000_000),
            fb("us-miami", "Miami", "North America", "United States", -80.4, 25.6, -80.0, 26.0, 100_000_000),
            fb("ca-toronto", "Toronto", "North America", "Canada", -79.6, 43.5, -79.1, 43.9, 110_000_000),
            fb("ca-montreal", "Montreal", "North America", "Canada", -73.8, 45.4, -73.4, 45.7, 90_000_000),
            fb("mx-cdmx", "Mexico City", "North America", "Mexico", -99.4, 19.2, -98.9, 19.6, 140_000_000),
            fb("jp-tokyo", "Tokyo", "Asia", "Japan", 139.5, 35.5, 140.0, 35.9, 250_000_000),
            fb("jp-osaka", "Osaka", "Asia", "Japan", 135.3, 34.5, 135.6, 34.8, 120_000_000),
            fb("kr-seoul", "Seoul", "Asia", "South Korea", 126.8, 37.4, 127.2, 37.7, 150_000_000),
            fb("sg-singapore", "Singapore", "Asia", "Singapore", 103.6, 1.2, 104.0, 1.5, 60_000_000),
            fb("th-bangkok", "Bangkok", "Asia", "Thailand", 100.4, 13.6, 100.7, 13.9, 100_000_000),
            fb("in-mumbai", "Mumbai", "Asia", "India", 72.7, 18.9, 73.0, 19.3, 110_000_000),
            fb("in-delhi", "Delhi", "Asia", "India", 76.9, 28.4, 77.4, 28.8, 130_000_000),
            fb("ae-dubai", "Dubai", "Asia", "UAE", 55.0, 25.0, 55.5, 25.4, 80_000_000),
            fb("hk-hongkong", "Hong Kong", "Asia", "China", 113.8, 22.1, 114.4, 22.6, 70_000_000),
            fb("br-saopaulo", "Sao Paulo", "South America", "Brazil", -46.9, -23.8, -46.3, -23.3, 200_000_000),
            fb("br-rio", "Rio de Janeiro", "South America", "Brazil", -43.8, -23.1, -43.1, -22.7, 130_000_000),
            fb("ar-buenosaires", "Buenos Aires", "South America", "Argentina", -58.6, -34.7, -58.3, -34.5, 120_000_000),
            fb("co", "Colombia", "South America", "Colombia", -79.0, -4.23, -66.87, 12.46, 1_200_000_000),
            fb("mx", "Mexico", "North America", "Mexico", -118.4, 14.53, -86.71, 32.72, 2_000_000_000),
            fb("ma", "Morocco", "Africa", "Morocco", -13.17, 27.66, -1.01, 35.92, 600_000_000),
            fb("eg", "Egypt", "Africa", "Egypt", 24.7, 22.0, 36.87, 31.67, 500_000_000),
            fb("tn", "Tunisia", "Africa", "Tunisia", 7.52, 30.23, 11.6, 37.54, 200_000_000),
            fb("za-capetown", "Cape Town", "Africa", "South Africa", 18.3, -34.2, 18.7, -33.8, 90_000_000),
            fb("eg-cairo", "Cairo", "Africa", "Egypt", 31.1, 29.9, 31.5, 30.2, 100_000_000),
            fb("ma-casablanca", "Casablanca", "Africa", "Morocco", -7.7, 33.5, -7.4, 33.7, 70_000_000),
            fb("ng-lagos", "Lagos", "Africa", "Nigeria", 3.1, 6.3, 3.6, 6.7, 110_000_000),
            fb("ae", "United Arab Emirates", "Asia", "UAE", 51.58, 22.63, 56.38, 26.08, 300_000_000),
            fb("in", "India", "Asia", "India", 68.18, 6.75, 97.4, 35.5, 3_000_000_000),
            fb("au", "Australia", "Oceania", "Australia", 113.34, -43.63, 153.64, -10.67, 2_400_000_000),
            fb("au-sydney", "Sydney", "Oceania", "Australia", 151.0, -33.9, 151.4, -33.6, 130_000_000),
            fb("au-melbourne", "Melbourne", "Oceania", "Australia", 144.7, -38.0, 145.2, -37.6, 120_000_000),
            fb("nz-auckland", "Auckland", "Oceania", "New Zealand", 174.6, -37.0, 175.0, -36.7, 70_000_000),
        )
        return MapManifest(version = 1, regions = regions, updated = "2026-04-01T00:00:00Z")
    }
}
