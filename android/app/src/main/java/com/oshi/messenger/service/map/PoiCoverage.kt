package com.oshi.messenger.service.map

import com.oshi.messenger.data.model.MapRegion

/**
 * PoiCoverage — "does this region actually ship a places database?", as a
 * first-class, stated condition instead of a silent permanent hole.
 *
 * ══ THE DEFECT THIS NAMES ══
 *
 * Measured off the live manifest on 2026-08-23
 * (`GET https://oshi-messenger.com/maps/v2/manifest.json`, 200, 321 996 B,
 * 254 regions): **22 of the 254 carry no usable `poi` artifact.** Their `poi`
 * entry exists but reports `size: 0` with no `shared_size`, which is exactly
 * what `regenerate_manifest.py` emits for a layer it could not resolve.
 *
 * The affected set: `us`, `jp`, `br`, `ca`, `ru`, `id` and 16 US/BR/JP metros.
 *
 * `OfflineMapManager.downloadRegion` asks `MapFile.isAvailable` and **skips** the
 * POI download when it is false — correctly, there is nothing to fetch. But the
 * region then reports a successful download, and POI search in it returns
 * nothing **for ever**, byte-identical to "your query matched nothing". A user in
 * Seattle, Tokyo, São Paulo, Toronto or Moscow downloads their region and gets a
 * map with no places on it and no explanation they can act on.
 *
 * ══ WHAT THIS DELIBERATELY DOES NOT DO ══
 *
 * It does not invent a fallback. This codebase has already been bitten by a
 * routing engine that synthesised a 3-step, 8 991 km straight line rather than
 * admit it had no graph. The honest move is to *say* the data is absent — the
 * only thing this file produces is a reason code for the UI to render.
 *
 * ══ ONE PREDICATE, NOT TWO ══
 *
 * [hasPoiArtifact] asks `region.files.poi?.isAvailable`, which is **the same
 * predicate the downloader branches on** (`OfflineMapManager.kt:300`). If the
 * two ever drifted, the UI would claim POI search works for a region whose POI
 * file was never fetched — the exact class of lie being fixed. It is
 * deliberately *not* `poi.size > 0`: `in-blr` inherits India's 72 MB POI file
 * through `shared_size` with `size: 0`, and a naive size check would slander it.
 */
object PoiCoverage {

    /**
     * True when the manifest advertises a POI artifact this app can actually
     * download for [region].
     *
     * Mirrors iOS `MapFile.isAvailable` (`MapModels.swift:132`) via
     * `MapModels.MapFile.isAvailable`, which handles both the regional
     * (`size > 0`) and inherited (`shared_size > 0`) shapes.
     */
    fun hasPoiArtifact(region: MapRegion): Boolean = region.files.poi?.isAvailable == true

    /** Every region in [regions] that ships no POI artifact, order preserved. */
    fun regionsWithoutPoiArtifact(regions: List<MapRegion>): List<MapRegion> =
        regions.filter { !hasPoiArtifact(it) }

    /**
     * The downloaded regions that will never answer a POI query.
     *
     * A downloaded id with **no** manifest entry is *not* reported: we cannot
     * tell whether it has POI data, and guessing "it has none" would put a false
     * accusation in front of the user. Silence about the unknown, never a claim.
     */
    fun downloadedRegionsWithoutPoiArtifact(
        regions: List<MapRegion>,
        downloadedRegionIds: Collection<String>
    ): List<MapRegion> {
        if (downloadedRegionIds.isEmpty()) return emptyList()
        val wanted = downloadedRegionIds.toSet()
        return regions.filter { it.id in wanted && !hasPoiArtifact(it) }
    }

    /**
     * Why a POI search came back with nothing. The whole point of the type: an
     * empty result set must not render identically in all four cases.
     */
    enum class EmptyResultReason {
        /** Nothing downloaded at all — offline search had no data to consult. */
        NO_REGION_DOWNLOADED,

        /** Every downloaded region ships without a places database. */
        NO_POI_ARTIFACT,

        /** Some downloaded regions have one, some do not — results may be partial. */
        PARTIAL_POI_ARTIFACT,

        /** The data was there and genuinely did not match. */
        NO_MATCH
    }

    /**
     * Classify an empty POI result.
     *
     * Only call this when the result set is actually empty; it says nothing about
     * whether a search succeeded.
     */
    fun classifyEmptyResult(
        regions: List<MapRegion>,
        downloadedRegionIds: Collection<String>
    ): EmptyResultReason {
        val ids = downloadedRegionIds.toSet()
        if (ids.isEmpty()) return EmptyResultReason.NO_REGION_DOWNLOADED
        val missing = downloadedRegionsWithoutPoiArtifact(regions, ids)
        if (missing.isEmpty()) return EmptyResultReason.NO_MATCH
        // A downloaded id absent from the manifest counts as "cannot tell": it is
        // not in `missing`, and it is also not allowed to sustain an
        // "everything is missing" verdict — so the strong claim requires that
        // every downloaded id both resolved AND lacked POI data.
        val resolvedIds = regions.map { it.id }.toSet()
        val allResolved = ids.all { it in resolvedIds }
        return if (allResolved && missing.size == ids.size) {
            EmptyResultReason.NO_POI_ARTIFACT
        } else {
            EmptyResultReason.PARTIAL_POI_ARTIFACT
        }
    }
}
