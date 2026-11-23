package com.oshi.messenger.ui.screens

import androidx.compose.animation.*
import com.oshi.messenger.ui.theme.OshiCard
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.oshi.messenger.R
import com.oshi.messenger.service.LocalAIOffer
import com.oshi.messenger.service.LocalLLMManager
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.viewmodel.AIChatViewModel
import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.oshi.messenger.service.AIDocumentProcessor
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * AIChatScreen — Full conversational AI assistant with chat history sidebar.
 *
 * Matches iOS OfflineAITabView + AIChatSidebarView feature-for-feature:
 * - Multi-chat history with sidebar drawer
 * - Create new chat, select previous chats, swipe-to-delete
 * - Persistent chat history saved to SharedPreferences
 * - Auto-title from first user message
 * - Multi-turn conversations with context
 * - Suggestion chips for first-time users
 * - Typing indicator while generating
 * - 100% offline, zero data sent anywhere
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIChatScreen(
    onBack: () -> Unit,
    viewModel: AIChatViewModel = hiltViewModel()
) {
    val chats by viewModel.chats.collectAsState()
    val activeChatId by viewModel.activeChatId.collectAsState()
    val activeChat by viewModel.activeChat.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val isAvailable by viewModel.isAvailable.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()

    var showSidebar by remember { mutableStateOf(false) }

    val isGeminiAvailable by viewModel.isGeminiAvailable.collectAsState()
    val geminiNanoState by viewModel.geminiNanoState.collectAsState()
    val localLLM = viewModel.localLLMManager
    val isLocalModelDownloaded by localLLM.isModelDownloaded.collectAsState()
    val isLocalDownloading by localLLM.isDownloading.collectAsState()
    val localDownloadProgress by localLLM.downloadProgress.collectAsState()
    var forceShowOSHIDownload by remember { mutableStateOf(false) }
    // Single source of truth for which of the three states to show — the screen is never
    // left without either a chat, a Gemini "preparing" view with an exit, or a download offer.
    val mode = LocalAIOffer.resolveMode(
        promptModelAvailable = isGeminiAvailable,
        localModelDownloaded = isLocalModelDownloaded,
        geminiState = geminiNanoState,
        userChoseOfflineModel = forceShowOSHIDownload
    )

    // Ensure a chat exists — runs once
    LaunchedEffect(Unit) {
        if (viewModel.activeChatId.value == null) {
            if (viewModel.chats.value.isEmpty()) {
                viewModel.createNewChat()
            } else {
                viewModel.selectChat(viewModel.chats.value.first().id)
            }
        }
    }

    // 🆕 Device supports Gemini Nano (Pixel 8+ / S24+) but it's currently downloading or
    // not yet AVAILABLE. Don't suggest a 2.4 GB OSHI offline-model download — show the
    // "Gemini Nano preparing…" state instead. User can still escape via the small button.
    if (mode == LocalAIOffer.Mode.GEMINI_PREPARING) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.ai_assistant_title), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.accessibility_back))
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = OSHIPurple,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.ai_gemini_nano_supported_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when (geminiNanoState) {
                        com.oshi.messenger.service.NLPManager.GeminiNanoState.DOWNLOADING ->
                            stringResource(R.string.ai_gemini_nano_downloading)
                        com.oshi.messenger.service.NLPManager.GeminiNanoState.DOWNLOADABLE ->
                            stringResource(R.string.ai_gemini_nano_downloadable)
                        else -> stringResource(R.string.ai_gemini_nano_preparing)
                    },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (geminiNanoState == com.oshi.messenger.service.NLPManager.GeminiNanoState.DOWNLOADING) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(20.dp))
                OutlinedButton(
                    onClick = { forceShowOSHIDownload = true },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ai_use_oshi_offline_instead), fontSize = 13.sp)
                }
            }
        }
        return
    }

    if (mode == LocalAIOffer.Mode.OFFER_DOWNLOAD) {
        // Neither Gemini Nano (Prompt API) nor the local LLM is available — offer the download
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.ai_assistant_title), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.accessibility_back))
                        }
                    }
                )
            }
        ) { padding ->
            UnavailableView(
                modifier = Modifier.padding(padding),
                localLLMManager = localLLM,
                isDownloading = isLocalDownloading,
                downloadProgress = localDownloadProgress
            )
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            ActiveConversationView(
                viewModel = viewModel,
                messages = messages,
                inputText = inputText,
                isGenerating = isGenerating,
                streamingText = streamingText,
                chatTitle = activeChat?.title ?: stringResource(R.string.ai_assistant_title),
                onBack = onBack,
                onToggleSidebar = { showSidebar = !showSidebar },
                onNewChat = { viewModel.createNewChat() }
            )

            // Sidebar overlay
            AnimatedVisibility(
                visible = showSidebar,
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(200))
            ) {
                ChatSidebar(
                    chats = chats,
                    activeChatId = activeChatId,
                    onSelectChat = { id ->
                        viewModel.selectChat(id)
                        showSidebar = false
                    },
                    onNewChat = {
                        viewModel.createNewChat()
                        showSidebar = false
                    },
                    onDeleteChat = { viewModel.deleteChat(it) },
                    onDismiss = { showSidebar = false }
                )
            }
        }
    }
}

