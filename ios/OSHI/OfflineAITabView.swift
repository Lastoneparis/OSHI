import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

#if canImport(FoundationModels)
import FoundationModels
#endif

// MARK: - AI Tab View (ChatGPT-like offline AI)

struct OfflineAITabView: View {
    @StateObject private var store = AIChatStore()
    @StateObject private var localLLM = LocalLLMManager.shared
    @State private var showSidebar = false
    @State private var forceShowOfflineDownload = false
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.oledBlackActive) private var oledBlack

    private let brandColor = Theme.brand

    /// True when Apple Intelligence is actually working (not just available)
    /// __AI_AVAILABILITY_NEVER_RECHECKED_2026_10_17__ Bumped whenever the tab
    /// appears or the app returns to the foreground, so the availability test
    /// below is actually re-run.
    ///
    /// Both existing hooks called only `localLLM.revalidateFromDisk()` — they
    /// refresh the DOWNLOADED QWEN MODEL, never Apple Intelligence. And
    /// `revalidateFromDisk` publishes nothing when the answer is unchanged, so
    /// with no Qwen model there was no state change, no re-render, and
    /// `isAppleIntelligenceWorking` was never read again. Turning Apple
    /// Intelligence on in System Settings and coming back therefore left the
    /// "Open Settings → Apple Intelligence" prompt on screen indefinitely —
    /// reported on the Mac, but the same stall exists on iOS.
    @State private var aiAvailabilityTick = 0

    private var isAppleIntelligenceWorking: Bool {
        // Reading the tick during `body` registers the dependency, which is
        // what makes the refresh below actually re-evaluate this.
        _ = aiAvailabilityTick
        #if DEBUG
        // Force the conversation UI for App Store screenshots (the simulator has
        // no Apple Intelligence model, which would otherwise show a download/
        // prompt state). Only active with the -OSHIScreens capture flag.
        if ProcessInfo.processInfo.arguments.contains("-OSHIScreens") { return true }
        #endif
        #if canImport(FoundationModels)
        if #available(iOS 26, *) {
            return SystemLanguageModel.default.isAvailable
        }
        #endif
        return false
    }

    /// True when the device's silicon supports Apple Intelligence even if it's currently
    /// disabled / downloading. Uses the `availability` enum to distinguish "not supported"
    /// from "supported but user hasn't enabled it yet". When this is true on an iPhone
    /// 15 Pro+ / 16+ / 17+, we should NOT prompt the user to download the OSHI offline
    /// model — instead point them at iOS Settings → Apple Intelligence.
    private var deviceSupportsAppleIntelligence: Bool {
        #if canImport(FoundationModels)
        if #available(iOS 26, *) {
            switch SystemLanguageModel.default.availability {
            case .available:
                return true
            case .unavailable(.deviceNotEligible):
                // Silicon too old (iPhone 14 or earlier) — fall back to Qwen3.
                return false
            case .unavailable:
                // appleIntelligenceNotEnabled / modelNotReady / etc. — hardware
                // CAN run Apple Intelligence, user just needs to enable it.
                let s = String(describing: SystemLanguageModel.default.availability).lowercased()
                if s.contains("noteligible") || s.contains("notsupported") {
                    return false
                }
                return true
            @unknown default:
                return false
            }
        }
        #endif
        return false
    }

    /// One-line message explaining WHY Apple Intelligence isn't usable right now, when the
    /// device DOES support it. Lets us show a precise CTA ("Enable Apple Intelligence in
    /// Settings") instead of a Qwen3 download prompt.
    private var appleIntelligenceUnavailableReason: String? {
        #if canImport(FoundationModels)
        if #available(iOS 26, *) {
            switch SystemLanguageModel.default.availability {
            case .available:
                return nil
            case .unavailable(let reason):
                return String(describing: reason)
            @unknown default:
                return "unknown"
            }
        }
        #endif
        return nil
    }

    /// Subtle brand-purple wash used as the bottom layer behind the AI welcome /
    /// conversation screens so the tab matches the rest of the app instead of
    /// sitting on the bare system background. Kept very low-opacity (and even
    /// fainter in dark mode) so chat bubbles, cards, and text stay legible.
    private var themedBackground: some View {
        LinearGradient(
            // __OLED_BLACK_2026_11_16__ true black under OLED black.
            colors: oledBlack ? [.black, .black]
                : colorScheme == .dark
                ? [Color(white: 0.06), brandColor.opacity(0.10)]
                : [Color(.systemBackground), brandColor.opacity(0.06)],
            startPoint: .top,
            endPoint: .bottom
        )
        .ignoresSafeArea()
    }

    var body: some View {
        NavigationStack {
            if isAppleIntelligenceWorking {
                ZStack {
                    themedBackground

                    // Active conversation or welcome
                    if let chat = store.activeChat {
                        AIChatConversationView(
                            chat: chat,
                            store: store,
                            onToggleSidebar: { showSidebar.toggle() }
                        )
                    } else {
                        aiWelcomeView
                    }

                    // Sidebar overlay
                    if showSidebar {
                        AIChatSidebarView(
                            store: store,
                            isPresented: $showSidebar
                        )
                        .transition(.move(edge: .leading).combined(with: .opacity))
                    }
                }
                .animation(.easeInOut(duration: 0.25), value: showSidebar)
            } else {
                aiUnavailableView
            }
        }
        // Re-check disk reality every time the tab appears or the app resumes —
        // covers the case where the download finished in the background but the
        // in-memory `isModelDownloaded` flag never flipped (e.g., delegate Task
        // @MainActor pre-empted by a relaunch).
        .onAppear {
            localLLM.revalidateFromDisk()
            aiAvailabilityTick &+= 1
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-OSHIScreens") {
                store.seedDemoConversation()
            }
            #endif
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            localLLM.revalidateFromDisk()
            // Coming back from System Settings is exactly the moment Apple
            // Intelligence may have just been switched on.
            aiAvailabilityTick &+= 1
        }
    }

    // MARK: - AI Unavailable (iOS < 26) — shows local LLM fallback

    @ViewBuilder
    private var aiUnavailableView: some View {
        // Priority order matters: once the user has opted into Qwen3 by
        // downloading it (often via the "Use OSHI offline instead" escape
        // hatch on the Apple Intelligence prompt), they have a working model
        // — route them to the Qwen chat UI instead of bouncing them back to
        // the "Open Settings → Apple Intelligence" prompt every time the
        // view re-renders. That bounce was making the download look like it
        // hadn't completed at all.
        if localLLM.isModelDownloaded {
            ZStack {
                themedBackground

                if let chat = store.activeChat {
                    AIChatConversationView(
                        chat: chat,
                        store: store,
                        onToggleSidebar: { showSidebar.toggle() }
                    )
                } else {
                    localLLMWelcomeView
                }

                if showSidebar {
                    AIChatSidebarView(
                        store: store,
                        isPresented: $showSidebar
                    )
                    .transition(.move(edge: .leading).combined(with: .opacity))
                }
            }
            .animation(.easeInOut(duration: 0.25), value: showSidebar)
        } else if deviceSupportsAppleIntelligence {
            // Apple-Intelligence-capable device, AI not active yet, no Qwen
            // downloaded — nudge the user to enable AI in Settings, but the
            // outlined "Use OSHI offline instead" button stays available.
            appleIntelligencePromptView
        } else {
            // Device can't run Apple Intelligence — fall back to Qwen3.
            localLLMDownloadView
        }
    }

    /// __WRONG_DEVICE_NOUN_ON_MAC_2026_10_17__ "Your iPhone supports Apple
    /// Intelligence" — said to someone sitting at a Mac. All 34 locales carry
    /// the iPhone wording, and the Mac build showed it verbatim; seen on screen
    /// in the Catalyst app.
    ///
    /// Substituted rather than given 34 new translated strings on purpose:
    /// "iPhone" is a PRODUCT NOUN that every one of those locales embeds
    /// untranslated (fr "Votre iPhone…", ja "お使いのiPhoneは…"), so swapping the
    /// token is exact in each of them, while 34 hand-written variants would be
    /// 34 chances to introduce a translation error for one word.
    private static var appleIntelligenceSupportedDesc: String {
        let text = "ai.apple_intelligence_supported_desc".localized
        #if targetEnvironment(macCatalyst)
        return text.replacingOccurrences(of: "iPhone", with: "Mac")
        #else
        return text
        #endif
    }

    /// Shown on Apple-Intelligence-capable devices where the feature is just not active yet.
    private var appleIntelligencePromptView: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "apple.logo")
                .font(.scaled(44))
                .foregroundStyle(LinearGradient(colors: [brandColor, brandColor.opacity(0.5)], startPoint: .topLeading, endPoint: .bottomTrailing))
                .accessibilityHidden(true)
            Text("ai.apple_intelligence_supported_title".localized)
                .font(.scaled(22, weight: .bold))
                .multilineTextAlignment(.center)
            // __OFFLINE_AI_HONEST_2026_09_28__ `modelNotReady` = already ON, iOS still downloading
            // the model (a first launch offline): "enable it in Settings, no download" was false.
            Text(OfflineAIHonestMessage.isModelDownloading
                 ? "ai.not_ready_model_downloading".localized
                 : Self.appleIntelligenceSupportedDesc)
                .font(.scaled(14))
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 30)

            // __SETTINGS_WITH_NO_TOGGLE_2026_10_17__ "Enable it in Settings" is
            // a dead end when the device and Siri languages differ: macOS hides
            // the Apple Intelligence switch entirely and only prints the reason
            // on the pane. Measured here — a Mac set to English (US) with Siri
            // in French (France) shows no toggle at all, so the button above
            // opens a page on which nothing can be done, and availability stays
            // `appleIntelligenceNotEnabled` forever.
            //
            // The app cannot read Siri's language from inside the sandbox, so it
            // cannot detect the mismatch and must not pretend to. It states the
            // requirement instead, and points at the path that has no language
            // constraint at all.
            if !OfflineAIHonestMessage.isModelDownloading {
                Text("ai.apple_intelligence_language_note".localized)
                    .font(.scaled(12))
                    .foregroundColor(.secondary.opacity(0.85))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 30)
            }
            Button {
                // __SETTINGS_LINK_IS_IOS_ONLY_2026_10_16__ `openSettingsURLString`
                // is the iOS `app-settings:` scheme and NOTHING on macOS handles
                // it, so on the Mac this button — the only route the app offers
                // to switching Apple Intelligence on — did nothing at all. The
                // AI tab therefore looked broken on Catalyst even though the
                // framework IS linked there and the code path is shared with
                // iOS: availability was reporting `appleIntelligenceNotEnabled`
                // and the one control that could fix that was inert. Third
                // instance of this bug; see NotificationManager for the others.
                //
                // `com.apple.Siri-Settings.extension` is the "Apple Intelligence
                // & Siri" pane, read off SiriPreferenceExtension.appex on
                // macOS 26 rather than guessed.
                #if targetEnvironment(macCatalyst)
                let settingsURL = URL(string: "x-apple.systempreferences:com.apple.Siri-Settings.extension")
                #else
                let settingsURL = URL(string: UIApplication.openSettingsURLString)
                #endif
                if let url = settingsURL {
                    UIApplication.shared.open(url)
                }
            } label: {
                Text("ai.open_settings_apple_intelligence".localized)
                    .font(.scaled(15, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .padding(.vertical, 14)
                    .background(brandColor)
                    .cornerRadius(12)
            }
            .padding(.horizontal, 30)
            // Escape hatch — same visual weight as the primary button so users who don't
            // want to enable Apple Intelligence don't miss that Qwen3 offline is an option.
            Button {
                forceShowOfflineDownload = true
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "arrow.down.circle")
                        .font(.scaled(15, weight: .semibold))
                    Text("ai.use_oshi_offline_instead".localized)
                        .font(.scaled(15, weight: .semibold))
                }
                .foregroundColor(brandColor)
                .frame(maxWidth: .infinity, minHeight: 44)
                .padding(.vertical, 14)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(brandColor, lineWidth: 1.5)
                )
            }
            .padding(.horizontal, 30)
            .padding(.top, 4)
            Spacer()
        }
        .frame(maxWidth: 520)
        .frame(maxWidth: .infinity)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(themedBackground)
        .sheet(isPresented: $forceShowOfflineDownload) {
            NavigationStack {
                localLLMDownloadView
                    // __SHEET_HAS_NO_EXIT_ON_MAC_2026_10_16__ This sheet had no
                    // toolbar, no title and no dismiss control. On iOS that is
                    // survivable — a sheet can be swiped down. A Mac Catalyst
                    // sheet CANNOT be swiped away, so "use OSHI offline instead"
                    // was a one-way door: the only exit was to download a
                    // multi-gigabyte model. Reported from the Mac build.
                    //
                    // `.cancellationAction` puts it top-left on the Mac and
                    // top-leading on iOS, and it costs the iOS user nothing.
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("common.close".localized) {
                                forceShowOfflineDownload = false
                            }
                        }
                    }
            }
        }
    }

    // MARK: - Local LLM Download View

    private var localLLMDownloadView: some View {
        VStack(spacing: 16) {
            Spacer()

            Image(systemName: "brain.head.profile")
                .font(.scaled(56))
                .foregroundStyle(
                    LinearGradient(colors: [brandColor, brandColor.opacity(0.5)], startPoint: .topLeading, endPoint: .bottomTrailing)
                )
                .accessibilityHidden(true)

            Text("ai_offline_assistant".localized)
                .font(.scaled(24, weight: .bold))

            Text("ai_download_model_desc".localized)
                .font(.scaled(14))
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 30)

            // Model info card
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    Image(systemName: "cpu")
                        .font(.scaled(14))
                        .foregroundColor(brandColor)
                    Text(localLLM.selectedModel.name)
                        .font(.scaled(14, weight: .semibold))
                }
                HStack(spacing: 8) {
                    Image(systemName: "internaldrive")
                        .font(.scaled(14))
                        .foregroundColor(.secondary)
                    Text(String(format: "%.1f \("ai_gb_download".localized)", localLLM.selectedModel.sizeMB / 1024.0))
                        .font(.scaled(13))
                        .foregroundColor(.secondary)
                }
                HStack(spacing: 8) {
                    Image(systemName: "memorychip")
                        .font(.scaled(14))
                        .foregroundColor(.secondary)
                    Text("\("ai_selected_device".localized) (\(ProcessInfo.processInfo.physicalMemory / (1024*1024*1024)) \("ai_gb_ram".localized))")
                        .font(.scaled(13))
                        .foregroundColor(.secondary)
                }
            }
            .padding(14)
            .oledGlassCard(RoundedRectangle(cornerRadius: 12), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
            .cornerRadius(12)
            .padding(.horizontal, 30)

            VStack(spacing: 12) {
                if localLLM.isDownloading {
                    VStack(spacing: 8) {
                        ProgressView(value: localLLM.downloadProgress)
                            .progressViewStyle(LinearProgressViewStyle(tint: brandColor))
                            .padding(.horizontal, 40)

                        Text("\(Int(localLLM.downloadProgress * 100))% — \("ai_downloading".localized) \(localLLM.selectedModel.name)...")
                            .font(.scaled(12))
                            .foregroundColor(.secondary)

                        Button {
                            localLLM.cancelDownload()
                        } label: {
                            Text("ai_cancel".localized)
                                .font(.scaled(14, weight: .medium))
                                .foregroundColor(.red)
                                .frame(minHeight: 44)
                        }
                    }
                } else {
                    Button {
                        #if !targetEnvironment(macCatalyst)
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        #endif
                        localLLM.downloadModel()
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: "arrow.down.circle.fill")
                            Text("\("ai_download_model".localized) \(localLLM.selectedModel.name) (\(localLLM.modelSizeString))")
                                .fontWeight(.semibold)
                        }
                        .foregroundColor(.white)
                        .frame(minHeight: 44)
                        .padding(.horizontal, 24)
                        .padding(.vertical, 14)
                        .background(brandColor)
                        .cornerRadius(14)
                    }
                }

                if let error = localLLM.errorMessage {
                    Text(error)
                        .font(.scaled(12))
                        .foregroundColor(.red)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 30)
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                deviceRow(icon: "checkmark.circle.fill", text: "ai_feature_text".localized)
                deviceRow(icon: "lock.shield", text: "ai_feature_privacy".localized)
                deviceRow(icon: "wifi.slash", text: "ai_feature_offline".localized)
                deviceRow(icon: "xmark.circle", text: "ai_feature_no_image".localized)
            }
            .padding(16)
            .oledGlassCard(RoundedRectangle(cornerRadius: 14), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
            .cornerRadius(14)
            .padding(.horizontal, 30)

            Text("ai_image_analysis_note".localized)
                .font(.scaled(12))
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 36)

            Spacer()
        }
        .frame(maxWidth: 540)
        .frame(maxWidth: .infinity)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(themedBackground)
    }

    // MARK: - Local LLM Welcome (model downloaded, no active chat)

    private var localLLMWelcomeView: some View {
        VStack(spacing: 0) {
            // Top bar
            HStack {
                Button { showSidebar.toggle() } label: {
                    Image(systemName: "line.3.horizontal")
                        .font(.system(size: 20, weight: .medium))
                        .foregroundColor(.primary)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("ai.a11y_open_chat_list".localized)

                Spacer()

                Text("ai_assistant".localized)
                    .font(.scaled(17, weight: .bold))

                Spacer()

                Button { store.createNewChat() } label: {
                    Image(systemName: "square.and.pencil")
                        .font(.system(size: 18, weight: .medium))
                        .foregroundColor(brandColor)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("ai_new_chat".localized)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)

            Spacer()

            VStack(spacing: 20) {
                Image(systemName: "brain.filled.head.profile")
                    .font(.scaled(56))
                    .foregroundStyle(
                        LinearGradient(colors: [brandColor, brandColor.opacity(0.5)], startPoint: .topLeading, endPoint: .bottomTrailing)
                    )
                    .accessibilityHidden(true)

                Text("ai_offline_assistant".localized)
                    .font(.scaled(24, weight: .bold))

                Text("ai_powered_local".localized)
                    .font(.scaled(14))
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 40)

                Button {
                    #if !targetEnvironment(macCatalyst)
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    #endif
                    store.createNewChat()
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "plus.circle.fill")
                        Text("ai_new_chat".localized)
                            .fontWeight(.semibold)
                    }
                    .foregroundColor(.white)
                    .frame(minHeight: 44)
                    .padding(.horizontal, 28)
                    .padding(.vertical, 14)
                    .background(brandColor)
                    .cornerRadius(14)
                }
                .padding(.top, 8)

                if !store.chats.isEmpty {
                    VStack(spacing: 8) {
                        Text("ai_recent_chats".localized)
                            .font(.scaled(13, weight: .semibold))
                            .foregroundColor(.secondary)
                            .padding(.top, 16)

                        List {
                            ForEach(store.chats.prefix(5)) { chat in
                                Button {
                                    store.selectChat(chat.id)
                                } label: {
                                    HStack(spacing: 10) {
                                        Image(systemName: "message.fill")
                                            .font(.scaled(14))
                                            .foregroundColor(brandColor)
                                        Text(chat.title)
                                            .font(.scaled(14))
                                            .foregroundColor(.primary)
                                            .lineLimit(1)
                                        Spacer()
                                        Text(chat.lastActivityFormatted)
                                            .font(.scaled(11))
                                            .foregroundColor(.secondary)
                                    }
                                }
                                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                    Button(role: .destructive) {
                                        store.deleteChat(chat.id)
                                    } label: {
                                        Label("common.delete".localized, systemImage: "trash")
                                    }
                                }
                            }
                        }
                        .listStyle(.plain)
                        .frame(maxHeight: 250)
                    }
                    .padding(.horizontal, 24)
                }

                // Delete model option
                Button {
                    localLLM.deleteModel()
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "trash")
                            .font(.scaled(11))
                        Text("\("ai_delete_offline_model".localized) (\(localLLM.downloadedSizeString))")
                            .font(.scaled(11))
                    }
                    .foregroundColor(.secondary)
                    .frame(minHeight: 44)
                }
                .padding(.top, 16)
            }
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)

            Spacer()
        }
    }

    private func deviceRow(icon: String, text: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 14))
                .foregroundColor(brandColor)
                .frame(width: 20)
                .accessibilityHidden(true)
            Text(text)
                .font(.scaled(13))
                .foregroundColor(.primary)
        }
        .accessibilityElement(children: .combine)
    }

    private var aiWelcomeView: some View {
        VStack(spacing: 0) {
            // Top bar
            HStack {
                Button { showSidebar.toggle() } label: {
                    Image(systemName: "line.3.horizontal")
                        .font(.system(size: 20, weight: .medium))
                        .foregroundColor(.primary)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("ai.a11y_open_chat_list".localized)

                Spacer()

                Text("ai_assistant".localized)
                    .font(.scaled(17, weight: .bold))

                Spacer()

                Button { store.createNewChat() } label: {
                    Image(systemName: "square.and.pencil")
                        .font(.system(size: 18, weight: .medium))
                        .foregroundColor(brandColor)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("ai_new_chat".localized)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)

            Spacer()

            VStack(spacing: 20) {
                Image(systemName: "brain.filled.head.profile")
                    .font(.scaled(56))
                    .foregroundStyle(
                        LinearGradient(colors: [brandColor, brandColor.opacity(0.5)], startPoint: .topLeading, endPoint: .bottomTrailing)
                    )
                    .accessibilityHidden(true)

                Text("ai_offline_assistant".localized)
                    .font(.scaled(24, weight: .bold))

                Text("ai_powered_apple".localized)
                    .font(.scaled(14))
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 40)

                Button {
                    #if !targetEnvironment(macCatalyst)
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    #endif
                    store.createNewChat()
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "plus.circle.fill")
                        Text("ai_new_chat".localized)
                            .fontWeight(.semibold)
                    }
                    .foregroundColor(.white)
                    .frame(minHeight: 44)
                    .padding(.horizontal, 28)
                    .padding(.vertical, 14)
                    .background(brandColor)
                    .cornerRadius(14)
                }
                .padding(.top, 8)

                if !store.chats.isEmpty {
                    VStack(spacing: 8) {
                        Text("ai_recent_chats".localized)
                            .font(.scaled(13, weight: .semibold))
                            .foregroundColor(.secondary)
                            .padding(.top, 16)

                        List {
                            ForEach(store.chats.prefix(5)) { chat in
                                Button {
                                    store.selectChat(chat.id)
                                } label: {
                                    HStack(spacing: 10) {
                                        Image(systemName: "message.fill")
                                            .font(.scaled(14))
                                            .foregroundColor(brandColor)
                                        Text(chat.title)
                                            .font(.scaled(14))
                                            .foregroundColor(.primary)
                                            .lineLimit(1)
                                        Spacer()
                                        Text(chat.lastActivityFormatted)
                                            .font(.scaled(11))
                                            .foregroundColor(.secondary)
                                    }
                                }
                                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                    Button(role: .destructive) {
                                        store.deleteChat(chat.id)
                                    } label: {
                                        Label("common.delete".localized, systemImage: "trash")
                                    }
                                }
                            }
                        }
                        .listStyle(.plain)
                        .frame(maxHeight: 250)
                    }
                    .padding(.horizontal, 24)
                }
            }
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)

            Spacer()
        }
    }
}

