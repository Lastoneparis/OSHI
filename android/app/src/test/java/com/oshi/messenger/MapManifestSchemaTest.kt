package com.oshi.messenger

import com.oshi.messenger.data.model.MapManifest
import com.oshi.messenger.data.model.MapRegion
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Region-manifest schema, pinned against a REAL capture of the live CDN.
 *
 * Fixture: `app/src/test/resources/maps_v2_manifest_trimmed.json`, four regions
 * lifted verbatim (no field edits) from
 * `GET https://oshi-messenger.com/maps/v2/manifest.json`
 * — fetched 2026-08-13, HTTP 200, 292 726 B, `{version:1, regions:[234]}`.
 *
 * The four were chosen to cover every shape the wire actually produces:
 *   fr-idf      sub-region, NO roadgraph, shared streets + shared routing
 *   de-by       sub-region, NO roadgraph (de-* is the other 404 family)
 *   gb-eng-lon  sub-region WITH a (shared) roadgraph
 *   gb          country region, all five layers, none shared
 */
class MapManifestSchemaTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("maps_v2_manifest_trimmed.json")) {
            "manifest fixture missing"
        }.bufferedReader().readText()

    private fun manifest(): MapManifest = json.decodeFromString(fixture())

    private fun region(id: String): MapRegion =
        manifest().regions.first { it.id == id }

    @Test
    fun `decodes the live manifest envelope`() {
        val m = manifest()
        assertEquals(1, m.version)
        // Server key is `updated`, NOT `updated_at` (the old model's name, which
        // never appeared on the wire and therefore always defaulted to "").
        assertEquals("2026-08-13T02:24:38Z", m.updated)
        assertEquals(4, m.regions.size)
    }

    @Test
    fun `bbox is minLon minLat maxLon maxLat`() {
        val fr = region("fr-idf")
        assertEquals(listOf(1.44, 48.12, 3.56, 49.24), fr.bbox)
        // Ile-de-France really is lon 1.44..3.56, lat 48.12..49.24 — reading the
        // array as lat-first would put the region in the Gulf of Guinea.
        assertEquals(1.44, fr.minLon, 1e-9)
        assertEquals(48.12, fr.minLat, 1e-9)
        assertEquals(3.56, fr.maxLon, 1e-9)
        assertEquals(49.24, fr.maxLat, 1e-9)
        assertEquals(48.68, fr.centerLat, 1e-9)
        assertEquals(2.50, fr.centerLon, 1e-9)
    }

    @Test
    fun `every live region carries a usable bbox`() {
        val m = manifest()
        assertEquals(m.regions.size, m.validRegions.size)
        m.regions.forEach { assertTrue(it.id, it.hasValidBBox) }
    }

    @Test
    fun `contains resolves a point to its region`() {
        // Paris.
        assertTrue(region("fr-idf").contains(48.8566, 2.3522))
        assertFalse(region("fr-idf").contains(51.5074, -0.1278))
        // London falls inside both gb and gb-eng-lon.
        assertTrue(region("gb").contains(51.5074, -0.1278))
        assertTrue(region("gb-eng-lon").contains(51.5074, -0.1278))
    }

    @Test
    fun `poi layer carries a real url size and digest`() {
        val poi = requireNotNull(region("fr-idf").files.poi)
        assertEquals("https://oshi-messenger.com/maps/v2/fr-idf/poi.db", poi.url)
        assertEquals(31_191_040L, poi.size)
        assertEquals(
            "51c121484cc16b76b5618e88e1c07c45c039d6220c508ee3039ce707c98812e1",
            poi.sha256
        )
        assertTrue(poi.isAvailable)
        assertNotNull(poi.verifiableSha256)
    }

    @Test
    fun `tiles layer is a placeholder and must never be gated on or fetched`() {
        val tiles = requireNotNull(region("fr-idf").files.tiles)
        // The server emits a {z}/{x}/{y} template, size 0 and 64 zeros.
        assertTrue(tiles.url.contains("{z}"))
        assertEquals(0L, tiles.size)
        assertEquals("0".repeat(64), tiles.sha256)
        // 64 zeros is not a digest — treating it as one would "verify" anything.
        assertNull(tiles.verifiableSha256)
        // size 0 with no shared_size means "the server never built this".
        assertFalse(tiles.isAvailable)
    }

    @Test
    fun `shared artifacts report size zero and carry the real count in shared_size`() {
        val streets = requireNotNull(region("fr-idf").files.streets)
        // A French sub-region points at the ONE country streets db.
        assertEquals("https://oshi-messenger.com/files/streets/fr-streets.db.gz", streets.url)
        assertEquals(0L, streets.size)
        assertEquals(true, streets.shared)
        assertEquals(78_470_916L, streets.sharedSize)
        assertEquals(78_470_916L, streets.realSize)
        assertTrue(streets.isAvailable)
        // ...so the region's displayed (incremental) size excludes it.
        assertEquals(0L + 0L + 31_191_040L, region("fr-idf").fileSize)
    }

    @Test
    fun `roadgraph is optional and absent for the fr and de families`() {
        // 79 of the live 234 regions have no roadgraph entry; fr-* and de-* are
        // all of them, and GET /files/roadgraph/fr-roadgraph.db.gz is a 404.
        assertNull(region("fr-idf").files.roadgraph)
        assertNull(region("de-by").files.roadgraph)

        val gb = requireNotNull(region("gb").files.roadgraph)
        assertEquals("https://oshi-messenger.com/files/roadgraph/gb-roadgraph.db.gz", gb.url)
        assertEquals(4_538_059_059L, gb.realSize)
        assertTrue(gb.isAvailable)

        // A sub-region inherits the country file as a shared artifact.
        val lon = requireNotNull(region("gb-eng-lon").files.roadgraph)
        assertEquals(true, lon.shared)
        assertEquals(4_538_059_059L, lon.realSize)
    }

    @Test
    fun `country prefix drives the country level fallback`() {
        assertEquals("fr", region("fr-idf").countryPrefix)
        assertEquals("gb", region("gb-eng-lon").countryPrefix)
        // A country region is its own prefix — there is no second attempt to make.
        assertEquals("gb", region("gb").countryPrefix)
    }

    /**
     * THE REGRESSION. The pre-2026-08-13 model declared `min_lat`/`max_lat`/
     * `min_lon`/`max_lon`/`tile_url`/`poi_url`, all defaulted, with
     * `ignoreUnknownKeys = true`. Feeding it the real manifest DECODED FINE into
     * 234 regions whose bbox was 0,0,0,0 — so the "fetch failed or regions empty"
     * fallback never fired, 8 ocean tiles were downloaded, and the region was
     * then marked DOWNLOADED.
     *
     * `validRegions` is the gate that makes that impossible: a payload in the old
     * shape now yields ZERO usable regions, so the caller falls back instead of
     * caching garbage.
     */
    @Test
    fun `legacy shaped payload yields no usable regions instead of zero bbox ones`() {
        val legacy = """
            {"version":1,"updated_at":"2026-04-01T00:00:00Z","regions":[
              {"id":"fr-idf","name":"Ile-de-France","continent":"Europe","country":"France",
               "min_lat":48.1,"max_lat":49.2,"min_lon":1.4,"max_lon":3.6,
               "tile_url":"x","poi_url":"y","sha256":"","min_zoom":1,"max_zoom":16}
            ]}
        """.trimIndent()
        val m = json.decodeFromString<MapManifest>(legacy)
        assertEquals(1, m.regions.size)          // it still decodes...
        assertTrue(m.regions[0].bbox.isEmpty())  // ...with no bbox at all
        assertFalse(m.regions[0].hasValidBBox)
        assertEquals(0, m.validRegions.size)     // and is therefore refused
    }

    @Test
    fun `degenerate bboxes are refused`() {
        fun r(bbox: List<Double>) = MapRegion(id = "x", name = "x", bbox = bbox)
        assertFalse(r(emptyList()).hasValidBBox)
        assertFalse(r(listOf(0.0, 0.0, 0.0, 0.0)).hasValidBBox)   // the 234-region bug
        assertFalse(r(listOf(1.0, 2.0, 3.0)).hasValidBBox)        // short
        assertFalse(r(listOf(1.0, 2.0, 1.0, 5.0)).hasValidBBox)   // zero width
        assertFalse(r(listOf(1.0, 2.0, 5.0, 2.0)).hasValidBBox)   // zero height
        assertFalse(r(listOf(Double.NaN, 2.0, 5.0, 6.0)).hasValidBBox)
        assertTrue(r(listOf(1.0, 2.0, 5.0, 6.0)).hasValidBBox)
    }
}
