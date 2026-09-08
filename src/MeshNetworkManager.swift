//
//  MeshNetworkManager.swift
//  OSHI - Complete Fixed Version with Connection Stability
//  ✅ Fixed: Auto-reconnect after disconnect
//  ✅ Fixed: Service type persistence
//  ✅ Fixed: Session cleanup on disconnect
//  ✅ Fixed: Connection retry with exponential backoff
//  ✅ Fixed: Reduced timeouts for faster failure detection
//

import Foundation
import MultipeerConnectivity
import Combine
import UIKit

class MeshNetworkManager: NSObject, ObservableObject {
    @Published var connectedPeers: [MCPeerID] = []
    @Published var discoveredPeers: [MCPeerID] = []
    @Published var discoveredNetworks: [String] = []
    @Published var isAdvertising = false
    @Published var isBrowsing = false
    @Published var meshAvailable = false
    
    private var peerID: MCPeerID!
    private var sessions: [String: MCSession] = [:]
    private var advertisers: [String: MCNearbyServiceAdvertiser] = [:]
    private var browsers: [String: MCNearbyServiceBrowser] = [:]
    
    // Track which service type each peer was discovered on
    private var peerServiceTypes: [MCPeerID: String] = [:]
    
    // 🔧 NEW: Map peer public keys to MCPeerID for call routing
    private var peerPublicKeys: [String: MCPeerID] = [:]  // publicKey -> peerID
    private var peerIDToPublicKey: [MCPeerID: String] = [:]  // peerID -> publicKey
    
    // 🔧 NEW: Connection retry management
    private var connectionRetries: [MCPeerID: Int] = [:]
    private let maxConnectionRetries = 5  // 🔧 Increased from 3 to 5
    private var retryResetTimer: Timer?  // 🔧 NEW: Reset retry counters periodically
    
    // 🔧 NEW: Track invitation timestamps to detect truly stuck invitations
    private var invitationTimestamps: [MCPeerID: Date] = [:]
    private let invitationTimeout: TimeInterval = 15.0  // Consider invitation stuck after 15s
    
    // 🔧 NEW: Track pending auto-invites to prevent multiple triggers
    private var pendingAutoInvites: Set<MCPeerID> = []
    
    private var serviceTypes = [
        "ihsotahs",
        "mesh-chat",
        "p2p-message",
        "local-mesh"
    ]
    
    private var messageQueue: [QueuedMessage] = []

    // MARK: - Peer Deduplication Helpers
    // MCPeerID object identity differs across service types for the same physical device.
    // Always compare by displayName to avoid showing duplicates.

    private func hasDiscoveredPeer(named name: String) -> Bool {
        discoveredPeers.contains { $0.displayName == name }
    }

    private func hasConnectedPeer(named name: String) -> Bool {
        connectedPeers.contains { $0.displayName == name }
    }

    /// 🔧 (1.0.10 v6) Check if a SPECIFIC recipient pubkey is currently reachable
    /// via MultipeerConnectivity. Different from `connectedPeers.isEmpty` which only
    /// tells you whether ANY peer is connected. The router previously used the latter
    /// and routed every send through mesh whenever any LAN peer was online — even
    /// for recipients on the other side of the world — making the bubble report
    /// "mesh delivered" while the message bounced around the local mesh and never
    /// reached the actual recipient.
    func isPeerConnected(publicKey: String) -> Bool {
        guard !publicKey.isEmpty,
              let peerID = peerPublicKeys[publicKey] else { return false }
        return connectedPeers.contains(peerID)
    }
    
    @Published var autoConnectEnabled = true
    @Published var multiNetworkEnabled = true
    private var pendingInvitations: Set<MCPeerID> = []
    
    private var seenMessageIDs: Set<String> = []
    private let maxHops = 500
    
    // Audio packet counter for debugging
    private var meshAudioPacketsReceived: Int = 0
    private var meshAudioPacketsSent: UInt64 = 0
    private var lastAudioSendLog: Date = .distantPast
    
    private var reconnectTimer: Timer?
    private var healthCheckTimer: Timer?
    private var lastHealthCheck: Date = Date()
    
    // 🔧 NEW: Session keepalive to maintain MCSession connections
    private var keepaliveTimer: Timer?
    private let keepaliveInterval: TimeInterval = 30.0  // Send keepalive every 30 seconds
    private let KEEPALIVE_PACKET_TYPE: UInt8 = 0xAA  // Unique identifier for keepalive packets

    // 🔧 FIX R16: Track last activity per peer to detect ghost connections
    private var lastPeerActivity: [String: Date] = [:]
    private let peerStaleTimeout: TimeInterval = 90.0  // Mark peer disconnected after 90s inactivity
    
    override init() {
        super.init()
        setupPeerID()
        loadMessageQueue()

        if multiNetworkEnabled {
            setupMultipleNetworks()
        } else {
            setupSingleNetwork()
        }

        setupGroupBroadcastListener()
        setupAppLifecycleObservers()
        setupCrossPlatformMeshCallback()  // ✅ NEW: iOS ↔ Android mesh integration

        // ✅ LAZY PERMISSIONS: Do NOT auto-start mesh here.
        // Starting MPC advertising/browsing triggers the Local Network permission dialog.
        // Instead, mesh is started from IdentitySetupView.startMeshNetworkingWhenReady()
        // after the user has completed the permission + identity setup flow.
        OshiLog.mesh.info("📡 MeshNetworkManager initialized (mesh will start after identity setup)")
    }

    /// Call this to start mesh networking after permissions and identity are ready.
    func startMeshAfterSetup() {
        OshiLog.mesh.info("📡 Starting mesh networking (post-setup)...")
        startAll()
        startHealthMonitoring()
        startRetryResetTimer()
        startKeepaliveTimer()
    }

    // MARK: - CrossPlatformMesh Integration (iOS ↔ Android)

    /// Set up callback to receive messages from CrossPlatformMesh (Android peers)
    private func setupCrossPlatformMeshCallback() {
        CrossPlatformMesh.shared.addMessageCallback { [weak self] message in
            guard let self = self else { return }

            OshiLog.mesh.info("🌐 [CrossPlatformMesh] Received message from \(message.platform) peer")
            OshiLog.mesh.info("   Type: \(message.type)")
            OshiLog.mesh.info("   Sender: \(message.senderPublicKey.prefix(16))...")
            CallFileLogger.shared.log("DIAG_XMESH_RX | type=\(message.type) | from=\(message.senderPublicKey.prefix(12)) | payloadLen=\(message.payload.count) | id=\(message.id.prefix(8))")

            // Check for duplicate messages
            guard !self.seenMessageIDs.contains(message.id) else {
                OshiLog.mesh.info("   ⚠️ Duplicate message, ignoring")
                CallFileLogger.shared.log("DIAG_XMESH_RX | dup | id=\(message.id.prefix(8))")
                return
            }
            self.seenMessageIDs.insert(message.id)

            // Route based on message type
            switch message.type {
            case "TEXT_MESSAGE", "RELAY":
                // Try to decode as SecureMessage and deliver
                if let payloadData = message.payload.data(using: .utf8) {
                    CallFileLogger.shared.log("DIAG_XMESH_RX | text_handoff | bytes=\(payloadData.count) | from=\(message.senderPublicKey.prefix(12))")
                    self.handleCrossPlatformTextMessage(payloadData, from: message.senderPublicKey)
                } else {
                    CallFileLogger.shared.log("DIAG_XMESH_RX | text_decode_fail_utf8 | payloadLen=\(message.payload.count)")
                }

            case "CALL_SIGNAL":
                // Handle call signal from Android — use keys expected by handleCallSignalNotification
                if let signalData = Data(base64Encoded: message.payload) {
                    NotificationCenter.default.post(
                        name: .didReceiveCallSignal,
                        object: nil,
                        userInfo: [
                            "data": signalData,
                            "peerName": message.senderName,
                            "peerPublicKey": message.senderPublicKey,
                            "platform": message.platform
                        ]
                    )
                }

            case "CALL_AUDIO":
                // Handle audio from Android
                if let audioData = Data(base64Encoded: message.payload) {
                    NotificationCenter.default.post(
                        name: .didReceiveCallAudio,
                        object: nil,
                        userInfo: [
                            "senderPublicKey": message.senderPublicKey,
                            "audioData": audioData,
                            "platform": message.platform
                        ]
                    )
                }

            case "LOCATION_MESSAGE":
                // Handle location from Android
                OshiLog.mesh.info("   📍 Location message from \(message.platform)")
                if let locationData = Data(base64Encoded: message.payload) {
                    self.handleCrossPlatformLocationMessage(locationData, from: message.senderPublicKey)
                }

            case "MEDIA_MESSAGE":
                // Handle media (image/video) from Android
                OshiLog.mesh.info("   🖼️ Media message from \(message.platform)")
                if let payloadData = message.payload.data(using: .utf8) {
                    self.handleCrossPlatformMediaMessage(payloadData, from: message.senderPublicKey)
                }

            case "DOCUMENT_MESSAGE":
                // Handle document from Android
                OshiLog.mesh.info("   📄 Document message from \(message.platform)")
                if let payloadData = message.payload.data(using: .utf8) {
                    self.handleCrossPlatformDocumentMessage(payloadData, from: message.senderPublicKey)
                }

            case "PUBLIC_GROUP_AD":
                // Handle public group ad from Android
                OshiLog.mesh.info("   📡 Public group ad from \(message.platform)")
                if let adData = message.payload.data(using: .utf8),
                   let json = try? JSONSerialization.jsonObject(with: adData) as? [String: Any],
                   let groupId = json["groupId"] as? String,
                   let groupName = json["groupName"] as? String,
                   let adminPublicKey = json["adminPublicKey"] as? String {
                    let memberCount = json["memberCount"] as? Int ?? 1
                    let avatar = json["avatar"] as? String
                    let groupAd = PublicGroupAd(
                        groupId: UUID(uuidString: groupId) ?? UUID(),
                        groupName: groupName,
                        adminPublicKey: adminPublicKey,
                        memberCount: memberCount,
                        avatar: avatar,
                        timestamp: Date()
                    )
                    DispatchQueue.main.async {
                        NotificationCenter.default.post(
                            name: NSNotification.Name("PublicGroupDiscovered"),
                            object: nil,
                            userInfo: ["groupAd": groupAd]
                        )
                    }
                }

            case "GROUP_UPDATE":
                // Handle group sync request (and other group updates) from Android via mesh
                OshiLog.mesh.info("   📢 GROUP_UPDATE from \(message.platform) peer \(message.senderPublicKey.prefix(8))...")
                let prefix = "\u{1F4E2}GROUP_UPDATE\u{1F4E2}"
                let jsonStr = message.payload.hasPrefix(prefix)
                    ? String(message.payload.dropFirst(prefix.count))
                    : message.payload
                if let jsonData = jsonStr.data(using: .utf8) {
                    DispatchQueue.main.async {
                        NotificationCenter.default.post(
                            name: NSNotification.Name("GroupUpdateReceived"),
                            object: nil,
                            userInfo: ["groupData": jsonData]
                        )
                    }
                }

            case "GROUP_MESSAGE":
                // Handle encrypted group message envelope from Android via mesh.
                // Payload is the same IPFS envelope JSON {v:2, envelope:..., nonce:..., hint:...}.
                // Post as a notification for GroupMessaging to decrypt with the group key.
                OshiLog.mesh.info("   👥 GROUP_MESSAGE (encrypted envelope) from \(message.platform) peer \(message.senderPublicKey.prefix(8))...")
                if let envelopeData = message.payload.data(using: .utf8) {
                    DispatchQueue.main.async {
                        NotificationCenter.default.post(
                            name: NSNotification.Name("MeshGroupEnvelopeReceived"),
                            object: nil,
                            userInfo: [
                                "envelopeData": envelopeData,
                                "senderPublicKey": message.senderPublicKey
                            ]
                        )
                    }
                }

            default:
                OshiLog.mesh.info("   ℹ️ Unhandled message type: \(message.type)")
            }
        }
    }

