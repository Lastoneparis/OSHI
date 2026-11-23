import SwiftUI
import Combine
import PhotosUI
import UniformTypeIdentifiers

#if canImport(FoundationModels)
import FoundationModels
#endif

// MARK: - Offline AI Chat View (iOS 26+ Apple Intelligence)

struct OfflineAIChatView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    /// __OLED_BLACK_2026_11_16__
    @Environment(\.oledBlackActive) private var oledBlack
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @StateObject private var chatVM = OfflineAIChatViewModel()
    @FocusState private var isInputFocused: Bool
    @State private var typingAnimationActive = false

    // Attachment states (documents only — photos removed, LLM is text-only)
    @State private var attachmentContext: String?
    @State private var attachmentLabel: String?
    @State private var attachmentThumbnail: UIImage?
    @State private var isProcessingAttachment = false
    @State private var showDocumentPicker = false

    private let brandColor = Theme.brand

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if chatVM.isAvailable || LocalLLMManager.shared.isModelDownloaded {
                    chatContent
                } else {
                    unavailableView
                }
            }
            .background(oledBlack ? Color.black : colorScheme == .dark ? Color(white: 0.06) : Color(white: 0.95))
            .navigationTitle("ai_assistant".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title3)
                            .foregroundColor(.secondary)
                    }
                    .accessibilityLabel("common.close".localized)
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    if !chatVM.messages.isEmpty {
                        Button {
                            chatVM.clearChat()
                        } label: {
                            Image(systemName: "trash")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                        }
                        .accessibilityLabel("ai.a11y_clear_chat".localized)
                    }
                }
            }
        }
        .interactiveDismissDisabled(false)
    }

    // MARK: - Chat Content

    private var chatContent: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 14) {
                        if chatVM.messages.isEmpty {
                            welcomeView
                        }

                        ForEach(chatVM.messages) { message in
                            chatBubble(message)
                                .id(message.id)
                        }

                        if chatVM.isGenerating {
                            typingIndicator
                                .id("typing")
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
                    .padding(.bottom, 16)
                    .frame(maxWidth: 700)
                    .frame(maxWidth: .infinity)
                }
                .scrollDismissesKeyboard(.interactively)
                .onTapGesture {
                    isInputFocused = false
                }
                .onChange(of: chatVM.messages.count) { _, _ in
                    withAnimation(.easeOut(duration: 0.2)) {
                        if chatVM.isGenerating {
                            proxy.scrollTo("typing", anchor: .bottom)
                        } else if let last = chatVM.messages.last {
                            proxy.scrollTo(last.id, anchor: .bottom)
                        }
                    }
                }
                .onChange(of: chatVM.isGenerating) { _, generating in
                    if generating {
                        typingAnimationActive = true
                        withAnimation {
                            proxy.scrollTo("typing", anchor: .bottom)
                        }
                    } else {
                        typingAnimationActive = false
                    }
                }
            }

            Divider()

            // Attachment preview
            if attachmentLabel != nil || isProcessingAttachment {
                attachmentPreview
            }

            inputBar
        }
        // Photo/camera pickers removed — offline LLM is text-only and cannot see
        // images; attaching photos produced hallucinated answers. Documents (with
        // extractable text) are the only supported attachment type.
        .sheet(isPresented: $showDocumentPicker) {
            AIDocumentPickerView { url in
                processDocument(at: url)
            }
        }
    }

    // MARK: - Welcome View

    private var welcomeView: some View {
        VStack(spacing: 16) {
            Spacer().frame(height: 40)

            Image(systemName: "brain.filled.head.profile")
                .font(.scaled(48))
                .foregroundStyle(
                    LinearGradient(colors: [brandColor, brandColor.opacity(0.6)], startPoint: .topLeading, endPoint: .bottomTrailing)
                )
                .accessibilityHidden(true)

            Text("ai_offline_assistant".localized)
                .font(.scaled(22, weight: .bold))

            Text("ai_powered_apple_full".localized)
                .font(.scaled(14))
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)

            // Suggestion chips
            VStack(spacing: 8) {
                suggestionChip("ai_suggest_quantum".localized)
                suggestionChip("ai_suggest_poem".localized)
                suggestionChip("ai_suggest_capitals".localized)
                suggestionChip("ai_suggest_email".localized)
            }
            .padding(.top, 8)
            .frame(maxWidth: 480)

            Spacer().frame(height: 20)
        }
    }

    private func suggestionChip(_ text: String) -> some View {
        Button {
            #if !targetEnvironment(macCatalyst)
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            #endif
            chatVM.inputText = text
            chatVM.sendMessage()
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "sparkles")
                    .font(.scaled(12))
                    .foregroundColor(brandColor)
                    .accessibilityHidden(true)
                Text(text)
                    .font(.scaled(13))
                    .foregroundColor(.primary)
                    .lineLimit(1)
                Spacer()
                Image(systemName: "arrow.up.circle.fill")
                    .font(.scaled(16))
                    .foregroundColor(brandColor.opacity(0.5))
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .frame(minHeight: 44)
            .background(colorScheme == .dark ? Color(white: 0.14) : .white)
            .cornerRadius(12)
            .shadow(color: .black.opacity(0.04), radius: 2, y: 1)
        }
        .accessibilityHint("ai.a11y_suggestion_hint".localized)
        .padding(.horizontal, 8)
    }

    // MARK: - Chat Bubble

    private func chatBubble(_ message: AIChatMessage) -> some View {
        HStack(alignment: .top, spacing: 8) {
            if message.isUser {
                Spacer(minLength: 60)
            } else {
                // AI avatar
                Image(systemName: "brain.filled.head.profile")
                    .font(.system(size: 14))
                    .foregroundColor(.white)
                    .frame(width: 28, height: 28)
                    .background(brandColor)
                    .clipShape(Circle())
                    .accessibilityHidden(true)
            }

            VStack(alignment: message.isUser ? .trailing : .leading, spacing: 4) {
                // Attachment preview (photo thumbnail or file label) shown inside user bubble
                if message.attachmentLabel != nil {
                    VStack(alignment: message.isUser ? .trailing : .leading, spacing: 6) {
                        if let thumb = message.attachmentThumbnail {
                            Image(uiImage: thumb)
                                .resizable()
                                .scaledToFill()
                                .frame(maxWidth: 200, maxHeight: 200)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                        } else if let label = message.attachmentLabel {
                            HStack(spacing: 6) {
                                Image(systemName: "doc.fill")
                                Text(label).lineLimit(1)
                            }
                            .font(.scaled(13, weight: .medium))
                            .foregroundColor(message.isUser ? .white : .primary)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 8)
                            .background(message.isUser ? Color.white.opacity(0.18) : Color.secondary.opacity(0.15))
                            .cornerRadius(10)
                        }

                        if !message.content.isEmpty {
                            Text(message.content)
                                .font(.callout)
                                .foregroundColor(message.isUser ? .white : .primary)
                                .textSelection(.enabled)
                        }
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .background(
                        message.isUser
                            ? brandColor
                            : (colorScheme == .dark ? Color(white: 0.16) : .white)
                    )
                    .cornerRadius(18)
                    .cornerRadius(4, corners: message.isUser ? [.bottomRight] : [.bottomLeft])
                } else {
                    Text(message.content)
                        .font(.callout)
                        .foregroundColor(message.isUser ? .white : .primary)
                        .textSelection(.enabled)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(
                            message.isUser
                                ? brandColor
                                : (colorScheme == .dark ? Color(white: 0.16) : .white)
                        )
                        .cornerRadius(18)
                        .cornerRadius(4, corners: message.isUser ? [.bottomRight] : [.bottomLeft])
                }

                Text(message.timestamp, style: .time)
                    .font(.scaled(10))
                    .foregroundColor(.secondary)
            }

            if !message.isUser {
                Spacer(minLength: 60)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel((message.isUser ? "ai.a11y_you_said".localized : "ai.a11y_assistant_said".localized) + " " + message.content)
    }

    // MARK: - Typing Indicator

    private var typingIndicator: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "brain.filled.head.profile")
                .font(.system(size: 14))
                .foregroundColor(.white)
                .frame(width: 28, height: 28)
                .background(brandColor)
                .clipShape(Circle())
                .accessibilityHidden(true)

            HStack(spacing: 5) {
                ForEach(0..<3, id: \.self) { i in
                    Circle()
                        .fill(Color.secondary.opacity(0.5))
                        .frame(width: 7, height: 7)
                        .scaleEffect(typingAnimationActive && !reduceMotion ? 1.0 : 0.5)
                        .animation(
                            reduceMotion ? nil :
                            .easeInOut(duration: 0.5)
                                .repeatForever(autoreverses: true)
                                .delay(Double(i) * 0.15),
                            value: typingAnimationActive
                        )
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(colorScheme == .dark ? Color(white: 0.16) : .white)
            .cornerRadius(18)
            .cornerRadius(4, corners: [.bottomLeft])

            Spacer(minLength: 60)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("ai.a11y_thinking".localized)
        .onAppear {
            // Trigger animation after view appears so the state change is observed
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                typingAnimationActive = true
            }
        }
    }

    // MARK: - Attachment Preview

    private var attachmentPreview: some View {
        HStack(spacing: 10) {
            if isProcessingAttachment {
                ProgressView()
                    .scaleEffect(0.8)
                Text("ai_processing".localized)
                    .font(.scaled(13))
                    .foregroundColor(.secondary)
            } else if let label = attachmentLabel {
                if let thumbnail = attachmentThumbnail {
                    Image(uiImage: thumbnail)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 36, height: 36)
                        .cornerRadius(6)
                        .clipped()
                } else {
                    Image(systemName: "doc.text.fill")
                        .font(.scaled(16))
                        .foregroundColor(brandColor)
                }

                Text(label)
                    .font(.scaled(13))
                    .foregroundColor(.primary)
                    .lineLimit(1)

                Spacer()

                Button {
                    clearAttachment()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 18))
                        .foregroundColor(.secondary)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("ai.a11y_remove_attachment".localized)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(colorScheme == .dark ? Color(white: 0.12) : Color(white: 0.93))
    }

    // MARK: - Input Bar

    private var inputBar: some View {
        HStack(spacing: 10) {
            // Attach button — documents only (PDF/DOCX/TXT/PPTX).
            // Photos/camera removed: offline AI is text-only and cannot see images;
            // photos without readable text would produce hallucinated answers.
            // Attachments removed entirely — the OSHI AI offline model via llama.cpp chokes on
            // large context and Apple Intelligence / Gemini Nano have small
            // context windows that make document injection unreliable.
            TextField("ai_ask_anything".localized, text: $chatVM.inputText, axis: .vertical)
                .font(.scaled(15))
                .lineLimit(1...5)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(colorScheme == .dark ? Color(white: 0.14) : .white)
                .cornerRadius(20)
                .focused($isInputFocused)
                .submitLabel(.send)
                .onSubmit {
                    sendWithAttachment()
                }

            Button {
                #if !targetEnvironment(macCatalyst)
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                #endif
                sendWithAttachment()
                isInputFocused = false
            } label: {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.scaled(32))
                    .foregroundColor(
                        (chatVM.inputText.trimmingCharacters(in: .whitespaces).isEmpty && attachmentLabel == nil)
                            ? .secondary : brandColor
                    )
                    .frame(minWidth: 44, minHeight: 44)
            }
            .accessibilityLabel("ai.a11y_send_message".localized)
            // 🔧 FIX: Allow send when there's an attachment even if text is empty
            .disabled(
                (chatVM.inputText.trimmingCharacters(in: .whitespaces).isEmpty && attachmentLabel == nil)
                    || chatVM.isGenerating
                    || isProcessingAttachment
            )
        }
        .frame(maxWidth: 700)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .padding(.bottom, 4)
        .frame(maxWidth: .infinity)
        // __OLED_GLASS_2026_11_16__ crystal-glass bar under OLED black.
        .oledGlassBar(fallback: colorScheme == .dark ? Color(white: 0.1) : Color(white: 0.96))
    }

    // MARK: - Attachment Processing

    private func sendWithAttachment() {
        chatVM.sendMessage(
            attachmentContext: attachmentContext,
            attachmentLabel: attachmentLabel,
            attachmentThumbnail: attachmentThumbnail
        )
        clearAttachment()
    }

    private func clearAttachment() {
        attachmentContext = nil
        attachmentLabel = nil
        attachmentThumbnail = nil
    }

    private func processDocument(at url: URL) {
        isProcessingAttachment = true
        let processor = AIDocumentProcessor.shared
        let fileName = url.lastPathComponent
        let ext = url.pathExtension.lowercased()

        // File size check
        guard processor.fileSizeOK(at: url) else {
            attachmentLabel = "ai_file_too_large".localized
            isProcessingAttachment = false
            return
        }

        Task {
            let result: AIDocumentProcessor.ProcessingResult

            // Images no longer supported — offline LLM is text-only and can't see them
            if ext == "pdf" {
                result = processor.extractText(from: url)
            } else if ["jpg", "jpeg", "png", "heic", "heif", "tiff", "bmp", "gif", "webp"].contains(ext) {
                result = .error("ai_no_text_found".localized)
            } else {
                result = processor.extractText(from: url, type: ext)
            }

            await MainActor.run {
                switch result {
                case .text(let text):
                    attachmentContext = processor.formatAsContext(text, sourceType: fileName)
                    attachmentLabel = "ai_text_extracted".localized(with: fileName)
                case .error(let errorMsg):
                    attachmentLabel = errorMsg
                }
                isProcessingAttachment = false
            }
        }
    }

    // MARK: - Unavailable View

    @StateObject private var localLLMChat = LocalLLMManager.shared

    private var unavailableView: some View {
        VStack(spacing: 16) {
            Spacer()

            Image(systemName: "brain.filled.head.profile")
                .font(.scaled(56))
                .foregroundStyle(
                    LinearGradient(colors: [brandColor, brandColor.opacity(0.5)], startPoint: .topLeading, endPoint: .bottomTrailing)
                )
                .accessibilityHidden(true)

            Text("ai_offline_assistant".localized)
                .font(.scaled(22, weight: .bold))

            Text("ai_download_lightweight".localized)
                .font(.scaled(14))
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 30)

            if localLLMChat.isDownloading {
                VStack(spacing: 8) {
                    ProgressView(value: localLLMChat.downloadProgress)
                        .progressViewStyle(LinearProgressViewStyle(tint: brandColor))
                        .padding(.horizontal, 40)
                    Text("\(Int(localLLMChat.downloadProgress * 100))% — \("ai_downloading".localized)...")
                        .font(.scaled(12))
                        .foregroundColor(.secondary)
                    Button { localLLMChat.cancelDownload() } label: {
                        Text("ai_cancel".localized).font(.scaled(14)).foregroundColor(.red)
                            .frame(minHeight: 44)
                    }
                }
            } else if !localLLMChat.isModelDownloaded {
                Button {
                    #if !targetEnvironment(macCatalyst)
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    #endif
                    localLLMChat.downloadModel()
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "arrow.down.circle.fill")
                        Text("\("ai_download_ai_model".localized) (\(localLLMChat.modelSizeString))")
                            .fontWeight(.semibold)
                    }
                    .foregroundColor(.white)
                    .frame(minHeight: 44)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 14)
                    .background(brandColor)
                    .cornerRadius(14)
                }
            } else {
                // Model downloaded — refresh availability
                Text("ai_model_downloaded".localized)
                    .font(.scaled(14, weight: .medium))
                    .foregroundColor(brandColor)
                    .onAppear { chatVM.isAvailable = true }
            }

            if let error = localLLMChat.errorMessage {
                Text(error)
                    .font(.scaled(12))
                    .foregroundColor(.red)
                    .padding(.horizontal, 30)
            }

            Text("ai_on_device_footer".localized)
                .font(.scaled(12))
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)

            Spacer()
        }
        .frame(maxWidth: 540)
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Corner Radius Extension (for specific corners)

