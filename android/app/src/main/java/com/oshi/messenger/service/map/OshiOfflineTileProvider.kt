package com.oshi.messenger.service.map

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import com.oshi.messenger.service.MapStorageManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.MapTileRequestState
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.MapTileIndex
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * OshiOfflineTileProvider — cache-first tile provider chain for OSHI.
 *
 * Reads tiles from local disk first, then (optionally) falls back to the OSM
 * network tile server. The provider order is:
 *
 *   1. [LocalTileFsModule] — reads PNG files from
 *        `MapStorageManager.tilesDir(regionId)/{z}/{x}/{y}.png` (downloaded
 *        regions) AND OSMDroid's own tile cache at
 *        `osmdroid/tiles/Mapnik/{z}/{x}/{y}.png.tile` (backward-compat with the
 *        existing download path in OfflineMapManager).
 *   2. [NetworkTileModule] — downloads from OSHI's own first-party tile
 *        proxy ([OshiTileSource.BASE_URL]) with a compliant User-Agent
 *        and writes the response back to the local cache so subsequent visits
 *        are offline-ready. Skipped entirely when offline-only mode is on or
 *        when the OkHttp call fails.
 *
 * Offline-only mode is read from
 *     `SharedPreferences.getBoolean("oshi.map.offlineOnly", false)`
 * via [PREF_FILE] / [PREF_KEY]. The settings UI that owns this preference is
 * a separate agent's responsibility; this class only consumes it.
 *
 * Usage from a Composable / Activity:
 *
 *     val provider = OshiOfflineTileProvider.create(context, storageManager)
 *     mapView.tileProvider = provider
 *     mapView.setUseDataConnection(!OshiOfflineTileProvider.isOfflineOnly(context))
 *     mapView.setTileSource(OshiTileSource.SOURCE)
 */
