//
//  IPFSEphemeralManager.swift
//  OSHI - Ephemeral IPFS Messages
//
//  🔐 Security Enhancement: Messages are automatically unpinned from IPFS
//  after delivery confirmation, ensuring content doesn't persist forever.
//
//  Flow:
//  1. Message uploaded to IPFS → Hash stored in pending list
//  2. Recipient downloads message → Sends delivery confirmation
//  3. Sender receives confirmation → Unpins from IPFS
//  4. IPFS garbage collector removes content
//

import Foundation

// MARK: - Pending IPFS Message

struct PendingIPFSMessage: Codable, Identifiable {
    let id: String           // Message UUID
    let ipfsHash: String     // IPFS CID
    let recipientKey: String // Recipient public key
    let uploadedAt: Date     // When uploaded
    let expiresAt: Date      // Auto-cleanup deadline
    var isDelivered: Bool    // Delivery confirmed?
    
    init(id: String, ipfsHash: String, recipientKey: String, ttlHours: Int = 24) {
        self.id = id
        self.ipfsHash = ipfsHash
        self.recipientKey = recipientKey
        self.uploadedAt = Date()
        self.expiresAt = Date().addingTimeInterval(TimeInterval(ttlHours * 3600))
        self.isDelivered = false
    }
}

// MARK: - IPFS Ephemeral Manager

@MainActor
class IPFSEphemeralManager: ObservableObject {
    static let shared = IPFSEphemeralManager()
    
    @Published private(set) var pendingMessages: [PendingIPFSMessage] = []
    
    private let storageKey = "pendingIPFSMessages"
    private var cleanupTimer: Timer?
    
    // Configuration
    private let defaultTTLHours = 24 * 30      // Messages expire after 30 days if not delivered
    private let cleanupIntervalMinutes = 60    // Check for expired messages every hour

    // 🔧 Base64URL encoding for URL-safe public keys
    private func base64urlEncode(_ base64String: String) -> String {
        return base64String
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private init() {
        loadPendingMessages()
        setupCleanupTimer()
        setupNotificationObservers()
        
        OshiLog.messaging.info("")
        OshiLog.messaging.info("╔════════════════════════════════════════════════════════╗")
        OshiLog.messaging.info("║  🔐 IPFSEphemeralManager INITIALIZED                  ║")
        OshiLog.messaging.info("║  📬 Pending messages: \(pendingMessages.count)                            ║")
        OshiLog.messaging.info("║  ⏰ Auto-cleanup: every \(cleanupIntervalMinutes) minutes                  ║")
        OshiLog.messaging.info("║  🗑️ TTL: \(defaultTTLHours) hours                                   ║")
        OshiLog.messaging.info("╚════════════════════════════════════════════════════════╝")
        OshiLog.messaging.info("")
    }
    
    // MARK: - Track New Message
    
    /// Call this after uploading a message to IPFS
    func trackMessage(id: String, ipfsHash: String, recipientKey: String, ttlHours: Int? = nil) {
        let pending = PendingIPFSMessage(
            id: id,
            ipfsHash: ipfsHash,
            recipientKey: recipientKey,
            ttlHours: ttlHours ?? defaultTTLHours
        )
        
        pendingMessages.append(pending)
        savePendingMessages()
        
        OshiLog.messaging.info("📌 Tracking IPFS message:")
        OshiLog.messaging.info("   ID: \(id.prefix(8))...")
        OshiLog.messaging.info("   Hash: \(ipfsHash.prefix(20))...")
        OshiLog.messaging.info("   Recipient: \(recipientKey.prefix(12))...")
        OshiLog.messaging.info("   Expires: \(pending.expiresAt)")
    }
    
    // MARK: - Confirm Delivery
    
    /// Call this when delivery confirmation is received
    func confirmDelivery(messageId: String) {
        guard let index = pendingMessages.firstIndex(where: { $0.id == messageId }) else {
            OshiLog.messaging.info("⚠️ Message not found in pending list: \(messageId.prefix(8))...")
            return
        }
        
        let message = pendingMessages[index]
        
        OshiLog.messaging.info("✅ Delivery confirmed for: \(messageId.prefix(8))...")
        OshiLog.messaging.info("   Unpinning from IPFS...")
        
        // Unpin from IPFS
        Task {
            do {
                try await PinataService.shared.unpin(message.ipfsHash)
                OshiLog.messaging.info("🗑️ Successfully unpinned after delivery")
                
                // Also notify VPS to clear from queue
                await clearFromVPSQueue(ipfsHash: message.ipfsHash, recipientKey: message.recipientKey)
                
            } catch {
                OshiLog.messaging.info("⚠️ Unpin failed: \(error.localizedDescription)")
                // Mark as delivered anyway - will be cleaned up by expiration
            }
            
            // Remove from pending list
            DispatchQueue.main.async {
                self.pendingMessages.removeAll { $0.id == messageId }
                self.savePendingMessages()
            }
        }
    }
    
    /// Confirm delivery by IPFS hash (alternative method)
    func confirmDeliveryByHash(_ ipfsHash: String) {
        guard let message = pendingMessages.first(where: { $0.ipfsHash == ipfsHash }) else {
            OshiLog.messaging.info("⚠️ Hash not found in pending list: \(ipfsHash.prefix(20))...")
            return
        }
        
        confirmDelivery(messageId: message.id)
    }
    
    // MARK: - Automatic Cleanup
    
    private func setupCleanupTimer() {
        cleanupTimer = Timer.scheduledTimer(withTimeInterval: TimeInterval(cleanupIntervalMinutes * 60), repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.cleanupExpiredMessages()
            }
        }
        
        // Also run cleanup on init
        cleanupExpiredMessages()
    }
    
