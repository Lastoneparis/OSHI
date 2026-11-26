//
//  V2Client.swift
//  OSHI — networking client for the v2 end-to-end server stack.
//
//  DEFENSIVE, PURELY ADDITIVE TRANSPORT LAYER.
//  This is the Swift client for the app owner's own v2 VPS services:
//
//    • PreKey server  (prekey_server.js)  — /v2/keys      X3DH bundle distribution
//    • Blob store     (blob_store.js)     — /v2/blobs      chunked, resumable, E2E
//    • Blind relay    (relay_v2.js)       — /v2/messages   store-and-forward
//
//  All three are gated by the shared request-signing scheme in oshi_auth.js
//  (Ed25519 over `METHOD\nPATH\nSHA256(rawBody)\nTIMESTAMP`, headers
//  x-oshi-signature / x-oshi-timestamp / x-oshi-signing-pubkey). The signing
//  here mirrors, byte-for-byte, the scheme already used by
//  `VoIPPushManager.signRequest` (which is NOT modified).
//
//  SCOPE / SAFETY:
//    • ADDITIVE ONLY. Nothing here touches or alters the existing IPFS / Pinata
//      path (PinataConfig, PinataService, BackgroundMediaUploader) or the
//      message pipeline. It defines new `V2`-namespaced types exclusively.
//    • This is transport ONLY — it is deliberately NOT wired into
//      MessageManager. That integration is a separate step.
//    • Crypto is reached solely through the `V2CryptoProviding` seam so this
//      file type-checks independently of the sibling OSHICryptoV2 file.
//
//  There is no iOS runtime in the authoring environment; this was validated by
//  careful reading against the Node servers. Runtime + integration testing
//  happen on a Mac.
//

import Foundation
import CryptoKit
import os
import Security

// MARK: - V2Client

