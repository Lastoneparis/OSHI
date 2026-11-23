//
//  PinataConfig.swift
//  Configuration for the content-addressed blob transport via the VPS proxy.
//
//  ⚠️ 2026-07-16: Pinata REVOKED our API key ("API_KEY_REVOKED"), taking down
//  message send/receive on the shipped 1.0.32 build. The VPS proxy backend was
//  switched from Pinata to an ON-SERVER content-addressed store
//  (/var/www/api/local_ipfs.js, blobs under /var/oshi_ipfs, real CIDv0 hashes).
//  The endpoint contract here is UNCHANGED — /api/pinata/pinning/pin* still
//  returns {IpfsHash}, /api/ipfs/proxy/<cid> still serves it — so no app change
//  was needed to restore the live app. Do NOT reintroduce a Pinata dependency;
//  keep the transport self-hosted (it can't be revoked out from under us).
//

import Foundation
import CryptoKit

struct PinataConfig {
    // VPS proxy endpoint. Backend is now the self-hosted blob store (was Pinata).
    static let baseURL = "https://oshi-messenger.com/api/pinata"

    // API URL (always via proxy for anonymity — VPS handles credentials)
    static var apiURL: String { baseURL }

    // Auth headers — proxy handles Pinata authentication server-side
    static func authHeaders() -> [String: String] {
        return [:]
    }
}

// MARK: - Pinata Service

class PinataService {
    static let shared = PinataService()
    
    // Test connection
    func testConnection() async throws -> Bool {
        let url = URL(string: "\(PinataConfig.apiURL)/data/testAuthentication")!
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        
        // Add auth headers
        for (key, value) in PinataConfig.authHeaders() {
            request.setValue(value, forHTTPHeaderField: key)
        }
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse else {
            throw PinataError.invalidResponse
        }
        
        if httpResponse.statusCode == 200 {
            OshiLog.messaging.info("✅ Pinata connection successful")
            OshiLog.messaging.info("Response: \(String(data: data, encoding: .utf8) ?? "N/A")")
            return true
        } else {
            OshiLog.messaging.info("❌ Pinata connection failed: \(httpResponse.statusCode)")
            OshiLog.messaging.info("Response: \(String(data: data, encoding: .utf8) ?? "N/A")")
            throw PinataError.authenticationFailed
        }
    }
    
    // Pin JSON to IPFS
    func pinJSON(_ json: [String: Any], name: String) async throws -> String {
        let url = URL(string: "\(PinataConfig.apiURL)/pinning/pinJSONToIPFS")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        // Add auth headers
        for (key, value) in PinataConfig.authHeaders() {
            request.setValue(value, forHTTPHeaderField: key)
        }
        
        let body: [String: Any] = [
            "pinataContent": json,
            "pinataMetadata": [
                "name": name
            ]
        ]
        
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse else {
            throw PinataError.invalidResponse
        }
        
        if httpResponse.statusCode == 200 {
            let result = try JSONSerialization.jsonObject(with: data) as! [String: Any]
            let ipfsHash = result["IpfsHash"] as! String
            OshiLog.messaging.info("✅ Pinned to IPFS: \(ipfsHash)")
            return ipfsHash
        } else {
            OshiLog.messaging.info("❌ Pin failed: \(httpResponse.statusCode)")
            OshiLog.messaging.info("Response: \(String(data: data, encoding: .utf8) ?? "N/A")")
            throw PinataError.pinFailed
        }
    }
    
    // Pin JSON to IPFS with progress tracking
    func pinJSONWithProgress(_ json: [String: Any], name: String, progressHandler: @escaping (Double) -> Void) async throws -> String {
        let url = URL(string: "\(PinataConfig.apiURL)/pinning/pinJSONToIPFS")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 300  // 5 minutes for large files
        
        // Add auth headers
        for (key, value) in PinataConfig.authHeaders() {
            request.setValue(value, forHTTPHeaderField: key)
        }
        
        let body: [String: Any] = [
            "pinataContent": json,
            "pinataMetadata": [
                "name": name
            ]
        ]
        
        let bodyData = try JSONSerialization.data(withJSONObject: body)
        // Don't set request.httpBody - we pass data to uploadTask instead
        
        // Calculate total size for progress estimation
        let totalSize = bodyData.count
        OshiLog.messaging.info("📤 Uploading \(String(format: "%.2f", Double(totalSize) / 1_000_000.0)) MB to IPFS...")
        
        // Use URLSession with delegate for progress tracking
        let (data, response) = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<(Data, URLResponse), Error>) in
            let delegate = UploadProgressDelegate(totalBytes: Int64(totalSize), progressHandler: progressHandler)
            let session = URLSession(configuration: .default, delegate: delegate, delegateQueue: nil)
            