    /// Handle text/relay messages from CrossPlatformMesh
    private func handleCrossPlatformTextMessage(_ data: Data, from senderPublicKey: String) {
        let prefix = String(data: data.prefix(40), encoding: .utf8) ?? "<binary>"
        CallFileLogger.shared.log("DIAG_XTEXT_HANDLE | bytes=\(data.count) | from=\(senderPublicKey.prefix(12)) | prefix=\(prefix)")

        // Try to decode as RelayMessage first (iOS → iOS mesh)
        if let relayMessage = try? JSONDecoder().decode(RelayMessage.self, from: data) {
            OshiLog.mesh.info("   📩 Decoded as RelayMessage")
            CallFileLogger.shared.log("DIAG_XTEXT_DECODE | path=RelayMessage")
            DispatchQueue.main.async {
                NotificationCenter.default.post(
                    name: NSNotification.Name("MeshMessageReceived"),
                    object: nil,
                    userInfo: ["message": relayMessage.message]
                )
            }
            return
        }

        // Try to decode as SecureMessage (iOS → iOS mesh)
        if let secureMessage = try? JSONDecoder().decode(SecureMessage.self, from: data) {
            OshiLog.mesh.info("   📩 Decoded as SecureMessage")
            CallFileLogger.shared.log("DIAG_XTEXT_DECODE | path=SecureMessage")
            DispatchQueue.main.async {
                NotificationCenter.default.post(
                    name: NSNotification.Name("MeshMessageReceived"),
                    object: nil,
                    userInfo: ["message": secureMessage]
                )
            }
            return
        }

        // Handle Android EncryptedMessage format:
        // Android now sends EncryptedMessage JSON directly as payload (no base64 wrapper).
        // Older Android builds may still send base64(EncryptedMessage JSON) — both are handled.
        if let payloadString = String(data: data, encoding: .utf8) {

            // Helper: wrap EncryptedMessage in SecureMessage and deliver it
            func deliver(_ encryptedMessage: EncryptedMessage) {
                let secureMessage = SecureMessage(
                    id: UUID().uuidString,
                    senderAddress: senderPublicKey,
                    recipientAddress: MessageManager.sharedIdentityManager?.publicKey ?? "",
                    encryptedContent: encryptedMessage,
                    timestamp: Date(),
                    isRead: false,
                    deliveryStatus: .delivered,
                    senderPublicKey: senderPublicKey,
                    recipientPublicKey: MessageManager.sharedIdentityManager?.publicKey ?? ""
                )
                DispatchQueue.main.async {
                    NotificationCenter.default.post(
                        name: NSNotification.Name("MeshMessageReceived"),
                        object: nil,
                        userInfo: ["message": secureMessage]
                    )
                }
            }

            // Path 1: Direct EncryptedMessage JSON (new Android format, payload starts with '{')
            if payloadString.hasPrefix("{"),
               let jsonData = payloadString.data(using: .utf8),
               let encryptedMessage = try? JSONDecoder().decode(EncryptedMessage.self, from: jsonData) {
                OshiLog.mesh.info("   📩 [Android mesh] Decoded EncryptedMessage JSON directly")
                CallFileLogger.shared.log("DIAG_XTEXT_DECODE | path=EncryptedMessage_JSON")
                deliver(encryptedMessage)
                return
            }

            // Path 2: base64(EncryptedMessage JSON) — legacy Android format or other senders
            if let base64Data = Data(base64Encoded: payloadString),
               let encryptedMessage = try? JSONDecoder().decode(EncryptedMessage.self, from: base64Data) {
                OshiLog.mesh.info("   📩 [Android mesh] Decoded base64-wrapped EncryptedMessage JSON")
                CallFileLogger.shared.log("DIAG_XTEXT_DECODE | path=Base64_EncryptedMessage")
                deliver(encryptedMessage)
                return
            }

            OshiLog.mesh.info("   ⚠️ Could not decode EncryptedMessage from payload (len=\(payloadString.count), prefix=\(payloadString.prefix(20))...)")
            CallFileLogger.shared.log("DIAG_XTEXT_DECODE | path=NONE_FAILED | len=\(payloadString.count) | prefix=\(payloadString.prefix(40))")
        }

        OshiLog.mesh.info("   ⚠️ Could not decode message from CrossPlatformMesh")
    }

    /// Handle location messages from CrossPlatformMesh (Android peers)
    private func handleCrossPlatformLocationMessage(_ data: Data, from senderPublicKey: String) {
        OshiLog.mesh.info("   📍 Processing location message from Android")

        // Try to decode as LocationMessage format
        if let locationDict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            DispatchQueue.main.async {
                NotificationCenter.default.post(
                    name: NSNotification.Name("CrossPlatformLocationReceived"),
                    object: nil,
                    userInfo: [
                        "senderPublicKey": senderPublicKey,
                        "locationData": locationDict,
                        "platform": "android"
                    ]
                )
            }
            OshiLog.mesh.info("   ✅ Location delivered via notification")
            return
        }

