package com.oshi.messenger.ui.components

import androidx.compose.animation.animateColorAsState
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.data.model.AliveCheckInInterval
import com.oshi.messenger.data.model.AliveCheckInPolicy
import com.oshi.messenger.service.AliveCheckInStore
import com.oshi.messenger.service.AliveDurationText
import kotlinx.coroutines.delay

/**
 * __ALIVE_CHECK_IN_2026_11_04__ Everything the liveness check-in draws.
 *
 * ## The colour rule
 *
 * Green and red are ACCENTS — a dot, a hairline, a tint on one word — never a
 * filled panel. Two reasons, and both are about the same failure:
 *
 *  * A red card across the top of a conversation reads as an alarm, and this
 *    feature must never claim an emergency it cannot know about (see
 *    [AliveCheckInPolicy.OVERDUE_IS_NOT_PROOF]).
 *  * Colour alone never carries the meaning. Every state also changes the
 *    ICON and the WORDS, because a red dot and a green dot are the same dot to
 *    a red-green colour-blind reader, and because a screen reader reads
 *    neither.
 *
 * ## The countdown ticks in the UI, not in a timer
 *
 * [rememberAliveNow] re-reads the clock on a cadence and nothing else moves.
 * The displayed value is always computed from the two stored timestamps at the
 * moment of the frame, so a composition that was off-screen for six hours comes
 * back correct rather than six hours behind — a countdown held in state and
 * decremented would drift exactly as long as the app was paused, which is most
 * of the time for this feature.
 */
object AliveCheckInColors {
    /**
     * Not `colorScheme.error`: that red is tuned to shout, which is wrong for a
     * state whose honest name is "no answer yet". A desaturated amber-red reads
     * as attention rather than danger, and clears 4.5:1 on both surfaces.
     */
    val overdue = Color(0xFFD9544F)
    val confirmed = Color(0xFF34B27B)
}

/**
 * The clock, re-read on a cadence chosen by what is actually on screen.
 *
 * Thirty seconds while a deadline is hours away would be 120 wasted
 * recompositions an hour for a number that changes once; ten minutes with four
 * minutes left on the clock would show a countdown that has stopped. So the
 * cadence follows the smallest unit currently displayed.
 */
