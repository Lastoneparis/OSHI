package com.oshi.messenger.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.oshi.messenger.R
import com.oshi.messenger.ui.theme.OshiAlertDialog
import com.oshi.messenger.ui.theme.glassSurface
import com.oshi.messenger.update.FlexibleUpdateManager
import com.oshi.messenger.update.FlexibleUpdatePolicy
import com.oshi.messenger.update.UpdateSnoozeStore

/**
 * __UPDATE_PROMPT_ANDROID_2026_11_21__
 *
 * The non-forced update UI, hosted once at the root of MainActivity:
 *  * "Update available" dialog — Update (Play's FLEXIBLE flow) / Later (3-day
 *    snooze of that store version) — at most once per app start;
 *  * "Update ready · Restart" bar once the background download is done; it stays
 *    until tapped (Restart = `completeUpdate()`, Play installs and relaunches).
 *
 * Nothing is drawn during onboarding, a call or the lock cover
 * ([FlexibleUpdatePolicy.Surface.busy]). Built on [OshiAlertDialog] and
 * [glassSurface], so it follows Light / Dark / OLED black / glass like every
 * other dialog and pill of the app.
 *
 * @param restartBarHere the restart bar is drawn on the tab screens only: in a
 *   conversation it would sit on the composer, on a map on the controls.
 * @param bottomInset how far above the bottom edge the restart bar sits (the
 *   tab bar's height when it is shown).
 */
@Composable
fun UpdatePromptHost(
    onboarded: Boolean,
    inCall: Boolean,
    locked: Boolean,
    restartBarHere: Boolean,
    bottomInset: Dp,
    onUpdate: () -> Unit,
) {
    val context = LocalContext.current
    val facts by FlexibleUpdateManager.facts.collectAsState()
    val asked by FlexibleUpdateManager.askedThisStart.collectAsState()
    val surface = FlexibleUpdatePolicy.Surface(onboarded = onboarded, inCall = inCall, locked = locked)

    // Survives a rotation: `asked` is already true by then, so the policy alone
    // would make the dialog vanish under the user's thumb.
    var dialogOpen by rememberSaveable { mutableStateOf(false) }

    val store = androidx.compose.runtime.remember(context) { UpdateSnoozeStore.fromContext(context) }
    val decision = FlexibleUpdatePolicy.decide(
        play = facts,
        surface = surface,
        askedThisStart = asked,
        snoozedVersionCode = runCatching { store.snoozedVersionCode }.getOrNull(),
        snoozedUntilMs = runCatching { store.snoozedUntilMs }.getOrNull(),
        nowMs = System.currentTimeMillis(),
    )

    LaunchedEffect(decision) {
        if (decision == FlexibleUpdatePolicy.Prompt.ASK) {
            FlexibleUpdateManager.onAskShown()
            dialogOpen = true
        }
    }

    if (dialogOpen && !surface.busy && facts?.forced != true) {
        val later = {
            dialogOpen = false
            FlexibleUpdateManager.onLater(context)
        }
        UpdateAvailableDialog(
            onUpdate = {
                dialogOpen = false
                onUpdate()
            },
            onLater = later,
        )
    }

    if (decision == FlexibleUpdatePolicy.Prompt.RESTART && restartBarHere) {
        Popup(alignment = Alignment.BottomCenter, properties = PopupProperties(focusable = false)) {
            UpdateReadyBar(
                onRestart = { FlexibleUpdateManager.completeUpdate(context) },
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = bottomInset + 12.dp),
            )
        }
    }
}

/** The dialog itself (public for the screenshot test / preview). */
@Composable
fun UpdateAvailableDialog(onUpdate: () -> Unit, onLater: () -> Unit) {
    OshiAlertDialog(
        // Back / tap outside = Later: the user turned it away, ask again in 3 days.
        onDismissRequest = onLater,
        icon = { OshiOMark(size = 40.dp) },
        title = { Text(stringResource(R.string.inapp_update_title)) },
        text = { Text(stringResource(R.string.inapp_update_body)) },
        confirmButton = {
            TextButton(onClick = onUpdate) {
                Text(stringResource(R.string.inapp_update_now), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text(stringResource(R.string.inapp_update_later)) }
        },
    )
}

/** "Update ready · Restart" — same pill language as the screenshot notice. */
@Composable
fun UpdateReadyBar(onRestart: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    val dark = MaterialTheme.colorScheme.surface.let { (it.red + it.green + it.blue) / 3f < 0.5f }
    val fill = if (dark) Color(0xF71C1A24) else Color(0xF7FFFFFF)
    Row(
        modifier = modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .shadow(10.dp, shape, clip = false)
            .glassSurface(shape, fill)
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OshiOMark(size = 28.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.inapp_update_ready),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRestart) {
            Text(stringResource(R.string.inapp_update_restart), fontWeight = FontWeight.SemiBold)
        }
    }
}
