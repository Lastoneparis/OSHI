package com.oshi.messenger.ui.components

import android.text.format.DateFormat
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import com.oshi.messenger.ui.theme.OshiAlertDialog
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import java.util.Calendar
import java.util.TimeZone

/**
 * __SCHEDULE_CALL_REDESIGN_2026_09_29__ "Schedule a call" from a 1:1 chat — the Android
 * twin of iOS `CreateScheduledCallView`: who, quick "when" chips (in 1 h, tonight,
 * tomorrow morning/evening, or a full calendar), a big readable date/time, duration
 * chips, an optional title and one button that says what will be sent.
 * Chip labels come from the platform's relative date formatting, so every language
 * is right without new strings.
 */
private val BrandGradient = Brush.linearGradient(listOf(Color(0xFF5616EC), Color(0xFF9933FF), Color(0xFF3B82F6)))
private val Brand = Color(0xFF5616EC)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleCallSheet(
    peerName: String,
    onDismiss: () -> Unit,
    // __AVATAR_COLOUR_LIST_EQUALS_CHAT_2026_11_09__ when known, the avatar is
    // the peer's own colour (the conversation list's), not the brand gradient.
    peerPublicKey: String? = null,
    onSchedule: (whenMs: Long, durationMin: Int, title: String?) -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val now = remember { System.currentTimeMillis() }
    val quick = remember(now) { quickOptions(context, now) }
    var whenMs by remember { mutableLongStateOf(quick.first().second) }
    var chosenQuick by remember { mutableStateOf<Int?>(0) }
    var duration by remember { mutableIntStateOf(30) }
    var title by remember { mutableStateOf("") }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    val durations = listOf(15, 30, 45, 60, 90, 120)

    val summary = "${relativeDayTime(context, whenMs)} · ${formatDuration(duration)}"

    OshiModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                stringResource(R.string.scheduled_calls_schedule_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            // Who
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (peerPublicKey != null) {
                    com.oshi.messenger.ui.theme.ContactAvatar(
                        publicKey = peerPublicKey,
                        name = peerName,
                        size = 54.dp,
                    )
                } else Box(
                    Modifier.size(54.dp).clip(CircleShape).background(BrandGradient),
                    contentAlignment = Alignment.Center
                ) {
                    Text(initials(peerName), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text(
                        stringResource(R.string.schedule_call_with, peerName),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // When
            Section(stringResource(R.string.schedule_call_when), Icons.Default.AccessTime) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    quick.forEachIndexed { i, (label, ms) ->
                        Chip(label, selected = chosenQuick == i) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            chosenQuick = i; whenMs = ms
                        }
                    }
                    Chip(stringResource(R.string.schedule_call_other_date), icon = Icons.Default.CalendarMonth,
                        selected = chosenQuick == null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showDate = true
                    }
                }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { showDate = true }
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                DateUtils.formatDateTime(context, whenMs,
                                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_YEAR),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                DateFormat.getTimeFormat(context).format(whenMs),
                                fontSize = 40.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable(role = Role.Button) { showTime = true }
                            )
                        }
                        Icon(Icons.Default.EditCalendar, contentDescription = null, tint = Brand, modifier = Modifier.size(28.dp))
                    }
                }
            }

            // Duration
            Section(stringResource(R.string.schedule_call_duration), Icons.Default.HourglassBottom) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    durations.forEach { m ->
                        Chip(formatDuration(m), selected = duration == m) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            duration = m
                        }
                    }
                }
            }

            // Title
            Section(stringResource(R.string.schedule_call_title_optional), Icons.Default.Title) {
                // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                    OutlinedTextField(
                        value = title, onValueChange = { title = it.take(80) },
                        placeholder = { Text(stringResource(R.string.schedule_call_title_placeholder)) },
                        singleLine = true, shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            val tz = TimeZone.getDefault()
            val off = tz.getOffset(System.currentTimeMillis()) / 3_600_000
            Text(
                stringResource(R.string.schedule_call_timezone,
                    tz.getDisplayName(tz.inDaylightTime(java.util.Date()), TimeZone.SHORT),
                    (if (off >= 0) "+" else "") + off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // One button, saying exactly what will be sent.
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(BrandGradient)
                    .clickable(role = Role.Button) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSchedule(whenMs, duration, title.ifBlank { null })
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.EventAvailable, contentDescription = null, tint = Color.White)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.schedule_call_schedule), color = Color.White,
                            fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Text(summary, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                    }
                    Icon(Icons.Default.Call, contentDescription = null, tint = Color.White)
                }
            }
        }
    }

    if (showDate) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = utcMidnightOf(whenMs))
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { utc ->
                        val picked = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
                        val c = Calendar.getInstance().apply {
                            timeInMillis = whenMs
                            set(picked.get(Calendar.YEAR), picked.get(Calendar.MONTH), picked.get(Calendar.DAY_OF_MONTH))
                        }
                        whenMs = c.timeInMillis; chosenQuick = null
                    }
                    showDate = false; showTime = true
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text(stringResource(R.string.cancel)) } }
        ) {
            DatePicker(
                state = dateState,
                dateValidator = { utc -> utc >= utcMidnightOf(System.currentTimeMillis()) }
            )
        }
    }

    if (showTime) {
        val c = Calendar.getInstance().apply { timeInMillis = whenMs }
        val timeState = rememberTimePickerState(
            initialHour = c.get(Calendar.HOUR_OF_DAY), initialMinute = c.get(Calendar.MINUTE),
            is24Hour = DateFormat.is24HourFormat(context)
        )
        OshiAlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    val t = Calendar.getInstance().apply {
                        timeInMillis = whenMs
                        set(Calendar.HOUR_OF_DAY, timeState.hour); set(Calendar.MINUTE, timeState.minute)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    // Never in the past: a time already gone today means tomorrow.
                    whenMs = if (t <= System.currentTimeMillis()) t + DateUtils.DAY_IN_MILLIS else t
                    chosenQuick = null; showTime = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text(stringResource(R.string.cancel)) } },
            text = { TimePicker(state = timeState) }
        )
    }
}

