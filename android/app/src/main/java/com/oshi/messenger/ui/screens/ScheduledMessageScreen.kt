package com.oshi.messenger.ui.screens

import android.app.DatePickerDialog
import com.oshi.messenger.ui.theme.OshiFilterChip
import com.oshi.messenger.ui.theme.OshiCard
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.oshi.messenger.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.service.MessageTone
import com.oshi.messenger.service.ScheduledMessage
import com.oshi.messenger.service.ScheduledMessageManager
import com.oshi.messenger.service.ScheduledMessageStatus
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.theme.OSHIOrange
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.OSHIRed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

// =============================================================================
// ViewModel
// =============================================================================

@HiltViewModel
class ScheduledMessageViewModel @Inject constructor(
    private val manager: ScheduledMessageManager,
    private val nlpManager: com.oshi.messenger.service.NLPManager,
    messageRepository: com.oshi.messenger.data.repository.MessageRepository
) : ViewModel() {

    val messages: StateFlow<List<ScheduledMessage>> = manager.getScheduledMessages()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The contacts the create sheet can address.
     *
     * This screen used to demand a **pasted raw public key** (a 20+ character
     * base64 blob) where iOS passes the participant automatically because its
     * sheet is opened from inside a chat (`OSHI/ChatView.swift:762-768`). A
     * standalone list has no participant, so it gets a picker instead.
     */
    val contacts: StateFlow<List<com.oshi.messenger.data.model.Contact>> =
        messageRepository.getAllContacts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun schedule(
        recipientKey: String,
        content: String,
        scheduledTimeMs: Long,
        tone: MessageTone,
        isAiGenerated: Boolean = false
    ) {
        viewModelScope.launch {
            manager.scheduleMessage(recipientKey, content, scheduledTimeMs, tone, isAiGenerated)
        }
    }

    fun cancel(messageId: String) {
        viewModelScope.launch {
            manager.cancelMessage(messageId)
        }
    }

    /**
     * 🔧 FIX (2026-08-24 three-gaps): was `manager.generateAiSuggestion(tone)` — **25
     * hardcoded English strings picked with `.random()`**, shown to users in all 34
     * locales, while the real multilingual generator sat unused two files away
     * (`NLPManager.generateThinkingOfYou`: Apple-Intelligence-equivalent → local
     * LLM → a template keyed by language). iOS routes this same button through
     * `NLPManager.processGenerateThinkingOfYou` (`OSHI/ScheduledMessageManager.swift:300-310`).
     *
     * No conversation context is available on a standalone settings screen, so the
     * generator falls back to the device language — which is the correct answer
     * here, and the reason it takes the context as a parameter rather than reading
     * a global.
     */
    suspend fun generateThinkingOfYou(tone: MessageTone, recipientName: String): String =
        nlpManager.generateThinkingOfYou(
            recipientName = recipientName,
            tone = tone.writingStyle()
        )
}

// =============================================================================
// Screen
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledMessageScreen(
    onBack: () -> Unit,
    viewModel: ScheduledMessageViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    var showCreateSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scheduled_messages_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.key_backup_hide))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateSheet = true },
                containerColor = OSHIPurple,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.scheduled_messages_new), tint = Color.White)
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (messages.isEmpty()) {
                EmptyScheduledMessages(onNewClick = { showCreateSheet = true })
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = messages.sortedWith(
                            compareBy({ it.status != ScheduledMessageStatus.PENDING }, { it.scheduledTime })
                        ),
                        key = { it.id }
                    ) { message ->
                        ScheduledMessageCard(
                            message = message,
                            onCancel = { viewModel.cancel(message.id) }
                        )
                    }
                }
            }
        }
    }

    if (showCreateSheet) {
        val contacts by viewModel.contacts.collectAsState()
        CreateScheduledMessageSheet(
            contacts = contacts,
            onDismiss = { showCreateSheet = false },
            onConfirm = { recipientKey, content, timeMs, tone, isAi ->
                viewModel.schedule(recipientKey, content, timeMs, tone, isAi)
                showCreateSheet = false
            },
            generateSuggestion = { tone, name -> viewModel.generateThinkingOfYou(tone, name) }
        )
    }
}

// =============================================================================
// Empty state
// =============================================================================

