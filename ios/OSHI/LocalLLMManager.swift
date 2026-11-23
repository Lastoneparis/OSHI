import Foundation
import SwiftUI
import NaturalLanguage

// MARK: - Local LLM Manager (llama.cpp native inference for GGUF models)

@MainActor
class LocalLLMManager: ObservableObject {
    static let shared = LocalLLMManager()

    @Published var isModelDownloaded: Bool = false
    @Published var downloadProgress: Double = 0.0
    @Published var isDownloading: Bool = false
    @Published var isModelLoaded: Bool = false
    @Published var isGenerating: Bool = false
    @Published var errorMessage: String?

    // MARK: - RAM-based Model Selection

    struct ModelConfig {
        let id: String
        let name: String
        let filename: String
        let primaryURL: URL
        let fallbackURL: URL
        let sizeMB: Double
        let minRAMMB: Int
    }

    static let availableModels: [ModelConfig] = [
        ModelConfig(
            id: "qwen3-4b",
            name: "OSHI AI Pro",
            filename: "Qwen3-4B-Q4_K_M.gguf",
            primaryURL: URL(string: "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf")!,
            fallbackURL: URL(string: "https://oshi-messenger.com/files/models/Qwen3-4B-Q4_K_M.gguf")!,
            sizeMB: 2380,
            minRAMMB: 6144
        ),
        ModelConfig(
            id: "qwen3-1.7b-q4",
            name: "OSHI AI Standard",
            filename: "Qwen3-1.7B-Q4_K_M.gguf",
            primaryURL: URL(string: "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf")!,
            fallbackURL: URL(string: "https://oshi-messenger.com/files/models/Qwen3-1.7B-Q4_K_M.gguf")!,
            sizeMB: 1056,
            minRAMMB: 2560
        ),
        ModelConfig(
            id: "qwen3-0.6b",
            name: "OSHI AI Lite",
            filename: "Qwen3-0.6B-Q8_0.gguf",
            primaryURL: URL(string: "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf")!,
            fallbackURL: URL(string: "https://oshi-messenger.com/files/models/Qwen3-0.6B-Q8_0.gguf")!,
            sizeMB: 610,
            minRAMMB: 1024
        )
    ]

    /// Select best model based on device RAM
    static func bestModelForDevice() -> ModelConfig {
        let totalRAMMB = Int(ProcessInfo.processInfo.physicalMemory / (1024 * 1024))
        OshiLog.app.info("[LocalLLM] Device RAM: \(totalRAMMB)MB")
        return availableModels.first(where: { totalRAMMB >= $0.minRAMMB }) ?? availableModels.last!
    }

    let selectedModel: ModelConfig

    private var llamaContext: LlamaContext?

    // Serializes generate() invocations. Without this, two overlapping smart-reply
    // requests both hop onto the same LlamaContext actor between their awaits,
    // leaving the KV cache at one task's position while another feeds tokens
    // from position 0. The decode rejects with "Y = X + 1 required", logits
    // never get populated, and the next sampler call hits a GGML_ASSERT that
    // crashes the app. Cancelling the prior task before starting a new one
    // guarantees the actor sees a single in-flight generation at a time.
    private var inFlightGeneration: Task<String, Never>?
    private var generationCounter: UInt64 = 0

    private var downloadTask: URLSessionDownloadTask?

    // MARK: - Background download infrastructure
    //
    // We MUST use a background URLSession for the multi-GB model download. With a
    // foreground (.default) session, iOS suspends the download seconds after the
    // screen locks or the user switches apps, and any retry restarts at byte 0
    // because there is no resume data persisted. A background session keeps
    // downloading while the app is suspended (and even relaunches the app to
    // deliver completion), and we additionally save resume data on every cancel/
    // error so explicit retries continue from the last byte instead of restarting.

    static let backgroundSessionIdentifier = "com.oshi.localllm.background"

    /// Stashed by AppDelegate's `handleEventsForBackgroundURLSession` so we can
    /// invoke it once `urlSessionDidFinishEvents(forBackgroundURLSession:)` fires.
    var backgroundEventsCompletionHandler: (() -> Void)?

