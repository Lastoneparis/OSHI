package com.oshi.messenger.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

// ─────────────────────────────────────────────────────────────────────────
// __GLASS_ALL_THEMES_2026_11_21__ "Reduce transparency", Android edition.
//
// Android has no "reduce transparency" switch (iOS does). The two settings
// that say "I need more contrast" are the closest, and both are read here:
//
// * Android 14+ Settings → Accessibility → Colour and motion → Contrast
//   (`UiModeManager.getContrast()`, -1..1; 0 = standard). Medium is 0.5,
//   High 1.0. Anything above standard counts.
// * « High contrast text » (`Settings.Secure.high_text_contrast_enabled`,
//   every API level; not in the public SDK constants but a stable key that
//   apps read).
//
// Either one turns every glass fill opaque and switches the blur off; the rims
// stay — they are edges, and edges help. Both are watched live.
// ─────────────────────────────────────────────────────────────────────────

object GlassAccessibility {
    private const val HIGH_TEXT_CONTRAST = "high_text_contrast_enabled"

    /** Pure rule, unit-tested: contrast above standard, or high-contrast text. */
    fun wantsOpaque(contrast: Float, highTextContrast: Boolean): Boolean =
        highTextContrast || contrast > 0.25f

    fun highContrast(context: Context): Boolean {
        val contrast = if (Build.VERSION.SDK_INT >= 34) runCatching {
            context.getSystemService(UiModeManager::class.java)?.contrast ?: 0f
        }.getOrDefault(0f) else 0f
        val text = runCatching {
            Settings.Secure.getInt(context.contentResolver, HIGH_TEXT_CONTRAST, 0) == 1
        }.getOrDefault(false)
        return wantsOpaque(contrast, text)
    }

    /** Live: re-reads when either setting changes. */
    @Composable
    fun rememberHighContrast(): Boolean {
        val context = LocalContext.current.applicationContext
        var value by remember { mutableStateOf(highContrast(context)) }
        DisposableEffect(context) {
            val handler = Handler(Looper.getMainLooper())
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) { value = highContrast(context) }
            }
            runCatching {
                context.contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(HIGH_TEXT_CONTRAST), false, observer
                )
            }
            var listener: Any? = null
            if (Build.VERSION.SDK_INT >= 34) runCatching {
                val ui = context.getSystemService(UiModeManager::class.java)
                val l = UiModeManager.ContrastChangeListener { value = highContrast(context) }
                ui?.addContrastChangeListener(context.mainExecutor, l)
                listener = l
            }
            onDispose {
                runCatching { context.contentResolver.unregisterContentObserver(observer) }
                if (Build.VERSION.SDK_INT >= 34) runCatching {
                    (listener as? UiModeManager.ContrastChangeListener)?.let {
                        context.getSystemService(UiModeManager::class.java)?.removeContrastChangeListener(it)
                    }
                }
            }
        }
        return value
    }
}
