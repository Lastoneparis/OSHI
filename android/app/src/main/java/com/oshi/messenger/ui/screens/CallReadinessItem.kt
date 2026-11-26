package com.oshi.messenger.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.oshi.messenger.R
import com.oshi.messenger.service.CallReadiness
import com.oshi.messenger.ui.theme.OSHIRed

/**
 * __CALL_WAKE_2026_11_22__ Settings → Calls: "Incoming calls may not ring" with the
 * reason and a tap that opens the system screen fixing it. Shows nothing when all is
 * well. Re-read on every resume (the user comes back from the system settings).
 */
@Composable
internal fun CallReadinessItem() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var epoch by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) epoch++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val issues = remember(epoch) { CallReadiness.evaluate(CallReadiness.read(context)) }
    val first = issues.firstOrNull() ?: return
    SettingsItem(
        icon = Icons.Default.Warning,
        iconTint = OSHIRed,
        title = stringResource(R.string.call_readiness_title),
        subtitle = stringResource(
            when (first) {
                CallReadiness.Issue.NOTIFICATIONS_OFF -> R.string.call_readiness_notifications
                CallReadiness.Issue.CALL_CHANNEL_OFF -> R.string.call_readiness_channel
                CallReadiness.Issue.FULL_SCREEN_DENIED -> R.string.call_readiness_full_screen
                CallReadiness.Issue.BACKGROUND_RESTRICTED -> R.string.call_readiness_background
            }
        ),
        onClick = { runCatching { context.startActivity(CallReadiness.settingsIntent(context, first)) } }
    )
}
