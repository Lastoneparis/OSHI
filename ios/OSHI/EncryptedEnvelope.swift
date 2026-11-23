//
//  EncryptedEnvelope.swift
//  OSHI - Encrypted Metadata for IPFS
//
//  🔐 Security: ALL message metadata is encrypted before uploading to IPFS
//  
//  Before (visible on IPFS):
//  {
//    "type": "group_message",
//    "senderAddress": "abc123...",  ← EXPOSED!
//    "timestamp": 1234567890,        ← EXPOSED!
//    "hasMedia": true,               ← EXPOSED!
//    "encryptedContent": "..."
//  }
//
//  After (encrypted envelope):
//  {
//    "v": 2,
//    "envelope": "base64_encrypted_blob",  ← Everything hidden!
//    "hint": "a1b2"                         ← Optional routing hint (4 chars)
//  }
//

import Foundation
import CryptoKit

// MARK: - Encrypted Envelope

struct EncryptedEnvelope: Codable {
    let v: Int                    // Version (for future compatibility)
    let envelope: String          // Base64 encrypted payload
    let hint: String?             // Optional: First 4 chars of recipient key (for debugging only)
    let nonce: String             // Nonce for decryption
    
    static let currentVersion = 2
}

// MARK: - Original Payload (what gets encrypted)

struct MessagePayload: Codable {
    let type: String              // "message", "group_message", "delivery_receipt"
    let id: String                // Message UUID
    let groupId: String?          // For group messages
    let senderKey: String         // Sender public key
    let recipientKeys: [String]?  // For group messages
    let timestamp: Date
    let content: String           // Encrypted message content (Double Ratchet)
    let hasMedia: Bool
    let mediaType: String?
    let ipfsHash: String?         // For receipts
    let chainIndex: Int?          // Message chain index
}

// MARK: - Envelope Crypto Manager

class EnvelopeCrypto {