// ── Chat List Welcome View (no active chat) ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatListWelcomeView(
    chats: List<AIChatViewModel.AIChat>,
    onBack: () -> Unit,
    onNewChat: () -> Unit,
    onSelectChat: (String) -> Unit,
    onToggleSidebar: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.ai_assistant_title), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                },
                navigationIcon = {
                    Row {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.accessibility_back))
                        }
                        if (chats.isNotEmpty()) {
                            IconButton(onClick = onToggleSidebar) {
                                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.accessibility_chat_history))
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onNewChat) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.accessibility_new_chat),
                            tint = OSHIPurple
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(60.dp))

            Surface(
                shape = CircleShape,
                color = OSHIPurple.copy(alpha = 0.1f),
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Psychology,
                        contentDescription = null,
                        tint = OSHIPurple,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                stringResource(R.string.ai_offline_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                stringResource(R.string.ai_offline_desc),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // New Chat button
            Button(
                onClick = onNewChat,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OSHIPurple),
                modifier = Modifier.padding(horizontal = 40.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.ai_new_chat), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }

            // Recent chats
            if (chats.isNotEmpty()) {
                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    stringResource(R.string.ai_recent_chats),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                chats.take(5).forEach { chat ->
                    Surface(
                        onClick = { onSelectChat(chat.id) },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.ChatBubble,
                                contentDescription = null,
                                tint = OSHIPurple,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                chat.title,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                chat.lastActivityFormatted,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

// ── Active Conversation View ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveConversationView(
    viewModel: AIChatViewModel,
    messages: List<AIChatViewModel.ChatMessage>,
    inputText: String,
    isGenerating: Boolean,
    streamingText: String,
    chatTitle: String,
    onBack: () -> Unit,
    onToggleSidebar: () -> Unit,
    onNewChat: () -> Unit
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Auto-scroll to bottom when new messages arrive or streaming
    LaunchedEffect(messages.size, isGenerating, streamingText) {
        kotlinx.coroutines.delay(100) // Small delay for layout to settle
        val targetIndex = messages.size - 1 + if (isGenerating || streamingText.isNotEmpty()) 1 else 0
        if (targetIndex >= 0) {
            listState.animateScrollToItem(targetIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = OSHIPurple,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Psychology,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                chatTitle,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                stringResource(R.string.ai_badge_on_device),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    Row {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.accessibility_back_to_chats))
                        }
                        IconButton(onClick = onToggleSidebar) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.accessibility_chat_history))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onNewChat) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.accessibility_new_chat),
                            tint = OSHIPurple
                        )
                    }
                    if (messages.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearChat() }) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = stringResource(R.string.accessibility_clear_chat),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            // Track if user has ever sent a message — once true, never show welcome again
            val hasInteracted = remember { mutableStateOf(messages.isNotEmpty() || isGenerating) }
            if (messages.isNotEmpty() || isGenerating) {
                hasInteracted.value = true
            }

            // Messages list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Welcome view — only shown if user hasn't interacted yet
                if (!hasInteracted.value) {
                    item {
                        WelcomeView(onSuggestionTap = { suggestion ->
                            hasInteracted.value = true
                            viewModel.updateInput(suggestion)
                            viewModel.sendMessage()
                        })
                    }
                }

                items(messages) { message ->
                    ChatBubble(message = message)
                }

                // Streaming text or typing indicator
                if (isGenerating) {
                    item {
                        if (streamingText.isNotEmpty()) {
                            // Show streaming text appearing word-by-word
                            StreamingTextBubble(text = streamingText)
                        } else {
                            // Show typing dots while model is loading/thinking
                            TypingIndicator()
                        }
                    }
                }
            }

            Divider()

            // Input bar with attachment support (documents only — photos removed)
            val context = LocalContext.current
            var attachmentLabel by remember { mutableStateOf<String?>(null) }
            var isProcessingAttachment by remember { mutableStateOf(false) }

            val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    isProcessingAttachment = true
                    attachmentLabel = "Document"
                    coroutineScope.launch {
                        val mimeType = context.contentResolver.getType(uri)
                        val (text, label) = AIDocumentProcessor.extractText(context, uri, mimeType)
                        if (text != null) {
                            val formatted = AIDocumentProcessor.formatAsContext(text, label)
                            // No thumbnail for documents, just label
                            viewModel.setAttachmentContext(formatted, label = label, thumbnailUri = null)
                            attachmentLabel = label
                        } else {
                            attachmentLabel = null
                            Toast.makeText(context, context.getString(R.string.ai_no_text_found), Toast.LENGTH_SHORT).show()
                        }
                        isProcessingAttachment = false
                    }
                }
            }

            // Attachment preview
            if (attachmentLabel != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isProcessingAttachment) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.ai_processing), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp), tint = OSHIPurple)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(attachmentLabel ?: "", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { attachmentLabel = null; viewModel.setAttachmentContext(null, null, null) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.accessibility_remove), modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            InputBar(
                text = inputText,
                onTextChange = { viewModel.updateInput(it) },
                onSend = {
                    viewModel.sendMessage()
                    attachmentLabel = null
                    coroutineScope.launch {
                        if (messages.isNotEmpty()) {
                            listState.animateScrollToItem(messages.size)
                        }
                    }
                },
                isGenerating = isGenerating,
                // 🔧 Attachments fully removed — unreliable with the OSHI offline model
                // (context overflow on docs >1500 chars) and Gemini Nano's small
                // context window. `onAttach = null` hides the attach button.
                onAttach = null
            )
        }
    }
}

