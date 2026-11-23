package com.oshi.messenger.service

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.oshi.messenger.data.model.AddressMatch
import com.oshi.messenger.data.model.AddressMatchConfidence
import com.oshi.messenger.data.model.StreetDownloadProgress
import com.oshi.messenger.data.model.StreetDownloadState
import com.oshi.messenger.data.model.StreetResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OfflineStreetDatabase — manages downloading and querying offline street index databases.
 *
 * Each region can have an optional street index downloaded from:
 *   https://oshi-messenger.com/files/streets/{regionId}-streets.db.gz
 *
 * The database is decompressed to:
 *   files/Maps/streets/{regionId}/streets.db
 *
 * The street database schema (created server-side):
 *   CREATE TABLE streets (
 *       id INTEGER PRIMARY KEY,
 *       name TEXT NOT NULL,
 *       city TEXT,
 *       region TEXT,
 *       country_code TEXT,
 *       lat REAL NOT NULL,
 *       lon REAL NOT NULL,
 *       bbox_min_lat REAL,
 *       bbox_min_lon REAL,
 *       bbox_max_lat REAL,
 *       bbox_max_lon REAL,
 *       highway_type TEXT
 *   );
 *   CREATE VIRTUAL TABLE streets_fts USING fts5(name, city, content=streets);
 */
