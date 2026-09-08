//
//  MeshRelay.swift
//  Multi-hop message routing that extends mesh reach beyond a single hop.
//
//  ⚠️ Do NOT turn `maxHops` into a marketing distance. This header used to read
//  "extended mesh network range (10km)", and that 10 km figure leaked into five
//  user-facing strings before it was removed. `maxHops` is a loop-prevention
//  ceiling, not a reachable range: hitting it needs 49 other people standing in
//  a line, each with OSHI in the foreground. The honest user-facing numbers are
//  ~100 m for a direct hop and ~500 m in a realistic crowd.
//

import Foundation
import MultipeerConnectivity
import CryptoKit

class MeshRelayManager: ObservableObject {
    @Published var relayedMessages: [String: RelayedMessage] = [:]
    @Published var routingTable: [String: RouteInfo] = [:]
    
    // Loop/flood ceiling, not a range promise — see the file header.
    private let maxHops: Int = 50
    private let maxRelayAge: TimeInterval = 600 // 10 minutes
    private var messageCache: Set<String> = []
    private let maxCacheSize = 5000
    
    weak var meshNetwork: MeshNetworkManager?
    
    struct RelayedMessage {
        let id: String
        let content: Data
        let sender: String
        let recipient: String
        var hopCount: Int
        let timestamp: Date
        let routePath: [String]
    }
    
    struct RouteInfo {
        let nextHop: String
        var hopCount: Int
        let lastSeen: Date
    }
    
    struct RelayPacket: Codable {
        let messageId: String
        let originalSender: String
        let finalRecipient: String
        let encryptedContent: Data
        var hopCount: Int
        let timestamp: Date
        var routePath: [String]
        let signature: Data
    }
    
    init() {
        startCleanupTimer()
    }
    
    // MARK: - Message Relaying
    
    func relayMessage(_ messageData: Data, sender: String, recipient: String, currentHops: Int = 0) {
        guard currentHops < maxHops else {
            OshiLog.mesh.info("🚫 Message exceeded max hops (\(maxHops))")
            return
        }
        
        let messageId = UUID().uuidString
        
        guard !messageCache.contains(messageId) else {
            OshiLog.mesh.info("⏭️ Already relayed message \(messageId)")
            return
        }
        
        messageCache.insert(messageId)
        if messageCache.count > maxCacheSize {
            messageCache.removeFirst()
        }
        
        OshiLog.mesh.info("🔄 Relaying message \(messageId) (hop \(currentHops + 1)/\(maxHops))")
        
        guard let packet = createRelayPacket(
            messageData: messageData,
            sender: sender,
            recipient: recipient,
            hops: currentHops
        ) else {
            OshiLog.mesh.info("❌ Failed to create relay packet")
            return
        }
        
        guard let network = meshNetwork else { return }
        
        if let route = findRoute(to: recipient, in: network) {
            OshiLog.mesh.info("📍 Found route to \(recipient.prefix(8))... via \(route.nextHop.prefix(8))...")
            sendRelayPacket(packet, to: route.nextHop, through: network)
        } else {
            OshiLog.mesh.info("📢 Broadcasting relay (no specific route found)")
            broadcastRelayPacket(packet, through: network)
        }
    }
    
    func handleRelayedPacket(_ data: Data, from peer: String) -> Data? {
        guard let packet = try? JSONDecoder().decode(RelayPacket.self, from: data) else {
            OshiLog.mesh.info("❌ Failed to decode relay packet")
            return nil
        }
        
        guard verifyRelayPacket(packet) else {
            OshiLog.mesh.info("❌ Relay packet signature verification failed")
            return nil
        }
        
        guard packet.hopCount < maxHops else {
            OshiLog.mesh.info("🚫 Relay packet exceeded max hops")
            return nil
        }
        
        guard !messageCache.contains(packet.messageId) else {
            OshiLog.mesh.info("⏭️ Already processed relay \(packet.messageId)")
            return nil
        }
        
        messageCache.insert(packet.messageId)
        
        OshiLog.mesh.info("📥 Received relay from \(peer.prefix(8))... (hop \(packet.hopCount))")
        
        updateRoutingTable(for: packet.originalSender, via: peer, hops: packet.hopCount)
        
        // Check if we're the final recipient
        if packet.finalRecipient == getCurrentPublicKey() {
            OshiLog.mesh.info("🎯 We are the final recipient!")
            return packet.encryptedContent
        }
        
        // Continue relaying
        if let network = meshNetwork {
            var relayedPacket = packet
            relayedPacket.hopCount += 1
            relayedPacket.routePath.append(getCurrentPublicKey())
            
            if let route = findRoute(to: packet.finalRecipient, in: network) {
                sendRelayPacket(relayedPacket, to: route.nextHop, through: network)
            } else {
                broadcastRelayPacket(relayedPacket, through: network, excluding: peer)
            }
        }
        
        return nil
    }
    