/// Async networking client for the OSHI v2 E2E stack. One instance is cheap;
/// share one if you like, but it holds no per-request mutable state.
public nonisolated final class V2Client: @unchecked Sendable {

    // MARK: Config

    /// Endpoint + policy configuration. The base URL is overridable so the app
    /// can point at staging / a local server in tests.
    public struct Config: Sendable {
        /// Scheme+host only, e.g. `https://oshi-messenger.com`. The `/v2/*`
        /// route prefixes are appended by the client.
        public var baseURL: URL
        /// Request timeout for control-plane calls (reserve/commit/pull/…).
        public var requestTimeout: TimeInterval
        /// Retry attempts for transient (429 / 5xx / network) failures.
        public var maxRetryAttempts: Int

        public init(baseURL: URL = Config.defaultBaseURL,
                    requestTimeout: TimeInterval = 30,
                    maxRetryAttempts: Int = 3) {
            self.baseURL = baseURL
            self.requestTimeout = requestTimeout
            self.maxRetryAttempts = maxRetryAttempts
        }

        public static let `default` = Config()

        /// Production host. DEBUG builds may point every `V2Client()` at a local server
        /// with `OSHI_V2_BASE_URL` (simulator runs of the multi-device self-test against
        /// `ServerPatches/per_device_mailbox/v2`). Release: always production.
        public static var defaultBaseURL: URL {
            #if DEBUG
            if let s = ProcessInfo.processInfo.environment["OSHI_V2_BASE_URL"], let u = URL(string: s) { return u }
            #endif
            return URL(string: "https://oshi-messenger.com")!
        }
    }

    private let config: Config
    private let crypto: V2CryptoProviding
    private let session: URLSession
    private let backgroundUploader: V2BackgroundBlobUploader
    private let log = Logger(subsystem: "com.oshi.v2", category: "V2Client")

    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        // Deterministic, compact output. (Order does not affect correctness —
        // we always sign the EXACT bytes we send — but keeps logs stable.)
        e.outputFormatting = [.withoutEscapingSlashes]
        return e
    }()
    private let decoder = JSONDecoder()

    /// - Parameters:
    ///   - config: endpoint/policy config (overridable base URL).
    ///   - crypto: the Ed25519 signing seam. Defaults to the Keychain-backed
    ///     implementation matching `VoIPPushManager`.
    ///   - session: URLSession for control-plane + foreground chunk transfers.
    public init(config: Config = .default,
                crypto: V2CryptoProviding = V2KeychainCrypto(),
                session: URLSession = V2Client.pinnedSession) {
        self.config = config
        self.crypto = crypto
        self.session = session
        self.backgroundUploader = V2BackgroundBlobUploader.shared
    }

    // MARK: - PreKey API  (/v2/keys)

    /// Publish (or replace) THIS user's X3DH bundle and append one-time pre-keys.
    /// `userKey` is bound to the local signing identity automatically. Returns
    /// the server's reported remaining one-time-prekey count.
    @discardableResult
    public func publishBundle(_ bundle: V2.PreKeyBundleUpload) async throws -> Int {
        let me = try requireIdentity()
        struct Body: Encodable {
            let userKey: String
            let identityKey: String
            let signingKey: String
            let signedPreKey: V2.SignedPreKey
            let oneTimePreKeys: [V2.OneTimePreKey]
            /// __CLIENT_CAPS_2026_08_31__ Ce que cette build sait lire. Un
            /// serveur anterieur ignore le champ; un client anterieur n'en
            /// envoie pas et le serveur stocke 0.
            let caps: Int
        }
        let body = try encoder.encode(Body(
            userKey: me,
            identityKey: bundle.identityKey,
            signingKey: bundle.signingKey,
            signedPreKey: bundle.signedPreKey,
            oneTimePreKeys: bundle.oneTimePreKeys,
            caps: bundle.caps))

        let req = try makeRequest(method: "POST",
                                  path: "/v2/keys/publish",
                                  body: body,
                                  contentType: "application/json")
        struct Resp: Decodable { let ok: Bool?; let remaining: Int }
        let resp: Resp = try await sendDecoding(req)
        return resp.remaining
    }

    /// Fetch a peer's bundle to bootstrap an X3DH session. The server pops AT
    /// MOST one one-time pre-key (nil when the pool is drained).
    public func fetchBundle(userKey: String) async throws -> V2.FetchedBundle {
        let req = try makeRequest(method: "GET",
                                  path: "/v2/keys/\(encodeIdentity(userKey))",
                                  body: nil)
        return try await sendDecoding(req)
    }

    /// Fetch the remote client config (`GET /v2/config`) — the production
    /// kill switch / staged-rollout lever. Unauthenticated, no signing; returns
    /// the raw JSON bytes for the caller to decode + cache. Short timeout so a
    /// slow/unreachable server never blocks the flag check for long.
    public func fetchClientConfig() async throws -> Data {
        let url = config.baseURL.appendingPathComponent("v2/config")
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.timeoutInterval = 8
        req.cachePolicy = .reloadIgnoringLocalCacheData
        let (data, resp) = try await V2Transport.data(for: req, session: session)
        guard let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw V2.ClientError.http(status: (resp as? HTTPURLResponse)?.statusCode ?? -1, reason: "config fetch")
        }
        return data
    }

    /// __GROUP_INFO_SETTINGS_2026_11_20__ Public group handle registry
    /// (GROUP_INFO_SETTINGS_SPEC §4.2). GET is UNSIGNED — signing a lookup would
    /// tell the server who looks at which public group. PUT / DELETE are signed
    /// and carry `x-oshi-user`. Returns the status instead of throwing on non-2xx:
    /// 404 / 409 are answers here, not failures.
    public func groupHandleRequest(method: String, handle: String, body: Data?) async throws -> (Int, Data) {
        // Same rule as GroupSettings.normalizedHandle; inlined because this file is
        // also compiled into the share extension, which has no group code.
        let h = handle.lowercased()
        guard (5...32).contains(h.unicodeScalars.count),
              h.unicodeScalars.allSatisfy({ ("a"..."z").contains($0) || ("0"..."9").contains($0) || $0 == "_" })
        else { return (404, Data()) }
        let path = "/v2/g/\(h)"
        var req: URLRequest
        if method == "GET" {
            guard let url = URL(string: path, relativeTo: config.baseURL)?.absoluteURL else {
                throw V2.ClientError.invalidURL(path)
            }
            req = URLRequest(url: url)
            req.httpMethod = "GET"
            req.timeoutInterval = 15
            req.cachePolicy = .reloadIgnoringLocalCacheData
        } else {
            req = try makeRequest(method: method, path: path, body: body,
                                  contentType: body == nil ? nil : "application/json",
                                  withUserHeader: true)
        }
        let (data, resp) = try await V2Transport.data(for: req, session: session)
        return ((resp as? HTTPURLResponse)?.statusCode ?? -1, data)
    }

    /// __CHANNEL_WEB_2026_11_26__ Public channel web preview
    /// (ServerVPS/v2/PUBLIC_CHANNEL_WEB_SPEC.md). `path` is built and validated by the
    /// caller (`ChannelWeb.path…`: handle `[a-z0-9_]`, postId `[A-Za-z0-9._:-]`, so no
    /// percent-encoding is ever needed); `query` is appended unsigned. Writes and the
    /// admin GET are signed with `x-oshi-user` (re-signed with a fresh timestamp on
    /// every call — the server refuses replays); the public reads are NOT signed, for
    /// the same reason as the group-handle lookup. Returns the status instead of
    /// throwing on non-2xx: the spec's 4xx codes are answers the caller acts on.
    public func channelWebRequest(method: String, path: String, query: String? = nil, body: Data?,
                                  contentType: String?, signed: Bool,
                                  timeout: TimeInterval? = nil) async throws -> (Int, Data) {
        let full = query.map { "\(path)?\($0)" } ?? path
        var req: URLRequest
        if signed {
            req = try makeRequest(method: method, path: full, body: body, contentType: contentType,
                                  withUserHeader: true)
        } else {
            guard let url = URL(string: full, relativeTo: config.baseURL)?.absoluteURL else {
                throw V2.ClientError.invalidURL(full)
            }
            req = URLRequest(url: url)
            req.httpMethod = method
            req.cachePolicy = .reloadIgnoringLocalCacheData
            if let body { req.httpBody = body }
            if let contentType { req.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        }
        req.timeoutInterval = timeout ?? 20
        let (data, resp) = try await V2Transport.data(for: req, session: session)
        return ((resp as? HTTPURLResponse)?.statusCode ?? -1, data)
    }

    /// How many one-time pre-keys remain for `userKey` (for refill decisions).
    public func prekeyCount(userKey: String) async throws -> Int {
        let req = try makeRequest(method: "GET",
                                  path: "/v2/keys/\(encodeIdentity(userKey))/count",
                                  body: nil)
        struct Resp: Decodable { let remaining: Int }
        let resp: Resp = try await sendDecoding(req)
        return resp.remaining
    }

    // MARK: - Blob API  (/v2/blobs)

    // MARK: - Missing-set arithmetic (pure — unit-tested)
    //
    // Both the fresh-reserve and the resume flows drive straight off a server
    // missing-set. That arithmetic used to be five inline `.filter` closures with
    // no test coverage; it is the difference between "top up 3 chunks" and
    // "re-upload 90 MB", so it lives here as pure functions instead.

    /// The chunk indices this attempt should PUT, given the server's missing-set.
    ///
    /// Out-of-range indices are DROPPED rather than throwing, because this runs on
    /// the happy path where `missing` came from a reservation we just made (so it
    /// is `0..<chunkCount` by construction) or from a resume that `resumeIsUsable`
    /// already vetted. `uploadChunks` — the standalone entry point, where `missing`
    /// has been vetted by nobody — throws instead. Note the consequence: if a
    /// server ever DID report an index we cannot supply, this silently skips it and
    /// `commit` then 409s with that index still missing. That is the correct
    /// failure (visible, retryable) rather than a truncated file.
    static func chunksToUpload(missing: [Int], chunkCount: Int) -> [Int] {
        missing.filter { $0 >= 0 && $0 < chunkCount }
    }

    /// Whether a remembered blobId's live status can still be topped up for THIS
    /// attempt. Rejects a status whose missing-set does not fit the chunk count we
    /// are about to upload — that record describes different content, so resuming
    /// into it would produce a blob whose chunks disagree. An empty missing-set is
    /// usable (nothing left to send).
    static func resumeIsUsable(missing: [Int], expectedChunkCount: Int) -> Bool {
        missing.allSatisfy { $0 >= 0 && $0 < expectedChunkCount }
    }

    /// Reserve a blob. Enforces the 50 MB client-side cap BEFORE the round-trip.
    /// `ownerKey` must be the local identity (the server binds it to the signing
    /// pubkey); it defaults to that identity.
    public func reserve(ownerKey: String? = nil,
                        recipientKey: String,
                        recipientKeys: [String]? = nil,
                        totalSize: Int,
                        chunkCount: Int) async throws -> V2.ReserveResult {
        guard totalSize > 0 else { throw V2.ClientError.invalidArgument("totalSize must be > 0") }
        guard totalSize <= V2.maxBlobBytes else {
            throw V2.ClientError.blobTooLarge(size: totalSize, max: V2.maxBlobBytes)
        }
        guard chunkCount >= 1 else { throw V2.ClientError.invalidArgument("chunkCount must be >= 1") }
        let owner = try (ownerKey ?? requireIdentity())

        let body = try Self.reserveBody(encoder: encoder, ownerKey: owner, recipientKey: recipientKey,
                                        recipientKeys: recipientKeys, totalSize: totalSize, chunkCount: chunkCount)
        // 🔧 RESILIENCE FIX: retried like the chunk PUTs are. A single transient 5xx
        // here aborted the whole V2 send and dropped the message into the legacy
        // ~13 MB Pinata chain. Rebuilding the request inside the closure also
        // re-signs it, so a stale timestamp can't poison the retry.
        return try await withV2Retry(label: "reserve") {
            let req = try self.makeRequest(method: "POST",
                                           path: "/v2/blobs",
                                           body: body,
                                           contentType: "application/json")
            return try await self.sendDecoding(req)
        }
    }

    /// The `POST /v2/blobs` body. `recipientKeys` is written only when non-nil
    /// (__GROUP_BLOB_2026_11_25__), so a 1:1 reservation sends exactly the body it always did.
    static func reserveBody(encoder: JSONEncoder, ownerKey: String, recipientKey: String,
                            recipientKeys: [String]?, totalSize: Int, chunkCount: Int) throws -> Data {
        struct Body: Encodable {
            let ownerKey: String
            let recipientKey: String
            let recipientKeys: [String]?
            let totalSize: Int
            let chunkCount: Int
            private enum CodingKeys: String, CodingKey { case ownerKey, recipientKey, recipientKeys, totalSize, chunkCount }
            func encode(to encoder: Encoder) throws {
                var c = encoder.container(keyedBy: CodingKeys.self)
                try c.encode(ownerKey, forKey: .ownerKey)
                try c.encode(recipientKey, forKey: .recipientKey)
                try c.encodeIfPresent(recipientKeys, forKey: .recipientKeys)
                try c.encode(totalSize, forKey: .totalSize)
                try c.encode(chunkCount, forKey: .chunkCount)
            }
        }
        return try encoder.encode(Body(ownerKey: ownerKey, recipientKey: recipientKey, recipientKeys: recipientKeys,
                                       totalSize: totalSize, chunkCount: chunkCount))
    }

    /// __GROUP_BLOB_2026_11_25__ Upload ONE blob for a whole group message.
    ///
    /// Reserves with `recipientKey = recipientKeys[0]` AND the full `recipientKeys` list. A server
    /// running ServerPatches/group_blob_2026_11_25 stores the list and echoes `recipients: N`:
    /// every listed member may read the blob (`shared == true`). An older server ignores the list
    /// and lets only `recipientKey` read it: the result is `shared == false` and the caller
    /// uploads one blob per remaining member (the old path). Same resumable contract as
    /// `uploadBlob`: `resumeBlobId` tops up whatever `/status` still reports missing, `onReserved`
    /// fires the instant a reservation lands (before the first chunk) so a killed app resumes.
    /// `chunk(n)` hands out the already-sealed ciphertext of chunk n (memory or disk).
    public func uploadGroupBlob(recipientKeys: [String],
                                chunkCount: Int,
                                totalSize: Int,
                                chunk: @escaping @Sendable (Int) throws -> Data,
                                useBackground: Bool = true,
                                resumeBlobId: String? = nil,
                                resumeShared: Bool = false,
                                onReserved: (@Sendable (String, Bool) -> Void)? = nil) async throws -> (blobId: String, shared: Bool) {
        guard let first = recipientKeys.first else { throw V2.ClientError.invalidArgument("no recipients") }
        guard chunkCount >= 1 else { throw V2.ClientError.invalidArgument("no chunks to upload") }
        guard totalSize <= V2.maxBlobBytes else {
            throw V2.ClientError.blobTooLarge(size: totalSize, max: V2.maxBlobBytes)
        }
        let put: @Sendable (String, Int) async throws -> Void = { [self] blobId, n in
            try await self.uploadChunk(blobId: blobId, index: n, data: try chunk(n), useBackground: useBackground)
        }
        if let resumed = try await resumeReservation(resumeBlobId, expectedChunkCount: chunkCount) {
            if !resumed.committed {
                try await withBoundedParallelism(over: Self.chunksToUpload(missing: resumed.missing, chunkCount: chunkCount)) { n in
                    try await put(resumed.blobId, n)
                }
                try await commit(blobId: resumed.blobId)
            }
            return (resumed.blobId, resumeShared)
        }
        let reservation = try await reserve(recipientKey: first,
                                            recipientKeys: recipientKeys.count > 1 ? recipientKeys : nil,
                                            totalSize: totalSize,
                                            chunkCount: chunkCount)
        let shared = recipientKeys.count == 1 || V2GroupBlobPlan.isShared(reservation)
        onReserved?(reservation.blobId, shared)
        try await withBoundedParallelism(over: Self.chunksToUpload(missing: reservation.missing, chunkCount: chunkCount)) { n in
            try await put(reservation.blobId, n)
        }
        let status = try await blobStatus(blobId: reservation.blobId)
        try await withBoundedParallelism(over: Self.chunksToUpload(missing: status.missing, chunkCount: chunkCount)) { n in
            try await put(reservation.blobId, n)
        }
        try await commit(blobId: reservation.blobId)
        return (reservation.blobId, shared)
    }

    /// Upload one chunk. Idempotent + retried on transient failures: the server
    /// treats a re-PUT of an already-stored chunk as a 200 no-op, so a retry
    /// after an ambiguous network drop is always safe.
    ///
    /// - Parameter useBackground: route through the background URLSession so the
    ///   transfer survives app suspension. NOTE: a background transfer the OS
    ///   defers past the 30 s signature freshness window can arrive stale (in
    ///   the server's `enforce` mode); the resumable design (`uploadChunks`)
    ///   recovers by re-uploading anything still missing, so prefer the
    ///   foreground path for individual small chunks.
    public func uploadChunk(blobId: String,
                            index: Int,
                            data: Data,
                            useBackground: Bool = false) async throws {
        guard index >= 0 else { throw V2.ClientError.invalidArgument("chunk index must be >= 0") }
        guard !data.isEmpty else { throw V2.ClientError.invalidArgument("chunk must be non-empty") }

        try await withV2Retry(label: "uploadChunk[\(index)]") {
            // NOTE ON SIGNING: the blob store's chunk-upload handler verifies the
            // request BEFORE the streamed body is read, so `req.rawBody` is
            // undefined server-side and oshi_auth hashes the literal 2-byte JSON
            // string `""`. We therefore sign with that same body-hash, not the
            // chunk bytes. (See `emptyJSONStringHashBody`.)
            let request = try self.makeRequest(
                method: "PUT",
                path: "/v2/blobs/\(blobId)/chunk/\(index)",
                body: data,
                contentType: "application/octet-stream",
                bodyHashData: Self.emptyJSONStringHashBody,
                withUserHeader: true)

            // __OHTTP_MEDIA_2026_11_10__ never the background session while a wrapping
            // transport (cover / privacy relay) is on: it would go direct.
            if useBackground && !V2Transport.blobsMustStayForeground {
                _ = try await self.backgroundUploader.upload(request: request, chunk: data)
            } else {
                _ = try await self.send(request)
            }
        }
    }

    /// RESUMABLE upload of a full set of chunks: queries `status`, then uploads
    /// only the chunks the server is still missing. Safe to call repeatedly to
    /// finish an interrupted transfer.
    public func uploadChunks(blobId: String,
                             chunks: [Data],
                             useBackground: Bool = false) async throws {
        // The status endpoint lists exactly the chunk indices the server is still
        // missing (a fresh reserve lists them all; an empty list means done), so
        // we can drive the resume straight off it.
        let status = try await blobStatus(blobId: blobId)
        for n in status.missing where n < 0 || n >= chunks.count {
            throw V2.ClientError.invalidArgument("server wants chunk \(n), out of range 0..<\(chunks.count)")
        }
        try await withBoundedParallelism(over: status.missing) { n in
            try await self.uploadChunk(blobId: blobId, index: n, data: chunks[n], useBackground: useBackground)
        }
    }

    /// Finalize a blob once every chunk is present. Returns silently on success;
    /// throws `.chunkStillMissing` if the server reports a 409 gap.
    public func commit(blobId: String) async throws {
        do {
            // Retried: losing a whole uploaded blob to one transient 5xx at the very
            // last step is the most expensive possible failure. The 409 below is a
            // 4xx, so withV2Retry propagates it immediately and the missing-set
            // translation still runs. Request built inside the closure so it re-signs.
            _ = try await withV2Retry(label: "commit") {
                // commit goes through the server's JSON-body reader, so it hashes the
                // ACTUAL (here empty) body — sign over empty, no override.
                let req = try self.makeRequest(method: "POST",
                                               path: "/v2/blobs/\(blobId)/commit",
                                               body: Data(),
                                               contentType: "application/json",
                                               withUserHeader: true)
                return try await self.send(req)
            }
        } catch let V2.ClientError.http(status, reason) where status == 409 {
            // Surface the missing set if the server included it.
            if let reason, let data = reason.data(using: .utf8),
               let obj = try? decoder.decode([String: [Int]].self, from: data),
               let missing = obj["missing"] {
                throw V2.ClientError.chunkStillMissing(missing)
            }
            throw V2.ClientError.http(status: status, reason: reason)
        }
    }

    /// Non-destructive status: which chunks are missing, committed flag, expiry.
    public func blobStatus(blobId: String) async throws -> V2.BlobStatus {
        // Retried: this sits between the uploads and the commit, so a transient
        // failure here threw away an otherwise complete transfer.
        return try await withV2Retry(label: "blobStatus") {
            // Server verifies before reading a body -> hashes `""`.
            let req = try self.makeRequest(method: "GET",
                                           path: "/v2/blobs/\(blobId)/status",
                                           body: nil,
                                           bodyHashData: Self.emptyJSONStringHashBody,
                                           withUserHeader: true)
            return try await self.sendDecoding(req)
        }
    }

    /// Download one chunk's opaque ciphertext bytes. Sends `x-oshi-user` so the
    /// server can authorize the caller as blob owner or recipient.
    public func downloadChunk(blobId: String, index: Int) async throws -> Data {
        guard index >= 0 else { throw V2.ClientError.invalidArgument("chunk index must be >= 0") }
        // __BLOB_RANGE_2026_11_19__ (docs/CENSORSHIP_2026_THREAT_MODEL.md §7) On a capped
        // network (CoverConnectionPolicy window open) read the chunk in parts small enough
        // for a 16 KB-per-connection freeze; a whole-chunk read that dies of a transport
        // error opens that window and is retried in parts. Not capped: unchanged.
        if CoverConnectionPolicy.freshConnections() {
            return try await downloadChunkRanged(blobId: blobId, index: index)
        }
        // Server verifies before reading a body -> hashes `""`.
        let req = try makeRequest(method: "GET",
                                  path: "/v2/blobs/\(blobId)/chunk/\(index)",
                                  body: nil,
                                  bodyHashData: Self.emptyJSONStringHashBody,
                                  withUserHeader: true)
        do {
            let (data, _) = try await send(req)
            return data
        } catch where CoverConnectionPolicy.isTransportFailure(error) {
            CoverConnectionPolicy.onFailure(error)
            return try await downloadChunkRanged(blobId: blobId, index: index)
        }
    }

    /// §7.3 part length: the 206 cover record must stay in the 4 KiB bucket (cover ON),
    /// or one raw part + headers under ~13 KB on a fresh connection (cover OFF).
    static let rangedPartLengthCover = 2688
    static let rangedPartLengthDirect = 12288
    /// A stored chunk is at most 2 MiB; 1024 B is the server's minimum part.
    static let rangedMaxParts = (2 * 1024 * 1024) / 1024 + 8

    /// `Content-Range: bytes o-e/size` → (o, e, size); nil when malformed.
    static func parseContentRange(_ value: String?) -> (start: Int, end: Int, size: Int)? {
        guard let value else { return nil }
        let v = value.trimmingCharacters(in: .whitespaces)
        guard v.lowercased().hasPrefix("bytes ") else { return nil }
        let spec = v.dropFirst(6).trimmingCharacters(in: .whitespaces)
        let halves = spec.split(separator: "/", omittingEmptySubsequences: false)
        guard halves.count == 2, let size = Int(halves[1]) else { return nil }
        let ends = halves[0].split(separator: "-", omittingEmptySubsequences: false)
        guard ends.count == 2, let start = Int(ends[0]), let end = Int(ends[1]),
              start >= 0, end >= start, end < size else { return nil }
        return (start, end, size)
    }

    /// §7.1/§7.2: `GET …/chunk/{n}?offset=o&length=n` until `e + 1 == size`; a 200 is an
    /// old server's whole chunk (used as is). Each part is freshly signed; the query is
    /// not part of the signature. Any mismatch fails the whole chunk (caller retries).
    func downloadChunkRanged(blobId: String, index: Int,
                             partLength: Int = CoverTransport.mode != nil ? V2Client.rangedPartLengthCover
                                                                          : V2Client.rangedPartLengthDirect) async throws -> Data {
        var out = Data()
        var offset = 0
        for _ in 0..<Self.rangedMaxParts {
            try Task.checkCancellation()
            let req = try makeRequest(method: "GET",
                                      path: "/v2/blobs/\(blobId)/chunk/\(index)?offset=\(offset)&length=\(partLength)",
                                      body: nil,
                                      bodyHashData: Self.emptyJSONStringHashBody,
                                      withUserHeader: true)
            let (data, http) = try await send(req)
            if http.statusCode == 200 { return data }
            guard http.statusCode == 206,
                  let range = Self.parseContentRange(http.value(forHTTPHeaderField: "Content-Range")),
                  range.start == offset, data.count == range.end - range.start + 1 else {
                throw V2.ClientError.invalidArgument("ranged chunk read: bad 206 at offset \(offset)")
            }
            out.append(data)
            offset = range.end + 1
            if offset == range.size { return out }
        }
        throw V2.ClientError.invalidArgument("ranged chunk read: too many parts")
    }

    /// Delete-after-delivery. Idempotent from the caller's view — a missing blob
    /// (404) is treated as already gone.
    public func deleteBlob(blobId: String) async throws {
        // Server verifies before reading a body -> hashes `""`.
        let req = try makeRequest(method: "DELETE",
                                  path: "/v2/blobs/\(blobId)",
                                  body: nil,
                                  bodyHashData: Self.emptyJSONStringHashBody,
                                  withUserHeader: true)
        do {
            _ = try await send(req)
        } catch let V2.ClientError.http(status, _) where status == 404 {
            return // already gone
        }
    }

    /// Result of `deleteAccount()` — a receipt, not a boolean.
    ///
    /// The server erases what it owns and then calls two sibling services it does
    /// not own (the legacy queue, the push service). If one of those cannot be
    /// reached it answers 207 with `complete: false`, and the user is entitled to
    /// be told that rather than shown a green tick. `unreachable` names the parts
    /// that did not confirm.
    public struct AccountDeletion: Sendable {
        public let complete: Bool
        public let prekeysErased: Bool
        public let relayEnvelopes: Int
        public let syncItems: Int
        public let inboundBlobs: Int
        /// Media this identity SENT that other people have not collected yet. Not
        /// deleted, on purpose: those are the recipients' messages, and letting a
        /// sender erase them would make account deletion a remote-wipe primitive.
        /// They expire on their own (30 days, or 24 h after collection).
        public let outboundBlobsLeft: Int
        public let unreachable: [String]
    }

    /// Erase this identity from the servers. Apple guideline 5.1.1(v) requires the
    /// in-app path; the GDPR requires that it delete something.
    ///
    /// Signed as the identity being deleted, like every other mutating call — this
    /// is the one request where a wrong authorization decision is unrecoverable,
    /// so the server refuses rather than guessing.
    public func deleteAccount() async throws -> AccountDeletion {
        // Same convention as the blob endpoints: the server authorizes before
        // reading a body, so the canonical hash is over the empty JSON string.
        let req = try makeRequest(method: "DELETE",
                                  path: "/v2/account",
                                  body: nil,
                                  bodyHashData: Self.emptyJSONStringHashBody,
                                  withUserHeader: true)
        // 207 is a SUCCESS here — partial, and its own answer. `send()` treats
        // anything outside 2xx as a throw, and 207 is inside it, so the receipt
        // comes back either way and the caller decides what to tell the user.
        let (data, _) = try await send(req)

        struct Wire: Decodable {
            struct Erased: Decodable {
                struct Relay: Decodable { let envelopes: Int? }
                struct Sync: Decodable { let items: Int? }
                struct Blobs: Decodable { let deleted: Int?; let outboundLeft: Int? }
                let prekeys: Bool?
                let relay: Relay?
                let sync: Sync?
                let blobs: Blobs?
            }
            struct Remote: Decodable { let ok: Bool? }
            let complete: Bool?
            let erased: Erased?
            let remote: [String: Remote]?
        }
        let wire = try JSONDecoder().decode(Wire.self, from: data)
        let unreachable = (wire.remote ?? [:]).filter { $0.value.ok != true }.map(\.key).sorted()
        return AccountDeletion(
            complete: wire.complete ?? unreachable.isEmpty,
            prekeysErased: wire.erased?.prekeys ?? false,
            relayEnvelopes: wire.erased?.relay?.envelopes ?? 0,
            syncItems: wire.erased?.sync?.items ?? 0,
            inboundBlobs: wire.erased?.blobs?.deleted ?? 0,
            outboundBlobsLeft: wire.erased?.blobs?.outboundLeft ?? 0,
            unreachable: unreachable
        )
    }

    /// Convenience: reserve → upload all chunks (resumably) → commit, enforcing
    /// the 50 MB cap. `chunks` are already-encrypted (AEAD) ciphertext blobs
    /// produced by the crypto layer. Returns the server blob id.
    ///
    /// 🔧 RESUME FIX: `resumeBlobId` + `onReserved` make an interrupted send pick up
    /// where it stopped instead of restarting from zero. MECHANISM: the resumable
    /// machinery (reserve → per-index PUT → `status.missing` → commit) already
    /// existed, but every attempt began with a fresh `reserve`, so the server's
    /// already-stored chunks were orphaned under the previous blobId and the whole
    /// file went up again. The caller now persists the blobId the instant the
    /// reservation lands (`onReserved`) and hands it back on the next attempt
    /// (`resumeBlobId`); we then drive straight off the live missing-set. NO new
    /// transfer mechanism is introduced — this is the existing one, remembered.
    @discardableResult
    public func uploadBlob(recipientKey: String,
                           chunks: [Data],
                           ownerKey: String? = nil,
                           useBackground: Bool = false,
                           resumeBlobId: String? = nil,
                           onReserved: (@Sendable (String) -> Void)? = nil) async throws -> String {
        guard !chunks.isEmpty else { throw V2.ClientError.invalidArgument("no chunks to upload") }
        let totalSize = chunks.reduce(0) { $0 + $1.count }
        guard totalSize <= V2.maxBlobBytes else {
            throw V2.ClientError.blobTooLarge(size: totalSize, max: V2.maxBlobBytes)
        }
        if let resumed = try await resumeReservation(resumeBlobId, expectedChunkCount: chunks.count) {
            if resumed.committed { return resumed.blobId }   // died after commit, before the envelope
            try await withBoundedParallelism(over: Self.chunksToUpload(missing: resumed.missing, chunkCount: chunks.count)) { n in
                try await self.uploadChunk(blobId: resumed.blobId, index: n,
                                           data: chunks[n], useBackground: useBackground)
            }
            try await commit(blobId: resumed.blobId)
            return resumed.blobId
        }
        let reservation = try await reserve(ownerKey: ownerKey,
                                            recipientKey: recipientKey,
                                            totalSize: totalSize,
                                            chunkCount: chunks.count)
        onReserved?(reservation.blobId)
        // 🔧 SPEED FIX: chunk PUTs were strictly serial, so every chunk paid a full
        // round-trip of latency before the next byte left the device — on a phone
        // link that idles the pipe most of the time. The PUTs are idempotent and
        // order-independent (the server addresses each by index), so run them a few
        // at a time. Bounded at 4 to stay well inside the per-host connection limit
        // and to keep at most 4 chunks resident.
        try await withBoundedParallelism(over: Self.chunksToUpload(missing: reservation.missing, chunkCount: chunks.count)) { n in
            try await self.uploadChunk(blobId: reservation.blobId,
                                       index: n,
                                       data: chunks[n],
                                       useBackground: useBackground)
        }
        // [V2-C2] Don't trust the single reserve snapshot: re-check the live
        // missing set and top up before committing. A dropped/failed PUT that
        // slipped past a retry would otherwise surface as a 409 at commit —
        // or worse, an incomplete file if the server ever raced. One cheap
        // status round-trip buys upload completeness.
        let status = try await blobStatus(blobId: reservation.blobId)
        try await withBoundedParallelism(over: Self.chunksToUpload(missing: status.missing, chunkCount: chunks.count)) { n in
            try await self.uploadChunk(blobId: reservation.blobId,
                                       index: n,
                                       data: chunks[n],
                                       useBackground: useBackground)
        }
        try await commit(blobId: reservation.blobId)
        return reservation.blobId
    }

    /// Streaming, RAM-safe counterpart to `uploadBlob`: the AEAD-sealed chunk
    /// ciphertext lives on DISK (produced by `OSHICryptoV2.encryptFileStreaming`)
    /// and is read one chunk at a time, so peak memory ≈ one chunk regardless of
    /// file size. Same reserve → per-chunk PUT → status top-up → commit contract
    /// as `uploadBlob`, and the resulting blob is byte-identical (the streaming
    /// encrypt matches the in-memory encrypt, cross-verified in OSHICryptoV2Tests).
    /// Used for large files (e.g. 200 MB) where materialising every ciphertext
    /// chunk in a `[Data]` would ~2× the file in RAM and risk a jetsam kill.
    /// `resumeBlobId`/`onReserved`: same resume contract as `uploadBlob`.
    public func uploadBlobFromDisk(recipientKey: String,
                                   chunkURLs: [URL],
                                   ownerKey: String? = nil,
                                   useBackground: Bool = false,
                                   resumeBlobId: String? = nil,
                                   onReserved: (@Sendable (String) -> Void)? = nil) async throws -> String {
        guard !chunkURLs.isEmpty else { throw V2.ClientError.invalidArgument("no chunks to upload") }
        // Sum the on-disk chunk sizes without reading any bytes into memory.
        let fm = FileManager.default
        var totalSize = 0
        for url in chunkURLs {
            let attrs = try fm.attributesOfItem(atPath: url.path)
            totalSize += (attrs[.size] as? Int) ?? 0
        }
        guard totalSize <= V2.maxBlobBytes else {
            throw V2.ClientError.blobTooLarge(size: totalSize, max: V2.maxBlobBytes)
        }
        if let resumed = try await resumeReservation(resumeBlobId, expectedChunkCount: chunkURLs.count) {
            if resumed.committed { return resumed.blobId }
            try await withBoundedParallelism(over: Self.chunksToUpload(missing: resumed.missing, chunkCount: chunkURLs.count)) { n in
                let data = try Data(contentsOf: chunkURLs[n], options: .mappedIfSafe)
                try await self.uploadChunk(blobId: resumed.blobId, index: n,
                                           data: data, useBackground: useBackground)
            }
            try await commit(blobId: resumed.blobId)
            return resumed.blobId
        }
        let reservation = try await reserve(ownerKey: ownerKey,
                                            recipientKey: recipientKey,
                                            totalSize: totalSize,
                                            chunkCount: chunkURLs.count)
        onReserved?(reservation.blobId)
        // Same bounded-parallel shape as `uploadBlob`; peak RAM stays ≈ 4 chunks
        // because each mapped chunk is released as its PUT completes.
        try await withBoundedParallelism(over: Self.chunksToUpload(missing: reservation.missing, chunkCount: chunkURLs.count)) { n in
            // .mappedIfSafe: the OS pages the chunk in lazily; it is not a heap copy.
            let data = try Data(contentsOf: chunkURLs[n], options: .mappedIfSafe)
            try await self.uploadChunk(blobId: reservation.blobId,
                                       index: n,
                                       data: data,
                                       useBackground: useBackground)
        }
        // Mirror uploadBlob's completeness re-check before commit.
        let status = try await blobStatus(blobId: reservation.blobId)
        try await withBoundedParallelism(over: Self.chunksToUpload(missing: status.missing, chunkCount: chunkURLs.count)) { n in
            let data = try Data(contentsOf: chunkURLs[n], options: .mappedIfSafe)
            try await self.uploadChunk(blobId: reservation.blobId,
                                       index: n,
                                       data: data,
                                       useBackground: useBackground)
        }
        try await commit(blobId: reservation.blobId)
        return reservation.blobId
    }

    /// Can we still upload into `blobId`? Returns its live status when the blob is
    /// usable, `nil` when the caller must reserve a fresh one.
    ///
    /// MECHANISM: the blob store keeps the reservation (and every chunk already
    /// PUT into it) until its TTL, and `/status` reports exactly which indices are
    /// still missing. So the ONLY thing a resume needs is the blobId — which the
    /// caller persisted. We reject a stale record whose highest missing index no
    /// longer fits this attempt's chunk count (i.e. the record does not describe
    /// this content), and treat ANY status failure — 404 after TTL expiry, an
    /// auth/network error — as "reserve fresh", so resume can only ever degrade to
    /// today's behaviour, never break a send.
    private func resumeReservation(_ blobId: String?,
                                   expectedChunkCount: Int) async throws -> V2.BlobStatus? {
        guard let blobId, !blobId.isEmpty else { return nil }
        guard let status = try? await blobStatus(blobId: blobId) else {
            log.info("v2 blob resume: \(blobId, privacy: .public) unusable — reserving fresh")
            return nil
        }
        guard Self.resumeIsUsable(missing: status.missing, expectedChunkCount: expectedChunkCount) else { return nil }
        log.info("v2 blob resume: \(blobId, privacy: .public) committed=\(status.committed) missing=\(status.missing.count)/\(expectedChunkCount)")
        return status
    }

    /// Convenience download of every chunk in order. `chunkCount` comes from the
    /// message metadata (the status endpoint does not expose the total count).
    /// [BLOB-H4] Each chunk fetch is wrapped in the standard retry/backoff so a
    /// flaky link mid-download doesn't fail the whole file (the per-chunk AEAD
    /// tag downstream guarantees integrity regardless of how many attempts).
    public func downloadBlob(blobId: String, chunkCount: Int) async throws -> [Data] {
        guard chunkCount >= 1 else { throw V2.ClientError.invalidArgument("chunkCount must be >= 1") }

        // 🔧 FIX (2026-08-11, media download): this was a plain serial `for` loop
        // while the UPLOAD side has used `withBoundedParallelism(limit: 4)` for
        // months. Nobody chose that asymmetry — receiving simply never got the
        // treatment sending did.
        //
        // It costs one full round trip of dead time per chunk, and worse, gives
        // TCP no chance to stay at speed: each request finishes before the next
        // starts, so the connection idles between chunks instead of streaming.
        // A single-chunk photo is unaffected (the bound collapses to one), but a
        // 20 MB video is 10 chunks — measured RTT to this host is 78–106 ms warm,
        // so that is ~1 s of pure waiting on top of the transfer, plus whatever
        // throughput is lost to repeated ramp-up.
        //
        // Same limit as the upload path, deliberately: 4 keeps a mobile link busy
        // without the request pile-up that makes a weak connection worse.
        //
        // Results are written by index into a pre-sized array, not appended —
        // completions arrive out of order, and appending would silently scramble
        // the chunk order. The per-chunk AEAD tag downstream would then fail to
        // open the file, which reads as "corrupt media" rather than as a bug here.
        var out = [Data?](repeating: nil, count: chunkCount)
        let lock = NSLock()
        try await withBoundedParallelism(over: Array(0..<chunkCount)) { [self] n in
            let chunk = try await withV2Retry(label: "downloadChunk[\(n)]") {
                try await downloadChunk(blobId: blobId, index: n)
            }
            lock.lock(); out[n] = chunk; lock.unlock()
        }
        guard let chunks = out as? [Data] else {
            throw V2.ClientError.invalidArgument("downloadBlob: chunk \(out.firstIndex(where: { $0 == nil }) ?? -1) missing")
        }
        return chunks
    }

    /// Background + resumable download of every chunk to DISK, returning chunk
    /// file URLs in order. Unlike `downloadBlob` (whole file in RAM, foreground),
    /// this stages each chunk as a `.part` file via a background URLSession and
    /// SKIPS chunks already on disk — so an interrupted transfer (network drop,
    /// backgrounding, app relaunch) resumes from the first missing chunk on the
    /// next call instead of restarting from zero. Peak RAM ≈ one chunk, not the
    /// whole file. The per-chunk AEAD tag downstream still guarantees integrity.
    public func downloadBlobToDisk(blobId: String, chunkCount: Int) async throws -> [URL] {
        if CoverTransport.mode != nil {
            guard chunkCount > 0, chunkCount <= 4096 else { throw V2.ClientError.invalidArgument("chunk count") }
            let directory = V2BackgroundBlobDownloader.stageDir(blobId: blobId)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var files: [URL] = []
            for n in 0..<chunkCount {
                try Task.checkCancellation()
                // __GROUP_BLOB_2026_11_25__ a chunk staged by an earlier attempt (any path) is
                // kept: this loop used to fetch every chunk again on each attempt.
                if let staged = V2BackgroundBlobDownloader.stagedChunk(blobId: blobId, index: n) {
                    files.append(staged); continue
                }
                let bytes = try await downloadChunk(blobId: blobId, index: n)
                let target = directory.appendingPathComponent("cover-\(n).part")
                try bytes.write(to: target, options: .atomic)
                files.append(target)
            }
            return files
        }

        guard chunkCount >= 1 else { throw V2.ClientError.invalidArgument("chunkCount must be >= 1") }
        // __RELAY_FALLBACK_2026_11_19__ / __BLOB_RANGE_2026_11_19__ same foreground path while
        // the blocked-host relay is in force or the network is capped (V2Transport.blobsMustStayForeground).
        if V2Transport.blobsMustStayForeground {
            // __OHTTP_MEDIA_2026_11_10__ Privacy relay on: the background downloader
            // would fetch straight from the API host (IP revealed, relay bypassed).
            // Foreground chunk GETs through `V2Transport` instead — still bounded-
            // parallel, retried, and resumable (a chunk already staged is kept; the
            // per-chunk AEAD tag downstream rejects anything wrong).
            guard chunkCount <= 4096 else { throw V2.ClientError.invalidArgument("chunk count") }
            let directory = V2BackgroundBlobDownloader.stageDir(blobId: blobId)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var staged = [URL?](repeating: nil, count: chunkCount)
            let stagedLock = NSLock()
            try await withBoundedParallelism(over: Array(0..<chunkCount)) { [self] n in
                var target = directory.appendingPathComponent("ohttp-\(n).part")
                if let prior = V2BackgroundBlobDownloader.stagedChunk(blobId: blobId, index: n) {
                    target = prior   // __GROUP_BLOB_2026_11_25__ staged by any earlier path
                } else {
                    let bytes = try await withV2Retry(label: "ohttpDownloadChunk[\(n)]") {
                        try await downloadChunk(blobId: blobId, index: n)
                    }
                    try bytes.write(to: target, options: .atomic)
                }
                stagedLock.lock(); staged[n] = target; stagedLock.unlock()
            }
            guard let urls = staged as? [URL] else {
                throw V2.ClientError.invalidArgument("downloadBlobToDisk: chunk \(staged.firstIndex(where: { $0 == nil }) ?? -1) missing")
            }
            return urls
        }
        // 🔧 FIX (2026-08-12): this is the path the app ACTUALLY uses to receive
        // media — `downloadAndDecryptFileToDisk` below, reached from
        // MessageManager+V2. The parallelism added yesterday went to
        // `downloadBlob`, the RAM variant, whose only caller is
        // `V2MessageRouter.downloadAndDecryptFile`. So receiving stayed serial
        // while the "fix" sat on a branch reception never takes.
        //
        // Same bound as the upload and RAM paths. Requests are built UP FRONT and
        // on this actor: `makeRequest` signs, and doing that inside concurrent
        // tasks would both duplicate work and make signing order nondeterministic.
        //
        // Results go into a pre-sized array BY INDEX, never appended. Completions
        // arrive out of order, and chunk order is load-bearing — the per-chunk
        // AEAD tag turns a scrambled reassembly into "corrupt media", which reads
        // as a decryption failure rather than as an ordering bug.
        var requests: [URLRequest] = []
        requests.reserveCapacity(chunkCount)
        for n in 0..<chunkCount {
            // Reuse the exact signed request the foreground chunk path builds so
            // authorization is byte-for-byte identical (server hashes "" body).
            requests.append(try makeRequest(method: "GET",
                                            path: "/v2/blobs/\(blobId)/chunk/\(n)",
                                            body: nil,
                                            bodyHashData: Self.emptyJSONStringHashBody,
                                            withUserHeader: true))
        }

        var out = [URL?](repeating: nil, count: chunkCount)
        let lock = NSLock()
        try await withBoundedParallelism(over: Array(0..<chunkCount)) { [self] n in
            let url: URL
            if let prior = V2BackgroundBlobDownloader.stagedChunk(blobId: blobId, index: n) {
                // __GROUP_BLOB_2026_11_25__ also a chunk an earlier attempt read in ranged parts
                // or through the relay path, not only `<n>.part`.
                lock.lock(); out[n] = prior; lock.unlock()
                return
            }
            do {
                url = try await withV2Retry(label: "bgDownloadChunk[\(n)]") {
                    try await V2BackgroundBlobDownloader.shared.downloadChunk(
                        request: requests[n], blobId: blobId, index: n)
                }
            } catch where CoverConnectionPolicy.isTransportFailure(error) {
                // __BLOB_RANGE_2026_11_19__ §7.3: a whole-chunk read that died of a transport
                // error is retried in ranged parts (foreground; the window is now open).
                CoverConnectionPolicy.onFailure(error)
                let bytes = try await downloadChunkRanged(blobId: blobId, index: n)
                let target = V2BackgroundBlobDownloader.stageDir(blobId: blobId).appendingPathComponent("ranged-\(n).part")
                try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
                try bytes.write(to: target, options: .atomic)
                url = target
            }
            lock.lock(); out[n] = url; lock.unlock()
        }
        guard let urls = out as? [URL] else {
            throw V2.ClientError.invalidArgument("downloadBlobToDisk: chunk \(out.firstIndex(where: { $0 == nil }) ?? -1) missing")
        }
        return urls
    }

    /// Resumable, disk-based, background download + STREAMING decrypt of a file
    /// blob straight to a temp file, returning its URL. Bounded memory end to end
    /// (≈ one chunk) and resumable across interruptions — the counterpart to the
    /// RAM-based `V2MessageRouter.downloadAndDecryptFile`. Base64 key material is
    /// passed as primitives so this stays independent of the router types.
    public func downloadAndDecryptFileToDisk(blobId: String,
                                             chunkCount: Int,
                                             fileKeyB64: String,
                                             fileNonceB64: String,
                                             manifestB64: String) async throws -> URL {
        guard let fileKey = Data(base64Encoded: fileKeyB64),
              let fileNonce = Data(base64Encoded: fileNonceB64),
              let manifest = Data(base64Encoded: manifestB64) else {
            throw V2.ClientError.invalidArgument("malformed file key material")
        }
        let chunkURLs = try await downloadBlobToDisk(blobId: blobId, chunkCount: chunkCount)
        let outURL = V2BackgroundBlobDownloader.stageDir(blobId: blobId)
            .appendingPathComponent("plain.bin")
        try? FileManager.default.removeItem(at: outURL)
        do {
            _ = try OSHICryptoV2.decryptFileStreaming(fileKey: fileKey,
                                                      fileNonce: fileNonce,
                                                      chunkURLs: chunkURLs,
                                                      manifest: manifest,
                                                      outputURL: outURL)
        } catch {
            // __OHTTP_MEDIA_2026_11_10__ chunks staged by the relay path are reused on
            // the next attempt; a set that failed to open must not be reused forever.
            // __GROUP_BLOB_2026_11_25__ the same for EVERY staged chunk: `<n>.part` from the
            // background path is reused too, so one bad chunk on disk made every later attempt
            // fail the same way (the pending-blob replay retries forever). Integrity = the
            // per-chunk AES-GCM tag; a set that does not open is fetched again.
            for u in chunkURLs { try? FileManager.default.removeItem(at: u) }
            throw error
        }
        // Reassembly succeeded — the staged .part chunks are no longer needed.
        for u in chunkURLs { try? FileManager.default.removeItem(at: u) }
        return outURL
    }

    // MARK: - Relay API  (/v2/messages)

    /// Send a single envelope. Returns the msgIds the relay accepted.
    @discardableResult
    public func sendEnvelope(_ env: V2.Envelope) async throws -> [String] {
        let body = try encoder.encode(env)     // a bare object is accepted as [env]
        struct Resp: Decodable { let accepted: [String] }
        // Retried: the relay POST is the last hop of every V2 send, and it is
        // idempotent by `msgId` (the relay dedupes), so a transient 5xx should cost
        // a backoff rather than the whole V2 path.
        let resp: Resp = try await withV2Retry(label: "sendEnvelope") {
            let req = try self.makeRequest(method: "POST",
                                           path: "/v2/messages",
                                           body: body,
                                           contentType: "application/json")
            return try await self.sendDecoding(req)
        }
        return resp.accepted
    }

    /// Send a batch of envelopes in one request — the group fan-out convenience.
    /// Every envelope MUST share the same `from` (the server authenticates the
    /// batch once as that sender and rejects mixed senders).
    @discardableResult
    public func sendBatch(_ envelopes: [V2.Envelope]) async throws -> [String] {
        guard !envelopes.isEmpty else { throw V2.ClientError.invalidArgument("empty batch") }
        if let first = envelopes.first?.from,
           envelopes.contains(where: { $0.from != first }) {
            throw V2.ClientError.invalidArgument("batch envelopes must share one `from`")
        }
        struct Body: Encodable { let envelopes: [V2.Envelope] }
        let body = try encoder.encode(Body(envelopes: envelopes))
        // __GROUP_FANOUT_2026_11_24__ a 429 waits Retry-After (V2RateLimitBackoff); the request is
        // re-signed on every attempt (fresh timestamp), the relay dedups an accepted batch.
        let (data, http) = try await V2RateLimitBackoff.retrying({ () async throws -> (Data, HTTPURLResponse) in
            let req = try self.makeRequest(method: "POST", path: "/v2/messages", body: body, contentType: "application/json")
            let (data, response) = try await V2Transport.data(for: req, session: self.session)
            guard let http = response as? HTTPURLResponse else { throw V2.ClientError.notHTTPResponse }
            return (data, http)
        }, status: { $0.1.statusCode }, retryAfter: { $0.1.value(forHTTPHeaderField: "Retry-After") })
        guard (200...299).contains(http.statusCode) else {
            throw V2.ClientError.http(status: http.statusCode, reason: reasonString(from: data))
        }
        struct Resp: Decodable { let accepted: [String] }
        do { return try decoder.decode(Resp.self, from: data).accepted }
        catch { throw V2.ClientError.decoding(underlying: error) }
    }

    /// __PULL_BUDGET_2026_11_19__ `GET /v2/messages/<key>[?after=N][&budget=B]`. On a network
    /// that already showed a stalled cover exchange (CoverConnectionPolicy window: Russia
    /// TSPU-style freeze after ~16 KB per connection), ask the relay for a page that fits the
    /// 4 KiB cover bucket (~5.5 KB on the wire): a page of several messages lands in the 16 KiB
    /// bucket (~22 KB) and is withheld forever by such a network. The query is not signed
    /// (`signRequest` signs the path only); relays without the feature ignore it. Each tick
    /// pulls one page and advances the cursor to its `maxSeq`, so the backlog drains over
    /// successive ticks. Mirrors Android `V2MessagesClient.pullBudgetQuery`.
    static func pullPath(encodedKey: String, afterSeq: Int,
                         budget: Int? = CoverConnectionPolicy.pullBudget()) -> String {
        var query: [String] = []
        if afterSeq > 0 { query.append("after=\(afterSeq)") }
        if let budget { query.append("budget=\(budget)") }
        return "/v2/messages/\(encodedKey)" + (query.isEmpty ? "" : "?" + query.joined(separator: "&"))
    }

    /// Non-destructive pull of everything with `seq > afterSeq` for `userKey`.
    /// Owner-only server-side: `userKey` must be the local identity.
    public func pullMessages(userKey: String, afterSeq: Int = 0) async throws -> V2.PullResult {
        let path = Self.pullPath(encodedKey: encodeIdentity(userKey), afterSeq: afterSeq)
        // GET queues sets rawBody = empty server-side -> sign over empty (no override).
        let req = try makeRequest(method: "GET", path: path, body: nil)
        return try await sendDecoding(req)
    }

    /// Acknowledge durable receipt up to `upToSeq`; the relay then drops those
    /// messages from the queue. Owner-only.
    public func ack(userKey: String, upToSeq: Int) async throws {
        struct Body: Encodable { let upToSeq: Int }
        let body = try encoder.encode(Body(upToSeq: upToSeq))
        let req = try makeRequest(method: "POST",
                                  path: "/v2/messages/\(encodeIdentity(userKey))/ack",
                                  body: body,
                                  contentType: "application/json")
        _ = try await send(req)
    }

    // MARK: - Per-device mailbox  (__PER_DEVICE_MAILBOX_2026_09_23__)
    //
    // Routes of `ServerPatches/per_device_mailbox` (design Appendix B). Every one is
    // account-signed with `x-oshi-user`; "+dev" routes also carry the device headers.
    // None of them is called unless `/v2/config` says `device_mailbox_enabled`.

    /// `GET /v2/devices` — this account's registered devices.
    public func listDevices() async throws -> [V2Device.Record] {
        let req = try makeRequest(method: "GET", path: "/v2/devices", body: nil, withUserHeader: true)
        struct Resp: Decodable { let devices: [V2Device.Record] }
        let r: Resp = try await sendDecoding(req)
        return r.devices
    }

    /// `POST /v2/devices/register`. Errors surface as `.http(status, "error=<code> …")`.
    @discardableResult
    public func registerDevice(_ registration: V2Device.Registration) async throws -> Bool {
        let body = try encoder.encode(registration)
        let req = try makeRequest(method: "POST", path: "/v2/devices/register", body: body,
                                  contentType: "application/json", withUserHeader: true)
        struct Resp: Decodable { let registered: Bool?; let created: Bool? }
        let r: Resp = try await sendDecoding(req)
        return r.created ?? false
    }

    /// `POST /v2/devices/unregister {deviceId}` +dev — any registered device may remove any
    /// other; `signer` is THIS device.
    public func unregisterDevice(_ deviceId: String, signer: V2Device.Signer) async throws {
        _ = try await send(try unregisterRequest(deviceId, signer: signer))
    }

    /// The same request, signed NOW. Sign-out builds it before the identity keys are wiped
    /// and sends it afterwards (`perform`).
    public func unregisterRequest(_ deviceId: String, signer: V2Device.Signer) throws -> URLRequest {
        struct Body: Encodable { let deviceId: String }
        let body = try encoder.encode(Body(deviceId: deviceId))
        return try makeRequest(method: "POST", path: "/v2/devices/unregister", body: body,
                               contentType: "application/json", deviceSigner: signer)
    }

    public func perform(_ request: URLRequest) async throws {
        _ = try await send(request)
    }

    /// `POST /v2/devices/reset` — recovery only (no device key survives). Loud by design.
    public func resetDevices() async throws -> Int {
        struct Body: Encodable { let confirm = "RESET-ALL-DEVICES" }
        let body = try encoder.encode(Body())
        let req = try makeRequest(method: "POST", path: "/v2/devices/reset", body: body,
                                  contentType: "application/json", withUserHeader: true)
        struct Resp: Decodable { let removed: Int? }
        let r: Resp = try await sendDecoding(req)
        return r.removed ?? 0
    }

    /// `POST /v2/keys/publish {…, deviceId}` +dev — THIS device's own X3DH bundle. Never
    /// touches the account bundle.
    @discardableResult
    public func publishDeviceBundle(_ bundle: V2.PreKeyBundleUpload, signer: V2Device.Signer) async throws -> Int {
        let me = try requireIdentity()
        struct Body: Encodable {
            let userKey: String
            let identityKey: String
            let signingKey: String
            let signedPreKey: V2.SignedPreKey
            let oneTimePreKeys: [V2.OneTimePreKey]
            let caps: Int
            let deviceId: String
        }
        let body = try encoder.encode(Body(userKey: me, identityKey: bundle.identityKey, signingKey: bundle.signingKey,
                                           signedPreKey: bundle.signedPreKey, oneTimePreKeys: bundle.oneTimePreKeys,
                                           caps: bundle.caps, deviceId: signer.deviceId))
        let req = try makeRequest(method: "POST", path: "/v2/keys/publish", body: body,
                                  contentType: "application/json", deviceSigner: signer)
        struct Resp: Decodable { let remaining: Int }
        let r: Resp = try await sendDecoding(req)
        return r.remaining
    }

    /// `GET /v2/keys/:userKey/devices` — the peer's devices holding a bundle.
    /// __ACCOUNT_CAPS_IN_DEVICE_LIST_2026_11_10__ `accountCaps` = the ACCOUNT bundle's caps (the
    /// install reading the account mailbox); nil from an older server or when there is none.
    public func peerDevices(userKey: String) async throws -> (devices: [V2Device.PeerDevice], enabled: Bool, accountCaps: Int?) {
        let req = try makeRequest(method: "GET", path: "/v2/keys/\(encodeIdentity(userKey))/devices", body: nil)
        struct Resp: Decodable { let devices: [V2Device.PeerDevice]; let enabled: Bool?; let accountCaps: Int? }
        let r: Resp = try await sendDecoding(req)
        return (r.devices, r.enabled ?? false, r.accountCaps)
    }

    /// `GET /v2/keys/:userKey?device=<id>` — one device's bundle (+1 one-time pre-key).
    public func fetchBundle(userKey: String, device: String) async throws -> V2.FetchedBundle {
        let req = try makeRequest(method: "GET",
                                  path: "/v2/keys/\(encodeIdentity(userKey))?device=\(device)",
                                  body: nil)
        return try await sendDecoding(req)
    }

    /// `GET /v2/keys/:userKey/count?device=<id>`.
    public func prekeyCount(userKey: String, device: String) async throws -> Int {
        let req = try makeRequest(method: "GET",
                                  path: "/v2/keys/\(encodeIdentity(userKey))/count?device=\(device)",
                                  body: nil)
        struct Resp: Decodable { let remaining: Int }
        let r: Resp = try await sendDecoding(req)
        return r.remaining
    }

    /// Device-mode pull: this device's copies + the account items it is still owed.
    public func pullMessages(userKey: String, afterSeq: Int, signer: V2Device.Signer) async throws -> V2.PullResult {
        let path = Self.pullPath(encodedKey: encodeIdentity(userKey), afterSeq: afterSeq)
        let req = try makeRequest(method: "GET", path: path, body: nil, deviceSigner: signer)
        return try await sendDecoding(req)
    }

    /// Device-mode ack: releases THIS device only.
    public func ack(userKey: String, upToSeq: Int, signer: V2Device.Signer) async throws {
        struct Body: Encodable { let upToSeq: Int }
        let body = try encoder.encode(Body(upToSeq: upToSeq))
        let req = try makeRequest(method: "POST",
                                  path: "/v2/messages/\(encodeIdentity(userKey))/ack",
                                  body: body, contentType: "application/json", deviceSigner: signer)
        _ = try await send(req)
    }

    /// `POST /v2/messages` for a batch that may carry `toDevice`/`fromDevice`. Throws
    /// `V2Device.ListMismatch` on `409 device-list-mismatch` (the caller refreshes the list
    /// and rebuilds), `.http` otherwise.
    @discardableResult
    public func sendDeviceBatch(_ envelopes: [V2.Envelope]) async throws -> [String] {
        guard !envelopes.isEmpty else { throw V2.ClientError.invalidArgument("empty batch") }
        if let first = envelopes.first?.from, envelopes.contains(where: { $0.from != first }) {
            throw V2.ClientError.invalidArgument("batch envelopes must share one `from`")
        }
        struct Body: Encodable { let envelopes: [V2.Envelope] }
        let body = try encoder.encode(Body(envelopes: envelopes))
        // __GROUP_FANOUT_2026_11_24__ this request was never retried: one 429 failed the whole group.
        let (data, http) = try await V2RateLimitBackoff.retrying({ () async throws -> (Data, HTTPURLResponse) in
            let req = try self.makeRequest(method: "POST", path: "/v2/messages", body: body, contentType: "application/json")
            let (data, response) = try await V2Transport.data(for: req, session: self.session)
            guard let http = response as? HTTPURLResponse else { throw V2.ClientError.notHTTPResponse }
            return (data, http)
        }, status: { $0.1.statusCode }, retryAfter: { $0.1.value(forHTTPHeaderField: "Retry-After") })
        if (200...299).contains(http.statusCode) {
            struct Resp: Decodable { let accepted: [String] }
            do { return try decoder.decode(Resp.self, from: data).accepted }
            catch { throw V2.ClientError.decoding(underlying: error) }
        }
        if http.statusCode == 409 {
            struct Mismatch: Decodable {
                let error: String?; let to: String?; let missing: [String]?; let stale: [String]?; let devices: [String]?
            }
            if let m = try? decoder.decode(Mismatch.self, from: data), m.error == "device-list-mismatch", let to = m.to {
                throw V2Device.ListMismatch(to: to, missing: m.missing ?? [], stale: m.stale ?? [], devices: m.devices ?? [])
            }
        }
        throw V2.ClientError.http(status: http.statusCode, reason: reasonString(from: data))
    }

    // MARK: - Request building + signing

    /// The exact bytes oshi_auth hashes when it verifies a request BEFORE any
    /// body has been read: `JSON.stringify(req.body || '')` === the 2-char
    /// string `""`. Used by the blob store's stream/verify-first endpoints
    /// (chunk PUT/GET, status GET, DELETE).
    static let emptyJSONStringHashBody = Data("\"\"".utf8)

    /// Percent-encode a path-embedded IDENTITY (base64 user key) so `/`, `+`,
    /// `=` survive routing. The prekey + relay servers `decodeURIComponent` the
    /// segment, so this round-trips to the original key. (Blob ids are NOT
    /// encoded — the blob store does not decode its path — and are always
    /// URL-safe hex/UUID anyway.)
    private func encodeIdentity(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? s
    }

    private func requireIdentity() throws -> String {
        let id = crypto.userKey
        guard !id.isEmpty else { throw V2.ClientError.missingSigningIdentity }
        return id
    }

    /// Build a signed `URLRequest`.
    /// - Parameters:
    ///   - path: absolute path (already percent-encoded where needed), optionally
    ///     with a `?query`.
    ///   - body: request body bytes (also set as `httpBody`), or nil.
    ///   - bodyHashData: override for the bytes fed into the canonical SHA-256.
    ///     Defaults to `body ?? empty` — pass `emptyJSONStringHashBody` for the
    ///     blob endpoints the server verifies before reading their body.
    ///   - withUserHeader: attach `x-oshi-user` = local identity (blob endpoints).
    private func makeRequest(method: String,
                             path: String,
                             body: Data?,
                             contentType: String? = nil,
                             bodyHashData: Data? = nil,
                             withUserHeader: Bool = false,
                             deviceSigner: V2Device.Signer? = nil) throws -> URLRequest {
        guard let url = URL(string: path, relativeTo: config.baseURL)?.absoluteURL else {
            throw V2.ClientError.invalidURL(path)
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = config.requestTimeout
        if let body { request.httpBody = body }
        if let contentType { request.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        if withUserHeader || deviceSigner != nil {
            request.setValue(crypto.userKey, forHTTPHeaderField: "x-oshi-user")
        }
        signRequest(&request, bodyToHash: bodyHashData ?? body ?? Data(), deviceSigner: deviceSigner)
        return request
    }

    /// Lower-case hex SHA-256 of the raw body bytes (empty body → `e3b0c442…b855`).
    nonisolated static func bodySHA256Hex(_ body: Data) -> String {
        SHA256.hash(data: body).map { String(format: "%02x", $0) }.joined()
    }

    /// The canonical string every v2 signature covers (oshi_auth.js):
    /// `METHOD\nPATH\nhex(sha256(body))\nTIMESTAMP`, no trailing newline. `path`
    /// excludes the query. Pinned by the PUBLIC_CHANNEL_WEB_SPEC §10 vectors.
    nonisolated static func canonicalRequestString(method: String, path: String, body: Data, timestamp: String) -> String {
        "\(method)\n\(path)\n\(bodySHA256Hex(body))\n\(timestamp)"
    }

    /// Attach the three `x-oshi-*` signature headers. Canonical message is
    /// `METHOD\nPATH\nSHA256(bodyToHash)\nTIMESTAMP`, exactly as oshi_auth.js
    /// and `VoIPPushManager.signRequest` compute it. PATH is the percent-encoded
    /// path (query excluded) — the same string the servers see in `req.url`.
    private func signRequest(_ request: inout URLRequest, bodyToHash: Data, deviceSigner: V2Device.Signer? = nil) {
        guard let url = request.url, let method = request.httpMethod else { return }
        let path = URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedPath
            ?? url.path
        // __CALL_CLOCK_SKEW_2026_11_21__ server time when this device is ≥ 5 s off.
        let timestamp = String(ServerClock.signingNowMs())
        let bodyHash = Self.bodySHA256Hex(bodyToHash)
        let message = Self.canonicalRequestString(method: method, path: path, body: bodyToHash, timestamp: timestamp)
        do {
            let (signature, pubKey) = try crypto.signCanonical(Data(message.utf8))
            request.setValue(signature, forHTTPHeaderField: "x-oshi-signature")
            request.setValue(timestamp, forHTTPHeaderField: "x-oshi-timestamp")
            request.setValue(pubKey, forHTTPHeaderField: "x-oshi-signing-pubkey")
            // __PER_DEVICE_MAILBOX_2026_09_23__ The device signature reuses the SAME path,
            // body hash and timestamp as the account signature (spec §2). Both headers are
            // `x-oshi-*`, so the cover transport forwards them unchanged.
            if let deviceSigner {
                let deviceMessage = V2Device.requestString(deviceId: deviceSigner.deviceId, method: method, path: path,
                                                           bodySHA256Hex: bodyHash, ts: timestamp)
                request.setValue(deviceSigner.deviceId, forHTTPHeaderField: "x-oshi-device")
                request.setValue(try deviceSigner.sign(Data(deviceMessage.utf8)), forHTTPHeaderField: "x-oshi-device-signature")
            }
        } catch {
            // No signing key available — send unsigned (server's `dual` mode
            // still accepts, `enforce` will 401). Mirrors VoIPPushManager.
            log.error("V2 request left unsigned: \(String(describing: error), privacy: .public)")
        }
    }

    // MARK: - Transport

    /// Perform a request, validating the HTTP status. Returns raw body + response
    /// on 2xx; throws `.http` (with any server `error`/`reason`) otherwise.
    @discardableResult
    private func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await V2Transport.data(for: request, session: session)
        guard let http = response as? HTTPURLResponse else { throw V2.ClientError.notHTTPResponse }
        guard (200...299).contains(http.statusCode) else {
            throw V2.ClientError.http(status: http.statusCode, reason: reasonString(from: data))
        }
        return (data, http)
    }

    /// `send` + JSON-decode into `T`, never force-unwrapping.
    private func sendDecoding<T: Decodable>(_ request: URLRequest) async throws -> T {
        let (data, _) = try await send(request)
        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw V2.ClientError.decoding(underlying: error)
        }
    }

    /// Extract a short human reason from a server error body (`{error, reason}`
    /// or `{error, missing}`), or the raw string, for diagnostics.
    private func reasonString(from data: Data) -> String? {
        if data.isEmpty { return nil }
        if let obj = try? decoder.decode([String: JSONFragment].self, from: data) {
            let parts = ["error", "reason", "missing", "max"].compactMap { key -> String? in
                obj[key].map { "\(key)=\($0.description)" }
            }
            if !parts.isEmpty { return parts.joined(separator: " ") }
        }
        return String(data: data, encoding: .utf8)
    }

    /// Retry `operation` on transient failures with 2s/4s/8s backoff. Permanent
    /// (4xx / decode) errors propagate immediately.
    private func withV2Retry<T>(label: String,
                                _ operation: () async throws -> T) async throws -> T {
        var lastError: Error?
        let attempts = max(1, config.maxRetryAttempts)
        for attempt in 1...attempts {
            do {
                return try await operation()
            } catch {
                lastError = error
                let transient = Self.isTransient(error)
                if attempt == attempts || !transient {
                    throw error
                }
                let delayNs = UInt64(pow(2.0, Double(attempt))) * 1_000_000_000
                log.info("\(label, privacy: .public) attempt \(attempt) transient failure — retrying")
                try? await Task.sleep(nanoseconds: delayNs)
            }
        }
        throw lastError ?? V2.ClientError.invalidArgument("retry exhausted")
    }

    /// Run `body` over `items` with at most `limit` in flight, rethrowing the first
    /// failure (and cancelling the rest, via the group). Used for chunk PUTs, which
    /// are idempotent and order-independent — the serial loop this replaces spent
    /// most of a phone link's time idle waiting on round-trips.
    private func withBoundedParallelism(over items: [Int],
                                        limit: Int = 4,
                                        _ body: @escaping @Sendable (Int) async throws -> Void) async throws {
        guard !items.isEmpty else { return }
        try await withThrowingTaskGroup(of: Void.self) { group in
            var next = 0
            let inFlight = min(limit, items.count)
            while next < inFlight {
                let item = items[next]
                group.addTask { try await body(item) }
                next += 1
            }
            // Start one replacement per completion so `limit` stays saturated.
            while try await group.next() != nil {
                guard next < items.count else { continue }
                let item = items[next]
                group.addTask { try await body(item) }
                next += 1
            }
        }
    }

    /// Transient = worth a backoff-retry: our own 429/5xx classification plus the
    /// same URLError set the existing Pinata path retries on.
    static func isTransient(_ error: Error) -> Bool {
        if let v2 = error as? V2.ClientError { return v2.isTransient }
        if case CoverTransport.Failure.transport(let status) = error {
            return status == 429 || (500...599).contains(status)
        }
        // __OHTTP_PRIVACY_RELAY_2026_11_09__ relay/gateway 429/5xx: retry THROUGH
        // the relay (never direct — see V2Transport).
        if let ohttp = error as? OHTTPError { return ohttp.isTransient }
        let ns = error as NSError
        if ns.domain == NSURLErrorDomain {
            switch ns.code {
            case NSURLErrorTimedOut,
                 NSURLErrorCannotConnectToHost,
                 NSURLErrorNetworkConnectionLost,
                 NSURLErrorDNSLookupFailed,
                 NSURLErrorNotConnectedToInternet,
                 NSURLErrorResourceUnavailable:
                return true
            default:
                return false
            }
        }
        return false
    }
}

