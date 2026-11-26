package com.oshi.messenger.ui.keyboard

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 🛡 __STRICT_MODE_2026_11_22__ the Compose-side copy/paste guards. */
class StrictClipboardGuardsTest {

    private class FakeClipboard(var held: AnnotatedString? = AnnotatedString("outside")) : ClipboardManager {
        override fun setText(annotatedString: AnnotatedString) { held = annotatedString }
        override fun getText(): AnnotatedString? = held
    }

    private class FakeToolbar : TextToolbar {
        var copy: (() -> Unit)? = null
        var paste: (() -> Unit)? = null
        var cut: (() -> Unit)? = null
        var selectAll: (() -> Unit)? = null
        var shown = false
        override fun showMenu(
            rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?,
        ) { copy = onCopyRequested; paste = onPasteRequested; cut = onCutRequested; selectAll = onSelectAllRequested; shown = true }
        override fun hide() { shown = false }
        override val status: TextToolbarStatus get() = if (shown) TextToolbarStatus.Shown else TextToolbarStatus.Hidden
    }

    @Test fun nothingIsCopiedOut() {
        val inner = FakeClipboard()
        StrictClipboardManager(inner, pasteAllowed = true).setText(AnnotatedString("secret message"))
        assertEquals("outside", inner.held?.text)
    }

    @Test fun pasteIsRefusedWhenBlocked() {
        val guard = StrictClipboardManager(FakeClipboard(), pasteAllowed = false)
        assertNull(guard.getText())
        assertFalse(guard.hasText())
    }

    @Test fun pasteWorksWhenTheSubSwitchIsOff() {
        val guard = StrictClipboardManager(FakeClipboard(), pasteAllowed = true)
        assertEquals("outside", guard.getText()?.text)
        assertTrue(guard.hasText())
    }

    @Test fun selectionMenuLosesCopyAndCut() {
        val inner = FakeToolbar()
        StrictTextToolbar(inner, pasteAllowed = true).showMenu(Rect.Zero, {}, {}, {}, {})
        assertNull(inner.copy)
        assertNull(inner.cut)
        assertTrue(inner.paste != null)
        assertTrue(inner.selectAll != null)
    }

    @Test fun selectionMenuLosesPasteWhenBlocked() {
        val inner = FakeToolbar()
        StrictTextToolbar(inner, pasteAllowed = false).showMenu(Rect.Zero, {}, {}, {}, {})
        assertNull(inner.paste)
    }

    @Test fun aMenuWithOnlyCopyIsNotShownAtAll() {
        val inner = FakeToolbar()
        StrictTextToolbar(inner, pasteAllowed = false).showMenu(Rect.Zero, {}, null, null, null)
        assertFalse(inner.shown)
    }
}
