//
//  DoubleRatchet_Fixed.swift
//  ✅ FIXED: Canonical session keys + message sequencing + out-of-order handling
//

import Foundation
import CryptoKit

class DoubleRatchetSession: Codable {
    private(set) var rootKey: Data
    private(set) var sendingChainKey: Data
    private(set) var sendingMessageNumber: UInt32 = 0
    private(set) var previousSendingChainLength: UInt32 = 0
    private(set) var receivingChainKey: Data
    private(set) var receivingMessageNumber: UInt32 = 0
    private(set) var ourRatchetKeyPair: Curve25519.KeyAgreement.PrivateKey
    private(set) var theirRatchetPublicKey: Data?
    
    private(set) var createdAt: Date
    private(set) var lastUsedAt: Date
    private(set) var sessionVersion: Int = 2  // ✅ Bumped version
    
    // ✅ NEW: Session identity
    private(set) var sessionId: String  // Canonical identifier
    private(set) var isInitiator: Bool  // Role in this session
    
    internal private(set) var skippedMessageKeys: [String: SkippedKey] = [:]
    private let maxSkippedMessages: Int = 200
    private var needsDHRatchet: Bool = false
    
    private let sessionQueue = DispatchQueue(label: "com.app.ratchet.session", qos: .userInitiated)
    
    struct SkippedKey: Codable {
        let key: Data
        let timestamp: Date
        let messageNumber: UInt32  // ✅ Track which message this was for
    }
    
    enum CodingKeys: String, CodingKey {
        case rootKey, sendingChainKey, sendingMessageNumber, previousSendingChainLength
        case receivingChainKey, receivingMessageNumber
        case ourRatchetKeyData, theirRatchetPublicKey, skippedMessageKeys
        case createdAt, lastUsedAt, sessionVersion
        case sessionId, isInitiator  // ✅ NEW
    }
    
    init(rootKey: Data, sendingChainKey: Data, receivingChainKey: Data, sessionId: String, isInitiator: Bool) {
        self.rootKey = rootKey
        self.sendingChainKey = sendingChainKey
        self.receivingChainKey = receivingChainKey
        self.ourRatchetKeyPair = Curve25519.KeyAgreement.PrivateKey()
        self.sessionId = sessionId
        self.isInitiator = isInitiator
        self.createdAt = Date()
        self.lastUsedAt = Date()
        self.sessionVersion = 2
    }
    
    required init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        rootKey = try container.decode(Data.self, forKey: .rootKey)
        sendingChainKey = try container.decode(Data.self, forKey: .sendingChainKey)
        sendingMessageNumber = try container.decode(UInt32.self, forKey: .sendingMessageNumber)
        previousSendingChainLength = try container.decodeIfPresent(UInt32.self, forKey: .previousSendingChainLength) ?? 0
        receivingChainKey = try container.decode(Data.self, forKey: .receivingChainKey)
        receivingMessageNumber = try container.decode(UInt32.self, forKey: .receivingMessageNumber)
        