// MARK: - Background chunk uploader

/// Owns the single BACKGROUND URLSession used for resilient blob-chunk uploads,
/// following the same pattern as the existing `BackgroundMediaUploader` (a
/// distinct session identifier so the two never collide). Uploads stream from a
/// temp file (background sessions cannot use a `Data` body) and complete via a
/// per-task continuation.
///
/// Cross-launch note: if the app is killed mid-transfer the in-memory
/// continuation is lost. Unlike the media uploader, no server-side finalize is
/// needed here — `V2Client.uploadChunks` is resumable, so a relaunch simply
/// re-uploads whatever the blob store still reports missing.
nonisolated final class V2BackgroundBlobUploader: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    static let shared = V2BackgroundBlobUploader()
    static let backgroundSessionIdentifier = "com.oshi.v2.blob.upload"

    private var session: URLSession!
    /// System-supplied handler to call once every queued event for this session has
    /// been delivered — iOS relaunches the app to hand it over and terminates us if
    /// we never invoke it. Mirrors `BackgroundMediaUploader`.
    private var backgroundCompletionHandler: (() -> Void)?
    private let stateLock = NSLock()
    private var continuations: [Int: CheckedContinuation<Data, Error>] = [:]
    private var responseData: [Int: Data] = [:]
    private var tempFiles: [Int: URL] = [:]

    private override init() {
        super.init()
        let config = URLSessionConfiguration.background(withIdentifier: Self.backgroundSessionIdentifier)
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        config.timeoutIntervalForRequest = 180
        config.timeoutIntervalForResource = 600
        session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }

    /// Stash the completion handler iOS gives us when it relaunches the app to
    /// deliver this session's events. Without this the app was relaunched, the
    /// chunk PUTs completed, and the handler was silently dropped by the
    /// AppDelegate's `else` branch — iOS then counts us as misbehaving.
    func handleBackgroundEvents(_ handler: @escaping () -> Void) {
        backgroundCompletionHandler = handler
    }

    /// Upload `chunk` for the already-signed `request` via the background session.
    /// Returns the response body on a 2xx; throws on error / non-2xx.
    func upload(request: URLRequest, chunk: Data) async throws -> Data {
        let tempURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("oshi_v2_chunk_\(UUID().uuidString).bin")
        try chunk.write(to: tempURL, options: .atomic)
        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Data, Error>) in
            let task = session.uploadTask(with: request, fromFile: tempURL)
            let id = task.taskIdentifier
            stateLock.lock()
            continuations[id] = cont
            tempFiles[id] = tempURL
            stateLock.unlock()
            task.resume()
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        stateLock.lock()
        responseData[dataTask.taskIdentifier, default: Data()].append(data)
        stateLock.unlock()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let id = task.taskIdentifier
        stateLock.lock()
        let cont = continuations.removeValue(forKey: id)
        let data = responseData.removeValue(forKey: id) ?? Data()
        let tmp = tempFiles.removeValue(forKey: id)
        stateLock.unlock()
        if let tmp { try? FileManager.default.removeItem(at: tmp) }

        if let error {
            cont?.resume(throwing: error)
            return
        }
        let status = (task.response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200...299).contains(status) else {
            let reason = String(data: data, encoding: .utf8)
            cont?.resume(throwing: V2.ClientError.http(status: status, reason: reason))
            return
        }
        cont?.resume(returning: data)
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async {
            let handler = self.backgroundCompletionHandler
            self.backgroundCompletionHandler = nil
            handler?()
        }
    }
}

