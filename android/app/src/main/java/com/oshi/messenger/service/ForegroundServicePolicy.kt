package com.oshi.messenger.service

/**
 * When a foreground service of ours may be started, and how long it may run.
 *
 * Deliberately pure Kotlin — no `android.*` imports — so the rules are covered by
 * JVM unit tests instead of only being exercisable on a device, the same shape as
 * [CheckInPermissionPolicy]. The Android-facing halves (calling `startForeground`,
 * posting the self-stop, returning the restart mode) live in [MeshNetworkService]
 * and [MessageSyncService] and call in here.
 *
 * ══ WHY THIS EXISTS (Play Console, 30 days to 2026-10-16) ══
 *
 * 96 crash reports on 12 distinct users — nearly every user OSHI actually has on
 * Android — and two thirds of them were one line of this file away:
 *
 *   • 45 reports / 8 users: `MeshNetworkService.onStartCommand` →
 *     `ForegroundServiceStartNotAllowedException`. The service returned
 *     `START_STICKY`, so after any process kill the system restarts it **with a
 *     null intent, in the background**, where Android 12 (API 31) forbids a
 *     foreground-service start. `startForeground()` then throws, the process
 *     dies, the system restarts it again. A loop, not an incident — which is why
 *     8 users produced 45 reports.
 *   • 15 reports / 9 users: `RemoteServiceException$ForegroundServiceDidNotStopInTime`.
 *     A `dataSync` service that never stops exhausts Android 15's cumulative
 *     daily budget; when the budget expires the platform gives the app seconds to
 *     stop and kills it otherwise. [MessageSyncService] overrides both `onTimeout`
 *     forms since 2026-08-23 and the crash STILL reached versionCode 81 — a
 *     callback that races the executioner is not a fix. Stopping on our own
 *     clock, well short of the cap, is.
 *
 * Note what is NOT a cause here: the `connectedDevice` type's prerequisite. That
 * type needs one of a set of permissions, and the manifest declares
 * `CHANGE_NETWORK_STATE` and `CHANGE_WIFI_STATE`, both *normal* permissions
 * granted at install and never revocable. So a missing prerequisite cannot throw
 * on this app, and there is no permission branch below pretending otherwise.
 */
enum class ForegroundStartDecision {
    /** Go ahead and call `startForeground`. */
    START,

    /**
     * The system restarted the service on its own (null intent) on a release that
     * forbids background starts. There is no exemption for this start and never
     * will be, so do not attempt it — stop quietly and come back on the next
     * launch or FCM push.
     */
    SKIP_SYSTEM_RESTART,
}

object ForegroundServicePolicy {

    /** Android 12 — the release that forbids starting an FGS from the background. */
    const val SDK_BACKGROUND_START_BLOCKED = 31

    /** Android 15 — the release that caps cumulative daily `dataSync` runtime. */
    const val SDK_DATA_SYNC_BUDGETED = 35

    /**
     * How long a `dataSync` service may run before stopping itself, on releases
     * that budget it. Four hours against the platform's six: the margin is large
     * on purpose, because the platform counts runtime **cumulatively across the
     * day**, so two long sessions can reach the cap even when neither does alone.
     */
    const val DATA_SYNC_SELF_STOP_MS = 4L * 60L * 60L * 1000L

    /**
     * @param isSystemRestart true when `onStartCommand` got a null intent, which
     *        means the platform — not us, not the user, not the boot broadcast —
     *        revived a `START_STICKY` service.
     */
    fun decideStart(sdkInt: Int, isSystemRestart: Boolean): ForegroundStartDecision =
        if (isSystemRestart && sdkInt >= SDK_BACKGROUND_START_BLOCKED) {
            ForegroundStartDecision.SKIP_SYSTEM_RESTART
        } else {
            ForegroundStartDecision.START
        }

    /**
     * Whether `START_STICKY` is still a safe answer. From Android 12 it is not: the
     * restart it asks for is precisely the start the platform refuses, so asking
     * for it buys a crash loop instead of resilience. The app's own paths —
     * `MainActivity`, `BootReceiver`, an FCM push — are what bring the service
     * back, and all three run where a start IS allowed.
     */
    fun stickyRestartIsSafe(sdkInt: Int): Boolean = sdkInt < SDK_BACKGROUND_START_BLOCKED

    /**
     * The self-stop deadline for a `dataSync` service, or null where the platform
     * does not budget one and the service may run for as long as it is useful.
     */
    fun dataSyncSelfStopMs(sdkInt: Int): Long? =
        if (sdkInt >= SDK_DATA_SYNC_BUDGETED) DATA_SYNC_SELF_STOP_MS else null

    /**
     * __ANDROID_ANSWERS_READS_IN_BACKGROUND_2026_10_23__
     *
     * Whether to attempt the `connectedDevice` start that keeps the mesh
     * answerable while the app is not on screen.
     *
     * ══ WHAT WAS BROKEN ══
     *
     * `CrossPlatformMesh` publishes one READ-only GATT characteristic whose value
     * is this phone's LAN `ip` and TCP `port`. That is the whole handshake: there
     * is no data path over BLE, so an iPhone that cannot read it cannot open the
     * TCP connection and iOS <-> Android mesh does not happen.
     *
     * The value is not stored in the characteristic — it is computed per read, in
     * `onCharacteristicReadRequest`, **in our process**. So the advertisement
     * (held by the system Bluetooth stack) outlived the ability to answer it:
     *
     *   • backgrounded, the process is cached and Android 12+ FREEZES it. The
     *     binder callback is never delivered, the central waits and times out.
     *   • under memory pressure the process is killed outright and the GATT
     *     server dies with it.
     *
     * The one thing that reliably prevents both is a foreground service, and it
     * must NOT be `dataSync`: that type is budgeted by Android 15 (see
     * [DATA_SYNC_SELF_STOP_MS]) and the mesh would go deaf when the budget ran
     * out. `connectedDevice` is not budgeted, which is why [MeshNetworkService]
     * was declared with it.
     *
     * ══ WHY THE FOREGROUND GATE ══
     *
     * Android 12+ refuses an FGS start from the background, and the mesh is also
     * re-armed from a 30 s ticker that keeps running there. Without this gate
     * every tick would throw [SecurityException] to be caught and logged. The
     * start is attempted from where it is allowed; a refusal is never fatal,
     * because an unanswered read costs a mesh link, not the user's data.
     */
    fun shouldStartMeshLifeline(sdkInt: Int, appIsForeground: Boolean): Boolean =
        appIsForeground || sdkInt < SDK_BACKGROUND_START_BLOCKED

    /** Any one of these satisfies a `connectedDevice` foreground service on API 34+. */
    val CONNECTED_DEVICE_PERMISSIONS = listOf(
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.BLUETOOTH_ADVERTISE",
    )

    /**
     * Android 14+ refuses `startForeground(..., CONNECTED_DEVICE)` with a
     * SecurityException unless one Bluetooth/NFC/UWB-type permission is granted;
     * after a `startForegroundService` that refusal becomes an uncatchable
     * ForegroundServiceDidNotStartInTimeException. Below 34 the type is not enforced.
     */
    fun connectedDeviceTypeAllowed(sdkInt: Int, granted: Set<String>): Boolean =
        sdkInt < 34 || CONNECTED_DEVICE_PERMISSIONS.any { it in granted }
}