        let keyData = try container.decode(Data.self, forKey: .ourRatchetKeyData)
        ourRatchetKeyPair = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: keyData)
        theirRatchetPublicKey = try container.decodeIfPresent(Data.self, forKey: .theirRatchetPublicKey)
        skippedMessageKeys = try container.decode([String: SkippedKey].self, forKey: .skippedMessageKeys)
        
        createdAt = try container.decodeIfPresent(Date.self, forKey: .createdAt) ?? Date()
        lastUsedAt = try container.decodeIfPresent(Date.self, forKey: .lastUsedAt) ?? Date()
        sessionVersion = try container.decodeIfPresent(Int.self, forKey: .sessionVersion) ?? 1
        
        // ✅ NEW: Load session identity or create from legacy data
        sessionId = try container.decodeIfPresent(String.self, forKey: .sessionId) ?? UUID().uuidString
        isInitiator = try container.decodeIfPresent(Bool.self, forKey: .isInitiator) ?? true
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(rootKey, forKey: .rootKey)
        try container.encode(sendingChainKey, forKey: .sendingChainKey)
        try container.encode(sendingMessageNumber, forKey: .sendingMessageNumber)
        try container.encode(previousSendingChainLength, forKey: .previousSendingChainLength)
        try container.encode(receivingChainKey, forKey: .receivingChainKey)
        try container.encode(receivingMessageNumber, forKey: .receivingMessageNumber)
        try container.encode(ourRatchetKeyPair.rawRepresentation, forKey: .ourRatchetKeyData)
        try container.encodeIfPresent(theirRatchetPublicKey, forKey: .theirRatchetPublicKey)
        try container.encode(skippedMessageKeys, forKey: .skippedMessageKeys)
        try container.encode(createdAt, forKey: .createdAt)
        try container.encode(lastUsedAt, forKey: .lastUsedAt)
        try container.encode(sessionVersion, forKey: .sessionVersion)
        try container.encode(sessionId, forKey: .sessionId)  // ✅ NEW
        try container.encode(isInitiator, forKey: .isInitiator)  // ✅ NEW
    }
    
    func isStale(maxAge: TimeInterval = 3600) -> Bool {
        return Date().timeIntervalSince(lastUsedAt) > maxAge
    }
    
    func updateLastUsed() {
        lastUsedAt = Date()
    }
    
    func encrypt(_ plaintext: String) throws -> DoubleRatchetMessage {
        return try sessionQueue.sync {
            try _encrypt(plaintext)
        }
    }
    
    private func _encrypt(_ plaintext: String) throws -> DoubleRatchetMessage {
        let ourPubKey = ourRatchetKeyPair.publicKey.rawRepresentation
        
        OshiLog.crypto.info("🔒 Encrypt:")
        OshiLog.crypto.info("   Session: \(sessionId.prefix(8))...")
        OshiLog.crypto.info("   Role: \(isInitiator ? "INITIATOR" : "RESPONDER")")
        OshiLog.crypto.info("   send#\(sendingMessageNumber)")
        
        let messageKey = deriveMessageKey(from: sendingChainKey)
        
        let header = DoubleRatchetMessage.MessageHeader(
            publicKey: ourPubKey,
            messageNumber: sendingMessageNumber,
            previousChainLength: previousSendingChainLength,
            isDHRatchet: needsDHRatchet,
            timestamp: Date(),
            sessionVersion: sessionVersion,
            sessionId: sessionId  // ✅ Include session ID in header
        )
        
        let headerData = encodeHeaderForAAD(header)
        let ciphertext = try encryptWithKey(plaintext, key: messageKey, authenticating: headerData)
        
        let message = DoubleRatchetMessage(header: header, ciphertext: ciphertext)
        
        sendingChainKey = advanceChainKey(sendingChainKey)
        sendingMessageNumber += 1
        needsDHRatchet = false
        
        updateLastUsed()
        DoubleRatchetSessionManager.shared.saveSession()
        
        OshiLog.crypto.info("   ✅ Encrypted msg#\(sendingMessageNumber - 1)")
        
        return message
    }
    
    func decrypt(_ message: DoubleRatchetMessage) throws -> String {
        return try sessionQueue.sync {
            try _decrypt(message)
        }
    }
    
    private func _decrypt(_ message: DoubleRatchetMessage) throws -> String {
        let hdrPubB64 = message.header.publicKey.base64EncodedString()
        
        OshiLog.crypto.info("🔓 Decrypt:")
        OshiLog.crypto.info("   Session: \(sessionId.prefix(8))...")
        OshiLog.crypto.info("   Role: \(isInitiator ? "INITIATOR" : "RESPONDER")")
        OshiLog.crypto.info("   incoming msg#\(message.header.messageNumber)")
        OshiLog.crypto.info("   recv#\(receivingMessageNumber)")
        
        // ✅ Check session ID match
        if message.header.sessionId != sessionId {
            OshiLog.crypto.info("   ⚠️ Session ID mismatch!")
            OshiLog.crypto.info("      Message session: \(message.header.sessionId.prefix(8))...")
            OshiLog.crypto.info("      Our session: \(sessionId.prefix(8))...")
            throw DoubleRatchetError.sessionMismatch
        }
        
        if message.header.sessionVersion != sessionVersion {
            throw DoubleRatchetError.versionMismatch
        }
        
        let skipKey = "\(hdrPubB64):\(message.header.messageNumber)"
        
        // Check skipped messages first
        if let skippedKey = skippedMessageKeys[skipKey] {
            OshiLog.crypto.info("   🔑 Using skipped key for msg#\(message.header.messageNumber)")
            let headerData = encodeHeaderForAAD(message.header)
            let plaintext = try decryptWithKey(message.ciphertext, key: skippedKey.key, authenticating: headerData)
            skippedMessageKeys.removeValue(forKey: skipKey)
            updateLastUsed()
            DoubleRatchetSessionManager.shared.saveSession()
            return plaintext
        }
        
        let savedState = saveState()
        
        do {
            // ✅ FIXED: Store their key on first message, regardless of message number
            if theirRatchetPublicKey == nil {
                OshiLog.crypto.info("   📝 First message from sender, storing their key (no DH ratchet)")
                OshiLog.crypto.info("      Message #: \(message.header.messageNumber)")
                theirRatchetPublicKey = message.header.publicKey
            } else if theirRatchetPublicKey != message.header.publicKey {
                // ✅ CRITICAL: Check if this is an old message with old ratchet key
                if message.header.messageNumber < receivingMessageNumber {
                    OshiLog.crypto.info("   ⚠️ Old message with old ratchet key detected")
                    OshiLog.crypto.info("      Message #\(message.header.messageNumber) < recv#\(receivingMessageNumber)")
                    OshiLog.crypto.info("      This message was encrypted before we stored the current ratchet key")
                    OshiLog.crypto.info("      Cannot decrypt - message arrived too late")
                    throw DoubleRatchetError.oldMessage
                }
                
                // Not an old message, perform DH ratchet
                OshiLog.crypto.info("   🔄 Key changed, DH ratchet")
                try performDHRatchet(newPublicKey: message.header.publicKey)
            } else if message.header.isDHRatchet {
                OshiLog.crypto.info("   🔄 Flagged for ratchet")
                try performDHRatchet(newPublicKey: message.header.publicKey)
            }
            
            // ✅ Handle message gaps
            if message.header.messageNumber > receivingMessageNumber {
                let gap = message.header.messageNumber - receivingMessageNumber
                OshiLog.crypto.info("   ⏭️ Message gap detected: \(gap) message(s)")
                OshiLog.crypto.info("      Expected: msg#\(receivingMessageNumber)")
                OshiLog.crypto.info("      Received: msg#\(message.header.messageNumber)")
                try skipMessageKeys(until: message.header.messageNumber, chainPublicKey: message.header.publicKey)
            }
            
            // ✅ Check for exact match (message arrived in order)
            if message.header.messageNumber == receivingMessageNumber {
                let messageKey = deriveMessageKey(from: receivingChainKey)
                
                let headerData = encodeHeaderForAAD(message.header)
                let plaintext = try decryptWithKey(message.ciphertext, key: messageKey, authenticating: headerData)
                
                receivingChainKey = advanceChainKey(receivingChainKey)
                receivingMessageNumber += 1
                
                updateLastUsed()
                DoubleRatchetSessionManager.shared.saveSession()
                
                OshiLog.crypto.info("   ✅ Decrypted in-order message, recv#\(receivingMessageNumber)")
                
                return plaintext
            } else {
                // Should have been handled by skipMessageKeys
                throw DoubleRatchetError.invalidMessageNumber
            }
            
        } catch {
            OshiLog.crypto.info("   ❌ Failed: \(error)")
            restoreState(savedState)
            throw error
        }
    }
    
    private struct SessionState {
        let rootKey: Data
        let sendingChainKey: Data
        let sendingMessageNumber: UInt32
        let previousSendingChainLength: UInt32
        let receivingChainKey: Data
        let receivingMessageNumber: UInt32
        let ourRatchetKeyPair: Curve25519.KeyAgreement.PrivateKey
        let theirRatchetPublicKey: Data?
        let skippedKeys: [String: SkippedKey]
    }
    
    private func saveState() -> SessionState {
        SessionState(
            rootKey: rootKey,
            sendingChainKey: sendingChainKey,
            sendingMessageNumber: sendingMessageNumber,
            previousSendingChainLength: previousSendingChainLength,
            receivingChainKey: receivingChainKey,
            receivingMessageNumber: receivingMessageNumber,
            ourRatchetKeyPair: ourRatchetKeyPair,
            theirRatchetPublicKey: theirRatchetPublicKey,
            skippedKeys: skippedMessageKeys
        )
    }
    
    private func restoreState(_ state: SessionState) {
        rootKey = state.rootKey
        sendingChainKey = state.sendingChainKey
        sendingMessageNumber = state.sendingMessageNumber
        previousSendingChainLength = state.previousSendingChainLength
        receivingChainKey = state.receivingChainKey
        receivingMessageNumber = state.receivingMessageNumber
        ourRatchetKeyPair = state.ourRatchetKeyPair
        theirRatchetPublicKey = state.theirRatchetPublicKey
        skippedMessageKeys = state.skippedKeys
    }
    
    private func performDHRatchet(newPublicKey: Data) throws {
        let theirPub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: newPublicKey)
        let shared1 = try ourRatchetKeyPair.sharedSecretFromKeyAgreement(with: theirPub)
        let (rk1, recvChain) = deriveRootKeys(rootKey: rootKey, dhOutput: shared1)
        let newOurRatchet = Curve25519.KeyAgreement.PrivateKey()
        let shared2 = try newOurRatchet.sharedSecretFromKeyAgreement(with: theirPub)
        let (rk2, sendChain) = deriveRootKeys(rootKey: rk1, dhOutput: shared2)
        
        previousSendingChainLength = sendingMessageNumber
        rootKey = rk2
        receivingChainKey = recvChain
        receivingMessageNumber = 0
        sendingChainKey = sendChain
        sendingMessageNumber = 0
        ourRatchetKeyPair = newOurRatchet
        theirRatchetPublicKey = newPublicKey
        needsDHRatchet = true
    }
    
    private func skipMessageKeys(until targetNumber: UInt32, chainPublicKey: Data) throws {
        let gap = Int(targetNumber - receivingMessageNumber)
        guard gap <= maxSkippedMessages else {
            throw DoubleRatchetError.tooManySkippedMessages
        }
        
        let chainPubB64 = chainPublicKey.base64EncodedString()
        let now = Date()
        
        while receivingMessageNumber < targetNumber {
            let skipKey = "\(chainPubB64):\(receivingMessageNumber)"
            let messageKey = deriveMessageKey(from: receivingChainKey)
            skippedMessageKeys[skipKey] = SkippedKey(
                key: messageKey,
                timestamp: now,
                messageNumber: receivingMessageNumber
            )
            OshiLog.crypto.info("      Skipping msg#\(receivingMessageNumber), storing key")
            receivingChainKey = advanceChainKey(receivingChainKey)
            receivingMessageNumber += 1
        }
        
        OshiLog.crypto.info("   ✅ Skipped to msg#\(receivingMessageNumber), stored \(gap) key(s)")
    }
    
    private func deriveMessageKey(from chainKey: Data) -> Data {
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: chainKey),
            salt: Data(),
            info: Data("MessageKey".utf8),
            outputByteCount: 32
        )
        return key.withUnsafeBytes { Data($0) }
    }
    
    private func advanceChainKey(_ chainKey: Data) -> Data {
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: chainKey),
            salt: Data(),
            info: Data("ChainKey".utf8),
            outputByteCount: 32
        )
        return key.withUnsafeBytes { Data($0) }
    }
    
    private func deriveRootKeys(rootKey: Data, dhOutput: SharedSecret) -> (Data, Data) {
        let sharedSecretData = dhOutput.withUnsafeBytes { Data($0) }
        let derived = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: sharedSecretData),
            salt: rootKey,
            info: Data("RootKey".utf8),
            outputByteCount: 64
        )
        let derivedData = derived.withUnsafeBytes { Data($0) }
        return (derivedData.prefix(32), derivedData.suffix(32))
    }
    
    private func encryptWithKey(_ plaintext: String, key: Data, authenticating headerData: Data) throws -> Data {
        guard let plaintextData = plaintext.data(using: .utf8) else {
            throw DoubleRatchetError.encryptionFailed
        }
        let box = try AES.GCM.seal(
            plaintextData,
            using: SymmetricKey(data: key),
            nonce: AES.GCM.Nonce(),
            authenticating: headerData
        )
        guard let combined = box.combined else {
            throw DoubleRatchetError.encryptionFailed
        }
        return combined
    }
    
    private func decryptWithKey(_ ciphertext: Data, key: Data, authenticating headerData: Data) throws -> String {
        let box = try AES.GCM.SealedBox(combined: ciphertext)
        let data = try AES.GCM.open(
            box,
            using: SymmetricKey(data: key),
            authenticating: headerData
        )
        guard let plaintext = String(data: data, encoding: .utf8) else {
            throw DoubleRatchetError.decryptionFailed
        }
        return plaintext
    }
    
    private func encodeHeaderForAAD(_ header: DoubleRatchetMessage.MessageHeader) -> Data {
        var data = Data()
        
        // Always same order: publicKey, msgNum, prevLen, flag, version, sessionId
        data.append(header.publicKey)
        
        var msgNum = header.messageNumber.bigEndian
        withUnsafeBytes(of: &msgNum) { data.append(contentsOf: $0) }
        
        var prevLen = header.previousChainLength.bigEndian
        withUnsafeBytes(of: &prevLen) { data.append(contentsOf: $0) }
        
        data.append(header.isDHRatchet ? 1 : 0)
        
        var version = Int32(header.sessionVersion).bigEndian
        withUnsafeBytes(of: &version) { data.append(contentsOf: $0) }
        
        // ✅ Include session ID in AAD
        if let sessionIdData = header.sessionId.data(using: .utf8) {
            data.append(sessionIdData)
        }
        
        return data
    }
    
    func cleanupOldSkippedKeys(olderThan days: Int = 7) {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            let cutoffDate = Date().addingTimeInterval(-Double(days * 24 * 60 * 60))
            let keysToRemove = self.skippedMessageKeys.filter { $0.value.timestamp < cutoffDate }.map { $0.key }
            keysToRemove.forEach { self.skippedMessageKeys.removeValue(forKey: $0) }
            if !keysToRemove.isEmpty {
                OshiLog.crypto.info("🧹 Cleaned \(keysToRemove.count) old skipped keys")
                DoubleRatchetSessionManager.shared.saveSession()
            }
        }
    }
}