            let task = session.uploadTask(with: request, from: bodyData) { data, response, error in
                if let error = error {
                    continuation.resume(throwing: error)
                } else if let data = data, let response = response {
                    continuation.resume(returning: (data, response))
                } else {
                    continuation.resume(throwing: PinataError.invalidResponse)
                }
            }
            task.resume()
        }
        
        guard let httpResponse = response as? HTTPURLResponse else {
            throw PinataError.invalidResponse
        }
        
        if httpResponse.statusCode == 200 {
            let result = try JSONSerialization.jsonObject(with: data) as! [String: Any]
            let ipfsHash = result["IpfsHash"] as! String
            OshiLog.messaging.info("✅ Pinned to IPFS: \(ipfsHash)")
            progressHandler(1.0)
            return ipfsHash
        } else {
            OshiLog.messaging.info("❌ Pin failed: \(httpResponse.statusCode)")
            OshiLog.messaging.info("Response: \(String(data: data, encoding: .utf8) ?? "N/A")")
            throw PinataError.pinFailed
        }
    }
    
    /// Pin a raw `Data` blob to IPFS via the multipart `pinFileToIPFS`
    /// endpoint with per-byte upload progress and a tighter, retry-friendly
    /// timeout.
    ///
    /// WHY this exists separately from `pinJSONWithProgress`: that helper
    /// posts to `/pinning/pinJSONToIPFS` which expects a JSON-shaped body.
    /// For binary or large payloads (encrypted media envelopes, etc.) the
    /// JSON endpoint forces base64 encoding (+33% bandwidth) and applies
    /// stricter rate limits. Routing through `pinFileToIPFS` (multipart)
    /// lets us ship the bytes directly, gets uniform progress callbacks
    /// from URLSession, and unlocks the longer-payload behavior Pinata
    /// applies to file pins.
    ///
    /// Caller wraps this in `withPinataRetry` for backoff on transient
    /// failures (network drops, 429s, 5xx).
    func pinFileWithProgress(
        _ fileData: Data,
        filename: String,
        mimeType: String,
        contentTag: String? = nil,
        progressHandler: @escaping (Double) -> Void
    ) async throws -> String {
        let url = URL(string: "\(PinataConfig.apiURL)/pinning/pinFileToIPFS")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        // 🔧 PERF FIX: `timeoutInterval` is an IDLE timeout. The byte upload
        // finishes quickly (a few hundred KB), but the request then sits idle
        // while the VPS proxy forwards to Pinata and Pinata PINS the file to the
        // IPFS network — which routinely takes 30–90 s. At the old 60 s budget,
        // that pin-wait tripped a timeout and `withPinataRetry` re-uploaded the
        // WHOLE file from 0 % again (and again), so a ~0.5 MB payload could burn
        // ~3 minutes in repeated full re-uploads while the bar sat frozen at 89 %.
        // 180 s lets the pin response arrive on the first attempt; even a 200 Kbps
        // link uploads the payload in well under that, so the budget is for the
        // server-side pin, not the transfer. Retries remain for genuine drops.
        request.timeoutInterval = 180

        let boundary = UUID().uuidString
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")

        for (key, value) in PinataConfig.authHeaders() {
            request.setValue(value, forHTTPHeaderField: key)
        }

        // Build the multipart body once so URLSession can stream it.
        var body = Data()
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"\(filename)\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: \(mimeType)\r\n\r\n".data(using: .utf8)!)
        body.append(fileData)
        body.append("\r\n".data(using: .utf8)!)

        // Tag the pin with a deterministic content hash (when provided) so a retry
        // can find an already-pinned copy instead of re-uploading (see findExistingPin).
        let metadata: [String: Any] = ["name": contentTag ?? filename]
        let metadataJSON = try JSONSerialization.data(withJSONObject: metadata)
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"pinataMetadata\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: application/json\r\n\r\n".data(using: .utf8)!)
        body.append(metadataJSON)
        body.append("\r\n".data(using: .utf8)!)
        body.append("--\(boundary)--\r\n".data(using: .utf8)!)

        OshiLog.messaging.info("📤 Uploading \(String(format: "%.2f", Double(fileData.count) / 1_000_000.0)) MB to IPFS (file endpoint)")

        let (responseData, response) = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<(Data, URLResponse), Error>) in
            let delegate = UploadProgressDelegate(totalBytes: Int64(body.count), progressHandler: progressHandler)
            let session = URLSession(configuration: .default, delegate: delegate, delegateQueue: nil)
            let task = session.uploadTask(with: request, from: body) { data, response, error in
                // 🔧 FIX: invalidate so the delegate-retaining session doesn't leak
                // on every upload (URLSession with a delegate is retained until
                // invalidated). finishTasksAndInvalidate lets THIS task finish first.
                session.finishTasksAndInvalidate()
                if let error = error {
                    continuation.resume(throwing: error)
                } else if let data = data, let response = response {
                    continuation.resume(returning: (data, response))
                } else {
                    continuation.resume(throwing: PinataError.invalidResponse)
                }
            }
            task.resume()
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            throw PinataError.invalidResponse
        }

        if httpResponse.statusCode == 200 {
            let result = try JSONSerialization.jsonObject(with: responseData) as! [String: Any]
            let ipfsHash = result["IpfsHash"] as! String
            OshiLog.messaging.info("✅ Pinned file to IPFS: \(ipfsHash)")
            progressHandler(1.0)
            return ipfsHash
        }

        // Surface the status code via a typed error so withPinataRetry can
        // classify it (e.g. retry on 5xx/429, give up on 4xx).
        OshiLog.messaging.info("❌ Pin failed: HTTP \(httpResponse.statusCode)")
        OshiLog.messaging.info("Response: \(String(data: responseData, encoding: .utf8) ?? "N/A")")
        throw PinataError.httpError(status: httpResponse.statusCode)
    }

    // Pin file to IPFS
    func pinFile(_ data: Data, filename: String, mimeType: String) async throws -> String {
        let url = URL(string: "\(PinataConfig.apiURL)/pinning/pinFileToIPFS")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        
        let boundary = UUID().uuidString
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        
        // Add auth headers
        for (key, value) in PinataConfig.authHeaders() {
            request.setValue(value, forHTTPHeaderField: key)
        }
        
        var body = Data()
        
        // File data
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"\(filename)\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: \(mimeType)\r\n\r\n".data(using: .utf8)!)
        body.append(data)
        body.append("\r\n".data(using: .utf8)!)
        
        // Metadata
        let metadata = ["name": filename]
        let metadataJSON = try JSONSerialization.data(withJSONObject: metadata)
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"pinataMetadata\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: application/json\r\n\r\n".data(using: .utf8)!)
        body.append(metadataJSON)
        body.append("\r\n".data(using: .utf8)!)
        
        body.append("--\(boundary)--\r\n".data(using: .utf8)!)
        
        request.httpBody = body
        
        let (responseData, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse else {
            throw PinataError.invalidResponse
        }
        
        if httpResponse.statusCode == 200 {
            let result = try JSONSerialization.jsonObject(with: responseData) as! [String: Any]
            let ipfsHash = result["IpfsHash"] as! String
            OshiLog.messaging.info("✅ Pinned file to IPFS: \(ipfsHash)")
            return ipfsHash
        } else {
            OshiLog.messaging.info("❌ Pin failed: \(httpResponse.statusCode)")
            OshiLog.messaging.info("Response: \(String(data: responseData, encoding: .utf8) ?? "N/A")")
            throw PinataError.pinFailed
        }
    }
    
    // Get pinned files
    func getPinnedFiles() async throws -> [[String: Any]] {
        let url = URL(string: "\(PinataConfig.apiURL)/data/pinList?status=pinned")!
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        
        // Add auth headers
        for (key, value) in PinataConfig.authHeaders() {
            request.setValue(value, forHTTPHeaderField: key)
        }
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse else {
            throw PinataError.invalidResponse
        }
        
        if httpResponse.statusCode == 200 {
            let result = try JSONSerialization.jsonObject(with: data) as! [String: Any]
            let rows = result["rows"] as! [[String: Any]]
            OshiLog.messaging.info("✅ Retrieved \(rows.count) pinned files")
            return rows
        } else {
            throw PinataError.requestFailed
        }
    }
    
    // MARK: - 🗑️ UNPIN - Remove from IPFS after delivery
    
    /// Unpin a file from IPFS - content will be garbage collected
    /// Call this AFTER message has been delivered to recipient
    func unpin(_ ipfsHash: String) async throws {
        // 🔧 FIX: Use VPS proxy for Pinata (not direct!)
        // Pinata API: DELETE /pinning/unpin/{hashToUnpin}
        let url = URL(string: "\(PinataConfig.apiURL)/pinning/unpin/\(ipfsHash)")!
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        
        // Auth headers (VPS proxy handles auth server-side)
        for (key, value) in PinataConfig.authHeaders() {
            request.setValue(value, forHTTPHeaderField: key)
        }
        request.timeoutInterval = 30
        
        OshiLog.messaging.info("🗑️ Unpinning from IPFS: \(ipfsHash.prefix(20))...")
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse else {
            throw PinataError.invalidResponse
        }
        
        switch httpResponse.statusCode {
        case 200:
            OshiLog.messaging.info("✅ Successfully unpinned: \(ipfsHash.prefix(20))...")
            OshiLog.messaging.info("   Content will be garbage collected from IPFS network")
        case 404:
            // Already unpinned or never existed - not an error
            OshiLog.messaging.info("ℹ️ Hash not found (already unpinned): \(ipfsHash.prefix(20))...")
        case 401:
            OshiLog.messaging.info("❌ Unpin failed: Authentication error")
            throw PinataError.authenticationFailed
        default:
            let responseStr = String(data: data, encoding: .utf8) ?? "No response"
            OshiLog.messaging.info("❌ Unpin failed [\(httpResponse.statusCode)]: \(responseStr)")
            throw PinataError.requestFailed
        }
    }
    
    /// Unpin multiple hashes (batch cleanup)
    func unpinMultiple(_ ipfsHashes: [String]) async {
        OshiLog.messaging.info("🗑️ Batch unpinning \(ipfsHashes.count) items...")
        
        var successCount = 0
        var failCount = 0
        
        for hash in ipfsHashes {
            do {
                try await unpin(hash)
                successCount += 1
            } catch {
                failCount += 1
                OshiLog.messaging.info("   ⚠️ Failed to unpin \(hash.prefix(15))...: \(error.localizedDescription)")
            }
            
            // Small delay to avoid rate limiting
            try? await Task.sleep(nanoseconds: 100_000_000) // 0.1s
        }
        
        OshiLog.messaging.info("✅ Batch unpin complete: \(successCount) success, \(failCount) failed")
    }
}

