package com.oshi.messenger.ui.components

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oshi.messenger.BuildConfig
import com.oshi.messenger.R
import com.oshi.messenger.network.encryption.PeerCapabilities
import com.oshi.messenger.network.version.AppVersions
import com.oshi.messenger.network.version.MinimumVersionGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "VersionNotice"
private const val PLAY_STORE_URI = "market://details?id=com.oshi.messenger"
private const val PLAY_STORE_WEB =
    "https://play.google.com/store/apps/details?id=com.oshi.messenger"

/**
 * The two version notices drawn above a conversation.
 *
 * Both are **inline, dismissible banners**. Neither is a Dialog, a ModalBottomSheet
 * nor anything else that takes the back gesture, which is on purpose: this app is
 * `targetSdk 36`, so predictive back is on, and a Compose sheet or dialog added here
 * would need an explicit `BackHandler` or the back gesture would sail straight past
 * it. An inline banner has nothing to intercept, so there is no back behaviour to get
 * wrong. Dismissal is a tap on the ✕.
 *
 * Neither notice blocks anything. They sit above the message list; composing,
 * sending, reading and the offline mesh path are all untouched whether they are
 * drawn or not.
 */
@Composable
fun VersionNotices(
    peerPublicKey: String,
    peerDisplayName: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        PeerVersionNotice(peerPublicKey = peerPublicKey, peerDisplayName = peerDisplayName)
        UpdateAvailableNotice()
    }
}

/**
 * "This contact is on an older version" — shown only when the peer ACTUALLY told
 * us a version and that version is provably below
 * [AppVersions.MIN_KEY_SCHEDULE_VERSION].
 *
 * A peer that has never sent an `appVersion` (every build that predates the field)
 * gets nothing drawn, because absent is not "old".
 */
@Composable
private fun PeerVersionNotice(peerPublicKey: String, peerDisplayName: String) {
    val context = LocalContext.current

    // `rememberSaveable`, keyed on the peer, so the dismissal survives a rotation
    // and a process death but does not leak into the next conversation.
    var dismissed by rememberSaveable(peerPublicKey) { mutableStateOf(false) }

    // 🔴 `mutableStateOf`, NOT a plain `var`. This is written after a suspension
    // (the SharedPreferences read is hopped onto Dispatchers.IO) and read by the
    // composition below; a plain `var` written across a suspension point and read
    // by Compose never triggers recomposition, so the banner would simply never be
    // drawn and nothing would say so.
    var peerVersion by remember(peerPublicKey) { mutableStateOf<String?>(null) }

    // The version LINE that number belongs to. Read together with the version and
    // never defaulted: "1.0.43" from an iPhone and "1.6.13" from an Android phone
    // are numbers on two independent ladders, and comparing them without this tag
    // labels every iOS peer "older" permanently.
    var peerPlatform by remember(peerPublicKey) { mutableStateOf<String?>(null) }

    // 🔴 The key is `peerPublicKey`, and the body writes `peerVersion` and
    // `peerPlatform` — DIFFERENT states. Nothing in this block assigns to the
    // effect's own key, which would cancel the effect halfway through and leave
    // both null forever.
    LaunchedEffect(peerPublicKey) {
        if (peerPublicKey.isEmpty()) return@LaunchedEffect
        val read = withContext(Dispatchers.IO) {
            runCatching {
                PeerCapabilities.appVersion(context, peerPublicKey) to
                    PeerCapabilities.appPlatform(context, peerPublicKey)
            }.getOrNull()
        }
        peerVersion = read?.first
        peerPlatform = read?.second
    }

    val stale = remember(peerVersion, peerPlatform) {
        AppVersions.peerPredatesKeySchedule(peerVersion, peerPlatform)
    }
    if (dismissed || !stale) return

    NoticeBanner(
        title = stringResource(R.string.peer_older_version_title, peerDisplayName),
        detail = stringResource(R.string.peer_older_version_detail),
        actionLabel = null,
        onAction = {},
        onDismiss = { dismissed = true }
    )
}

/**
 * "An update is available" — driven by [MinimumVersionGate], which fails open in
 * every direction. If the server is unreachable, answers 500, or returns something
 * that is not JSON, [MinimumVersionGate.minimumVersion] stays `null`, `shouldPrompt`
 * is false, and nothing is drawn. Silently.
 *
 * At most a dismissible prompt with a Play Store link. It never gates the composer.
 */
@Composable
private fun UpdateAvailableNotice() {
    val context = LocalContext.current
    var dismissed by rememberSaveable { mutableStateOf(false) }

    // Same two rules as above: state, not a bare `var`, and the effect's key
    // (`Unit`) is never assigned inside the block.
    var promptNeeded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { MinimumVersionGate.refresh() }
        promptNeeded = runCatching { MinimumVersionGate.shouldPromptThisBuild() }
            .getOrDefault(false)
    }

    if (dismissed || !promptNeeded) return

    NoticeBanner(
        title = stringResource(R.string.app_update_available_title),
        detail = stringResource(
            R.string.app_update_available_detail,
            BuildConfig.VERSION_NAME
        ),
        actionLabel = stringResource(R.string.app_update_available_action),
        onAction = { openPlayStore(context) },
        onDismiss = { dismissed = true }
    )
}

@Composable
private fun NoticeBanner(
    title: String,
    detail: String,
    actionLabel: String?,
    onAction: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Default.SystemUpdateAlt,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (actionLabel != null) {
                TextButton(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) {
                    Text(actionLabel)
                }
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.version_notice_dismiss),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A missing Play Store must never crash a courtesy prompt. */
private fun openPlayStore(context: android.content.Context) {
    val flags = Intent.FLAG_ACTIVITY_NEW_TASK
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_URI)).addFlags(flags)
        )
    } catch (_: Exception) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_WEB)).addFlags(flags)
            )
        } catch (e: Exception) {
            Log.w(TAG, "no handler for the store link: ${e.message}")
        }
    }
}