struct DoubleRatchetMessage: Codable {
    let header: MessageHeader
    let ciphertext: Data
    
    struct MessageHeader: Codable {
        let publicKey: Data
        let messageNumber: UInt32
        let previousChainLength: UInt32
        let isDHRatchet: Bool
        let timestamp: Date
        let sessionVersion: Int
        let sessionId: String  // ✅ NEW: Identify which session this belongs to
    }
}

enum DoubleRatchetError: Error {
    case tooManySkippedMessages
    case decryptionFailed
    case encryptionFailed
    case oldMessage
    case invalidPublicKey
    case versionMismatch
    case staleSession
    case sessionMismatch  // ✅ NEW
    case invalidMessageNumber  // ✅ NEW
}

class DoubleRatchetSessionManager {
    static let shared = DoubleRatchetSessionManager()
    private var sessions: [String: DoubleRatchetSession] = [:]
    private let storageKey = "doubleRatchetSessions_v4"  // ✅ Bumped version
    private let queue = DispatchQueue(label: "com.app.ratchet.manager", qos: .userInitiated)
    
    init() {
        // Instrumented: whole-blob SecItemCopyMatching + a JSON decode of every
        // ratchet session. MessageManager now builds this lazily rather than during
        // @StateObject construction, but keep it visible in launch_timing.log.
        LaunchTimingLogger.measure("DoubleRatchetSessionManager.loadSessions") { loadSessions() }
        cleanupStaleSessions()
        Timer.scheduledTimer(withTimeInterval: 300, repeats: true) { [weak self] timer in
            self?.cleanupStaleSessions()
        }
    }
    
