package com.oshi.messenger.network.mesh

import android.Manifest
import com.oshi.messenger.ui.viewmodel.MeshStatus
import com.oshi.messenger.ui.viewmodel.statusFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mesh transport was fully built and never carried a message between two
 * phones sitting side by side. Not because the transport was wrong — the BLE
 * service UUID, the characteristic UUID, the GATT JSON keys and the Bonjour
 * service type all match iOS byte for byte — but because nothing ever held the
 * Bluetooth permissions it needs, and every layer above reported success anyway.
 *
 * These tests pin the three rules that, broken, reproduce that exact silence.
 */
class MeshPermissionPolicyTest {

    private val ANDROID_11 = 30   // pre-split: legacy BLUETOOTH / BLUETOOTH_ADMIN
    private val ANDROID_12 = 31   // BLUETOOTH_SCAN / _ADVERTISE / _CONNECT
    private val ANDROID_13 = 33   // + NEARBY_WIFI_DEVICES
    private val ANDROID_14 = 34

    /** Everything the mesh could want on API 33+, as a grant set. */
    private fun allGranted(sdkInt: Int): Set<String> =
        MeshPermissionPolicy.allRequestable(sdkInt).toSet()

    // ── Rule 1: the policy's strings must BE the platform's strings ──────────
    //
    // MeshPermissionPolicy holds permission names as literals so it can stay
    // free of `android.*` imports and run on the JVM. That freedom is only safe
    // while the literals are exactly the platform constants: a single typo is a
    // permission that is never requested (so never granted) AND never reported
    // missing (so never surfaced) — invisible in both directions, which is
    // precisely the failure mode this whole file exists to prevent.
    //
    // `Manifest.permission.*` are compile-time String constants, so reading them
    // here is a literal comparison and needs no Android runtime.

    @Test
    fun `policy permission names match the platform constants exactly`() {
        assertEquals(Manifest.permission.BLUETOOTH_SCAN, MeshPermissionPolicy.BLUETOOTH_SCAN)
        assertEquals(Manifest.permission.BLUETOOTH_ADVERTISE, MeshPermissionPolicy.BLUETOOTH_ADVERTISE)
        assertEquals(Manifest.permission.BLUETOOTH_CONNECT, MeshPermissionPolicy.BLUETOOTH_CONNECT)
        assertEquals(Manifest.permission.BLUETOOTH, MeshPermissionPolicy.BLUETOOTH_LEGACY)
        assertEquals(Manifest.permission.BLUETOOTH_ADMIN, MeshPermissionPolicy.BLUETOOTH_ADMIN_LEGACY)
        assertEquals(Manifest.permission.ACCESS_FINE_LOCATION, MeshPermissionPolicy.ACCESS_FINE_LOCATION)
        assertEquals(Manifest.permission.ACCESS_COARSE_LOCATION, MeshPermissionPolicy.ACCESS_COARSE_LOCATION)
        assertEquals(Manifest.permission.NEARBY_WIFI_DEVICES, MeshPermissionPolicy.NEARBY_WIFI_DEVICES)
    }

    @Test
    fun `every requestable permission is declared in the manifest`() {
        // The three modern Bluetooth permissions plus the two location ones are
        // AndroidManifest.xml:19-20,36-40. NEARBY_WIFI_DEVICES is :29-31.
        // Requesting a permission the manifest does not declare yields an
        // immediate silent denial with no dialog.
        val declared = setOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        assertEquals(
            "requestable set drifted from AndroidManifest.xml",
            emptyList<String>(),
            MeshPermissionPolicy.allRequestable(ANDROID_14).filterNot { it in declared },
        )
    }