// MARK: - Errors

enum PinataError: Error {
    case invalidResponse
    case authenticationFailed
    case pinFailed
    case requestFailed
    case fileTooLarge
    case encryptionFailed
    case decryptionFailed
    /// HTTP non-200 from Pinata; carries the status so `withPinataRetry`
    /// can decide whether to back-off-and-retry (5xx, 429) or fail fast
    /// (4xx that won't get better — 400 malformed, 401 auth, 403 forbidden,
    /// 413 payload too large).
    case httpError(status: Int)
}

extension PinataError {
    /// Should `withPinataRetry` retry this error?
    var isTransient: Bool {
        if case .httpError(let status) = self {
            return status == 429 || (500...599).contains(status)
        }
        return false
    }
}

// MARK: - Usage Example

// MARK: - Retry wrapper

extension PinataService {
    /// Run a Pinata upload-style block with up to `maxAttempts` total tries,
    /// pausing 2s / 4s / 8s between attempts. Only retries on transient
    /// errors (Pinata 5xx/429, URL session timeouts, connection-lost,
    /// host-unreachable). Permanent errors (4xx, decoding) propagate
    /// immediately so the user gets a fast failure message instead of a
    /// 30-second backoff loop.
    ///
    /// `label` is purely for logs.
    func withPinataRetry<T>(
        maxAttempts: Int = 3,
        label: String = "pinata-op",
        operation: () async throws -> T
    ) async throws -> T {
        var lastError: Error?
        for attempt in 1...maxAttempts {
            do {
                return try await operation()
            } catch {
                lastError = error
                let transient = isTransientError(error)
                if attempt == maxAttempts || !transient {
                    OshiLog.messaging.info("❌ \(label) failed (attempt \(attempt)/\(maxAttempts), transient=\(transient)): \(error.localizedDescription)")
                    throw error
                }
                // Exponential back-off: 2s, 4s, 8s.
                let delaySeconds = UInt64(pow(2.0, Double(attempt))) * 1_000_000_000
                OshiLog.messaging.info("⚠️ \(label) attempt \(attempt) failed (transient) — retrying in \(delaySeconds / 1_000_000_000)s")
                try? await Task.sleep(nanoseconds: delaySeconds)
            }
        }
        throw lastError ?? PinataError.requestFailed
    }