        // Fallback: post raw location data as notification
        let locationContent = String(data: data, encoding: .utf8) ?? ""
        DispatchQueue.main.async {
            NotificationCenter.default.post(
                name: NSNotification.Name("CrossPlatformLocationReceived"),
                object: nil,
                userInfo: [
                    "senderPublicKey": senderPublicKey,
                    "locationData": ["raw": locationContent],
                    "platform": "android"
                ]
            )
        }
        OshiLog.mesh.info("   ✅ Raw location data delivered via notification")
    }

    /// Handle media messages from CrossPlatformMesh (Android peers)
    private func handleCrossPlatformMediaMessage(_ data: Data, from senderPublicKey: String) {
        OshiLog.mesh.info("   🖼️ Processing media message from Android")
        CallFileLogger.shared.log("DIAG_XMEDIA_HANDLE | bytes=\(data.count) | from=\(senderPublicKey.prefix(12))")

        // Cross-platform mesh media envelope (iOS-compatible plaintext JSON, sent by
        // both iOS sender (CrossPlatformMesh.sendMedia) and Android sender after the
        // 2026-04-26 fix). Required fields: `data`, `mediaType`. Optional: `fileName`,
        // `fileSize`, `caption`, `isViewOnce`. Field name aliases accepted for the type
        // (`mediaType` and `type`) and for the caption (`caption` and `text`) so the
        // older Android encrypted-payload field names also parse.
        guard let mediaPayload = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let base64Data = mediaPayload["data"] as? String,
              let mediaData = Data(base64Encoded: base64Data) else {
            OshiLog.mesh.info("   ⚠️ Invalid media payload format (data missing or not base64)")
            CallFileLogger.shared.log("DIAG_XMEDIA_HANDLE | parse_fail")
            return
        }

        let rawType = (mediaPayload["mediaType"] as? String)
            ?? (mediaPayload["type"] as? String)
            ?? "image"
        // Android sends UPPERCASE enum names (IMAGE/VIDEO/AUDIO/DOCUMENT); iOS
        // MediaManager.MediaType uses lowercase. Normalize.
        let mediaTypeStr = rawType.lowercased()
        let fileName = mediaPayload["fileName"] as? String ?? "media_\(UUID().uuidString.prefix(8))"
        let fileSize = mediaPayload["fileSize"] as? Int ?? mediaData.count
        let caption = (mediaPayload["caption"] as? String) ?? (mediaPayload["text"] as? String) ?? ""
        let isViewOnce = mediaPayload["isViewOnce"] as? Bool ?? false

        // Wrap into a SecureMessage and route through the SAME pipeline as iOS↔iOS
        // mesh text/media. Without this, the previous code posted only a
        // `CrossPlatformMediaReceived` notification that had no observer in the
        // codebase — so Android-→-iOS mesh media silently disappeared.
        let placeholderEncrypted = EncryptedMessage(
            ciphertext: "",
            signature: "",
            senderPublicKey: senderPublicKey,
            timestamp: Date()
        )
        let myKey = MessageManager.sharedIdentityManager?.publicKey ?? ""
        let typeEnum = MediaManager.MediaType(rawValue: mediaTypeStr) ?? .image
        let secureMessage = SecureMessage(
            id: UUID().uuidString,
            senderAddress: String(senderPublicKey.prefix(8)),
            recipientAddress: String(myKey.prefix(8)),
            encryptedContent: placeholderEncrypted,
            timestamp: Date(),
            isRead: false,
            deliveryStatus: .delivered,
            senderPublicKey: senderPublicKey,
            recipientPublicKey: myKey,
            plaintextContent: caption,
            mediaAttachment: mediaData,
            deliveryMethod: .mesh,
            mediaType: typeEnum,
            mediaFileName: fileName,
            isViewOnce: isViewOnce
        )
        DispatchQueue.main.async {
            NotificationCenter.default.post(
                name: NSNotification.Name("MeshMessageReceived"),
                object: nil,
                userInfo: ["message": secureMessage]
            )
        }
        OshiLog.mesh.info("   ✅ Media (\(mediaTypeStr)) delivered to MessageManager: \(fileName) (\(fileSize) bytes)")
        CallFileLogger.shared.log("DIAG_XMEDIA_HANDLE | ok | type=\(mediaTypeStr) | bytes=\(fileSize) | name=\(fileName)")
    }

    /// Handle document messages from CrossPlatformMesh (Android peers)
    private func handleCrossPlatformDocumentMessage(_ data: Data, from senderPublicKey: String) {
        OshiLog.mesh.info("   📄 Processing document message from Android")

        guard let docPayload = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let base64Data = docPayload["data"] as? String,
              let documentData = Data(base64Encoded: base64Data),
              let fileName = docPayload["fileName"] as? String else {
            OshiLog.mesh.info("   ⚠️ Invalid document payload format")
            return
        }

        let mimeType = docPayload["mimeType"] as? String ?? "application/octet-stream"
        let fileSize = docPayload["fileSize"] as? Int ?? documentData.count

        DispatchQueue.main.async {
            NotificationCenter.default.post(
                name: NSNotification.Name("CrossPlatformDocumentReceived"),
                object: nil,
                userInfo: [
                    "senderPublicKey": senderPublicKey,
                    "documentData": documentData,
                    "fileName": fileName,
                    "mimeType": mimeType,
                    "fileSize": fileSize,
                    "platform": "android"
                ]
            )
        }
        OshiLog.mesh.info("   ✅ Document delivered: \(fileName) (\(fileSize) bytes)")
    }

    // 🔧 NEW: Reset retry counters every 60 seconds to give peers another chance
    private func startRetryResetTimer() {
        retryResetTimer?.invalidate()
        retryResetTimer = Timer.scheduledTimer(withTimeInterval: 60.0, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            
            // Reset retry counters for peers that have maxed out
            let maxedOutPeers = self.connectionRetries.filter { $0.value >= self.maxConnectionRetries }
            if !maxedOutPeers.isEmpty {
                OshiLog.mesh.info("🔄 Resetting retry counters for \(maxedOutPeers.count) peer(s)")
                for (peerID, _) in maxedOutPeers {
                    self.connectionRetries[peerID] = 0
                    
                    // Try to reconnect if still discovered
                    if self.hasDiscoveredPeer(named: peerID.displayName),
                       let serviceType = self.peerServiceTypes[peerID],
                       !self.hasConnectedPeer(named: peerID.displayName) {
                        OshiLog.mesh.info("🔄 Auto-retrying connection to \(peerID.displayName) after reset")
                        DispatchQueue.main.asyncAfter(deadline: .now() + Double.random(in: 1.0...3.0)) {
                            self.invitePeer(peerID, to: serviceType)
                        }
                    }
                }
            }
        }
    }
    
    // 🔧 NEW: Session keepalive to maintain MCSession connections
    // MCSession can disconnect after inactivity - sending periodic small packets prevents this
    private func startKeepaliveTimer() {
        keepaliveTimer?.invalidate()
        keepaliveTimer = Timer.scheduledTimer(withTimeInterval: keepaliveInterval, repeats: true) { [weak self] _ in
            self?.sendKeepaliveToAllPeers()
        }
    }
    
    private func sendKeepaliveToAllPeers() {
        // 🔧 FIX R16: Check for stale/ghost peers before sending keepalives
        let now = Date()
        var stalePeerNames: [String] = []
        for (peerId, lastActivity) in lastPeerActivity {
            if now.timeIntervalSince(lastActivity) > peerStaleTimeout {
                stalePeerNames.append(peerId)
                lastPeerActivity.removeValue(forKey: peerId)
                OshiLog.mesh.info("👻 Stale peer detected (no activity for \(Int(peerStaleTimeout))s): \(peerId)")
            }
        }
        // Remove stale peers from connectedPeers
        if !stalePeerNames.isEmpty {
            DispatchQueue.main.async {
                self.connectedPeers.removeAll { stalePeerNames.contains($0.displayName) }
                self.meshAvailable = !self.connectedPeers.isEmpty
            }
        }

        guard !connectedPeers.isEmpty else { return }

        // Create minimal keepalive packet: [type(1)][timestamp(8)]
        var keepaliveData = Data()
        keepaliveData.append(KEEPALIVE_PACKET_TYPE)
        var timestamp = UInt64(Date().timeIntervalSince1970 * 1000)  // Milliseconds
        keepaliveData.append(Data(bytes: &timestamp, count: 8))

        // Send to all connected peers via all sessions
        for (serviceType, session) in sessions {
            let peersInSession = session.connectedPeers
            guard !peersInSession.isEmpty else { continue }

            do {
                try session.send(keepaliveData, toPeers: peersInSession, with: .unreliable)
                OshiLog.mesh.info("💓 Mesh keepalive sent to \(peersInSession.count) peer(s) via \(serviceType)")
            } catch {
                // Keepalive failures are not critical, just log
                OshiLog.mesh.info("⚠️ Keepalive send failed for \(serviceType): \(error.localizedDescription)")
            }
        }
    }
    
    private func stopKeepaliveTimer() {
        keepaliveTimer?.invalidate()
        keepaliveTimer = nil
    }
    
    private func isKeepalivePacket(_ data: Data) -> Bool {
        return data.first == KEEPALIVE_PACKET_TYPE && data.count >= 9
    }
    
    // MARK: - App Lifecycle
    
    private func setupAppLifecycleObservers() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appWillEnterForeground),
            name: UIApplication.willEnterForegroundNotification,
            object: nil
        )
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appWillResignActive),
            name: UIApplication.willResignActiveNotification,
            object: nil
        )
    }
    
    @objc private func appWillEnterForeground() {
        OshiLog.mesh.info("\(NSLocalizedString("mesh.log.app_entering_foreground", comment: "Log: App entering foreground"))")
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            self?.forceReconnect()
        }
    }
    
    @objc private func appDidBecomeActive() {
        OshiLog.mesh.info("\(NSLocalizedString("mesh.log.app_became_active", comment: "Log: App became active"))")
        if !isAdvertising || !isBrowsing {
            startAll()
        }
        startHealthMonitoring()
    }
    
    @objc private func appWillResignActive() {
        OshiLog.mesh.info("\(NSLocalizedString("mesh.log.app_going_background", comment: "Log: App going to background"))")
        healthCheckTimer?.invalidate()
    }
    
    // MARK: - Health Monitoring
    
    private func startHealthMonitoring() {
        healthCheckTimer?.invalidate()
        healthCheckTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            self?.performHealthCheck()
        }
        performHealthCheck()
    }
    
    private func performHealthCheck() {
        lastHealthCheck = Date()
        
        let wasMeshAvailable = meshAvailable
        meshAvailable = !connectedPeers.isEmpty
        
        if wasMeshAvailable && !meshAvailable {
            OshiLog.mesh.info("\(NSLocalizedString("mesh.log.network_lost", comment: "Log: Mesh network lost"))")
            NotificationCenter.default.post(name: .meshNetworkLost, object: nil)
        } else if !wasMeshAvailable && meshAvailable {
            OshiLog.mesh.info("\(NSLocalizedString("mesh.log.network_restored", comment: "Log: Mesh network restored"))")
            NotificationCenter.default.post(name: .meshNetworkRestored, object: nil)
            processMessageQueue()
        }
        
        if !isAdvertising || !isBrowsing {
            OshiLog.mesh.info("\(NSLocalizedString("mesh.log.restarting_mesh", comment: "Log: Restarting mesh"))")
            startAll()
        }
        
        if connectedPeers.isEmpty && discoveredPeers.isEmpty {
            if Date().timeIntervalSince(lastHealthCheck) > 30 {
                OshiLog.mesh.info("\(NSLocalizedString("mesh.log.forcing_reconnect", comment: "Log: Forcing reconnect"))")
                forceReconnect()
            }
        }
    }
    
    // MARK: - Force Reconnect
    
    func forceReconnect() {
        OshiLog.mesh.info("\(NSLocalizedString("mesh.log.force_reconnecting", comment: "Log: Force reconnecting"))")
        
        stopAll()
        
        // 🔧 Random delay (0.5-2.0s) to avoid collision when both devices reconnect simultaneously
        let randomDelay = Double.random(in: 0.5...2.0)
        OshiLog.mesh.info("   ⏱️ Waiting \(String(format: "%.1f", randomDelay))s before reconnecting...")
        
        DispatchQueue.main.asyncAfter(deadline: .now() + randomDelay) { [weak self] in
            guard let self = self else { return }
            
            if self.multiNetworkEnabled {
                self.setupMultipleNetworks()
            } else {
                self.setupSingleNetwork()
            }
            
            self.startAll()
            self.startKeepaliveTimer()  // 🔧 NEW: Restart keepalive timer
            OshiLog.mesh.info("\(NSLocalizedString("mesh.log.reconnect_complete", comment: "Log: Reconnect complete"))")
        }
    }
    
    private func stopAll() {
        OshiLog.mesh.info("\(NSLocalizedString("mesh.log.stopping_all", comment: "Log: Stopping all mesh networking"))")
        
        stopAdvertising()
        stopBrowsing()
        stopKeepaliveTimer()  // 🔧 NEW: Stop keepalive timer
        
        for (serviceType, session) in sessions {
            if !session.connectedPeers.isEmpty {
                session.disconnect()
                OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.disconnected_session", comment: "Log: Disconnected session"), serviceType))")
            }
        }
        
        connectedPeers.removeAll()
        lastPeerActivity.removeAll()  // 🔧 FIX R16: Clean up activity tracking
        discoveredPeers.removeAll()
        pendingInvitations.removeAll()
        // 🔧 DON'T clear peerServiceTypes here - keep for reconnection
        
        OshiLog.mesh.info("\(NSLocalizedString("mesh.log.all_stopped", comment: "Log: All mesh networking stopped"))")
    }
    
    // MARK: - Network Setup
    
    private func setupMultipleNetworks() {
        // 🔧 Clear old sessions/advertisers/browsers first
        cleanupNetworkResources()
        
        OshiLog.mesh.info("🌐 Setting up multiple service type networks...")
        
        for serviceType in serviceTypes {
            setupNetwork(serviceType: serviceType)
        }
        
        OshiLog.mesh.info("✅ All service types ready: \(serviceTypes.joined(separator: ", "))")
    }
    
    private func setupSingleNetwork() {
        // 🔧 Clear old sessions/advertisers/browsers first
        cleanupNetworkResources()
        
        guard let serviceType = serviceTypes.first else { return }
        setupNetwork(serviceType: serviceType)
        OshiLog.mesh.info("✅ Single service type ready: \(serviceType)")
    }
    
    private func cleanupNetworkResources() {
        // Stop and clear advertisers
        for advertiser in advertisers.values {
            advertiser.stopAdvertisingPeer()
        }
        advertisers.removeAll()
        
        // Stop and clear browsers
        for browser in browsers.values {
            browser.stopBrowsingForPeers()
        }
        browsers.removeAll()
        
        // Disconnect and clear sessions
        for session in sessions.values {
            session.disconnect()
        }
        sessions.removeAll()
        
        isAdvertising = false
        isBrowsing = false
        
        OshiLog.mesh.info("🧹 Cleaned up old network resources")
    }
    
    private func setupNetwork(serviceType: String) {
        // Skip if already set up for this service type
        if sessions[serviceType] != nil {
            OshiLog.mesh.info("⏭️ Network \(serviceType) already set up, skipping")
            return
        }
        
        let session = MCSession(
            peer: peerID,
            securityIdentity: nil,
            encryptionPreference: .required  // [C2] restored: link-encrypt so routing metadata isn't sniffable. .required interoperates with existing .optional peers (only .none is incompatible).
        )
        session.delegate = self
        sessions[serviceType] = session
        
        let advertiser = MCNearbyServiceAdvertiser(
            peer: peerID,
            // NOT LOCALIZED: this is the dictionary key the *peer device* reads
            // out of discoveryInfo (see didFind, `networkKey` below). Translating
            // it would make a French phone advertise a key an English phone
            // cannot look up, so the two would never pair. Cross-device wire
            // format — keep it a literal.
            discoveryInfo: [MeshDiscoveryKeys.network: serviceType],
            serviceType: serviceType
        )
        advertiser.delegate = self
        advertisers[serviceType] = advertiser
        
        let browser = MCNearbyServiceBrowser(peer: peerID, serviceType: serviceType)
        browser.delegate = self
        browsers[serviceType] = browser
    }
    
    // MARK: - Peer ID Setup
    
    private func setupPeerID() {
        let deviceName = UIDevice.current.name
        
        // 🔧 Add unique suffix to avoid collision when multiple devices have same name (e.g., "iPhone")
        // Use last 4 characters of device identifier for uniqueness
        let uniqueSuffix = String(UIDevice.current.identifierForVendor?.uuidString.suffix(4) ?? "")
        let uniqueName = uniqueSuffix.isEmpty ? deviceName : "\(deviceName)-\(uniqueSuffix)"
        
        peerID = MCPeerID(displayName: uniqueName)
        OshiLog.mesh.info("📱 Peer ID created: \(uniqueName)")
    }
    
    // MARK: - Start/Stop
    
    func startAll() {
        startAdvertising()
        startBrowsing()
    }
    
    func startAdvertising() {
        guard !isAdvertising else { return }
        
        OshiLog.mesh.info("\(NSLocalizedString("mesh.log.starting_advertising", comment: "Log: Starting advertising"))")
        
        for (network, advertiser) in advertisers {
            advertiser.startAdvertisingPeer()
            OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.advertising_on", comment: "Log: Advertising on"), network))")
        }
        
        isAdvertising = true
        OshiLog.mesh.info("✅ \(NSLocalizedString("mesh.log.advertising_all_networks", comment: "Log: Advertising on all networks"))")
    }
    
    func stopAdvertising() {
        guard isAdvertising else { return }
        
        OshiLog.mesh.info("\(NSLocalizedString("mesh.log.stopping_advertising", comment: "Log: Stopping advertising"))")
        
        for advertiser in advertisers.values {
            advertiser.stopAdvertisingPeer()
        }
        
        isAdvertising = false
        OshiLog.mesh.info("\(NSLocalizedString("mesh.log.advertising_stopped", comment: "Log: Advertising stopped"))")
    }
    
    func startBrowsing() {
        guard !isBrowsing else { return }
        
        OshiLog.mesh.info("\(NSLocalizedString("mesh.log.starting_browsing", comment: "Log: Starting browsing"))")
        
        for (network, browser) in browsers {
            browser.startBrowsingForPeers()
            OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.browsing_on", comment: "Log: Browsing on"), network))")
            OshiLog.mesh.info("   🔍 Browser delegate set: \(browser.delegate != nil)")
        }
        
        isBrowsing = true
        OshiLog.mesh.info("✅ \(NSLocalizedString("mesh.log.browsing_all_networks", comment: "Log: Browsing on all networks"))")
        OshiLog.mesh.info("   📱 My peer ID: \(peerID.displayName)")
        OshiLog.mesh.info("   🔢 Active browsers: \(browsers.count)")
    }
    
    func stopBrowsing() {
        guard isBrowsing else { return }
        
        OshiLog.mesh.info("\(NSLocalizedString("mesh.log.stopping_browsing", comment: "Log: Stopping browsing"))")
        
        for browser in browsers.values {
            browser.stopBrowsingForPeers()
        }
        
        isBrowsing = false
        OshiLog.mesh.info("\(NSLocalizedString("mesh.log.browsing_stopped", comment: "Log: Browsing stopped"))")
    }
    
    // MARK: - Public API for PeersView
    
    /// Get the service type that a peer was discovered on
    func getServiceType(for peerID: MCPeerID) -> String? {
        return peerServiceTypes[peerID]
    }
    
    // MARK: - Manual Connection
    
    func invitePeer(_ peerID: MCPeerID, to requestedServiceType: String) {
        OshiLog.mesh.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        OshiLog.mesh.info("📤 INVITING PEER: \(peerID.displayName)")
        
        // Prevent duplicate invitations (compare by displayName)
        guard !hasConnectedPeer(named: peerID.displayName) else {
            OshiLog.mesh.info("   ⏭️ Already connected to \(peerID.displayName)")
            OshiLog.mesh.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            return
        }
        
        // Check if invitation is truly stuck (older than timeout)
        if pendingInvitations.contains(peerID) {
            if let timestamp = invitationTimestamps[peerID],
               Date().timeIntervalSince(timestamp) < invitationTimeout {
                OshiLog.mesh.info("   ⏭️ Invitation already pending (started \(Int(Date().timeIntervalSince(timestamp)))s ago)")
                OshiLog.mesh.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                return
            }
            
            // Invitation is stuck, force clear and retry
            OshiLog.mesh.info("   🔄 Clearing stuck invitation (older than \(Int(invitationTimeout))s)")
            pendingInvitations.remove(peerID)
            invitationTimestamps.removeValue(forKey: peerID)
        }
        
        // 🔧 NEW: Try ALL service types, not just one
        // This increases chances of successful connection
        let preferredType = peerServiceTypes[peerID] ?? requestedServiceType
        let allTypes = [preferredType] + serviceTypes.filter { $0 != preferredType }
        
        var invitationSent = false
        for serviceType in allTypes {
            guard let session = sessions[serviceType],
                  let browser = browsers[serviceType] else {
                continue
            }
            
            if !invitationSent {
                pendingInvitations.insert(peerID)
                invitationTimestamps[peerID] = Date()
                
                OshiLog.mesh.info("   📋 Service type: \(serviceType)")
                OshiLog.mesh.info("   📋 Session: \(ObjectIdentifier(session))")
                OshiLog.mesh.info("   📋 Current session peers: \(session.connectedPeers.map { $0.displayName })")
                OshiLog.mesh.info("   📨 Sending invitation with 10s timeout...")
                
                browser.invitePeer(
                    peerID,
                    to: session,
                    withContext: nil,
                    timeout: 10
                )
                
                invitationSent = true
                peerServiceTypes[peerID] = serviceType
            }
        }
        
        if !invitationSent {
            OshiLog.mesh.info("   ❌ No valid session/browser found for any service type")
        }
        
        OshiLog.mesh.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        
        // Clear pending after timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 12) { [weak self] in
            self?.pendingInvitations.remove(peerID)
            self?.invitationTimestamps.removeValue(forKey: peerID)
        }
    }

    func disconnectPeer(_ peerID: MCPeerID) {
        OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.disconnecting_peer", comment: "Log: Disconnecting peer"), peerID.displayName))")
        
        for (serviceType, session) in sessions {
            if session.connectedPeers.contains(peerID) {
                OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.removing_from_session", comment: "Log: Removing from session"), serviceType))")
            }
        }
        
        DispatchQueue.main.async { [weak self] in
            self?.connectedPeers.removeAll { $0.displayName == peerID.displayName }
            // 🔧 KEEP service type for reconnection - DON'T remove it!
            // self?.peerServiceTypes.removeValue(forKey: peerID)
            self?.meshAvailable = !(self?.connectedPeers.isEmpty ?? true)
            
            if !(self?.meshAvailable ?? true) {
                OshiLog.mesh.info("\(NSLocalizedString("mesh.log.no_more_peers", comment: "Log: No more peers"))")
            }
            
            OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.peer_removed", comment: "Log: Peer removed"), peerID.displayName))")
        }
    }
    
    // MARK: - Session Reset
    
    func resetSessions() {
        OshiLog.mesh.info("💥 Resetting all mesh sessions...")
        
        stopAdvertising()
        stopBrowsing()
        
        for (_, session) in sessions {
            session.disconnect()
        }
        
        connectedPeers.removeAll()
        lastPeerActivity.removeAll()  // 🔧 FIX R16: Clean up activity tracking
        discoveredPeers.removeAll()
        pendingInvitations.removeAll()
        peerServiceTypes.removeAll()
        connectionRetries.removeAll()  // 🔧 NEW: Clear retry counters
        
        sessions.removeAll()
        advertisers.removeAll()
        browsers.removeAll()
        
        if multiNetworkEnabled {
            setupMultipleNetworks()
        } else {
            setupSingleNetwork()
        }
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            self?.startAll()
        }
        
        OshiLog.mesh.info("✅ Mesh reset complete")
    }
    
    // MARK: - Message Sending
    
    func sendMessage(_ message: SecureMessage, to recipientAddress: String) throws {
        OshiLog.mesh.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        OshiLog.mesh.info("📤 MeshNetworkManager.sendMessage")
        OshiLog.mesh.info("   Message ID: \(message.id.prefix(8))...")
        OshiLog.mesh.info("   Sender: \(message.senderPublicKey.prefix(16))...")
        OshiLog.mesh.info("   Recipient: \(message.recipientPublicKey.prefix(16))...")
        OshiLog.mesh.info("   Connected peers: \(connectedPeers.count)")
        OshiLog.mesh.info("   CrossPlatform peers: \(CrossPlatformMesh.shared.connectedPeers.count)")
        OshiLog.mesh.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        // ✅ Try CrossPlatformMesh first for iOS ↔ Android communication — but only
        // if we can actually deliver RIGHT NOW (live TCP connection), and only
        // treat it as sent if the transport reports it handed off the payload.
        // Gating on `isPeerLiveConnected` (not the ≤90s-stale `isPeerReachable`)
        // plus acting on the returned Bool is what stops the phantom "delivered"
        // bug: on non-delivery we fall through to Multipeer and ultimately throw,
        // so MessageManager's catch → IPFS fallback engages.
        if CrossPlatformMesh.shared.isPeerLiveConnected(recipientAddress) {
            OshiLog.mesh.info("🌐 Recipient live via CrossPlatformMesh (iOS ↔ Android)")

            // Check if this is a location message
            if let content = message.plaintextContent, content.hasPrefix("📍LOCATION📍") {
                OshiLog.mesh.info("   📍 Sending as LOCATION_MESSAGE")
                if let locationData = content.dropFirst("📍LOCATION📍".count).data(using: .utf8),
                   CrossPlatformMesh.shared.sendLocation(to: recipientAddress, locationData: locationData) {
                    OshiLog.mesh.info("✅ Location delivered via CrossPlatformMesh")
                    return
                }
            }
            // Check if this is a media message
            else if let mediaData = message.mediaAttachment, let mediaType = message.mediaType {
                OshiLog.mesh.info("   🖼️ Sending as MEDIA_MESSAGE (type: \(mediaType.rawValue))")
                let fileName = message.mediaFileName ?? "media_\(Date().timeIntervalSince1970)"
                if CrossPlatformMesh.shared.sendMedia(
                    to: recipientAddress,
                    mediaData: mediaData,
                    mediaType: mediaType.rawValue,
                    fileName: fileName
                ) {
                    OshiLog.mesh.info("✅ Media delivered via CrossPlatformMesh")
                    return
                }
            }
            // Regular text message
            else if let messageData = try? JSONEncoder().encode(message),
                    CrossPlatformMesh.shared.relayMessage(messageData, to: recipientAddress) {
                OshiLog.mesh.info("✅ Message delivered via CrossPlatformMesh")
                return
            }

            // Not delivered by CrossPlatformMesh → do NOT return success.
            OshiLog.mesh.info("↩️ CrossPlatformMesh could not deliver; trying MultipeerConnectivity")
        }

        // Check if we have any connected peers (MultipeerConnectivity OR CrossPlatformMesh)
        let hasCrossPlatformPeers = !CrossPlatformMesh.shared.connectedPeers.isEmpty
        guard !connectedPeers.isEmpty || hasCrossPlatformPeers else {
            OshiLog.mesh.info("❌ No peers connected - queueing message")
            queueMessage(message, recipientAddress: recipientAddress)
            throw MeshError.noPeersConnected
        }
        
        var sentSuccessfully = false

        // 🔧 FIX ("shows mesh, never arrives"): the Multipeer relay wire strips media.
        // `meshWireSafe()` (below) removes mediaAttachment/originalMediaData as a
        // multi-hop size/privacy measure ([C2]), and there is NO media-preserving
        // Multipeer path for an iOS↔iOS send. Relaying a media/document message here
        // therefore handed the recipient an EMPTY bubble while STILL reporting success
        // (sentSuccessfully=true below) — so the file silently vanished and no IPFS
        // fallback ever ran. Only relay/broadcast attachment-LESS messages over
        // Multipeer. A message that carries media skips this block, so it either gets
        // delivered intact over the CrossPlatform (Android) media path below, or —
        // when no media-capable peer exists — falls through to the throw at the end,
        // which makes MessageManager's catch deliver the whole file over IPFS.
        let carriesMedia = message.mediaAttachment != nil || message.originalMediaData != nil
        if !carriesMedia {
            let relayMessage = RelayMessage(
                message: message.meshWireSafe(),   // [C2] strip plaintext/media sidecar off the wire
                hopCount: 0,
                maxHops: maxHops,
                seenBy: [peerID.displayName]
            )

            seenMessageIDs.insert(message.id)

            let messageData = try JSONEncoder().encode(relayMessage)
            OshiLog.mesh.info("📦 Encoded message size: \(messageData.count) bytes")

            // 🔧 Try to find the specific recipient peer
            if let recipientPeer = findPeerByAddress(recipientAddress) {
                OshiLog.mesh.info("🎯 Found direct peer: \(recipientPeer.displayName)")
                for (network, session) in sessions {
                    if session.connectedPeers.contains(recipientPeer) {
                        do {
                            try session.send(messageData, toPeers: [recipientPeer], with: .reliable)
                            OshiLog.mesh.info("✅ Sent DIRECTLY to \(recipientPeer.displayName) via \(network)")
                            sentSuccessfully = true
                            break
                        } catch {
                            OshiLog.mesh.info("❌ Direct send failed on \(network): \(error.localizedDescription)")
                        }
                    }
                }
            } else {
                OshiLog.mesh.info("⚠️ Direct peer not found, will broadcast to all peers")
            }

            // 🔧 Broadcast to all peers if direct send failed
            if !sentSuccessfully {
                for (network, session) in sessions where !session.connectedPeers.isEmpty {
                    do {
                        try session.send(messageData, toPeers: session.connectedPeers, with: .reliable)
                        OshiLog.mesh.info("📡 BROADCAST to \(session.connectedPeers.count) peers via \(network)")
                        for peer in session.connectedPeers {
                            OshiLog.mesh.info("   → \(peer.displayName)")
                        }
                        sentSuccessfully = true
                    } catch {
                        OshiLog.mesh.info("❌ Broadcast failed on \(network): \(error.localizedDescription)")
                    }
                }
            }
        } else {
            OshiLog.mesh.info("🖼️ Media message — skipping media-stripping Multipeer relay; will try CrossPlatform media path or defer to IPFS")
        }
        
        // ✅ NEW: Final fallback - try CrossPlatformMesh broadcast to Android peers
        if !sentSuccessfully && CrossPlatformMesh.shared.isRunning {
            OshiLog.mesh.info("🌐 Attempting CrossPlatformMesh broadcast to Android peers...")

            for androidPeer in CrossPlatformMesh.shared.getAndroidPeers() {
                // Handle location messages
                if let content = message.plaintextContent, content.hasPrefix("📍LOCATION📍") {
                    if let locationData = content.dropFirst("📍LOCATION📍".count).data(using: .utf8) {
                        if CrossPlatformMesh.shared.sendLocation(to: androidPeer.publicKey, locationData: locationData) {
                            OshiLog.mesh.info("   → Location delivered to Android peer: \(androidPeer.displayName)")
                            sentSuccessfully = true
                        }
                        continue
                    }
                }

                // Handle media messages
                if let mediaData = message.mediaAttachment, let mediaType = message.mediaType {
                    let fileName = message.mediaFileName ?? "media_\(Date().timeIntervalSince1970)"
                    if CrossPlatformMesh.shared.sendMedia(
                        to: androidPeer.publicKey,
                        mediaData: mediaData,
                        mediaType: mediaType.rawValue,
                        fileName: fileName
                    ) {
                        OshiLog.mesh.info("   → Media delivered to Android peer: \(androidPeer.displayName)")
                        sentSuccessfully = true
                    }
                    continue
                }

                // Regular message
                if let messageData = try? JSONEncoder().encode(message),
                   CrossPlatformMesh.shared.relayMessage(messageData, to: androidPeer.publicKey) {
                    OshiLog.mesh.info("   → Delivered to Android peer: \(androidPeer.displayName)")
                    sentSuccessfully = true
                }
            }
        }

        if !sentSuccessfully {
            OshiLog.mesh.info("❌ All send attempts failed - queueing message")
            queueMessage(message, recipientAddress: recipientAddress)
            throw MeshError.noPeersConnected
        }

        OshiLog.mesh.info("✅ Message sent successfully")
    }
    
    private func forwardRelayMessage(_ relayMsg: RelayMessage, from sourcePeer: MCPeerID) {
        guard !seenMessageIDs.contains(relayMsg.message.id) else {
            return
        }
        
        seenMessageIDs.insert(relayMsg.message.id)
        
        guard relayMsg.hopCount < relayMsg.maxHops else {
            OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.max_hops_reached", comment: "Log: Max hops reached"), relayMsg.maxHops))")
            return
        }
        
        guard !relayMsg.seenBy.contains(peerID.displayName) else {
            return
        }
        
        var updatedMessage = relayMsg
        updatedMessage.hopCount += 1
        updatedMessage.seenBy.append(peerID.displayName)
        updatedMessage.message = updatedMessage.message.meshWireSafe()   // [C2] never relay cleartext, even from an older peer

        var didRelay = false
        
        for (network, session) in sessions {
            let relayPeers = session.connectedPeers.filter { $0 != sourcePeer }
            
            guard !relayPeers.isEmpty else { continue }
            
            do {
                let relayData = try JSONEncoder().encode(updatedMessage)
                try session.send(relayData, toPeers: relayPeers, with: .reliable)
                OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.relayed_message", comment: "Log: Relayed message"), updatedMessage.hopCount, updatedMessage.maxHops, relayPeers.count))")
                didRelay = true
            } catch {
                OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.relay_failed", comment: "Log: Relay failed"), network, error.localizedDescription))")
            }
        }
        
        // ✅ NEW: Notify gamification system of successful relay
        if didRelay {
            DispatchQueue.main.async {
                NotificationCenter.default.post(
                    name: NSNotification.Name("MeshMessageRelayed"),
                    object: nil,
                    userInfo: [
                        "peerKey": sourcePeer.displayName,
                        "messageId": relayMsg.message.id,
                        "hopCount": updatedMessage.hopCount
                    ]
                )
            }
        }
        
        if seenMessageIDs.count > 10000 {
            let sortedIDs = Array(seenMessageIDs)
            let idsToKeep = Set(sortedIDs.suffix(5000))
            seenMessageIDs = idsToKeep
        }
    }
    
    private func queueMessage(_ message: SecureMessage, recipientAddress: String) {
        let queuedMessage = QueuedMessage(
            message: message,
            recipientAddress: recipientAddress,
            timestamp: Date()
        )
        messageQueue.append(queuedMessage)
        saveMessageQueue()
        OshiLog.mesh.info("\(NSLocalizedString("mesh.log.message_queued", comment: "Log: Message queued"))")
    }
    
    private func processMessageQueue() {
        guard !messageQueue.isEmpty else { return }
        
        OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.processing_queue", comment: "Log: Processing queue"), messageQueue.count))")
        
        messageQueue.removeAll { queuedMessage in
            if let recipientPeer = findPeerByAddress(queuedMessage.recipientAddress) {
                do {
                    let relayMessage = RelayMessage(
                        message: queuedMessage.message.meshWireSafe(),   // [C2]
                        hopCount: 0,
                        maxHops: maxHops,
                        seenBy: [peerID.displayName]
                    )
                    
                    let messageData = try JSONEncoder().encode(relayMessage)
                    
                    for (_, session) in sessions {
                        if session.connectedPeers.contains(recipientPeer) {
                            try session.send(messageData, toPeers: [recipientPeer], with: .reliable)
                            OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.sent_queued_message", comment: "Log: Sent queued message"), recipientPeer.displayName))")
                            return true
                        }
                    }
                } catch {
                    OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.failed_queued_message", comment: "Log: Failed queued message"), error.localizedDescription))")
                }
            }
            return false
        }
        
        saveMessageQueue()
    }
    
    private func findPeerByAddress(_ address: String) -> MCPeerID? {
        // 🔧 FIX: First try to find by public key mapping (most reliable)
        if let peer = peerPublicKeys[address] {
            // Verify peer is still connected
            for session in sessions.values {
                if session.connectedPeers.contains(peer) {
                    OshiLog.mesh.info("✅ Found peer by public key mapping: \(peer.displayName)")
                    return peer
                }
            }
        }
        
        // 🔧 FIX: Also try prefix matching for partial public keys
        let addressPrefix = String(address.prefix(16))
        for (key, peer) in peerPublicKeys {
            if key.hasPrefix(addressPrefix) {
                for session in sessions.values {
                    if session.connectedPeers.contains(peer) {
                        OshiLog.mesh.info("✅ Found peer by public key prefix: \(peer.displayName)")
                        return peer
                    }
                }
            }
        }
        
        // Fallback: Check if address is in any peer's public key (legacy support)
        for session in sessions.values {
            for peer in session.connectedPeers {
                if let peerKey = peerIDToPublicKey[peer], peerKey == address {
                    OshiLog.mesh.info("✅ Found peer by reverse key lookup: \(peer.displayName)")
                    return peer
                }
            }
        }
        
        OshiLog.mesh.info("⚠️ No peer found for address: \(address.prefix(16))...")
        return nil
    }
    
    // MARK: - Group Broadcasting
    
    private func setupGroupBroadcastListener() {
        // 🔴 WATCHDOG (0x8BADF00D): these notifications are posted from background
        // threads. `queue: .main` would park the POSTER on `-[NSOperation waitUntilFinished]`
        // until the main runloop drained — deadlocking whenever main was itself waiting on
        // that thread's queue/lock. addMainThreadObserver hops to main without blocking it.
        // See NotificationCenter+MainThread in NotificationManager.swift.
        NotificationCenter.default.addMainThreadObserver(
            forName: NSNotification.Name("BroadcastGroupUpdate")
        ) { [weak self] notification in
            guard let groupData = notification.userInfo?["groupData"] as? Data else { return }
            self?.broadcastGroupUpdate(groupData)
        }
        
        NotificationCenter.default.addMainThreadObserver(
            forName: NSNotification.Name("BroadcastGroupMessage")
        ) { [weak self] notification in
            guard let messageData = notification.userInfo?["messageData"] as? Data else { return }
            // 🔧 FIX (security R1): group traffic is addressed to the group's
            // roster, never to "whoever happens to be connected". Posters supply
            // `memberKeys`; a poster that omits it gets the old fan-out-to-all
            // behaviour, which `broadcastGroupMessage` logs as a warning.
            let memberKeys = notification.userInfo?["memberKeys"] as? [String]
            self?.broadcastGroupMessage(messageData, toMemberKeys: memberKeys)
        }
        
        NotificationCenter.default.addMainThreadObserver(
            forName: NSNotification.Name("BroadcastPublicGroupAd")
        ) { [weak self] notification in
            guard let adData = notification.userInfo?["adData"] as? Data else { return }
            self?.broadcastPublicGroupAd(adData)
        }
        
        // Listen for requests to broadcast public groups (from discovery refresh)
        NotificationCenter.default.addMainThreadObserver(
            forName: NSNotification.Name("RequestPublicGroupBroadcast")
        ) { _ in
            OshiLog.mesh.info("📡 Received request to broadcast public groups")
            // Post notification for GroupManager to handle
            NotificationCenter.default.post(
                name: NSNotification.Name("ShouldBroadcastPublicGroups"),
                object: nil
            )
        }
    }
    
    func broadcastGroupUpdate(_ groupData: Data) {
        guard !connectedPeers.isEmpty else {
            OshiLog.mesh.info("\(NSLocalizedString("mesh.log.no_peers_broadcast", comment: "Log: No peers for broadcast"))")
            return
        }
        
        for (network, session) in sessions where !session.connectedPeers.isEmpty {
            do {
                try session.send(groupData, toPeers: session.connectedPeers, with: .reliable)
                OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.broadcasted_group_update", comment: "Log: Broadcasted group update"), session.connectedPeers.count, network))")
            } catch {
                OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.broadcast_failed_network", comment: "Log: Broadcast failed on network"), network, error.localizedDescription))")
            }
        }
    }
    
    /// 🔧 FIX (security R1): send group traffic ONLY to peers we have positively
    /// identified as members of that group.
    ///
    /// MECHANISM: `peerPublicKeys` (publicKey → MCPeerID) is populated by the key
    /// exchange handshake (`:2436`), so a member's public key resolves to the
    /// MCPeerID it is connected as. We intersect that resolution with each
    /// session's `connectedPeers` and send to the intersection.
    ///
    /// This previously passed `toPeers: session.connectedPeers` — every peer the
    /// device had auto-accepted, member or not. Even with an encrypted payload
    /// that is wrong: it discloses the existence, size and timing of a group's
    /// traffic to strangers and spends their bandwidth. A non-member now receives
    /// nothing at all, so there is no packet for them to time or size.
    ///
    /// A member who has not completed the key exchange is not resolvable and is
    /// therefore skipped — a delivery gap, not a leak, and the relay paths cover
    /// them. `memberKeys == nil` means the caller did not scope the message; it
    /// keeps the old broadcast-to-all behaviour and says so in the log.
    func broadcastGroupMessage(_ messageData: Data, toMemberKeys memberKeys: [String]? = nil) {
        guard !connectedPeers.isEmpty else { return }

        var memberPeerIDs: Set<MCPeerID>?
        if let memberKeys {
            var resolved = Set<MCPeerID>()
            for key in memberKeys {
                // Same prefix-tolerant lookup `sendData` uses (`:2051`) — keys are
                // occasionally carried truncated across platforms.
                if let peer = peerPublicKeys[key]
                    ?? peerPublicKeys.first(where: { $0.key.hasPrefix(String(key.prefix(16))) })?.value {
                    resolved.insert(peer)
                }
            }
            memberPeerIDs = resolved
            if resolved.isEmpty {
                OshiLog.mesh.info("⏭️ Group broadcast skipped: none of the \(memberKeys.count) member(s) are identified peers")
                return
            }
        } else {
            OshiLog.mesh.info("⚠️ Group broadcast without a member list — falling back to all connected peers")
        }

        for (_, session) in sessions where !session.connectedPeers.isEmpty {
            let targets = memberPeerIDs.map { members in
                session.connectedPeers.filter { members.contains($0) }
            } ?? session.connectedPeers
            guard !targets.isEmpty else { continue }
            do {
                try session.send(messageData, toPeers: targets, with: .reliable)
            } catch {
                OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.group_message_failed", comment: "Log: Group message failed"), error.localizedDescription))")
            }
        }
    }
    
    func broadcastPublicGroupAd(_ adData: Data) {
        OshiLog.mesh.info("📡 broadcastPublicGroupAd called with \(adData.count) bytes")
        OshiLog.mesh.info("   Connected peers count: \(connectedPeers.count)")
        
        if connectedPeers.isEmpty {
            OshiLog.mesh.info("⚠️ MeshNetwork: No connected peers to broadcast public group ad")
            return
        }
        
        OshiLog.mesh.info("📡 MeshNetwork: Broadcasting public group ad to \(connectedPeers.count) peers")
        
        for (network, session) in sessions where !session.connectedPeers.isEmpty {
            do {
                try session.send(adData, toPeers: session.connectedPeers, with: .reliable)
                OshiLog.mesh.info("✅ MeshNetwork: Sent public group ad on \(network) to \(session.connectedPeers.count) peers")
            } catch {
                OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.ad_broadcast_failed", comment: "Log: Ad broadcast failed"), error.localizedDescription))")
            }
        }
    }
    
    // MARK: - Persistence
    
    private func saveMessageQueue() {
        // ✅ Use FileStorage instead of UserDefaults
        FileStorage.shared.save(messageQueue, forKey: "mesh.message_queue")
        OshiLog.mesh.info("💾 Saved \(messageQueue.count) queued messages to FileStorage")
    }
    
    private func loadMessageQueue() {
        // ✅ Try FileStorage first
        if let queue: [QueuedMessage] = FileStorage.shared.load(forKey: "mesh.message_queue") {
            messageQueue = queue
            OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.loaded_queue", comment: "Log: Loaded queue"), queue.count))")
        }
        // ✅ Migrate from old UserDefaults
        // NOT LOCALIZED: a UserDefaults key. A translated key reads a different
        // slot than the one the previous version wrote, so the migration would
        // silently find nothing and drop the queued messages.
        else if let data = UserDefaults.standard.data(forKey: MeshDiscoveryKeys.messageQueue),
                let queue = try? JSONDecoder().decode([QueuedMessage].self, from: data) {
            messageQueue = queue
            
            // Save to FileStorage
            FileStorage.shared.save(messageQueue, forKey: "mesh.message_queue")
            
            // Remove from UserDefaults
            UserDefaults.standard.removeObject(forKey: MeshDiscoveryKeys.messageQueue)
            
            OshiLog.mesh.info("🔄 Migrated \(queue.count) queued messages to FileStorage")
        }
    }
    
    deinit {
        OshiLog.mesh.info("\(NSLocalizedString("mesh.log.deinit", comment: "Log: MeshNetworkManager deinit"))")
        
        reconnectTimer?.invalidate()
        reconnectTimer = nil
        healthCheckTimer?.invalidate()
        healthCheckTimer = nil
        
        stopAll()
        
        browsers.removeAll()
        advertisers.removeAll()
        sessions.removeAll()
        peerServiceTypes.removeAll()
        connectionRetries.removeAll()
        
        NotificationCenter.default.removeObserver(self)
        
        OshiLog.mesh.info("\(NSLocalizedString("mesh.log.cleaned_up", comment: "Log: MeshNetworkManager cleaned up"))")
    }
}

