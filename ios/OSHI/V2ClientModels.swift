//
//  V2ClientModels.swift
//  OSHI — v2 E2E server stack: typed models + crypto seam for `V2Client`.
//
//  DEFENSIVE / ADDITIVE INFRASTRUCTURE.
//  This file is transport-layer plumbing for the app owner's own messenger
//  servers (prekey / blob / relay under https://oshi-messenger.com/v2/*).
//  It is ENTIRELY ADDITIVE: it defines NEW types under the `V2` namespace and
//  does NOT touch, reference, or alter the existing IPFS / Pinata path
//  (PinataConfig, PinataService, BackgroundMediaUploader, MessageManager, …).
//
//  Everything the network layer exchanges is modelled here as Codable value
//  types so `V2Client` never force-unwraps decoded network data.
//
//  NOTE: There is no iOS runtime in the authoring environment — this was
//  verified by careful reading against the Node servers (prekey_server.js,
//  blob_store.js, relay_v2.js, oshi_auth.js). Runtime + integration testing
//  happen on a Mac.
//

import Foundation
import CryptoKit

// MARK: - V2 namespace

/// Namespace for every value type the v2 client exchanges with the servers.
/// Kept as a caseless `enum` so it can never be instantiated and never clashes
/// with module-level types (MessageManager, IdentityManager, PinataConfig, …).
public enum V2 {

    // MARK: Envelope

    /// The routing/type discriminator carried by every relay envelope.
    /// Values match `relay_v2.js` `validateEnvelope` exactly ("1to1" | "group").
    public enum MessageType: String, Codable, Sendable {
        case oneToOne = "1to1"
        case group
    }

    /// Opaque X3DH initiator header, present ONLY on the first message of a new
    /// session. The relay stores/forwards it verbatim (`if (env.x3dh) …`) and
    /// never interprets it — these are public keys the RECIPIENT needs to derive
    /// the shared secret. Fields are intentionally permissive (optional) so a
    /// decode of a peer-produced header never throws; the crypto layer
    /// (OSHICryptoV2) owns the exact semantics.
    public struct X3DHHeader: Codable, Sendable, Equatable {
        /// Initiator identity public key (base64, opaque).
        public let identityKey: String
        /// Initiator ephemeral public key (base64, opaque).
        public let ephemeralKey: String
        /// Which of the recipient's signed pre-keys was used.
        public let signedPreKeyId: String?
        /// Which of the recipient's one-time pre-keys was consumed (nil if none).
        public let oneTimePreKeyId: String?

