//
//  IdentityManager.swift
//  Handles Web3 wallet connection and cryptographic operations
//  APPLE STORE COMPLIANT: Uses "Identity" terminology instead of "Wallet"
//  This is an IDENTITY SYSTEM for messaging, not a cryptocurrency wallet or anything related to it

import Foundation
import CryptoKit
import Combine
import UIKit

class IdentityManager: ObservableObject {
    @Published var isConnected: Bool = false
    @Published var identityAddress: String = ""
    @Published var publicKey: String = ""
    @Published var profileImageData: Data?
    @Published var lastSeenTimestamp: Double = 0
    
    internal var privateKey: Curve25519.KeyAgreement.PrivateKey?
    internal var signingPrivateKey: Curve25519.Signing.PrivateKey?

    // Key for storing key creation date
    private let keyCreationDateKey = "keyCreationDate"

    /// True when the most recent loadStoredWallet() couldn't read the keychain
    /// because the device hasn't been unlocked yet since boot. We must NOT show
    /// onboarding in this state — the keys still exist, we just can't read them.
    /// Cleared once a load succeeds. Drives the foreground-retry logic.
    @Published private(set) var keysLockedByDevice: Bool = false

    /// Set when a message that decrypted carried an identity key that is not the one last seen
    /// behind that sender's address. Observed by a view; detection happens on the message path,
    /// so nothing has to be opened for it to fire — see `KeyChangeWatch` for why that is the
    /// entire point.
    @Published private(set) var pendingKeyChange: KeyChangeWatch.Alert?