    private func isTransientError(_ error: Error) -> Bool {
        if let pinata = error as? PinataError { return pinata.isTransient }
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

    // ≥ this size → route the upload through the BACKGROUND URLSession instead of
    // the foreground request. Small sends (text, small images) stay on the
    // immediate foreground path; large media (videos, multi-MB) gets the
    // background session's longer resource budget.
    //
    // 🔧 FIX (2026-08-11, "a 502 KB photo sits at 30 % for tens of seconds"):
    // 2 MB → 12 MB. The comment above says "small images stay foreground", and
    // that was FALSE for every photo, because this is compared against the
    // ENVELOPE, not the file. `sendSingleMessage` wraps media through six nested
    // base64/JSON layers before it gets here — MEASURED at 5.62× (502 KB → 2822
    // KB). So the real-file break-even was 356 KB, and essentially every photo
    // crossed it into the background session.
    //
    // The symptom is specific and matches exactly: `uploadToIPFS` sets progress
    // to 0.30 immediately BEFORE handing off, and moves it only when byte-
    // progress events arrive. A background transfer that `nsurlsessiond` has not
    // scheduled emits none, so the bar sits at precisely 30 %.
    //
    // 12 MB of envelope ≈ 2.1 MB of real file — a large photo or short video
    // still gets the background session's suspend survival, while ordinary
    // photos upload immediately. MEASURED against the live host: 2.82 MB pins in
    // 2.6–2.9 s foreground, so a 12 MB envelope is ~11 s, still inside the 180 s
    // foreground idle timeout with a wide margin.
    //
    // Two stale comments elsewhere argued for the old value and should not be
    // trusted: "the server is now pinning to IPFS (often 30–90 s)" and "slow
    // Tor-routed pin, ~335 KB/s". Pinata was dropped on 2026-07-16 for a
    // self-hosted local pin (`ServerVPS/api/local_ipfs.js`) that is sha256 +
    // write; measured throughput today is 960 KB–1.08 MB/s.
    static let backgroundUploadThreshold = 12_000_000  // 12 MB of ENVELOPE ≈ 2.1 MB of file

    /// Look up an already-pinned copy of this content by its deterministic tag
    /// (set as the pin's metadata `name`). Best-effort: any failure returns nil so
    /// the caller simply re-uploads. Used on RETRY so a prior attempt that pinned
    /// server-side — but whose 200 the slow Tor path never returned to the client —
    /// is reused instead of re-uploading the whole file from 0%.
    func findExistingPin(contentTag: String) async -> String? {
        // contentTag is all-alphanumeric by construction, so percent-encoding is a no-op for it.
        let encodedTag = contentTag.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? contentTag
        let urlStr = "\(PinataConfig.apiURL)/data/pinList?status=pinned&pageLimit=1&metadata%5Bname%5D=\(encodedTag)"
        guard let url = URL(string: urlStr) else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 30
        for (k, v) in PinataConfig.authHeaders() { request.setValue(v, forHTTPHeaderField: k) }
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse)?.statusCode == 200,
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rows = obj["rows"] as? [[String: Any]],
              let hash = rows.first?["ipfs_pin_hash"] as? String else { return nil }
        return hash
    }

