package com.oshi.messenger.service

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LocalLLMManager — manages downloading and running the OSHI AI offline model
 * as a fallback for devices that don't support Gemini Nano.
 *
 * Three offline tiers branded as OSHI AI Pro / Standard / Lite (sizes ~2.4 GB / 1 GB
 * / 600 MB). Inference runs natively via `LlamaCpp` JNI (libllama_jni.so / libllama.so)
 * — see LlamaCpp.kt. 100% offline after download; no data sent anywhere.
 */
@Singleton
class LocalLLMManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class ModelConfig(
        val id: String,
        val name: String,
        val filename: String,
        val primaryUrl: String,
        val fallbackUrl: String,
        val sizeBytes: Long,
        val minRamMb: Long
    )

    companion object {
        private const val TAG = "LocalLLMManager"
        private const val MODEL_DIR = "models"

        private val MODELS = listOf(
            ModelConfig(
                id = "qwen3-4b",
                name = "OSHI AI Pro",
                filename = "Qwen3-4B-Q4_K_M.gguf",
                primaryUrl = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
                fallbackUrl = "https://oshi-messenger.com/files/models/Qwen3-4B-Q4_K_M.gguf",
                sizeBytes = 2_497_280_256L,
                minRamMb = 5120  // 5GB+ RAM (4B model fits in ~3GB, leaves room for OS)
            ),
            ModelConfig(
                id = "qwen3-1.7b-q4",
                name = "OSHI AI Standard",
                filename = "Qwen3-1.7B-Q4_K_M.gguf",
                primaryUrl = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
                fallbackUrl = "https://oshi-messenger.com/files/models/Qwen3-1.7B-Q4_K_M.gguf",
                sizeBytes = 1_107_409_472L,  // verified 1.107 GB on HF (unsloth Q4_K_M), ~60% of the old Q8_0
                minRamMb = 2560  // 2.5GB+ RAM is enough for Q4_K_M; leaves breathing room on budget devices
            ),
            ModelConfig(
                id = "qwen3-0.6b",
                name = "OSHI AI Lite",
                filename = "Qwen3-0.6B-Q8_0.gguf",
                primaryUrl = "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf",
                fallbackUrl = "https://oshi-messenger.com/files/models/Qwen3-0.6B-Q8_0.gguf",
                sizeBytes = 639_446_688L,
                minRamMb = 1024
            )
        )

        /** Select the best model for this device based on available RAM */
        fun bestModelForDevice(context: Context): ModelConfig {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val totalRamMb = memInfo.totalMem / (1024 * 1024)

            Log.d(TAG, "Device RAM: ${totalRamMb}MB")
            // Pick the largest model the device can handle
            return MODELS.firstOrNull { totalRamMb >= it.minRamMb } ?: MODELS.last()
        }
    }

    /** The model selected for this device based on RAM */
    val selectedModel: ModelConfig = bestModelForDevice(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isModelDownloaded = MutableStateFlow(false)
    val isModelDownloaded: StateFlow<Boolean> = _isModelDownloaded.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    /** Why the last download failed (null = no failure). Shown with a Retry button so a
     *  failed download is never silent. Cleared when a new download starts. */
    private val _downloadError = MutableStateFlow<LocalAIOffer.DownloadError?>(null)
    val downloadError: StateFlow<LocalAIOffer.DownloadError?> = _downloadError.asStateFlow()

    /** Streaming text — updated word-by-word during generation for live UI display */
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private var downloadJob: Job? = null

    private val modelDir: File
        get() = File(context.filesDir, MODEL_DIR)

    private val modelFile: File
        get() = File(modelDir, selectedModel.filename)

    init {
        checkModelExists()
        cleanupOldModels()
    }

    private fun checkModelExists() {
        _isModelDownloaded.value = modelFile.exists() && modelFile.length() > 0
        Log.d(TAG, "Model exists: ${_isModelDownloaded.value}, path: ${modelFile.absolutePath}")
    }

    /**
     * Remove any .gguf files (or stale .gguf.tmp files) in the models directory
     * that don't match the current selectedModel. Frees storage automatically
     * when the user switches to a different model tier.
     *
     * IMPORTANT: never delete `<selectedModel.filename>.tmp` — that's the
     * in-progress download for the model we're keeping.
     */
    private fun cleanupOldModels() {
        try {
            val dir = modelDir
            if (!dir.exists()) return

            val currentFilename = selectedModel.filename
            val currentTmpName  = "$currentFilename.tmp"

            val oldFiles = dir.listFiles()?.filter {
                val name = it.name
                val isCompletedOldModel = it.extension == "gguf" && name != currentFilename
                // Stale .tmp from a previous model: ends in ".gguf.tmp" and its
                // base name (".tmp" stripped) is NOT the current model file.
                val isStaleTmp = name.endsWith(".gguf.tmp") &&
                                 name.removeSuffix(".tmp") != currentFilename
                isCompletedOldModel || isStaleTmp
            } ?: return

            for (file in oldFiles) {
                val sizeMb = file.length() / 1_000_000
                val isStaleTmp = file.name.endsWith(".gguf.tmp")
                val kind = if (isStaleTmp) "stale .tmp" else "old model"
                if (file.delete()) {
                    Log.d(TAG, "Cleaned up $kind: ${file.name} (${sizeMb}MB freed)")
                } else {
                    Log.w(TAG, "Failed to remove $kind: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during model cleanup: ${e.message}")
        }
    }

    /**
     * Returns the size of free space available on the device's internal storage, in bytes.
     */
    fun getFreeSpaceBytes(): Long {
        return context.filesDir.usableSpace
    }

    /** Remove ChatML special tokens from generated text to prevent artifacts in UI */
    private fun stripChatMLTokens(text: String): String {
        val tokens = listOf(
            "<|im_start|>assistant", "<|im_start|>user", "<|im_start|>system",
            "<|im_start|>", "<|im_end|>", "<|im_end|",
            "<|endoftext|>", "<|end|>"
        )
        var result = text
        for (token in tokens) {
            result = result.replace(token, "")
        }
        return result
    }

    /** Strip Qwen3 chain-of-thought blocks (<think>…</think>) from output. */
    private fun stripThinkBlocks(text: String): String {
        var s = text
        while (true) {
            val open = s.indexOf("<think>")
            if (open < 0) break
            val close = s.indexOf("</think>", startIndex = open + 7)
            s = if (close >= 0) s.removeRange(open, close + 8) else s.substring(0, open)
        }
        return s
    }

    /** Strip leading filler lines small models sometimes emit (||, ---, ***, blank). */
    private fun stripLeadingNoise(text: String): String {
        val lines = text.split("\n").toMutableList()
        val junkChars = " \t|-*_.=~`>".toSet()
        while (lines.isNotEmpty()) {
            val first = lines.first().trim()
            if (first.isEmpty() || first.all { it in junkChars }) {
                lines.removeAt(0)
            } else break
        }
        return lines.joinToString("\n")
    }

    /** Drop a hallucinated leading greeting-then-question preamble ("bonjour je cherche…"). */
    private fun stripRolePreamble(text: String): String {
        val trimmed = text.trim()
        val lower = trimmed.lowercase()
        val greetings = listOf("bonjour", "bonsoir", "salut", "hello", "hi ", "hey ",
            "hola", "ciao", "hallo", "olá", "oi ")
        if (greetings.none { lower.startsWith(it) }) return trimmed
        val breakIdx = trimmed.indexOf("\n\n")
        if (breakIdx < 0) return trimmed
        val preamble = trimmed.substring(0, breakIdx)
        val rest = trimmed.substring(breakIdx + 2).trim()
        val fakeUserTurn = preamble.contains("?") ||
            preamble.contains("pouvez-vous", ignoreCase = true) ||
            preamble.contains("pouvez vous", ignoreCase = true) ||
            preamble.contains("je cherche", ignoreCase = true) ||
            preamble.contains("can you", ignoreCase = true) ||
            preamble.contains("could you", ignoreCase = true)
        return if (fakeUserTurn && rest.isNotEmpty()) rest else trimmed
    }

    /** True if the tail of `text` (length `window`) appears earlier — repetition loop. */
    private fun hasRepeatedTail(text: String, window: Int = 160): Boolean {
        if (text.length < window * 2) return false
        val tail = text.takeLast(window)
        val head = text.dropLast(window)
        return head.contains(tail)
    }

    /** Does the user's prompt explicitly ask for code? */
    private fun userAskedForCode(prompt: String): Boolean {
        val lower = prompt.lowercase()
        return listOf("code", "python", "javascript", "swift", "kotlin", "java",
            "script", "function", "algorithm", "snippet", "écris", "écrire",
            "implémente", "fonction", "algorithme").any { lower.contains(it) }
    }

    /** Strip hallucinated code blocks when the user didn't ask for code. */
    private fun stripHallucinatedCode(text: String): String {
        var s = text
        while (true) {
            val open = s.indexOf("```")
            if (open < 0) break
            val close = s.indexOf("```", startIndex = open + 3)
            s = if (close >= 0) s.removeRange(open, close + 3)
            else if (open == 0) "" else break
        }
        return stripLeadingNoise(s)
    }

    /** Heuristic: did the model return a runaway/corrupted reply we should not feed back? */
    private fun looksCorrupted(text: String): Boolean {
        if (text.length > 1500) {
            val fenceCount = text.split("```").size - 1
            if (fenceCount >= 4) return true
        }
        return hasRepeatedTail(text)
    }

    /**
     * Downloads the GGUF model from HuggingFace to app's internal storage.
     * Progress is reported via [downloadProgress] (0.0 to 1.0).
     */
    fun downloadModel() {
        if (_isDownloading.value) {
            Log.w(TAG, "Download already in progress")
            return
        }
        if (_isModelDownloaded.value) {
            Log.d(TAG, "Model already downloaded")
            return
        }

        downloadJob = scope.launch {
            _isDownloading.value = true
            _downloadProgress.value = 0f
            _downloadError.value = null

            try {
                modelDir.mkdirs()
                if (!LocalAIOffer.hasRoomFor(selectedModel.sizeBytes, getFreeSpaceBytes())) {
                    throw IOException("Not enough storage for ${selectedModel.filename}")
                }

                val tempFile = File(modelDir, "${selectedModel.filename}.tmp")

                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build()

                // Try primary server first, fallback to HuggingFace
                Log.d(TAG, "Starting download of ${selectedModel.name} (${String.format("%.1f", selectedModel.sizeBytes / 1_000_000_000.0)}GB)")

                // A network-level failure on the primary (DNS, TLS, blocked host, timeout)
                // must also fall back — previously only a non-2xx HTTP status did, so an
                // unreachable primary failed the whole download silently.
                val primary = try {
                    client.newCall(Request.Builder().url(selectedModel.primaryUrl).build()).execute()
                } catch (e: IOException) {
                    Log.w(TAG, "Primary server unreachable (${e.message}), trying fallback...")
                    null
                }
                var response = primary
                if (response == null || !response.isSuccessful) {
                    response?.let {
                        Log.w(TAG, "Primary server failed (${it.code}), trying fallback...")
                        it.close()
                    }
                    response = client.newCall(Request.Builder().url(selectedModel.fallbackUrl).build()).execute()
                }

                if (!response.isSuccessful) {
                    throw Exception("Download failed: HTTP ${response.code}")
                }

                val body = response.body ?: throw Exception("Empty response body")
                val contentLength = body.contentLength().takeIf { it > 0 } ?: selectedModel.sizeBytes

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Long = 0
                        var read: Int

                        while (input.read(buffer).also { read = it } != -1) {
                            if (!isActive) {
                                tempFile.delete()
                                return@launch
                            }
                            output.write(buffer, 0, read)
                            bytesRead += read
                            _downloadProgress.value = (bytesRead.toFloat() / contentLength).coerceIn(0f, 1f)
                        }
                    }
                }

                // Rename temp file to final
                if (tempFile.renameTo(modelFile)) {
                    _isModelDownloaded.value = true
                    _downloadProgress.value = 1f
                    Log.d(TAG, "Model download complete: ${modelFile.absolutePath} (${modelFile.length()} bytes)")
                } else {
                    throw Exception("Failed to rename temp file to final path")
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Download cancelled")
                File(modelDir, "${selectedModel.filename}.tmp").delete()
                _downloadProgress.value = 0f
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}", e)
                File(modelDir, "${selectedModel.filename}.tmp").delete()
                _downloadProgress.value = 0f
                _downloadError.value = LocalAIOffer.classifyDownloadError(e)
            } finally {
                _isDownloading.value = false
            }
        }
    }

    /**
     * Cancels an ongoing download.
     */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    /**
     * Deletes the downloaded model to free storage.
     */
    fun deleteModel() {
        // Unload from native engine first
        if (modelLoadedInNative) {
            llamaCpp?.unloadModel()
            modelLoadedInNative = false
        }
        modelFile.delete()
        _isModelDownloaded.value = false
        _downloadProgress.value = 0f
        Log.d(TAG, "Model deleted")
    }

    /**
     * Whether the local LLM can actually run inference right now.
     */
    val isInferenceReady: Boolean
        get() = _isModelDownloaded.value && nativeLibraryLoaded

    /**
     * Tracks whether the native llama.cpp library is loaded.
     */
    private var nativeLibraryLoaded: Boolean = false

    /** JNI bridge instance — lazy initialized on first use. */
    private var llamaCpp: LlamaCpp? = null

    /** Whether the model is currently loaded in native memory. */
    private var modelLoadedInNative: Boolean = false

    init {
        try {
            // LlamaCpp's companion init block calls System.loadLibrary("llama_jni")
            llamaCpp = LlamaCpp()
            nativeLibraryLoaded = true
            Log.d(TAG, "Native llama.cpp JNI library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            nativeLibraryLoaded = false
            Log.d(TAG, "Native llama.cpp JNI library not available: ${e.message}")
        }
    }

    /**
     * Ensure the native model is loaded. Called lazily on first generate().
     */
    private fun ensureModelLoaded(): Boolean {
        if (modelLoadedInNative) return true
        val cpp = llamaCpp ?: return false
        val path = modelFile.absolutePath
        Log.d(TAG, "Loading model into native engine: $path")
        modelLoadedInNative = cpp.loadModel(path)
        if (!modelLoadedInNative) {
            Log.e(TAG, "Failed to load model in native engine")
        }
        return modelLoadedInNative
    }

    /**
     * Generate a response using the local LLM model via llama.cpp JNI.
     * Uses the Qwen chat template for prompt formatting.
     * Streams the response word-by-word via [streamingText] for live UI display.
     */
    /**
     * @param conversationHistory Optional list of (role, content) pairs for multi-turn context.
     *        Roles: "user" or "assistant". Last 10 messages max recommended.
     */
    // llama.cpp's ggml context is NOT thread-safe for concurrent inference. Two coroutines
    // hitting native generate() at once corrupts the bionic allocator (SIGSEGV inside
    // ggml_compute_forward_mul_mat). Serialize every call.
    private val inferenceMutex = Mutex()

    suspend fun generate(
        prompt: String,
        conversationHistory: List<Pair<String, String>>? = null
    ): String = withContext(Dispatchers.Default) {
        // Hard cap: on low-RAM devices the JNI call can thrash for minutes or hang.
        // Fail fast so the UI never shows an indefinite spinner.
        try {
            withTimeout(90_000L) {
                inferenceMutex.withLock {
                    runGenerate(prompt, conversationHistory)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Generation timed out after 90s")
            _streamingText.value = ""
            _isGenerating.value = false
            "The model is taking too long on this device. Try a shorter question, or restart the app if this keeps happening."
        }
    }

    private suspend fun runGenerate(
        prompt: String,
        conversationHistory: List<Pair<String, String>>?
    ): String {
        if (!_isModelDownloaded.value) {
            return "Local AI model not downloaded. Please download the model first."
        }
        if (!nativeLibraryLoaded) {
            Log.d(TAG, "generate() called but native library not loaded.")
            return "The offline AI model is installed and ready. Full AI responses will be available in the next update."
        }
        if (!ensureModelLoaded()) {
            return "Failed to initialize the AI model. Please try again or restart the app."
        }

        _isGenerating.value = true
        _streamingText.value = ""

        // Format prompt using Qwen3 ChatML template. /no_think disables chain-of-thought
        // so factual questions get a direct answer instead of long <think> blocks.
        val formattedPrompt = buildString {
            append("<|im_start|>system\n")
            append("You are OSHI, a helpful and privacy-focused AI assistant. Give clear, accurate, concise answers. Respond in the same language the user writes in. ")
            append("Answer the user directly. Do not greet (no \"bonjour\", \"hello\"). Do not restate the question. Never speak as the user. ")
            append("Only produce code when the user explicitly asks for code — never answer a factual question with a code block.")
            append("\n\n/no_think<|im_end|>\n")

            // Include conversation history — but skip prior assistant turns that look
            // corrupted (runaway Python loops, etc.) so they don't prime the next reply.
            val hist = conversationHistory?.takeLast(10)
            if (!hist.isNullOrEmpty()) {
                for ((role, content) in hist) {
                    if (role == "assistant" && looksCorrupted(content)) continue
                    append("<|im_start|>$role\n")
                    append(content)
                    append("<|im_end|>\n")
                }
            } else {
                append("<|im_start|>user\n")
                append(prompt)
                append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }

        return try {
            val t0 = System.currentTimeMillis()
            // RAM-aware token budget: the A17 generates ~4 chars/s, so a 256-token reply
            // can exceed the 90s hard timeout. Smaller budget on low-RAM devices keeps
            // every answer under the timeout envelope at the cost of shorter replies.
            //   <4 GB RAM → 128 tokens (~30s worst case on A17)
            //   4-6 GB    → 192 tokens
            //   ≥6 GB     → 256 tokens (full budget for high-RAM devices)
            val maxTokens = when {
                selectedModel.minRamMb < 3072 -> 128
                selectedModel.minRamMb < 5120 -> 192
                else -> 256
            }
            val response = llamaCpp!!.generate(formattedPrompt, maxTokens)
            val elapsedMs = System.currentTimeMillis() - t0
            Log.d(TAG, "Native generate() returned ${response.length} chars in ${elapsedMs}ms (budget=$maxTokens)")

            val wantsCode = userAskedForCode(prompt)
            var cleaned = stripLeadingNoise(
                stripRolePreamble(
                    stripThinkBlocks(
                        stripChatMLTokens(response)
                    )
                )
            ).trim()
            if (!wantsCode) {
                cleaned = stripHallucinatedCode(cleaned).trim()
            }

            // Surface the result through the streaming flow so the UI can subscribe.
            _streamingText.value = cleaned
            _isGenerating.value = false
            cleaned.ifEmpty { "I couldn't generate a response. Please try again." }
        } catch (e: Exception) {
            Log.e(TAG, "Native inference failed: ${e.message}", e)
            _streamingText.value = ""
            _isGenerating.value = false
            "I encountered an error processing your request. Please try again."
        }
    }

    /**
     * Returns the path to the downloaded GGUF model file, or null if not downloaded.
     */
    fun getModelPath(): String? {
        return if (_isModelDownloaded.value) modelFile.absolutePath else null
    }
}