// MARK: - Conversation View

struct AIChatConversationView: View {
    let chat: AIChat
    @ObservedObject var store: AIChatStore
    var onToggleSidebar: () -> Void

    @State private var inputText = ""
    @FocusState private var isInputFocused: Bool
    @Environment(\.colorScheme) private var colorScheme

    // 🎙️ Native on-device voice dictation (Apple Speech, fully offline).
    @StateObject private var dictation = SpeechDictationManager()
    /// Text already in the field when dictation started, so partial results are
    /// appended rather than replacing what the user typed.
    @State private var dictationBaseText = ""

    // Attachment states (documents only — photos removed, LLM is text-only)
    @State private var attachmentContext: String?
    @State private var attachmentLabel: String?
    @State private var attachmentThumbnail: UIImage?
    @State private var isProcessingAttachment = false
    @State private var showDocumentPicker = false

    // __AI_CHANNEL_OPEN_LANDS_SHORT__ Cold-open landing, mirroring ChatView.
    // On a cold app start the store's chats are loaded from UserDefaults, so
    // `chat.messages` is already fully populated when this view first appears.
    // The only scroll-to-bottom here was `.onChange(of: chat.messages.count)`,
    // which never fires on the initial appear (the count doesn't CHANGE after
    // the view is built), so the ScrollView opened at the TOP — above the newest
    // message — and the user had to swipe to reach it. These two flags drive the
    // same `ChatBottomAnchored` + `settleScrollToBottomOnOpen` machinery the
    // normal chat uses, so the AI channel lands at the true bottom on cold open
    // without yanking the view once the reader has scrolled up.
    @State private var bottomAnchorSighted = false
    @State private var userScrolledSinceOpen = false