    @Test
    fun `legacy install-time bluetooth permissions are never requested at runtime`() {
        // AndroidManifest.xml:34-35 caps them at maxSdkVersion="30"; they are
        // install-time permissions and handing them to requestPermissions on a
        // modern device produces a dialog that can never be granted.
        val requestable = MeshPermissionPolicy.allRequestable(ANDROID_12)
        assertFalse(MeshPermissionPolicy.BLUETOOTH_LEGACY in requestable)
        assertFalse(MeshPermissionPolicy.BLUETOOTH_ADMIN_LEGACY in requestable)
    }

    // ── Rule 2: advertising and scanning are SEPARATE, and advertising is the
    //           one an iPhone depends on ─────────────────────────────────────
    //
    // iOS learns an Android peer's public key, IP and port by connecting and
    // reading GATT characteristic 0542 (OSHI/CrossPlatformMesh.swift:1414,
    // :1429-1439). Android only ever opens that GATT server from inside
    // startBLEAdvertising (CrossPlatformMesh.kt, startGattServer is called at
    // the tail of it). So BLUETOOTH_ADVERTISE denied ⇒ no advertisement AND no
    // characteristic ⇒ the iPhone cannot see this phone at all, however well
    // this phone scans.

    @Test
    fun `scan grant without advertise grant leaves the phone MUTE not ready`() {
        val scanOnly = setOf(
            MeshPermissionPolicy.BLUETOOTH_SCAN,
            MeshPermissionPolicy.BLUETOOTH_CONNECT,
        )
        assertEquals(MeshPermissionPolicy.Reach.MUTE, MeshPermissionPolicy.reach(ANDROID_12, scanOnly))
        assertTrue(MeshPermissionPolicy.canRun(MeshPermissionPolicy.Lane.BLE_SCAN, ANDROID_12, scanOnly))
        assertFalse(MeshPermissionPolicy.canRun(MeshPermissionPolicy.Lane.BLE_ADVERTISE, ANDROID_12, scanOnly))
        assertFalse(
            "MUTE must never be reported as cross-platform reachable — the iPhone cannot read a GATT server that was never opened",
            MeshPermissionPolicy.isCrossPlatformReachable(ANDROID_12, scanOnly),
        )
    }

    @Test
    fun `advertise grant without scan grant leaves the phone DEAF`() {
        val advertiseOnly = setOf(
            MeshPermissionPolicy.BLUETOOTH_ADVERTISE,
            MeshPermissionPolicy.BLUETOOTH_CONNECT,
        )
        assertEquals(MeshPermissionPolicy.Reach.DEAF, MeshPermissionPolicy.reach(ANDROID_12, advertiseOnly))
    }

    @Test
    fun `no bluetooth grant at all is DEAF_AND_MUTE`() {
        // The shipping default: MainActivity requests only POST_NOTIFICATIONS at
        // startup (MainActivity.kt:588-589) and BLUETOOTH_* only from the Peers
        // route (MainActivity.kt:1085, :2491), which is not a bottom-nav tab.
        assertEquals(
            MeshPermissionPolicy.Reach.DEAF_AND_MUTE,
            MeshPermissionPolicy.reach(ANDROID_14, emptySet()),
        )
        assertFalse(MeshPermissionPolicy.isCrossPlatformReachable(ANDROID_14, emptySet()))
    }

    @Test
    fun `all grants is READY and reachable`() {
        assertEquals(MeshPermissionPolicy.Reach.READY, MeshPermissionPolicy.reach(ANDROID_14, allGranted(ANDROID_14)))
        assertTrue(MeshPermissionPolicy.isCrossPlatformReachable(ANDROID_14, allGranted(ANDROID_14)))
        assertEquals(emptyList<String>(), MeshPermissionPolicy.missing(ANDROID_14, allGranted(ANDROID_14)))
    }

