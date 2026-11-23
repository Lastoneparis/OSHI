//
//  PinataCleanupService.swift
//  OSHI
//
//  ✅ ENHANCED: Properly deletes messages after receipt + 30 day auto-cleanup
//  Manages IPFS/Pinata message lifecycle
//

import Foundation
import SwiftUI

class PinataCleanupService {
    static let shared = PinataCleanupService()
    
    // MARK: - Configuration
    // 🔧 FIX: Use VPS proxy for Pinata (not direct!)
    private let pinataAPIBaseURL = "https://oshi-messenger.com/api/pinata"
    private var pinataJWT: String {
        UserDefaults.standard.string(forKey: "pinata_jwt") ?? ""
    }
    
    private let vpsBaseURL = "https://oshi-messenger.com/api/call"
    private let retentionDays: Int = 30
    private let sentMessagesKey = "oshi_sent_ipfs_messages"
    private let receivedMessagesKey = "oshi_received_ipfs_messages"  // ✅ NEW: Track received messages
    
    // MARK: - Models
    struct SentIPFSMessage: Codable {
        let ipfsHash: String
        let messageId: String
        let recipientPublicKey: String
        let sentAt: Date
        var isDelivered: Bool
        var deliveredAt: Date?
        var isGroupMessage: Bool  // ✅ NEW: Track if it's a group message
    }
    
    struct ReceivedIPFSMessage: Codable {
        let ipfsHash: String
        let senderPublicKey: String
        let receivedAt: Date
        var isDeleted: Bool
    }
    
    struct CleanupStatistics {
        let totalTracked: Int
        let delivered: Int
        let pending: Int
        let expiringSoon: Int
        let oldestMessage: Date?
        let receivedPendingDeletion: Int
    }
    
    // MARK: - Initialization
    private init() {
        startBackgroundCleanup()
    }
    
    // MARK: - Configuration
    func configure(jwt: String) {
        UserDefaults.standard.set(jwt, forKey: "pinata_jwt")
        OshiLog.messaging.info("✅ PinataCleanup: Configured with JWT")
    }
    
    // MARK: - Track Sent Messages
    func trackSentMessage(ipfsHash: String, messageId: String, recipientPublicKey: String, isGroupMessage: Bool = false) {
        var messages = getSentMessages()
        
        // Avoid duplicates
        if messages.contains(where: { $0.ipfsHash == ipfsHash }) {
            return
        }
        
        let newMessage = SentIPFSMessage(
            ipfsHash: ipfsHash,
            messageId: messageId,
            recipientPublicKey: recipientPublicKey,
            sentAt: Date(),
            isDelivered: false,
            deliveredAt: nil,
            isGroupMessage: isGroupMessage
        )
        messages.append(newMessage)
        saveSentMessages(messages)
        OshiLog.messaging.info("📝 PinataCleanup: Tracking sent message \(ipfsHash.prefix(12))...")
    }
    
    // MARK: - Track Received Messages (for deletion)
    func trackReceivedMessage(ipfsHash: String, senderPublicKey: String) {
        var messages = getReceivedMessages()
        
        // Avoid duplicates
        if messages.contains(where: { $0.ipfsHash == ipfsHash }) {
            return
        }
        
        let newMessage = ReceivedIPFSMessage(
            ipfsHash: ipfsHash,
            senderPublicKey: senderPublicKey,
            receivedAt: Date(),
            isDeleted: false
        )
        messages.append(newMessage)
        saveReceivedMessages(messages)
        OshiLog.messaging.info("📝 PinataCleanup: Tracking received message \(ipfsHash.prefix(12))...")
        
        // Immediately trigger deletion for received messages
        Task {
            await deleteReceivedMessageFromPinata(ipfsHash: ipfsHash)
        }
    }
    