    private let brandColor = Theme.brand

    var body: some View {
        VStack(spacing: 0) {
            // Top bar
            HStack(spacing: 12) {
                // Back to chat list
                Button { store.activeChatId = nil } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundColor(brandColor)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("common.back".localized)

                Button { onToggleSidebar() } label: {
                    Image(systemName: "line.3.horizontal")
                        .font(.system(size: 18, weight: .medium))
                        .foregroundColor(.primary)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("ai.a11y_open_chat_list".localized)

                Spacer()

                // Apple-Intelligence-style title: a glowing sparkle in an
                // iridescent ring + the chat title tinted with the same shifting
                // gradient. Reads instantly as "on-device AI".
                HStack(spacing: 7) {
                    Image(systemName: "sparkles")
                        .font(.scaled(13, weight: .bold))
                        .foregroundStyle(AIGradient.angular)
                        .padding(6)
                        .oledAwareBackground(.ultraThinMaterial, in: Circle())
                        .overlay(Circle().strokeBorder(AIGradient.angular, lineWidth: 1.2))
                    Text(chat.title)
                        .font(.scaled(16, weight: .bold))
                        .lineLimit(1)
                        .accessibilityAddTraits(.isHeader)
                }

                Spacer()

                Button { store.createNewChat() } label: {
                    Image(systemName: "square.and.pencil")
                        .font(.system(size: 18, weight: .medium))
                        .foregroundColor(brandColor)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("ai_new_chat".localized)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)

            Divider()

            // Messages
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 14) {
                        ForEach(chat.messages) { message in
                            AIChatBubbleView(message: message, brandColor: brandColor)
                                .id(message.id)
                        }

                        if store.isGenerating {
                            // Show streaming text if available, otherwise show typing dots
                            if !LocalLLMManager.shared.streamingText.isEmpty {
                                AIChatBubbleView(
                                    message: AIChatMsg(content: LocalLLMManager.shared.streamingText, isUser: false),
                                    brandColor: brandColor
                                )
                                .id("streaming")
                            } else {
                                AIChatTypingView(brandColor: brandColor)
                                    .id("typing")
                            }
                        }

                        // Bottom anchor — the single source of truth for "am I at
                        // the bottom?" and the target the open-settle scrolls to.
                        // Same id ("chatBottomAnchor") and 8pt height as the normal
                        // chat (see ChatView / SharedUIComponents), so it reuses the
                        // exact same landing machinery.
                        Color.clear.frame(height: 8).id("chatBottomAnchor")
                            .onAppear { bottomAnchorSighted = true }
                            .onDisappear { bottomAnchorSighted = false }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
                    .padding(.bottom, 16)
                    .frame(maxWidth: 700)
                    .frame(maxWidth: .infinity)
                }
                // Open at (and hold) the bottom, exactly like the normal chat:
                // `.initialOffset` bottom = cold open lands on the newest message;
                // `.sizeChanges` bottom re-pins while the lazy rows materialise —
                // but only while the reader hasn't scrolled up, so an active read
                // isn't yanked.
                .modifier(ChatBottomAnchored(pinnedToBottom: bottomAnchorSighted || !userScrolledSinceOpen))
                // "The reader dragged" signal so the open-settle stops the moment
                // they start reading higher up.
                .simultaneousGesture(
                    DragGesture(minimumDistance: 12).onChanged { _ in
                        userScrolledSinceOpen = true
                    }
                )
                .scrollDismissesKeyboard(.interactively)
                .onAppear {
                    // __AI_CHANNEL_OPEN_LANDS_SHORT__ Land at the TRUE bottom on
                    // cold open. `.onChange(of: chat.messages.count)` never fires
                    // for the already-loaded history, so without this the view
                    // opened parked above the newest message.
                    userScrolledSinceOpen = false
                    settleScrollToBottomOnOpen(proxy, to: "chatBottomAnchor") {
                        userScrolledSinceOpen
                    }
                }
                .onChange(of: chat.messages.count) { oldCount, newCount in
                    // Cold-fill guard: if the history arrives (0 -> N) a beat after
                    // appear, re-settle to the bottom instead of sitting on an empty
                    // list. The predicate stops the instant the reader scrolls up.
                    if oldCount == 0 && newCount > 0 && !userScrolledSinceOpen {
                        settleScrollToBottomOnOpen(proxy, to: "chatBottomAnchor") {
                            userScrolledSinceOpen
                        }
                    }
                    withAnimation(.easeOut(duration: 0.2)) {
                        if store.isGenerating {
                            proxy.scrollTo("typing", anchor: .bottom)
                        } else if let last = chat.messages.last {
                            proxy.scrollTo(last.id, anchor: .bottom)
                        }
                    }
                }
                .onChange(of: store.isGenerating) { _, generating in
                    if generating {
                        withAnimation {
                            proxy.scrollTo("typing", anchor: .bottom)
                        }
                    }
                }
            }

            Divider()

            // Attachment preview
            if attachmentLabel != nil || isProcessingAttachment {
                conversationAttachmentPreview
            }

            // Input bar
            HStack(spacing: 10) {
                // Attachment button removed — unreliable with the OSHI AI offline model (context
                // overflow) and limited context on Apple Intelligence / Gemini Nano.

                TextField("ai_ask_anything".localized, text: $inputText, axis: .vertical)
                    .font(.scaled(15))
                    .lineLimit(1...5)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(colorScheme == .dark ? Color(white: 0.14) : Color(white: 0.95))
                    .cornerRadius(20)
                    .focused($isInputFocused)
                    .submitLabel(.send)
                    .onSubmit { sendMessage() }

                // 🎙️ Voice dictation — Apple's native on-device speech-to-text.
                // Only shown when the device/locale actually supports offline
                // recognition (so we never quietly stream audio to Apple's cloud).
                if dictation.isAvailable {
                    Button { toggleDictation() } label: {
                        Image(systemName: dictation.isRecording ? "waveform.circle.fill" : "mic.fill")
                            .font(.system(size: dictation.isRecording ? 28 : 22))
                            .foregroundColor(dictation.isRecording ? .red : brandColor)
                            .frame(minWidth: 44, minHeight: 44)
                            .scaleEffect(dictation.isRecording ? 1.08 : 1.0)
                            .animation(.spring(response: 0.3), value: dictation.isRecording)
                    }
                    .accessibilityLabel(dictation.isRecording ? "speech.stop".localized : "speech.dictate".localized)
                    .disabled(store.isGenerating)
                }

                Button { sendMessage() } label: {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.scaled(32))
                        .foregroundColor(inputText.trimmingCharacters(in: .whitespaces).isEmpty ? .secondary : brandColor)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .accessibilityLabel("ai.a11y_send_message".localized)
                .disabled(inputText.trimmingCharacters(in: .whitespaces).isEmpty || store.isGenerating)
            }
            .frame(maxWidth: 700)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .padding(.bottom, 4)
            // Live-mirror the on-device transcript into the field as the user
            // speaks (appended after whatever they'd already typed).
            .onChange(of: dictation.transcript) { _, newValue in
                guard dictation.isRecording else { return }
                inputText = dictationBaseText + newValue
            }
            // Never leave the mic capturing if the view goes away.
            .onDisappear { if dictation.isRecording { dictation.cancel() } }
        }
        // Photo/camera pickers removed — offline LLM is text-only. Documents only.
        .sheet(isPresented: $showDocumentPicker) {
            AIDocumentPickerView { url in
                processDocument(at: url)
            }
        }
    }

