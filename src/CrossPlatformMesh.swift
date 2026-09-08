//
//  CrossPlatformMesh.swift
//  OSHI - Cross-Platform Mesh Network (iOS ↔ Android)
//
//  Uses BLE + Bonjour + TCP to enable direct mesh communication
//  between iOS and Android devices WITHOUT internet.
//
//  Protocol:
//  1. BLE advertising for presence detection
//  2. Bonjour/mDNS for service discovery
//  3. TCP sockets for data transfer
//  4. Same message format as internal mesh
//

import Foundation
import CoreBluetooth
import Network

// MARK: - Cross-Platform Mesh Manager

class CrossPlatformMesh: NSObject, ObservableObject {
    static let shared = CrossPlatformMesh()

    // Published state
    @Published var isRunning = false
    @Published var crossPlatformPeers: [CrossPlatformPeer] = []
    @Published var connectedPeers: [CrossPlatformPeer] = []
    @Published var diagnostics: String = ""

    // Diagnostic counters
    private var totalBLEScanResults = 0
    private var oshiBLEMatches = 0

    // BLE (created lazily in start() to avoid permission prompt at launch)
    private var centralManager: CBCentralManager?
    private var peripheralManager: CBPeripheralManager?
    private var discoveredPeripherals: [UUID: CBPeripheral] = [:]

    // Bonjour
    private var netService: NetService?
    private var netServiceBrowser: NetServiceBrowser?
    private var resolvedServices: [NetService] = []
    private var bonjourServiceToKey: [String: String] = [:]  // service.name → publicKey (for removal)

    // TCP Server
    private var tcpListener: NWListener?
    private var tcpConnections: [String: NWConnection] = [:]  // publicKey -> connection

    // Identity
    private var myPublicKey: String = ""
    private var myDisplayName: String = ""
    private var myPort: UInt16 = 0

    // Constants - MUST match Android
    // CRITICAL: Use 16-bit UUID form for BLE advertising!
    // iOS 128-bit UUIDs in advertising packets are often INVISIBLE to Android scanners
    // (pushed to iOS-only "overflow area" or not parsed by Android's ScanRecord).
    // 16-bit UUIDs (4 bytes in ad packet vs 18 for 128-bit) work reliably on ALL platforms.
    // CoreBluetooth treats CBUUID("0541") == CBUUID("00000541-0000-1000-8000-00805F9B34FB")
    static let SERVICE_UUID = CBUUID(string: "0541")
    static let CHAR_UUID = CBUUID(string: "0542")
    static let BONJOUR_TYPE = "_oshi-mesh._tcp."  // Trailing dot required for Bonjour
    static let BONJOUR_DOMAIN = "local."

    // Periodic scan restart
    private var scanRestartTimer: DispatchSourceTimer?
    private var diagnosticsTimer: DispatchSourceTimer?
    private var bleRetryTimer: DispatchSourceTimer?
    private var routeCleanupTimer: DispatchSourceTimer?
    private var identityAnnounceTimer: DispatchSourceTimer?

    // GATT service add → advertising sequencing
    private var pendingAdvertising = false

    // Relay settings
    private let maxHops = 50
    private var seenMessageIds = Set<String>()
    private var routingTable: [String: RouteInfo] = [:]  // publicKey -> route
    // FREEZE FIX: throttle the "No direct/routed path" log per recipient.
    private var lastNoRouteLogAt: [String: Date] = [:]

    // Track connections we've already sent identity to (prevent infinite exchange loop)
    private var sentIdentityTo = Set<String>()  // publicKey set

    // 🔧 FIX: Serial queue to protect mutable state from concurrent access
    // NWConnection/BLE callbacks run on background queues — all shared state mutations must be serialized
    private let stateQueue = DispatchQueue(label: "com.oshi.mesh.state", qos: .userInitiated)

    // Callbacks
    private var messageCallbacks: [(CrossPlatformMessage) -> Void] = []

    // MARK: - Centralized Peer Dedup

    /// Add a discovered peer, deduplicating by publicKey AND trimmed displayName.
    /// Must be called on main thread.
    private func addDiscoveredPeerOnMain(_ peer: CrossPlatformPeer) {
        let trimmedName = peer.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        let isDuplicate = crossPlatformPeers.contains {
            (!peer.publicKey.isEmpty && $0.publicKey == peer.publicKey) ||
            $0.displayName.trimmingCharacters(in: .whitespacesAndNewlines) == trimmedName
        }
        if !isDuplicate {
            var p = peer
            p.displayName = trimmedName
            crossPlatformPeers.append(p)
        } else if let idx = crossPlatformPeers.firstIndex(where: {
            (!peer.publicKey.isEmpty && $0.publicKey == peer.publicKey) ||
            $0.displayName.trimmingCharacters(in: .whitespacesAndNewlines) == trimmedName
        }) {
            // Update existing with fresh data
            if let ip = peer.ipAddress { crossPlatformPeers[idx].ipAddress = ip }
            if let port = peer.port { crossPlatformPeers[idx].port = port }
            if !peer.publicKey.isEmpty { crossPlatformPeers[idx].publicKey = peer.publicKey }
            crossPlatformPeers[idx].displayName = trimmedName
        }
    }

    /// Add a connected peer, deduplicating by publicKey AND trimmed displayName.
    /// Must be called on main thread.
    private func addConnectedPeerOnMain(_ peer: CrossPlatformPeer) {
        let trimmedName = peer.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        let isDuplicate = connectedPeers.contains {
            (!peer.publicKey.isEmpty && $0.publicKey == peer.publicKey) ||
            $0.displayName.trimmingCharacters(in: .whitespacesAndNewlines) == trimmedName
        }
        if !isDuplicate {
            var p = peer
            p.displayName = trimmedName
            connectedPeers.append(p)
            // Notify GroupMessaging so it can push groups this peer is a member of
            if !p.publicKey.isEmpty {
                NotificationCenter.default.post(
                    name: NSNotification.Name("CrossPlatformPeerConnected"),
                    object: nil,
                    userInfo: ["peerPublicKey": p.publicKey]
                )
            }
        }
    }

    // MARK: - Initialization
    // BLE managers are created lazily in start() to avoid triggering
    // the Bluetooth permission dialog at app launch.

    override private init() {
        super.init()
        // Do NOT create CBCentralManager/CBPeripheralManager here.
        // Creating them triggers the Bluetooth permission dialog immediately.
        // They are initialized in start() when mesh features are actually used.
    }

    // MARK: - Public API

    func start(publicKey: String, displayName: String) {
        // Prevent double-start (called from both initializeApp AND MeshNetworkManager.startAll)
        guard !isRunning else {
            OshiLog.mesh.info("[CrossPlatformMesh] ⚠️ Already running, skipping duplicate start()")
            return
        }
        guard !publicKey.isEmpty else {
            OshiLog.mesh.info("[CrossPlatformMesh] ⚠️ Cannot start: empty public key")
            return
        }

        myPublicKey = publicKey
        myDisplayName = displayName

        OshiLog.mesh.info("[CrossPlatformMesh] 🚀 Starting...")
        OshiLog.mesh.info("   Public key: \(publicKey.prefix(16))...")
        OshiLog.mesh.info("   Display name: \(displayName)")

        // Lazily create BLE managers on first start() call
        // (not in init(), to avoid triggering Bluetooth permission at app launch)
        if centralManager == nil {
            centralManager = CBCentralManager(delegate: self, queue: DispatchQueue.global(qos: .userInitiated))
        }
        if peripheralManager == nil {
            peripheralManager = CBPeripheralManager(delegate: self, queue: DispatchQueue.global(qos: .userInitiated))
        }

        // Start TCP server first to get port
        startTCPServer()

        // Start BLE advertising/scanning (may need retry if BT not yet ready)
        startBLEAdvertising()
        startBLEScanning()

        // Schedule BLE retry in case BT wasn't ready at start time
        // (e.g., Bluetooth permission dialog still showing)
        scheduleBLERetry()

        // Start Bonjour — MUST run on main thread for run loop
        DispatchQueue.main.async { [weak self] in
            self?.startBonjourAdvertising()
            self?.startBonjourBrowsing()
        }

        // Periodic BLE scan restart for robustness (every 30s)
        scheduleScanRestart()

        // Periodic diagnostics update (every 5s)
        scheduleDiagnosticsUpdate()

        // Periodic stale route cleanup (every 60s, removes entries older than 5 minutes)
        scheduleRouteCleanup()

        // Periodic 2-hop identity gossip (every 30s)
        scheduleIdentityAnnounce()

        DispatchQueue.main.async {
            self.isRunning = true
        }
    }