    private func cleanupStaleSessions() {
        queue.async {
            let staleKeys = self.sessions.filter { $0.value.isStale(maxAge: 7200) }.map { $0.key }
            for key in staleKeys {
                OshiLog.crypto.info("🧹 Removing stale session: \(key.prefix(20))...")
                self.sessions.removeValue(forKey: key)
            }
            if !staleKeys.isEmpty {
                self.saveSessions()
            }
        }
    }
    
    // ✅ CRITICAL: Create canonical session ID from both public keys
    private func makeCanonicalSessionId(publicKey1: String, publicKey2: String) -> String {
        let sorted = [publicKey1, publicKey2].sorted()
        let combined = sorted.joined(separator: ":")
        
        // Create deterministic session ID
        let hash = SHA256.hash(data: Data(combined.utf8))
        return hash.compactMap { String(format: "%02x", $0) }.joined()
    }
    
    // ✅ CRITICAL: Determine who is initiator based on lexicographic ordering
    private func isInitiator(ourKey: String, theirKey: String) -> Bool {
        return ourKey < theirKey
    }
    
    func getOrCreateSession(with theirPublicKey: String, ourPublicKey: String, sharedSecret: Data) -> DoubleRatchetSession {
        return queue.sync {
            // ✅ Use canonical session ID
            let sessionId = makeCanonicalSessionId(publicKey1: ourPublicKey, publicKey2: theirPublicKey)
            
            if let existing = sessions[sessionId] {
                // Check if session is stale
                if existing.isStale(maxAge: 7200) {
                    let age = Int(Date().timeIntervalSince(existing.createdAt))
                    OshiLog.crypto.info("   ⚠️ Stale session detected (age: \(age)s)")
                    OshiLog.crypto.info("   🔄 Removing stale session and creating fresh one")
                    sessions.removeValue(forKey: sessionId)
                } else {
                    OshiLog.crypto.info("   📖 Using existing session \(sessionId.prefix(16))... (\(existing.isInitiator ? "INITIATOR" : "RESPONDER"), send#\(existing.sendingMessageNumber) recv#\(existing.receivingMessageNumber))")
                    return existing
                }
            }
            
            // Create new session
            let rootKeySymmetric = HKDF<SHA256>.deriveKey(
                inputKeyMaterial: SymmetricKey(data: sharedSecret),
                salt: Data(),
                info: Data("RootKey".utf8),
                outputByteCount: 32
            )
            let rootKey = rootKeySymmetric.withUnsafeBytes { Data($0) }
            
            let initialChainSymmetric = HKDF<SHA256>.deriveKey(
                inputKeyMaterial: SymmetricKey(data: sharedSecret),
                salt: Data(),
                info: Data("InitialChain".utf8),
                outputByteCount: 32
            )
            let initialChain = initialChainSymmetric.withUnsafeBytes { Data($0) }
            
            // ✅ Determine role deterministically
            let weAreInitiator = isInitiator(ourKey: ourPublicKey, theirKey: theirPublicKey)
            
            let session = DoubleRatchetSession(
                rootKey: rootKey,
                sendingChainKey: weAreInitiator ? initialChain : Data(repeating: 0, count: 32),
                receivingChainKey: weAreInitiator ? Data(repeating: 0, count: 32) : initialChain,
                sessionId: sessionId,
                isInitiator: weAreInitiator
            )
            
            sessions[sessionId] = session
            // 🔴 WATCHDOG (0x8BADF00D): getOrCreateSession is reached synchronously from the
            // 1 Hz pending-decryption timer on the main runloop (MessageManager
            // checkPendingIPFSMessages -> retryAllPendingDecryption -> decryptMessage) and from
            // the QR "add contact" flow. Persisting inline meant the caller's thread paid for a
            // JSONEncoder pass over EVERY session plus a Keychain delete+add — two synchronous
            // securityd round-trips — inside this `queue.sync`. Hand the write to the same
            // serial queue instead: ordering is preserved (a serial queue drains in FIFO order,
            // so the write still lands before any later sync/async block sees the store) and the
            // caller returns as soon as the in-memory session exists.
            queue.async { [weak self] in self?.saveSessions() }

            let role = weAreInitiator ? "INITIATOR" : "RESPONDER"
            OshiLog.crypto.info("🆕 Created new Double Ratchet session")
            OshiLog.crypto.info("   Session ID: \(sessionId.prefix(16))...")
            OshiLog.crypto.info("   Role: \(role)")
            
            return session
        }
    }
    