    // MARK: - Attachment Preview

    private var conversationAttachmentPreview: some View {
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

    // MARK: - Send & Attachment Processing

    private func sendMessage() {
        // If a dictation is in progress, stop it first so the final transcript
        // lands in the field before we read it.
        if dictation.isRecording { dictation.cancel() }
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !store.isGenerating else { return }
        #if !targetEnvironment(macCatalyst)
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        #endif
        inputText = ""
        isInputFocused = false
        store.sendMessage(text, in: chat.id, attachmentContext: attachmentContext)
        clearAttachment()
    }

    /// Toggle native on-device dictation. Partial results are appended to
    /// whatever the user had already typed (captured as `dictationBaseText`).
    private func toggleDictation() {
        #if !targetEnvironment(macCatalyst)
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        #endif
        if dictation.isRecording {
            dictation.toggle { _ in }   // stop; live mirroring already filled the field
        } else {
            isInputFocused = false
            dictationBaseText = inputText.isEmpty ? "" : inputText + " "
            dictation.toggle { _ in }
        }
    }

    private func clearAttachment() {
        attachmentContext = nil
        attachmentLabel = nil
        attachmentThumbnail = nil
    }

    // Photo/camera processing removed — offline LLM cannot see images.

    private func processDocument(at url: URL) {
        isProcessingAttachment = true
        let processor = AIDocumentProcessor.shared
        let fileName = url.lastPathComponent
        let ext = url.pathExtension.lowercased()

        guard processor.fileSizeOK(at: url) else {
            attachmentLabel = "ai_file_too_large".localized
            isProcessingAttachment = false
            return
        }

        Task {
            let result: AIDocumentProcessor.ProcessingResult

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
                case .error(let err):
                    attachmentLabel = err
                }
                isProcessingAttachment = false
            }
        }
    }
}