    /// Tear down and restart with a new identity. Required after the user
    /// rotates / regenerates their public key — otherwise the stale `myPublicKey`
    /// keeps being stamped on every outgoing CrossPlatformMessage and the peer
    /// (Android) attributes the message to the OLD conversation instead of
    /// creating a fresh one for the new identity.
    func reconfigureIdentity(publicKey: String, displayName: String) {
        guard !publicKey.isEmpty else {
            OshiLog.mesh.info("[CrossPlatformMesh] ⚠️ reconfigureIdentity called with empty public key — ignoring")
            return
        }
        OshiLog.mesh.info("[CrossPlatformMesh] 🔄 Reconfiguring identity \(myPublicKey.prefix(12))… → \(publicKey.prefix(12))…")
        stop()
        // start() short-circuits when isRunning, but stop() flips it to false
        // on the main queue. Defer restart so the flag has actually settled.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
            self?.start(publicKey: publicKey, displayName: displayName)
        }
    }

    func stop() {
        OshiLog.mesh.info("[CrossPlatformMesh] Stopping...")

        scanRestartTimer?.cancel()
        scanRestartTimer = nil
        diagnosticsTimer?.cancel()
        diagnosticsTimer = nil
        bleRetryTimer?.cancel()
        bleRetryTimer = nil
        routeCleanupTimer?.cancel()
        routeCleanupTimer = nil
        identityAnnounceTimer?.cancel()
        identityAnnounceTimer = nil

        stopBLEAdvertising()
        stopBLEScanning()
        stopBonjourAdvertising()
        stopBonjourBrowsing()
        stopTCPServer()

        stateQueue.async { [weak self] in
            self?.sentIdentityTo.removeAll()
            self?.routingTable.removeAll()
            self?.seenMessageIds.removeAll()
        }

        DispatchQueue.main.async {
            self.isRunning = false
            self.crossPlatformPeers.removeAll()
            self.connectedPeers.removeAll()
        }
    }

    /// Send message to a peer (direct or via relay)
    /// Returns true only if the payload was handed to a live TCP connection.
    /// `@discardableResult` keeps every fire-and-forget caller source-compatible.
    @discardableResult
    func sendMessage(to recipientPublicKey: String, content: String, type: String = "TEXT_MESSAGE") -> Bool {
        let message = CrossPlatformMessage(
            id: UUID().uuidString,
            type: type,
            senderPublicKey: myPublicKey,
            senderName: myDisplayName,
            recipientPublicKey: recipientPublicKey,
            payload: content,
            timestamp: Date().timeIntervalSince1970 * 1000,  // Milliseconds to match Android
            hopCount: 0,
            maxHops: maxHops,
            seenBy: [myPublicKey],
            platform: "ios"
        )

        return sendOrRelay(message)
    }

    /// Broadcast a message to ALL connected cross-platform peers (for group ads, etc.)
    func broadcastToAllPeers(content: String, type: String) {
        // 🔧 FIX: Serialize tcpConnections access
        stateQueue.async { [weak self] in
            guard let self = self, !self.tcpConnections.isEmpty else { return }
            let message = CrossPlatformMessage(
                id: UUID().uuidString,
                type: type,
                senderPublicKey: self.myPublicKey,
                senderName: self.myDisplayName,
                recipientPublicKey: "",
                payload: content,
                timestamp: Date().timeIntervalSince1970 * 1000,
                hopCount: 0,
                maxHops: self.maxHops,
                seenBy: [self.myPublicKey],
                platform: "ios"
            )
            let data = message.toJSON()
            OshiLog.mesh.info("[CrossPlatformMesh] 📡 Broadcasting \(type) to \(self.tcpConnections.count) cross-platform peer(s)")
            for (_, connection) in self.tcpConnections {
                self.sendData(data, to: connection)
            }
        }
    }

    /// Send a relay message (for MeshNetworkManager integration)
    @discardableResult
    func relayMessage(_ originalMessage: Data, to recipientPublicKey: String) -> Bool {
        guard let content = String(data: originalMessage, encoding: .utf8) else { return false }
        return sendMessage(to: recipientPublicKey, content: content, type: "RELAY")
    }

    /// Add callback for received messages
    func addMessageCallback(_ callback: @escaping (CrossPlatformMessage) -> Void) {
        messageCallbacks.append(callback)
    }

    /// Check if peer is reachable
    func isPeerReachable(_ publicKey: String) -> Bool {
        if connectedPeers.contains(where: { $0.publicKey == publicKey }) {
            return true
        }
        if let route = routingTable[publicKey],
           Date().timeIntervalSince(route.lastSeen) < 90 {
            return true
        }
        return false
    }

    /// True ONLY if we can hand a payload to a live TCP connection *right now* —
    /// the recipient is directly connected, or we hold a route whose nextHop is
    /// directly connected. This is the SAME condition `sendOrRelay` uses to
    /// deliver, so callers that gate IPFS fallback on it are never lied to
    /// (unlike `isPeerReachable`, which trusts a routing entry up to 90 s stale).
    ///
    /// Thread-safety: reads tcpConnections/routingTable on stateQueue via `sync`.
    /// SAFE because all callers (MeshNetworkManager.sendMessage, MessageManager
    /// send path) run OFF stateQueue. Never call this from inside a stateQueue block.
    func isPeerLiveConnected(_ publicKey: String) -> Bool {
        return stateQueue.sync {
            if tcpConnections[publicKey] != nil { return true }
            if let route = routingTable[publicKey], tcpConnections[route.nextHop] != nil {
                return true
            }
            return false
        }
    }

    /// Check if peer is locally discoverable (BLE/Bonjour seen, TCP handshake may still be in progress).
    /// Used by the routing layer to wait briefly for a fresh connection before falling back to IPFS.
    func isPeerDiscovered(_ publicKey: String) -> Bool {
        return crossPlatformPeers.contains { $0.publicKey == publicKey }
    }

    /// Get Android peers
    func getAndroidPeers() -> [CrossPlatformPeer] {
        return crossPlatformPeers.filter { $0.platform == "android" }
    }

    /// Send call signal to a peer (for voice/video calls)
    func sendCallSignal(to recipientPublicKey: String, signalData: Data) {
        let base64Signal = signalData.base64EncodedString()
        sendMessage(to: recipientPublicKey, content: base64Signal, type: "CALL_SIGNAL")
    }

    /// Send call audio via cross-platform mesh (for direct P2P calls)
    func sendCallAudio(to recipientPublicKey: String, audioData: Data) {
        let base64Audio = audioData.base64EncodedString()
        sendMessage(to: recipientPublicKey, content: base64Audio, type: "CALL_AUDIO")
    }

    /// Send location via cross-platform mesh (iOS ↔ Android)
    @discardableResult
    func sendLocation(to recipientPublicKey: String, locationData: Data) -> Bool {
        let base64Location = locationData.base64EncodedString()
        return sendMessage(to: recipientPublicKey, content: base64Location, type: "LOCATION_MESSAGE")
    }

    /// Send media (image/video/document) via cross-platform mesh
    @discardableResult
    func sendMedia(to recipientPublicKey: String, mediaData: Data, mediaType: String, fileName: String) -> Bool {
        // Create a media payload with metadata
        let mediaPayload: [String: Any] = [
            "data": mediaData.base64EncodedString(),
            "mediaType": mediaType,
            "fileName": fileName,
            "fileSize": mediaData.count
        ]

        guard let payloadData = try? JSONSerialization.data(withJSONObject: mediaPayload),
              let payloadString = String(data: payloadData, encoding: .utf8) else {
            return false
        }
        return sendMessage(to: recipientPublicKey, content: payloadString, type: "MEDIA_MESSAGE")
    }

    /// Send document via cross-platform mesh
    func sendDocument(to recipientPublicKey: String, documentData: Data, fileName: String, mimeType: String) {
        let documentPayload: [String: Any] = [
            "data": documentData.base64EncodedString(),
            "fileName": fileName,
            "mimeType": mimeType,
            "fileSize": documentData.count
        ]

        if let payloadData = try? JSONSerialization.data(withJSONObject: documentPayload),
           let payloadString = String(data: payloadData, encoding: .utf8) {
            sendMessage(to: recipientPublicKey, content: payloadString, type: "DOCUMENT_MESSAGE")
        }
    }

    // MARK: - TCP Server

    private func startTCPServer() {
        do {
            // Use dynamic port
            let parameters = NWParameters.tcp
            parameters.allowLocalEndpointReuse = true

            tcpListener = try NWListener(using: parameters)

            tcpListener?.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    if let port = self?.tcpListener?.port?.rawValue {
                        self?.myPort = port
                        OshiLog.mesh.info("[CrossPlatformMesh] TCP server ready on port \(port)")
                    }
                case .failed(let error):
                    OshiLog.mesh.info("[CrossPlatformMesh] TCP server failed: \(error)")
                default:
                    break
                }
            }

            tcpListener?.newConnectionHandler = { [weak self] connection in
                self?.handleIncomingConnection(connection)
            }

            tcpListener?.start(queue: DispatchQueue.global(qos: .userInitiated))

        } catch {
            OshiLog.mesh.info("[CrossPlatformMesh] Failed to create TCP listener: \(error)")
        }
    }

    private func stopTCPServer() {
        tcpListener?.cancel()
        tcpListener = nil

        // 🔧 FIX: Serialize tcpConnections access
        stateQueue.async { [weak self] in
            guard let self = self else { return }
            for (_, connection) in self.tcpConnections {
                connection.cancel()
            }
            self.tcpConnections.removeAll()
        }
    }

    private func handleIncomingConnection(_ connection: NWConnection) {
        OshiLog.mesh.info("[CrossPlatformMesh] Incoming TCP connection")

        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                OshiLog.mesh.info("[CrossPlatformMesh] Connection ready")
                self?.receiveData(from: connection)
            case .failed(let error):
                OshiLog.mesh.info("[CrossPlatformMesh] Connection failed: \(error)")
                self?.cleanupConnection(for: connection)
            case .cancelled:
                OshiLog.mesh.info("[CrossPlatformMesh] Connection cancelled")
                self?.cleanupConnection(for: connection)
            default:
                break
            }
        }

        connection.start(queue: DispatchQueue.global(qos: .userInitiated))
    }

    private func connectToPeer(_ peer: CrossPlatformPeer) {
        guard let host = peer.ipAddress, let port = peer.port else {
            OshiLog.mesh.info("[CrossPlatformMesh] No address for peer")
            return
        }

        // 🔧 FIX: Serialize duplicate check on stateQueue (tcpConnections accessed from multiple threads)
        stateQueue.async { [weak self] in
            guard let self = self else { return }

            // Don't create duplicate TCP connections
            if let existing = self.tcpConnections[peer.publicKey] {
                let state = existing.state
                if state == .ready || state == .preparing {
                    OshiLog.mesh.info("[CrossPlatformMesh] Already connected to \(peer.displayName), skipping")
                    return
                }
            }

            let endpoint = NWEndpoint.hostPort(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!)
            let connection = NWConnection(to: endpoint, using: .tcp)

        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                OshiLog.mesh.info("[CrossPlatformMesh] Connected to \(peer.displayName)")
                // 🔧 FIX: Serialize state mutations
                self?.stateQueue.async {
                    self?.tcpConnections[peer.publicKey] = connection
                    self?.sentIdentityTo.insert(peer.publicKey)
                }

                DispatchQueue.main.async {
                    self?.addConnectedPeerOnMain(peer)
                }

                // Send identity
                self?.sendIdentity(to: connection)
                // 2-hop gossip: announce ourselves so the new peer can route to us
                self?.sendIdentityAnnounce(to: connection)
                self?.receiveData(from: connection)

            case .failed(let error):
                OshiLog.mesh.info("[CrossPlatformMesh] Connection to \(peer.displayName) failed: \(error)")
                self?.cleanupConnection(for: connection)

            case .cancelled:
                OshiLog.mesh.info("[CrossPlatformMesh] Connection to \(peer.displayName) cancelled")
                self?.cleanupConnection(for: connection)

            default:
                break
            }
        }

            connection.start(queue: DispatchQueue.global(qos: .userInitiated))
        } // end stateQueue.async
    }

    private func sendIdentity(to connection: NWConnection) {
        let identity = CrossPlatformMessage(
            id: UUID().uuidString,
            type: "IDENTITY_EXCHANGE",
            senderPublicKey: myPublicKey,
            senderName: myDisplayName,
            recipientPublicKey: "",
            payload: "{\"publicKey\":\"\(myPublicKey)\",\"name\":\"\(myDisplayName)\",\"platform\":\"ios\"}",
            timestamp: Date().timeIntervalSince1970 * 1000,  // Milliseconds to match Android
            hopCount: 0,
            maxHops: 1,
            seenBy: [],
            platform: "ios"
        )

        sendData(identity.toJSON(), to: connection)
    }

    private func sendData(_ data: Data, to connection: NWConnection) {
        // Prepend length header (4 bytes, big endian)
        var length = UInt32(data.count).bigEndian
        var packet = Data(bytes: &length, count: 4)
        packet.append(data)

        connection.send(content: packet, completion: .contentProcessed { [weak self] error in
            if let error = error {
                OshiLog.mesh.info("[CrossPlatformMesh] Send error: \(error)")
                self?.cleanupConnection(for: connection)
            }
        })
    }

    // 🔧 CRITICAL: Clean up all state when a TCP connection dies
    // This ensures isPeerReachable() returns false and routing falls back to IPFS immediately
    // Also allows BLE scan to reconnect to the peer
    private func cleanupConnection(for connection: NWConnection) {
        // 🔧 FIX: Serialize all mutable state access to prevent concurrent mutation crash
        stateQueue.async { [weak self] in
            guard let self = self else { return }

            // Find the public key associated with this connection
            guard let peerKey = self.tcpConnections.first(where: { $0.value === connection })?.key else {
                return
            }

            OshiLog.mesh.info("[CrossPlatformMesh] 🧹 Cleaning up dead connection for \(peerKey.prefix(16))...")

            self.tcpConnections.removeValue(forKey: peerKey)
            self.routingTable.removeValue(forKey: peerKey)
            self.sentIdentityTo.remove(peerKey)

            // Clear discoveredPeripherals to allow BLE re-discovery and reconnection
            self.discoveredPeripherals.removeAll()

            // Remove from connectedPeers on main thread
            DispatchQueue.main.async { [weak self] in
                self?.connectedPeers.removeAll { $0.publicKey == peerKey }
                OshiLog.mesh.info("[CrossPlatformMesh] 🧹 Peer removed from connectedPeers (now: \(self?.connectedPeers.count ?? 0))")
            }

            // Cancel the dead connection
            connection.cancel()
        }
    }

    private func receiveData(from connection: NWConnection) {
        // Read length header first (4 bytes)
        connection.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] data, _, isComplete, error in
            if let error = error {
                OshiLog.mesh.info("[CrossPlatformMesh] Receive error: \(error)")
                self?.cleanupConnection(for: connection)
                return
            }

            guard let data = data, data.count == 4 else {
                if isComplete {
                    OshiLog.mesh.info("[CrossPlatformMesh] Connection completed (header read)")
                    self?.cleanupConnection(for: connection)
                } else {
                    self?.receiveData(from: connection)
                }
                return
            }

            // loadUnaligned: the 4 received bytes are not guaranteed to be word-aligned (MED-11).
            let length = data.withUnsafeBytes { $0.loadUnaligned(as: UInt32.self).bigEndian }

            // Security: bound the length-prefixed allocation. An unauthenticated peer could send
            // a 0xFFFFFFFF header to force a ~4 GB buffer (remote OOM, H-MSG-1). Reject frames
            // outside (0, maxFrameSize]. 100 MB comfortably covers the 50 MB media limit + base64.
            let maxFrameSize: UInt32 = 100 * 1024 * 1024
            guard length > 0 && length <= maxFrameSize else {
                OshiLog.mesh.info("[CrossPlatformMesh] Rejecting frame with invalid length \(length); closing connection")
                self?.cleanupConnection(for: connection)
                return
            }

            // Now read the message
            connection.receive(minimumIncompleteLength: Int(length), maximumLength: Int(length)) { [weak self] messageData, _, isComplete, error in
                if let error = error {
                    OshiLog.mesh.info("[CrossPlatformMesh] Receive error (body): \(error)")
                    self?.cleanupConnection(for: connection)
                    return
                }

                if let messageData = messageData {
                    self?.handleReceivedData(messageData, from: connection)
                }

                if isComplete {
                    OshiLog.mesh.info("[CrossPlatformMesh] Connection completed (body read)")
                    self?.cleanupConnection(for: connection)
                } else {
                    self?.receiveData(from: connection)
                }
            }
        }
    }

    private func handleReceivedData(_ data: Data, from connection: NWConnection) {
        guard let message = CrossPlatformMessage.fromJSON(data) else {
            OshiLog.mesh.info("[CrossPlatformMesh] Failed to parse message")
            return
        }

        OshiLog.mesh.info("[CrossPlatformMesh] Received \(message.type) from \(message.senderName) (\(message.platform))")

        switch message.type {
        case "IDENTITY_EXCHANGE":
            handleIdentityExchange(message, connection: connection)

        case "IDENTITY_ANNOUNCE":
            handleIdentityAnnounce(message, from: connection)

        case "TEXT_MESSAGE", "MEDIA_MESSAGE", "CALL_SIGNAL", "CALL_AUDIO", "RELAY",
             "LOCATION_MESSAGE", "DOCUMENT_MESSAGE":
            handleContentMessage(message, from: connection)

        default:
            OshiLog.mesh.info("[CrossPlatformMesh] Unknown message type: \(message.type)")
        }
    }

    private func handleIdentityExchange(_ message: CrossPlatformMessage, connection: NWConnection) {
        // Skip iOS peers — MultipeerConnectivity handles iOS-to-iOS mesh
        if message.platform == "ios" {
            OshiLog.mesh.info("[CrossPlatformMesh] Skipping iOS identity from \(message.senderName) — handled by MultipeerConnectivity")
            return
        }

        // 🔧 FIX: Serialize all mutable state access
        stateQueue.async { [weak self] in
            guard let self = self else { return }

            // Don't replace existing live connection — prevents RST on duplicate connections
            if let existing = self.tcpConnections[message.senderPublicKey],
               existing !== connection,
               existing.state == .ready {
                OshiLog.mesh.info("[CrossPlatformMesh] Already have live connection for \(message.senderName), keeping existing")
            } else {
                self.tcpConnections[message.senderPublicKey] = connection
            }

            // Update routing table
            self.routingTable[message.senderPublicKey] = RouteInfo(
                nextHop: message.senderPublicKey,
                hopCount: 1,
                lastSeen: Date()
            )

            // Send our identity back if not already sent
            let shouldSendIdentity = !self.sentIdentityTo.contains(message.senderPublicKey)
            if shouldSendIdentity {
                self.sentIdentityTo.insert(message.senderPublicKey)
            }

            // Update peer info on main thread
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                let trimmedName = message.senderName.trimmingCharacters(in: .whitespacesAndNewlines)
                // Update any discovered peer that matches (by publicKey or name)
                for i in self.crossPlatformPeers.indices {
                    let p = self.crossPlatformPeers[i]
                    if p.publicKey == message.senderPublicKey ||
                       p.publicKey.isEmpty && p.displayName.trimmingCharacters(in: .whitespacesAndNewlines) == trimmedName {
                        self.crossPlatformPeers[i].publicKey = message.senderPublicKey
                        self.crossPlatformPeers[i].displayName = trimmedName
                        self.crossPlatformPeers[i].platform = message.platform
                        break
                    }
                }
                let peer = CrossPlatformPeer(
                    publicKey: message.senderPublicKey,
                    displayName: trimmedName,
                    platform: message.platform,
                    ipAddress: nil,
                    port: nil
                )
                self.addConnectedPeerOnMain(peer)
            }

            if shouldSendIdentity {
                OshiLog.mesh.info("[CrossPlatformMesh] Identity exchange complete: \(message.senderName) (\(message.platform)) — sending our identity back")
                self.sendIdentity(to: connection)
            } else {
                OshiLog.mesh.info("[CrossPlatformMesh] Identity exchange complete: \(message.senderName) (\(message.platform)) — already sent our identity")
            }

            // 2-hop gossip: tell the new peer our identity (and let them re-broadcast)
            self.sendIdentityAnnounce(to: connection)
        }
    }

    private func handleContentMessage(_ message: CrossPlatformMessage, from connection: NWConnection) {
        // 🔧 FIX: Serialize mutable state access
        stateQueue.async { [weak self] in
            guard let self = self else { return }

            // Check if already seen (duplicate prevention)
            if self.seenMessageIds.contains(message.id) {
                return
            }
            self.seenMessageIds.add(message.id)

            // Cleanup old message IDs
            if self.seenMessageIds.count > 5000 {
                self.seenMessageIds = Set(self.seenMessageIds.suffix(2500))
            }

            // Update routing table (learn route back to sender)
            self.routingTable[message.senderPublicKey] = RouteInfo(
                nextHop: message.senderPublicKey,
                hopCount: message.hopCount,
                lastSeen: Date()
            )

            // Check if message is for us.
            // Be tolerant: Android sometimes stores our key with different whitespace/case
            // padding than what we hold locally, which would cause a strict == to silently
            // drop the message and route it to relay (where it dies because we have no
            // outbound peers). Compare a normalized form first, then fall back to a
            // prefix match for any remaining encoding skew.
            let normalize: (String) -> String = { s in
                s.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            }
            let recipientNorm = normalize(message.recipientPublicKey)
            let myNorm        = normalize(self.myPublicKey)
            let prefixLen     = min(myNorm.count, recipientNorm.count, 32)
            let isForUs =
                message.recipientPublicKey.isEmpty ||
                recipientNorm == myNorm ||
                (prefixLen >= 16 && String(recipientNorm.prefix(prefixLen)) == String(myNorm.prefix(prefixLen)))

            if isForUs {
                OshiLog.mesh.info("[CrossPlatformMesh] Message is for us!")

                // Notify callbacks
                DispatchQueue.main.async { [weak self] in
                    self?.messageCallbacks.forEach { $0(message) }
                }

                // Also notify main mesh manager
                NotificationCenter.default.post(
                    name: NSNotification.Name("CrossPlatformMessageReceived"),
                    object: nil,
                    userInfo: ["message": message]
                )
            } else {
                OshiLog.mesh.info("[CrossPlatformMesh] ⚠️ Recipient key mismatch — relaying. mine=\(myNorm.prefix(24))... msg=\(recipientNorm.prefix(24))...")
                // Relay to intended recipient
                self.relayToRecipient(message)
            }
        }
    }

    // MARK: - Send/Relay Logic

    /// Delivers `message` to a live TCP connection and returns whether it could.
    ///
    /// The verdict (which connection, if any) is resolved SYNCHRONOUSLY on
    /// stateQueue so callers get the truth; the socket write itself stays async.
    /// Returning `false` lets callers (MeshNetworkManager → MessageManager)
    /// engage the IPFS fallback instead of falsely reporting mesh success.
    ///
    /// Strict mesh delivery — DO NOT broadcast. The CrossPlatformMessage envelope
    /// carries sender/recipient public keys in PLAINTEXT; broadcasting an
    /// unroutable message would leak the sender↔recipient relationship to every
    /// connected peer. When we can't deliver, we say so and IPFS takes over.
    @discardableResult
    private func sendOrRelay(_ message: CrossPlatformMessage) -> Bool {
        // Resolve the target connection synchronously so we can report the truth.
        // Also handles the throttled "no route" log inside the same critical
        // section (lastNoRouteLogAt is stateQueue-confined).
        let target: NWConnection? = stateQueue.sync {
            if let connection = tcpConnections[message.recipientPublicKey] {
                OshiLog.mesh.info("[CrossPlatformMesh] Direct send to \(message.recipientPublicKey.prefix(16))...")
                return connection
            }
            if let route = routingTable[message.recipientPublicKey],
               let connection = tcpConnections[route.nextHop] {
                OshiLog.mesh.info("[CrossPlatformMesh] Routed send via \(route.nextHop.prefix(16))...")
                return connection
            }
            // 🔧 FREEZE FIX: voice calls invoke this 50×/sec via sendCallAudio.
            // Throttle the no-route log to one per recipient per 5 s.
            let now = Date()
            let lastAt = lastNoRouteLogAt[message.recipientPublicKey] ?? .distantPast
            if now.timeIntervalSince(lastAt) > 5.0 {
                lastNoRouteLogAt[message.recipientPublicKey] = now
                OshiLog.mesh.info("[CrossPlatformMesh] No live path for \(message.recipientPublicKey.prefix(16))…, NOT delivered (peers=\(tcpConnections.count))")
            }
            return nil
        }

        guard let connection = target else {
            return false   // honest: NOT delivered — caller should fall back to IPFS
        }

        // Deliver on stateQueue (unchanged behavior); verdict already returned.
        stateQueue.async { [weak self] in
            self?.sendData(message.toJSON(), to: connection)
        }
        return true
    }

    private func relayToRecipient(_ message: CrossPlatformMessage) {
        // Check hop count
        guard message.hopCount < message.maxHops else {
            OshiLog.mesh.info("[CrossPlatformMesh] Max hops reached, dropping message")
            return
        }

        // Check if we've already relayed
        guard !message.seenBy.contains(myPublicKey) else {
            return
        }

        // Update message for relay
        var relayMessage = message
        relayMessage.hopCount += 1
        relayMessage.seenBy.append(myPublicKey)

        // Try direct route first
        if let route = routingTable[message.recipientPublicKey],
           let connection = tcpConnections[route.nextHop] {
            OshiLog.mesh.info("[CrossPlatformMesh] Relaying to \(message.recipientPublicKey.prefix(16))... via \(route.nextHop.prefix(16))...")
            sendData(relayMessage.toJSON(), to: connection)
            return
        }

        // Broadcast to all except sender
        OshiLog.mesh.info("[CrossPlatformMesh] Broadcast relay (hop \(relayMessage.hopCount))")
        for (publicKey, connection) in tcpConnections {
            if !message.seenBy.contains(publicKey) {
                sendData(relayMessage.toJSON(), to: connection)
            }
        }
    }

    // MARK: - BLE Advertising

    private func startBLEAdvertising() {
        guard let peripheralManager = peripheralManager, peripheralManager.state == .poweredOn else { return }

        // Stop existing advertising/services to avoid duplicates
        peripheralManager.stopAdvertising()
        peripheralManager.removeAllServices()

        // Create service
        let service = CBMutableService(type: CrossPlatformMesh.SERVICE_UUID, primary: true)

        // Create characteristic WITHOUT cached value (nil)
        // This ensures didReceiveRead delegate is called with fresh data (including current port/IP)
        let characteristic = CBMutableCharacteristic(
            type: CrossPlatformMesh.CHAR_UUID,
            properties: [.read],
            value: nil,
            permissions: [.readable]
        )
        service.characteristics = [characteristic]

        // Mark that we want to start advertising after service is added
        // peripheralManager.add() is ASYNC — advertising must wait for didAdd callback
        // Otherwise Android connects via BLE but can't find the GATT service
        pendingAdvertising = true
        peripheralManager.add(service)

        OshiLog.mesh.info("[CrossPlatformMesh] BLE GATT service adding... (advertising will start after)")
    }

    private func stopBLEAdvertising() {
        peripheralManager?.stopAdvertising()
        peripheralManager?.removeAllServices()
    }

    private func createAdvertisementData() -> Data {
        let info: [String: Any] = [
            "pk": String(myPublicKey.prefix(32)),  // Shortened for BLE
            "name": myDisplayName,
            "port": myPort,
            "platform": "ios",
            "ip": getLocalIPAddress() ?? ""
        ]
        return (try? JSONSerialization.data(withJSONObject: info)) ?? Data()
    }

    private func getLocalIPAddress() -> String? {
        var address: String?
        var linkLocalFallback: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0 else { return nil }
        defer { freeifaddrs(ifaddr) }

        var ptr = ifaddr
        while ptr != nil {
            defer { ptr = ptr?.pointee.ifa_next }
            guard let interface = ptr?.pointee else { continue }
            let addrFamily = interface.ifa_addr.pointee.sa_family
            if addrFamily == UInt8(AF_INET) {
                let name = String(cString: interface.ifa_name)
                if name == "en0" || name == "en1" {
                    var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    getnameinfo(interface.ifa_addr, socklen_t(interface.ifa_addr.pointee.sa_len), &hostname, socklen_t(hostname.count), nil, 0, NI_NUMERICHOST)
                    let ip = String(cString: hostname)
                    // 🔧 FIX: Prefer non-link-local addresses (169.254.x.x)
                    if ip.hasPrefix("169.254.") {
                        linkLocalFallback = ip
                    } else {
                        address = ip
                    }
                }
            }
        }
        return address ?? linkLocalFallback
    }

    // MARK: - BLE Scanning

    private func startBLEScanning() {
        guard let centralManager = centralManager, centralManager.state == .poweredOn else {
            OshiLog.mesh.info("[CrossPlatformMesh] BLE scanning skipped: central state=\(centralManager?.state.rawValue ?? -1)")
            return
        }

        // CRITICAL: Use broad scan (nil services) for cross-platform discovery!
        // UUID-filtered scan only sees devices advertising that EXACT UUID format,
        // but Android's 128-bit UUID ads may not match iOS's 16-bit UUID filter.
        // Broad scan + manual filtering (same as Android approach) is more reliable.
        centralManager.scanForPeripherals(
            withServices: nil,
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )

        OshiLog.mesh.info("[CrossPlatformMesh] BLE scanning started (broad scan, manual filtering)")
    }

    private func stopBLEScanning() {
        centralManager?.stopScan()
    }

    private func scheduleDiagnosticsUpdate() {
        diagnosticsTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .userInitiated))
        timer.schedule(deadline: .now() + 2, repeating: 5)
        timer.setEventHandler { [weak self] in
            guard let self = self else { return }
            let centralState = self.centralManager?.state == .poweredOn ? "ON" : "s\(self.centralManager?.state.rawValue ?? -1)"
            let periphState = self.peripheralManager?.state == .poweredOn ? "ON" : "s\(self.peripheralManager?.state.rawValue ?? -1)"
            let ip = self.getLocalIPAddress() ?? "none"
            let port = self.myPort
            let bleTotal = self.totalBLEScanResults
            let bleOshi = self.oshiBLEMatches
            let discovered = self.crossPlatformPeers.count
            let connected = self.connectedPeers.count
            let tcp = self.tcpConnections.count

            let diag = "v2 C:\(centralState) P:\(periphState) IP:\(ip) TCP:\(port) BLE[\(bleTotal)/\(bleOshi)] Peers:\(discovered)/\(connected)/\(tcp)"
            DispatchQueue.main.async {
                self.diagnostics = diag
            }
        }
        timer.resume()
        diagnosticsTimer = timer
    }

    private func scheduleScanRestart() {
        scanRestartTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .userInitiated))
        timer.schedule(deadline: .now() + 30, repeating: 30)
        timer.setEventHandler { [weak self] in
            guard let self = self, !self.myPublicKey.isEmpty else { return }

            // Restart BLE scanning
            if self.centralManager?.state == .poweredOn {
                self.centralManager?.stopScan()
                self.startBLEScanning()
                OshiLog.mesh.info("[CrossPlatformMesh] Periodic BLE scan restart (connected: \(self.connectedPeers.count))")
            }
        }
        timer.resume()
        scanRestartTimer = timer
    }

    /// Retry BLE advertising/scanning if Bluetooth wasn't ready at start time.
    /// Polls every 3s for up to 30s until both central and peripheral are powered on.
    private func scheduleBLERetry() {
        bleRetryTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .userInitiated))
        timer.schedule(deadline: .now() + 3, repeating: 3)
        var attempts = 0
        timer.setEventHandler { [weak self] in
            guard let self = self, !self.myPublicKey.isEmpty else { return }
            attempts += 1

            // Try advertising if peripheral is ready but we're not advertising yet
            if let pm = self.peripheralManager, pm.state == .poweredOn && !pm.isAdvertising {
                OshiLog.mesh.info("[CrossPlatformMesh] BLE retry #\(attempts): starting advertising")
                self.startBLEAdvertising()
            }

            // Try scanning if central is ready
            if self.centralManager?.state == .poweredOn {
                OshiLog.mesh.info("[CrossPlatformMesh] BLE retry #\(attempts): restarting scan")
                self.startBLEScanning()
            }

            // Stop retrying after 10 attempts (30s) or when both are running
            let advertisingOk = self.peripheralManager?.isAdvertising ?? false
            let scanningOk = self.centralManager?.state == .poweredOn
            if (advertisingOk && scanningOk) || attempts >= 10 {
                OshiLog.mesh.info("[CrossPlatformMesh] BLE retry done (adv:\(advertisingOk) scan:\(scanningOk) attempts:\(attempts))")
                self.bleRetryTimer?.cancel()
                self.bleRetryTimer = nil
            }
        }
        timer.resume()
        bleRetryTimer = timer
    }

    /// Periodically removes stale entries from the routing table.
    /// Runs every 60 seconds, evicting routes whose lastSeen is older than 5 minutes.
    private func scheduleRouteCleanup() {
        routeCleanupTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now() + 60, repeating: 60)
        timer.setEventHandler { [weak self] in
            guard let self = self else { return }
            self.stateQueue.async {
                let cutoff = Date().addingTimeInterval(-300) // 5 minutes ago
                let staleKeys = self.routingTable.filter { $0.value.lastSeen < cutoff }.map { $0.key }
                for key in staleKeys {
                    self.routingTable.removeValue(forKey: key)
                }
                if !staleKeys.isEmpty {
                    OshiLog.mesh.info("[CrossPlatformMesh] Route cleanup: removed \(staleKeys.count) stale route(s)")
                }
            }
        }
        timer.resume()
        routeCleanupTimer = timer
    }

    // MARK: - 2-Hop Identity Gossip (IDENTITY_ANNOUNCE)

    /// Build an IDENTITY_ANNOUNCE message describing the originator at the given hop count.
    /// Wire keys (originator, displayName, platform, hops, ttl) are kept identical with Android.
    private func buildIdentityAnnounce(originatorPublicKey: String,
                                       originatorDisplayName: String,
                                       originatorPlatform: String,
                                       hopCount: Int,
                                       ttl: Int) -> CrossPlatformMessage {
        let payloadDict: [String: Any] = [
            "originator": originatorPublicKey,
            "displayName": originatorDisplayName,
            "platform": originatorPlatform,
            "hops": hopCount,
            "ttl": ttl
        ]
        let payloadString: String
        if let data = try? JSONSerialization.data(withJSONObject: payloadDict),
           let s = String(data: data, encoding: .utf8) {
            payloadString = s
        } else {
            payloadString = "{}"
        }
        return CrossPlatformMessage(
            id: UUID().uuidString,
            type: "IDENTITY_ANNOUNCE",
            senderPublicKey: myPublicKey,
            senderName: myDisplayName,
            recipientPublicKey: "",
            payload: payloadString,
            timestamp: Date().timeIntervalSince1970 * 1000,
            hopCount: hopCount,
            maxHops: ttl,
            seenBy: [],
            platform: "ios"
        )
    }

    /// Broadcast our own IDENTITY_ANNOUNCE to all connected peers (hopCount=0, ttl=2).
    private func broadcastOwnIdentityAnnounce() {
        stateQueue.async { [weak self] in
            guard let self = self, !self.myPublicKey.isEmpty, !self.tcpConnections.isEmpty else { return }
            let msg = self.buildIdentityAnnounce(
                originatorPublicKey: self.myPublicKey,
                originatorDisplayName: self.myDisplayName,
                originatorPlatform: "ios",
                hopCount: 0,
                ttl: 2
            )
            self.seenMessageIds.add(msg.id)
            let data = msg.toJSON()
            for (_, connection) in self.tcpConnections {
                self.sendData(data, to: connection)
            }
            OshiLog.mesh.info("[CrossPlatformMesh] 📢 IDENTITY_ANNOUNCE broadcast (peers=\(self.tcpConnections.count))")
        }
    }

    /// Send a fresh IDENTITY_ANNOUNCE for ourselves to a single connection.
    /// Used when a peer just connected so they can immediately learn about us.
    private func sendIdentityAnnounce(to connection: NWConnection) {
        stateQueue.async { [weak self] in
            guard let self = self, !self.myPublicKey.isEmpty else { return }
            let msg = self.buildIdentityAnnounce(
                originatorPublicKey: self.myPublicKey,
                originatorDisplayName: self.myDisplayName,
                originatorPlatform: "ios",
                hopCount: 0,
                ttl: 2
            )
            self.seenMessageIds.add(msg.id)
            self.sendData(msg.toJSON(), to: connection)
        }
    }

    /// Schedule a 30s recurring IDENTITY_ANNOUNCE broadcast.
    private func scheduleIdentityAnnounce() {
        identityAnnounceTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        // Fire first announce shortly after start (5s), then every 30s
        timer.schedule(deadline: .now() + 5, repeating: 30)
        timer.setEventHandler { [weak self] in
            self?.broadcastOwnIdentityAnnounce()
        }
        timer.resume()
        identityAnnounceTimer = timer
    }

    /// Handle an inbound IDENTITY_ANNOUNCE: dedupe, learn route, optionally re-broadcast.
    private func handleIdentityAnnounce(_ message: CrossPlatformMessage, from connection: NWConnection) {
        stateQueue.async { [weak self] in
            guard let self = self else { return }

            // Dedupe by message id (also covers our own echoes)
            if self.seenMessageIds.contains(message.id) { return }
            self.seenMessageIds.add(message.id)

            // Parse payload
            guard let data = message.payload.data(using: .utf8),
                  let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
                return
            }
            let originator = obj["originator"] as? String ?? ""
            let displayName = obj["displayName"] as? String ?? ""
            let platform = obj["platform"] as? String ?? "unknown"
            let hopsIn = obj["hops"] as? Int ?? 0
            let ttl = obj["ttl"] as? Int ?? 2

            if originator.isEmpty { return }
            if originator == self.myPublicKey { return } // echo

            // Find the link this came in on (publicKey of direct neighbor)
            let nextHopKey = self.tcpConnections.first(where: { $0.value === connection })?.key
                ?? message.senderPublicKey

            let newHopCount = hopsIn + 1
            let now = Date()

            // Update routing table only if this is a strictly better path,
            // or refresh lastSeen if hop counts match.
            if let existing = self.routingTable[originator] {
                if newHopCount < existing.hopCount ||
                   (newHopCount == existing.hopCount && now.timeIntervalSince(existing.lastSeen) > 0) {
                    self.routingTable[originator] = RouteInfo(
                        nextHop: nextHopKey,
                        hopCount: newHopCount,
                        lastSeen: now
                    )
                }
            } else {
                self.routingTable[originator] = RouteInfo(
                    nextHop: nextHopKey,
                    hopCount: newHopCount,
                    lastSeen: now
                )
            }

            OshiLog.mesh.info("[CrossPlatformMesh] 📢 IDENTITY_ANNOUNCE \(displayName) (\(platform)) hops=\(newHopCount) via \(nextHopKey.prefix(12))…")

            // Re-broadcast if we haven't reached TTL
            if newHopCount < ttl {
                let relay = self.buildIdentityAnnounce(
                    originatorPublicKey: originator,
                    originatorDisplayName: displayName,
                    originatorPlatform: platform,
                    hopCount: newHopCount,
                    ttl: ttl
                )
                // Reuse the same id so dedupe upstream still works (prevent loops)
                let relayWithSameId = CrossPlatformMessage(
                    id: message.id,
                    type: relay.type,
                    senderPublicKey: relay.senderPublicKey,
                    senderName: relay.senderName,
                    recipientPublicKey: relay.recipientPublicKey,
                    payload: relay.payload,
                    timestamp: relay.timestamp,
                    hopCount: relay.hopCount,
                    maxHops: relay.maxHops,
                    seenBy: relay.seenBy,
                    platform: relay.platform
                )
                let relayData = relayWithSameId.toJSON()
                for (peerKey, peerConn) in self.tcpConnections {
                    if peerConn === connection { continue }    // skip the link it came from
                    if peerKey == originator { continue }      // skip the originator itself
                    self.sendData(relayData, to: peerConn)
                }
            }
        }
    }

    // MARK: - Bonjour Advertising

    private func startBonjourAdvertising() {
        guard myPort > 0 else {
            // Retry after TCP server is ready
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
                self?.startBonjourAdvertising()
            }
            return
        }

        let serviceName = "OSHI-\(myPublicKey.prefix(8))"
        netService = NetService(
            domain: CrossPlatformMesh.BONJOUR_DOMAIN,
            type: CrossPlatformMesh.BONJOUR_TYPE,
            name: serviceName,
            port: Int32(myPort)
        )

        // Set TXT record with our info
        let txtData: [String: Data] = [
            "pk": myPublicKey.data(using: .utf8)!,
            "name": myDisplayName.data(using: .utf8)!,
            "platform": "ios".data(using: .utf8)!
        ]
        netService?.setTXTRecord(NetService.data(fromTXTRecord: txtData))

        netService?.delegate = self
        netService?.publish()

        OshiLog.mesh.info("[CrossPlatformMesh] Bonjour advertising: \(serviceName) on port \(myPort)")
    }

    private func stopBonjourAdvertising() {
        netService?.stop()
        netService = nil
    }

    // MARK: - Bonjour Browsing

    private func startBonjourBrowsing() {
        netServiceBrowser = NetServiceBrowser()
        netServiceBrowser?.delegate = self
        netServiceBrowser?.searchForServices(
            ofType: CrossPlatformMesh.BONJOUR_TYPE,
            inDomain: CrossPlatformMesh.BONJOUR_DOMAIN
        )

        OshiLog.mesh.info("[CrossPlatformMesh] Bonjour browsing started")
    }

    private func stopBonjourBrowsing() {
        netServiceBrowser?.stop()
        netServiceBrowser = nil
        resolvedServices.removeAll()
    }
}