@Composable
private fun Section(label: String, icon: ImageVector, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .then(
                if (selected) Modifier.background(BrandGradient)
                else Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), shape)
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        val color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text, color = color, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** In 1 h (next quarter hour), tonight 20:00 if still ahead, tomorrow 09:00 and 18:00. */
private fun quickOptions(context: android.content.Context, now: Long): List<Pair<String, Long>> {
    val out = mutableListOf<Pair<String, Long>>()
    val inAnHour = roundUpQuarter(now + DateUtils.HOUR_IN_MILLIS)
    out += DateUtils.getRelativeTimeSpanString(now + DateUtils.HOUR_IN_MILLIS, now, DateUtils.MINUTE_IN_MILLIS).toString() to inAnHour
    fun at(dayOffset: Int, hour: Int) = Calendar.getInstance().apply {
        timeInMillis = now; add(Calendar.DAY_OF_YEAR, dayOffset)
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val tonight = at(0, 20)
    if (tonight > now + 30 * DateUtils.MINUTE_IN_MILLIS) out += relativeDayTime(context, tonight) to tonight
    at(1, 9).let { out += relativeDayTime(context, it) to it }
    at(1, 18).let { out += relativeDayTime(context, it) to it }
    return out
}

/** "Tomorrow, 09:00" / "Today, 20:00" — platform-localised. */
private fun relativeDayTime(context: android.content.Context, ms: Long): String =
    DateUtils.getRelativeDateTimeString(context, ms, DateUtils.DAY_IN_MILLIS, DateUtils.WEEK_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_ALL).toString()

private fun roundUpQuarter(ms: Long): Long {
    val q = 15 * DateUtils.MINUTE_IN_MILLIS
    val floorMin = ms - ms % DateUtils.MINUTE_IN_MILLIS
    return ((floorMin + q - 1) / q) * q
}

private fun utcMidnightOf(localMs: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = localMs }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

private fun formatDuration(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60}"
}

private fun initials(name: String): String =
    name.split(" ").filter { it.isNotBlank() }.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("").ifEmpty { "?" }
