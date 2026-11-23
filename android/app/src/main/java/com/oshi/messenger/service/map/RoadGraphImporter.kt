package com.oshi.messenger.service.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.oshi.messenger.data.model.MapRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * RoadGraphImporter — fetches OSM data from the Overpass API and writes a
 * SQLite road graph that [OfflineRouter] can query.
 *
 * Mirrors iOS RoadGraphImporter.swift:
 *  - Same Overpass query (highway whitelist).
 *  - Same SQLite schema (road_nodes, road_edges with weight_car / weight_bike /
 *    weight_foot, polyline as base64 little-endian Float64 pairs).
 *  - Same speed tables for mode-specific weights.
 *  - Same junction-anchor detection (only nodes referenced by 2+ ways become
 *    graph nodes; intermediate way points end up packed into the polyline blob).
 */
@Singleton
class RoadGraphImporter @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "RoadGraphImporter"
        private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
        private const val USER_AGENT = "OSHI-Messenger/1.0 (Android; offline routing)"
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder().header("User-Agent", USER_AGENT).build()
            )
        }
        .build()

    /**
     * Import the road graph for [region] into [db]. Caller is responsible for
     * opening [db] (read-write) and closing it. [progress] reports values in
     * `[0,1]` and is called from the import coroutine.
     */
    suspend fun importRegion(
        region: MapRegion,
        db: SQLiteDatabase,
        progress: (Float) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        progress(0.05f)
        val osm = fetchOverpass(
            minLat = region.minLat, minLon = region.minLon,
            maxLat = region.maxLat, maxLon = region.maxLon
        )
        progress(0.55f)
        val edgeCount = writeDatabase(osm = osm, db = db) { p ->
            progress(0.55f + 0.45f * p)
        }
        progress(1f)
        Log.d(TAG, "Imported road graph: ${osm.nodes.size} nodes, $edgeCount edges")
    }

    // ───────────────────────────────────────────────────────────────────
    // Overpass fetch
    // ───────────────────────────────────────────────────────────────────

    private data class OSMResult(
        val nodes: HashMap<Long, DoubleArray>,         // id → [lat, lon]
        val ways: ArrayList<Way>
    )

    private data class Way(
        val ids: LongArray,
        val tags: HashMap<String, String>
    )

    private fun fetchOverpass(
        minLat: Double, minLon: Double,
        maxLat: Double, maxLon: Double
    ): OSMResult {
        // Overpass bbox order is south,west,north,east.
        val south = minLat
        val west = minLon
        val north = maxLat
        val east = maxLon

        val query = """
            [out:json][timeout:180];
            (
              way["highway"~"motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|pedestrian|footway|path|cycleway|track|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|steps"]
                ($south,$west,$north,$east);
            );
            (._;>;);
            out body;
        """.trimIndent()

        val body = "data=${java.net.URLEncoder.encode(query, "UTF-8")}"
            .toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())

        val req = Request.Builder().url(OVERPASS_URL).post(body).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("Overpass HTTP ${resp.code}")
            }
            val text = resp.body?.string() ?: throw RuntimeException("Overpass empty body")
            return parseOverpassJson(text)
        }
    }

    private fun parseOverpassJson(json: String): OSMResult {
        val root = JSONObject(json)
        val elements = root.optJSONArray("elements") ?: JSONArray()

        val nodes = HashMap<Long, DoubleArray>()
        val ways = ArrayList<Way>()

        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            when (el.optString("type")) {
                "node" -> {
                    val id = el.optLong("id", -1L)
                    if (id < 0) continue
                    val lat = el.optDouble("lat", Double.NaN)
                    val lon = el.optDouble("lon", Double.NaN)
                    if (!lat.isNaN() && !lon.isNaN()) {
                        nodes[id] = doubleArrayOf(lat, lon)
                    }
                }
                "way" -> {
                    val ndsArr = el.optJSONArray("nodes") ?: continue
                    if (ndsArr.length() < 2) continue
                    val ids = LongArray(ndsArr.length()) { ndsArr.getLong(it) }
                    val tagsJson = el.optJSONObject("tags")
                    val tags = HashMap<String, String>()
                    if (tagsJson != null) {
                        val keys = tagsJson.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            tags[k] = tagsJson.optString(k, "")
                        }
                    }
                    ways.add(Way(ids, tags))
                }
            }
        }
        return OSMResult(nodes, ways)
    }

    // ───────────────────────────────────────────────────────────────────
    // SQLite write
    // ───────────────────────────────────────────────────────────────────

    private fun writeDatabase(
        osm: OSMResult,
        db: SQLiteDatabase,
        progress: (Float) -> Unit
    ): Int {
        // Identify junction nodes: any node referenced by 2+ ways or way endpoints
        // becomes an anchor (a graph vertex).
        val refCount = HashMap<Long, Int>()
        for (w in osm.ways) {
            for (idx in w.ids.indices) {
                val id = w.ids[idx]
                val isEndpoint = (idx == 0 || idx == w.ids.size - 1)
                refCount[id] = (refCount[id] ?: 0) + (if (isEndpoint) 1 else 0)
            }
            for (id in w.ids) {
                refCount[id] = (refCount[id] ?: 0) + 1
            }
        }
        val anchorNodes = HashSet<Long>()
        for ((id, c) in refCount) {
            if (c >= 2) anchorNodes.add(id)
        }

        // PRAGMAs for fast bulk insert.
        db.execSQL("PRAGMA journal_mode=OFF")
        db.execSQL("PRAGMA synchronous=OFF")
        db.execSQL("PRAGMA temp_store=MEMORY")

        // Schema (matches iOS).
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS road_nodes(
                id INTEGER PRIMARY KEY,
                lat REAL,
                lon REAL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS road_edges(
                from_id INTEGER,
                to_id INTEGER,
                weight_car REAL,
                weight_bike REAL,
                weight_foot REAL,
                polyline TEXT,
                highway TEXT,
                oneway INTEGER,
                PRIMARY KEY(from_id, to_id)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_road_edges_from ON road_edges(from_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_road_nodes_geo ON road_nodes(lat, lon)")

        db.beginTransaction()
        var edgeCount = 0
        try {
            // Insert anchor nodes.
            val nodeStmt = db.compileStatement(
                "INSERT OR REPLACE INTO road_nodes(id, lat, lon) VALUES(?,?,?)"
            )
            try {
                for (id in anchorNodes) {
                    val coord = osm.nodes[id] ?: continue
                    nodeStmt.clearBindings()
                    nodeStmt.bindLong(1, id)
                    nodeStmt.bindDouble(2, coord[0])
                    nodeStmt.bindDouble(3, coord[1])
                    nodeStmt.executeInsert()
                }
            } finally {
                nodeStmt.close()
            }

            // Insert edges. Each contiguous span between two anchor nodes inside
            // a way becomes one edge.
            val edgeStmt = db.compileStatement(
                """INSERT OR REPLACE INTO road_edges
                   (from_id, to_id, weight_car, weight_bike, weight_foot,
                    polyline, highway, oneway)
                   VALUES(?,?,?,?,?,?,?,?)""".trimIndent()
            )
            try {
                val totalWays = max(osm.ways.size, 1)
                for ((wIdx, way) in osm.ways.withIndex()) {
                    val highway = way.tags["highway"] ?: ""
                    val onewayTag = way.tags["oneway"] ?: "no"
                    val oneway = if (onewayTag == "yes" || onewayTag == "true" || onewayTag == "1") 1 else 0
                    val isFootOnly = highway == "footway" || highway == "path" ||
                            highway == "pedestrian" || highway == "steps"
                    val isMotorwayOnly = highway == "motorway" || highway == "motorway_link" ||
                            highway == "trunk" || highway == "trunk_link"

                    val segment = ArrayList<Long>()
                    for (nid in way.ids) {
                        segment.add(nid)
                        if (anchorNodes.contains(nid) && segment.size >= 2 && segment.first() != nid) {
                            val edge = makeEdge(
                                ids = segment,
                                osm = osm,
                                highway = highway,
                                oneway = oneway,
                                isFootOnly = isFootOnly,
                                isMotorwayOnly = isMotorwayOnly
                            )
                            if (edge != null) {
                                writeEdge(edgeStmt, edge); edgeCount++
                                val rev = makeReverseEdge(edge, oneway)
                                writeEdge(edgeStmt, rev); edgeCount++
                            }
                            segment.clear()
                            segment.add(nid)
                        }
                    }
                    if (wIdx % 256 == 0) {
                        progress(wIdx.toFloat() / totalWays.toFloat())
                    }
                }
            } finally {
                edgeStmt.close()
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return edgeCount
    }

    private data class EdgeRow(
        val fromId: Long,
        val toId: Long,
        val weightCar: Double,
        val weightBike: Double,
        val weightFoot: Double,
        val polyline: List<GeoPoint>,
        val highway: String,
        val oneway: Int
    )

    private fun makeEdge(
        ids: List<Long>,
        osm: OSMResult,
        highway: String,
        oneway: Int,
        isFootOnly: Boolean,
        isMotorwayOnly: Boolean
    ): EdgeRow? {
        if (ids.size < 2) return null
        if (osm.nodes[ids.first()] == null || osm.nodes[ids.last()] == null) return null

        val coords = ArrayList<GeoPoint>(ids.size)
        for (id in ids) {
            val c = osm.nodes[id] ?: continue
            coords.add(GeoPoint(c[0], c[1]))
        }
        if (coords.size < 2) return null

        var meters = 0.0
        for (i in 1 until coords.size) {
            meters += haversine(
                coords[i - 1].latitude, coords[i - 1].longitude,
                coords[i].latitude, coords[i].longitude
            )
        }

        val carMps = if (isFootOnly) Double.MAX_VALUE else carSpeed(highway)
        val bikeMps = if (isMotorwayOnly) Double.MAX_VALUE else bikeSpeed(highway)
        val footMps = if (isMotorwayOnly) Double.MAX_VALUE else footSpeed(highway)

        return EdgeRow(
            fromId = ids.first(),
            toId = ids.last(),
            weightCar = meters / max(carMps, 0.1),
            weightBike = meters / max(bikeMps, 0.1),
            weightFoot = meters / max(footMps, 0.1),
            polyline = coords,
            highway = highway,
            oneway = oneway
        )
    }

    private fun makeReverseEdge(edge: EdgeRow, oneway: Int): EdgeRow {
        val carWeight = if (oneway == 1) Double.MAX_VALUE else edge.weightCar
        return edge.copy(
            fromId = edge.toId,
            toId = edge.fromId,
            weightCar = carWeight,
            polyline = edge.polyline.reversed()
        )
    }

    private fun writeEdge(
        stmt: android.database.sqlite.SQLiteStatement,
        e: EdgeRow
    ) {
        stmt.clearBindings()
        stmt.bindLong(1, e.fromId)
        stmt.bindLong(2, e.toId)
        stmt.bindDouble(3, if (e.weightCar.isFinite()) e.weightCar else 1e12)
        stmt.bindDouble(4, if (e.weightBike.isFinite()) e.weightBike else 1e12)
        stmt.bindDouble(5, if (e.weightFoot.isFinite()) e.weightFoot else 1e12)
        stmt.bindString(6, OfflineRouter.encodePolyline(e.polyline))
        stmt.bindString(7, e.highway)
        stmt.bindLong(8, e.oneway.toLong())
        try {
            stmt.executeInsert()
        } catch (_: Exception) {
            // PK conflict can occur when a way visits the same junction pair twice — ignore.
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // Speed tables (m/s) — match iOS RoadGraphImporter.swift
    // ───────────────────────────────────────────────────────────────────

    private fun carSpeed(highway: String): Double = when (highway) {
        "motorway", "motorway_link" -> 33.3
        "trunk", "trunk_link" -> 25.0
        "primary", "primary_link" -> 20.0
        "secondary", "secondary_link" -> 16.7
        "tertiary", "tertiary_link" -> 13.9
        "unclassified" -> 11.1
        "residential", "living_street" -> 8.3
        "service" -> 5.5
        else -> 5.5
    }

    private fun bikeSpeed(highway: String): Double = when (highway) {
        "cycleway" -> 5.5
        "primary", "primary_link",
        "secondary", "secondary_link" -> 4.4
        "tertiary", "tertiary_link",
        "residential", "living_street",
        "unclassified", "service" -> 4.0
        "footway", "path", "pedestrian", "track" -> 3.5
        else -> 3.5
    }

    private fun footSpeed(highway: String): Double = when (highway) {
        "footway", "path", "pedestrian", "steps", "residential", "living_street" -> 1.4
        "cycleway", "track", "service", "unclassified", "tertiary", "tertiary_link" -> 1.3
        "secondary", "secondary_link", "primary", "primary_link" -> 1.2
        else -> 1.2
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