// MARK: - CBCentralManagerDelegate

extension CrossPlatformMesh: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        // Use myPublicKey instead of isRunning to avoid race condition:
        // isRunning is set async on main queue, but this delegate fires on BLE queue
        if central.state == .poweredOn && !myPublicKey.isEmpty {
            startBLEScanning()
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String : Any], rssi RSSI: NSNumber) {
        totalBLEScanResults += 1
        let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name ?? ""

        // Manual filtering since we use broad scan (nil services):
        // Check 1: Service UUID in advertisement data
        var hasOshiUuid = false
        if let serviceUUIDs = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] {
            // CBUUID("0541") == CBUUID("00000541-0000-1000-8000-00805F9B34FB")
            hasOshiUuid = serviceUUIDs.contains(where: {
                $0 == CrossPlatformMesh.SERVICE_UUID ||
                $0.uuidString == "00000541-0000-1000-8000-00805F9B34FB"
            })
        }

        // Check 2: Name starts with "OSHI"
        let hasOshiName = name.hasPrefix("OSHI")

        // Check 3: Manufacturer data contains "OSHI" bytes (Android sends this)
        var hasOshiMfg = false
        if let mfgData = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data, mfgData.count >= 6 {
            // First 2 bytes = manufacturer ID (little-endian 0xFFFF), rest = "OSHI"
            let payload = mfgData.subdata(in: 2..<mfgData.count)
            if String(data: payload, encoding: .utf8)?.hasPrefix("OSHI") == true {
                hasOshiMfg = true
            }
        }

        if !hasOshiUuid && !hasOshiName && !hasOshiMfg { return }

        oshiBLEMatches += 1
        OshiLog.mesh.info("[CrossPlatformMesh] BLE discovered OSHI: \(name) (RSSI: \(RSSI), uuid:\(hasOshiUuid), name:\(hasOshiName), mfg:\(hasOshiMfg))")

        // Avoid duplicate connections
        if discoveredPeripherals[peripheral.identifier] != nil { return }

        // Store and connect to read characteristics
        discoveredPeripherals[peripheral.identifier] = peripheral
        peripheral.delegate = self
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        OshiLog.mesh.info("[CrossPlatformMesh] BLE connected: \(peripheral.name ?? "Unknown")")
        peripheral.discoverServices([CrossPlatformMesh.SERVICE_UUID])
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        discoveredPeripherals.removeValue(forKey: peripheral.identifier)
    }
}

