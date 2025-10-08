//
//  ChatView.swift
//  COMPLETE FIX: Navigation bar colors + light mode support
//

import SwiftUI

struct ChatView: View {
    let conversation: Conversation
    
    @EnvironmentObject var messageManager: MessageManager
    @EnvironmentObject var walletManager: WalletManager
    @EnvironmentObject var meshManager: MeshNetworkManager
    @Environment(\.colorScheme) var colorScheme // ✅ Add color scheme
    
    @State private var messageText = ""
    @State private var showingError = false
    @State private var errorMessage = ""
    @State private var isConnected = false
    @State private var showingAliasEditor = false
    @State private var showMediaPicker = false
    @State private var loadedMessages: [SecureMessage] = []
    @AppStorage("contactAliases") private var contactAliasesData: Data = Data()
    @StateObject private var mediaManager = MediaManager()
    
    private var contactAliases: [String: String] {
        get {
            (try? JSONDecoder().decode([String: String].self, from: contactAliasesData)) ?? [:]
        }
    }
    
    private var currentAlias: String {
        contactAliases[conversation.participantPublicKey] ?? formatAddress(conversation.participantAddress)
    }
    
    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                LazyVStack(spacing: 15) {
                    ForEach(loadedMessages) { message in
                        MessageBubble(
                            message: message,
                            currentUserPublicKey: walletManager.publicKey,
                            walletManager: walletManager,
                            meshManager: meshManager
                        )
                        .id(message.id)
                    }
                }
                .padding()
            }
            .defaultScrollAnchor(.bottom)
            
            MessageInputView(
                text: $messageText,
                onSend: sendMessage,
                onMediaTap: { showMediaPicker = true }
            )
        }
        .navigationTitle(currentAlias)
        .navigationBarTitleDisplayMode(.inline)
        // ✅ FIX: Set navigation bar appearance for light mode
        .toolbarColorScheme(colorScheme == .dark ? .dark : .light, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                HStack(spacing: 10) {
                    Circle()
                        .fill(isConnected ? Color.green : Color.gray)
                        .frame(width: 8, height: 8)
                    
                    Menu {
                        Button {
                            showingAliasEditor = true
                        } label: {
                            Label("Set Alias", systemImage: "person.text.rectangle")
                        }
                        
                        Button(role: .destructive) {
                            messageManager.deleteConversation(with: conversation.participantPublicKey)
                        } label: {
                            Label("Delete Conversation", systemImage: "trash")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                            .foregroundColor(colorScheme == .dark ? .white : .primary) // ✅ Adaptive
                    }
                }
            }
        }
        .alert("Set Alias", isPresented: $showingAliasEditor) {
            TextField("Enter alias", text: Binding(
                get: { currentAlias },
                set: { newAlias in
                    saveAlias(newAlias)
                }
            ))
            Button("Save") {}
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Give this contact a friendly name")
        }
        .alert("Error", isPresented: $showingError) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage)
        }
        .sheet(isPresented: $showMediaPicker) {
            MediaPickerView { attachment in
                sendMediaMessage(attachment)
            }
        }
        .onAppear {
            loadMessages()
            checkConnection()
            markMessagesAsRead()
        }
        .onChange(of: messageManager.messages) { _, _ in
            loadMessages()
        }
    }
    
    private func loadMessages() {
        loadedMessages = messageManager.messages.filter { message in
            (message.recipientPublicKey == conversation.participantPublicKey &&
             message.senderPublicKey == walletManager.publicKey) ||
            (message.senderPublicKey == conversation.participantPublicKey &&
             message.recipientPublicKey == walletManager.publicKey)
        }.sorted { $0.timestamp < $1.timestamp }
    }
    
    private func sendMessage() {
        guard !messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        
        print("💬 Sending reply in chat...")
        print("   To public key: \(conversation.participantPublicKey)")
        
        messageManager.sendMessage(
            content: messageText,
            recipientAddress: conversation.participantPublicKey,
            walletManager: walletManager,
            meshManager: meshManager
        )
        messageText = ""
        print("✅ Reply sent successfully")
    }
    
    private func sendMediaMessage(_ attachment: MediaManager.MediaAttachment) {
        print("📸 Sending media message...")
        
        messageManager.sendMediaMessage(
            attachment: attachment,
            recipientAddress: conversation.participantPublicKey,
            walletManager: walletManager,
            meshManager: meshManager,
            mediaManager: mediaManager
        )
        print("✅ Media sent successfully")
    }
    
    private func saveAlias(_ alias: String) {
        var aliases = contactAliases
        aliases[conversation.participantPublicKey] = alias
        if let encoded = try? JSONEncoder().encode(aliases) {
            contactAliasesData = encoded
        }
    }
    
    private func checkConnection() {
        isConnected = meshManager.connectedPeers.contains { peer in
            peer.displayName.contains(conversation.participantAddress.suffix(8))
        }
    }
    
    private func markMessagesAsRead() {
        let autoDeleteOnRead = UserDefaults.standard.bool(forKey: "autoDeleteOnRead")
        
        for message in loadedMessages where !message.isRead {
            messageManager.markAsRead(message.id)
            
            if autoDeleteOnRead {
                messageManager.deleteMessage(message.id)
            }
        }
    }
    
    private func formatAddress(_ address: String) -> String {
        guard address.count > 10 else { return address }
        let start = address.prefix(6)
        let end = address.suffix(4)
        return "\(start)...\(end)"
    }
}