    /// Resilient media pin for the message-send path.
    /// - Routes payloads ≥ `backgroundUploadThreshold` through the BACKGROUND
    ///   URLSession (survives app backgrounding and a slow Tor pin); smaller
    ///   payloads keep the immediate foreground path.
    /// - Makes retries idempotent by content: the payload is tagged with a
    ///   deterministic SHA-256, and on any retry we first ask Pinata whether that
    ///   content is already pinned and reuse its CID — so a stalled/timed-out
    ///   attempt never restarts the whole transfer from 0%.
    func pinMediaResilient(
        _ payload: Data,
        pinName: String,
        messageId: String,
        recipientPublicKey: String,
        senderPublicKey: String,
        maxAttempts: Int = 3,
        label: String = "pinMedia",
        progressHandler: @escaping (Double) -> Void
    ) async throws -> String {
        let tag = "oshimedia" + Data(SHA256.hash(data: payload)).map { String(format: "%02x", $0) }.joined()
        let useBackground = payload.count >= Self.backgroundUploadThreshold
        OshiLog.messaging.info("📤 \(label): \(String(format: "%.2f", Double(payload.count)/1_000_000.0)) MB, transport=\(useBackground ? "background" : "foreground")")
        var lastError: Error?
        for attempt in 1...maxAttempts {
            // On retry, reuse a server-side pin from a previous attempt rather than
            // restarting the whole transfer (the slow-Tor stall case).
            if attempt > 1, let hash = await findExistingPin(contentTag: tag) {
                OshiLog.messaging.info("♻️ \(label): reusing already-pinned content \(hash) — skipped re-upload on attempt \(attempt)")
                progressHandler(1.0)
                return hash
            }
            do {
                if useBackground {
                    return try await pinFileInBackground(
                        payload,
                        filename: "\(pinName).json",
                        mimeType: "application/json",
                        messageId: messageId,
                        recipientPublicKey: recipientPublicKey,
                        senderPublicKey: senderPublicKey,
                        contentTag: tag,
                        progressHandler: progressHandler
                    )
                } else {
                    return try await pinFileWithProgress(
                        payload,
                        filename: "\(pinName).json",
                        mimeType: "application/json",
                        contentTag: tag,
                        progressHandler: progressHandler
                    )
                }
            } catch {
                lastError = error
                let transient = isTransientError(error)
                if attempt == maxAttempts || !transient {
                    OshiLog.messaging.info("❌ \(label) failed (attempt \(attempt)/\(maxAttempts), transient=\(transient)): \(error.localizedDescription)")
                    throw error
                }
                let delaySeconds = UInt64(pow(2.0, Double(attempt))) * 1_000_000_000
                OshiLog.messaging.info("⚠️ \(label) attempt \(attempt) failed (transient) — retrying in \(delaySeconds / 1_000_000_000)s")
                try? await Task.sleep(nanoseconds: delaySeconds)
            }
        }
        throw lastError ?? PinataError.requestFailed
    }
}