// MARK: - Minimal JSON fragment (for error-body diagnostics only)

/// A tiny sum type used ONLY to render `{error, reason, missing, max}` server
/// error bodies into a diagnostic string without force-unwrapping or committing
/// to a rigid shape. Not part of the public API.
private enum JSONFragment: Decodable, CustomStringConvertible {
    case string(String)
    case int(Int)
    case double(Double)
    case bool(Bool)
    case array([JSONFragment])
    case null

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Int.self) { self = .int(v) }
        else if let v = try? c.decode(Double.self) { self = .double(v) }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode([JSONFragment].self) { self = .array(v) }
        else { self = .null }
    }

    var description: String {
        switch self {
        case .string(let s): return s
        case .int(let i): return String(i)
        case .double(let d): return String(d)
        case .bool(let b): return String(b)
        case .array(let a): return "[" + a.map(\.description).joined(separator: ",") + "]"
        case .null: return "null"
        }
    }
}

// MARK: - Background + Resumable Blob Downloader

/// Downloads V2 blob chunks to disk via a background `URLSession`, one download
/// task per chunk. The chunk's identity (`blobId|index`) travels in
/// `taskDescription`, so a completion delivered after the app is relaunched can
/// still be matched to its slot on disk without any in-memory state. Resume =
/// whatever `.part` files are already staged are reused (see `downloadChunk`).
/// Mirrors `V2BackgroundBlobUploader`; see also `OfflineMapManager`.
nonisolated final class V2BackgroundBlobDownloader: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    static let shared = V2BackgroundBlobDownloader()
    static let backgroundSessionIdentifier = "com.oshi.v2.blob.download"

    private var session: URLSession!
    private var backgroundCompletionHandler: (() -> Void)?
    private let lock = NSLock()
    private var conts: [Int: CheckedContinuation<URL, Error>] = [:]

    private override init() {
        super.init()
        let cfg = URLSessionConfiguration.background(withIdentifier: Self.backgroundSessionIdentifier)
        cfg.isDiscretionary = false
        cfg.sessionSendsLaunchEvents = true
        cfg.timeoutIntervalForRequest = 180
        cfg.timeoutIntervalForResource = 600
        session = URLSession(configuration: cfg, delegate: self, delegateQueue: nil)
    }

    func handleBackgroundEvents(_ h: @escaping () -> Void) { backgroundCompletionHandler = h }

    /// Per-blob staging dir under Application Support (survives relaunch).
    static func stageDir(blobId: String) -> URL {
        let safe = blobId.replacingOccurrences(of: "/", with: "_")
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("blobdl", isDirectory: true)
            .appendingPathComponent(safe, isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        // Keep partial downloads out of iCloud backup.
        var url = base
        var vals = URLResourceValues(); vals.isExcludedFromBackup = true
        try? url.setResourceValues(vals)
        return base
    }
    static func chunkFile(blobId: String, index: Int) -> URL {
        stageDir(blobId: blobId).appendingPathComponent("\(index).part")
    }

    /// __GROUP_BLOB_2026_11_25__ Every name a chunk can be staged under, by the path that fetched
    /// it: background (`<n>.part`), ranged fallback, relay (OHTTP / blocked host), cover.
    static func stagedChunkNames(index: Int) -> [String] {
        ["\(index).part", "ranged-\(index).part", "ohttp-\(index).part", "cover-\(index).part"]
    }

    /// A non-empty chunk already on disk from an earlier attempt (possibly before an app kill),
    /// or nil. Staged chunks live under Application Support until the file opens.
    static func stagedChunk(blobId: String, index: Int) -> URL? {
        let dir = stageDir(blobId: blobId)
        for name in stagedChunkNames(index: index) {
            let u = dir.appendingPathComponent(name)
            if let size = (try? FileManager.default.attributesOfItem(atPath: u.path))?[.size] as? Int, size > 0 {
                return u
            }
        }
        return nil
    }

    /// Download one chunk to its staged file. If the file already exists (a prior
    /// run got this far), returns immediately — this is the resume.
    func downloadChunk(request: URLRequest, blobId: String, index: Int) async throws -> URL {
        let dest = Self.chunkFile(blobId: blobId, index: index)
        if FileManager.default.fileExists(atPath: dest.path) { return dest }
        return try await withCheckedThrowingContinuation { cont in
            let task = session.downloadTask(with: request)
            task.taskDescription = "\(blobId)|\(index)"   // survives kill + relaunch
            lock.lock(); conts[task.taskIdentifier] = cont; lock.unlock()
            task.resume()
        }
    }

    func urlSession(_ s: URLSession, downloadTask t: URLSessionDownloadTask, didFinishDownloadingTo loc: URL) {
        guard let desc = t.taskDescription, let sep = desc.lastIndex(of: "|") else { return }
        let blobId = String(desc[..<sep])
        let index = Int(desc[desc.index(after: sep)...]) ?? -1
        let status = (t.response as? HTTPURLResponse)?.statusCode ?? 0
        // Only a real 2xx is a chunk; error bodies also fire this delegate.
        guard (200...299).contains(status), index >= 0 else {
            lock.lock(); let c = conts.removeValue(forKey: t.taskIdentifier); lock.unlock()
            c?.resume(throwing: V2.ClientError.http(status: status, reason: "chunk download non-2xx"))
            return
        }
        let dest = Self.chunkFile(blobId: blobId, index: index)
        // Move off the delegate's temp slot NOW — it is deleted when this returns.
        try? FileManager.default.removeItem(at: dest)
        do {
            try FileManager.default.moveItem(at: loc, to: dest)
        } catch {
            lock.lock(); let c = conts.removeValue(forKey: t.taskIdentifier); lock.unlock()
            c?.resume(throwing: error); return
        }
        lock.lock(); let c = conts.removeValue(forKey: t.taskIdentifier); lock.unlock()
        c?.resume(returning: dest)   // nil after a relaunch -> fine, the file is on disk
    }

    func urlSession(_ s: URLSession, task t: URLSessionTask, didCompleteWithError err: Error?) {
        guard let err else { return }   // success handled in didFinishDownloadingTo
        lock.lock(); let c = conts.removeValue(forKey: t.taskIdentifier); lock.unlock()
        c?.resume(throwing: err)
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        let h = backgroundCompletionHandler
        backgroundCompletionHandler = nil
        DispatchQueue.main.async { h?() }
    }
}

