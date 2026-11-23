package com.oshi.messenger.network.mesh

import java.util.concurrent.atomic.AtomicInteger

/**
 * The BLE radio's own rules: when a periodic tick may touch `startScan`, what a
 * rejected start actually means, and how not to lose the user's Bluetooth
 * adapter name.
 *
 * Pure Kotlin on purpose — no `android.*` import, error codes passed in as
 * plain `Int` — so every branch below runs under a JVM unit test instead of
 * being reachable only with a real radio in a real room. The `android.bluetooth`
 * constants are pinned against these by [BleRadioPolicyTest]; a drift there is
 * a failing test rather than a silently-misread error code.
 *
 * ───────────────────── the defect this file exists for ─────────────────────
 *
 * Captured verbatim on a Galaxy A17 (SM-A175F), OSHI 1.6.7, 2026-08-24 21:51 —
 * a run in which discovery *succeeded*, which is exactly why nobody had noticed:
 *
 *     🔄 Periodic BLE scan restart (connected: 2)      <- twice in the same second
 *     🔍 BLE scanning started …                        <- three times in ~0.5 s
 *     ❌ BLE scan failed: errorCode=1
 *
 * `errorCode=1` is `ScanCallback.SCAN_FAILED_ALREADY_STARTED`: the framework
 * refuses a `startScan` whose `ScanCallback` is already registered. Two things
 * were producing it, and they compound:
 *
 *  1. **One tick armed the scan twice.** `schedulePeriodicScanRestart` called
 *     `ensureBleRunning()` — which starts the scan when it is down — and then
 *     unconditionally ran `stopBLEScanning() / delay(500) / startBLEScanning()`.
 *     On the tick after a late permission grant that is two `startScan` calls
 *     0.5 s apart from a single tick.
 *  2. **There were two tickers.** `CrossPlatformMesh.stop()` never cancelled the
 *     periodic coroutines and the loops' own exit test was `_isRunning.value`,
 *     which `start()` sets back to `true`. `MainViewModel` starts the mesh twice
 *     on every launch — `meshManager.startMesh()` → `startCrossPlatformMesh()` →
 *     `start()`, then, seeing `isRunning == true`, `reconfigureIdentity()` →
 *     `stop()` + `start()`. The first launch's ticker is parked in
 *     `delay(30_000)` while that happens; it wakes to find `_isRunning` true
 *     again and runs forever alongside the second one. Hence *two* "Periodic BLE
 *     scan restart" lines in the same second, and a third `startScan`.
 *
 * Neither was fatal — scanning continues from the call that did land — but it
 * burns radio, and a rejected start that nobody checks is the shape that hides
 * a real failure later. [ScanTick] and [MeshRunGeneration] below are the two
 * fixes, and [scanFault] is the third: the rejection is now read rather than
 * logged past.
 */
object BleRadioPolicy {

    // ─────────────────────────── platform error codes ───────────────────────────
    //
    // Held as literals so this file keeps its no-`android.*` property; pinned
    // against `ScanCallback.*` / `AdvertiseCallback.*` by the test.

    /** `ScanCallback.SCAN_FAILED_ALREADY_STARTED` */
    const val SCAN_FAILED_ALREADY_STARTED = 1

    /** `ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED` */
    const val SCAN_FAILED_APPLICATION_REGISTRATION_FAILED = 2

    /** `ScanCallback.SCAN_FAILED_INTERNAL_ERROR` */
    const val SCAN_FAILED_INTERNAL_ERROR = 3

    /** `ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED` */
    const val SCAN_FAILED_FEATURE_UNSUPPORTED = 4

    /** `ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES` (API 26) */
    const val SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES = 5

    /** `ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY` (API 26) */
    const val SCAN_FAILED_SCANNING_TOO_FREQUENTLY = 6

    /** `AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE` */
    const val ADVERTISE_FAILED_DATA_TOO_LARGE = 1

    /** `AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS` */
    const val ADVERTISE_FAILED_TOO_MANY_ADVERTISERS = 2

    /** `AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED` */
    const val ADVERTISE_FAILED_ALREADY_STARTED = 3

    /** `AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR` */
    const val ADVERTISE_FAILED_INTERNAL_ERROR = 4

