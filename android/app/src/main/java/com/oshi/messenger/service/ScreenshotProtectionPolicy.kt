package com.oshi.messenger.service

/**
 * The preference -> window-flag mapping behind "Screenshot Protection", kept
 * free of every Android import so it can be asserted in a plain JVM test.
 *
 * WHY THIS EXISTS AS A SEPARATE OBJECT
 * ------------------------------------
 * The 2026-08-24 screen review filed this switch as a dead security promise:
 * "`applyProtection`, `removeProtection` and `getSecureWindowFlags` have zero
 * callers, no FLAG_SECURE is set anywhere active, and the Settings switch
 * writes a preference nothing ever reads." Two of those three clauses are
 * wrong, and the chain is worth writing down because it is not greppable in
 * one hop — `applyProtection` genuinely has no DIRECT caller outside this
 * package, which is what the grep saw:
 *
 *   DataStore `screenshot_protection` (default true)
 *     -> SettingsViewModel.loadSettings()      (SettingsViewModel.kt:335-339)
 *     -> ScreenshotProtectionManager.enableProtection() / disableProtection()
 *     -> activities.forEach { applyProtection(it) }
 *     -> Activity.window.setFlags(FLAG_SECURE)
 *
 *   MainActivity.onCreate                      (MainActivity.kt:605)
 *     -> ScreenshotProtectionManager.registerActivity(this)
 *     -> applyProtection(this) when the preference is on
 *
 *   the Settings switch                        (SettingsViewModel.kt:446-460)
 *     -> setScreenshotProtection(enabled) -> enable/disableProtection()
 *
 * Verified on the running emulator rather than argued from the code:
 * `adb shell dumpsys window windows` reports
 * `fl=LAYOUT_IN_SCREEN SECURE LAYOUT_INSET_DECOR ...` on
 * `com.oshi.messenger/.ui.MainActivity`. The flag is set, right now, on the
 * real window.
 *
 * The review's own evidence was in fact a demonstration that the feature
 * WORKS: `screencap` returning an all-black PNG for OSHI while capturing the
 * launcher perfectly is precisely what FLAG_SECURE does. It was read as an
 * emulator GPU artifact because the launcher — a different, unprotected app —
 * captured fine.
 *
 * Only `getSecureWindowFlags()` was truly uncalled. It is now this function,
 * so the mapping the switch promises is pinned by a test instead of by a
 * comment.
 *
 * iOS, for parity: `SecureWeb3MessengerApp.swift:120` applies `.secureScreen()`
 * unconditionally at the scene root — protection is always on there, with no
 * user control. Android defaults the preference to `true`, so the shipped
 * behaviour matches; the switch is an Android-only affordance on top.
 */
object ScreenshotProtectionPolicy {

    /**
     * `android.view.WindowManager.LayoutParams.FLAG_SECURE`.
     *
     * Written as the literal so this object stays loadable off-device;
     * `SettingsTouchAndSecureFlagTest` pins it to the platform value.
     */
    const val FLAG_SECURE: Int = 0x00002000

    /** The window flags the preference asks for. */
    fun windowFlags(protectionEnabled: Boolean): Int =
        if (protectionEnabled) FLAG_SECURE else 0

    /** Is a window carrying this flag set actually protected? */
    fun isProtected(windowFlags: Int): Boolean =
        (windowFlags and FLAG_SECURE) != 0

    // __SCREEN_CAPTURE_ON_BY_DEFAULT_2026_11_09__
    //
    // The default is ON for everyone. The only thing that turns it off is a
    // choice the user actually made: the DataStore key `screenshot_protection`
    // is written by exactly one function (SettingsViewModel
    // .setScreenshotProtection, i.e. the Settings switch), so "key absent"
    // means "never touched the switch" and resolves to ON, while "key = false"
    // is an explicit opt-out and is respected. The default has been `true`
    // since the feature was written (git -S finds no `?: false` in history),
    // so no installed user can be holding a `false` they did not choose.
    //
    // The SharedPreferences mirror (`protection_enabled`) is NOT a record of
    // choice: SettingsViewModel.loadSettings rewrites it on every load. It only
    // exists so MainActivity.onCreate can apply the flag synchronously, before
    // the first frame, without waiting for DataStore.

    /** The user's protection preference: absent (never chosen) means ON. */
    fun resolveUserChoice(explicitChoice: Boolean?): Boolean = explicitChoice ?: true

    /**
     * What the windows actually get. [debugCaptureOverride] is honoured ONLY in
     * a debug build, so store-screenshot tooling can capture a debug install;
     * a release build ignores it whatever the intent says.
     */
    fun effectiveProtection(
        userChoice: Boolean,
        isDebugBuild: Boolean,
        debugCaptureOverride: Boolean,
    ): Boolean = if (isDebugBuild && debugCaptureOverride) false else userChoice

    /**
     * New window flags for a window currently carrying [currentFlags], or null
     * when nothing needs to change (so callers skip a pointless
     * `updateViewLayout`). Used for the windows Compose does not secure on its
     * own — Material3 1.1.2's ModalBottomSheet window and platform pickers.
     */
    fun reconcile(currentFlags: Int, protectionEnabled: Boolean): Int? {
        if (isProtected(currentFlags) == protectionEnabled) return null
        return if (protectionEnabled) currentFlags or FLAG_SECURE else currentFlags and FLAG_SECURE.inv()
    }

    // __SCREENSHOT_NOTICE_2026_11_18__ API 34+: when someone presses the
    // screenshot keys over a protected OSHI window the capture is black (the
    // flag does that) and OSHI says why, in its own words — a small glass pill,
    // never a dialog, at most once per [CAPTURE_NOTICE_MIN_INTERVAL_MS].
    const val CAPTURE_NOTICE_MIN_INTERVAL_MS: Long = 4_000L

    /** Show the "screenshots are blocked" pill for a capture at [nowMs]? */
    fun captureNoticeDue(protectionOn: Boolean, nowMs: Long, lastShownMs: Long?): Boolean =
        protectionOn && (lastShownMs == null || nowMs - lastShownMs >= CAPTURE_NOTICE_MIN_INTERVAL_MS || nowMs < lastShownMs)

    /** Intent extra a DEBUG build reads to allow screen capture for this process. */
    const val DEBUG_ALLOW_CAPTURE_EXTRA = "com.oshi.messenger.debug.ALLOW_SCREEN_CAPTURE"

    /**
     * __ANTI_STALKERWARE_2026_11_20__ `View.accessibilityDataSensitive` for the window
     * roots: YES (= 1, hidden from non-`isAccessibilityTool` services) while protection is
     * on, AUTO (= 0) otherwise. Spelled as literals so this stays pure on the JVM; they are
     * `View.ACCESSIBILITY_DATA_SENSITIVE_YES` / `_AUTO` (API 34).
     */
    fun accessibilityDataSensitive(protectionOn: Boolean): Int = if (protectionOn) 1 else 0
}