        public init(identityKey: String,
                    ephemeralKey: String,
                    signedPreKeyId: String? = nil,
                    oneTimePreKeyId: String? = nil) {
            self.identityKey = identityKey
            self.ephemeralKey = ephemeralKey
            self.signedPreKeyId = signedPreKeyId
            self.oneTimePreKeyId = oneTimePreKeyId
        }

        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            self.identityKey = try c.decode(String.self, forKey: .identityKey)
            self.ephemeralKey = try c.decode(String.self, forKey: .ephemeralKey)
            self.signedPreKeyId = try c.decodeFlexibleKeyIdIfPresent(forKey: .signedPreKeyId)
            self.oneTimePreKeyId = try c.decodeFlexibleKeyIdIfPresent(forKey: .oneTimePreKeyId)
        }
    }

    /// A relay envelope — the exact wire shape accepted by `relay_v2.js`
    /// (`{ v:4, msgId, from, to, type, groupId?, x3dh?, header, ciphertext, ts }`).
    /// `header` and `ciphertext` are opaque base64 the server never reads.
    /// Optional fields are emitted only when non-nil (synthesized `encodeIfPresent`),
    /// so a 1:1 message omits `groupId`/`x3dh` exactly as the validator expects.
    public struct Envelope: Codable, Sendable, Equatable {
        /// Protocol version — MUST be 4 (`ENVELOPE_VERSION`).
        public let v: Int
        public let msgId: String
        /// Sender identity (base64 X25519 contact address — see
        /// `V2CryptoProviding.userKey`). The relay binds it to the request's
        /// verified identity (403 `sender-mismatch` once auth is enforced).
        public let from: String
        /// Recipient identity (base64 X25519 contact address). For a group the
        /// CLIENT fans out one envelope per member with `to` = that member.
        public let to: String
        public let type: MessageType
        /// Required iff `type == .group`.
        public let groupId: String?
        /// First-message-only X3DH initiator header (opaque, preserved verbatim).
        public let x3dh: X3DHHeader?
        /// Opaque Double-Ratchet header (base64).
        public let header: String
        /// Opaque ciphertext (base64). Relay caps this at 256 KB — big media
        /// goes through the blob store, never the relay.
        public let ciphertext: String
        /// Client timestamp in ms since epoch (opaque to the server).
        public let ts: Int64
        /// __PER_DEVICE_MAILBOX_2026_09_23__ The ONE recipient device this copy is encrypted
        /// for (32 hex), or nil for an account envelope. Encoded only when set, so every
        /// envelope of the flag-off path is byte-identical to before.
        public let toDevice: String?
        /// The sending device (32 hex), set on per-device copies and self-copies.
        public let fromDevice: String?

        public init(msgId: String,
                    from: String,
                    to: String,
                    type: MessageType,
                    groupId: String? = nil,
                    x3dh: X3DHHeader? = nil,
                    header: String,
                    ciphertext: String,
                    ts: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
                    v: Int = 4,
                    toDevice: String? = nil,
                    fromDevice: String? = nil) {
            self.v = v
            self.msgId = msgId
            self.from = from
            self.to = to
            self.type = type
            self.groupId = groupId
            self.x3dh = x3dh
            self.header = header
            self.ciphertext = ciphertext
            self.ts = ts
            self.toDevice = toDevice
            self.fromDevice = fromDevice
        }
    }

    /// A message as returned by `GET /v2/messages/:userKey` — the relay merges a
    /// server-assigned `seq` into the stored envelope (`{ seq, ...envelope }`).
    /// `x3dh`/`groupId` are present only when the sender included them.
    public struct ReceivedEnvelope: Codable, Sendable, Equatable {
        public let seq: Int
        public let v: Int
        public let msgId: String
        public let from: String
        public let to: String
        public let type: MessageType
        public let groupId: String?
        public let x3dh: X3DHHeader?
        public let header: String
        public let ciphertext: String
        public let ts: Int64
        /// __PER_DEVICE_MAILBOX_2026_09_23__ set on per-device copies (device-mode pulls).
        public var toDevice: String? = nil
        public var fromDevice: String? = nil
    }

    /// Result of a non-destructive pull. `maxSeq` is the highest seq observed and
    /// is what the caller should later `ack(upToSeq:)` once durably stored.
    public struct PullResult: Codable, Sendable, Equatable {
        public let messages: [ReceivedEnvelope]
        public let maxSeq: Int
    }

    // MARK: PreKeys (X3DH)

    /// A signed pre-key. `keyId` is stored/echoed by the server as whatever the
    /// publisher sent (string OR number), so decode is flexible; we always
    /// publish it as a string.
    public struct SignedPreKey: Codable, Sendable, Equatable {
        public let keyId: String
        public let key: String        // opaque base64 public key
        public let signature: String  // opaque base64 signature

        public init(keyId: String, key: String, signature: String) {
            self.keyId = keyId
            self.key = key
            self.signature = signature
        }

        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            self.keyId = try c.decodeFlexibleKeyId(forKey: .keyId)
            self.key = try c.decode(String.self, forKey: .key)
            self.signature = try c.decode(String.self, forKey: .signature)
        }
    }

    /// A one-time pre-key (consumable pool entry).
    public struct OneTimePreKey: Codable, Sendable, Equatable {
        public let keyId: String
        public let key: String        // opaque base64 public key

        public init(keyId: String, key: String) {
            self.keyId = keyId
            self.key = key
        }

        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            self.keyId = try c.decodeFlexibleKeyId(forKey: .keyId)
            self.key = try c.decode(String.self, forKey: .key)
        }
    }

    /// Everything needed to `POST /v2/keys/publish` for the local user. The
    /// `userKey` field is NOT here on purpose: `V2Client` fills it from its own
    /// signing identity so the published bundle is always self-bound (the server
    /// requires signingPubKey == userKey).
    public struct PreKeyBundleUpload: Sendable, Equatable {
        public let identityKey: String        // opaque base64
        public let signingKey: String         // opaque base64
        public let signedPreKey: SignedPreKey
        public let oneTimePreKeys: [OneTimePreKey]
        /// __CLIENT_CAPS_2026_08_31__ Ce que cette build sait faire. Defaut 0
        /// pour que tout appelant existant garde le comportement d'avant.
        public let caps: Int

        public init(identityKey: String,
                    signingKey: String,
                    signedPreKey: SignedPreKey,
                    oneTimePreKeys: [OneTimePreKey],
                    // __NO_SILENT_ZERO_2026_10_19__ This defaulted to 0, and a
                    // forgotten argument at ONE of three publish sites was
                    // enough to un-advertise every capability the client has —
                    // with no error, because 0 is a valid bitmask meaning
                    // "supports nothing". Required now: a publisher must say
                    // what it can read, and a future site cannot omit it by
                    // accident. See the republish path in V2MessageRouter.
                    caps: Int) {
            self.identityKey = identityKey
            self.signingKey = signingKey
            self.signedPreKey = signedPreKey
            self.oneTimePreKeys = oneTimePreKeys
            self.caps = caps
        }
    }

    /// A bundle fetched via `GET /v2/keys/:userKey`. The server pops AT MOST one
    /// one-time pre-key; `oneTimePreKey` is nil when the pool is exhausted (the
    /// initiator then falls back to the signed pre-key alone).
    public struct FetchedBundle: Codable, Sendable, Equatable {
        public let identityKey: String
        public let signingKey: String
        public let signedPreKey: SignedPreKey
        public let oneTimePreKey: OneTimePreKey?

        /// __CLIENT_CAPS_2026_08_31__ Drapeaux de capacite du pair.
        ///
        /// OPTIONNEL, et c'est structurel: un bundle publie avant ce changement
        /// ne porte pas le champ, et un decodage strict ferait echouer TOUTE
        /// establissement de session avec ce pair — on casserait la messagerie
        /// pour ajouter une optimisation. `nil` se lit comme « aucune capacite
        /// annoncee », c'est-a-dire le comportement d'avant.
        public let caps: Int?
    }

    // MARK: Blobs

    /// Response to `POST /v2/blobs` (reserve). `chunkSize` is the server's
    /// advisory plaintext chunk size; `missing` starts as every index.
    public struct ReserveResult: Codable, Sendable, Equatable {
        public let blobId: String
        public let chunkSize: Int
        public let missing: [Int]
    }

    /// Response to `GET /v2/blobs/:id/status`.
    public struct BlobStatus: Codable, Sendable, Equatable {
        public let blobId: String
        public let missing: [Int]
        public let committed: Bool
        public let expiresAt: Double   // ms epoch
    }

    /// User-facing file limit: 200 MB of PLAINTEXT. Enforce this on the raw file
    /// BEFORE encryption (in the router's sendFile), so "200 MB" means the user's
    /// file. Raised 50 → 200 MB once the send path streams-encrypts to disk
    /// (see V2MessageRouter.sendFile + streamingSendThreshold) instead of holding
    /// the whole ciphertext in RAM. MUST stay in lock-step with the server's
    /// `blob_store.js` MAX_PLAINTEXT_BYTES — a client that allows more than the
    /// server does just fails at reserve with 413.
    public static let maxPlaintextBytes = 200 * 1024 * 1024

    /// Ciphertext ceiling for a reserve, mirroring `blob_store.js` `MAX_BLOB_BYTES`
    /// (= 200 MB + 1 MiB AEAD slack). AEAD adds a 16-byte GCM tag per chunk + an
    /// encrypted manifest; at the 2 MiB default chunk size a 200 MB file is ~100
    /// chunks → ~1.6 KB of tags + a small manifest, so 1 MiB slack is ample.
    /// Enforced before reserve.
    public static let maxBlobBytes = 200 * 1024 * 1024 + 1024 * 1024

    /// Plaintext size at/above which `sendFile` switches from the in-memory
    /// encrypt path (whole ciphertext as `[Data]`) to the disk-streaming path
    /// (`encryptFileStreaming` → `uploadBlobFromDisk`). Below this, the simpler
    /// in-memory path is cheaper; above it, the streaming path keeps peak RAM at
    /// ~one chunk so a 200 MB send can't ~2× the file in memory and get jetsam'd.
    public static let streamingSendThreshold = 16 * 1024 * 1024

    // MARK: Errors

    /// Every failure surfaced by `V2Client`. Network data never force-unwraps;
    /// decode/HTTP problems land here as typed cases.
    public enum ClientError: Error, LocalizedError {
        case invalidURL(String)
        case notHTTPResponse
        case http(status: Int, reason: String?)
        case decoding(underlying: Error)
        case blobTooLarge(size: Int, max: Int)
        case invalidArgument(String)
        case missingSigningIdentity
        case chunkStillMissing([Int])

        public var errorDescription: String? {
            switch self {
            case .invalidURL(let s): return "V2Client: invalid URL: \(s)"
            case .notHTTPResponse: return "V2Client: response was not HTTP"
            case .http(let status, let reason):
                return "V2Client: HTTP \(status)\(reason.map { " — \($0)" } ?? "")"
            case .decoding(let e): return "V2Client: decode failed: \(e.localizedDescription)"
            case .blobTooLarge(let size, let max):
                return "V2Client: blob \(size) bytes exceeds \(max) byte cap"
            case .invalidArgument(let m): return "V2Client: invalid argument: \(m)"
            case .missingSigningIdentity:
                return "V2Client: no Ed25519 signing identity available to sign the request"
            case .chunkStillMissing(let idx):
                return "V2Client: chunks still missing after upload: \(idx)"
            }
        }

        /// Transient failures worth a backoff-retry (matches the classification
        /// used by the existing Pinata path): 429 + 5xx, plus an expired request
        /// signature.
        ///
        /// 🔧 BACKGROUND-UPLOAD FIX: `oshi_auth.js` enforces a 30 s freshness window
        /// on `x-oshi-timestamp`. A chunk handed to the BACKGROUND URLSession is sent
        /// whenever the OS decides to, which is routinely more than 30 s later, so it
        /// lands as a 401 `timestamp-expired`. That is not a permanent auth failure —
        /// `makeRequest` is rebuilt inside the retry closure, so the retry re-signs
        /// with a fresh timestamp and succeeds. Without this, turning background
        /// uploads on converts a slow send into a hard failure.
        public var isTransient: Bool {
            if case .http(let status, let reason) = self {
                if status == 429 || (500...599).contains(status) { return true }
                if status == 401, let reason, reason.contains("timestamp-expired") { return true }
            }
            return false
        }
    }
}

