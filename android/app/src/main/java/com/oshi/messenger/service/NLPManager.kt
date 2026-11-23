package com.oshi.messenger.service

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NLPManager - Android equivalent of iOS NLPManager.swift
 *
 * Provides on-device AI text processing using Google's Gemini Nano (ML Kit GenAI APIs):
 * - Proofreading API: spelling & grammar correction (like iOS Apple Intelligence aiCorrect)
 * - Rewriting API: text improvement with 6 tones (like iOS aiImprove)
 * - Prompt API: Q&A / Ask AI (like iOS aiAsk via FoundationModels)
 * - ML Kit Translate: 59 languages, ~30MB per model
 * - ML Kit Language ID: offline language detection
 *
 * Falls back to rule-based processing when Gemini Nano is unavailable.
 * 100% offline, no data sent to servers.
 */
@Singleton
class NLPManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localLLMManager: LocalLLMManager
) {
    /**
     * Outcome of a translate request, rich enough for the UI to say something honest.
     *
     * `processTranslateReceived` used to collapse every failure into `null`, which is
     * fine for a caller that only shows "unavailable" — but the three failure modes a
     * user actually hits are different problems with different remedies, and a sheet
     * that spins forever (or prints a single dead-end string) is the reason this
     * feature stayed a stub. See §RP-2 of the 2026-08-24 react-reply audit.
     */
    sealed class TranslationOutcome {
        /** Real translated text. */
        data class Translated(val text: String) : TranslationOutcome()

        /**
         * Source and target are the same language, so there is nothing to translate.
         * The UI shows the original rather than pretending work happened.
         */
        data class AlreadyInTargetLanguage(val text: String) : TranslationOutcome()

        /** ML Kit has no model for the source and/or the device language. */
        data class UnsupportedLanguage(
            val sourceCode: String?,
            val targetCode: String?
        ) : TranslationOutcome()

        /**
         * The language pack is not on the device yet and there is no usable network to
         * fetch it. ML Kit downloads ~30 MB on first use per pair; offline, this is a
         * permanent failure until connectivity returns, not a transient hiccup.
         */
        object ModelNotDownloaded : TranslationOutcome()

        /** Everything was in place and it still failed. */
        data class Failed(val reason: String?) : TranslationOutcome()
    }

    companion object {
        private const val TAG = "NLPManager"

        /** The plaintext debug file versions ≤ 1.6.x wrote (see [debugLog]). */
        const val LEGACY_DEBUG_FILE = "nlp_debug.txt"

        /**
         * __NLP_DEBUG_FILE_LEAK_2026_09_23__ Deletes every `nlp_debug.txt` an older build
         * may have left. The only writer ever was `getExternalFilesDir(null)` (git history
         * has no other); the internal dirs are swept too because deleting a file that is
         * not there costs nothing. Called off the main thread from `Application.onCreate`.
         * @return how many files were removed.
         */
        fun purgeLegacyDebugFiles(dirs: List<java.io.File?>): Int =
            dirs.filterNotNull().distinct().count { dir ->
                val f = java.io.File(dir, LEGACY_DEBUG_FILE)
                f.isFile && f.delete()
            }

        fun legacyDebugFileDirs(context: Context): List<java.io.File?> = listOf(
            runCatching { context.getExternalFilesDir(null) }.getOrNull(),
            runCatching { context.externalCacheDir }.getOrNull(),
            context.filesDir,
            context.cacheDir,
        )

        /**
         * Pure classifier for the ML Kit pre-flight. Split out from the coroutine so the
         * decision table is JVM-unit-testable without ML Kit, Play Services or a device.
         *
         * @param sourceCode ML Kit language code for the detected source, null if unmapped.
         * @param targetCode ML Kit language code for the device locale, null if unmapped.
         * @param modelsPresent whether BOTH packs are already on the device.
         * @param online whether a network is available to download a missing pack.
         * @return the outcome to report, or null to mean "go ahead and translate".
         */
        fun classifyTranslationPreflight(
            sourceCode: String?,
            targetCode: String?,
            modelsPresent: Boolean,
            online: Boolean
        ): TranslationOutcome? = when {
            sourceCode == null || targetCode == null ->
                TranslationOutcome.UnsupportedLanguage(sourceCode, targetCode)
            !modelsPresent && !online -> TranslationOutcome.ModelNotDownloaded
            else -> null
        }
    }

    // 9 tones in 3 categories (matching iOS)
    enum class StyleCategory(val displayName: String) {
        GENERAL("General"),
        SOCIAL("Social"),
        DATING("Dating")
    }

    enum class ImprovementStyle(val displayName: String, val category: StyleCategory) {
        // General (3)
        FORMAL("Formal", StyleCategory.GENERAL),
        PROFESSIONAL("Professional", StyleCategory.GENERAL),
        PERSUASIVE("Persuasive", StyleCategory.GENERAL),
        // Social (3)
        CASUAL("Casual", StyleCategory.SOCIAL),
        FRIENDLY("Friendly", StyleCategory.SOCIAL),
        PLAYFUL("Playful", StyleCategory.SOCIAL),
        // Dating (3)
        FLIRTY("Flirty", StyleCategory.DATING),
        SEDUCTIVE("Seductive", StyleCategory.DATING),
        INTRIGUED("Intrigued", StyleCategory.DATING)
    }

    enum class ConversationGeneratorMode(val displayName: String) {
        ICEBREAKER("Icebreaker"),
        RELAUNCH("Relaunch"),
        FLIRTY("Flirty"),
        FRIENDLY("Friendly")
    }

    enum class Sentiment { POSITIVE, NEUTRAL, NEGATIVE }

    data class SpellSuggestion(
        val range: IntRange,
        val word: String,
        val suggestions: List<String>
    )

    // State
    private val _detectedLanguage = MutableStateFlow("en")
    val detectedLanguage: StateFlow<String> = _detectedLanguage.asStateFlow()

    private val _spellSuggestions = MutableStateFlow<List<SpellSuggestion>>(emptyList())
    val spellSuggestions: StateFlow<List<SpellSuggestion>> = _spellSuggestions.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _isOnDeviceModelAvailable = MutableStateFlow(false)
    val isOnDeviceModelAvailable: StateFlow<Boolean> = _isOnDeviceModelAvailable.asStateFlow()

    private val _isTranslationAvailable = MutableStateFlow(true)
    val isTranslationAvailable: StateFlow<Boolean> = _isTranslationAvailable.asStateFlow()

    private val _downloadedLanguages = MutableStateFlow<Set<String>>(emptySet())
    val downloadedLanguages: StateFlow<Set<String>> = _downloadedLanguages.asStateFlow()

    // 🆕 Gemini Nano availability state, exposed to the UI so AIChatScreen can show
    // a "Gemini Nano is downloading..." view instead of the OSHI offline-model
    // download prompt on devices that natively support Gemini Nano.
    //   UNKNOWN | UNSUPPORTED | DOWNLOADABLE | DOWNLOADING | AVAILABLE
    // ⚠️ Declared BEFORE the init block — the coroutine launched there can hit
    // `_geminiNanoState.value = …` from an IO worker before any property
    // declared after init is initialised, which crashed the app on QR scan
    // (NPE on StateFlow.setValue, see commit 9953220 follow-up).
    enum class GeminiNanoState { UNKNOWN, UNSUPPORTED, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

    private val _geminiNanoState = MutableStateFlow(GeminiNanoState.UNKNOWN)
    val geminiNanoState: StateFlow<GeminiNanoState> = _geminiNanoState.asStateFlow()

    // Gemini Nano *Prompt API* specifically — the only GenAI feature that can answer a
    // chat question. [isOnDeviceModelAvailable] also turns true when only Proofreading or
    // Rewriting is available (common on non-Pixel AICore phones such as the vivo X300),
    // which made the AI Assistant think a chat model was ready and hide the OSHI offline
    // model download. The AI Assistant must gate on this flow instead.
    private val _isPromptModelAvailable = MutableStateFlow(false)
    val isPromptModelAvailable: StateFlow<Boolean> = _isPromptModelAvailable.asStateFlow()

    // ML Kit Language Identifier (offline)
    private var languageIdentifier: com.google.mlkit.nl.languageid.LanguageIdentifier? = null

    // ML Kit Translation model manager
    private val modelManager = RemoteModelManager.getInstance()

    // ML Kit GenAI - Gemini Nano clients (lazy-initialized)
    private var generativeModel: com.google.mlkit.genai.prompt.GenerativeModel? = null
    private var isProofreadingAvailable = false
    private var isRewritingAvailable = false
    private var isPromptAvailable = false

    // Coroutine scope for background init (not tied to any lifecycle — singleton)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // __NLP_DEBUG_FILE_LEAK_2026_09_23__ This used to append every line below to
    // `getExternalFilesDir(null)/nlp_debug.txt` — IN CLEAR, on external storage — and
    // through `Log.e`, which R8 keeps in release. `processCorrection` logged the first
    // 50 characters of what the user was typing and 80 of the corrected text, so an
    // E2E messenger was writing drafts to a world-readable-on-old-Android file and to
    // logcat on every "fix my spelling". Now: logcat only, debug builds only, and the
    // lines carry counters and states, never text. Old files are deleted at start
    // ([purgeLegacyDebugFiles]).
    private fun debugLog(msg: String) {
        if (com.oshi.messenger.BuildConfig.DEBUG) Log.d(TAG, msg)
    }

    init {
        debugLog("🚀 NLPManager init starting...")
        try {
            languageIdentifier = com.google.mlkit.nl.languageid.LanguageIdentification.getClient()
            debugLog("✅ ML Kit Language Identifier initialized")
        } catch (e: Exception) {
            debugLog("❌ ML Kit Language ID not available: ${e.message}")
        }
        checkOnDeviceModelAvailability()
        refreshDownloadedLanguages()
        registerNetworkRecheck()
    }

    // __AI_OFFLINE_FIRST_RUN_2026_09_28__ Gemini Nano is installed by Google AICore, which needs
    // the network once. A first launch in airplane mode found it DOWNLOADABLE, the download failed,
    // the state went UNSUPPORTED and nothing looked again until the next cold start: the assistant
    // answered "please rephrase" until the user happened to relaunch online (user report 28/09,
    // vivo X300). Re-check as soon as a network is back, rate-limited.
    @Volatile private var lastOnlineRecheckMs = 0L

    private fun registerNetworkRecheck() {
        try {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    if (isPromptAvailable) return
                    if (_geminiNanoState.value == GeminiNanoState.DOWNLOADING) return
                    val now = System.currentTimeMillis()
                    if (now - lastOnlineRecheckMs < 5 * 60_000L) return
                    lastOnlineRecheckMs = now
                    debugLog("🌐 Network available — re-checking Gemini Nano (${_geminiNanoState.value})")
                    checkOnDeviceModelAvailability()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Gemini Nano network re-check not registered: ${e.message}")
        }
    }

    /** True when the device has a network with internet capability right now. */
    fun isOnline(): Boolean = try {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        cm?.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) { true }

    private fun checkOnDeviceModelAvailability() {
        _isTranslationAvailable.value = true

        // Check Gemini Nano availability via ML Kit GenAI Prompt API
        try {
            generativeModel = com.google.mlkit.genai.prompt.Generation.getClient()
            scope.launch {
                // Check Prompt API (suspend function)
                try {
                    val status = generativeModel!!.checkStatus()
                    when (status) {
                        3 /* AVAILABLE */ -> {
                            isPromptAvailable = true
                            _isPromptModelAvailable.value = true
                            _isOnDeviceModelAvailable.value = true
                            _geminiNanoState.value = GeminiNanoState.AVAILABLE
                            debugLog("✅ Gemini Nano Prompt API: AVAILABLE")
                            try { generativeModel!!.warmup() } catch (_: Exception) {}
                        }
                        1 /* DOWNLOADABLE */ -> {
                            _geminiNanoState.value = GeminiNanoState.DOWNLOADABLE
                            debugLog("⬇️ Gemini Nano: DOWNLOADABLE — triggering download")
                            triggerModelDownload()
                        }
                        2 /* DOWNLOADING */ -> {
                            _geminiNanoState.value = GeminiNanoState.DOWNLOADING
                            debugLog("⏳ Gemini Nano: DOWNLOADING")
                            // Follow the in-flight download so the state can leave DOWNLOADING
                            // (to AVAILABLE, or to UNSUPPORTED if it fails) instead of pinning
                            // the AI Assistant on "preparing" forever.
                            triggerModelDownload()
                        }
                        else -> {
                            _geminiNanoState.value = GeminiNanoState.UNSUPPORTED
                            debugLog("❌ Gemini Nano: UNAVAILABLE on this device (status=$status)")
                        }
                    }
                } catch (e: Exception) {
                    _geminiNanoState.value = GeminiNanoState.UNSUPPORTED
                    Log.w(TAG, "Gemini Nano status check failed: ${e.message}")
                }

                // Check Proofreading API (ListenableFuture)
                try {
                    val options = com.google.mlkit.genai.proofreading.ProofreaderOptions.builder(context)
                        .setInputType(com.google.mlkit.genai.proofreading.ProofreaderOptions.InputType.KEYBOARD)
                        .setLanguage(com.google.mlkit.genai.proofreading.ProofreaderOptions.Language.ENGLISH)
                        .build()
                    val proofreader = com.google.mlkit.genai.proofreading.Proofreading.getClient(options)
                    val proofStatus = proofreader.checkFeatureStatus().get()
                    isProofreadingAvailable = (proofStatus == 3) // AVAILABLE
                    debugLog("📋 Proofreading: ${if (isProofreadingAvailable) "AVAILABLE" else "NOT AVAILABLE (status=$proofStatus)"}")
                    proofreader.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Proofreading check failed: ${e.message}")
                }

                // Check Rewriting API (ListenableFuture)
                try {
                    val options = com.google.mlkit.genai.rewriting.RewriterOptions.builder(context)
                        .setOutputType(com.google.mlkit.genai.rewriting.RewriterOptions.OutputType.PROFESSIONAL)
                        .setLanguage(com.google.mlkit.genai.rewriting.RewriterOptions.Language.ENGLISH)
                        .build()
                    val rewriter = com.google.mlkit.genai.rewriting.Rewriting.getClient(options)
                    val rewriteStatus = rewriter.checkFeatureStatus().get()
                    isRewritingAvailable = (rewriteStatus == 3) // AVAILABLE
                    debugLog("✏️ Rewriting: ${if (isRewritingAvailable) "AVAILABLE" else "NOT AVAILABLE (status=$rewriteStatus)"}")
                    rewriter.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Rewriting check failed: ${e.message}")
                }

                // Update overall availability
                if (isProofreadingAvailable || isRewritingAvailable || isPromptAvailable) {
                    _isOnDeviceModelAvailable.value = true
                }
                debugLog("🤖 On-device AI summary: prompt=$isPromptAvailable, proofread=$isProofreadingAvailable, rewrite=$isRewritingAvailable")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit GenAI not available: ${e.message}")
            _isOnDeviceModelAvailable.value = false
        }
    }

    private fun triggerModelDownload() {
        try {
            val flow = generativeModel?.download()
            if (flow == null) {
                markGeminiDownloadUnusable("download() returned no flow")
                return
            }
            scope.launch {
                try {
                    flow.collect { status ->
                        Log.d(TAG, "Gemini Nano download status: $status")
                        // Check if download completed by re-checking status
                        try {
                            val currentStatus = generativeModel!!.checkStatus()
                            if (currentStatus == 3) { // AVAILABLE
                                _isOnDeviceModelAvailable.value = true
                                _isPromptModelAvailable.value = true
                                _geminiNanoState.value = GeminiNanoState.AVAILABLE
                                isPromptAvailable = true
                                Log.d(TAG, "Gemini Nano download completed!")
                                try { generativeModel!!.warmup() } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    // Previously uncaught: a failed AICore download escaped the launch.
                    Log.w(TAG, "Gemini Nano download failed: ${e.message}")
                }
                // The flow ended (completed or failed). If Gemini Nano still isn't usable,
                // stop claiming it is "preparing" — otherwise the AI Assistant waits on it
                // forever and never offers the OSHI offline model.
                if (!isPromptAvailable) {
                    val finalStatus = try { generativeModel?.checkStatus() } catch (_: Exception) { null }
                    when (finalStatus) {
                        3 -> {
                            isPromptAvailable = true
                            _isPromptModelAvailable.value = true
                            _isOnDeviceModelAvailable.value = true
                            _geminiNanoState.value = GeminiNanoState.AVAILABLE
                        }
                        2 -> _geminiNanoState.value = GeminiNanoState.DOWNLOADING
                        else -> markGeminiDownloadUnusable("download flow ended, status=$finalStatus")
                    }
                }
            }
        } catch (e: Exception) {
            markGeminiDownloadUnusable("download trigger failed: ${e.message}")
        }
    }

    private fun markGeminiDownloadUnusable(reason: String) {
        debugLog("❌ Gemini Nano not obtainable ($reason) — offering the OSHI offline model")
        _geminiNanoState.value = GeminiNanoState.UNSUPPORTED
    }

    private fun refreshDownloadedLanguages() {
        modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                val langs = models.map { it.language }.toSet()
                _downloadedLanguages.value = langs
                Log.d(TAG, "Downloaded translation models: $langs")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to list downloaded models: ${e.message}")
            }
    }

    // =========================================================================
    // LOCAL LLM FALLBACK HELPER
    // =========================================================================

    /**
     * Whether the OSHI AI offline model is downloaded and can be called.
     * Note: even when downloaded, actual inference requires native JNI bindings.
     * The fallback still calls generate() which returns a graceful placeholder
     * until native inference is shipped, keeping all wiring in place.
     */
    private val isLocalLLMAvailable: Boolean
        get() = localLLMManager.isModelDownloaded.value

    private suspend fun localLLMGenerate(prompt: String): String {
        return localLLMManager.generate(prompt)
    }

    // =========================================================================
    // UNIFIED ENTRY POINTS (match iOS processCorrection/processImprovement)
    // =========================================================================

    suspend fun processCorrection(text: String): String = withContext(Dispatchers.Default) {
        _isProcessing.value = true
        try {
            debugLog("📝 processCorrection: proofread=$isProofreadingAvailable, prompt=$isPromptAvailable, localLLM=$isLocalLLMAvailable, inputLen=${text.length}")
            val result = if (isProofreadingAvailable) {
                debugLog("📝 Using PROOFREADING API")
                aiCorrect(text)
            } else if (isPromptAvailable) {
                debugLog("📝 Using PROMPT API for correction")
                aiCorrectViaPrompt(text)
            } else if (isLocalLLMAvailable) {
                debugLog("📝 Using LOCAL LLM for correction")
                val prompt = "Correct the spelling and grammar of this text. Return ONLY the corrected text, nothing else:\n$text"
                localLLMGenerate(prompt)
            } else {
                debugLog("📝 Using RULE-BASED correction")
                autoCorrect(text)
            }
            debugLog("📝 processCorrection resultLen=${result.length}")
            validateResult(text, result)
        } finally {
            _isProcessing.value = false
        }
    }

    suspend fun processImprovement(text: String, style: ImprovementStyle, conversationContext: String = ""): String = withContext(Dispatchers.Default) {
        _isProcessing.value = true
        try {
            debugLog("📝 processImprovement: style=${style.displayName}, rewrite=$isRewritingAvailable, prompt=$isPromptAvailable, localLLM=$isLocalLLMAvailable, inputLen=${text.length}")
            val result = if (style.category == StyleCategory.DATING) {
                if (isPromptAvailable) {
                    aiImproveViaPrompt(text, style, conversationContext)
                } else if (isLocalLLMAvailable) {
                    val prompt = "Rewrite this message in a ${style.displayName.lowercase()} tone. Return ONLY the rewritten text:\n$text"
                    localLLMGenerate(prompt)
                } else {
                    improveMessageRuleBased(text, style)
                }
            } else if (isRewritingAvailable && conversationContext.isEmpty()) {
                aiImprove(text, style)
            } else if (isPromptAvailable) {
                aiImproveViaPrompt(text, style, conversationContext)
            } else if (isLocalLLMAvailable) {
                val contextBlock = if (conversationContext.isNotEmpty()) "\nConversation context:\n$conversationContext\n" else ""
                val prompt = "Rewrite this message in a ${style.displayName.lowercase()} tone.${contextBlock}\nReturn ONLY the rewritten text:\n$text"
                localLLMGenerate(prompt)
            } else {
                improveMessageRuleBased(text, style)
            }
            debugLog("📝 processImprovement resultLen=${result.length}")
            validateResult(text, result)
        } finally {
            _isProcessing.value = false
        }
    }

    suspend fun processTranslation(text: String, targetLanguage: String): String? = withContext(Dispatchers.Default) {
        _isProcessing.value = true
        try {
            if (isPromptAvailable) {
                val aiResult = aiTranslateViaPrompt(text, targetLanguage)
                if (aiResult != null) return@withContext aiResult
            }
            if (isLocalLLMAvailable) {
                val targetName = languageName(targetLanguage)
                val prompt = "Translate the following text into $targetName. Return ONLY the translated text:\n$text"
                val localResult = localLLMGenerate(prompt)
                if (localResult.isNotBlank() && !localResult.contains("Native inference engine")) {
                    return@withContext localResult
                }
            }
            mlKitTranslate(text, targetLanguage)
        } finally {
            _isProcessing.value = false
        }
    }

    suspend fun processAskAI(question: String): String? = withContext(Dispatchers.Default) {
        _isProcessing.value = true
        try {
            if (isPromptAvailable) {
                aiAsk(question)
            } else if (isLocalLLMAvailable) {
                val prompt = "You are a helpful assistant in a messaging app. Answer concisely:\n$question"
                localLLMGenerate(prompt)
            } else {
                null
            }
        } finally {
            _isProcessing.value = false
        }
    }

    /**
     * Legacy shape — a bare `String?`. Kept so existing callers compile; new call sites
     * should use [translateReceived], which distinguishes the failure modes.
     */
    suspend fun processTranslateReceived(text: String): String? =
        when (val outcome = translateReceived(text)) {
            is TranslationOutcome.Translated -> outcome.text
            is TranslationOutcome.AlreadyInTargetLanguage -> outcome.text
            else -> null
        }

    /**
     * Translate a received message into the device language, exactly like iOS's
     * `NLPManager.processTranslateReceived` (called from `ChatView.swift:4583` and
     * `GroupViews.swift:4431`): Gemini Nano prompt → local LLM → ML Kit.
     *
     * Unlike the old `String?` version, every failure carries *why*, so the sheet can say
     * "unsupported language" / "download needed, you are offline" instead of spinning.
     */
    suspend fun translateReceived(text: String): TranslationOutcome = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext TranslationOutcome.Translated(text)
        _isProcessing.value = true
        try {
            val deviceLang = Locale.getDefault().language

            // Tier 1 — Gemini Nano (on-device, no network, no language pack).
            if (isPromptAvailable) {
                val aiResult = aiTranslateViaPrompt(text, deviceLang)
                if (aiResult != null) return@withContext TranslationOutcome.Translated(aiResult)
            }

            // Tier 2 — bundled local LLM.
            if (isLocalLLMAvailable) {
                val targetName = languageName(deviceLang)
                val prompt = "Translate the following text into $targetName. Return ONLY the translated text:\n$text"
                val localResult = localLLMGenerate(prompt)
                if (localResult.isNotBlank() && !localResult.contains("Native inference engine")) {
                    return@withContext TranslationOutcome.Translated(localResult)
                }
            }

            // Tier 3 — ML Kit, the only tier that can need a download.
            mlKitTranslateDetailed(text, deviceLang)
        } finally {
            _isProcessing.value = false
        }
    }

    // =========================================================================
    // RESULT VALIDATION — catch corrupted AI output
    // =========================================================================

    /**
     * Strip chain-of-thought / preamble that on-device Gemini Nano sometimes leaks despite
     * "OUTPUT ONLY" instructions. Mirrors the iOS sanitizeLLMOutput in NLPManager.swift.
     */
    private fun sanitizeLLMOutput(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s: String = raw

        // Strip <think>…</think> blocks (some models echo their reasoning)
        s = s.replace(Regex("(?is)<think>.*?</think>"), "")

        // Strip code-fence wrapper ```...```
        val fence = Regex("(?s)```(?:[a-zA-Z]+)?\\n?(.*?)```")
        fence.find(s)?.let { match -> s = match.groupValues[1] }

        s = s.trim()

        val metaLeads = listOf(
            "okay,", "okay.", "ok,", "ok.", "sure,", "sure.", "of course,", "of course.",
            "alright,", "alright.", "got it,", "got it.",
            "the user wants", "the user is asking", "the user asked",
            "let me ", "let's ", "i'll ", "i will ", "i'm going to ", "i am going to ",
            "first,", "first ", "step ", "thinking",
            "here's the rewrite", "here's the rewritten", "here is the rewrite", "here is the rewritten",
            "here's the message", "here is the message",
            "here's the casual", "here's a casual", "here is the casual", "here is a casual",
            "here's the formal", "here is the formal",
            "here's the translation", "here is the translation",
            "here's the corrected", "here is the corrected",
            "here you go", "here ya go", "voici la", "voici le", "voici votre",
            "ecco il", "ecco la", "aquí está", "aquí tienes", "hier ist",
        )
        for (i in 0 until 3) {
            val firstChunk = s.lowercase().take(80)
            if (metaLeads.none { firstChunk.startsWith(it) }) break
            val dblNL = s.indexOf("\n\n")
            val colonNL = s.indexOf(":\n")
            val nl = s.indexOf('\n')
            s = when {
                dblNL >= 0 -> s.substring(dblNL + 2).trim()
                colonNL >= 0 -> s.substring(colonNL + 2).trim()
                nl >= 0 -> s.substring(nl + 1).trim()
                else -> break.let { s }
            }
        }

        val inlinePrefixes = listOf(
            "Here's the rewrite:", "Here is the rewrite:",
            "Here's the rewritten message:", "Here is the rewritten message:",
            "Here's the corrected text:", "Here is the corrected text:",
            "Here's the translation:", "Here is the translation:",
            "Rewrite:", "Translation:", "Corrected:", "Output:",
            "Voici :", "Voici:", "Voici la traduction :", "Voici le message :",
        )
        for (p in inlinePrefixes) {
            if (s.startsWith(p, ignoreCase = true)) {
                s = s.substring(p.length).trim()
            }
        }

        // Strip wrapping quotes
        val quotePairs = listOf('"' to '"', '\'' to '\'', '\u201C' to '\u201D', '\u00AB' to '\u00BB')
        for ((open, close) in quotePairs) {
            if (s.length >= 2 && s.first() == open && s.last() == close) {
                s = s.substring(1, s.length - 1).trim()
            }
        }

        return s
    }

    /**
     * Validate AI result: if the output looks like it just appended random chars
     * to the input (e.g. "hello" → "hello8"), fall back to rule-based processing.
     */
    private fun validateResult(input: String, output: String): String {
        val trimmedInput = input.trim()
        val trimmedOutput = output.trim()

        // If output is empty or identical, return input
        if (trimmedOutput.isBlank()) return trimmedInput
        if (trimmedOutput == trimmedInput) return trimmedOutput

        // Check if output is just input + appended garbage (1-3 random chars)
        if (trimmedOutput.startsWith(trimmedInput) && trimmedOutput.length <= trimmedInput.length + 3) {
            val appended = trimmedOutput.removePrefix(trimmedInput)
            // If appended chars are just digits or random symbols (not punctuation we'd add), it's corrupted
            if (appended.isNotEmpty() && appended.all { it.isDigit() || it in "~`@#\$%^&*_=+|\\<>" }) {
                Log.w(TAG, "⚠️ Corrupted AI result detected: appended='$appended', falling back to rule-based")
                return autoCorrect(trimmedInput)
            }
        }

        // Check if output is nonsensically short compared to input
        if (trimmedOutput.length < trimmedInput.length / 3 && trimmedInput.length > 10) {
            Log.w(TAG, "⚠️ Suspicious AI result: output too short (${trimmedOutput.length} vs ${trimmedInput.length})")
            return autoCorrect(trimmedInput)
        }

        return trimmedOutput
    }

    // =========================================================================
    // ML KIT GenAI: PROOFREADING API (Gemini Nano on-device)
    // Equivalent to iOS: FoundationModels LanguageModelSession for correction
    // =========================================================================

    private suspend fun aiCorrect(text: String): String = withContext(Dispatchers.IO) {
        try {
            val lang = detectLanguage(text)
            val proofreaderLang = mapToProofreaderLanguage(lang)

            val options = com.google.mlkit.genai.proofreading.ProofreaderOptions.builder(context)
                .setInputType(com.google.mlkit.genai.proofreading.ProofreaderOptions.InputType.KEYBOARD)
                .setLanguage(proofreaderLang)
                .build()

            val proofreader = com.google.mlkit.genai.proofreading.Proofreading.getClient(options)

            val status = proofreader.checkFeatureStatus().get()
            if (status != 3) { // not AVAILABLE
                proofreader.close()
                Log.d(TAG, "Proofreading not available for $lang (status=$status), using prompt fallback")
                return@withContext if (isPromptAvailable) aiCorrectViaPrompt(text) else autoCorrect(text)
            }

            val request = com.google.mlkit.genai.proofreading.ProofreadingRequest.builder(text).build()
            val result = proofreader.runInference(request).get()
            proofreader.close()

            val corrected = result.results.firstOrNull()?.text ?: text
            Log.d(TAG, "AI Proofread ($lang): len=${text.length} -> len=${corrected.length}")
            corrected
        } catch (e: Exception) {
            Log.w(TAG, "AI proofreading failed: ${e.message}, falling back to rules")
            autoCorrect(text)
        }
    }

    private suspend fun aiCorrectViaPrompt(text: String): String = withContext(Dispatchers.IO) {
        try {
            val lang = detectLanguage(text)
            val langName = languageName(lang)
            val prompt = "Correct the spelling and grammar of this $langName text. Return ONLY the corrected text, nothing else:\n$text"
            val response = generativeModel!!.generateContent(prompt)
            val result = sanitizeLLMOutput(response.candidates.firstOrNull()?.text).takeIf { it.isNotEmpty() } ?: text
            Log.d(TAG, "AI Correct via Prompt ($lang): len=${text.length} -> len=${result.length}")
            result
        } catch (e: Exception) {
            Log.w(TAG, "AI correct via prompt failed: ${e.message}")
            autoCorrect(text)
        }
    }

    // =========================================================================
    // ML KIT GenAI: REWRITING API (Gemini Nano on-device)
    // Equivalent to iOS: FoundationModels LanguageModelSession for improvement
    // =========================================================================

    private suspend fun aiImprove(text: String, style: ImprovementStyle): String = withContext(Dispatchers.IO) {
        try {
            val outputType = mapToRewriterOutputType(style)
            val options = com.google.mlkit.genai.rewriting.RewriterOptions.builder(context)
                .setOutputType(outputType)
                .setLanguage(com.google.mlkit.genai.rewriting.RewriterOptions.Language.ENGLISH)
                .build()

            val rewriter = com.google.mlkit.genai.rewriting.Rewriting.getClient(options)

            val status = rewriter.checkFeatureStatus().get()
            if (status != 3) { // not AVAILABLE
                rewriter.close()
                Log.d(TAG, "Rewriting not available (status=$status), using prompt fallback")
                return@withContext if (isPromptAvailable) aiImproveViaPrompt(text, style) else improveMessageRuleBased(text, style)
            }

            val request = com.google.mlkit.genai.rewriting.RewritingRequest.builder(text).build()
            val result = rewriter.runInference(request).get()
            rewriter.close()

            val improved = result.results.firstOrNull()?.text ?: text
            Log.d(TAG, "AI Rewrite (${style.displayName}): len=${text.length} -> len=${improved.length}")
            improved
        } catch (e: Exception) {
            Log.w(TAG, "AI rewriting failed: ${e.message}, falling back to rules")
            improveMessageRuleBased(text, style)
        }
    }

    private suspend fun aiImproveViaPrompt(text: String, style: ImprovementStyle, conversationContext: String = ""): String = withContext(Dispatchers.IO) {
        try {
            val styleDesc = getStyleDescription(style)
            val lang = detectLanguage(text)
            val langName = languageName(lang)

            val contextBlock = if (conversationContext.isNotEmpty()) {
                "\n\nConversation context (last messages):\n$conversationContext\n"
            } else ""

            val prompt = """Rewrite this $langName message in a ${style.displayName.lowercase()} tone.
$styleDesc
${contextBlock}Return ONLY the rewritten text in $langName, nothing else:
$text"""
            val response = generativeModel!!.generateContent(prompt)
            val result = sanitizeLLMOutput(response.candidates.firstOrNull()?.text).takeIf { it.isNotEmpty() } ?: text
            Log.d(TAG, "AI Improve via Prompt (${style.displayName}): len=${text.length} -> len=${result.length}")
            result
        } catch (e: Exception) {
            Log.w(TAG, "AI improve via prompt failed: ${e.message}")
            improveMessageRuleBased(text, style)
        }
    }

    // =========================================================================
    // ML KIT GenAI: PROMPT API (Gemini Nano on-device)
    // Equivalent to iOS: FoundationModels LanguageModelSession for Q&A
    // =========================================================================

    private suspend fun aiAsk(question: String): String = withContext(Dispatchers.IO) {
        try {
            val prompt = "You are a helpful assistant in a messaging app. Answer concisely:\n$question"
            val response = generativeModel!!.generateContent(prompt)
            val result = sanitizeLLMOutput(response.candidates.firstOrNull()?.text).takeIf { it.isNotEmpty() } ?: "Could not generate a response."
            Log.d(TAG, "AI Ask: len=${question.length} -> len=${result.length}")
            result
        } catch (e: Exception) {
            Log.w(TAG, "AI ask failed: ${e.message}")
            "AI not available on this device. Rule-based processing is active."
        }
    }

    private suspend fun aiTranslateViaPrompt(text: String, targetLanguage: String): String? = withContext(Dispatchers.IO) {
        try {
            val targetName = languageName(targetLanguage)
            val prompt = "Translate the following text into $targetName. Return ONLY the translated text, nothing else:\n$text"
            val response = generativeModel!!.generateContent(prompt)
            val result = sanitizeLLMOutput(response.candidates.firstOrNull()?.text).takeIf { it.isNotEmpty() }
            if (result != null) {
                Log.d(TAG, "AI Translate to $targetName: len=${text.length} -> len=${result.length}")
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "AI translate via prompt failed: ${e.message}")
            null
        }
    }

    // =========================================================================
    // ML KIT TRANSLATION (on-device, 59 languages, ~30MB per model)
    // =========================================================================

    private suspend fun mlKitTranslate(text: String, targetLanguage: String): String? =
        when (val outcome = mlKitTranslateDetailed(text, targetLanguage)) {
            is TranslationOutcome.Translated -> outcome.text
            is TranslationOutcome.AlreadyInTargetLanguage -> outcome.text
            else -> null
        }

    /**
     * ML Kit translate with a pre-flight that separates the three honest failures:
     * unsupported pair, pack-missing-and-offline, and a genuine error.
     *
     * The pre-flight matters because `downloadModelIfNeeded` on a device with no pack and
     * no network hangs on a retrying Task until it eventually fails — which is exactly the
     * "spinner that never resolves" the audit called out.
     */
    private suspend fun mlKitTranslateDetailed(
        text: String,
        targetLanguage: String
    ): TranslationOutcome = withContext(Dispatchers.IO) {
        try {
            val sourceLang = detectLanguage(text)
            val sourceCode = mapToTranslateLanguage(sourceLang)
            val targetCode = mapToTranslateLanguage(targetLanguage)

            if (sourceCode != null && sourceCode == targetCode) {
                return@withContext TranslationOutcome.AlreadyInTargetLanguage(text)
            }

            val modelsPresent = sourceCode != null && targetCode != null &&
                areTranslateModelsDownloaded(sourceCode, targetCode)
            classifyTranslationPreflight(sourceCode, targetCode, modelsPresent, isNetworkAvailable())
                ?.let {
                    Log.w(TAG, "Translate pre-flight refused: $it ($sourceLang -> $targetLanguage)")
                    return@withContext it
                }
            // classifyTranslationPreflight guarantees both codes are non-null past here.
            sourceCode!!; targetCode!!

            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceCode)
                .setTargetLanguage(targetCode)
                .build()

            val translator = Translation.getClient(options)

            val conditions = DownloadConditions.Builder().build()
            com.google.android.gms.tasks.Tasks.await(
                translator.downloadModelIfNeeded(conditions)
            )

            val result = com.google.android.gms.tasks.Tasks.await(
                translator.translate(text)
            )

            translator.close()
            refreshDownloadedLanguages()

            Log.d(TAG, "Translated ($sourceLang->$targetLanguage): len=${text.length} -> len=${result.length}")
            if (result.isBlank()) TranslationOutcome.Failed("empty result")
            else TranslationOutcome.Translated(result)
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit translation failed: ${e.message}")
            TranslationOutcome.Failed(e.message)
        }
    }

    /** True when BOTH translate packs are already on disk (no download needed). */
    private fun areTranslateModelsDownloaded(sourceCode: String, targetCode: String): Boolean = try {
        val downloaded = com.google.android.gms.tasks.Tasks.await(
            modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
        ).map { it.language }.toSet()
        // English is bundled with every other pack, so ML Kit needs only the non-English side.
        val needed = setOf(sourceCode, targetCode) - TranslateLanguage.ENGLISH
        needed.all { it in downloaded }
    } catch (e: Exception) {
        Log.w(TAG, "Could not list downloaded translate models: ${e.message}")
        false
    }

    /**
     * Is there a network that could fetch a missing language pack?
     *
     * Used only to tell "you are offline and the pack isn't here yet" apart from a real
     * failure — never to gate the on-device tiers, which work with no network at all.
     */
    private fun isNetworkAvailable(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) {
        Log.w(TAG, "Connectivity check failed: ${e.message}")
        // Fail OPEN: if we cannot tell, let ML Kit try rather than refusing pre-emptively.
        true
    }

    private fun mapToTranslateLanguage(langCode: String): String? {
        val normalized = langCode.lowercase().split("-").first()
        return when (normalized) {
            "af" -> TranslateLanguage.AFRIKAANS
            "ar" -> TranslateLanguage.ARABIC
            "be" -> TranslateLanguage.BELARUSIAN
            "bg" -> TranslateLanguage.BULGARIAN
            "bn" -> TranslateLanguage.BENGALI
            "ca" -> TranslateLanguage.CATALAN
            "cs" -> TranslateLanguage.CZECH
            "cy" -> TranslateLanguage.WELSH
            "da" -> TranslateLanguage.DANISH
            "de" -> TranslateLanguage.GERMAN
            "el" -> TranslateLanguage.GREEK
            "en" -> TranslateLanguage.ENGLISH
            "eo" -> TranslateLanguage.ESPERANTO
            "es" -> TranslateLanguage.SPANISH
            "et" -> TranslateLanguage.ESTONIAN
            "fa" -> TranslateLanguage.PERSIAN
            "fi" -> TranslateLanguage.FINNISH
            "fr" -> TranslateLanguage.FRENCH
            "ga" -> TranslateLanguage.IRISH
            "gl" -> TranslateLanguage.GALICIAN
            "gu" -> TranslateLanguage.GUJARATI
            "he" -> TranslateLanguage.HEBREW
            "hi" -> TranslateLanguage.HINDI
            "hr" -> TranslateLanguage.CROATIAN
            "ht" -> TranslateLanguage.HAITIAN_CREOLE
            "hu" -> TranslateLanguage.HUNGARIAN
            "id" -> TranslateLanguage.INDONESIAN
            "is" -> TranslateLanguage.ICELANDIC
            "it" -> TranslateLanguage.ITALIAN
            "ja" -> TranslateLanguage.JAPANESE
            "ka" -> TranslateLanguage.GEORGIAN
            "kn" -> TranslateLanguage.KANNADA
            "ko" -> TranslateLanguage.KOREAN
            "lt" -> TranslateLanguage.LITHUANIAN
            "lv" -> TranslateLanguage.LATVIAN
            "mk" -> TranslateLanguage.MACEDONIAN
            "mr" -> TranslateLanguage.MARATHI
            "ms" -> TranslateLanguage.MALAY
            "mt" -> TranslateLanguage.MALTESE
            "nl" -> TranslateLanguage.DUTCH
            "no" -> TranslateLanguage.NORWEGIAN
            "pl" -> TranslateLanguage.POLISH
            "pt" -> TranslateLanguage.PORTUGUESE
            "ro" -> TranslateLanguage.ROMANIAN
            "ru" -> TranslateLanguage.RUSSIAN
            "sk" -> TranslateLanguage.SLOVAK
            "sl" -> TranslateLanguage.SLOVENIAN
            "sq" -> TranslateLanguage.ALBANIAN
            "sv" -> TranslateLanguage.SWEDISH
            "sw" -> TranslateLanguage.SWAHILI
            "ta" -> TranslateLanguage.TAMIL
            "te" -> TranslateLanguage.TELUGU
            "th" -> TranslateLanguage.THAI
            "tl" -> TranslateLanguage.TAGALOG
            "tr" -> TranslateLanguage.TURKISH
            "uk" -> TranslateLanguage.UKRAINIAN
            "ur" -> TranslateLanguage.URDU
            "vi" -> TranslateLanguage.VIETNAMESE
            "zh" -> TranslateLanguage.CHINESE
            else -> {
                Log.w(TAG, "No ML Kit mapping for language: $normalized")
                null
            }
        }
    }

    /**
     * Map detected language to ML Kit Proofreader language.
     * Supports: English, Japanese, French, German, Italian, Spanish, Korean.
     */
    private fun mapToProofreaderLanguage(langCode: String): Int {
        val normalized = langCode.lowercase().split("-").first()
        return when (normalized) {
            "en" -> com.google.mlkit.genai.proofreading.ProofreaderOptions.Language.ENGLISH
            "ja" -> com.google.mlkit.genai.proofreading.ProofreaderOptions.Language.JAPANESE
            "fr" -> com.google.mlkit.genai.proofreading.ProofreaderOptions.Language.FRENCH
            "de" -> com.google.mlkit.genai.proofreading.ProofreaderOptions.Language.GERMAN
            "it" -> com.google.mlkit.genai.proofreading.ProofreaderOptions.Language.ITALIAN
            "es" -> com.google.mlkit.genai.proofreading.ProofreaderOptions.Language.SPANISH
            "ko" -> com.google.mlkit.genai.proofreading.ProofreaderOptions.Language.KOREAN
            else -> com.google.mlkit.genai.proofreading.ProofreaderOptions.Language.ENGLISH
        }
    }

    /**
     * Map OSHI ImprovementStyle to ML Kit Rewriter OutputType.
     * ML Kit output types: ELABORATE, EMOJIFY, SHORTEN, FRIENDLY, PROFESSIONAL, REPHRASE
     */
    private fun mapToRewriterOutputType(style: ImprovementStyle): Int {
        return when (style) {
            ImprovementStyle.FORMAL -> com.google.mlkit.genai.rewriting.RewriterOptions.OutputType.PROFESSIONAL
            ImprovementStyle.CASUAL -> com.google.mlkit.genai.rewriting.RewriterOptions.OutputType.FRIENDLY
            ImprovementStyle.FRIENDLY -> com.google.mlkit.genai.rewriting.RewriterOptions.OutputType.FRIENDLY
            ImprovementStyle.PROFESSIONAL -> com.google.mlkit.genai.rewriting.RewriterOptions.OutputType.PROFESSIONAL
            ImprovementStyle.PLAYFUL -> com.google.mlkit.genai.rewriting.RewriterOptions.OutputType.EMOJIFY
            ImprovementStyle.PERSUASIVE -> com.google.mlkit.genai.rewriting.RewriterOptions.OutputType.ELABORATE
            // Dating tones: use REPHRASE as closest mapping (actual handling via Prompt API)
            ImprovementStyle.FLIRTY -> com.google.mlkit.genai.rewriting.RewriterOptions.OutputType.REPHRASE
            ImprovementStyle.SEDUCTIVE -> com.google.mlkit.genai.rewriting.RewriterOptions.OutputType.REPHRASE
            ImprovementStyle.INTRIGUED -> com.google.mlkit.genai.rewriting.RewriterOptions.OutputType.REPHRASE
        }
    }

    private fun languageName(langCode: String): String {
        return when (langCode.lowercase().split("-").first()) {
            "en" -> "English"; "fr" -> "French"; "de" -> "German"; "es" -> "Spanish"
            "it" -> "Italian"; "pt" -> "Portuguese"; "ru" -> "Russian"; "ja" -> "Japanese"
            "zh" -> "Chinese"; "ko" -> "Korean"; "ar" -> "Arabic"; "hi" -> "Hindi"
            "he" -> "Hebrew"; "fa" -> "Persian"; "pl" -> "Polish"; "uk" -> "Ukrainian"
            "nl" -> "Dutch"; "sv" -> "Swedish"; "da" -> "Danish"; "no" -> "Norwegian"
            "fi" -> "Finnish"; "tr" -> "Turkish"; "th" -> "Thai"; "vi" -> "Vietnamese"
            "id" -> "Indonesian"
            else -> Locale(langCode).displayLanguage.ifBlank { "the detected language" }
        }
    }

    fun getSupportedLanguages(): List<String> {
        return TranslateLanguage.getAllLanguages()
    }

    fun downloadLanguageModel(languageCode: String, onComplete: (Boolean) -> Unit = {}) {
        val mlLang = mapToTranslateLanguage(languageCode)
        if (mlLang == null) { onComplete(false); return }

        val model = TranslateRemoteModel.Builder(mlLang).build()
        val conditions = DownloadConditions.Builder().build()

        modelManager.download(model, conditions)
            .addOnSuccessListener {
                Log.d(TAG, "Downloaded translation model: $languageCode")
                refreshDownloadedLanguages()
                onComplete(true)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to download model $languageCode: ${e.message}")
                onComplete(false)
            }
    }

    fun deleteLanguageModel(languageCode: String, onComplete: (Boolean) -> Unit = {}) {
        val mlLang = mapToTranslateLanguage(languageCode)
        if (mlLang == null) { onComplete(false); return }

        val model = TranslateRemoteModel.Builder(mlLang).build()
        modelManager.deleteDownloadedModel(model)
            .addOnSuccessListener {
                Log.d(TAG, "Deleted translation model: $languageCode")
                refreshDownloadedLanguages()
                onComplete(true)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to delete model $languageCode: ${e.message}")
                onComplete(false)
            }
    }

    // =========================================================================
    // LANGUAGE DETECTION (ML Kit - offline)
    // =========================================================================

    suspend fun detectLanguage(text: String): String = withContext(Dispatchers.Default) {
        try {
            val identifier = languageIdentifier ?: return@withContext "und"
            val result = com.google.android.gms.tasks.Tasks.await(
                identifier.identifyLanguage(text)
            )
            _detectedLanguage.value = result
            result
        } catch (e: Exception) {
            Log.w(TAG, "Language detection failed: ${e.message}")
            "und"
        }
    }

    // =========================================================================
    // RULE-BASED FALLBACK (matches iOS < 26 behavior)
    // =========================================================================

    fun autoCorrect(text: String, language: String? = null): String {
        var result = text
        result = capitalizeFirstLetter(result)
        result = fixDoubleSpaces(result)
        result = ensurePunctuation(result)
        return result
    }

    fun improveMessageRuleBased(text: String, style: ImprovementStyle): String {
        var result = autoCorrect(text)

        result = when (style) {
            ImprovementStyle.FORMAL -> formalizeText(result)
            ImprovementStyle.CASUAL -> makeCasual(result)
            ImprovementStyle.FRIENDLY -> makeFriendly(result)
            ImprovementStyle.PROFESSIONAL -> makeProfessional(result)
            ImprovementStyle.PLAYFUL -> makePlayful(result)
            ImprovementStyle.PERSUASIVE -> makePersuasive(result)
            ImprovementStyle.FLIRTY -> makeFlirty(result)
            ImprovementStyle.SEDUCTIVE -> makeSeductive(result)
            ImprovementStyle.INTRIGUED -> makeIntrigued(result)
        }

        return result
    }

    fun analyzeSentiment(text: String): Pair<Sentiment, Double> {
        val lower = text.lowercase()
        val positiveWords = listOf("good", "great", "love", "happy", "wonderful", "excellent", "amazing", "fantastic", "thanks", "thank")
        val negativeWords = listOf("bad", "hate", "terrible", "awful", "horrible", "worst", "angry", "sad", "disappointed", "annoying")

        val posCount = positiveWords.count { lower.contains(it) }
        val negCount = negativeWords.count { lower.contains(it) }

        return when {
            posCount > negCount -> Sentiment.POSITIVE to (posCount.toDouble() / (posCount + negCount + 1))
            negCount > posCount -> Sentiment.NEGATIVE to (negCount.toDouble() / (posCount + negCount + 1))
            else -> Sentiment.NEUTRAL to 0.5
        }
    }

    // =========================================================================
    // TEXT UTILITIES (private, matching iOS)
    // =========================================================================

    private fun capitalizeFirstLetter(text: String): String {
        if (text.isEmpty()) return text
        return text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun fixDoubleSpaces(text: String): String {
        return text.replace(Regex("\\s{2,}"), " ")
    }

    private fun ensurePunctuation(text: String): String {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return text
        val lastChar = trimmed.last()
        return if (lastChar.isLetterOrDigit()) "$trimmed." else text
    }

    private val contractionsToFormal = mapOf(
        "i'm" to "I am", "i've" to "I have", "i'll" to "I will", "i'd" to "I would",
        "you're" to "you are", "you've" to "you have", "you'll" to "you will", "you'd" to "you would",
        "he's" to "he is", "she's" to "she is", "it's" to "it is",
        "we're" to "we are", "we've" to "we have", "we'll" to "we will", "we'd" to "we would",
        "they're" to "they are", "they've" to "they have", "they'll" to "they will", "they'd" to "they would",
        "that's" to "that is", "there's" to "there is", "here's" to "here is",
        "what's" to "what is", "who's" to "who is", "where's" to "where is",
        "can't" to "cannot", "won't" to "will not", "don't" to "do not", "doesn't" to "does not",
        "didn't" to "did not", "wasn't" to "was not", "weren't" to "were not",
        "isn't" to "is not", "aren't" to "are not", "hasn't" to "has not",
        "haven't" to "have not", "hadn't" to "had not", "couldn't" to "could not",
        "wouldn't" to "would not", "shouldn't" to "should not"
    )

    private val formalToCasual = mapOf(
        "I am" to "I'm", "I have" to "I've", "I will" to "I'll", "I would" to "I'd",
        "you are" to "you're", "you have" to "you've", "you will" to "you'll",
        "cannot" to "can't", "will not" to "won't", "do not" to "don't",
        "does not" to "doesn't", "did not" to "didn't", "is not" to "isn't",
        "are not" to "aren't", "was not" to "wasn't"
    )

    private fun formalizeText(text: String): String {
        var result = text
        for ((contraction, expanded) in contractionsToFormal) {
            result = result.replace(Regex("(?i)\\b${Regex.escape(contraction)}\\b"), expanded)
        }
        result = capitalizeFirstLetter(result)
        result = ensurePunctuation(result)
        return result
    }

    private fun makeCasual(text: String): String {
        var result = text
        for ((formal, casual) in formalToCasual) {
            result = result.replace(formal, casual, ignoreCase = true)
        }
        if (result.endsWith(".") && !result.endsWith("...")) {
            result = result.dropLast(1)
        }
        return result
    }

    private fun makeFriendly(text: String): String {
        var result = autoCorrect(text)
        if (!result.lowercase().startsWith("hey") && !result.lowercase().startsWith("hi")) {
            val greetings = listOf("Hey! ", "Hi there! ", "Hello! ")
            result = greetings.random() + result.replaceFirstChar { it.lowercase() }
        }
        return result
    }

    private fun makeProfessional(text: String): String {
        var result = formalizeText(text)
        val sentences = result.split(". ").map { it.trim() }.filter { it.isNotEmpty() }
        result = sentences.joinToString(". ") { capitalizeFirstLetter(it) }
        if (!result.endsWith(".")) result += "."
        return result
    }

    private fun makePlayful(text: String): String {
        var result = makeCasual(text)
        if (result.endsWith(".")) {
            result = result.dropLast(1) + "!"
        } else if (!result.endsWith("!") && !result.endsWith("?")) {
            result += "!"
        }
        return result
    }

    private fun makePersuasive(text: String): String {
        var result = makeProfessional(text)
        result = result.replace("I think ", "I am confident that ", ignoreCase = true)
        result = result.replace("maybe ", "certainly ", ignoreCase = true)
        result = result.replace("perhaps ", "undoubtedly ", ignoreCase = true)
        result = result.replace("might ", "will ", ignoreCase = true)
        return result
    }

    private fun makeFlirty(text: String): String {
        var result = autoCorrect(text)
        if (result.endsWith(".")) result = result.dropLast(1) + " ;)"
        else if (!result.endsWith(";)") && !result.endsWith("😉")) result += " ;)"
        return result
    }

    private fun makeSeductive(text: String): String {
        var result = autoCorrect(text)
        result = result.replace("like", "adore", ignoreCase = true)
        result = result.replace("nice", "irresistible", ignoreCase = true)
        if (result.endsWith(".")) result = result.dropLast(1) + "..."
        return result
    }

    private fun makeIntrigued(text: String): String {
        var result = autoCorrect(text)
        if (!result.endsWith("?") && !result.endsWith("...")) {
            result = if (result.endsWith(".")) result.dropLast(1) + "..." else "$result..."
        }
        return result
    }

    // =========================================================================
    // STYLE DESCRIPTIONS (rich prompt instructions per tone)
    // =========================================================================

    private fun getStyleDescription(style: ImprovementStyle): String {
        return when (style) {
            ImprovementStyle.FORMAL -> "Use formal language, no contractions, proper grammar."
            ImprovementStyle.PROFESSIONAL -> "Use business-appropriate language, clear and concise."
            ImprovementStyle.PERSUASIVE -> "Use confident, convincing language. Replace hedging words with assertive ones."
            ImprovementStyle.CASUAL -> "Use relaxed, everyday language with contractions."
            ImprovementStyle.FRIENDLY -> "Use warm, approachable language. Add a friendly greeting if appropriate."
            ImprovementStyle.PLAYFUL -> "Use fun, energetic language with enthusiasm."
            ImprovementStyle.FLIRTY -> "Use playful, charming language with subtle hints of romantic interest. Add wit and light teasing."
            ImprovementStyle.SEDUCTIVE -> "Use alluring, confident language that builds tension and intrigue. Be bold but tasteful."
            ImprovementStyle.INTRIGUED -> "Show genuine curiosity and fascination. Ask engaging follow-up questions. Express interest."
        }
    }

    // =========================================================================
    // TONE SUGGESTION (rule-based sentiment analysis → recommended tone)
    // =========================================================================

    fun suggestTone(message: String): ImprovementStyle? {
        val (sentiment, _) = analyzeSentiment(message)
        val lower = message.lowercase()

        // Detect flirty/dating context
        val flirtyWords = listOf("cute", "beautiful", "handsome", "date", "dinner", "drink", "tonight", "miss you", "thinking of you")
        val isFlirtyContext = flirtyWords.any { lower.contains(it) }
        if (isFlirtyContext) return ImprovementStyle.FLIRTY

        // Detect question / curiosity
        if (message.trimEnd().endsWith("?") || lower.contains("what do you think") || lower.contains("tell me")) {
            return ImprovementStyle.INTRIGUED
        }

        return when (sentiment) {
            Sentiment.POSITIVE -> ImprovementStyle.FRIENDLY
            Sentiment.NEGATIVE -> ImprovementStyle.PROFESSIONAL
            Sentiment.NEUTRAL -> null
        }
    }

    // =========================================================================
    // SMART REPLIES (3 context-aware reply suggestions)
    // =========================================================================

    suspend fun generateSmartReplies(lastMessage: String, conversationContext: String = ""): List<String> = withContext(Dispatchers.Default) {
        _isProcessing.value = true
        try {
            if (isPromptAvailable) {
                aiGenerateSmartReplies(lastMessage, conversationContext)
            } else if (isLocalLLMAvailable) {
                localLLMSmartReplies(lastMessage, conversationContext)
            } else {
                ruleBasedSmartReplies(lastMessage)
            }
        } finally {
            _isProcessing.value = false
        }
    }

    private suspend fun localLLMSmartReplies(lastMessage: String, conversationContext: String): List<String> {
        return try {
            // Detect from the whole context so a short last message ("ok") doesn't
            // misclassify the conversation language. Fall back to device locale
            // when ML Kit can't decide ("und") — see aiGenerateSmartReplies for
            // the same logic.
            val sample = (conversationContext + "\n" + lastMessage).trim()
            val rawLang = detectLanguage(sample.ifEmpty { lastMessage })
            val effectiveLang = if (rawLang.isBlank() || rawLang == "und") {
                java.util.Locale.getDefault().language.ifBlank { "en" }
            } else rawLang
            val langName = languageName(effectiveLang)
            val contextBlock = if (conversationContext.isNotEmpty()) "\nConversation context:\n$conversationContext\n" else ""
            val prompt = """Suggest 3 short reply options for this message. Reply in $langName. Each reply must REACT to what was said specifically — no generic "Nice!", "Cool!", "OK". Show personality.${contextBlock}
Message: "$lastMessage"
Return ONLY 3 lines in $langName, one reply per line, no numbering:"""
            val result = localLLMGenerate(prompt)
            val replies = result.lines()
                .map { it.trim().removePrefix("- ").removePrefix("1.").removePrefix("2.").removePrefix("3.").trim() }
                .filter { it.isNotBlank() && it.length > 2 }
                .take(3)
            if (replies.size >= 2) replies else ruleBasedSmartReplies(lastMessage)
        } catch (e: Exception) {
            Log.w(TAG, "Local LLM smart replies failed: ${e.message}")
            ruleBasedSmartReplies(lastMessage)
        }
    }

    private suspend fun aiGenerateSmartReplies(lastMessage: String, conversationContext: String): List<String> = withContext(Dispatchers.IO) {
        try {
            // Detect language from the whole conversation, not just the last message —
            // a one-word reply ("ok") in an English chat shouldn't switch the suggestions to French.
            // ML Kit returns "und" when it can't decide on a very short or mixed-language
            // input; in that case fall back to the device's UI language so the LLM
            // doesn't end up generating English replies for a French conversation.
            val sample = (conversationContext + "\n" + lastMessage).trim()
            val rawLang = detectLanguage(sample.ifEmpty { lastMessage })
            val effectiveLang = if (rawLang.isBlank() || rawLang == "und") {
                java.util.Locale.getDefault().language.ifBlank { "en" }
            } else rawLang
            val langName = languageName(effectiveLang)
            val contextBlock = if (conversationContext.isNotEmpty()) {
                """
                FULL CONVERSATION (use this to understand the relationship, tone, and ongoing topics):
                ---
                ${conversationContext.take(800)}
                ---
                Your replies MUST feel like natural continuations of THIS specific conversation.
                Reference things that were discussed. Match the energy and register of the conversation.
                """.trimIndent()
            } else ""

            // Mirrors the iOS prompt — strong anti-cliché guard, language-specific
            // examples of "lazy" replies that the model should avoid. Without these
            // bumpers the model defaults to "Cool!" / "Sounds good!" / "Tell me more"
            // regardless of what was actually said.
            val prompt = """
                You are an emotionally intelligent messaging ghostwriter. You write smart reply suggestions that sound like the user genuinely wrote them — not a robot.

                $contextBlock

                Generate exactly 3 reply options in $langName.

                WHAT MAKES A REPLY SMART — READ THIS:
                - It REACTS to what they SPECIFICALLY said. Not a generic reaction.
                - It sounds like a REAL PERSON texting — with personality, warmth, and natural language patterns.
                - It uses the natural texting style of $langName speakers (not textbook language).

                BANNED REPLIES (if you generate any of these, you have FAILED):
                "Nice!", "Cool!", "Sounds good!", "That's great!", "Haha", "Ok", "I see", "Interesting",
                "Genial!", "Super!", "D'accord", "Bien", "Toll!", "すごい!", "좋아!", "Va bene!", "¡Genial!"
                These are LAZY. The user could type these without AI help.

                THE 3 REPLIES — each must be CREATIVE and SPECIFIC:
                1. WARM + SPECIFIC REACTION: React to THE EXACT THING they said with genuine emotion.
                2. WITTY + PERSONALITY: Show humor, cleverness, or a playful angle.
                3. CURIOUS + DEEP QUESTION: Ask something that makes them want to tell the FULL story.

                FORMAT: One reply per line. No numbering. No quotes. No explanation.
                Each reply: 5-25 words. Natural $langName texting style.

                Message to reply to: "$lastMessage"
                """.trimIndent()

            val response = generativeModel!!.generateContent(prompt)
            val result = sanitizeLLMOutput(response.candidates.firstOrNull()?.text).takeIf { it.isNotEmpty() } ?: ""
            val replies = result.lines()
                .map { it.trim().removePrefix("- ").removePrefix("1.").removePrefix("2.").removePrefix("3.").trim() }
                .filter { it.isNotBlank() && it.length > 2 }
                .take(3)
            Log.d(TAG, "AI Smart Replies (${replies.size}, lang=$effectiveLang)")
            if (replies.size >= 2) replies else ruleBasedSmartReplies(lastMessage)
        } catch (e: Exception) {
            Log.w(TAG, "AI smart replies failed: ${e.message}")
            ruleBasedSmartReplies(lastMessage)
        }
    }

    private fun ruleBasedSmartReplies(message: String): List<String> {
        val lang = _detectedLanguage.value.lowercase().split("-").first()
        val lower = message.lowercase()
        val isQuestion = message.trimEnd().endsWith("?")

        return when (lang) {
            "fr" -> if (isQuestion) {
                listOf("Oui, bien sur !", "Bonne question, laisse-moi reflechir", "Et toi, qu'en penses-tu ?")
            } else {
                listOf("Super, merci !", "C'est interessant !", "Dis-m'en plus !")
            }
            "de" -> if (isQuestion) {
                listOf("Ja, klar!", "Gute Frage, lass mich nachdenken", "Was meinst du dazu?")
            } else {
                listOf("Super, danke!", "Das ist interessant!", "Erzahl mir mehr!")
            }
            "es" -> if (isQuestion) {
                listOf("Si, por supuesto!", "Buena pregunta, dejame pensar", "Y tu, que piensas?")
            } else {
                listOf("Genial, gracias!", "Que interesante!", "Cuentame mas!")
            }
            else -> if (isQuestion) {
                listOf("Yes, absolutely!", "Good question, let me think about it", "What do you think?")
            } else {
                listOf("That's great, thanks!", "That's really interesting!", "Tell me more!")
            }
        }
    }

    // =========================================================================
    // CONVERSATION GENERATOR (4 modes: icebreaker, relaunch, flirty, friendly)
    // =========================================================================

    suspend fun generateConversationSuggestions(
        mode: ConversationGeneratorMode,
        conversationContext: String = "",
        contactName: String = ""
    ): List<String> = withContext(Dispatchers.Default) {
        _isProcessing.value = true
        try {
            if (isPromptAvailable) {
                aiGenerateConversation(mode, conversationContext, contactName)
            } else if (isLocalLLMAvailable) {
                localLLMConversation(mode, conversationContext, contactName)
            } else {
                ruleBasedConversation(mode, contactName)
            }
        } finally {
            _isProcessing.value = false
        }
    }

    private fun buildConversationPrompt(
        mode: ConversationGeneratorMode,
        conversationContext: String,
        contactName: String
    ): String {
        val contextBlock = if (conversationContext.isNotEmpty()) {
            "\nRecent conversation for context:\n$conversationContext\n"
        } else ""
        val nameBlock = if (contactName.isNotEmpty()) " to someone named $contactName" else ""

        val modeInstructions = when (mode) {
            ConversationGeneratorMode.ICEBREAKER -> """Mode: ICEBREAKER — Starting a brand new conversation with someone.
Generate 3 creative, attention-grabbing first messages$nameBlock. These should make the person WANT to reply.

Guidelines:
- NO generic openers like "Hey" or "How are you?" — those get ignored
- Use one of these approaches per suggestion: a witty observation or hot take, a fun hypothetical question ("Would you rather..."), or a specific shared-interest hook
- Be confident and interesting, show personality
- Keep it light and intriguing — make them curious about you
- 1-2 sentences max per suggestion

Examples of the QUALITY level expected (don't copy these, create original ones):
- "I have a theory that people who like pineapple on pizza are secretly the most adventurous people. Where do you stand?"
- "Okay real question — if you could master any skill overnight, what would it be and why?"
- "I just saw something that reminded me of your profile and now I'm genuinely curious — are you a morning person or a night owl?""""

            ConversationGeneratorMode.RELAUNCH -> """Mode: RELAUNCH — Re-engaging a conversation that went quiet for days.
Generate 3 natural messages$nameBlock to restart the conversation without seeming desperate.

Guidelines:
- Reference something interesting (a shared topic, current event, something you "just saw")
- Sound spontaneous, like you naturally thought of them — NOT like you've been waiting to text
- Avoid guilt-tripping ("you never replied") or being passive-aggressive
- Be warm and genuine, give them an easy hook to respond to
- 1-2 sentences max per suggestion

Examples of the QUALITY level expected (don't copy these, create original ones):
- "Hey! I just tried that restaurant you mentioned and okay wow, you were right"
- "Random thought but I just saw the funniest meme and you were literally the first person I wanted to send it to"
- "So I finally watched that show you recommended... we need to talk about that plot twist""""

            ConversationGeneratorMode.FLIRTY -> """Mode: FLIRTY — Playful, charming messages with romantic undertones.
Generate 3 flirty messages$nameBlock that are confident and fun without being creepy.

Guidelines:
- Be playful and teasing, not overtly sexual or aggressive
- Use subtle compliments that feel genuine, not rehearsed pickup lines
- Show confidence and wit — humor is attractive
- Create a sense of connection and mutual interest
- 1-2 sentences max per suggestion

Examples of the QUALITY level expected (don't copy these, create original ones):
- "I was trying to think of something clever to say but honestly you're kind of distracting"
- "Is it weird that talking to you is the highlight of my day? Because I'm not even sorry about it"
- "You have this annoying habit of making me smile at my phone in public""""

            ConversationGeneratorMode.FRIENDLY -> """Mode: FRIENDLY — Warm, caring messages showing genuine interest in their life.
Generate 3 friendly, warm messages$nameBlock that show you care.

Guidelines:
- Be genuinely interested in their wellbeing — not just small talk
- Share a small personal update or ask about something specific
- Create space for a real conversation, not just "hey how are you"
- Be positive and uplifting
- 1-2 sentences max per suggestion

Examples of the QUALITY level expected (don't copy these, create original ones):
- "I just had the best coffee of my life and it made me wonder — what's your go-to order?"
- "Hey! How did that thing you were nervous about go? I've been thinking about it"
- "I just discovered this amazing podcast and I feel like you'd love it — are you into true crime stuff?""""
        }

        return """You are a social messaging expert. $modeInstructions
$contextBlock
STRICT RULES:
- Return EXACTLY 3 suggestions, one per line
- No numbering, no bullets, no quotes, no labels
- Each must sound natural, like a real person texting
- Vary the tone and approach across the 3 suggestions
- Be original — never use cliches or generic phrases"""
    }

    private suspend fun localLLMConversation(
        mode: ConversationGeneratorMode,
        conversationContext: String,
        contactName: String
    ): List<String> {
        return try {
            val prompt = buildConversationPrompt(mode, conversationContext, contactName)
            val result = localLLMGenerate(prompt)
            val suggestions = result.lines()
                .map { it.trim().removePrefix("- ").removePrefix("1.").removePrefix("2.").removePrefix("3.").trim() }
                .filter { it.isNotBlank() && it.length > 5 && !it.startsWith("Mode:") && !it.startsWith("STRICT") }
                .take(3)
            if (suggestions.size >= 2) suggestions else ruleBasedConversation(mode, contactName)
        } catch (e: Exception) {
            Log.w(TAG, "Local LLM conversation suggestions failed: ${e.message}")
            ruleBasedConversation(mode, contactName)
        }
    }

    private suspend fun aiGenerateConversation(
        mode: ConversationGeneratorMode,
        conversationContext: String,
        contactName: String
    ): List<String> = withContext(Dispatchers.IO) {
        try {
            val prompt = buildConversationPrompt(mode, conversationContext, contactName)

            val response = generativeModel!!.generateContent(prompt)
            val result = sanitizeLLMOutput(response.candidates.firstOrNull()?.text).takeIf { it.isNotEmpty() } ?: ""
            val suggestions = result.lines()
                .map { it.trim().removePrefix("- ").removePrefix("1.").removePrefix("2.").removePrefix("3.").trim() }
                .filter { it.isNotBlank() && it.length > 5 && !it.startsWith("Mode:") && !it.startsWith("STRICT") }
                .take(3)
            Log.d(TAG, "AI Conversation (${mode.displayName}, ${suggestions.size})")
            if (suggestions.size >= 2) suggestions else ruleBasedConversation(mode, contactName)
        } catch (e: Exception) {
            Log.w(TAG, "AI conversation generator failed: ${e.message}")
            ruleBasedConversation(mode, contactName)
        }
    }

    private fun ruleBasedConversation(mode: ConversationGeneratorMode, contactName: String): List<String> {
        val name = contactName.ifEmpty { "there" }
        return when (mode) {
            ConversationGeneratorMode.ICEBREAKER -> listOf(
                "I have a theory that people who like pineapple on pizza are secretly the most adventurous. Where do you stand, $name?",
                "Okay real question — if you could master any skill overnight, what would it be?",
                "Hot take: the best conversations start with random questions. So what's the last thing that genuinely made you laugh?"
            )
            ConversationGeneratorMode.RELAUNCH -> listOf(
                "Hey $name! I just saw something that instantly made me think of you and now I'm curious how you've been",
                "So random but I was just telling someone about that thing you said and it still cracks me up. How are you doing?",
                "I just tried something new and you were literally the first person I wanted to tell about it. What have you been up to?"
            )
            ConversationGeneratorMode.FLIRTY -> listOf(
                "I was trying to think of something clever to say but honestly you're kind of distracting",
                "Is it weird that talking to you is the highlight of my day? Because I'm not even sorry about it",
                "You have this annoying habit of making me smile at my phone in public and people are starting to notice"
            )
            ConversationGeneratorMode.FRIENDLY -> listOf(
                "Hey $name! I just had the best coffee of my life and it made me wonder — what's your go-to order?",
                "I just discovered this amazing podcast and I feel like you'd love it. Are you into any good ones lately?",
                "Hey! How did that thing you were telling me about go? I've been thinking about it"
            )
        }
    }

    // =========================================================================
    // THINKING OF YOU (templated + AI generator)
    // Mirrors iOS processGenerateThinkingOfYou
    // =========================================================================

    /**
     * Generate a "Thinking of you" message. Uses Gemini Nano when available,
     * then local LLM, then a template list keyed by detected conversation language
     * and selected tone.
     */
    suspend fun generateThinkingOfYou(
        recipientName: String = "",
        tone: ImprovementStyle = ImprovementStyle.FRIENDLY,
        conversationContext: String = ""
    ): String = withContext(Dispatchers.Default) {
        _isProcessing.value = true
        try {
            val lang = if (conversationContext.isNotBlank()) {
                detectLanguage(conversationContext)
            } else {
                Locale.getDefault().language
            }
            if (isPromptAvailable) {
                aiGenerateThinkingOfYou(recipientName, tone, conversationContext, lang)
            } else if (isLocalLLMAvailable) {
                val toneDesc = thinkingToneDescription(tone)
                val ctxBlock = if (conversationContext.isNotBlank()) {
                    "\nConversation context (for personalization):\n${conversationContext.take(400)}\n"
                } else ""
                val targetName = recipientName.ifBlank { "them" }
                val prompt = """Write a short heartfelt "thinking of you" message to $targetName in a $toneDesc tone.${ctxBlock}
Rules:
- 1 to 3 sentences
- Sound like a real person texting, not a greeting card
- Write in ${languageName(lang)}
- Return ONLY the message, no quotes, no preface"""
                val out = sanitizeLLMOutput(localLLMGenerate(prompt))
                if (out.isNotBlank() && !out.contains("Native inference engine")) out
                else thinkingOfYouTemplate(recipientName, tone, lang)
            } else {
                thinkingOfYouTemplate(recipientName, tone, lang)
            }
        } finally {
            _isProcessing.value = false
        }
    }

    private suspend fun aiGenerateThinkingOfYou(
        recipientName: String,
        tone: ImprovementStyle,
        conversationContext: String,
        lang: String
    ): String = withContext(Dispatchers.IO) {
        try {
            val toneDesc = thinkingToneDescription(tone)
            val targetName = recipientName.ifBlank { "the person" }
            val ctxBlock = if (conversationContext.isNotBlank()) {
                "Recent conversation for personalization (reference something specific if it fits):\n${conversationContext.take(600)}\n"
            } else ""
            val langName = languageName(lang)

            val prompt = """You are a warm, thoughtful messaging assistant. Write a heartfelt "thinking of you" message for $targetName.
Relationship tone: $toneDesc.
$ctxBlock
Constraints:
- Open naturally — something like "I was thinking of you today..." adapted to the tone, NOT mechanical.
- 2 to 4 sentences. Genuine. Personal. Not a greeting card.
- Write ONLY in $langName. Do NOT translate or add other languages.
- Return ONLY the message — no quotes, no labels, no explanation."""

            val response = generativeModel!!.generateContent(prompt)
            val out = sanitizeLLMOutput(response.candidates.firstOrNull()?.text)
            if (out.isNotBlank()) {
                Log.d(TAG, "AI ThinkingOfYou ($lang/${tone.displayName}): len=${out.length}")
                out
            } else {
                thinkingOfYouTemplate(recipientName, tone, lang)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AI thinking-of-you failed: ${e.message}")
            thinkingOfYouTemplate(recipientName, tone, lang)
        }
    }

    private fun thinkingToneDescription(tone: ImprovementStyle): String = when (tone) {
        ImprovementStyle.FORMAL -> "polite, respectful, considerate"
        ImprovementStyle.PROFESSIONAL -> "polite, respectful, business-friendly"
        ImprovementStyle.PERSUASIVE -> "warm, confident, sincere"
        ImprovementStyle.CASUAL -> "relaxed, light, easygoing"
        ImprovementStyle.FRIENDLY -> "friendly, warm, sincere, casual"
        ImprovementStyle.PLAYFUL -> "cheerful, light, fun"
        ImprovementStyle.FLIRTY -> "playful, charming, with light romantic undertones"
        ImprovementStyle.SEDUCTIVE -> "romantic, warm, intimate, alluring"
        ImprovementStyle.INTRIGUED -> "curious, attentive, caring"
    }

    private fun thinkingOfYouTemplate(recipientName: String, tone: ImprovementStyle, lang: String): String {
        val name = recipientName.ifBlank { "" }
        val isRomantic = tone == ImprovementStyle.SEDUCTIVE || tone == ImprovementStyle.FLIRTY
        val isPlayful = tone == ImprovementStyle.PLAYFUL || tone == ImprovementStyle.CASUAL
        val isFormal = tone == ImprovementStyle.FORMAL || tone == ImprovementStyle.PROFESSIONAL
        val normalized = lang.lowercase().split("-").first()

        return when (normalized) {
            "fr" -> when {
                isRomantic -> "J'ai pensé à toi aujourd'hui... tu me manques plus que les mots ne peuvent le dire."
                isPlayful -> "Hey ${name.ifBlank { "toi" }} ! Je pensais à toi, faut qu'on se fasse un truc bientôt."
                isFormal -> "Je tenais à vous dire que j'ai pensé à vous aujourd'hui. J'espère que vous allez bien."
                else -> "Hey ${name.ifBlank { "" }}! Je pensais à toi aujourd'hui, j'espère que tout va bien.".trim()
            }
            "de" -> when {
                isRomantic -> "Ich habe heute an dich gedacht... Du fehlst mir mehr, als Worte sagen können."
                isPlayful -> "Hey ${name.ifBlank { "" }}, ich hab an dich gedacht! Wir sollten uns bald mal treffen.".trim()
                isFormal -> "Ich wollte Ihnen mitteilen, dass ich heute an Sie gedacht habe. Ich hoffe, es geht Ihnen gut."
                else -> "Hey ${name.ifBlank { "" }}! Ich habe heute an dich gedacht, ich hoffe, es geht dir gut.".trim()
            }
            "es" -> when {
                isRomantic -> "Hoy pensé en ti... te extraño más de lo que las palabras pueden decir."
                isPlayful -> "¡Ey ${name.ifBlank { "" }}, estaba pensando en ti! Deberíamos quedar pronto.".trim()
                isFormal -> "Quería hacerle saber que hoy pensé en usted. Espero que se encuentre bien."
                else -> "¡Hola ${name.ifBlank { "" }}! Hoy pensé en ti, espero que estés bien.".trim()
            }
            "it" -> when {
                isRomantic -> "Ho pensato a te oggi... mi manchi più di quanto le parole possano dire."
                isPlayful -> "Ehi ${name.ifBlank { "" }}, pensavo a te! Dovremmo vederci presto.".trim()
                isFormal -> "Volevo farLe sapere che ho pensato a Lei oggi. Spero che stia bene."
                else -> "Ehi ${name.ifBlank { "" }}! Ho pensato a te oggi, spero stia andando tutto bene.".trim()
            }
            "pt" -> when {
                isRomantic -> "Pensei em você hoje... sinto sua falta mais do que palavras podem dizer."
                isPlayful -> "E aí ${name.ifBlank { "" }}, tava pensando em você! A gente devia se ver logo.".trim()
                isFormal -> "Gostaria de informá-lo que pensei em você hoje. Espero que esteja bem."
                else -> "Ei ${name.ifBlank { "" }}! Pensei em você hoje, espero que esteja bem.".trim()
            }
            "ja" -> when {
                isRomantic -> "今日あなたのことを思っていました…言葉では言い表せないほど恋しいです。"
                isPlayful -> "ねえ${name}、今日あなたのことを思い出しました。今度遊びに行こうよ！"
                isFormal -> "本日あなたのことを思い出しました。お元気でいらっしゃいますように。"
                else -> "やあ${name}！今日あなたのことを思い出しました、元気にしていますか？"
            }
            "zh" -> when {
                isRomantic -> "今天我一直在想你……言语无法表达我有多想念你。"
                isPlayful -> "嘿${name}，刚刚想到你了！我们应该尽快聚一聚。"
                isFormal -> "今天我想到了您，希望您一切都好。"
                else -> "嘿${name}！今天我想到了你，希望你一切都好。"
            }
            "ru" -> when {
                isRomantic -> "Сегодня я думал о тебе... мне не хватает тебя больше, чем можно выразить словами."
                isPlayful -> "Привет${if (name.isNotBlank()) ", $name" else ""}! Думал о тебе — надо нам скоро пересечься."
                isFormal -> "Хотел сообщить, что сегодня вспоминал о Вас. Надеюсь, у Вас всё хорошо."
                else -> "Привет${if (name.isNotBlank()) ", $name" else ""}! Сегодня думал о тебе, надеюсь, у тебя всё хорошо."
            }
            "ar" -> when {
                isRomantic -> "كنت أفكر فيك اليوم… أفتقدك أكثر مما تستطيع الكلمات وصفه."
                isPlayful -> "هلا ${name}! كنت أفكر فيك، يجب أن نلتقي قريبًا."
                isFormal -> "أردت إعلامك بأنني فكرت بك اليوم. أتمنى أن تكون بخير."
                else -> "أهلًا ${name}! كنت أفكر فيك اليوم، أتمنى أن تكون بخير."
            }
            "hi" -> when {
                isRomantic -> "आज मैं तुम्हारे बारे में सोच रहा था… शब्दों में बताना मुश्किल है कि तुम कितने याद आते हो।"
                isPlayful -> "हे ${name}! मैं तुम्हारे बारे में सोच रहा था, जल्द मिलते हैं!"
                isFormal -> "मैं आपको बताना चाहता था कि आज मैंने आपके बारे में सोचा। आशा है आप ठीक होंगे।"
                else -> "हाय ${name}! आज मैं तुम्हारे बारे में सोच रहा था, आशा है तुम ठीक हो।"
            }
            else -> when {
                isRomantic -> "I was thinking about you today... I miss you more than words can say."
                isPlayful -> "Hey ${name.ifBlank { "" }}, I was thinking about you! We should hang out soon.".trim()
                isFormal -> "I wanted to let you know I was thinking of you today. I hope you are well."
                else -> "Hey ${name.ifBlank { "" }}! I was thinking about you today, hope you're doing well.".trim()
            }
        }.trim()
    }

    fun cleanup() {
        try {
            languageIdentifier?.close()
            generativeModel?.close()
        } catch (_: Exception) {}
    }
}
