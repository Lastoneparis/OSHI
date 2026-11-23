package com.oshi.messenger.service

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.oshi.messenger.data.model.MapPOI
import com.oshi.messenger.data.model.POICategory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MapPOIDatabase — offline POI search over the SQLite database the SERVER builds.
 *
 * ══ THE SCHEMA IS THE SERVER'S. WE ONLY READ IT. ══
 *
 * Read off the live `https://oshi-messenger.com/maps/v2/fr-idf/poi.db`
 * (2026-08-13, 31 191 040 B, 190 364 rows):
 *
 *   CREATE TABLE pois (id INTEGER PRIMARY KEY, name TEXT NOT NULL,
 *                      category TEXT NOT NULL, subcategory TEXT,
 *                      lat REAL NOT NULL, lon REAL NOT NULL,
 *                      address TEXT, phone TEXT, website TEXT, opening_hours TEXT);
 *   CREATE INDEX idx_pois_category ON pois(category);
 *   CREATE INDEX idx_pois_lat_lon  ON pois(lat, lon);
 *   CREATE VIRTUAL TABLE pois_fts USING fts5(name, category, subcategory, address,
 *                      content='pois', content_rowid='id');
 *
 * ══ WHAT WAS BROKEN (both fixed here) ══
 *
 * 1. Every SELECT asked for `p.region_id` and `p.tags`. Neither column exists
 *    server-side, so the statement failed at prepare time: the FTS branch threw,
 *    the LIKE fallback selected the same missing columns and threw too, and
 *    search returned `emptyList()` — silently. `region_id` was never a column;
 *    the caller always knows which region's file it opened, so it is stamped in
 *    Kotlin. `tags` is derived from the real `subcategory` column.
 *
 * 2. `migrateFtsIfNeeded` looked for `remove_diacritics 2` in the server's
 *    `CREATE VIRTUAL TABLE`, did not find it, and **`DROP TABLE pois_fts`** —
 *    destroying a prebuilt index over 190 364 rows. Its replacement declared a
 *    `tags` column the content table lacks, so `'rebuild'` threw and was
 *    swallowed, leaving an empty index and an early-return guard that made the
 *    damage permanent. Re-download was the only recovery.
 *
 *    The database is now opened **READ-ONLY** (as iOS does,
 *    `MapPOIDatabase.swift:94-99`) so no code path can mutate a server artifact,
 *    and diacritic folding happens in Kotlin via [normalizeForLike] instead.
 */
