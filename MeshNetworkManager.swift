//
//  MeshNetworkManager.swift
//  ENHANCED: Multi-mesh network discovery + auto-connect to all nearby mesh networks
//

import Foundation
import MultipeerConnectivity
import Combine

class MeshNetworkManager: NSObject, ObservableObject {
    @Published var connectedPeers: [MCPeerID] = []
    @Published var discoveredPeers: [MCPeerID] = []
    @Published var discoveredNetworks: [String] = [] // NEW: Track different mesh networks
    @Published var isAdvertising = false
    @Published var isBrowsing = false
    
    private var peerID: MCPeerID!
    private var sessions: [String: MCSession] = [:] // NEW: Multiple sessions for different networks
    private var advertisers: [String: MCNearbyServiceAdvertiser] = [:] // NEW: Multiple advertisers
    private var browsers: [String: MCNearbyServiceBrowser] = [:] // NEW: Multiple browsers
    
    // Default service types to discover (can be extended)
    private var serviceTypes = [
        "oshi",      // Your app
        "mesh-chat",     // Generic mesh chat
        "p2p-message",   // P2P messaging
        "local-mesh"     // Local mesh network
    ]
    
    private var messageQueue: [QueuedMessage] = []
    
    // Auto-connect settings
    @Published var autoConnectEnabled = true
    @Published var multiNetworkEnabled = true // NEW: Enable/disable multi-network
    private var pendingInvitations: Set<MCPeerID> = []
    
    // Message relay settings
    private var seenMessageIDs: Set<String> = []
    private let maxHops = 500
    