    // MARK: - Flexible Date Decoder
    // Server sends ISO8601 strings, iOS encodes as Double — need to handle both
    private static func makeDecoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            // Try ISO8601 string first (from server/bot messages)
            if let dateString = try? container.decode(String.self) {
                let fmt = ISO8601DateFormatter()
                fmt.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
                if let date = fmt.date(from: dateString) { return date }
                fmt.formatOptions = [.withInternetDateTime]
                if let date = fmt.date(from: dateString) { return date }
            }
            // Fall back to Double (timeIntervalSinceReferenceDate — iOS default)
            if let ts = try? container.decode(Double.self) {
                return Date(timeIntervalSinceReferenceDate: ts)
            }
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Invalid date format")
        }
        return decoder
    }

    // MARK: - Encrypt for Single Recipient (1-on-1)
    
    /// Encrypt payload using shared secret between sender and recipient
    static func encrypt(
        payload: MessagePayload,
        sharedSecret: Data
    ) throws -> EncryptedEnvelope {
        // Derive encryption key from shared secret
        let key = deriveKey(from: sharedSecret, salt: "oshi-envelope-v2")
        
        // Encode payload
        let payloadData = try JSONEncoder().encode(payload)
        
        // Encrypt with AES-GCM
        let nonce = AES.GCM.Nonce()
        let sealed = try AES.GCM.seal(payloadData, using: key, nonce: nonce)
        
        // Combine ciphertext + tag
        var combined = Data()
        combined.append(sealed.ciphertext)
        combined.append(sealed.tag)
        
        return EncryptedEnvelope(
            v: EncryptedEnvelope.currentVersion,
            envelope: combined.base64EncodedString(),
            hint: nil, // No hint for 1-on-1 (recipient knows)
            nonce: Data(nonce).base64EncodedString()
        )
    }
    
    // MARK: - Encrypt for Group
    
    /// Encrypt payload using group-derived key
    static func encryptForGroup(
        payload: MessagePayload,
        groupKey: Data
    ) throws -> EncryptedEnvelope {
        // Derive encryption key from group key
        let key = deriveKey(from: groupKey, salt: "oshi-group-envelope-v2")
        
        // Encode payload
        let payloadData = try JSONEncoder().encode(payload)
        
        // Encrypt with AES-GCM
        let nonce = AES.GCM.Nonce()
        let sealed = try AES.GCM.seal(payloadData, using: key, nonce: nonce)
        
        // Combine ciphertext + tag
        var combined = Data()
        combined.append(sealed.ciphertext)
        combined.append(sealed.tag)
        
        // Use first 4 chars of group ID as hint (for debugging)
        let hint = payload.groupId?.prefix(4).description
        
        return EncryptedEnvelope(
            v: EncryptedEnvelope.currentVersion,
            envelope: combined.base64EncodedString(),
            hint: hint,
            nonce: Data(nonce).base64EncodedString()
        )
    }
    
    // MARK: - Decrypt
    
    /// Decrypt envelope using shared secret
    static func decrypt(
        envelope: EncryptedEnvelope,
        sharedSecret: Data
    ) throws -> MessagePayload {
        // Check version
        guard envelope.v == EncryptedEnvelope.currentVersion else {
            throw EnvelopeError.unsupportedVersion(envelope.v)
        }
        
        // Derive key
        let key = deriveKey(from: sharedSecret, salt: "oshi-envelope-v2")

        // Decode components
        guard let combined = Data(base64Encoded: envelope.envelope),
              let nonceData = Data(base64Encoded: envelope.nonce) else {
            throw EnvelopeError.invalidData
        }

        guard combined.count >= 16 else {
            throw EnvelopeError.invalidData
        }

        // Extract ciphertext and tag
        let ciphertext = combined.dropLast(16)
        let tag = combined.suffix(16)
        
        // Decrypt
        let nonce = try AES.GCM.Nonce(data: nonceData)
        let sealedBox = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
        let payloadData = try AES.GCM.open(sealedBox, using: key)
        
        return try makeDecoder().decode(MessagePayload.self, from: payloadData)
    }
    
    /// Decrypt group envelope using group key
    static func decryptGroup(
        envelope: EncryptedEnvelope,
        groupKey: Data
    ) throws -> MessagePayload {
        // Check version
        guard envelope.v == EncryptedEnvelope.currentVersion else {
            throw EnvelopeError.unsupportedVersion(envelope.v)
        }
        
        // Derive key
        let key = deriveKey(from: groupKey, salt: "oshi-group-envelope-v2")

        // Decode components
        guard let combined = Data(base64Encoded: envelope.envelope),
              let nonceData = Data(base64Encoded: envelope.nonce) else {
            throw EnvelopeError.invalidData
        }

        guard combined.count >= 16 else {
            throw EnvelopeError.invalidData
        }

        // Extract ciphertext and tag
        let ciphertext = combined.dropLast(16)
        let tag = combined.suffix(16)
        
        // Decrypt
        let nonce = try AES.GCM.Nonce(data: nonceData)
        let sealedBox = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
        let payloadData = try AES.GCM.open(sealedBox, using: key)
        
        return try makeDecoder().decode(MessagePayload.self, from: payloadData)
    }
    
    // MARK: - Key Derivation
    
    private static func deriveKey(from secret: Data, salt: String) -> SymmetricKey {
        // Use HKDF to derive a proper AES key
        let saltData = Data(salt.utf8)
        let info = Data("envelope-key".utf8)
        
        // Simple HKDF-like derivation using SHA256
        var input = Data()
        input.append(secret)
        input.append(saltData)
        input.append(info)
        
        let hash = SHA256.hash(data: input)
        return SymmetricKey(data: hash.withUnsafeBytes { Data($0) })
    }
    
    // MARK: - Detect Envelope Version
    
    /// Check if data is an encrypted envelope (v2) or legacy format (v1)
    static func isEncryptedEnvelope(_ data: Data) -> Bool {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let version = json["v"] as? Int else {
            return false
        }
        return version >= 2
    }
    
    /// Try to detect message type from legacy format
    static func detectLegacyType(_ data: Data) -> String? {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return json["type"] as? String
    }
}

