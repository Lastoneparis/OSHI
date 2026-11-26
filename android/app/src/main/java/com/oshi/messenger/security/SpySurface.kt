package com.oshi.messenger.security

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import com.oshi.messenger.R
import com.oshi.messenger.ui.theme.OshiAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// =============================================================================
// 🕵️ __ANTI_STALKERWARE_2026_11_20__ "Tell the person when another app on this phone
// holds a permission that lets it read OSHI."
//
// Commercial spy apps (stalkerware) do not need root. They get the person — or
// whoever set the phone up — to grant one of three things, and from then on they
// read everything OSHI decrypts:
//   • an ACCESSIBILITY SERVICE: reads every text on screen and every keystroke;
//   • a NOTIFICATION LISTENER: reads every notification (message previews);
//   • a DEVICE ADMIN: can lock/wipe, and is the usual persistence trick.
// OSHI already hides its windows from non-tool accessibility services
// (ScreenshotProtectionManager.applySpywareShields, API 34+) and notification
// previews are off by default — but on older Android, and for anything the person
// turned on, only the person can remove the app. So we SAY which apps hold these
// powers. Informational only: nothing is blocked, and a real assistive tool
// (TalkBack etc., `isAccessibilityTool`) or a system component is never listed.
// The dialog shows once per distinct set, so a new spy app re-triggers it.
// =============================================================================

object SpySurface {
    private const val TAG = "SpySurface"
    private const val PREFS = "spy_surface"
    private const val KEY_LAST_WARNED = "last_warned_fingerprint"

    fun evaluate(granted: List<GrantedComponent>, ownPackage: String) = SpySurfacePolicy.evaluate(granted, ownPackage)
    fun fingerprint(apps: List<SpyCapableApp>) = SpySurfacePolicy.fingerprint(apps)
    fun shouldWarn(apps: List<SpyCapableApp>, lastWarned: String?) = SpySurfacePolicy.shouldWarn(apps, lastWarned)

    /** Gather the granted components on this device. Never throws. */
    fun collect(context: Context): List<GrantedComponent> {
        val out = mutableListOf<GrantedComponent>()
        val pm = context.packageManager
        fun isSystem(pkg: String): Boolean = try {
            val ai = pm.getApplicationInfo(pkg, 0)
            (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        } catch (_: Exception) { false }
        fun label(pkg: String): String = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }

        runCatching {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            for (info in am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                val si = info.resolveInfo?.serviceInfo ?: continue
                val system = (si.applicationInfo.flags and
                    (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                val tool = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.isAccessibilityTool
                out += GrantedComponent(
                    si.packageName, runCatching { si.applicationInfo.loadLabel(pm).toString() }.getOrDefault(si.packageName),
                    SpyPower.ACCESSIBILITY, system, tool
                )
            }
        }.onFailure { Log.w(TAG, "accessibility list unavailable: ${it.javaClass.simpleName}") }

        runCatching {
            for (pkg in NotificationManagerCompat.getEnabledListenerPackages(context)) {
                out += GrantedComponent(pkg, label(pkg), SpyPower.NOTIFICATIONS, isSystem(pkg))
            }
        }.onFailure { Log.w(TAG, "listener list unavailable: ${it.javaClass.simpleName}") }

        runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            for (cn in dpm.activeAdmins.orEmpty()) {
                out += GrantedComponent(cn.packageName, label(cn.packageName), SpyPower.DEVICE_ADMIN, isSystem(cn.packageName))
            }
        }.onFailure { Log.w(TAG, "device admin list unavailable: ${it.javaClass.simpleName}") }
        return out
    }

    suspend fun check(context: Context): List<SpyCapableApp> = withContext(Dispatchers.IO) {
        evaluate(collect(context), context.packageName)
    }

    fun lastWarned(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_WARNED, null)

    fun markWarned(context: Context, apps: List<SpyCapableApp>) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_WARNED, fingerprint(apps)).apply()
    }
}

/**
 * 🕵️ __ANTI_STALKERWARE_2026_11_20__ Hosted once at the root next to
 * DeviceIntegrityHost. Checks on every launch (cheap system calls, off the main
 * thread), warns once per distinct set of apps.
 */
@Composable
fun SpySurfaceHost() {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<SpyCapableApp>>(emptyList()) }
    LaunchedEffect(Unit) {
        val found = SpySurface.check(context)
        if (SpySurface.shouldWarn(found, SpySurface.lastWarned(context))) apps = found
    }
    if (apps.isEmpty()) return
    val dismiss = {
        SpySurface.markWarned(context, apps)
        apps = emptyList()
    }
    val accessibility = stringResource(R.string.spy_surface_power_accessibility)
    val notifications = stringResource(R.string.spy_surface_power_notifications)
    val admin = stringResource(R.string.spy_surface_power_admin)
    val list = apps.joinToString("\n") { a ->
        "• " + a.label + " — " + a.powers.sortedBy { it.ordinal }.joinToString(", ") {
            when (it) {
                SpyPower.ACCESSIBILITY -> accessibility
                SpyPower.NOTIFICATIONS -> notifications
                SpyPower.DEVICE_ADMIN -> admin
            }
        }
    }
    OshiAlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.spy_surface_dialog_title)) },
        text = { Text(stringResource(R.string.spy_surface_dialog_body, list)) },
        confirmButton = {
            TextButton(onClick = dismiss) { Text(stringResource(R.string.device_integrity_dialog_ok)) }
        },
    )
}