// MARK: - MCSessionDelegate

extension MeshNetworkManager: MCSessionDelegate {
    func session(_ session: MCSession, peer peerID: MCPeerID, didChange state: MCSessionState) {
        let stateString: String
        switch state {
        case .connected: stateString = "CONNECTED ✅"
        case .connecting: stateString = "CONNECTING ⏳"
        case .notConnected: stateString = "NOT CONNECTED ❌"
        @unknown default: stateString = "UNKNOWN"
        }
        
        OshiLog.mesh.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        OshiLog.mesh.info("📡 SESSION STATE CHANGE")
        OshiLog.mesh.info("   Peer: \(peerID.displayName)")
        OshiLog.mesh.info("   State: \(stateString)")
        OshiLog.mesh.info("   Session peers: \(session.connectedPeers.map { $0.displayName })")
        OshiLog.mesh.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            switch state {
            case .connected:
                if !self.hasConnectedPeer(named: peerID.displayName) {
                    self.connectedPeers.append(peerID)
                    OshiLog.mesh.info("🎉 PEER CONNECTED: \(peerID.displayName)")
                    OshiLog.mesh.info("   Total connected peers: \(self.connectedPeers.count)")
                    self.meshAvailable = true

                    // 🔧 NEW: Send our public key to the peer for call routing
                    self.sendPublicKeyToPeer(peerID, session: session)
                }
                self.pendingInvitations.remove(peerID)
                self.invitationTimestamps.removeValue(forKey: peerID)
                self.connectionRetries.removeValue(forKey: peerID)

                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                    self?.processMessageQueue()
                }

            case .notConnected:
                self.connectedPeers.removeAll { $0.displayName == peerID.displayName }
                self.lastPeerActivity.removeValue(forKey: peerID.displayName)  // 🔧 FIX R16
                self.pendingInvitations.remove(peerID)
                self.invitationTimestamps.removeValue(forKey: peerID)
                
                // Clean up public key mapping
                if let publicKey = self.peerIDToPublicKey[peerID] {
                    self.peerPublicKeys.removeValue(forKey: publicKey)
                }
                self.peerIDToPublicKey.removeValue(forKey: peerID)
                
                self.meshAvailable = !self.connectedPeers.isEmpty
                OshiLog.mesh.info("👋 PEER DISCONNECTED: \(peerID.displayName)")
                OshiLog.mesh.info("   Remaining connected peers: \(self.connectedPeers.count)")
                
                // Auto-retry connection if peer still discovered
                if self.hasDiscoveredPeer(named: peerID.displayName) {
                    // 🔧 Check if we have service type, if not try to get from any active browser
                    var serviceType = self.peerServiceTypes[peerID]
                    if serviceType == nil {
                        // Try first active service type
                        serviceType = self.serviceTypes.first
                        if let st = serviceType {
                            self.peerServiceTypes[peerID] = st
                            OshiLog.mesh.info("   🔧 Assigned default service type: \(st)")
                        }
                    }
                    
                    if let serviceType = serviceType {
                        let currentRetries = self.connectionRetries[peerID] ?? 0
                        if currentRetries < self.maxConnectionRetries {
                            // 🔧 FIX: Even shorter delays for call stability: 1s, 1.5s, 2s, 3s, 4s
                            let delay = 1.0 + Double(currentRetries) * 0.5 + Double(currentRetries * currentRetries) * 0.25
                            OshiLog.mesh.info("🔄 Will retry connection to \(peerID.displayName) in \(String(format: "%.1f", delay))s (attempt \(currentRetries + 1)/\(self.maxConnectionRetries))")
                            
                            self.connectionRetries[peerID] = currentRetries + 1
                            
                            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
                                guard let self = self else { return }
                                if !self.hasConnectedPeer(named: peerID.displayName) && self.hasDiscoveredPeer(named: peerID.displayName) {
                                    OshiLog.mesh.info("🔄 Retrying connection to \(peerID.displayName)")
                                    self.invitePeer(peerID, to: serviceType)
                                }
                            }
                        } else {
                            OshiLog.mesh.info("⚠️ Max retries reached for \(peerID.displayName) - will retry after 60s reset")
                            // 🔧 DON'T remove retry counter - let the reset timer handle it
                        }
                    } else {
                        OshiLog.mesh.info("⚠️ No service type for \(peerID.displayName) - cannot retry")
                    }
                }
                