    func getSession(with theirPublicKey: String, ourPublicKey: String) -> DoubleRatchetSession? {
        return queue.sync {
            let sessionId = makeCanonicalSessionId(publicKey1: ourPublicKey, publicKey2: theirPublicKey)
            return sessions[sessionId]
        }
    }
    
    func saveSession() {
        queue.async {
            self.saveSessions()
        }
    }
    
    private func saveSessions() {
        guard let encoded = try? JSONEncoder().encode(sessions) else { return }
        // Security (H-CRY-3): the serialized sessions include the root key, both chain keys,
        // skipped message keys and the ratchet private key — i.e. message-decryption secrets.
        // Persist them in the Keychain (ThisDeviceOnly, not iCloud-synced) instead of
        // UserDefaults, whose plist is captured in unencrypted local/iCloud backups.
        do {
            try KeychainHelper.save(key: storageKey, data: encoded)
            // Only purge the legacy plaintext copy once the Keychain write has succeeded — and
            // only if one is actually still there. 🔴 WATCHDOG (0x8BADF00D): saveSessions() runs
            // on the `com.app.ratchet.manager` serial queue, and `UserDefaults.removeObject`
            // posts `UserDefaults.didChangeNotification` synchronously on the calling thread; a
            // block observer bound to an OperationQueue parks the poster on
            // `-[NSOperation waitUntilFinished]` until the main runloop drains. With the main
            // thread simultaneously inside `queue.sync` on this same queue, that deadlocked the
            // app until FrontBoard killed it. Reading first makes the write a no-op after the
            // one-time migration. (See NotificationCenter+MainThread in NotificationManager.swift.)
            if UserDefaults.standard.object(forKey: storageKey) != nil {
                UserDefaults.standard.removeObject(forKey: storageKey)
            }
        } catch {
            OshiLog.crypto.warning("⚠️ Failed to persist ratchet sessions to Keychain: \(error.localizedDescription)")
        }
    }

