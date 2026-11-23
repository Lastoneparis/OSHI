//
//  DeliveryReceiptManager.swift
//  OSHI - Bidirectional Delivery Confirmation
//
//  🔐 Security: Messages are only unpinned from IPFS after the recipient
//  sends back an encrypted delivery confirmation.
//
//  Flow:
//  1. Alice sends message → IPFS (tracked by IPFSEphemeralManager)
//  2. Bob receives message → Sends encrypted DeliveryReceipt to Alice
//  3. Alice receives receipt → Verifies signature → Unpins from IPFS
//  4. Content removed from IPFS network
//

import Foundation
import CryptoKit

// MARK: - Delivery Receipt

struct DeliveryReceipt: Codable {
    var type: String = "delivery_receipt"
    let messageId: String           // Original message UUID
    let ipfsHash: String            // IPFS CID to unpin
    let recipientKey: String        // Bob's public key
    let senderKey: String           // Alice's public key (who to notify)
    let timestamp: Date             // When received
    let signature: String           // HMAC signature for authenticity
    
    // Generate signature using shared secret
    static func sign(messageId: String, ipfsHash: String, sharedSecret: Data) -> String {
        let message = "\(messageId)|\(ipfsHash)"
        let key = SymmetricKey(data: sharedSecret)
        let signature = HMAC<SHA256>.authenticationCode(for: Data(message.utf8), using: key)
        return Data(signature).base64EncodedString()
    }
    
    // Verify signature
    func verify(sharedSecret: Data) -> Bool {
        let message = "\(messageId)|\(ipfsHash)"
        let key = SymmetricKey(data: sharedSecret)
        guard let signatureData = Data(base64Encoded: signature) else { return false }
        return HMAC<SHA256>.isValidAuthenticationCode(signatureData, authenticating: Data(message.utf8), using: key)
    }
}

// MARK: - Read Receipt (Optional - for "seen" status)

struct ReadReceipt: Codable {
    var type: String = "read_receipt"
    let messageId: String
    let recipientKey: String
    let senderKey: String
    let timestamp: Date
    let signature: String
}

// MARK: - Delivery Receipt Manager

class DeliveryReceiptManager: ObservableObject {
    static let shared = DeliveryReceiptManager()
    
    // Track pending receipts we're waiting for
    @Published private(set) var pendingReceipts: [String: PendingReceipt] = [:] // messageId -> receipt info
    
    private let storageKey = "pendingDeliveryReceipts"
    private let vpsURL = "https://oshi-messenger.com"

