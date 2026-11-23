package com.oshi.messenger.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.oshi.messenger.service.LocalLLMManager
import com.oshi.messenger.service.NLPManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import com.oshi.messenger.R

/**
 * AIChatViewModel — drives the full AI chat assistant screen with multi-chat history.
 *
 * Matches iOS AIChatStore feature-for-feature:
 * - Multiple conversations with sidebar
 * - Persistent chat history, sealed at rest (files/ai_chats.sealed) — __LEAK_FIX_2026_09_25__
 * - Auto-title from first user message
 * - Create, select, delete chats
 * - 100% on-device, no data sent anywhere.
 */
@HiltViewModel
class AIChatViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val nlpManager: NLPManager,
    val localLLMManager: LocalLLMManager
) : ViewModel() {

    companion object {
        private const val TAG = "AIChatViewModel"
        private const val STORAGE_KEY = "oshi_ai_chats_v1"
        /** __LEAK_FIX_2026_09_25__ legacy plaintext location, migrated away on first load. */
        private const val LEGACY_PREFS = "oshi_ai"
        private const val AI_CHATS_FILE = com.oshi.messenger.data.local.atrest.SealedStringStore.AI_CHATS_FILE
        private const val AI_CHATS_LABEL = "ai-chats-v1"
        /** __AI_OFFLINE_FIRST_RUN_2026_09_28__ No engine installed yet, and the phone is offline. */
        const val AI_NOT_SET_UP_OFFLINE =
            "The on-device AI isn't set up on this phone yet. Connect to the internet once so Android can " +
                "finish installing Gemini Nano (or download the OSHI offline model from this screen). " +
                "After that, it works fully offline."
    }

    // ── Data Models ──

    data class ChatMessage(
        val id: String = UUID.randomUUID().toString(),
        val content: String,
        val isUser: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
        // Attachment rendered inside user bubble when a photo/file is sent to the AI
        val attachmentLabel: String? = null,
        val attachmentThumbnailUri: String? = null
    ) {
        val timeFormatted: String
            get() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    data class AIChat(
        val id: String = UUID.randomUUID().toString(),
        var title: String = "New Chat",
        var messages: MutableList<ChatMessage> = mutableListOf(),
        val createdAt: Long = System.currentTimeMillis(),
        var lastActivity: Long = System.currentTimeMillis()
    ) {
        val lastActivityFormatted: String
            get() {
                val now = System.currentTimeMillis()
                val diff = now - lastActivity
                return when {
                    diff < 60_000 -> "just now"
                    diff < 3600_000 -> "${diff / 60_000}m ago"
                    diff < 86400_000 -> "${diff / 3600_000}h ago"
                    diff < 604800_000 -> "${diff / 86400_000}d ago"
                    else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(lastActivity))
                }
            }
    }

    // ── State ──

    private val _chats = MutableStateFlow<List<AIChat>>(emptyList())
    val chats: StateFlow<List<AIChat>> = _chats.asStateFlow()

    private val _activeChatId = MutableStateFlow<String?>(null)
    val activeChatId: StateFlow<String?> = _activeChatId.asStateFlow()

    /** Messages of the currently active chat — direct StateFlow, not derived */
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /** The active chat object */
    private val _activeChat = MutableStateFlow<AIChat?>(null)
    val activeChat: StateFlow<AIChat?> = _activeChat.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    /** Streaming text from the local LLM — updated word-by-word during generation */
    val streamingText: StateFlow<String> = localLLMManager.streamingText

    // Available if either Gemini Nano's Prompt API or the local LLM model is ready.
    // NOT nlpManager.isOnDeviceModelAvailable: that is also true when only Proofreading /
    // Rewriting is available, which can't chat — on such phones (e.g. vivo X300 Ultra)
    // this screen used to open straight into a chat with no model and never offer the
    // OSHI offline download.
    val isAvailable: StateFlow<Boolean> = combine(
        nlpManager.isPromptModelAvailable,
        localLLMManager.isModelDownloaded
    ) { gemini, local -> gemini || local }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isGeminiAvailable: StateFlow<Boolean> = nlpManager.isPromptModelAvailable

    /** 🆕 Detailed Gemini Nano state so the UI can distinguish "downloadable / downloading"
     *  from truly unsupported. On supported devices we never want to suggest the OSHI
     *  offline model — Gemini Nano is the system-managed default. */
    val geminiNanoState: StateFlow<com.oshi.messenger.service.NLPManager.GeminiNanoState> = nlpManager.geminiNanoState

    // Conversation context for multi-turn support (per active chat)
    private val conversationHistory = mutableListOf<Pair<String, String>>()

    private val gson = Gson()

    // __INIT_ORDER_2026_09_28__ Declared BEFORE `init`: Kotlin runs property initializers and init blocks in
    // source order, and `init` reads this store — declared below it, the lazy delegate was
    // still null and the constructor threw a NullPointerException (1.6.32/73).
    private val store by lazy {
        com.oshi.messenger.data.local.atrest.SealedStringStore.inFiles(
            appContext,
            fileName = AI_CHATS_FILE,
            label = AI_CHATS_LABEL,
            legacy = com.oshi.messenger.data.local.atrest.SealedStringStore.prefsLegacy(
                appContext, LEGACY_PREFS, STORAGE_KEY,
            ),
        )
    }

    init {
        loadChats()
    }

    // ── Input ──

    fun updateInput(text: String) {
        _inputText.value = text
    }

    // ── Chat Management (matching iOS AIChatStore) ──

    fun createNewChat() {
        val chat = AIChat()
        val updated = listOf(chat) + _chats.value
        _chats.value = updated
        _activeChatId.value = chat.id
        _activeChat.value = chat
        _messages.value = emptyList()
        conversationHistory.clear()
        saveChats()
    }

    fun selectChat(chatId: String) {
        _activeChatId.value = chatId
        val chat = _chats.value.find { it.id == chatId } ?: return
        _activeChat.value = chat
        _messages.value = chat.messages.toList()
        // Rebuild conversation history from the chat messages
        conversationHistory.clear()
        val msgs = chat.messages
        var i = 0
        while (i < msgs.size - 1) {
            if (msgs[i].isUser && !msgs[i + 1].isUser) {
                conversationHistory.add(msgs[i].content to msgs[i + 1].content)
                i += 2
            } else {
                i++
            }
        }
        // Keep last 10 turns
        while (conversationHistory.size > 10) {
            conversationHistory.removeAt(0)
        }
    }

    fun deleteChat(chatId: String) {
        val updated = _chats.value.filter { it.id != chatId }
        _chats.value = updated
        if (_activeChatId.value == chatId) {
            _activeChatId.value = null
            _activeChat.value = null
            _messages.value = emptyList()
        }
        saveChats()
    }

    fun goBackToChatList() {
        _activeChatId.value = null
        _activeChat.value = null
        _messages.value = emptyList()
    }

    // ── Send Message ──

    /** Attachment context from document/image processing */
    private var pendingAttachmentContext: String? = null
    private var pendingAttachmentLabel: String? = null
    private var pendingAttachmentThumbnailUri: String? = null

    fun setAttachmentContext(context: String?, label: String? = null, thumbnailUri: String? = null) {
        pendingAttachmentContext = context
        pendingAttachmentLabel = label
        pendingAttachmentThumbnailUri = thumbnailUri
    }

    fun sendMessage() {
        val text = _inputText.value.trim()
        // Allow send if we have text OR an attachment
        if ((text.isEmpty() && pendingAttachmentLabel == null) || _isGenerating.value) return

        // Ensure we have an active chat — reuse existing or create
        if (_activeChatId.value == null) {
            if (_chats.value.isNotEmpty()) {
                _activeChatId.value = _chats.value.first().id
            } else {
                createNewChat()
            }
        }

        val chatId = _activeChatId.value ?: return

        // Add user message with attachment preview so it shows up in the bubble
        val userMessage = ChatMessage(
            content = text,
            isUser = true,
            attachmentLabel = pendingAttachmentLabel,
            attachmentThumbnailUri = pendingAttachmentThumbnailUri
        )
        updateChatMessages(chatId) { chat ->
            chat.messages.add(userMessage)
            chat.lastActivity = System.currentTimeMillis()
            // Auto-title from first user message (use attachment label if text is empty)
            if (chat.messages.count { it.isUser } == 1) {
                val titleSource = if (text.isNotEmpty()) text else (pendingAttachmentLabel ?: "Attachment")
                chat.title = titleSource.take(40) + if (titleSource.length > 40) "..." else ""
            }
        }

        _inputText.value = ""
        _isGenerating.value = true
        saveChats()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Include attachment context if present
                // 🔧 PERF: Cap OCR/extracted text at 1500 chars — longer context
                // blows up prompt processing on low-RAM devices (e.g. A17 @ 3.6GB)
                val effectiveText = if (pendingAttachmentContext != null) {
                    val question = if (text.isEmpty()) "Please analyze this attachment briefly." else text
                    val ctx = pendingAttachmentContext!!.let {
                        if (it.length > 1500) it.take(1500) + "\n...[truncated for speed]" else it
                    }
                    "Attachment content:\n$ctx\n\nQuestion: $question\n(Keep answer concise.)"
                } else text
                // Clear all pending attachment state after use
                pendingAttachmentContext = null
                pendingAttachmentLabel = null
                pendingAttachmentThumbnailUri = null

                val contextPrompt = buildContextPrompt(effectiveText)

                var aiText: String
                // __AI_OFFLINE_FIRST_RUN_2026_09_28__ No engine is ready and there is no network: say
                // so, instead of a "please rephrase" that sends the user after the wrong cause.
                val noEngineReady = !nlpManager.isPromptModelAvailable.value &&
                    !(localLLMManager.isModelDownloaded.value && localLLMManager.isInferenceReady)
                if (noEngineReady && !nlpManager.isOnline()) {
                    aiText = AI_NOT_SET_UP_OFFLINE
                } else if (nlpManager.isPromptModelAvailable.value) {
                    val response = nlpManager.processAskAI(contextPrompt)
                    aiText = response?.takeIf { it.isNotBlank() }
                        ?: "I couldn't generate a response. Please try rephrasing."
                } else if (localLLMManager.isModelDownloaded.value && localLLMManager.isInferenceReady) {
                    // Build ChatML history from conversation pairs
                    val chatMLHistory = conversationHistory.takeLast(10).flatMap { (user, ai) ->
                        listOf("user" to user, "assistant" to ai)
                    } + listOf("user" to text)
                    aiText = localLLMManager.generate(text, conversationHistory = chatMLHistory)
                } else {
                    val response = nlpManager.processAskAI(contextPrompt)
                    aiText = response?.takeIf { it.isNotBlank() }
                        ?: "I'm currently using basic language rules. For better AI responses, please use a device with Gemini Nano support or wait for the next update."
                }

                // Store in history for multi-turn context
                conversationHistory.add(text to aiText)
                while (conversationHistory.size > 10) {
                    conversationHistory.removeAt(0)
                }

                val aiMessage = ChatMessage(content = aiText, isUser = false)
                // Update messages AND set isGenerating=false atomically on main thread
                withContext(Dispatchers.Main) {
                    updateChatMessages(chatId) { chat ->
                        chat.messages.add(aiMessage)
                        chat.lastActivity = System.currentTimeMillis()
                    }
                    _isGenerating.value = false
                }
                saveChats()
            } catch (e: Exception) {
                Log.e(TAG, "AI response failed: ${e.message}")
                val friendlyText = when {
                    e.message?.contains("model", ignoreCase = true) == true ||
                    e.message?.contains("download", ignoreCase = true) == true ->
                        "The AI model is not ready yet. Please check that Gemini Nano is enabled in Settings > Google > AI settings, then try again."
                    e.message?.contains("memory", ignoreCase = true) == true ||
                    e.message?.contains("resource", ignoreCase = true) == true ->
                        "Not enough memory to run the AI model. Try closing other apps and try again."
                    e.message?.contains("timeout", ignoreCase = true) == true ->
                        "The response took too long. Please try a shorter or simpler question."
                    !nlpManager.isPromptModelAvailable.value && !nlpManager.isOnline() ->
                        AI_NOT_SET_UP_OFFLINE
                    else ->
                        "Something went wrong. Please try rephrasing your question or restart the app."
                }
                val errorMessage = ChatMessage(content = friendlyText, isUser = false)
                withContext(Dispatchers.Main) {
                    updateChatMessages(chatId) { chat ->
                        chat.messages.add(errorMessage)
                    }
                    _isGenerating.value = false
                }
                saveChats()
            }
        }
    }

    private fun updateChatMessages(chatId: String, action: (AIChat) -> Unit) {
        val chatList = _chats.value.toMutableList()
        val index = chatList.indexOfFirst { it.id == chatId }
        if (index >= 0) {
            val chat = chatList[index]
            action(chat)
            chatList[index] = chat
            _chats.value = chatList.toList()
            // Directly update messages and activeChat flows
            if (chatId == _activeChatId.value) {
                _messages.value = chat.messages.toList()
                _activeChat.value = chat
            }
        }
    }

    private fun moveChatToTop(chatId: String) {
        val chatList = _chats.value.toMutableList()
        val index = chatList.indexOfFirst { it.id == chatId }
        if (index > 0) {
            val chat = chatList.removeAt(index)
            chatList.add(0, chat)
            _chats.value = chatList.toList()
        }
    }

    private fun buildContextPrompt(currentQuestion: String): String {
        if (conversationHistory.isEmpty()) {
            return currentQuestion
        }

        val sb = StringBuilder()
        sb.appendLine("Previous conversation for context:")
        conversationHistory.takeLast(5).forEach { (user, ai) ->
            sb.appendLine("User: ${user.take(200)}")
            sb.appendLine("Assistant: ${ai.take(300)}")
        }
        sb.appendLine()
        sb.appendLine("Now answer this: $currentQuestion")
        return sb.toString()
    }

    /** Clear all messages from the active chat (keep the chat itself) */
    fun clearChat() {
        val chatId = _activeChatId.value ?: return
        updateChatMessages(chatId) { chat ->
            chat.messages.clear()
            chat.title = appContext.getString(R.string.ai_new_chat)
        }
        conversationHistory.clear()
        saveChats()
    }

    // ── Persistence ──
    //
    // __LEAK_FIX_2026_09_25__ The whole assistant history (everything the user ever
    // asked the on-device model, and its answers) sat in cleartext in
    // `shared_prefs/oshi_ai.xml`. It now lives in `files/ai_chats.sealed`, AES-GCM
    // under the Keystore-wrapped at-rest key ([SealedStringStore]). Existing history
    // is migrated on first load (read → seal → verify → remove the prefs entry); a
    // failed migration keeps the old copy, so no history is ever lost.


    private fun saveChats() {
        try {
            val json = gson.toJson(_chats.value)
            store.write(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save chats: ${e.message}")
        }
    }

    private fun loadChats() {
        try {
            val json = store.read()
            if (json != null) {
                val type = object : TypeToken<List<AIChat>>() {}.type
                val loaded: List<AIChat> = gson.fromJson(json, type)
                _chats.value = loaded
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load chats: ${e.message}")
        }
    }
}