// MARK: - Errors

enum EnvelopeError: Error, LocalizedError {
    case unsupportedVersion(Int)
    case invalidData
    case decryptionFailed
    case missingGroupKey
    
    var errorDescription: String? {
        switch self {
        case .unsupportedVersion(let v):
            return "Unsupported envelope version: \(v)"
        case .invalidData:
            return "Invalid envelope data"
        case .decryptionFailed:
            return "Failed to decrypt envelope"
        case .missingGroupKey:
            return "Group key not found"
        }
    }
}

// MARK: - Convenience Extensions

extension MessagePayload {
    /// Create payload for a 1-on-1 message
    static func forMessage(
        id: String,
        senderKey: String,
        content: String,
        hasMedia: Bool = false,
        mediaType: String? = nil
    ) -> MessagePayload {
        MessagePayload(
            type: "message",
            id: id,
            groupId: nil,
            senderKey: senderKey,
            recipientKeys: nil,
            timestamp: Date(),
            content: content,
            hasMedia: hasMedia,
            mediaType: mediaType,
            ipfsHash: nil,
            chainIndex: nil
        )
    }
    
    /// Create payload for a group message
    static func forGroupMessage(
        id: String,
        groupId: String,
        senderKey: String,
        recipientKeys: [String],
        content: String,
        chainIndex: Int,
        hasMedia: Bool = false,
        mediaType: String? = nil
    ) -> MessagePayload {
        MessagePayload(
            type: "group_message",
            id: id,
            groupId: groupId,
            senderKey: senderKey,
            recipientKeys: recipientKeys,
            timestamp: Date(),
            content: content,
            hasMedia: hasMedia,
            mediaType: mediaType,
            ipfsHash: nil,
            chainIndex: chainIndex
        )
    }
    
    /// Create payload for a delivery receipt
    static func forReceipt(
        messageId: String,
        senderKey: String,
        ipfsHash: String
    ) -> MessagePayload {
        MessagePayload(
            type: "delivery_receipt",
            id: UUID().uuidString,
            groupId: nil,
            senderKey: senderKey,
            recipientKeys: nil,
            timestamp: Date(),
            content: "",
            hasMedia: false,
            mediaType: nil,
            ipfsHash: ipfsHash,
            chainIndex: nil
        )
    }
}

/*
 ╔════════════════════════════════════════════════════════════════╗
 ║  ENCRYPTED ENVELOPE - SECURITY ANALYSIS                       ║
 ╚════════════════════════════════════════════════════════════════╝
 
 WHAT'S VISIBLE ON IPFS:
 ┌─────────────────────────────────────────────────────────────┐
 │  {                                                          │
 │    "v": 2,                    ← Version only                │
 │    "envelope": "aGVsbG8...",  ← Encrypted blob              │
 │    "hint": "a1b2",            ← Optional 4-char hint        │
 │    "nonce": "xyz123..."       ← Random nonce                │
 │  }                                                          │
 └─────────────────────────────────────────────────────────────┘
 
 WHAT'S HIDDEN (encrypted inside):
 ┌─────────────────────────────────────────────────────────────┐
 │  - Message type (message/group/receipt)                     │
 │  - Sender public key                                        │
 │  - Recipient public keys                                    │
 │  - Timestamp                                                │
 │  - Group ID                                                 │
 │  - Whether it has media                                     │
 │  - Media type                                               │
 │  - The actual encrypted content                             │
 └─────────────────────────────────────────────────────────────┘
 
 STILL VISIBLE (unavoidable):
 - That a message exists (IPFS CID)
 - Approximate size of message
 - Upload time (Pinata metadata)
 - VPS queue recipient (needed for routing)
 
 ENCRYPTION:
 - AES-256-GCM (authenticated encryption)
 - Key derived from shared secret via HKDF-like function
 - Unique nonce per message
 - Authentication tag prevents tampering
 
 */
