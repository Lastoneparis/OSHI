//
//  WalletManager.swift
//  Handles Web3 wallet connection and cryptographic operations
//

import Foundation
import CryptoKit
import Combine

class WalletManager: ObservableObject {
    @Published var isConnected: Bool = false
    @Published var walletAddress: String = ""
    @Published var publicKey: String = ""
    
    internal var privateKey: Curve25519.KeyAgreement.PrivateKey?
    private var signingPrivateKey: Curve25519.Signing.PrivateKey?
    
    init() {
        loadStoredWallet()
    }
    
    // MARK: - Wallet Connection
    
    func connectWallet(seedPhrase: String? = nil) {
        // In production, integrate WalletConnect or similar
        // For now, we generate a keypair from seed or create new
        
        if let seed = seedPhrase, !seed.isEmpty {
            // Derive keys from seed phrase
            generateKeysFromSeed(seed)
        } else {
            // Generate new keys
            generateNewKeys()
        }
        
        isConnected = true
        saveWallet()
    }
    
    func disconnectWallet() {
        isConnected = false
        walletAddress = ""
        publicKey = ""
        privateKey = nil
        signingPrivateKey = nil
        clearStoredWallet()
    }
    
    // MARK: - Key Generation
    
    private func generateNewKeys() {
        // Generate encryption keypair
        let encryptionKey = Curve25519.KeyAgreement.PrivateKey()
        privateKey = encryptionKey
        
        // Generate signing keypair
        let signKey = Curve25519.Signing.PrivateKey()
        signingPrivateKey = signKey
        
        // Create wallet address from public key (Ethereum-style)
        let pubKeyData = encryptionKey.publicKey.rawRepresentation
        walletAddress = "0x" + pubKeyData.prefix(20).map { String(format: "%02x", $0) }.joined()
        
        publicKey = encryptionKey.publicKey.rawRepresentation.base64EncodedString()
    }
    
