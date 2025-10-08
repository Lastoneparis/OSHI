//
//  DoubleRatchet.swift
//  SAFE VERSION - No force unwraps, proper error handling
//

import Foundation
import CryptoKit

// MARK: - Double Ratchet Session

class DoubleRatchetSession: Codable {
    private(set) var rootKey: Data
    private(set) var sendingChainKey: Data
    private(set) var sendingMessageNumber: UInt32 = 0
    private(set) var previousSendingChainLength: UInt32 = 0
    private(set) var receivingChainKey: Data
    private(set) var receivingMessageNumber: UInt32 = 0
    private(set) var ourRatchetKeyPair: Curve25519.KeyAgreement.PrivateKey
    private(set) var theirRatchetPublicKey: Data?
    
    internal private(set) var skippedMessageKeys: [String: SkippedKey] = [:]
    private let maxSkippedMessages: Int = 200
    private var needsDHRatchet: Bool = false
    
    private let sessionQueue = DispatchQueue(label: "com.app.ratchet.session", qos: .userInitiated)
    
    struct SkippedKey: Codable {
        let key: Data
        let timestamp: Date
    }
    
    enum CodingKeys: String, CodingKey {
        case rootKey, sendingChainKey, sendingMessageNumber, previousSendingChainLength
        case receivingChainKey, receivingMessageNumber
        case ourRatchetKeyData, theirRatchetPublicKey, skippedMessageKeys
    }
    
    init(rootKey: Data, sendingChainKey: Data, receivingChainKey: Data) {
        self.rootKey = rootKey
        self.sendingChainKey = sendingChainKey
        self.receivingChainKey = receivingChainKey
        self.ourRatchetKeyPair = Curve25519.KeyAgreement.PrivateKey()
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
    }
    
    // MARK: - Encrypt (Thread-Safe)
    
    func encrypt(_ plaintext: String) throws -> DoubleRatchetMessage {
        return try sessionQueue.sync {
            try _encrypt(plaintext)
        }
    }
    
    private func _encrypt(_ plaintext: String) throws -> DoubleRatchetMessage {
        let ourPubKey = ourRatchetKeyPair.publicKey.rawRepresentation
        
        print("🔐 Encrypt:")
        print("   send#\(sendingMessageNumber) prevChainLen=\(previousSendingChainLength)")
        print("   ourRatchetPub=\(ourPubKey.base64EncodedString().prefix(20))...")
        print("   recv#\(receivingMessageNumber)")
        
        // Derive message key
        let messageKey = deriveMessageKey(from: sendingChainKey)
        
        // Create header
        let header = DoubleRatchetMessage.MessageHeader(
            publicKey: ourPubKey,
            messageNumber: sendingMessageNumber,
            previousChainLength: previousSendingChainLength,
            isDHRatchet: needsDHRatchet
        )
        
        // ✅ Authenticate header as AEAD associated data
        let headerData = try JSONEncoder().encode(header)
        let ciphertext = try encryptWithKey(plaintext, key: messageKey, authenticating: headerData)
        
        let message = DoubleRatchetMessage(
            header: header,
            ciphertext: ciphertext
        )
        
        // Advance sending chain
        sendingChainKey = advanceChainKey(sendingChainKey)
        sendingMessageNumber += 1
        needsDHRatchet = false
        
        // Save session after successful encryption
        DoubleRatchetSessionManager.shared.saveSession()
        
        print("   ✅ Encrypted as msg#\(sendingMessageNumber - 1)")
        
        return message
    }
    
    // MARK: - Decrypt (Thread-Safe)
    
    func decrypt(_ message: DoubleRatchetMessage) throws -> String {
        return try sessionQueue.sync {
            try _decrypt(message)
        }
    }
    