private extension View {
    func cornerRadius(_ radius: CGFloat, corners: UIRectCorner) -> some View {
        clipShape(RoundedCornerShape(radius: radius, corners: corners))
    }
}

private struct RoundedCornerShape: Shape {
    var radius: CGFloat
    var corners: UIRectCorner

    func path(in rect: CGRect) -> Path {
        let path = UIBezierPath(
            roundedRect: rect,
            byRoundingCorners: corners,
            cornerRadii: CGSize(width: radius, height: radius)
        )
        return Path(path.cgPath)
    }
}

// MARK: - Chat Message Model

struct AIChatMessage: Identifiable {
    let id = UUID()
    let content: String
    let isUser: Bool
    let timestamp: Date
    // Attachment preview shown in chat bubble when user sends photo/file to AI
    let attachmentLabel: String?
    let attachmentThumbnail: UIImage?

    init(content: String, isUser: Bool, timestamp: Date,
         attachmentLabel: String? = nil, attachmentThumbnail: UIImage? = nil) {
        self.content = content
        self.isUser = isUser
        self.timestamp = timestamp
        self.attachmentLabel = attachmentLabel
        self.attachmentThumbnail = attachmentThumbnail
    }
}

// MARK: - ViewModel

@MainActor
final class OfflineAIChatViewModel: ObservableObject {
    @Published var messages: [AIChatMessage] = []
    @Published var inputText: String = ""
    @Published var isGenerating: Bool = false
    @Published var isAvailable: Bool = false

