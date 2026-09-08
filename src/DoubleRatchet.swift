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
    fileprivate(set) var sessionVersion: Int = 2  // ✅ Bumped version
    
    // ✅ NEW: Session identity
    private(set) var sessionId: String  // Canonical identifier
    private(set) var isInitiator: Bool  // Role in this session

    /// Whether this session's root key absorbed post-quantum material.
    ///
    /// Decided ONCE, when the session is created, and then fixed. It is not a
    /// per-message property on purpose: if the mode could drift, stripping the
    /// KEM ciphertext off one message would downgrade the session to classical,
    /// which is the whole attack the hybrid exists to stop. Because it is mixed
    /// into the AEAD's additional data (`encodeHeaderForAAD`), a stripped or
    /// forged mode produces an authentication FAILURE rather than a quieter,
    /// weaker session.
    ///
    /// Defaults to `.classical`, and every session persisted before this change
    /// decodes as `.classical`, so existing conversations are untouched.
    private(set) var securityMode: RatchetSecurityMode = .classical

    /// The X-Wing ciphertext WE produced when we seeded this session, kept so it
    /// can ride along on outgoing messages until the peer demonstrably has it.
    ///
    /// Only the side that encapsulated holds one; the responder derived its
    /// secret by decapsulating and has nothing to send back.
    private(set) var pqCiphertext: Data?

    /// Set the first time we successfully decrypt anything from the peer.
    ///
    /// That is proof they already hold the hybrid root key — they could not have
    /// produced a message we can read otherwise — so the 1120-byte ciphertext
    /// can stop being attached to every message. Until then it MUST keep going
    /// out: this is a store-and-forward mesh over IPFS, messages are dropped and
    /// reordered, and a ciphertext sent once is a session that never starts.
    private(set) var pqCiphertextAcknowledged: Bool = false

    /// What `_encrypt` should attach to the next header.
    private var pendingPQCiphertext: Data? {
        guard securityMode == .hybridPQ, !pqCiphertextAcknowledged else { return nil }
        return pqCiphertext
    }
    
    /// 🔴 FIX (2026-08-20) — CONFIRMED CRASH, `OSHI-2026-08-20-195151.ips`.
    ///
    /// `SIGABRT`, thread `com.app.ratchet.manager`:
    ///
    ///     -[NSObject doesNotRecognizeSelector:]
    ///     ___forwarding___ / _CF_forwarding_prep_0
    ///     Dictionary<>.encode(to:)
    ///     __JSONEncoder.wrapGeneric
    ///     DoubleRatchetSession.encode(to:)
    ///
    /// This dictionary was mutated on ONE queue and serialised on ANOTHER, with
    /// nothing in between. `encrypt`, `decrypt` and `cleanupOldSkippedKeys` all
    /// run on `sessionQueue` ("com.app.ratchet.session"); `encode(to:)` runs
    /// wherever `DoubleRatchetSessionManager` persists from, which its own label
    /// says is "com.app.ratchet.manager". A Swift `Dictionary` iterated while
    /// another thread inserts into it walks a buffer that is being reallocated
    /// underneath — which is how a JSON encode ends up sending an unknown
    /// selector to whatever it found there, and aborting.
    ///
    /// It is not exotic: skipping keys for out-of-order messages (`skipMessageKeys`)
    /// writes here on every gap, and the manager saves on a timer and after every
    /// send. The two queues meet constantly.
    ///
    /// The backing store is now private and every access goes through the lock.
    /// The public getter returns a COPY — `Dictionary` is a value type, so callers
    /// (including `encode(to:)` and `saveState()`) get a stable snapshot and
    /// cannot hold the lock across encoding.
    private var _skippedMessageKeys: [String: SkippedKey] = [:]

    /// Guards `_skippedMessageKeys` ONLY. Deliberately not `sessionQueue`: the
    /// cleanup path already runs on that queue and calls `saveSession()` from
    /// inside it, so making the encoder take `sessionQueue.sync` would deadlock
    /// the two queues against each other instead of racing them.
    private let skippedLock = NSLock()

    internal var skippedMessageKeys: [String: SkippedKey] {
        skippedLock.lock(); defer { skippedLock.unlock() }
        return _skippedMessageKeys
    }

    private func setSkipped(_ value: SkippedKey, for key: String) {
        skippedLock.lock(); defer { skippedLock.unlock() }
        _skippedMessageKeys[key] = value
    }

    private func removeSkipped(_ key: String) {
        skippedLock.lock(); defer { skippedLock.unlock() }
        _skippedMessageKeys.removeValue(forKey: key)
    }

    private func replaceSkipped(_ all: [String: SkippedKey]) {
        skippedLock.lock(); defer { skippedLock.unlock() }
        _skippedMessageKeys = all
    }

    /// Removes everything older than `cutoff` and returns what went, so the
    /// caller can log and save AFTER the lock is released.
    private func pruneSkipped(before cutoff: Date) -> [String] {
        skippedLock.lock(); defer { skippedLock.unlock() }
        let doomed = _skippedMessageKeys.filter { $0.value.timestamp < cutoff }.map { $0.key }
        doomed.forEach { _skippedMessageKeys.removeValue(forKey: $0) }
        return doomed
    }
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
        case securityMode            // post-quantum: absent ⇒ .classical
        case pqCiphertext, pqCiphertextAcknowledged
    }
    
    init(rootKey: Data, sendingChainKey: Data, receivingChainKey: Data, sessionId: String, isInitiator: Bool,
         securityMode: RatchetSecurityMode = .classical, pqCiphertext: Data? = nil) {
        self.rootKey = rootKey
        self.sendingChainKey = sendingChainKey
        self.receivingChainKey = receivingChainKey
        self.ourRatchetKeyPair = Curve25519.KeyAgreement.PrivateKey()
        self.sessionId = sessionId
        self.isInitiator = isInitiator
        self.createdAt = Date()
        self.lastUsedAt = Date()
        self.sessionVersion = 2
        self.securityMode = securityMode
        self.pqCiphertext = pqCiphertext
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
        _skippedMessageKeys = try container.decode([String: SkippedKey].self, forKey: .skippedMessageKeys)
        
        createdAt = try container.decodeIfPresent(Date.self, forKey: .createdAt) ?? Date()
        lastUsedAt = try container.decodeIfPresent(Date.self, forKey: .lastUsedAt) ?? Date()
        sessionVersion = try container.decodeIfPresent(Int.self, forKey: .sessionVersion) ?? 1
        
        // ✅ NEW: Load session identity or create from legacy data
        sessionId = try container.decodeIfPresent(String.self, forKey: .sessionId) ?? UUID().uuidString
        isInitiator = try container.decodeIfPresent(Bool.self, forKey: .isInitiator) ?? true
        // A session stored by any build before the hybrid existed has no mode.
        // It must come back as `.classical` — never as "unknown" and never as a
        // throw, or every conversation on the device would break on upgrade.
        securityMode = try container.decodeIfPresent(RatchetSecurityMode.self, forKey: .securityMode) ?? .classical
        pqCiphertext = try container.decodeIfPresent(Data.self, forKey: .pqCiphertext)
        pqCiphertextAcknowledged = try container.decodeIfPresent(Bool.self, forKey: .pqCiphertextAcknowledged) ?? false
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
        try container.encode(securityMode, forKey: .securityMode)
        try container.encodeIfPresent(pqCiphertext, forKey: .pqCiphertext)
        try container.encode(pqCiphertextAcknowledged, forKey: .pqCiphertextAcknowledged)
    }
    
    // MARK: - CV-010: making the DH ratchet actually turn

    /// Session version for the ratchet that DOES rotate. v2 sessions keep the
    /// old behaviour untouched, and `_decrypt`'s version guard keeps the two
    /// kinds of traffic apart.
    static let ratchetingSessionVersion = 3

    /// The responder's INITIAL ratchet key pair, derived from the shared secret
    /// so that BOTH sides can compute its public half.
    ///
    /// This is the piece that was missing. A Double Ratchet bootstraps from a
    /// responder key the initiator already knows — in Signal that is the signed
    /// pre-key from the bundle. This path has no bundle (it is the offline mesh
    /// path; that is the whole point of it), so the pair is derived from the
    /// static X25519 secret both sides already share.
    ///
    /// Consequence, stated plainly: the initiator's FIRST message is still
    /// derivable by anyone holding the identity keys, because this key is. From
    /// the responder's first reply onward the ratchet uses freshly RANDOM keys
    /// and that stops being true. Same guarantee Signal gives when a pre-key is
    /// compromised, and a strict improvement on "never rotates at all".
    static func derivedResponderRatchetKey(sharedSecret: Data) -> Curve25519.KeyAgreement.PrivateKey {
        let material = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: sharedSecret),
            salt: Data(),
            info: Data("InitialRatchetKey-v3".utf8),
            outputByteCount: 32
        ).withUnsafeBytes { Data($0) }
        // Curve25519 clamps internally, so any 32 bytes are a valid private key.
        return (try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: material))
            ?? Curve25519.KeyAgreement.PrivateKey()
    }

    /// The chain that carries the RESPONDER's messages until its first DH ratchet.
    ///
    /// It replaces 32 zero bytes. That constant was a matched pair — the
    /// responder's sending chain and the initiator's receiving chain — so a
    /// responder that spoke before it had received anything encrypted every
    /// message under HKDF(00x32, "MessageKey"), a value with no secret input at
    /// all: d663c614…4562, identical on every device and every conversation.
    ///
    /// Stated plainly, because it matters: this chain is derivable by anyone who
    /// holds BOTH identity private keys, exactly like the initiator's own first
    /// chain. It stops being derivable at the first real ratchet. That is a
    /// strict improvement on a public constant, not a claim of forward secrecy
    /// for the opening messages.
    ///
    /// The info string is the cross-platform contract: Kotlin must derive the
    /// same bytes or the two sides fail the AEAD in silence. [Audit 2026-09-08]
    static func responderInitialChain(sharedSecret: Data) -> Data {
        HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: sharedSecret),
            salt: Data(),
            info: Data("ResponderInitialChain-v3".utf8),
            outputByteCount: 32
        ).withUnsafeBytes { Data($0) }
    }

    /// Build a v3 session, initialised the way the Double Ratchet actually
    /// specifies rather than with both sides holding an un-rotating key.
    ///
    ///  * responder — holds the derived pair, no peer key yet, so its FIRST
    ///    receive performs a real DH ratchet and generates a random key of its
    ///    own;
    ///  * initiator — random pair, peer key already known (the derived public),
    ///    and the sending chain comes from that DH straight away.
    ///
    /// After the responder's first reply, both sides are on random ephemeral
    /// keys and the chain stops being derivable from the identity keys.
    static func makeRatcheting(rootSeed: Data,
                               sharedSecret: Data,
                               sessionId: String,
                               isInitiator: Bool,
                               securityMode: RatchetSecurityMode,
                               pqCiphertext: Data?) -> DoubleRatchetSession {
        let zero = Data(repeating: 0, count: 32)
        let responderPair = derivedResponderRatchetKey(sharedSecret: sharedSecret)
        let responderChain = responderInitialChain(sharedSecret: sharedSecret)

        let session = DoubleRatchetSession(rootKey: rootSeed,
                                           sendingChainKey: zero,
                                           receivingChainKey: zero,
                                           sessionId: sessionId,
                                           isInitiator: isInitiator,
                                           securityMode: securityMode,
                                           pqCiphertext: pqCiphertext)
        session.sessionVersion = ratchetingSessionVersion

        if isInitiator {
            let ours = Curve25519.KeyAgreement.PrivateKey()
            let theirPub = responderPair.publicKey
            if let dh = try? ours.sharedSecretFromKeyAgreement(with: theirPub) {
                let (rk, sendChain) = session.deriveRootKeys(rootKey: rootSeed, dhOutput: dh)
                session.rootKey = rk
                session.sendingChainKey = sendChain
            }
            session.ourRatchetKeyPair = ours
            session.theirRatchetPublicKey = theirPub.rawRepresentation
            // Matches the responder's sending chain below. Was `zero`. [Audit 2026-09-08]
            session.receivingChainKey = responderChain
        } else {
            // Root stays the seed; the first receive ratchets it forward.
            session.ourRatchetKeyPair = responderPair
            session.theirRatchetPublicKey = nil
            // Was `zero`, a public constant. [Audit 2026-09-08]
            session.sendingChainKey = responderChain
        }
        return session
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
        
        // A sending chain of 32 zero bytes is a PUBLIC CONSTANT, not a secret.
        // `makeRatcheting` leaves the RESPONDER's sending chain at zero and only
        // replaces it on that peer's first RECEIVE, so a responder that speaks
        // before it has heard anything encrypts under
        //   HKDF(00×32, salt: "", info: "MessageKey", 32)
        //   = d663c61441ce56f9d25db8149a2d3867b92aaaa9cf2ff7025649801d6fe34562
        // — the same AES-256-GCM key on every device, in every conversation, for
        // every message on that chain until the first ratchet. Nobody noticed
        // because the initiator's receiving chain is zero too, so it decrypts
        // fine, and because no test ever has the responder speak first: the role
        // rule is `ourKey < theirKey` and "ALICE…" always sorts before "BOB…".
        //
        // Refusing is not the whole fix — the real repair is a derived initial
        // chain for the responder, and that changes the key schedule invisibly on
        // the wire, so it cannot ship on one platform alone. This guard is the
        // half that IS safe alone: it turns a silent loss of confidentiality into
        // a visible send failure the caller's heal path already handles.
        //
        // Scoped to v3 deliberately. v2 has the identical defect and a larger
        // installed base; an unscoped guard would break every live v2 responder's
        // first send. v2 needs its own coordinated fix. [Audit 2026-09-08]
        if sessionVersion >= DoubleRatchetSession.ratchetingSessionVersion,
           sendingChainKey == Data(repeating: 0, count: 32) {
            OshiLog.crypto.error("🚨 Refusing to encrypt on an all-zero sending chain (v\(self.sessionVersion), \(self.isInitiator ? "INITIATOR" : "RESPONDER"))")
            throw DoubleRatchetError.uninitializedSendingChain
        }

        let messageKey = deriveMessageKey(from: sendingChainKey)
        
        let header = DoubleRatchetMessage.MessageHeader(
            publicKey: ourPubKey,
            messageNumber: sendingMessageNumber,
            previousChainLength: previousSendingChainLength,
            isDHRatchet: needsDHRatchet,
            timestamp: Date(),
            sessionVersion: sessionVersion,
            sessionId: sessionId,  // ✅ Include session ID in header
            // Stamped from the SESSION, never from a caller: the mode is a
            // property of the key material already agreed, so it cannot be
            // talked down message by message.
            securityMode: securityMode == .hybridPQ ? .hybridPQ : nil,
            pqCiphertext: pendingPQCiphertext
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
            if securityMode == .hybridPQ { pqCiphertextAcknowledged = true }
            removeSkipped(skipKey)
            updateLastUsed()
            DoubleRatchetSessionManager.shared.saveSession()
            return plaintext
        }
        
        let savedState = saveState()
        
        do {
            // ✅ FIXED: Store their key on first message, regardless of message number
            if theirRatchetPublicKey == nil {
                // CV-010. This branch is the reason the DH ratchet never turned.
                //
                // `performDHRatchet` only runs when the peer's key CHANGES, and a
                // side replaces its own key pair only INSIDE `performDHRatchet`.
                // Storing the first key without ratcheting meant neither side
                // ever rotated, so neither ever saw a change, so the ratchet
                // never fired for the life of the session — and the whole chain
                // stayed derivable from the long-term identity keys.
                //
                // A v3 session ratchets here, which it CAN do because its own
                // ratchet key is the one derived from the shared secret, so the
                // sender computed the same DH. A v2 session keeps the old
                // behaviour exactly: its ratchet key is random and unknown to the
                // sender, so ratcheting here would derive a chain the sender
                // never used and break every existing conversation.
                if sessionVersion >= DoubleRatchetSession.ratchetingSessionVersion {
                    OshiLog.crypto.info("   🔄 First message from sender — DH ratchet (v3)")
                    try performDHRatchet(newPublicKey: message.header.publicKey)
                } else {
                    OshiLog.crypto.info("   📝 First message from sender, storing their key (no DH ratchet)")
                    OshiLog.crypto.info("      Message #: \(message.header.messageNumber)")
                    theirRatchetPublicKey = message.header.publicKey
                }
            } else if theirRatchetPublicKey != message.header.publicKey {
                // ✅ CRITICAL: Check if this is an old message with old ratchet key
                //
                // v2 ONLY. That guard compares the incoming number against the
                // counter of the chain we are on, which is sound only while the
                // ratchet never turns and numbering is monotonic for the life of
                // the session — the v2 world.
                //
                // Once the ratchet DOES turn (v3), every new chain restarts at 0,
                // so a perfectly good first message of a new chain looks "older"
                // than the last message of the previous one and was rejected as
                // `oldMessage`. That is what made the conversation stall on the
                // second round trip. A genuinely stale message is still handled —
                // by the skipped-key lookup above, which runs BEFORE this.
                if sessionVersion < DoubleRatchetSession.ratchetingSessionVersion,
                   message.header.messageNumber < receivingMessageNumber {
                    OshiLog.crypto.info("   ⚠️ Old message with old ratchet key detected")
                    OshiLog.crypto.info("      Message #\(message.header.messageNumber) < recv#\(receivingMessageNumber)")
                    OshiLog.crypto.info("      This message was encrypted before we stored the current ratchet key")
                    OshiLog.crypto.info("      Cannot decrypt - message arrived too late")
                    throw DoubleRatchetError.oldMessage
                }
                
                // Before moving to the new chain, bank the keys still owed on the
                // OLD one. `previousChainLength` is exactly how many the sender
                // produced there; without this, anything from the old chain that
                // arrives after the ratchet — routine on a mesh that reorders —
                // is unrecoverable.
                if sessionVersion >= DoubleRatchetSession.ratchetingSessionVersion,
                   let currentChainKey = theirRatchetPublicKey,
                   message.header.previousChainLength > receivingMessageNumber {
                    try skipMessageKeys(until: message.header.previousChainLength,
                                        chainPublicKey: currentChainKey)
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

                // The peer produced something we can read, so they hold the
                // hybrid root key and therefore already have our encapsulation.
                // Stop attaching 1120 bytes to every subsequent message.
                if securityMode == .hybridPQ { pqCiphertextAcknowledged = true }

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
        // __RATCHET_STATE_IS_TRANSACTIONAL_2026_08_18__ (completed 2026-09-08)
        // `needsDHRatchet` is protocol state like any other: `performDHRatchet`
        // sets it BEFORE the AEAD tag is checked. Leaving it out of the snapshot
        // meant one forged packet flipped it permanently — the victim's next
        // legitimate message then went out stamped `isDHRatchet: true`, the peer
        // ratcheted a second time on a key it had already consumed, and BOTH
        // directions died with no key material and no MITM position required.
        let needsDHRatchet: Bool
    }
    
    #if DEBUG
    /// Test-only: reproduce the state an older build left on disk. Never called
    /// by the app. [Audit 2026-09-08]
    func forceSendingChainForTesting(_ chain: Data) { sendingChainKey = chain }
    #endif

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
            skippedKeys: skippedMessageKeys,
            needsDHRatchet: needsDHRatchet
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
        needsDHRatchet = state.needsDHRatchet
        replaceSkipped(state.skippedKeys)
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
            setSkipped(SkippedKey(
                key: messageKey,
                timestamp: now,
                messageNumber: receivingMessageNumber
            ), for: skipKey)
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
    
    fileprivate func deriveRootKeys(rootKey: Data, dhOutput: SharedSecret) -> (Data, Data) {
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

        // Post-quantum material is appended ONLY for a hybrid session, and only
        // at the very end.
        //
        // Both halves of that sentence are load-bearing. Appending nothing in
        // the classical case keeps the additional data byte-for-byte what every
        // shipped build already computes, so a new client and an old one
        // authenticate the same bytes and existing conversations keep working.
        // Putting it last means the classical prefix never shifts.
        //
        // And binding it here is what makes the mode tamper-evident: an attacker
        // who strips `pqCiphertext` to force a classical session changes the
        // additional data, and AES-GCM rejects the message instead of quietly
        // accepting a weaker one.
        if header.securityMode == .hybridPQ {
            data.append(contentsOf: Data(RatchetSecurityMode.hybridPQ.rawValue.utf8))
            if let pq = header.pqCiphertext { data.append(pq) }
        }

        return data
    }
    
    func cleanupOldSkippedKeys(olderThan days: Int = 7) {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            let cutoffDate = Date().addingTimeInterval(-Double(days * 24 * 60 * 60))
            // Prune under the lock, log and save OUTSIDE it — `saveSession()`
            // encodes, and encoding takes the same lock.
            let keysToRemove = self.pruneSkipped(before: cutoffDate)
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

        // MARK: Post-quantum (both OPTIONAL — absent means a classical session)
        //
        // Optional is what keeps this change off the wire for everyone else.
        // Swift's synthesised `encode` uses `encodeIfPresent` for Optionals, so a
        // classical message serialises to EXACTLY the JSON it does today — same
        // keys, same bytes — and an older client, whose `MessageHeader` has no
        // such properties, ignores them when they are present. Old ⇄ new in both
        // directions, with no version gate.

        /// `.hybridPQ` when this session's root key absorbed X-Wing material.
        var securityMode: RatchetSecurityMode? = nil

        /// The X-Wing encapsulation the responder needs in order to derive the
        /// same root key. A KEM is one-directional — unlike DH, the peer cannot
        /// compute the secret from keys it already holds — which is the whole
        /// reason a field had to appear on the wire at all.
        var pqCiphertext: Data? = nil
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
    /// The sending chain is still the all-zero placeholder, so any message
    /// encrypted on it would be readable by anyone. [Audit 2026-09-08]
    case uninitializedSendingChain
    case invalidMessageNumber  // ✅ NEW
}

class DoubleRatchetSessionManager {
    static let shared = DoubleRatchetSessionManager()
    private var sessions: [String: DoubleRatchetSession] = [:]
    // Name lives in `KeychainHelper.Account` — the shared-access-group list reads
    // the same constant, so the two cannot disagree.
    private let storageKey: String
    private let queue = DispatchQueue(label: "com.app.ratchet.manager", qos: .userInitiated)

    /// - Parameter storageKey: where this manager persists.
    ///
    /// Injectable ONLY so tests can be isolated, and that is not a nicety. A test
    /// that builds a `DoubleRatchetSessionManager()` on a REAL DEVICE loads the
    /// user's live sessions from the Keychain, clears them, creates its own, and
    /// then `saveSessions()` writes that single test session back over the lot —
    /// silently destroying every real conversation on the phone. Running the
    /// suite on hardware is exactly when that bites.
    ///
    /// Production passes nothing and is unchanged.
    init(storageKey: String = KeychainHelper.Account.legacyRatchetSessions) {
        self.storageKey = storageKey
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
    
    /// - Parameters:
    ///   - pqSecret: X-Wing shared secret to mix into the root key, or nil for a
    ///     classical session. The initiator gets it from `encapsulate`, the
    ///     responder from `decapsulate` on the ciphertext in the first header.
    ///   - pqCiphertext: the encapsulation to attach to outgoing messages.
    ///     Initiator only — the responder passes nil, having decapsulated.
    ///
    /// Passing neither reproduces the classical behaviour EXACTLY, which is what
    /// every existing call site does by omission.
    ///
    /// An existing session is returned unchanged even when hybrid material is
    /// offered. Re-keying a live conversation from under the peer would strand
    /// them; upgrading an established session is deliberately a `resetSession`
    /// away, so the decision is explicit and both sides start clean.
    func getOrCreateSession(with theirPublicKey: String, ourPublicKey: String, sharedSecret: Data,
                            pqSecret: Data? = nil, pqCiphertext: Data? = nil,
                            ratcheting: Bool = false) -> DoubleRatchetSession {
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
            //
            // POST-QUANTUM: the KEM secret is absorbed HERE, into the initial
            // root key, and nowhere else — the same shape as Signal's PQXDH.
            //
            // Mixing it once is sufficient and is not a shortcut. The root key
            // is the input to every later DH ratchet, and HKDF is a PRF, so an
            // adversary who breaks every X25519 exchange in the conversation
            // still cannot advance the root without the PQ secret. Re-running a
            // KEM per message would cost 1120 bytes each and buy nothing against
            // the harvest-now-decrypt-later attack this is here for.
            //
            // `info` is domain-separated per mode so a hybrid and a classical
            // session can never derive the same key from the same X25519 secret.
            let hybrid = pqSecret != nil
            let ikm: Data = hybrid ? (sharedSecret + pqSecret!) : sharedSecret
            let rootInfo = hybrid ? "RootKey-XWing-v1" : "RootKey"
            let chainInfo = hybrid ? "InitialChain-XWing-v1" : "InitialChain"

            let rootKeySymmetric = HKDF<SHA256>.deriveKey(
                inputKeyMaterial: SymmetricKey(data: ikm),
                salt: Data(),
                info: Data(rootInfo.utf8),
                outputByteCount: 32
            )
            let rootKey = rootKeySymmetric.withUnsafeBytes { Data($0) }
            
            let initialChainSymmetric = HKDF<SHA256>.deriveKey(
                inputKeyMaterial: SymmetricKey(data: ikm),
                salt: Data(),
                info: Data(chainInfo.utf8),
                outputByteCount: 32
            )
            let initialChain = initialChainSymmetric.withUnsafeBytes { Data($0) }
            
            // ✅ Determine role deterministically
            let weAreInitiator = isInitiator(ourKey: ourPublicKey, theirKey: theirPublicKey)
            
            // CV-010: a v3 session initialises the way the Double Ratchet
            // specifies — the responder holds a key derived from the shared
            // secret, so its first receive can perform a REAL ratchet. v2 keeps
            // the old, non-rotating layout byte for byte.
            let session: DoubleRatchetSession
            if ratcheting {
                session = DoubleRatchetSession.makeRatcheting(
                    rootSeed: rootKey,
                    sharedSecret: ikm,
                    sessionId: sessionId,
                    isInitiator: weAreInitiator,
                    securityMode: hybrid ? .hybridPQ : .classical,
                    pqCiphertext: pqCiphertext)
            } else {
                session = DoubleRatchetSession(
                    rootKey: rootKey,
                    sendingChainKey: weAreInitiator ? initialChain : Data(repeating: 0, count: 32),
                    receivingChainKey: weAreInitiator ? Data(repeating: 0, count: 32) : initialChain,
                    sessionId: sessionId,
                    isInitiator: weAreInitiator,
                    securityMode: hybrid ? .hybridPQ : .classical,
                    pqCiphertext: pqCiphertext
                )
            }
            
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

            // The Keychain is the PRIMARY store — `loadSessions()` reads it first and
            // `saveSessions()` writes it. Clearing memory and the legacy UserDefaults
            // copy left every root key, both chain keys per session, every skipped
            // message key and every ratchet private key on disk, and the next launch
            // restored all of it. This is the "burn it" gesture someone uses at a
            // checkpoint; it has to actually burn. [Audit 2026-09-08]
            do {
                try KeychainHelper.delete(key: storageKey)
                OshiLog.crypto.info("🔥 Ratchet session Keychain item deleted")
            } catch {
                // Deleting something that is not there is success, not failure.
                OshiLog.crypto.warning("⚠️ Ratchet Keychain delete: \(error.localizedDescription)")
            }
            KeychainHelper.deletePrivateCopy(key: storageKey)

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
    
    /// Look a session up by the id its messages carry.
    ///
    /// The IPFS/relay path receives an ANONYMOUS object: unlike the mesh, there
    /// is no transport-level sender. A v3 group envelope deliberately does not
    /// name its sender either, so the only handle is the `sessionId` inside the
    /// wrap's own ratchet header — which is exactly enough, because the session
    /// it names is the one that can open it.
    ///
    /// This replaces what the old format did instead: trial-decrypting the
    /// envelope against every group's derived key in turn.
    func session(withId sessionId: String) -> DoubleRatchetSession? {
        queue.sync { sessions[sessionId] }
    }

    func getAllSessionKeys() -> [String] {
        return queue.sync {
            Array(sessions.keys)
        }
    }
}

// MARK: - Post-quantum negotiation on top of the session store

extension DoubleRatchetSessionManager {

    /// The one entry point every send and receive path uses, so the decision to
    /// go hybrid is made in ONE place rather than eight.
    ///
    /// It handles both roles:
    ///
    ///  * **Receiving** — `incomingHeader` carries a KEM ciphertext. We
    ///    decapsulate with this device's seed and seed the session with the
    ///    result. This is how the responder reaches the same root key without
    ///    ever having encapsulated anything.
    ///  * **Sending** — no header. If the peer has published an X-Wing key and
    ///    policy allows, we encapsulate to it and keep the ciphertext on the
    ///    session so outgoing headers carry it until acknowledged.
    ///
    /// INTEROPERABILITY, which is the whole point of routing everything through
    /// here: every branch below can fall back to the classical session, and the
    /// classical session is byte-identical to what shipped before. A peer on
    /// iOS 17, on Android, on desktop, or simply one whose profile we have not
    /// received yet, produces `pqSecret == nil` and the ordinary path runs.
    func getOrCreateSessionNegotiatingPQ(with theirPublicKey: String,
                                         ourPublicKey: String,
                                         sharedSecret: Data,
                                         incomingHeader: DoubleRatchetMessage.MessageHeader? = nil) -> DoubleRatchetSession {

        // The peer told us this message belongs to a hybrid session.
        if let header = incomingHeader, header.securityMode == .hybridPQ {
            if let ciphertext = header.pqCiphertext {
                do {
                    let seed = try PostQuantumIdentity.shared.seed()
                    let secret = try PostQuantumKEM.decapsulate(ciphertext, withSeed: seed)

                    // AUTO-RECONNECT, and the reason it is safe.
                    //
                    // If we already hold a CLASSICAL session for this peer, they
                    // have re-established as hybrid — after a heal, a reinstall,
                    // or simply after learning our PQ key — and our classical
                    // session can no longer read them. Dropping it here upgrades
                    // in one step instead of waiting for a decrypt failure to
                    // trigger the heal path.
                    //
                    // Only ever classical → hybrid. The reverse is refused a few
                    // lines below, because a header is attacker-controlled and
                    // "reset to something weaker" is exactly what a downgrade
                    // would ask for.
                    if let existing = getSession(with: theirPublicKey, ourPublicKey: ourPublicKey),
                       existing.securityMode == .classical {
                        OshiLog.crypto.info("🔐 PQ: peer upgraded to hybrid — re-establishing session")
                        resetSession(with: theirPublicKey, ourPublicKey: ourPublicKey)
                    }

                    return getOrCreateSession(with: theirPublicKey, ourPublicKey: ourPublicKey,
                                              sharedSecret: sharedSecret, pqSecret: secret,
                                              ratcheting: shouldRatchet(peer: theirPublicKey,
                                                                        incomingHeader: header))
                } catch {
                    // We cannot decapsulate: no PQ identity, an old OS, or a
                    // ciphertext meant for a key we no longer hold (reinstall).
                    // Falling back to a classical session is correct AND safe —
                    // it will simply fail to decrypt, because the sender's root
                    // key absorbed material we do not have, and the existing
                    // heal path takes over. What we must not do is pretend.
                    OshiLog.crypto.info("⚠️ PQ: decapsulation failed (\(String(describing: error))) — classical fallback")
                }
            }
            // Hybrid claimed with no ciphertext attached: the sender has already
            // seen us reply, so we must already hold the hybrid session. Fall
            // through and return it as it stands.
            return getOrCreateSession(with: theirPublicKey, ourPublicKey: ourPublicKey,
                                      sharedSecret: sharedSecret,
                                      ratcheting: shouldRatchet(peer: theirPublicKey,
                                                                incomingHeader: header))
        }

        // An existing session is authoritative. Notably a HYBRID session is never
        // rebuilt because a classical-looking header arrived — that is the
        // downgrade this asymmetry exists to refuse.
        if let existing = getSession(with: theirPublicKey, ourPublicKey: ourPublicKey) {
            return existing
        }

        // Fresh session, and we are the one starting it.
        let peerKey = PostQuantumPeerDirectory.shared.publicKey(for: theirPublicKey)
        guard let mode = PostQuantumNegotiator.mode(policy: .current,
                                                    localSupportsPQ: PostQuantumKEM.isSupportedByPlatform,
                                                    peerPublicKey: peerKey),
              mode == .hybridPQ,
              let peerKey else {
            // `.required` returning nil lands here too. We still create the
            // classical session rather than refusing to talk: `.required` is not
            // the shipping policy, and silently dropping a user's message would
            // be a worse failure than the one it prevents.
            return getOrCreateSession(with: theirPublicKey, ourPublicKey: ourPublicKey,
                                      sharedSecret: sharedSecret,
                                      ratcheting: shouldRatchet(peer: theirPublicKey,
                                                                incomingHeader: incomingHeader))
        }

        do {
            let enc = try PostQuantumKEM.encapsulate(toPublicKey: peerKey)
            OshiLog.crypto.info("🔐 PQ: hybrid session with \(theirPublicKey.prefix(8))…")
            return getOrCreateSession(with: theirPublicKey, ourPublicKey: ourPublicKey,
                                      sharedSecret: sharedSecret,
                                      pqSecret: enc.sharedSecret, pqCiphertext: enc.ciphertext,
                                      ratcheting: shouldRatchet(peer: theirPublicKey,
                                                                incomingHeader: incomingHeader))
        } catch {
            OshiLog.crypto.info("⚠️ PQ: encapsulation failed (\(String(describing: error))) — classical")
            return getOrCreateSession(with: theirPublicKey, ourPublicKey: ourPublicKey,
                                      sharedSecret: sharedSecret,
                                      ratcheting: shouldRatchet(peer: theirPublicKey,
                                                                incomingHeader: incomingHeader))
        }
    }

    /// CV-010: only build a rotating session when the peer can read one.
    ///
    /// `_decrypt` compares `sessionVersion` with a strict equality, so a v3
    /// session and a v2 one cannot exchange a single message. A peer that has
    /// not advertised support therefore keeps the old, non-rotating session —
    /// worse cryptography, but a working conversation, and it upgrades on its own
    /// once their profile arrives and the session is next re-established.
    func wantsRatchetingSession(with peer: String) -> Bool {
        PeerCapabilityStore.shared.supportsRatchetV3(peer)
    }

    /// Whether a session about to be CREATED should rotate.
    ///
    /// Capability alone is not enough, and assuming it was is a bug this caught
    /// late: capability is learned from a profile that arrives asynchronously, so
    /// two clients that have BOTH updated can disagree. A receives B's profile
    /// and builds v3; B has not yet received A's, builds v2, and the strict
    /// `sessionVersion` check then kills a conversation between two perfectly
    /// capable clients.
    ///
    /// So an incoming header decides for the receiver. It can only ever raise the
    /// version of a session being created, never lower one already established —
    /// a header is attacker-controlled, and "claim v2 to force the weaker
    /// ratchet" is exactly the downgrade that asymmetry refuses.
    private func shouldRatchet(peer: String,
                               incomingHeader: DoubleRatchetMessage.MessageHeader?) -> Bool {
        if let v = incomingHeader?.sessionVersion,
           v >= DoubleRatchetSession.ratchetingSessionVersion {
            return true
        }
        // No header means we are starting the conversation, so only what the peer
        // has advertised can justify v3.
        return incomingHeader == nil && wantsRatchetingSession(with: peer)
    }
}