    private lazy var sessionDelegate: LocalLLMSessionDelegate =
        LocalLLMSessionDelegate(targetURL: modelFilePath, manager: self)

    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.background(withIdentifier: Self.backgroundSessionIdentifier)
        config.isDiscretionary = false              // start ASAP, don't wait for "ideal" conditions
        config.sessionSendsLaunchEvents = true       // relaunch the app to deliver completion
        config.allowsCellularAccess = true
        config.waitsForConnectivity = true           // ride out brief network drops
        config.timeoutIntervalForResource = 7 * 24 * 60 * 60
        return URLSession(configuration: config, delegate: sessionDelegate, delegateQueue: .main)
    }()

    /// Resume data is URL-bound; iOS gives it to us when a download is cancelled
    /// or interrupted, and we replay it via `downloadTask(withResumeData:)` to
    /// pick up at the last persisted byte.
    private var resumeDataFilePath: URL {
        modelsDirectory.appendingPathComponent("\(selectedModel.filename).resumeData")
    }
    fileprivate func loadResumeData() -> Data? { try? Data(contentsOf: resumeDataFilePath) }
    fileprivate func saveResumeData(_ data: Data) { try? data.write(to: resumeDataFilePath) }
    fileprivate func clearResumeData() { try? FileManager.default.removeItem(at: resumeDataFilePath) }

    /// Called by the AppDelegate when iOS hands us a completion handler for
    /// background-session events. Touching `session` lazily creates / reattaches
    /// the URLSession so the delegate methods can fire.
    func handleBackgroundEvents(_ completionHandler: @escaping () -> Void) {
        backgroundEventsCompletionHandler = completionHandler
        _ = session
    }

    private init() {
        selectedModel = Self.bestModelForDevice()
        OshiLog.app.info("[LocalLLM] Selected model: \(selectedModel.name) (\(Int(selectedModel.sizeMB))MB)")
        // Persist across App Store updates: the file lives in Documents/, which
        // is preserved on iOS over normal updates. We only redownload if either
        // (a) the file is missing, or (b) the existing file is suspiciously
        // small (truncated download from a prior session). Without the size
        // sanity check, a partial download could be silently treated as
        // "ready" → `LlamaContext.create_context` would later fail to parse
        // the GGUF and the app would surface a confusing error rather than
        // re-downloading.
        isModelDownloaded = isModelFileValid()
        // Mark the model file so iCloud doesn't try to back up gigabytes of
        // model weights (Apple recommends excluding user-downloadable assets
        // from backups). Also means iCloud restores won't expect the file —
        // a fresh device after iCloud restore will redownload, which is the
        // right behavior since the user hasn't downloaded on that device yet.
        if isModelDownloaded {
            markModelFileExcludedFromBackup()
            OshiLog.app.info("[LocalLLM] Existing model preserved across update: \(modelFilePath.lastPathComponent)")
        }
        cleanupOldModels()
    }

    /// True iff the model file exists AND is at least 80% of the expected size.
    /// Guards against partial-download artifacts being mistaken for a complete
    /// download on a relaunch after a network failure.
    private func isModelFileValid() -> Bool {
        let fm = FileManager.default
        guard fm.fileExists(atPath: modelFilePath.path) else { return false }
        let size = (try? fm.attributesOfItem(atPath: modelFilePath.path)[.size] as? Int64) ?? 0
        let expectedBytes = Int64(selectedModel.sizeMB * 1024 * 1024)
        let minAcceptable = Int64(Double(expectedBytes) * 0.80)
        return size >= minAcceptable
    }

    /// Mark the model file so it's excluded from iCloud / iTunes backups.
    /// Required for any large-but-redownloadable user asset.
    private func markModelFileExcludedFromBackup() {
        var url = modelFilePath
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
    }

    /// Remove any .gguf files (or stale .gguf.tmp files) in the Models directory
    /// that don't match the current selectedModel.
    /// IMPORTANT: never delete `<selectedModel.filename>.tmp` — that's the
    /// in-progress download for the model we're keeping.
    private func cleanupOldModels() {
        let fm = FileManager.default
        let dir = modelsDirectory
        guard let files = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return }

        let currentFilename = selectedModel.filename
        let currentTmpName  = "\(currentFilename).tmp"

        for file in files {
            let name = file.lastPathComponent
            let isCompletedOldModel = file.pathExtension == "gguf" && name != currentFilename
            // A stale .tmp from a previous model: ends in ".gguf.tmp" but its
            // base name (stripped of ".tmp") is NOT the current model file.
            let isStaleTmp = name.hasSuffix(".gguf.tmp") && name != currentTmpName
            guard isCompletedOldModel || isStaleTmp else { continue }

            do {
                let size = (try? fm.attributesOfItem(atPath: file.path)[.size] as? Int) ?? 0
                try fm.removeItem(at: file)
                let kind = isStaleTmp ? "stale .tmp" : "old model"
                OshiLog.app.info("[LocalLLM] Cleaned up \(kind): \(name) (\(size / 1_000_000)MB freed)")
            } catch {
                OshiLog.app.info("[LocalLLM] Failed to remove \(name): \(error.localizedDescription)")
            }
        }
    }

    // MARK: - File Paths

    var modelsDirectory: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let modelsDir = docs.appendingPathComponent("Models")
        if !FileManager.default.fileExists(atPath: modelsDir.path) {
            try? FileManager.default.createDirectory(at: modelsDir, withIntermediateDirectories: true)
        }
        return modelsDir
    }

    var modelFilePath: URL {
        modelsDirectory.appendingPathComponent(selectedModel.filename)
    }

    // MARK: - Download

    func downloadModel() {
        guard !isDownloading else { return }

        isDownloading = true
        downloadProgress = 0.0
        errorMessage = nil

        // 1) If iOS still has an in-flight task from a prior launch, re-attach to it.
        //    2) Otherwise, if we have resume data from a cancel/error, replay it.
        //    3) Otherwise, start fresh from the primary URL.
        session.getAllTasks { [weak self] tasks in
            Task { @MainActor [weak self] in
                guard let self = self else { return }

                if let inflight = tasks.compactMap({ $0 as? URLSessionDownloadTask }).first {
                    OshiLog.app.info("[LocalLLM] Re-attaching to in-flight background download")
                    self.downloadTask = inflight
                    return
                }
                if let data = self.loadResumeData() {
                    OshiLog.app.info("[LocalLLM] Resuming from \(data.count) bytes of saved resume data")
                    let task = self.session.downloadTask(withResumeData: data)
                    task.taskDescription = "primary"
                    self.downloadTask = task
                    task.resume()
                    return
                }
                self.startFreshDownload(url: self.selectedModel.primaryURL, source: "primary")
            }
        }
    }

    private func startFreshDownload(url: URL, source: String) {
        let task = session.downloadTask(with: url)
        task.taskDescription = source
        downloadTask = task
        task.resume()
    }

    // MARK: - Delegate-side callbacks (always invoked on the main actor by the proxy)

    fileprivate func didReceiveProgress(written: Int64, expected: Int64) {
        guard expected > 0 else { return }
        downloadProgress = Double(written) / Double(expected)
    }

    fileprivate func didFinishMovingToFinalLocation() {
        // Defense: re-validate from disk before flipping isModelDownloaded. If the
        // move succeeded but the file is somehow short or missing (truncated by
        // the OS, wrong path), surface an error instead of silently lying about
        // a download being complete and bouncing the user back to the download
        // screen with no explanation.
        let valid = isModelFileValid()
        let bytes = (try? FileManager.default.attributesOfItem(atPath: modelFilePath.path)[.size] as? Int64) ?? 0
        OshiLog.app.info("[LocalLLM] didFinishMoving: path=\(self.modelFilePath.path) bytes=\(bytes) valid=\(valid)")

        if valid {
            markModelFileExcludedFromBackup()
            clearResumeData()
            isModelDownloaded = true
            downloadProgress = 1.0
            errorMessage = nil
        } else {
            // The bytes on disk don't match the expected size — most often a
            // truncated download that 100%'d on a stale Content-Length. Discard
            // and surface a clear error so the user knows to retry.
            try? FileManager.default.removeItem(at: modelFilePath)
            clearResumeData()
            errorMessage = "Download completed but the file was incomplete (\(bytes / 1_000_000) MB). Please retry."
            downloadProgress = 0.0
            isModelDownloaded = false
        }
        isDownloading = false
        downloadTask = nil
    }

    /// Force a re-check from disk. Useful when the singleton's `isModelDownloaded`
    /// might be stale (e.g., the download completed in a background launch and
    /// the state-update Task @MainActor never ran before the singleton was
    /// re-instantiated). Called from view appearance and foreground transitions.
    func revalidateFromDisk() {
        let valid = isModelFileValid()
        if valid && !isModelDownloaded {
            OshiLog.app.info("[LocalLLM] Reconciled stale state — file is valid on disk, flipping isModelDownloaded to true")
            isModelDownloaded = true
            downloadProgress = 1.0
            isDownloading = false
        }
    }

    fileprivate func didFailToMove(_ error: Error) {
        errorMessage = "Failed to save model: \(error.localizedDescription)"
        isDownloading = false
        downloadTask = nil
    }

    fileprivate func didCompleteTask(_ task: URLSessionTask, error: Error?) {
        // The success path is handled in didFinishDownloadingTo before this fires.
        guard let error = error else { return }
        let nsError = error as NSError

        // iOS hands back resume data for any recoverable interruption (network
        // drop, low power, user backgrounding too long). Persist it so the next
        // downloadModel() call resumes instead of restarting at zero.
        if let data = nsError.userInfo[NSURLSessionDownloadTaskResumeData] as? Data {
            saveResumeData(data)
            OshiLog.app.info("[LocalLLM] Saved \(data.count) bytes of resume data after error")
        }

        if nsError.code == NSURLErrorCancelled {
            isDownloading = false
            return
        }

        // If primary failed outright, try the fallback URL. Resume data is bound
        // to the primary URL, so we drop it before switching servers.
        if task.taskDescription == "primary" {
            OshiLog.app.info("[LocalLLM] Primary failed (\(error.localizedDescription)) — trying fallback")
            clearResumeData()
            startFreshDownload(url: selectedModel.fallbackURL, source: "fallback")
            return
        }

        // Both servers failed. Leave isDownloading=false and surface a message
        // that tells the user a retry will resume from the last byte if iOS gave
        // us resume data above.
        errorMessage = "Download failed: \(error.localizedDescription). Tap download again to resume."
        isDownloading = false
        downloadTask = nil
    }

    fileprivate func didFinishBackgroundEvents() {
        backgroundEventsCompletionHandler?()
        backgroundEventsCompletionHandler = nil
    }

    func cancelDownload() {
        guard let task = downloadTask else {
            isDownloading = false
            downloadProgress = 0.0
            return
        }
        // cancel(byProducingResumeData:) gives us bytes-so-far so a future
        // downloadModel() call resumes instead of restarting.
        task.cancel(byProducingResumeData: { [weak self] data in
            Task { @MainActor [weak self] in
                guard let self = self else { return }
                if let data = data {
                    self.saveResumeData(data)
                    OshiLog.app.info("[LocalLLM] Cancelled — saved \(data.count) bytes for later resume")
                }
                self.downloadTask = nil
                self.isDownloading = false
                self.downloadProgress = 0.0
            }
        })
    }

    func deleteModel() {
        try? FileManager.default.removeItem(at: modelFilePath)
        clearResumeData()
        isModelDownloaded = false
        isModelLoaded = false
        llamaContext = nil
    }

    // MARK: - Model Loading

    private func ensureModelLoaded() async throws {
        if llamaContext == nil {
            guard isModelDownloaded else {
                throw LocalLLMError.modelNotDownloaded
            }
            OshiLog.app.info("[LocalLLM] Loading model from \(modelFilePath.path)...")
            llamaContext = try LlamaContext.create_context(path: modelFilePath.path)
            isModelLoaded = true
            OshiLog.app.info("[LocalLLM] Model loaded successfully")
        }
    }

    // MARK: - Generate

    /// Streaming text published token by token during generation
    @Published var streamingText: String = ""

    func generate(prompt: String, conversationHistory: [(role: String, content: String)]? = nil) async -> String {
        // Cancel any prior generation and wait for it to finish before
        // starting a new one. Concurrent runs corrupt the KV cache.
        if let prior = inFlightGeneration {
            prior.cancel()
            _ = await prior.value
        }
        generationCounter &+= 1
        let myGen = generationCounter
        let task = Task<String, Never> { [weak self] in
            guard let self = self else { return "" }
            return await self._generateBody(prompt: prompt, conversationHistory: conversationHistory)
        }
        inFlightGeneration = task
        let result = await task.value
        // Only clear the slot if a newer generate() didn't already replace it.
        if generationCounter == myGen { inFlightGeneration = nil }
        return result
    }

    private func _generateBody(prompt: String, conversationHistory: [(role: String, content: String)]?) async -> String {
        do {
            isGenerating = true
            streamingText = ""
            defer { isGenerating = false }

            try await ensureModelLoaded()
            guard let context = llamaContext else {
                return "Model could not be loaded."
            }

            // 🔁 Harmonized with Android `LocalLLMManager.kt` (same model, same prompt → same answer).
            // System prompt is byte-for-byte the Android version. /no_think disables Qwen3's
            // chain-of-thought so short factual questions get a direct answer instead of long
            // <think> blocks.
            var formattedPrompt = "<|im_start|>system\n"
            formattedPrompt += "You are OSHI, a helpful and privacy-focused AI assistant. Give clear, accurate, concise answers. Respond in the same language the user writes in. "
            formattedPrompt += "Answer the user directly. Do not greet (no \"bonjour\", \"hello\"). Do not restate the question. Never speak as the user. "
            formattedPrompt += "Only produce code when the user explicitly asks for code — never answer a factual question with a code block."
            formattedPrompt += "<|im_end|>\n"

            // Include conversation history (last ~10 turns) — same cap as Android.
            if let history = conversationHistory, history.count > 1 {
                let trimmed = Array(history.dropLast().suffix(10))
                for msg in trimmed {
                    if msg.role == "assistant", Self.looksCorrupted(msg.content) { continue }
                    formattedPrompt += "<|im_start|>\(msg.role)\n\(msg.content)<|im_end|>\n"
                }
                formattedPrompt += "<|im_start|>user\n\(prompt)<|im_end|>\n"
            } else {
                formattedPrompt += "<|im_start|>user\n\(prompt)<|im_end|>\n"
            }
            // Canonical Qwen3 way to disable thinking on 0.6B/1.7B/4B: pre-fill
            // the assistant turn with a closed empty <think></think> so the
            // model believes it has finished its reasoning phase and jumps
            // straight to the answer. The `/no_think` directive in the system
            // prompt is honored by Qwen3 14B+ but ignored by the smaller models
            // we ship to the iPad — so `streamingText` ended up showing a long
            // chain-of-thought block, the 4096-char cap truncated mid-think,
            // and the final stripThinkBlocks pass left nothing to display.
            formattedPrompt += "<|im_start|>assistant\n<think>\n\n</think>\n\n"

            await context.clear()
            await context.completion_init(text: formattedPrompt)

            var result = ""
            while await !context.is_done {
                // Cooperative cancellation: bail out between tokens if the caller's Task is cancelled.
                if Task.isCancelled {
                    OshiLog.app.info("[LocalLLM] Generation cancelled by caller")
                    await context.clear()
                    streamingText = ""
                    return ""
                }
                let token = await context.completion_loop()
                // Stop generation at ChatML end token (don't include it in output)
                if token.contains("<|im_end|>") || token.contains("<|im_end|") || token.contains("<|im_start|>") {
                    break
                }
                result += token
                // Belt-and-suspenders repetition guard: if the sampler's penalties fail and the
                // model enters a loop, detect it in Swift and cut the output. Checks whether the
                // tail of the output matches an earlier window of the same length.
                if result.count >= 320, Self.hasRepeatedTail(result, window: 160) {
                    OshiLog.app.info("[LocalLLM] repetition loop detected — truncating")
                    result = Self.trimRepeatedTail(result, window: 160)
                    break
                }
                // Strip ChatML tokens AND any chain-of-thought leakage so the
                // user never sees raw <think>…</think> artefacts mid-stream.
                let cleanStream = Self.stripThinkBlocks(Self.stripChatMLTokens(result))
                streamingText = cleanStream.trimmingCharacters(in: .whitespacesAndNewlines)
                // Allow up to 4096 chars (was 2048 — too short, caused mid-sentence truncation)
                if result.count > 4096 { break }
            }

            let wantsCode = Self.userAskedForCode(prompt)
            var cleaned = Self.stripLeadingNoise(Self.stripRolePreamble(Self.stripThinkBlocks(Self.stripChatMLTokens(result)))).trimmingCharacters(in: .whitespacesAndNewlines)
            if !wantsCode {
                cleaned = Self.stripHallucinatedCode(cleaned).trimmingCharacters(in: .whitespacesAndNewlines)
            }
            streamingText = ""
            if !cleaned.isEmpty { return cleaned }

            // Aggressive cleanup left nothing — usually because the model spent
            // its whole budget inside a (possibly unterminated) <think> block.
            // Try a less aggressive fallback: only strip ChatML tokens, then
            // keep whatever text follows the last </think>. If that still
            // leaves nothing, only then surface the "couldn't generate" line.
            var fallback = Self.stripChatMLTokens(result)
            if let lastClose = fallback.range(of: "</think>", options: .backwards) {
                fallback = String(fallback[lastClose.upperBound...])
            } else if let firstOpen = fallback.range(of: "<think>") {
                // Unterminated <think> — drop just the opening tag and keep
                // whatever the model managed to emit, even if it's the partial
                // chain-of-thought. Better than a blank "couldn't generate".
                fallback.removeSubrange(firstOpen)
            }
            fallback = fallback.trimmingCharacters(in: .whitespacesAndNewlines)
            return fallback.isEmpty ? "I couldn't generate a response. Please try again." : fallback
        } catch {
            OshiLog.app.info("[LocalLLM] Generation error: \(error)")
            streamingText = ""
            return "Error generating response: \(error.localizedDescription)"
        }
    }

    // MARK: - ChatML Token Cleanup

    /// Remove ChatML special tokens from generated text to prevent artifacts in UI
    private static func stripChatMLTokens(_ text: String) -> String {
        var s = text
        let tokens = ["<|im_start|>assistant", "<|im_start|>user", "<|im_start|>system",
                       "<|im_start|>", "<|im_end|>", "<|im_end|",
                       "<|endoftext|>", "<|end|>"]
        for token in tokens {
            s = s.replacingOccurrences(of: token, with: "")
        }
        return s
    }

    /// Did the user explicitly ask for code? Used to decide whether to strip hallucinated
    /// code blocks from the output. Matches keywords in ~10 common languages.
    private static func userAskedForCode(_ prompt: String) -> Bool {
        let lower = prompt.lowercased()
        let keywords = [
            "code", "python", "javascript", "swift", "java", "kotlin", "rust",
            "c++", "c#", "golang", " go ", "typescript", "html", "css", "sql",
            "script", "function", "algorithm", "snippet", "programm",
            "écris", "écrire", "implémente", "fonction", "algorithme",
            "escribe", "función", "programa",
            "schreib", "funktion",
            "scrivi", "funzione",
            "书写", "函数", "プログラム", "関数", "코드"
        ]
        return keywords.contains(where: { lower.contains($0) })
    }

    /// When the user didn't ask for code but the model emits a code block (Qwen3 small
    /// models default to Python for any question), drop the code block and any
    /// stray symbol line between it and the real text answer.
    private static func stripHallucinatedCode(_ text: String) -> String {
        var s = text
        while true {
            // Remove fenced ```…``` blocks anywhere in the string.
            guard let openRange = s.range(of: "```") else { break }
            let afterOpen = openRange.upperBound
            if let closeRange = s.range(of: "```", range: afterOpen..<s.endIndex) {
                s.removeSubrange(openRange.lowerBound..<closeRange.upperBound)
            } else {
                // Unterminated fence — drop everything from the fence onward only if it's
                // at the start (hallucinated preamble). Otherwise leave it for the user to see.
                if openRange.lowerBound == s.startIndex {
                    s.removeSubrange(openRange.lowerBound..<s.endIndex)
                }
                break
            }
        }
        // If the output starts with an obvious unfenced code line (import/def/var/etc.)
        // and a prose paragraph follows a blank line, drop the code and keep the prose.
        let lines = s.components(separatedBy: "\n")
        let codePrefixes = ["import ", "from ", "def ", "class ", "var ", "let ",
                            "const ", "function ", "public ", "private ", "int ",
                            "#include", "package ", "using ", "namespace ",
                            "print(", "console.log", "System.out"]
        if let first = lines.first?.trimmingCharacters(in: .whitespaces),
           codePrefixes.contains(where: { first.hasPrefix($0) }) {
            // Find first blank line — prose starts after it.
            if let blankIdx = lines.firstIndex(where: { $0.trimmingCharacters(in: .whitespaces).isEmpty }) {
                let after = lines[(blankIdx + 1)...].joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
                if !after.isEmpty { return after }
            }
        }
        // Re-run leading noise strip since we may have exposed new leading junk.
        return stripLeadingNoise(s)
    }

    /// Detect the language of the user's prompt and return an explicit directive for
    /// the model. On very short prompts NLLanguageRecognizer is unreliable, so we fall
    /// back to "Respond in the same language the user writes in."
    private static func languageDirective(for prompt: String) -> String {
        let fallback = "Respond in the same language the user writes in."
        let text = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.count >= 3 else { return fallback }
        let recognizer = NLLanguageRecognizer()
        recognizer.processString(text)
        let hypotheses = recognizer.languageHypotheses(withMaximum: 2)
        guard let (lang, confidence) = hypotheses.max(by: { $0.value < $1.value }), confidence >= 0.5 else {
            return fallback
        }
        let names: [NLLanguage: String] = [
            .french: "French", .english: "English", .spanish: "Spanish", .italian: "Italian",
            .german: "German", .portuguese: "Portuguese", .dutch: "Dutch", .russian: "Russian",
            .polish: "Polish", .turkish: "Turkish", .arabic: "Arabic", .hebrew: "Hebrew",
            .hindi: "Hindi", .japanese: "Japanese", .korean: "Korean",
            .simplifiedChinese: "Chinese", .traditionalChinese: "Chinese",
            .ukrainian: "Ukrainian", .czech: "Czech", .greek: "Greek", .swedish: "Swedish",
            .danish: "Danish", .norwegian: "Norwegian", .finnish: "Finnish",
            .romanian: "Romanian", .hungarian: "Hungarian", .catalan: "Catalan",
            .thai: "Thai", .vietnamese: "Vietnamese", .indonesian: "Indonesian",
            .malay: "Malay", .persian: "Persian"
        ]
        guard let name = names[lang] else { return fallback }
        return "You MUST respond entirely in \(name). Every sentence must be in \(name)."
    }

    /// Strip leading junk that small models sometimes emit before the real answer —
    /// empty table rows ("||"), separator bars, runs of punctuation, blank lines.
    /// Keeps the first substantive line intact.
    private static func stripLeadingNoise(_ text: String) -> String {
        var lines = text.components(separatedBy: "\n")
        let junkSet = CharacterSet(charactersIn: " \t|-*_.=~`>")
        while let first = lines.first {
            let trimmed = first.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty {
                lines.removeFirst()
                continue
            }
            // Drop the line if every character is in the "junk" set (bars, dashes, etc.)
            if trimmed.unicodeScalars.allSatisfy({ junkSet.contains($0) }) {
                lines.removeFirst()
                continue
            }
            break
        }
        return lines.joined(separator: "\n")
    }

    /// Remove Qwen3 chain-of-thought sections. When /no_think fails and the model
    /// still emits <think>…</think>, we keep only the content after the closing tag.
    /// Also handles unterminated <think> (drop everything before it or fall back to full text).
    private static func stripThinkBlocks(_ text: String) -> String {
        var s = text
        while let open = s.range(of: "<think>") {
            if let close = s.range(of: "</think>", range: open.upperBound..<s.endIndex) {
                s.removeSubrange(open.lowerBound..<close.upperBound)
            } else {
                s.removeSubrange(open.lowerBound..<s.endIndex)
                break
            }
        }
        return s
    }

    /// Heuristic: does this look like a runaway or off-topic answer we should exclude
    /// from history to stop it from priming the next turn?
    ///   • Very long output with repeated code fences (Python-loop symptom)
    ///   • Multiple identical ~200-char substrings (repetition loop)
    private static func looksCorrupted(_ text: String) -> Bool {
        if text.count > 1500 {
            let fenceCount = text.components(separatedBy: "```").count - 1
            if fenceCount >= 4 { return true }
        }
        return hasRepeatedTail(text, window: 160)
    }

    /// True if the last `window` characters of `text` appear identically somewhere
    /// earlier in the string — i.e. the model is repeating a block verbatim.
    private static func hasRepeatedTail(_ text: String, window: Int) -> Bool {
        guard text.count >= window * 2 else { return false }
        let chars = Array(text)
        let tailStart = chars.count - window
        let tail = String(chars[tailStart..<chars.count])
        let head = String(chars[0..<tailStart])
        return head.range(of: tail) != nil
    }

    /// Return `text` with the repeating tail removed, keeping one clean copy of the block.
    private static func trimRepeatedTail(_ text: String, window: Int) -> String {
        let chars = Array(text)
        guard chars.count >= window * 2 else { return text }
        let tail = String(chars[(chars.count - window)..<chars.count])
        let head = String(chars[0..<(chars.count - window)])
        if let match = head.range(of: tail) {
            return String(head[..<match.upperBound])
        }
        return text
    }

    /// Strip a leading user-role preamble that the model sometimes hallucinates
    /// before its actual answer (e.g. "bonjour je cherche à savoir… pouvez-vous me dire…").
    /// Pattern: starts with a greeting, contains a question mark or polite request,
    /// ends at the first paragraph break — drop everything up to that break.
    private static func stripRolePreamble(_ text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = trimmed.lowercased()
        let greetings = ["bonjour", "bonsoir", "salut", "coucou",
                         "hello", "hi ", "hey ",
                         "hola", "buenos días", "buenas",
                         "ciao", "buongiorno",
                         "hallo", "guten tag",
                         "olá", "oi ",
                         "你好", "こんにちは", "안녕"]
        guard greetings.contains(where: { lower.hasPrefix($0) }) else { return trimmed }

        if let paragraphBreak = trimmed.range(of: "\n\n") {
            let preamble = trimmed[..<paragraphBreak.lowerBound]
            let rest = trimmed[paragraphBreak.upperBound...].trimmingCharacters(in: .whitespacesAndNewlines)
            let looksLikeFakeUserTurn = preamble.contains("?")
                || preamble.localizedCaseInsensitiveContains("pouvez-vous")
                || preamble.localizedCaseInsensitiveContains("pouvez vous")
                || preamble.localizedCaseInsensitiveContains("could you")
                || preamble.localizedCaseInsensitiveContains("can you")
                || preamble.localizedCaseInsensitiveContains("je cherche")
                || preamble.localizedCaseInsensitiveContains("i'm looking")
                || preamble.localizedCaseInsensitiveContains("i am looking")
            if looksLikeFakeUserTurn && !rest.isEmpty {
                return rest
            }
        }
        return trimmed
    }

    // MARK: - Model size info

    var modelSizeString: String {
        return String(format: "%.1f GB", selectedModel.sizeMB / 1024.0)
    }

    var downloadedSizeString: String {
        guard isModelDownloaded else { return "0 GB" }
        let size = (try? FileManager.default.attributesOfItem(atPath: modelFilePath.path)[.size] as? Int64) ?? 0
        return String(format: "%.1f GB", Double(size) / 1_000_000_000)
    }
}