            case .connecting:
                OshiLog.mesh.info("⏳ CONNECTING TO: \(peerID.displayName)")
                
            @unknown default:
                break
            }
        }
    }
    
    func session(_ session: MCSession, didReceive data: Data, fromPeer peerID: MCPeerID) {
        // 🔧 CRASH FIX (2026-07-08): MCSession delivers this callback on an
        // arbitrary background queue and can fire CONCURRENTLY for multiple peers.
        // The body mutates shared collections (lastPeerActivity, seenMessageIDs,
        // messageQueue, …) that the rest of this class only ever touches on the
        // main queue — the unsynchronized concurrent access raced the underlying
        // Swift Dictionary/Set during a resize and corrupted memory (EXC_BAD_ACCESS
        // / SIGSEGV in Dictionary.subscript.setter, seen in field crash logs).
        // Serialize the whole handler onto main so it shares one queue with every
        // other mutation of this state.
        DispatchQueue.main.async { [weak self] in
            self?.handleReceivedSessionData(data, fromPeer: peerID)
        }
    }

    private func handleReceivedSessionData(_ data: Data, fromPeer peerID: MCPeerID) {
        // 🔧 FIX R16: Track peer activity on ANY data received (heartbeat ACK)
        lastPeerActivity[peerID.displayName] = Date()

        // 🔧 NEW: Handle keepalive packets first (ignore them, just acknowledge connection is alive)
        if isKeepalivePacket(data) {
            // Keepalive received - connection is healthy, no action needed
            return
        }
        
        // Check for key exchange packets first (highest priority)
        if isKeyExchangePacket(data) {
            handlePublicKeyExchange(data, from: peerID)
            return
        }
        
        // Check for call packets (priority handling)
        if isCallPacket(data) {
            handleCallPacket(data, from: peerID)
            return
        }
        
        // 🔧 FIX: Get our public key once for all recipient checks
        let myPublicKey = UserDefaults.standard.string(forKey: "publicKey") ?? ""
        
        if let relayMessage = try? JSONDecoder().decode(RelayMessage.self, from: data) {
            let message = relayMessage.message
            
            // 🔧 FIX: Early duplicate check before any processing
            guard !seenMessageIDs.contains(message.id) else {
                OshiLog.mesh.info("⏭️ Relay message already seen, skipping: \(message.id.prefix(8))...")
                return
            }
            
            OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.relay_message_received", comment: "Log: Relay message received"), relayMessage.hopCount, relayMessage.maxHops))")
            
            // 🔧 FIX: Only check recipientPublicKey - remove obsolete device name check
            let isForMe = message.recipientPublicKey == myPublicKey
            
            if isForMe {
                // Mark as seen BEFORE processing to prevent duplicates
                seenMessageIDs.insert(message.id)
                OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.message_for_us", comment: "Log: Message for us"), String(message.id.prefix(8))))")
                OshiLog.mesh.info("   ✅ Recipient matches our public key")
                handleReceivedMessage(message, from: peerID)
            } else {
                OshiLog.mesh.info("   📤 Message not for us (recipient: \(message.recipientPublicKey.prefix(16))..., me: \(myPublicKey.prefix(16))...)")
                // 🔧 FIX: Only forward if NOT for us AND under hop limit
                if relayMessage.hopCount < relayMessage.maxHops {
                    forwardRelayMessage(relayMessage, from: peerID)
                }
            }
        }
        // Check for group update by JSON structure
        else if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                json["id"] != nil && json["name"] != nil && json["members"] != nil {
            OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.group_update_from", comment: "Log: Group update from"), peerID.displayName))")
            
            DispatchQueue.main.async {
                NotificationCenter.default.post(
                    name: NSNotification.Name("GroupUpdateReceived"),
                    object: nil,
                    userInfo: ["groupData": data]
                )
            }
        }
        // Check for group message by JSON structure
        else if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                json["id"] != nil && json["groupId"] != nil && (json["encryptedContent"] != nil || json["content"] != nil) {
            // 🔧 FIX (security R1) — MIRROR BUG. This branch hands an unauthenticated,
            // unencrypted blob straight to `receiveGroupMessage`, which auto-adds an
            // unknown sender as a group member on the stated grounds that "they have
            // the group key ... so they're legitimate" (GroupMessaging `:2819`). That
            // reasoning holds for the IPFS/v2 paths, where the payload had to be
            // decrypted before it got here. It is FALSE for this one: nothing was
            // decrypted, so any nearby peer that knows a group UUID — which the old
            // cleartext broadcast handed out — could inject a message into that group
            // AND write itself into the roster.
            //
            // The send side no longer emits cleartext group bodies, so refusing them
            // on receive costs nothing and closes the injection. Gated on the SAME
            // flag so send and receive can never be live independently.
            guard GroupMeshPlaintextBroadcast.enabled else {
                OshiLog.mesh.info("⛔️ Dropped unauthenticated cleartext group message from \(peerID.displayName) — mesh group traffic is disabled")
                return
            }
            OshiLog.mesh.info("\(NSLocalizedString("mesh.log.group_message_received", comment: "Log: Group message received"))")

            DispatchQueue.main.async {
                NotificationCenter.default.post(
                    name: NSNotification.Name("NewGroupMessage"),
                    object: nil,
                    userInfo: ["groupMessage": data]
                )
            }
        }
        else if let groupAd = try? JSONDecoder().decode(PublicGroupAd.self, from: data) {
            OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.public_group_ad", comment: "Log: Public group ad"), groupAd.groupName))")
            
            DispatchQueue.main.async {
                NotificationCenter.default.post(
                    name: NSNotification.Name("PublicGroupDiscovered"),
                    object: nil,
                    userInfo: [
                        "groupAd": groupAd,
                        "groupAdData": data,
                        // Extra fields for GroupDiscoveryIndicator
                        "groupName": groupAd.groupName,
                        "groupId": groupAd.groupId,
                        "memberCount": groupAd.memberCount
                    ]
                )
            }
        }
        else if let message = try? JSONDecoder().decode(SecureMessage.self, from: data) {
            // 🔧 FIX: Early duplicate check for direct messages
            guard !seenMessageIDs.contains(message.id) else {
                OshiLog.mesh.info("⏭️ Direct message already seen, skipping: \(message.id.prefix(8))...")
                return
            }
            
            // 🔧 FIX: Verify recipient before processing direct SecureMessage
            let isForMe = message.recipientPublicKey == myPublicKey
            
            if isForMe {
                // Mark as seen BEFORE processing to prevent duplicates
                seenMessageIDs.insert(message.id)
                OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.message_from", comment: "Log: Message from"), peerID.displayName))")
                OshiLog.mesh.info("   ✅ Direct message recipient matches our public key")
                handleReceivedMessage(message, from: peerID)
            } else {
                OshiLog.mesh.info("📤 Received direct SecureMessage not for us - ignoring")
                OshiLog.mesh.info("   Recipient: \(message.recipientPublicKey.prefix(16))...")
                OshiLog.mesh.info("   Me: \(myPublicKey.prefix(16))...")
            }
        }
    }
    
    private func handleReceivedMessage(_ message: SecureMessage, from peer: MCPeerID) {
        DispatchQueue.main.async {
            NotificationCenter.default.post(
                name: .didReceiveMessage,
                object: message,
                // NOT LOCALIZED: userInfo dictionary key, read by observers.
                userInfo: [MeshDiscoveryKeys.peer: peer]
            )
        }
    }
    
    func session(_ session: MCSession, didReceive stream: InputStream, withName streamName: String, fromPeer peerID: MCPeerID) {}
    
    func session(_ session: MCSession, didStartReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, with progress: Progress) {}
    
    func session(_ session: MCSession, didFinishReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, at localURL: URL?, withError error: Error?) {}
}

