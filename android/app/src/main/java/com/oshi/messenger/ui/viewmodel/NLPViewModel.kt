package com.oshi.messenger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.network.location.LocationSharingManager
import com.oshi.messenger.service.NLPManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NLPViewModel @Inject constructor(
    private val nlpManager: NLPManager
) : ViewModel() {

    enum class AIAction(val displayName: String) {
        CORRECT("Correct"),
        IMPROVE("Improve"),
        TRANSLATE("Translate"),
        TONE("Tone"),
        ASK("Ask AI"),
        GENERATE("Generate")
    }

    data class TranslationTarget(val code: String, val name: String)

    data class NLPUiState(
        val originalText: String = "",
        val correctedText: String = "",
        val resultVersion: Int = 0,  // Incremented on each result to force UI update
        val selectedAction: AIAction = AIAction.CORRECT,
        val selectedStyle: NLPManager.ImprovementStyle = NLPManager.ImprovementStyle.FORMAL,
        val selectedCategory: NLPManager.StyleCategory = NLPManager.StyleCategory.GENERAL,
        val isLoading: Boolean = false,
        val detectedLanguage: String = "",
        val isOnDeviceModelAvailable: Boolean = false,
        val selectedTranslationTarget: TranslationTarget? = null,
        val askQuestion: String = "",
        val isRuleBased: Boolean = true,
        // Context-aware features
        val conversationContext: String = "",
        val smartReplies: List<String> = emptyList(),
        val conversationSuggestions: List<String> = emptyList(),
        val selectedGeneratorMode: NLPManager.ConversationGeneratorMode = NLPManager.ConversationGeneratorMode.ICEBREAKER,
        val suggestedTone: NLPManager.ImprovementStyle? = null,
        // Thinking-of-you tab
        val thinkingOfYouTone: NLPManager.ImprovementStyle = NLPManager.ImprovementStyle.FRIENDLY,
        val thinkingOfYouResult: String = "",
        /**
         * Dedicated spinner flag for the "Je pense à toi" sheet.
         *
         * [isLoading] is shared by three different user-initiated actions, and the
         * sheet is a second, independent consumer of the generator now — a shared
         * flag would let a correction started elsewhere spin this sheet's button,
         * and vice versa.
         */
        val isGeneratingThinkingOfYou: Boolean = false
    )

    private val _uiState = MutableStateFlow(NLPUiState())
    val uiState: StateFlow<NLPUiState> = _uiState.asStateFlow()

    companion object {
        private const val MAX_CONTEXT_CHARS = 800

        val TRANSLATION_TARGETS = listOf(
            TranslationTarget("en", "English"),
            TranslationTarget("fr", "French"),
            TranslationTarget("de", "German"),
            TranslationTarget("es", "Spanish"),
            TranslationTarget("it", "Italian"),
            TranslationTarget("pt", "Portuguese"),
            TranslationTarget("ru", "Russian"),
            TranslationTarget("zh", "Chinese"),
            TranslationTarget("ja", "Japanese"),
            TranslationTarget("ar", "Arabic"),
            TranslationTarget("hi", "Hindi"),
            TranslationTarget("ko", "Korean")
        )
    }

    init {
        viewModelScope.launch {
            nlpManager.isOnDeviceModelAvailable.collect { available ->
                _uiState.value = _uiState.value.copy(isOnDeviceModelAvailable = available)
            }
        }
    }

    fun setOriginalText(text: String) {
        _uiState.value = _uiState.value.copy(originalText = text, correctedText = "")
        viewModelScope.launch {
            val lang = nlpManager.detectLanguage(text)
            _uiState.value = _uiState.value.copy(detectedLanguage = lang)
        }
    }

    fun selectAction(action: AIAction) {
        _uiState.value = _uiState.value.copy(selectedAction = action, correctedText = "", conversationSuggestions = emptyList())
    }

    fun selectStyle(style: NLPManager.ImprovementStyle) {
        _uiState.value = _uiState.value.copy(selectedStyle = style, correctedText = "")
    }

    fun selectCategory(category: NLPManager.StyleCategory) {
        _uiState.value = _uiState.value.copy(selectedCategory = category, correctedText = "")
        // Auto-select first style in category
        val firstStyle = NLPManager.ImprovementStyle.entries.first { it.category == category }
        selectStyle(firstStyle)
    }

    fun selectGeneratorMode(mode: NLPManager.ConversationGeneratorMode) {
        _uiState.value = _uiState.value.copy(selectedGeneratorMode = mode, conversationSuggestions = emptyList())
    }

    fun selectTranslationTarget(target: TranslationTarget) {
        _uiState.value = _uiState.value.copy(selectedTranslationTarget = target, correctedText = "")
    }

    fun setAskQuestion(question: String) {
        _uiState.value = _uiState.value.copy(askQuestion = question)
    }

    fun setConversationContext(context: String) {
        _uiState.value = _uiState.value.copy(conversationContext = context)
    }

    /**
     * Build conversation context from last N messages (max 800 chars).
     * Messages are formatted as "You: ..." / "Them: ..." for LLM context.
     */
    fun buildConversationContext(
        messages: List<Pair<Boolean, String>>,  // (isMine, decryptedContent)
    ): String {
        val lastMessages = messages.takeLast(5)
        val sb = StringBuilder()
        for ((isMine, content) in lastMessages) {
            val prefix = if (isMine) "You" else "Them"
            val line = "$prefix: ${sanitizeForAIContext(content)}"
            if (sb.length + line.length + 1 > MAX_CONTEXT_CHARS) break
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(line)
        }
        val context = sb.toString()
        _uiState.value = _uiState.value.copy(conversationContext = context)
        return context
    }

    /**
     * Strip media JSON, raw base64 blobs, and signal envelopes from text used in AI prompts.
     * Returns a short natural-language placeholder when the content is non-textual.
     */
    private fun sanitizeForAIContext(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        // The wire prefix, not a retyped emoji: the previous literal had a U+FE0F
        // after the pushpin and never matched, so the model was primed with raw
        // coordinates instead of "[location]" (iOS `ChatView.swift:346`).
        if (trimmed.startsWith(LocationSharingManager.MESSAGE_PREFIX)) return "[location]"
        if (trimmed.startsWith("{") &&
            (trimmed.contains("\"type\":\"media\"") || trimmed.contains("\"type\": \"media\""))) {
            return try {
                val json = org.json.JSONObject(trimmed)
                val mediaType = json.optString("mediaType").lowercase()
                val caption = json.optString("caption").trim()
                val label = when (mediaType) {
                    "photo", "image" -> "[image]"
                    "video" -> "[video]"
                    "audio", "voice" -> "[audio]"
                    "document", "file" -> "[file]"
                    else -> "[media]"
                }
                if (caption.isEmpty()) label else "$label $caption"
            } catch (_: Exception) {
                "[media]"
            }
        }
        // Long blob with no whitespace — likely a base64 attachment payload.
        if (trimmed.length > 200 && !trimmed.contains(' ') && !trimmed.contains('\n')) {
            return "[media]"
        }
        return trimmed
    }

    /**
     * Generate smart reply suggestions for the last received message.
     */
    fun generateSmartReplies(lastMessage: String) {
        val state = _uiState.value
        viewModelScope.launch {
            try {
                val replies = nlpManager.generateSmartReplies(lastMessage, state.conversationContext)
                _uiState.value = _uiState.value.copy(smartReplies = replies)
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(smartReplies = emptyList())
            }
        }
    }

    fun clearSmartReplies() {
        _uiState.value = _uiState.value.copy(smartReplies = emptyList())
    }

    /**
     * Generate conversation starters for the selected mode.
     */
    fun generateConversationSuggestions(contactName: String = "") {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val suggestions = nlpManager.generateConversationSuggestions(
                    mode = state.selectedGeneratorMode,
                    conversationContext = state.conversationContext,
                    contactName = contactName
                )
                _uiState.value = _uiState.value.copy(
                    conversationSuggestions = suggestions,
                    isLoading = false,
                    isRuleBased = !state.isOnDeviceModelAvailable
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(conversationSuggestions = emptyList(), isLoading = false)
            }
        }
    }

    /**
     * Suggest a tone based on the sentiment of the last received message.
     */
    fun suggestToneForLastMessage(message: String) {
        val suggested = nlpManager.suggestTone(message)
        _uiState.value = _uiState.value.copy(suggestedTone = suggested)
    }

    fun processCurrentAction() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val result = when (state.selectedAction) {
                    AIAction.CORRECT -> nlpManager.processCorrection(state.originalText)
                    AIAction.IMPROVE -> nlpManager.processImprovement(state.originalText, state.selectedStyle, state.conversationContext)
                    AIAction.TRANSLATE -> {
                        val target = state.selectedTranslationTarget?.code ?: "en"
                        nlpManager.processTranslation(state.originalText, target) ?: state.originalText
                    }
                    AIAction.TONE -> nlpManager.processImprovement(state.originalText, state.selectedStyle, state.conversationContext)
                    AIAction.ASK -> nlpManager.processAskAI(state.askQuestion) ?: "AI not available"
                    AIAction.GENERATE -> {
                        // Generate is handled by generateConversationSuggestions(), not here
                        state.originalText
                    }
                }
                _uiState.value = _uiState.value.copy(
                    correctedText = result ?: state.originalText,
                    resultVersion = _uiState.value.resultVersion + 1,
                    isLoading = false,
                    isRuleBased = !state.isOnDeviceModelAvailable && state.selectedAction != AIAction.TRANSLATE
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    correctedText = state.originalText,
                    isLoading = false
                )
            }
        }
    }

    fun getResult(): String = _uiState.value.correctedText

    /**
     * One-shot Improve helper for the AIWritingAssistantSheet "Improve" tab.
     * Sets text, action, style, then runs.
     */
    fun aiImprove(text: String, style: NLPManager.ImprovementStyle) {
        _uiState.value = _uiState.value.copy(
            originalText = text,
            selectedAction = AIAction.IMPROVE,
            selectedStyle = style,
            selectedCategory = style.category,
            correctedText = ""
        )
        processCurrentAction()
    }

    /**
     * One-shot Correct helper.
     */
    fun aiCorrect(text: String) {
        _uiState.value = _uiState.value.copy(
            originalText = text,
            selectedAction = AIAction.CORRECT,
            correctedText = ""
        )
        processCurrentAction()
    }

    /**
     * One-shot Translate helper.
     */
    fun aiTranslate(text: String, target: TranslationTarget) {
        _uiState.value = _uiState.value.copy(
            originalText = text,
            selectedAction = AIAction.TRANSLATE,
            selectedTranslationTarget = target,
            correctedText = ""
        )
        processCurrentAction()
    }

    fun selectThinkingOfYouTone(tone: NLPManager.ImprovementStyle) {
        _uiState.value = _uiState.value.copy(thinkingOfYouTone = tone, thinkingOfYouResult = "")
    }

    /**
     * Generate a "Thinking of you" message via NLPManager.
     */
    fun generateThinkingOfYou(contactName: String = "") {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                isGeneratingThinkingOfYou = true,
                thinkingOfYouResult = ""
            )
            try {
                val msg = nlpManager.generateThinkingOfYou(
                    recipientName = contactName,
                    tone = state.thinkingOfYouTone,
                    conversationContext = state.conversationContext
                )
                _uiState.value = _uiState.value.copy(
                    thinkingOfYouResult = msg,
                    isLoading = false,
                    isGeneratingThinkingOfYou = false,
                    isRuleBased = !state.isOnDeviceModelAvailable
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    thinkingOfYouResult = "",
                    isLoading = false,
                    isGeneratingThinkingOfYou = false
                )
            }
        }
    }

    /**
     * Drop the generated draft when the "Je pense à toi" sheet closes.
     *
     * Without this the NEXT open of the sheet would prefill itself with the message
     * the user just scheduled (or just dismissed), because
     * `ThinkingOfYouSheet`'s `LaunchedEffect(generatedText)` fires on any non-blank
     * value — including a stale one that never changed.
     */
    fun clearThinkingOfYou() {
        _uiState.value = _uiState.value.copy(
            thinkingOfYouResult = "",
            isGeneratingThinkingOfYou = false
        )
    }

    fun clearTransientResults() {
        _uiState.value = _uiState.value.copy(
            correctedText = "",
            conversationSuggestions = emptyList(),
            thinkingOfYouResult = ""
        )
    }
}
