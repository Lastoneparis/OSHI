//
//  V2SyncModels.swift
//  OSHI — v2 MULTI-DEVICE SAME-KEY SYNC: typed models for the `/v2/sync` archive.
//
//  DEFENSIVE / ADDITIVE INFRASTRUCTURE — the cross-device archive layer.
//  ---------------------------------------------------------------------------
//  This is the client-side model layer for the app owner's own same-key sync
//  archive (server: `sync_store.js`, mounted under `/v2/sync`). ONE human
//  identity (one Ed25519 identity key) imported on web + iOS + iPad sees the
//  SAME history everywhere by pushing/pulling an APPEND-ONLY, END-TO-END
//  ENCRYPTED log keyed to the identity's PUBLIC key. The server stores only
//  opaque ciphertext tagged by an opaque `kind`; it can never read any of it.
//
//  Relationship to the rest of the stack (per MULTI_DEVICE_SYNC.md):
//    * LIVE transport stays on the relay + Double-Ratchet (forward secret).
//    * THIS archive trades per-message forward secrecy for same-key
//      availability: the archive key is derived from the long-lived identity
//      key so ANY device holding that key can decrypt the whole log. That is
//      the inherent cost of "same key sees everything, everywhere"; it is still
//      strictly better than a non-E2E cloud, where the SERVER holds plaintext.
//
//  ENTIRELY ADDITIVE: only NEW `V2`-namespaced value types are defined here. It
//  does not touch MessageManager, IdentityManager, PinataConfig, or the bodies
//  of the existing V2 files. Everything is Codable/typed so the network layer
//  never force-unwraps decoded data.
//
//  No iOS runtime in the authoring environment; verified by reading against
//  `sync_store.js` + `oshi_auth.js`. Runtime + integration testing on a Mac.
//

import Foundation

extension V2 {

    // MARK: - Archive item (wire)

    /// One append-only archive item as pushed to `POST /v2/sync/:userKey`.
    /// `kind`, `ciphertext`, and `deviceId` are OPAQUE to the server (it bounds
    /// their size but never interprets them). `ciphertext` is base64 of
    /// `nonce(12) || AES-256-GCM(ct) || tag(16)` sealed under the archive key.
    /// `itemId` is the idempotency key: a re-push of the same `itemId` is a
    /// server-side no-op that returns the EXISTING seq.
    public struct SyncItem: Codable, Sendable, Equatable {
        public let itemId: String
        public let kind: String
        public let ciphertext: String   // base64( nonce || ct || tag )
        public let deviceId: String
        public let ts: Int64            // client ms-since-epoch (opaque to server)

        public init(itemId: String,
                    kind: String,
                    ciphertext: String,
                    deviceId: String,
                    ts: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
            self.itemId = itemId
            self.kind = kind
            self.ciphertext = ciphertext
            self.deviceId = deviceId
            self.ts = ts
        }
    }

    /// One item as returned by `GET /v2/sync/:userKey?after=` — the server merges
    /// its monotonic `seq` into the stored item (`{ seq, itemId, kind, ciphertext,
    /// deviceId, ts }`). Devices page deterministically by `seq`.
    public struct SyncPulledItem: Codable, Sendable, Equatable {
        public let seq: Int
        public let itemId: String
        public let kind: String
        public let ciphertext: String
        public let deviceId: String
        public let ts: Int64
    }

    // MARK: - Result envelopes

    /// One `{ itemId, seq }` acknowledgement in a push response. For a duplicate
    /// `itemId` the server echoes the EXISTING seq (idempotent).
    public struct SyncAccepted: Codable, Sendable, Equatable {
        public let itemId: String
        public let seq: Int
    }

    /// Response to `POST /v2/sync/:userKey`.
    public struct SyncPushResult: Codable, Sendable, Equatable {
        public let accepted: [SyncAccepted]
        public let maxSeq: Int
    }

    /// Response to `GET /v2/sync/:userKey?after=&limit=` (non-destructive pull).
    public struct SyncPullResult: Codable, Sendable, Equatable {
        public let items: [SyncPulledItem]
        public let maxSeq: Int
    }

    /// Response to `GET /v2/sync/:userKey/head` — lets a device know if it's
    /// behind without transferring the log.
    public struct SyncHead: Codable, Sendable, Equatable {
        public let maxSeq: Int
        public let count: Int
    }

    /// Response to `POST /v2/sync/:userKey/checkpoint`.
    public struct SyncCheckpointResult: Codable, Sendable, Equatable {
        public let trimmed: Int
        public let remaining: Int
    }

    // MARK: - Record kinds

