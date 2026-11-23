package com.oshi.messenger.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

// =============================================================================
// __DEVICE_INTEGRITY_2026_10_01__ "Tell the person when the phone itself looks
// compromised."
//
// End-to-end encryption protects a message on the wire, not on a phone where some
// other software runs as root or hooks into apps: there, the screen and the keyboard
// can be read before OSHI ever encrypts anything. An app cannot fix that, but it can
// say so. These checks are HEURISTICS — well-hidden root (Magisk DenyList, Shamiko,
// renamed packages) passes them — so the UI only ever says "OSHI detected signs
// that…", never "your phone is safe". We never wipe keys or refuse to run: a false
// positive must not cost anyone their message history.
// =============================================================================

/** __DEVICE_INTEGRITY_2026_10_01__ One reason the device was flagged. */
enum class IntegrityFinding {
    SU_BINARY,
    MAGISK,
    ROOT_MANAGER_APP,
    HOOK_FRAMEWORK,
    TEST_KEYS,
    DEBUGGABLE_BUILD,
    FRIDA,
}

/**
 * __DEVICE_INTEGRITY_2026_10_01__ Raw observations, gathered on the device by
 * [DeviceIntegrity.collect] and judged by the pure [DeviceIntegrity.evaluate] —
 * split so the decision is provable on the plain JVM without a rooted phone.
 */
data class IntegritySignals(
    val existingPaths: Set<String> = emptySet(),
    val installedPackages: Set<String> = emptySet(),
    val buildTags: String? = null,
    val fingerprint: String? = null,
    val hardware: String? = null,
    val model: String? = null,
    val buildType: String? = null,
    /** Value of the `ro.debuggable` system property, null when unreadable. */
    val roDebuggable: String? = null,
    val fridaPortListening: Boolean = false,
    val fridaLibraryMapped: Boolean = false,
)

data class IntegrityVerdict(
    val findings: Set<IntegrityFinding>,
    val isEmulator: Boolean,
) {
    /** Emulators are test rigs, not compromised phones: never warn there. */
    val isFlagged: Boolean get() = !isEmulator && findings.isNotEmpty()

    companion object {
        val CLEAN = IntegrityVerdict(emptySet(), isEmulator = false)
    }
}

object DeviceIntegrity {