    override init() {
        super.init()
        setupPeerID()
        
        if multiNetworkEnabled {
            setupMultipleNetworks()
        } else {
            setupSingleNetwork()
        }
        
        setupGroupBroadcastListener()
        
        // AUTO-START
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            self?.startAll()
        }
    }
    
    // MARK: - Multi-Network Setup
    
    private func setupMultipleNetworks() {
        print("🌐 Setting up multi-network discovery...")
        
        for serviceType in serviceTypes {
            let session = MCSession(
                peer: peerID,
                securityIdentity: nil,
                encryptionPreference: .required
            )
            session.delegate = self
            sessions[serviceType] = session
            
            let advertiser = MCNearbyServiceAdvertiser(
                peer: peerID,
                discoveryInfo: ["network": serviceType],
                serviceType: serviceType
            )
            advertiser.delegate = self
            advertisers[serviceType] = advertiser
            
            let browser = MCNearbyServiceBrowser(
                peer: peerID,
                serviceType: serviceType
            )
            browser.delegate = self
            browsers[serviceType] = browser
            
            print("✅ Registered for network: \(serviceType)")
        }
        
        discoveredNetworks = serviceTypes
    }
    
    private func setupSingleNetwork() {
        print("📡 Setting up single network...")
        let serviceType = "oshi"
        
        let session = MCSession(
            peer: peerID,
            securityIdentity: nil,
            encryptionPreference: .required
        )
        session.delegate = self
        sessions[serviceType] = session
        
        let advertiser = MCNearbyServiceAdvertiser(
            peer: peerID,
            discoveryInfo: nil,
            serviceType: serviceType
        )
        advertiser.delegate = self
        advertisers[serviceType] = advertiser
        
        let browser = MCNearbyServiceBrowser(
            peer: peerID,
            serviceType: serviceType
        )
        browser.delegate = self
        browsers[serviceType] = browser
    }
    
    private func setupPeerID() {
        if let data = UserDefaults.standard.data(forKey: "peerID"),
           let storedPeerID = try? NSKeyedUnarchiver.unarchivedObject(ofClass: MCPeerID.self, from: data) {
            peerID = storedPeerID
        } else {
            let deviceName = UIDevice.current.name
            peerID = MCPeerID(displayName: deviceName)
            
            if let data = try? NSKeyedArchiver.archivedData(withRootObject: peerID!, requiringSecureCoding: true) {
                UserDefaults.standard.set(data, forKey: "peerID")
            }
        }
    }
    
    private func setupGroupBroadcastListener() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleGroupBroadcastRequest(_:)),
            name: NSNotification.Name("BroadcastPublicGroupAd"),
            object: nil
        )
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleGroupUpdateRequest(_:)),
            name: NSNotification.Name("BroadcastGroupUpdate"),
            object: nil
        )
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleGroupMessageRequest(_:)),
            name: NSNotification.Name("BroadcastGroupMessage"),
            object: nil
        )
    }
    
    @objc private func handleGroupBroadcastRequest(_ notification: Notification) {
        guard let data = notification.userInfo?["data"] as? Data else { return }
        
        // Broadcast to ALL sessions
        for (network, session) in sessions {
            do {
                let peers = session.connectedPeers
                if !peers.isEmpty {
                    try session.send(data, toPeers: peers, with: .reliable)
                    print("📡 Broadcast group ad to \(peers.count) peer(s) on \(network)")
                }
            } catch {
                print("❌ Failed to broadcast on \(network): \(error)")
            }
        }
    }
    
    @objc private func handleGroupUpdateRequest(_ notification: Notification) {
        guard let data = notification.userInfo?["data"] as? Data else { return }
        
        for (_, session) in sessions {
            let peers = session.connectedPeers
            if !peers.isEmpty {
                try? session.send(data, toPeers: peers, with: .reliable)
            }
        }
    }
    
    @objc private func handleGroupMessageRequest(_ notification: Notification) {
        guard let data = notification.userInfo?["data"] as? Data else { return }
        
        for (_, session) in sessions {
            let peers = session.connectedPeers
            if !peers.isEmpty {
                try? session.send(data, toPeers: peers, with: .reliable)
            }
        }
    }
    
    // MARK: - Public Methods
    
    func startAll() {
        startAdvertising()
        startBrowsing()
        print("🚀 Mesh network auto-started with max \(maxHops) hops!")
        if multiNetworkEnabled {
            print("🌐 Multi-network mode: Discovering \(serviceTypes.count) network types")
        }
    }
    
    func startAdvertising() {
        guard !isAdvertising else { return }
        
        for (serviceType, advertiser) in advertisers {
            advertiser.startAdvertisingPeer()
            print("✅ Advertising on: \(serviceType)")
        }
        
        isAdvertising = true
    }
    
    func stopAdvertising() {
        guard isAdvertising else { return }
        
        for advertiser in advertisers.values {
            advertiser.stopAdvertisingPeer()
        }
        
        isAdvertising = false
        print("🛑 Stopped advertising")
    }
    
    func startBrowsing() {
        guard !isBrowsing else { return }
        
        for (serviceType, browser) in browsers {
            browser.startBrowsingForPeers()
            print("🔍 Browsing for: \(serviceType)")
        }
        
        isBrowsing = true
        
        // Auto-retry
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
            guard let self = self else { return }
            if self.discoveredPeers.isEmpty && self.isBrowsing {
                print("ℹ️ No peers found, restarting browsers...")
                for browser in self.browsers.values {
                    browser.stopBrowsingForPeers()
                    browser.startBrowsingForPeers()
                }
            }
        }
    }
    
    func stopBrowsing() {
        guard isBrowsing else { return }
        
        for browser in browsers.values {
            browser.stopBrowsingForPeers()
        }
        
        isBrowsing = false
    }
    
    func invitePeer(_ peerID: MCPeerID, to serviceType: String? = nil) {
        guard !connectedPeers.contains(peerID),
              !pendingInvitations.contains(peerID) else {
            return
        }
        
        pendingInvitations.insert(peerID)
        
        // If service type specified, invite to that session
        // Otherwise, invite to all sessions
        let sessionsToInvite: [(String, MCSession)]
        if let type = serviceType, let session = sessions[type] {
            sessionsToInvite = [(type, session)]
        } else {
            sessionsToInvite = Array(sessions)
        }
        
        for (type, _) in sessionsToInvite {
            if let browser = browsers[type] {
                browser.invitePeer(
                    peerID,
                    to: sessions[type]!,
                    withContext: nil,
                    timeout: 30
                )
                print("📤 Invited \(peerID.displayName) to \(type)")
            }
        }
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 30) { [weak self] in
            self?.pendingInvitations.remove(peerID)
        }
    }
    
    // MARK: - Message Sending
    
    func sendMessage(_ message: SecureMessage, to recipientAddress: String) throws {
        let relayMessage = RelayMessage(
            message: message,
            hopCount: 0,
            maxHops: maxHops,
            seenBy: [peerID.displayName]
        )
        
        let messageData = try JSONEncoder().encode(relayMessage)
        
        // Send to ALL connected peers across ALL networks
        var sentSuccessfully = false
        
        for (network, session) in sessions {
            let peers = session.connectedPeers
            guard !peers.isEmpty else { continue }
            
            // Check if recipient is in this network
            if let recipientPeer = peers.first(where: { findPeerByAddress(recipientAddress) == $0 }) {
                try session.send(messageData, toPeers: [recipientPeer], with: .reliable)
                print("✅ Sent to \(recipientPeer.displayName) on \(network)")
                sentSuccessfully = true
            } else {
                // Broadcast to all peers in this network for relay
                try session.send(messageData, toPeers: peers, with: .reliable)
                print("📡 Broadcast to \(peers.count) peer(s) on \(network)")
                sentSuccessfully = true
            }
        }
        
        if !sentSuccessfully {
            queueMessage(message, recipientAddress: recipientAddress)
            throw MeshError.noPeersConnected
        }
    }
    
    private func forwardRelayMessage(_ relayMsg: RelayMessage, from sourcePeer: MCPeerID) {
        guard !seenMessageIDs.contains(relayMsg.message.id) else {
            return
        }
        
        seenMessageIDs.insert(relayMsg.message.id)
        
        guard relayMsg.hopCount < relayMsg.maxHops else {
            print("🛑 Message reached max hops (\(relayMsg.maxHops))")
            return
        }
        
        guard !relayMsg.seenBy.contains(peerID.displayName) else {
            return
        }
        
        var updatedMessage = relayMsg
        updatedMessage.hopCount += 1
        updatedMessage.seenBy.append(peerID.displayName)
        
        // Relay to all networks
        for (network, session) in sessions {
            let relayPeers = session.connectedPeers.filter { $0 != sourcePeer }
            
            guard !relayPeers.isEmpty else { continue }
            
            do {
                let relayData = try JSONEncoder().encode(updatedMessage)
                try session.send(relayData, toPeers: relayPeers, with: .reliable)
                print("🔄 Relayed (hop \(updatedMessage.hopCount)/\(updatedMessage.maxHops)) to \(relayPeers.count) peer(s) on \(network)")
            } catch {
                print("❌ Relay failed on \(network): \(error)")
            }
        }
        
        // Cleanup
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
    }
    
    private func processMessageQueue() {
        messageQueue.removeAll { queuedMessage in
            if let recipientPeer = findPeerByAddress(queuedMessage.recipientAddress) {
                do {
                    let relayMessage = RelayMessage(
                        message: queuedMessage.message,
                        hopCount: 0,
                        maxHops: maxHops,
                        seenBy: [peerID.displayName]
                    )
                    let messageData = try JSONEncoder().encode(relayMessage)
                    
                    // Try to send on any session that has this peer
                    for session in sessions.values where session.connectedPeers.contains(recipientPeer) {
                        try session.send(messageData, toPeers: [recipientPeer], with: .reliable)
                        print("✅ Sent queued message")
                        return true
                    }
                } catch {
                    print("❌ Queue send error: \(error)")
                }
            }
            return false
        }
        saveMessageQueue()
    }
    
    private func findPeerByAddress(_ address: String) -> MCPeerID? {
        // Search across all sessions
        for session in sessions.values {
            if let peer = session.connectedPeers.first(where: { $0.displayName.contains(address.suffix(8)) }) {
                return peer
            }
        }
        return nil
    }
    
    // MARK: - Persistence
    
    private func saveMessageQueue() {
        if let data = try? JSONEncoder().encode(messageQueue) {
            UserDefaults.standard.set(data, forKey: "messageQueue")
        }
    }
    
    private func loadMessageQueue() {
        if let data = UserDefaults.standard.data(forKey: "messageQueue"),
           let queue = try? JSONDecoder().decode([QueuedMessage].self, from: data) {
            messageQueue = queue
        }
    }
}