    @Test
    fun `BLE scan does not require location from Android 12 on`() {
        // AndroidManifest.xml:36-38 carries usesPermissionFlags="neverForLocation",
        // so BLUETOOTH_SCAN stands alone. Requiring FINE_LOCATION here would make
        // an approximate-location user permanently DEAF for no reason.
        val scanPerms = MeshPermissionPolicy.permissionsFor(MeshPermissionPolicy.Lane.BLE_SCAN, ANDROID_12)
        assertFalse(MeshPermissionPolicy.ACCESS_FINE_LOCATION in scanPerms)

        val legacyScanPerms = MeshPermissionPolicy.permissionsFor(MeshPermissionPolicy.Lane.BLE_SCAN, ANDROID_11)
        assertTrue("below API 31 BLE scanning genuinely needs location", MeshPermissionPolicy.ACCESS_FINE_LOCATION in legacyScanPerms)
    }

    @Test
    fun `NEARBY_WIFI_DEVICES is only asked for from Android 13 on`() {
        assertFalse(MeshPermissionPolicy.NEARBY_WIFI_DEVICES in MeshPermissionPolicy.allRequestable(ANDROID_12))
        assertTrue(MeshPermissionPolicy.NEARBY_WIFI_DEVICES in MeshPermissionPolicy.allRequestable(ANDROID_13))
    }

    @Test
    fun `a Wi-Fi-only gap does not take out the iOS lane`() {
        // NEARBY_WIFI_DEVICES is a Nearby Connections / Wi-Fi Direct concern and
        // never carries iOS traffic. Missing it must still leave BLE READY, or
        // one irrelevant denial silently kills cross-platform messaging.
        val noWifi = allGranted(ANDROID_13) - MeshPermissionPolicy.NEARBY_WIFI_DEVICES
        assertEquals(MeshPermissionPolicy.Reach.READY, MeshPermissionPolicy.reach(ANDROID_13, noWifi))
        assertTrue(MeshPermissionPolicy.isCrossPlatformReachable(ANDROID_13, noWifi))
        assertFalse(
            "the gap must still be reported so the user can close it",
            MeshPermissionPolicy.missing(ANDROID_13, noWifi).isEmpty(),
        )
    }

    // ── Rule 3: a denied permission must not render as "Searching…" ──────────

    @Test
    fun `missing permissions render as PERMISSION_REQUIRED not SEARCHING`() {
        // The exact shipping state: CrossPlatformMesh.start() set _isRunning=true
        // unconditionally after the BLE bring-up had already bailed at its
        // permission guard, and PeersViewModel mapped that flag straight to
        // SEARCHING. The screen said "Searching…" forever while advertising
        // nothing and scanning nothing.
        val status = statusFor(
            connectedCount = 0,
            discoveredCount = 0,
            state = MeshState.IDLE,
            crossPlatformRunning = true,
            missingPermissions = listOf(MeshPermissionPolicy.BLUETOOTH_SCAN),
        )
        assertNotEquals(
            "a mesh holding no Bluetooth grant must never claim to be searching",
            MeshStatus.SEARCHING, status,
        )
        assertEquals(MeshStatus.PERMISSION_REQUIRED, status)
    }

    @Test
    fun `a peer actually found outranks a missing permission`() {
        // NSD/TCP over a shared Wi-Fi needs no Bluetooth at all. If something
        // did connect, that is the truth worth showing.
        assertEquals(
            MeshStatus.CONNECTED,
            statusFor(1, 0, MeshState.IDLE, true, listOf(MeshPermissionPolicy.BLUETOOTH_SCAN)),
        )
        assertEquals(
            MeshStatus.PEERS_FOUND,
            statusFor(0, 1, MeshState.IDLE, true, listOf(MeshPermissionPolicy.BLUETOOTH_SCAN)),
        )
    }

    @Test
    fun `full grants and a running mesh still render as SEARCHING`() {
        assertEquals(
            MeshStatus.SEARCHING,
            statusFor(0, 0, MeshState.DISCOVERING, true, emptyList()),
        )
    }

    @Test
    fun `nothing running and nothing missing is OFFLINE`() {
        assertEquals(
            MeshStatus.OFFLINE,
            statusFor(0, 0, MeshState.IDLE, false, emptyList()),
        )
    }
}