// MARK: - Usage Example

extension PinataService {
    func exampleUsage() async {
        do {
            // Test connection
            OshiLog.messaging.info("Testing Pinata connection...")
            _ = try await testConnection()
            
            // Pin JSON
            OshiLog.messaging.info("\nPinning JSON...")
            let json: [String: Any] = ["message": "Hello from iOS!", "timestamp": Date().timeIntervalSince1970]
            let ipfsHash = try await pinJSON(json, name: "test_message")
            OshiLog.messaging.info("Access at: https://gateway.pinata.cloud/ipfs/\(ipfsHash)")
            
            // Pin file
            OshiLog.messaging.info("\nPinning file...")
            let textData = "Hello IPFS!".data(using: .utf8)!
            let fileHash = try await pinFile(textData, filename: "hello.txt", mimeType: "text/plain")
            OshiLog.messaging.info("Access at: https://gateway.pinata.cloud/ipfs/\(fileHash)")
            
            // Get pinned files
            OshiLog.messaging.info("\nGetting pinned files...")
            let files = try await getPinnedFiles()
            for file in files.prefix(5) {
                if let hash = file["ipfs_pin_hash"] as? String,
                   let name = (file["metadata"] as? [String: Any])?["name"] as? String {
                    OshiLog.messaging.info("  - \(name): \(hash)")
                }
            }
            
        } catch {
            OshiLog.messaging.info("❌ Error: \(error)")
        }
    }
}

// MARK: - Upload Progress Delegate

class UploadProgressDelegate: NSObject, URLSessionTaskDelegate {
    private let totalBytes: Int64
    private let progressHandler: (Double) -> Void
    private var lastReportedProgress: Double = 0
    