    // 🔧 Base64URL encoding for URL-safe public keys
    private func base64urlEncode(_ base64String: String) -> String {
        return base64String
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private init() {
        loadPendingReceipts()
        setupObservers()
        
        OshiLog.messaging.info("")
        OshiLog.messaging.info("╔════════════════════════════════════════════════════════╗")
        OshiLog.messaging.info("║  📬 DeliveryReceiptManager INITIALIZED                ║")
        OshiLog.messaging.info("║  🔐 Bidirectional confirmation enabled                ║")
        OshiLog.messaging.info("║  📋 Pending receipts: \(pendingReceipts.count)                           ║")
        OshiLog.messaging.info("╚════════════════════════════════════════════════════════╝")
        OshiLog.messaging.info("")
    }
    
    // MARK: - Track Outgoing Message (Alice side)
    
    /// Call this when sending a message - tracks it for receipt
    func trackOutgoingMessage(
        messageId: String,
        ipfsHash: String,
        recipientKey: String,
        senderKey: String
    ) {
        let pending = PendingReceipt(
            messageId: messageId,
            ipfsHash: ipfsHash,
            recipientKey: recipientKey,
            senderKey: senderKey,
            sentAt: Date()
        )
        
        // __RECEIPT_LEDGER_ON_MAIN_2026_09_12__ Same reasoning as `processReceipt`:
        // `pendingReceipts` is @Published and this is called from the send path, which
        // is not always on main. Apply synchronously when we already are, so the
        // common case keeps its existing ordering, and hop only when we genuinely
        // aren't.
        let apply: () -> Void = { [weak self] in
            guard let self = self else { return }
            self.pendingReceipts[messageId] = pending
            self.savePendingReceipts()
        }
        if Thread.isMainThread { apply() } else { DispatchQueue.main.async(execute: apply) }
        
        OshiLog.messaging.info("📤 Tracking message for delivery receipt:")
        OshiLog.messaging.info("   ID: \(messageId.prefix(8))...")
        OshiLog.messaging.info("   IPFS: \(ipfsHash.prefix(15))...")
        OshiLog.messaging.info("   To: \(recipientKey.prefix(12))...")
    }
    
    // MARK: - Send Receipt (Bob side)
    
    /// Call this when receiving a message - sends receipt back to sender
    func sendDeliveryReceipt(
        for messageId: String,
        ipfsHash: String,
        senderKey: String,
        recipientKey: String,
        identityManager: IdentityManager
    ) {
        Task {
            do {
                // Compute shared secret for signature
                let sharedSecret = try identityManager.computeSharedSecret(with: senderKey)
                
                // Create signed receipt
                let signature = DeliveryReceipt.sign(
                    messageId: messageId,
                    ipfsHash: ipfsHash,
                    sharedSecret: sharedSecret
                )
                
                let receipt = DeliveryReceipt(
                    messageId: messageId,
                    ipfsHash: ipfsHash,
                    recipientKey: recipientKey,
                    senderKey: senderKey,
                    timestamp: Date(),
                    signature: signature
                )
                
                // Encrypt the receipt
                let receiptData = try JSONEncoder().encode(receipt)
                let encryptedReceipt = try encryptReceipt(receiptData, sharedSecret: sharedSecret)
                
                // Upload to IPFS (small, will be quickly unpinned)
                let receiptHash = try await PinataService.shared.pinJSON(
                    ["encrypted": encryptedReceipt],
                    name: "receipt_\(messageId.prefix(8))"
                )
                
                // Notify sender via VPS
                try await notifyVPS(ipfsHash: receiptHash, recipientKey: senderKey)
                
                OshiLog.messaging.info("✅ Delivery receipt sent:")
                OshiLog.messaging.info("   For message: \(messageId.prefix(8))...")
                OshiLog.messaging.info("   To sender: \(senderKey.prefix(12))...")
                OshiLog.messaging.info("   Receipt hash: \(receiptHash.prefix(15))...")
                
                // Auto-unpin receipt after 1 hour (sender should get it quickly)
                Task {
                    try? await Task.sleep(nanoseconds: 3600_000_000_000) // 1 hour
                    try? await PinataService.shared.unpin(receiptHash)
                }
                
            } catch {
                OshiLog.messaging.info("❌ Failed to send delivery receipt: \(error.localizedDescription)")
            }
        }
    }
    
    // MARK: - Process Incoming Receipt (Alice side)
    
    /// Call this when receiving a delivery receipt
    func processReceipt(_ encryptedData: String, from senderKey: String, identityManager: IdentityManager) {
        Task {
            do {
                // Compute shared secret
                let sharedSecret = try identityManager.computeSharedSecret(with: senderKey)
                
                // Decrypt receipt
                guard let receiptData = try decryptReceipt(encryptedData, sharedSecret: sharedSecret) else {
                    OshiLog.messaging.info("❌ Failed to decrypt receipt")
                    return
                }
                
                let receipt = try JSONDecoder().decode(DeliveryReceipt.self, from: receiptData)
                
                // Verify signature
                guard receipt.verify(sharedSecret: sharedSecret) else {
                    OshiLog.messaging.info("❌ Receipt signature verification failed!")
                    return
                }
                
                OshiLog.messaging.info("✅ Valid delivery receipt received:")
                OshiLog.messaging.info("   Message ID: \(receipt.messageId.prefix(8))...")
                OshiLog.messaging.info("   From: \(receipt.recipientKey.prefix(12))...")
                
                // Remove from pending.
                //
                // __RECEIPT_LEDGER_ON_MAIN_2026_09_12__ `pendingReceipts` is
                // @Published, and this Task runs OFF the main actor: the class is a
                // plain nonisolated ObservableObject (the app target does not set
                // SWIFT_DEFAULT_ACTOR_ISOLATION — only the Watch target does), so an
                // unstructured `Task { }` here does NOT inherit main. Mutating the
                // dictionary from this thread published SwiftUI changes off-main and
                // raced `trackOutgoingMessage`, which writes the same dictionary from
                // the send path — two receipts arriving together raced each other.
                await MainActor.run {
                    self.pendingReceipts.removeValue(forKey: receipt.messageId)
                    self.savePendingReceipts()
                }
                
                // ✅ NOW it's safe to unpin from IPFS!
                try await PinataService.shared.unpin(receipt.ipfsHash)
                OshiLog.messaging.info("🗑️ Message unpinned from IPFS after confirmed delivery")
                
                // Notify UI
                await MainActor.run {
                    NotificationCenter.default.post(
                        name: NSNotification.Name("MessageDeliveryConfirmed"),
                        object: nil,
                        userInfo: [
                            "messageId": receipt.messageId,
                            "ipfsHash": receipt.ipfsHash
                        ]
                    )
                }
                
            } catch {
                OshiLog.messaging.info("❌ Error processing receipt: \(error.localizedDescription)")
            }
        }
    }
    
    // MARK: - Encryption Helpers
    
    private func encryptReceipt(_ data: Data, sharedSecret: Data) throws -> String {
        let key = SymmetricKey(data: SHA256.hash(data: sharedSecret).withUnsafeBytes { Data($0) })
        let nonce = AES.GCM.Nonce()
        let sealed = try AES.GCM.seal(data, using: key, nonce: nonce)
        
        // Combine nonce + ciphertext + tag
        var combined = Data()
        combined.append(contentsOf: nonce)
        combined.append(sealed.ciphertext)
        combined.append(sealed.tag)
        
        return combined.base64EncodedString()
    }
    
    private func decryptReceipt(_ encrypted: String, sharedSecret: Data) throws -> Data? {
        guard let combined = Data(base64Encoded: encrypted) else { return nil }
        
        let key = SymmetricKey(data: SHA256.hash(data: sharedSecret).withUnsafeBytes { Data($0) })
        
        // Extract nonce (12 bytes), ciphertext, tag (16 bytes)
        let nonceData = combined.prefix(12)
        let tag = combined.suffix(16)
        let ciphertext = combined.dropFirst(12).dropLast(16)
        
        let nonce = try AES.GCM.Nonce(data: nonceData)
        let sealedBox = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
        
        return try AES.GCM.open(sealedBox, using: key)
    }
    
    // MARK: - VPS Notification
    
    private func notifyVPS(ipfsHash: String, recipientKey: String) async throws {
        // __COVER_FAIL_CLOSED_2026_11_18__ cover mode: a receipt is 1:1 traffic too.
        guard CoverTransport.legacyOneToOneRelayAllowed else { throw CoverTransport.Failure.legacyRelayRefused }
        // __NEVER_OVER_THE_INTERNET_2026_10_20__ (a) One of the doors to the
        // internet. Every one of them is closed by the same line, because the
        // promise is "nothing of mine leaves over the network", and a door
        // somebody forgot is the whole of the broken promise.
        // A receipt is traffic too: leaking "she read it, at 23:04" over the
        // relay is exactly what the switch is meant to stop.
        guard TransportPolicy.internetAllowedForSending else {
            throw TransportPolicyError.internetSendingDisabled
        }
        // 🔧 CRITICAL FIX: Use base64url encoding (not percent encoding)
        let encodedKey = base64urlEncode(recipientKey)
        // 🔕 2026-06-11 (b56): ?nopush=1 — a delivery receipt must never fire
        // the server's generic "You have a new message" fallback push at the
        // original sender. This was the "I receive a notification right after
        // I send a message" bug: the recipient's auto-receipt queued here and
        // the server pushed the sender 4 s later.
        let fullURL = "\(vpsURL)/api/queue/\(encodedKey)/\(ipfsHash)?nopush=1"
        
        guard let url = URL(string: fullURL) else {
            throw NSError(domain: "Receipt", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid URL"])
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 10
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode != 200 {
            throw NSError(domain: "VPS", code: httpResponse.statusCode)
        }
    }
    
    // MARK: - Persistence
    
    private func loadPendingReceipts() {
        guard let data = UserDefaults.standard.data(forKey: storageKey),
              let receipts = try? JSONDecoder().decode([String: PendingReceipt].self, from: data) else {
            return
        }
        pendingReceipts = receipts
        prunePendingReceipts()
    }

    /// __RECEIPT_LEDGER_IS_BOUNDED_2026_09_12__ Drop receipts that are never coming.
    ///
    /// An entry is added by `trackOutgoingMessage` and was removed ONLY when a valid
    /// receipt came back. But the receipt send is fire-and-forget — any failure is
    /// logged and dropped, with no retry and no persistence — and a recipient who
    /// never comes back online never sends one at all. Those entries stayed in
    /// UserDefaults forever, so the ledger grew without bound for the life of the
    /// install.
    ///
    /// Fourteen days is far longer than any delivery that is still going to happen.
    /// Deliberately NOT unpinning the stale IPFS hashes from here: that would mean
    /// network calls during launch, and the send path already has its own cleanup.
    /// Bounding the ledger is the correctness fix; reclaiming pins is separate.
    private static let pendingReceiptTTL: TimeInterval = 14 * 24 * 3600

    private func prunePendingReceipts() {
        let cutoff = Date().addingTimeInterval(-Self.pendingReceiptTTL)
        let staleKeys = pendingReceipts.filter { $0.value.sentAt < cutoff }.map { $0.key }
        guard !staleKeys.isEmpty else { return }
        for key in staleKeys { pendingReceipts.removeValue(forKey: key) }
        savePendingReceipts()
        OshiLog.messaging.info("🧹 DeliveryReceiptManager: dropped \(staleKeys.count) pending receipt(s) older than 14 days")
    }

    private func savePendingReceipts() {
        if let data = try? JSONEncoder().encode(pendingReceipts) {
            UserDefaults.standard.set(data, forKey: storageKey)
        }
    }
    
    // MARK: - Observers
    
    private func setupObservers() {
        // Listen for incoming receipts.
        //
        // 🔴 WATCHDOG (0x8BADF00D): this is posted from the message-receive path on a background
        // thread. `queue: .main` would park the POSTER on `-[NSOperation waitUntilFinished]`
        // until the main runloop drained — deadlocking whenever main was itself waiting on that
        // thread's serial queue or lock. See NotificationCenter+MainThread in NotificationManager.swift.
        NotificationCenter.default.addMainThreadObserver(
            forName: NSNotification.Name("DeliveryReceiptReceived")
        ) { [weak self] notification in
            guard let encryptedData = notification.userInfo?["encrypted"] as? String,
                  let senderKey = notification.userInfo?["senderKey"] as? String,
                  let identityManager = MessageManager.sharedIdentityManager else { return }
            
            self?.processReceipt(encryptedData, from: senderKey, identityManager: identityManager)
        }
    }
}

// MARK: - Pending Receipt Model

struct PendingReceipt: Codable {
    let messageId: String
    let ipfsHash: String
    let recipientKey: String
    let senderKey: String
    let sentAt: Date
}

// MARK: - Notification Names

extension Notification.Name {
    static let deliveryReceiptReceived = Notification.Name("DeliveryReceiptReceived")
}
