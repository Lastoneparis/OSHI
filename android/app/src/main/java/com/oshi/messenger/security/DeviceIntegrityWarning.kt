package com.oshi.messenger.security

import android.content.Context
import com.oshi.messenger.ui.theme.OshiAlertDialog
import androidx.compose.material3.AlertDialog
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
import com.oshi.messenger.BuildConfig
import com.oshi.messenger.R

/**
 * __DEVICE_INTEGRITY_2026_10_01__ Remembers which app version last showed the
 * "this phone may be modified" dialog. Per versionCode, not forever: a person who
 * rooted after dismissing it once is reminded on the next update, but nobody is
 * nagged on every launch.
 */
object DeviceIntegrityWarningStore {
    private const val PREFS = "device_integrity"
    private const val KEY_WARNED_VERSION = "warned_version_code"

    fun shouldWarn(context: Context, versionCode: Int = BuildConfig.VERSION_CODE): Boolean =
        prefs(context).getInt(KEY_WARNED_VERSION, -1) != versionCode

    fun markWarned(context: Context, versionCode: Int = BuildConfig.VERSION_CODE) {
        prefs(context).edit().putInt(KEY_WARNED_VERSION, versionCode).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * __DEVICE_INTEGRITY_2026_10_01__ Hosted once at the root (next to CallRatingHost).
 * Runs the heuristics once per process, off the main thread, and shows the dialog
 * once per app version if something was found. It informs only: no key is wiped,
 * nothing is blocked.
 */
@Composable
fun DeviceIntegrityHost() {
    val context = LocalContext.current
    var verdict by remember { mutableStateOf<IntegrityVerdict?>(null) }
    LaunchedEffect(Unit) {
        val v = DeviceIntegrity.check(context)
        if (v.isFlagged && DeviceIntegrityWarningStore.shouldWarn(context)) verdict = v
    }
    val v = verdict ?: return
    val dismiss = {
        DeviceIntegrityWarningStore.markWarned(context)
        verdict = null
    }
    OshiAlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.device_integrity_dialog_title)) },
        text = {
            Text(stringResource(R.string.device_integrity_dialog_body, integrityFindingsText(v.findings)))
        },
        confirmButton = {
            TextButton(onClick = dismiss) { Text(stringResource(R.string.device_integrity_dialog_ok)) }
        },
    )
}

/** __DEVICE_INTEGRITY_2026_10_01__ "su binary, Magisk" — localized, stable order. */
@Composable
fun integrityFindingsText(findings: Set<IntegrityFinding>): String =
    IntegrityFinding.values().filter { it in findings }.map { finding ->
        stringResource(
            when (finding) {
                IntegrityFinding.SU_BINARY -> R.string.device_integrity_finding_su
                IntegrityFinding.MAGISK -> R.string.device_integrity_finding_magisk
                IntegrityFinding.ROOT_MANAGER_APP -> R.string.device_integrity_finding_root_app
                IntegrityFinding.HOOK_FRAMEWORK -> R.string.device_integrity_finding_hook
                IntegrityFinding.TEST_KEYS -> R.string.device_integrity_finding_test_keys
                IntegrityFinding.DEBUGGABLE_BUILD -> R.string.device_integrity_finding_debuggable
                IntegrityFinding.FRIDA -> R.string.device_integrity_finding_frida
            }
        )
    }.joinToString(", ")