    val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
    )

    val MAGISK_PATHS = listOf(
        "/sbin/.magisk",
        "/data/adb/magisk",
    )

    /** Must stay in sync with the <queries><package> list in AndroidManifest.xml. */
    val ROOT_MANAGER_PACKAGES = listOf(
        "com.topjohnwu.magisk",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
    )
    val HOOK_PACKAGES = listOf(
        "de.robv.android.xposed.installer",
        "org.lsposed.manager",
    )

    /** frida-server's default listening port (27042 = 0x69A2). */
    const val FRIDA_DEFAULT_PORT = 27042

    /**
     * Pure decision. Every input is a plain value so the tests can build any device
     * by hand — including the emulator case, which must NOT be flagged (its system
     * image is test-keys + debuggable by design, and that is where we test).
     */
    fun evaluate(s: IntegritySignals): IntegrityVerdict {
        val findings = mutableSetOf<IntegrityFinding>()
        if (SU_PATHS.any { it in s.existingPaths }) findings += IntegrityFinding.SU_BINARY
        if (MAGISK_PATHS.any { it in s.existingPaths } || "com.topjohnwu.magisk" in s.installedPackages) {
            findings += IntegrityFinding.MAGISK
        }
        if (ROOT_MANAGER_PACKAGES.any { it != "com.topjohnwu.magisk" && it in s.installedPackages }) {
            findings += IntegrityFinding.ROOT_MANAGER_APP
        }
        if (HOOK_PACKAGES.any { it in s.installedPackages }) findings += IntegrityFinding.HOOK_FRAMEWORK
        if (s.buildTags?.contains("test-keys") == true) findings += IntegrityFinding.TEST_KEYS
        // ro.debuggable=1 is normal on userdebug/eng images; on a "user" (retail) build
        // it means someone flipped it — typically MagiskHide props or a patched boot.
        if (s.roDebuggable == "1" && s.buildType == "user") findings += IntegrityFinding.DEBUGGABLE_BUILD
        if (s.fridaPortListening || s.fridaLibraryMapped) findings += IntegrityFinding.FRIDA
        return IntegrityVerdict(findings, isEmulator = isEmulator(s))
    }

    fun isEmulator(s: IntegritySignals): Boolean {
        val fp = s.fingerprint.orEmpty().lowercase()
        val hw = s.hardware.orEmpty().lowercase()
        val model = s.model.orEmpty().lowercase()
        return fp.startsWith("generic") ||
            fp.startsWith("unknown") ||
            fp.contains("emulator") ||
            fp.contains("/sdk_gphone") ||
            fp.contains("/sdk_") ||
            hw == "goldfish" || hw == "ranchu" ||
            model.contains("emulator") ||
            model.contains("android sdk built for")
    }

    // ---------------------------------------------------------------------------
    // Device side (best effort; every probe swallows its own failure)
    // ---------------------------------------------------------------------------

    private val _verdict = MutableStateFlow<IntegrityVerdict?>(null)
    /** null until the first [check] completes. */
    val verdict: StateFlow<IntegrityVerdict?> = _verdict.asStateFlow()

    /** Runs the probes off the main thread (file stats + /proc reads) and caches. */
    suspend fun check(context: Context): IntegrityVerdict {
        _verdict.value?.let { return it }
        val v = withContext(Dispatchers.IO) {
            try {
                evaluate(collect(context.applicationContext))
            } catch (_: Throwable) {
                IntegrityVerdict.CLEAN
            }
        }
        _verdict.value = v
        return v
    }

    fun collect(context: Context): IntegritySignals {
        val paths = (SU_PATHS + MAGISK_PATHS).filterTo(mutableSetOf()) { p ->
            try { File(p).exists() } catch (_: Exception) { false }
        }
        val pm = context.packageManager
        val packages = (ROOT_MANAGER_PACKAGES + HOOK_PACKAGES).filterTo(mutableSetOf()) { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            } catch (_: Exception) {
                false
            }
        }
        return IntegritySignals(
            existingPaths = paths,
            installedPackages = packages,
            buildTags = Build.TAGS,
            fingerprint = Build.FINGERPRINT,
            hardware = Build.HARDWARE,
            model = Build.MODEL,
            buildType = Build.TYPE,
            roDebuggable = systemProperty("ro.debuggable"),
            fridaPortListening = fridaPortListening(),
            fridaLibraryMapped = fridaLibraryMapped(),
        )
    }

    private fun systemProperty(name: String): String? = try {
        val cls = Class.forName("android.os.SystemProperties")
        (cls.getMethod("get", String::class.java).invoke(null, name) as? String)?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    /**
     * Reads the kernel's socket table instead of connecting to the port: no socket is
     * opened, nothing leaves the device. Android 10+ SELinux usually denies apps
     * /proc/net, in which case this simply reports false.
     */
    private fun fridaPortListening(): Boolean {
        val hexPort = ":%04X".format(FRIDA_DEFAULT_PORT)
        for (table in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            try {
                File(table).useLines { lines ->
                    if (lines.drop(1).any { procNetLineListensOn(it, hexPort) }) return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    /** `sl local_address rem_address st …` — st 0A = LISTEN. */
    fun procNetLineListensOn(line: String, hexPort: String): Boolean {
        val cols = line.trim().split(Regex("\\s+"))
        if (cols.size < 4) return false
        return cols[1].endsWith(hexPort, ignoreCase = true) && cols[3].equals("0A", ignoreCase = true)
    }

    /** frida-gadget / frida-agent injected into OSHI's own process. */
    private fun fridaLibraryMapped(): Boolean = try {
        File("/proc/self/maps").useLines { lines ->
            lines.any { mapsLineLooksLikeFrida(it) }
        }
    } catch (_: Exception) {
        false
    }

    fun mapsLineLooksLikeFrida(line: String): Boolean {
        val l = line.lowercase()
        return l.contains("frida-agent") || l.contains("frida-gadget") || l.contains("libfrida")
    }
}