@Singleton
class OfflineStreetDatabase @Inject constructor(
    private val context: Context,
    private val storageManager: MapStorageManager
) {
    companion object {
        private const val TAG = "OfflineStreetDatabase"
        private const val STREETS_DIR = "streets"
        private const val STREETS_BASE_URL = "https://oshi-messenger.com/files/streets"
        private const val USER_AGENT = "OSHI-Messenger/1.0 (Android; offline streets)"

        /**
         * Candidate URLs for a region's street index, best first. Pure so it is
         * covered by a JVM unit test.
         *
         * Order: manifest URL → constructed regional → constructed country.
         * The old code built ONLY the regional name, `{regionId}-streets.db.gz`,
         * which 404s for every sub-region (probed: `fr-idf-streets.db.gz` → 404).
         * The server publishes one streets db per COUNTRY and points sub-regions
         * at it via `shared: true`, which is why the manifest URL for `fr-idf` is
         * `fr-streets.db.gz` (200). iOS reads the manifest URL and additionally
         * retries the country file on 404 (OfflineStreetDatabase.swift:224-245).
         */
        fun buildStreetUrlCandidates(
            regionId: String,
            manifestStreets: com.oshi.messenger.data.model.MapFile?,
        ): List<Pair<String, com.oshi.messenger.data.model.MapFile?>> {
            val out = mutableListOf<Pair<String, com.oshi.messenger.data.model.MapFile?>>()
            val usable = manifestStreets?.takeIf { it.isAvailable }
            if (usable != null) out.add(usable.url to usable)
            val regional = "$STREETS_BASE_URL/$regionId-streets.db.gz"
            if (out.none { it.first == regional }) out.add(regional to null)
            val dash = regionId.indexOf('-')
            if (dash > 0) {
                val country = "$STREETS_BASE_URL/${regionId.substring(0, dash)}-streets.db.gz"
                if (out.none { it.first == country }) out.add(country to null)
            }
            return out
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _downloadProgress = MutableStateFlow<Map<String, StreetDownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, StreetDownloadProgress>> = _downloadProgress.asStateFlow()

    // ═══════════════════════════════════════════════════════════════
    // File management
    // ═══════════════════════════════════════════════════════════════

    /** Directory for a region's street database */
    private fun streetDir(regionId: String): File =
        File(storageManager.mapsRoot, "$STREETS_DIR/$regionId").also { it.mkdirs() }

    /** Street database file path */
    private fun streetDbFile(regionId: String): File =
        File(streetDir(regionId), "streets.db")

    /** Check if street index is available (downloaded) for a region */
    fun isAvailable(regionId: String): Boolean =
        streetDbFile(regionId).exists()

    /** Get the state for a region's street index */
    fun getStreetState(regionId: String): StreetDownloadState {
        val progress = _downloadProgress.value[regionId]
        if (progress != null) return progress.state
        return if (isAvailable(regionId)) StreetDownloadState.DOWNLOADED
        else StreetDownloadState.NOT_DOWNLOADED
    }

    // ═══════════════════════════════════════════════════════════════
    // Download
    // ═══════════════════════════════════════════════════════════════

    /**
     * v2 manifest regions, pushed by OfflineMapManager. A direct dependency would
     * be a DI cycle (OfflineMapManager holds this class).
     */
    private val _manifestRegions = MutableStateFlow<List<com.oshi.messenger.data.model.MapRegion>>(emptyList())

    fun setRegions(regions: List<com.oshi.messenger.data.model.MapRegion>) {
        if (regions.isNotEmpty()) _manifestRegions.value = regions
    }

    /**
     * Candidate URLs for a region's street index, best first.
     *
     * The old code built exactly one URL, `{regionId}-streets.db.gz`, which for
     * every sub-region is a **404** (probed: `fr-idf-streets.db.gz` → 404). The
     * manifest advertises `fr-idf.files.streets.url = /files/streets/fr-streets.db.gz`,
     * which is a 200 — the server publishes ONE streets db per country and points
     * every sub-region at it via `shared: true`. iOS reads the manifest URL and
     * additionally retries the country file on 404
     * (`OfflineStreetDatabase.swift:224-245`); both paths are restored here.
     */
    internal fun streetUrlCandidates(regionId: String): List<Pair<String, com.oshi.messenger.data.model.MapFile?>> {
        val region = _manifestRegions.value.firstOrNull { it.id == regionId }
        return buildStreetUrlCandidates(regionId, region?.files?.streets)
    }

    /** Download street index for a region */
    fun downloadIndex(regionId: String) {
        // Don't start if already downloading
        if (_downloadProgress.value[regionId]?.state == StreetDownloadState.DOWNLOADING) return

        updateProgress(regionId, StreetDownloadProgress(
            regionId = regionId,
            progress = 0f,
            state = StreetDownloadState.DOWNLOADING
        ))

        scope.launch {
            try {
                val candidates = streetUrlCandidates(regionId)

                // DISCOVERY, separated from the transfer.
                //
                // This loop used to fire a GET per candidate and then stream the
                // body of whichever one answered 200. One request instead of
                // two, but it also meant the download could never be resumed:
                // the bytes arrived on a response opened before anyone knew
                // whether a partial file was already on disk, and the write
                // below truncated it. A cut transfer restarted from zero.
                //
                // HEAD costs one extra round trip on a transfer of up to 78 MB,
                // and buys the ability to hand the chosen url to
                // ResumableArtifactDownload, which decides about resuming with
                // the partial in front of it.
                var chosen: Pair<String, com.oshi.messenger.data.model.MapFile?>? = null
                for (candidate in candidates) {
                    Log.d(TAG, "Probing street index at ${candidate.first}")
                    val r = httpClient.newCall(
                        Request.Builder().url(candidate.first).head()
                            .header("User-Agent", USER_AGENT).build()
                    ).execute()
                    r.close()
                    if (r.isSuccessful) {
                        chosen = candidate
                        break
                    }
                    Log.d(TAG, "Street index ${candidate.first} → HTTP ${r.code}")
                }
                if (chosen == null) {
                    throw Exception("No street index available for $regionId (tried ${candidates.size} URLs)")
                }
                Log.d(TAG, "Downloading street index from ${chosen.first}")

                val dir = streetDir(regionId)
                val gzFile = File(dir, "streets.db.gz")
                val dbFile = streetDbFile(regionId)

                // The candidate list is region-url-then-country-url and both
                // land on this one streets.db.gz, so the helper's sidecar check
                // is what stops a region's partial being continued with the
                // country file's bytes.
                when (
                    val got = com.oshi.messenger.service.map.ResumableArtifactDownload.fetch(
                        client = httpClient,
                        url = chosen.first,
                        staged = gzFile,
                        label = "$regionId/streets",
                        extraHeaders = mapOf("User-Agent" to USER_AGENT),
                        // __MAP_SPACE_GUARD_2026_10_21__ The chosen candidate
                        // carries the manifest entry, so the size is already in
                        // hand; 0 for a constructed fallback URL, which skips
                        // the check rather than guessing. Streets are gunzipped,
                        // so the unpacked size counts too.
                        expectedBytes = chosen.second?.realSize ?: 0L,
                        expansionRatio =
                            com.oshi.messenger.service.map.ResumableArtifactDownload.GZIP_EXPANSION_RATIO,
                    ) { p ->
                        updateProgress(regionId, StreetDownloadProgress(
                            regionId = regionId,
                            progress = p.coerceIn(0f, 0.9f),
                            state = StreetDownloadState.DOWNLOADING
                        ))
                    }
                ) {
                    is com.oshi.messenger.service.map.ResumableArtifactDownload.Outcome.Complete -> Unit
                    is com.oshi.messenger.service.map.ResumableArtifactDownload.Outcome.NotAvailable ->
                        throw Exception("Street index vanished for $regionId after probing 200")
                    is com.oshi.messenger.service.map.ResumableArtifactDownload.Outcome.InsufficientSpace ->
                        throw Exception(
                            "Street index for $regionId needs ~${got.neededBytes} B, " +
                                "${got.availableBytes} B free — not downloaded"
                        )
                    is com.oshi.messenger.service.map.ResumableArtifactDownload.Outcome.Failed ->
                        // The partial stays; the next attempt continues from it.
                        throw Exception("Street index incomplete for $regionId (kept=${got.partialKept})")
                }

                // INTEGRITY GATE on the compressed artifact, against the digest
                // the manifest carries for it. Nothing verified any street
                // download before: a truncated .gz produced a truncated .db that
                // opened fine and returned no streets. Non-strict for the
                // constructed fallback URLs, whose SHA is not in this region's
                // manifest entry — the gzip CRC-32/ISIZE trailer still guards
                // those, via GZIPInputStream below.
                val manifestFile = chosen.second
                val verdict = com.oshi.messenger.service.map.MapArtifactVerifier.verifyOrDelete(
                    file = gzFile,
                    expectedSha256 = manifestFile?.verifiableSha256,
                    expectedSize = manifestFile?.realSize ?: 0L,
                    strict = manifestFile?.verifiableSha256 != null,
                )
                if (!gzFile.exists()) {
                    // verifyOrDelete removed the archive; drop the sidecar too,
                    // or the next attempt would try to continue a file that is
                    // gone and keep re-fetching a known-bad digest.
                    com.oshi.messenger.service.map.ResumableArtifactDownload.clear(gzFile)
                    throw Exception("Street index failed its integrity check ($verdict)")
                }

                // Decompress gzip to streets.db
                updateProgress(regionId, StreetDownloadProgress(
                    regionId = regionId,
                    progress = 0.95f,
                    state = StreetDownloadState.DOWNLOADING
                ))

                // Decompress through a .part, the way OfflineRoutingManager's
                // gunzipToFile already does. This wrote straight to dbFile, so
                // an interrupted decompression left a truncated streets.db at
                // the final path -- and per the integrity comment above, a
                // truncated street db "opened fine and returned no streets".
                // GZIPInputStream validates the CRC-32/ISIZE trailer, so a
                // short archive throws here rather than producing a short db.
                val dbPart = File(dbFile.parentFile, dbFile.name + ".part")
                try {
                    GZIPInputStream(gzFile.inputStream().buffered()).use { gzInput ->
                        FileOutputStream(dbPart).use { output ->
                            gzInput.copyTo(output)
                        }
                    }
                    if (dbPart.length() <= 0L) throw Exception("Decompressed street index is empty")
                    if (dbFile.exists()) dbFile.delete()
                    if (!dbPart.renameTo(dbFile)) throw Exception("Could not install street index")
                } finally {
                    dbPart.delete()
                    // The archive and its resume marker are spent either way: a
                    // failure here is a bad artifact, not a short one, so
                    // resuming it would loop.
                    com.oshi.messenger.service.map.ResumableArtifactDownload.clear(gzFile)
                }

                Log.d(TAG, "Street index downloaded for $regionId: ${dbFile.length()} bytes")

                updateProgress(regionId, StreetDownloadProgress(
                    regionId = regionId,
                    progress = 1f,
                    state = StreetDownloadState.DOWNLOADED
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download street index for $regionId: ${e.message}")
                updateProgress(regionId, StreetDownloadProgress(
                    regionId = regionId,
                    progress = 0f,
                    state = StreetDownloadState.ERROR,
                    error = e.message ?: "Download failed"
                ))
            }
        }
    }

    /** Delete street index for a region */
    fun deleteIndex(regionId: String) {
        try {
            val dir = streetDir(regionId)
            if (dir.exists()) {
                dir.deleteRecursively()
                Log.d(TAG, "Deleted street index for $regionId")
            }
            // Remove from progress map
            val updated = _downloadProgress.value.toMutableMap()
            updated.remove(regionId)
            _downloadProgress.value = updated
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete street index for $regionId: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Search
    // ═══════════════════════════════════════════════════════════════

    /** Search streets in a region's street index database */
    fun searchStreets(query: String, regionId: String, limit: Int = 20): List<StreetResult> {
        val dbFile = streetDbFile(regionId)
        if (!dbFile.exists()) return emptyList()

        val results = mutableListOf<StreetResult>()
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

            // Try FTS5 search first
            // Tokens MUST be quoted: an apostrophe or hyphen in a bare FTS5 token
            // ("l'eglise", "saint-denis") is syntax, not text, and raises
            // "fts5: syntax error". iOS quotes them (OfflineStreetDatabase.swift:380)
            // and orders by relevance (:398); both were missing here.
            val ftsQuery = query.trim()
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.isNotBlank() }
                .joinToString(" ") { "\"" + it.replace("\"", "\"\"") + "\"*" }

            val cursor = db.rawQuery("""
                SELECT s.id, s.name, s.city, s.lat, s.lon, s.highway_type
                FROM streets_fts f
                JOIN streets s ON s.id = f.rowid
                WHERE f MATCH ?
                ORDER BY rank
                LIMIT ?
            """, arrayOf(ftsQuery, limit.toString()))

            while (cursor.moveToNext()) {
                results.add(StreetResult(
                    id = cursor.getLong(0),
                    name = cursor.getString(1) ?: "",
                    city = cursor.getString(2) ?: "",
                    lat = cursor.getDouble(3),
                    lon = cursor.getDouble(4),
                    highwayType = cursor.getString(5) ?: ""
                ))
            }
            cursor.close()
        } catch (e: Exception) {
            Log.w(TAG, "FTS street search failed, trying LIKE: ${e.message}")
            // Fallback to LIKE
            try {
                if (db == null) {
                    db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                }
                val cursor = db.rawQuery("""
                    SELECT id, name, city, lat, lon, highway_type
                    FROM streets
                    WHERE name LIKE ?
                    LIMIT ?
                """, arrayOf("%$query%", limit.toString()))

                while (cursor.moveToNext()) {
                    results.add(StreetResult(
                        id = cursor.getLong(0),
                        name = cursor.getString(1) ?: "",
                        city = cursor.getString(2) ?: "",
                        lat = cursor.getDouble(3),
                        lon = cursor.getDouble(4),
                        highwayType = cursor.getString(5) ?: ""
                    ))
                }
                cursor.close()
            } catch (e2: Exception) {
                Log.e(TAG, "LIKE street search also failed: ${e2.message}")
            }
        } finally {
            db?.close()
        }
        return results
    }

    /** Search streets across all downloaded regions */
    fun searchAllStreets(query: String, limit: Int = 20): List<StreetResult> {
        val results = mutableListOf<StreetResult>()
        val regionIds = storageManager.listDownloadedRegionIds()

        for (regionId in regionIds) {
            if (!isAvailable(regionId)) continue
            results.addAll(searchStreets(query, regionId, limit - results.size))
            if (results.size >= limit) break
        }
        return results.take(limit)
    }

    // ═══════════════════════════════════════════════════════════════
    // House-number aware address search (parity with iOS searchAddress)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Idempotent migration: ensure the `street_house_numbers` table exists in
     * a downloaded street DB. Safe to call on every open. Silently no-ops on
     * a read-only-mounted DB. Mirrors the iOS migration in OfflineStreetDatabase.swift.
     */
    private fun runHouseNumberMigration(db: SQLiteDatabase) {
        try {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS street_house_numbers(
                    street_id INTEGER NOT NULL,
                    house_number TEXT NOT NULL,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    PRIMARY KEY(street_id, house_number)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_house_numbers_street ON street_house_numbers(street_id)")
        } catch (e: Exception) {
            Log.w(TAG, "house# migration note: ${e.message}")
        }
    }

    /**
     * Open a region's street DB read-write so the house-number migration runs.
     * Falls back to read-only if RW open fails. Caller must close().
     */
    private fun openRegionDb(regionId: String): SQLiteDatabase? {
        val dbFile = streetDbFile(regionId)
        if (!dbFile.exists()) return null
        return try {
            val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            runHouseNumberMigration(db)
            db
        } catch (e: Exception) {
            try {
                SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            } catch (e2: Exception) {
                Log.e(TAG, "openRegionDb failed: ${e2.message}")
                null
            }
        }
    }

    /**
     * Parse a leading house number from a raw query.
     *
     * Regex: `^\s*((?:bis|ter|quater)\s+)?(\d+)\s*([A-Za-z])?\b\s*`
     *
     * Examples:
     *   "12 rue de Rivoli"      → number="12",      street="rue de Rivoli"
     *   "100A Main Street"      → number="100A",    street="Main Street"
     *   "Bis 14 avenue Foch"    → number="Bis 14",  street="avenue Foch"
     *   "rue de Rivoli"         → null
     */
    fun parseHouseNumber(rawQuery: String): Pair<String, String>? {
        val q = rawQuery.trim()
        if (q.isEmpty()) return null
        val regex = Regex("""^\s*((?:bis|ter|quater)\s+)?(\d+)\s*([A-Za-z])?\b\s*""", RegexOption.IGNORE_CASE)
        val m = regex.find(q) ?: return null
        if (m.range.isEmpty()) return null
        val prefix = m.groupValues.getOrNull(1)?.trim().orEmpty()
        val digits = m.groupValues.getOrNull(2).orEmpty()
        val letter = m.groupValues.getOrNull(3)?.uppercase().orEmpty()
        if (digits.isEmpty()) return null
        val number = buildString {
            if (prefix.isNotEmpty()) { append(prefix); append(' ') }
            append(digits)
            append(letter)
        }
        val street = q.substring(m.range.last + 1).trim()
        if (street.isEmpty()) return null
        return number to street
    }

    private fun numericPart(token: String): Int? =
        token.filter { it.isDigit() }.toIntOrNull()

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLam = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dPhi / 2).let { it * it } +
                Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLam / 2).let { it * it }
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    /** Exact `house_number` row for a street in a region's DB. */
    private fun lookupExactHouseNumber(
        db: SQLiteDatabase,
        streetId: Long,
        number: String
    ): Pair<Double, Double>? {
        val cursor = db.rawQuery(
            "SELECT lat, lon FROM street_house_numbers WHERE street_id = ? AND house_number = ? COLLATE NOCASE LIMIT 1",
            arrayOf(streetId.toString(), number)
        )
        return cursor.use {
            if (it.moveToFirst()) it.getDouble(0) to it.getDouble(1) else null
        }
    }

    /**
     * Linear interpolation between the two nearest known numerical
     * `house_number`s on a street. Returns null when the street has fewer
     * than two known anchors.
     */
    private fun interpolateHouseNumber(
        db: SQLiteDatabase,
        streetId: Long,
        targetNumber: Int
    ): Pair<Double, Double>? {
        val anchors = mutableListOf<Triple<Int, Double, Double>>()
        val cursor = db.rawQuery(
            "SELECT house_number, lat, lon FROM street_house_numbers WHERE street_id = ?",
            arrayOf(streetId.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                val raw = it.getString(0) ?: continue
                val n = numericPart(raw) ?: continue
                anchors.add(Triple(n, it.getDouble(1), it.getDouble(2)))
            }
        }
        if (anchors.size < 2) return null
        anchors.sortBy { it.first }
        val lo = anchors.lastOrNull { it.first <= targetNumber }
        val hi = anchors.firstOrNull { it.first > targetNumber }
        val (a, b) = when {
            lo != null && hi != null -> lo to hi
            else -> {
                val sorted = anchors.sortedBy { Math.abs(it.first - targetNumber) }
                sorted[0] to sorted[1]
            }
        }
        if (a.first == b.first) return a.second to a.third
        val t = (targetNumber - a.first).toDouble() / (b.first - a.first).toDouble()
        val lat = a.second + (b.second - a.second) * t
        val lon = a.third + (b.third - a.third) * t
        return lat to lon
    }

    /**
     * Offline house-number aware address search across all downloaded regions
     * (or a single region when [regionId] is provided). Returns an empty list
     * if no leading house number is detected — callers should still use
     * [searchStreets] or [searchAllStreets] for plain street search.
     *
     * Algorithm:
     *   1. Parse `<number> <street>` from [query].
     *   2. Find candidate streets via FTS on the street part.
     *   3. For each candidate, look up an exact `house_number` row.
     *   4. If absent and ≥2 known house numbers exist on that street,
     *      linearly interpolate along the polyline of the street between
     *      the two nearest neighbours by numerical comparison.
     *   5. Sort by haversine distance from (userLat, userLon) when known.
     */
    fun searchAddress(
        query: String,
        userLat: Double? = null,
        userLon: Double? = null,
        regionId: String? = null,
        limit: Int = 20
    ): List<AddressMatch> {
        val parsed = parseHouseNumber(query) ?: return emptyList()
        val (number, street) = parsed
        val targetNumeric = numericPart(number)

        val regionIds = regionId?.let { listOf(it) }
            ?: storageManager.listDownloadedRegionIds().filter { isAvailable(it) }
        if (regionIds.isEmpty()) return emptyList()

        val matches = mutableListOf<AddressMatch>()
        for (rId in regionIds) {
            val db = openRegionDb(rId) ?: continue
            try {
                val candidateStreets = searchStreets(street, rId, limit = 10)
                for (s in candidateStreets) {
                    // Exact house-number row
                    val exact = lookupExactHouseNumber(db, s.id, number)
                    if (exact != null) {
                        matches.add(AddressMatch(
                            houseNumber = number,
                            streetName = s.name,
                            city = s.city,
                            lat = exact.first,
                            lon = exact.second,
                            regionId = rId,
                            confidence = AddressMatchConfidence.EXACT
                        ))
                        continue
                    }
                    // Interpolate when ≥2 anchors exist
                    if (targetNumeric != null) {
                        val interp = interpolateHouseNumber(db, s.id, targetNumeric)
                        if (interp != null) {
                            matches.add(AddressMatch(
                                houseNumber = number,
                                streetName = s.name,
                                city = s.city,
                                lat = interp.first,
                                lon = interp.second,
                                regionId = rId,
                                confidence = AddressMatchConfidence.INTERPOLATED
                            ))
                        }
                    }
                }
            } finally {
                db.close()
            }
        }

        return if (userLat != null && userLon != null) {
            matches.sortedWith(compareBy(
                { if (it.confidence == AddressMatchConfidence.EXACT) 0 else 1 },
                { haversineMeters(userLat, userLon, it.lat, it.lon) }
            )).take(limit)
        } else {
            matches.sortedBy { if (it.confidence == AddressMatchConfidence.EXACT) 0 else 1 }
                .take(limit)
        }
    }

    /**
     * Insert (or replace) a batch of `(streetId, houseNumber, lat, lon)`
     * anchors for a region. Idempotent. Called by the OSM region downloader
     * once it has parsed `addr:housenumber` tags from the raw OSM data.
     */
    fun insertHouseNumbers(
        regionId: String,
        entries: List<Quad>
    ): Boolean {
        if (entries.isEmpty()) return false
        val db = openRegionDb(regionId) ?: return false
        return try {
            db.beginTransaction()
            val stmt = db.compileStatement(
                "INSERT OR REPLACE INTO street_house_numbers(street_id, house_number, lat, lon) VALUES (?, ?, ?, ?)"
            )
            for (e in entries) {
                stmt.clearBindings()
                stmt.bindLong(1, e.streetId)
                stmt.bindString(2, e.houseNumber)
                stmt.bindDouble(3, e.lat)
                stmt.bindDouble(4, e.lon)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
            true
        } catch (e: Exception) {
            Log.e(TAG, "insertHouseNumbers failed: ${e.message}")
            false
        } finally {
            try { db.endTransaction() } catch (_: Exception) {}
            db.close()
        }
    }

    /** Tuple for [insertHouseNumbers]. */
    data class Quad(
        val streetId: Long,
        val houseNumber: String,
        val lat: Double,
        val lon: Double
    )

    /** Get size of street index for a region (0 if not downloaded) */
    fun getIndexSize(regionId: String): Long {
        val dbFile = streetDbFile(regionId)
        return if (dbFile.exists()) dbFile.length() else 0L
    }

    /** Format index size as human-readable string */
    fun getIndexSizeFormatted(regionId: String): String {
        val bytes = getIndexSize(regionId)
        if (bytes == 0L) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) {
            String.format("%.1f GB", mb / 1024.0)
        } else {
            String.format("%.1f MB", mb)
        }
    }

    private fun updateProgress(regionId: String, progress: StreetDownloadProgress) {
        val updated = _downloadProgress.value.toMutableMap()
        updated[regionId] = progress
        _downloadProgress.value = updated
    }
}
