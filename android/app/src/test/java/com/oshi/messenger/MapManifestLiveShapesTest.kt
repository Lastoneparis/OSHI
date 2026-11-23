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
 * Manifest shapes that only appeared on the wire AFTER the fixture
 * `MapManifestSchemaTest` pins (a 2026-08-13 capture of 234 regions).
 *
 * ## Provenance
 *
 * Fixture: `app/src/test/resources/maps_v2_manifest_2026_08_23.json` — five
 * regions lifted **verbatim, no field edited**, from
 * `GET https://oshi-messenger.com/maps/v2/manifest.json`, fetched 2026-08-23,
 * HTTP 200, 321 996 B, `{version: 1, updated: "2026-08-23T02:21:53Z",
 * regions: [254]}`.
 *
 * Census of that live response, which is what the five regions were chosen to
 * represent:
 *
 * | property                                    | count of 254 |
 * |---------------------------------------------|--------------|
 * | has `roadgraph`                             | 175          |
 * | **no** `roadgraph` (offline routing absent) | **79**       |
 * | has `streets`                               | 160          |
 * | **no usable `poi` artifact at all**         | **22**       |
 * | every layer reports 0 usable bytes          | 12           |
 * | degenerate / short bbox                     | 0            |
 *
 * The five:
 *
 * | id           | why it is here |
 * |--------------|----------------|
 * | `us`         | THREE layers only, every one `size: 0` with an all-zero sha256 — the "0 MB" trap |
 * | `id`         | usable streets + a 5.5 GB roadgraph, but **no** POI artifact |
 * | `fr-idf`     | the 79-region no-roadgraph family, shared streets + shared routing |
 * | `gb-eng-lon` | a shared roadgraph (`shared_size` 4.5 GB, `size` 0) |
 * | `in-blr`     | the largest total in the manifest (~11.9 GB) |
 *
 * ## What this is defending
 *
 * The pre-2026-08 Android model shared ZERO field names with the server, and
 * because every field carried a default plus `ignoreUnknownKeys = true`, the
 * decode **succeeded** — into 234 all-zero regions. The hardcoded fallback
 * therefore never fired and the tile downloader walked a 0,0 bbox. A decode
 * that "works" is not evidence; only asserting real values off real bytes is.
 */
class MapManifestLiveShapesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun manifest(): MapManifest {
        val text = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("maps_v2_manifest_2026_08_23.json")
        ) { "2026-08-23 manifest fixture missing" }.bufferedReader().readText()
        return json.decodeFromString(text)
    }

    private fun region(id: String): MapRegion = manifest().regions.first { it.id == id }

    // ───────────────────────────────────────────────────────────────────
    // The decode carries real values, not defaults
    // ───────────────────────────────────────────────────────────────────

    @Test
    fun `the envelope decodes with the servers own key names`() {
        val m = manifest()
        assertEquals(1, m.version)
        // `updated`, not `updated_at` — the legacy name never appeared on the wire.
        assertEquals("2026-08-23T02:21:53Z", m.updated)
        assertEquals(5, m.regions.size)
    }

    /**
     * The all-zero-regions bug in one assertion: if the model ever drifts from
     * the wire again, these become 0 / "" / empty and this test says so.
     */
    @Test
    fun `no region decodes to an all-zero shell`() {
        val m = manifest()
        assertEquals("every region must keep a usable bbox", 5, m.validRegions.size)
        m.regions.forEach { r ->
            assertTrue("id lost for $r", r.id.isNotBlank())
            assertTrue("name lost for ${r.id}", r.name.isNotBlank())
            assertTrue("country lost for ${r.id}", r.country.isNotBlank())
            assertTrue("continent lost for ${r.id}", r.continent.isNotBlank())
            assertEquals("bbox lost for ${r.id}", 4, r.bbox.size)
            assertTrue("bbox degenerate for ${r.id}", r.hasValidBBox)
            assertTrue("osmDate lost for ${r.id}", r.osmDate.isNotBlank())
            assertNotNull("tiles key lost for ${r.id}", r.files.tiles)
            assertNotNull("poi key lost for ${r.id}", r.files.poi)
        }
    }

    @Test
    fun `bbox is minLon minLat maxLon maxLat, not lat-first`() {
        val us = region("us")
        assertEquals(listOf(-125.0, 24.4, -66.93, 49.39), us.bbox)
        // Read lat-first this would place the continental United States in the
        // Southern Ocean and every downloaded tile would be open water.
        assertEquals(-125.0, us.minLon, 1e-9)
        assertEquals(24.4, us.minLat, 1e-9)
        assertTrue(us.centerLat in 24.4..49.39)
        assertTrue(us.centerLon in -125.0..-66.93)
        assertTrue(us.contains(38.9, -77.0))       // Washington DC
        assertFalse(us.contains(48.86, 2.35))      // Paris
    }

    // ───────────────────────────────────────────────────────────────────
    // "0 MB" must never be shown as a confident number
    // ───────────────────────────────────────────────────────────────────

    /**
     * 12 of the 254 live regions report no measurable artifact on ANY layer.
     * `us` is one. The UI must fall back to "size unavailable" rather than
     * printing a confident "0 MB" next to a card promising the full map.
     */
    @Test
    fun `a region whose every layer is zero reports an unknown size`() {
        val us = region("us")
        assertEquals(0L, us.fileSize)
        assertFalse("us must not claim a known size", us.hasKnownSize)
        // Nothing is shared either, so there is no "+N GB shared" to soften it.
        assertEquals("", us.sharedSizeFormatted)
    }

    /**
     * The counterpart: a region with real bytes must report them, and must
     * include streets + roadgraph. Counting only tiles+routing+poi (the iOS
     * sum) under-reported Algeria by ~100x.
     */
    @Test
    fun `a sized region counts streets and roadgraph, not just poi`() {
        val id = region("id")
        val files = id.files
        assertEquals(12_755_571L, files.streets?.size)
        assertEquals(5_512_829_982L, files.roadgraph?.size)
        // poi/tiles/routing are all 0 here, so the whole figure IS streets+roadgraph.
        assertEquals(12_755_571L + 5_512_829_982L, id.fileSize)
        assertTrue(id.hasKnownSize)
        // Had the sum stopped at poi, this region would have displayed "0 MB"
        // while actually downloading 5.5 GB.
        assertTrue("under-reporting regression", id.fileSize > 5_000_000_000L)
    }

    /**
     * A shared artifact reports `size: 0` and `shared_size: N`. It must not be
     * added to the per-region total (it is fetched once per country) but it
     * must be disclosed separately, or the user is surprised by 4.5 GB.
     */
    @Test
    fun `a shared roadgraph is excluded from the total but disclosed`() {
        val lon = region("gb-eng-lon")
        val rg = requireNotNull(lon.files.roadgraph)
        assertEquals(0L, rg.size)
        assertEquals(4_538_059_059L, rg.sharedSize)
        assertEquals("realSize must fall back to shared_size", 4_538_059_059L, rg.realSize)
        assertTrue("a shared artifact is still available", rg.isAvailable)
        // Excluded from fileSize...
        assertEquals(0L, lon.files.roadgraph?.size)
        // ...but surfaced to the user.
        assertTrue(
            "shared bytes must be disclosed, got '${lon.sharedSizeFormatted}'",
            lon.sharedSizeFormatted.isNotBlank()
        )
    }

    // ───────────────────────────────────────────────────────────────────
    // Hashes: what may and may not be gated on
    // ───────────────────────────────────────────────────────────────────

    /**
     * The server writes 64 zeros for the placeholder `tiles` entry on all 254
     * regions. Treating that as a digest would mean "verified" for any file
     * whose hash happened to be zeros — which is exactly how a `verifyFile`
     * once returned `true` on a blank hash.
     */
    @Test
    fun `an all-zero sha256 is not a verifiable hash`() {
        manifest().regions.forEach { r ->
            val tiles = requireNotNull(r.files.tiles) { "tiles key missing on ${r.id}" }
            assertEquals(64, tiles.sha256.length)
            assertTrue("expected zeros on ${r.id}", tiles.sha256.all { it == '0' })
            assertNull("all-zero hash must not be gateable on ${r.id}", tiles.verifiableSha256)
        }
    }

    @Test
    fun `a real digest survives as a verifiable hash`() {
        val streets = requireNotNull(region("id").files.streets)
        assertEquals(
            "85510dad9207bb408dd1948f9552761087936b895b0eab1bc9fdb0c0ee8b8a08",
            streets.verifiableSha256
        )
    }

    // ───────────────────────────────────────────────────────────────────
    // Absent layers are a normal state, not an error
    // ───────────────────────────────────────────────────────────────────

    /**
     * 79 of 254 regions — every `fr-*` and `de-*` — ship no roadgraph, and
     * `fr-roadgraph.db.gz` 404s. `roadgraph == null` must decode cleanly;
     * making the layer required would sink the WHOLE manifest into the drop
     * branch on the strength of one absent key.
     */
    @Test
    fun `a region with no roadgraph decodes rather than failing the manifest`() {
        val fr = region("fr-idf")
        assertNull("fr-idf must have no roadgraph", fr.files.roadgraph)
        assertNotNull(fr.files.streets)
        assertNotNull(fr.files.routing)
        // ...and the other four regions still decoded alongside it.
        assertEquals(5, manifest().regions.size)
    }

    /**
     * 22 of 254 regions — all of `us-*`, `jp-*`, `br-*`, plus `ca`, `ru`, `id`
     * — carry a `poi` KEY whose artifact does not exist (`size: 0`, no
     * `shared_size`). `isAvailable` must be false so the downloader skips it
     * instead of fetching a 0-byte file and reporting the region broken.
     *
     * The user-visible consequence is recorded here deliberately: these
     * regions download successfully and then find nothing when searched,
     * because there is no POI database to search.
     */
    @Test
    fun `a poi key with no artifact behind it is not available`() {
        listOf("us", "id").forEach { rid ->
            val poi = requireNotNull(region(rid).files.poi) { "$rid lost its poi key" }
            assertTrue("$rid poi url should still be present", poi.url.isNotBlank())
            assertEquals(0L, poi.realSize)
            assertFalse("$rid must not be treated as having POI data", poi.isAvailable)
            assertEquals("", poi.sizeFormatted)
        }
        // Contrast: fr-idf has a real 31 MB POI database.
        val fr = requireNotNull(region("fr-idf").files.poi)
        assertEquals(31_191_040L, fr.realSize)
        assertTrue(fr.isAvailable)
    }

    /**
     * `countryPrefix` is what the country-level artifact fallback keys off. It
     * must survive both a two-segment and a three-segment id, or a London user
     * would be sent looking for `gb-eng-roadgraph.db.gz`.
     */
    @Test
    fun `countryPrefix takes the first segment of any id depth`() {
        assertEquals("gb", region("gb-eng-lon").countryPrefix)
        assertEquals("fr", region("fr-idf").countryPrefix)
        assertEquals("in", region("in-blr").countryPrefix)
        assertEquals("us", region("us").countryPrefix)
        assertEquals("id", region("id").countryPrefix)
    }
}
