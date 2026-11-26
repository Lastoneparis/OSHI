package com.oshi.messenger.ui.components

import android.graphics.Rect as AndroidRect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.oshi.messenger.R
import com.oshi.messenger.util.MessageFormatting

/**
 * __TEXT_FORMATTING_2026_11_24__ The composer's Bold / Underline actions.
 *
 * They live in the text-selection menu (the floating Cut / Copy / Paste bar), not as
 * buttons on the pill: nothing new on screen until the user selects text or long-presses
 * the cursor, which is where WhatsApp and Telegram put them too.
 *
 *  * With text selected: wraps it in `**` / `__` and keeps the inner text selected
 *    (tapping again on the same selection removes the markers).
 *  * On the cursor (long-press → menu): inserts `****` / `____` with the cursor between.
 *
 * The screens keep their `String` draft; this holds the selection beside it
 * ([TextFieldValue]) and puts the cursor at the end whenever the draft is changed from
 * outside (a mention picked, a draft restored, an edit loaded).
 */
@Composable
fun FormattingComposerField(
    text: String,
    onTextChange: (String) -> Unit,
    field: @Composable (value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit) -> Unit,
) {
    var held by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val value = if (held.text == text) held else TextFieldValue(text, TextRange(text.length))
    SideEffect { if (held !== value) held = value }

    val currentText by rememberUpdatedState(text)
    val currentOnTextChange by rememberUpdatedState(onTextChange)
    val onValueChange: (TextFieldValue) -> Unit = { v ->
        held = v
        if (v.text != currentText) currentOnTextChange(v.text)
    }
    val onValueChangeState = rememberUpdatedState(onValueChange)

    fun format(marker: String) {
        val now = held
        val edit = MessageFormatting.toggle(now.text, now.selection.start, now.selection.end, marker)
        onValueChangeState.value(TextFieldValue(edit.text, TextRange(edit.selectionStart, edit.selectionEnd)))
    }

    val view = LocalView.current
    val boldLabel = stringResource(R.string.composer_format_bold)
    val underlineLabel = stringResource(R.string.composer_format_underline)
    val toolbar = remember(view) { FormattingTextToolbar(view) }
    toolbar.extraItems = listOf(
        boldLabel to { format(MessageFormatting.BOLD) },
        underlineLabel to { format(MessageFormatting.UNDERLINE) },
    )

    CompositionLocalProvider(LocalTextToolbar provides toolbar) {
        field(value, onValueChange)
    }
}

/**
 * The platform floating text toolbar (same items and order as Compose's own
 * `AndroidTextToolbar`) plus [extraItems] after Select all.
 */
internal class FormattingTextToolbar(private val view: View) : TextToolbar {
    var extraItems: List<Pair<String, () -> Unit>> = emptyList()

    private var actionMode: ActionMode? = null
    private var rect: Rect = Rect.Zero
    private var onCopy: (() -> Unit)? = null
    private var onPaste: (() -> Unit)? = null
    private var onCut: (() -> Unit)? = null
    private var onSelectAll: (() -> Unit)? = null

    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        this.rect = rect
        onCopy = onCopyRequested
        onPaste = onPasteRequested
        onCut = onCutRequested
        onSelectAll = onSelectAllRequested
        if (actionMode == null) {
            status = TextToolbarStatus.Shown
            actionMode = view.startActionMode(callback, ActionMode.TYPE_FLOATING)
        } else {
            actionMode?.invalidate()
        }
    }

    override fun hide() {
        status = TextToolbarStatus.Hidden
        actionMode?.finish()
        actionMode = null
    }

    private val callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            populate(menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.clear()
            populate(menu)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                ID_COPY -> onCopy?.invoke()
                ID_PASTE -> onPaste?.invoke()
                ID_CUT -> onCut?.invoke()
                ID_SELECT_ALL -> onSelectAll?.invoke()
                else -> extraItems.getOrNull(item.itemId - ID_EXTRA)?.second?.invoke()
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            status = TextToolbarStatus.Hidden
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: AndroidRect) {
            outRect.set(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        }
    }

    private fun populate(menu: Menu) {
        var order = 0
        fun add(id: Int, title: CharSequence) {
            menu.add(0, id, order++, title).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        if (onCopy != null) add(ID_COPY, view.context.getString(android.R.string.copy))
        if (onPaste != null) add(ID_PASTE, view.context.getString(android.R.string.paste))
        if (onCut != null) add(ID_CUT, view.context.getString(android.R.string.cut))
        if (onSelectAll != null) add(ID_SELECT_ALL, view.context.getString(android.R.string.selectAll))
        extraItems.forEachIndexed { i, (title, _) -> add(ID_EXTRA + i, title) }
    }

    private companion object {
        const val ID_COPY = 1
        const val ID_PASTE = 2
        const val ID_CUT = 3
        const val ID_SELECT_ALL = 4
        const val ID_EXTRA = 100
    }
}
