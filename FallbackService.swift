//
//  FallbackService.swift
//  VPS + IPFS fallback when mesh fails
//

import Foundation
import CryptoKit

class FallbackService {
    static let shared = FallbackService()
    
    // MARK: - Configuration
    private let vpsOnionURL: String
    private let vpsAPIKey: String
    
    init() {
        // Load from config or environment
        self.vpsOnionURL = UserDefaults.standard.string(forKey: "vps_onion_url") ?? ""
        self.vpsAPIKey = UserDefaults.standard.string(forKey: "vps_api_key") ?? ""
    }
    
    func configure(onionURL: String, apiKey: String) {
        UserDefaults.standard.set(onionURL, forKey: "vps_onion_url")
        UserDefaults.standard.set(apiKey, forKey: "vps_api_key")
    }
    
    // MARK: - Upload to IPFS via VPS
    
    func uploadToIPFS(message: SecureMessage) async throws -> String {
        print("📤 Uploading message to IPFS via VPS...")
        
        // Prepare payload
        let payload = IPFSPayload(
            encryptedContent: message.encryptedContent,
            recipientPublicKey: message.recipientPublicKey,
            senderPublicKey: message.senderPublicKey,
            timestamp: message.timestamp,
            mediaAttachment: message.mediaAttachment
        )
        
        let encoder = JSONEncoder()
        let jsonData = try encoder.encode(payload)
        
        // Upload to VPS endpoint
        var request = URLRequest(url: URL(string: "\(vpsOnionURL)/api/ipfs/upload")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(vpsAPIKey)", forHTTPHeaderField: "Authorization")
        request.httpBody = jsonData
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw FallbackError.uploadFailed
        }
        
        let result = try JSONDecoder().decode(IPFSUploadResponse.self, from: data)
        print("✅ Uploaded to IPFS: \(result.ipfsHash)")
        
        // Notify recipient via VPS
        try await notifyRecipient(
            recipientPublicKey: message.recipientPublicKey,
            ipfsHash: result.ipfsHash,
            messageId: message.id
        )
        
        return result.ipfsHash
    }
    
    // MARK: - Notify Recipient
    
    private func notifyRecipient(recipientPublicKey: String, ipfsHash: String, messageId: String) async throws {
        print("🔔 Notifying recipient via VPS...")
        
        let notification = NotificationPayload(
            recipientPublicKey: recipientPublicKey,
            ipfsHash: ipfsHash,
            messageId: messageId,
            timestamp: Date()
        )
        
        let encoder = JSONEncoder()
        let jsonData = try encoder.encode(notification)
        
        var request = URLRequest(url: URL(string: "\(vpsOnionURL)/api/notify")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(vpsAPIKey)", forHTTPHeaderField: "Authorization")
        request.httpBody = jsonData
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw FallbackError.notificationFailed
        }
        
        print("✅ Recipient notified")
    }
    
    // MARK: - Fetch from IPFS
    
    func fetchFromIPFS(ipfsHash: String) async throws -> SecureMessage {
        print("📥 Fetching message from IPFS...")
        
        // Fetch via VPS proxy (hides user IP)
        var request = URLRequest(url: URL(string: "\(vpsOnionURL)/api/ipfs/fetch/\(ipfsHash)")!)
        request.httpMethod = "GET"
        request.setValue("Bearer \(vpsAPIKey)", forHTTPHeaderField: "Authorization")
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw FallbackError.fetchFailed
        }
        
        let payload = try JSONDecoder().decode(IPFSPayload.self, from: data)
        
        let message = SecureMessage(
            id: UUID().uuidString,
            senderAddress: payload.senderPublicKey,
            recipientAddress: payload.recipientPublicKey,
            encryptedContent: payload.encryptedContent,
            timestamp: payload.timestamp,
            isRead: false,
            deliveryStatus: .delivered,
            senderPublicKey: payload.senderPublicKey,
            recipientPublicKey: payload.recipientPublicKey,
            plaintextContent: nil,
            mediaAttachment: payload.mediaAttachment
        )
        
        print("✅ Message fetched from IPFS")
        return message
    }
    
    // MARK: - Check for pending messages
    
    func checkPendingMessages(publicKey: String) async throws -> [String] {
        print("🔍 Checking for pending messages on VPS...")
        
        var request = URLRequest(url: URL(string: "\(vpsOnionURL)/api/pending/\(publicKey)")!)
        request.httpMethod = "GET"
        request.setValue("Bearer \(vpsAPIKey)", forHTTPHeaderField: "Authorization")
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw FallbackError.fetchFailed
        }
        
        let result = try JSONDecoder().decode(PendingMessagesResponse.self, from: data)
        print("✅ Found \(result.ipfsHashes.count) pending messages")
        
        return result.ipfsHashes
    }
}

// MARK: - Models

struct IPFSPayload: Codable {
    let encryptedContent: EncryptedMessage
    let recipientPublicKey: String
    let senderPublicKey: String
    let timestamp: Date
    let mediaAttachment: Data?
}

struct IPFSUploadResponse: Codable {
    let ipfsHash: String
    let pinataUrl: String?
}

struct NotificationPayload: Codable {
    let recipientPublicKey: String
    let ipfsHash: String
    let messageId: String
    let timestamp: Date
}

struct PendingMessagesResponse: Codable {
    let ipfsHashes: [String]
}

enum FallbackError: LocalizedError {
    case uploadFailed
    case fetchFailed
    case notificationFailed
    case notConfigured
    
    var errorDescription: String? {
        switch self {
        case .uploadFailed: return "Failed to upload to IPFS"
        case .fetchFailed: return "Failed to fetch from IPFS"
        case .notificationFailed: return "Failed to notify recipient"
        case .notConfigured: return "VPS not configured"
        }
    }
}