// MARK: - MCSessionDelegate

extension MeshNetworkManager: MCSessionDelegate {
    func session(_ session: MCSession, peer peerID: MCPeerID, didChange state: MCSessionState) {
        DispatchQueue.main.async {
            switch state {
            case .connected:
                if !self.connectedPeers.contains(peerID) {
                    self.connectedPeers.append(peerID)
                    print("✅ Peer connected: \(peerID.displayName)")
                }
                self.pendingInvitations.remove(peerID)
                
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                    self?.processMessageQueue()
                }
                
            case .notConnected:
                self.connectedPeers.removeAll { $0 == peerID }
                self.pendingInvitations.remove(peerID)
                print("👋 Peer disconnected: \(peerID.displayName)")
                
            case .connecting:
                print("🔄 Connecting to: \(peerID.displayName)")
                
            @unknown default:
                break
            }
        }
    }
    
    func session(_ session: MCSession, didReceive data: Data, fromPeer peerID: MCPeerID) {
        // Try to decode as relay message first
        if let relayMessage = try? JSONDecoder().decode(RelayMessage.self, from: data) {
            print("📥 Relay message (hop \(relayMessage.hopCount)/\(relayMessage.maxHops))")
            
            let message = relayMessage.message
            handleReceivedMessage(message, from: peerID)
            forwardRelayMessage(relayMessage, from: peerID)
        }
        // Try to decode as group message
        else if (try? JSONDecoder().decode(GroupMessage.self, from: data)) != nil {
            print("📥 Group message received")
            
            DispatchQueue.main.async {
                NotificationCenter.default.post(
                    name: NSNotification.Name("NewGroupMessage"),
                    object: nil,
                    userInfo: ["groupMessage": data]
                )
            }
        }
        // Try to decode as public group advertisement
        else if let groupAd = try? JSONDecoder().decode(PublicGroupAd.self, from: data) {
            print("📥 Public group ad: \(groupAd.groupName)")
            
            DispatchQueue.main.async {
                NotificationCenter.default.post(
                    name: NSNotification.Name("PublicGroupDiscovered"),
                    object: nil,
                    userInfo: ["groupAd": data]
                )
            }
        }
        // Try to decode as regular secure message
        else if let message = try? JSONDecoder().decode(SecureMessage.self, from: data) {
            print("📥 Message from: \(peerID.displayName)")
            handleReceivedMessage(message, from: peerID)
        }
    }
    
    private func handleReceivedMessage(_ message: SecureMessage, from peer: MCPeerID) {
        DispatchQueue.main.async {
            NotificationCenter.default.post(
                name: .didReceiveMessage,
                object: message,
                userInfo: ["peer": peer]
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
        print("📨 Invitation from: \(peerID.displayName)")
        
        if autoConnectEnabled {
            // Find which session this advertiser belongs to
            for (serviceType, adv) in advertisers where adv === advertiser {
                if let session = sessions[serviceType] {
                    print("🤝 Auto-accepting on \(serviceType)")
                    invitationHandler(true, session)
                    return
                }
            }
        }
        
        invitationHandler(false, nil)
    }
    
    func advertiser(_ advertiser: MCNearbyServiceAdvertiser, didNotStartAdvertisingPeer error: Error) {
        let nsError = error as NSError
        if nsError.code != -72008 {
            print("⚠️ Advertiser error: \(error)")
        }
    }
}

// MARK: - MCNearbyServiceBrowserDelegate

extension MeshNetworkManager: MCNearbyServiceBrowserDelegate {
    func browser(_ browser: MCNearbyServiceBrowser, foundPeer peerID: MCPeerID, withDiscoveryInfo info: [String : String]?) {
        DispatchQueue.main.async {
            if !self.discoveredPeers.contains(peerID) {
                self.discoveredPeers.append(peerID)
                
                // Detect which network
                let networkType = info?["network"] ?? "unknown"
                print("✅ Discovered \(peerID.displayName) on \(networkType)")
                
                if self.autoConnectEnabled {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                        // Find which browser discovered this peer
                        for (serviceType, brw) in self?.browsers ?? [:] where brw === browser {
                            self?.invitePeer(peerID, to: serviceType)
                        }
                    }
                }
            }
        }
    }
    
    func browser(_ browser: MCNearbyServiceBrowser, lostPeer peerID: MCPeerID) {
        DispatchQueue.main.async {
            self.discoveredPeers.removeAll { $0 == peerID }
            self.pendingInvitations.remove(peerID)
            print("👋 Lost peer: \(peerID.displayName)")
        }
    }
    
    func browser(_ browser: MCNearbyServiceBrowser, didNotStartBrowsingForPeers error: Error) {
        let nsError = error as NSError
        if nsError.code != -72008 {
            print("⚠️ Browser error: \(error)")
        }
    }
}

// MARK: - Models

struct QueuedMessage: Codable {
    let message: SecureMessage
    let recipientAddress: String
    let timestamp: Date
}

struct RelayMessage: Codable {
    let message: SecureMessage
    var hopCount: Int
    let maxHops: Int
    var seenBy: [String]
}

enum MeshError: LocalizedError {
    case noPeersConnected
    case peerNotConnected
    
    var errorDescription: String? {
        switch self {
        case .noPeersConnected:
            return "No peers connected to mesh network"
        case .peerNotConnected:
            return "Peer is not in connected state"
        }
    }
}

extension Notification.Name {
    static let didReceiveMessage = Notification.Name("didReceiveMessage")
}