// MARK: - Chat Bubble

struct AIChatBubbleView: View {
    let message: AIChatMsg
    let brandColor: Color

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            if message.isUser {
                Spacer(minLength: 60)
            } else {
                // Assistant avatar: a sparkle in an iridescent Apple-Intelligence
                // ring with a soft glow — signals on-device AI at a glance.
                Image(systemName: "sparkles")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(AIGradient.angular)
                    .frame(width: 28, height: 28)
                    .oledAwareBackground(.ultraThinMaterial, in: Circle())
                    .overlay(Circle().strokeBorder(AIGradient.angular, lineWidth: 1.4))
                    .neonGlow(brandColor, radius: 6, opacity: 0.4)
                    .accessibilityHidden(true)
            }

            VStack(alignment: message.isUser ? .trailing : .leading, spacing: 4) {
                Text(message.content)
                    .font(.callout)
                    .foregroundColor(message.isUser ? .white : .primary)
                    .textSelection(.enabled)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(
                        message.isUser
                            ? brandColor
                            : (colorScheme == .dark ? Color(white: 0.16) : Color(white: 0.94))
                    )
                    .cornerRadius(18)

                Text(message.timeFormatted)
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
}

// MARK: - Typing Indicator

struct AIChatTypingView: View {
    let brandColor: Color
    @State private var animating = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
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
                        .scaleEffect(animating && !reduceMotion ? 1.0 : 0.5)
                        .animation(
                            reduceMotion ? nil :
                            .easeInOut(duration: 0.5)
                                .repeatForever(autoreverses: true)
                                .delay(Double(i) * 0.15),
                            value: animating
                        )
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(Color.secondary.opacity(0.1))
            .cornerRadius(18)