    private func loadSessions() {
        // Prefer the Keychain.
        do {
            let data = try KeychainHelper.load(key: storageKey)
            if let decoded = try? JSONDecoder().decode([String: DoubleRatchetSession].self, from: data) {
                sessions = decoded
                OshiLog.crypto.info("✅ Loaded \(sessions.count) session(s) from Keychain")
            }
            return
        } catch KeychainError.locked {
            // Device not yet unlocked since boot. The secrets are still safely stored — do NOT
            // fall back to (or purge) anything. They will load once the device is unlocked.
            OshiLog.crypto.warning("⏳ Ratchet sessions unavailable: Keychain locked (pre-first-unlock)")
            return
        } catch {
            // itemNotFound (or other) — fall through to the one-time legacy migration below.
        }
        // Legacy fallback + one-time migration from the old UserDefaults store.
        guard let data = UserDefaults.standard.data(forKey: storageKey),
              let decoded = try? JSONDecoder().decode([String: DoubleRatchetSession].self, from: data) else {
            return
        }
        sessions = decoded
        OshiLog.crypto.info("✅ Loaded \(sessions.count) legacy session(s); migrating to Keychain")
        saveSessions() // migrate into the Keychain (purges the UserDefaults copy on success)
    }
    
    func resetSession(with theirPublicKey: String, ourPublicKey: String) {
        queue.sync {
            let sessionId = makeCanonicalSessionId(publicKey1: ourPublicKey, publicKey2: theirPublicKey)
            sessions.removeValue(forKey: sessionId)
            saveSessions()
            OshiLog.crypto.info("🔄 Reset session: \(sessionId.prefix(16))...")
        }
    }
    
