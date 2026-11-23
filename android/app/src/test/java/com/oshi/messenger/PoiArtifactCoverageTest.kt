package com.oshi.messenger

import com.oshi.messenger.data.model.MapFile
import com.oshi.messenger.data.model.MapManifest
import com.oshi.messenger.data.model.MapRegion
import com.oshi.messenger.data.model.MapRegionFiles
import com.oshi.messenger.service.map.PoiCoverage
import com.oshi.messenger.service.map.PoiCoverage.EmptyResultReason
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 22 of the 254 live map regions ship **no POI artifact at all**.
 *
 * ## The defect
 *
 * Measured off `GET https://oshi-messenger.com/maps/v2/manifest.json` on
 * 2026-08-23 (200, 321 996 B, 254 regions): `us`, `jp`, `br`, `ca`, `ru`, `id`
 * and 16 US/BR/JP metros carry a `poi` entry whose `size` is 0 with no
 * `shared_size` — the shape `regenerate_manifest.py` emits for a layer it could
 * not resolve. `OfflineMapManager.downloadRegion` correctly skips it, the region
 * reports a **successful** download, and place search inside it then returns
 * nothing **for ever**, rendered identically to "your query matched nothing".
 *
 * ## Fixtures — neither is produced by the code under test
 *
 * * `app/src/test/resources/maps_v2_manifest_2026_08_23.json` — five regions
 *   lifted verbatim from the live response, no field edited.
 * * Hand-written [MapRegion] literals below, spelled out field by field.
 *
 * Nothing here asks `PoiCoverage` to build its own input.
 */
class PoiArtifactCoverageTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun liveRegions(): List<MapRegion> {
        val text = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("maps_v2_manifest_2026_08_23.json")
        ) { "2026-08-23 manifest fixture missing" }.bufferedReader().readText()
        return json.decodeFromString<MapManifest>(text).regions
    }

    private fun live(id: String): MapRegion = liveRegions().first { it.id == id }

    // ───────────────────────────────────────────────────────────────────
    // hasPoiArtifact, against bytes the server really sent
    // ───────────────────────────────────────────────────────────────────

    /**
     * `us` and `id` are two of the 22. Their `poi` entry exists — url and all —
     * and is worthless: `size: 0`, no `shared_size`.
     */
    @Test
    fun `the live regions with no POI artifact are recognised as having none`() {
        for (id in listOf("us", "id")) {
            val r = live(id)
            assertTrue("fixture drift: $id should still declare a poi entry", r.files.poi != null)
            assertEquals("fixture drift: $id poi.size", 0L, r.files.poi!!.size)
            assertFalse(
                "$id ships no POI artifact and must be reported as such",
                PoiCoverage.hasPoiArtifact(r)
            )
        }
    }

    @Test
    fun `a region with a real POI file is not slandered`() {
        for (id in listOf("fr-idf", "gb-eng-lon")) {
            val r = live(id)
            assertTrue("fixture drift: $id poi.size", r.files.poi!!.size > 0)
            assertTrue(
                "$id has a real POI database and must not be flagged",
                PoiCoverage.hasPoiArtifact(r)
            )
        }
    }

    /**
     * The trap a naive `poi.size > 0` check falls into. `in-blr` inherits India's
     * 72 MB POI database through `shared_size`, so its own `size` is 0 — but the
     * file exists and the downloader fetches it. Reporting Bangalore as
     * POI-less would be a fresh lie in place of the old one.
     */
    @Test
    fun `a SHARED POI artifact with size 0 still counts as present`() {
        val r = live("in-blr")
        val poi = requireNotNull(r.files.poi)
        assertEquals("fixture drift: in-blr poi.size", 0L, poi.size)
        assertEquals("fixture drift: in-blr shared_size", 72732672L, poi.sharedSize ?: -1L)
        assertTrue(
            "in-blr inherits India's POI database — it is NOT one of the 22",
            PoiCoverage.hasPoiArtifact(r)
        )
    }

    /**
     * The census over the whole fixture, as a count rather than five separate
     * calls — the number is what the report claims, so the number is what is
     * pinned.
     */
    @Test
    fun `exactly two of the five fixture regions ship no POI artifact`() {
        val missing = PoiCoverage.regionsWithoutPoiArtifact(liveRegions()).map { it.id }
        assertEquals(listOf("us", "id"), missing)
    }

    /**
     * The predicate must be the SAME one the downloader branches on
     * (`OfflineMapManager.kt`: `if (poiFile != null && poiFile.isAvailable)`).
     * If they drift, the UI promises POI search for a file that was never
     * fetched — the exact class of lie being fixed here.
     */
    @Test
    fun `the UI predicate and the downloader predicate are the same predicate`() {
        val downloader = File("src/main/java/com/oshi/messenger/service/OfflineMapManager.kt")
        assertTrue("not found: ${downloader.absolutePath}", downloader.exists())
        assertTrue(
            "the downloader no longer gates the POI fetch on MapFile.isAvailable — " +
                "PoiCoverage.hasPoiArtifact would then be describing a different world",
            downloader.readText().contains("poiFile.isAvailable")
        )
        for (r in liveRegions()) {
            assertEquals(
                "hasPoiArtifact disagrees with the downloader for ${r.id}",
                r.files.poi != null && r.files.poi!!.isAvailable,
                PoiCoverage.hasPoiArtifact(r)
            )
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // Hand-built regions — the edges the live five do not cover
    // ───────────────────────────────────────────────────────────────────

    private fun regionWithPoi(id: String) = MapRegion(
        id = id,
        name = id.uppercase(),
        bbox = listOf(1.0, 48.0, 3.0, 49.0),
        files = MapRegionFiles(
            poi = MapFile(url = "https://example.invalid/$id/poi.db", size = 1_000_000L)
        )
    )

    private fun regionWithoutPoi(id: String) = MapRegion(
        id = id,
        name = id.uppercase(),
        bbox = listOf(1.0, 48.0, 3.0, 49.0),
        files = MapRegionFiles(
            poi = MapFile(url = "https://example.invalid/$id/poi.db", size = 0L)
        )
    )

    private fun regionWithNoPoiKeyAtAll(id: String) = MapRegion(
        id = id,
        name = id.uppercase(),
        bbox = listOf(1.0, 48.0, 3.0, 49.0),
        files = MapRegionFiles(poi = null)
    )

    @Test
    fun `an absent poi key is treated the same as an empty one`() {
        assertFalse(PoiCoverage.hasPoiArtifact(regionWithNoPoiKeyAtAll("zz")))
    }

    /** A `poi` entry with a size but no url is not a downloadable artifact. */
    @Test
    fun `a poi entry with no url does not count`() {
        val r = MapRegion(
            id = "nourl",
            name = "NoUrl",
            files = MapRegionFiles(poi = MapFile(url = "", size = 9_000_000L))
        )
        assertFalse(PoiCoverage.hasPoiArtifact(r))
    }

    @Test
    fun `only the DOWNLOADED regions are reported, not the whole manifest`() {
        val regions = listOf(
            regionWithoutPoi("us"),
            regionWithoutPoi("jp"),
            regionWithPoi("fr-idf")
        )
        val reported = PoiCoverage
            .downloadedRegionsWithoutPoiArtifact(regions, listOf("us"))
            .map { it.id }
        assertEquals(
            "jp has no POI data but the user never downloaded it — saying so would be noise",
            listOf("us"),
            reported
        )
    }

    /**
     * A downloaded id the manifest does not describe is UNKNOWN, not guilty.
     * Claiming "this region has no places database" about a region we cannot look
     * up would be a fabricated explanation, which is the failure mode this whole
     * pass exists to remove.
     */
    @Test
    fun `a downloaded id missing from the manifest is never accused`() {
        val regions = listOf(regionWithPoi("fr-idf"))
        assertEquals(
            emptyList<String>(),
            PoiCoverage.downloadedRegionsWithoutPoiArtifact(regions, listOf("atlantis"))
                .map { it.id }
        )
    }

    // ───────────────────────────────────────────────────────────────────
    // classifyEmptyResult — four empties, four different messages
    // ───────────────────────────────────────────────────────────────────

    @Test
    fun `nothing downloaded is its own reason`() {
        assertEquals(
            EmptyResultReason.NO_REGION_DOWNLOADED,
            PoiCoverage.classifyEmptyResult(listOf(regionWithPoi("fr-idf")), emptyList())
        )
    }

    @Test
    fun `an empty result over a POI-less region is NOT a plain miss`() {
        val reason = PoiCoverage.classifyEmptyResult(
            regions = listOf(regionWithoutPoi("us"), regionWithPoi("fr-idf")),
            downloadedRegionIds = listOf("us")
        )
        assertEquals(EmptyResultReason.NO_POI_ARTIFACT, reason)
        assertTrue(
            "the whole defect is that this rendered the same as a genuine miss",
            reason != EmptyResultReason.NO_MATCH
        )
    }

    @Test
    fun `an empty result over a region that HAS POI data is a plain miss`() {
        assertEquals(
            EmptyResultReason.NO_MATCH,
            PoiCoverage.classifyEmptyResult(
                regions = listOf(regionWithPoi("fr-idf")),
                downloadedRegionIds = listOf("fr-idf")
            )
        )
    }

    @Test
    fun `a mixed set is reported as partial, not as total absence`() {
        assertEquals(
            EmptyResultReason.PARTIAL_POI_ARTIFACT,
            PoiCoverage.classifyEmptyResult(
                regions = listOf(regionWithoutPoi("us"), regionWithPoi("fr-idf")),
                downloadedRegionIds = listOf("us", "fr-idf")
            )
        )
    }

    /**
     * One known-bad region plus one region we cannot resolve must not harden into
     * the absolute claim "none of your regions has POI data".
     */
    @Test
    fun `an unresolvable id downgrades the verdict from absolute to partial`() {
        assertEquals(
            EmptyResultReason.PARTIAL_POI_ARTIFACT,
            PoiCoverage.classifyEmptyResult(
                regions = listOf(regionWithoutPoi("us")),
                downloadedRegionIds = listOf("us", "atlantis")
            )
        )
    }

    /** A duplicated id must not skew the "all of them" arithmetic. */
    @Test
    fun `duplicate downloaded ids do not change the verdict`() {
        assertEquals(
            EmptyResultReason.NO_POI_ARTIFACT,
            PoiCoverage.classifyEmptyResult(
                regions = listOf(regionWithoutPoi("us")),
                downloadedRegionIds = listOf("us", "us", "us")
            )
        )
    }

    /**
     * Real bytes, not literals: `us` downloaded on its own is the Seattle /
     * Tokyo / São Paulo / Toronto / Moscow case from the report.
     */
    @Test
    fun `the live us region alone classifies as NO_POI_ARTIFACT`() {
        assertEquals(
            EmptyResultReason.NO_POI_ARTIFACT,
            PoiCoverage.classifyEmptyResult(liveRegions(), listOf("us"))
        )
        assertEquals(
            EmptyResultReason.NO_MATCH,
            PoiCoverage.classifyEmptyResult(liveRegions(), listOf("fr-idf"))
        )
    }

    // ───────────────────────────────────────────────────────────────────
    // The condition has to reach a screen, or it is not a fix
    // ───────────────────────────────────────────────────────────────────

    private fun source(path: String): String {
        val f = File(path)
        assertTrue("not found: ${f.absolutePath}", f.exists())
        return f.readText()
    }

    /**
     * `MapViewModel` already exposed `routeError` that no screen read, and the
     * refusal was invisible for months. Not repeating that: the reason code must
     * be produced by the view model AND consumed by both screens.
     */
    @Test
    fun `the reason is produced and actually rendered`() {
        val vm = source("src/main/java/com/oshi/messenger/ui/viewmodel/MapViewModel.kt")
        assertTrue("MapViewModel must expose the reason", vm.contains("val poiEmptyReason"))
        assertTrue("…and recompute it when a search finishes", vm.contains("refreshPoiCoverage("))

        val search = source("src/main/java/com/oshi/messenger/ui/screens/MapSearchScreen.kt")
        assertTrue(
            "an exposed reason nothing collects is a dead feature",
            search.contains("viewModel.poiEmptyReason")
        )
        for (key in listOf(
            "map_search_no_region_downloaded",
            "map_search_no_poi_data",
            "map_search_partial_poi_data"
        )) {
            assertTrue("MapSearchScreen never renders $key", search.contains(key))
        }

        val regions = source("src/main/java/com/oshi/messenger/ui/screens/MapRegionDownloadScreen.kt")
        assertTrue(
            "the region's own card must state the limitation before the download",
            regions.contains("PoiCoverage.hasPoiArtifact(region)") &&
                regions.contains("map_region_no_poi_search")
        )
    }

    /**
     * No fabricated fallback. The routing engine's 3-step 8 991 km straight line
     * is the precedent; a POI "fallback" that invents places would be worse.
     */
    @Test
    fun `nothing pretends to work when the artifact is absent`() {
        val strings = source("src/main/res/values/strings_map_offline_round5.xml")
        assertTrue(
            "the message must say retrying will not help",
            strings.contains("retrying won\\'t help") || strings.contains("retrying won&apos;t help")
        )
        val coverage = source("src/main/java/com/oshi/messenger/service/map/PoiCoverage.kt")
        assertFalse(
            "PoiCoverage must not acquire a synthesise/fabricate path",
            coverage.contains("fun synthesi") || coverage.contains("fun fabricate")
        )
    }
}