// MARK: - Crypto seam

/// The ONLY crypto dependency `V2Client` has. Defined as a thin protocol so the
/// transport layer type-checks and unit-tests independently of the sibling
/// `OSHICryptoV2` file being written in parallel. A concrete
/// `V2KeychainCrypto` implementation is provided below, mirroring the exact
/// Ed25519 signing scheme already used by `VoIPPushManager.signRequest`.
public protocol V2CryptoProviding: Sendable {
    /// This device's identity for the v2 servers: the base64 X25519 public key
    /// that the rest of OSHI already uses as the contact address
    /// (`IdentityManager.publicKey`). Used as the relay `from`/`to`, the blob
    /// `ownerKey`, the `x-oshi-user` header, and the prekey `userKey` — so
    /// peers can address each other with the identity string they already
    /// know. Requests are still authenticated with the separate Ed25519
    /// signing key (`signCanonical`); the prekey bundle publishes both keys,
    /// which is what lets the server bind signingKey↔userKey at registration
    /// (required before flipping `OSHI_AUTH_MODE=enforce`; `dual` mode accepts
    /// the pair as `identity-mismatch-grace`).
    var userKey: String { get }

    /// Ed25519-sign the canonical request bytes
    /// (`METHOD\nPATH\nSHA256(rawBody)\nTIMESTAMP`), returning the base64
    /// signature and the base64 signing public key. Throws if no key exists.
    func signCanonical(_ message: Data) throws -> (signature: String, signingPublicKey: String)
}