    init() {
        loadStoredWallet()
        loadProfileImage()
        // Auto-save identity to vault on launch
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            guard let self = self, self.isConnected else { return }
            IdentityVaultManager.shared.autoSaveIfNew(identityManager: self)
        }
        // Retry keychain load when the app comes back to the foreground —
        // the singleton may have been initialized while the device was locked.
        NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil, queue: .main
        ) { [weak self] _ in
            guard let self = self, !self.isConnected else { return }
            self.loadStoredWallet()
        }
        NotificationCenter.default.addObserver(
            forName: UIApplication.protectedDataDidBecomeAvailableNotification,
            object: nil, queue: .main
        ) { [weak self] _ in
            guard let self = self, !self.isConnected else { return }
            self.loadStoredWallet()
        }
        // 🔴 WATCHDOG (0x8BADF00D): posted from MultiDeviceSyncManager on a network callback
        // thread. `queue: .main` would block that thread on `-[NSOperation waitUntilFinished]`
        // until main drained. See NotificationCenter+MainThread in NotificationManager.swift.
        NotificationCenter.default.addMainThreadObserver(forName: NSNotification.Name("ProfileImageSyncedFromDevice")) { [weak self] notification in
            if let imageData = notification.object as? Data {
                self?.profileImageData = imageData
            }
        }
    }
    
    // MARK: - Wallet Connection
    
    func connectWallet(seedPhrase: String? = nil) {
        // Save old key before regeneration (needed for group migration)
        let oldPublicKey = isConnected ? publicKey : nil

        // 🔧 MULTI-DEVICE: Check if importing the SAME key (same identity on another device)
        // If same key → skip wipe, just re-register push tokens and trigger sync
        if let seed = seedPhrase, !seed.isEmpty {
            let predictedPubKey = predictPublicKey(from: seed)
            if predictedPubKey != nil && predictedPubKey == publicKey && isConnected {
                OshiLog.crypto.info("🔄 Same-key import detected — skipping data wipe, re-registering push tokens")
                // Re-derive keys (refreshes in-memory state)
                generateKeysFromSeed(seed)
                saveWallet()
                // If the per-identity alias archive survived (e.g., iCloud
                // restored UserDefaults but the live `contactAliases` blob
                // was cleared during the restore flow), pull it back in now.
                // Safe: restoreContactAliasesForIdentity only overwrites when
                // a non-empty archive exists for this pubkey.
                restoreContactAliasesForIdentity(publicKey)
                // Re-register push tokens for this device
                VoIPPushManager.shared.registerTokensWithVPS()
                // Importing an existing seed is the strongest signal that the
                // user wants multi-device sync — enable it implicitly so they
                // don't have to find the toggle in Settings.
                MultiDeviceSyncManager.shared.initialize(publicKey: publicKey)
                Task { _ = await MultiDeviceSyncManager.shared.enableSync() }
                // Trigger group sync pull from other devices
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                    MultiDeviceSyncManager.shared.pullGroupsFromSync()
                    MultiDeviceSyncManager.shared.pullContactsFromSync()
                }
                return
            }
        }

        // Different key or new identity → full wipe flow
        // 🔧 FIX: Unregister old public key from push service before generating new one
        if isConnected {
            // Save profile data for current identity before switching
            saveProfileImageForIdentity(publicKey)
            ProfileLinksManager.shared.saveLinksForIdentity(publicKey)
            ProfileLinksManager.shared.clearForIdentitySwitch()
            VoIPPushManager.shared.unregisterDevice()

            // 📦 Archive current identity's conversations & contacts before clearing
            let archivePubKey = publicKey
            MessageManager.shared?.archiveMessagesForIdentity(archivePubKey)
            DispatchQueue.main.async {
                MessageGroupManager.shared.archiveDataForIdentity(archivePubKey)
            }
            DoubleRatchetSessionManager.shared.archiveSessionsForIdentity(archivePubKey)
            archiveContactAliasesForIdentity(archivePubKey)
        } else if let stalePubKey = UserDefaults.standard.string(forKey: "publicKey"),
                  !stalePubKey.isEmpty,
                  (MessageManager.shared?.messages.isEmpty == false) {
            // 🛟 RESCUE: isConnected is false (keychain locked at boot, post-update
            // load failure, etc.) but MessageManager loaded messages from disk under
            // the previous identity. Without this, clearAllMessages() below would
            // wipe `messages.json` and the user's history would be unrecoverable
            // because nothing was archived under the previous pubkey.
            OshiLog.crypto.info("🛟 Rescuing live messages under stale identity \(stalePubKey.prefix(12))... before wipe")
            MessageManager.shared?.archiveMessagesForIdentity(stalePubKey)
            DispatchQueue.main.async {
                MessageGroupManager.shared.archiveDataForIdentity(stalePubKey)
            }
            DoubleRatchetSessionManager.shared.archiveSessionsForIdentity(stalePubKey)
            archiveContactAliasesForIdentity(stalePubKey)
        }

        // Clear old messages, sessions, and typing indicators before creating new identity
        MessageManager.shared?.clearAllMessages()
        MessageManager.shared?.clearRatchetSessions()
        TypingIndicatorManager.shared.clearAll()

        if let seed = seedPhrase, !seed.isEmpty {
            generateKeysFromSeed(seed)
        } else {
            generateNewKeys()
        }

        // R9 FIX: Minimize seed phrase exposure — clear local reference
        // Note: Swift String is immutable/COW, but clearing the parameter binding
        // helps the optimizer release the backing storage sooner.
        // The caller should also clear their copy after this function returns.
        var seedCopy = seedPhrase
        seedCopy = nil
        _ = seedCopy // suppress unused warning

        isConnected = true
        saveWallet()

        // 👋 First thing a new account sees: a greeting in their own language
        // plus a contact card, so they can talk to a human in one tap instead of
        // staring at an empty list. Purely local — nothing is announced to any
        // server, which matters for an app whose point is that signing up tells
        // nobody you exist. A seed-phrase import is a RESTORE, not a new user,
        // so it is excluded: they already have their history.
        let isRestore = !(seedPhrase ?? "").isEmpty
        let newKey = publicKey
        DispatchQueue.main.async {
            WelcomeMessageManager.shared.seedIfNeeded(for: newKey, isRestoredIdentity: isRestore)
        }

        // Store key creation date for new identity
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: keyCreationDateKey)

        // Check if switching to a previously used identity (has archived data)
        let isReturningIdentity = IdentityVaultManager.shared.loadVault().contains { $0.publicKey == publicKey }

        if isReturningIdentity {
            // Returning to a known identity — restore its archived data instead of migrating
            OshiLog.crypto.info("🔄 Returning to known identity \(publicKey.prefix(12))... — restoring archived data")
        } else {
            // Brand new identity — migrate group memberships (leave old groups)
            if let oldKey = oldPublicKey, !oldKey.isEmpty, !publicKey.isEmpty, oldKey != publicKey {
                DispatchQueue.main.async {
                    MessageGroupManager.shared.migrateGroupsToNewKey(oldKey: oldKey, newKey: self.publicKey)
                }
            }
        }

        // Auto-save new identity to vault
        IdentityVaultManager.shared.autoSaveIfNew(identityManager: self)

        // Restore profile data for this identity (if previously saved)
        restoreProfileImageForIdentity(publicKey)
        ProfileLinksManager.shared.restoreLinksForIdentity(publicKey)

        // 📥 Restore archived conversations & contacts for this identity (if any)
        let restorePubKey = publicKey
        MessageManager.shared?.restoreMessagesForIdentity(restorePubKey)
        DispatchQueue.main.async {
            MessageGroupManager.shared.restoreDataForIdentity(restorePubKey)
        }
        DoubleRatchetSessionManager.shared.restoreSessionsForIdentity(restorePubKey)
        restoreContactAliasesForIdentity(restorePubKey)

        // After new identity setup, trigger sync pull in case another device has data
        if let _ = seedPhrase, !seedPhrase!.isEmpty {
            // Seed import → user has the same identity on another device.
            // Auto-enable multi-device sync so messages, voice memos, and
            // contacts replicate without the user having to toggle it on.
            MultiDeviceSyncManager.shared.initialize(publicKey: publicKey)
            Task { _ = await MultiDeviceSyncManager.shared.enableSync() }
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                MultiDeviceSyncManager.shared.pullGroupsFromSync()
                MultiDeviceSyncManager.shared.pullContactsFromSync()
            }
        }

        // 🔧 FIX R14: Restart CovertChannelReceiver with new identity
        // The singleton keeps polling with old identity after switch; reconfigure and restart
        if let msgManager = MessageManager.shared {
            CovertChannelReceiver.shared.stopPolling()
            CovertChannelReceiver.shared.configure(identityManager: self, messageManager: msgManager)
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                CovertChannelReceiver.shared.startPolling()
            }
        }

        // CrossPlatformMesh stamps `senderPublicKey: myPublicKey` on every
        // outgoing message; without this restart, peers (Android) keep
        // attributing posts to the OLD conversation under the previous key.
        let displayName = UserDefaults.standard.string(forKey: "userName") ?? "OSHI User"
        CrossPlatformMesh.shared.reconfigureIdentity(publicKey: publicKey, displayName: displayName)
    }
    
    func disconnectWallet() {
        // 🔧 FIX: Unregister old public key from push service BEFORE clearing keys
        // Without this, the VPS still sends push notifications to the old key,
        // causing phantom ringing on this device even after key regeneration
        VoIPPushManager.shared.unregisterDevice()
        // __PER_DEVICE_MAILBOX_2026_09_23__ the device leaves the account's device list
        // (request signed now, before the identity keys go; spec §3.7).
        V2DeviceMailbox.unregisterSelfOnSignOut()

        // Save profile data for this identity before clearing
        saveProfileImageForIdentity(publicKey)
        ProfileLinksManager.shared.saveLinksForIdentity(publicKey)
        ProfileLinksManager.shared.clearForIdentitySwitch()

        // 📦 Archive current identity's conversations & contacts before clearing
        let disconnectPubKey = publicKey
        MessageManager.shared?.archiveMessagesForIdentity(disconnectPubKey)
        DispatchQueue.main.async {
            MessageGroupManager.shared.archiveDataForIdentity(disconnectPubKey)
        }
        DoubleRatchetSessionManager.shared.archiveSessionsForIdentity(disconnectPubKey)
        archiveContactAliasesForIdentity(disconnectPubKey)

        // Clear all messages and sessions on disconnect
        MessageManager.shared?.clearAllMessages()
        MessageManager.shared?.clearRatchetSessions()

        // Clear all group data
        DispatchQueue.main.async {
            MessageGroupManager.shared.clearAllData()
        }

        // Clear demo/onboarding state so user can start fresh
        UserDefaults.standard.removeObject(forKey: "contactAliases")
        UserDefaults.standard.removeObject(forKey: "hasSeenOnboarding")
        UserDefaults.standard.removeObject(forKey: "hasCompletedFirstTimeGuide")
        UserDefaults.standard.removeObject(forKey: "currentGuideStep")

        isConnected = false
        identityAddress = ""
        publicKey = ""
        privateKey = nil
        signingPrivateKey = nil
        profileImageData = nil
        clearStoredWallet()

        // 🔧 FIX R14: Stop CovertChannelReceiver polling — singleton would keep polling with old identity
        CovertChannelReceiver.shared.stopPolling()
    }
    
    // MARK: - Key Generation
    
    private func generateNewKeys() {
        // Generate encryption keypair
        let encryptionKey = Curve25519.KeyAgreement.PrivateKey()
        privateKey = encryptionKey
        
        // Generate signing keypair
        let signKey = Curve25519.Signing.PrivateKey()
        signingPrivateKey = signKey
        
        // Create identity address from public key
        let pubKeyData = encryptionKey.publicKey.rawRepresentation
        identityAddress = "0x" + pubKeyData.prefix(20).map { String(format: "%02x", $0) }.joined()
        
        publicKey = encryptionKey.publicKey.rawRepresentation.base64EncodedString()
    }
    
    /// Deterministically derives the mail X25519 private-key material (32 bytes) from
    /// this identity's stable encryption secret. Because it is a pure function of the
    /// identity key material — which the seed phrase / 64-byte backup restores exactly —
    /// the SAME mail key comes back on any device that restores this identity. That is
    /// what makes a mailbox recoverable: lose the phone, keep the key, and the mail
    /// sealed to this key (and the identity-bound mailbox and drive) all come back.
    /// The server never sees this: zero-access is unchanged, we only stop tying the
    /// mail key to one physical device.
    func deriveMailKeyMaterial() -> Data? {
        guard let priv = privateKey else { return nil }
        let ikm = SymmetricKey(data: priv.rawRepresentation)
        let derived = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: ikm,
            salt: Data("oshi-mail".utf8),
            info: Data("oshi-mail-x25519-v1".utf8),
            outputByteCount: 32)
        return derived.withUnsafeBytes { Data($0) }
    }

    /// __ENCRYPTED_MESSAGE_EXPORT_2026_09_22__ Clé AES-256 d'un export de
    /// messages, dérivée de la clé privée du compte — la même que la clé de
    /// récupération (64 octets) restaure à l'identique. Un export n'est donc
    /// lisible QUE par une session OSHI qui détient ce compte, sur n'importe quel
    /// appareil. `salt` est aléatoire par export: deux exports n'ont jamais la
    /// même clé.
    func deriveMessageExportKey(salt: Data) -> SymmetricKey? {
        guard let priv = privateKey else { return nil }
        return HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: priv.rawRepresentation),
            salt: salt,
            info: Data("oshi-message-export-v1".utf8),
            outputByteCount: 32)
    }

    /// 8 premiers octets de SHA-256(clé publique du compte): dit, avant tout
    /// déchiffrement, si un export appartient à ce compte. Ne révèle rien de
    /// plus que la clé publique, qui est déjà publique.
    func messageExportAccountTag() -> Data? {
        guard let priv = privateKey else { return nil }
        return Data(SHA256.hash(data: priv.publicKey.rawRepresentation).prefix(8))
    }

    private func generateKeysFromSeed(_ seed: String) {
        let trimmed = seed.trimmingCharacters(in: .whitespacesAndNewlines)

        // 🔧 FIX: Detect raw private key export (base64 of 64 bytes = encryption + signing keys)
        // This is what BackupSeedView exports via exportRecoveryKey()
        if let rawData = Data(base64Encoded: trimmed), rawData.count == 64 {
            do {
                let encKeyData = rawData.prefix(32)
                let sigKeyData = rawData.suffix(32)
                privateKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: encKeyData)
                signingPrivateKey = try Curve25519.Signing.PrivateKey(rawRepresentation: sigKeyData)

                let pubKeyData = privateKey!.publicKey.rawRepresentation
                identityAddress = "0x" + pubKeyData.prefix(20).map { String(format: "%02x", $0) }.joined()
                publicKey = pubKeyData.base64EncodedString()
                OshiLog.crypto.info("🔑 Identity restored from private key backup (64-byte raw)")
                return
            } catch {
                OshiLog.crypto.info("❌ Failed to restore from raw key, falling back to seed: \(error)")
            }
        }

        // Fallback: Hash the seed to get deterministic keys (legacy seed phrase flow)
        let seedData = Data(seed.utf8)
        let hash = SHA256.hash(data: seedData)

        do {
            // Use hash as private key material
            privateKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: hash)

            // Create signing key from hash
            let signingHash = SHA256.hash(data: hash + seedData)
            signingPrivateKey = try Curve25519.Signing.PrivateKey(rawRepresentation: signingHash)

            let pubKeyData = privateKey!.publicKey.rawRepresentation
            identityAddress = "0x" + pubKeyData.prefix(20).map { String(format: "%02x", $0) }.joined()
            publicKey = privateKey!.publicKey.rawRepresentation.base64EncodedString()
        } catch {
            OshiLog.crypto.info("Error generating keys from seed: \(error)")
            generateNewKeys()
        }
    }

    /// 🔧 MULTI-DEVICE: Predict the public key that would result from importing a seed/recovery key
    /// WITHOUT actually modifying state. Returns nil if parsing fails.
    private func predictPublicKey(from seed: String) -> String? {
        let trimmed = seed.trimmingCharacters(in: .whitespacesAndNewlines)

        // Try 64-byte raw key export format
        if let rawData = Data(base64Encoded: trimmed), rawData.count == 64 {
            let encKeyData = rawData.prefix(32)
            if let key = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: encKeyData) {
                return key.publicKey.rawRepresentation.base64EncodedString()
            }
        }

        // Fallback: legacy seed phrase hash
        let seedData = Data(trimmed.utf8)
        let hash = SHA256.hash(data: seedData)
        if let key = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: hash) {
            return key.publicKey.rawRepresentation.base64EncodedString()
        }

        return nil
    }

    /// Export both private keys as a single base64 string (64 bytes: 32 encryption + 32 signing)
    /// This is the recovery key that can be imported to restore the exact same identity.
    func exportRecoveryKey() -> String? {
        guard let encKey = privateKey, let sigKey = signingPrivateKey else { return nil }
        let combined = encKey.rawRepresentation + sigKey.rawRepresentation
        return combined.base64EncodedString()
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

        // Only once the message has opened: that proves the sender holds the private half of
        // this key, so an unauthenticated envelope cannot poison the baseline with a key of
        // the attacker's choosing.
        noteSenderIdentityKey(encryptedMessage.senderPublicKey)

        return message
    }

    // MARK: - Identity-key change detection (proactive, not screen-triggered)

    /// Clear the warning and adopt the new key as the baseline. Called when the user has
    /// actually seen it — deliberately not before, so a warning that was never displayed is
    /// raised again by the next message instead of being lost.
    func acknowledgeKeyChange() {
        guard let alert = pendingKeyChange else { return }
        KeyChangeWatch.remember(address: alert.address, currentKey: alert.currentKey)
        pendingKeyChange = nil
    }

    private func noteSenderIdentityKey(_ senderKey: String) {
        // Our own key: rotating it is something the user did, not something done to them.
        guard KeyChangeWatch.canonicalKey(senderKey) != KeyChangeWatch.canonicalKey(publicKey) else { return }
        let address = KeyChangeWatch.address(forKey: senderKey)
        switch KeyChangeWatch.classify(address: address, currentKey: senderKey, known: KeyChangeWatch.loadKnown()) {
        case .unchanged:
            break
        case .firstSight:
            KeyChangeWatch.remember(address: address, currentKey: senderKey)
        case .changed(let previous, let current):
            let alert = KeyChangeWatch.Alert(address: address, previous: previous,
                                             current: current, currentKey: senderKey)
            // decrypt() runs on the networking path; publishing off the main thread deadlocks
            // Combine (see the 2026-09-06 crash catalog).
            DispatchQueue.main.async { [weak self] in self?.pendingKeyChange = alert }
        }
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
    
    // MARK: - Request Signing (Ed25519)

    /// Sign an HTTP request for server-side verification.
    ///
    /// Canonical message: `METHOD\nPATH\nSHA256(body)\nTIMESTAMP`, built by
    /// ``HTTPRequestSignature/canonicalMessage(method:path:bodyData:timestamp:)`` — the bytes
    /// used to live inline here, and were moved out so a unit test can assert them without a
    /// keychain (see `HTTPRequestSignatureTests`). Nothing about the wire format changed.
    ///
    /// `path` MUST exclude the query string: the server verifies against
    /// `(req.originalUrl || req.url).split('?')[0]` (`ServerVPS/v2/oshi_auth.js:244`). A GET
    /// passes `nil`/empty `bodyData` and therefore signs over the SHA-256 of zero bytes.
    func signHTTPRequest(method: String, path: String, bodyData: Data?) -> (signature: String, timestamp: String, signingPublicKey: String)? {
        guard let signingKey = signingPrivateKey else {
            return nil
        }

        let timestamp = HTTPRequestSignature.currentTimestampMillis()
        let message = HTTPRequestSignature.canonicalMessage(method: method,
                                                           path: path,
                                                           bodyData: bodyData,
                                                           timestamp: timestamp)

        guard let messageData = message.data(using: .utf8) else { return nil }

        do {
            let signature = try signingKey.signature(for: messageData)
            return (
                signature: signature.base64EncodedString(),
                timestamp: timestamp,
                signingPublicKey: signingKey.publicKey.rawRepresentation.base64EncodedString()
            )
        } catch {
            return nil
        }
    }

    /// Sign a WebSocket message payload.
    /// Canonical message: WS\nTYPE\nPAYLOAD_HASH\nTIMESTAMP
    func signWebSocketMessage(type: String, payloadHash: String) -> (signature: String, timestamp: String, signingPublicKey: String)? {
        guard let signingKey = signingPrivateKey else { return nil }

        let timestamp = String(Int(Date().timeIntervalSince1970 * 1000))
        let message = "WS\n\(type)\n\(payloadHash)\n\(timestamp)"

        guard let messageData = message.data(using: .utf8) else { return nil }

        do {
            let signature = try signingKey.signature(for: messageData)
            return (
                signature: signature.base64EncodedString(),
                timestamp: timestamp,
                signingPublicKey: signingKey.publicKey.rawRepresentation.base64EncodedString()
            )
        } catch {
            return nil
        }
    }

    // MARK: - Persistence

    private func saveWallet() {
        UserDefaults.standard.set(identityAddress, forKey: "identityAddress")
        UserDefaults.standard.set(publicKey, forKey: "publicKey")
        
        if let privateKey = privateKey {
            let keyData = privateKey.rawRepresentation
            try? KeychainHelper.save(key: "privateKey", data: keyData)
        }
        
        if let signingKey = signingPrivateKey {
            let keyData = signingKey.rawRepresentation
            try? KeychainHelper.save(key: "signingKey", data: keyData)
            CallPushSigner.invalidate()  // __CALL_PUSH_AUTH_IOS_2026_09_23__
        }
    }
    
    private func loadStoredWallet() {
        let privKeyData: Data
        let signKeyData: Data
        do {
            privKeyData = try KeychainHelper.load(key: "privateKey")
            signKeyData = try KeychainHelper.load(key: "signingKey")
        } catch KeychainError.locked {
            // Device hasn't been unlocked since boot — keys still exist, we just
            // can't read them yet. DO NOT clear isConnected to false here; that
            // would force the user to re-onboard the next time they open the
            // app. The foreground / protectedDataDidBecomeAvailable observers
            // installed in init() will re-invoke this method once the device is
            // unlocked.
            keysLockedByDevice = true
            OshiLog.crypto.info("🔒 Keychain locked at boot — will retry after first unlock")
            return
        } catch KeychainError.itemNotFound {
            // Legitimate first launch (or post-reinstall after iOS 10.3 keychain
            // wipe). Onboarding is the right next step. Only treat ItemNotFound
            // as "no identity" — every other error is transient/unknown and
            // must NOT bounce the user to setup, which one tap can wipe.
            keysLockedByDevice = false
            OshiLog.crypto.info("🔑 No keys found in Keychain — fresh install")
            return
        } catch {
            // Unknown keychain failure (errSecAuthFailed -25293 during OS-update
            // entitlement rebuild, errSecDecode after backup restore, transient
            // SEP errors, etc). These are NOT "no identity" — but the previous
            // code returned without setting keysLockedByDevice, which dumped the
            // user on IdentitySetupView, and one accidental "Create new" wiped
            // everything. Treat any non-itemNotFound error as locked-equivalent:
            // keep isConnected as-is (don't flip to false on a transient error)
            // and let the foreground / protectedDataDidBecomeAvailable observers
            // retry the load.
            keysLockedByDevice = true
            OshiLog.crypto.info("⚠️ Keychain load failed (transient/unknown): \(error) — treating as locked, NOT clearing identity")
            return
        }

        do {
            privateKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privKeyData)
            signingPrivateKey = try Curve25519.Signing.PrivateKey(rawRepresentation: signKeyData)
            keysLockedByDevice = false

            // Try to load from UserDefaults first
            if let address = UserDefaults.standard.string(forKey: "identityAddress"),
               let pubKey = UserDefaults.standard.string(forKey: "publicKey") {
                identityAddress = address
                publicKey = pubKey
                OshiLog.crypto.info("🔑 Loaded identity from UserDefaults + Keychain")
            } else {
                // UserDefaults cleared (reinstall) - regenerate from private key
                let pubKeyData = privateKey!.publicKey.rawRepresentation
                identityAddress = "0x" + pubKeyData.prefix(20).map { String(format: "%02x", $0) }.joined()
                publicKey = pubKeyData.base64EncodedString()

                // Re-save to UserDefaults for next time
                UserDefaults.standard.set(identityAddress, forKey: "identityAddress")
                UserDefaults.standard.set(publicKey, forKey: "publicKey")
                OshiLog.crypto.info("🔑 Recovered identity from Keychain after reinstall")
            }

            isConnected = true

            // One-time migration: re-save with the new accessibility class
            // (kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly). Existing items
            // saved with the old WhenUnlockedThisDeviceOnly class won't be
            // accessible during background launches; saveWallet() does
            // SecItemDelete + SecItemAdd, which rewrites the accessibility.
            let migratedKey = "keychainAccessibilityMigratedV2"
            if !UserDefaults.standard.bool(forKey: migratedKey) {
                saveWallet()
                UserDefaults.standard.set(true, forKey: migratedKey)
                OshiLog.crypto.info("🔑 Migrated keychain accessibility to AfterFirstUnlock")
            }
        } catch {
            OshiLog.crypto.info("❌ Error decoding stored wallet: \(error)")
        }
    }
    
    private func clearStoredWallet() {
        UserDefaults.standard.removeObject(forKey: "identityAddress")
        UserDefaults.standard.removeObject(forKey: "publicKey")
        UserDefaults.standard.removeObject(forKey: keyCreationDateKey)
        UserDefaults.standard.removeObject(forKey: "profileImageData")
        try? KeychainHelper.delete(key: "privateKey")
        try? KeychainHelper.delete(key: "signingKey")
        CallPushSigner.invalidate()  // __CALL_PUSH_AUTH_IOS_2026_09_23__
        // Otherwise the next identity inherits this one's baselines and reports its contacts
        // as key changes — a false alarm on the one warning that has to be believed.
        KeyChangeWatch.reset()
    }
    
    // MARK: - Profile Image

    func setProfileImage(_ imageData: Data?) {
        profileImageData = imageData
        // 🆕 Notify web sessions of the change so they re-render this user's avatar everywhere.
        NotificationCenter.default.post(
            name: .init("OSHIProfilePictureChanged"),
            object: nil,
            userInfo: [
                "publicKey": publicKey,
                "imageData": imageData ?? Data()
            ]
        )
        if let data = imageData {
            UserDefaults.standard.set(data, forKey: "profileImageData")
            // Also persist for this identity so it restores on switch-back
            if !publicKey.isEmpty {
                UserDefaults.standard.set(data, forKey: "profileImageData_\(publicKey)")
            }
        } else {
            UserDefaults.standard.removeObject(forKey: "profileImageData")
            if !publicKey.isEmpty {
                UserDefaults.standard.removeObject(forKey: "profileImageData_\(publicKey)")
            }
        }
    }

    private func loadProfileImage() {
        profileImageData = UserDefaults.standard.data(forKey: "profileImageData")
    }

    // MARK: - Per-Identity Profile Data Save/Restore

    /// Save profile image for the given identity (call before switching away)
    func saveProfileImageForIdentity(_ pubKey: String) {
        guard !pubKey.isEmpty else { return }
        if let data = profileImageData {
            UserDefaults.standard.set(data, forKey: "profileImageData_\(pubKey)")
        }
    }

    /// Restore profile image for the given identity (call after switching to)
    func restoreProfileImageForIdentity(_ pubKey: String) {
        guard !pubKey.isEmpty else { return }
        if let data = UserDefaults.standard.data(forKey: "profileImageData_\(pubKey)") {
            profileImageData = data
            UserDefaults.standard.set(data, forKey: "profileImageData")
        } else {
            profileImageData = nil
            UserDefaults.standard.removeObject(forKey: "profileImageData")
        }
    }

    // MARK: - Identity-Scoped Contact Aliases

    /// Archive contact aliases for the current identity before switching.
    /// The UI persists `contactAliases` as JSON-encoded Data via @AppStorage, so the
    /// per-identity copy must use the same format — earlier code read/wrote a plist
    /// dictionary here and silently archived nothing.
    private func archiveContactAliasesForIdentity(_ pubKey: String) {
        guard !pubKey.isEmpty else { return }
        let aliasData = UserDefaults.standard.data(forKey: "contactAliases") ?? Data()
        guard !aliasData.isEmpty,
              let decoded = try? JSONDecoder().decode([String: String].self, from: aliasData),
              !decoded.isEmpty else {
            return
        }
        UserDefaults.standard.set(aliasData, forKey: "contactAliases_\(pubKey)")
        OshiLog.crypto.info("📦 Archived \(decoded.count) contact aliases for identity \(pubKey.prefix(12))...")
    }

    /// Restore contact aliases for the given identity after switching.
    /// Reads the JSON-encoded Data archive and writes it back to "contactAliases" so
    /// the @AppStorage-backed UI sees the restored values. When no archive exists for
    /// this identity, leave any preexisting live aliases alone — they may have been
    /// restored from an iCloud/UserDefaults backup that wasn't yet partitioned
    /// per-identity (older installs wrote everything to the unscoped key).
    func restoreContactAliasesForIdentity(_ pubKey: String) {
        guard !pubKey.isEmpty else { return }
        if let aliasData = UserDefaults.standard.data(forKey: "contactAliases_\(pubKey)"),
           !aliasData.isEmpty,
           let decoded = try? JSONDecoder().decode([String: String].self, from: aliasData),
           !decoded.isEmpty {
            UserDefaults.standard.set(aliasData, forKey: "contactAliases")
            OshiLog.crypto.info("📥 Restored \(decoded.count) contact aliases for identity \(pubKey.prefix(12))...")
        } else {
            // No per-identity archive. If there's a live alias blob, treat it as
            // an unscoped legacy carry-over for this identity and adopt it under
            // the per-identity key so subsequent identity switches don't lose it.
            // If there's nothing live either, clear so other identities' aliases
            // don't leak in.
            if let liveData = UserDefaults.standard.data(forKey: "contactAliases"),
               !liveData.isEmpty,
               let liveDecoded = try? JSONDecoder().decode([String: String].self, from: liveData),
               !liveDecoded.isEmpty {
                UserDefaults.standard.set(liveData, forKey: "contactAliases_\(pubKey)")
                OshiLog.crypto.info("📥 Adopted \(liveDecoded.count) legacy unscoped aliases for identity \(pubKey.prefix(12))...")
            } else {
                UserDefaults.standard.removeObject(forKey: "contactAliases")
                OshiLog.crypto.info("📭 No archived contact aliases for identity \(pubKey.prefix(12))...")
            }
        }
    }
    
    // MARK: - Last Seen
    
    func updateLastSeen() {
        lastSeenTimestamp = Date().timeIntervalSince1970
        UserDefaults.standard.set(lastSeenTimestamp, forKey: "lastSeenTimestamp")
    }
    
    /// __KEY_CREATED_SHOWED_TODAY_2026_11_07__ (Anatole N6, iOS half.)
    ///
    /// Android showed TODAY for this row and moved it every day, because
    /// nothing ever wrote the value. iOS is better — `createIdentity` stamps it
    /// — but the fallback here had the same flaw in a quieter form: an identity
    /// older than the stamping wrote `Date()` and froze it, so the row stated a
    /// creation date that was simply the day the user first opened Settings.
    ///
    /// Frozen-wrong is less visible than moving-wrong and no more true. The
    /// seed is now the creation date of this app's Documents directory, which
    /// is a real fact about this install and bounds the key from above — the
    /// key cannot predate the container it lives in. Same reasoning as
    /// `firstInstallTime` on Android.
    func getKeyCreationDate() -> Date {
        let timestamp = UserDefaults.standard.double(forKey: keyCreationDateKey)
        if timestamp > 0 { return Date(timeIntervalSince1970: timestamp) }

        let seed = Self.installationDate() ?? Date()
        UserDefaults.standard.set(seed.timeIntervalSince1970, forKey: keyCreationDateKey)
        return seed
    }

    /// When this app's container was created. An upper bound on the key's age.
    private static func installationDate() -> Date? {
        guard let docs = FileManager.default.urls(for: .documentDirectory,
                                                  in: .userDomainMask).first else { return nil }
        let attrs = try? FileManager.default.attributesOfItem(atPath: docs.path)
        return attrs?[.creationDate] as? Date
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
