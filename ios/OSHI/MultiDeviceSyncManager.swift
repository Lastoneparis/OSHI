//
//  MultiDeviceSyncManager.swift
//  OSHI
//
//  Synchronizes messages and sessions across multiple devices
//  using the same private key (e.g., iPhone + Mac)
//
//  Architecture:
//  - Each device registers with VPS using their publicKey + deviceId
//  - When a message is sent/received, it's synced to all registered devices
//  - Encryption keys are derived from the same private key on each device
//

import Foundation
import CryptoKit
import UIKit

class MultiDeviceSyncManager: ObservableObject {
    static let shared = MultiDeviceSyncManager()
    
    // MARK: - Published State
    @Published var registeredDevices: [SyncDevice] = []
    @Published var isSyncEnabled: Bool = false
    @Published var lastSyncDate: Date?
    @Published var syncStatus: SyncStatus = .idle
    
    // MARK: - Configuration
    private let vpsBaseURL = "https://oshi-messenger.com/api/call"
    private let syncBaseURL = "https://oshi-messenger.com"  // message_queue_server (8081) via nginx
    private var deviceId: String
    private var publicKey: String = ""
    
    enum SyncStatus: String {
        case idle = "Idle"
        case syncing = "Syncing..."
        case success = "Synced"
        case error = "Sync Error"

        var localizedString: String {
            switch self {
            case .idle: return "multi_device.status.idle".localized
            case .syncing: return "multi_device.status.syncing".localized
            case .success: return "multi_device.status.synced".localized
            case .error: return "multi_device.status.error".localized
            }
        }
    }
    
    // MARK: - Device Model
    struct SyncDevice: Identifiable, Codable {
        let id: String  // Unique device ID
        let name: String
        let type: DeviceType
        let publicKey: String
        let registeredAt: Date
        var lastSeenAt: Date
        var isCurrentDevice: Bool = false
        
        enum DeviceType: String, Codable {
            case iPhone = "iphone"
            case iPad = "ipad"
            case mac = "mac"
            case watch = "watch"
            case unknown = "unknown"
        }
    }
    
    // MARK: - Sync Message Model
    struct SyncMessage: Codable {
        let messageId: String
        let senderPublicKey: String
        let recipientPublicKey: String
        let encryptedContent: Data
        let timestamp: Date
        let mediaData: Data?
        let mediaType: String?
        let isRead: Bool
        let deviceOrigin: String  // Which device sent/received this
    }
    
    // MARK: - Initialization
    
    private var syncTimer: Timer?

    private init() {
        // 🔧 MULTI-DEVICE: Use the per-physical-device id from VoIPPushManager
        // (Keychain-backed, ThisDeviceOnly). MUST NOT read UserDefaults directly:
        // UserDefaults is iCloud-backed, so a restore can give two phones the
        // same id and the sync server then short-circuits with `same_device`.
        self.deviceId = VoIPPushManager.getDeviceId()

        // Load sync state. On a true first launch (no prior preference set)
        // default to enabled so a user who later uninstalls + reinstalls
        // automatically gets their conversations pulled back from the sync
        // server. Users can still turn it off in Settings.
        let defaults = UserDefaults.standard
        if defaults.object(forKey: "oshi_sync_enabled") == nil {
            defaults.set(true, forKey: "oshi_sync_enabled")
            OshiLog.messaging.info("📱 MultiDeviceSync: first-launch default → enabled")
        }
        isSyncEnabled = defaults.bool(forKey: "oshi_sync_enabled")

        if let lastSync = UserDefaults.standard.object(forKey: "oshi_last_sync") as? Date {
            lastSyncDate = lastSync
        }

        OshiLog.messaging.info("📱 MultiDeviceSync: Device ID = \(deviceId)")

        // Start periodic sync timer (every 30 seconds)
        DispatchQueue.main.asyncAfter(deadline: .now() + 5.0) { [weak self] in
            self?.startPeriodicSync()
        }
    }

    /// Periodic sync: uploads from devices with data, pulls to devices without
    func startPeriodicSync() {
        syncTimer?.invalidate()
        syncTimer = Timer.scheduledTimer(withTimeInterval: 30.0, repeats: true) { [weak self] _ in
            self?.performFullSync()
        }
        // 🔧 FIX R5: Removed immediate performFullSync() call here.
        // The timer handles periodic sync; callers (initializeApp, onChange) already call performFullSync() explicitly.
        // Having both caused double sync at t=0.
    }

    private var isSyncing = false

