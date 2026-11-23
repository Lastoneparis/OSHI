package com.oshi.messenger.network.mesh

import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.ScanCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The scan-restart defect from the Galaxy A17 capture (2026-08-24 21:51, OSHI
 * 1.6.7), and the two things that caused it.
 *
 * Every assertion here runs on the JVM. Nothing in this file needs a radio, a
 * permission dialog or a second phone — which is the whole point, because the
 * bug it covers only showed up in a log from a run that *worked*.
 */
class BleRadioPolicyTest {

    // ─────────────────────────────────────────────────────────────────────────
    // The error codes must be the platform's, exactly
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * These are read from an `Int` handed to us by the framework. A wrong
     * constant here is a misread failure — the worst case being reading a real
     * failure as ALREADY_STARTED, which would leave `bleScanning` claiming a
     * scan that does not exist, silently and forever.
     */
    @Test
    fun `scan error codes match the platform constants`() {
        assertEquals(ScanCallback.SCAN_FAILED_ALREADY_STARTED, BleRadioPolicy.SCAN_FAILED_ALREADY_STARTED)
        assertEquals(
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED,
            BleRadioPolicy.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED,
        )
        assertEquals(ScanCallback.SCAN_FAILED_INTERNAL_ERROR, BleRadioPolicy.SCAN_FAILED_INTERNAL_ERROR)
        assertEquals(ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED, BleRadioPolicy.SCAN_FAILED_FEATURE_UNSUPPORTED)
        assertEquals(
            ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES,
            BleRadioPolicy.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES,
        )
        assertEquals(
            ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY,
            BleRadioPolicy.SCAN_FAILED_SCANNING_TOO_FREQUENTLY,
        )
    }