    private func _decrypt(_ message: DoubleRatchetMessage) throws -> String {
        let hdrPubB64 = message.header.publicKey.base64EncodedString()
        
        print("🔓 Decrypt:")
        print("   incoming msg#\(message.header.messageNumber) prevChainLen=\(message.header.previousChainLength)")
        print("   hdrPub=\(hdrPubB64.prefix(20))...")
        print("   current theirRatchetPub=\(theirRatchetPublicKey?.base64EncodedString().prefix(20) ?? "nil")...")
        print("   recv#\(receivingMessageNumber) send#\(sendingMessageNumber)")
        print("   isDHRatchet=\(message.header.isDHRatchet)")
        
        // Create unique key for skipped messages
        let skipKey = "\(hdrPubB64):\(message.header.messageNumber)"
        
        // Check if we have a skipped key for this message
        if let skippedKey = skippedMessageKeys[skipKey] {
            print("   🔑 Using skipped key for \(skipKey)")
            
            // ✅ Authenticate header
            let headerData = try JSONEncoder().encode(message.header)
            let plaintext = try decryptWithKey(message.ciphertext, key: skippedKey.key, authenticating: headerData)
            
            skippedMessageKeys.removeValue(forKey: skipKey)
            DoubleRatchetSessionManager.shared.saveSession()
            print("   ✅ Decrypted with skipped key")
            return plaintext
        }
        
        // Check if message is from old chain
        if let theirPub = theirRatchetPublicKey,
           message.header.publicKey == theirPub,
           message.header.messageNumber < receivingMessageNumber {
            print("   ⚠️ Old message #\(message.header.messageNumber) (current: #\(receivingMessageNumber))")
            throw DoubleRatchetError.oldMessage
        }
        
        // Save state for rollback
        let savedState = saveState()
        
        do {
            // Check if we need to perform DH ratchet
            let needsRatchet = (theirRatchetPublicKey != message.header.publicKey) || message.header.isDHRatchet
            
            if needsRatchet {
                print("   🔄 Performing DH ratchet (new public key or flag set)")
                try performDHRatchet(newPublicKey: message.header.publicKey)
            }
            
            // Skip messages if needed
            if message.header.messageNumber > receivingMessageNumber {
                let gap = message.header.messageNumber - receivingMessageNumber
                print("   ⏭️ Skipping \(gap) message(s) from #\(receivingMessageNumber) to #\(message.header.messageNumber)")
                try skipMessageKeys(
                    until: message.header.messageNumber,
                    chainPublicKey: message.header.publicKey
                )
            }
            
            // Derive message key
            let messageKey = deriveMessageKey(from: receivingChainKey)
            
            // ✅ Authenticate header
            let headerData = try JSONEncoder().encode(message.header)
            let plaintext = try decryptWithKey(message.ciphertext, key: messageKey, authenticating: headerData)
            
            // Only advance state after successful decryption
            receivingChainKey = advanceChainKey(receivingChainKey)
            receivingMessageNumber += 1
            
            print("   ✅ Decrypted successfully, state advanced to recv#\(receivingMessageNumber)")
            
            // Save session after successful decryption
            DoubleRatchetSessionManager.shared.saveSession()
            
            return plaintext
            
        } catch {
            // Rollback state on failure
            print("   ❌ Decryption failed, rolling back state: \(error)")
            restoreState(savedState)
            throw error
        }
    }
    
    // MARK: - State Management
    
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
    
    // MARK: - DH Ratchet (Atomic)
    
    private func performDHRatchet(newPublicKey: Data) throws {
        print("   performDHRatchet: newPub=\(newPublicKey.base64EncodedString().prefix(20))...")
        
        let theirPub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: newPublicKey)
        
        // 1) DH(ourPriv, theirNewPub) -> new root & receiving chain
        let shared1 = try ourRatchetKeyPair.sharedSecretFromKeyAgreement(with: theirPub)
        let (rk1, recvChain) = deriveRootKeys(rootKey: rootKey, dhOutput: shared1)
        
        // 2) Generate new our ratchet key
        let newOurRatchet = Curve25519.KeyAgreement.PrivateKey()
        
        // 3) DH(newOurPriv, theirNewPub) -> new root & sending chain
        let shared2 = try newOurRatchet.sharedSecretFromKeyAgreement(with: theirPub)
        let (rk2, sendChain) = deriveRootKeys(rootKey: rk1, dhOutput: shared2)
        
        // 4) Atomically commit all changes
        previousSendingChainLength = sendingMessageNumber
        rootKey = rk2
        receivingChainKey = recvChain
        receivingMessageNumber = 0
        sendingChainKey = sendChain
        sendingMessageNumber = 0
        ourRatchetKeyPair = newOurRatchet
        theirRatchetPublicKey = newPublicKey
        needsDHRatchet = true
        
        print("   ✅ DH ratchet complete, prevSendChainLen=\(previousSendingChainLength)")
        print("   new rootKey prefix: \(rootKey.prefix(8).base64EncodedString())")
    }
    
    // MARK: - Skip Messages
    
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
            
            skippedMessageKeys[skipKey] = SkippedKey(key: messageKey, timestamp: now)
            
            print("      skip: generating skip-key for \(skipKey)")
            
            receivingChainKey = advanceChainKey(receivingChainKey)
            receivingMessageNumber += 1
        }
    }
    
    // MARK: - Key Derivation (HKDF with proper domain separation)
    
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
    
    // MARK: - Encryption/Decryption (AEAD with header authentication) - ✅ SAFE VERSION
    
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
        
        // ✅ SAFE: No force unwrap
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
        
        // ✅ SAFE: No force unwrap
        guard let plaintext = String(data: data, encoding: .utf8) else {
            throw DoubleRatchetError.decryptionFailed
        }
        
        return plaintext
    }
    
    // MARK: - Maintenance
    
    func cleanupOldSkippedKeys(olderThan days: Int = 7) {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            
            let cutoffDate = Date().addingTimeInterval(-Double(days * 24 * 60 * 60))
            let keysToRemove = self.skippedMessageKeys.filter { $0.value.timestamp < cutoffDate }.map { $0.key }
            
            keysToRemove.forEach { self.skippedMessageKeys.removeValue(forKey: $0) }
            
            if !keysToRemove.isEmpty {
                print("   🧹 Cleaned up \(keysToRemove.count) old skipped keys")
                DoubleRatchetSessionManager.shared.saveSession()
            }
        }
    }
}