// ── Chat Sidebar (overlay drawer, matching iOS AIChatSidebarView) ──

@Composable
private fun ChatSidebar(
    chats: List<AIChatViewModel.AIChat>,
    activeChatId: String?,
    onSelectChat: (String) -> Unit,
    onNewChat: () -> Unit,
    onDeleteChat: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // Sidebar panel
        Surface(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .shadow(10.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.ai_chats_title),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onNewChat) {
                        Icon(
                            Icons.Default.AddCircle,
                            contentDescription = stringResource(R.string.accessibility_new_chat),
                            tint = OSHIPurple,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Divider()

                if (chats.isEmpty()) {
                    // Empty state
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.ChatBubbleOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.ai_no_conversations),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // Chat list with swipe-to-delete
                    LazyColumn(
                        modifier = Modifier.weight(1f)
                    ) {
                        items(
                            items = chats,
                            key = { it.id }
                        ) { chat ->
                            SwipeToDeleteChatItem(
                                chat = chat,
                                isActive = chat.id == activeChatId,
                                onClick = { onSelectChat(chat.id) },
                                onDelete = { onDeleteChat(chat.id) }
                            )
                        }
                    }
                }

                // Footer
                Divider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.ai_badge_on_device_caps),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Tap-to-dismiss scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
                .clickable(onClick = onDismiss)
        )
    }
}

// ── Swipe-to-Delete Chat Item ──

@Composable
private fun SwipeToDeleteChatItem(
    chat: AIChatViewModel.AIChat,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    val deleteThreshold = -200f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (offsetX < deleteThreshold / 2) MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                else Color.Transparent
            )
    ) {
        // Delete background
        if (offsetX < -20f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.accessibility_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Chat item
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX < deleteThreshold) {
                                onDelete()
                            }
                            offsetX = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            offsetX = (offsetX + dragAmount).coerceIn(-300f, 0f)
                        }
                    )
                },
            onClick = onClick,
            color = if (isActive) OSHIPurple else Color.Transparent
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.ChatBubble,
                    contentDescription = null,
                    tint = if (isActive) Color.White else OSHIPurple,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        chat.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isActive) Color.White else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        chat.lastActivityFormatted,
                        fontSize = 11.sp,
                        color = if (isActive) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ── Welcome View ──

