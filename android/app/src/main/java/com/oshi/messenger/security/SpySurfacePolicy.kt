package com.oshi.messenger.security

// 🕵️ __ANTI_STALKERWARE_2026_11_20__ Pure half of SpySurface (no Android): what is
// worth telling the person, and when. JVM-testable (SpySurfaceTest).

/** One app that holds a spy-capable permission. */
data class SpyCapableApp(val packageName: String, val label: String, val powers: Set<SpyPower>)

enum class SpyPower { ACCESSIBILITY, NOTIFICATIONS, DEVICE_ADMIN }

/** Raw observation of one granted component (pure, for [SpySurface.evaluate]). */
data class GrantedComponent(
    val packageName: String,
    val label: String,
    val power: SpyPower,
    val isSystemApp: Boolean,
    /** Only meaningful for ACCESSIBILITY: the service declares android:isAccessibilityTool. */
    val isAccessibilityTool: Boolean = false,
)

object SpySurfacePolicy {
    /** Pure decision: which granted components are worth telling the person about. */
    fun evaluate(granted: List<GrantedComponent>, ownPackage: String): List<SpyCapableApp> =
        granted.asSequence()
            .filter { it.packageName != ownPackage }
            .filter { !it.isSystemApp }
            .filter { !(it.power == SpyPower.ACCESSIBILITY && it.isAccessibilityTool) }
            .groupBy { it.packageName }
            .map { (pkg, list) -> SpyCapableApp(pkg, list.first().label, list.map { it.power }.toSet()) }
            .sortedBy { it.packageName }

    /** Stable identity of a finding set: a new app or a new power re-arms the warning. */
    fun fingerprint(apps: List<SpyCapableApp>): String =
        apps.joinToString(";") { a -> a.packageName + ":" + a.powers.map { it.name }.sorted().joinToString(",") }

    fun shouldWarn(apps: List<SpyCapableApp>, lastWarned: String?): Boolean =
        apps.isNotEmpty() && fingerprint(apps) != lastWarned

}