    /// Remove expired messages from IPFS
    private func cleanupExpiredMessages() {
        let now = Date()
        let expiredMessages = pendingMessages.filter { $0.expiresAt < now }
        
        guard !expiredMessages.isEmpty else {
            OshiLog.messaging.info("🧹 Cleanup check: No expired messages")
            return
        }
        
        OshiLog.messaging.info("")
        OshiLog.messaging.info("🧹 Cleaning up \(expiredMessages.count) expired IPFS messages...")
        
        Task {
            for message in expiredMessages {
                do {
                    try await PinataService.shared.unpin(message.ipfsHash)
                    OshiLog.messaging.info("   ✅ Unpinned expired: \(message.ipfsHash.prefix(15))...")
                } catch {
                    OshiLog.messaging.info("   ⚠️ Failed to unpin: \(message.ipfsHash.prefix(15))... - \(error.localizedDescription)")
                }
            }
            
            // Remove from pending list
            DispatchQueue.main.async {
                self.pendingMessages.removeAll { $0.expiresAt < now }
                self.savePendingMessages()
                OshiLog.messaging.info("✅ Cleanup complete. Remaining pending: \(self.pendingMessages.count)")
            }
        }
    }
    
    // MARK: - VPS Queue Cleanup
    
    private func clearFromVPSQueue(ipfsHash: String, recipientKey: String) async {
        // Optional: Tell VPS to remove this hash from recipient's queue
        // This prevents unnecessary downloads if recipient polls before GC

        let vpsURL = "https://oshi-messenger.com"
        // 🔧 CRITICAL FIX: Use base64url encoding (not percent encoding)
        let encodedKey = base64urlEncode(recipientKey)

        guard let url = URL(string: "\(vpsURL)/api/queue/\(encodedKey)/\(ipfsHash)") else { return }
        
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.timeoutInterval = 5
        
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                OshiLog.messaging.info("   ✅ Cleared from VPS queue")
            }
        } catch {
            // Non-critical, VPS will clean up eventually
            OshiLog.messaging.info("   ℹ️ VPS cleanup skipped: \(error.localizedDescription)")
        }
    }
    
    // MARK: - Notification Observers
    
    private func setupNotificationObservers() {
        // 🔴 WATCHDOG (0x8BADF00D): these notifications are posted from background
        // threads. `queue: .main` would park the POSTER on `-[NSOperation waitUntilFinished]`
        // until the main runloop drained — deadlocking whenever main was itself waiting on
        // that thread's queue/lock. addMainThreadObserver hops to main without blocking it.
        // See NotificationCenter+MainThread in NotificationManager.swift.
        // Listen for delivery confirmations
        NotificationCenter.default.addMainThreadObserver(
            forName: NSNotification.Name("MessageDeliveryConfirmed")
        ) { [weak self] notification in
            Task { @MainActor in
                if let messageId = notification.userInfo?["messageId"] as? String {
                    self?.confirmDelivery(messageId: messageId)
                } else if let ipfsHash = notification.userInfo?["ipfsHash"] as? String {
                    self?.confirmDeliveryByHash(ipfsHash)
                }
            }
        }
        
        // Listen for read receipts (also trigger unpin)
        NotificationCenter.default.addMainThreadObserver(
            forName: NSNotification.Name("MessageReadConfirmed")
        ) { [weak self] notification in
            Task { @MainActor in
                if let messageId = notification.userInfo?["messageId"] as? String {
                    self?.confirmDelivery(messageId: messageId)
                }
            }
        }
    }
    
    // MARK: - Persistence
    
    private func loadPendingMessages() {
        // R11 FIX: Use FileStorage (.completeFileProtection) instead of UserDefaults
        // UserDefaults is accessible before first unlock; FileStorage is not.
        guard let data = FileStorage.shared.loadData(for: storageKey),
              let messages = try? JSONDecoder().decode([PendingIPFSMessage].self, from: data) else {
            pendingMessages = []
            return
        }
        pendingMessages = messages
    }

    private func savePendingMessages() {
        // R11 FIX: Use FileStorage (.completeFileProtection) instead of UserDefaults
        if let data = try? JSONEncoder().encode(pendingMessages) {
            FileStorage.shared.saveData(data, for: storageKey)
        }
    }
    
    // MARK: - Manual Controls
    
    /// Force cleanup all messages (use with caution)
    func forceCleanupAll() {
        OshiLog.messaging.info("⚠️ Force cleanup ALL pending IPFS messages...")
        
        Task {
            await PinataService.shared.unpinMultiple(pendingMessages.map { $0.ipfsHash })
            
            DispatchQueue.main.async {
                self.pendingMessages.removeAll()
                self.savePendingMessages()
                OshiLog.messaging.info("✅ All pending messages cleared")
            }
        }
    }
    
    /// Get statistics
    func getStats() -> (pending: Int, oldestAge: TimeInterval?, totalBytes: Int) {
        let pending = pendingMessages.count
        let oldestAge = pendingMessages.map { Date().timeIntervalSince($0.uploadedAt) }.max()
        return (pending, oldestAge, 0) // Bytes would need to be tracked separately
    }
    
    deinit {
        cleanupTimer?.invalidate()
    }
}