// MARK: - CBPeripheralDelegate

extension CrossPlatformMesh: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }

        for service in services {
            if service.uuid == CrossPlatformMesh.SERVICE_UUID {
                peripheral.discoverCharacteristics([CrossPlatformMesh.CHAR_UUID], for: service)
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let characteristics = service.characteristics else { return }

        for char in characteristics {
            if char.uuid == CrossPlatformMesh.CHAR_UUID {
                peripheral.readValue(for: char)
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value,
              let info = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return
        }

        let pk = info["pk"] as? String ?? ""
        let name = info["name"] as? String ?? "Unknown"
        let port = (info["port"] as? Int).flatMap { UInt16($0) } ?? 0
        let platform = info["platform"] as? String ?? "unknown"
        let ip = info["ip"] as? String ?? ""

        OshiLog.mesh.info("[CrossPlatformMesh] BLE peer info: \(name) (\(platform)) ip=\(ip) port=\(port) pk=\(pk.prefix(16))...")

        // Skip self-discovery
        if pk == myPublicKey {
            OshiLog.mesh.info("[CrossPlatformMesh] Skipping self-discovery via BLE")
            return
        }

        // Skip iOS peers — MultipeerConnectivity handles iOS-to-iOS mesh
        if platform == "ios" {
            OshiLog.mesh.info("[CrossPlatformMesh] Skipping iOS peer \(name) — handled by MultipeerConnectivity")
            return
        }

        // If we have IP and port from BLE, try TCP connection directly
        if !ip.isEmpty && port > 0 && !pk.isEmpty {
            let peer = CrossPlatformPeer(
                publicKey: pk,
                displayName: name,
                platform: platform,
                ipAddress: ip,
                port: port
            )

            // Add to discovered peers (centralized dedup)
            DispatchQueue.main.async { [weak self] in
                self?.addDiscoveredPeerOnMain(peer)
            }

            OshiLog.mesh.info("[CrossPlatformMesh] BLE provided IP+port, connecting via TCP to \(name) at \(ip):\(port)")
            connectToPeer(peer)
        }
    }
}

// MARK: - CBPeripheralManagerDelegate

extension CrossPlatformMesh: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn && !myPublicKey.isEmpty {
            startBLEAdvertising()
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error = error {
            OshiLog.mesh.info("[CrossPlatformMesh] Failed to add BLE service: \(error)")
            pendingAdvertising = false
            return
        }

        // GATT service is now in the database — safe to start advertising
        // Android will be able to discover services after connecting
        if pendingAdvertising {
            pendingAdvertising = false
            // Include BOTH Service UUID AND a short name "OSHI"
            // BLE advertising packet is limited to 31 bytes:
            //   - Flags: 3 bytes
            //   - 128-bit UUID: 18 bytes (2 header + 16 UUID)
            //   - Short name "OSHI": 6 bytes (2 header + 4 chars)
            //   = 27 bytes total, fits in 31 bytes!
            // Android can discover via UUID match OR name prefix "OSHI" — dual detection.
            peripheral.startAdvertising([
                CBAdvertisementDataServiceUUIDsKey: [CrossPlatformMesh.SERVICE_UUID],
                CBAdvertisementDataLocalNameKey: "OSHI"
            ])
            OshiLog.mesh.info("[CrossPlatformMesh] BLE advertising started (GATT service ready, name: OSHI)")
        }
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error = error {
            OshiLog.mesh.info("[CrossPlatformMesh] BLE advertising failed: \(error)")
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        if request.characteristic.uuid == CrossPlatformMesh.CHAR_UUID {
            let data = createAdvertisementData()
            if request.offset > data.count {
                peripheral.respond(to: request, withResult: .invalidOffset)
                return
            }
            request.value = data.subdata(in: request.offset..<data.count)
            peripheral.respond(to: request, withResult: .success)
            OshiLog.mesh.info("[CrossPlatformMesh] BLE read request served (port:\(myPort) ip:\(getLocalIPAddress() ?? "?"))")
        } else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
        }
    }
}