            Spacer(minLength: 60)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("ai.a11y_thinking".localized)
        .onAppear {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                animating = true
            }
        }
    }
}

// MARK: - Sidebar

struct AIChatSidebarView: View {
    @ObservedObject var store: AIChatStore
    @Binding var isPresented: Bool
    @Environment(\.colorScheme) private var colorScheme

    private let brandColor = Theme.brand

    var body: some View {
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                // Header
                HStack {
                    Text("ai_chats".localized)
                        .font(.scaled(20, weight: .bold))
                    Spacer()
                    Button {
                        store.createNewChat()
                        isPresented = false
                    } label: {
                        Image(systemName: "plus.circle.fill")
                            .font(.system(size: 22))
                            .foregroundColor(brandColor)
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityLabel("ai_new_chat".localized)
                }
                .padding(.horizontal, 16)
                .padding(.top, 20)
                .padding(.bottom, 12)

                Divider()

                // Chat list
                if store.chats.isEmpty {
                    VStack(spacing: 12) {
                        Spacer()
                        Image(systemName: "message")
                            .font(.scaled(32))
                            .foregroundColor(.secondary.opacity(0.5))
                            .accessibilityHidden(true)
                        Text("ai_no_conversations".localized)
                            .font(.scaled(14))
                            .foregroundColor(.secondary)
                        Spacer()
                    }
                    .frame(maxWidth: .infinity)
                } else {
                    List {
                        ForEach(store.chats) { chat in
                            Button {
                                store.selectChat(chat.id)
                                isPresented = false
                                } label: {
                                    HStack(spacing: 10) {
                                        Image(systemName: "message.fill")
                                            .font(.scaled(14))
                                            .foregroundColor(store.activeChatId == chat.id ? .white : brandColor)
                                            .accessibilityHidden(true)

                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(chat.title)
                                                .font(.scaled(14, weight: .medium))
                                                .foregroundColor(store.activeChatId == chat.id ? .white : .primary)
                                                .lineLimit(1)
                                            Text(chat.lastActivityFormatted)
                                                .font(.scaled(11))
                                                .foregroundColor(store.activeChatId == chat.id ? .white.opacity(0.7) : .secondary)
                                        }

                                        Spacer()
                                    }
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 10)
                                    .background(store.activeChatId == chat.id ? brandColor : Color.clear)
                                    .cornerRadius(10)
                                }
                                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                    Button(role: .destructive) {
                                        store.deleteChat(chat.id)
                                    } label: {
                                        Label("common.delete".localized, systemImage: "trash")
                                    }
                                }
                                .contextMenu {
                                    Button(role: .destructive) {
                                        store.deleteChat(chat.id)
                                    } label: {
                                        Label("common.delete".localized, systemImage: "trash")
                                    }
                                }
                            }
                        }
                    .listStyle(.plain)
                }

                Spacer()

                // Footer
                Divider()
                HStack(spacing: 6) {
                    Image(systemName: "cpu")
                        .font(.scaled(12))
                        .foregroundColor(.secondary)
                        .accessibilityHidden(true)
                    Text("ai_on_device".localized)
                        .font(.scaled(11))
                        .foregroundColor(.secondary)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .accessibilityElement(children: .combine)
            }
            .frame(width: 280)
            // __OLED_GLASS_2026_11_16__ smoked glass panel under OLED black.
            .oledGlassCard(Rectangle(), fallback: colorScheme == .dark ? Color(white: 0.1) : Color.white, blur: true)
            .clipShape(RoundedRectangle(cornerRadius: 0))
            .shadow(color: .black.opacity(0.2), radius: 10, x: 5)

            // Tap-to-dismiss area
            Color.black.opacity(0.3)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture {
                    isPresented = false
                }
                .accessibilityLabel("common.dismiss".localized)
                .accessibilityAddTraits(.isButton)
        }
    }
}

// MARK: - Data Models

struct AIChatMsg: Identifiable, Codable {
    let id: String
    let content: String
    let isUser: Bool
    let timestamp: Date

    init(id: String = UUID().uuidString, content: String, isUser: Bool, timestamp: Date = Date()) {
        self.id = id
        self.content = content
        self.isUser = isUser
        self.timestamp = timestamp
    }

    var timeFormatted: String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f.string(from: timestamp)
    }
}

struct AIChat: Identifiable, Codable {
    let id: String
    var title: String
    var messages: [AIChatMsg]
    let createdAt: Date
    var lastActivity: Date

    init(id: String = UUID().uuidString, title: String = "ai_new_chat".localized) {
        self.id = id
        self.title = title
        self.messages = []
        self.createdAt = Date()
        self.lastActivity = Date()
    }

    var lastActivityFormatted: String {
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .abbreviated
        return f.localizedString(for: lastActivity, relativeTo: Date())
    }
}

// MARK: - Honest "no answer" messages (__OFFLINE_AI_HONEST_2026_09_28__)

