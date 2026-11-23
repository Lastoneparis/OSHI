package com.oshi.messenger.service.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import android.util.Log
import com.oshi.messenger.R
import com.oshi.messenger.data.model.MapRoute
import com.oshi.messenger.data.model.RouteManeuver
import com.oshi.messenger.data.model.RouteMode
import com.oshi.messenger.data.model.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OfflineRouter — bidirectional A* over a SQLite-backed OSM road graph.
 *
 * Mirrors iOS OfflineRouter.swift feature-for-feature:
 *  - Same SQLite schema (road_nodes, road_edges) so a server-built tar.gz
 *    can be unpacked and used identically on iOS or Android.
 *  - Same polyline encoding: base64-packed pairs of little-endian Float64
 *    (lat, lon).
 *  - Same edge-weight columns: weight_car, weight_bike, weight_foot.
 *  - Same heuristic: haversine / max-mode-speed.
 *  - Same maneuver thresholds based on bearing delta.
 *
 *  DB path: `context.filesDir/Maps/regions/{regionId}/routing.db`
 *
 *  Note: the iOS implementation uses `road_graph.db`. This Android port keeps
 *  the schema column names identical but stores under `routing.db` so it lives
 *  alongside the existing OfflineRoutingManager region directory layout
 *  (`Maps/routing/{regionId}/`). Server-built tar.gz files MUST contain a file
 *  named `routing.db` with the iOS schema for cross-platform compatibility.
 */