    // MARK: - Routing
    
    private func findRoute(to recipient: String, in network: MeshNetworkManager) -> RouteInfo? {
        // Check routing table
        if let route = routingTable[recipient], route.lastSeen.timeIntervalSinceNow > -maxRelayAge {
            return route
        }
        return nil
    }
    
    private func updateRoutingTable(for destination: String, via nextHop: String, hops: Int) {
        let route = RouteInfo(nextHop: nextHop, hopCount: hops, lastSeen: Date())
        
        if let existing = routingTable[destination] {
            if route.hopCount < existing.hopCount || existing.lastSeen.timeIntervalSinceNow < -60 {
                routingTable[destination] = route
                OshiLog.mesh.info("🗺️ Updated route to \(destination.prefix(8))... via \(nextHop.prefix(8))... (\(hops) hops)")
            }
        } else {
            routingTable[destination] = route
            OshiLog.mesh.info("🗺️ New route to \(destination.prefix(8))... via \(nextHop.prefix(8))... (\(hops) hops)")
        }
    }
    
    // MARK: - Packet Management
    
    private func createRelayPacket(messageData: Data, sender: String, recipient: String, hops: Int) -> RelayPacket? {
        var routePath = [sender]
        if hops > 0 {
            routePath.append(getCurrentPublicKey())
        }
        
        let packet = RelayPacket(
            messageId: UUID().uuidString,
            originalSender: sender,
            finalRecipient: recipient,
            encryptedContent: messageData,
            hopCount: hops + 1,
            timestamp: Date(),
            routePath: routePath,
            signature: signPacket(messageData)
        )
        
        return packet
    }
    
    private func signPacket(_ data: Data) -> Data {
        return Data(SHA256.hash(data: data))
    }
    
    private func verifyRelayPacket(_ packet: RelayPacket) -> Bool {
        let expectedSignature = Data(SHA256.hash(data: packet.encryptedContent))
        return packet.signature == expectedSignature
    }
    
    private func sendRelayPacket(_ packet: RelayPacket, to peer: String, through network: MeshNetworkManager) {
        guard let data = try? JSONEncoder().encode(packet) else { return }
        
        // Send to specific peer via network manager
        NotificationCenter.default.post(
            name: NSNotification.Name("SendRelayPacket"),
            object: nil,
            userInfo: ["data": data, "peer": peer]
        )
    }
    
    private func broadcastRelayPacket(_ packet: RelayPacket, through network: MeshNetworkManager, excluding: String? = nil) {
        guard let data = try? JSONEncoder().encode(packet) else { return }
        
        NotificationCenter.default.post(
            name: NSNotification.Name("BroadcastRelayPacket"),
            object: nil,
            userInfo: ["data": data, "excluding": excluding as Any]
        )
    }
    
    // MARK: - Helpers
    
    private func getCurrentPublicKey() -> String {
        // Get from IdentityManager or similar
        return UserDefaults.standard.string(forKey: "publicKey") ?? "unknown"
    }
    
    // MARK: - Cleanup
    
    private func startCleanupTimer() {
        Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            self?.cleanupOldRoutes()
        }
    }
    
    private func cleanupOldRoutes() {
        let cutoffDate = Date().addingTimeInterval(-maxRelayAge)
        routingTable = routingTable.filter { $0.value.lastSeen > cutoffDate }
        
        if messageCache.count > maxCacheSize / 2 {
            let toRemove = messageCache.count - maxCacheSize / 2
            messageCache = Set(messageCache.dropFirst(toRemove))
        }
    }
}