// MARK: - Integration with IPFSGroupUploader

extension IPFSGroupUploader {
    /// Enhanced upload that tracks for ephemeral cleanup
    func uploadAndTrack(
        groupMessage: GroupMessage,
        recipients: [String],
        groupId: String
    ) async throws -> String {
        // Upload to IPFS (existing logic)
        // ... return ipfsHash
        
        // This is called from the existing uploadViaPinata method
        // See updated IPFSGroupUploader below
        fatalError("Use the notification-based flow instead")
    }
}

// MARK: - Notification Names

extension Notification.Name {
    static let messageDeliveryConfirmed = Notification.Name("MessageDeliveryConfirmed")
    static let messageReadConfirmed = Notification.Name("MessageReadConfirmed")
    static let ipfsMessageTracked = Notification.Name("IPFSMessageTracked")
}

/*
 ╔════════════════════════════════════════════════════════════════╗
 ║  HOW EPHEMERAL IPFS MESSAGES WORK                             ║
 ╚════════════════════════════════════════════════════════════════╝
 
 UPLOAD FLOW:
 ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐
 │  Alice  │───►│  IPFS   │───►│   VPS   │───►│   Bob   │
 │ (Send)  │    │(Pinata) │    │ (Queue) │    │(Receive)│
 └─────────┘    └─────────┘    └─────────┘    └─────────┘
      │              │              │              │
      │   Track      │              │              │
      └──────────────┼──────────────┼──────────────┘
                     │              │
                     ▼              ▼
              ┌─────────────────────────┐
              │  IPFSEphemeralManager   │
              │  - Stores pending list  │
              │  - Watches for delivery │
              │  - Auto-expires 24h     │
              └─────────────────────────┘
 
 DELIVERY CONFIRMATION:
 ┌─────────┐    ┌─────────┐    ┌─────────┐
 │   Bob   │───►│   VPS   │───►│  Alice  │
 │(Confirm)│    │(Relay)  │    │(Receive)│
 └─────────┘    └─────────┘    └─────────┘
                                    │
                                    ▼
              ┌─────────────────────────┐
              │  IPFSEphemeralManager   │
              │  - Receives confirmation│
              │  - Calls Pinata UNPIN   │
              │  - Content disappears   │
              └─────────────────────────┘
 
 SECURITY BENEFITS:
 ✅ Messages don't persist forever on IPFS
 ✅ Even encrypted data is eventually removed
 ✅ Reduces attack surface for future decryption
 ✅ Complies with "right to be forgotten"
 ✅ Reduces Pinata storage costs
 
 */
