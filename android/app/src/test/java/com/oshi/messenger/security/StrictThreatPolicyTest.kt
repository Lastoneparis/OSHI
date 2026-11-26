package com.oshi.messenger.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 🛡 __STRICT_MODE_2026_11_22__ detection lists. */
class StrictThreatPolicyTest {
    private val own = "com.oshi.messenger"

    @Test fun nonToolAccessibilityServicesAreListedToolsAreNot() {
        val threats = StrictThreatPolicy.evaluate(
            listOf(
                EnabledA11yService("com.google.android.marvin.talkback", "TalkBack", isAccessibilityTool = true),
                EnabledA11yService("com.agent.computeruse", "Agent", isAccessibilityTool = false),
                EnabledA11yService(own, "OSHI", isAccessibilityTool = false),
            ),
            installedPackages = emptySet(), ownPackage = own,
        )
        assertEquals(listOf("com.agent.computeruse"), threats.map { it.packageName })
        assertEquals(StrictThreatKind.ACCESSIBILITY_SERVICE, threats.single().kind)
    }

    @Test fun installedRemoteControlAppsAreListedWithTheirName() {
        val threats = StrictThreatPolicy.evaluate(
            emptyList(),
            installedPackages = setOf("com.teamviewer.quicksupport.market", "com.carriez.flutter_hbb", "com.example.notes"),
            ownPackage = own,
        )
        assertEquals(setOf("TeamViewer QuickSupport", "RustDesk"), threats.map { it.label }.toSet())
        assertTrue(threats.all { it.kind == StrictThreatKind.REMOTE_CONTROL_APP })
    }

    @Test fun aServiceWithSeveralComponentsIsListedOnce() {
        val threats = StrictThreatPolicy.evaluate(
            listOf(
                EnabledA11yService("com.spy", "Spy A", false),
                EnabledA11yService("com.spy", "Spy B", false),
            ),
            emptySet(), own,
        )
        assertEquals(1, threats.size)
    }

    @Test fun nonToolFlag() {
        assertFalse(StrictThreatPolicy.hasNonToolService(emptyList(), own))
        assertFalse(StrictThreatPolicy.hasNonToolService(listOf(EnabledA11yService("t", "T", true)), own))
        assertTrue(StrictThreatPolicy.hasNonToolService(listOf(EnabledA11yService("x", "X", false)), own))
    }

    @Test fun remoteControlListHasKnownHostsAndValidPackageNames() {
        val keys = StrictThreatPolicy.REMOTE_CONTROL_PACKAGES.keys
        listOf("com.teamviewer.quicksupport.market", "com.anydesk.anydeskandroid", "com.carriez.flutter_hbb",
            "net.christianbeier.droidvnc_ng").forEach { assertTrue(it, it in keys) }
        val pkg = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$")
        keys.forEach { assertTrue(it, pkg.matches(it)) }
    }
}
