//
//  MessagesListView.swift
//  View showing all conversations with alias support + LIGHT MODE FIX
//

import SwiftUI

struct MessagesListView: View {
    @EnvironmentObject var messageManager: MessageManager
    @EnvironmentObject var walletManager: WalletManager
    @State private var searchText = ""
    @AppStorage("contactAliases") private var contactAliasesData: Data = Data()
    @Environment(\.colorScheme) var colorScheme
    
    private var contactAliases: [String: String] {
        (try? JSONDecoder().decode([String: String].self, from: contactAliasesData)) ?? [:]
    }
    
    var filteredConversations: [Conversation] {
        if searchText.isEmpty {
            return messageManager.conversations
        } else {
            return messageManager.conversations.filter { conversation in
                let alias = contactAliases[conversation.participantPublicKey] ?? conversation.participantAddress
                return alias.localizedCaseInsensitiveContains(searchText) ||
                       conversation.participantAddress.localizedCaseInsensitiveContains(searchText)
            }
        }
    }
    
    var body: some View {
        NavigationView {
            ZStack {
                if messageManager.conversations.isEmpty {
                    EmptyStateView()
                } else {
                    List {
                        ForEach(filteredConversations) { conversation in
                            NavigationLink(destination: ChatView(conversation: conversation)) {
                                ConversationRow(
                                    conversation: conversation,
                                    walletManager: walletManager,
                                    alias: contactAliases[conversation.participantPublicKey]
                                )
                            }
                        }
                        .onDelete(perform: deleteConversations)
                    }
                    .searchable(text: $searchText, prompt: "Search conversations")
                }
            }
            .navigationTitle("Messages")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        Button {
                            // Export backup
                        } label: {
                            Label("Export Backup", systemImage: "square.and.arrow.up")
                        }
                        
                        Button(role: .destructive) {
                            messageManager.clearAllMessages()
                        } label: {
                            Label("Clear All", systemImage: "trash")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                            .foregroundColor(colorScheme == .dark ? .white : .primary) // ✅ Better visibility
                    }
                }
            }
        }
    }
    
    private func deleteConversations(at offsets: IndexSet) {
        for index in offsets {
            let conversation = filteredConversations[index]
            messageManager.deleteConversation(with: conversation.participantPublicKey)
        }
    }
}

struct ConversationRow: View {
    let conversation: Conversation
    let walletManager: WalletManager
    let alias: String?
    
    @EnvironmentObject var messageManager: MessageManager
    @State private var previewText: String = ""
    @State private var isDecrypting = false
    @Environment(\.colorScheme) var colorScheme
    
    private var displayName: String {
        alias ?? formatAddress(conversation.participantAddress)
    }
    
    var body: some View {
        HStack(spacing: 15) {
            // Avatar
            ZStack {
                Circle()
                    .fill(LinearGradient(
                        colors: [.blue, .purple],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ))
                    .frame(width: 50, height: 50)
                
                Text((alias ?? conversation.participantAddress).prefix(2).uppercased())
                    .font(.headline)
                    .foregroundColor(.white)
            }
            
            VStack(alignment: .leading, spacing: 5) {
                Text(displayName)
                    .font(.headline)
                    .foregroundColor(colorScheme == .dark ? .white : .black) // ✅ Adaptive
                
                if alias != nil {
                    Text(formatAddress(conversation.participantAddress))
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
                
                // Message preview
                if isDecrypting {
                    HStack(spacing: 5) {
                        ProgressView()
                            .scaleEffect(0.7)
                        Text("Decrypting...")
                            .font(.caption)
                    }
                    .foregroundColor(.secondary)
                } else if previewText.isEmpty {
                    Text("🔒 Encrypted message")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                } else {
                    Text(previewText)
                        .font(.subheadline)
                        .foregroundColor(colorScheme == .dark ? .secondary : Color(.darkGray)) // ✅ Better contrast in light
                        .lineLimit(1)
                }
            }
            
            Spacer()
            
            VStack(alignment: .trailing, spacing: 5) {
                Text(formatTimestamp(conversation.lastMessage.timestamp))
                    .font(.caption)
                    .foregroundColor(.secondary)
                
                if conversation.unreadCount > 0 {
                    ZStack {
                        Circle()
                            .fill(Color.blue)
                            .frame(width: 20, height: 20)
                        
                        Text("\(conversation.unreadCount)")
                            .font(.caption2)
                            .foregroundColor(.white)
                    }
                }
            }
        }
        .padding(.vertical, 5)
        .task {
            await decryptPreview()
        }
    }
    
    private func decryptPreview() async {
        guard !isDecrypting && previewText.isEmpty else { return }
        
        await MainActor.run { isDecrypting = true }
        
        let message = conversation.lastMessage
        let preview: String
        
        // Check if it's a self-sent message
        if message.senderPublicKey == message.recipientPublicKey {
            preview = message.plaintextContent ?? "Test message"
        } else if let plaintext = message.plaintextContent {
            // Sent message with plaintext
            preview = plaintext
        } else {
            // Decrypt received message in background
            let manager = messageManager
            let wallet = walletManager
            preview = await Task.detached(priority: .userInitiated) {
                manager.decryptMessage(message, using: wallet) ?? "🔒 Encrypted"
            }.value
        }
        
        await MainActor.run {
            previewText = preview
            isDecrypting = false
        }
    }
    
    private func formatAddress(_ address: String) -> String {
        guard address.count > 10 else { return address }
        let start = address.prefix(6)
        let end = address.suffix(4)
        return "\(start)...\(end)"
    }
    
    private func formatTimestamp(_ date: Date) -> String {
        let formatter = DateFormatter()
        
        if Calendar.current.isDateInToday(date) {
            formatter.dateFormat = "HH:mm"
        } else if Calendar.current.isDateInYesterday(date) {
            return "Yesterday"
        } else if Calendar.current.isDate(date, equalTo: Date(), toGranularity: .weekOfYear) {
            formatter.dateFormat = "EEEE"
        } else {
            formatter.dateFormat = "MMM d"
        }
        
        return formatter.string(from: date)
    }
}

struct EmptyStateView: View {
    @Environment(\.colorScheme) var colorScheme
    
    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "envelope.open")
                .font(.system(size: 80))
                .foregroundColor(colorScheme == .dark ? .gray : Color(.systemGray)) // ✅ Visible in both modes
            
            Text("No Messages Yet")
                .font(.title2)
                .fontWeight(.bold)
                .foregroundColor(colorScheme == .dark ? .white : .black) // ✅ Adaptive
            
            Text("Start a new conversation to send encrypted messages")
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)
        }
    }
}