    /** `AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED` */
    const val ADVERTISE_FAILED_FEATURE_UNSUPPORTED = 5

    // ───────────────────────────── what a failure means ─────────────────────────

    /**
     * What a rejected `startScan` / `startAdvertising` tells us about the radio.
     *
     * The distinction that matters is [live]: `…_ALREADY_STARTED` is the one
     * failure code that means the thing we asked for **is running** — from an
     * earlier call. Clearing the state flag on it would be a lie in the other
     * direction, and would make the next tick re-arm and be rejected again,
     * forever. Every other code means the radio is genuinely not doing what we
     * think it is.
     */
    enum class RadioFault(
        /** True when the scan/advertisement is running despite the rejection. */
        val live: Boolean,
        /** True when re-trying on the next 30 s tick can plausibly succeed. */
        val retryable: Boolean,
        /** True when the user should be told, because no retry will fix it. */
        val userVisible: Boolean,
    ) {
        /** No failure recorded. */
        NONE(live = true, retryable = false, userVisible = false),

        /**
         * Our own duplicate call. The radio is fine; we asked twice. This is the
         * `errorCode=1` from the Galaxy A17 capture.
         */
        DUPLICATE_START(live = true, retryable = false, userVisible = false),

        /** Framework-side registration hiccup — the classic "toggle Bluetooth" case. */
        TRANSIENT(live = false, retryable = true, userVisible = false),

        /**
         * Android throttles an app to 5 scan starts in 30 s and then silently
         * refuses for the rest of the window. Retrying is right, telling the
         * user is not — but it must never be mistaken for "scanning".
         */
        THROTTLED(live = false, retryable = true, userVisible = false),

        /** The radio cannot do this at all on this device. Retrying is pointless. */
        UNSUPPORTED(live = false, retryable = false, userVisible = true),

        /**
         * Too many advertisers / out of hardware resources: another app owns the
         * radio slot. Retryable, and worth saying, because the user can close
         * the other app.
         */
        RESOURCE_EXHAUSTED(live = false, retryable = true, userVisible = true),
    }

    /** Read a `ScanCallback.onScanFailed` error code. */
    fun scanFault(errorCode: Int): RadioFault = when (errorCode) {
        SCAN_FAILED_ALREADY_STARTED -> RadioFault.DUPLICATE_START
        SCAN_FAILED_APPLICATION_REGISTRATION_FAILED,
        SCAN_FAILED_INTERNAL_ERROR -> RadioFault.TRANSIENT
        SCAN_FAILED_FEATURE_UNSUPPORTED -> RadioFault.UNSUPPORTED
        SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> RadioFault.RESOURCE_EXHAUSTED
        SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> RadioFault.THROTTLED
        // An unknown code is NOT "probably fine". Treat it as the radio being
        // down and retryable — the flag must never claim a scan we cannot see.
        else -> RadioFault.TRANSIENT
    }

    /** Read an `AdvertiseCallback.onStartFailure` error code. */
    fun advertiseFault(errorCode: Int): RadioFault = when (errorCode) {
        ADVERTISE_FAILED_ALREADY_STARTED -> RadioFault.DUPLICATE_START
        ADVERTISE_FAILED_INTERNAL_ERROR -> RadioFault.TRANSIENT
        ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> RadioFault.UNSUPPORTED
        ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> RadioFault.RESOURCE_EXHAUSTED
        // Our advertisement is a fixed 16-bit UUID + 4 bytes of manufacturer
        // data and cannot outgrow 31 bytes, so DATA_TOO_LARGE would be a coding
        // error on our side, not a radio condition. Not retryable, and loud.
        ADVERTISE_FAILED_DATA_TOO_LARGE -> RadioFault.UNSUPPORTED
        else -> RadioFault.TRANSIENT
    }

    // ─────────────────────────────── the periodic tick ──────────────────────────

    /**
     * What the 30 s ticker is allowed to do to the scanner.
     *
     * The old tick did [BRING_UP] *and* [REFRESH] every time, which is the
     * double `startScan` in the capture.
     */
    enum class ScanTick {
        /** No Bluetooth grant. Touch nothing, and let the state flags say so. */
        SKIP_NO_PERMISSION,

