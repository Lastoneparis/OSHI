package com.oshi.messenger.service.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.oshi.messenger.data.model.MapFile
import com.oshi.messenger.data.model.MapRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OfflineRoutingManager (service.map) — installs the A* road graph, mirroring
 * iOS `OfflineRoadGraphDownloader.swift`.
 *
 * ══ WHAT CHANGED 2026-08-13, AND WHY ══
 *
 * This class used to download `/files/routing/{id}-routing.tar.gz` and hunt the
 * tar for an entry ending in `routing.db`. That artifact was fetched and opened
 * against the live CDN:
 *
 *   GET /files/routing/fr-routing.tar.gz → 200, 1 639 819 051 B
 *   gunzip | tar -t                      → ONE entry: `fr-latest.osrm` (2.7 GB)
 *
 * It is an OSRM binary. There is no `routing.db` in it and never was, so the old
 * path burned 1.64 GB of the user's data, extracted nothing, and returned false.
 *
 * iOS reads `region.files.roadgraph.url` (`OfflineRoadGraphDownloader.swift:111-112`)
 * — a bare single-member gzip of the SQLite graph — and that is what we do now.
 *
 * ══ THE ARTIFACT IS OPTIONAL ══
 *
 * Measured over the live manifest (234 regions): only **155** carry a `roadgraph`
 * entry. 79 do not — including every `fr-*` and every `de-*` — and
 * `GET /files/roadgraph/fr-roadgraph.db.gz` is a **404**. A missing road graph is
 * therefore a normal state, not a bug, and is surfaced as a soft error exactly
 * like iOS does (`:200-206`, `map.regions.roadgraph.unavailable_for_country`).
 *
 * Installed at: `context.filesDir/Maps/regions/{regionId}/routing.db`
 */