    func resetAllSessions() {
        queue.sync {
            let count = sessions.count
            OshiLog.crypto.info("🔥 Resetting ALL sessions...")
            sessions.removeAll()
            UserDefaults.standard.removeObject(forKey: storageKey)
            UserDefaults.standard.synchronize()
            OshiLog.crypto.info("✅ Cleared \(count) session(s)")
        }
    }

    // MARK: - Identity-Scoped Session Archive

    /// Archive current ratchet sessions for a specific identity
    func archiveSessionsForIdentity(_ publicKey: String) {
        queue.sync {
            guard !publicKey.isEmpty, !sessions.isEmpty else { return }
            let safeKey = publicKey.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "+", with: "-")
            FileStorage.shared.save(sessions, forKey: "ratchet_identity_\(safeKey)")
            OshiLog.crypto.info("📦 Archived \(sessions.count) ratchet sessions for identity \(publicKey.prefix(12))...")
        }
    }

    /// Restore archived ratchet sessions for a specific identity
    func restoreSessionsForIdentity(_ publicKey: String) {
        queue.sync {
            guard !publicKey.isEmpty else { return }
            let safeKey = publicKey.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "+", with: "-")
            let restored: [String: DoubleRatchetSession] = FileStorage.shared.load(forKey: "ratchet_identity_\(safeKey)") ?? [:]
            if !restored.isEmpty {
                sessions = restored
                saveSessions()
                OshiLog.crypto.info("📥 Restored \(sessions.count) ratchet sessions for identity \(publicKey.prefix(12))...")
            } else {
                OshiLog.crypto.info("📭 No archived ratchet sessions for identity \(publicKey.prefix(12))...")
            }
        }
    }
    
    func getSessionCount() -> Int {
        return queue.sync {
            sessions.count
        }
    }
    
    func getAllSessionKeys() -> [String] {
        return queue.sync {
            Array(sessions.keys)
        }
    }
}