/// What to tell the user when no on-device engine answered. `ai_unavailable_device` ("requires
/// iPhone 15 Pro … not available on this device") is true only for an ineligible device or an
/// iOS older than 26; it used to be shown for every miss, including an eligible iPhone whose Apple
/// Intelligence model was still downloading (a first launch in airplane mode).
enum OfflineAIHonestMessage {
    /// Apple Intelligence is on, but iOS has not finished downloading its model.
    static var isModelDownloading: Bool {
        #if canImport(FoundationModels)
        if #available(iOS 26, *), case .unavailable(.modelNotReady) = SystemLanguageModel.default.availability {
            return true
        }
        #endif
        return false
    }

    /// Neither Apple Intelligence nor the OSHI offline model can answer.
    static func noEngine() -> String {
        #if canImport(FoundationModels)
        if #available(iOS 26, *) {
            switch SystemLanguageModel.default.availability {
            case .available: return "ai.error_generic".localized
            case .unavailable(.modelNotReady): return "ai.not_ready_model_downloading".localized
            case .unavailable(.appleIntelligenceNotEnabled): return "ai.not_ready_not_enabled".localized
            case .unavailable(.deviceNotEligible): return "ai_unavailable_device".localized
            @unknown default: return "ai.error_generic".localized
            }
        }
        #endif
        return "ai_unavailable_device".localized
    }

    /// A `LanguageModelSession.respond` failure, when there is no local model to fall back to.
    static func forAppleIntelligenceError(_ error: Error) -> String {
        #if canImport(FoundationModels)
        if #available(iOS 26, *), let e = error as? LanguageModelSession.GenerationError {
            switch e {
            case .guardrailViolation, .refusal: return "ai.error_guardrail".localized
            case .exceededContextWindowSize: return "ai.error_context_window".localized
            case .unsupportedLanguageOrLocale: return "ai.error_language".localized
            case .concurrentRequests, .rateLimited: return "ai.error_busy".localized
            case .assetsUnavailable: return noEngine()
            default: return "ai.error_generic".localized
            }
        }
        #endif
        return noEngine()
    }
}

// MARK: - Chat Store (persistent)

@MainActor
class AIChatStore: ObservableObject {
    @Published var chats: [AIChat] = []
    @Published var activeChatId: String?
    @Published var isGenerating = false

    #if canImport(FoundationModels)
    private var _session: Any?
    @available(iOS 26, *)
    private var session: LanguageModelSession? {
        get { _session as? LanguageModelSession }
        set { _session = newValue }
    }
    #endif

    private var isModelAvailable = false

    var activeChat: AIChat? {
        guard let id = activeChatId else { return nil }
        return chats.first(where: { $0.id == id })
    }

    init() {
        loadChats()
        checkAvailability()
    }

    // MARK: - Model Availability