    init(totalBytes: Int64, progressHandler: @escaping (Double) -> Void) {
        self.totalBytes = totalBytes
        self.progressHandler = progressHandler
        super.init()
    }
    
    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64, totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        let progress = Double(totalBytesSent) / Double(max(totalBytesExpectedToSend, 1))
        
        // Only report if progress changed by at least 5%
        if progress - lastReportedProgress >= 0.05 || progress >= 1.0 {
            lastReportedProgress = progress
            OshiLog.messaging.info("📤 Upload progress: \(Int(progress * 100))%")
            DispatchQueue.main.async {
                self.progressHandler(progress)
            }
        }
    }
}

// MARK: - Background Media Upload

extension PinataService {
    /// Upload a media payload to IPFS via a **background** URLSession so the
    /// transfer (and the server-side pin) keeps running even when the user
    /// backgrounds the app or iOS suspends the process. Mirrors
    /// `pinFileWithProgress` but hands the request to `BackgroundMediaUploader`
    /// (which uploads from a file — background sessions can't use a Data body).
    ///
    /// If the app is alive when the transfer finishes, the IPFS hash is returned
    /// here and `uploadToIPFS` continues normally (mark .sent + notifyVPS). If
    /// the app was killed mid-transfer and relaunched in the background to
    /// deliver completion, this call's continuation no longer exists, so the
    /// uploader drives `MessageManager.completeBackgroundUpload(...)` instead.
    func pinFileInBackground(
        _ fileData: Data,
        filename: String,
        mimeType: String,
        messageId: String,
        recipientPublicKey: String,
        senderPublicKey: String,
        contentTag: String? = nil,
        progressHandler: @escaping (Double) -> Void
    ) async throws -> String {
        let url = URL(string: "\(PinataConfig.apiURL)/pinning/pinFileToIPFS")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        let boundary = UUID().uuidString
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        for (key, value) in PinataConfig.authHeaders() {
            request.setValue(value, forHTTPHeaderField: key)
        }

        // Build the multipart body (identical shape to pinFileWithProgress).
        var body = Data()
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"\(filename)\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: \(mimeType)\r\n\r\n".data(using: .utf8)!)
        body.append(fileData)
        body.append("\r\n".data(using: .utf8)!)
        // Content-tag the pin (see findExistingPin) so a retry can reuse it.
        let metadata: [String: Any] = ["name": contentTag ?? filename]
        let metadataJSON = try JSONSerialization.data(withJSONObject: metadata)
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"pinataMetadata\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: application/json\r\n\r\n".data(using: .utf8)!)
        body.append(metadataJSON)
        body.append("\r\n".data(using: .utf8)!)
        body.append("--\(boundary)--\r\n".data(using: .utf8)!)

        // Background sessions require uploading FROM A FILE (not a Data body),
        // so stage the multipart body in the temp dir. The uploader deletes it
        // once the task completes.
        let tempURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("oshi_upload_\(UUID().uuidString).multipart")
        try body.write(to: tempURL, options: .atomic)

        OshiLog.messaging.info("📤 [bg] Uploading \(String(format: "%.2f", Double(fileData.count) / 1_000_000.0)) MB to IPFS via background session")

        let meta = BackgroundMediaUploader.UploadMetadata(
            messageId: messageId,
            recipientPublicKey: recipientPublicKey,
            senderPublicKey: senderPublicKey
        )
        return try await BackgroundMediaUploader.shared.upload(
            bodyFileURL: tempURL,
            request: request,
            metadata: meta,
            progress: progressHandler
        )
    }
}

/// Owns the single background URLSession used for media (image/file) uploads to
/// IPFS. Follows the same pattern as `OfflineMapManager` /`LocalLLMManager`:
/// AppDelegate routes `handleEventsForBackgroundURLSession` here, and we invoke
/// the stashed completion handler from `urlSessionDidFinishEvents`.
final class BackgroundMediaUploader: NSObject, URLSessionDataDelegate {
    static let shared = BackgroundMediaUploader()
    static let backgroundSessionIdentifier = "com.oshi.pinata.upload"