    // ✅ NEW: Delete received message from Pinata
    func deleteReceivedMessageFromPinata(ipfsHash: String) async {
        // First try to notify sender via VPS that message was received
        await notifySenderOfDelivery(ipfsHash: ipfsHash)
        
        // Then request deletion via VPS proxy
        await requestDeletionViaVPS(ipfsHash: ipfsHash)
        
        // Mark as deleted locally
        var messages = getReceivedMessages()
        if let index = messages.firstIndex(where: { $0.ipfsHash == ipfsHash }) {
            messages[index].isDeleted = true
            saveReceivedMessages(messages)
        }
    }
    
    // ✅ NEW: Notify sender that their message was delivered
    private func notifySenderOfDelivery(ipfsHash: String) async {
        do {
            let url = URL(string: "\(vpsBaseURL)/api/delivery-receipt/\(ipfsHash)")!
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            
            let body: [String: Any] = [
                "ipfsHash": ipfsHash,
                "deliveredAt": ISO8601DateFormatter().string(from: Date())
            ]
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            
            let (_, response) = try await URLSession.shared.data(for: request)
            
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                OshiLog.messaging.info("✅ PinataCleanup: Notified sender of delivery for \(ipfsHash.prefix(12))...")
            }
        } catch {
            OshiLog.messaging.info("⚠️ PinataCleanup: Failed to notify sender: \(error)")
        }
    }
    
    // ✅ NEW: Request VPS to delete from Pinata (receiver side)
    private func requestDeletionViaVPS(ipfsHash: String) async {
        do {
            let url = URL(string: "\(vpsBaseURL)/api/ipfs/cleanup/\(ipfsHash)")!
            var request = URLRequest(url: url)
            request.httpMethod = "DELETE"
            
            let (_, response) = try await URLSession.shared.data(for: request)
            
            if let httpResponse = response as? HTTPURLResponse {
                if httpResponse.statusCode == 200 {
                    OshiLog.messaging.info("✅ PinataCleanup: Requested VPS to delete \(ipfsHash.prefix(12))...")
                } else {
                    OshiLog.messaging.info("⚠️ PinataCleanup: VPS cleanup returned status \(httpResponse.statusCode)")
                }
            }
        } catch {
            OshiLog.messaging.info("⚠️ PinataCleanup: VPS cleanup request failed: \(error)")
        }
    }
    
    // MARK: - Mark as Delivered (sender side)
    func markAsDelivered(ipfsHash: String) async {
        var messages = getSentMessages()
        if let index = messages.firstIndex(where: { $0.ipfsHash == ipfsHash }) {
            messages[index].isDelivered = true
            messages[index].deliveredAt = Date()
            saveSentMessages(messages)
            OshiLog.messaging.info("✅ PinataCleanup: Marked \(ipfsHash.prefix(12))... as delivered")
            
            // Delete from Pinata since message was delivered
            await deleteFromPinata(ipfsHash: ipfsHash)
            await removeFromVPSPending(ipfsHash: ipfsHash)
        }
    }
    
    func markAsDeliveredByMessageId(_ messageId: String) async {
        let messages = getSentMessages()
        if let message = messages.first(where: { $0.messageId == messageId }) {
            await markAsDelivered(ipfsHash: message.ipfsHash)
        }
    }
    
    // MARK: - Cleanup Operations
    func deleteFromPinata(ipfsHash: String) async {
        guard !pinataJWT.isEmpty else {
            OshiLog.messaging.info("⚠️ PinataCleanup: No JWT configured, using VPS proxy")
            await deleteViaVPS(ipfsHash: ipfsHash)
            return
        }
        
        do {
            let url = URL(string: "\(pinataAPIBaseURL)/pinning/unpin/\(ipfsHash)")!
            var request = URLRequest(url: url)
            request.httpMethod = "DELETE"
            request.setValue("Bearer \(pinataJWT)", forHTTPHeaderField: "Authorization")
            
            let (_, response) = try await URLSession.shared.data(for: request)
            
            if let httpResponse = response as? HTTPURLResponse {
                if httpResponse.statusCode == 200 {
                    OshiLog.messaging.info("✅ PinataCleanup: Deleted \(ipfsHash.prefix(12))... from Pinata")
                    removeFromTracking(ipfsHash: ipfsHash)
                } else if httpResponse.statusCode == 404 {
                    OshiLog.messaging.info("⚠️ PinataCleanup: Hash not found on Pinata (already deleted?)")
                    removeFromTracking(ipfsHash: ipfsHash)
                } else {
                    OshiLog.messaging.info("❌ PinataCleanup: Delete failed with status \(httpResponse.statusCode)")
                }
            }
        } catch {
            OshiLog.messaging.info("❌ PinataCleanup: Delete error: \(error)")
        }
    }
    
    private func deleteViaVPS(ipfsHash: String) async {
        do {
            let url = URL(string: "\(vpsBaseURL)/api/ipfs/delete/\(ipfsHash)")!
            var request = URLRequest(url: url)
            request.httpMethod = "DELETE"
            
            let (_, response) = try await URLSession.shared.data(for: request)
            
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                OshiLog.messaging.info("✅ PinataCleanup: Deleted \(ipfsHash.prefix(12))... via VPS")
                removeFromTracking(ipfsHash: ipfsHash)
            }
        } catch {
            OshiLog.messaging.info("❌ PinataCleanup: VPS delete error: \(error)")
        }
    }
    
    private func removeFromVPSPending(ipfsHash: String) async {
        do {
            let url = URL(string: "\(vpsBaseURL)/api/pending/remove/\(ipfsHash)")!
            var request = URLRequest(url: url)
            request.httpMethod = "DELETE"
            
            let (_, response) = try await URLSession.shared.data(for: request)
            
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                OshiLog.messaging.info("✅ PinataCleanup: Removed from VPS pending")
            }
        } catch {
            OshiLog.messaging.info("❌ PinataCleanup: VPS pending removal error: \(error)")
        }
    }
    
    // MARK: - 30-Day Auto Cleanup
    func cleanupOldMessages() async {
        let messages = getSentMessages()
        guard let cutoffDate = Calendar.current.date(byAdding: .day, value: -retentionDays, to: Date()) else { return }
        
        var cleanedCount = 0
        for message in messages {
            // Delete if older than 30 days regardless of delivery status
            if message.sentAt < cutoffDate {
                OshiLog.messaging.info("🗑️ PinataCleanup: Message \(message.ipfsHash.prefix(12))... is older than \(retentionDays) days")
                await deleteFromPinata(ipfsHash: message.ipfsHash)
                await removeFromVPSPending(ipfsHash: message.ipfsHash)
                cleanedCount += 1
            }
        }
        
        // Also cleanup old received messages tracking
        var receivedMessages = getReceivedMessages()
        receivedMessages.removeAll { $0.receivedAt < cutoffDate }
        saveReceivedMessages(receivedMessages)
        
        if cleanedCount > 0 {
            OshiLog.messaging.info("✅ PinataCleanup: Cleaned up \(cleanedCount) old messages")
        }
    }
    
    func cleanupDeliveredMessages() async {
        let messages = getSentMessages()
        for message in messages where message.isDelivered {
            await deleteFromPinata(ipfsHash: message.ipfsHash)
        }
    }
    
    // ✅ NEW: Cleanup all pending received messages
    func cleanupPendingReceivedMessages() async {
        let messages = getReceivedMessages()
        for message in messages where !message.isDeleted {
            await deleteReceivedMessageFromPinata(ipfsHash: message.ipfsHash)
        }
    }
    
    // MARK: - Background Cleanup
    private func startBackgroundCleanup() {
        // Run daily cleanup
        Timer.scheduledTimer(withTimeInterval: 86400, repeats: true) { [weak self] _ in
            Task {
                await self?.cleanupOldMessages()
                await self?.cleanupPendingReceivedMessages()
            }
        }
        
        // Initial cleanup after 30 seconds
        DispatchQueue.main.asyncAfter(deadline: .now() + 30) { [weak self] in
            Task {
                await self?.cleanupOldMessages()
                await self?.cleanupPendingReceivedMessages()
            }
        }
        
        // Cleanup received messages after 5 seconds (for quick cleanup)
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
            Task {
                await self?.cleanupPendingReceivedMessages()
            }
        }
    }
    
    // MARK: - Persistence
    private func getSentMessages() -> [SentIPFSMessage] {
        guard let data = UserDefaults.standard.data(forKey: sentMessagesKey),
              let messages = try? JSONDecoder().decode([SentIPFSMessage].self, from: data) else {
            return []
        }
        return messages
    }
    
    private func saveSentMessages(_ messages: [SentIPFSMessage]) {
        if let data = try? JSONEncoder().encode(messages) {
            UserDefaults.standard.set(data, forKey: sentMessagesKey)
        }
    }
    
    private func getReceivedMessages() -> [ReceivedIPFSMessage] {
        guard let data = UserDefaults.standard.data(forKey: receivedMessagesKey),
              let messages = try? JSONDecoder().decode([ReceivedIPFSMessage].self, from: data) else {
            return []
        }
        return messages
    }
    
    private func saveReceivedMessages(_ messages: [ReceivedIPFSMessage]) {
        if let data = try? JSONEncoder().encode(messages) {
            UserDefaults.standard.set(data, forKey: receivedMessagesKey)
        }
    }
    
    private func removeFromTracking(ipfsHash: String) {
        var messages = getSentMessages()
        messages.removeAll { $0.ipfsHash == ipfsHash }
        saveSentMessages(messages)
    }
    
    // MARK: - Statistics
    func getStatistics() -> CleanupStatistics {
        let messages = getSentMessages()
        let receivedMessages = getReceivedMessages()
        let fiveDaysAgo = Calendar.current.date(byAdding: .day, value: -25, to: Date()) ?? Date()
        
        return CleanupStatistics(
            totalTracked: messages.count,
            delivered: messages.filter { $0.isDelivered }.count,
            pending: messages.filter { !$0.isDelivered }.count,
            expiringSoon: messages.filter { !$0.isDelivered && $0.sentAt < fiveDaysAgo }.count,
            oldestMessage: messages.min(by: { $0.sentAt < $1.sentAt })?.sentAt,
            receivedPendingDeletion: receivedMessages.filter { !$0.isDeleted }.count
        )
    }
}

