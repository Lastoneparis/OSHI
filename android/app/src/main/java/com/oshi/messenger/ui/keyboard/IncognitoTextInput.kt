// __KEYBOARD_PRIVACY_2026_10_01__
// WHY the suppression: Compose UI 1.5.4 (BOM 2023.10.01) has no public hook on the
// EditorInfo of a text field — InterceptPlatformTextInput only exists from 1.7.
// The stock text-input plugin (AndroidTextInputServicePlugin) is `internal`, so we
// reach it here, in this one file, and nowhere else. The replacement when the BOM
// is bumped is `InterceptPlatformTextInput { request, next -> ... }` around the
// app content; delete this file in that change.
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.oshi.messenger.ui.keyboard

import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalPlatformTextInputPluginRegistry
import androidx.compose.ui.platform.LocalTextInputService
import androidx.compose.ui.text.input.AndroidTextInputServicePlugin
import androidx.compose.ui.text.input.PlatformTextInput
import androidx.compose.ui.text.input.PlatformTextInputAdapter
import androidx.compose.ui.text.input.PlatformTextInputPlugin

/**
 * __KEYBOARD_PRIVACY_2026_10_01__ Incognito keyboard for EVERY text field below it.
 *
 * WHY: what is typed in OSHI goes through the keyboard before it is encrypted, and
 * the keyboard learns words (suggestions, cloud-synced dictionaries).
 * `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` asks it not to keep any of it —
 * Gboard and most serious keyboards honour it ("incognito"). Android cannot force a
 * keyboard to obey; this is a request, and the Settings explainer says so.
 *
 * HOW (one app-wide hook, no per-field edits): Compose creates the InputConnection
 * through the *focused* text-input adapter of the hosting AndroidComposeView. We
 * register our own plugin whose adapter wraps a stock Android text-input adapter
 * built with OUR [PlatformTextInput]; when a field starts input, the stock service
 * asks our PlatformTextInput for focus, so the view calls OUR createInputConnection,
 * which delegates and then ORs the flag into `outAttrs.imeOptions`. Fields pick the
 * wrapped service up through [LocalTextInputService], which we re-provide.
 *
 * Dialogs, popups and bottom sheets are their own AndroidComposeView and re-provide
 * their default LocalTextInputService, so this never mis-targets a window: a field in
 * a dialog simply falls back to the normal keyboard unless that dialog's content is
 * wrapped in this scope too.
 *
 * The toggle (Settings › Keyboard › Incognito keyboard) is read when each input
 * connection is created, so it applies from the next time a field is focused.
 */
@Composable
fun IncognitoKeyboardScope(content: @Composable () -> Unit) {
    val store = KeyboardPrivacyStore.get(LocalContext.current)
    val registry = LocalPlatformTextInputPluginRegistry.current
    val adapter = registry.rememberAdapter(IncognitoTextInputPlugin)
    adapter.isIncognito = { store.isIncognitoEnabled }
    CompositionLocalProvider(LocalTextInputService provides adapter.service) {
        content()
    }
}

private object IncognitoTextInputPlugin : PlatformTextInputPlugin<IncognitoTextInputAdapter> {
    override fun createAdapter(platformTextInput: PlatformTextInput, view: View): IncognitoTextInputAdapter =
        IncognitoTextInputAdapter(AndroidTextInputServicePlugin.createAdapter(platformTextInput, view))
}

private class IncognitoTextInputAdapter(
    private val inner: AndroidTextInputServicePlugin.Adapter,
) : PlatformTextInputAdapter {
    val service get() = inner.service

    @Volatile
    var isIncognito: () -> Boolean = { true }

    override fun createInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = inner.createInputConnection(outAttrs)
        if (isIncognito()) {
            outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        return connection
    }

    override fun onDisposed() = inner.onDisposed()
}
