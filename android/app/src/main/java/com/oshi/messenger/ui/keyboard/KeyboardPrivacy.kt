package com.oshi.messenger.ui.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oshi.messenger.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// =============================================================================
// __KEYBOARD_PRIVACY_2026_10_01__ "Protect what is typed before it is encrypted."
//
// The composer text passes through the keyboard before OSHI encrypts it. Two things
// an app can honestly do about that on Android:
//   1. ask every keyboard not to learn (IncognitoKeyboardScope, default ON);
//   2. tell the person when the active keyboard is a third-party app, which can read
//      every keystroke whatever we ask (banner in chats + status in Settings).
// What an app can NOT do: forbid a keyboard, or defend a phone that is itself
// compromised (spyware). The Settings explainer says exactly that.
// =============================================================================

/**
 * __KEYBOARD_PRIVACY_2026_10_01__ Persisted keyboard-privacy settings.
 *
 * Stored in SharedPreferences like ScreenshotProtectionManager ("screenshot_protection")
 * rather than DataStore because the incognito flag is read synchronously on the main
 * thread each time an input connection is created — no suspend point there.
 */
class KeyboardPrivacyStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _incognito = MutableStateFlow(prefs.getBoolean(KEY_INCOGNITO, true))
    val incognito: StateFlow<Boolean> = _incognito.asStateFlow()

    /** Read by the input-connection hook; plain volatile-backed getter, main thread. */
    val isIncognitoEnabled: Boolean get() = _incognito.value

    private val _dismissed = MutableStateFlow(prefs.getStringSet(KEY_DISMISSED, emptySet())!!.toSet())
    val dismissedPackages: StateFlow<Set<String>> = _dismissed.asStateFlow()

    fun setIncognito(enabled: Boolean) {
        _incognito.value = enabled
        prefs.edit().putBoolean(KEY_INCOGNITO, enabled).apply()
    }

    /** The banner is dismissed per keyboard package: installing another one re-warns. */
    fun dismissWarning(packageName: String) {
        val next = _dismissed.value + packageName
        _dismissed.value = next
        prefs.edit().putStringSet(KEY_DISMISSED, next).apply()
    }

    companion object {
        private const val PREFS = "keyboard_privacy"
        const val KEY_INCOGNITO = "incognito_keyboard"
        private const val KEY_DISMISSED = "third_party_warning_dismissed"

        @Volatile private var instance: KeyboardPrivacyStore? = null

        fun get(context: Context): KeyboardPrivacyStore =
            instance ?: synchronized(this) {
                instance ?: KeyboardPrivacyStore(context).also { instance = it }
            }
    }
}

/** __KEYBOARD_PRIVACY_2026_10_01__ The keyboard currently selected on this device. */
data class CurrentKeyboard(
    val packageName: String,
    val label: String,
    /** Built-in (system / OEM image, incl. updated system apps) or genuine Gboard. */
    val isTrusted: Boolean,
)

object KeyboardInspector {
    private const val GBOARD = "com.google.android.inputmethod.latin"
    private const val PLAY_STORE = "com.android.vending"