        /** The scan is down. Start it — exactly once — and do not then cycle it. */
        BRING_UP,

        /**
         * The scan is up. Stop / pause / start to shake loose a scanner that has
         * gone quiet, which is what the ticker was for in the first place.
         */
        REFRESH,
    }

    /**
     * @param permitted whether the BLE **scan** lane holds its permissions.
     * @param scanning the honest `bleScanning` state, i.e. whether we believe a
     *   `startScan` has landed and not been stopped or failed since.
     */
    fun scanTick(permitted: Boolean, scanning: Boolean): ScanTick = when {
        !permitted -> ScanTick.SKIP_NO_PERMISSION
        scanning -> ScanTick.REFRESH
        else -> ScanTick.BRING_UP
    }

    // ───────────────────────── the adapter name, kept safe ──────────────────────

    /**
     * Prefix `CrossPlatformMesh.startBLEAdvertising` renames the phone's whole
     * Bluetooth adapter to, so iOS's name check (`CrossPlatformMesh.swift:1372`)
     * matches. Load-bearing for interop — hence the care below rather than
     * simply not renaming.
     */
    const val ADAPTER_NAME_PREFIX = "OSHI-"

    /**
     * The value to persist as "the user's real Bluetooth name", or null to leave
     * whatever is already persisted alone.
     *
     * The rule that matters is the second one. The previous fix kept the
     * original in a plain field:
     *
     *     if (savedAdapterName == null) savedAdapterName = bluetoothAdapter?.name
     *
     * A field does not survive the process. Swipe OSHI away while it is
     * advertising and the phone stays called "OSHI-Hugo"; the next launch reads
     * *that* as the original, and `stopBLEAdvertising` dutifully "restores" the
     * phone to "OSHI-Hugo". The user's real adapter name — the one their car,
     * their headphones and every laptop they have ever paired with display — is
     * then gone for good, with no way back short of a factory reset of the
     * Bluetooth stack.
     *
     * @param current the adapter name right now.
     * @param alreadySaved what durable storage already holds, if anything.
     */
    fun adapterNameToSave(current: String?, alreadySaved: String?): String? = when {
        // Never overwrite a good saved value: it is the only copy.
        !alreadySaved.isNullOrBlank() -> null
        current.isNullOrBlank() -> null
        // Never record one of our own names as the original.
        current.startsWith(ADAPTER_NAME_PREFIX) -> null
        else -> current
    }

    /**
     * The name to put back, or null if there is nothing to do.
     *
     * Called both from `stopBLEAdvertising` and once at `start()`, so a rename
     * orphaned by a killed process is repaired on the next launch instead of
     * being permanent.
     */
    fun adapterNameToRestore(saved: String?, current: String?): String? = when {
        saved.isNullOrBlank() -> null
        // Someone (the user, another app) renamed it since; do not stomp them.
        current != null && !current.startsWith(ADAPTER_NAME_PREFIX) -> null
        saved == current -> null
        else -> saved
    }
}

/**
 * Which `start()` the periodic coroutines belong to.
 *
 * `CrossPlatformMesh.stop()` cancels its timer jobs, but cancellation is
 * cooperative: a loop already past its `delay` can still run one more body
 * before it notices. And the loops' old exit test — `_isRunning.value` — is
 * *restored to true* by the immediately-following `start()`, so a loop that
 * woke up during a `stop()`/`start()` pair (which is every single app launch,
 * via `MainViewModel`'s `startMesh()` then `reconfigureIdentity()`) simply never
 * exited. That is the second timer behind the doubled
 * "🔄 Periodic BLE scan restart" line in the Galaxy A17 capture.
 *
 * A monotonically increasing token fixes it without depending on cancellation
 * timing: every loop body re-checks that its own generation is still the
 * current one, and a retired generation never comes back.
 */
internal class MeshRunGeneration {
    private val current = AtomicInteger(0)

    /** Claim a new generation for a fresh `start()`; retires every earlier one. */
    fun begin(): Int = current.incrementAndGet()

    /** Retire the running generation. Any loop still in flight stops at its next check. */
    fun end() {
        current.incrementAndGet()
    }

    /** True only for the generation claimed by the most recent [begin]. */
    fun isCurrent(generation: Int): Boolean = current.get() == generation
}