    /// Travels with the task via `taskDescription`, so it survives an app
    /// kill+relaunch and lets us finish the send (notifyVPS) without the
    /// original in-memory continuation.
    struct UploadMetadata {
        let messageId: String
        let recipientPublicKey: String
        let senderPublicKey: String

        func encodedString() -> String {
            let dict = ["m": messageId, "r": recipientPublicKey, "s": senderPublicKey]
            guard let data = try? JSONSerialization.data(withJSONObject: dict),
                  let str = String(data: data, encoding: .utf8) else { return "" }
            return str
        }

        static func decode(_ s: String?) -> UploadMetadata? {
            guard let s, let data = s.data(using: .utf8),
                  let dict = try? JSONSerialization.jsonObject(with: data) as? [String: String],
                  let m = dict["m"], let r = dict["r"], let sender = dict["s"] else { return nil }
            return UploadMetadata(messageId: m, recipientPublicKey: r, senderPublicKey: sender)
        }
    }

    private var session: URLSession!
    private var backgroundCompletionHandler: (() -> Void)?

    private let stateLock = NSLock()
    private var responseData: [Int: Data] = [:]
    private var continuations: [Int: CheckedContinuation<String, Error>] = [:]
    private var progressHandlers: [Int: (Double) -> Void] = [:]
    private var tempFiles: [Int: URL] = [:]

    private override init() {
        super.init()
        let config = URLSessionConfiguration.background(withIdentifier: Self.backgroundSessionIdentifier)
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        // Generous resource budget: the bytes are small but the server-side IPFS
        // pin can take a while. The system keeps the transfer alive regardless of
        // app state.
        config.timeoutIntervalForRequest = 180
        config.timeoutIntervalForResource = 600
        session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }

    func handleBackgroundEvents(_ handler: @escaping () -> Void) {
        backgroundCompletionHandler = handler
    }

    func upload(
        bodyFileURL: URL,
        request: URLRequest,
        metadata: UploadMetadata,
        progress: @escaping (Double) -> Void
    ) async throws -> String {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<String, Error>) in
            let task = session.uploadTask(with: request, fromFile: bodyFileURL)
            task.taskDescription = metadata.encodedString()
            let id = task.taskIdentifier
            stateLock.lock()
            continuations[id] = cont
            progressHandlers[id] = progress
            tempFiles[id] = bodyFileURL
            stateLock.unlock()
            task.resume()
        }
    }

    // MARK: URLSessionTaskDelegate / DataDelegate

    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64, totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        let p = Double(totalBytesSent) / Double(max(totalBytesExpectedToSend, 1))
        stateLock.lock(); let h = progressHandlers[task.taskIdentifier]; stateLock.unlock()
        h?(p)
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
        progressHandlers.removeValue(forKey: id)
        let data = responseData.removeValue(forKey: id) ?? Data()
        let tmp = tempFiles.removeValue(forKey: id)
        stateLock.unlock()
        if let tmp { try? FileManager.default.removeItem(at: tmp) }

        let meta = UploadMetadata.decode(task.taskDescription)

        func fail(_ err: Error) {
            if let cont {
                cont.resume(throwing: err)
            } else if let meta {
                Task { @MainActor in MessageManager.shared?.failBackgroundUpload(messageId: meta.messageId) }
            }
        }

        if let error {
            OshiLog.messaging.info("❌ [bg] Upload task error: \(error.localizedDescription)")
            fail(error)
            return
        }

        let status = (task.response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let hash = json["IpfsHash"] as? String else {
            OshiLog.messaging.info("❌ [bg] Upload bad response: HTTP \(status)")
            fail(PinataError.httpError(status: status))
            return
        }

        OshiLog.messaging.info("✅ [bg] Pinned to IPFS: \(hash) (continuation=\(cont != nil))")
        if let cont {
            // App alive: hand the hash back so uploadToIPFS finishes the send.
            cont.resume(returning: hash)
        } else if let meta {
            // App was killed+relaunched mid-transfer — finish the send here.
            Task { @MainActor in
                MessageManager.shared?.completeBackgroundUpload(
                    messageId: meta.messageId,
                    recipientPublicKey: meta.recipientPublicKey,
                    senderPublicKey: meta.senderPublicKey,
                    ipfsHash: hash
                )
            }
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async {
            let handler = self.backgroundCompletionHandler
            self.backgroundCompletionHandler = nil
            handler?()
        }
    }
}