    private func generateKeysFromSeed(_ seed: String) {
        // Hash the seed to get deterministic keys
        let seedData = Data(seed.utf8)
        let hash = SHA256.hash(data: seedData)
        
        do {
            // Use hash as private key material
            privateKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: hash)
            
            // Create signing key from hash
            let signingHash = SHA256.hash(data: hash + seedData)
            signingPrivateKey = try Curve25519.Signing.PrivateKey(rawRepresentation: signingHash)
            
            let pubKeyData = privateKey!.publicKey.rawRepresentation
            walletAddress = "0x" + pubKeyData.prefix(20).map { String(format: "%02x", $0) }.joined()
            publicKey = privateKey!.publicKey.rawRepresentation.base64EncodedString()
        } catch {
            print("Error generating keys from seed: \(error)")
            generateNewKeys()
        }
    }
    
    // MARK: - Encryption/Decryption
    
    func encrypt(message: String, recipientPublicKey: String) throws -> EncryptedMessage {
        guard let privateKey = privateKey else {
            throw CryptoError.noPrivateKey
        }
        
        // Handle both wallet address (0x...) and public key formats
        var pubKeyToUse = recipientPublicKey
        
        // If it's a wallet address (starts with 0x), we can't encrypt with it
        // This is a limitation - we need the actual public key
        if recipientPublicKey.hasPrefix("0x") {
            // Try to look up the public key from stored peers
            // For now, throw a helpful error
            throw CryptoError.invalidPublicKey
        }
        
        // Remove any whitespace
        pubKeyToUse = pubKeyToUse.trimmingCharacters(in: .whitespacesAndNewlines)
        
        // Decode recipient's public key
        guard let recipientPubKeyData = Data(base64Encoded: pubKeyToUse),
              let recipientPubKey = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: recipientPubKeyData) else {
            throw CryptoError.invalidPublicKey
        }
        
        // Generate shared secret
        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: recipientPubKey)
        
        // Derive symmetric key from shared secret
        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: Data(),
            outputByteCount: 32
        )
        
        // Encrypt the message
        let messageData = Data(message.utf8)
        let sealedBox = try AES.GCM.seal(messageData, using: symmetricKey)
        
        // Sign the encrypted message
        guard let signingKey = signingPrivateKey else {
            throw CryptoError.noSigningKey
        }
        
        let signature = try signingKey.signature(for: sealedBox.combined!)
        
        return EncryptedMessage(
            ciphertext: sealedBox.combined!.base64EncodedString(),
            signature: signature.base64EncodedString(),
            senderPublicKey: self.publicKey,
            timestamp: Date()
        )
    }
    
    func decrypt(encryptedMessage: EncryptedMessage) throws -> String {
        guard let privateKey = privateKey else {
            throw CryptoError.noPrivateKey
        }
        
        // Decode sender's public key
        guard let senderPubKeyData = Data(base64Encoded: encryptedMessage.senderPublicKey),
              let senderPubKey = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: senderPubKeyData) else {
            throw CryptoError.invalidPublicKey
        }
        
        // Load encrypted message data
        guard let ciphertextData = Data(base64Encoded: encryptedMessage.ciphertext) else {
            throw CryptoError.invalidData
        }
        
        // Generate shared secret
        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: senderPubKey)
        
        // Derive symmetric key
        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: Data(),
            outputByteCount: 32
        )
        
        // Decrypt the message
        let sealedBox = try AES.GCM.SealedBox(combined: ciphertextData)
        let decryptedData = try AES.GCM.open(sealedBox, using: symmetricKey)
        
        guard let message = String(data: decryptedData, encoding: .utf8) else {
            throw CryptoError.decryptionFailed
        }
        
        return message
    }
    
    // MARK: - Double Ratchet Support
    
    func computeSharedSecret(with recipientPublicKey: String) throws -> Data {
        guard let privateKey = privateKey else {
            throw CryptoError.noPrivateKey
        }
        
        // Convert recipient public key to Data
        let recipientKeyData: Data
        if recipientPublicKey.hasPrefix("0x") {
            let hex = String(recipientPublicKey.dropFirst(2))
            recipientKeyData = Data(hex: hex)
        } else {
            recipientKeyData = Data(base64Encoded: recipientPublicKey) ?? Data()
        }
        
        guard !recipientKeyData.isEmpty else {
            throw CryptoError.invalidPublicKey
        }
        
        let recipientKey = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: recipientKeyData)
        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: recipientKey)
        
        return sharedSecret.withUnsafeBytes { Data($0) }
    }
    
    func wrapRatchetMessage(_ ratchetData: Data, for recipientPublicKey: String) throws -> EncryptedMessage {
        let base64Ratchet = ratchetData.base64EncodedString()
        return try encrypt(message: base64Ratchet, recipientPublicKey: recipientPublicKey)
    }
    
    func unwrapRatchetMessage(_ encryptedMessage: EncryptedMessage) throws -> Data {
        let decrypted = try decrypt(encryptedMessage: encryptedMessage)
        guard let data = Data(base64Encoded: decrypted) else {
            throw CryptoError.invalidData
        }
        return data
    }
    
    // MARK: - Persistence
    
    private func saveWallet() {
        UserDefaults.standard.set(walletAddress, forKey: "walletAddress")
        UserDefaults.standard.set(publicKey, forKey: "publicKey")
        
        if let privateKey = privateKey {
            let keyData = privateKey.rawRepresentation
            try? KeychainHelper.save(key: "privateKey", data: keyData)
        }
        
        if let signingKey = signingPrivateKey {
            let keyData = signingKey.rawRepresentation
            try? KeychainHelper.save(key: "signingKey", data: keyData)
        }
    }
    
    private func loadStoredWallet() {
        guard let address = UserDefaults.standard.string(forKey: "walletAddress"),
              let pubKey = UserDefaults.standard.string(forKey: "publicKey"),
              let privKeyData = try? KeychainHelper.load(key: "privateKey"),
              let signKeyData = try? KeychainHelper.load(key: "signingKey") else {
            return
        }
        
        do {
            privateKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privKeyData)
            signingPrivateKey = try Curve25519.Signing.PrivateKey(rawRepresentation: signKeyData)
            walletAddress = address
            publicKey = pubKey
            isConnected = true
        } catch {
            print("Error loading stored wallet: \(error)")
        }
    }
    
    private func clearStoredWallet() {
        UserDefaults.standard.removeObject(forKey: "walletAddress")
        UserDefaults.standard.removeObject(forKey: "publicKey")
        try? KeychainHelper.delete(key: "privateKey")
        try? KeychainHelper.delete(key: "signingKey")
    }
}

// MARK: - Models

struct EncryptedMessage: Codable {
    let ciphertext: String
    let signature: String
    let senderPublicKey: String
    let timestamp: Date
}

enum CryptoError: LocalizedError {
    case noPrivateKey
    case noSigningKey
    case invalidPublicKey
    case invalidData
    case decryptionFailed
    
    var errorDescription: String? {
        switch self {
        case .noPrivateKey: return "No private key available"
        case .noSigningKey: return "No signing key available"
        case .invalidPublicKey: return "Invalid public key format"
        case .invalidData: return "Invalid encrypted data"
        case .decryptionFailed: return "Failed to decrypt message"
        }
    }
}

// MARK: - Extensions

extension Data {
    init(hex: String) {
        var data = Data()
        var hex = hex
        
        if hex.hasPrefix("0x") {
            hex = String(hex.dropFirst(2))
        }
        
        if hex.count % 2 != 0 {
            hex = "0" + hex
        }
        
        var index = hex.startIndex
        while index < hex.endIndex {
            let nextIndex = hex.index(index, offsetBy: 2)
            if let byte = UInt8(hex[index..<nextIndex], radix: 16) {
                data.append(byte)
            }
            index = nextIndex
        }
        
        self = data
    }
}