/**
 * __MESH_PERMANENT_DENIAL_2026_10_12__
 *
 * Google Play, 12/10/2026, Honor 90 Lite + Galaxy Tab A9, v1.6.36, ★★★★★:
 * *"It's a good messaging app, however the Bluetooth permission feature…
 * nothing happens"*.
 *
 * It did nothing because `requestPermissions` on a permanently denied
 * permission returns WITHOUT showing a dialog, and the screen could not tell
 * that had happened: it tracked refusals in a `remember { mutableStateOf(false) }`,
 * which reset every time the user left the screen, so the card went back to
 * offering an "Allow" button that could never produce a dialog again.
 */
class MeshPermanentDenialTest {

    private val SCAN = MeshPermissionPolicy.BLUETOOTH_SCAN
    private val CONNECT = MeshPermissionPolicy.BLUETOOTH_CONNECT
    private val ADVERTISE = MeshPermissionPolicy.BLUETOOTH_ADVERTISE

    // ─────────── the three states Android cannot distinguish for us ───────────

    @Test
    fun `never asked is not permanently denied`() {
        // A first-run user must get a normal Allow button, not a trip to Settings.
        assertFalse(
            MeshPermissionPolicy.isPermanentlyDenied(
                granted = false, everAsked = false, shouldShowRationale = false
            )
        )
    }

    @Test
    fun `refused once is still askable`() {
        // Android says "show your rationale and ask again".
        assertFalse(
            MeshPermissionPolicy.isPermanentlyDenied(
                granted = false, everAsked = true, shouldShowRationale = true
            )
        )
    }

    @Test
    fun `refused twice is beyond asking`() {
        // shouldShowRationale back to false AFTER we have asked: no dialog will
        // ever appear again. This is the reported bug's state.
        assertTrue(
            MeshPermissionPolicy.isPermanentlyDenied(
                granted = false, everAsked = true, shouldShowRationale = false
            )
        )
    }

    @Test
    fun `a granted permission is never permanently denied`() {
        for (asked in listOf(true, false)) {
            for (rationale in listOf(true, false)) {
                assertFalse(
                    "asked=$asked rationale=$rationale",
                    MeshPermissionPolicy.isPermanentlyDenied(
                        granted = true, everAsked = asked, shouldShowRationale = rationale
                    )
                )
            }
        }
    }

    @Test
    fun `never asked and permanently denied are the same Android call`() {
        // The whole reason the app has to persist "we asked": these two states
        // are indistinguishable from shouldShowRequestPermissionRationale alone,
        // and they need OPPOSITE buttons.
        val neverAsked = MeshPermissionPolicy.isPermanentlyDenied(
            granted = false, everAsked = false, shouldShowRationale = false
        )
        val refusedTwice = MeshPermissionPolicy.isPermanentlyDenied(
            granted = false, everAsked = true, shouldShowRationale = false
        )
        assertNotEquals(neverAsked, refusedTwice)
    }

    // ─────────────────────── what the screen must offer ───────────────────────

    @Test
    fun `the card sends the user to settings only once asking is useless`() {
        val missing = listOf(SCAN, CONNECT, ADVERTISE)

        // First run: nothing asked yet -> Allow button.
        assertFalse(
            MeshPermissionPolicy.mustOpenAppSettings(
                stillMissing = missing, everAsked = emptySet(), shouldShowRationale = emptySet()
            )
        )
        // Asked once, refused: Android still allows a dialog -> Allow button.
        assertFalse(
            MeshPermissionPolicy.mustOpenAppSettings(
                stillMissing = missing, everAsked = missing.toSet(), shouldShowRationale = missing.toSet()
            )
        )
        // Asked, refused, no rationale left -> Settings.
        assertTrue(
            MeshPermissionPolicy.mustOpenAppSettings(
                stillMissing = missing, everAsked = missing.toSet(), shouldShowRationale = emptySet()
            )
        )
    }

