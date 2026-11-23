package com.oshi.messenger.ui.components

import com.oshi.messenger.ui.components.bubbleClickable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.data.model.PollMessage
import com.oshi.messenger.data.model.PollResults
import com.oshi.messenger.service.PollStore
import java.util.concurrent.TimeUnit

/**
 * The two surfaces of a poll: the sheet that composes one, and the bubble that
 * shows it in a thread. Both are used unchanged by a 1:1 chat and by a group —
 * a poll does not behave differently in a group, it just has more voters.
 *
 * Layout notes that are not decoration
 * ------------------------------------
 * * The answer label takes `Modifier.weight(1f)` and the percentage does not,
 *   so a long answer wraps and the number keeps its width. The other way round
 *   gives one letter per line, which is the trap AX5 found on nine screens of
 *   another app in this repo.
 * * The bar is a Row of two Boxes rather than a `LinearProgressIndicator`, so
 *   it mirrors itself under an RTL layout direction — three of the 34 languages
 *   this ships in are right-to-left.
 * * The bar is drawn against the number of VOTERS, not the number of picks, so
 *   in a multiple-answer poll the bars can add up past 100 % and each one still
 *   reads as "this share of people chose it".
 *
 * iOS: `OSHI/PollViews.swift`.
 */

@Composable
fun PollBubble(
    content: String,
    // No `isFromMe`: a poll looks the same whoever asked, and this was declared
    // and never read on both platforms.
    myPublicKey: String,
    onVote: (PollMessage, List<Int>) -> Unit,
    displayName: (String) -> String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    PollStore.attach(context)

    val poll = remember(content) { PollMessage.fromMessageString(content) }
    if (poll == null) {
        Text(
            text = "📊 " + stringResource(R.string.poll_title),
            modifier = modifier.padding(12.dp),
        )
        return
    }

    // Collecting the whole table and computing here keeps the bubble correct
    // when a ballot arrives while it is on screen — which, in a group, is the
    // normal case rather than the exception.
    val ballots by PollStore.ballots.collectAsState()
    val results = remember(ballots, poll.id, myPublicKey) {
        PollStore.results(poll, myPublicKey)
    }
    var showVoters by remember(poll.id) { mutableStateOf(false) }
    val closed = poll.isClosed

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 1.dp,
        modifier = modifier.widthIn(max = 320.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.BarChart,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.poll_title),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (poll.isAnonymous) PollBadge(stringResource(R.string.poll_anonymous_badge))
                if (poll.allowsMultipleAnswers) {
                    Spacer(Modifier.size(4.dp))
                    PollBadge(stringResource(R.string.poll_multiple_answers_badge))
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = poll.question,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))

            poll.options.forEachIndexed { i, label ->
                PollOptionRow(
                    label = label,
                    share = results.share(i),
                    percent = results.percent(i),
                    count = results.counts.getOrElse(i) { 0 },
                    isPicked = results.myPicks.contains(i),
                    enabled = !closed,
                    multiple = poll.allowsMultipleAnswers,
                    onTap = {
                        val next = nextPicks(poll, results.myPicks, i) ?: return@PollOptionRow
                        onVote(poll, next)
                    },
                )
                if (i != poll.options.lastIndex) Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (results.voterCount == 1) stringResource(R.string.poll_votes_one)
                           else stringResource(R.string.poll_votes, results.voterCount),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!poll.isAnonymous && results.voterCount > 0) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = if (showVoters) stringResource(R.string.poll_hide_voters)
                               else stringResource(R.string.poll_see_voters),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.bubbleClickable { showVoters = !showVoters },
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stateLabel(poll, results, closed, context),
                    fontSize = 11.sp,
                    textAlign = TextAlign.End,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (showVoters && !poll.isAnonymous) {
                Spacer(Modifier.height(6.dp))
                poll.options.forEachIndexed { i, label ->
                    val keys = results.votersByOption.getOrElse(i) { emptyList() }
                    if (keys.isNotEmpty()) {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = keys.joinToString(", ") { displayName(it) },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

/**
 * Single answer: tapping another option moves the vote, tapping the one already
 * chosen does nothing — a poll is not a thing you un-answer by accident.
 * Multiple answers: every tap toggles, and emptying the set is an explicit
 * retraction. Returns null when the tap means nothing.
 */
private fun nextPicks(poll: PollMessage, current: Set<Int>, tapped: Int): List<Int>? {
    if (poll.isClosed || !poll.isValidOptionIndex(tapped)) return null
    return if (poll.allowsMultipleAnswers) {
        if (current.contains(tapped)) (current - tapped).sorted() else (current + tapped).sorted()
    } else {
        if (current.contains(tapped)) null else listOf(tapped)
    }
}

private fun stateLabel(
    poll: PollMessage,
    results: PollResults,
    closed: Boolean,
    context: android.content.Context,
): String {
    if (closed) return context.getString(R.string.poll_closed)
    val closes = poll.closesAtUnix
    if (closes != null) {
        // __SAY_IT_LIKE_IOS_2026_10_23__ This used to bucket the remaining time
        // into the composer's six fixed labels, so a poll closing in 20 minutes
        // read "In 1 hour" — a different sentence from the one an iPhone shows
        // for the same poll, and a wrong one. `getRelativeTimeSpanString` is
        // the platform twin of iOS's `RelativeDateTimeFormatter` with
        // `unitsStyle = .short`, and it is localized by Android itself in all
        // 34 languages.
        val relative = DateUtils.getRelativeTimeSpanString(
            (closes * 1000).toLong(),
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
        return context.getString(R.string.poll_closes_in, relative)
    }
    return if (results.myPicks.isEmpty()) context.getString(R.string.poll_tap_to_vote) else ""
}

@Composable
private fun PollBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun PollOptionRow(
    label: String,
    share: Float,
    percent: Int,
    count: Int,
    isPicked: Boolean,
    enabled: Boolean,
    multiple: Boolean,
    onTap: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    // __POLL_ROW_MUST_ANNOUNCE_ITSELF_2026_10_23__ One node per answer, read the
    // way iOS reads it (`OSHI/PollViews.swift` PollOptionRow): the answer as the
    // name, "42 percent, 3 votes" as the value, and the role carrying whether
    // this is one choice among many or a box that toggles. Without `mergeDescendants`
    // TalkBack walks the label and the percentage as two unrelated nodes and never
    // says the count at all.
    val spoken = stringResource(R.string.poll_accessibility_option, percent, count)
    // The bar is the only thing that moves when a vote lands; iOS eases it over
    // 0.25 s and so do we, rather than having it jump.
    val animatedShare by animateFloatAsState(
        targetValue = share.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 250),
        label = "pollShare",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bubbleClickable(enabled = enabled, onClick = onTap)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = spoken
                role = if (multiple) Role.Checkbox else Role.RadioButton
                selected = isPicked
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when {
                    multiple && isPicked -> Icons.Default.CheckBox
                    multiple -> Icons.Default.CheckBoxOutlineBlank
                    isPicked -> Icons.Default.RadioButtonChecked
                    else -> Icons.Default.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = if (isPicked) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(8.dp))
            // weight on the LABEL, not on the number: the number must keep its
            // width or it is what gets squeezed to one digit per line.
            Text(
                text = label,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = "$percent%",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(5.dp))
        // Two Boxes in a Row, so the fill grows from the leading edge in both
        // directions of text.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(track, RoundedCornerShape(3.dp))
        ) {
            val filled = animatedShare
            if (filled > 0f) {
                Box(
                    modifier = Modifier
                        .weight(filled)
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(
                            if (isPicked) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            RoundedCornerShape(3.dp),
                        )
                )
            }
            if (filled < 1f) Spacer(Modifier.weight(1f - filled))
        }
    }
}

// ── Composing ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PollComposerSheet(
    creatorPublicKey: String,
    onDismiss: () -> Unit,
    onSend: (PollMessage) -> Unit,
) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var multiple by remember { mutableStateOf(false) }
    var anonymous by remember { mutableStateOf(false) }
    var durationIndex by remember { mutableStateOf(0) }
    var durationExpanded by remember { mutableStateOf(false) }

    // Fixed labels rather than "%d hours": the app has no plurals.xml, and
    // "1 hours" in 34 languages is a worse outcome than six more keys.
    val durationLabels = listOf(
        R.string.poll_duration_never to null,
        R.string.poll_duration_1h to TimeUnit.HOURS.toSeconds(1),
        R.string.poll_duration_6h to TimeUnit.HOURS.toSeconds(6),
        R.string.poll_duration_24h to TimeUnit.HOURS.toSeconds(24),
        R.string.poll_duration_3d to TimeUnit.DAYS.toSeconds(3),
        R.string.poll_duration_7d to TimeUnit.DAYS.toSeconds(7),
    )

    val clean = PollMessage.sanitize(question, options.toList())

    // No scroll modifier here on purpose: the sheet body that hosts this
    // composable carries it (`ChatScreen` / `GroupChatScreen`), which is where
    // `GroupChatSheetReachabilityTest` requires it to be. Two nested vertical
    // scrolls in the same direction is not a second safety net, it is a bug.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
    ) {
        Text(
            text = stringResource(R.string.poll_create),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = question,
            onValueChange = { if (it.length <= PollMessage.MAX_QUESTION_LENGTH) question = it },
            label = { Text(stringResource(R.string.poll_question)) },
            placeholder = { Text(stringResource(R.string.poll_question_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4,
        )
        // Appears only near the limit, like iOS: a counter on an empty field is
        // noise, and silently refusing the 301st character is worse.
        if (question.length > PollMessage.MAX_QUESTION_LENGTH - 40) {
            Text(
                text = "${question.length}/${PollMessage.MAX_QUESTION_LENGTH}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.poll_options),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        options.forEachIndexed { i, value ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        if (it.length <= PollMessage.MAX_OPTION_LENGTH) options[i] = it
                    },
                    placeholder = { Text(stringResource(R.string.poll_option_placeholder, i + 1)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (options.size > PollMessage.MIN_OPTIONS) {
                    IconButton(onClick = { options.removeAt(i) }) {
                        Icon(
                            imageVector = Icons.Default.RemoveCircle,
                            contentDescription = stringResource(R.string.poll_remove_option),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        if (options.size < PollMessage.MAX_OPTIONS) {
            TextButton(onClick = { options.add("") }) {
                Icon(Icons.Default.AddCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.poll_add_option))
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.poll_multiple_answers),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Switch(checked = multiple, onCheckedChange = { multiple = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.VisibilityOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.poll_anonymous),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Switch(checked = anonymous, onCheckedChange = { anonymous = it })
        }

        // Says what anonymous DOES mean and what it cannot mean. A ballot is a
        // message; it travels from its sender like every other message.
        // Promising more than that on a peer-to-peer mesh would be a lie in 34
        // languages.
        Text(
            text = if (anonymous) stringResource(R.string.poll_anonymous_hint)
                   else stringResource(R.string.poll_multiple_answers_hint),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        ExposedDropdownMenuBox(
            expanded = durationExpanded,
            onExpandedChange = { durationExpanded = it },
        ) {
            OutlinedTextField(
                value = stringResource(durationLabels[durationIndex].first),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.poll_duration)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = durationExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = durationExpanded,
                onDismissRequest = { durationExpanded = false },
            ) {
                durationLabels.forEachIndexed { i, (res, _) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(res)) },
                        onClick = { durationIndex = i; durationExpanded = false },
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            Spacer(Modifier.size(8.dp))
            Button(
                enabled = clean != null,
                onClick = {
                    val (q, opts) = clean ?: return@Button
                    val seconds = durationLabels[durationIndex].second
                    onSend(
                        PollMessage(
                            question = q,
                            options = opts,
                            allowsMultipleAnswers = multiple,
                            isAnonymous = anonymous,
                            closesAtUnix = seconds?.let { System.currentTimeMillis() / 1000.0 + it },
                            creatorPublicKey = creatorPublicKey,
                        )
                    )
                    onDismiss()
                },
            ) { Text(stringResource(R.string.poll_send)) }
        }
        Spacer(Modifier.height(12.dp))
    }
}