// MARK: - TLS certificate pinning (SPKI)

extension V2Client {

    /// The API host whose TLS chain is pinned.
    static let apiPinnedHost = "oshi-messenger.com"

    /// SHA-256(SubjectPublicKeyInfo) pins, base64, computed from the LIVE cert
    /// chain of `apiPinnedHost` (2026-09-13):
    ///
    ///   openssl s_client -connect oshi-messenger.com:443 -servername oshi-messenger.com -showcerts
    ///   | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der
    ///   | openssl dgst -sha256 -binary | openssl base64
    ///
    /// BOTH the leaf and the issuing intermediate are pinned. Let's Encrypt
    /// renews the leaf ~every 60 days, so pinning the (longer-lived) intermediate
    /// as a backup keeps a routine leaf rotation from bricking the app, while a
    /// forged certificate on any other chain still fails the handshake.
    /// __PINS_WENT_STALE_AND_KILLED_V2_2026_10_19__ The 2026-09-13 set matched
    /// NOTHING on the live chain 36 days later, and the failure is invisible:
    /// URLSession reports a pinning rejection as `NSURLErrorDomain -999
    /// "cancelled"`, which reads like an ordinary cancellation. Every
    /// `V2Client()` takes `pinnedSession` by default, so on EVERY platform the
    /// whole v2 control plane was failing — no bundle publish (the Mac was
    /// stuck advertising `caps: 0`), and no group rosters or messages, which
    /// is how "I added the Mac to four groups and none arrived" presented.
    ///
    /// The backup pin did not save it: the leaf rotated AND the issuer moved
    /// from YE2 to YE1. Let's Encrypt alternates between those intermediates,
    /// so BOTH are pinned now — the next rotation back to YE2 must not repeat
    /// this. Verified against the live chain on 2026-10-19:
    ///
    ///   tcs1FWdgXPmNWGfDyxW1PXxrJ6C5uP1xyppLl1E/Wdg=  CN=oshi-messenger.com
    ///   brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4=  Let's Encrypt CN=YE1
    ///   sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=  ISRG Root YE
    ///
    /// 🔴 A leaf pin expires every ~60 days by design. Re-run the openssl
    /// pipeline above before each release, or ship only intermediate pins.
    /// __PINS_UNION_2026_11_20__ The comment above lists ISRG Root YE, but the
    /// set did not contain it, and Android pinned a leaf iOS did not. All three
    /// clients now carry the same union (desktop `net/CertificatePins.kt`,
    /// 60c86f2c). The root pin is what survives a leaf rotation that also moves
    /// to a new intermediate. Matching is against the EVALUATED chain
    /// (`SecTrustCopyCertificateChain` after `SecTrustEvaluateWithError`), so a
    /// pinned certificate merely appended by the server cannot satisfy it.
    static let apiSPKIPins: Set<String> = [
        "0nAG9HjNRcCEVoDETqe5BKVXl97VZqmyVK+VikDj1r4=", // previous leaf (Android)
        "tcs1FWdgXPmNWGfDyxW1PXxrJ6C5uP1xyppLl1E/Wdg=", // leaf CN=oshi-messenger.com (live 2026-10-19)
        "brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4=", // intermediate CN=YE1 — current issuer
        "s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y=", // intermediate CN=YE2 — kept: LE alternates
        "sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w="  // ISRG Root YE
    ]