// MARK: - MCNearbyServiceAdvertiserDelegate

extension MeshNetworkManager: MCNearbyServiceAdvertiserDelegate {
    func advertiser(_ advertiser: MCNearbyServiceAdvertiser, didReceiveInvitationFromPeer peerID: MCPeerID, withContext context: Data?, invitationHandler: @escaping (Bool, MCSession?) -> Void) {
        OshiLog.mesh.info("📩 Invitation received from \(peerID.displayName)")
        
        if autoConnectEnabled {
            for (serviceType, adv) in advertisers where adv === advertiser {
                OshiLog.mesh.info("   📋 Service type: \(serviceType)")
                
                // ✅ CRITICAL FIX: Always use the existing session, never create a new one
                // Creating a new session causes connection failures because inviter and invitee
                // end up with different sessions
                guard let existingSession = sessions[serviceType] else {
                    OshiLog.mesh.info("❌ No session exists for \(serviceType), rejecting invitation")
                    invitationHandler(false, nil)
                    return
                }
                
                // Check if already connected
                if existingSession.connectedPeers.contains(peerID) {
                    OshiLog.mesh.info("⏭️ \(peerID.displayName) already connected in this session")
                    invitationHandler(true, existingSession)
                    return
                }
                
                // Check session capacity
                if existingSession.connectedPeers.count >= 8 {
                    OshiLog.mesh.info("⚠️ Session at capacity (8 peers), rejecting")
                    invitationHandler(false, nil)
                    return
                }
                
                // Store peer service type mapping
                peerServiceTypes[peerID] = serviceType
                
                OshiLog.mesh.info("✅ Accepting invitation from \(peerID.displayName) to existing session")
                OshiLog.mesh.info("   Current peers in session: \(existingSession.connectedPeers.count)")
                
                invitationHandler(true, existingSession)
                return
            }
            
            OshiLog.mesh.info("⚠️ No matching advertiser found for invitation")
        } else {
            OshiLog.mesh.info("⚠️ Auto-connect disabled, rejecting invitation")
        }
        
        invitationHandler(false, nil)
    }
    
    func advertiser(_ advertiser: MCNearbyServiceAdvertiser, didNotStartAdvertisingPeer error: Error) {
        let nsError = error as NSError
        OshiLog.mesh.info("❌ Advertiser failed to start: \(error.localizedDescription)")
        OshiLog.mesh.info("   Error code: \(nsError.code)")
        OshiLog.mesh.info("   Error domain: \(nsError.domain)")
        if nsError.code != -72008 {
            OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.advertiser_error", comment: "Log: Advertiser error"), error.localizedDescription))")
        }
    }
}

// MARK: - MCNearbyServiceBrowserDelegate

extension MeshNetworkManager: MCNearbyServiceBrowserDelegate {
    func browser(_ browser: MCNearbyServiceBrowser, foundPeer peerID: MCPeerID, withDiscoveryInfo info: [String : String]?) {
        OshiLog.mesh.info("🎉 PEER DISCOVERED: \(peerID.displayName)")
        OshiLog.mesh.info("   📋 Discovery info: \(info ?? [:])")
        OshiLog.mesh.info("   📱 My peer ID: \(self.peerID.displayName)")
        
        // 🔧 CRITICAL: Filter out self-discovery
        // MCPeerID equality is based on object identity, so we compare display names
        if peerID.displayName == self.peerID.displayName {
            OshiLog.mesh.info("⚠️ Ignoring self-discovery for: \(peerID.displayName)")
            return
        }
        
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            // Double-check we're not connecting to ourselves
            if peerID.displayName == self.peerID.displayName {
                OshiLog.mesh.info("⚠️ Ignoring self-discovery (async check): \(peerID.displayName)")
                return
            }
            
            // Find which service type this browser belongs to
            var foundServiceType: String?
            for (serviceType, brw) in self.browsers where brw === browser {
                foundServiceType = serviceType
                break
            }
            
            guard let serviceType = foundServiceType else {
                OshiLog.mesh.info("\(NSLocalizedString("mesh.log.cannot_determine_service", comment: "Log: Cannot determine service type"))")
                return
            }
            
            OshiLog.mesh.info("   🌐 Found on service type: \(serviceType)")
            
            // 🔧 CRITICAL FIX: ALWAYS store service type, even on rediscovery!
            self.peerServiceTypes[peerID] = serviceType
            OshiLog.mesh.info("🔍 Stored service type '\(serviceType)' for \(peerID.displayName)")
            
            // Add to discovered list if not already there (deduplicate by displayName)
            if !self.hasDiscoveredPeer(named: peerID.displayName) {
                self.discoveredPeers.append(peerID)
                OshiLog.mesh.info("✅ Added \(peerID.displayName) to discovered peers (total: \(self.discoveredPeers.count))")
            } else {
                // Same device rediscovered on another service type — update service type but don't add again
                OshiLog.mesh.info("⏭️ Already discovered \(peerID.displayName) — updating service type to '\(serviceType)'")
            }

            let networkType = info?[MeshDiscoveryKeys.network] ?? serviceType
            OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.discovered_peer_on", comment: "Log: Discovered peer on"), peerID.displayName, networkType))")