    /**
     * Reads Settings.Secure.DEFAULT_INPUT_METHOD ("pkg/.Service") and classifies it.
     * Returns null when it cannot be read (some OEM builds) — callers then show nothing
     * rather than a false alarm.
     */
    fun current(context: Context): CurrentKeyboard? {
        val id = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        } catch (_: Exception) {
            null
        } ?: return null
        val pkg = packageOfInputMethodId(id) ?: return null
        val pm = context.packageManager

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val info = try {
            imm?.enabledInputMethodList?.firstOrNull { it.id == id || it.packageName == pkg }
        } catch (_: Exception) {
            null
        }
        val appInfo: ApplicationInfo? = info?.serviceInfo?.applicationInfo ?: try {
            pm.getApplicationInfo(pkg, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val label = try {
            info?.loadLabel(pm)?.toString() ?: appInfo?.loadLabel(pm)?.toString()
        } catch (_: Exception) {
            null
        } ?: pkg

        val installer = if (pkg == GBOARD) installerOf(pm, pkg) else null
        return CurrentKeyboard(pkg, label, isTrusted = isTrusted(pkg, appInfo?.flags, installer))
    }

    /**
     * __DEVICE_INTEGRITY_2026_10_01__ The trust rule, factored out of [current] as a
     * pure function so it is unit-tested on the JVM (KeyboardTrustDecisionTest).
     * [appFlags] null = ApplicationInfo unreadable ⇒ not provably built-in.
     */
    fun isTrusted(packageName: String, appFlags: Int?, installer: String?): Boolean {
        val isSystem = appFlags != null &&
            (appFlags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        // Gboard installed from Play on a device that does not preload it is still the
        // Google keyboard; a sideloaded APK reusing its package name is not — hence the
        // installer check instead of trusting the package name alone.
        val isPlayGboard = packageName == GBOARD && installer == PLAY_STORE
        return isSystem || isPlayGboard
    }

    /**
     * __DEVICE_INTEGRITY_2026_10_01__ "pkg/.Service" or "pkg/pkg.Service" ⇒ "pkg";
     * null for a missing/garbled Settings.Secure value (no keyboard ⇒ no banner).
     * Same result as ComponentName.unflattenFromString(id).packageName, but pure.
     */
    fun packageOfInputMethodId(id: String?): String? {
        if (id.isNullOrBlank()) return null
        val slash = id.indexOf('/')
        if (slash <= 0 || slash == id.length - 1) return null
        return id.substring(0, slash)
    }

    private fun installerOf(pm: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(pkg)
        }
    } catch (_: Exception) {
        null
    }

    /** System picker: the only way an app may switch keyboards — the person chooses. */
    fun showPicker(context: Context) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        try {
            imm?.showInputMethodPicker()
        } catch (_: Exception) {
            try {
                context.startActivity(
                    android.content.Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * __KEYBOARD_PRIVACY_2026_10_01__ Live current keyboard: re-read whenever the default
 * input method changes (the picker is a system dialog, the screen never pauses, so a
 * lifecycle ON_RESUME would miss the switch).
 */
@Composable
fun rememberCurrentKeyboard(): State<CurrentKeyboard?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(KeyboardInspector.current(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                state.value = KeyboardInspector.current(context)
            }
        }
        val registered = try {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, observer
            )
            true
        } catch (_: Exception) {
            false
        }
        state.value = KeyboardInspector.current(context)
        onDispose {
            if (registered) context.contentResolver.unregisterContentObserver(observer)
        }
    }
    return state
}

/**
 * __KEYBOARD_PRIVACY_2026_10_01__ Chat-screen warning, shown above the composer only
 * when the active keyboard is a third-party app the person has not dismissed it for.
 * Renders nothing otherwise, so hosting it costs nothing.
 */
@Composable
fun ThirdPartyKeyboardBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { KeyboardPrivacyStore.get(context) }
    val keyboard by rememberCurrentKeyboard()
    val dismissed by store.dismissedPackages.collectAsState()
    val kb = keyboard
    val visible = kb != null && !kb.isTrusted && kb.packageName !in dismissed

    val amber = Color(0xFFF59E0B)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(14.dp),
            color = amber.copy(alpha = 0.12f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(amber.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Keyboard, contentDescription = null, tint = amber, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.keyboard_banner_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.keyboard_banner_body, kb?.label ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = { KeyboardInspector.showPicker(context) },
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Text(stringResource(R.string.keyboard_banner_switch))
                    }
                }
                IconButton(onClick = { kb?.let { store.dismissWarning(it.packageName) } }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.keyboard_banner_dismiss),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** __KEYBOARD_PRIVACY_2026_10_01__ "Gboard · Built-in keyboard" for Settings. */
@Composable
fun keyboardStatusText(keyboard: CurrentKeyboard?): String = when {
    keyboard == null -> stringResource(R.string.keyboard_status_unknown)
    keyboard.isTrusted -> stringResource(R.string.keyboard_status_trusted, keyboard.label)
    else -> stringResource(R.string.keyboard_status_third_party, keyboard.label)
}