/// Default `V2CryptoProviding` backed by the Keychain, byte-for-byte compatible
/// with `VoIPPushManager.signRequest` (same "signingKey" Keychain item, same
/// `Curve25519.Signing` Ed25519 key, same base64 encodings). Introduces no new
/// key material and touches nothing outside its own reads.
public struct V2KeychainCrypto: V2CryptoProviding {
    /// Keychain item holding the raw Ed25519 private key — identical to the one
    /// `VoIPPushManager` already loads.
    private let keychainKey: String

    public init(keychainKey: String = "signingKey") {
        self.keychainKey = keychainKey
    }

    private func privateKey() throws -> Curve25519.Signing.PrivateKey {
        let data = try KeychainHelper.load(key: keychainKey)
        return try Curve25519.Signing.PrivateKey(rawRepresentation: data)
    }

    /// The legacy contact address: the X25519 key-agreement public key stored
    /// by `IdentityManager` under the "privateKey" Keychain item. This — not
    /// the Ed25519 signing key — is the identity every peer already has for
    /// us, so it is the only string they can address v2 envelopes/bundles to.
    public var userKey: String {
        if let raw = try? KeychainHelper.load(key: "privateKey"),
           let key = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: raw) {
            return key.publicKey.rawRepresentation.base64EncodedString()
        }
        // Fallback: IdentityManager mirrors the same value to UserDefaults.
        return UserDefaults.standard.string(forKey: "publicKey") ?? ""
    }

    public func signCanonical(_ message: Data) throws -> (signature: String, signingPublicKey: String) {
        let key = try privateKey()
        let signature = try key.signature(for: message)
        return (signature.base64EncodedString(),
                key.publicKey.rawRepresentation.base64EncodedString())
    }
}