    /// The small opaque `kind` tag on each archive item. Values are stable wire
    /// strings; an UNKNOWN kind (produced by a newer client) decodes to
    /// `.unknown` while the raw string is preserved on `SyncInboundRecord`, so an
    /// older client never crashes on a kind it doesn't recognise — it just skips
    /// applying it. Big media is NEVER archived inline: only a `.file` pointer
    /// record `{ blobId, fileKey, … }` is (the bytes stay in the blob store).
    public enum SyncKind: String, Codable, Sendable, CaseIterable {
        case message = "msg"        // a message pointer / small message record
        case readState = "read"     // read / seen markers
        case contact = "contact"    // added/updated contact
        case group = "group"        // group membership / metadata
        case file = "file"          // { blobId, fileKey } pointer — NOT the bytes
        case unknown = "unknown"    // forward-compat sentinel (never pushed)
    }

    // MARK: - Decoded inbound record

    /// A decrypted archive item handed to the apply-closure by `V2SyncManager`.
    /// `payload` is the plaintext bytes (typically JSON) of the record; the
    /// integration layer decodes it per `kind`. Apply MUST be idempotent on
    /// `itemId` (a late-joining device replays the whole non-destructive log,
    /// and last-writer-wins is resolved by `ts` for mutable kinds).
    public struct SyncInboundRecord: Sendable, Equatable {
        public let kind: SyncKind
        public let rawKind: String
        public let itemId: String
        public let seq: Int
        public let deviceId: String
        public let ts: Int64
        public let payload: Data

        public init(kind: SyncKind,
                    rawKind: String,
                    itemId: String,
                    seq: Int,
                    deviceId: String,
                    ts: Int64,
                    payload: Data) {
            self.kind = kind
            self.rawKind = rawKind
            self.itemId = itemId
            self.seq = seq
            self.deviceId = deviceId
            self.ts = ts
            self.payload = payload
        }
    }

    // MARK: - File pointer record

    /// The ONLY thing archived for media: a small pointer to the blob-store bytes
    /// plus the per-file key needed to decrypt them. The bytes themselves are
    /// never placed in the archive (they stay E2E in the blob store). Push this
    /// as a `.file` record via `V2SyncManager.archive(_:kind:itemId:)`.
    public struct SyncFilePointer: Codable, Sendable, Equatable {
        public let blobId: String
        public let fileKey: String      // base64 (32 bytes)
        public let fileNonce: String    // base64 (8 bytes)
        public let chunkCount: Int
        public let manifest: String     // base64 (ct || tag)
        public let filename: String
        public let mime: String

        public init(blobId: String,
                    fileKey: String,
                    fileNonce: String,
                    chunkCount: Int,
                    manifest: String,
                    filename: String,
                    mime: String) {
            self.blobId = blobId
            self.fileKey = fileKey
            self.fileNonce = fileNonce
            self.chunkCount = chunkCount
            self.manifest = manifest
            self.filename = filename
            self.mime = mime
        }
    }

    // MARK: - Pull summary

    /// What one `V2SyncManager.pull()` accomplished (for callers/telemetry).
    public struct SyncPullSummary: Sendable, Equatable {
        /// Number of items successfully decrypted + handed to the apply-closure.
        public let applied: Int
        /// The highest seq durably consumed (== the new persisted `lastSeq`).
        public let lastSeq: Int
        /// The archive head at the time the pull started.
        public let headMaxSeq: Int

        public init(applied: Int, lastSeq: Int, headMaxSeq: Int) {
            self.applied = applied
            self.lastSeq = lastSeq
            self.headMaxSeq = headMaxSeq
        }
    }

    // MARK: - Errors

    /// Failures specific to the sync-archive layer. Network/HTTP/decode problems
    /// from the transport reuse `V2.ClientError`; these cover the crypto +
    /// identity concerns unique to the archive.
    public enum SyncError: Error, LocalizedError {
        case missingIdentity
        case archiveKeyUnavailable
        case malformedCiphertext
        case decryptFailed(underlying: Error)
        case encodeFailed(underlying: Error)

        public var errorDescription: String? {
            switch self {
            case .missingIdentity:
                return "V2Sync: no Ed25519 identity available to key the archive"
            case .archiveKeyUnavailable:
                return "V2Sync: could not derive the archive key from the identity key"
            case .malformedCiphertext:
                return "V2Sync: archive item ciphertext was not valid base64 / too short"
            case .decryptFailed(let e):
                return "V2Sync: archive item failed to decrypt: \(e.localizedDescription)"
            case .encodeFailed(let e):
                return "V2Sync: archive record failed to encode: \(e.localizedDescription)"
            }
        }
    }
}
