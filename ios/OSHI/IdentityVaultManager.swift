//
//  IdentityVaultManager.swift
//  OSHI
//
//  Identity vault: stores all identities in Keychain for recovery across reinstalls.
//

import Foundation
import CryptoKit

struct StoredIdentity: Codable, Identifiable {
    let id: UUID
    let publicKey: String
    let encryptionKeyData: Data  // 32 bytes
    let signingKeyData: Data     // 32 bytes
    let createdAt: Date
    let savedAt: Date
    var nickname: String?
}

class IdentityVaultManager {
    static let shared = IdentityVaultManager()
    private let keychainKey = "oshi_identity_vault"

    private init() {}

    // MARK: - Vault CRUD

    func loadVault() -> [StoredIdentity] {
        guard let data = try? KeychainHelper.load(key: keychainKey),
              let identities = try? JSONDecoder().decode([StoredIdentity].self, from: data) else {
            return []
        }
        return identities
    }

    private func saveVault(_ identities: [StoredIdentity]) {
        guard let data = try? JSONEncoder().encode(identities) else { return }
        try? KeychainHelper.save(key: keychainKey, data: data)
    }

    /// Auto-save current identity if not already in vault. Call after every identity creation.
    func autoSaveIfNew(identityManager: IdentityManager) {
        let currentPubKey = identityManager.publicKey
        guard !currentPubKey.isEmpty,
              let _ = identityManager.privateKey,
              let recoveryKey = identityManager.exportRecoveryKey(),
              let rawData = Data(base64Encoded: recoveryKey), rawData.count == 64 else { return }

        var vault = loadVault()

        // Skip if already saved
        if vault.contains(where: { $0.publicKey == currentPubKey }) { return }

        let identity = StoredIdentity(
            id: UUID(),
            publicKey: currentPubKey,
            encryptionKeyData: rawData.prefix(32),
            signingKeyData: rawData.suffix(32),
            createdAt: identityManager.getKeyCreationDate(),
            savedAt: Date(),
            nickname: nil
        )
        vault.append(identity)
        saveVault(vault)
        OshiLog.crypto.info("🔐 Identity auto-saved to vault: \(currentPubKey.prefix(12))...")
    }

    func deleteIdentity(id: UUID) {
        var vault = loadVault()
        vault.removeAll { $0.id == id }
        saveVault(vault)
    }

    func updateNickname(id: UUID, nickname: String?) {
        var vault = loadVault()
        if let index = vault.firstIndex(where: { $0.id == id }) {
            vault[index].nickname = nickname
            saveVault(vault)
        }
    }

    /// Import a recovery key (base64 of 64 bytes) into the vault without switching.
    /// Returns the StoredIdentity if valid, nil if invalid format.
    func importIdentity(recoveryKey: String) -> StoredIdentity? {
        let trimmed = recoveryKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let rawData = Data(base64Encoded: trimmed), rawData.count == 64 else { return nil }

        let encKeyData = rawData.prefix(32)
        let sigKeyData = rawData.suffix(32)

        guard let encKey = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: encKeyData) else { return nil }

        let publicKey = encKey.publicKey.rawRepresentation.base64EncodedString()

        // Check if already in vault
        var vault = loadVault()
        if let existing = vault.first(where: { $0.publicKey == publicKey }) {
            return existing
        }

        let identity = StoredIdentity(
            id: UUID(),
            publicKey: publicKey,
            encryptionKeyData: Data(encKeyData),
            signingKeyData: Data(sigKeyData),
            createdAt: Date(),
            savedAt: Date(),
            nickname: nil
        )
        vault.append(identity)
        saveVault(vault)
        OshiLog.crypto.info("🔐 Identity imported to vault: \(publicKey.prefix(12))...")
        return identity
    }

    /// Switch to a vault identity. Saves current identity first, then loads the new one.
    func switchToIdentity(_ identity: StoredIdentity, identityManager: IdentityManager) {
        // Save current identity before switching
        autoSaveIfNew(identityManager: identityManager)

        // Build recovery key (64 bytes = enc + sig)
        let combined = identity.encryptionKeyData + identity.signingKeyData
        let recoveryKey = combined.base64EncodedString()

        // Use existing connectWallet flow which handles:
        // - Unregister old push
        // - Clear messages/ratchets
        // - Generate keys from seed
        // - Save wallet
        // - Migrate groups
        // - Re-register push
        identityManager.connectWallet(seedPhrase: recoveryKey)

        // Re-register push tokens for new identity
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
            VoIPPushManager.shared.registerTokensWithVPS()
        }

        OshiLog.crypto.info("🔄 Switched to vault identity: \(identity.publicKey.prefix(12))...")
    }
}
