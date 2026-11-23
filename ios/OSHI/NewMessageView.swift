//
//  NewMessageView.swift
//  ✅ REDESIGNED: Modern, user-friendly UX
//  ✅ UNIFIED: Scan QR, Show QR, Paste Key all in one clean interface
//  ✅ FULLY LOCALIZED
//

import SwiftUI
import CoreImage.CIFilterBuiltins

// MARK: - Brand Color
private let brandColor = Theme.brand

struct NewMessageView: View {
    @EnvironmentObject var messageManager: MessageManager
    @EnvironmentObject var identityManager: IdentityManager
    @EnvironmentObject var meshManager: MeshNetworkManager

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.oledBlackActive) private var oledBlack

    @State private var recipientAddress = ""
    @State private var messageText = ""
    @State private var showingScanner = false
    @State private var showingSuccess = false
    @State private var showingError = false
    @State private var errorMessage = ""
    @State private var isValidAddress = false
    @State private var showMediaPicker = false
    /// Opens on MY CODE, not the scanner.
    ///
    /// 🔧 (2026-08-07) Scanning needs the OTHER person to already have their
    /// code on screen, so opening there shows a camera pointed at nothing. Both
    /// people reach for this screen at the same moment, and if it opens on the
    /// scanner for both, neither is showing anything to scan. Showing your own
    /// code first means one side is always ready, and the scanner is one tap
    /// away for whoever moves second.
    @State private var selectedTab: ContactTab = .myCode
    @State private var isViewOnceEnabled = false  // ✅ View once toggle
    @FocusState private var isMessageFieldFocused: Bool
    @FocusState private var isAddressFieldFocused: Bool

    // Navigation handled via OpenChat deep-link notification (switches to Messages tab)

    // Media attachment
    @State private var selectedMediaAttachments: [MediaManager.MediaAttachment] = []
    @State private var showMultiSelectOption = false
    
    enum ContactTab: String, CaseIterable {
        case scan = "new.tab.scan"
        case myCode = "new.tab.myCode"
        case paste = "new.tab.paste"
        
        var icon: String {
            switch self {
            case .scan: return "qrcode.viewfinder"
            case .myCode: return "qrcode"
            case .paste: return "doc.on.clipboard"
            }
        }
    }
    
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    // Header Icon
                    headerSection
                        .padding(.top, 8)

                    // Tab Selection
                    contactTabSelector

                    // Tab Content
                    tabContent

                    // Show preview while navigating
                    if !recipientAddress.isEmpty && isValidAddress {
                        recipientPreview
                            .transition(.move(edge: .top).combined(with: .opacity))
                    }

                    Spacer(minLength: 50)
                }
                .animation(.easeInOut(duration: 0.25), value: isValidAddress)
                .padding(.horizontal)
                // Cap content width so the compose form stays centered and
                // legible on iPad / landscape instead of stretching wide.
                .frame(maxWidth: 600)
                .frame(maxWidth: .infinity)
            }
            .background(themedBackground)
            .navigationTitle("new.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            // Navigation handled via OpenChat notification → switches to Messages tab
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("common.done".localized) {
                        isMessageFieldFocused = false
                        isAddressFieldFocused = false
                    }
                }
            }
            .sheet(isPresented: $showingScanner) {
                QRScannerView(scannedAddress: $recipientAddress)
            }
            .onChange(of: showingScanner) { _, isShowing in
                // Auto-open conversation after QR scanner sheet closes with a valid address.
                // Skip the artificial delay — by the time we get here, the sheet is gone,
                // so jumping straight to navigation eliminates the visible "Messages list
                // flash" the user sees between scan and chat.
                if !isShowing && !recipientAddress.isEmpty {
                    validateAddress()
                    if isValidAddress {
                        openConversation()
                    }
                }
            }
            .onChange(of: recipientAddress) { _, newAddress in
                // Also trigger when scannedAddress is set (backup for sheet dismiss race)
                guard !newAddress.isEmpty, showingScanner else { return }
                let trimmed = newAddress.trimmingCharacters(in: .whitespacesAndNewlines)
                // Handle QR codes that might have a prefix like "oshi:", custom URL,
                // or a regular https://oshi-messenger.{org,com}/add?key=… link.
                let cleanKey: String = MessagesListView.extractPublicKeyFromQRPayload(trimmed)
                if cleanKey.count >= 20 && cleanKey != identityManager.publicKey {
                    recipientAddress = cleanKey
                    isValidAddress = true
                    showingScanner = false // Dismiss scanner
                    // Fire navigation on the next runloop tick so the sheet has time
                    // to begin dismissing — but no artificial delay beyond that.
                    DispatchQueue.main.async {
                        openConversation()
                    }
                }
            }
            .sheet(isPresented: $showMediaPicker) {
                MediaPickerView(
                    allowMultipleSelection: showMultiSelectOption,
                    onMediaSelected: { attachment in
                        selectedMediaAttachments.append(attachment)
                    },
                    onMultipleMediaSelected: { attachments in
                        selectedMediaAttachments.append(contentsOf: attachments)
                    }
                )
            }
            .alert("common.success".localized, isPresented: $showingSuccess) {
                Button("common.ok".localized) { clearForm() }
            } message: {
                Text("new.message.sent".localized)
            }
            .alert("common.error".localized, isPresented: $showingError) {
                Button("common.ok".localized, role: .cancel) {}
            } message: {
                Text(errorMessage)
            }
        }
    }
    
    // MARK: - Themed Background

    /// Subtle brand-purple wash matching the AI tab and the rest of the app, so
    /// the compose screen isn't a flat grouped-background. Kept faint (fainter
    /// still in dark mode) so the inner cards keep clear contrast against it.
    private var themedBackground: some View {
        LinearGradient(
            // __OLED_BLACK_2026_11_16__ true black under OLED black.
            colors: oledBlack ? [.black, .black]
                : colorScheme == .dark
                ? [Color(white: 0.06), brandColor.opacity(0.10)]
                : [Color(.systemGroupedBackground), brandColor.opacity(0.07)],
            startPoint: .top,
            endPoint: .bottom
        )
        .ignoresSafeArea()
    }

    // MARK: - Header

    private var headerSection: some View {
        VStack(spacing: 8) {
            ZStack {
                Circle()
                    .fill(
                        LinearGradient(
                            colors: [brandColor.opacity(0.15), brandColor.opacity(0.05)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 70, height: 70)
                
                Image(systemName: "envelope.badge.fill")
                    .font(.scaled(30))
                    .foregroundStyle(
                        LinearGradient(
                            colors: [brandColor, brandColor.opacity(0.7)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
            }
            
            Text("new.hero.title".localized)
                .font(.headline)
            
            Text("new.hero.subtitle".localized)
                .font(.caption)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
    }
    
    // MARK: - Tab Selector
    
    private var contactTabSelector: some View {
        HStack(spacing: 0) {
            ForEach(ContactTab.allCases, id: \.self) { tab in
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        selectedTab = tab
                    }
                } label: {
                    VStack(spacing: 6) {
                        Image(systemName: tab.icon)
                            .font(.scaled(20))
                        Text(tab.rawValue.localized)
                            .font(.caption2)
                            .fontWeight(.medium)
                    }
                    .foregroundColor(selectedTab == tab ? .white : .secondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(
                        RoundedRectangle(cornerRadius: 10)
                            .fill(selectedTab == tab ? brandColor : Color.clear)
                    )
                }
            }
        }
        .padding(4)
        .oledGlassCard(RoundedRectangle(cornerRadius: 14), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(14)
    }
    
    // MARK: - Tab Content
    
    @ViewBuilder
    private var tabContent: some View {
        switch selectedTab {
        case .scan:
            scanQRContent
        case .myCode:
            myQRContent
        case .paste:
            pasteKeyContent
        }
    }
    
    // MARK: - Scan QR Tab
    
    private var scanQRContent: some View {
        VStack(spacing: 16) {
            Button {
                showingScanner = true
            } label: {
                VStack(spacing: 16) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 20)
                            .stroke(brandColor.opacity(0.3), style: StrokeStyle(lineWidth: 2, dash: [10]))
                            .frame(height: 180)
                        
                        VStack(spacing: 12) {
                            Image(systemName: "camera.viewfinder")
                                .font(.scaled(50))
                                .foregroundColor(brandColor)
                            
                            Text("new.scan.tapToScan".localized)
                                .font(.headline)
                                .foregroundColor(brandColor)
                            
                            Text("new.scan.hint".localized)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                }
            }
            .buttonStyle(.plain)
        }
        .padding()
        .oledGlassCard(RoundedRectangle(cornerRadius: 16), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(16)
    }
    
    // MARK: - My QR Code Tab
    
    private var myQRContent: some View {
        VStack(spacing: 16) {
            // QR Code
            if let qrImage = generateQRCode(from: identityManager.publicKey) {
                Image(uiImage: qrImage)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 180, height: 180)
                    .padding(16)
                    .background(Color.white)
                    .cornerRadius(16)
                    .shadow(color: .black.opacity(0.1), radius: 8, x: 0, y: 4)
                    .accessibilityLabel("a11y.my_qr_code".localized)
            }
            
            Text("new.myCode.shareHint".localized)
                .font(.caption)
                .foregroundColor(.secondary)
            
            // My Public Key (truncated)
            HStack {
                Text(formatAddress(identityManager.publicKey))
                    .font(.system(.caption, design: .monospaced))
                    .foregroundColor(.secondary)
                
                Spacer()
                
                Button {
                    UIPasteboard.general.string = identityManager.publicKey
                    let generator = UINotificationFeedbackGenerator()
                    generator.notificationOccurred(.success)
                } label: {
                    Image(systemName: "doc.on.doc")
                        .foregroundColor(brandColor)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("a11y.copy_my_key".localized)
            }
            .padding()
            .background(Color(.systemBackground))
            .cornerRadius(10)
            
            // Share Button
            Button {
                shareMyKey()
            } label: {
                HStack {
                    Image(systemName: "square.and.arrow.up")
                    Text("new.myCode.share".localized)
                }
                .font(.subheadline)
                .fontWeight(.medium)
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(brandColor)
                .cornerRadius(10)
            }
        }
        .padding()
        .oledGlassCard(RoundedRectangle(cornerRadius: 16), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(16)
    }
    
    // MARK: - Paste Key Tab
    
    private var pasteKeyContent: some View {
        VStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Image(systemName: "key.fill")
                        .foregroundColor(brandColor)
                    Text("new.paste.recipientKey".localized)
                        .font(.subheadline)
                        .fontWeight(.medium)
                }
                
                HStack {
                    TextField("new.paste.placeholder".localized, text: $recipientAddress)
                        .font(.system(.body, design: .monospaced))
                        .autocapitalization(.none)
                        .autocorrectionDisabled()
                        .focused($isAddressFieldFocused)
                        .submitLabel(.go)
                        .onSubmit {
                            validateAddress()
                            if isValidAddress {
                                openConversation()
                            }
                        }
                        .onChange(of: recipientAddress) { _, _ in
                            validateAddress()
                        }
                    
                    // Paste button — auto-opens conversation if valid
                    Button {
                        if let pasteboardString = UIPasteboard.general.string {
                            recipientAddress = pasteboardString.trimmingCharacters(in: .whitespacesAndNewlines)
                            validateAddress()
                            if isValidAddress {
                                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                                    openConversation()
                                }
                            }
                        }
                    } label: {
                        Image(systemName: "doc.on.clipboard")
                            .foregroundColor(brandColor)
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel("a11y.paste_key".localized)

                    // Clear button
                    if !recipientAddress.isEmpty {
                        Button {
                            recipientAddress = ""
                            isValidAddress = false
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundColor(.secondary)
                                .frame(width: 44, height: 44)
                                .contentShape(Rectangle())
                        }
                        .accessibilityLabel("a11y.clear_field".localized)
                        .transition(.opacity.combined(with: .scale))
                    }
                }
                .padding()
                .background(Color(.systemBackground))
                .cornerRadius(10)
                
                // Validation indicator
                if !recipientAddress.isEmpty {
                    HStack {
                        Image(systemName: isValidAddress ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                        Text(isValidAddress ? "new.paste.valid".localized : "new.paste.invalid".localized)
                            .font(.caption)
                    }
                    .foregroundColor(isValidAddress ? .green : .orange)
                    .accessibilityElement(children: .combine)
                    .transition(.opacity)
                }
            }
            .animation(.easeInOut(duration: 0.2), value: isValidAddress)
            .animation(.easeInOut(duration: 0.2), value: recipientAddress.isEmpty)
        }
        .padding()
        .oledGlassCard(RoundedRectangle(cornerRadius: 16), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(16)
    }

    // MARK: - Recipient Preview
    
    private var recipientPreview: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(brandColor.opacity(0.15))
                    .frame(width: 44, height: 44)
                
                Image(systemName: "person.fill")
                    .foregroundColor(brandColor)
            }
            
            VStack(alignment: .leading, spacing: 2) {
                Text("new.recipient.title".localized)
                    .font(.caption)
                    .foregroundColor(.secondary)
                Text(formatAddress(recipientAddress))
                    .font(.system(.subheadline, design: .monospaced))
                    .fontWeight(.medium)
            }
            
            Spacer()

            Image(systemName: "checkmark.circle.fill")
                .foregroundColor(.green)
        }
        .padding()
        .oledGlassCard(RoundedRectangle(cornerRadius: 12), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(12)
        .accessibilityElement(children: .combine)
    }

    // MARK: - Message Input
    
    private var messageInputSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "text.bubble.fill")
                    .foregroundColor(brandColor)
                Text("new.composer.message".localized)
                    .font(.subheadline)
                    .fontWeight(.medium)
                
                Spacer()
                
                // Multi-select attachment button
                Menu {
                    Button {
                        showMultiSelectOption = false
                        showMediaPicker = true
                    } label: {
                        Label("new.composer.attach.single".localized, systemImage: "photo")
                    }
                    
                    Button {
                        showMultiSelectOption = true
                        showMediaPicker = true
                    } label: {
                        Label("new.composer.attach.multiple".localized, systemImage: "photo.stack")
                    }
                } label: {
                    Image(systemName: "paperclip")
                        .foregroundColor(brandColor)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("a11y.attach_media".localized)
            }

            TextEditor(text: $messageText)
                .privateTypingComposer()  // __KEYBOARD_PRIVACY_2026_10_01__ no autocorrect learning when Private typing is on
                .frame(minHeight: 100)
                .padding(8)
                .background(Color(.systemBackground))
                .cornerRadius(10)
                .focused($isMessageFieldFocused)
        }
        .padding()
        .oledGlassCard(RoundedRectangle(cornerRadius: 16), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(16)
    }
    
    // MARK: - Media Previews
    
    private var mediaPreviews: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "photo.stack.fill")
                    .foregroundColor(brandColor)
                Text("\(selectedMediaAttachments.count) " + "new.composer.attachments".localized)
                    .font(.subheadline)
                    .fontWeight(.medium)
                
                Spacer()
                
                Button {
                    selectedMediaAttachments.removeAll()
                } label: {
                    Text("new.composer.clearAll".localized)
                        .font(.caption)
                        .foregroundColor(.red)
                }
            }
            
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(selectedMediaAttachments.indices, id: \.self) { index in
                        let attachment = selectedMediaAttachments[index]
                        ZStack(alignment: .topTrailing) {
                            if let image = MediaManager.safeUIImage(from: attachment.data, maxDimension: 200) {
                                Image(uiImage: image)
                                    .resizable()
                                    .scaledToFill()
                                    .frame(width: 70, height: 70)
                                    .cornerRadius(8)
                                    .clipped()
                            } else {
                                RoundedRectangle(cornerRadius: 8)
                                    .fill(Color.gray.opacity(0.3))
                                    .frame(width: 70, height: 70)
                                    .overlay(
                                        Image(systemName: attachment.type == .video ? "video.fill" : "doc.fill")
                                            .foregroundColor(.secondary)
                                    )
                            }
                            
                            Button {
                                selectedMediaAttachments.remove(at: index)
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .foregroundColor(.white)
                                    .background(Circle().fill(Color.black.opacity(0.6)))
                            }
                            .offset(x: 5, y: -5)
                            .accessibilityLabel("a11y.remove_attachment".localized)
                        }
                    }
                }
            }
            
            // ✅ View Once Toggle
            HStack {
                Toggle(isOn: $isViewOnceEnabled) {
                    HStack(spacing: 8) {
                        Image(systemName: isViewOnceEnabled ? "eye.slash.fill" : "eye.fill")
                            .foregroundColor(isViewOnceEnabled ? .orange : .secondary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("new.composer.viewOnce".localized)
                                .font(.subheadline)
                                .fontWeight(.medium)
                            Text("new.composer.viewOnce.desc".localized)
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                    }
                }
                .toggleStyle(SwitchToggleStyle(tint: .orange))
            }
            .padding(.top, 8)
        }
        .padding()
        .oledGlassCard(RoundedRectangle(cornerRadius: 16), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(16)
    }
    
    // MARK: - Send Button
    
    private var sendButton: some View {
        Button {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            sendMessage()
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "paperplane.fill")
                Text("new.composer.send".localized)
            }
            .font(.headline)
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(
                LinearGradient(
                    colors: canSend ? [brandColor, brandColor.opacity(0.8)] : [Color.gray, Color.gray.opacity(0.8)],
                    startPoint: .leading,
                    endPoint: .trailing
                )
            )
            .cornerRadius(14)
            .shadow(color: canSend ? brandColor.opacity(0.3) : .clear, radius: 8, x: 0, y: 4)
        }
        .disabled(!canSend)
    }
    
    // MARK: - Helpers

    private var canSend: Bool {
        isValidAddress && (!messageText.isEmpty || !selectedMediaAttachments.isEmpty)
    }

    private func validateAddress() {
        let trimmed = recipientAddress.trimmingCharacters(in: .whitespacesAndNewlines)
        isValidAddress = trimmed.count >= 20 && trimmed != identityManager.publicKey
    }

    /// 🔧 Create or reuse conversation and navigate to ChatView
    /// Posts OpenChat notification which switches to Messages tab and opens the conversation.
    /// Uses retry pattern to handle view mount timing across tab switch.
    private func openConversation() {
        let trimmedAddress = recipientAddress.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedAddress.isEmpty, isValidAddress else { return }

        // Ensure conversation exists before deep-linking.
        messageManager.addPendingConversation(publicKey: trimmedAddress, identityManager: identityManager)

        // Single navigation transaction: post OpenChat, ContentView handles
        // tab switch + pendingConversationId in a no-animation transaction,
        // MessagesListView's .onAppear / .onChange triggers navigateToConversation.
        //
        // Previously we ALSO posted `StartChatWithContact` here as a "backup
        // path" — but that listener (MessagesListView.handleStartChatNotification)
        // drives navigation via a *different* state (`newChatConversation` +
        // `navigateToNotificationChat`), and the two handlers firing in
        // sequence pushed the chat, the second handler re-evaluated the nav
        // stack, and the chat appeared to "open, bounce back to the list,
        // re-open". Now that OpenChat has no artificial delay, the backup
        // is unnecessary and was causing the visible double-push.
        NotificationCenter.default.post(
            name: NSNotification.Name("OpenChat"),
            object: nil,
            userInfo: ["peerPublicKey": trimmedAddress]
        )
    }

    private func formatAddress(_ address: String) -> String {
        guard address.count > 16 else { return address }
        return "\(address.prefix(8))...\(address.suffix(8))"
    }
    
    private func generateQRCode(from string: String) -> UIImage? {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        
        guard let outputImage = filter.outputImage else { return nil }
        let transform = CGAffineTransform(scaleX: 10, y: 10)
        let scaledImage = outputImage.transformed(by: transform)
        
        guard let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
    
    private func shareMyKey() {
        let publicKey = identityManager.publicKey
        let encodedKey = publicKey.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? publicKey
        let deepLinkURL = "https://oshi-messenger.org/add?key=\(encodedKey)"
        // 🔧 FIX (2026-08-07, CORRUPTED SHARED KEYS): the key used to be the last
        // thing in this string. Pasted into WhatsApp, iOS's data detectors then
        // latched onto the trailing base64 and APPENDED a unit conversion —
        // observed in the wild as "…muFE3A=0.0741 acre". The pasted key was
        // silently wrong and adding the contact failed with no explanation.
        //
        // Two defences, because either alone is fragile:
        //   * the key sits between markers, so a detector has a boundary to stop
        //     at instead of running to end-of-string;
        //   * something follows it, so it is never the final token.
        //
        // The link above is what people should actually tap; the raw key is the
        // fallback for anyone who cannot open links.
        let shareText = """
        Add me on OSHI!

        \(deepLinkURL)

        My public key:
        ⟦\(publicKey)⟧

        Copy everything between the brackets.
        """

        let activityVC = UIActivityViewController(activityItems: [shareText], applicationActivities: nil)
        
        if let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
           let window = windowScene.windows.first,
           let rootVC = window.rootViewController {
            rootVC.present(activityVC, animated: true)
        }
    }
    
    private func sendMessage() {
        guard isValidAddress else {
            errorMessage = "new.error.invalidRecipient".localized
            showingError = true
            return
        }
        
        // Send text message if present
        if !messageText.isEmpty {
            messageManager.sendMessage(
                content: messageText,
                recipientAddress: recipientAddress,
                identityManager: identityManager,
                meshManager: meshManager
            )
        }
        
        // Send media attachments
        for var attachment in selectedMediaAttachments {
            // Apply view once setting
            attachment.isViewOnce = isViewOnceEnabled
            
            do {
                try messageManager.sendMediaMessage(
                    attachment: attachment,
                    recipientAddress: recipientAddress,
                    identityManager: identityManager,
                    meshManager: meshManager,
                    mediaManager: MediaManager()
                )
            } catch {
                OshiLog.messaging.info("❌ Failed to send media: \(error)")
                errorMessage = "new.error.mediaSend".localized
                showingError = true
                return
            }
        }
        
        showingSuccess = true
    }
    
    private func clearForm() {
        recipientAddress = ""
        messageText = ""
        isValidAddress = false
        selectedMediaAttachments.removeAll()
        isViewOnceEnabled = false
    }
}

#Preview {
    NewMessageView()
        .environmentObject(MessageManager())
        .environmentObject(IdentityManager())
        .environmentObject(MeshNetworkManager())
}