// MARK: - NetServiceDelegate

extension CrossPlatformMesh: NetServiceDelegate {
    func netServiceDidPublish(_ sender: NetService) {
        OshiLog.mesh.info("[CrossPlatformMesh] Bonjour service published: \(sender.name)")
    }

    func netService(_ sender: NetService, didNotPublish errorDict: [String : NSNumber]) {
        OshiLog.mesh.info("[CrossPlatformMesh] Bonjour publish failed: \(errorDict)")
    }
}

// MARK: - NetServiceBrowserDelegate

extension CrossPlatformMesh: NetServiceBrowserDelegate {
    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        // Don't connect to ourselves
        if service.name.contains(String(myPublicKey.prefix(8))) {
            return
        }

        OshiLog.mesh.info("[CrossPlatformMesh] Bonjour found: \(service.name)")

        resolvedServices.append(service)
        service.delegate = self
        service.resolve(withTimeout: 10)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        OshiLog.mesh.info("[CrossPlatformMesh] Bonjour lost: \(service.name)")
        resolvedServices.removeAll { $0.name == service.name }

        // Remove from peers using tracked publicKey (service.name ≠ displayName from TXT)
        let trackedKey = bonjourServiceToKey[service.name]
        bonjourServiceToKey.removeValue(forKey: service.name)

