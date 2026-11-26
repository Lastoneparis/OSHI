package com.oshi.messenger.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 🕵️ __ANTI_STALKERWARE_2026_11_20__ */
class SpySurfaceTest {
    private val own = "com.oshi.messenger"

    @Test fun aThirdPartyAccessibilityServiceIsListed() {
        val apps = SpySurfacePolicy.evaluate(
            listOf(GrantedComponent("com.spy.app", "System Update", SpyPower.ACCESSIBILITY, isSystemApp = false)), own
        )
        assertEquals(listOf("com.spy.app"), apps.map { it.packageName })
    }

    @Test fun realAssistiveToolsAndSystemComponentsAreNotListed() {
        val apps = SpySurfacePolicy.evaluate(
            listOf(
                GrantedComponent("com.google.android.marvin.talkback", "TalkBack", SpyPower.ACCESSIBILITY, false, isAccessibilityTool = true),
                GrantedComponent("com.android.systemui", "System UI", SpyPower.NOTIFICATIONS, isSystemApp = true),
                GrantedComponent("com.google.android.gms", "Find My Device", SpyPower.DEVICE_ADMIN, isSystemApp = true),
                GrantedComponent(own, "OSHI", SpyPower.NOTIFICATIONS, false),
            ), own
        )
        assertTrue(apps.isEmpty())
    }

    @Test fun anAccessibilityToolFlagDoesNotExemptOtherPowers() {
        val apps = SpySurfacePolicy.evaluate(
            listOf(
                GrantedComponent("com.x", "X", SpyPower.ACCESSIBILITY, false, isAccessibilityTool = true),
                GrantedComponent("com.x", "X", SpyPower.NOTIFICATIONS, false),
            ), own
        )
        assertEquals(setOf(SpyPower.NOTIFICATIONS), apps.single().powers)
    }

    @Test fun powersOfOneAppAreGrouped() {
        val apps = SpySurfacePolicy.evaluate(
            listOf(
                GrantedComponent("com.spy", "S", SpyPower.ACCESSIBILITY, false),
                GrantedComponent("com.spy", "S", SpyPower.DEVICE_ADMIN, false),
                GrantedComponent("com.spy", "S", SpyPower.NOTIFICATIONS, false),
            ), own
        )
        assertEquals(1, apps.size)
        assertEquals(SpyPower.values().toSet(), apps.single().powers)
    }

    @Test fun warnsOncePerDistinctSet() {
        val a = SpySurfacePolicy.evaluate(listOf(GrantedComponent("com.spy", "S", SpyPower.ACCESSIBILITY, false)), own)
        assertTrue(SpySurfacePolicy.shouldWarn(a, null))
        assertFalse(SpySurfacePolicy.shouldWarn(a, SpySurfacePolicy.fingerprint(a)))
        val b = SpySurfacePolicy.evaluate(
            listOf(
                GrantedComponent("com.spy", "S", SpyPower.ACCESSIBILITY, false),
                GrantedComponent("com.spy2", "T", SpyPower.NOTIFICATIONS, false),
            ), own
        )
        assertTrue("a new app re-arms the warning", SpySurfacePolicy.shouldWarn(b, SpySurfacePolicy.fingerprint(a)))
        assertFalse(SpySurfacePolicy.shouldWarn(emptyList(), null))
    }
}
