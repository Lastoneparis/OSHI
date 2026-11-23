package com.oshi.messenger.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two rules that turned OSHI's foreground services into the app's
 * dominant crash — 96 reports on 12 distinct users in the 30 days to 2026-10-16,
 * on an app with roughly 500 installs:
 *
 *   1. a `START_STICKY` service is restarted by the platform **in the background**,
 *      which is the one foreground-service start Android 12+ refuses. Asking for
 *      that restart buys a crash loop, so above API 31 we must not ask;
 *   2. a `dataSync` service must stop on its own clock, short of Android 15's
 *      cumulative daily budget. Waiting for `onTimeout` loses the race — that
 *      callback was already overridden when the crash reached versionCode 81.
 */
class ForegroundServicePolicyTest {

    private val ANDROID_11 = 30  // background FGS starts still allowed
    private val ANDROID_12 = 31  // background FGS starts forbidden
    private val ANDROID_14 = 34  // FGS types required at runtime
    private val ANDROID_15 = 35  // dataSync runtime budgeted
    private val ANDROID_16 = 36  // what this build targets

    // MARK: - A platform-initiated restart must never try to go foreground

    @Test
    fun `system restart is refused from Android 12 on`() {
        for (sdk in listOf(ANDROID_12, ANDROID_14, ANDROID_15, ANDROID_16)) {
            assertEquals(
                "a null-intent restart on API $sdk has no exemption and must not be attempted",
                ForegroundStartDecision.SKIP_SYSTEM_RESTART,
                ForegroundServicePolicy.decideStart(sdk, isSystemRestart = true),
            )
        }
    }

    @Test
    fun `system restart is still fine before Android 12`() {
        assertEquals(
            ForegroundStartDecision.START,
            ForegroundServicePolicy.decideStart(ANDROID_11, isSystemRestart = true),
        )
    }

    @Test
    fun `an explicit start is always attempted`() {
        for (sdk in listOf(ANDROID_11, ANDROID_12, ANDROID_14, ANDROID_15, ANDROID_16)) {
            assertEquals(
                "a start we asked for on API $sdk must be attempted — the caller may hold an exemption",
                ForegroundStartDecision.START,
                ForegroundServicePolicy.decideStart(sdk, isSystemRestart = false),
            )
        }
    }

    // MARK: - START_STICKY is only safe where the restart it asks for is allowed

    @Test
    fun `sticky restart is unsafe from Android 12 on`() {
        assertTrue(ForegroundServicePolicy.stickyRestartIsSafe(ANDROID_11))
        for (sdk in listOf(ANDROID_12, ANDROID_14, ANDROID_15, ANDROID_16)) {
            assertFalse(
                "START_STICKY on API $sdk asks for exactly the start the platform refuses",
                ForegroundServicePolicy.stickyRestartIsSafe(sdk),
            )
        }
    }

    // MARK: - The dataSync deadline is ours, and it is inside the platform's

    @Test
    fun `dataSync stops itself only where the platform budgets it`() {
        assertNull(ForegroundServicePolicy.dataSyncSelfStopMs(ANDROID_14))
        assertEquals(
            ForegroundServicePolicy.DATA_SYNC_SELF_STOP_MS,
            ForegroundServicePolicy.dataSyncSelfStopMs(ANDROID_15),
        )
        assertEquals(
            ForegroundServicePolicy.DATA_SYNC_SELF_STOP_MS,
            ForegroundServicePolicy.dataSyncSelfStopMs(ANDROID_16),
        )
    }

    @Test
    fun `the self-stop leaves real margin under the six-hour cap`() {
        val sixHours = 6L * 60L * 60L * 1000L
        val deadline = ForegroundServicePolicy.DATA_SYNC_SELF_STOP_MS
        assertTrue("the deadline must be under the platform cap", deadline < sixHours)
        assertTrue(
            "the budget is cumulative across the day, so one session must not spend most of it",
            deadline <= sixHours * 2 / 3,
        )
    }

    // __ANDROID_ANSWERS_READS_IN_BACKGROUND_2026_10_23__

    @Test
    fun `the mesh lifeline is claimed from the foreground on every release`() {
        for (sdk in listOf(ANDROID_11, ANDROID_12, ANDROID_14, ANDROID_15, ANDROID_16)) {
            assertTrue(
                "sdk $sdk: the mesh must take its foreground service while the app is on screen",
                ForegroundServicePolicy.shouldStartMeshLifeline(sdk, appIsForeground = true),
            )
        }
    }

    @Test
    fun `from the background it is only attempted where the platform allows it`() {
        assertTrue(
            "before Android 12 a background start is legal, so take it",
            ForegroundServicePolicy.shouldStartMeshLifeline(ANDROID_11, appIsForeground = false),
        )
        for (sdk in listOf(ANDROID_12, ANDROID_14, ANDROID_15, ANDROID_16)) {
            assertFalse(
                "sdk $sdk: attempting it from the background buys a caught exception on " +
                    "every 30 s tick and nothing else",
                ForegroundServicePolicy.shouldStartMeshLifeline(sdk, appIsForeground = false),
            )
        }
    }

    /**
     * The whole point of the type. `dataSync` is budgeted by Android 15 and stops
     * itself at [ForegroundServicePolicy.DATA_SYNC_SELF_STOP_MS]; if the mesh ever
     * rode that service, the BLE read that hands an iPhone our ip and port would
     * go unanswered for the rest of the day, with the service advertised the whole
     * time. The mesh's lifeline has no deadline for exactly that reason.
     */
    @Test
    fun `the mesh lifeline is not the budgeted kind`() {
        assertTrue(
            "a dataSync service has a self-stop",
            ForegroundServicePolicy.dataSyncSelfStopMs(ANDROID_15) != null,
        )
        assertTrue(
            "the mesh lifeline must stay claimable with no deadline of its own",
            ForegroundServicePolicy.shouldStartMeshLifeline(ANDROID_15, appIsForeground = true),
        )
    }


    /** __FGS_DID_NOT_START_2026_11_10__ Galaxy S25 crash: no BT permission on 34+. */
    @Test
    fun `connectedDevice foreground needs a bluetooth permission from Android 14`() {
        assertTrue(!ForegroundServicePolicy.connectedDeviceTypeAllowed(34, emptySet()))
        assertTrue(!ForegroundServicePolicy.connectedDeviceTypeAllowed(37, setOf("android.permission.ACCESS_FINE_LOCATION")))
        assertTrue(ForegroundServicePolicy.connectedDeviceTypeAllowed(34, setOf("android.permission.BLUETOOTH_CONNECT")))
        assertTrue(ForegroundServicePolicy.connectedDeviceTypeAllowed(36, setOf("android.permission.BLUETOOTH_SCAN")))
        assertTrue("not enforced before 14", ForegroundServicePolicy.connectedDeviceTypeAllowed(33, emptySet()))
    }
}
