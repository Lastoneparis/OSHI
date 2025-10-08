//
//  MessageManager.swift
//  COMPLETE VERSION WITH VPS URLs + Out-of-Order + isInitiator FIX
//

import Foundation
import Combine
import CryptoKit
import UIKit

// MARK: - Ratchet Error Types

enum RatchetError: Error {
    case messageSkipped
    case tooManySkippedMessages
    case authenticationFailed
    case invalidMessage
}

class MessageManager: ObservableObject {
    @Published var conversations: [Conversation] = []
    @Published var messages: [SecureMessage] = []
    
    // MARK: - VPS Server Configuration
    private let vpsPublicURL = "http://45.67.216.197"
    private let vpsTorURL = "http://qak57gcjrxwvy3nz7lbgizmxjffgaekcfz7yimw6z6dx7y3yyc7zriid.onion"
    
    private var currentVPSURL: String {
        return vpsPublicURL
    }
    
    private var cancellables = Set<AnyCancellable>()
    private let ratchetManager = DoubleRatchetSessionManager.shared
    private var meshReconnectionTimer: Timer?
    private var pendingMeshMessages: [(message: SecureMessage, recipient: String)] = []
    static var sharedMeshManager: MeshNetworkManager?
    
    static weak var shared: MessageManager?
    static var sharedWalletManager: WalletManager?
    
    private let maxMessagesInMemory = 30
    
    // Out-of-order message handling
    private var pendingDecryption: [String: [SecureMessage]] = [:]
    private let maxPendingPerSender = 50
    private var decryptionRetryTimer: Timer?
    
    init() {
        loadMessages()
        setupMessageListener()
        cleanupUserDefaults()
        
        DispatchQueue.main.async {
            MessageManager.shared = self
        }
        
        startIPFSPolling()
        startMeshReconnectionMonitor()
        startPendingDecryptionRetry()
    }
    
    deinit {
        meshReconnectionTimer?.invalidate()
        decryptionRetryTimer?.invalidate()
    }
    
    // MARK: - Helper: Determine if we're initiator
    
    /// Returns true if we're starting a NEW conversation (we're the initiator)
    /// Returns false if conversation already exists (we're responder or continuing)
    private func isInitiator(with publicKey: String) -> Bool {
        // If we have NO messages with this person, we're initiating
        let hasExistingConversation = messages.contains { message in
            (message.senderPublicKey == publicKey || message.recipientPublicKey == publicKey)
        }
        
        return !hasExistingConversation
    }
    
    private func cleanupUserDefaults() {
        UserDefaults.standard.removeObject(forKey: "messageQueue")
        UserDefaults.standard.synchronize()
    }
    
    private func startIPFSPolling() {
        // Check immediately on startup
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
            self?.checkPendingIPFSMessages()
        }
        
