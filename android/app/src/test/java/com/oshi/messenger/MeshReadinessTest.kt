package com.oshi.messenger

import com.oshi.messenger.data.model.MeshReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N15 — telling someone the mesh is "searching" when it cannot search.
 *
 * The sentence is true with the radios on and nobody in range, and false with Bluetooth
 * off. They looked identical, which is the whole report.
 */
class MeshReadinessTest {

    @Test
    fun `with everything on, searching is honest and there is nothing to fix`() {
        assertNull(MeshReadiness.firstBlocker(hasNearbyPermission = true, bluetoothOn = true, wifiOn = true))
        assertTrue(MeshReadiness.searchingIsHonest(true, true, true))
    }

    @Test
    fun `bluetooth off is named`() {
        assertEquals(
            MeshReadiness.Reason.BLUETOOTH_OFF,
            MeshReadiness.firstBlocker(hasNearbyPermission = true, bluetoothOn = false, wifiOn = true),
        )
        assertFalse(MeshReadiness.searchingIsHonest(true, false, true))
    }

    @Test
    fun `wifi off is named`() {
        assertEquals(
            MeshReadiness.Reason.WIFI_OFF,
            MeshReadiness.firstBlocker(hasNearbyPermission = true, bluetoothOn = true, wifiOn = false),
        )
    }

    /**
     * Permission first: one the app never got stops the scan entirely and silently, while a
     * radio the user switched off may well have been on purpose.
     */
    @Test
    fun `a missing permission outranks a radio that is off`() {
        assertEquals(
            MeshReadiness.Reason.NEARBY_PERMISSION,
            MeshReadiness.firstBlocker(hasNearbyPermission = false, bluetoothOn = false, wifiOn = false),
        )
    }

    /**
     * Bluetooth before Wi-Fi: BLE is the transport that works with no network at all, which
     * is the case this app exists for.
     */
    @Test
    fun `bluetooth outranks wifi`() {
        assertEquals(
            MeshReadiness.Reason.BLUETOOTH_OFF,
            MeshReadiness.firstBlocker(hasNearbyPermission = true, bluetoothOn = false, wifiOn = false),
        )
    }

    /**
     * ONE thing at a time. Four problems at once is a wall; one problem with one fix is an
     * instruction, and the next is offered when the person comes back.
     */
    @Test
    fun `only the first blocker is reported, never a list`() {
        val all = MeshReadiness.firstBlocker(hasNearbyPermission = false, bluetoothOn = false, wifiOn = false)
        assertEquals(MeshReadiness.Reason.NEARBY_PERMISSION, all)
        // Fix it, and the next one surfaces rather than everything at once.
        assertEquals(
            MeshReadiness.Reason.BLUETOOTH_OFF,
            MeshReadiness.firstBlocker(hasNearbyPermission = true, bluetoothOn = false, wifiOn = false),
        )
    }
}
