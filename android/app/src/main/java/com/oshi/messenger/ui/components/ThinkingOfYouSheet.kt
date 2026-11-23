package com.oshi.messenger.ui.components

import android.app.DatePickerDialog
import com.oshi.messenger.ui.theme.OshiFilterChip
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.service.MessageTone
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * "Je pense à toi" / "Thinking of You" — the AI-crafted scheduled message.
 *
 * ## What this replaced, and why
 *
 * Android had all three pieces of this feature and **none of them were connected**:
 *
 * 1. `service/ScheduledMessageManager.kt` — a real WorkManager-backed delivery
 *    engine with iOS's five `MessageTone`s. Reachable, but only ever asked to
 *    send text the user had already typed.
 * 2. `ScheduleMessageSheet` (formerly in `ChatScreen.kt`) — a date picker over
 *    the composer's current contents. No tone, no AI, no preview: "send this
 *    later", not "the app writes you a heartfelt message".
 * 3. `NLPManager.generateThinkingOfYou` — the genuine multilingual generator
 *    (Gemini Nano → local LLM → a template keyed by the language detected from
 *    the CONVERSATION, `service/NLPManager.kt:1420-1455`). Its only consumer was
 *    the writing assistant's third tab, which pastes text into the composer.
 *    **Zero connection to the scheduler.**
 *
 * Meanwhile the Settings → Scheduled Messages screen's "AI" button called
 * `ScheduledMessageManager.generateAiSuggestion` — 25 hardcoded English strings
 * picked with `.random()`, never localized — while the good generator sat unused
 * two files away.
 *
 * This sheet is the join. It is iOS's `CreateScheduledMessageView`
 * (`OSHI/ScheduledMessageManager.swift:319-470`) section for section: header,
 * tone picker, date picker, generate button, editable preview.
 *
 * ## Wire
 *
 * There is none to design. A scheduled message is delivered as an **ordinary text
 * message** on both platforms — no new type, no new field, no new enum case — so
 * there is no strict-decoder hazard in either direction, and the on-disk schema
 * divergence between `scheduled_messages.json` on the two platforms never crosses
 * a device.
 *
 * ## Strings
 *
 * Every label reuses a `scheduled_msg_*` key that already exists in all 34
 * locales and had **no Kotlin consumer** — they were minted for this screen and
 * then stranded. No new keys.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThinkingOfYouSheet(
    /** Prefills the editable preview — whatever the user had already typed, if anything. */
    initialText: String,
    /** The generator's current output, `""` until the user asks for one. */
    generatedText: String,
    isGenerating: Boolean,
    selectedTone: MessageTone,
    onSelectTone: (MessageTone) -> Unit,
    onGenerate: () -> Unit,
    /** `wasAiGenerated` is TRUE only when the text on screen came from the generator. */
    onSchedule: (text: String, whenMs: Long, wasAiGenerated: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val calendar = remember { Calendar.getInstance() }

    var editedText by remember { mutableStateOf(initialText) }
    var textIsFromGenerator by remember { mutableStateOf(false) }
    var scheduledTimeMs by remember {
        mutableStateOf(System.currentTimeMillis() + 60 * 60 * 1000L)
    }

    // A fresh generation replaces the preview. Keyed on the value, so an edit the
    // user makes afterwards survives recomposition — and a REgeneration, which
    // yields a different string, correctly overwrites it.
    LaunchedEffect(generatedText) {
        if (generatedText.isNotBlank()) {
            editedText = generatedText
            textIsFromGenerator = true
        }
    }

    val displayDate = remember(scheduledTimeMs) {
        SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(scheduledTimeMs))
    }
    val tooSoon = scheduledTimeMs <= System.currentTimeMillis() + 60_000L
    val canSchedule = editedText.isNotBlank() && !tooSoon

    OshiModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.chat_schedule_message),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = stringResource(R.string.scheduled_msg_subtitle),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            // ── Tone (iOS `tonePickerSection`, ScheduledMessageManager.swift:409-437)
            Text(
                text = stringResource(R.string.scheduled_messages_tone),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 16.dp)
            ) {
                items(MessageTone.entries.toList()) { tone ->
                    OshiFilterChip(
                        selected = tone == selectedTone,
                        onClick = { onSelectTone(tone) },
                        label = { Text("${tone.emoji} ${stringResource(messageToneLabel(tone))}") }
                    )
                }
            }

            // ── Generate (iOS `generateButton`, :455-470)
            Button(
                onClick = onGenerate,
                enabled = !isGenerating,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (generatedText.isBlank()) R.string.scheduled_msg_generating
                        else R.string.scheduled_msg_regenerate
                    )
                )
            }

            // ── Editable preview (iOS `previewSection`)
            // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
            // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
            com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                OutlinedTextField(
                    value = editedText,
                    onValueChange = {
                        editedText = it
                        // An edit is still "AI generated" on iOS — `userEditedContent`
                        // overrides the text but `wasAIGenerated` stays true
                        // (ScheduledMessageManager.swift:19-21). Only text the user typed
                        // from nothing is not.
                    },
                    label = { Text(stringResource(R.string.scheduled_msg_preview)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    minLines = 3,
                    maxLines = 6
                )
            }

            // ── When (iOS `datePickerSection`, :439-453)
            Text(
                text = stringResource(R.string.scheduled_msg_when),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            OutlinedButton(
                onClick = {
                    calendar.timeInMillis = scheduledTimeMs
                    DatePickerDialog(
                        context,
                        { _, year, month, day ->
                            calendar.set(year, month, day)
                            TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    calendar.set(Calendar.HOUR_OF_DAY, hour)
                                    calendar.set(Calendar.MINUTE, minute)
                                    calendar.set(Calendar.SECOND, 0)
                                    scheduledTimeMs = calendar.timeInMillis
                                },
                                calendar.get(Calendar.HOUR_OF_DAY),
                                calendar.get(Calendar.MINUTE),
                                true
                            ).show()
                        },
                        calendar.get(Calendar.YEAR),
                        calendar.get(Calendar.MONTH),
                        calendar.get(Calendar.DAY_OF_MONTH)
                    ).apply { datePicker.minDate = System.currentTimeMillis() }.show()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(displayDate)
            }

            if (tooSoon) {
                Text(
                    text = stringResource(R.string.chat_schedule_too_soon),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.chat_schedule_cancel)) }
                Button(
                    onClick = { onSchedule(editedText.trim(), scheduledTimeMs, textIsFromGenerator) },
                    enabled = canSchedule,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.scheduled_msg_schedule)) }
            }
        }
    }
}

/**
 * The localized label for a tone.
 *
 * `MessageTone.displayName` is a hardcoded English literal on the enum, so every
 * surface that printed it read "Romantic"/"Friendly"/… in all 34 locales. The
 * `scheduled_msg_tone_*` keys have existed in all 34 all along; nothing used them.
 * iOS resolves the same set through `RelationshipTone.localizedKey`
 * (`OSHI/ScheduledMessageManager.swift:44-46`).
 */
fun messageToneLabel(tone: MessageTone): Int = when (tone) {
    MessageTone.ROMANTIC -> R.string.scheduled_msg_tone_romantic
    MessageTone.FRIENDLY -> R.string.scheduled_msg_tone_friendly
    MessageTone.FAMILY -> R.string.scheduled_msg_tone_family
    MessageTone.CASUAL -> R.string.scheduled_msg_tone_casual
    MessageTone.FORMAL -> R.string.scheduled_msg_tone_formal
}
