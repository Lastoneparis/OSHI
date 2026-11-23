package com.oshi.messenger.service.map

import android.content.Context
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.XYTileSource

/**
 * (N2, Anatole 13/11) The base layers the Android map can show.
 *
 * What the stack can actually do: osmdroid draws any XYZ raster source, so a
 * "layer" is a tile URL + a zoom range + an attribution. What OSHI can do
 * PRIVATELY is narrower — only [STANDARD] goes through OSHI's own tile proxy
 * (`oshi-messenger.com/tiles/`, nginx in front of OSM), and only [STANDARD]
 * tiles are in the downloadable offline regions. There is no first-party
 * satellite or terrain proxy on the server today.
 *
 * So the other two layers are fetched DIRECTLY from their publishers. That is a
 * real privacy cost on a privacy app — the publisher sees the user's IP and the
 * area being looked at — and the picker says so in its own words
 * (`map_layer_third_party_desc`) rather than hiding it. [STANDARD] stays the
 * default and the only layer that ever works with no network at all; the others
 * still show any tile they have already fetched (cached per layer) when offline.
 *
 * Moving TERRAIN/SATELLITE behind the OSHI proxy later is a server change plus
 * a [tileUrl] change here; nothing else in the app knows where tiles come from.
 */
enum class MapLayer(
    /** Stable value written to preferences. Never rename. */
    val prefValue: String,
    val maxZoom: Int,
    /** Sub-folder of osmdroid's tile cache. STANDARD keeps "Mapnik" for the existing downloader. */
    val cacheFolder: String,
    /** Shown on the map while the layer is on; the publisher's own wording. */
    val attribution: String,
    /** Host named in the privacy line of the picker. Null for first-party. */
    val thirdPartyHost: String?,
) {
    STANDARD(
        prefValue = "standard",
        maxZoom = OshiTileSource.MAX_ZOOM,
        cacheFolder = "Mapnik",
        attribution = "© OpenStreetMap contributors",
        thirdPartyHost = null,
    ),

    /** OpenTopoMap: contours + hill shading, CC-BY-SA, tiles exist to z=17. */
    TERRAIN(
        prefValue = "terrain",
        maxZoom = 17,
        cacheFolder = "OSHI-Terrain",
        attribution = "© OpenStreetMap contributors, SRTM · © OpenTopoMap (CC-BY-SA)",
        thirdPartyHost = "opentopomap.org",
    ),

    /** Esri World Imagery. */
    SATELLITE(
        prefValue = "satellite",
        maxZoom = 19,
        cacheFolder = "OSHI-Satellite",
        attribution = "Tiles © Esri — Source: Esri, Maxar, Earthstar Geographics, and the GIS User Community",
        thirdPartyHost = "arcgisonline.com",
    );

    val isFirstParty: Boolean get() = thirdPartyHost == null

    /** Only the first-party layer is in the downloaded offline regions. */
    val inOfflineRegions: Boolean get() = this == STANDARD

    /**
     * Tile URL for a slippy-map index. Pure, so the JVM tests can pin it.
     * Esri orders its path z/y/x, not z/x/y — the classic way to get a world
     * made of the wrong tiles.
     */
    fun tileUrl(z: Int, x: Int, y: Int): String = when (this) {
        STANDARD -> OshiTileSource.tileUrl(z, x, y)
        TERRAIN -> "https://${"abc"[Math.floorMod(x + y, 3)]}.tile.opentopomap.org/$z/$x/$y.png"
        SATELLITE -> "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
    }

    /** The osmdroid source. STANDARD is the very same object as before. */
    val source: ITileSource by lazy {
        if (this == STANDARD) OshiTileSource.SOURCE
        else XYTileSource(
            /* aName = */ cacheFolder,
            /* aZoomMinLevel = */ OshiTileSource.MIN_ZOOM,
            /* aZoomMaxLevel = */ maxZoom,
            /* aTileSizePixels = */ OshiTileSource.TILE_SIZE,
            /* aImageFilenameEnding = */ ".png",
            /* aBaseUrl = */ arrayOf(""),
            /* pCopyright = */ attribution
        )
    }

    companion object {
        const val PREF_KEY = "oshi.map.layer"

        val DEFAULT = STANDARD

        /** Unknown or missing value → STANDARD, never a crash and never a third party. */
        fun fromPref(value: String?): MapLayer =
            entries.firstOrNull { it.prefValue == value } ?: DEFAULT

        fun load(context: Context): MapLayer = fromPref(
            context.getSharedPreferences(OshiOfflineTileProvider.PREF_FILE, Context.MODE_PRIVATE)
                .getString(PREF_KEY, null)
        )

        fun save(context: Context, layer: MapLayer) {
            context.getSharedPreferences(OshiOfflineTileProvider.PREF_FILE, Context.MODE_PRIVATE)
                .edit().putString(PREF_KEY, layer.prefValue).apply()
        }
    }
}