@Composable
fun rememberAliveNow(remainingMillis: Long): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val cadence = when {
        kotlin.math.abs(remainingMillis) < 2 * 60_000L -> 5_000L
        kotlin.math.abs(remainingMillis) < 60 * 60_000L -> 20_000L
        else -> 60_000L
    }
    LaunchedEffect(cadence) {
        while (true) {
            delay(cadence)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * The strip that sits under the conversation's top bar once a check-in exists
 * in either direction.
 *
 * Absent entirely when nothing is configured — the entry point for that case is
 * the conversation's overflow sheet. A permanent bar in every one of a user's
 * conversations, for an opt-in feature most of them will never use, is the
 * clutter the design brief calls out: "avoid intrusive pop-ups".
 */
@Composable
fun AliveCheckInBar(
    peerName: String,
    entry: AliveCheckInStore.Entry,
    onConfirm: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entry.isIdle) return
    val context = LocalContext.current
    val due = entry.rowDueAtMillis()
    val now = rememberAliveNow(if (due > 0) due - System.currentTimeMillis() else Long.MAX_VALUE)
    val status = entry.rowStatus(now)

    val accent by animateColorAsState(
        targetValue = when (status) {
            AliveCheckInPolicy.Status.OVERDUE -> AliveCheckInColors.overdue
            AliveCheckInPolicy.Status.CONFIRMED -> AliveCheckInColors.confirmed
            AliveCheckInPolicy.Status.NEUTRAL -> MaterialTheme.colorScheme.outline
        },
        animationSpec = tween(durationMillis = 260),
        label = "aliveAccent",
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(18.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSettings)
                .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AliveStatusDot(status = status, accent = accent)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Their lane first: it is the half the reader cannot act on, and
                // therefore the half they came to check.
                if (entry.theirsEnabled) {
                    AliveLaneLine(
                        who = peerName,
                        text = laneText(
                            context = context,
                            enabled = true,
                            lastConfirmedAtMillis = entry.theirsLastConfirmedAtMillis,
                            intervalSeconds = entry.theirsIntervalSeconds,
                            now = now,
                        ),
                        emphasised = entry.theirStatus(now) == AliveCheckInPolicy.Status.OVERDUE,
                        accent = accent,
                    )
                }
                if (entry.mineEnabled) {
                    AliveLaneLine(
                        who = stringResource(R.string.alive_lane_you),
                        text = laneText(
                            context = context,
                            enabled = true,
                            lastConfirmedAtMillis = entry.mineLastConfirmedAtMillis,
                            intervalSeconds = entry.mineIntervalSeconds,
                            now = now,
                        ),
                        emphasised = entry.myStatus(now) == AliveCheckInPolicy.Status.OVERDUE,
                        accent = accent,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onConfirm,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 14.dp, vertical = 6.dp
                ),
            ) {
                Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.alive_action_im_alive),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = stringResource(R.string.alive_bar_open_settings),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun AliveLaneLine(who: String, text: String, emphasised: Boolean, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = who,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            // The WORDS already say which state this is; the colour only
            // reinforces it, and only on the half that is overdue.
            color = if (emphasised) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (emphasised) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One line about one direction: how long is left, or how long it has been.
 *
 * Both readings come from the same two numbers, so there is no state in which
 * the countdown and the colour can disagree.
 */
fun laneText(
    context: android.content.Context,
    enabled: Boolean,
    lastConfirmedAtMillis: Long,
    intervalSeconds: Long,
    now: Long,
): String {
    if (!enabled || lastConfirmedAtMillis <= 0L || intervalSeconds <= 0L) {
        return context.getString(R.string.alive_state_not_set)
    }
    val remaining = AliveCheckInPolicy.remainingMillis(lastConfirmedAtMillis, intervalSeconds, now)
    return if (remaining >= 0) {
        context.getString(R.string.alive_state_due_in, AliveDurationText.short(context, remaining))
    } else {
        context.getString(R.string.alive_state_overdue_by, AliveDurationText.short(context, -remaining))
    }
}

/**
 * The dot. It pulses while confirmed, holds still while overdue.
 *
 * That way round on purpose: a pulse reads as "alive, and recently", and
 * freezing it is the visual form of "nothing has arrived". An animation that
 * got FASTER when overdue would be the app raising its voice about a silence it
 * cannot explain.
 *
 * The pulse is dropped entirely when the platform animator scale is off — the
 * same read `MainActivity.isReduceMotionOn` makes for the nav graph. The state
 * is still legible without it, because it is also an icon and a sentence.
 */
@Composable
private fun AliveStatusDot(status: AliveCheckInPolicy.Status, accent: Color) {
    val context = LocalContext.current
    val reduceMotion = remember {
        try {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        } catch (_: Exception) {
            false
        }
    }
    val pulse = if (status == AliveCheckInPolicy.Status.CONFIRMED && !reduceMotion) {
        val transition = rememberInfiniteTransition(label = "alivePulse")
        transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_400),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "alivePulseAlpha",
        ).value
    } else {
        1f
    }

    val description = stringResource(
        when (status) {
            AliveCheckInPolicy.Status.CONFIRMED -> R.string.alive_a11y_confirmed
            AliveCheckInPolicy.Status.OVERDUE -> R.string.alive_a11y_overdue
            AliveCheckInPolicy.Status.NEUTRAL -> R.string.alive_a11y_neutral
        }
    )

    Box(
        modifier = Modifier
            .size(22.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(accent.copy(alpha = 0.14f * pulse), CircleShape)
        )
        Icon(
            imageVector = if (status == AliveCheckInPolicy.Status.CONFIRMED) {
                Icons.Default.Favorite
            } else {
                Icons.Default.FavoriteBorder
            },
            contentDescription = null,
            tint = accent.copy(alpha = if (status == AliveCheckInPolicy.Status.NEUTRAL) 0.6f else pulse),
            modifier = Modifier.size(13.dp),
        )
    }
}

/**
 * The marker a CONVERSATION ROW carries: a hairline on the leading edge plus a
 * heart glyph before the name.
 *
 * Deliberately not a coloured background or a badge. The row already spends its
 * colour budget on the unread pill, and a check-in state that outranked unread
 * would reorder the reader's attention away from the actual messages.
 */
@Composable
fun AliveRowMarker(status: AliveCheckInPolicy.Status, modifier: Modifier = Modifier) {
    if (status == AliveCheckInPolicy.Status.NEUTRAL) return
    val accent = when (status) {
        AliveCheckInPolicy.Status.OVERDUE -> AliveCheckInColors.overdue
        else -> AliveCheckInColors.confirmed
    }
    val description = stringResource(
        if (status == AliveCheckInPolicy.Status.OVERDUE) R.string.alive_a11y_overdue
        else R.string.alive_a11y_confirmed
    )
    Icon(
        imageVector = if (status == AliveCheckInPolicy.Status.OVERDUE) {
            Icons.Default.FavoriteBorder
        } else {
            Icons.Default.Favorite
        },
        contentDescription = description,
        tint = accent,
        modifier = modifier.size(12.dp),
    )
}

/** The countdown a conversation row shows beside the preview, or null. */
@Composable
fun aliveRowCountdown(entry: AliveCheckInStore.Entry?): String? {
    if (entry == null || entry.isIdle) return null
    val context = LocalContext.current
    val due = entry.rowDueAtMillis()
    if (due <= 0L) return null
    val now = rememberAliveNow(due - System.currentTimeMillis())
    val remaining = due - now
    return if (remaining >= 0) {
        context.getString(R.string.alive_state_due_in, AliveDurationText.short(context, remaining))
    } else {
        context.getString(R.string.alive_state_overdue_by, AliveDurationText.short(context, -remaining))
    }
}

/**
 * The settings sheet: the switch, the interval, the other side's state, and the
 * two sentences that keep the feature honest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AliveCheckInSheet(
    peerName: String,
    entry: AliveCheckInStore.Entry,
    onConfirm: () -> Unit,
    onEnable: (Long) -> Unit,
    onDisable: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    OshiModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        // Scrolls, and `GroupChatSheetReachabilityTest` is why: this body grows
        // with the state — the interval rows and the custom stepper only exist
        // while the switch is on — and at its tallest it does not fit a short or
        // landscape screen. Without this the two notes at the bottom, which are
        // the ones that keep the feature honest about what it can promise, are
        // exactly what gets stranded off the end.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null,
                    tint = AliveCheckInColors.confirmed,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.alive_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                stringResource(R.string.alive_sheet_explainer, peerName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Our own half ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.alive_sheet_mine_switch, peerName),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = entry.mineEnabled,
                    onCheckedChange = { on ->
                        if (on) onEnable(entry.mineIntervalSeconds) else onDisable()
                    },
                )
            }

            if (entry.mineEnabled) {
                Text(
                    stringResource(R.string.alive_sheet_interval),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AliveIntervalRow(
                        label = stringResource(R.string.alive_interval_24h),
                        selected = entry.mineIntervalSeconds == AliveCheckInInterval.EVERY_24H,
                        onClick = { onEnable(AliveCheckInInterval.EVERY_24H) },
                    )
                    AliveIntervalRow(
                        label = stringResource(R.string.alive_interval_48h),
                        selected = entry.mineIntervalSeconds == AliveCheckInInterval.EVERY_48H,
                        onClick = { onEnable(AliveCheckInInterval.EVERY_48H) },
                    )
                    AliveIntervalRow(
                        label = stringResource(R.string.alive_interval_72h),
                        selected = entry.mineIntervalSeconds == AliveCheckInInterval.EVERY_72H,
                        onClick = { onEnable(AliveCheckInInterval.EVERY_72H) },
                    )
                    // Custom is shown as a row carrying its own current value, so a
                    // user who set "every 8 hours" sees that number rather than an
                    // unlabelled "Custom" they have to tap to recall.
                    if (!AliveCheckInInterval.isPreset(entry.mineIntervalSeconds)) {
                        AliveIntervalRow(
                            label = stringResource(
                                R.string.alive_interval_custom_hours,
                                (entry.mineIntervalSeconds / AliveCheckInInterval.HOUR_SECONDS).toInt()
                            ),
                            selected = true,
                            onClick = { },
                        )
                    }
                    AliveCustomIntervalStepper(
                        currentSeconds = entry.mineIntervalSeconds,
                        onPick = onEnable,
                    )
                }

                FilledTonalButton(
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.alive_action_im_alive), fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = onDisable, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.alive_sheet_stop))
                }
            }

            // ── Their half: read-only. We cannot set someone else's schedule. ──
            Text(
                stringResource(R.string.alive_sheet_their_section, peerName),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!entry.theirsEnabled) {
                Text(
                    stringResource(R.string.alive_sheet_their_none, peerName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val now = rememberAliveNow(
                    AliveCheckInPolicy.remainingMillis(
                        entry.theirsLastConfirmedAtMillis,
                        entry.theirsIntervalSeconds,
                        System.currentTimeMillis(),
                    )
                )
                Text(
                    text = laneText(
                        context = context,
                        enabled = true,
                        lastConfirmedAtMillis = entry.theirsLastConfirmedAtMillis,
                        intervalSeconds = entry.theirsIntervalSeconds,
                        now = now,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = AliveDurationText.interval(context, entry.theirsIntervalSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.alive_note_no_location),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.alive_note_delivery),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AliveIntervalRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        },
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                // 48 dp: a selectable row has to clear the touch-target floor,
                // and three of these stacked at 36 dp is where mis-taps start.
                .height(48.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (selected) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null,
                    tint = AliveCheckInColors.confirmed,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * Hours, by steps of one, between the policy's floor and ceiling.
 *
 * A stepper rather than a text field: a keyboard here invites "0", "999" and
 * "", each of which has to be rejected with a message, and the clamp in
 * [AliveCheckInInterval.clamp] would silently rewrite what the user typed. A
 * stepper cannot express an invalid interval at all.
 */
@Composable
private fun AliveCustomIntervalStepper(currentSeconds: Long, onPick: (Long) -> Unit) {
    val hours = (currentSeconds / AliveCheckInInterval.HOUR_SECONDS).toInt().coerceAtLeast(1)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.alive_interval_custom),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                onPick(
                    AliveCheckInInterval.clamp((hours - 1).toLong() * AliveCheckInInterval.HOUR_SECONDS)
                )
            }
        ) { Text("−") }
        Text(
            stringResource(R.string.alive_interval_custom_hours, hours),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(
            onClick = {
                onPick(
                    AliveCheckInInterval.clamp((hours + 1).toLong() * AliveCheckInInterval.HOUR_SECONDS)
                )
            }
        ) { Text("+") }
    }
}