// MARK: - MessageManager Extension

extension MessageManager {
    /// Call this when a message is successfully received from IPFS
    func notifyMessageReceived(ipfsHash: String, senderPublicKey: String = "") {
        // Track for deletion
        PinataCleanupService.shared.trackReceivedMessage(
            ipfsHash: ipfsHash,
            senderPublicKey: senderPublicKey
        )
    }
    
    /// Call this when you send a message via IPFS
    func trackIPFSMessage(ipfsHash: String, messageId: String, recipientPublicKey: String, isGroupMessage: Bool = false) {
        PinataCleanupService.shared.trackSentMessage(
            ipfsHash: ipfsHash,
            messageId: messageId,
            recipientPublicKey: recipientPublicKey,
            isGroupMessage: isGroupMessage
        )
    }
    
    /// Call this when you receive a delivery receipt
    func handleDeliveryReceipt(for ipfsHash: String) {
        Task {
            await PinataCleanupService.shared.markAsDelivered(ipfsHash: ipfsHash)
        }
    }
}

// MARK: - VPS Endpoints Required
/*
 The VPS needs these endpoints for full cleanup support:
 
 1. POST /api/delivery-receipt/{ipfsHash}
    - Stores delivery receipt for sender to poll
    - Body: { "ipfsHash": "...", "deliveredAt": "..." }
 
 2. DELETE /api/ipfs/cleanup/{ipfsHash}
    - Proxied Pinata unpin request
    - Uses VPS Pinata JWT to delete
 
 3. GET /api/delivery-receipts/{senderPublicKey}
    - Returns array of delivery receipts for this sender
    - Sender polls this to know which messages to delete
 
 4. DELETE /api/ipfs/delete/{ipfsHash}
    - Already exists - direct Pinata delete via VPS
*/