    /// Single sync cycle: upload what we have, pull what we don't
    func performFullSync() {
        guard !DevSyncFlag.legacySyncSuppressed else { return }  // __DEVSYNC_DIRECT_2026_09_23__ direct device sync replaces /api/sync
        // 🔧 FIX R5: Guard against overlapping syncs
        guard !isSyncing else {
            OshiLog.messaging.info("🔄 MultiDeviceSync: Sync already in progress, skipping")
            return
        }
        guard let pubKey = UserDefaults.standard.string(forKey: "publicKey"), !pubKey.isEmpty else {
            return
        }
        isSyncing = true
        OshiLog.messaging.info("🔄 MultiDeviceSync: Sync cycle start (pubKey=\(pubKey.prefix(12))...)")

        // Pull first so we get the other device's data before overwriting the store slot.
        // Upload after merging so the store always contains the union of both devices.
        pullGroupsFromSyncWithCompletion { [weak self] in
            DispatchQueue.main.async {
                self?.pullContactsFromSync()
                self?.pullMessagesFromSync()
                self?.pullGroupMessagesFromSync()
                self?.pullProfileFromSync()
                MessageManager.shared?.retryPendingGroupEnvelopes()

                // Upload the merged result after a short delay to let merges settle
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    self?.uploadGroupsForSync()
                    self?.uploadContactsForSync()
                    self?.uploadMessagesForSync()
                    self?.uploadGroupMessagesForSync()
                    self?.uploadProfileForSync()
                    // 🔧 FIX R5: Mark sync complete after uploads finish
                    self?.isSyncing = false
                }
            }
        }
    }
    
    // MARK: - Public Methods
    
    /// Initialize sync with user's public key
    func initialize(publicKey: String) {
        self.publicKey = publicKey
        OshiLog.messaging.info("🔄 MultiDeviceSync: Initialized with publicKey \(publicKey.prefix(16))...")
        
        if isSyncEnabled {
            Task {
                await registerDevice()
                await syncFromCloud()
            }
        }
    }
    
    /// Enable multi-device sync
    func enableSync() async -> Bool {
        guard !publicKey.isEmpty else {
            OshiLog.messaging.info("❌ MultiDeviceSync: Cannot enable - no public key")
            return false
        }
        
        let success = await registerDevice()
        if success {
            isSyncEnabled = true
            UserDefaults.standard.set(true, forKey: "oshi_sync_enabled")
            OshiLog.messaging.info("✅ MultiDeviceSync: Enabled")
        }
        return success
    }
    
    /// Disable multi-device sync
    func disableSync() async {
        await unregisterDevice()
        isSyncEnabled = false
        UserDefaults.standard.set(false, forKey: "oshi_sync_enabled")
        OshiLog.messaging.info("🔴 MultiDeviceSync: Disabled")
    }
    
    /// Register this device for sync
    @discardableResult
    func registerDevice() async -> Bool {
        guard !publicKey.isEmpty else { return false }
        
        let device = SyncDevice(
            id: deviceId,
            name: getDeviceName(),
            type: getDeviceType(),
            publicKey: publicKey,
            registeredAt: Date(),
            lastSeenAt: Date(),
            isCurrentDevice: true
        )
        
        do {
            let url = URL(string: "\(vpsBaseURL)/api/sync/register")!
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            
            let payload = try JSONEncoder().encode(device)
            request.httpBody = payload
            
            let (data, response) = try await URLSession.shared.data(for: request)
            
            guard let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200 else {
                OshiLog.messaging.info("❌ MultiDeviceSync: Registration failed")
                return false
            }
            
            // Parse registered devices list
            let result = try JSONDecoder().decode(RegisterResponse.self, from: data)
            
            await MainActor.run {
                self.registeredDevices = result.devices.map { d in
                    var device = d
                    device.isCurrentDevice = (d.id == self.deviceId)
                    return device
                }
            }
            
            OshiLog.messaging.info("✅ MultiDeviceSync: Registered. Total devices: \(result.devices.count)")
            return true
            
        } catch {
            OshiLog.messaging.info("❌ MultiDeviceSync: Registration error: \(error)")
            return false
        }
    }
    
    /// Unregister this device
    func unregisterDevice() async {
        guard !publicKey.isEmpty else { return }
        
        do {
            let url = URL(string: "\(vpsBaseURL)/api/sync/unregister")!
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            
            let payload = UnregisterPayload(publicKey: publicKey, deviceId: deviceId)
            request.httpBody = try JSONEncoder().encode(payload)
            
            let (_, response) = try await URLSession.shared.data(for: request)
            
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                OshiLog.messaging.info("✅ MultiDeviceSync: Unregistered device")
            }
        } catch {
            OshiLog.messaging.info("❌ MultiDeviceSync: Unregister error: \(error)")
        }
    }
    
    /// Sync a new message to all devices
    func syncMessage(_ message: SecureMessage) async {
        guard !DevSyncFlag.legacySyncSuppressed else { return }  // __DEVSYNC_DIRECT_2026_09_23__ direct device sync replaces /api/sync
        guard isSyncEnabled, !publicKey.isEmpty else { return }
        
        let syncMsg = SyncMessage(
            messageId: message.id,
            senderPublicKey: message.senderPublicKey,
            recipientPublicKey: message.recipientPublicKey,
            encryptedContent: try! JSONEncoder().encode(message.encryptedContent),
            timestamp: message.timestamp,
            mediaData: message.mediaAttachment,
            mediaType: message.mediaType?.rawValue,
            isRead: message.isRead,
            deviceOrigin: deviceId
        )
        
        do {
            let url = URL(string: "\(vpsBaseURL)/api/sync/message")!
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            
            let payload = SyncMessagePayload(publicKey: publicKey, message: syncMsg)
            request.httpBody = try JSONEncoder().encode(payload)
            
            let (_, response) = try await URLSession.shared.data(for: request)
            
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                OshiLog.messaging.info("✅ MultiDeviceSync: Message synced to cloud")
            }
        } catch {
            OshiLog.messaging.info("❌ MultiDeviceSync: Sync message error: \(error)")
        }
    }
    
    /// Sync read status across devices
    func syncReadStatus(messageId: String, isRead: Bool) async {
        guard !DevSyncFlag.legacySyncSuppressed else { return }  // __DEVSYNC_DIRECT_2026_09_23__ direct device sync replaces /api/sync
        guard isSyncEnabled, !publicKey.isEmpty else { return }
        
        do {
            let url = URL(string: "\(vpsBaseURL)/api/sync/read")!
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            
            let payload = ReadStatusPayload(
                publicKey: publicKey,
                messageId: messageId,
                isRead: isRead,
                deviceId: deviceId
            )
            request.httpBody = try JSONEncoder().encode(payload)
            
            let (_, _) = try await URLSession.shared.data(for: request)
            OshiLog.messaging.info("✅ MultiDeviceSync: Read status synced")
        } catch {
            OshiLog.messaging.info("❌ MultiDeviceSync: Sync read status error: \(error)")
        }
    }
    
    /// Pull new messages from cloud (from other devices)
    func syncFromCloud() async {
        guard !DevSyncFlag.legacySyncSuppressed else { return }  // __DEVSYNC_DIRECT_2026_09_23__ direct device sync replaces /api/sync
        guard isSyncEnabled, !publicKey.isEmpty else { return }
        
        await MainActor.run {
            syncStatus = .syncing
        }
        
        do {
            let lastSync = lastSyncDate ?? Date.distantPast
            let url = URL(string: "\(vpsBaseURL)/api/sync/pull?publicKey=\(publicKey)&since=\(lastSync.timeIntervalSince1970)&deviceId=\(deviceId)")!
            
            var request = URLRequest(url: url)
            request.httpMethod = "GET"
            
            let (data, response) = try await URLSession.shared.data(for: request)
            
            guard let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200 else {
                await MainActor.run { syncStatus = .error }
                return
            }
            
            let result = try JSONDecoder().decode(SyncPullResponse.self, from: data)
            
            OshiLog.messaging.info("📥 MultiDeviceSync: Received \(result.messages.count) messages from other devices")
            
            // Process synced messages
            for syncMsg in result.messages {
                await processIncomingSyncMessage(syncMsg)
            }
            
            // Process read status updates
            for readUpdate in result.readUpdates {
                await processReadUpdate(readUpdate)
            }
            
            await MainActor.run {
                lastSyncDate = Date()
                UserDefaults.standard.set(lastSyncDate, forKey: "oshi_last_sync")
                syncStatus = .success
            }
            
            // Reset status after delay
            // Reset status after delay
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                if self.syncStatus == .success {
                    self.syncStatus = .idle
                }
            }
            
        } catch {
            OshiLog.messaging.info("❌ MultiDeviceSync: Pull error: \(error)")
            await MainActor.run { syncStatus = .error }
        }
    }
    
    /// Get list of all registered devices
    func fetchRegisteredDevices() async {
        guard !publicKey.isEmpty else { return }
        
        do {
            let url = URL(string: "\(vpsBaseURL)/api/sync/devices?publicKey=\(publicKey)")!
            let (data, response) = try await URLSession.shared.data(from: url)
            
            guard let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200 else { return }
            
            let result = try JSONDecoder().decode(DevicesResponse.self, from: data)
            
            await MainActor.run {
                self.registeredDevices = result.devices.map { d in
                    var device = d
                    device.isCurrentDevice = (d.id == self.deviceId)
                    return device
                }
            }
        } catch {
            OshiLog.messaging.info("❌ MultiDeviceSync: Fetch devices error: \(error)")
        }
    }
    
    /// Remove a specific device from sync
    func removeDevice(_ device: SyncDevice) async {
        guard !publicKey.isEmpty else { return }
        
        do {
            let url = URL(string: "\(vpsBaseURL)/api/sync/remove-device")!
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            
            let payload = UnregisterPayload(publicKey: publicKey, deviceId: device.id)
            request.httpBody = try JSONEncoder().encode(payload)
            
            let (_, response) = try await URLSession.shared.data(for: request)
            
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                await MainActor.run {
                    registeredDevices.removeAll { $0.id == device.id }
                }
                OshiLog.messaging.info("✅ MultiDeviceSync: Removed device \(device.name)")
            }
        } catch {
            OshiLog.messaging.info("❌ MultiDeviceSync: Remove device error: \(error)")
        }
    }
    
    /// Convert public key to base64url (URL-safe, no /, +, = chars — works through nginx/Express without encoding issues)
    private func urlEncodedKey(_ key: String) -> String {
        return key
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    // MARK: - Group & Contact Sync (Multi-Device)

    /// Upload encrypted group data for other devices to pull
    // __SYNC_UPLOAD_ONLY_ON_CHANGE_2026_09_22__ MESURÉ sur le serveur le
    // 2026-09-22: l'iPhone 17 renvoyait `groupMessages` (2,3 Mo) et `groups`
    // (284 Ko) 30 à 44 fois par 10 minutes — le minuteur de 30 s plus les
    // déclencheurs ponctuels — alors que rien n'avait changé. Jusqu'à ~280 Mo
    // par heure sur cellulaire, et la radio tenue éveillée.
    //
    // On ne renvoie plus un bloc identique au dernier envoi RÉUSSI. Empreinte
    // sur le clair (le chiffré change à chaque envoi, nonce aléatoire). Un
    // envoi reste forcé toutes les 10 min: si l'autre appareil a écrasé le
    // créneau entre-temps, il est rétabli.
    private var lastUploadDigest: [String: (digest: Data, at: Date)] = [:]

    /// __SYNC_ENCODE_OFF_MAIN_2026_09_23__ Every legacy sync upload builds its body HERE,
    /// never on main. MEASURED 2026-09-22 (build 143): the iPhone was killed by iOS
    /// (FRONTBOARD 0xBAADCA11) with MAIN inside `JSONEncoder.encode` in
    /// `uploadMessagesForSync` — the whole message history (media ≤ 1 MB each) was
    /// serialised on main, re-encoded from scratch every 50 messages, while a VoIP push
    /// needed main to be reported to CallKit. Main now only snapshots the model arrays
    /// (copy-on-write, O(1)); sorting, trimming, encoding and AES-GCM run on this serial
    /// queue, which also keeps two uploads of the same kind from overlapping.
    private let uploadQueue = DispatchQueue(label: "com.oshi.sync.upload", qos: .utility)
    private let lastUploadLock = NSLock()
    private static let forcedUploadInterval: TimeInterval = 600

    private func uploadNeeded(_ kind: String, _ plaintext: Data) -> Bool {
        let digest = Data(SHA256.hash(data: plaintext))
        lastUploadLock.lock(); defer { lastUploadLock.unlock() }
        guard let last = lastUploadDigest[kind] else { return true }
        return last.digest != digest || Date().timeIntervalSince(last.at) > Self.forcedUploadInterval
    }

    private func markUploaded(_ kind: String, _ plaintext: Data) {
        let digest = Data(SHA256.hash(data: plaintext))
        lastUploadLock.lock(); defer { lastUploadLock.unlock() }
        lastUploadDigest[kind] = (digest, Date())
    }

    func uploadGroupsForSync() {
        guard !DevSyncFlag.legacySyncSuppressed else { return }  // __DEVSYNC_DIRECT_2026_09_23__ direct device sync replaces /api/sync
        guard let pubKey = UserDefaults.standard.string(forKey: "publicKey"), !pubKey.isEmpty else {
            OshiLog.messaging.info("❌ MultiDeviceSync uploadGroups: No publicKey in UserDefaults")
            return
        }

        // Access MainActor-isolated groups on main thread — snapshot ONLY; the encode
        // runs on `uploadQueue` (__SYNC_ENCODE_OFF_MAIN_2026_09_23__).
        DispatchQueue.main.async { [self] in
            let groups = MessageGroupManager.shared.groups
            guard !groups.isEmpty else { return }

            uploadQueue.async { [self] in
            do {
                let groupData = try JSONEncoder().encode(groups)
                guard self.uploadNeeded("groups", groupData) else { return }
                guard let encrypted = self.encryptSyncData(groupData) else {
                    OshiLog.messaging.info("❌ MultiDeviceSync: Failed to encrypt groups")
                    return
                }

                let payload: [String: Any] = [
                    "deviceId": self.deviceId,
                    "data": encrypted
                ]

                guard let url = URL(string: "\(self.syncBaseURL)/api/sync/groups/\(urlEncodedKey(pubKey))"),
                      let jsonData = try? JSONSerialization.data(withJSONObject: payload) else { return }

                var request = URLRequest(url: url)
                request.httpMethod = "POST"
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                request.httpBody = jsonData
                request.timeoutInterval = 10

                URLSession.shared.dataTask(with: request) { _, response, error in
                    if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                        self.markUploaded("groups", groupData)
                        OshiLog.messaging.info("✅ MultiDeviceSync: Groups uploaded for sync (\(groups.count) groups)")
                    } else {
                        OshiLog.messaging.info("❌ MultiDeviceSync: Group upload failed: \(error?.localizedDescription ?? "unknown")")
                    }
                }.resume()
            } catch {
                OshiLog.messaging.info("❌ MultiDeviceSync: Group encode error: \(error)")
            }
            }
        }
    }

    /// Pull encrypted group data from another device
    func pullGroupsFromSync() {
        pullGroupsFromSyncWithCompletion {}
    }

    /// Pull encrypted group data from another device, with completion handler
    func pullGroupsFromSyncWithCompletion(_ completion: @escaping () -> Void) {
        guard !DevSyncFlag.legacySyncSuppressed else { completion(); return }  // __DEVSYNC_DIRECT_2026_09_23__
        guard let pubKey = UserDefaults.standard.string(forKey: "publicKey"), !pubKey.isEmpty else {
            completion()
            return
        }
        guard let url = URL(string: "\(syncBaseURL)/api/sync/groups/\(urlEncodedKey(pubKey))?deviceId=\(deviceId)") else {
            completion()
            return
        }

        URLSession.shared.dataTask(with: url) { [weak self] data, response, error in
            defer { completion() }

            guard let data = data,
                  let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200 else {
                OshiLog.messaging.info("❌ MultiDeviceSync: Group pull failed: \(error?.localizedDescription ?? "unknown")")
                return
            }

            do {
                let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
                let encryptedPayloads = self?.syncPayloads(from: json ?? [:]) ?? []
                guard !encryptedPayloads.isEmpty else {
                    OshiLog.messaging.info("📱 MultiDeviceSync: No group sync data available")
                    return
                }
                let remoteGroups: [MessageGroup] = encryptedPayloads.flatMap { encrypted in
                    guard let decrypted = self?.decryptSyncData(encrypted) else { return [MessageGroup]() }
                    return (try? JSONDecoder().decode([MessageGroup].self, from: decrypted)) ?? []
                }
                OshiLog.messaging.info("📥 MultiDeviceSync: Pulled \(remoteGroups.count) groups from sync")

                DispatchQueue.main.async {
                    // Merge: add groups that don't exist locally
                    // B3: with ≥2 linked devices the per-device `dataItems` carry the SAME
                    // group once per device — the set grows as we add, or it is appended twice.
                    var localGroupIds = Set(MessageGroupManager.shared.groups.map { $0.id })
                    var added = 0
                    for group in remoteGroups {
                        if localGroupIds.insert(group.id).inserted {
                            MessageGroupManager.shared.groups.append(group)
                            added += 1
                        }
                    }
                    if added > 0 {
                        MessageGroupManager.shared.saveGroups()
                        OshiLog.messaging.info("✅ MultiDeviceSync: Merged \(added) new groups")
                    }
                }
            } catch {
                OshiLog.messaging.info("❌ MultiDeviceSync: Group pull decode error: \(error)")
            }
        }.resume()
    }

    /// Upload encrypted contacts for other devices to pull
    func uploadContactsForSync() {
        guard !DevSyncFlag.legacySyncSuppressed else { return }  // __DEVSYNC_DIRECT_2026_09_23__ direct device sync replaces /api/sync
        guard let pubKey = UserDefaults.standard.string(forKey: "publicKey"), !pubKey.isEmpty else { return }

        // Read contact aliases from UserDefaults. The live blob is JSON-encoded
        // Data written by @AppStorage("contactAliases") in the UI layer — do NOT
        // read it as a plist dictionary here (it isn't one).
        guard let aliasData = UserDefaults.standard.data(forKey: "contactAliases"),
              !aliasData.isEmpty else { return }

        uploadQueue.async { [self] in  // __SYNC_ENCODE_OFF_MAIN_2026_09_23__
        guard let aliases = try? JSONDecoder().decode([String: String].self, from: aliasData),
              !aliases.isEmpty else { return }
        do {
            let contactData = try JSONEncoder().encode(aliases)
            guard let encrypted = encryptSyncData(contactData) else { return }

            let payload: [String: Any] = [
                "deviceId": deviceId,
                "data": encrypted
            ]

            guard let url = URL(string: "\(syncBaseURL)/api/sync/contacts/\(urlEncodedKey(pubKey))"),
                  let jsonData = try? JSONSerialization.data(withJSONObject: payload) else { return }

            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = jsonData
            request.timeoutInterval = 10

            URLSession.shared.dataTask(with: request) { _, response, _ in
                if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                    OshiLog.messaging.info("✅ MultiDeviceSync: Contacts uploaded for sync (\(aliases.count) contacts)")
                }
            }.resume()
        } catch {
            OshiLog.messaging.info("❌ MultiDeviceSync: Contact encode error: \(error)")
        }
        }
    }

    /// Pull encrypted contacts from another device
    func pullContactsFromSync() {
        guard !DevSyncFlag.legacySyncSuppressed else { return }  // __DEVSYNC_DIRECT_2026_09_23__ direct device sync replaces /api/sync
        guard let pubKey = UserDefaults.standard.string(forKey: "publicKey"), !pubKey.isEmpty else { return }
        guard let url = URL(string: "\(syncBaseURL)/api/sync/contacts/\(urlEncodedKey(pubKey))?deviceId=\(deviceId)") else { return }

        URLSession.shared.dataTask(with: url) { [weak self] data, response, error in
            guard let data = data,
                  let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200 else { return }

            do {
                let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
                guard let encryptedStr = json?["data"] as? String else { return }
                guard let decrypted = self?.decryptSyncData(encryptedStr) else { return }

                let remoteContacts = try JSONDecoder().decode([String: String].self, from: decrypted)
                OshiLog.messaging.info("📥 MultiDeviceSync: Pulled \(remoteContacts.count) contacts from sync")

                DispatchQueue.main.async {
                    // Merge contacts: remote contacts fill in gaps, don't overwrite existing.
                    // Storage format must match the @AppStorage("contactAliases") readers in
                    // ChatView/MessagesListView/etc. — JSON-encoded Data, not a plist dict.
                    var localContacts: [String: String] = [:]
                    if let localData = UserDefaults.standard.data(forKey: "contactAliases"),
                       !localData.isEmpty,
                       let decoded = try? JSONDecoder().decode([String: String].self, from: localData) {
                        localContacts = decoded
                    }
                    var added = 0
                    for (key, name) in remoteContacts {
                        if localContacts[key] == nil {
                            localContacts[key] = name
                            added += 1
                        }
                    }
                    if added > 0,
                       let mergedData = try? JSONEncoder().encode(localContacts) {
                        UserDefaults.standard.set(mergedData, forKey: "contactAliases")
                        // Also persist into the per-identity archive so a later
                        // identity switch doesn't drop the pulled aliases.
                        if let pubKey = UserDefaults.standard.string(forKey: "publicKey"), !pubKey.isEmpty {
                            UserDefaults.standard.set(mergedData, forKey: "contactAliases_\(pubKey)")
                        }
                        OshiLog.messaging.info("✅ MultiDeviceSync: Merged \(added) new contacts")
                    }
                }
            } catch {
                OshiLog.messaging.info("❌ MultiDeviceSync: Contact pull decode error: \(error)")
            }
        }.resume()
    }

    // MARK: - Message Sync (1-on-1 conversations)

    /// Upload encrypted 1-on-1 messages for other devices to pull
    /// Coalesces the post-send sync so a burst of sends does not trigger a burst
    /// of full-history uploads.
    ///
    /// 🔧 FIX (2026-08-12, "why is upload only 150 KB/s?"): this was fired
    /// immediately after EVERY send. Sending a photo therefore started two
    /// concurrent uploads of overlapping data on one uplink — the media itself,
    /// and a sync payload that carries `mediaAttachment` bytes for every message
    /// under the 1 MB cap plus the full `encryptedContent`. They split the
    /// bandwidth, and the payload is built on `DispatchQueue.main`, which stalls
    /// the URLSession delegate callbacks driving the media transfer.
    ///
    /// MEASURED: the same 3.8 MB reaches this VPS at ~1 MB/s from a Mac on the
    /// same connection (200 KB → 0.80 s, 800 KB → 1.55 s, 3.8 MB → 3.67 s, so
    /// ~0.55 s fixed cost plus ~1.1 MB/s steady state). The phone managed
    /// ~150 KB/s — seven times slower on the same internet, against a server that
    /// is demonstrably not the limit.
    ///
    /// Debounced 4 s: long enough that the media transfer owns the link while it
    /// matters, short enough that a second device still sees the message quickly
    /// — which is the reason the immediate call was added in the first place, so
    /// it is preserved rather than removed.
    private var syncDebounce: DispatchWorkItem?
    func scheduleUploadMessagesForSync(delay: TimeInterval = 4.0) {
        guard !DevSyncFlag.legacySyncSuppressed else { return }  // __DEVSYNC_DIRECT_2026_09_23__ direct device sync replaces /api/sync
        syncDebounce?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.uploadMessagesForSync() }
        syncDebounce = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    func uploadMessagesForSync() {
        guard !DevSyncFlag.legacySyncSuppressed else { return }  // __DEVSYNC_DIRECT_2026_09_23__ direct device sync replaces /api/sync
        guard let pubKey = UserDefaults.standard.string(forKey: "publicKey"), !pubKey.isEmpty else {
            OshiLog.messaging.info("❌ MultiDeviceSync uploadMessages: No publicKey")
            return
        }
        guard let manager = MessageManager.shared else {
            OshiLog.messaging.info("❌ MultiDeviceSync uploadMessages: MessageManager.shared is nil")
            return
        }

        // __SYNC_ENCODE_OFF_MAIN_2026_09_23__ main takes the snapshot (a COW copy) and
        // nothing else.
        DispatchQueue.main.async { [self] in
            let messages = manager.messages
            guard !messages.isEmpty else {
                OshiLog.messaging.info("📱 MultiDeviceSync uploadMessages: No messages to upload")
                return
            }
            uploadQueue.async { [self] in
                self.encodeAndUploadMessages(messages, pubKey: pubKey)
            }
        }
    }

    /// Serialised body of the most recent messages that fit `totalBodyCap`, plus how many
    /// made it in. Runs on `uploadQueue`.
    ///
    /// 🔧 FIX #3 (2026-06-07, v1.0.19): cap the upload body to ~4 MB by including only
    /// the MOST RECENT messages whose serialized size fits (a ~200 MB body blew past
    /// nginx's client_max_body_size and starved /push/send-push). Older history is
    /// durable in IPFS and can be lazy-fetched.
    ///
    /// __SYNC_ENCODE_OFF_MAIN_2026_09_23__ LINEAR now: each message is encoded ONCE and
    /// the array body is assembled as `[` + elements joined by `,` + `]` — byte-for-byte
    /// what `JSONEncoder().encode(array)` emits with the default (compact) formatting and
    /// default strategies. The old loop re-encoded the WHOLE growing prefix every 50
    /// messages (quadratic in history size), on main.
    static func buildRecentMessagesBody(_ messages: [SecureMessage],
                                        perMessageMediaCap: Int = 1_000_000,
                                        totalBodyCap: Int = 4_000_000) -> (body: Data, count: Int) {
        let sortedDesc = messages.sorted { $0.timestamp > $1.timestamp }
        let encoder = JSONEncoder()
        var pieces: [Data] = []
        pieces.reserveCapacity(min(sortedDesc.count, 4096))
        var size = 2  // "[" and "]"
        for msg in sortedDesc {
            var t = msg
            if let media = t.mediaAttachment, media.count > perMessageMediaCap {
                t.mediaAttachment = nil
            }
            guard let piece = try? encoder.encode(t) else { continue }
            let added = piece.count + (pieces.isEmpty ? 0 : 1)
            if size + added > totalBodyCap { break }
            pieces.append(piece)
            size += added
        }
        var body = Data()
        body.reserveCapacity(size)
        body.append(UInt8(ascii: "["))
        for (i, piece) in pieces.enumerated() {
            if i > 0 { body.append(UInt8(ascii: ",")) }
            body.append(piece)
        }
        body.append(UInt8(ascii: "]"))
        return (body, pieces.count)
    }

    private func encodeAndUploadMessages(_ messages: [SecureMessage], pubKey: String) {
        dispatchPrecondition(condition: .notOnQueue(.main))
        guard !DevSyncFlag.legacySyncSuppressed else { return }  // flag may have flipped while queued

        let (msgData, kept) = Self.buildRecentMessagesBody(messages)
        if kept < messages.count {
            OshiLog.messaging.info("📱 MultiDeviceSync: chunked to \(kept) recent messages (~\(msgData.count / 1024) KB), dropping \(messages.count - kept) older")
        }
        OshiLog.messaging.info("📱 MultiDeviceSync: Uploading \(kept) of \(messages.count) messages...")

        // Hard ceiling guard: if somehow over 5 MB, bail rather than poison the HTTP/2 pool.
        if msgData.count > 5_000_000 {
            OshiLog.messaging.info("⚠️ MultiDeviceSync: payload \(msgData.count / 1024)KB still over hard ceiling — SKIPPING this cycle")
            return
        }

        guard let encrypted = self.encryptSyncData(msgData) else {
            OshiLog.messaging.info("❌ MultiDeviceSync: Failed to encrypt messages")
            return
        }

        let payload: [String: Any] = [
            "deviceId": self.deviceId,
            "data": encrypted
        ]

        guard let url = URL(string: "\(self.syncBaseURL)/api/sync/messages/\(urlEncodedKey(pubKey))"),
              let jsonData = try? JSONSerialization.data(withJSONObject: payload) else { return }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = jsonData
        request.timeoutInterval = 30

        URLSession.shared.dataTask(with: request) { _, response, error in
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                OshiLog.messaging.info("✅ MultiDeviceSync: Messages uploaded for sync (\(kept) messages)")
            } else {
                OshiLog.messaging.info("❌ MultiDeviceSync: Message upload failed: \(error?.localizedDescription ?? "unknown")")
            }
        }.resume()
    }

    /// Pull encrypted 1-on-1 messages from another device
    func pullMessagesFromSync() {
        guard !DevSyncFlag.legacySyncSuppressed else { return }  // __DEVSYNC_DIRECT_2026_09_23__ direct device sync replaces /api/sync
        OshiLog.messaging.info("📱 MultiDeviceSync: pullMessagesFromSync called")
        guard let pubKey = UserDefaults.standard.string(forKey: "publicKey"), !pubKey.isEmpty else {
            OshiLog.messaging.info("❌ MultiDeviceSync pullMessages: No publicKey")
            return
        }
        guard let url = URL(string: "\(syncBaseURL)/api/sync/messages/\(urlEncodedKey(pubKey))?deviceId=\(deviceId)") else { return }

        URLSession.shared.dataTask(with: url) { [weak self] data, response, error in
            guard let data = data,
                  let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200 else {
                OshiLog.messaging.info("❌ MultiDeviceSync: Message pull failed: \(error?.localizedDescription ?? "unknown")")
                return
            }

            do {
                let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
                let encryptedPayloads = self?.syncPayloads(from: json ?? [:]) ?? []
                guard !encryptedPayloads.isEmpty else {
                    OshiLog.messaging.info("📱 MultiDeviceSync: No message sync data available")
                    return
                }
                let remoteMessages: [SecureMessage] = encryptedPayloads.flatMap { encrypted in
                    guard let decrypted = self?.decryptSyncData(encrypted) else { return [SecureMessage]() }
                    return (try? JSONDecoder().decode([SecureMessage].self, from: decrypted)) ?? []
                }
                OshiLog.messaging.info("📥 MultiDeviceSync: Pulled \(remoteMessages.count) messages from sync")

                DispatchQueue.main.async {
                    guard let manager = MessageManager.shared else { return }
                    // B3: one copy of a message per device payload — dedupe while merging.
                    var localIds = Set(manager.messages.map { $0.id })
                    var added = 0
                    for msg in remoteMessages {
                        if localIds.insert(msg.id).inserted {
                            manager.messages.append(msg)
                            added += 1
                        }
                    }
                    if added > 0 {
                        manager.messages.sort { $0.timestamp < $1.timestamp }
                        manager.saveMessages()
                        manager.refreshConversations()
                        OshiLog.messaging.info("✅ MultiDeviceSync: Merged \(added) new 1-on-1 messages, conversations updated")
                    }
                }
            } catch {
                OshiLog.messaging.info("❌ MultiDeviceSync: Message pull decode error: \(error)")
            }
        }.resume()
    }

    // MARK: - Group Message Sync

    /// Upload encrypted group messages for other devices to pull
    func uploadGroupMessagesForSync() {
        guard !DevSyncFlag.legacySyncSuppressed else { return }  // __DEVSYNC_DIRECT_2026_09_23__ direct device sync replaces /api/sync
        guard let pubKey = UserDefaults.standard.string(forKey: "publicKey"), !pubKey.isEmpty else {
            OshiLog.messaging.info("❌ MultiDeviceSync uploadGroupMessages: No publicKey")
            return
        }

        DispatchQueue.main.async { [self] in
            let groupMessages = MessageGroupManager.shared.groupMessages
            guard !groupMessages.isEmpty else {
                OshiLog.messaging.info("📱 MultiDeviceSync uploadGroupMessages: No group messages to upload")
                return
            }
            OshiLog.messaging.info("📱 MultiDeviceSync: Uploading group messages...")

            // __SYNC_ENCODE_OFF_MAIN_2026_09_23__ snapshot above, work below.
            uploadQueue.async { [self] in
            do {
                // 🔧 CRASH FIX (1.0.15): NSMallocException "failed to grow buffer
                // to ~2.8 GB". For GROUP messages the media payload lives in
                // `encryptedContent` (the full encrypted image/video blob) — NOT
                // in `mediaData` (which is nil / "not persisted"), so nilling
                // mediaData did nothing. Base64-encoding hundreds of media blobs
                // into one JSON blew the buffer past 2 GB.
                // History sync is best-effort and text-focused: skip media/oversized
                // messages (the other device still gets media through the normal
                // group-message path), keep text history, and hard-cap the whole
                // blob so this can never OOM again.
                let perMsgCeiling = 256_000          // 256 KB — text is tiny; larger = media
                let totalPayloadCeiling = 25_000_000 // 25 MB hard cap on the sync blob
                var runningTotal = 0
                var skipped = 0
                let stripped: [UUID: [GroupMessage]] = groupMessages.mapValues { messages in
                    messages.compactMap { msg -> GroupMessage? in
                        if msg.mediaType != nil || msg.encryptedContent.count > perMsgCeiling {
                            skipped += 1
                            return nil
                        }
                        if runningTotal + msg.encryptedContent.count > totalPayloadCeiling {
                            skipped += 1
                            return nil
                        }
                        runningTotal += msg.encryptedContent.count
                        var m = msg
                        m.mediaData = nil
                        return m
                    }
                }
                if skipped > 0 {
                    OshiLog.messaging.info("📱 MultiDeviceSync: skipped \(skipped) media/oversized group msg(s) from history sync (~\(runningTotal / 1000) KB kept)")
                }
                // Convert UUID keys to String keys for encoding
                let stringKeyed = Dictionary(uniqueKeysWithValues: stripped.map { key, value in
                    (key.uuidString, value)
                })
                let msgData = try JSONEncoder().encode(stringKeyed)
                guard self.uploadNeeded("groupMessages", msgData) else { return }
                guard let encrypted = self.encryptSyncData(msgData) else {
                    OshiLog.messaging.info("❌ MultiDeviceSync: Failed to encrypt group messages")
                    return
                }

                let payload: [String: Any] = [
                    "deviceId": self.deviceId,
                    "data": encrypted
                ]

                guard let url = URL(string: "\(self.syncBaseURL)/api/sync/groupMessages/\(urlEncodedKey(pubKey))"),
                      let jsonData = try? JSONSerialization.data(withJSONObject: payload) else { return }

                var request = URLRequest(url: url)
                request.httpMethod = "POST"
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                request.httpBody = jsonData
                request.timeoutInterval = 30

                URLSession.shared.dataTask(with: request) { _, response, error in
                    if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                        self.markUploaded("groupMessages", msgData)
                        let totalMsgs = groupMessages.values.reduce(0) { $0 + $1.count }
                        OshiLog.messaging.info("✅ MultiDeviceSync: Group messages uploaded for sync (\(totalMsgs) messages in \(groupMessages.count) groups)")
                    } else {
                        OshiLog.messaging.info("❌ MultiDeviceSync: Group message upload failed: \(error?.localizedDescription ?? "unknown")")
                    }
                }.resume()
            } catch {
                OshiLog.messaging.info("❌ MultiDeviceSync: Group message encode error: \(error)")
            }
            }
        }
    }

    /// Pull encrypted group messages from another device
    func pullGroupMessagesFromSync() {
        guard !DevSyncFlag.legacySyncSuppressed else { return }  // __DEVSYNC_DIRECT_2026_09_23__ direct device sync replaces /api/sync
        OshiLog.messaging.info("📱 MultiDeviceSync: pullGroupMessagesFromSync called")
        guard let pubKey = UserDefaults.standard.string(forKey: "publicKey"), !pubKey.isEmpty else {
            OshiLog.messaging.info("❌ MultiDeviceSync pullGroupMessages: No publicKey")
            return
        }
        guard let url = URL(string: "\(syncBaseURL)/api/sync/groupMessages/\(urlEncodedKey(pubKey))?deviceId=\(deviceId)") else { return }

        URLSession.shared.dataTask(with: url) { [weak self] data, response, error in
            guard let data = data,
                  let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200 else {
                OshiLog.messaging.info("❌ MultiDeviceSync: Group message pull failed: \(error?.localizedDescription ?? "unknown")")
                return
            }

            do {
                let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
                let encryptedPayloads = self?.syncPayloads(from: json ?? [:]) ?? []
                guard !encryptedPayloads.isEmpty else {
                    OshiLog.messaging.info("📱 MultiDeviceSync: No group message sync data available")
                    return
                }
                var remoteGroupMessages: [UUID: [GroupMessage]] = [:]
                for encrypted in encryptedPayloads {
                    guard let decrypted = self?.decryptSyncData(encrypted),
                          let stringKeyed = try? JSONDecoder().decode([String: [GroupMessage]].self, from: decrypted) else { continue }
                    for (key, value) in stringKeyed {
                        guard let groupID = UUID(uuidString: key) else { continue }
                        remoteGroupMessages[groupID, default: []].append(contentsOf: value)
                    }
                }
                let totalMsgs = remoteGroupMessages.values.reduce(0) { $0 + $1.count }
                OshiLog.messaging.info("📥 MultiDeviceSync: Pulled \(totalMsgs) group messages from sync")

                DispatchQueue.main.async {
                    let mgr = MessageGroupManager.shared
                    var addedTotal = 0
                    for (groupId, remoteMessages) in remoteGroupMessages {
                        // B3: the same group message arrives once per device payload.
                        var localIds = Set((mgr.groupMessages[groupId] ?? []).map { $0.id })
                        let newMessages = remoteMessages.filter { localIds.insert($0.id).inserted }
                        if !newMessages.isEmpty {
                            if mgr.groupMessages[groupId] != nil {
                                mgr.groupMessages[groupId]!.append(contentsOf: newMessages)
                                mgr.groupMessages[groupId]!.sort { $0.timestamp < $1.timestamp }
                            } else {
                                mgr.groupMessages[groupId] = newMessages.sorted { $0.timestamp < $1.timestamp }
                            }
                            addedTotal += newMessages.count
                        }
                    }
                    if addedTotal > 0 {
                        mgr.saveGroupMessagesPublic()
                        mgr.objectWillChange.send()
                        OshiLog.messaging.info("✅ MultiDeviceSync: Merged \(addedTotal) new group messages")
                    }
                }
            } catch {
                OshiLog.messaging.info("❌ MultiDeviceSync: Group message pull decode error: \(error)")
            }
        }.resume()
    }

    // MARK: - Sync Encryption Helpers

    /// Accept both the original single-snapshot response and the newer
    /// per-device response. The latter prevents one linked device from
    /// overwriting another before their histories have been merged.
    private func syncPayloads(from json: [String: Any]) -> [String] {
        if let items = json["dataItems"] as? [[String: Any]] {
            return items.compactMap { $0["data"] as? String }
        }
        if let data = json["data"] as? String { return [data] }
        return []
    }

    /// Derive AES-256-GCM key from private key for sync encryption
    private func deriveSyncKey() -> SymmetricKey? {
        guard let privKeyData = try? KeychainHelper.load(key: "privateKey") else { return nil }
        let salt = "oshi-sync-key".data(using: .utf8)!
        let combined = privKeyData + salt
        let hash = SHA256.hash(data: combined)
        return SymmetricKey(data: hash)
    }

    /// Encrypt data for sync (AES-256-GCM, base64 output)
    private func encryptSyncData(_ data: Data) -> String? {
        guard let key = deriveSyncKey() else { return nil }
        do {
            let sealed = try AES.GCM.seal(data, using: key)
            return sealed.combined!.base64EncodedString()
        } catch {
            OshiLog.messaging.info("❌ Sync encrypt error: \(error)")
            return nil
        }
    }

    /// Decrypt sync data (base64 input, AES-256-GCM)
    private func decryptSyncData(_ base64String: String) -> Data? {
        guard let key = deriveSyncKey(),
              let combined = Data(base64Encoded: base64String) else { return nil }
        do {
            let box = try AES.GCM.SealedBox(combined: combined)
            return try AES.GCM.open(box, using: key)
        } catch {
            OshiLog.messaging.info("❌ Sync decrypt error: \(error)")
            return nil
        }
    }

    // MARK: - Private Methods

    private func processIncomingSyncMessage(_ syncMsg: SyncMessage) async {
        // Skip messages from this device
        guard syncMsg.deviceOrigin != deviceId else { return }
        
        // Check if message already exists
        guard let manager = MessageManager.shared else { return }
        
        let exists = manager.messages.contains { $0.id == syncMsg.messageId }
        if exists {
            OshiLog.messaging.info("⏭️ MultiDeviceSync: Message already exists, skipping")
            return
        }
        
        // Reconstruct SecureMessage
        do {
            let encryptedContent = try JSONDecoder().decode(EncryptedMessage.self, from: syncMsg.encryptedContent)
            
            let message = SecureMessage(
                id: syncMsg.messageId,
                senderAddress: String(syncMsg.senderPublicKey.prefix(8)),
                recipientAddress: String(syncMsg.recipientPublicKey.prefix(8)),
                encryptedContent: encryptedContent,
                timestamp: syncMsg.timestamp,
                isRead: syncMsg.isRead,
                deliveryStatus: .delivered,
                senderPublicKey: syncMsg.senderPublicKey,
                recipientPublicKey: syncMsg.recipientPublicKey,
                mediaAttachment: syncMsg.mediaData,
                deliveryMethod: .ipfs,
                mediaType: syncMsg.mediaType.flatMap { MediaManager.MediaType(rawValue: $0) }
            )
            
            await MainActor.run {
                manager.addMessage(message)
                OshiLog.messaging.info("✅ MultiDeviceSync: Added synced message \(syncMsg.messageId.prefix(8))...")
            }
        } catch {
            OshiLog.messaging.info("❌ MultiDeviceSync: Failed to process synced message: \(error)")
        }
    }
    
    private func processReadUpdate(_ update: ReadStatusUpdate) async {
        guard update.deviceId != deviceId else { return }
        
        await MainActor.run {
            guard let manager = MessageManager.shared,
                  let index = manager.messages.firstIndex(where: { $0.id == update.messageId }) else { return }
            
            manager.messages[index].isRead = update.isRead
            manager.saveMessages()
            OshiLog.messaging.info("✅ MultiDeviceSync: Updated read status for \(update.messageId.prefix(8))...")
        }
    }
    
    // MARK: - Profile Sync (userName, profileImage, profileLinks)

    /// Upload encrypted profile data for other devices to pull
    func uploadProfileForSync() {
        guard !DevSyncFlag.legacySyncSuppressed else { return }  // __DEVSYNC_DIRECT_2026_09_23__ direct device sync replaces /api/sync
        guard let pubKey = UserDefaults.standard.string(forKey: "publicKey"), !pubKey.isEmpty else { return }

        // __SYNC_ENCODE_OFF_MAIN_2026_09_23__ base64 of the profile image + JSON + AES off main.
        uploadQueue.async { [self] in
        var profileDict: [String: Any] = [:]
        if let name = UserDefaults.standard.string(forKey: "userName"), !name.isEmpty {
            profileDict["userName"] = name
        }
        if let imageData = UserDefaults.standard.data(forKey: "profileImageData") {
            profileDict["profileImageData"] = imageData.base64EncodedString()
        }
        if let linksData = UserDefaults.standard.data(forKey: "profileLinks") {
            profileDict["profileLinks"] = linksData.base64EncodedString()
        }

        guard !profileDict.isEmpty,
              let jsonData = try? JSONSerialization.data(withJSONObject: profileDict),
              let encrypted = encryptSyncData(jsonData) else { return }

        let payload: [String: Any] = ["deviceId": deviceId, "data": encrypted]
        guard let url = URL(string: "\(syncBaseURL)/api/sync/profile/\(urlEncodedKey(pubKey))"),
              let jsonPayload = try? JSONSerialization.data(withJSONObject: payload) else { return }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = jsonPayload
        request.timeoutInterval = 10

        URLSession.shared.dataTask(with: request) { _, response, _ in
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                OshiLog.messaging.info("✅ MultiDeviceSync: Profile uploaded for sync")
            }
        }.resume()
        }
    }

    /// Pull encrypted profile data from another device
    func pullProfileFromSync() {
        guard !DevSyncFlag.legacySyncSuppressed else { return }  // __DEVSYNC_DIRECT_2026_09_23__ direct device sync replaces /api/sync
        guard let pubKey = UserDefaults.standard.string(forKey: "publicKey"), !pubKey.isEmpty else { return }
        guard let url = URL(string: "\(syncBaseURL)/api/sync/profile/\(urlEncodedKey(pubKey))?deviceId=\(deviceId)") else { return }

        URLSession.shared.dataTask(with: url) { [weak self] data, response, error in
            guard let data = data,
                  let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200 else { return }

            do {
                let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
                guard let encryptedStr = json?["data"] as? String else {
                    OshiLog.messaging.info("📱 MultiDeviceSync: No profile sync data available")
                    return
                }
                guard let decrypted = self?.decryptSyncData(encryptedStr) else {
                    OshiLog.messaging.info("❌ MultiDeviceSync: Failed to decrypt profile data")
                    return
                }
                let profileDict = (try? JSONSerialization.jsonObject(with: decrypted) as? [String: Any]) ?? [:]

                DispatchQueue.main.async {
                    var updated = false
                    if let name = profileDict["userName"] as? String, !name.isEmpty {
                        // __NICKNAME_REBROADCAST_2026_09_23__ Un surnom CHANGÉ sur un
                        // autre appareil du compte (Android, ou Desktop via « Envoyer à
                        // mon téléphone ») arrivait ici et n'était jamais annoncé aux
                        // contacts avant le prochain envoi de profil. On le rediffuse
                        // tout de suite — seulement s'il a vraiment changé, pour ne pas
                        // réémettre à chaque tirage de 30 s. Silencieux comme toute
                        // mise à jour de profil.
                        let previous = UserDefaults.standard.string(forKey: "userName")
                        UserDefaults.standard.set(name, forKey: "userName")
                        if previous != name {
                            ContactPresenceManager.shared.broadcastProfileUpdateToContacts(via: MessageManager.shared)
                        }
                        updated = true
                    }
                    if let imageBase64 = profileDict["profileImageData"] as? String,
                       let imageData = Data(base64Encoded: imageBase64) {
                        UserDefaults.standard.set(imageData, forKey: "profileImageData")
                        NotificationCenter.default.post(name: NSNotification.Name("ProfileImageSyncedFromDevice"), object: imageData)
                        updated = true
                    }
                    if let linksBase64 = profileDict["profileLinks"] as? String,
                       let linksData = Data(base64Encoded: linksBase64) {
                        // Conflict resolution: only overwrite local links if the
                        // remote copy is strictly newer (or local has no
                        // timestamp at all). Without this, two devices each
                        // pulling+uploading on the timer can clobber each
                        // other's recent edits.
                        let remote = try? JSONDecoder().decode(ProfileLinks.self, from: linksData)
                        let localData = UserDefaults.standard.data(forKey: "profileLinks")
                        let local = localData.flatMap { try? JSONDecoder().decode(ProfileLinks.self, from: $0) }
                        let remoteAt = remote?.updatedAt ?? .distantPast
                        let localAt = local?.updatedAt ?? .distantPast
                        if remote != nil && remoteAt > localAt {
                            UserDefaults.standard.set(linksData, forKey: "profileLinks")
                            ProfileLinksManager.shared.reloadMyLinks()
                            updated = true
                        }
                    }
                    if updated {
                        OshiLog.messaging.info("✅ MultiDeviceSync: Profile synced from other device")
                    }
                }
            } catch {
                OshiLog.messaging.info("❌ MultiDeviceSync: Profile pull decode error: \(error)")
            }
        }.resume()
    }

    private func getDeviceName() -> String {
        return UIDevice.current.name
    }

    private func getDeviceType() -> SyncDevice.DeviceType {
        switch UIDevice.current.userInterfaceIdiom {
        case .phone: return .iPhone
        case .pad: return .iPad
        case .mac: return .mac
        default: return .unknown
        }
    }
    
    // MARK: - Response Models
    
    private struct RegisterResponse: Codable {
        let success: Bool
        let devices: [SyncDevice]
    }
    
    private struct DevicesResponse: Codable {
        let devices: [SyncDevice]
    }
    
    private struct UnregisterPayload: Codable {
        let publicKey: String
        let deviceId: String
    }
    
    private struct SyncMessagePayload: Codable {
        let publicKey: String
        let message: SyncMessage
    }
    
    private struct ReadStatusPayload: Codable {
        let publicKey: String
        let messageId: String
        let isRead: Bool
        let deviceId: String
    }
    
    private struct SyncPullResponse: Codable {
        let messages: [SyncMessage]
        let readUpdates: [ReadStatusUpdate]
    }
    
    private struct ReadStatusUpdate: Codable {
        let messageId: String
        let isRead: Bool
        let deviceId: String
        let timestamp: Date
    }
}

