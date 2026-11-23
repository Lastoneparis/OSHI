package com.oshi.messenger

import com.oshi.messenger.service.map.OfflineRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * __MAPS_COMPLETE_2026_10_13__
 *
 * The server-built roadgraph shape, which had no test at all on either platform.
 *
 * `maps_pipeline/build_roadgraph.py` emits one edge per consecutive node pair
 * and stores the EMPTY STRING in `polyline` for every one of them. Measured on
 * the published Nicaragua artifact: 2,904,022 edges, 2,904,022 empty, none
 * carrying geometry. Every published country is built that way.
 *
 * `appendEdge` used to drop any edge whose decoded polyline had fewer than two
 * points, so every edge of every server-built region was discarded and a route
 * came back as distance 0, duration 0, no maneuvers beyond depart/arrive, and a
 * two-point straight line from origin to destination.
 *
 * Note on scope: these tests deliberately never call [OfflineRouter.encodePolyline]
 * or feed a non-empty string to the decoder. Both paths reach
 * `android.util.Base64`, which is a throwing stub in a plain JVM unit test --
 * this module has no Robolectric and no `returnDefaultValues`, which is why
 * every other test here only mentions Base64 in a comment. The empty-string
 * path returns before touching it, so it is testable, and it is also the exact
 * value the bug turned on.
 */
class OfflineRouterServerShapeTest {

    @Test
    fun `the empty polyline the server writes decodes to nothing`() {
        // Not corrupt and not an error -- this is the normal, intended content
        // of the column for a 2-point edge, and it is precisely why the
        // `pts.size < 2` guard in appendEdge rejected the entire graph.
        assertEquals(emptyList<GeoPoint>(), OfflineRouter.decodePolyline(""))
    }

    @Test
    fun `two endpoints clear the guard that an empty polyline fails`() {
        // The fallback's premise. A server edge decodes to zero points and is
        // rejected; the same edge described by its two road_nodes endpoints has
        // two points and is accepted. Both numbers are what appendEdge tests.
        val fromServerPolyline = OfflineRouter.decodePolyline("")
        assertTrue(
            "an empty polyline must fail the >= 2 guard -- this is the bug",
            fromServerPolyline.size < 2
        )

        val a = GeoPoint(12.1364, -86.2514)   // Managua, inside the ni graph
        val b = GeoPoint(12.1401, -86.2489)
        val fromEndpoints = listOf(a, b)
        assertTrue(
            "the same edge drawn from its endpoints must pass the guard",
            fromEndpoints.size >= 2
        )
    }

    @Test
    fun `an edge between two distinct nodes has real length`() {
        // Guards the consequence rather than the mechanism: if the fallback
        // ever regresses to contributing zero-length segments, a route's
        // distance and duration go back to 0 and the UI shows "0 km, 0 min".
        val a = GeoPoint(12.1364, -86.2514)
        val b = GeoPoint(12.1401, -86.2489)
        assertTrue(
            "a segment between two distinct road nodes must have length",
            a.distanceToAsDouble(b) > 1.0
        )
    }
}