@Singleton
class MapPOIDatabase @Inject constructor(
    @Suppress("unused") private val context: android.content.Context,
    private val storageManager: MapStorageManager
) {
    companion object {
        private const val TAG = "MapPOIDatabase"
        private const val TABLE_POIS = "pois"
        private const val TABLE_FTS = "pois_fts"

        /**
         * The server's column list, in a fixed order. `region_id` and `tags` are
         * deliberately absent — they are not columns.
         */
        private const val COLUMNS =
            "id, name, category, subcategory, lat, lon, address, phone, website, opening_hours"

        private const val P_COLUMNS =
            "p.id, p.name, p.category, p.subcategory, p.lat, p.lon, p.address, p.phone, p.website, p.opening_hours"

        /**
         * Build an FTS5 MATCH expression from user input.
         *
         * Tokens are double-quoted before the `*`, because an apostrophe or hyphen in
         * a bare token (`l'église`, `saint-denis`) is FTS5 *syntax*, not text, and
         * raises "fts5: syntax error" — which then fell through to the (also broken)
         * LIKE path. Same quoting iOS uses (`OfflineStreetDatabase.swift:380`).
         * Returns null when nothing usable is left.
         */
        fun buildFtsQuery(query: String, column: String? = null): String? {
            val tokens = query.trim()
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.isNotBlank() }
            if (tokens.isEmpty()) return null
            val body = tokens.joinToString(" ") { "\"" + it.replace("\"", "\"\"") + "\"*" }
            // FTS5 column filters bind to the NEXT token only, so a multi-token
            // address query has to be parenthesised: `address : ("rue"* "pasteur"*)`.
            return if (column != null) "$column : ($body)" else body
        }

        /** Lower-case + Unicode-NFD strip diacritics. Used by the Kotlin-side LIKE fallback. */
        fun normalizeForLike(s: String): String {
            val nfd = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            val sb = StringBuilder(nfd.length)
            for (c in nfd) {
                if (Character.getType(c).toByte() != Character.NON_SPACING_MARK) sb.append(c)
            }
            return sb.toString().lowercase()
        }
    }

    /**
     * Open a region's POI database READ-ONLY.
     *
     * Returns null when the file is absent — `openOrCreateDatabase` used to
     * conjure an empty database in that case, which then took the `CREATE TABLE
     * IF NOT EXISTS` path and looked like a working (but permanently empty) index.
     */
    private fun openDb(regionId: String): SQLiteDatabase? {
        val dbFile = storageManager.poisDbFile(regionId)
        if (!dbFile.exists() || dbFile.length() <= 0L) return null
        return try {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open POI db for $regionId: ${e.message}")
            null
        }
    }

    /** Whether this region's db exposes the prebuilt FTS index. */
    private fun hasFts(db: SQLiteDatabase): Boolean = try {
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(TABLE_FTS)
        ).use { it.moveToFirst() }
    } catch (_: Exception) {
        false
    }

    /** Haversine distance in km between two lat/lon pairs. */
    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    // ═══════════════════════════════════════════════════════════════
    // Text search
    // ═══════════════════════════════════════════════════════════════

    /** Search POIs by text query across all downloaded regions */
    fun search(query: String, limit: Int = 50): List<MapPOI> {
        val results = mutableListOf<MapPOI>()
        for (regionId in storageManager.listDownloadedRegionIds()) {
            results.addAll(searchInRegion(regionId, query, limit - results.size))
            if (results.size >= limit) break
        }
        return results.take(limit)
    }

    /** Search POIs in a specific region */
    fun searchInRegion(regionId: String, query: String, limit: Int = 50): List<MapPOI> {
        val db = openDb(regionId) ?: return emptyList()
        val results = mutableListOf<MapPOI>()
        try {
            val ftsQuery = buildFtsQuery(query)
            if (ftsQuery != null && hasFts(db)) {
                try {
                    db.rawQuery(
                        """
                        SELECT $P_COLUMNS
                        FROM $TABLE_FTS f
                        JOIN $TABLE_POIS p ON p.id = f.rowid
                        WHERE f MATCH ?
                        ORDER BY rank
                        LIMIT ?
                        """.trimIndent(),
                        arrayOf(ftsQuery, limit.toString())
                    ).use { c -> while (c.moveToNext()) results.add(cursorToPOI(c, regionId)) }
                } catch (e: Exception) {
                    Log.w(TAG, "FTS search failed for $regionId, falling back to LIKE: ${e.message}")
                }
            }

            if (results.isEmpty()) {
                val like = "%${query.trim()}%"
                db.rawQuery(
                    """
                    SELECT $COLUMNS FROM $TABLE_POIS
                    WHERE name LIKE ? OR address LIKE ? OR category LIKE ?
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(like, like, like, limit.toString())
                ).use { c -> while (c.moveToNext()) results.add(cursorToPOI(c, regionId)) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "POI search failed for $regionId: ${e.message}")
        } finally {
            db.close()
        }
        return results
    }

    /** Search POIs by address across all downloaded regions */
    fun searchByAddress(query: String, limit: Int = 20): List<MapPOI> {
        val results = mutableListOf<MapPOI>()
        for (regionId in storageManager.listDownloadedRegionIds()) {
            results.addAll(searchByAddressInRegion(regionId, query, limit - results.size))
            if (results.size >= limit) break
        }
        return results.take(limit)
    }

    /** Search POIs by address in a specific region using FTS5 on the address column */
    fun searchByAddressInRegion(regionId: String, query: String, limit: Int = 20): List<MapPOI> {
        val db = openDb(regionId) ?: return emptyList()
        val results = mutableListOf<MapPOI>()
        try {
            val ftsQuery = buildFtsQuery(query, column = "address")
            if (ftsQuery != null && hasFts(db)) {
                try {
                    db.rawQuery(
                        """
                        SELECT $P_COLUMNS
                        FROM $TABLE_FTS f
                        JOIN $TABLE_POIS p ON p.id = f.rowid
                        WHERE f MATCH ?
                        ORDER BY rank
                        LIMIT ?
                        """.trimIndent(),
                        arrayOf(ftsQuery, limit.toString())
                    ).use { c -> while (c.moveToNext()) results.add(cursorToPOI(c, regionId)) }
                } catch (e: Exception) {
                    Log.w(TAG, "FTS address search failed for $regionId: ${e.message}")
                }
            }

            if (results.isEmpty()) {
                db.rawQuery(
                    "SELECT $COLUMNS FROM $TABLE_POIS WHERE address LIKE ? LIMIT ?",
                    arrayOf("%${query.trim()}%", limit.toString())
                ).use { c -> while (c.moveToNext()) results.add(cursorToPOI(c, regionId)) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Address search failed for $regionId: ${e.message}")
        } finally {
            db.close()
        }
        return results
    }

    /**
     * Free-text search that also accepts user lat/lon to sort results by proximity
     * and widens to a Kotlin-side accent-folded scan when FTS5 returns nothing.
     *
     * The folding fallback is why the server's index is left alone: it ships
     * without `remove_diacritics 2`, so `cafe` will not reach `Café` through FTS.
     * Folding here costs one scan on a miss; dropping the index cost everything.
     */
    fun searchTextNearby(
        query: String,
        userLat: Double? = null,
        userLon: Double? = null,
        limit: Int = 50
    ): List<MapPOI> {
        val raw = mutableListOf<MapPOI>()
        val normQ = normalizeForLike(query.trim())
        for (regionId in storageManager.listDownloadedRegionIds()) {
            val ftsHits = searchInRegion(regionId, query, limit)
            raw.addAll(ftsHits)
            if (ftsHits.isEmpty() && normQ.isNotEmpty()) {
                raw.addAll(searchInRegionFolded(regionId, normQ, limit))
            }
            if (raw.size >= limit * 2) break
        }
        val byId = LinkedHashMap<String, MapPOI>()
        for (p in raw) byId.putIfAbsent(p.id, p)
        val unique = byId.values.toList()
        return if (userLat != null && userLon != null) {
            unique.sortedBy { haversineKm(userLat, userLon, it.lat, it.lon) }.take(limit)
        } else unique.take(limit)
    }

    /** Kotlin-side LIKE fallback comparing against an accent-folded copy of name/address. */
    private fun searchInRegionFolded(regionId: String, normalizedQuery: String, limit: Int): List<MapPOI> {
        val db = openDb(regionId) ?: return emptyList()
        val results = mutableListOf<MapPOI>()
        try {
            db.rawQuery("SELECT $COLUMNS FROM $TABLE_POIS LIMIT 5000", null).use { c ->
                while (c.moveToNext()) {
                    val poi = cursorToPOI(c, regionId)
                    if (normalizeForLike(poi.name).contains(normalizedQuery) ||
                        normalizeForLike(poi.address).contains(normalizedQuery)
                    ) {
                        results.add(poi)
                        if (results.size >= limit) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Folded fallback failed for $regionId: ${e.message}")
        } finally {
            db.close()
        }
        return results
    }

    // ═══════════════════════════════════════════════════════════════
    // Category search
    // ═══════════════════════════════════════════════════════════════

    /**
     * Category search across all downloaded regions, optionally biased toward
     * `userLat/userLon`.
     */
    fun searchByCategoryNearby(
        category: POICategory,
        userLat: Double? = null,
        userLon: Double? = null,
        radiusKm: Double = 25.0,
        limit: Int = 50
    ): List<MapPOI> {
        val regionIds = storageManager.listDownloadedRegionIds()
        val raw = mutableListOf<MapPOI>()

        if (userLat != null && userLon != null) {
            val latDelta = radiusKm / 111.0
            val lonDelta = radiusKm / (111.0 * Math.cos(Math.toRadians(userLat)))
            for (regionId in regionIds) {
                val db = openDb(regionId) ?: continue
                try {
                    db.rawQuery(
                        """
                        SELECT $COLUMNS FROM $TABLE_POIS
                        WHERE category = ?
                          AND lat BETWEEN ? AND ?
                          AND lon BETWEEN ? AND ?
                        """.trimIndent(),
                        arrayOf(
                            // The SERVER key, never the enum name — see
                            // POICategory.serverKey.
                            category.serverKey,
                            (userLat - latDelta).toString(), (userLat + latDelta).toString(),
                            (userLon - lonDelta).toString(), (userLon + lonDelta).toString()
                        )
                    ).use { c -> while (c.moveToNext()) raw.add(cursorToPOI(c, regionId)) }
                } catch (e: Exception) {
                    Log.e(TAG, "Category nearby search failed for $regionId: ${e.message}")
                } finally {
                    db.close()
                }
            }
            return raw.sortedBy { haversineKm(userLat, userLon, it.lat, it.lon) }.take(limit)
        }

        for (regionId in regionIds) {
            raw.addAll(searchByCategory(regionId, category, limit - raw.size))
            if (raw.size >= limit) break
        }
        return raw.take(limit)
    }

    /** Search POIs by category in a specific region */
    fun searchByCategory(regionId: String, category: POICategory, limit: Int = 100): List<MapPOI> {
        val db = openDb(regionId) ?: return emptyList()
        val results = mutableListOf<MapPOI>()
        try {
            db.rawQuery(
                "SELECT $COLUMNS FROM $TABLE_POIS WHERE category = ? LIMIT ?",
                arrayOf(category.serverKey, limit.toString())
            ).use { c -> while (c.moveToNext()) results.add(cursorToPOI(c, regionId)) }
        } catch (e: Exception) {
            Log.e(TAG, "Category search failed: ${e.message}")
        } finally {
            db.close()
        }
        return results
    }

    /** Find POIs near a given lat/lon within a radius (approximate, uses bounding box) */
    fun searchNearby(lat: Double, lon: Double, radiusKm: Double = 5.0, limit: Int = 50): List<MapPOI> {
        val results = mutableListOf<MapPOI>()
        val latDelta = radiusKm / 111.0
        val lonDelta = radiusKm / (111.0 * Math.cos(Math.toRadians(lat)))

        for (regionId in storageManager.listDownloadedRegionIds()) {
            val db = openDb(regionId) ?: continue
            try {
                db.rawQuery(
                    """
                    SELECT $COLUMNS FROM $TABLE_POIS
                    WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(
                        (lat - latDelta).toString(), (lat + latDelta).toString(),
                        (lon - lonDelta).toString(), (lon + lonDelta).toString(),
                        limit.toString()
                    )
                ).use { c -> while (c.moveToNext()) results.add(cursorToPOI(c, regionId)) }
            } catch (e: Exception) {
                Log.e(TAG, "Nearby search failed for $regionId: ${e.message}")
            } finally {
                db.close()
            }
            if (results.size >= limit) break
        }
        return results.take(limit)
    }

    /** Get total POI count for a region */
    fun poiCount(regionId: String): Int {
        val db = openDb(regionId) ?: return 0
        return try {
            db.rawQuery("SELECT COUNT(*) FROM $TABLE_POIS", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        } catch (e: Exception) {
            0
        } finally {
            db.close()
        }
    }

    // ═══════════════════════════════════════════════════════════════

    /**
     * Map one row of [COLUMNS] onto [MapPOI].
     *
     * `regionId` is passed IN — it is not a column. `getColumnIndexOrThrow(
     * "region_id"/"tags")` was the second half of the failure: even a query that
     * had somehow returned rows would have thrown here.
     */
    private fun cursorToPOI(cursor: Cursor, regionId: String): MapPOI {
        fun str(name: String): String {
            val idx = cursor.getColumnIndex(name)
            return if (idx < 0 || cursor.isNull(idx)) "" else cursor.getString(idx) ?: ""
        }

        val subcategory = str("subcategory")
        return MapPOI(
            // `pois.id` restarts at 1 in every region file, so the bare rowid is
            // not unique across regions and de-duplication by id would drop real
            // hits. Namespaced by region.
            id = regionId + ":" + cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            name = str("name"),
            category = POICategory.fromServerKey(str("category")),
            lat = cursor.getDouble(cursor.getColumnIndexOrThrow("lat")),
            lon = cursor.getDouble(cursor.getColumnIndexOrThrow("lon")),
            address = str("address"),
            phone = str("phone"),
            website = str("website"),
            subcategory = subcategory,
            openingHours = str("opening_hours"),
            regionId = regionId,
            tags = if (subcategory.isBlank()) emptyList() else listOf(subcategory)
        )
    }
}