// MARK: - Settings View

struct PinataCleanupSettingsView: View {
    @State private var statistics: PinataCleanupService.CleanupStatistics?
    @State private var isLoading = false
    @State private var pinataJWT: String = UserDefaults.standard.string(forKey: "pinata_jwt") ?? ""
    @State private var showingJWTField = false
    
    private let brandColor = Theme.brand
    
    var body: some View {
        List {
            Section {
                if let stats = statistics {
                    StatRow(label: "cleanup.stats.total".localized, value: "\(stats.totalTracked)")
                    StatRow(label: "cleanup.stats.delivered".localized, value: "\(stats.delivered)")
                    StatRow(label: "cleanup.stats.pending".localized, value: "\(stats.pending)")
                    StatRow(label: "cleanup.stats.received".localized, value: "\(stats.receivedPendingDeletion)")
                    
                    if stats.expiringSoon > 0 {
                        StatRow(label: "cleanup.stats.expiring".localized, value: "\(stats.expiringSoon)")
                            .foregroundColor(.orange)
                    }
                    
                    if let oldest = stats.oldestMessage {
                        HStack {
                            Text("cleanup.stats.oldest".localized)
                            Spacer()
                            Text(oldest, style: .date)
                                .foregroundColor(.secondary)
                        }
                    }
                } else {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                }
            } header: {
                Text("cleanup.section.stats".localized)
            }
            
            Section {
                Button {
                    Task {
                        isLoading = true
                        await PinataCleanupService.shared.cleanupOldMessages()
                        await refreshStatistics()
                        isLoading = false
                    }
                } label: {
                    HStack {
                        Image(systemName: "calendar.badge.minus")
                            .foregroundColor(.orange)
                        Text("cleanup.action.old".localized)
                        if isLoading {
                            Spacer()
                            ProgressView()
                        }
                    }
                }
                .disabled(isLoading)
                
                Button {
                    Task {
                        isLoading = true
                        await PinataCleanupService.shared.cleanupDeliveredMessages()
                        await refreshStatistics()
                        isLoading = false
                    }
                } label: {
                    HStack {
                        Image(systemName: "checkmark.circle")
                            .foregroundColor(.green)
                        Text("cleanup.action.delivered".localized)
                    }
                }
                .disabled(isLoading)
                
                Button {
                    Task {
                        isLoading = true
                        await PinataCleanupService.shared.cleanupPendingReceivedMessages()
                        await refreshStatistics()
                        isLoading = false
                    }
                } label: {
                    HStack {
                        Image(systemName: "arrow.down.circle")
                            .foregroundColor(.blue)
                        Text("cleanup.action.received".localized)
                    }
                }
                .disabled(isLoading)
            } header: {
                Text("cleanup.section.actions".localized)
            } footer: {
                Text("cleanup.footer.info".localized)
            }
            
            Section {
                Button {
                    showingJWTField.toggle()
                } label: {
                    HStack {
                        Image(systemName: "key")
                            .foregroundColor(brandColor)
                        Text("cleanup.pinata.configure".localized)
                        Spacer()
                        Image(systemName: pinataJWT.isEmpty ? "xmark.circle.fill" : "checkmark.circle.fill")
                            .foregroundColor(pinataJWT.isEmpty ? .red : .green)
                    }
                }
                
                if showingJWTField {
                    SecureField("cleanup.pinata.jwt.placeholder".localized, text: $pinataJWT)
                        .textContentType(.password)
                    
                    Button("cleanup.pinata.save".localized) {
                        PinataCleanupService.shared.configure(jwt: pinataJWT)
                        showingJWTField = false
                    }
                    .disabled(pinataJWT.isEmpty)
                }
            } header: {
                Text("cleanup.section.config".localized)
            }
        }
        .navigationTitle("cleanup.title".localized)
        .onAppear {
            Task {
                await refreshStatistics()
            }
        }
    }
    
    private func refreshStatistics() async {
        await MainActor.run {
            statistics = PinataCleanupService.shared.getStatistics()
        }
    }
}

struct StatRow: View {
    let label: String
    let value: String
    
    var body: some View {
        HStack {
            Text(label)
            Spacer()
            Text(value)
                .foregroundColor(.secondary)
                .fontWeight(.medium)
        }
    }
}