@Singleton
class OfflineRoutingManager @Inject constructor(
    private val context: Context,
    private val importer: RoadGraphImporter,
    private val router: OfflineRouter
) {
    companion object {
        private const val TAG = "OfflineRoutingManager(map)"

        /** Base for the country-level fallback filename. Matches iOS `Self.baseURL`. */
        private const val ROADGRAPH_BASE_URL = "https://oshi-messenger.com/files/roadgraph"
        private const val USER_AGENT = "OSHI-Messenger/1.0 (Android; offline routing)"

        /**
         * Overpass produces a graph by hammering a public API with a bbox query.
         * A country bbox would be an abusive request that also never returns, so
         * the last-resort import is capped to a small metro-sized box.
         */
        private const val OVERPASS_MAX_BBOX_DEGREES = 1.5

        /**
         * Whether it is legitimate to build this region's graph from Overpass.
         *
         * Pure, so a unit test can pin it. `false` for a zero bbox — the exact
         * input the old code supplied on every call, because the region it
         * synthesized had no bbox at all — and `false` for a country-scale box,
         * which would be an abusive Overpass query that never returns.
         */
        fun isOverpassImportable(region: MapRegion): Boolean {
            if (!region.hasValidBBox) return false
            val dLat = region.maxLat - region.minLat
            val dLon = region.maxLon - region.minLon
            return dLat > 0.0 && dLon > 0.0 &&
                dLat <= OVERPASS_MAX_BBOX_DEGREES && dLon <= OVERPASS_MAX_BBOX_DEGREES
        }
    }

    /** Why a region has no road graph, keyed by regionId. Rendered by the UI. */
    enum class RoadGraphError {
        /** The manifest has no `files.roadgraph` for this region, and no country file. */
        UNAVAILABLE_FOR_COUNTRY,

        /** Downloaded bytes failed their SHA-256 / gzip integrity check. */
        CHECKSUM_MISMATCH,

        /** Network / IO failure. */
        DOWNLOAD_FAILED,

        /**
         * __MAP_SPACE_GUARD_2026_10_21__ The graph provably will not fit, so
         * nothing was downloaded. Distinct from [DOWNLOAD_FAILED] on purpose:
         * "it failed" sends the user back to retry forever, where "it does not
         * fit" tells them the one thing that would change the outcome.
         */
        INSUFFICIENT_SPACE,
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder().header("User-Agent", USER_AGENT).build()
            )
        }
        .build()

    private val _isDownloading = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val isDownloading: StateFlow<Map<String, Boolean>> = _isDownloading.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, RoadGraphError>>(emptyMap())
    val errors: StateFlow<Map<String, RoadGraphError>> = _errors.asStateFlow()

    /** Whether a routing.db file exists and is non-empty for [regionId]. */
    fun isRoutingAvailable(regionId: String): Boolean {
        val f = router.roadGraphPath(regionId)
        return f.exists() && f.length() > 0
    }

    /**
     * Install the road graph for [region].
     *
     * @param allowOverpassFallback opt-in last resort. Off by default: it needs
     *   network at "offline map setup" time and, before this rewrite, it was the
     *   path that ALWAYS ran (the tar path always failed) and it was handed a
     *   synthesized region whose bbox was `0,0,0,0`, so it queried Overpass for
     *   the Gulf of Guinea. It is now refused outright unless the region carries
     *   a real, small bbox.
     */
    suspend fun downloadRouting(region: MapRegion, allowOverpassFallback: Boolean = false) {
        val regionId = region.id
        markDownloading(regionId, true)
        clearError(regionId)
        try {
            if (isRoutingAvailable(regionId)) {
                Log.d(TAG, "Region $regionId: road graph already installed")
                return
            }

            when (val outcome = downloadRoadGraph(region)) {
                InstallOutcome.INSTALLED -> {
                    Log.d(TAG, "Region $regionId: road graph installed")
                    return
                }
                InstallOutcome.CHECKSUM_MISMATCH -> {
                    setError(regionId, RoadGraphError.CHECKSUM_MISMATCH)
                    return
                }
                // __MAP_SPACE_GUARD_2026_10_21__ No Overpass fallback here: that
                // path builds a graph locally and would need the same space the
                // download was just refused for.
                InstallOutcome.INSUFFICIENT_SPACE -> {
                    setError(regionId, RoadGraphError.INSUFFICIENT_SPACE)
                    return
                }
                InstallOutcome.NOT_AVAILABLE, InstallOutcome.FAILED -> {
                    val err = if (outcome == InstallOutcome.NOT_AVAILABLE) {
                        RoadGraphError.UNAVAILABLE_FOR_COUNTRY
                    } else {
                        RoadGraphError.DOWNLOAD_FAILED
                    }
                    if (allowOverpassFallback && canImportFromOverpass(region)) {
                        Log.d(TAG, "Region $regionId: no server road graph, importing from Overpass")
                        importFromOverpass(region)
                        if (!isRoutingAvailable(regionId)) setError(regionId, err)
                    } else {
                        // iOS surfaces exactly this and stops
                        // (OfflineRoadGraphDownloader.swift:200-206).
                        Log.i(TAG, "Region $regionId: no road graph available ($err)")
                        setError(regionId, err)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Region $regionId: routing download failed: ${e.message}")
            setError(regionId, RoadGraphError.DOWNLOAD_FAILED)
        } finally {
            markDownloading(regionId, false)
        }
    }

    /** Convenience routing entry-point used by the legacy manager / VM. */
    suspend fun route(
        from: org.osmdroid.util.GeoPoint,
        to: org.osmdroid.util.GeoPoint,
        mode: com.oshi.messenger.data.model.RouteMode,
        regionId: String
    ) = router.route(from, to, mode, regionId)

    // ───────────────────────────────────────────────────────────────────
    // roadgraph.db.gz path
    // ───────────────────────────────────────────────────────────────────

    private enum class InstallOutcome {
        INSTALLED, NOT_AVAILABLE, CHECKSUM_MISMATCH, FAILED,
        /** __MAP_SPACE_GUARD_2026_10_21__ Refused before any bytes were spent. */
        INSUFFICIENT_SPACE,
    }

    /**
     * One attempt at the manifest URL, then (on 404 only) one attempt at the
     * country-level file — same two-step as iOS `attempt(...)`
     * (`OfflineRoadGraphDownloader.swift:148-184`).
     */
    private suspend fun downloadRoadGraph(region: MapRegion): InstallOutcome =
        withContext(Dispatchers.IO) {
            val regionId = region.id
            val manifestFile: MapFile? = region.files.roadgraph

            // `isAvailable` is the manifest's contract for "the server actually
            // built this" — size 0 with no shared_size means it did not
            // (iOS MapModels.swift:126-132). Firing the request anyway just buys
            // a guaranteed 404.
            val primaryUrl = manifestFile
                ?.takeIf { it.isAvailable }
                ?.url
                ?: run {
                    // No usable manifest entry. iOS still tries the constructed
                    // regional name (`:112`) because a manifest can lag the CDN.
                    "$ROADGRAPH_BASE_URL/$regionId-roadgraph.db.gz"
                }

            val staged = File(router.roadGraphPath(regionId).parentFile, "roadgraph.db.gz.part")
            staged.parentFile?.mkdirs()

            // Attempt 1 — manifest / regional URL, gated on the manifest digest.
            when (val r = attempt(
                regionId = regionId,
                url = primaryUrl,
                staged = staged,
                expectedSha256 = manifestFile?.verifiableSha256,
                expectedSize = manifestFile?.realSize ?: 0L,
                strictHash = manifestFile?.verifiableSha256 != null,
            )) {
                // __MAP_SPACE_GUARD_2026_10_21__ INSUFFICIENT_SPACE stops here
                // too. The attempt-2 fallback is the COUNTRY file, which is the
                // same size or larger, so retrying it could only fail again —
                // after a second pointless round trip.
                InstallOutcome.INSTALLED,
                InstallOutcome.CHECKSUM_MISMATCH,
                InstallOutcome.INSUFFICIENT_SPACE -> return@withContext r
                else -> Unit
            }

            // Attempt 2 — country-level file, once, only when the id is a sub-region.
            val country = region.countryPrefix
            if (country == regionId) return@withContext InstallOutcome.NOT_AVAILABLE

            val fallbackUrl = "$ROADGRAPH_BASE_URL/$country-roadgraph.db.gz"
            Log.i(TAG, "$regionId: no regional road graph — retrying $country-roadgraph.db.gz")
            // The country file's SHA is not in this region's manifest entry, so
            // there is nothing to gate on. Same knowing exception iOS makes at
            // `OfflineRoadGraphDownloader.swift:176-182`; the gzip CRC-32 + ISIZE
            // check below is still enforced, so a truncated file is still caught.
            attempt(
                regionId = regionId,
                url = fallbackUrl,
                staged = staged,
                expectedSha256 = null,
                expectedSize = 0L,
                strictHash = false,
            )
        }

    private fun attempt(
        regionId: String,
        url: String,
        staged: File,
        expectedSha256: String?,
        expectedSize: Long,
        strictHash: Boolean,
    ): InstallOutcome {
        try {
            // Resumed, not restarted. This used to be a plain GET that opened
            // with `if (staged.exists()) staged.delete()`, so an interrupted
            // transfer destroyed its own progress -- and the catch below deleted
            // the partial as well, leaving nothing to continue from. France is a
            // 10.1 GB road graph and India 11.8 GB; on a phone connection those
            // never finish if every drop restarts at zero. iOS has resumed from
            // the exact byte offset since OfflineDownloadResume.swift was
            // written, and the server answers 206 with a correct Content-Range
            // (verified against the live host 2026-10-13).
            when (val got = ResumableArtifactDownload.fetch(
                client = httpClient,
                url = url,
                staged = staged,
                label = regionId,
                // __MAP_SPACE_GUARD_2026_10_21__ The road graph is the artifact
                // that made a space guard necessary: us-roadgraph.db.gz is
                // 19.6 GiB compressed and 45.3 GiB unpacked. `expectedSize` is
                // the manifest's `realSize`, already resolved for the shared
                // country-file case; 0 on the fallback attempt, which simply
                // skips the check rather than guessing at a size.
                expectedBytes = expectedSize,
                expansionRatio = ResumableArtifactDownload.GZIP_EXPANSION_RATIO,
            )) {
                is ResumableArtifactDownload.Outcome.Complete -> Unit
                is ResumableArtifactDownload.Outcome.NotAvailable ->
                    return InstallOutcome.NOT_AVAILABLE
                is ResumableArtifactDownload.Outcome.InsufficientSpace -> {
                    Log.i(
                        TAG,
                        "$regionId: road graph needs ~${got.neededBytes} B, " +
                            "${got.availableBytes} B free — refused before downloading"
                    )
                    return InstallOutcome.INSUFFICIENT_SPACE
                }
                is ResumableArtifactDownload.Outcome.Failed -> {
                    // Deliberately do NOT delete the staged file: `partialKept`
                    // means the next attempt continues from these bytes.
                    Log.d(TAG, "$regionId: road graph incomplete (kept=${got.partialKept})")
                    return InstallOutcome.FAILED
                }
            }

            // Integrity of the COMPRESSED artifact — `regenerate_manifest.py`
            // hashes the `.db.gz` on disk, so `MapFile.sha256` is the digest of
            // exactly these bytes (iOS `:108-110,401-405`).
            val verdict = MapArtifactVerifier.verifyOrDelete(
                file = staged,
                expectedSha256 = expectedSha256,
                expectedSize = expectedSize,
                strict = strictHash,
            )
            if (verdict == MapArtifactVerifier.Result.HASH_MISMATCH ||
                verdict == MapArtifactVerifier.Result.SIZE_MISMATCH
            ) {
                Log.w(TAG, "$regionId: road graph failed integrity check ($verdict) — discarded")
                // verifyOrDelete removed the artifact; drop the resume sidecar
                // too, or the next attempt would try to continue a file that is
                // no longer there and keep re-fetching a known-bad digest.
                ResumableArtifactDownload.clear(staged)
                return InstallOutcome.CHECKSUM_MISMATCH
            }
            if (!staged.exists() || staged.length() <= 0L) return InstallOutcome.FAILED

            val dest = router.roadGraphPath(regionId)
            val ok = gunzipToFile(staged, dest)
            // The compressed artifact and its resume marker are both spent once
            // it has been decompressed, whether or not that succeeded: the gzip
            // CRC-32/ISIZE check means a failure here is a bad artifact, not a
            // short one, so resuming it would loop.
            ResumableArtifactDownload.clear(staged)
            if (!ok) {
                dest.delete()
                return InstallOutcome.CHECKSUM_MISMATCH
            }
            return InstallOutcome.INSTALLED
        } catch (e: Exception) {
            // No staged.delete() here. Anything thrown past the download is
            // about the artifact we already hold, and the bytes are worth more
            // than the tidiness -- the next attempt resumes instead of
            // re-fetching 10 GB.
            Log.w(TAG, "Road graph install for $url failed: ${e.message}")
            return InstallOutcome.FAILED
        }
    }

    /**
     * Streaming gunzip, bare single-member `.gz` → `routing.db`.
     *
     * `GZIPInputStream` validates the member's **CRC-32 and ISIZE trailer** and
     * throws `ZipException` on mismatch, which is the same truncation guard iOS
     * implements by hand (`OfflineRoadGraphDownloader.swift:795-825`). On any
     * failure the partial output is removed so `isRoutingAvailable` can never see
     * half a graph.
     */
    private fun gunzipToFile(gz: File, dest: File): Boolean {
        val part = File(dest.parentFile, dest.name + ".part")
        return try {
            dest.parentFile?.mkdirs()
            GZIPInputStream(gz.inputStream().buffered(), 64 * 1024).use { input ->
                FileOutputStream(part).use { out -> input.copyTo(out, 64 * 1024) }
            }
            if (part.length() <= 0L) {
                part.delete()
                return false
            }
            if (dest.exists()) dest.delete()
            val renamed = part.renameTo(dest)
            if (!renamed) part.delete()
            renamed
        } catch (e: Exception) {
            Log.e(TAG, "gunzip failed for ${gz.name}: ${e.message}")
            part.delete()
            false
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // Overpass path — explicit last resort only
    // ───────────────────────────────────────────────────────────────────

    /**
     * Overpass may only be handed a real, modest bbox. The old code called it
     * with `synthesizeMapRegion`'s `0,0,0,0` whenever the (always-404ing) routing
     * manifest had no entry, producing a degenerate graph for the Gulf of Guinea.
     */
    internal fun canImportFromOverpass(region: MapRegion): Boolean =
        isOverpassImportable(region)

    private suspend fun importFromOverpass(region: MapRegion) = withContext(Dispatchers.IO) {
        val dbFile = router.roadGraphPath(region.id)
        if (dbFile.exists()) dbFile.delete()
        dbFile.parentFile?.mkdirs()

        val db = SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null)
        try {
            importer.importRegion(region, db) { p ->
                Log.v(TAG, "import progress: $p")
            }
        } finally {
            db.close()
        }
    }

    // ───────────────────────────────────────────────────────────────────

    private fun markDownloading(regionId: String, downloading: Boolean) {
        _isDownloading.value = _isDownloading.value.toMutableMap().apply {
            if (downloading) put(regionId, true) else remove(regionId)
        }
    }

    private fun setError(regionId: String, error: RoadGraphError) {
        _errors.value = _errors.value.toMutableMap().apply { put(regionId, error) }
    }

    private fun clearError(regionId: String) {
        _errors.value = _errors.value.toMutableMap().apply { remove(regionId) }
    }
}
