package com.oshi.messenger.network.mesh

/**
 * Which runtime permissions the mesh actually needs, and what it can still do
 * with the ones it has.
 *
 * Deliberately pure Kotlin — no `android.*` imports, `sdkInt` passed in rather
 * than read from `Build.VERSION` — so the decision table runs under JVM unit
 * tests instead of only being exercisable on two phones in the same room. Same
 * shape as [com.oshi.messenger.service.CheckInPermissionPolicy].
 *
 * ─────────────────────────── why this file exists ───────────────────────────
 *
 * The mesh transport was fully built and, in practice, never carried a single
 * message. Not because the transport was wrong — because nothing ever held the
 * permissions it needs, and every layer above reported success anyway:
 *
 *  • `MainActivity.requestNotificationPermission` (MainActivity.kt:1668-1677) is
 *    the ONLY permission request at startup. The comment at MainActivity.kt:588
 *    says so outright: "Request only notification permission at startup (all
 *    others are lazy)".
 *  • The only place BLUETOOTH_SCAN / _ADVERTISE / _CONNECT are ever requested is
 *    `MeshPermissionGate` (MainActivity.kt:2491-2536), attached to exactly one
 *    route — `composable("peers")` (MainActivity.kt:1085) — which is not a
 *    bottom-nav tab. It is reached only from Settings → Nearby
 *    (MainActivity.kt:1187).
 *  • So a user who opens OSHI and chats never grants a Bluetooth permission.
 *    [CrossPlatformMesh.startBLEAdvertising] returns at its permission guard,
 *    [MeshNetworkManager.startAdvertising] returns at its own, and both log to
 *    logcat and nothing else.
 *  • `CrossPlatformMesh._isRunning` is then set to `true` regardless
 *    (CrossPlatformMesh.kt:185), and `PeersViewModel.meshStatus` maps
 *    `cpRunning` to `SEARCHING`. The screen says "Searching…" forever while
 *    nothing is scanning and nothing is advertising.
 *
 * A denial that is only ever written to logcat is indistinguishable from a
 * transport that does not work. This object exists so the denial has a name, a
 * value, and a test.
 *
 * iOS needs no equivalent: CoreBluetooth and MultipeerConnectivity get their
 * grants from Info.plist usage strings and a single system prompt at first use,
 * so there is no iOS file to mirror here.
 */
object MeshPermissionPolicy {

    // ───────────────────────────── permission names ─────────────────────────────
    //
    // Held as literals rather than `android.Manifest.permission.*` so this file
    // keeps its no-`android.*` property. They are asserted against the platform
    // constants at the two call sites, and `MeshPermissionPolicyTest` pins the
    // exact strings — a typo here would otherwise be a permission that is never
    // requested and never reported missing.

    const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
    const val BLUETOOTH_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"
    const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val BLUETOOTH_LEGACY = "android.permission.BLUETOOTH"
    const val BLUETOOTH_ADMIN_LEGACY = "android.permission.BLUETOOTH_ADMIN"
    const val ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val ACCESS_COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
    const val NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES"

    /** API 31. Bluetooth permissions split out of location. */
    const val SDK_S = 31

    /** API 33. `NEARBY_WIFI_DEVICES` replaces location for Wi-Fi Direct. */
    const val SDK_TIRAMISU = 33

    // ─────────────────────────────── the lanes ───────────────────────────────

    /**
     * The three discovery lanes, kept apart because they fail independently and
     * the user-visible consequence differs.
     */
    enum class Lane {
        /**
         * BLE advertise + GATT server. This is the half an **iPhone reads**:
         * iOS scans, finds our advertisement, connects and reads characteristic
         * 0542 to learn our public key. Without it we are invisible to iOS no
         * matter how well we scan.
         */
        BLE_ADVERTISE,

        /** BLE scan + GATT client — the half that finds an iPhone. */
        BLE_SCAN,

        /**
         * Google Nearby Connections (Android ↔ Android only) and Wi-Fi Direct.
         * Never carries iOS traffic; listed so a partial grant is reported
         * honestly rather than folded into "mesh is off".
         */
        NEARBY,
    }