    /// Foreground URLSession used for control-plane + relay + foreground chunk
    /// transfers, enforcing SPKI pinning on `apiPinnedHost`. This replaces
    /// `.shared` as the default `session`, so every `V2Client()` is pinned. The
    /// session retains its delegate for its lifetime.
    public static let pinnedSession: URLSession = {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.httpCookieStorage = nil
        cfg.httpShouldSetCookies = false
        cfg.urlCache = nil
        return URLSession(configuration: cfg,
                          delegate: OshiSPKIPinningDelegate(host: apiPinnedHost, pins: apiSPKIPins),
                          delegateQueue: nil)
    }()
}

/// `URLSessionDelegate` enforcing SPKI public-key pinning for the OSHI API host.
///
/// A server-trust challenge from `host` is accepted only if its chain, after the
/// system has validated it (expiry / name / trusted root), also presents a
/// certificate whose `SHA-256(SubjectPublicKeyInfo)` is one of `pins`. Any other
/// host keeps default system validation, so IPFS gateways, Giphy, link previews,
/// etc. are unaffected.
nonisolated final class OshiSPKIPinningDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let host: String
    private let pins: Set<String>

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(Self.followsRedirect(fromPath: task.originalRequest?.url?.path) ? request : nil)
    }

    /// __COVER_REDIRECT_2026_11_18__ No `/v2/` request — and so no cover request —
    /// ever follows a redirect: the 3xx itself is returned to the caller, which
    /// treats it as a failed (and, in cover mode, retried-over-cover) attempt.
    /// This used to compare against "/v2/cover" only; since the aliases
    /// `/v2/collect` and `/v2/metrics` (2026-11-13) two cover requests in three
    /// FOLLOWED a 302 — a request outside the cover aliases to wherever a portal
    /// or middlebox pointed (seen with tools/covert-regression/netem, captive 302).
    /// A followed redirect also carries the signed `x-oshi-*` headers elsewhere.
    static func followsRedirect(fromPath path: String?) -> Bool {
        guard let path else { return true }
        return !(path.hasPrefix("/v2/") || CoverTransport.coverPaths.contains(path))
    }


    init(host: String, pins: Set<String>) {
        self.host = host
        self.pins = pins
    }

    func urlSession(_ session: URLSession,
                    didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        // Only intercept server-trust challenges.
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        // Pin only our API host (match host or a subdomain of it). Everything else
        // keeps default validation.
        let challengedHost = challenge.protectionSpace.host
        guard challengedHost == host || challengedHost.hasSuffix("." + host) else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        // Require the system chain validation to pass FIRST, then enforce pins.
        guard SecTrustEvaluateWithError(trust, nil) else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        if Self.trust(trust, matchesAnyOf: pins) {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            // Pin mismatch → fail the TLS challenge.
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }

    /// True iff ANY certificate in the chain has a pinned SPKI digest.
    static func trust(_ trust: SecTrust, matchesAnyOf pins: Set<String>) -> Bool {
        for cert in certificates(in: trust) {
            if let digest = spkiSHA256Base64(cert), pins.contains(digest) {
                return true
            }
        }
        return false
    }

    private static func certificates(in trust: SecTrust) -> [SecCertificate] {
        if #available(iOS 15.0, *) {
            return (SecTrustCopyCertificateChain(trust) as? [SecCertificate]) ?? []
        } else {
            let count = SecTrustGetCertificateCount(trust)
            return (0..<count).compactMap { SecTrustGetCertificateAtIndex(trust, $0) }
        }
    }

    /// SHA-256, base64, of the certificate's full DER SubjectPublicKeyInfo.
    ///
    /// `SecKeyCopyExternalRepresentation` returns the bare key bits (for EC, the
    /// uncompressed point `04||X||Y`), NOT the SPKI, so the matching ASN.1 SPKI
    /// header for the key's algorithm/size is prepended before hashing — the same
    /// bytes `openssl pkey -pubin -outform der` produces (verified against the
    /// live chain).
    private static func spkiSHA256Base64(_ cert: SecCertificate) -> String? {
        guard let key = SecCertificateCopyKey(cert),
              let keyData = SecKeyCopyExternalRepresentation(key, nil) as Data?,
              let attrs = SecKeyCopyAttributes(key) as? [CFString: Any],
              let header = asn1Header(for: attrs, keyBytes: keyData.count) else {
            return nil
        }
        var spki = Data(header)
        spki.append(keyData)
        let digest = SHA256.hash(data: spki)
        return Data(digest).base64EncodedString()
    }

    /// Standard ASN.1 SPKI headers (TrustKit/OWASP table) keyed by key type + size.
    private static func asn1Header(for attrs: [CFString: Any], keyBytes: Int) -> [UInt8]? {
        let type = attrs[kSecAttrKeyType] as? String
        let bits = (attrs[kSecAttrKeySizeInBits] as? Int) ?? 0

        if type == (kSecAttrKeyTypeECSECPrimeRandom as String) || type == (kSecAttrKeyTypeEC as String) {
            switch bits {
            case 256: // P-256, 65-byte point
                return [0x30,0x59,0x30,0x13,0x06,0x07,0x2a,0x86,0x48,0xce,0x3d,0x02,0x01,
                        0x06,0x08,0x2a,0x86,0x48,0xce,0x3d,0x03,0x01,0x07,0x03,0x42,0x00]
            case 384: // P-384, 97-byte point
                return [0x30,0x76,0x30,0x10,0x06,0x07,0x2a,0x86,0x48,0xce,0x3d,0x02,0x01,
                        0x06,0x05,0x2b,0x81,0x04,0x00,0x22,0x03,0x62,0x00]
            default:
                return nil
            }
        }
        if type == (kSecAttrKeyTypeRSA as String) {
            switch bits {
            case 2048:
                return [0x30,0x82,0x01,0x22,0x30,0x0d,0x06,0x09,0x2a,0x86,0x48,0x86,0xf7,
                        0x0d,0x01,0x01,0x01,0x05,0x00,0x03,0x82,0x01,0x0f,0x00]
            case 4096:
                return [0x30,0x82,0x02,0x22,0x30,0x0d,0x06,0x09,0x2a,0x86,0x48,0x86,0xf7,
                        0x0d,0x01,0x01,0x01,0x05,0x00,0x03,0x82,0x02,0x0f,0x00]
            default:
                return nil
            }
        }
        _ = keyBytes
        return nil
    }
}