// MARK: - Multi-Device Settings View

import SwiftUI

struct MultiDeviceSyncSettingsView: View {
    @StateObject private var syncManager = MultiDeviceSyncManager.shared
    @EnvironmentObject var identityManager: IdentityManager
    @State private var showingDisableAlert = false
    
    var body: some View {
        List {
            // Sync Status Section
            Section {
                HStack {
                    Image(systemName: syncManager.isSyncEnabled ? "arrow.triangle.2.circlepath.circle.fill" : "arrow.triangle.2.circlepath.circle")
                        .foregroundColor(syncManager.isSyncEnabled ? .green : .gray)
                        .font(.title2)
                        .accessibilityHidden(true)

                    VStack(alignment: .leading, spacing: 2) {
                        Text("multi_device.title".localized)
                            .font(.headline)
                        Text(syncManager.isSyncEnabled ? "multi_device.enabled".localized : "multi_device.disabled".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }

                    Spacer()

                    Toggle("", isOn: Binding(
                        get: { syncManager.isSyncEnabled },
                        set: { newValue in
                            Task {
                                if newValue {
                                    _ = await syncManager.enableSync()
                                } else {
                                    showingDisableAlert = true
                                }
                            }
                        }
                    ))
                    .labelsHidden()
                    .accessibilityLabel(Text("multi_device.title".localized))
                }
                .accessibilityElement(children: .combine)
                
                if syncManager.isSyncEnabled {
                    HStack {
                        Text("multi_device.status".localized)
                        Spacer()
                        Text(syncManager.syncStatus.localizedString)
                            .foregroundColor(.secondary)

                        if syncManager.syncStatus == .syncing {
                            ProgressView()
                                .scaleEffect(0.8)
                                .padding(.leading, 4)
                                .accessibilityHidden(true)
                        }
                    }
                    .accessibilityElement(children: .combine)

                    if let lastSync = syncManager.lastSyncDate {
                        HStack {
                            Text("multi_device.last_sync".localized)
                            Spacer()
                            Text(lastSync, style: .relative)
                                .foregroundColor(.secondary)
                        }
                        .accessibilityElement(children: .combine)
                    }

                    Button {
                        #if !targetEnvironment(macCatalyst)
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        #endif
                        Task {
                            await syncManager.syncFromCloud()
                        }
                    } label: {
                        HStack {
                            Image(systemName: "arrow.clockwise")
                                .accessibilityHidden(true)
                            Text("multi_device.sync_now".localized)
                            Spacer()
                        }
                        .contentShape(Rectangle())
                    }
                    .disabled(syncManager.syncStatus == .syncing)
                    .accessibilityLabel(Text("multi_device.sync_now".localized))
                }
            } header: {
                Text("multi_device.synchronization".localized)
            } footer: {
                Text("multi_device.description".localized)
            }
            
            // Registered Devices Section
            if syncManager.isSyncEnabled {
                Section {
                    if syncManager.registeredDevices.isEmpty {
                        HStack(spacing: 12) {
                            Image(systemName: "antenna.radiowaves.left.and.right.slash")
                                .foregroundColor(.secondary)
                                .accessibilityHidden(true)
                            Text("multi_device.no_devices".localized)
                                .foregroundColor(.secondary)
                                .font(.subheadline)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityElement(children: .combine)
                    } else {
                        ForEach(syncManager.registeredDevices) { device in
                            DeviceRow(device: device, onRemove: {
                                Task {
                                    await syncManager.removeDevice(device)
                                }
                            })
                        }
                    }
                } header: {
                    Text(String(format: "multi_device.registered_devices".localized, syncManager.registeredDevices.count))
                }
            }
            
            // Info Section
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    InfoRow(icon: "lock.shield", text: "multi_device.info_1".localized)
                    InfoRow(icon: "icloud.and.arrow.up", text: "multi_device.info_2".localized)
                    InfoRow(icon: "eye.slash", text: "multi_device.info_3".localized)
                }
                .padding(.vertical, 4)
            } header: {
                Text("multi_device.how_it_works".localized)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: syncManager.isSyncEnabled)
        .animation(.easeInOut(duration: 0.25), value: syncManager.registeredDevices.count)
        .navigationTitle("multi_device.title".localized)
        .onAppear {
            if syncManager.isSyncEnabled {
                Task {
                    await syncManager.fetchRegisteredDevices()
                }
            }
        }
        .alert("multi_device.disable_title".localized, isPresented: $showingDisableAlert) {
            Button("common.cancel".localized, role: .cancel) { }
            Button("multi_device.disable".localized, role: .destructive) {
                Task {
                    await syncManager.disableSync()
                }
            }
        } message: {
            Text("multi_device.disable_message".localized)
        }
    }
}

struct DeviceRow: View {
    let device: MultiDeviceSyncManager.SyncDevice
    let onRemove: () -> Void
    