    @Test
    fun `advertise error codes match the platform constants`() {
        assertEquals(AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE, BleRadioPolicy.ADVERTISE_FAILED_DATA_TOO_LARGE)
        assertEquals(
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS,
            BleRadioPolicy.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS,
        )
        assertEquals(AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED, BleRadioPolicy.ADVERTISE_FAILED_ALREADY_STARTED)
        assertEquals(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR, BleRadioPolicy.ADVERTISE_FAILED_INTERNAL_ERROR)
        assertEquals(
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED,
            BleRadioPolicy.ADVERTISE_FAILED_FEATURE_UNSUPPORTED,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // errorCode=1 — the line in the live log
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the captured errorCode 1 means the scan is running, not that it failed`() {
        // ❌ BLE scan failed: errorCode=1, from the Galaxy A17, in a run where
        // discovery worked. The scan really was up — from the call before ours.
        val fault = BleRadioPolicy.scanFault(1)
        assertEquals(BleRadioPolicy.RadioFault.DUPLICATE_START, fault)
        assertTrue("clearing bleScanning here would make every tick re-arm and be rejected again", fault.live)
        assertFalse("a duplicate of our own making is not the user's problem", fault.userVisible)
        assertFalse("retrying is what caused it", fault.retryable)
    }

    @Test
    fun `every other scan failure marks the scan as down`() {
        for (code in listOf(2, 3, 4, 5, 6)) {
            assertFalse(
                "errorCode=$code must not leave bleScanning claiming a scan that is not running",
                BleRadioPolicy.scanFault(code).live,
            )
        }
    }

    @Test
    fun `an unknown scan error code is treated as down and retryable`() {
        // "Probably fine" is how a rejected start becomes invisible. An error
        // code we do not recognise is a scan we cannot vouch for.
        val fault = BleRadioPolicy.scanFault(9999)
        assertFalse(fault.live)
        assertTrue(fault.retryable)
    }

    @Test
    fun `throttling is retryable but must never read as scanning`() {
        // 5 starts / 30 s, then Android refuses silently for the rest of the
        // window. Exactly the state the old code would have believed was a
        // healthy scan finding nobody.
        val fault = BleRadioPolicy.scanFault(ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY)
        assertEquals(BleRadioPolicy.RadioFault.THROTTLED, fault)
        assertFalse(fault.live)
        assertTrue(fault.retryable)
    }

    @Test
    fun `an unsupported radio is not retried and IS told to the user`() {
        val fault = BleRadioPolicy.scanFault(ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED)
        assertFalse("retrying forever on a phone that cannot do this is pure battery", fault.retryable)
        assertTrue("nothing the app does will fix it, so say so", fault.userVisible)
    }

    @Test
    fun `advertise ALREADY_STARTED is the same trap on the other side`() {
        val fault = BleRadioPolicy.advertiseFault(AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED)
        assertEquals(BleRadioPolicy.RadioFault.DUPLICATE_START, fault)
        assertTrue(
            "clearing bleAdvertising here would make ensureBleRunning re-arm forever",
            fault.live,
        )
    }

    @Test
    fun `too many advertisers is worth telling the user about`() {
        val fault = BleRadioPolicy.advertiseFault(AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS)
        assertFalse("we are NOT advertising — iOS cannot see us at all", fault.live)
        assertTrue("the user can close the other app", fault.userVisible)
        assertTrue(fault.retryable)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // One tick, one call to startScan
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a tick on a running scan refreshes it, and a tick on a dead scan only brings it up`() {
        // The bug: the tick did BOTH. ensureBleRunning() started the scan when
        // it was down, and then the tick unconditionally ran
        // stop / delay(500) / start — two startScan calls half a second apart,
        // the second of which comes back SCAN_FAILED_ALREADY_STARTED.
        assertEquals(
            BleRadioPolicy.ScanTick.BRING_UP,
            BleRadioPolicy.scanTick(permitted = true, scanning = false),
        )
        assertNotEquals(
            "a scan that ensureBleRunning has just started must not be started again",
            BleRadioPolicy.ScanTick.REFRESH,
            BleRadioPolicy.scanTick(permitted = true, scanning = false),
        )
        assertEquals(
            BleRadioPolicy.ScanTick.REFRESH,
            BleRadioPolicy.scanTick(permitted = true, scanning = true),
        )
    }

    @Test
    fun `no permission means the tick touches the radio at all`() {
        assertEquals(
            BleRadioPolicy.ScanTick.SKIP_NO_PERMISSION,
            BleRadioPolicy.scanTick(permitted = false, scanning = false),
        )
        assertEquals(
            "a stale scanning flag must not turn a denial into a refresh",
            BleRadioPolicy.ScanTick.SKIP_NO_PERMISSION,
            BleRadioPolicy.scanTick(permitted = false, scanning = true),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Two tickers — the reason "Periodic BLE scan restart" printed twice
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a second start retires the first start's timers`() {
        val gen = MeshRunGeneration()
        val first = gen.begin()
        assertTrue(gen.isCurrent(first))

        // MainViewModel's launch path: meshManager.startMesh() reaches start(),
        // then, seeing isRunning == true, it calls reconfigureIdentity() which
        // is stop() + start(). The first launch's ticker is parked in
        // delay(30_000) throughout and used to wake to _isRunning == true again.
        gen.end()
        val second = gen.begin()

        assertFalse("the first launch's ticker must not survive the restart", gen.isCurrent(first))
        assertTrue(gen.isCurrent(second))
    }

    @Test
    fun `a retired generation never becomes current again`() {
        val gen = MeshRunGeneration()
        val first = gen.begin()
        gen.end()
        assertFalse(gen.isCurrent(first))
        repeat(5) { gen.begin(); gen.end() }
        assertFalse("no amount of churn may resurrect a retired loop", gen.isCurrent(first))
    }

    @Test
    fun `a start with no matching stop still retires the previous generation`() {
        // Defensive: stop() is not guaranteed to have run (a crash between the
        // two, a caller that only ever calls start). begin() alone must be
        // enough to retire whatever came before.
        val gen = MeshRunGeneration()
        val first = gen.begin()
        val second = gen.begin()
        assertFalse(gen.isCurrent(first))
        assertTrue(gen.isCurrent(second))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The phone's Bluetooth name is the user's, not ours
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `an OSHI name is never recorded as the user's own`() {
        // The residual bug in the previous fix. `savedAdapterName` lived in a
        // field, which does not survive the process: swipe OSHI away while it
        // is advertising and the adapter stays "OSHI-Hugo". The next launch read
        // THAT as the original and "restored" the phone to it — the user's real
        // Bluetooth name, the one their car and headphones show, gone for good.
        assertNull(BleRadioPolicy.adapterNameToSave(current = "OSHI-Hugo", alreadySaved = null))
    }

    @Test
    fun `a real name is saved once and never overwritten`() {
        assertEquals("Hugo's Galaxy", BleRadioPolicy.adapterNameToSave("Hugo's Galaxy", null))
        assertNull(
            "the saved copy is the only one there is",
            BleRadioPolicy.adapterNameToSave(current = "OSHI-Hugo", alreadySaved = "Hugo's Galaxy"),
        )
        assertNull(
            "even a plausible-looking current name must not replace a saved original",
            BleRadioPolicy.adapterNameToSave(current = "Galaxy A17", alreadySaved = "Hugo's Galaxy"),
        )
    }

    @Test
    fun `a blank or unreadable name is not saved`() {
        assertNull(BleRadioPolicy.adapterNameToSave(current = null, alreadySaved = null))
        assertNull(BleRadioPolicy.adapterNameToSave(current = "", alreadySaved = null))
    }

    @Test
    fun `an orphaned rename is repaired on the next launch`() {
        // A process killed while advertising leaves "OSHI-Hugo" on the adapter.
        assertEquals(
            "Hugo's Galaxy",
            BleRadioPolicy.adapterNameToRestore(saved = "Hugo's Galaxy", current = "OSHI-Hugo"),
        )
    }

    @Test
    fun `we never stomp a name the user changed themselves`() {
        assertNull(
            "the adapter is not ours any more — leave it alone",
            BleRadioPolicy.adapterNameToRestore(saved = "Hugo's Galaxy", current = "Pixel 9"),
        )
        assertNull(BleRadioPolicy.adapterNameToRestore(saved = null, current = "OSHI-Hugo"))
        assertNull(
            "nothing to do when it is already right",
            BleRadioPolicy.adapterNameToRestore(saved = "Hugo's Galaxy", current = "Hugo's Galaxy"),
        )
    }

    @Test
    fun `save then restore is a round trip that returns the original`() {
        val real = "Hugo's Galaxy"
        val saved = BleRadioPolicy.adapterNameToSave(current = real, alreadySaved = null)
        assertEquals(real, saved)
        // …app renames the adapter…
        val renamed = "${BleRadioPolicy.ADAPTER_NAME_PREFIX}Hugo"
        // …a second bring-up must not clobber the saved copy…
        assertNull(BleRadioPolicy.adapterNameToSave(current = renamed, alreadySaved = saved))
        // …and stop puts it back.
        assertEquals(real, BleRadioPolicy.adapterNameToRestore(saved = saved, current = renamed))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The call sites, checked against the source
    // ─────────────────────────────────────────────────────────────────────────

    private fun crossPlatformMeshSource(): String =
        File("src/main/java/com/oshi/messenger/network/mesh/CrossPlatformMesh.kt").readText()

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("expected to find `$signature`", start >= 0)
        var depth = 0
        var i = source.indexOf('{', start)
        val open = i
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced braces after `$signature`")
    }

    @Test
    fun `startBLEScanning refuses to re-arm a scan that is already running`() {
        // The fix, at the one place it has to be: `bleScanning` is checked
        // BEFORE `startScan`, not after. Without this guard the framework
        // rejects the call with SCAN_FAILED_ALREADY_STARTED and nothing above
        // notices.
        val body = functionBody(crossPlatformMeshSource(), "private fun startBLEScanning()")
        val guard = body.indexOf("if (_bleScanning.value)")
        val start = body.indexOf(".startScan(")
        assertTrue("startBLEScanning must check _bleScanning before starting a scan", guard >= 0)
        assertTrue("expected the startScan call", start >= 0)
        assertTrue("the guard has to come BEFORE the startScan call to be worth anything", guard < start)
    }

    @Test
    fun `startBLEAdvertising refuses to re-arm an advertisement the radio CONFIRMED`() {
        // __A_LATE_SUCCESS_MUST_NOT_RESURRECT_A_DEAD_ADVERTISEMENT_2026_10_23__
        //
        // This test used to pin `if (_bleAdvertising.value) return`, and that
        // guard was too trusting. `_bleAdvertising` is set optimistically at
        // call time, so on its own it only ever meant "we asked". Measured on a
        // Galaxy A17: `reconfigureIdentity()` tore the advertisement down, the
        // success callback for the DEAD one arrived afterwards and set the flag
        // true, and every later attempt took this early exit — for the lifetime
        // of the process. A scan from a Mac one metre away saw 45 devices and
        // not that phone.
        //
        // The intent of the original test is kept, because it is right: do not
        // hammer a radio that is genuinely advertising. What changed is what
        // counts as genuinely. An advertisement the radio has CONFIRMED for the
        // current epoch is left alone; one that is merely claimed is re-armed.
        val body = functionBody(crossPlatformMeshSource(), "private fun startBLEAdvertising()")
        val start = body.indexOf(".startAdvertising(")
        assertTrue("expected the startAdvertising call", start >= 0)

        val confirmedGuard = body.indexOf("advertiseConfirmedEpoch == advertiseEpoch")
        assertTrue(
            "the early exit must require a CONFIRMATION, not just the optimistic flag",
            confirmedGuard >= 0,
        )
        assertTrue("the guard is worthless after the call", confirmedGuard < start)

        // And the other branch must actually re-arm rather than fall through
        // believing the flag.
        val rearm = body.indexOf("_bleAdvertising.value = false")
        assertTrue(
            "a claimed-but-unconfirmed advertisement must be re-armed, not trusted",
            rearm in 1 until start,
        )
    }

    @Test
    fun `the periodic tick decides through the policy instead of always cycling the scanner`() {
        val body = functionBody(
            crossPlatformMeshSource(),
            "private fun schedulePeriodicScanRestart(generation: Int)",
        )
        assertTrue(
            "the tick must ask BleRadioPolicy.scanTick what to do",
            body.contains("BleRadioPolicy.scanTick("),
        )
        // The precise regression: startBLEScanning() reachable from the tick
        // without a REFRESH decision in front of it.
        val refreshAt = body.indexOf("ScanTick.REFRESH")
        val restartAt = body.indexOf("startBLEScanning()")
        assertTrue("expected a REFRESH branch", refreshAt >= 0)
        assertTrue(
            "the only startBLEScanning() in the tick belongs inside the REFRESH branch",
            restartAt > refreshAt,
        )
        assertEquals(
            "exactly one startBLEScanning() call in the tick, and it is the refresh",
            1,
            Regex("startBLEScanning\\(\\)").findAll(body).count(),
        )
    }

    @Test
    fun `every periodic loop is gated on its own generation`() {
        val source = crossPlatformMeshSource()
        for (fn in listOf(
            "private fun schedulePeriodicScanRestart(generation: Int)",
            "private fun scheduleIdentityAnnounce(generation: Int)",
            "private fun scheduleRouteCleanup(generation: Int)",
            "private fun scheduleDiagnosticsUpdate(generation: Int)",
        )) {
            val body = functionBody(source, fn)
            assertTrue(
                "$fn must exit when its generation is retired, not merely when _isRunning is false",
                body.contains("stillCurrent(generation)"),
            )
            assertFalse(
                "$fn must not loop on _isRunning.value — start() puts it back to true",
                body.contains("while (isActive && _isRunning.value)"),
            )
        }
    }

    @Test
    fun `stop cancels the periodic jobs and retires the generation`() {
        val body = functionBody(crossPlatformMeshSource(), "fun stop()")
        assertTrue("stop() must retire the generation", body.contains("runGeneration.end()"))
        assertTrue("stop() must cancel the timers it started", body.contains("it.cancel()"))
    }
}
