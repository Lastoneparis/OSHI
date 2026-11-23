package com.oshi.messenger

import com.oshi.messenger.service.map.MapLayer
import com.oshi.messenger.service.map.OshiTileSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** (N2, Anatole 13/11) Map base layers. */
class MapLayerTest {

    @Test
    fun `standard is unchanged - same proxy, same cache folder, first party`() {
        val l = MapLayer.STANDARD
        assertEquals(OshiTileSource.tileUrl(10, 511, 340), l.tileUrl(10, 511, 340))
        assertEquals("Mapnik", l.cacheFolder)   // the existing downloader writes here
        assertTrue(l.isFirstParty)
        assertTrue(l.inOfflineRegions)
        assertNull(l.thirdPartyHost)
        assertEquals(OshiTileSource.MAX_ZOOM, l.maxZoom)
    }

    @Test
    fun `default and unknown values fall back to standard, never to a third party`() {
        assertEquals(MapLayer.STANDARD, MapLayer.DEFAULT)
        assertEquals(MapLayer.STANDARD, MapLayer.fromPref(null))
        assertEquals(MapLayer.STANDARD, MapLayer.fromPref(""))
        assertEquals(MapLayer.STANDARD, MapLayer.fromPref("hybrid"))
        for (l in MapLayer.entries) assertEquals(l, MapLayer.fromPref(l.prefValue))
    }

    @Test
    fun `esri imagery is addressed z-y-x`() {
        assertEquals(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/10/340/511",
            MapLayer.SATELLITE.tileUrl(10, 511, 340)
        )
    }

    @Test
    fun `opentopomap is addressed z-x-y on one of its three hosts, capped at 17`() {
        val url = MapLayer.TERRAIN.tileUrl(10, 511, 340)
        assertTrue(url, Regex("https://[abc]\\.tile\\.opentopomap\\.org/10/511/340\\.png").matches(url))
        assertEquals(17, MapLayer.TERRAIN.maxZoom)
    }

    @Test
    fun `third-party layers say who they are and keep their own cache`() {
        for (l in listOf(MapLayer.TERRAIN, MapLayer.SATELLITE)) {
            assertFalse(l.isFirstParty)
            assertFalse("regions hold standard tiles only", l.inOfflineRegions)
            assertNotNull(l.thirdPartyHost)
            assertTrue(l.tileUrl(1, 0, 0).contains(l.thirdPartyHost!!))
            assertTrue(l.attribution.isNotBlank())
        }
        assertEquals(MapLayer.entries.size, MapLayer.entries.map { it.cacheFolder }.toSet().size)
        assertEquals(MapLayer.entries.size, MapLayer.entries.map { it.prefValue }.toSet().size)
    }

    @Test
    fun `every map in the app opens on the chosen layer`() {
        for (f in listOf("MapScreen.kt", "MapNavigationScreen.kt", "ItineraryScreen.kt")) {
            val src = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/$f")
            assertFalse("$f still hard-codes the standard source", src.contains("setTileSource(com.oshi.messenger.service.map.OshiTileSource.SOURCE)"))
            assertTrue("$f must build its provider for a layer", Regex("OshiOfflineTileProvider\\.create\\([^)]*,[^)]*,[^)]*\\)").containsMatchIn(src))
        }
    }
}
