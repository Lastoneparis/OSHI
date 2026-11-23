//
//  ContactShareHandler.swift
//  OSHI - Contact Sharing with Auto-Navigation
//
//  Handles sharing contacts and automatically creating/navigating to conversations
//

import SwiftUI
import Contacts

// MARK: - Brand Color
private let appBrandColor = Theme.brand

// MARK: - Contact Share Handler

struct ContactShareHandler {
    
    /// Find existing conversation for a contact
    func findExistingConversation(for publicKey: String, in messages: [SecureMessage]) -> Conversation? {
        // Group messages by participant to find existing conversations
        let conversationMessages = Dictionary(grouping: messages) { message in
            message.senderAddress == publicKey ? publicKey : message.recipientAddress
        }
        
        // Find conversation with this public key
        if let messagesWithContact = conversationMessages[publicKey],
           let lastMessage = messagesWithContact.sorted(by: { $0.timestamp > $1.timestamp }).first {
            return Conversation(
                participantAddress: publicKey,
                participantPublicKey: publicKey,
                lastMessage: lastMessage,
                unreadCount: messagesWithContact.filter { !$0.isRead }.count
            )
        }
        
        return nil
    }
    
    /// Create a placeholder message for new conversation
    func createPlaceholderMessage(from senderKey: String, to recipientKey: String) -> SecureMessage {
        // Create a simple encrypted message placeholder
        let encryptedMsg = EncryptedMessage(
            ciphertext: "",
            signature: "",
            senderPublicKey: senderKey,
            timestamp: Date()
        )
        
        return SecureMessage(
            id: UUID().uuidString,
            senderAddress: senderKey,
            recipientAddress: recipientKey,
            encryptedContent: encryptedMsg,
            timestamp: Date(),
            isRead: true,
            deliveryStatus: .delivered
        )
    }
    
    /// Request contact access permission
    func requestContactAccess() async -> Bool {
        let contactStore = CNContactStore()
        let status = CNContactStore.authorizationStatus(for: .contacts)
        
        switch status {
        case .authorized:
            OshiLog.messaging.info("✅ Contact access already authorized")
            return true
            
        case .notDetermined:
            OshiLog.messaging.info("🔐 Requesting contact access...")
            do {
                let granted = try await contactStore.requestAccess(for: .contacts)
                OshiLog.messaging.info("\(granted ? "✅ Contact access granted" : "❌ Contact access denied")")
                return granted
            } catch {
                OshiLog.messaging.info("❌ Contact access error: \(error.localizedDescription)")
                return false
            }
            
        case .denied:
            OshiLog.messaging.info("❌ Contact access denied by user")
            return false
            
        case .restricted:
            OshiLog.messaging.info("❌ Contact access restricted")
            return false
        
        case .limited:
            OshiLog.messaging.info("⚠️ Contact access limited")
            return true
            
        @unknown default:
            OshiLog.messaging.info("⚠️ Unknown contact access status")
            return false
        }
    }
    
    /// Export contact to vCard format
    func exportToVCard(publicKey: String, alias: String?) -> String? {
        let contact = CNMutableContact()
        
        // Set name
        if let alias = alias {
            let components = alias.components(separatedBy: " ")
            contact.givenName = components.first ?? alias
            if components.count > 1 {
                contact.familyName = components[1...].joined(separator: " ")
            }
        } else {
            contact.givenName = "OSHI Contact"
        }
        
        // Add note with public key
        contact.note = "OSHI Public Key: \(publicKey)"
        
        do {
            let data = try CNContactVCardSerialization.data(with: [contact])
            return String(data: data, encoding: .utf8)
        } catch {
            OshiLog.messaging.info("❌ Failed to export vCard: \(error)")
            return nil
        }
    }
}

// MARK: - Share Contact Button

struct ShareContactButton: View {
    let publicKey: String
    let alias: String?
    @Binding var selectedConversation: Conversation?
    @Binding var navigateToChat: Bool
    @EnvironmentObject var messageManager: MessageManager
    @EnvironmentObject var identityManager: IdentityManager
    