    var body: some View {
        HStack {
            Image(systemName: deviceIcon)
                .font(.title2)
                .foregroundColor(.blue)
                .frame(width: 30)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(device.name)
                        .font(.headline)

                    if device.isCurrentDevice {
                        Text("multi_device.this_device".localized)
                            .font(.caption2)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.green.opacity(0.2))
                            .foregroundColor(.green)
                            .cornerRadius(4)
                    }
                }

                Text(String(format: "multi_device.last_seen".localized, device.lastSeenAt.formatted(.relative(presentation: .named))))
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            if !device.isCurrentDevice {
                Button(role: .destructive) {
                    #if !targetEnvironment(macCatalyst)
                    UIImpactFeedbackGenerator(style: .rigid).impactOccurred()
                    #endif
                    onRemove()
                } label: {
                    Image(systemName: "trash")
                        .foregroundColor(.red)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(Text(String(format: "multi_device.remove_device_a11y".localized, device.name)))
            }
        }
        .accessibilityElement(children: .combine)
    }
    
    private var deviceIcon: String {
        switch device.type {
        case .iPhone: return "iphone"
        case .iPad: return "ipad"
        case .mac: return "laptopcomputer"
        case .watch: return "applewatch"
        case .unknown: return "desktopcomputer"
        }
    }
}

struct InfoRow: View {
    let icon: String
    let text: String
    
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .foregroundColor(.blue)
                .frame(width: 20)
                .accessibilityHidden(true)
            Text(text)
                .font(.subheadline)
                .foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }
}
