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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import android.os.Bundle
import com.oshi.messenger.security.StrictModeGate
import com.oshi.messenger.security.StrictModeStore
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
    // 🛡 __STRICT_MODE_2026_11_22__ This scope already wraps every Compose window that
    // has text input (root + the dialogs that re-apply it), so the copy/paste guards
    // ride on it: no Copy/Cut/Paste in the selection toolbar, a clipboard that neither
    // receives nor gives text (also covers hardware Ctrl+C/X/V in text fields), and
    // (below) an InputConnection that refuses the keyboard's copy/cut/paste commands.
    val strictStore = StrictModeStore.get(LocalContext.current)
    val strict by strictStore.active.collectAsState()
    val strictOptions by strictStore.options.collectAsState()
    val baseClipboard = LocalClipboardManager.current
    val baseToolbar = LocalTextToolbar.current
    val pasteAllowed = com.oshi.messenger.security.StrictModePolicy.pasteAllowed(strict, strictOptions)
    val clipboard = remember(baseClipboard, strict, pasteAllowed) {
        if (strict) StrictClipboardManager(baseClipboard, pasteAllowed) else baseClipboard
    }
    val toolbar = remember(baseToolbar, strict, pasteAllowed) {
        if (strict) StrictTextToolbar(baseToolbar, pasteAllowed) else baseToolbar
    }
    CompositionLocalProvider(
        LocalTextInputService provides adapter.service,
        LocalClipboardManager provides clipboard,
        LocalTextToolbar provides toolbar,
    ) {
        content()
    }
}

/**
 * 🛡 __STRICT_MODE_2026_11_22__ Nothing goes OUT through Compose's clipboard while
 * strict mode is on; paste IN only when the "block paste" sub-switch is off.
 */
internal class StrictClipboardManager(
    private val inner: ClipboardManager,
    private val pasteAllowed: Boolean,
) : ClipboardManager {
    override fun setText(annotatedString: AnnotatedString) { /* copy out refused */ }
    override fun getText(): AnnotatedString? = if (pasteAllowed) inner.getText() else null
    override fun hasText(): Boolean = pasteAllowed && inner.hasText()
}

/** 🛡 __STRICT_MODE_2026_11_22__ Selection menu without Copy/Cut (and Paste unless allowed). */
internal class StrictTextToolbar(
    private val inner: TextToolbar,
    private val pasteAllowed: Boolean,
) : TextToolbar {
    override val status: TextToolbarStatus get() = inner.status
    override fun hide() = inner.hide()
    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        val paste = if (pasteAllowed) onPasteRequested else null
        if (paste == null && onSelectAllRequested == null) { inner.hide(); return }
        inner.showMenu(rect, null, paste, null, onSelectAllRequested)
    }
}

/**
 * 🛡 __STRICT_MODE_2026_11_22__ The keyboard side of copy/paste. A keyboard can ask
 * the field to copy, cut or paste (`performContextMenuAction`) and can insert rich
 * content from its own clipboard (`commitContent`). While strict mode is on those
 * requests are refused. Text a keyboard types — including a clipboard suggestion it
 * chooses to type — arrives through `commitText` and cannot be told apart from
 * typing: that limit is stated on the explanation screen.
 */
internal class StrictInputConnection(target: InputConnection) : InputConnectionWrapper(target, true) {
    override fun performContextMenuAction(id: Int): Boolean {
        val refused = when (id) {
            android.R.id.copy, android.R.id.cut -> !StrictModeGate.copyAllowed
            android.R.id.paste, android.R.id.pasteAsPlainText -> !StrictModeGate.pasteAllowed
            else -> false
        }
        return if (refused) true else super.performContextMenuAction(id)
    }

    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean =
        if (!StrictModeGate.pasteAllowed) false else super.commitContent(inputContentInfo, flags, opts)
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
        if (isIncognito() || StrictModeGate.active) {
            outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        // 🛡 __STRICT_MODE_2026_11_22__ decided per call, so toggling applies live.
        return connection?.let { StrictInputConnection(it) }
    }

    override fun onDisposed() = inner.onDisposed()
}