// MARK: - Errors

enum LocalLLMError: LocalizedError {
    case modelNotDownloaded
    case generationFailed(String)

    var errorDescription: String? {
        switch self {
        case .modelNotDownloaded:
            return "AI model is not downloaded yet."
        case .generationFailed(let reason):
            return "Generation failed: \(reason)"
        }
    }
}

// MARK: - URLSession delegate proxy
//
// Lives outside the @MainActor class so URLSession can invoke its delegate
// methods from any thread without fighting actor isolation. Owned by URLSession
// (which retains its delegate strongly until invalidate()), so the strong ref
// to LocalLLMManager would create a retain cycle — we use weak. The only
// synchronous obligation is to move the temp file inside didFinishDownloadingTo
// before that method returns, which we do using the immutable target URL
// captured at init.

final class LocalLLMSessionDelegate: NSObject, URLSessionDownloadDelegate {
    private let targetURL: URL
    weak var manager: LocalLLMManager?

    init(targetURL: URL, manager: LocalLLMManager) {
        self.targetURL = targetURL
        self.manager = manager
    }

    func urlSession(_ session: URLSession,
                    downloadTask: URLSessionDownloadTask,
                    didFinishDownloadingTo location: URL) {
        // CRITICAL: the temp file is deleted as soon as this method returns, so
        // we move it synchronously here. We don't touch any @MainActor state —
        // FileManager + the captured target URL are sufficient.
        let outcome: Result<Void, Error>
        do {
            try FileManager.default.createDirectory(at: targetURL.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: targetURL.path) {
                try FileManager.default.removeItem(at: targetURL)
            }
            try FileManager.default.moveItem(at: location, to: targetURL)
            outcome = .success(())
        } catch {
            outcome = .failure(error)
        }
        Task { @MainActor [weak manager] in
            guard let manager = manager else { return }
            switch outcome {
            case .success:        manager.didFinishMovingToFinalLocation()
            case .failure(let e): manager.didFailToMove(e)
            }
        }
    }

    func urlSession(_ session: URLSession,
                    downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64,
                    totalBytesExpectedToWrite: Int64) {
        let written = totalBytesWritten
        let expected = totalBytesExpectedToWrite
        Task { @MainActor [weak manager] in
            manager?.didReceiveProgress(written: written, expected: expected)
        }
    }

    func urlSession(_ session: URLSession,
                    downloadTask: URLSessionDownloadTask,
                    didResumeAtOffset fileOffset: Int64,
                    expectedTotalBytes: Int64) {
        let written = fileOffset
        let expected = expectedTotalBytes
        Task { @MainActor [weak manager] in
            manager?.didReceiveProgress(written: written, expected: expected)
        }
    }

    func urlSession(_ session: URLSession,
                    task: URLSessionTask,
                    didCompleteWithError error: Error?) {
        Task { @MainActor [weak manager] in
            manager?.didCompleteTask(task, error: error)
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor [weak manager] in
            manager?.didFinishBackgroundEvents()
        }
    }
}
