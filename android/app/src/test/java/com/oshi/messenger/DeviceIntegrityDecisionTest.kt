package com.oshi.messenger

import com.oshi.messenger.security.DeviceIntegrity
import com.oshi.messenger.security.IntegrityFinding
import com.oshi.messenger.security.IntegritySignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DEVICE_INTEGRITY_2026_10_01__ The compromised-device decision, on the plain JVM.
 * Probes (file stats, PackageManager, /proc) are gathered by DeviceIntegrity.collect;
 * the verdict is the pure DeviceIntegrity.evaluate, so every device is built by hand.
 */
class DeviceIntegrityDecisionTest {

    private val retailPixel = IntegritySignals(
        buildTags = "release-keys",
        fingerprint = "google/panther/panther:14/UQ1A.240205.004/11269751:user/release-keys",
        hardware = "panther",
        model = "Pixel 7",
        buildType = "user",
        roDebuggable = "0",
    )

    @Test
    fun `stock retail phone is not flagged`() {
        val v = DeviceIntegrity.evaluate(retailPixel)
        assertTrue(v.findings.isEmpty())
        assertFalse(v.isEmulator)
        assertFalse(v.isFlagged)
    }

    @Test
    fun `every su path is a finding`() {
        for (p in DeviceIntegrity.SU_PATHS) {
            val v = DeviceIntegrity.evaluate(retailPixel.copy(existingPaths = setOf(p)))
            assertEquals(p, setOf(IntegrityFinding.SU_BINARY), v.findings)
            assertTrue(p, v.isFlagged)
        }
    }

    @Test
    fun `magisk by path or by package`() {
        assertEquals(
            setOf(IntegrityFinding.MAGISK),
            DeviceIntegrity.evaluate(retailPixel.copy(existingPaths = setOf("/data/adb/magisk"))).findings,
        )
        assertEquals(
            setOf(IntegrityFinding.MAGISK),
            DeviceIntegrity.evaluate(retailPixel.copy(existingPaths = setOf("/sbin/.magisk"))).findings,
        )
        assertEquals(
            setOf(IntegrityFinding.MAGISK),
            DeviceIntegrity.evaluate(retailPixel.copy(installedPackages = setOf("com.topjohnwu.magisk"))).findings,
        )
    }

    @Test
    fun `root managers and hooking frameworks`() {
        for (pkg in listOf("eu.chainfire.supersu", "com.koushikdutta.superuser")) {
            val v = DeviceIntegrity.evaluate(retailPixel.copy(installedPackages = setOf(pkg)))
            assertEquals(pkg, setOf(IntegrityFinding.ROOT_MANAGER_APP), v.findings)
        }
        for (pkg in listOf("de.robv.android.xposed.installer", "org.lsposed.manager")) {
            val v = DeviceIntegrity.evaluate(retailPixel.copy(installedPackages = setOf(pkg)))
            assertEquals(pkg, setOf(IntegrityFinding.HOOK_FRAMEWORK), v.findings)
        }
    }

    @Test
    fun `unrelated packages are ignored`() {
        val v = DeviceIntegrity.evaluate(
            retailPixel.copy(installedPackages = setOf("com.whatsapp", "com.google.android.inputmethod.latin"))
        )
        assertFalse(v.isFlagged)
    }

    @Test
    fun `test-keys build is flagged`() {
        val v = DeviceIntegrity.evaluate(retailPixel.copy(buildTags = "test-keys"))
        assertEquals(setOf(IntegrityFinding.TEST_KEYS), v.findings)
        assertTrue(v.isFlagged)
    }

    @Test
    fun `ro_debuggable only counts on a user build`() {
        assertEquals(
            setOf(IntegrityFinding.DEBUGGABLE_BUILD),
            DeviceIntegrity.evaluate(retailPixel.copy(roDebuggable = "1")).findings,
        )
        // userdebug / eng images are debuggable by design.
        assertFalse(DeviceIntegrity.evaluate(retailPixel.copy(roDebuggable = "1", buildType = "userdebug")).isFlagged)
        // Unreadable property ⇒ no finding, not a guess.
        assertFalse(DeviceIntegrity.evaluate(retailPixel.copy(roDebuggable = null)).isFlagged)
    }

    @Test
    fun `frida port or library`() {
        assertEquals(
            setOf(IntegrityFinding.FRIDA),
            DeviceIntegrity.evaluate(retailPixel.copy(fridaPortListening = true)).findings,
        )
        assertEquals(
            setOf(IntegrityFinding.FRIDA),
            DeviceIntegrity.evaluate(retailPixel.copy(fridaLibraryMapped = true)).findings,
        )
    }

    @Test
    fun `proc net tcp LISTEN line on 27042 is recognised`() {
        val hex = ":%04X".format(DeviceIntegrity.FRIDA_DEFAULT_PORT)
        assertEquals(":69A2", hex)
        val listen = "   0: 0100007F:69A2 00000000:0000 0A 00000000:00000000 00:00000000 00000000  2000        0 12345 1"
        val established = "   1: 0100007F:69A2 0100007F:C350 01 00000000:00000000 00:00000000 00000000  2000        0 12346 1"
        val otherPort = "   2: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000  2000        0 12347 1"
        assertTrue(DeviceIntegrity.procNetLineListensOn(listen, hex))
        assertFalse(DeviceIntegrity.procNetLineListensOn(established, hex))
        assertFalse(DeviceIntegrity.procNetLineListensOn(otherPort, hex))
        assertFalse(DeviceIntegrity.procNetLineListensOn("garbage", hex))
    }

    @Test
    fun `maps line with frida agent is recognised`() {
        assertTrue(DeviceIntegrity.mapsLineLooksLikeFrida("7f00-7f10 r-xp 0 fd:00 1 /data/local/tmp/re.frida.server/frida-agent-64.so"))
        assertTrue(DeviceIntegrity.mapsLineLooksLikeFrida("7f00-7f10 r-xp 0 fd:00 1 /data/app/x/lib/arm64/libfrida-gadget.so"))
        assertFalse(DeviceIntegrity.mapsLineLooksLikeFrida("7f00-7f10 r-xp 0 fd:00 1 /system/lib64/libc.so"))
    }

    @Test
    fun `emulator is never flagged even with root signals`() {
        val emulator = IntegritySignals(
            existingPaths = setOf("/system/xbin/su"),
            buildTags = "test-keys",
            fingerprint = "google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.036/10924223:userdebug/dev-keys",
            hardware = "ranchu",
            model = "sdk_gphone64_arm64",
            buildType = "userdebug",
            roDebuggable = "1",
        )
        val v = DeviceIntegrity.evaluate(emulator)
        assertTrue(v.isEmulator)
        assertFalse(v.isFlagged)
    }

    @Test
    fun `emulator recognised by each fingerprint shape`() {
        val shapes = listOf(
            IntegritySignals(fingerprint = "generic/sdk/generic:8.0.0/OSR1/123:userdebug/test-keys"),
            IntegritySignals(fingerprint = "generic_x86/sdk_x86/generic_x86:9/PSR1/1:user/release-keys"),
            IntegritySignals(fingerprint = "Android/sdk_gphone_x86/generic_x86:11/RSR1/1:userdebug/dev-keys"),
            IntegritySignals(hardware = "goldfish"),
            IntegritySignals(model = "Android SDK built for x86"),
        )
        for (s in shapes) assertTrue(s.toString(), DeviceIntegrity.isEmulator(s))
        assertFalse(DeviceIntegrity.isEmulator(retailPixel))
    }
}