@Singleton
class OfflineRouter @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "OfflineRouter"

        /** Hard timeout for an A* search (matches iOS). */
        private const val SEARCH_TIMEOUT_MS = 5_000L

        /** Maximum snap distance from the requested coordinate to a road node. */
        private const val SNAP_MAX_METERS = 200.0

        /** DB filename inside each region directory. */
        const val DB_FILENAME = "routing.db"

        /**
         * Encode a polyline as base64 of little-endian Float64 (lat, lon) pairs.
         * Byte-for-byte compatible with iOS OfflineRouter.encodePolyline so a
         * server-built routing.db can be consumed by either platform.
         */
        fun encodePolyline(pts: List<GeoPoint>): String {
            val bb = ByteBuffer.allocate(pts.size * 16).order(ByteOrder.LITTLE_ENDIAN)
            for (p in pts) {
                bb.putDouble(p.latitude)
                bb.putDouble(p.longitude)
            }
            return Base64.encodeToString(bb.array(), Base64.NO_WRAP)
        }

        /**
         * Decode a polyline written by [encodePolyline].
         *
         * Moved here from an instance method 2026-10-13: it reads no instance
         * state, and keeping it off the companion meant the only way to test it
         * was to construct an @Inject OfflineRouter with a Context. That is
         * part of why nothing ever tested this router -- and why the empty
         * string the server writes for every edge went unexamined through 80
         * published countries.
         *
         * An empty string is the NORMAL content of `road_edges.polyline` for a
         * server-built graph, not an error: build_roadgraph.py emits one edge
         * per consecutive node pair, so the edge's shape is just its endpoints,
         * which live in road_nodes. Callers must fall back to those rather than
         * discard the edge -- see geometry() in buildRoute.
         */
        fun decodePolyline(b64: String): List<GeoPoint> {
            if (b64.isEmpty()) return emptyList()
            return try {
                val data = Base64.decode(b64, Base64.DEFAULT)
                if (data.size % 16 != 0) return emptyList()
                val count = data.size / 16
                val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                val out = ArrayList<GeoPoint>(count)
                for (i in 0 until count) {
                    val lat = bb.getDouble(i * 16)
                    val lon = bb.getDouble(i * 16 + 8)
                    out.add(GeoPoint(lat, lon))
                }
                out
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Calculate an offline route. Runs on Dispatchers.IO.
     * Returns null if no road graph DB covers the origin, or no route is found.
     */
    suspend fun route(
        from: GeoPoint,
        to: GeoPoint,
        mode: RouteMode,
        regionId: String
    ): MapRoute? = withContext(Dispatchers.IO) {
        val dbFile = roadGraphPath(regionId)
        if (!dbFile.exists() || dbFile.length() == 0L) {
            Log.d(TAG, "No road graph DB at ${dbFile.absolutePath}")
            return@withContext null
        }

        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            routeSync(db, from, to, mode)
        } catch (e: Exception) {
            Log.e(TAG, "Routing failed: ${e.message}")
            null
        } finally {
            db?.close()
        }
    }

    /** Whether a road-graph DB exists and is non-empty for the given region. */
    fun hasGraph(regionId: String): Boolean {
        val f = roadGraphPath(regionId)
        if (!f.exists() || f.length() == 0L) return false
        return try {
            val db = SQLiteDatabase.openDatabase(
                f.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            db.use {
                it.rawQuery("SELECT COUNT(*) FROM road_nodes LIMIT 1", null).use { c ->
                    c.moveToFirst() && c.getLong(0) > 0
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Path to the per-region routing.db file. */
    fun roadGraphPath(regionId: String): File {
        val dir = File(context.filesDir, "Maps/routing/$regionId")
        dir.mkdirs()
        return File(dir, DB_FILENAME)
    }

    // ───────────────────────────────────────────────────────────────────
    // Synchronous worker
    // ───────────────────────────────────────────────────────────────────

    private fun routeSync(
        db: SQLiteDatabase,
        origin: GeoPoint,
        destination: GeoPoint,
        mode: RouteMode
    ): MapRoute? {
        val deadline = System.currentTimeMillis() + SEARCH_TIMEOUT_MS

        val startId = snapNearestNode(db, origin.latitude, origin.longitude) ?: return null
        val goalId = snapNearestNode(db, destination.latitude, destination.longitude) ?: return null

        if (startId == goalId) {
            // Trivial route: snap is the same node, return a tiny stub.
            return MapRoute(
                points = listOf(
                    RoutePoint(origin.latitude, origin.longitude),
                    RoutePoint(destination.latitude, destination.longitude)
                ),
                distance = 0.0,
                duration = 0.0,
                mode = mode,
                maneuvers = listOf(
                    RouteManeuver(
                context.getString(R.string.map_maneuver_start),
                0.0, "depart", origin.latitude, origin.longitude
            ),
                    RouteManeuver(
                        "Arrive at destination",
                        0.0,
                        "arrive",
                        destination.latitude,
                        destination.longitude
                    )
                )
            )
        }

        val nodeIds = bidirectionalAStar(db, startId, goalId, mode, deadline) ?: return null
        return buildRoute(db, nodeIds, origin, destination, mode)
    }

    // ───────────────────────────────────────────────────────────────────
    // Snap to nearest node
    // ───────────────────────────────────────────────────────────────────

    private fun snapNearestNode(db: SQLiteDatabase, lat: Double, lon: Double): Long? {
        // Pre-filter by a tight bbox using idx_road_nodes_geo, then sort by haversine.
        val dLat = 0.005
        val dLon = 0.005 / max(cos(Math.toRadians(lat)), 0.1)

        val cursor = db.rawQuery(
            "SELECT id, lat, lon FROM road_nodes WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
            arrayOf(
                (lat - dLat).toString(), (lat + dLat).toString(),
                (lon - dLon).toString(), (lon + dLon).toString()
            )
        )

        var bestId = -1L
        var bestDist = Double.MAX_VALUE
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val nlat = c.getDouble(1)
                val nlon = c.getDouble(2)
                val d = haversine(lat, lon, nlat, nlon)
                if (d < bestDist) {
                    bestDist = d
                    bestId = id
                }
            }
        }
        return if (bestId < 0 || bestDist > SNAP_MAX_METERS) null else bestId
    }

    // ───────────────────────────────────────────────────────────────────
    // Bidirectional A*
    // ───────────────────────────────────────────────────────────────────

    private data class Edge(
        val toId: Long,
        val weight: Double,                  // seconds
        val polyline: List<GeoPoint>,
        val highway: String,
        val oneway: Int
    )

    private data class HeapEntry(val nodeId: Long, val priority: Double)

    private fun bidirectionalAStar(
        db: SQLiteDatabase,
        start: Long,
        goal: Long,
        mode: RouteMode,
        deadline: Long
    ): List<Long>? {
        val forwardAdj = HashMap<Long, List<Edge>>()
        val backwardAdj = HashMap<Long, List<Edge>>()

        val goalCoord = nodeCoord(db, goal) ?: return null
        val startCoord = nodeCoord(db, start) ?: return null

        val maxSpeed = when (mode) {
            RouteMode.DRIVING -> 33.3   // ~120 km/h cap
            RouteMode.CYCLING -> 8.0    // ~28 km/h
            RouteMode.WALKING -> 2.0    // ~7 km/h
        }

        val cmp = Comparator<HeapEntry> { a, b -> a.priority.compareTo(b.priority) }
        val openF = PriorityQueue(cmp)
        val openB = PriorityQueue(cmp)

        val gF = HashMap<Long, Double>().apply { put(start, 0.0) }
        val gB = HashMap<Long, Double>().apply { put(goal, 0.0) }
        val parentF = HashMap<Long, Long>()
        val parentB = HashMap<Long, Long>()

        openF.add(HeapEntry(start, heuristic(startCoord, goalCoord, maxSpeed)))
        openB.add(HeapEntry(goal, heuristic(goalCoord, startCoord, maxSpeed)))

        var meetingNode = -1L
        var bestMu = Double.MAX_VALUE
        var iter = 0

        while (openF.isNotEmpty() && openB.isNotEmpty()) {
            iter++
            if (iter and 0xFF == 0 && System.currentTimeMillis() > deadline) {
                return null
            }

            val topF = openF.peek()?.priority ?: Double.MAX_VALUE
            val topB = openB.peek()?.priority ?: Double.MAX_VALUE
            if (topF + topB >= bestMu && bestMu < Double.MAX_VALUE) break

            if (topF <= topB) {
                val u = openF.poll()?.nodeId ?: break
                val gU = gF[u] ?: Double.MAX_VALUE
                val edges = adjacency(db, u, mode, reverse = false, cache = forwardAdj)
                for (e in edges) {
                    val tentative = gU + e.weight
                    if (tentative < (gF[e.toId] ?: Double.MAX_VALUE)) {
                        gF[e.toId] = tentative
                        parentF[e.toId] = u
                        val coord = nodeCoord(db, e.toId) ?: continue
                        val f = tentative + heuristic(coord, goalCoord, maxSpeed)
                        openF.add(HeapEntry(e.toId, f))
                        gB[e.toId]?.let { gbV ->
                            val mu = tentative + gbV
                            if (mu < bestMu) {
                                bestMu = mu
                                meetingNode = e.toId
                            }
                        }
                    }
                }
            } else {
                val u = openB.poll()?.nodeId ?: break
                val gU = gB[u] ?: Double.MAX_VALUE
                val edges = adjacency(db, u, mode, reverse = true, cache = backwardAdj)
                for (e in edges) {
                    val tentative = gU + e.weight
                    if (tentative < (gB[e.toId] ?: Double.MAX_VALUE)) {
                        gB[e.toId] = tentative
                        parentB[e.toId] = u
                        val coord = nodeCoord(db, e.toId) ?: continue
                        val f = tentative + heuristic(coord, startCoord, maxSpeed)
                        openB.add(HeapEntry(e.toId, f))
                        gF[e.toId]?.let { gfV ->
                            val mu = tentative + gfV
                            if (mu < bestMu) {
                                bestMu = mu
                                meetingNode = e.toId
                            }
                        }
                    }
                }
            }
        }

        if (meetingNode < 0) return null

        val pathFwd = ArrayList<Long>().apply { add(meetingNode) }
        var cur = meetingNode
        while (parentF[cur] != null) {
            val p = parentF[cur]!!
            pathFwd.add(p)
            cur = p
        }
        pathFwd.reverse()

        val pathBwd = ArrayList<Long>()
        cur = meetingNode
        while (parentB[cur] != null) {
            val p = parentB[cur]!!
            pathBwd.add(p)
            cur = p
        }

        return pathFwd + pathBwd
    }

    private fun adjacency(
        db: SQLiteDatabase,
        nodeId: Long,
        mode: RouteMode,
        reverse: Boolean,
        cache: HashMap<Long, List<Edge>>
    ): List<Edge> {
        cache[nodeId]?.let { return it }

        val weightCol = when (mode) {
            RouteMode.DRIVING -> "weight_car"
            RouteMode.CYCLING -> "weight_bike"
            RouteMode.WALKING -> "weight_foot"
        }

        val sql = if (reverse) {
            "SELECT from_id, to_id, $weightCol, polyline, highway, oneway FROM road_edges WHERE to_id = ?"
        } else {
            "SELECT from_id, to_id, $weightCol, polyline, highway, oneway FROM road_edges WHERE from_id = ?"
        }

        val out = ArrayList<Edge>()
        db.rawQuery(sql, arrayOf(nodeId.toString())).use { c ->
            while (c.moveToNext()) {
                val fromId = c.getLong(0)
                val toId = c.getLong(1)
                val weight = c.getDouble(2)
                val polyStr = c.getString(3) ?: ""
                val highway = c.getString(4) ?: ""
                val oneway = c.getInt(5)

                val neighbour = if (reverse) fromId else toId
                // For driving, drop oneway-violating reverse edges. Walking/biking
                // ignore oneway (people walk against traffic).
                if (reverse && oneway == 1 && mode == RouteMode.DRIVING) continue

                out.add(Edge(neighbour, weight, decodePolyline(polyStr), highway, oneway))
            }
        }
        cache[nodeId] = out
        return out
    }

    private fun nodeCoord(db: SQLiteDatabase, id: Long): GeoPoint? {
        db.rawQuery("SELECT lat, lon FROM road_nodes WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return null
            return GeoPoint(c.getDouble(0), c.getDouble(1))
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // Build MapRoute from node IDs
    // ───────────────────────────────────────────────────────────────────

    private fun buildRoute(
        db: SQLiteDatabase,
        nodeIds: List<Long>,
        origin: GeoPoint,
        destination: GeoPoint,
        mode: RouteMode
    ): MapRoute? {
        if (nodeIds.size < 2) return null

        // Every node on the path, resolved once.
        //
        // The server builder (maps_pipeline/build_roadgraph.py) emits ONE EDGE
        // PER CONSECUTIVE NODE PAIR and stores the empty string in `polyline`
        // for all of them -- a deleted comment in an older revision said so
        // outright: "polyline is left as an empty string for 2-point edges
        // (the vast majority)". Measured on the published Nicaragua roadgraph:
        // 2,904,022 edges, 2,904,022 of them empty, none with geometry.
        //
        // appendEdge below opens with `if (pts.size < 2) return EdgeTally(0.0,
        // 0.0, ...)`, and decodePolyline("") is emptyList(). So EVERY edge of
        // EVERY server-built region was discarded: distance 0, duration 0, no
        // maneuvers beyond depart/arrive, and a points list of just
        // [origin, destination] -- a straight line drawn across the country.
        // iOS had the identical defect in the identical place, and no test on
        // either platform exercises this router, which is how it survived 80
        // published countries.
        //
        // The geometry was never missing, only unused: a 2-point edge's shape
        // IS its two endpoints, and both are in road_nodes. One lookup per
        // path node, not per edge, since consecutive edges share a node.
        val nodeCoords = HashMap<Long, GeoPoint>(nodeIds.size)
        for (id in nodeIds) {
            if (!nodeCoords.containsKey(id)) {
                nodeCoord(db, id)?.let { nodeCoords[id] = it }
            }
        }

        // The edge's own geometry when it has some, otherwise the straight
        // segment between its two endpoints.
        fun geometry(pts: List<GeoPoint>, from: Long, to: Long): List<GeoPoint> {
            if (pts.size >= 2) return pts
            val a = nodeCoords[from] ?: return pts
            val b = nodeCoords[to] ?: return pts
            return listOf(a, b)
        }

        val fullPolyline = ArrayList<GeoPoint>()
        fullPolyline.add(origin)

        var totalMeters = 0.0
        var totalSeconds = 0.0

        val maneuvers = ArrayList<RouteManeuver>()
        maneuvers.add(
            RouteManeuver(
                context.getString(R.string.map_maneuver_start),
                0.0, "depart", origin.latitude, origin.longitude
            )
        )

        var lastBearing: Double? = null

        for (i in 0 until nodeIds.size - 1) {
            val from = nodeIds[i]
            val to = nodeIds[i + 1]

            val edge = fetchEdge(db, from, to, mode)
                ?: fetchEdge(db, to, from, mode)?.let {
                    // Reverse-direction fallback: some graphs only store one row
                    // per undirected edge.
                    it.copy(polyline = it.polyline.reversed())
                }
                ?: continue

            appendEdge(
                pts = geometry(edge.polyline, from, to),
                highway = edge.highway,
                weight = edge.weight,
                fullPolyline = fullPolyline,
                lastBearingRef = LastBearingRef(lastBearing),
                maneuvers = maneuvers
            ).also { tally ->
                totalMeters += tally.meters
                totalSeconds += tally.seconds
                lastBearing = tally.newLastBearing
            }
        }

        fullPolyline.add(destination)
        maneuvers.add(
            RouteManeuver(
                context.getString(R.string.map_maneuver_arrive),
                0.0,
                "arrive",
                destination.latitude,
                destination.longitude
            )
        )

        return MapRoute(
            points = fullPolyline.map { RoutePoint(it.latitude, it.longitude) },
            distance = totalMeters,
            duration = totalSeconds,
            mode = mode,
            maneuvers = maneuvers
        )
    }

    private data class LastBearingRef(val value: Double?)
    private data class EdgeTally(val meters: Double, val seconds: Double, val newLastBearing: Double?)

    private fun appendEdge(
        pts: List<GeoPoint>,
        highway: String,
        weight: Double,
        fullPolyline: ArrayList<GeoPoint>,
        lastBearingRef: LastBearingRef,
        maneuvers: ArrayList<RouteManeuver>
    ): EdgeTally {
        if (pts.size < 2) return EdgeTally(0.0, 0.0, lastBearingRef.value)

        val segMeters = polylineLengthMeters(pts)

        val outBearing = bearing(pts[0], pts[1])
        lastBearingRef.value?.let { last ->
            val delta = angleDifference(last, outBearing)
            if (abs(delta) > 25.0) {
                val type = maneuverTypeFromDelta(delta)
                val instr = instructionFor(type, highway)
                maneuvers.add(RouteManeuver(instr, segMeters, type, pts[0].latitude, pts[0].longitude))
            }
        }
        val newLast = bearing(pts[pts.size - 2], pts[pts.size - 1])

        // Append polyline (skip first point if it duplicates the last appended point).
        val tail = fullPolyline.lastOrNull()
        if (tail != null && tail.latitude == pts[0].latitude && tail.longitude == pts[0].longitude) {
            for (idx in 1 until pts.size) fullPolyline.add(pts[idx])
        } else {
            fullPolyline.addAll(pts)
        }

        return EdgeTally(segMeters, weight, newLast)
    }

    private fun fetchEdge(db: SQLiteDatabase, from: Long, to: Long, mode: RouteMode): Edge? {
        val weightCol = when (mode) {
            RouteMode.DRIVING -> "weight_car"
            RouteMode.CYCLING -> "weight_bike"
            RouteMode.WALKING -> "weight_foot"
        }
        val sql = "SELECT $weightCol, polyline, highway, oneway FROM road_edges WHERE from_id = ? AND to_id = ?"
        db.rawQuery(sql, arrayOf(from.toString(), to.toString())).use { c ->
            if (!c.moveToFirst()) return null
            val weight = c.getDouble(0)
            val polyStr = c.getString(1) ?: ""
            val highway = c.getString(2) ?: ""
            val oneway = c.getInt(3)
            return Edge(to, weight, decodePolyline(polyStr), highway, oneway)
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // Heuristic / Geometry helpers
    // ───────────────────────────────────────────────────────────────────

    private fun heuristic(a: GeoPoint, b: GeoPoint, maxSpeed: Double): Double =
        haversine(a.latitude, a.longitude, b.latitude, b.longitude) / maxSpeed

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

    private fun polylineLengthMeters(pts: List<GeoPoint>): Double {
        var total = 0.0
        for (i in 1 until pts.size) {
            total += haversine(
                pts[i - 1].latitude, pts[i - 1].longitude,
                pts[i].latitude, pts[i].longitude
            )
        }
        return total
    }

    private fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val theta = atan2(y, x)
        return ((Math.toDegrees(theta) + 360.0) % 360.0)
    }

    /** Smallest signed angle from a→b in degrees (-180,180]. */
    private fun angleDifference(a: Double, b: Double): Double {
        var d = b - a
        while (d > 180) d -= 360
        while (d <= -180) d += 360
        return d
    }

    /**
     * Maneuver type strings match RouteManeuver.type usage in MapNavigationScreen
     * (`turn-left`, `turn-right`, `continue`, `arrive`, `depart`, `u-turn`).
     */
    private fun maneuverTypeFromDelta(delta: Double): String {
        val absD = abs(delta)
        if (absD > 150) return "u-turn"
        return if (delta > 0) {
            when {
                absD > 100 -> "sharp-right"
                absD > 45 -> "turn-right"
                absD > 10 -> "slight-right"
                else -> "continue"
            }
        } else {
            when {
                absD > 100 -> "sharp-left"
                absD > 45 -> "turn-left"
                absD > 10 -> "slight-left"
                else -> "continue"
            }
        }
    }

    /**
     * Build a spoken/displayed instruction.
     *
     * Every fragment goes through `strings.xml`. These were hardcoded English
     * literals in an app that ships 33 languages, so a French user navigating in
     * French heard *"Turn left onto the street."* iOS runs the identical set
     * through `NSLocalizedString` (`OfflineRouter.swift:628-648`), including the
     * `"onto %@"` joiner — which must be a format string, not a concatenation,
     * because the word order of "<maneuver> onto <road>" is not universal.
     */
    internal fun instructionFor(type: String, highway: String): String {
        val road = humanRoadName(highway)
        val prefix = context.getString(maneuverPrefixRes(type))
        if (road.isEmpty()) return prefix
        return prefix + " " + context.getString(R.string.map_maneuver_onto, road)
    }

    private fun maneuverPrefixRes(type: String): Int = when (type) {
        "turn-left" -> R.string.map_maneuver_turn_left
        "turn-right" -> R.string.map_maneuver_turn_right
        "slight-left" -> R.string.map_maneuver_bear_left
        "slight-right" -> R.string.map_maneuver_bear_right
        "sharp-left" -> R.string.map_maneuver_sharp_left
        "sharp-right" -> R.string.map_maneuver_sharp_right
        "u-turn" -> R.string.map_maneuver_uturn
        "roundabout" -> R.string.map_maneuver_roundabout
        "merge" -> R.string.map_maneuver_merge
        "fork" -> R.string.map_maneuver_fork
        "continue" -> R.string.map_maneuver_continue
        "depart" -> R.string.map_maneuver_start
        "arrive" -> R.string.map_maneuver_arrive
        else -> R.string.map_maneuver_continue
    }

    /** Localized road-class noun. Empty string = no "onto ..." suffix at all. */
    private fun humanRoadName(highway: String): String {
        val res = when (highway) {
            "motorway", "motorway_link" -> R.string.map_road_motorway
            "trunk", "trunk_link" -> R.string.map_road_trunk
            "primary", "primary_link" -> R.string.map_road_primary
            "secondary", "secondary_link" -> R.string.map_road_secondary
            "tertiary", "tertiary_link" -> R.string.map_road_tertiary
            "residential", "living_street" -> R.string.map_road_residential
            "footway", "path", "pedestrian" -> R.string.map_road_path
            "cycleway" -> R.string.map_road_cycleway
            "service" -> R.string.map_road_service
            else -> return ""
        }
        return context.getString(res)
    }

    // ───────────────────────────────────────────────────────────────────
    // Polyline encoding — base64 of little-endian Float64 (lat, lon) pairs.
    // Compatible byte-for-byte with iOS OfflineRouter.encodePolyline / decode.
    // ───────────────────────────────────────────────────────────────────


}
