package com.oshi.messenger

import com.oshi.messenger.data.model.MapFile
import com.oshi.messenger.data.model.MapManifest
import com.oshi.messenger.data.model.MapRegion
import com.oshi.messenger.service.OfflineStreetDatabase
import com.oshi.messenger.service.map.OfflineRoutingManager
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which URL each artifact is actually fetched from, and when Overpass is allowed
 * to run at all.
 *
 * Every URL asserted here was probed against the live server on 2026-08-13:
 *   /files/streets/fr-idf-streets.db.gz   → 404
 *   /files/streets/fr-streets.db.gz       → 200   (what the manifest advertises)
 *   /files/roadgraph/fr-roadgraph.db.gz   → 404
 *   /files/roadgraph/gb-roadgraph.db.gz   → 200
 *   /maps/v2/routing/manifest.json        → 404   (the deleted Android-only one)
 */
class MapArtifactUrlResolutionTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun regions(): List<MapRegion> {
        val text = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("maps_v2_manifest_trimmed.json")
        ).bufferedReader().readText()
        return json.decodeFromString<MapManifest>(text).regions
    }

    private fun region(id: String) = regions().first { it.id == id }

    // ── Streets ────────────────────────────────────────────────────────

    @Test
    fun `street index prefers the manifest url over the constructed one`() {
        val fr = region("fr-idf")
        val candidates = OfflineStreetDatabase.buildStreetUrlCandidates(fr.id, fr.files.streets)

        // FIRST candidate must be the manifest's country file, which is the only
        // one that 200s. The old code only ever built candidate #2.
        assertEquals(
            "https://oshi-messenger.com/files/streets/fr-streets.db.gz",
            candidates[0].first
        )
        assertEquals(fr.files.streets, candidates[0].second)

        assertEquals(
            "https://oshi-messenger.com/files/streets/fr-idf-streets.db.gz",
            candidates[1].first
        )
        // No digest to gate the constructed fallbacks on.
        assertNull(candidates[1].second)
    }

    @Test
    fun `street index falls back to the country file when the manifest has none`() {
        val candidates = OfflineStreetDatabase.buildStreetUrlCandidates("fr-idf", null)
        assertEquals(
            listOf(
                "https://oshi-messenger.com/files/streets/fr-idf-streets.db.gz",
                "https://oshi-messenger.com/files/streets/fr-streets.db.gz"
            ),
            candidates.map { it.first }
        )
    }

    @Test
    fun `a country region has no second constructed candidate`() {
        val candidates = OfflineStreetDatabase.buildStreetUrlCandidates("gb", null)
        assertEquals(1, candidates.size)
        assertEquals("https://oshi-messenger.com/files/streets/gb-streets.db.gz", candidates[0].first)
    }

    @Test
    fun `an unbuilt manifest entry is skipped rather than fetched`() {
        // size 0 with no shared_size is the manifest's way of saying "not built";
        // requesting it only buys a guaranteed 404.
        val unbuilt = MapFile(url = "https://example.invalid/x.db.gz", size = 0L, sha256 = "")
        val candidates = OfflineStreetDatabase.buildStreetUrlCandidates("fr-idf", unbuilt)
        assertFalse(candidates.any { it.first.contains("example.invalid") })
    }

    // ── Overpass gate ──────────────────────────────────────────────────

    @Test
    fun `overpass is refused for a zero bbox`() {
        // This is precisely what `synthesizeMapRegion` handed it on every call:
        // the region it built had no bbox, so Overpass was queried for
        // south=west=north=east=0 — the Gulf of Guinea.
        assertFalse(OfflineRoutingManager.isOverpassImportable(MapRegion(id = "fr", name = "France")))
        assertFalse(
            OfflineRoutingManager.isOverpassImportable(
                MapRegion(id = "fr", name = "France", bbox = listOf(0.0, 0.0, 0.0, 0.0))
            )
        )
    }

    @Test
    fun `overpass is refused for a country-scale bbox`() {
        // fr-idf is ~2.1° x 1.1°; France is ~14° x 10°.
        assertFalse(OfflineRoutingManager.isOverpassImportable(region("gb")))
        assertFalse(OfflineRoutingManager.isOverpassImportable(region("de-by")))
    }

    @Test
    fun `overpass is allowed for a metro-scale bbox`() {
        // Greater London: ~0.84° x 0.41°.
        assertTrue(OfflineRoutingManager.isOverpassImportable(region("gb-eng-lon")))
    }
}
