package com.oshi.messenger.service.map

import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.XYTileSource

/**
 * OshiTileSource — the single, first-party raster tile origin for OSHI on Android.
 *
 * ## Why this exists
 *
 * Android previously pointed osmdroid straight at OpenStreetMap's volunteer tile
 * servers (`https://tile.openstreetmap.org/{z}/{x}/{y}.png`, via
 * `TileSourceFactory.MAPNIK`) and identified itself with a bare application id
 * (`Configuration.getInstance().userAgentValue = context.packageName`). That
 * combination violates the OSM tile usage policy, and the servers answered every
 * request with the 403 "App is not following the tile usage policy" placeholder
 * image — so *every* tile on the Map tab rendered as a hazard-striped error card.
 *
 * iOS never did this. `OSHI/OfflineFirstTileOverlay.swift:167` requests tiles from
 * OSHI's own CDN and sends a compliant, contactable User-Agent plus a Referer:
 *
 * ```swift
 * // OfflineFirstTileOverlay.swift:167-171
 * config.httpAdditionalHeaders = [
 *     "User-Agent": "OSHI-Messenger/1.0 (iOS; https://oshi-messenger.com)",
 *     "Referer": "https://oshi-messenger.com/",
 * ]
 * ```
 *
 * Android already had a first-party path — [OshiStaticMapRenderer] fetches from
 * `https://oshi-messenger.com/tiles` (see `OshiStaticMapRenderer.kt:68`). This
 * object hoists that origin into one shared constant so the interactive map, the
 * navigation map and the static preview renderer all use the same proxy with the
 * same UA discipline, rather than each inventing its own.
 *
 * ## Zoom range
 *
 * OSM raster tiles only exist up to z=19. iOS advertises a deeper `maximumZ` (22)
 * and upscales the z=19 parent (`OfflineFirstTileOverlay.swift:181-186`); osmdroid
 * does its own parent-tile upscaling when `MapView.maxZoomLevel` exceeds the
 * source maximum, so the source itself stays honest at 19.
 */
object OshiTileSource {

    /**
     * First-party tile proxy origin. Same host [OshiStaticMapRenderer] uses
     * (`OshiStaticMapRenderer.kt:68`) and the same one iOS's overlay points at
     * (`OfflineFirstTileOverlay.swift` `onlineTemplate`).
     */
    const val BASE_URL: String = "https://oshi-messenger.com/tiles/"

    /**
     * Compliant, contactable User-Agent. Mirrors iOS
     * `OfflineFirstTileOverlay.swift:168` with the platform token swapped.
     *
     * A bare package name is NOT an acceptable UA for a tile client; it carries
     * no way to contact the operator, which is exactly what got the previous
     * requests 403'd.
     */
    const val USER_AGENT: String = "OSHI-Messenger/1.0 (Android; https://oshi-messenger.com)"

    /** Referer sent alongside the UA, matching iOS `OfflineFirstTileOverlay.swift:169`. */
    const val REFERER: String = "https://oshi-messenger.com/"

    /** Lowest zoom served. Matches iOS `minimumZ = 1`. */
    const val MIN_ZOOM: Int = 1

    /** Highest zoom for which raster tiles physically exist. */
    const val MAX_ZOOM: Int = 19

    /** Tile edge length in pixels. */
    const val TILE_SIZE: Int = 256

    /**
     * Build the tile URL for a slippy-map index. Kept as a pure function so the
     * JVM unit tests can assert the origin without touching osmdroid.
     */
    fun tileUrl(z: Int, x: Int, y: Int): String = "$BASE_URL$z/$x/$y.png"

    /**
     * osmdroid tile source backed by the first-party proxy.
     *
     * The name doubles as osmdroid's on-disk cache folder name for any provider
     * that derives paths from the source, so it is deliberately stable.
     */
    val SOURCE: ITileSource = XYTileSource(
        /* aName = */ "OSHI",
        /* aZoomMinLevel = */ MIN_ZOOM,
        /* aZoomMaxLevel = */ MAX_ZOOM,
        /* aTileSizePixels = */ TILE_SIZE,
        /* aImageFilenameEnding = */ ".png",
        /* aBaseUrl = */ arrayOf(BASE_URL),
        /* pCopyright = */ "© OpenStreetMap contributors"
    )
}