    @State private var showingShareSheet = false
    @State private var showingSuccessAlert = false
    
    var body: some View {
        Menu {
            // Open Chat
            Button {
                openChat()
            } label: {
                Label("Open Chat", systemImage: "message")
            }
            
            // Share via System
            if let vCard = ContactShareHandler().exportToVCard(publicKey: publicKey, alias: alias) {
                Button {
                    shareVCard(vCard)
                } label: {
                    Label("Share Contact", systemImage: "square.and.arrow.up")
                }
            }
            
            // Copy Public Key
            Button {
                UIPasteboard.general.string = publicKey
                showingSuccessAlert = true
            } label: {
                Label("Copy Public Key", systemImage: "doc.on.doc")
            }
        } label: {
            Image(systemName: "ellipsis.circle")
                .font(.title3)
        }
        .alert("alert.public_key_copied".localized, isPresented: $showingSuccessAlert) {
            Button("common.ok".localized, role: .cancel) { }
        }
    }
    
    private func openChat() {
        let handler = ContactShareHandler()
        
        // Find or create conversation
        if let existing = handler.findExistingConversation(for: publicKey, in: messageManager.messages) {
            OshiLog.messaging.info("✅ Found existing conversation with: \(alias ?? publicKey.prefix(16).description)")
            selectedConversation = existing
        } else {
            // Create new conversation with placeholder message
            let placeholderMessage = handler.createPlaceholderMessage(
                from: identityManager.publicKey,
                to: publicKey
            )
            
            let newConversation = Conversation(
                participantAddress: publicKey,
                participantPublicKey: publicKey,
                lastMessage: placeholderMessage,
                unreadCount: 0
            )
            
            // ✅ CRITICAL: Add conversation to MessageManager
            if !messageManager.conversations.contains(where: { $0.participantPublicKey == publicKey }) {
                messageManager.conversations.append(newConversation)
                OshiLog.messaging.info("✅ Created and added new conversation with: \(alias ?? publicKey.prefix(16).description)")
            }
            
            selectedConversation = newConversation
        }
        
        // Navigate to chat
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
            navigateToChat = true
        }
    }
    
    private func shareVCard(_ vCard: String) {
        let activityVC = UIActivityViewController(
            activityItems: [vCard],
            applicationActivities: nil
        )
        
        if let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
           let rootVC = windowScene.windows.first?.rootViewController {
            rootVC.present(activityVC, animated: true)
        }
    }
}

// MARK: - Quick Chat Button (for Contact List)

struct QuickChatButton: View {
    let publicKey: String
    let alias: String?
    @Binding var selectedConversation: Conversation?
    @Binding var navigateToChat: Bool
    @EnvironmentObject var messageManager: MessageManager
    @EnvironmentObject var identityManager: IdentityManager
    
    var body: some View {
        Button {
            openChat()
        } label: {
            Image(systemName: "message.fill")
                .font(.title3)
                .foregroundColor(appBrandColor)
        }
        .buttonStyle(.plain)
    }
    
    private func openChat() {
        let handler = ContactShareHandler()
        
        // Find or create conversation
        if let existing = handler.findExistingConversation(for: publicKey, in: messageManager.messages) {
            selectedConversation = existing
        } else {
            // Create new conversation with placeholder
            let placeholderMessage = handler.createPlaceholderMessage(
                from: identityManager.publicKey,
                to: publicKey
            )
            
            let newConversation = Conversation(
                participantAddress: publicKey,
                participantPublicKey: publicKey,
                lastMessage: placeholderMessage,
                unreadCount: 0
            )
            
            // ✅ CRITICAL: Add conversation to MessageManager
            if !messageManager.conversations.contains(where: { $0.participantPublicKey == publicKey }) {
                messageManager.conversations.append(newConversation)
                OshiLog.messaging.info("✅ Created and added new conversation with: \(alias ?? publicKey.prefix(16).description)")
            }
            
            selectedConversation = newConversation
        }
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
            navigateToChat = true
        }
    }
}