// MARK: - __GROUP_FANOUT_2026_11_24__ waiting out a relay 429

/// The relay charges one token per member envelope to a per-sender bucket (60 tokens, 10/s on the
/// relay of 2026-11-24). Reproduced on a local copy of production: a group batch of more than 60
/// members got 429 on every attempt, and `sendBatch`/`sendDeviceBatch` did not even retry, so the
/// whole group message failed. Now a 429 waits what the relay says (`Retry-After`, seconds, sent
/// from ServerPatches/group_fanout_2026_11_24 on) or backs off 1, 2, 4… s without it; each wait is
/// capped at `maxSingleWait` and all of them at `maxTotalWait`. Other statuses are returned as is.
/// Same rule as Android `RateLimitBackoff` and desktop `RateLimitBackoff`.
nonisolated enum V2RateLimitBackoff {
    static let maxSingleWait: TimeInterval = 30
    static let maxTotalWait: TimeInterval = 120

    /// `Retry-After` in delta-seconds; an HTTP-date or garbage ⇒ nil (use the backoff).
    static func retryAfterSeconds(_ header: String?) -> TimeInterval? {
        guard let h = header?.trimmingCharacters(in: .whitespaces), let n = Int(h), n >= 0 else { return nil }
        return TimeInterval(n)
    }

    /// Delay before the next attempt after a 429, or nil to stop.
    static func nextDelay(retryAfter: String?, rateLimitedSoFar: Int, waitedSoFar: TimeInterval) -> TimeInterval? {
        let backoff = TimeInterval(1 << min(rateLimitedSoFar, 5))
        let d = min(max(retryAfterSeconds(retryAfter) ?? backoff, 1), maxSingleWait)
        return waitedSoFar + d > maxTotalWait ? nil : d
    }

    /// Runs `call` again after each 429 until it is something else or the budget is spent.
    static func retrying<A>(_ call: () async throws -> A,
                            status: (A) -> Int,
                            retryAfter: (A) -> String?,
                            sleep: (TimeInterval) async -> Void = { try? await Task.sleep(nanoseconds: UInt64($0 * 1_000_000_000)) }) async throws -> A {
        var limited = 0
        var waited: TimeInterval = 0
        while true {
            let a = try await call()
            guard status(a) == 429,
                  let d = nextDelay(retryAfter: retryAfter(a), rateLimitedSoFar: limited, waitedSoFar: waited) else { return a }
            limited += 1
            waited += d
            await sleep(d)
        }
    }

    /// The relay refused the send for rate reasons even after the wait budget.
    static func isRateLimited(_ error: Error) -> Bool {
        if case .http(let status, _)? = error as? V2.ClientError { return status == 429 }
        return false
    }
}