        // Then check every 10 seconds (increased frequency)
        Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            self?.checkPendingIPFSMessages()
        }
    }
    
    // MARK: - Mesh Reconnection Monitor
    
    private func startMeshReconnectionMonitor() {
        // Check every 30 seconds if we should retry mesh for pending messages
        meshReconnectionTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            self?.retryPendingMeshMessages()
        }
    }
    
    // MARK: - Pending Decryption Retry
    
    private func startPendingDecryptionRetry() {
        // Retry pending decryptions every 30 seconds
        decryptionRetryTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            self?.retryAllPendingDecryption()
        }
    }
    
    private func retryAllPendingDecryption() {
        guard !pendingDecryption.isEmpty else { return }
        
        print("🔄 Retrying \(pendingDecryption.values.flatMap { $0 }.count) pending message(s)...")
        let senders = Array(pendingDecryption.keys)
        
        for sender in senders {
            tryDecryptPendingMessages(from: sender)
        }
    }
    
    private func tryDecryptPendingMessages(from senderKey: String) {
        guard let pending = pendingDecryption[senderKey], !pending.isEmpty else { return }
        guard let walletManager = MessageManager.sharedWalletManager else { return }
        
        print("🔓 Trying to decrypt \(pending.count) pending message(s) from \(senderKey.prefix(10))...")
        
        var decryptedIds: [String] = []
        
        for var pendingMessage in pending {
            if let decrypted = decryptMessage(pendingMessage, using: walletManager) {
                pendingMessage.plaintextContent = decrypted
                addMessage(pendingMessage)
                decryptedIds.append(pendingMessage.id)
                print("✅ Decrypted pending message: \(decrypted.prefix(30))...")
            }
        }
        
        // Remove successfully decrypted messages
        if !decryptedIds.isEmpty {
            pendingDecryption[senderKey] = pending.filter { !decryptedIds.contains($0.id) }
            
            if pendingDecryption[senderKey]?.isEmpty == true {
                pendingDecryption.removeValue(forKey: senderKey)
            }
            
            print("✅ Decrypted \(decryptedIds.count) pending message(s), \(pendingDecryption[senderKey]?.count ?? 0) remaining")
        }
    }
    
    private func retryPendingMeshMessages() {
        guard !pendingMeshMessages.isEmpty else { return }
        guard let meshManager = MessageManager.sharedMeshManager else { return }
        
        // Only retry if we have connected peers
        guard !meshManager.connectedPeers.isEmpty else {
            print("⏭️ No mesh peers, keeping \(pendingMeshMessages.count) messages queued")
            return
        }
        
        print("🔄 Mesh reconnected! Retrying \(pendingMeshMessages.count) pending message(s)...")
        
        let messagesToRetry = pendingMeshMessages
        pendingMeshMessages.removeAll()
        
        for (message, recipient) in messagesToRetry {
            Task {
                await retryMessageViaMesh(message: message, recipientAddress: recipient)
            }
        }
    }
    
    private func retryMessageViaMesh(message: SecureMessage, recipientAddress: String) async {
        guard let meshManager = MessageManager.sharedMeshManager else { return }
        
        do {
            try meshManager.sendMessage(message, to: recipientAddress)
            print("✅ Queued message sent via mesh after reconnection!")
            
            await MainActor.run {
                if let index = self.messages.firstIndex(where: { $0.id == message.id }) {
                    self.messages[index].deliveryStatus = .sent
                    self.messages[index].deliveryMethod = .mesh
                    self.saveMessages()
                }
            }
        } catch {
            // Still can't send, re-add to pending
            pendingMeshMessages.append((message, recipientAddress))
            print("⚠️ Mesh still unavailable for this recipient, will retry later")
        }
    }
    
    private func checkPendingIPFSMessages() {
        guard let walletManager = MessageManager.sharedWalletManager else {
            print("⏭️ No wallet manager")
            return
        }
        
        print("🔍 Checking for pending IPFS messages...")
        print("🔑 My public key: \(walletManager.publicKey.prefix(10))...")
        
        Task {
            do {
                // Properly encode base64 characters: +, /, =
                let encodedKey = walletManager.publicKey
                    .addingPercentEncoding(withAllowedCharacters: CharacterSet.alphanumerics)
                    ?? walletManager.publicKey
                let fullURL = "\(currentVPSURL)/api/pending/\(encodedKey)"
                
                print("📡 Polling URL: \(fullURL)")
                
                guard let url = URL(string: fullURL) else {
                    print("❌ Invalid URL")
                    return
                }
                
                let (data, response) = try await URLSession.shared.data(from: url)
                
                guard let httpResponse = response as? HTTPURLResponse else {
                    print("❌ No HTTP response")
                    return
                }
                
                if httpResponse.statusCode == 404 {
                    print("⏭️ No pending messages")
                    return
                }
                
                guard httpResponse.statusCode == 200 else {
                    print("❌ HTTP \(httpResponse.statusCode)")
                    return
                }
                
                // Parse response - should be array of IPFS hashes
                let ipfsHashes = try JSONDecoder().decode([String].self, from: data)
                
                if ipfsHashes.isEmpty {
                    print("⏭️ No pending messages")
                    return
                }
                
                print("📥 Found \(ipfsHashes.count) pending message(s)")
                
                for hash in ipfsHashes {
                    print("📦 Fetching IPFS: \(hash)")
                    
                    do {
                        let message = try await fetchFromIPFS(ipfsHash: hash)
                        
                        print("🔍 Message details:")
                        print("   ID: \(message.id)")
                        print("   From: \(message.senderPublicKey.prefix(10))...")
                        print("   To: \(message.recipientPublicKey.prefix(10))...")
                        print("   My key: \(walletManager.publicKey.prefix(10))...")
                        print("   Plaintext: \(message.plaintextContent != nil ? "✅" : "❌")")
                        
                        // Don't process messages we sent (sender check)
                        if message.senderPublicKey == walletManager.publicKey {
                            print("⏭️ Skipping own sent message")
                            try await markAsReceived(ipfsHash: hash, publicKey: walletManager.publicKey)
                            continue
                        }
                        
                        // Verify this message is actually for us
                        if message.recipientPublicKey != walletManager.publicKey {
                            print("⚠️ Message not for us (for: \(message.recipientPublicKey.prefix(10))...)")
                            try await markAsReceived(ipfsHash: hash, publicKey: walletManager.publicKey)
                            continue
                        }
                        
                        print("✅ Processing incoming message...")
                        
                        await MainActor.run { [weak self] in
                            guard let self = self else { return }
                            
                            // Check if we already have this message
                            if let existingIndex = self.messages.firstIndex(where: { $0.id == message.id }) {
                                let existingMethod = self.messages[existingIndex].deliveryMethod?.rawValue ?? "none"
                                print("⏭️ Message already exists, preserving original delivery method: \(existingMethod)")
                                return
                            }
                            
                            var updatedMessage = message
                            // Mark as received via IPFS/Cloud (network)
                            updatedMessage.deliveryMethod = .ipfs
                            print("   🌐 Tagging as Cloud delivery (received from IPFS)")
                            
                            self.receiveMessage(updatedMessage)
                        }
                        
                        // Notify VPS that we received it
                        try await markAsReceived(ipfsHash: hash, publicKey: walletManager.publicKey)
                        
                        print("✅ Received message via IPFS")
                        
                    } catch {
                        print("❌ Failed to fetch \(hash): \(error.localizedDescription)")
                    }
                }
                
            } catch {
                print("⏭️ IPFS poll error: \(error.localizedDescription)")
            }
        }
    }
    
    private func fetchFromIPFS(ipfsHash: String) async throws -> SecureMessage {
        let url = URL(string: "https://gateway.pinata.cloud/ipfs/\(ipfsHash)")!
        
        print("🌐 Fetching from IPFS gateway...")
        let (data, response) = try await URLSession.shared.data(from: url)
        
        if let httpResponse = response as? HTTPURLResponse {
            print("📡 IPFS gateway response: \(httpResponse.statusCode)")
            guard httpResponse.statusCode == 200 else {
                throw NSError(domain: "IPFS", code: httpResponse.statusCode, userInfo: [NSLocalizedDescriptionKey: "Gateway returned \(httpResponse.statusCode)"])
            }
        }
        
        print("📦 Downloaded \(data.count) bytes from IPFS")
        
        // Try to decode the IPFS response
        if let messageDict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            print("✅ Decoded JSON structure")
            print("   Keys: \(messageDict.keys.joined(separator: ", "))")
            
            guard let id = messageDict["id"] as? String,
                  let senderAddress = messageDict["senderAddress"] as? String,
                  let recipientAddress = messageDict["recipientAddress"] as? String,
                  let timestamp = messageDict["timestamp"] as? TimeInterval,
                  let encryptedContentBase64 = messageDict["encryptedContent"] as? String else {
                print("❌ Missing required fields in message")
                throw NSError(domain: "IPFS", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid message format"])
            }
            
            guard let encryptedContentData = Data(base64Encoded: encryptedContentBase64) else {
                print("❌ Failed to decode base64 encrypted content")
                throw NSError(domain: "IPFS", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid base64"])
            }
            
            // Decode the EncryptedMessage
            let encryptedMessage = try JSONDecoder().decode(EncryptedMessage.self, from: encryptedContentData)
            
            print("✅ Successfully decoded encrypted message")
            
            let message = SecureMessage(
                id: id,
                senderAddress: senderAddress,
                recipientAddress: recipientAddress,
                encryptedContent: encryptedMessage,
                timestamp: Date(timeIntervalSince1970: timestamp),
                isRead: false,
                deliveryStatus: .delivered,
                senderPublicKey: senderAddress,
                recipientPublicKey: recipientAddress,
                plaintextContent: nil,
                mediaAttachment: nil,
                deliveryMethod: nil
            )
            
            // Try to decrypt immediately
            if let walletManager = MessageManager.sharedWalletManager {
                if let decrypted = decryptMessage(message, using: walletManager) {
                    var decryptedMessage = message
                    decryptedMessage.plaintextContent = decrypted
                    print("✅ Message decrypted: \(decrypted.prefix(50))...")
                    return decryptedMessage
                } else {
                    print("❌ Could not decrypt message, will show encrypted")
                }
            }
            
            return message
        }
        
        print("⚠️ Not a JSON dict, trying direct decode...")
        return try JSONDecoder().decode(SecureMessage.self, from: data)
    }
    
    private func markAsReceived(ipfsHash: String, publicKey: String) async throws {
        let encodedKey = publicKey.addingPercentEncoding(withAllowedCharacters: CharacterSet.alphanumerics) ?? publicKey
        let fullURL = "\(currentVPSURL)/api/received/\(encodedKey)/\(ipfsHash)"
        
        guard let url = URL(string: fullURL) else { return }
        
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        
        let (_, _) = try await URLSession.shared.data(for: request)
        print("✅ Marked message as received on VPS")
    }
    
    private func storeIPFSHash(messageId: String, ipfsHash: String) {
        UserDefaults.standard.set(ipfsHash, forKey: "ipfs_\(messageId)")
    }
    
    func sendMessage(
        content: String,
        recipientAddress: String,
        walletManager: WalletManager,
        meshManager: MeshNetworkManager
    ) {
        MessageManager.sharedWalletManager = walletManager
        MessageManager.sharedMeshManager = meshManager
        
        Task.detached {
            do {
                let sharedSecret = try walletManager.computeSharedSecret(with: recipientAddress)
                
                // ✅ FIX: Determine if we're initiator
                let isInitiator = await MainActor.run {
                    self.isInitiator(with: recipientAddress)
                }
                
                let session = DoubleRatchetSessionManager.shared.getOrCreateSession(
                    with: recipientAddress,
                    sharedSecret: sharedSecret,
                    isInitiator: isInitiator  // ✅ Added parameter
                )
                
                let ratchetMessage = try session.encrypt(content)
                let ratchetData = try JSONEncoder().encode(ratchetMessage)
                let encryptedMessage = try walletManager.wrapRatchetMessage(ratchetData, for: recipientAddress)
                
                let message = SecureMessage(
                    id: UUID().uuidString,
                    senderAddress: walletManager.publicKey,
                    recipientAddress: recipientAddress,
                    encryptedContent: encryptedMessage,
                    timestamp: Date(),
                    isRead: false,
                    deliveryStatus: .pending,
                    senderPublicKey: walletManager.publicKey,
                    recipientPublicKey: recipientAddress,
                    plaintextContent: content,
                    mediaAttachment: nil,
                    deliveryMethod: .pending
                )
                
                let messageId = message.id
                
                await MainActor.run { [message] in
                    guard let manager = MessageManager.shared else { return }
                    manager.addMessage(message)
                }
                
                print("📤 Attempting to send via mesh...")
                
                // Try mesh first with 3-second timeout
                let meshTask = Task {
                    try meshManager.sendMessage(message, to: recipientAddress)
                }
                
                do {
                    // Wait up to 3 seconds for mesh delivery
                    try await withTimeout(seconds: 3) {
                        try await meshTask.value
                    }
                    
                    await MainActor.run {
                        guard let manager = MessageManager.shared,
                              let index = manager.messages.firstIndex(where: { $0.id == messageId }) else { return }
                        
                        manager.messages[index].deliveryMethod = .mesh
                        manager.messages[index].deliveryStatus = .sent
                        manager.saveMessages()
                        print("✅ Sent via mesh network (Direct)")
                        print("   📱 Tagged as: Direct (mesh)")
                    }
                } catch is TimeoutError {
                    meshTask.cancel()
                    print("⏱️ Mesh timeout after 3 seconds, trying IPFS fallback...")
                } catch {
                    print("⚠️ Mesh unavailable, trying IPFS fallback...")
                    
                    do {
                        let ipfsHash = try await self.uploadToIPFS(
                            message: message,
                            recipientPublicKey: recipientAddress
                        )
                        
                        await MainActor.run {
                            guard let manager = MessageManager.shared,
                                  let index = manager.messages.firstIndex(where: { $0.id == messageId }) else { return }
                            
                            manager.messages[index].deliveryStatus = .sent
                            manager.messages[index].deliveryMethod = .ipfs
                            manager.saveMessages()
                            manager.storeIPFSHash(messageId: messageId, ipfsHash: ipfsHash)
                            manager.pendingMeshMessages.append((message, recipientAddress))
                            print("✅ Sent via IPFS (Cloud)")
                            print("   ☁️ Tagged as: Cloud (network)")
                            print("📋 Queued for mesh retry when reconnected")
                        }
                    } catch {
                        print("❌ IPFS upload failed: \(error)")
                        await MainActor.run {
                            guard let manager = MessageManager.shared,
                                  let index = manager.messages.firstIndex(where: { $0.id == messageId }) else { return }
                            manager.messages[index].deliveryStatus = .failed
                            manager.saveMessages()
                        }
                    }
                }
            } catch {
                print("❌ Error: \(error)")
            }
        }
    }
    
    private func uploadToIPFS(message: SecureMessage, recipientPublicKey: String) async throws -> String {
        let encryptedData = try JSONEncoder().encode(message.encryptedContent)
        
        let messageDict: [String: Any] = [
            "id": message.id,
            "senderAddress": message.senderAddress,
            "recipientAddress": message.recipientAddress,
            "timestamp": message.timestamp.timeIntervalSince1970,
            "encryptedContent": encryptedData.base64EncodedString()
        ]
        
        let ipfsHash = try await PinataService.shared.pinJSON(
            messageDict,
            name: "message_\(message.id)"
        )
        
        try await notifyVPS(ipfsHash: ipfsHash, recipientPublicKey: recipientPublicKey)
        
        return ipfsHash
    }
    
    private func notifyVPS(ipfsHash: String, recipientPublicKey: String) async throws {
        let encodedKey = recipientPublicKey
            .addingPercentEncoding(withAllowedCharacters: CharacterSet.alphanumerics) ?? recipientPublicKey
        let fullURL = "\(currentVPSURL)/api/queue/\(encodedKey)/\(ipfsHash)"
        
        print("📡 Notifying VPS: \(fullURL)")
        
        guard let url = URL(string: fullURL) else {
            print("❌ Invalid VPS URL")
            throw NSError(domain: "VPS", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid URL"])
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 10
        
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            
            if let httpResponse = response as? HTTPURLResponse {
                print("📡 VPS response: \(httpResponse.statusCode)")
                if httpResponse.statusCode != 200 {
                    let responseString = String(data: data, encoding: .utf8) ?? "No response"
                    print("❌ VPS error: \(responseString)")
                }
            }
        } catch {
            print("❌ VPS notification failed: \(error)")
            throw error
        }
        print("✅ Notified VPS to queue message")
    }
    
    func sendMediaMessage(
        attachment: MediaManager.MediaAttachment,
        recipientAddress: String,
        walletManager: WalletManager,
        meshManager: MeshNetworkManager,
        mediaManager: MediaManager
    ) {
        MessageManager.sharedWalletManager = walletManager
        MessageManager.sharedMeshManager = meshManager
        
        Task.detached {
            do {
                let sharedSecret = try walletManager.computeSharedSecret(with: recipientAddress)
                let key = SymmetricKey(data: sharedSecret.prefix(32))
                
                let encryptedMedia = try await MainActor.run {
                    try mediaManager.encryptMedia(attachment, with: key)
                }
                
                print("🔒 Media encrypted (\(attachment.fileSizeFormatted))")
                
                let markerData = "MEDIA_MESSAGE".data(using: .utf8)!
                let markerString = String(data: markerData, encoding: .utf8)!
                let encryptedMarker = try walletManager.encrypt(message: markerString, recipientPublicKey: recipientAddress)
                
                let message = SecureMessage(
                    id: UUID().uuidString,
                    senderAddress: walletManager.publicKey,
                    recipientAddress: recipientAddress,
                    encryptedContent: encryptedMarker,
                    timestamp: Date(),
                    isRead: false,
                    deliveryStatus: .pending,
                    senderPublicKey: walletManager.publicKey,
                    recipientPublicKey: recipientAddress,
                    plaintextContent: "[Media: \(attachment.type.rawValue)]",
                    mediaAttachment: encryptedMedia,
                    deliveryMethod: .pending,
                    mediaType: attachment.type,
                    originalMediaData: attachment.data
                )
                
                let messageId = message.id
                
                await MainActor.run { [message] in
                    guard let manager = MessageManager.shared else { return }
                    manager.addMessage(message)
                }
                
                print("📤 Attempting to send media via mesh...")
                
                do {
                    try meshManager.sendMessage(message, to: recipientAddress)
                    
                    await MainActor.run {
                        guard let manager = MessageManager.shared,
                              let index = manager.messages.firstIndex(where: { $0.id == messageId }) else { return }
                        manager.messages[index].deliveryStatus = .sent
                        manager.messages[index].deliveryMethod = .mesh
                        manager.saveMessages()
                        print("✅ Media sent via mesh (Direct)")
                        print("   📱 Tagged as: Direct")
                    }
                } catch {
                    print("⚠️ Mesh unavailable for media, trying IPFS...")
                    
                    do {
                        let ipfsHash = try await self.uploadToIPFS(
                            message: message,
                            recipientPublicKey: recipientAddress
                        )
                        
                        await MainActor.run {
                            guard let manager = MessageManager.shared,
                                  let index = manager.messages.firstIndex(where: { $0.id == messageId }) else { return }
                            manager.messages[index].deliveryStatus = .sent
                            manager.messages[index].deliveryMethod = .ipfs
                            manager.saveMessages()
                            manager.storeIPFSHash(messageId: messageId, ipfsHash: ipfsHash)
                            print("✅ Media sent via IPFS (Cloud)")
                            print("   ☁️ Tagged as: Cloud")
                        }
                    } catch {
                        await MainActor.run {
                            guard let manager = MessageManager.shared,
                                  let index = manager.messages.firstIndex(where: { $0.id == messageId }) else { return }
                            manager.messages[index].deliveryStatus = .failed
                            manager.saveMessages()
                        }
                    }
                }
            } catch {
                print("❌ Error sending media: \(error)")
            }
        }
    }
    
    private func setupMessageListener() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleIncomingMessage(_:)),
            name: .didReceiveMessage,
            object: nil
        )
    }
    
    @objc private func handleIncomingMessage(_ notification: Notification) {
        guard let message = notification.object as? SecureMessage else { return }
        receiveMessage(message)
    }
    
    func receiveMessage(_ message: SecureMessage) {
        print("📨 Processing received message...")
        print("   Message ID: \(message.id)")
        print("   Delivery method from source: \(message.deliveryMethod?.rawValue ?? "nil")")
        print("   Current message count: \(messages.count)")
        
        guard !messages.contains(where: { $0.id == message.id }) else {
            print("⏭️ Duplicate message ignored (ID already exists)")
            return
        }
        
        print("✅ New message, adding to storage...")
        
        var receivedMessage = message
        receivedMessage.deliveryStatus = .delivered
        receivedMessage.isRead = false
        
        if receivedMessage.deliveryMethod == nil {
            receivedMessage.deliveryMethod = .mesh
            print("   📡 Received via Mesh (direct)")
        } else {
            print("   ☁️ Received via \(receivedMessage.deliveryMethod!.rawValue)")
        }
        
        if let walletManager = MessageManager.sharedWalletManager {
            if receivedMessage.plaintextContent == nil || receivedMessage.plaintextContent?.starts(with: "eyJ") == true {
                if let decrypted = decryptMessage(receivedMessage, using: walletManager) {
                    receivedMessage.plaintextContent = decrypted
                    print("✅ Message decrypted on receipt")
                    
                    addMessage(receivedMessage)
                    tryDecryptPendingMessages(from: message.senderPublicKey)
                    
                } else {
                    print("⏸️ Could not decrypt - adding to pending queue")
                    
                    if pendingDecryption[message.senderPublicKey] == nil {
                        pendingDecryption[message.senderPublicKey] = []
                    }
                    
                    if pendingDecryption[message.senderPublicKey]!.count < maxPendingPerSender {
                        pendingDecryption[message.senderPublicKey]?.append(receivedMessage)
                        print("📋 Queued for later decryption (queue: \(pendingDecryption[message.senderPublicKey]!.count))")
                    } else {
                        print("⚠️ Pending queue full, dropping message")
                    }
                }
            } else {
                addMessage(receivedMessage)
            }
        } else {
            print("⚠️ No wallet manager available")
            addMessage(receivedMessage)
        }
        
        print("   New message count: \(messages.count)")
    }
    
    private func addMessage(_ message: SecureMessage) {
        messages.append(message)
        saveMessages()
        updateConversations()
        
        NotificationCenter.default.post(
            name: NSNotification.Name("NewMessageReceived"),
            object: message
        )
    }
    
    func decryptMessage(
        _ message: SecureMessage,
        using walletManager: WalletManager
    ) -> String? {
        if let plaintext = message.plaintextContent,
           !plaintext.starts(with: "eyJ"),
           !plaintext.isEmpty,
           !plaintext.contains("[🔒"),
           plaintext != "[Media: Image]",
           plaintext != "[Media: Video]" {
            return plaintext
        }
        
        print("🔓 Attempting to decrypt message ID: \(message.id.prefix(8))...")
        
        // Try Double Ratchet first
        do {
            print("   Trying Double Ratchet decryption...")
            let ratchetData = try walletManager.unwrapRatchetMessage(message.encryptedContent)
            print("   ✅ Unwrapped ratchet envelope")
            
            let ratchetMessage = try JSONDecoder().decode(DoubleRatchetMessage.self, from: ratchetData)
            print("   ✅ Decoded ratchet message")
            print("   📊 Chain: \(ratchetMessage.header.previousChainLength), Msg: \(ratchetMessage.header.messageNumber)")
            
            let sharedSecret = try walletManager.computeSharedSecret(with: message.senderPublicKey)
            print("   ✅ Computed shared secret")
            
            // ✅ FIX: When receiving, we're responder (not initiator)
            let session = ratchetManager.getOrCreateSession(
                with: message.senderPublicKey,
                sharedSecret: sharedSecret,
                isInitiator: false  // ✅ Receiving = responder
            )
            print("   ✅ Got ratchet session")
            
            let plaintext = try session.decrypt(ratchetMessage)
            print("   ✅ Decrypted with Double Ratchet: \(plaintext.prefix(50))...")
            return plaintext
        } catch let error as CryptoKitError {
            print("   ⏸️ CryptoKit error (likely out-of-order): \(error)")
        } catch {
            print("   ⚠️ Double Ratchet failed: \(error.localizedDescription)")
        }
        
        // Try regular ECIES encryption
        do {
            print("   Trying regular ECIES decryption...")
            let decrypted = try walletManager.decrypt(encryptedMessage: message.encryptedContent)
            print("   ✅ Decrypted with ECIES: \(decrypted.prefix(50))...")
            
            if decrypted.starts(with: "eyJ"), let ratchetData = Data(base64Encoded: decrypted) {
                print("   🔓 Detected Double Ratchet inside ECIES, decrypting again...")
                do {
                    let ratchetMessage = try JSONDecoder().decode(DoubleRatchetMessage.self, from: ratchetData)
                    print("   📊 Ratchet chain: \(ratchetMessage.header.previousChainLength)")
                    
                    let sharedSecret = try walletManager.computeSharedSecret(with: message.senderPublicKey)
                    
                    // ✅ FIX: Receiving = responder
                    let session = ratchetManager.getOrCreateSession(
                        with: message.senderPublicKey,
                        sharedSecret: sharedSecret,
                        isInitiator: false  // ✅ Added parameter
                    )
                    
                    let finalPlaintext = try session.decrypt(ratchetMessage)
                    print("   ✅ Fully decrypted: \(finalPlaintext.prefix(50))...")
                    return finalPlaintext
                } catch is CryptoKitError {
                    print("   ⏸️ Inner Double Ratchet out-of-order (will retry)")
                    return nil
                } catch {
                    print("   ⚠️ Inner Double Ratchet failed: \(error.localizedDescription)")
                    return nil
                }
            }
            
            return decrypted
        } catch {
            print("   ⚠️ ECIES decryption failed: \(error.localizedDescription)")
        }
        
        print("   ⏸️ Decryption deferred - message may be out of order")
        return nil
    }
    
    func markAsRead(_ messageId: String) {
        if let index = messages.firstIndex(where: { $0.id == messageId }) {
            messages[index].isRead = true
            saveMessages()
            updateConversations()
        }
    }
    
    func deleteMessage(_ messageId: String) {
        messages.removeAll { $0.id == messageId }
        saveMessages()
        updateConversations()
    }
    
    func deleteConversation(with address: String) {
        messages.removeAll { message in
            message.senderPublicKey == address || message.recipientPublicKey == address
        }
        saveMessages()
        updateConversations()
    }
    
    func clearAllMessages() {
        messages.removeAll()
        conversations.removeAll()
        saveMessages()
    }
    
    private func sendDeliveryConfirmation(for message: SecureMessage) {}
    
    private func saveMessages() {
        let recentMessages = Array(messages.suffix(maxMessagesInMemory))
        
        let trimmedMessages = recentMessages.map { message -> SecureMessage in
            var trimmed = message
            trimmed.mediaAttachment = nil
            return trimmed
        }
        
        if let encoded = try? JSONEncoder().encode(trimmedMessages) {
            let dataSize = encoded.count
            
            if dataSize < 2_500_000 {
                UserDefaults.standard.set(encoded, forKey: "messages")
                print("💾 Saved \(trimmedMessages.count) messages (\(dataSize / 1024) KB)")
            } else {
                let emergency = Array(trimmedMessages.suffix(15))
                if let encoded = try? JSONEncoder().encode(emergency) {
                    UserDefaults.standard.set(encoded, forKey: "messages")
                    print("⚠️ Emergency trim to 15 messages")
                }
            }
        }
        
        if messages.count > maxMessagesInMemory {
            archiveOldMessages()
        }
    }
    
    private func archiveOldMessages() {
        let oldMessages = Array(messages.prefix(messages.count - maxMessagesInMemory))
        
        let fileManager = FileManager.default
        guard let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        
        let archiveURL = documentsURL.appendingPathComponent("archived_messages.json")
        
        if let encoded = try? JSONEncoder().encode(oldMessages) {
            try? encoded.write(to: archiveURL)
            print("📦 Archived \(oldMessages.count) messages")
        }
    }
    
    private func loadMessages() {
        if let data = UserDefaults.standard.data(forKey: "messages"),
           let decoded = try? JSONDecoder().decode([SecureMessage].self, from: data) {
            messages = decoded
            print("✅ Loaded \(messages.count) messages")
        }
        
        updateConversations()
    }
    
    private func updateConversations() {
        guard let walletManager = MessageManager.sharedWalletManager else { return }
        
        var conversationDict: [String: Conversation] = [:]
        
        for message in messages {
            let otherPartyKey: String
            let otherPartyAddress: String
            
            if message.senderPublicKey == walletManager.publicKey {
                otherPartyKey = message.recipientPublicKey
                otherPartyAddress = message.recipientAddress
            } else {
                otherPartyKey = message.senderPublicKey
                otherPartyAddress = message.senderAddress
            }
            
            if otherPartyKey == walletManager.publicKey {
                continue
            }
            
            if var conversation = conversationDict[otherPartyKey] {
                if message.timestamp > conversation.lastMessage.timestamp {
                    conversation.lastMessage = message
                }
                conversation.unreadCount = messages.filter {
                    ($0.senderPublicKey == otherPartyKey || $0.recipientPublicKey == otherPartyKey) &&
                    !$0.isRead &&
                    $0.recipientPublicKey == walletManager.publicKey
                }.count
                conversationDict[otherPartyKey] = conversation
            } else {
                let unreadCount = messages.filter {
                    ($0.senderPublicKey == otherPartyKey || $0.recipientPublicKey == otherPartyKey) &&
                    !$0.isRead &&
                    $0.recipientPublicKey == walletManager.publicKey
                }.count
                
                conversationDict[otherPartyKey] = Conversation(
                    participantAddress: otherPartyAddress,
                    participantPublicKey: otherPartyKey,
                    lastMessage: message,
                    unreadCount: unreadCount
                )
            }
        }
        
        conversations = conversationDict.values.sorted { $0.lastMessage.timestamp > $1.lastMessage.timestamp }
    }
}