    private func checkAvailability() {
        #if canImport(FoundationModels)
        if #available(iOS 26, *) {
            isModelAvailable = SystemLanguageModel.default.isAvailable
            if isModelAvailable {
                createSession()
            }
        }
        #endif
    }

    private func createSession() {
        #if canImport(FoundationModels)
        // Apple Intelligence requires: iOS 26+, an eligible device (iPhone 15 Pro / 15 Pro Max /
        // 16 series, M1+ iPad, Apple Silicon Mac), user-enabled in Settings, and the model
        // actually downloaded. SystemLanguageModel.default.isAvailable checks all of that.
        if #available(iOS 26, *), SystemLanguageModel.default.isAvailable {
            session = LanguageModelSession(instructions: """
                You are a helpful, knowledgeable, and friendly AI assistant. \
                Give clear, accurate, and concise answers. \
                If you don't know something, say so honestly. \
                You support multiple languages: respond in the same language the user writes in. \
                You are running 100% offline on the user's device. You have no internet access. \
                Never fabricate URLs, phone numbers, or real-time information. \
                Answer the user's message directly. Do not greet the user. Do not restate or rephrase the user's question. \
                Never speak as if you were the user or pretend to ask a question on their behalf. Begin with the answer itself.
                """)
        }
        #endif
    }

    // MARK: - Chat Management

    func createNewChat() {
        let chat = AIChat()
        chats.insert(chat, at: 0)
        activeChatId = chat.id
        createSession() // Fresh context
        saveChats()
    }

    #if DEBUG
    /// Seed a realistic on-device AI conversation for App Store screenshots
    /// (`-OSHIScreens`). Never called in production.
    func seedDemoConversation() {
        guard chats.isEmpty else { activeChatId = chats.first?.id; return }
        var chat = AIChat(title: "Private AI")
        chat.messages = [
            AIChatMsg(content: "What makes OSHI's AI private?", isUser: true,
                      timestamp: Date().addingTimeInterval(-240)),
            AIChatMsg(content: "It runs 100% on your device — no internet, no servers. Your questions and my answers never leave your iPhone, so nothing can be logged, read, or trained on.",
                      isUser: false, timestamp: Date().addingTimeInterval(-232)),
            AIChatMsg(content: "Can you help me draft a message?", isUser: true,
                      timestamp: Date().addingTimeInterval(-120)),
            AIChatMsg(content: "Of course. Tell me the tone and who it's for, and I'll write it — all offline. Nothing you share here is ever transmitted.",
                      isUser: false, timestamp: Date().addingTimeInterval(-112)),
            AIChatMsg(content: "A warm thank-you note to a friend who helped me move.",
                      isUser: true, timestamp: Date().addingTimeInterval(-92)),
            AIChatMsg(content: "Here's a draft:\n\n\"Hey — thank you so much for helping me move this weekend. I honestly couldn't have done it without you. Dinner's on me next week!\"\n\nWant it more casual, or shorter?",
                      isUser: false, timestamp: Date().addingTimeInterval(-84)),
            AIChatMsg(content: "That's perfect. Can you also summarize a long article for me?",
                      isUser: true, timestamp: Date().addingTimeInterval(-48)),
            AIChatMsg(content: "Absolutely — paste the text and I'll give you the key points in seconds, entirely on-device. Nothing gets uploaded.",
                      isUser: false, timestamp: Date().addingTimeInterval(-40)),
        ]
        chats = [chat]
        activeChatId = chat.id
    }
    #endif

    func selectChat(_ id: String) {
        activeChatId = id
        createSession() // Reset session for this chat
        // Replay context from history — only when Apple Intelligence is actually available;
        // otherwise we'd be firing prompts at a nil session that silently fail and waste work.
        #if canImport(FoundationModels)
        if #available(iOS 26, *), SystemLanguageModel.default.isAvailable,
           let chat = chats.first(where: { $0.id == id }) {
            Task {
                let contextMessages = chat.messages.suffix(10)
                for msg in contextMessages where msg.isUser {
                    _ = try? await session?.respond(to: msg.content)
                }
            }
        }
        #endif
    }

    func deleteChat(_ id: String) {
        chats.removeAll(where: { $0.id == id })
        if activeChatId == id {
            activeChatId = chats.first?.id
        }
        saveChats()
    }

    // MARK: - Send Message

    func sendMessage(_ text: String, in chatId: String, attachmentContext: String? = nil) {
        guard let index = chats.firstIndex(where: { $0.id == chatId }) else { return }

        let userMsg = AIChatMsg(content: text, isUser: true)
        chats[index].messages.append(userMsg)
        chats[index].lastActivity = Date()

        // Auto-title from first message
        if chats[index].messages.count == 1 {
            chats[index].title = String(text.prefix(40)) + (text.count > 40 ? "..." : "")
        }

        // Move to top
        let chat = chats.remove(at: index)
        chats.insert(chat, at: 0)

        isGenerating = true
        saveChats()

        // Build full prompt with attachment context if present
        let fullPrompt: String
        if let ctx = attachmentContext {
            fullPrompt = "The user attached a document. Here is the extracted content:\n\n\(ctx)\n\nUser's question: \(text)"
        } else {
            fullPrompt = text
        }

        // Try Apple Intelligence first (eligible device only), then local LLM fallback.
        #if canImport(FoundationModels)
        // __OFFLINE_AI_HONEST_2026_09_28__ Apple Intelligence became ready after this store was
        // built (first launch offline, model downloaded since): open the session now, instead of
        // answering "requires iPhone 15 Pro" until the next chat or relaunch.
        if #available(iOS 26, *), SystemLanguageModel.default.isAvailable, self.session == nil {
            createSession()
        }
        if #available(iOS 26, *), SystemLanguageModel.default.isAvailable, self.session != nil {
            Task {
                do {
                    let response = try await self.session!.respond(to: fullPrompt)
                    let responseText = String(response.content.trimmingCharacters(in: .whitespacesAndNewlines))
                    appendAIResponse(responseText.isEmpty ? "I couldn't generate a response." : responseText, chatId: chatId)
                } catch {
                    // __OFFLINE_AI_HONEST_2026_09_28__ Say what failed. Falling through to the
                    // local model with it absent answered "requires iPhone 15 Pro" to a guardrail,
                    // a busy session or a long chat on an eligible iPhone.
                    OshiLog.ai.info("Apple Intelligence respond failed: \(String(describing: error))")
                    if LocalLLMManager.shared.isModelDownloaded {
                        generateWithLocalLLM(text: fullPrompt, chatId: chatId)
                    } else {
                        appendAIResponse(OfflineAIHonestMessage.forAppleIntelligenceError(error), chatId: chatId)
                    }
                }
            }
        } else {
            // Device not eligible for Apple Intelligence (iPhone 14 or older, iOS < 26, disabled,
            // or model not downloaded) → always use the bundled OSHI AI offline model.
            generateWithLocalLLM(text: fullPrompt, chatId: chatId)
        }
        #else
        generateWithLocalLLM(text: fullPrompt, chatId: chatId)
        #endif
    }

    private func generateWithLocalLLM(text: String, chatId: String) {
        let llm = LocalLLMManager.shared
        if llm.isModelDownloaded {
            // Build conversation history for context (last 10 messages max to fit in context window)
            let history: [(role: String, content: String)]
            if let index = chats.firstIndex(where: { $0.id == chatId }) {
                history = chats[index].messages.suffix(10).map {
                    (role: $0.isUser ? "user" : "assistant", content: $0.content)
                }
            } else {
                history = [(role: "user", content: text)]
            }
            Task {
                let response = await llm.generate(prompt: text, conversationHistory: history)
                appendAIResponse(response.isEmpty ? "I couldn't generate a response." : response, chatId: chatId)
            }
        } else {
            appendAIResponse(OfflineAIHonestMessage.noEngine(), chatId: chatId)
        }
    }

    private func appendAIResponse(_ text: String, chatId: String) {
        guard let index = chats.firstIndex(where: { $0.id == chatId }) else { return }
        let aiMsg = AIChatMsg(content: text, isUser: false)
        chats[index].messages.append(aiMsg)
        chats[index].lastActivity = Date()
        isGenerating = false
        saveChats()
    }

    // MARK: - Persistence

    // __LEAK_FIX_2026_09_25__ The history used to live in UserDefaults (Library/Preferences,
    // cleartext, INCLUDED in iCloud/Finder backups). It now lives in `Documents/ai_chats.json`
    // sealed with `AtRestFile` (OSHIENC1, key in the keychain ThisDeviceOnly; Documents is
    // excluded from backup). The old UserDefaults blob is migrated once, then deleted.
    private var storeUnreadable = false

    private func saveChats() {
        // Never overwrite a history we could not read (e.g. before first unlock).
        guard !storeUnreadable, let data = try? JSONEncoder().encode(chats) else { return }
        AIChatPersistence.save(data)
    }

    private func loadChats() {
        switch AIChatPersistence.load() {
        case .data(let data):
            if let decoded = try? JSONDecoder().decode([AIChat].self, from: data) { chats = decoded }
        case .empty:
            break
        case .unreadable:
            storeUnreadable = true
        }
    }
}

/// __LEAK_FIX_2026_09_25__ Encrypted-at-rest storage of the AI assistant history, with the
/// one-time migration from the old cleartext UserDefaults key. Injectable for tests.
enum AIChatPersistence {
    static let legacyDefaultsKey = "oshi_ai_chats_v1"

    enum LoadResult: Equatable { case data(Data), empty, unreadable }

    static var defaultURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ai_chats.json")
    }

    static func save(_ data: Data, url: URL = defaultURL) {
        do { try AtRestFile.write(data, to: url) }
        catch { OshiLog.storage.info("❌ AI chats save failed: \(error)") }
    }

    /// Reads the sealed file; if absent, migrates the legacy UserDefaults blob (write sealed,
    /// read back, and only then delete the cleartext copy — nothing is lost on failure).
    static func load(url: URL = defaultURL, defaults: UserDefaults = .standard) -> LoadResult {
        if FileManager.default.fileExists(atPath: url.path) {
            guard let data = try? AtRestFile.read(url) else { return .unreadable }
            // A leftover legacy blob (migration interrupted after the write) goes now.
            if defaults.object(forKey: legacyDefaultsKey) != nil { defaults.removeObject(forKey: legacyDefaultsKey) }
            return .data(data)
        }
        guard let legacy = defaults.data(forKey: legacyDefaultsKey) else { return .empty }
        do {
            try AtRestFile.write(legacy, to: url)
            guard try AtRestFile.read(url) == legacy else { return .data(legacy) }
            defaults.removeObject(forKey: legacyDefaultsKey)
            OshiLog.storage.info("🔐 AI chats migrated out of UserDefaults (\(legacy.count) bytes)")
        } catch {
            OshiLog.storage.info("❌ AI chats migration deferred: \(error)")
        }
        return .data(legacy)
    }
}