    #if canImport(FoundationModels)
    private var _session: Any?
    @available(iOS 26, *)
    private var session: LanguageModelSession? {
        get { _session as? LanguageModelSession }
        set { _session = newValue }
    }
    #endif

    private var localLLMObserver: Any?

    init() {
        checkAvailability()
        // Observe LocalLLMManager changes reactively
        localLLMObserver = LocalLLMManager.shared.$isModelDownloaded.sink { [weak self] downloaded in
            if downloaded {
                self?.isAvailable = true
            }
        }
    }

    private func checkAvailability() {
        #if canImport(FoundationModels)
        if #available(iOS 26, *) {
            if SystemLanguageModel.default.isAvailable {
                isAvailable = true
                createSession()
                return
            }
        }
        #endif
        // Check local LLM
        if LocalLLMManager.shared.isModelDownloaded {
            isAvailable = true
        }
    }

    private func createSession() {
        #if canImport(FoundationModels)
        // Apple Intelligence requires: iOS 26+, an eligible device (iPhone 15 Pro / 15 Pro Max /
        // 16 series, M1+ iPad, Apple Silicon Mac), user-enabled in Settings, and the model
        // actually downloaded. SystemLanguageModel.default.isAvailable checks all of that.
        if #available(iOS 26, *), SystemLanguageModel.default.isAvailable {
            session = LanguageModelSession(instructions: """
                You are a helpful, knowledgeable, and friendly AI assistant running entirely offline on the user's device via Apple Intelligence. \
                \
                Key behaviors: \
                - Give clear, accurate, and concise answers. \
                - Answer the user's message directly — do not greet them, do not restate their question, do not speak as if you were the user. Begin your first sentence with the answer itself. \
                - If you don't know something, say so honestly rather than guessing. \
                - Adapt your tone to match the user: casual for casual questions, professional for work topics. \
                - You can help with: general knowledge, writing, coding, math, language translation, brainstorming, advice, explanations, and creative tasks. \
                - Keep responses focused and avoid unnecessary verbosity. \
                - You support multiple languages: respond in the same language the user writes in. \
                \
                Important: \
                - You are running 100% offline. You have no internet access and cannot browse the web, check live data, or access external APIs. \
                - Your knowledge comes from your training data. Be transparent about this limitation. \
                - Never fabricate specific URLs, phone numbers, or real-time information (weather, stock prices, news).
                """)
        }
        #endif
    }

    func sendMessage(attachmentContext: String? = nil,
                     attachmentLabel: String? = nil,
                     attachmentThumbnail: UIImage? = nil) {
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        // Allow empty text if we have an attachment (user can send just a photo)
        guard (!text.isEmpty || attachmentLabel != nil), !isGenerating else { return }

        let userMessage = AIChatMessage(
            content: text,
            isUser: true,
            timestamp: Date(),
            attachmentLabel: attachmentLabel,
            attachmentThumbnail: attachmentThumbnail
        )
        messages.append(userMessage)
        inputText = ""
        isGenerating = true

        let fullPrompt: String
        if let ctx = attachmentContext {
            let question = text.isEmpty ? "Please analyze this attachment." : text
            fullPrompt = "The user attached a document. Here is the extracted content:\n\n\(ctx)\n\nUser's question: \(question)"
        } else {
            fullPrompt = text
        }

        #if canImport(FoundationModels)
        // __OFFLINE_AI_HONEST_2026_09_28__ see `OfflineAIHonestMessage` (OfflineAITabView.swift).
        if #available(iOS 26, *), SystemLanguageModel.default.isAvailable, self.session == nil {
            createSession()
        }
        if #available(iOS 26, *), SystemLanguageModel.default.isAvailable, self.session != nil {
            Task {
                do {
                    let response = try await self.session!.respond(to: fullPrompt)
                    let responseText = String(response.content.trimmingCharacters(in: .whitespacesAndNewlines))
                    appendAIMessage(responseText.isEmpty ? "I couldn't generate a response. Please try rephrasing." : responseText)
                } catch {
                    OshiLog.ai.info("Apple Intelligence respond failed: \(String(describing: error))")
                    if LocalLLMManager.shared.isModelDownloaded {
                        await generateWithLocalLLM(text: fullPrompt)
                    } else {
                        appendAIMessage(OfflineAIHonestMessage.forAppleIntelligenceError(error))
                    }
                }
            }
        } else {
            // Device not eligible for Apple Intelligence (iPhone 14 or older, iOS < 26, disabled,
            // or model not downloaded) → always use the bundled OSHI AI offline model.
            Task { await generateWithLocalLLM(text: fullPrompt) }
        }
        #else
        Task { await generateWithLocalLLM(text: fullPrompt) }
        #endif
    }

    private func generateWithLocalLLM(text: String) async {
        let llm = LocalLLMManager.shared
        if llm.isModelDownloaded {
            let response = await llm.generate(prompt: text)
            appendAIMessage(response.isEmpty ? "I couldn't generate a response." : response)
        } else {
            appendAIMessage(OfflineAIHonestMessage.noEngine())
        }
    }

    private func appendAIMessage(_ text: String) {
        let aiMessage = AIChatMessage(content: text, isUser: false, timestamp: Date())
        messages.append(aiMessage)
        isGenerating = false
    }

    func clearChat() {
        messages.removeAll()
        // Recreate session for fresh context
        createSession()
    }
}

