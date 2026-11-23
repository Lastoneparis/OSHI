package com.oshi.messenger

import com.oshi.messenger.service.lora.LoRaCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirror of `LoRaCoverageTests.swift` — same vectors, so the platforms draw alike. */
class LoRaCoverageTest {

    /**
     * THE test. A relayed node's RSSI belongs to the relay, not to it, so no
     * ring may be drawn — a circle built from someone else's measurement would
     * be confidently wrong.
     */
    @Test
    fun `a relayed node gets no ring`() {
        assertNull(LoRaCoverage.radiusMeters(-70, 1))
        assertNull(LoRaCoverage.radiusMeters(-70, 3))
        assertEquals(0.0, LoRaCoverage.fillOpacity(-70, 1), 0.0001)
    }

    @Test
    fun `a direct node gets a ring`() {
        assertNotNull(LoRaCoverage.radiusMeters(-70, 0))
        assertNotNull(LoRaCoverage.radiusMeters(-70, null))
    }

    @Test
    fun `a node never heard gets no ring`() {
        assertNull(LoRaCoverage.radiusMeters(null, 0))
    }

    @Test
    fun `a signal below the usable floor gets no ring`() {
        assertNull(LoRaCoverage.radiusMeters(-140, 0))
    }

    @Test
    fun `an implausible positive rssi is rejected`() {
        assertNull(LoRaCoverage.radiusMeters(20, 0))
    }

    @Test
    fun `a weaker signal gives a larger ring`() {
        val strong = LoRaCoverage.radiusMeters(-40, 0)!!
        val medium = LoRaCoverage.radiusMeters(-85, 0)!!
        val weak = LoRaCoverage.radiusMeters(-120, 0)!!
        assertTrue("$strong should be < $medium", strong < medium)
        assertTrue("$medium should be < $weak", medium < weak)
    }

    @Test
    fun `the radius stays within what we will claim`() {
        for (rssi in -125..-30) {
            val radius = LoRaCoverage.radiusMeters(rssi, 0)!!
            assertTrue(radius >= LoRaCoverage.MIN_RADIUS_M)
            assertTrue(radius <= LoRaCoverage.MAX_RADIUS_M)
        }
    }

    @Test
    fun `wider rings are fainter`() {
        val tight = LoRaCoverage.fillOpacity(-35, 0)
        val wide = LoRaCoverage.fillOpacity(-120, 0)
        assertTrue(tight > wide)
        assertTrue(wide > 0)
    }

    @Test
    fun `scaling is not linear`() {
        val a = LoRaCoverage.radiusMeters(-50, 0)!!
        val b = LoRaCoverage.radiusMeters(-70, 0)!!
        val c = LoRaCoverage.radiusMeters(-90, 0)!!
        assertTrue(Math.abs((b - a) - (c - b)) > 1.0)
    }

    /** The iOS file must agree to the metre; these are its computed values. */
    @Test
    fun `radii match the iOS implementation`() {
        // Computed from the formula, not copied from either implementation, so
        // the two are pinned to the same third-party arithmetic.
        assertEquals(150.0, LoRaCoverage.radiusMeters(-30, 0)!!, 0.01)
        assertEquals(1009.8338, LoRaCoverage.radiusMeters(-70, 0)!!, 0.01)
        assertEquals(4502.9086, LoRaCoverage.radiusMeters(-120, 0)!!, 0.01)
        assertEquals(0.1423, LoRaCoverage.fillOpacity(-70, 0), 0.0001)
    }
}