            // Auto-connect if enabled and not already connected (deduplicate by displayName)
            if self.autoConnectEnabled && !self.hasConnectedPeer(named: peerID.displayName) {
                // 🔧 FIX: Only schedule ONE auto-invite per peer, even if discovered on multiple service types
                let alreadyPending = self.pendingAutoInvites.contains { $0.displayName == peerID.displayName }
                guard !alreadyPending else {
                    OshiLog.mesh.info("⏭️ Auto-invite already scheduled for \(peerID.displayName)")
                    return
                }

                self.pendingAutoInvites.insert(peerID)

                // Wait 2 seconds before inviting
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { [weak self] in
                    guard let self = self else { return }

                    // Remove from pending auto-invites
                    self.pendingAutoInvites.remove(peerID)

                    // Triple-check: still discovered, still not connected, still have service type
                    if self.hasDiscoveredPeer(named: peerID.displayName) &&
                       !self.hasConnectedPeer(named: peerID.displayName) &&
                       self.peerServiceTypes[peerID] != nil {
                        OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.inviting_after_delay", comment: "Log: Inviting after delay"), peerID.displayName))")
                        self.invitePeer(peerID, to: serviceType)
                    } else {
                        OshiLog.mesh.info("⏭️ Skipping auto-invite for \(peerID.displayName) (already connected or lost)")
                    }
                }
            }
        }
    }
    
    func browser(_ browser: MCNearbyServiceBrowser, lostPeer peerID: MCPeerID) {
        DispatchQueue.main.async { [weak self] in
            // Remove by displayName to match deduplication logic
            self?.discoveredPeers.removeAll { $0.displayName == peerID.displayName }
            self?.pendingInvitations.remove(peerID)
            OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.lost_peer", comment: "Log: Lost peer"), peerID.displayName))")
        }
    }
    
    func browser(_ browser: MCNearbyServiceBrowser, didNotStartBrowsingForPeers error: Error) {
        let nsError = error as NSError
        OshiLog.mesh.info("❌ Browser failed to start: \(error.localizedDescription)")
        OshiLog.mesh.info("   Error code: \(nsError.code)")
        OshiLog.mesh.info("   Error domain: \(nsError.domain)")
        if nsError.code != -72008 {
            OshiLog.mesh.info("\(String(format: NSLocalizedString("mesh.log.browser_error", comment: "Log: Browser error"), error.localizedDescription))")
        }
    }
}

// MARK: - Models

struct QueuedMessage: Codable {
    let message: SecureMessage
    let recipientAddress: String
    let timestamp: Date
}

extension SecureMessage {
    /// A copy safe to transmit over the mesh wire. Strips the local-only
    /// plaintext/media sidecar fields (plaintextContent, mediaAttachment,
    /// originalMediaData, editedContent) that are NOT needed for delivery — the
    /// recipient reconstructs them by ratchet-decrypting `encryptedContent`,
    /// exactly as the online/IPFS path already does (which transmits only the
    /// ciphertext). Without this, message text and raw media travelled in
    /// cleartext beside the ciphertext on every hop, readable by any relay node
    /// or AWDL/Wi-Fi sniffer. [Audit 2026-07 C2]
    func meshWireSafe() -> SecureMessage {
        var m = self
        m.plaintextContent = nil
        m.mediaAttachment = nil
        m.originalMediaData = nil
        m.editedContent = nil
        // The sender's idea of how IT delivered the message is meaningless to
        // the receiver, who knows perfectly well which radio the bytes came out
        // of. Worse, it is the one field carrying an enum raw value across the
        // wire, so every new transport case would be a decode throw — and thus
        // a dropped message — on any peer running an older build or Android.
        // Send nothing; each side stamps the transport it actually observed.
        m.deliveryMethod = nil
        return m
    }
}

struct RelayMessage: Codable {
    var message: SecureMessage
    var hopCount: Int
    let maxHops: Int
    var seenBy: [String]
}

/// Relay structure for call signals (can hop through mesh peers)
struct RelayCallSignal: Codable {
    let senderPublicKey: String      // Original caller's public key
    let recipientPublicKey: String   // Target recipient's public key
    let encryptedData: Data          // Encrypted call signal data
    var hopCount: Int
    let maxHops: Int
    var seenBy: [String]
    let timestamp: Date
    let signalId: String             // Unique ID to prevent duplicates
}

enum MeshError: LocalizedError {
    case noPeersConnected
    case peerNotConnected
    
    var errorDescription: String? {
        switch self {
        case .noPeersConnected:
            return NSLocalizedString("mesh.error.no_peers_connected", comment: "Error: No peers connected")
        case .peerNotConnected:
            return NSLocalizedString("mesh.error.peer_not_connected", comment: "Error: Peer not connected")
        }
    }
}

// MARK: - Voice Call Extension
extension MeshNetworkManager {
    
    // Track seen call signal IDs to prevent duplicates
    private static var seenCallSignalIDs: Set<String> = []
    
    /// Send call signaling data (call request, accept, decline, end)
    /// Tries direct connection first, then relay through other peers
    func sendCallSignal(to recipientAddress: String, data: Data, useRelay: Bool = true) {
        // Get my public key for relay
        guard let myPublicKey = UserDefaults.standard.string(forKey: "publicKey") else {
            OshiLog.mesh.info("❌ VoiceCall: No public key for sending")
            return
        }
        
        // 🔧 FIXED: Use public key mapping instead of displayName matching
        // First try exact public key match
        var targetPeer: MCPeerID? = peerPublicKeys[recipientAddress]
        
        // Fallback: try prefix matching on public keys
        if targetPeer == nil {
            let prefix = String(recipientAddress.prefix(16))
            for (key, peerID) in peerPublicKeys {
                if key.hasPrefix(prefix) {
                    targetPeer = peerID
                    break
                }
            }
        }
        
        // Last resort: old method (displayName matching) - unlikely to work
        if targetPeer == nil {
            targetPeer = connectedPeers.first(where: { $0.displayName.contains(recipientAddress.prefix(8)) })
        }
        
        // Try DIRECT connection first
        if let peer = targetPeer,
           let serviceType = peerServiceTypes[peer],
           let session = sessions[serviceType] {
            
            // Create call signal packet with type identifier
            var signalData = Data([0xCA, 0x11])  // "CALL" magic bytes
            signalData.append(0x01)  // Signal type (direct)
            signalData.append(contentsOf: data)
            
            do {
                try session.send(signalData, toPeers: [peer], with: .reliable)
                OshiLog.mesh.info("📞 MeshNetwork: Sent DIRECT call signal to \(peer.displayName) (key: \(recipientAddress.prefix(16))...)")
                return  // Success - no need for relay
            } catch {
                OshiLog.mesh.info("⚠️ MeshNetwork: Direct send failed, trying relay: \(error)")
            }
        }
        
        // RELAY through other peers if direct failed and relay is enabled
        if useRelay && !connectedPeers.isEmpty {
            OshiLog.mesh.info("📡 MeshNetwork: No direct connection, using RELAY for call signal")
            relayCallSignal(
                senderPublicKey: myPublicKey,
                recipientPublicKey: recipientAddress,
                encryptedData: data,
                excludePeer: nil
            )
        } else {
            OshiLog.mesh.info("❌ VoiceCall: Peer not found for call signal (key: \(recipientAddress.prefix(16))...)")
            OshiLog.mesh.info("   📋 Known peer keys: \(peerPublicKeys.keys.map { String($0.prefix(16)) })")
            OshiLog.mesh.info("   📋 Connected peers: \(connectedPeers.map { $0.displayName })")
        }
    }
    
    // MARK: - Generic Data Send (for MeshDirectCallManager)
    
    /// Send raw data to a peer by public key - used for mesh direct calls
    func sendData(_ data: Data, to peerPublicKey: String) {
        // Find peer by public key
        guard let targetPeer = peerPublicKeys[peerPublicKey] ??
              peerPublicKeys.first(where: { $0.key.hasPrefix(String(peerPublicKey.prefix(16))) })?.value else {
            OshiLog.mesh.info("❌ MeshNetwork: Cannot send data - peer not found: \(peerPublicKey.prefix(16))...")
            return
        }
        
        guard let serviceType = peerServiceTypes[targetPeer],
              let session = sessions[serviceType] else {
            OshiLog.mesh.info("❌ MeshNetwork: No session for peer")
            return
        }
        
        // Create mesh data packet
        var packetData = Data([0xDA, 0x7A])  // "DATA" magic bytes
        packetData.append(contentsOf: data)
        
        do {
            try session.send(packetData, toPeers: [targetPeer], with: .reliable)
            OshiLog.mesh.info("📤 MeshNetwork: Sent \(data.count) bytes to \(targetPeer.displayName)")
        } catch {
            OshiLog.mesh.info("❌ MeshNetwork: Send data failed: \(error)")
        }
    }
    
    /// Check if peer is directly connected (1 hop)
    func isPeerDirectlyConnected(_ peerPublicKey: String) -> Bool {
        return peerPublicKeys[peerPublicKey] != nil ||
               peerPublicKeys.keys.contains(where: { $0.hasPrefix(String(peerPublicKey.prefix(16))) })
    }

    /// 🔧 NEW: Check if we have any relay peers available (for multi-hop calls)
    func hasRelayPeersAvailable() -> Bool {
        return !connectedPeers.isEmpty
    }

    /// 🔧 NEW: Get count of connected relay peers
    func getRelayPeerCount() -> Int {
        return connectedPeers.count
    }

    /// 🔧 NEW: Check if we can potentially reach a peer (directly or via relay)
    func canPotentiallyReachPeer(_ peerPublicKey: String) -> (reachable: Bool, method: String) {
        // First check direct connection
        if isPeerDirectlyConnected(peerPublicKey) {
            return (true, "direct")
        }

        // Check if we have relay peers that might be able to reach the target
        if hasRelayPeersAvailable() {
            // We have relay peers - the target might be reachable via them
            // Note: We can't know for certain without trying, but we have a path to try
            return (true, "relay")
        }

        // No direct connection and no relay peers
        return (false, "none")
    }

    /// Relay a call signal through connected peers
    func relayCallSignal(senderPublicKey: String, recipientPublicKey: String, encryptedData: Data, excludePeer: MCPeerID?) {
        let signalId = UUID().uuidString
        
        let relaySignal = RelayCallSignal(
            senderPublicKey: senderPublicKey,
            recipientPublicKey: recipientPublicKey,
            encryptedData: encryptedData,
            hopCount: 0,
            maxHops: 3,  // Limit to 3 hops for latency
            seenBy: [peerID.displayName],
            timestamp: Date(),
            signalId: signalId
        )
        
        // Mark as seen
        MeshNetworkManager.seenCallSignalIDs.insert(signalId)
        
        // Send to all connected peers except the excluded one
        guard let relayData = try? JSONEncoder().encode(relaySignal) else {
            OshiLog.mesh.info("❌ MeshNetwork: Failed to encode relay call signal")
            return
        }
        
        // Add relay call signal magic bytes
        var packetData = Data([0xCA, 0x11])  // "CALL" magic bytes
        packetData.append(0x03)  // Relay signal type
        packetData.append(relayData)
        
        var sentCount = 0
        for (serviceType, session) in sessions {
            let relayPeers = session.connectedPeers.filter { $0 != excludePeer }
            guard !relayPeers.isEmpty else { continue }
            
            do {
                try session.send(packetData, toPeers: relayPeers, with: .reliable)
                sentCount += relayPeers.count
            } catch {
                OshiLog.mesh.info("❌ MeshNetwork: Failed to relay call signal on \(serviceType): \(error)")
            }
        }
        
        if sentCount > 0 {
            OshiLog.mesh.info("📡 MeshNetwork: Relayed call signal to \(sentCount) peer(s)")
        }
    }
    
    /// Forward a received relay call signal
    private func forwardRelayCallSignal(_ relaySignal: RelayCallSignal, from sourcePeer: MCPeerID) {
        // Check if already seen
        guard !MeshNetworkManager.seenCallSignalIDs.contains(relaySignal.signalId) else {
            return
        }
        MeshNetworkManager.seenCallSignalIDs.insert(relaySignal.signalId)
        
        // Check hop count
        guard relaySignal.hopCount < relaySignal.maxHops else {
            OshiLog.mesh.info("📡 MeshNetwork: Relay call signal max hops reached")
            return
        }
        
        // Check if we've seen this
        guard !relaySignal.seenBy.contains(peerID.displayName) else {
            return
        }
        
        // Update relay signal
        var updatedSignal = relaySignal
        updatedSignal.hopCount += 1
        updatedSignal.seenBy.append(peerID.displayName)
        
        guard let relayData = try? JSONEncoder().encode(updatedSignal) else { return }
        
        var packetData = Data([0xCA, 0x11])
        packetData.append(0x03)
        packetData.append(relayData)
        
        // Forward to other peers
        for (_, session) in sessions {
            let forwardPeers = session.connectedPeers.filter {
                $0 != sourcePeer && !updatedSignal.seenBy.contains($0.displayName)
            }
            guard !forwardPeers.isEmpty else { continue }
            
            do {
                try session.send(packetData, toPeers: forwardPeers, with: .reliable)
                OshiLog.mesh.info("📡 MeshNetwork: Forwarded call signal (hop \(updatedSignal.hopCount)/\(updatedSignal.maxHops)) to \(forwardPeers.count) peer(s)")
            } catch {
                OshiLog.mesh.info("❌ MeshNetwork: Forward failed: \(error)")
            }
        }
    }
    
