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
        // __NEVER_OVER_THE_INTERNET_2026_10_20__ (a) One of the doors to the
        // internet. Every one of them is closed by the same line, because the
        // promise is "nothing of mine leaves over the network", and a door
        // somebody forgot is the whole of the broken promise.
        guard TransportPolicy.internetAllowedForSending else {
            throw TransportPolicyError.internetSendingDisabled
        }
        OshiLog.mesh.info("📤 Uploading message to IPFS via VPS...")
        
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
        OshiLog.mesh.info("✅ Uploaded to IPFS: \(result.ipfsHash)")
        
        // __LOCATION_PING_SPAM_2026_08_24__ THE fix for "sharing a position
        // sends a notification many times".
        //
        // Every message relayed this way asked the VPS to fire an APNs push, and
        // a live-location share emits a ping every ~10 s. So the recipient's lock
        // screen filled up for the whole duration of the share. It only became
        // visible once remote push started working again — before that the
        // pushes were being dropped, which is why this had never been noticed.
        //
        // The guard itself is not new: `MessageManager.swift:4675` and
        // `MessageManager+V2.swift:396` both already ask this question before
        // calling `notifyVPS`. THIS path — the older IPFS fallback — is the one
        // that never got it. The sibling that was missed, again.
        //
        // The upload above still happens: the ping IS delivered, and the map
        // keeps following the other person. Only the banner is withheld.
        let plaintext = message.plaintextContent ?? ""
        if LocationMessage.isSilentPushContent(plaintext) {
            OshiLog.mesh.info("📍 Live-location ping: uploaded, no push requested")
            return result.ipfsHash
        }

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
        OshiLog.mesh.info("🔔 Notifying recipient via VPS...")
        
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
        
        OshiLog.mesh.info("✅ Recipient notified")
    }
    
    // MARK: - Fetch from IPFS
    
    func fetchFromIPFS(ipfsHash: String) async throws -> SecureMessage {
        OshiLog.mesh.info("📥 Fetching message from IPFS...")
        
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
        
        OshiLog.mesh.info("✅ Message fetched from IPFS")
        return message
    }
    
    // MARK: - Check for pending messages
    
    func checkPendingMessages(publicKey: String) async throws -> [String] {
        OshiLog.mesh.info("🔍 Checking for pending messages on VPS...")
        
        var request = URLRequest(url: URL(string: "\(vpsOnionURL)/api/pending/\(publicKey)")!)
        request.httpMethod = "GET"
        request.setValue("Bearer \(vpsAPIKey)", forHTTPHeaderField: "Authorization")
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw FallbackError.fetchFailed
        }
        
        let result = try JSONDecoder().decode(PendingMessagesResponse.self, from: data)
        OshiLog.mesh.info("✅ Found \(result.ipfsHashes.count) pending messages")
        
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
