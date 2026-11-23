package com.oshi.messenger.ui.components

import androidx.compose.foundation.clickable
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The conversation "…" menu, as a Material 3 modal bottom sheet.
 *
 * __CHAT_OVERFLOW_IS_A_SHEET_2026_11_02__ This used to be a `DropdownMenu`: a
 * floating card anchored to the ⋮ button in the top bar, holding fifteen items.
 * Asked for by a user who sent a screenshot of both, and the reasons hold up on
 * their own:
 *
 *  * Fifteen items do not fit the space a dropdown anchored under the status
 *    bar can use, so Compose scrolls them inside the popup. The result is a
 *    long scrolling list whose reachable half is the half furthest from the
 *    thumb.
 *  * Every other menu of comparable size in this app is already a
 *    `ModalBottomSheet` — check-in, the poll composer, the bubble-style picker,
 *    the wallpaper picker. The ⋮ menu was the odd one out, so the app changed
 *    presentation style depending on which button was pressed.
 *
 * Rows keep the same shape as the `DropdownMenuItem` calls they replaced —
 * `text`, `onClick`, `leadingIcon` — so the conversion was a rename at each
 * call site, and nothing about what the items DO changed.
 *
 * Back behaviour is `ModalBottomSheet`'s own, which is the reason to use it
 * rather than hand-rolling a sheet: this module is `targetSdk 36`, predictive
 * back is on, and a bespoke sheet would need its own `BackHandler` or the back
 * gesture would sail past it and leave the conversation (`VersionNotice.kt`
 * chose an inline banner over a sheet for exactly that reason).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatOverflowSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ChatSheetScope.() -> Unit,
) {
    OshiModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            ChatSheetScope.content()
        }
    }
}

/**
 * Receiver for [ChatOverflowSheet]'s body.
 *
 * Stateless. It exists so [ChatSheetItem] and [ChatSheetDivider] cannot be
 * called from somewhere that is not a sheet: a plain lambda would have let a
 * row be dropped into any layout, where its full-width click target and sheet
 * margins read as a broken list row instead of a compile error.
 */
object ChatSheetScope

/**
 * One row of the sheet. Deliberately the same parameter names as
 * `DropdownMenuItem`, so the call sites read unchanged.
 *
 * [destructive] paints the row in the error colour, for the one item ("Delete
 * conversation") the dropdown used to colour by tinting its `Text` and `Icon`
 * separately at the call site.
 */
@Composable
fun ChatSheetScope.ChatSheetItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    leadingIcon: @Composable (() -> Unit)? = null,
    destructive: Boolean = false,
) {
    val tint = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The whole row is the target, not the label. A sheet row that only
            // answers under its text is the classic "I tapped it and nothing
            // happened" report.
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (leadingIcon != null) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides tint) { leadingIcon() }
            }
            Spacer(Modifier.width(20.dp))
        } else {
            Spacer(Modifier.width(44.dp))
        }
        CompositionLocalProvider(LocalContentColor provides tint) {
            ProvideTextStyle(MaterialTheme.typography.bodyLarge.copy(color = tint)) { text() }
        }
    }
}

/** The rule the dropdown drew with `Divider()`, at sheet margins. */
@Composable
fun ChatSheetScope.ChatSheetDivider() {
    Divider(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
