//
//  CloudGroupService.swift
//  Handle cloud delivery for group messages and updates
//
//  🔧 REWRITTEN: Now properly fetches IPFS hashes from VPS, downloads from IPFS, decrypts, and processes
//

import Foundation
import CryptoKit

class CloudGroupService {
    static let shared = CloudGroupService()

    private let serverURL = "https://oshi-messenger.com"
    private var pollingTimer: Timer?

    // ⚠️ 2026-07-17: public-IPFS-gateway fallback list REMOVED. Our blobs live only in
    // `/var/oshi_ipfs` on the VPS and are never published to the IPFS DHT, so
    // gateway.pinata.cloud / ipfs.io / cloudflare-ipfs.com could never resolve our
    // CIDs (verified live: they 404, time out, or no longer resolve at all). They only
    // added dead timeouts and leaked the CID + user IP. `fetchViaVPSProxy` is the path.

    // 🔧 Base64URL encoding for URL-safe public keys
    private func base64urlEncode(_ base64String: String) -> String {
        return base64String
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private init() {
        setupNotificationObservers()
        // 🔧 FIX: DISABLED polling — MessageManager.checkPendingIPFSMessages() already polls
        // the same /api/pending/{key} endpoint every 2s and handles group messages via
        // processDecryptedPayload() and processSimpleGroupMessage(). Running both creates
        // a race condition where CloudGroupService consumes hashes before MessageManager.
        // startPolling()
        OshiLog.messaging.info("☁️ CloudGroupService initialized (polling disabled — handled by MessageManager)")
    }

    // MARK: - Setup

    private func setupNotificationObservers() {
        // Listen for group messages to send
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleSendGroupMessage(_:)),
            name: NSNotification.Name("SendGroupMessageViaCloud"),
            object: nil
        )