// MARK: - Message Structure

struct DoubleRatchetMessage: Codable {
    let header: MessageHeader
    let ciphertext: Data
    
    struct MessageHeader: Codable {
        let publicKey: Data
        let messageNumber: UInt32
        let previousChainLength: UInt32
        let isDHRatchet: Bool
    }
}

// MARK: - Errors

enum DoubleRatchetError: Error {
    case tooManySkippedMessages
    case decryptionFailed
    case encryptionFailed  // ✅ Added
    case oldMessage
    case invalidPublicKey
}

// MARK: - Session Manager

class DoubleRatchetSessionManager {
    static let shared = DoubleRatchetSessionManager()
    private var sessions: [String: DoubleRatchetSession] = [:]
    private let storageKey = "doubleRatchetSessions_v3"
    private let queue = DispatchQueue(label: "com.app.ratchet.manager", qos: .userInitiated)
    
    init() {
        loadSessions()
        
        Timer.scheduledTimer(withTimeInterval: 3600, repeats: true) { [weak self] _ in
            self?.cleanupAllSessions()
        }
    }
    
    // ✅ CRITICAL: Initiator/Responder role handling
    func getOrCreateSession(with publicKey: String, sharedSecret: Data, isInitiator: Bool) -> DoubleRatchetSession {
        return queue.sync {
            if let existing = sessions[publicKey] {
                print("   📖 Using existing Double Ratchet session")
                print("      Send: #\(existing.sendingMessageNumber), Recv: #\(existing.receivingMessageNumber)")
                print("      Skipped keys: \(existing.skippedMessageKeys.count)")
                return existing
            }
            
            let rootKeySymmetric = HKDF<SHA256>.deriveKey(
                inputKeyMaterial: SymmetricKey(data: sharedSecret),
                salt: Data(),
                info: Data("RootKey".utf8),
                outputByteCount: 32
            )
            let rootKey = rootKeySymmetric.withUnsafeBytes { Data($0) }
            
            let sendChainSymmetric = HKDF<SHA256>.deriveKey(
                inputKeyMaterial: SymmetricKey(data: sharedSecret),
                salt: Data(),
                info: Data("SendChain".utf8),
                outputByteCount: 32
            )
            let sendChain = sendChainSymmetric.withUnsafeBytes { Data($0) }
            
            let recvChainSymmetric = HKDF<SHA256>.deriveKey(
                inputKeyMaterial: SymmetricKey(data: sharedSecret),
                salt: Data(),
                info: Data("RecvChain".utf8),
                outputByteCount: 32
            )
            let recvChain = recvChainSymmetric.withUnsafeBytes { Data($0) }
            
            let session = DoubleRatchetSession(
                rootKey: rootKey,
                sendingChainKey: isInitiator ? sendChain : recvChain,
                receivingChainKey: isInitiator ? recvChain : sendChain
            )
            
            sessions[publicKey] = session
            saveSessions()
            
            let role = isInitiator ? "INITIATOR" : "RESPONDER"
            print("🔐 Created new Double Ratchet session as \(role) with \(publicKey.prefix(8))...")
            
            return session
        }
    }
    
    func getSession(for publicKey: String) -> DoubleRatchetSession? {
        return queue.sync {
            sessions[publicKey]
        }
    }
    
    func saveSession() {
        queue.async {
            self.saveSessions()
        }
    }
    
    private func saveSessions() {
        if let encoded = try? JSONEncoder().encode(sessions) {
            UserDefaults.standard.set(encoded, forKey: storageKey)
        }
    }
    
    private func loadSessions() {
        guard let data = UserDefaults.standard.data(forKey: storageKey),
              let decoded = try? JSONDecoder().decode([String: DoubleRatchetSession].self, from: data) else {
            return
        }
        sessions = decoded
        print("✅ Loaded \(sessions.count) Double Ratchet session(s)")
    }
    
    func resetSession(for publicKey: String) {
        queue.sync {
            sessions.removeValue(forKey: publicKey)
            saveSessions()
            print("🔄 Reset Double Ratchet session for \(publicKey.prefix(8))...")
        }
    }
    
    func resetAllSessions() {
        queue.sync {
            sessions.removeAll()
            saveSessions()
            print("🔄 Reset all Double Ratchet sessions")
        }
    }
    
    private func cleanupAllSessions() {
        queue.async {
            for (_, session) in self.sessions {
                session.cleanupOldSkippedKeys()
            }
        }
    }
}