struct MessageBubble: View {
    let message: SecureMessage
    let currentUserPublicKey: String
    let walletManager: WalletManager
    let meshManager: MeshNetworkManager
    
    @EnvironmentObject var messageManager: MessageManager
    @State private var decryptedText: String?
    @State private var isDecrypting = true
    
    @Environment(\.colorScheme) var colorScheme
    
    var isFromCurrentUser: Bool {
        message.senderPublicKey == currentUserPublicKey
    }
    
    var deliveryMethod: String {
        switch message.deliveryStatus {
        case .sent, .delivered:
            if meshManager.connectedPeers.isEmpty {
                return "📡 Network"
            } else {
                return "🔗 Mesh"
            }
        case .pending:
            return "⏳ Pending"
        case .failed:
            return "❌ Failed"
        }
    }
    
    private var bubbleBackground: Color {
        if isFromCurrentUser {
            return Color.blue
        } else {
            return colorScheme == .dark ? Color(.systemGray5) : Color(.systemGray6)
        }
    }
    
    private var bubbleForeground: Color {
        if isFromCurrentUser {
            return .white
        } else {
            return colorScheme == .dark ? .white : .black
        }
    }
    
    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            if isFromCurrentUser {
                Spacer()
            }
            
            VStack(alignment: isFromCurrentUser ? .trailing : .leading, spacing: 5) {
                // Check if it's a media message
                if message.mediaAttachment != nil {
                    MediaChatBubble(
                        message: message,
                        isFromCurrentUser: isFromCurrentUser
                    )
                    .environmentObject(walletManager)
                } else if isDecrypting {
                    HStack {
                        ProgressView()
                            .scaleEffect(0.8)
                        Text("Decrypting...")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    .padding(12)
                    .background(bubbleBackground.opacity(0.5))
                    .cornerRadius(15)
                } else if let text = decryptedText {
                    Text(text)
                        .padding(12)
                        .background(bubbleBackground)
                        .foregroundColor(bubbleForeground)
                        .cornerRadius(15)
                } else {
                    Text("🔒 Failed to decrypt")
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .padding(12)
                        .background(Color(.systemGray6))
                        .cornerRadius(15)
                }
                
                HStack(spacing: 5) {
                    Text(formatTimestamp(message.timestamp))
                        .font(.caption2)
                        .foregroundColor(.secondary)
                    
                    Text("•")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                    
                    Text(deliveryMethod)
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
            }
            
            if !isFromCurrentUser {
                Spacer()
            }
        }
        .task {
            await decryptMessage()
        }
    }
    
    private func decryptMessage() async {
        // If it's from current user, show immediately
        if isFromCurrentUser {
            await MainActor.run {
                decryptedText = message.plaintextContent ?? "[Message content unavailable]"
                isDecrypting = false
            }
            return
        }
        
        // Check if already decrypted
        if let plaintext = message.plaintextContent,
           !plaintext.isEmpty,
           !plaintext.starts(with: "eyJ"),
           plaintext != "[Media: Image]",
           plaintext != "[Media: Video]" {
            await MainActor.run {
                decryptedText = plaintext
                isDecrypting = false
            }
            return
        }
        
        // Decrypt on background thread
        let manager = messageManager
        let wallet = walletManager
        let msg = message
        
        let decrypted = await Task.detached(priority: .userInitiated) {
            manager.decryptMessage(msg, using: wallet)
        }.value
        
        await MainActor.run {
            decryptedText = decrypted ?? "[🔒 Encrypted]"
            isDecrypting = false
        }
    }
    
    private func formatTimestamp(_ date: Date) -> String {
        let formatter = DateFormatter()
        
        if Calendar.current.isDateInToday(date) {
            formatter.dateFormat = "HH:mm"
        } else if Calendar.current.isDateInYesterday(date) {
            return "Yesterday"
        } else {
            formatter.dateFormat = "MMM d"
        }
        
        return formatter.string(from: date)
    }
}

struct MessageInputView: View {
    @Binding var text: String
    let onSend: () -> Void
    let onMediaTap: () -> Void
    
    @Environment(\.colorScheme) var colorScheme
    
    private var inputBackground: Color {
        colorScheme == .dark ? Color(.systemGray6) : Color(.systemGray5)
    }
    
    private var isEmpty: Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
    
    var body: some View {
        HStack(spacing: 12) {
            Button(action: onMediaTap) {
                Image(systemName: "photo.on.rectangle")
                    .font(.title3)
                    .foregroundColor(.blue)
            }
            .frame(width: 44, height: 44)
            
            TextField("Message", text: $text, axis: .vertical)
                .textFieldStyle(.plain)
                .padding(10)
                .background(inputBackground)
                .cornerRadius(20)
                .lineLimit(1...5)
                .frame(minHeight: 40)
            
            // ✅ Send button with improved light mode visibility
            Button(action: onSend) {
                ZStack {
                    // Background circle for disabled state in light mode
                    if isEmpty && colorScheme == .light {
                        Circle()
                            .fill(Color(.systemGray4))
                            .frame(width: 32, height: 32)
                    }
                    
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 32))
                        .foregroundColor(
                            isEmpty
                                ? (colorScheme == .dark ? Color(.systemGray3) : Color(.systemGray2))
                                : .blue
                        )
                }
            }
            .frame(width: 44, height: 44)
            .disabled(isEmpty)
        }
        .padding()
        .background(Color(.systemBackground))
        .frame(minHeight: 60)
    }
}