class OshiOfflineTileProvider private constructor(
    tileSource: ITileSource,
    registerReceiver: SimpleRegisterReceiver,
    modules: Array<MapTileModuleProviderBase>
) : MapTileProviderArray(tileSource, registerReceiver, modules) {

    companion object {
        private const val TAG = "OshiOfflineTileProvider"

        /** SharedPreferences file owning the offline-only toggle. */
        const val PREF_FILE = "oshi_map_prefs"

        /** SharedPreferences key for the offline-only toggle (owned by settings UI). */
        const val PREF_KEY = "oshi.map.offlineOnly"

        /**
         * Compliant, contactable User-Agent — see [OshiTileSource.USER_AGENT].
         * A UA without a contact URL is what got the previous requests 403'd.
         * Mirrors iOS `OfflineFirstTileOverlay.swift:168`.
         */
        private const val OSM_USER_AGENT = OshiTileSource.USER_AGENT

        /** SharedPreferences file the settings screen has always written. */
        const val SETTINGS_PREF_FILE = "oshi_settings"

        /** The key the settings screen has always written. */
        const val SETTINGS_PREF_KEY = "settings_routing_offline_only"

        /**
         * Read the offline-only preference. Defaults to `false` (online fallback
         * allowed) when the key is unset.
         *
         * iOS has one key for the whole map stack — `oshi.map.offlineOnly`, read by
         * routing, tiles and search alike (`OSHI/MapRoutingEngine.swift:42`,
         * `OSHI/OfflineFirstTileOverlay.swift:13`, `OSHI/OfflineMapView.swift:2267`).
         * Android grew two: [PREF_KEY] here, which nothing wrote — so tiles and
         * previews ignored the switch entirely — and [SETTINGS_PREF_KEY], which the
         * settings screen wrote and only routing read. `SettingsViewModel` now writes
         * both; the second read keeps the switch honoured for anyone who turned it on
         * before that.
         */
        @JvmStatic
        fun isOfflineOnly(context: Context): Boolean {
            if (context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                    .getBoolean(PREF_KEY, false)
            ) return true
            return context.getSharedPreferences(SETTINGS_PREF_FILE, Context.MODE_PRIVATE)
                .getBoolean(SETTINGS_PREF_KEY, false)
        }

        /**
         * Build an [OshiOfflineTileProvider] wired with the local cache module
         * (always active) and the network module (skipped when offline-only).
         *
         * The returned provider can be assigned to `mapView.tileProvider`.
         */
        @JvmStatic
        fun create(
            context: Context,
            storageManager: MapStorageManager,
            // (N2) Which base layer. STANDARD is exactly the provider this used to
            // build; the others read/write their own cache folder and skip the
            // downloaded regions, which only hold STANDARD tiles.
            layer: MapLayer = MapLayer.STANDARD
        ): OshiOfflineTileProvider {
            val tileSource: ITileSource = layer.source
            val receiver = SimpleRegisterReceiver(context.applicationContext)
            val offlineOnly = isOfflineOnly(context)

            val osmCacheDir = osmDroidTileCacheDir(context)

            val localModule = LocalTileFsModule(
                tileSource = tileSource,
                regionsRoot = File(storageManager.mapsRoot, "regions"),
                osmCacheDir = osmCacheDir,
                layer = layer
            )

            val modules: Array<MapTileModuleProviderBase> = if (offlineOnly) {
                arrayOf(localModule)
            } else {
                arrayOf(localModule, NetworkTileModule(tileSource, osmCacheDir, layer))
            }

            return OshiOfflineTileProvider(tileSource, receiver, modules)
        }

        /**
         * The directory OSMDroid itself uses for its tile cache. The existing
         * download code in [OfflineMapManager] writes tiles here as
         * `Mapnik/{z}/{x}/{y}.png.tile`, so we read from it for backward compat.
         */
        private fun osmDroidTileCacheDir(context: Context): File {
            val osmConfig = Configuration.getInstance()
            val basePath = osmConfig.osmdroidBasePath
                ?: File(context.filesDir, "osmdroid")
            return (osmConfig.osmdroidTileCache ?: File(basePath, "tiles")).also { it.mkdirs() }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Local filesystem module — first in the provider chain.
    // Reads from BOTH the per-region tiles dir (prompt's documented layout)
    // and OSMDroid's own cache (backward compat with the current downloader).
    // ─────────────────────────────────────────────────────────────

    private class LocalTileFsModule(
        @Volatile private var tileSource: ITileSource,
        private val regionsRoot: File,
        private val osmCacheDir: File,
        private val layer: MapLayer
    ) : MapTileModuleProviderBase(
        /* pThreadPoolSize = */ 2,
        /* pPendingQueueSize = */ 40
    ) {
        override fun getName(): String = "OshiLocalTileFs"
        override fun getThreadGroupName(): String = "oshi-local-tile-fs"

        override fun getMinimumZoomLevel(): Int = tileSource.minimumZoomLevel
        override fun getMaximumZoomLevel(): Int = tileSource.maximumZoomLevel

        // Local-only — never hits the network.
        override fun getUsesDataConnection(): Boolean = false

        override fun setTileSource(pTileSource: ITileSource) {
            tileSource = pTileSource
        }

        override fun getTileLoader(): TileLoader = LocalTileLoader()

        private inner class LocalTileLoader : MapTileModuleProviderBase.TileLoader() {
            override fun loadTile(pMapTileIndex: Long): Drawable? {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)

                // 1. Per-region tile cache: regions/*/tiles/{z}/{x}/{y}.png
                //    (STANDARD only — a downloaded region holds no satellite or
                //    terrain tiles, and drawing street tiles into a satellite map
                //    would be a patchwork of two different maps.)
                if (layer.inOfflineRegions && regionsRoot.isDirectory) {
                    val regions = regionsRoot.listFiles()
                    if (regions != null) {
                        for (regionDir in regions) {
                            if (!regionDir.isDirectory) continue
                            val candidate = File(
                                regionDir,
                                "tiles${File.separator}$z${File.separator}$x${File.separator}$y.png"
                            )
                            if (candidate.exists() && candidate.length() > 0) {
                                decodeBitmapDrawable(candidate)?.let { return it }
                            }
                        }
                    }
                }

                // 2. OSMDroid's own cache (backward-compat): Mapnik/{z}/{x}/{y}.png.tile
                val osmCached = File(
                    osmCacheDir,
                    "${layer.cacheFolder}${File.separator}$z${File.separator}$x${File.separator}$y.png.tile"
                )
                if (osmCached.exists() && osmCached.length() > 0) {
                    decodeBitmapDrawable(osmCached)?.let { return it }
                }

                // 3. Miss — let the next module (network) handle it.
                return null
            }

            private fun decodeBitmapDrawable(file: File): BitmapDrawable? = try {
                // A saved "Access blocked" picture is a miss: drop it so the network refetches.
                if (PoisonedTilePurge.isPoisonedFile(file)) {
                    file.delete()
                    null
                } else {
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    if (bmp != null) BitmapDrawable(null, bmp) else null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode tile ${file.path}: ${e.message}")
                null
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Network module — second in the chain. Downloads from OSM and
    // writes the response into OSMDroid's cache so the local module
    // serves it next time.
    // ─────────────────────────────────────────────────────────────

    private class NetworkTileModule(
        @Volatile private var tileSource: ITileSource,
        private val osmCacheDir: File,
        private val layer: MapLayer
    ) : MapTileModuleProviderBase(
        /* pThreadPoolSize = */ 2,
        /* pPendingQueueSize = */ 40
    ) {
        private val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val b = chain.request().newBuilder().header("User-Agent", OSM_USER_AGENT)
                // The OSHI Referer is for OSHI's own proxy. A third-party layer
                // gets the contactable UA its usage policy asks for, nothing more.
                if (layer.isFirstParty) b.header("Referer", OshiTileSource.REFERER)
                chain.proceed(b.build())
            }
            .build()

        override fun getName(): String = "OshiNetworkTile"
        override fun getThreadGroupName(): String = "oshi-network-tile"

        override fun getMinimumZoomLevel(): Int = tileSource.minimumZoomLevel
        override fun getMaximumZoomLevel(): Int = tileSource.maximumZoomLevel

        // Network-backed — uses the data connection.
        override fun getUsesDataConnection(): Boolean = true

        override fun setTileSource(pTileSource: ITileSource) {
            tileSource = pTileSource
        }

        override fun getTileLoader(): TileLoader = NetworkTileLoader()

        private inner class NetworkTileLoader : MapTileModuleProviderBase.TileLoader() {
            override fun loadTile(pMapTileIndex: Long): Drawable? {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)

                // First-party tile proxy — NOT OSM's volunteer servers. Hitting
                // tile.openstreetmap.org from the app returned the 403 "not
                // following the tile usage policy" placeholder for every tile.
                // iOS points at the same CDN: OfflineFirstTileOverlay.swift:167.
                // (N2) Third-party layers are fetched from their publisher; see MapLayer.
                val url = layer.tileUrl(z, x, y)
                return try {
                    val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
                    response.use {
                        if (!it.isSuccessful) {
                            return@use null
                        }
                        val bytes = it.body?.bytes() ?: return@use null
                        if (bytes.isEmpty()) return@use null
                        // Never cache/draw the OSM "Access blocked" picture.
                        if (PoisonedTilePurge.isPoisoned(bytes)) return@use null

                        // Cache-population: write the response into OSMDroid's
                        // cache so the local module hits next time.
                        try {
                            val dest = File(
                                osmCacheDir,
                                "${layer.cacheFolder}${File.separator}$z${File.separator}$x${File.separator}$y.png.tile"
                            )
                            dest.parentFile?.mkdirs()
                            FileOutputStream(dest).use { out -> out.write(bytes) }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to write tile $z/$x/$y to cache: ${e.message}")
                        }

                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) BitmapDrawable(null, bmp) else null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Network fetch failed for $z/$x/$y: ${e.message}")
                    null
                }
            }
        }
    }
}