// MARK: - Document Picker for AI Chat

struct AIDocumentPickerView: UIViewControllerRepresentable {
    let onDocumentSelected: (URL) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let types: [UTType] = [.pdf, .plainText, .text, .utf8PlainText,
                               UTType(filenameExtension: "docx") ?? .data,
                               UTType(filenameExtension: "pptx") ?? .data,
                               UTType(filenameExtension: "doc") ?? .data,
                               .image]
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: true)
        picker.delegate = context.coordinator
        picker.allowsMultipleSelection = false
        picker.shouldShowFileExtensions = true
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onDocumentSelected: onDocumentSelected)
    }

    class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onDocumentSelected: (URL) -> Void

        init(onDocumentSelected: @escaping (URL) -> Void) {
            self.onDocumentSelected = onDocumentSelected
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard let url = urls.first else { return }

            // Copy to temp if needed
            if FileManager.default.fileExists(atPath: url.path) {
                let tempDir = FileManager.default.temporaryDirectory
                let destURL = tempDir.appendingPathComponent(url.lastPathComponent)
                try? FileManager.default.removeItem(at: destURL)
                do {
                    try FileManager.default.copyItem(at: url, to: destURL)
                    DispatchQueue.main.async {
                        self.onDocumentSelected(destURL)
                    }
                } catch {
                    DispatchQueue.main.async {
                        self.onDocumentSelected(url)
                    }
                }
            } else {
                DispatchQueue.main.async {
                    self.onDocumentSelected(url)
                }
            }
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {}
    }
}
