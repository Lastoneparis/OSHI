package com.oshi.messenger.security

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

// =============================================================================
// 🛡 __STRICT_MODE_2026_11_22__ "Strict security mode" — Android state + guards.
// Pure rules: StrictModePolicy.kt. Settings UI: ui/screens/StrictModeScreen.kt.
// =============================================================================

/**
 * Process-wide mirror of the mode, readable from anywhere without a Context
 * (GiphyClient, SensitiveClipboard, the input-connection hook). Written ONLY by
 * [StrictModeStore]; @Volatile because the readers are on IO/binder threads.
 */
object StrictModeGate {
    @Volatile var active: Boolean = false
        internal set
    @Volatile var options: StrictOptions = StrictOptions()
        internal set
    /** Updated on every resume: an enabled AccessibilityService that is not a declared tool. */
    @Volatile var nonToolServiceEnabled: Boolean = false

    val copyAllowed: Boolean get() = StrictModePolicy.copyAllowed(active)
    val pasteAllowed: Boolean get() = StrictModePolicy.pasteAllowed(active, options)
    val thirdPartyFetchAllowed: Boolean get() = StrictModePolicy.thirdPartyFetchAllowed(active)
    val mediaExportAllowed: Boolean get() = StrictModePolicy.mediaExportAllowed(active)
}

/**
 * Minimal key/value seam so [StrictModeStore]'s persistence and restore logic is
 * JVM-tested with a map (no Robolectric in this project).
 */
interface StrictKeyValue {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun contains(key: String): Boolean
    fun edit(block: MutableMap<String, Boolean?>.() -> Unit)
}

private class PrefsKeyValue(private val prefs: SharedPreferences) : StrictKeyValue {
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun contains(key: String) = prefs.contains(key)
    override fun edit(block: MutableMap<String, Boolean?>.() -> Unit) {
        val changes = mutableMapOf<String, Boolean?>().apply(block)
        // commit(), not apply(): the mode must survive a process death that follows
        // the toggle immediately (the person toggles, then swipes the app away).
        prefs.edit().apply {
            changes.forEach { (k, v) -> if (v == null) remove(k) else putBoolean(k, v) }
        }.commit()
    }
}

/**
 * Local to the device, never synced (spec). SharedPreferences, like the other
 * security preferences (screenshot_protection, keyboard_privacy), because several
 * readers are synchronous (FCM service, input-connection hook).
 */
class StrictModeStore(private val kv: StrictKeyValue) {

    private val _active = MutableStateFlow(kv.getBoolean(KEY_ACTIVE, false))
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _options = MutableStateFlow(
        StrictOptions(
            blockPaste = kv.getBoolean(KEY_BLOCK_PASTE, true),
            hidePresence = kv.getBoolean(KEY_HIDE_PRESENCE, true),
        )
    )
    val options: StateFlow<StrictOptions> = _options.asStateFlow()

    init { publish() }

    val isActive: Boolean get() = _active.value

    /** The person's own values, saved when the mode was turned on. Null when off. */
    fun snapshot(): StrictGuardedSettings? {
        if (!kv.contains(SNAP_PREFIX + "present")) return null
        fun b(k: String) = kv.getBoolean(SNAP_PREFIX + k, false)
        return StrictGuardedSettings(
            appLock = b("app_lock"),
            screenshotProtection = b("screenshot"),
            incognitoKeyboard = b("incognito"),
            notificationPreview = b("notification_preview"),
            readReceipts = b("read_receipts"),
            typingIndicators = b("typing"),
            privacyRelay = b("privacy_relay"),
            blockForwardCopy = b("block_forward_copy"),
        )
    }

    /**
     * Turns the mode on and keeps [before] to restore later. If the mode is already
     * on, the FIRST snapshot is kept (re-enabling must not capture forced values as
     * the person's own).
     */
    fun activate(before: StrictGuardedSettings) {
        val keep = snapshot() ?: before
        kv.edit {
            put(KEY_ACTIVE, true)
            put(SNAP_PREFIX + "present", true)
            put(SNAP_PREFIX + "app_lock", keep.appLock)
            put(SNAP_PREFIX + "screenshot", keep.screenshotProtection)
            put(SNAP_PREFIX + "incognito", keep.incognitoKeyboard)
            put(SNAP_PREFIX + "notification_preview", keep.notificationPreview)
            put(SNAP_PREFIX + "read_receipts", keep.readReceipts)
            put(SNAP_PREFIX + "typing", keep.typingIndicators)
            put(SNAP_PREFIX + "privacy_relay", keep.privacyRelay)
            put(SNAP_PREFIX + "block_forward_copy", keep.blockForwardCopy)
        }
        _active.value = true
        publish()
    }