    /**
     * Permissions [lane] cannot run without, on [sdkInt].
     *
     * Note BLE_SCAN needs `ACCESS_FINE_LOCATION` below API 31 only: from
     * Android 12 the manifest carries `usesPermissionFlags="neverForLocation"`
     * (AndroidManifest.xml:36-38) and `BLUETOOTH_SCAN` stands alone.
     */
    fun permissionsFor(lane: Lane, sdkInt: Int): List<String> = when (lane) {
        Lane.BLE_ADVERTISE ->
            if (sdkInt >= SDK_S) listOf(BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT)
            else listOf(BLUETOOTH_LEGACY, BLUETOOTH_ADMIN_LEGACY)

        Lane.BLE_SCAN ->
            if (sdkInt >= SDK_S) listOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT)
            else listOf(BLUETOOTH_LEGACY, BLUETOOTH_ADMIN_LEGACY, ACCESS_FINE_LOCATION)

        Lane.NEARBY -> buildList {
            add(ACCESS_FINE_LOCATION)
            add(ACCESS_COARSE_LOCATION)
            if (sdkInt >= SDK_S) {
                add(BLUETOOTH_SCAN); add(BLUETOOTH_ADVERTISE); add(BLUETOOTH_CONNECT)
            }
            if (sdkInt >= SDK_TIRAMISU) add(NEARBY_WIFI_DEVICES)
        }
    }

    /**
     * __WIFI_AWARE_2026_11_25__ What Wi-Fi Aware needs: `NEARBY_WIFI_DEVICES` from
     * Android 13, `ACCESS_FINE_LOCATION` below. Deliberately NOT a new [Lane]:
     * both are already in the [Lane.NEARBY] set, so Wi-Fi Aware adds no prompt
     * of its own (pinned by `WifiAwareArbiterTest`).
     */
    fun wifiAwarePermissions(sdkInt: Int): List<String> =
        if (sdkInt >= SDK_TIRAMISU) listOf(NEARBY_WIFI_DEVICES) else listOf(ACCESS_FINE_LOCATION)

    /** Everything the mesh would ask for on [sdkInt], de-duplicated, in ask order. */
    fun allRequestable(sdkInt: Int): List<String> =
        Lane.entries.flatMap { permissionsFor(it, sdkInt) }
            .distinct()
            // The legacy pair is manifest-declared with `maxSdkVersion="30"`
            // (AndroidManifest.xml:34-35) and is install-time, not runtime:
            // handing it to `requestPermissions` on any modern device produces a
            // dialog that cannot be granted.
            .filter { sdkInt >= SDK_S || (it != BLUETOOTH_LEGACY && it != BLUETOOTH_ADMIN_LEGACY) }

    /** Which of [allRequestable] are not in [granted]. Empty means nothing to ask for. */
    fun missing(sdkInt: Int, granted: Set<String>): List<String> =
        allRequestable(sdkInt).filterNot { it in granted }

    /** True when every permission [lane] needs is held. */
    fun canRun(lane: Lane, sdkInt: Int, granted: Set<String>): Boolean =
        permissionsFor(lane, sdkInt).all { it in granted }

    // ───────────────────────────── the verdict ─────────────────────────────

    /**
     * What the mesh can honestly claim to be doing. The point of the enum is
     * that [DEAF_AND_MUTE] and [MUTE] are *distinguishable from* [READY] — the
     * bug this whole file addresses is that they were not.
     */
    enum class Reach {
        /** Advertising and scanning. An iPhone in range can see us and we can see it. */
        READY,

        /**
         * Scanning but not advertising. **We can see an iPhone; it can never see
         * us.** The asymmetry is not academic: iOS learns an Android peer's
         * public key by reading GATT characteristic 0542, which
         * `CrossPlatformMesh.startGattServer` only ever opens from inside
         * `startBLEAdvertising` — so with BLUETOOTH_ADVERTISE denied there is no
         * characteristic to read even if the iPhone somehow found us.
         */
        MUTE,

        /** Advertising but not scanning — an iPhone can find us, we cannot find it. */
        DEAF,

        /** Neither. Discovery cannot happen; every message will take the internet. */
        DEAF_AND_MUTE,
    }

    fun reach(sdkInt: Int, granted: Set<String>): Reach {
        val canAdvertise = canRun(Lane.BLE_ADVERTISE, sdkInt, granted)
        val canScan = canRun(Lane.BLE_SCAN, sdkInt, granted)
        return when {
            canAdvertise && canScan -> Reach.READY
            canScan -> Reach.MUTE
            canAdvertise -> Reach.DEAF
            else -> Reach.DEAF_AND_MUTE
        }
    }

    /**
     * True when the mesh has enough to reach an iPhone in both directions.
     * The `PeersScreen` status card and the `CrossPlatformMesh` bring-up both
     * read this rather than each keeping their own idea of "working".
     */
    fun isCrossPlatformReachable(sdkInt: Int, granted: Set<String>): Boolean =
        reach(sdkInt, granted) == Reach.READY

    // ──────────────── beyond the reach of a dialog (__MESH_PERMANENT_DENIAL_2026_10_12__) ────────────────
    //
    // Reported 2026-10-12 on Google Play (Honor 90 Lite + Galaxy Tab A9,
    // v1.6.36): "the Bluetooth permission feature… nothing happens".
    //
    // Android exposes three states through two calls, and they are NOT
    // distinguishable from one of them:
    //
    //   never asked        checkSelfPermission=DENIED  shouldShowRationale=false
    //   denied once        checkSelfPermission=DENIED  shouldShowRationale=TRUE
    //   denied permanently checkSelfPermission=DENIED  shouldShowRationale=false
    //
    // The first and the last are identical. So "have we ever asked?" is a fact
    // only the app can know, and it has to OUTLIVE THE PROCESS: a refusal from
    // last week is still a refusal, and `requestPermissions` for it returns
    // instantly with no dialog.
    //
    // `PeersScreen` previously decided this from `deniedInSession`, a
    // `remember { mutableStateOf(false) }` — session-scoped AND
    // composition-scoped. Leaving the screen reset it to false, so the card
    // went back to offering "Allow", and the button called a launcher that
    // could not produce a dialog. Tapping it did nothing, every time, forever.

    /**
     * True when [permission] cannot be obtained by asking any more — the only
     * remaining route is Android's app-settings page.
     *
     * [everAsked] must come from storage that survives a restart, written at
     * the moment a request is launched.
     */
    fun isPermanentlyDenied(granted: Boolean, everAsked: Boolean, shouldShowRationale: Boolean): Boolean =
        !granted && everAsked && !shouldShowRationale

    /**
     * True when at least one of [stillMissing] is beyond asking, so the screen
     * must offer app settings rather than a button that does nothing.
     *
     * [stillMissing] is passed in rather than derived from [missing] on purpose:
     * `PeersScreen` asks only for the BLE lanes' permissions, not for
     * `ACCESS_FINE_LOCATION` / `NEARBY_WIFI_DEVICES`, and sending the user to
     * settings over a permission the screen never requests would be its own bug.
     *
     * A permission we have never asked for is NOT permanently denied, so a
     * first-run user still gets a normal "Allow" button.
     */
    fun mustOpenAppSettings(stillMissing: List<String>,
                            everAsked: Set<String>,
                            shouldShowRationale: Set<String>): Boolean =
        stillMissing.any {
            isPermanentlyDenied(granted = false,
                                everAsked = it in everAsked,
                                shouldShowRationale = it in shouldShowRationale)
        }

    /**
     * What a `RequestMultiplePermissions` result means for "did the user refuse?".
     *
     * ⚠️ An EMPTY result map is a refusal, not a success. It is what arrives when
     * the system declines to show a dialog at all — which is exactly the
     * permanently-denied case. The old call site read
     * `grants.isNotEmpty() && grants.values.any { !it }`, so an empty map left
     * the screen believing nothing had been refused.
     */
    fun resultIsRefusal(grants: Map<String, Boolean>): Boolean =
        grants.isEmpty() || grants.values.any { !it }
}