// MARK: - Flexible keyId decoding

/// `keyId` is opaque to the servers, which store/echo it as EITHER a JSON string
/// OR a number depending on what a client sent. We normalise both to `String` on
/// the way in so callers get one stable type.
extension KeyedDecodingContainer {
    func decodeFlexibleKeyId(forKey key: Key) throws -> String {
        if let s = try? decode(String.self, forKey: key) { return s }
        if let i = try? decode(Int.self, forKey: key) { return String(i) }
        if let d = try? decode(Double.self, forKey: key) { return String(d) }
        throw DecodingError.dataCorruptedError(
            forKey: key, in: self,
            debugDescription: "keyId is neither String nor Number")
    }

    func decodeFlexibleKeyIdIfPresent(forKey key: Key) throws -> String? {
        guard contains(key), try decodeNil(forKey: key) == false else { return nil }
        return try decodeFlexibleKeyId(forKey: key)
    }
}


// MARK: - Per-device mailbox (__PER_DEVICE_MAILBOX_2026_09_23__)
//
// `ServerPatches/per_device_mailbox/CLIENT_SPEC.md` §2 (normative) and
// `docs/OSHI_DEVICE_SYNC_DIRECT.md` §15. Golden vectors: `device_vectors.json`, reproduced
// by `DeviceMailboxSelfTest` (DEBUG, `-OSHISelfTestMultiDevice`).
//
// Two keys per INSTALL, never exported, never backed up (Keychain,
// AfterFirstUnlockThisDeviceOnly via `KeychainHelper`):
//   • `dk`  X25519 — the devsync Noise static key. THE SAME KEYCHAIN ITEM devsync uses
//           (`DevSyncIdentity.deviceKeyKeychainItem`, "oshi.devsync.deviceKey.v1"): the
//           mailbox deviceId and the devsync deviceId are one id. Whichever side runs first
//           creates it; neither ever replaces an item it merely cannot read (locked device).
//   • `dsk` Ed25519 — signs this device's mailbox requests and its approvals of other devices.
public nonisolated enum V2Device {

    /// Must stay equal to `DevSyncIdentity.deviceKeyKeychainItem` (V2Client+Sync.swift).
    public static let dkAccount = "oshi.devsync.deviceKey.v1"
    public static let dskAccount = "oshi.devmailbox.dsk.v1"

    public enum KeyError: Error { case keychainUnavailable }

    /// Read-or-create one raw 32-byte key item. Creates ONLY on `itemNotFound`: a locked
    /// Keychain (before first unlock) must never make us mint a second device identity.
    private static func loadOrCreateRaw(_ account: String, make: () -> Data) throws -> Data {
        do {
            let d = try KeychainHelper.load(key: account)
            guard d.count == 32 else { throw KeyError.keychainUnavailable }
            return d
        } catch KeychainError.itemNotFound {
            let k = make()
            try KeychainHelper.save(key: account, data: k)
            return k
        }
    }

    /// `hex(SHA-256("OSHI-DEVSYNC-id" ‖ dk_raw))[0..32]`.
    public static func deviceId(dk: Data) -> String {
        var d = Data("OSHI-DEVSYNC-id".utf8); d.append(dk)
        return SHA256.hash(data: d).map { String(format: "%02x", $0) }.joined().prefix(32).description
    }

    public static func isDeviceId(_ s: String?) -> Bool {
        guard let s, s.count == 32 else { return false }
        return s.allSatisfy { ("0"..."9").contains($0) || ("a"..."f").contains($0) }
    }

    /// The account identity in its normalised spelling (standard base64, padded). The proof
    /// strings are compared byte for byte by the server.
    public static func normalizeIdentity(_ s: String) -> String {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        t = t.trimmingCharacters(in: CharacterSet(charactersIn: "="))
        while t.count % 4 != 0 { t += "=" }
        return t
    }

    public static func registerProofString(identity: String, deviceId: String, dk: String, dsk: String, ts: String) -> String {
        "OSHI-DEVICE-REGISTER/1\n\(identity)\n\(deviceId)\n\(dk)\n\(dsk)\n\(ts)"
    }

    public static func approvalString(identity: String, deviceId: String, dsk: String, ts: String) -> String {
        "OSHI-DEVICE-APPROVE/1\n\(identity)\n\(deviceId)\n\(dsk)\n\(ts)"
    }

    public static func requestString(deviceId: String, method: String, path: String, bodySHA256Hex: String, ts: String) -> String {
        "OSHI-DEVICE/1\n\(deviceId)\n\(method)\n\(path)\n\(bodySHA256Hex)\n\(ts)"
    }

    /// This install's device keys.
    public struct Keys: @unchecked Sendable {
        public let dk: Curve25519.KeyAgreement.PrivateKey
        public let dsk: Curve25519.Signing.PrivateKey
        public var dkB64: String { dk.publicKey.rawRepresentation.base64EncodedString() }
        public var dskB64: String { dsk.publicKey.rawRepresentation.base64EncodedString() }
        public var deviceId: String { V2Device.deviceId(dk: dk.publicKey.rawRepresentation) }

        public init(dk: Curve25519.KeyAgreement.PrivateKey, dsk: Curve25519.Signing.PrivateKey) {
            self.dk = dk
            self.dsk = dsk
        }

        /// Ed25519 over the UTF-8 string, standard base64.
        public func sign(_ message: String) throws -> String {
            try dsk.signature(for: Data(message.utf8)).base64EncodedString()
        }

        public var signer: Signer {
            let key = dsk
            return Signer(deviceId: deviceId, sign: { try key.signature(for: $0).base64EncodedString() })
        }
    }

    /// What `V2Client` needs to add the two device headers to a request.
    public struct Signer: Sendable {
        public let deviceId: String
        public let sign: @Sendable (Data) throws -> String
    }

    /// Read the keys, or nil when this install never created them.
    public static func loadKeys() -> Keys? {
        guard let dkRaw = try? KeychainHelper.load(key: dkAccount),
              let dskRaw = try? KeychainHelper.load(key: dskAccount),
              let dk = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: dkRaw),
              let dsk = try? Curve25519.Signing.PrivateKey(rawRepresentation: dskRaw) else { return nil }
        return Keys(dk: dk, dsk: dsk)
    }

    /// Read or create. Each key is created independently so a devsync build that created
    /// `dk` first keeps its id when the mailbox adds `dsk`.
    public static func loadOrCreateKeys() throws -> Keys {
        let dkRaw = try loadOrCreateRaw(dkAccount) { Curve25519.KeyAgreement.PrivateKey().rawRepresentation }
        let dskRaw = try loadOrCreateRaw(dskAccount) { Curve25519.Signing.PrivateKey().rawRepresentation }
        return Keys(dk: try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: dkRaw),
                    dsk: try Curve25519.Signing.PrivateKey(rawRepresentation: dskRaw))
    }

    /// `409 device-conflict` (should never happen): a new id for this install.
    public static func regenerateKeys() throws -> Keys {
        deleteKeys()
        return try loadOrCreateKeys()
    }

    public static func deleteKeys() {
        try? KeychainHelper.delete(key: dkAccount)
        try? KeychainHelper.delete(key: dskAccount)
    }

    /// `POST /v2/devices/register` body.
    public struct Registration: Encodable, Sendable {
        public struct Approval: Codable, Sendable, Equatable {
            public let by: String
            public let ts: String
            public let sig: String
            public init(by: String, ts: String, sig: String) { self.by = by; self.ts = ts; self.sig = sig }
        }
        public let deviceId: String
        public let dk: String
        public let dsk: String
        public let ts: String
        public let proof: String
        public let approval: Approval?
        public let adoptBacklog: Bool
        public let retireLegacy: Bool
    }

    /// Build the signed registration for `identity` (normalised inside).
    public static func registration(keys: Keys, identity: String, approval: Registration.Approval?,
                                    now: Date = Date(), retireLegacy: Bool = false) throws -> Registration {
        let ts = String(Int64(now.timeIntervalSince1970 * 1000))
        let proof = try keys.sign(registerProofString(identity: normalizeIdentity(identity), deviceId: keys.deviceId,
                                                      dk: keys.dkB64, dsk: keys.dskB64, ts: ts))
        return Registration(deviceId: keys.deviceId, dk: keys.dkB64, dsk: keys.dskB64, ts: ts, proof: proof,
                            approval: approval, adoptBacklog: true, retireLegacy: retireLegacy)
    }

    /// The approval an already-registered device gives a new one (§3.2), carried in the
    /// devsync `APPROVAL` record as `mailbox`.
    public static func approve(newDeviceId: String, newDsk: String, identity: String, approver: Keys,
                               now: Date = Date()) throws -> Registration.Approval {
        let ts = String(Int64(now.timeIntervalSince1970 * 1000))
        let sig = try approver.sign(approvalString(identity: normalizeIdentity(identity), deviceId: newDeviceId,
                                                   dsk: newDsk, ts: ts))
        return .init(by: approver.deviceId, ts: ts, sig: sig)
    }

    /// One row of `GET /v2/devices`.
    public struct Record: Codable, Sendable, Equatable {
        public let deviceId: String
        public let dk: String?
        public let dsk: String?
        public let registeredAt: Double?
        public let lastSeenAt: Double?
        public let approvedBy: String?
        public let hasBundle: Bool?
    }

    /// A device of a PEER (`GET /v2/keys/:userKey/devices`).
    public struct PeerDevice: Codable, Sendable, Equatable {
        public let deviceId: String
        public let caps: Int?
    }

    /// `409 device-list-mismatch {to, missing, stale, devices}`: the sender's view of `to`'s
    /// devices is out of date. `devices` is the server's current bundled list.
    public struct ListMismatch: Error, Sendable, Equatable {
        public let to: String
        public let missing: [String]
        public let stale: [String]
        public let devices: [String]
    }
}