    /** Turns the mode off; returns the snapshot to restore from (null if none). */
    fun deactivate(): StrictGuardedSettings? {
        val snap = snapshot()
        kv.edit {
            put(KEY_ACTIVE, false)
            SNAP_KEYS.forEach { put(SNAP_PREFIX + it, null) }
        }
        _active.value = false
        publish()
        return snap
    }

    fun setOptions(options: StrictOptions) {
        kv.edit {
            put(KEY_BLOCK_PASTE, options.blockPaste)
            put(KEY_HIDE_PRESENCE, options.hidePresence)
        }
        _options.value = options
        publish()
    }

    private fun publish() {
        StrictModeGate.active = _active.value
        StrictModeGate.options = _options.value
    }

    companion object {
        const val PREFS = "strict_mode"
        const val KEY_ACTIVE = "strict_active"
        const val KEY_BLOCK_PASTE = "strict_block_paste"
        const val KEY_HIDE_PRESENCE = "strict_hide_presence"
        private const val SNAP_PREFIX = "strict_prev_"
        private val SNAP_KEYS = listOf(
            "present", "app_lock", "screenshot", "incognito", "notification_preview",
            "read_receipts", "typing", "privacy_relay", "block_forward_copy",
        )

        @Volatile private var instance: StrictModeStore? = null

        fun get(context: Context): StrictModeStore =
            instance ?: synchronized(this) {
                instance ?: StrictModeStore(
                    PrefsKeyValue(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
                ).also { instance = it }
            }

        /** For services that may run before any UI (FCM): loads the store if needed. */
        fun isActive(context: Context): Boolean = try { get(context).isActive } catch (_: Exception) { false }
    }
}

/**
 * What on this phone can read or drive OSHI's screen: enabled accessibility
 * services that are not declared assistive tools, and installed remote-control /
 * mirroring apps. Informational — Android lets no app disable another app.
 */
object StrictModeThreats {
    private const val TAG = "StrictModeThreats"

    fun enabledServices(context: Context): List<EnabledA11yService> = try {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val pm = context.packageManager
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).mapNotNull { info ->
            val si = info.resolveInfo?.serviceInfo ?: return@mapNotNull null
            EnabledA11yService(
                packageName = si.packageName,
                label = runCatching { info.resolveInfo.loadLabel(pm).toString() }.getOrDefault(si.packageName),
                isAccessibilityTool = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.isAccessibilityTool,
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "accessibility list unavailable: ${e.javaClass.simpleName}")
        emptyList()
    }

    fun installedRemoteControlPackages(context: Context): Set<String> {
        val pm = context.packageManager
        return StrictThreatPolicy.REMOTE_CONTROL_PACKAGES.keys.filterTo(mutableSetOf()) { pkg ->
            try {
                pm.getApplicationInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun collect(context: Context): List<StrictThreat> = withContext(Dispatchers.IO) {
        StrictThreatPolicy.evaluate(enabledServices(context), installedRemoteControlPackages(context), context.packageName)
    }

    /** Cheap (one binder call): refreshes [StrictModeGate.nonToolServiceEnabled]. */
    fun refreshNonToolFlag(context: Context) {
        StrictModeGate.nonToolServiceEnabled =
            StrictThreatPolicy.hasNonToolService(enabledServices(context), context.packageName)
    }

    fun openAccessibilitySettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun openAppDetails(context: Context, pkg: String) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/** Window-level guards applied to every OSHI activity while the mode is on. */
object StrictWindowGuards {
    /**
     * `filterTouchesWhenObscured` on the decor view: ViewGroup dispatch checks it at
     * the root, so ANY touch delivered while another app's window covers OSHI
     * (tapjacking / "draw over other apps" layers) is dropped for the whole tree.
     * Complements HIDE_OVERLAY_WINDOWS (API 31+), which ScreenshotProtectionManager
     * already sets, for overlays the system does not hide (and API 26–30).
     */
    fun apply(activity: Activity, strict: Boolean) {
        runCatching { activity.window.decorView.filterTouchesWhenObscured = strict }
    }

    /** True ⇒ the activity must swallow this event (see StrictModePolicy.dropInjectedTouch). */
    fun shouldDrop(ev: MotionEvent): Boolean =
        StrictModePolicy.dropInjectedTouch(StrictModeGate.active, ev.flags, StrictModeGate.nonToolServiceEnabled)
}