        DispatchQueue.main.async { [weak self] in
            self?.crossPlatformPeers.removeAll {
                (trackedKey != nil && $0.publicKey == trackedKey) || $0.displayName == service.name
            }
        }
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        guard let addresses = sender.addresses, !addresses.isEmpty else {
            OshiLog.mesh.info("[CrossPlatformMesh] No addresses for \(sender.name)")
            return
        }

        // Get IP address
        var ipAddress: String?
        for addressData in addresses {
            var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            addressData.withUnsafeBytes { ptr in
                let sockaddr = ptr.bindMemory(to: sockaddr.self).baseAddress!
                getnameinfo(sockaddr, socklen_t(addressData.count), &hostname, socklen_t(hostname.count), nil, 0, NI_NUMERICHOST)
            }
            let ip = String(cString: hostname)
            // Prefer IPv4
            if !ip.contains(":") {
                ipAddress = ip
                break
            }
        }

        // Get TXT record info
        var publicKey = ""
        var displayName = sender.name
        var platform = "unknown"

        if let txtData = sender.txtRecordData() {
            let txtDict = NetService.dictionary(fromTXTRecord: txtData)
            if let pkData = txtDict["pk"], let pk = String(data: pkData, encoding: .utf8) {
                publicKey = pk
            }
            if let nameData = txtDict["name"], let name = String(data: nameData, encoding: .utf8) {
                displayName = name
            }
            if let platData = txtDict["platform"], let plat = String(data: platData, encoding: .utf8) {
                platform = plat
            }
        }

