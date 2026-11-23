package com.oshi.messenger.ui.components

import android.content.Context
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Shop
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oshi.messenger.BuildConfig
import com.oshi.messenger.R
import com.oshi.messenger.network.version.MinimumVersionGate

private const val TAG = "UpdatesSheet"
private const val PLAY_URI = "market://details?id=com.oshi.messenger"
private const val PLAY_WEB = "https://play.google.com/store/apps/details?id=com.oshi.messenger"
private const val NEWS_URL = "https://oshi-messenger.com/blog"

/**
 * "Updates and maintenance" — what is running, whether something newer exists,
 * and where the news is written.
 *
 * __UPDATES_BUTTON_2026_11_02__ (N10) Asked for as "a button, next to the mail
 * button, to be informed when updates come out, with the new features, or
 * maintenance".
 *
 * WHAT THIS DELIBERATELY IS NOT: a subscribe switch. There is no announcement
 * channel on the server to subscribe to, so a toggle labelled "notify me"
 * would be a control that stores a boolean and changes nothing — the exact
 * shape of defect this codebase keeps finding and naming (a catalogue entry
 * that is not a feature). Everything on this sheet is backed by something that
 * already exists and already works:
 *
 *   * the running version, from `BuildConfig`;
 *   * whether a newer one is out, from [MinimumVersionGate], which the chat
 *     screen's update banner has been reading all along — it is refreshed here
 *     rather than trusted stale, because someone opening this sheet is asking
 *     the question right now;
 *   * the release notes, on the site that already publishes them.
 *
 * When the server offers no floor the gate stays null, `shouldPromptThisBuild`
 * is false, and this sheet says "you are up to date" — which is the honest
 * reading of "the server is not asking you to move".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current

    // null = still asking. Three states, so the sheet never claims "up to date"
    // before it has looked — a wrong reassurance is worse than a spinner.
    var updateAvailable by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        runCatching { MinimumVersionGate.refresh(force = true) }
        // __UPDATE_LATEST_VERSION_2026_11_03__ (Anatole A7) Two different
        // questions, and this sheet was only ever asking the first.
        //
        // `shouldPromptThisBuild` means "you are below the minimum we still
        // support". The floor sits at 1.6.38 and production is 1.6.66, so every
        // user between the two was told they were up to date — reported as "the
        // button does not warn when an update comes out". It was answering
        // honestly; it was answering the wrong question.
        //
        // `newerAvailableThanThisBuild` is the question the user is actually
        // asking. It stays false until the server publishes
        // `android_latest_version`, so this is inert rather than wrong on a
        // server that has not been updated yet.
        updateAvailable = runCatching {
            MinimumVersionGate.shouldPromptThisBuild() ||
                MinimumVersionGate.newerAvailableThanThisBuild()
        }.getOrDefault(false)
    }

    OshiModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Campaign, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.updates_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                stringResource(R.string.updates_current_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when (updateAvailable) {
                null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.updates_checking),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                true -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.SystemUpdateAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.updates_available),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Button(
                        onClick = { openUri(context, PLAY_URI, PLAY_WEB) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(stringResource(R.string.app_update_available_action)) }
                }

                false -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.updates_up_to_date),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // (N6) "Dans mises à jour et maintenance mettre un bouton pour
            // rediriger vers OSHI dans le Play Store." There already WAS one —
            // inside the `true` branch above, so it existed only for the minutes
            // after the gate had decided an update was out. Someone who is up to
            // date, or whose check is still in flight, or whose check failed, saw
            // no way to the store at all, which is every state but one. Shown in
            // all the others; the `true` branch keeps its own filled button as
            // the primary action rather than being given a second one.
            if (updateAvailable != true) {
                OutlinedButton(
                    onClick = { openUri(context, PLAY_URI, PLAY_WEB) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.Shop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.updates_open_store))
                }
            }

            Divider()

            Text(
                stringResource(R.string.updates_news_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { openUri(context, NEWS_URL, NEWS_URL) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.updates_news_action))
            }
        }
    }
}

/** A missing handler must never crash a courtesy screen. */
private fun openUri(context: Context, primary: String, fallback: String) {
    val flags = Intent.FLAG_ACTIVITY_NEW_TASK
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(primary)).addFlags(flags))
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)).addFlags(flags))
        } catch (e: Exception) {
            Log.w(TAG, "no handler for $fallback: ${e.message}")
        }
    }
}
