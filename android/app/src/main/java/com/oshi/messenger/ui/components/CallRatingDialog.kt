package com.oshi.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.oshi.messenger.R
import com.oshi.messenger.service.CallRatingManager

/**
 * __CALL_RATING_2026_09_22__
 *
 * The post-call sheet, matching iOS `CallRatingView.swift` arm for arm: one tap
 * is a complete answer for a good call, and the reasons and note only appear for
 * a poor one, because that is the only case where more detail changes what
 * anyone can fix.
 *
 * Host it once, near the root — [CallRatingHost].
 */
@Composable
fun CallRatingHost() {
    val pending by CallRatingManager.pending.collectAsState()
    val item = pending ?: return
    val context = LocalContext.current

    val appVersion = remember {
        runCatching {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            "${pInfo.versionName} (${pInfo.versionCode})"
        }.getOrDefault("?")
    }

    Dialog(onDismissRequest = { CallRatingManager.dismiss() }) {
        CallRatingContent(
            wasVideo = item.wasVideo,
            onSend = { rating, reasons, comment ->
                CallRatingManager.submit(
                    rating = rating,
                    reasons = reasons,
                    comment = comment,
                    appVersion = appVersion
                )
            },
            onDismiss = { CallRatingManager.dismiss() }
        )
    }
}

@Composable
private fun CallRatingContent(
    wasVideo: Boolean,
    onSend: (Int, Set<CallRatingManager.Reason>, String) -> Unit,
    onDismiss: () -> Unit
) {
    var rating by remember { mutableStateOf(0) }
    var reasons by remember { mutableStateOf(setOf<CallRatingManager.Reason>()) }
    var comment by remember { mutableStateOf("") }

    // Three and below is where the detail is worth asking for.
    val wantsDetail = rating in 1..3

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.call_rating_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.call_rating_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))

            // ── Stars ────────────────────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..5).forEach { value ->
                    val selected = value <= rating
                    val label = stringResource(R.string.call_rating_star_accessibility, value)
                    Icon(
                        imageVector = if (selected) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = null,
                        tint = if (selected) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .semantics { contentDescription = label }
                            .clickable {
                                rating = value
                                // A good call needs nothing more from the person.
                                if (value >= 4) onSend(value, emptySet(), "")
                            }
                    )
                }
            }

            // ── Detail, only for a poor rating ───────────────────────────────
            if (wantsDetail) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.call_rating_what_happened),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))

                CallRatingManager.Reason.values().forEach { reason ->
                    val checked = reasons.contains(reason)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (checked) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                                }
                            )
                            .clickable {
                                reasons = if (checked) reasons - reason else reasons + reason
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        Icon(
                            imageVector = if (checked) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                            contentDescription = null,
                            tint = if (checked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            },
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(reason.labelRes()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it.take(300) },
                        placeholder = { Text(stringResource(R.string.call_rating_note_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.call_rating_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onSend(rating, reasons, comment) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.call_rating_send))
                }
            }

            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.call_rating_not_now),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** The reason's string resource — kept next to the UI, not in the service layer. */
private fun CallRatingManager.Reason.labelRes(): Int = when (this) {
    CallRatingManager.Reason.COULD_NOT_HEAR_THEM -> R.string.call_rating_reason_could_not_hear_them
    CallRatingManager.Reason.THEY_COULD_NOT_HEAR_ME -> R.string.call_rating_reason_they_could_not_hear_me
    CallRatingManager.Reason.CHOPPY -> R.string.call_rating_reason_choppy
    CallRatingManager.Reason.DELAY -> R.string.call_rating_reason_delay
    CallRatingManager.Reason.ECHO -> R.string.call_rating_reason_echo
    CallRatingManager.Reason.DROPPED -> R.string.call_rating_reason_dropped
}