        // Listen for group updates to send
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleSendGroupUpdate(_:)),
            name: NSNotification.Name("SendGroupUpdateViaCloud"),
            object: nil
        )

        OshiLog.messaging.info("☁️ Cloud Group Service initialized")
    }

    // MARK: - Sending

    @objc private func handleSendGroupMessage(_ notification: Notification) {
        // __GROUP_E2E_V2_2026_09_23__ Group traffic never goes to IPFS any more (audit L1/L2,
        // docs/GROUP_E2E_V2_SPEC.md rule zero). Nothing in the app posts this notification;
        // the guard makes sure a stray poster cannot resurrect a plaintext pin.
        guard GroupWireV2.legacyGroupUploadsAllowed else {
            OshiLog.messaging.info("🛑 legacy group IPFS upload refused (v2-only)")
            return
        }
        guard let data = notification.userInfo?["data"] as? Data,
              let recipients = notification.userInfo?["recipients"] as? [String],
              let groupId = notification.userInfo?["groupId"] as? String else {
            OshiLog.messaging.info("❌ Invalid SendGroupMessageViaCloud notification")
            return
        }

        OshiLog.messaging.info("☁️ Uploading group message to cloud")
        OshiLog.messaging.info("   Group: \(groupId)")
        OshiLog.messaging.info("   Recipients: \(recipients.count)")
        OshiLog.messaging.info("   Size: \(data.count) bytes")

        // Upload via IPFS and queue for recipients
        Task {
            await uploadViaIPFS(data: data, type: "group_message", recipients: recipients, groupId: groupId)
        }
    }

    @objc private func handleSendGroupUpdate(_ notification: Notification) {
        // __GROUP_E2E_V2_2026_09_23__ Group traffic never goes to IPFS any more (audit L1/L2,
        // docs/GROUP_E2E_V2_SPEC.md rule zero). Nothing in the app posts this notification;
        // the guard makes sure a stray poster cannot resurrect a plaintext pin.
        guard GroupWireV2.legacyGroupUploadsAllowed else {
            OshiLog.messaging.info("🛑 legacy group IPFS upload refused (v2-only)")
            return
        }
        guard let data = notification.userInfo?["data"] as? Data,
              let recipients = notification.userInfo?["recipients"] as? [String],
              let groupId = notification.userInfo?["groupId"] as? String else {
            OshiLog.messaging.info("❌ Invalid SendGroupUpdateViaCloud notification")
            return
        }

        OshiLog.messaging.info("☁️ Uploading group update to cloud")
        OshiLog.messaging.info("   Group: \(groupId)")
        OshiLog.messaging.info("   Recipients: \(recipients.count)")

        Task {
            await uploadViaIPFS(data: data, type: "group_update", recipients: recipients, groupId: groupId)
        }
    }

    // MARK: - Upload via IPFS

    private func uploadViaIPFS(data: Data, type: String, recipients: [String], groupId: String) async {
        // Create payload with type info so receiver knows what it is
        let payload: [String: Any] = [
            "type": type,
            "data": data.base64EncodedString(),
            "groupId": groupId,
            "timestamp": Date().timeIntervalSince1970
        ]

        guard let payloadData = try? JSONSerialization.data(withJSONObject: payload) else {
            OshiLog.messaging.info("❌ Failed to encode payload")
            return
        }

        // Upload to Pinata
        do {
            let ipfsHash = try await PinataService.shared.pinFile(payloadData, filename: "group_\(type).json", mimeType: "application/json")
            OshiLog.messaging.info("✅ Uploaded to IPFS: \(ipfsHash)")

            // Queue hash for each recipient
            for recipient in recipients {
                await queueHashForRecipient(ipfsHash: ipfsHash, recipient: recipient)
            }
        } catch {
            OshiLog.messaging.info("❌ IPFS upload failed: \(error)")
        }
    }

    private func queueHashForRecipient(ipfsHash: String, recipient: String) async {
        let encodedKey = base64urlEncode(recipient)
        let urlString = "\(serverURL)/api/queue/\(encodedKey)/\(ipfsHash)"

        guard let url = URL(string: urlString) else { return }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 10

        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                OshiLog.messaging.info("✅ Queued for recipient: \(recipient.prefix(12))...")
            }
        } catch {
            OshiLog.messaging.info("⚠️ Failed to queue for \(recipient.prefix(12))...: \(error.localizedDescription)")
        }
    }

    // MARK: - Polling

    func startPolling() {
        // Poll every 30 seconds
        pollingTimer = Timer.scheduledTimer(
            withTimeInterval: 30.0,
            repeats: true
        ) { [weak self] _ in
            self?.pollForMessages()
        }

        // Poll immediately on start (with delay for identity setup)
        DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) { [weak self] in
            self?.pollForMessages()
        }

        OshiLog.messaging.info("⏰ CloudGroupService polling started (30s interval)")
    }

    func stopPolling() {
        pollingTimer?.invalidate()
        pollingTimer = nil
        OshiLog.messaging.info("⏰ Cloud polling stopped")
    }

    // MARK: - 🔧 REWRITTEN: Poll for IPFS hashes

    private func pollForMessages() {
        guard let myPublicKey = getMyPublicKey(), !myPublicKey.isEmpty else {
            OshiLog.messaging.info("⚠️ [CloudGroupService] No public key for polling")
            return
        }

        let encodedKey = base64urlEncode(myPublicKey)
        guard let url = URL(string: "\(serverURL)/api/pending/\(encodedKey)") else {
            return
        }

        OshiLog.messaging.info("🔍 [CloudGroupService] Polling for pending messages...")

        Task {
            do {
                var request = URLRequest(url: url)
                request.timeoutInterval = 10

                let (data, response) = try await URLSession.shared.data(for: request)

                guard let httpResponse = response as? HTTPURLResponse else { return }

                guard httpResponse.statusCode == 200 else {
                    if httpResponse.statusCode != 404 {
                        OshiLog.messaging.info("⚠️ [CloudGroupService] VPS returned \(httpResponse.statusCode)")
                    }
                    return
                }

                // 🔧 Parse response as array of IPFS hashes
                let ipfsHashes = try parseIPFSHashes(from: data)

                guard !ipfsHashes.isEmpty else {
                    OshiLog.messaging.info("⏭️ [CloudGroupService] No pending messages")
                    return
                }

                OshiLog.messaging.info("📥 [CloudGroupService] Found \(ipfsHashes.count) pending message(s)")

                // Process each IPFS hash
                for hash in ipfsHashes {
                    await processIPFSHash(hash, myPublicKey: myPublicKey)
                }

            } catch {
                OshiLog.messaging.info("❌ [CloudGroupService] Poll error: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - Parse IPFS Hashes from VPS Response

    private func parseIPFSHashes(from data: Data) throws -> [String] {
        guard let json = try? JSONSerialization.jsonObject(with: data) else {
            return []
        }

        // Format 1: ["hash1", "hash2"] - simple string array
        if let simpleArray = json as? [String] {
            return simpleArray
        }

        // Format 2: {"ipfsHashes": ["hash1", "hash2"]}
        if let dict = json as? [String: Any], let hashes = dict["ipfsHashes"] as? [String] {
            return hashes
        }

        // Format 3: [{"ipfsHash": "hash1"}, ...]
        if let objectArray = json as? [[String: Any]] {
            return objectArray.compactMap { $0["ipfsHash"] as? String ?? $0["hash"] as? String }
        }

        return []
    }

    // MARK: - 🔧 Process IPFS Hash: Fetch → Decrypt → Dispatch

    private func processIPFSHash(_ ipfsHash: String, myPublicKey: String) async {
        OshiLog.messaging.info("📥 [CloudGroupService] Processing hash: \(ipfsHash.prefix(20))...")

        // Step 1: Fetch from IPFS
        guard let data = await fetchFromIPFS(hash: ipfsHash) else {
            OshiLog.messaging.info("❌ [CloudGroupService] Failed to fetch from IPFS: \(ipfsHash.prefix(20))...")
            return
        }

        OshiLog.messaging.info("✅ [CloudGroupService] Fetched \(data.count) bytes from IPFS")

        // Step 2: Parse the JSON payload
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            OshiLog.messaging.info("⚠️ [CloudGroupService] Invalid JSON in IPFS content")
            // Mark as received to clear from queue
            await markAsReceived(ipfsHash: ipfsHash, publicKey: myPublicKey)
            return
        }

        // Step 3: Process based on type
        guard let typeString = json["type"] as? String else {
            OshiLog.messaging.info("⚠️ [CloudGroupService] No type field in message")
            await markAsReceived(ipfsHash: ipfsHash, publicKey: myPublicKey)
            return
        }

        guard let dataString = json["data"] as? String,
              let messageData = Data(base64Encoded: dataString) else {
            OshiLog.messaging.info("⚠️ [CloudGroupService] No data field or invalid base64")
            await markAsReceived(ipfsHash: ipfsHash, publicKey: myPublicKey)
            return
        }

        OshiLog.messaging.info("📥 [CloudGroupService] Processing \(typeString) (\(messageData.count) bytes)")

        // Step 4: Dispatch based on type
        await MainActor.run {
            switch typeString {
            case "group_message":
                NotificationCenter.default.post(
                    name: NSNotification.Name("CloudGroupMessageReceived"),
                    object: nil,
                    userInfo: ["messageData": messageData]
                )
                OshiLog.messaging.info("📬 [CloudGroupService] Posted CloudGroupMessageReceived")

            case "group_update":
                NotificationCenter.default.post(
                    name: NSNotification.Name("CloudGroupUpdateReceived"),
                    object: nil,
                    userInfo: ["groupData": messageData]
                )
                OshiLog.messaging.info("📬 [CloudGroupService] Posted CloudGroupUpdateReceived")

            case "secure_message":
                NotificationCenter.default.post(
                    name: NSNotification.Name("didReceiveSecureMessage"),
                    object: nil,
                    userInfo: ["data": messageData]
                )
                OshiLog.messaging.info("📬 [CloudGroupService] Posted didReceiveSecureMessage")

            default:
                OshiLog.messaging.info("⚠️ [CloudGroupService] Unknown message type: \(typeString)")
            }
        }

        // Step 5: Mark as received to remove from VPS queue
        await markAsReceived(ipfsHash: ipfsHash, publicKey: myPublicKey)
    }

    // MARK: - Fetch from IPFS

    private func fetchFromIPFS(hash: String) async -> Data? {
        // Try VPS proxy first (hides IP from IPFS gateways)
        if let data = await fetchViaVPSProxy(hash: hash) {
            return data
        }

        // No public-gateway fallback (removed 2026-07-17): our CIDs are not on the DHT.
        return nil
    }

    private func fetchViaVPSProxy(hash: String) async -> Data? {
        guard let url = URL(string: "\(serverURL)/ipfs/\(hash)") else { return nil }

        do {
            var request = URLRequest(url: url)
            request.timeoutInterval = 10

            let (data, response) = try await URLSession.shared.data(for: request)

            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                return data
            }
        } catch {
            // VPS proxy failed, will try direct gateways
        }

        return nil
    }

    // MARK: - Mark as Received

    private func markAsReceived(ipfsHash: String, publicKey: String) async {
        let encodedKey = base64urlEncode(publicKey)
        guard let url = URL(string: "\(serverURL)/api/received/\(encodedKey)/\(ipfsHash)") else { return }

        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.timeoutInterval = 5

        do {
            let (_, _) = try await URLSession.shared.data(for: request)
            OshiLog.messaging.info("✅ [CloudGroupService] Marked as received: \(ipfsHash.prefix(15))...")
        } catch {
            // Non-critical, VPS will clean up eventually
        }
    }

    // MARK: - Helper

    private func getMyPublicKey() -> String? {
        return UserDefaults.standard.string(forKey: "publicKey")
    }
}