@Composable
private fun EmptyScheduledMessages(onNewClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Schedule,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.scheduled_messages_empty),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.scheduled_messages_empty_desc),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onNewClick,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = OSHIPurple)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.scheduled_messages_new))
        }
    }
}

// =============================================================================
// Message card
// =============================================================================

private val dateFormatter = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())

@Composable
private fun ScheduledMessageCard(
    message: ScheduledMessage,
    onCancel: () -> Unit
) {
    val statusColor = when (message.status) {
        ScheduledMessageStatus.PENDING -> OSHIPurple
        ScheduledMessageStatus.SENT -> OSHIGreen
        ScheduledMessageStatus.CANCELLED -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        ScheduledMessageStatus.FAILED -> OSHIRed
    }

    val statusLabel = when (message.status) {
        ScheduledMessageStatus.PENDING -> stringResource(R.string.scheduled_messages_status_pending)
        ScheduledMessageStatus.SENT -> stringResource(R.string.scheduled_messages_status_sent)
        ScheduledMessageStatus.CANCELLED -> stringResource(R.string.scheduled_messages_status_cancelled)
        ScheduledMessageStatus.FAILED -> stringResource(R.string.scheduled_messages_status_failed)
    }

    OshiCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row: tone badge + status chip + cancel
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tone badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = OSHIPurple.copy(alpha = 0.12f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(message.tone.emoji, fontSize = 13.sp)
                        Text(
                            text = stringResource(com.oshi.messenger.ui.components.messageToneLabel(message.tone)),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = OSHIPurple
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Status chip
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = statusColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = statusLabel,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = statusColor
                        )
                    }

                    // Cancel button (only for PENDING)
                    if (message.status == ScheduledMessageStatus.PENDING) {
                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Cancel,
                                contentDescription = stringResource(R.string.cancel),
                                tint = OSHIRed,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Recipient
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (message.recipientPublicKey.length > 16) {
                        "${message.recipientPublicKey.take(8)}...${message.recipientPublicKey.takeLast(8)}"
                    } else message.recipientPublicKey,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (message.isAiGenerated) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = OSHIOrange.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = "AI",
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = OSHIOrange
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Content preview
            Text(
                text = message.content,
                fontSize = 14.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            Spacer(modifier = Modifier.height(8.dp))

            // Scheduled time
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = dateFormatter.format(Date(message.scheduledTime)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
    }
}

// =============================================================================
// Create sheet / dialog
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateScheduledMessageSheet(
    contacts: List<com.oshi.messenger.data.model.Contact>,
    onDismiss: () -> Unit,
    onConfirm: (recipientKey: String, content: String, timeMs: Long, tone: MessageTone, isAi: Boolean) -> Unit,
    generateSuggestion: suspend (MessageTone, recipientName: String) -> String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var recipientKey by remember { mutableStateOf("") }
    var recipientAlias by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }

    if (showPicker) {
        com.oshi.messenger.ui.components.OshiContactPickerSheet(
            contacts = contacts,
            onContactSelected = { pk, alias ->
                recipientKey = pk
                recipientAlias = alias.orEmpty()
            },
            onDismiss = { showPicker = false }
        )
    }
    var content by remember { mutableStateOf("") }
    var selectedTone by remember { mutableStateOf(MessageTone.CASUAL) }
    var scheduledTimeMs by remember { mutableStateOf(System.currentTimeMillis() + 60 * 60 * 1000L) }
    var isAiGenerated by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val calendar = remember { Calendar.getInstance() }

    val canConfirm = recipientKey.length >= 20 && content.isNotBlank() && scheduledTimeMs > System.currentTimeMillis()

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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Title
            Text(
                text = stringResource(R.string.scheduled_messages_new),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )

            // Pick an OSHI contact — the raw-key field below stays as the manual
            // escape hatch for a key someone was sent out of band.
            OutlinedButton(
                onClick = { showPicker = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = recipientAlias.ifBlank { stringResource(R.string.share_contact) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Recipient public key input
            // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
            // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
            com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                OutlinedTextField(
                    value = recipientKey,
                    onValueChange = { recipientKey = it; errorMessage = null },
                    label = { Text(stringResource(R.string.scheduled_messages_recipient_key)) },
                    placeholder = { Text(stringResource(R.string.scheduled_messages_paste_key)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(20.dp))
                    },
                    isError = recipientKey.isNotEmpty() && recipientKey.length < 20,
                    supportingText = {
                        if (recipientKey.isNotEmpty() && recipientKey.length < 20) {
                            Text(stringResource(R.string.scheduled_messages_key_short))
                        }
                    }
                )
            }

            // Tone selector
            Text(
                text = stringResource(R.string.scheduled_messages_tone),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            // 🔧 P2 FIX (device walkthrough #2, 2026-08-13): the tone chip row was
            // clipped at the right screen edge — "👋 Casual" rendered as "Ca" with
            // no end padding and no edge fade. The row already scrolls; it just had
            // zero content padding, so the last chip sat flush against the bezel
            // and read as truncated.
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 16.dp)
            ) {
                items(MessageTone.entries.toList()) { tone ->
                    val selected = tone == selectedTone
                    OshiFilterChip(
                        selected = selected,
                        onClick = {
                            selectedTone = tone
                            // Regenerate for the new tone only if the text on screen came
                            // from the generator — never overwrite something the user typed.
                            // Suspending now (the real generator runs a model), so it goes
                            // through the same scope and spinner as the button below.
                            if (isAiGenerated && !isGenerating) {
                                isGenerating = true
                                scope.launch {
                                    content = generateSuggestion(tone, recipientAlias)
                                    isGenerating = false
                                }
                            }
                        },
                        label = {
                            Text(
                                text = "${tone.emoji} ${stringResource(com.oshi.messenger.ui.components.messageToneLabel(tone))}",
                                fontSize = 13.sp
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = OSHIPurple.copy(alpha = 0.15f),
                            selectedLabelColor = OSHIPurple
                        )
                    )
                }
            }

            // Content input + AI generate button
            // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
            // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
            com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it; isAiGenerated = false; errorMessage = null },
                    label = { Text(stringResource(R.string.scheduled_messages_message_label)) },
                    placeholder = { Text(stringResource(R.string.scheduled_messages_message_placeholder)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 6
                )
            }

            // AI suggest button — the REAL multilingual generator, not the 25
            // English literals this used to paste in.
            val toneLabel = stringResource(com.oshi.messenger.ui.components.messageToneLabel(selectedTone))
            OutlinedButton(
                onClick = {
                    if (isGenerating) return@OutlinedButton
                    isGenerating = true
                    scope.launch {
                        content = generateSuggestion(selectedTone, recipientAlias)
                        isAiGenerated = true
                        isGenerating = false
                    }
                },
                enabled = !isGenerating,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = OSHIOrange)
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp), tint = OSHIOrange)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.scheduled_messages_ai_suggestion, toneLabel), color = OSHIOrange)
            }

            // Date & time picker
            val displayDate = remember(scheduledTimeMs) {
                dateFormatter.format(Date(scheduledTimeMs))
            }

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
                                    errorMessage = null
                                },
                                calendar.get(Calendar.HOUR_OF_DAY),
                                calendar.get(Calendar.MINUTE),
                                true
                            ).show()
                        },
                        calendar.get(Calendar.YEAR),
                        calendar.get(Calendar.MONTH),
                        calendar.get(Calendar.DAY_OF_MONTH)
                    ).apply {
                        datePicker.minDate = System.currentTimeMillis()
                    }.show()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(displayDate)
            }

            if (scheduledTimeMs <= System.currentTimeMillis()) {
                Text(
                    text = stringResource(R.string.scheduled_messages_time_future),
                    color = OSHIRed,
                    fontSize = 12.sp
                )
            }

            errorMessage?.let {
                Text(text = it, color = OSHIRed, fontSize = 13.sp)
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        when {
                            recipientKey.length < 20 -> errorMessage = context.getString(R.string.scheduled_messages_key_error)
                            content.isBlank() -> errorMessage = context.getString(R.string.scheduled_messages_content_error)
                            scheduledTimeMs <= System.currentTimeMillis() -> errorMessage = context.getString(R.string.scheduled_messages_time_error)
                            else -> {
                                onConfirm(recipientKey.trim(), content.trim(), scheduledTimeMs, selectedTone, isAiGenerated)
                                Toast.makeText(context, context.getString(R.string.scheduled_messages_scheduled_toast), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = canConfirm,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OSHIPurple)
                ) {
                    Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.scheduled_messages_schedule))
                }
            }
        }
    }
}