// MARK: - __GROUP_BLOB_2026_11_25__ one blob per group message

/// Pure decisions of the group upload, unit-tested.
nonisolated enum V2GroupBlobPlan {
    /// Server cap on one reservation's list (`OSHI_BLOB_MAX_RECIPIENTS`).
    static let maxRecipients = 5000

    /// The server stored the list iff it echoed `recipients`. Without the echo it is an older
    /// server and only `recipientKey` (the first listed member) may read the blob.
    static func isShared(_ r: V2.ReserveResult) -> Bool { (r.recipients ?? 0) > 0 }

    /// Who a group blob lets read, given the list it was reserved with and the server's answer.
    static func covered(listed: [String], shared: Bool) -> [String] {
        guard let first = listed.first else { return [] }
        return shared ? listed : [first]
    }

    /// Who the shared reservation lists: the reachable members first, then the members whose
    /// reachability is unknown this attempt; never a member with no v2 bundle.
    static func readers(reachable: [String], recipients: [String], noBundle: Set<String>) -> [String] {
        let r = Set(reachable)
        return reachable + recipients.filter { !r.contains($0) && !noBundle.contains($0) }
    }

    /// Members who still need a blob of their own (old-server fallback, or a member that joined
    /// the reachable set after the shared blob was reserved).
    static func needOwnBlob(reachable: [String], covered: [String],
                            canon: (String) -> String = { $0 }) -> [String] {
        let c = Set(covered.map(canon))
        return reachable.filter { !c.contains(canon($0)) }
    }
}