    @Test
    fun `one permanently denied permission is enough to need settings`() {
        // BLUETOOTH_ADVERTISE alone beyond asking still makes us invisible to iOS
        // (no GATT server, so no characteristic 0542 to read) — Reach.MUTE.
        assertTrue(
            MeshPermissionPolicy.mustOpenAppSettings(
                stillMissing = listOf(SCAN, ADVERTISE),
                everAsked = setOf(ADVERTISE),
                shouldShowRationale = setOf(SCAN),
            )
        )
    }

    @Test
    fun `nothing missing never asks for anything`() {
        assertFalse(
            MeshPermissionPolicy.mustOpenAppSettings(
                stillMissing = emptyList(),
                everAsked = setOf(SCAN, CONNECT, ADVERTISE),
                shouldShowRationale = emptySet(),
            )
        )
    }

    // ───────────────── reading the launcher's result honestly ─────────────────

    @Test
    fun `an empty result map is a refusal`() {
        // THE one that kept the button dead: no dialog shown means nothing was
        // granted. The old call site read `grants.isNotEmpty() && any { !it }`,
        // so an empty map meant "no refusal seen" and the screen kept offering
        // Allow for ever.
        assertTrue(MeshPermissionPolicy.resultIsRefusal(emptyMap()))
    }

    @Test
    fun `a full grant is not a refusal`() {
        assertFalse(
            MeshPermissionPolicy.resultIsRefusal(
                mapOf(SCAN to true, CONNECT to true, ADVERTISE to true)
            )
        )
    }

    @Test
    fun `a partial grant is a refusal`() {
        assertTrue(
            MeshPermissionPolicy.resultIsRefusal(
                mapOf(SCAN to true, CONNECT to true, ADVERTISE to false)
            )
        )
    }

    @Test
    fun `the old rule called an empty result a success`() {
        // Replay the shipped expression and assert it disagrees with the new one.
        val grants = emptyMap<String, Boolean>()
        val oldSawRefusal = grants.isNotEmpty() && grants.values.any { !it }
        assertFalse(oldSawRefusal)
        assertTrue(MeshPermissionPolicy.resultIsRefusal(grants))
        assertNotEquals(oldSawRefusal, MeshPermissionPolicy.resultIsRefusal(grants))
    }

    // ────────────────── one permission set, not two ──────────────────

    @Test
    fun `the BLE lanes never ask for location or wifi on modern Android`() {
        // The gate used to add ACCESS_FINE_LOCATION and NEARBY_WIFI_DEVICES to
        // this request on every Android version. On API 31+ the manifest carries
        // neverForLocation and the BLE lanes do not need either, and the extra
        // dialogs are what burned the user's refusals before the card appeared.
        val sdk = 34
        val bleSet = (MeshPermissionPolicy.permissionsFor(MeshPermissionPolicy.Lane.BLE_ADVERTISE, sdk) +
            MeshPermissionPolicy.permissionsFor(MeshPermissionPolicy.Lane.BLE_SCAN, sdk)).distinct()

        assertFalse(MeshPermissionPolicy.ACCESS_FINE_LOCATION in bleSet)
        assertFalse(MeshPermissionPolicy.NEARBY_WIFI_DEVICES in bleSet)
        assertEquals(setOf(SCAN, CONNECT, ADVERTISE), bleSet.toSet())
    }

    @Test
    fun `the policy literals are the platform constants`() {
        assertEquals(Manifest.permission.BLUETOOTH_SCAN, MeshPermissionPolicy.BLUETOOTH_SCAN)
        assertEquals(Manifest.permission.BLUETOOTH_CONNECT, MeshPermissionPolicy.BLUETOOTH_CONNECT)
        assertEquals(Manifest.permission.BLUETOOTH_ADVERTISE, MeshPermissionPolicy.BLUETOOTH_ADVERTISE)
    }
}
