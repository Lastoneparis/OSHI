package com.oshi.messenger.service

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ScreenshotProtectionManager - Compatible with iOS ScreenshotBlocker.swift
 * Prevents screenshots and screen recording in sensitive screens
 */
@Singleton
class ScreenshotProtectionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ScreenshotProtection"
        private const val PREFS = "screenshot_protection"
        private const val KEY_ENABLED = "protection_enabled"

        private fun readUserChoice(prefs: android.content.SharedPreferences): Boolean =
            ScreenshotProtectionPolicy.resolveUserChoice(
                if (prefs.contains(KEY_ENABLED)) prefs.getBoolean(KEY_ENABLED, true) else null
            )

        /**
         * For activities outside Hilt (OshiScanActivity extends zxing's
         * CaptureActivity): secure the window per the stored choice, ON when
         * never set.
         */
        fun applyToUnmanagedActivity(activity: Activity) {
            val on = readUserChoice(
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            )
            activity.applyScreenshotProtection(on)
        }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // State
    private val _isProtectionEnabled = MutableStateFlow(true)
    val isProtectionEnabled: StateFlow<Boolean> = _isProtectionEnabled.asStateFlow()

    private val _protectedScreens = MutableStateFlow<Set<String>>(emptySet())
    val protectedScreens: StateFlow<Set<String>> = _protectedScreens.asStateFlow()

    // Registered activities
    private val activities = mutableSetOf<Activity>()

    // __SCREENSHOT_NOTICE_2026_11_18__ API 34+ capture callbacks, one per activity.
    private val captureCallbacks = mutableMapOf<Activity, Any>()
    @Volatile private var lastNoticeAt: Long? = null

    /** A screenshot was attempted over a protected window: show the pill if due (rate-limited). */
    internal fun onScreenCaptured(nowMs: Long = System.currentTimeMillis()) {
        if (!ScreenshotProtectionPolicy.captureNoticeDue(effectiveEnabled(), nowMs, lastNoticeAt)) return
        lastNoticeAt = nowMs
        Log.d(TAG, "Screenshot attempted over a protected window: notice shown")
        ScreenCaptureNotices.emit(nowMs)
    }

    /**
     * DEBUG builds only: set by an intent extra so `adb exec-out screencap`
     * (store-shot tooling) can capture the app. Process-lifetime, never
     * persisted, never touches the user's stored choice. See [effectiveEnabled].
     */
    @Volatile
    private var debugCaptureOverride = false

    init {
        loadSettings()
    }

    /** What windows actually get: the user's choice, unless a debug build asked for capture. */
    private fun effectiveEnabled(): Boolean = ScreenshotProtectionPolicy.effectiveProtection(
        userChoice = _isProtectionEnabled.value,
        isDebugBuild = com.oshi.messenger.BuildConfig.DEBUG,
        debugCaptureOverride = debugCaptureOverride,
    )

    /**
     * Reads [ScreenshotProtectionPolicy.DEBUG_ALLOW_CAPTURE_EXTRA] off a launch
     * intent. A release build returns immediately — the extra is ignored, the
     * branch is dead code R8 removes. Usage on a debug install:
     *   adb shell am start -n com.oshi.messenger/.ui.MainActivity \
     *       --ez com.oshi.messenger.debug.ALLOW_SCREEN_CAPTURE true
     */
    fun applyDebugCaptureOverride(intent: android.content.Intent?) {
        if (!com.oshi.messenger.BuildConfig.DEBUG || intent == null) return
        if (!intent.hasExtra(ScreenshotProtectionPolicy.DEBUG_ALLOW_CAPTURE_EXTRA)) return
        debugCaptureOverride =
            intent.getBooleanExtra(ScreenshotProtectionPolicy.DEBUG_ALLOW_CAPTURE_EXTRA, false)
        Log.w(TAG, "DEBUG build: screen capture override = $debugCaptureOverride")
        activities.forEach { if (effectiveEnabled()) applyProtection(it) else removeProtection(it) }
    }

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /**
     * Enable screenshot protection globally
     */
    fun enableProtection() {
        _isProtectionEnabled.value = true
        saveSettings()

        // Apply to all registered activities (and their open dialogs/sheets)
        activities.forEach { if (effectiveEnabled()) applyProtection(it) else removeProtection(it) }

        Log.d(TAG, "Screenshot protection enabled")
    }

    /**
     * Disable screenshot protection globally
     */
    fun disableProtection() {
        _isProtectionEnabled.value = false
        saveSettings()

        // Remove from all registered activities
        activities.forEach { removeProtection(it) }

        Log.d(TAG, "Screenshot protection disabled")
    }

    /**
     * Toggle protection
     */
    fun toggleProtection(): Boolean {
        if (_isProtectionEnabled.value) {
            disableProtection()
        } else {
            enableProtection()
        }
        return _isProtectionEnabled.value
    }

    /**
     * Register an activity for screenshot protection
     */
    fun registerActivity(activity: Activity) {
        activities.add(activity)

        if (effectiveEnabled()) {
            applyProtection(activity)
        } else {
            removeProtection(activity)
        }
        watchAuxiliaryWindows(activity)
        registerCaptureCallback(activity)

        Log.d(TAG, "Registered activity: ${activity.javaClass.simpleName}")
    }

    /**
     * Compose `Dialog`/`AlertDialog`/`DropdownMenu`/`Popup` default to
     * `SecureFlagPolicy.Inherit` and copy FLAG_SECURE from the activity window
     * on their own (verified in compose-ui 1.5.4 `DialogWrapper`/`PopupLayout`).
     * Material3 1.1.2's ModalBottomSheet does NOT: its window
     * (TYPE_APPLICATION_PANEL) is created with no secure flag and that version
     * has no `securePolicy` to ask for one — so a sheet listing messages or
     * contacts was capturable over an otherwise black screenshot. Same for the
     * platform `android.app.DatePickerDialog`/`TimePickerDialog`.
     *
     * Rather than touch ~40 sheet call sites, every in-process root window is
     * reconciled with the current setting whenever the activity's window draws
     * or loses focus to another window (what a sheet or dialog opening does).
     * `WindowInspector` is API 29+; on 26–28 those sheets stay as before.
     */
    private fun watchAuxiliaryWindows(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val observer = activity.window.decorView.viewTreeObserver
        observer.addOnWindowFocusChangeListener { hasFocus ->
            if (!hasFocus) activity.window.decorView.post { reconcileAuxiliaryWindows(activity) }
        }
        observer.addOnPreDrawListener {
            reconcileAuxiliaryWindows(activity)
            true
        }
    }

    private fun reconcileAuxiliaryWindows(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (activity.isDestroyed) return
        val enabled = effectiveEnabled()
        try {
            for (root in android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                if (root === activity.window.decorView) continue // handled by setFlags
                val lp = root.layoutParams as? WindowManager.LayoutParams ?: continue
                val newFlags = ScreenshotProtectionPolicy.reconcile(lp.flags, enabled) ?: continue
                if (!root.isAttachedToWindow) continue
                lp.flags = newFlags
                activity.windowManager.updateViewLayout(root, lp)
                Log.d(TAG, "Secure=$enabled applied to auxiliary window ${lp.title}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reconcile auxiliary windows: ${e.message}")
        }
    }

    /**
     * Unregister an activity
     */
    fun unregisterActivity(activity: Activity) {
        activities.remove(activity)
        unregisterCaptureCallback(activity)
        Log.d(TAG, "Unregistered activity: ${activity.javaClass.simpleName}")
    }

    /**
     * __SCREENSHOT_NOTICE_2026_11_18__ `Activity.ScreenCaptureCallback` (API 34,
     * normal permission DETECT_SCREEN_CAPTURE). The system calls it when the user
     * takes a screenshot while this activity is visible; FLAG_SECURE has already
     * made the capture black, so the pill only explains. Screen RECORDING has no
     * callback: it is blanked by the flag, silently. Below API 34 the flag still
     * blocks, and the system shows its own "can't take screenshot" notice.
     */
    private fun registerCaptureCallback(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || captureCallbacks.containsKey(activity)) return
        try {
            val cb = Activity.ScreenCaptureCallback { onScreenCaptured() }
            activity.registerScreenCaptureCallback(activity.mainExecutor, cb)
            captureCallbacks[activity] = cb
        } catch (e: Exception) {
            Log.w(TAG, "Screen capture callback unavailable: ${e.message}")
        }
    }

    private fun unregisterCaptureCallback(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val cb = captureCallbacks.remove(activity) as? Activity.ScreenCaptureCallback ?: return
        try { activity.unregisterScreenCaptureCallback(cb) } catch (_: Exception) { }
    }

    /**
     * Add a screen to protected list
     */
    fun addProtectedScreen(screenName: String) {
        val current = _protectedScreens.value.toMutableSet()
        current.add(screenName)
        _protectedScreens.value = current
        saveSettings()
    }

    /**
     * Remove a screen from protected list
     */
    fun removeProtectedScreen(screenName: String) {
        val current = _protectedScreens.value.toMutableSet()
        current.remove(screenName)
        _protectedScreens.value = current
        saveSettings()
    }

    /**
     * Check if a screen should be protected
     */
    fun isScreenProtected(screenName: String): Boolean {
        return _isProtectionEnabled.value && (
            _protectedScreens.value.isEmpty() ||  // All screens if none specified
            _protectedScreens.value.contains(screenName)
        )
    }

    /**
     * Apply protection to a specific activity
     */
    fun applyProtection(activity: Activity) {
        try {
            activity.window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
            applyRecentsPolicy(activity, secure = true)
            reconcileAuxiliaryWindows(activity)
            Log.d(TAG, "Screenshot protection applied to ${activity.javaClass.simpleName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply protection: ${e.message}")
        }
    }

    /**
     * Remove protection from a specific activity
     */
    fun removeProtection(activity: Activity) {
        try {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            applyRecentsPolicy(activity, secure = false)
            reconcileAuxiliaryWindows(activity)
            Log.d(TAG, "Removed protection from ${activity.javaClass.simpleName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove protection: ${e.message}")
        }
    }

    /**
     * Recents (task switcher). With FLAG_SECURE set — or, API 33+,
     * `setRecentsScreenshotEnabled(false)` — the system never stores a pixel
     * snapshot of the window; it draws an "app theme" placeholder instead:
     * a solid fill in the TaskDescription background colour, under the app's
     * icon and name. Android offers no way to put a custom image there, so the
     * fill is set to the same OSHI ink as [PrivacyCoverManager]'s cover and the
     * header carries the OSHI icon/label. With protection OFF the pixel
     * snapshot is allowed again and captures PrivacyCoverManager's OSHI cover
     * (added in MainActivity.onPause), never the conversation.
     */
    private fun applyRecentsPolicy(activity: Activity, secure: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            activity.setRecentsScreenshotEnabled(!secure)
            activity.setTaskDescription(
                android.app.ActivityManager.TaskDescription.Builder()
                    .setBackgroundColor(PrivacyCoverManager.COVER_COLOR)
                    .build()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply recents policy: ${e.message}")
        }
    }


    /**
     * The window flags the current preference asks for.
     *
     * Delegates to [ScreenshotProtectionPolicy] so the preference -> flag
     * mapping this switch promises is asserted by a JVM test rather than
     * restated in three places. See that object for the full call chain from
     * the DataStore key down to `window.setFlags`, and for the device evidence
     * that the flag really is set.
     */
    fun getSecureWindowFlags(): Int =
        ScreenshotProtectionPolicy.windowFlags(_isProtectionEnabled.value)

    // =========================================================================
    // PERSISTENCE
    // =========================================================================

    private fun loadSettings() {
        _isProtectionEnabled.value = readUserChoice(prefs)
        _protectedScreens.value = prefs.getStringSet("protected_screens", emptySet()) ?: emptySet()
    }

    private fun saveSettings() {
        prefs.edit()
            .putBoolean("protection_enabled", _isProtectionEnabled.value)
            .putStringSet("protected_screens", _protectedScreens.value)
            .apply()
    }
}

/**
 * Extension function to easily apply screenshot protection in Compose
 */
fun Activity.applyScreenshotProtection(enable: Boolean) {
    if (enable) {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