        OshiLog.mesh.info("[CrossPlatformMesh] Resolved: \(displayName) (\(platform)) at \(ipAddress ?? "?"):\(sender.port)")

        // Skip iOS peers — MultipeerConnectivity handles iOS-to-iOS mesh
        if platform == "ios" {
            OshiLog.mesh.info("[CrossPlatformMesh] Skipping iOS peer \(displayName) — handled by MultipeerConnectivity")
            return
        }

        let peer = CrossPlatformPeer(
            publicKey: publicKey,
            displayName: displayName,
            platform: platform,
            ipAddress: ipAddress,
            port: UInt16(sender.port)
        )

        // Track service.name → publicKey mapping for proper removal
        if !publicKey.isEmpty {
            bonjourServiceToKey[sender.name] = publicKey
        }

        // Add to discovered peers (centralized dedup)
        DispatchQueue.main.async { [weak self] in
            self?.addDiscoveredPeerOnMain(peer)
        }

        // Connect via TCP
        if ipAddress != nil && sender.port > 0 {
            connectToPeer(peer)
        }
    }
}

// MARK: - Data Structures

struct CrossPlatformPeer: Identifiable, Equatable {
    let id = UUID()
    var publicKey: String
    var displayName: String
    var platform: String  // "ios" or "android"
    var ipAddress: String?
    var port: UInt16?

    static func == (lhs: CrossPlatformPeer, rhs: CrossPlatformPeer) -> Bool {
        return lhs.publicKey == rhs.publicKey
    }
}

struct CrossPlatformMessage: Codable {
    let id: String
    let type: String
    let senderPublicKey: String
    let senderName: String
    let recipientPublicKey: String
    let payload: String
    let timestamp: Double
    var hopCount: Int
    let maxHops: Int
    var seenBy: [String]
    let platform: String

    func toJSON() -> Data {
        return (try? JSONEncoder().encode(self)) ?? Data()
    }

    static func fromJSON(_ data: Data) -> CrossPlatformMessage? {
        return try? JSONDecoder().decode(CrossPlatformMessage.self, from: data)
    }
}

struct RouteInfo {
    let nextHop: String
    let hopCount: Int
    let lastSeen: Date
}

// MARK: - Set Extension

extension Set where Element == String {
    mutating func add(_ element: String) {
        self.insert(element)
    }
}
