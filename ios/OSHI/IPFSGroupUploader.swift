//
//  IPFSGroupUploader.swift
//  Uses Pinata IPFS + VPS queue (SAME AS 1-ON-1 MESSAGES!)
//
//  ✅ Works with GroupMessage from GroupMessaging.swift
//  ✅ No duplicate struct definitions
//  ✅ Uses Data for encryptedContent (not EncryptedMessage)
//  ✅ Encrypted envelopes for metadata privacy
//

import Foundation
import CryptoKit

class IPFSGroupUploader {
    static let shared = IPFSGroupUploader()
    
    // ✅ Use SAME infrastructure as MessageManager
    private let vpsPublicURL = "https://oshi-messenger.com"  // ✅ Matches Info.plist!

    private var currentVPSURL: String {
        return vpsPublicURL
    }

    // 🔧 Base64URL encoding for URL-safe public keys
    private func base64urlEncode(_ base64String: String) -> String {
        return base64String
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private init() {
        #if DEBUG
        OshiLog.messaging.info("")
        OshiLog.messaging.info("╔════════════════════════════════════════════════════════╗")
        OshiLog.messaging.info("║  ✅ IPFSGroupUploader INITIALIZED                     ║")
        OshiLog.messaging.info("║  📡 VPS: \(vpsPublicURL.prefix(25))...       ║")
        OshiLog.messaging.info("║  🎯 Using Pinata IPFS + VPS Queue                    ║")
        OshiLog.messaging.info("╚════════════════════════════════════════════════════════╝")
        OshiLog.messaging.info("")
        #endif
        
        setupObservers()
        // __GROUP_E2E_V2_2026_09_23__ `runSelfTest()` removed from init: it posted a fake
        // group message that was uploaded to IPFS on every debug launch.
    }
    
    private func setupObservers() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(uploadGroupMessage(_:)),
            name: NSNotification.Name("SendGroupMessageViaCloud"),
            object: nil
        )
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(uploadGroupUpdate(_:)),
            name: NSNotification.Name("SendGroupUpdateViaCloud"),
            object: nil
        )
        
        OshiLog.messaging.info("✅ Listening for:")
        OshiLog.messaging.info("   • SendGroupMessageViaCloud")
        OshiLog.messaging.info("   • SendGroupUpdateViaCloud")
        OshiLog.messaging.info("")
    }
    
    private func runSelfTest() {
        OshiLog.messaging.info("🧪 Running self-test...")
        
        // Create test data
        let testData = "test".data(using: .utf8)!
        NotificationCenter.default.post(
            name: NSNotification.Name("SendGroupMessageViaCloud"),
            object: nil,
            userInfo: [
                "data": testData,
                "recipients": ["test_recipient"],
                "groupId": "test-group-id"
            ]
        )
        
        OshiLog.messaging.info("")
        OshiLog.messaging.info("📝 When you send a group message, you MUST see:")
        OshiLog.messaging.info("   '🔔 RECEIVED SendGroupMessageViaCloud!'")
        OshiLog.messaging.info("")
    }
    
    // MARK: - Upload Group Message
    
    @objc private func uploadGroupMessage(_ notification: Notification) {
        // __GROUP_E2E_V2_2026_09_23__ Group traffic never goes to IPFS any more (audit L1/L2,
        // docs/GROUP_E2E_V2_SPEC.md rule zero). Nothing in the app posts this notification;
        // the guard makes sure a stray poster cannot resurrect a plaintext pin.
        guard GroupWireV2.legacyGroupUploadsAllowed else {
            OshiLog.messaging.info("🛑 legacy group IPFS upload refused (v2-only)")
            return
        }
        OshiLog.messaging.info("")
        OshiLog.messaging.info("╔════════════════════════════════════════════════════════╗")
        OshiLog.messaging.info("║  🔔 RECEIVED SendGroupMessageViaCloud!                ║")
        OshiLog.messaging.info("╚════════════════════════════════════════════════════════╝")
        OshiLog.messaging.info("")
        
        guard let data = notification.userInfo?["data"] as? Data else {
            OshiLog.messaging.info("❌ ERROR: No 'data' field in notification!")
            return
        }
        
        guard let recipients = notification.userInfo?["recipients"] as? [String] else {
            OshiLog.messaging.info("❌ ERROR: No 'recipients' field in notification!")
            return
        }
        
        let groupId = notification.userInfo?["groupId"] as? String ?? "unknown"
        
        OshiLog.messaging.info("📤 Uploading group message:")
        OshiLog.messaging.info("   📦 Size: \(data.count) bytes")
        OshiLog.messaging.info("   👥 Recipients: \(recipients.count) - \(recipients)")
        OshiLog.messaging.info("   📁 Group ID: \(groupId)")
        OshiLog.messaging.info("")
        
        // ✅ Decode the GroupMessage (from GroupMessaging.swift)
        guard let groupMessage = try? JSONDecoder().decode(GroupMessage.self, from: data) else {
            OshiLog.messaging.info("❌ Failed to decode GroupMessage")
            OshiLog.messaging.info("   This might be a test message, which is OK")
            
            // If it's the self-test, just return
            if String(data: data, encoding: .utf8) == "test" {
                OshiLog.messaging.info("   ✅ Self-test notification received successfully")
                return
            }
            
            return
        }
        
        Task {
            await uploadViaPinata(
                groupMessage: groupMessage,
                recipients: recipients,
                groupId: groupId
            )
        }
    }
    
    // MARK: - Upload Group Update
    
    @objc private func uploadGroupUpdate(_ notification: Notification) {
        // __GROUP_E2E_V2_2026_09_23__ Group traffic never goes to IPFS any more (audit L1/L2,
        // docs/GROUP_E2E_V2_SPEC.md rule zero). Nothing in the app posts this notification;
        // the guard makes sure a stray poster cannot resurrect a plaintext pin.
        guard GroupWireV2.legacyGroupUploadsAllowed else {
            OshiLog.messaging.info("🛑 legacy group IPFS upload refused (v2-only)")
            return
        }
        OshiLog.messaging.info("")
        OshiLog.messaging.info("🔔 IPFSGroupUploader: Received SendGroupUpdateViaCloud!")
        OshiLog.messaging.info("")
        
        guard let data = notification.userInfo?["data"] as? Data else {
            OshiLog.messaging.info("❌ ERROR: No 'data' field")
            return
        }
        
        guard let recipients = notification.userInfo?["recipients"] as? [String] else {
            OshiLog.messaging.info("❌ ERROR: No 'recipients' field")
            return
        }
        
        let groupId = notification.userInfo?["groupId"] as? String ?? "unknown"
        
        OshiLog.messaging.info("📤 Uploading group update:")
        OshiLog.messaging.info("   Size: \(data.count) bytes")
        OshiLog.messaging.info("   Recipients: \(recipients.count)")
        OshiLog.messaging.info("   Group: \(groupId)")
        OshiLog.messaging.info("")
        
        // For group updates, just upload the raw data
        Task {
            do {
                let updateDict: [String: Any] = [
                    "type": "group_update",
                    "groupId": groupId,
                    "data": data.base64EncodedString(),
                    "timestamp": Date().timeIntervalSince1970
                ]
                
                let ipfsHash = try await PinataService.shared.pinJSON(
                    updateDict,
                    name: "group_update_\(groupId)"
                )
                
                OshiLog.messaging.info("✅ Uploaded to Pinata: \(ipfsHash)")
                
                // Notify VPS for each recipient
                for recipient in recipients {
                    try await notifyVPS(ipfsHash: ipfsHash, recipientPublicKey: recipient)
                }
                
                OshiLog.messaging.info("")
                OshiLog.messaging.info("╔════════════════════════════════════════════════════════╗")
                OshiLog.messaging.info("║  ✅✅✅ SUCCESS! Group update uploaded! ✅✅✅         ║")
                OshiLog.messaging.info("╚════════════════════════════════════════════════════════╝")
                OshiLog.messaging.info("")
                
            } catch {
                OshiLog.messaging.info("❌ Upload failed: \(error.localizedDescription)")
            }
        }
    }
    
    // MARK: - Upload to Pinata + Notify VPS
    
    private func uploadViaPinata(
        groupMessage: GroupMessage,
        recipients: [String],
        groupId: String
    ) async {
        do {
            OshiLog.messaging.info("☁️ Step 1: Creating encrypted envelope...")
            
            // ✅ Encode the ENTIRE GroupMessage struct
            let groupMessageData = try JSONEncoder().encode(groupMessage)
            let groupMessageBase64 = groupMessageData.base64EncodedString()
            
            let estimatedSize = groupMessageData.count
            let sizeMB = Double(estimatedSize) / 1_000_000.0
            OshiLog.messaging.info("   📦 Size: \(String(format: "%.2f", sizeMB)) MB")
            
            if estimatedSize > 10_000_000 {
                OshiLog.messaging.info("❌ File too large (\(String(format: "%.2f", sizeMB)) MB)")
                OshiLog.messaging.info("   Maximum: ~10 MB")
                return
            }
            
            // ✅ NEW: Create encrypted envelope (hides all metadata)
            let payload = MessagePayload.forGroupMessage(
                id: groupMessage.id.uuidString,
                groupId: groupId,
                senderKey: groupMessage.senderPublicKey,
                recipientKeys: recipients,
                content: groupMessageBase64,
                chainIndex: groupMessage.messageChainIndex,
                hasMedia: groupMessage.mediaData != nil,
                mediaType: groupMessage.mediaType?.rawValue
            )
            
            // Derive group envelope key from group ID
            let groupKey = deriveGroupKey(groupId: groupId)
            let envelope = try EnvelopeCrypto.encryptForGroup(payload: payload, groupKey: groupKey)
            
            // Upload encrypted envelope to IPFS
            let envelopeDict: [String: Any] = [
                "v": envelope.v,
                "envelope": envelope.envelope,
                "hint": envelope.hint ?? "",
                "nonce": envelope.nonce
            ]
            
            OshiLog.messaging.info("☁️ Step 2: Uploading encrypted envelope to Pinata...")
            
            let ipfsHash = try await PinataService.shared.pinJSON(
                envelopeDict,
                name: "msg_\(Date().timeIntervalSince1970)"  // Generic name, no ID exposed
            )
            
            OshiLog.messaging.info("✅ Encrypted envelope uploaded to IPFS")
            OshiLog.messaging.info("   Hash: \(ipfsHash)")
            OshiLog.messaging.info("   🔐 Metadata hidden: sender, timestamp, groupId all encrypted")
            OshiLog.messaging.info("")
            
            OshiLog.messaging.info("☁️ Step 3: Notifying VPS for \(recipients.count) recipient(s)...")
            
            // Notify VPS for EACH recipient (same as 1-on-1!)
            var successCount = 0
            
            for (index, recipient) in recipients.enumerated() {
                OshiLog.messaging.info("   [\(index + 1)/\(recipients.count)] Notifying \(recipient.prefix(10))...")
                
                do {
                    try await notifyVPS(ipfsHash: ipfsHash, recipientPublicKey: recipient)
                    OshiLog.messaging.info("   ✅ Queued for \(recipient.prefix(10))...")
                    successCount += 1
                } catch {
                    OshiLog.messaging.info("   ⚠️ VPS notify failed: \(error.localizedDescription)")
                }
            }
            
            OshiLog.messaging.info("")
            
            if successCount == recipients.count {
                OshiLog.messaging.info("╔════════════════════════════════════════════════════════╗")
                OshiLog.messaging.info("║  ✅✅✅ SUCCESS! Encrypted message on IPFS! ✅✅✅     ║")
                OshiLog.messaging.info("╚════════════════════════════════════════════════════════╝")
                OshiLog.messaging.info("")
                OshiLog.messaging.info("📬 All \(recipients.count) recipient(s) will receive on next poll")
                OshiLog.messaging.info("   (Polling happens every 60 seconds)")
                
                // ✅ Track for bidirectional confirmation cleanup
                for recipient in recipients {
                    DeliveryReceiptManager.shared.trackOutgoingMessage(
                        messageId: groupMessage.id.uuidString,
                        ipfsHash: ipfsHash,
                        recipientKey: recipient,
                        senderKey: groupMessage.senderPublicKey
                    )
                }
                OshiLog.messaging.info("🔐 Tracked for delivery confirmation (will unpin after receipt)")
                
            } else {
                OshiLog.messaging.info("⚠️ Partial success: \(successCount)/\(recipients.count) recipients notified")
            }
            
            OshiLog.messaging.info("")
            
        } catch {
            OshiLog.messaging.info("")
            OshiLog.messaging.info("╔════════════════════════════════════════════════════════╗")
            OshiLog.messaging.info("║  ❌ UPLOAD FAILED                                      ║")
            OshiLog.messaging.info("╚════════════════════════════════════════════════════════╝")
            OshiLog.messaging.info("   Error: \(error.localizedDescription)")
            OshiLog.messaging.info("")
            
            // Don't reference PinataError enum since it might not have all cases
            // Just print the error
            if error.localizedDescription.contains("401") || error.localizedDescription.contains("unauthorized") {
                OshiLog.messaging.info("   🔑 Pinata authentication failed")
                OshiLog.messaging.info("   💡 Check Pinata JWT in PinataService")
            } else if error.localizedDescription.contains("network") || error.localizedDescription.contains("connection") {
                OshiLog.messaging.info("   🌐 Network error")
                OshiLog.messaging.info("   💡 Check internet connection")
            } else if error.localizedDescription.contains("too large") {
                OshiLog.messaging.info("   📦 File is too large for IPFS")
                OshiLog.messaging.info("   💡 Try using mesh network or reduce media quality")
            }
        }
    }
    
    // MARK: - Notify VPS
    
    /// Derive envelope encryption key from group ID
    private func deriveGroupKey(groupId: String) -> Data {
        // Use SHA256 of group ID as the envelope key
        // All group members know the group ID, so they can derive this key
        let groupIdData = Data(groupId.utf8)
        let salt = Data("oshi-group-envelope-key".utf8)
        var combined = Data()
        combined.append(groupIdData)
        combined.append(salt)
        
        let hash = SHA256.hash(data: combined)
        return hash.withUnsafeBytes { Data($0) }
    }
    
    private func notifyVPS(ipfsHash: String, recipientPublicKey: String) async throws {
        // __NEVER_OVER_THE_INTERNET_2026_10_20__ (a) One of the doors to the
        // internet. Every one of them is closed by the same line, because the
        // promise is "nothing of mine leaves over the network", and a door
        // somebody forgot is the whole of the broken promise.
        guard TransportPolicy.internetAllowedForSending else {
            throw TransportPolicyError.internetSendingDisabled
        }
        // 🔧 CRITICAL FIX: Use base64url encoding (not percent encoding)
        let encodedKey = base64urlEncode(recipientPublicKey)

        // Server expects: POST /api/queue/{recipientKey}/{ipfsHash}
        let fullURL = "\(currentVPSURL)/api/queue/\(encodedKey)/\(ipfsHash)"
        
        OshiLog.messaging.info("   🔗 VPS URL: \(fullURL)")
        
        guard let url = URL(string: fullURL) else {
            OshiLog.messaging.info("   ❌ Failed to create URL from: \(fullURL)")
            throw NSError(domain: "VPS", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid URL: \(fullURL)"])
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 10  // 🔧 FIX: Increased from 2s (was causing false failures for large group media)

        let (data, response) = try await URLSession.shared.data(for: request)
        
        if let httpResponse = response as? HTTPURLResponse {
            if httpResponse.statusCode == 200 {
                let responseString = String(data: data, encoding: .utf8) ?? ""
                OshiLog.messaging.info("   ✅ VPS queued: \(responseString)")
            } else {
                let responseString = String(data: data, encoding: .utf8) ?? "No response"
                throw NSError(
                    domain: "VPS",
                    code: httpResponse.statusCode,
                    userInfo: [NSLocalizedDescriptionKey: "VPS returned \(httpResponse.statusCode): \(responseString)"]
                )
            }
        }
    }
}

// MARK: - Integration Instructions

/*
 
 ╔════════════════════════════════════════════════════════════════╗
 ║  HOW TO USE IPFSGroupUploader                                 ║
 ╚════════════════════════════════════════════════════════════════╝
 
 STEP 1: Add to Xcode
 --------------------
 1. Delete old IPFSCloudManager.swift if it exists
 2. Add this IPFSGroupUploader.swift
 3. Build to check for errors
 
 STEP 2: Initialize in app
 -------------------------
 In SecureWeb3MessengerApp.swift:
 
    init() {
        let _ = IPFSGroupUploader.shared  // ← Initialize
    }
 
 STEP 3: Verify it works
 -----------------------
 When you send a group message, console should show:
 
    ╔════════════════════════════════════════════════════════════╗
    ║  🔔 RECEIVED SendGroupMessageViaCloud!                    ║
    ╚════════════════════════════════════════════════════════════╝
    
    ☁️ Step 1: Uploading to Pinata IPFS...
    ✅ Uploaded to Pinata IPFS
       Hash: Qm...
    
    ☁️ Step 2: Notifying VPS for 1 recipient(s)...
       [1/1] Notifying cOJIMBRW...
       ✅ Queued for cOJIMBRW...
    
    ╔════════════════════════════════════════════════════════════╗
    ║  ✅✅✅ SUCCESS! Message uploaded to IPFS! ✅✅✅         ║
    ╚════════════════════════════════════════════════════════════╝
 
 STEP 4: Receiver gets message
 ------------------------------
 After 60 seconds, receiver polls VPS and gets the IPFS hash.
 MessageManager detects it's a group message and forwards to GroupManager.
 Message appears in group! ✅
 
 TROUBLESHOOTING
 ---------------
 
 ERROR: 'GroupMessage' is ambiguous
 FIX: Make sure you only have ONE GroupMessage struct (in GroupMessaging.swift)
      Delete any duplicate definitions
 
 ERROR: Type 'PinataError' has no member 'unauthorized'
 FIX: This file doesn't reference specific PinataError cases anymore
      It just uses generic error handling
 
 */