@Composable
private fun WelcomeView(onSuggestionTap: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = OSHIPurple.copy(alpha = 0.1f),
            modifier = Modifier.size(80.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = null,
                    tint = OSHIPurple,
                    modifier = Modifier.size(44.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            stringResource(R.string.ai_offline_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            stringResource(R.string.ai_offline_desc),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        val suggestions = listOf(
            "Explain quantum computing simply",
            "Write a short poem about privacy",
            "What are the capitals of Europe?",
            "Help me write a professional email"
        )

        suggestions.forEach { suggestion ->
            SuggestionChip(
                text = suggestion,
                onClick = { onSuggestionTap(suggestion) }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = OSHIPurple,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            Icon(
                Icons.Default.ArrowUpward,
                contentDescription = null,
                tint = OSHIPurple.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ── Chat Bubble ──

@Composable
private fun ChatBubble(message: AIChatViewModel.ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!message.isUser) {
            Surface(
                shape = CircleShape,
                color = OSHIPurple,
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Psychology,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        if (message.isUser) {
            Spacer(modifier = Modifier.width(60.dp))
        }

        Column(
            modifier = Modifier.weight(1f, fill = false),
            horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (message.isUser) 18.dp else 4.dp,
                    bottomEnd = if (message.isUser) 4.dp else 18.dp
                ),
                color = if (message.isUser) OSHIPurple else MaterialTheme.colorScheme.surfaceVariant
            ) {
                // If this message has an attachment, render thumbnail/label above the text
                if (message.attachmentLabel != null) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        if (!message.attachmentThumbnailUri.isNullOrEmpty()) {
                            coil.compose.AsyncImage(
                                model = message.attachmentThumbnailUri,
                                contentDescription = message.attachmentLabel,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier
                                    .size(200.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .background(
                                        if (message.isUser) Color.White.copy(alpha = 0.18f)
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Description,
                                    contentDescription = null,
                                    tint = if (message.isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = message.attachmentLabel,
                                    color = if (message.isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (message.content.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = message.content,
                                color = if (message.isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp
                            )
                        }
                    }
                } else {
                    Text(
                        text = message.content,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        color = if (message.isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = message.timeFormatted,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!message.isUser) {
            Spacer(modifier = Modifier.width(60.dp))
        }

        if (message.isUser) {
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

// ── Typing Indicator ──

@Composable
private fun TypingIndicator() {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            shape = CircleShape,
            color = OSHIPurple,
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "typing")
                repeat(3) { i ->
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 500, delayMillis = i * 150),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dot_$i"
                    )
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.6f),
                        modifier = Modifier.size(7.dp)
                    ) {}
                }
            }
        }
    }
}

// ── Streaming Text Bubble (token-by-token display like ChatGPT) ──

@Composable
private fun StreamingTextBubble(text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            shape = CircleShape,
            color = OSHIPurple,
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // Blinking cursor
                val infiniteTransition = rememberInfiniteTransition(label = "cursor")
                val cursorAlpha by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 500),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "cursor_blink"
                )
                Text(
                    text = "▌",
                    color = OSHIPurple.copy(alpha = cursorAlpha),
                    fontSize = 15.sp
                )
            }
        }
        Spacer(modifier = Modifier.width(40.dp))
    }
}

// ── Input Bar ──

@Composable
private fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isGenerating: Boolean,
    onAttach: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .navigationBarsPadding(),
        verticalAlignment = Alignment.Bottom
    ) {
        // Attach button
        if (onAttach != null) {
            IconButton(
                onClick = onAttach,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = stringResource(R.string.ai_attach),
                    tint = OSHIPurple
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }

        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.ai_ask_anything), fontSize = 15.sp) },
            shape = RoundedCornerShape(20.dp),
            maxLines = 5,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = OSHIPurple,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        )

        Spacer(modifier = Modifier.width(10.dp))

        val canSend = text.isNotBlank() && !isGenerating
        IconButton(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (canSend) OSHIPurple else Color.Transparent)
        ) {
            Icon(
                Icons.Default.ArrowUpward,
                contentDescription = stringResource(R.string.accessibility_send),
                tint = if (canSend) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Unavailable View ──

@Composable
private fun UnavailableView(
    modifier: Modifier = Modifier,
    localLLMManager: LocalLLMManager,
    isDownloading: Boolean,
    downloadProgress: Float
) {
    val freeSpaceBytes = remember { localLLMManager.getFreeSpaceBytes() }
    val freeSpaceMB = freeSpaceBytes / (1024 * 1024)
    // Enough room for the model actually selected (was a flat 600 MB, which let a 2.5 GB
    // download start and then fail silently on a nearly-full phone).
    val hasRoom = LocalAIOffer.hasRoomFor(localLLMManager.selectedModel.sizeBytes, freeSpaceBytes)
    val downloadError by localLLMManager.downloadError.collectAsState()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Surface(
                shape = CircleShape,
                color = OSHIPurple.copy(alpha = 0.1f),
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Psychology,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = OSHIPurple
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                stringResource(R.string.ai_offline_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                stringResource(R.string.ai_offline_download_desc),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── Download Section (PRIMARY action) ──
            OshiCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = OSHIPurple.copy(alpha = 0.08f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                ) {
                    // Model name (centered)
                    Text(
                        localLLMManager.selectedModel.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Model info card (iOS style: icon + label rows)
                    val context = LocalContext.current
                    val totalRamGb = remember {
                        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                        val mi = android.app.ActivityManager.MemoryInfo()
                        am.getMemoryInfo(mi)
                        mi.totalMem / (1024L * 1024 * 1024)
                    }

                    // CPU / Model tier
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(16.dp), tint = OSHIPurple)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(localLLMManager.selectedModel.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }

                    // Download size
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("${String.format("%.1f", localLLMManager.selectedModel.sizeBytes / 1_000_000_000.0)} ${stringResource(R.string.ai_gb_download)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // RAM
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Icon(Icons.Default.DeveloperBoard, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.ai_selected_device) + " ($totalRamGb GB RAM)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // Free space
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (hasRoom) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("${freeSpaceMB / 1024} ${stringResource(R.string.ai_gb_free)}", fontSize = 13.sp, color = if (hasRoom) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isDownloading) {
                        LinearProgressIndicator(
                            progress = downloadProgress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = OSHIPurple,
                            trackColor = OSHIPurple.copy(alpha = 0.15f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.ai_downloading, (downloadProgress * 100).toInt()),
                            fontSize = 13.sp,
                            color = OSHIPurple,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { localLLMManager.cancelDownload() },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.cancel), fontSize = 13.sp)
                        }
                    } else {
                        Button(
                            onClick = { localLLMManager.downloadModel() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = OSHIPurple),
                            enabled = hasRoom
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (downloadError != null) stringResource(R.string.retry)
                                else stringResource(R.string.ai_download_model),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        downloadError?.let { err ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                stringResource(
                                    when (err) {
                                        LocalAIOffer.DownloadError.NETWORK -> R.string.ai_download_failed_network
                                        LocalAIOffer.DownloadError.STORAGE -> R.string.ai_not_enough_storage
                                        LocalAIOffer.DownloadError.SERVER -> R.string.ai_download_failed_server
                                    }
                                ),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (!hasRoom && downloadError != LocalAIOffer.DownloadError.STORAGE) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.ai_not_enough_storage),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Privacy badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = null,
                    tint = OSHIPurple,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    stringResource(R.string.ai_on_device_detail),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Gemini Nano section (secondary — collapsible)
            var showGeminiInfo by remember { mutableStateOf(false) }
            OshiCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showGeminiInfo = !showGeminiInfo },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(R.string.ai_alternative_gemini),
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            if (showGeminiInfo) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    if (showGeminiInfo) {
                        Spacer(modifier = Modifier.height(12.dp))

                        RequirementRow(
                            icon = Icons.Default.PhoneAndroid,
                            text = stringResource(R.string.ai_requirement_device)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        RequirementRow(
                            icon = Icons.Default.Android,
                            text = stringResource(R.string.ai_requirement_os)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        RequirementRow(
                            icon = Icons.Default.Settings,
                            text = stringResource(R.string.ai_requirement_enable)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RequirementRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = OSHIPurple,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 18.sp
        )
    }
}