// MARK: - Supporting Types

struct Conversation: Identifiable, Equatable {
    let id = UUID()
    let participantAddress: String
    let participantPublicKey: String
    var lastMessage: SecureMessage
    var unreadCount: Int
    
    static func == (lhs: Conversation, rhs: Conversation) -> Bool {
        lhs.participantPublicKey == rhs.participantPublicKey
    }
}

enum DeliveryMethod: String, Codable {
    case mesh = "Direct"
    case ipfs = "Cloud"
    case pending = "Sending..."
}

struct TimeoutError: Error {}

func withTimeout<T>(seconds: TimeInterval, operation: @escaping () async throws -> T) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask {
            try await operation()
        }
        
        group.addTask {
            try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            throw TimeoutError()
        }
        
        let result = try await group.next()!
        group.cancelAll()
        return result
    }
}

struct SecureMessage: Identifiable, Codable, Equatable {
    let id: String
    let senderAddress: String
    let recipientAddress: String
    let encryptedContent: EncryptedMessage
    let timestamp: Date
    var isRead: Bool
    var deliveryStatus: DeliveryStatus
    
    var senderPublicKey: String = ""
    var recipientPublicKey: String = ""
    var plaintextContent: String?
    var mediaAttachment: Data?
    var deliveryMethod: DeliveryMethod? = nil
    var mediaType: MediaManager.MediaType? = nil
    var originalMediaData: Data? = nil
    
    enum CodingKeys: String, CodingKey {
        case id, senderAddress, recipientAddress, encryptedContent, timestamp
        case isRead, deliveryStatus, senderPublicKey, recipientPublicKey
        case plaintextContent, mediaAttachment, deliveryMethod, mediaType, originalMediaData
    }
    
    static func == (lhs: SecureMessage, rhs: SecureMessage) -> Bool {
        lhs.id == rhs.id
    }
}

enum DeliveryStatus: String, Codable {
    case pending = "pending"
    case sent = "sent"
    case delivered = "delivered"
    case failed = "failed"
}