    /// Send audio data during call (lower latency, unreliable delivery OK)
    /// Audio does NOT use relay - too much latency
    /// ✅ IMPROVED: Uses unreliable mode for lower latency, with jitter buffer on receiver
    func sendCallAudio(to recipientAddress: String, data: Data) {
        // 🔧 FIXED: Use public key mapping instead of displayName matching
        var targetPeer: MCPeerID? = peerPublicKeys[recipientAddress]

        // Fallback: try prefix matching on public keys
        if targetPeer == nil {
            let prefix = String(recipientAddress.prefix(16))
            for (key, peerID) in peerPublicKeys {
                if key.hasPrefix(prefix) {
                    targetPeer = peerID
                    break
                }
            }
        }

        // Fallback 2: Try normalized key matching (base64url vs base64)
        if targetPeer == nil {
            let normalizedRecipient = recipientAddress
                .replacingOccurrences(of: "-", with: "+")
                .replacingOccurrences(of: "_", with: "/")

            for (key, peerID) in peerPublicKeys {
                let normalizedKey = key
                    .replacingOccurrences(of: "-", with: "+")
                    .replacingOccurrences(of: "_", with: "/")
                if normalizedKey == normalizedRecipient {
                    targetPeer = peerID
                    break
                }
            }
        }

        guard let peer = targetPeer,
              let serviceType = peerServiceTypes[peer],
              let session = sessions[serviceType] else {
            // Log only once per second to avoid spam
            if Date().timeIntervalSince(lastAudioSendLog) > 1.0 {
                OshiLog.mesh.info("⚠️ MeshNetwork: sendCallAudio failed - no peer/session")
                OshiLog.mesh.info("   Recipient: \(recipientAddress.prefix(16))...")
                OshiLog.mesh.info("   Known peers: \(peerPublicKeys.keys.map { String($0.prefix(16)) })")
                lastAudioSendLog = Date()
            }
            return
        }

        // Verify peer is still connected
        guard session.connectedPeers.contains(peer) else {
            if Date().timeIntervalSince(lastAudioSendLog) > 1.0 {
                OshiLog.mesh.info("⚠️ MeshNetwork: Audio peer disconnected, attempting reconnect...")
                lastAudioSendLog = Date()
            }
            return
        }

        // Create audio packet with type identifier
        var audioData = Data([0xCA, 0x11])  // "CALL" magic bytes
        audioData.append(0x02)  // Audio type
        audioData.append(contentsOf: data)

        do {
            // 🔧 CHANGED: Use unreliable mode for lower latency
            // The receiver has a jitter buffer to handle packet loss
            try session.send(audioData, toPeers: [peer], with: .unreliable)
            meshAudioPacketsSent += 1

            // Log every 100 packets for monitoring
            if meshAudioPacketsSent % 100 == 0 {
                OshiLog.mesh.info("📡 MeshNetwork: Sent \(meshAudioPacketsSent) audio packets (\(data.count) bytes each)")
            }
        } catch {
            if Date().timeIntervalSince(lastAudioSendLog) > 1.0 {
                OshiLog.mesh.info("⚠️ MeshNetwork: sendCallAudio error: \(error.localizedDescription)")
                lastAudioSendLog = Date()
            }
        }
    }

    /// Reset audio packet counters (call when call ends)
    func resetAudioStats() {
        meshAudioPacketsSent = 0
        meshAudioPacketsReceived = 0
    }
    
    /// Check if a call packet was received
    func isCallPacket(_ data: Data) -> Bool {
        return data.count >= 3 && data[0] == 0xCA && data[1] == 0x11
    }
    
    /// Extract call data type (0x01 = direct signal, 0x02 = audio, 0x03 = relay signal)
    func callPacketType(_ data: Data) -> UInt8? {
        guard isCallPacket(data) else { return nil }
        return data[2]
    }
    
    /// Extract call payload
    func callPacketPayload(_ data: Data) -> Data? {
        guard isCallPacket(data), data.count > 3 else { return nil }
        // 🛟 CRASH FIX: `suffix(from: 3)` returns a Data slice whose
        // `startIndex == 3`. Consumers downstream (VoiceCallManager.receiveAudio,
        // call-signal observers, relay handlers) use absolute subscripts like
        // `payload[0]` and `subdata(in: 1..<9)` — which are valid only on a
        // 0-based Data and SIGTRAP on a slice. Rebase here so every consumer
        // receives a fresh 0-indexed buffer without each having to know the
        // gotcha. Confirmed root cause of the macOS-26 crash report
        // (Foundation Data._Representation.subscript.getter OOB on main thread).
        return Data(data.suffix(from: 3))
    }
    
    /// Handle received call packet
    private func handleCallPacket(_ data: Data, from peerID: MCPeerID) {
        guard let packetType = callPacketType(data),
              let payload = callPacketPayload(data) else { return }
        
        // Get peer's public key from our mapping (or use display name as fallback)
        let peerPublicKey = peerIDToPublicKey[peerID] ?? peerID.displayName
        // 🔧 FIX: Use shortened public key as peerName, NOT the device name
        // The VoiceCallManager will look up the contact alias from UserDefaults
        let peerName = String(peerPublicKey.prefix(8)) + "..."
        
        if packetType == 0x01 {
            // DIRECT signal packet
            DispatchQueue.main.async {
                NotificationCenter.default.post(
                    name: .didReceiveCallSignal,
                    object: nil,
                    userInfo: [
                        "data": payload,
                        "peerName": peerName,
                        "peerPublicKey": peerPublicKey,
                        "viaMesh": true  // Flag that this came via mesh, not Tor
                    ]
                )
            }
        } else if packetType == 0x02 {
            // Audio packet - log first packet to confirm audio is working
            if meshAudioPacketsReceived == 0 {
                OshiLog.mesh.info("🎵 MeshNetwork: First AUDIO packet received! size: \(payload.count) bytes from \(peerID.displayName)")
            }
            meshAudioPacketsReceived += 1
            
            DispatchQueue.main.async {
                NotificationCenter.default.post(
                    name: .didReceiveCallAudio,
                    object: nil,
                    userInfo: ["data": payload]
                )
            }
        } else if packetType == 0x03 {
            // RELAY signal packet
            handleRelayCallSignal(payload, from: peerID)
        }
    }
    
    /// Handle a relayed call signal
    private func handleRelayCallSignal(_ payload: Data, from sourcePeer: MCPeerID) {
        guard let relaySignal = try? JSONDecoder().decode(RelayCallSignal.self, from: payload) else {
            OshiLog.mesh.info("❌ MeshNetwork: Failed to decode relay call signal")
            return
        }
        
        // Check if we've already seen this signal
        guard !MeshNetworkManager.seenCallSignalIDs.contains(relaySignal.signalId) else {
            return
        }
        MeshNetworkManager.seenCallSignalIDs.insert(relaySignal.signalId)
        
        // Get my public key
        guard let myPublicKey = UserDefaults.standard.string(forKey: "publicKey") else { return }
        
        // Check if this signal is for us
        if relaySignal.recipientPublicKey == myPublicKey {
            OshiLog.mesh.info("📡 MeshNetwork: Received RELAYED call signal for me! (from: \(relaySignal.senderPublicKey.prefix(16))..., hops: \(relaySignal.hopCount))")
            
            // Notify VoiceCallManager
            DispatchQueue.main.async {
                NotificationCenter.default.post(
                    name: .didReceiveCallSignal,
                    object: nil,
                    userInfo: [
                        "data": relaySignal.encryptedData,
                        "peerName": String(relaySignal.senderPublicKey.prefix(8)) + "...",
                        "peerPublicKey": relaySignal.senderPublicKey,
                        "viaMesh": true,
                        "viaRelay": true  // Additional flag to indicate this came via relay
                    ]
                )
            }
        } else {
            // Not for us - forward it
            OshiLog.mesh.info("📡 MeshNetwork: Relayed call signal not for me, forwarding...")
            forwardRelayCallSignal(relaySignal, from: sourcePeer)
        }
    }
    
    // MARK: - Public Key Exchange for Call Routing
    
    /// Send our public key to a connected peer
    private func sendPublicKeyToPeer(_ peerID: MCPeerID, session: MCSession) {
        guard let myPublicKey = UserDefaults.standard.string(forKey: "publicKey") else {
            OshiLog.mesh.info("⚠️ MeshNetwork: No public key to send")
            return
        }
        
        // Create key exchange packet: [0xEE, 0xEE] + publicKey bytes
        var keyData = Data([0xEE, 0xEE])  // "KEY EXCHANGE" magic bytes
        if let keyBytes = myPublicKey.data(using: .utf8) {
            keyData.append(keyBytes)
        }
        
        do {
            try session.send(keyData, toPeers: [peerID], with: .reliable)
            OshiLog.mesh.info("🔑 MeshNetwork: Sent public key to \(peerID.displayName)")
        } catch {
            OshiLog.mesh.info("❌ MeshNetwork: Failed to send public key: \(error)")
        }
    }
    
    /// Handle received public key from peer
    private func handlePublicKeyExchange(_ data: Data, from peerID: MCPeerID) {
        // Extract public key (skip magic bytes)
        let keyData = data.suffix(from: 2)
        guard let publicKey = String(data: keyData, encoding: .utf8), !publicKey.isEmpty else {
            OshiLog.mesh.info("❌ MeshNetwork: Invalid public key data from \(peerID.displayName)")
            return
        }
        
        // Check if we already have this peer's key (to avoid infinite loop)
        let alreadyHaveKey = peerIDToPublicKey[peerID] != nil
        
        // Store bidirectional mapping
        DispatchQueue.main.async { [weak self] in
            self?.peerPublicKeys[publicKey] = peerID
            self?.peerIDToPublicKey[peerID] = publicKey
            OshiLog.mesh.info("🔑 MeshNetwork: Registered public key for \(peerID.displayName): \(publicKey.prefix(16))...")
            
            // 🔧 FIX: If we didn't have their key, send our key back (bidirectional exchange)
            if !alreadyHaveKey {
                // Find the session for this peer and send our key back
                for (_, session) in self?.sessions ?? [:] {
                    if session.connectedPeers.contains(peerID) {
                        self?.sendPublicKeyToPeer(peerID, session: session)
                        OshiLog.mesh.info("🔑 MeshNetwork: Sent our key back to \(peerID.displayName) (bidirectional exchange)")
                        break
                    }
                }
            }
        }
    }
    
    /// Check if data is a key exchange packet
    private func isKeyExchangePacket(_ data: Data) -> Bool {
        return data.count >= 3 && data[0] == 0xEE && data[1] == 0xEE
    }
    
    /// Get peer ID for a public key (for call routing)
    func getPeerForPublicKey(_ publicKey: String) -> MCPeerID? {
        return peerPublicKeys[publicKey]
    }
    
    /// Get public key for a peer ID
    func getPublicKeyForPeer(_ peerID: MCPeerID) -> String? {
        return peerIDToPublicKey[peerID]
    }
}

/// Machine-facing identifiers that were previously routed through
/// `NSLocalizedString`. They are dictionary keys and storage keys, not copy:
/// `network` travels between devices inside `MCNearbyServiceAdvertiser`'s
/// `discoveryInfo`, and `messageQueue` names a `UserDefaults` slot written by
/// earlier builds. Their string values are unchanged from what shipped.
enum MeshDiscoveryKeys {
    /// Read by the peer device out of `discoveryInfo` — cross-device wire format.
    static let network = "mesh.key.network"
    /// `UserDefaults` slot for the pre-FileStorage message queue migration.
    static let messageQueue = "mesh.key.message_queue"
    /// `userInfo` key on `.didReceiveMessage`.
    static let peer = "mesh.key.peer"
}

extension Notification.Name {
    // NOT LOCALIZED, and must never become so. These are wire/runtime
    // identifiers, not copy. They were wrapped in NSLocalizedString, which
    // "worked" only because the keys happen to be absent from every .strings
    // file, so NSLocalizedString returned the key unchanged. The day someone
    // sweeps the .strings files and helpfully adds a translation, every
    // observer registered under the English name stops hearing the posts made
    // under the translated one. The values below are byte-identical to what
    // shipped, so nothing changes today — the landmine is just defused.
    static let didReceiveMessage = Notification.Name("mesh.notification.did_receive_message")
    static let meshNetworkLost = Notification.Name("mesh.notification.mesh_network_lost")
    static let meshNetworkRestored = Notification.Name("mesh.notification.mesh_network_restored")
    // didReceiveCallSignal and didReceiveCallAudio are defined in VoiceCallManager.swift
    static let publicGroupDiscovered = Notification.Name("PublicGroupDiscovered")
}
