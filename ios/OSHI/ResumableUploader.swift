//
//  ResumableUploader.swift
//  OSHI
//
//  Chunked, resumable upload for LARGE encrypted payloads (up to ~300 MB of
//  base64-JSON, i.e. raw files up to 200 MB). Talks to the VPS resumable service
//  at /api/upload/ (see ServerVPS/resumable_upload.js).
//
//  WHY: the front nginx caps a single POST at 50 MB. This uploads the payload in
//  ≤8 MB chunks that each slip under that wall, appends them by byte-offset, and —
//  if the transfer is interrupted (network drop, backgrounding, even an app
//  relaunch) — asks the server how many bytes it already has and resumes from
//  exactly there instead of restarting from zero. The finalized content id is the
//  SAME CIDv0 the single-POST blob store produces, so recipients fetch large files
//  through the existing /api/ipfs/proxy path with no changes.
//

import Foundation

enum ResumableError: Error {
    case initFailed(Int)
    case chunkFailed(Int)
    case finalizeFailed(Int)
    case incomplete
    case badResponse
    case payloadTooLarge
}

/// Threshold above which a payload MUST use the resumable/chunked path.
///
/// 🔧 FIX: this was 45 MB, which meant a ~30 MB document (≈41 MB base64-JSON
/// envelope) sat JUST under the bar and took the single-POST legacy path. That
/// path streams the whole payload through the VPS→Pinata proxy over a slow/jittery
/// Tor circuit (~335 KB/s measured) — a 41 MB one-shot upload routinely stalled,
/// tripped the idle timeout, and surfaced as "Appuyez pour réessayer". The
/// resumable path chunks (8 MB) under the 50 MB nginx wall, stores directly in the
/// on-server blob store (no Tor proxy hop), and resumes from the last acked byte on
/// interruption. Lowering the bar to 12 MB routes multi-MB documents through it so
/// they actually deliver, while short images/clips keep the immediate single POST.
let kResumableUploadThreshold = 12 * 1024 * 1024

actor ResumableUploader {
    static let shared = ResumableUploader()

    private let baseURL = "https://oshi-messenger.com/api/upload"
    private let chunkSize = 8 * 1024 * 1024          // 8 MB — well under the 50 MB wall
    private let session: URLSession

    // Persisted map: contentTag -> uploadId, so a retry (even after relaunch)
    // reuses the server-side partial and resumes instead of starting over.
    private var resumeMap: [String: String] = [:]

    private init() {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 120
        cfg.waitsForConnectivity = true
        self.session = URLSession(configuration: cfg)
        self.resumeMap = Self.loadResumeMap()
    }

    // MARK: - Public API

    /// Upload `payload` in resumable chunks and return the CIDv0 content hash.
    /// `contentTag` is a stable, deterministic id for this exact content — reusing
    /// it lets an interrupted upload resume. `progress` reports 0…1 by bytes acked.
    func upload(_ payload: Data,
                contentTag: String,
                progress: (Double) -> Void) async throws -> String {
        guard payload.count <= 300 * 1024 * 1024 else { throw ResumableError.payloadTooLarge }
        let total = payload.count

        // Resume an existing server-side partial if we have one for this content,
        // otherwise initialise a fresh upload.
        var uploadId = resumeMap[contentTag]
        var offset = 0
        if let existing = uploadId, let serverOffset = try? await queryOffset(existing) {
            offset = serverOffset
            OshiLog.messaging.info("↩️ Resuming upload \(existing.prefix(8)) at \(offset)/\(total) bytes")
        } else {
            uploadId = try await initUpload(size: total, name: contentTag)
            resumeMap[contentTag] = uploadId
            persistResumeMap()
            offset = 0
        }
        guard let id = uploadId else { throw ResumableError.badResponse }

        progress(total > 0 ? Double(offset) / Double(total) : 0)

        // Stream chunks from `offset` to the end. A 409 (offset mismatch) resyncs
        // to the server's real offset and continues — never a full restart.
        while offset < total {
            let end = min(offset + chunkSize, total)
            let chunk = payload.subdata(in: offset..<end)
            let result = try await putChunk(id: id, offset: offset, chunk: chunk)
            switch result {
            case .accepted(let newOffset):
                offset = newOffset
            case .resync(let serverOffset):
                OshiLog.messaging.info("🔄 Offset resync \(offset) → \(serverOffset)")
                offset = serverOffset
            }
            progress(total > 0 ? Double(offset) / Double(total) : 1)
        }

        let cid = try await finalize(id: id)
        // Success — drop the resume record; the content now lives under its CID.
        resumeMap[contentTag] = nil
        persistResumeMap()
        progress(1.0)
        OshiLog.messaging.info("✅ Resumable upload complete: \(cid)")
        return cid
    }

    // MARK: - Protocol steps

    private func initUpload(size: Int, name: String) async throws -> String {
        var req = URLRequest(url: URL(string: "\(baseURL)/init")!)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: ["size": size, "name": name])
        let (data, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw ResumableError.badResponse }
        guard http.statusCode == 200,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let id = json["uploadId"] as? String else {
            throw ResumableError.initFailed(http.statusCode)
        }
        return id
    }

    private func queryOffset(_ id: String) async throws -> Int {
        var req = URLRequest(url: URL(string: "\(baseURL)/offset/\(id)")!)
        req.httpMethod = "GET"
        let (data, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let offset = json["offset"] as? Int else {
            throw ResumableError.badResponse
        }
        return offset
    }

    private enum ChunkResult { case accepted(Int); case resync(Int) }

    private func putChunk(id: String, offset: Int, chunk: Data) async throws -> ChunkResult {
        var req = URLRequest(url: URL(string: "\(baseURL)/chunk/\(id)")!)
        req.httpMethod = "PATCH"
        req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        req.setValue(String(offset), forHTTPHeaderField: "upload-offset")
        let (data, resp) = try await session.upload(for: req, from: chunk)
        guard let http = resp as? HTTPURLResponse else { throw ResumableError.badResponse }
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        if http.statusCode == 200, let newOffset = json?["offset"] as? Int {
            return .accepted(newOffset)
        }
        if http.statusCode == 409, let serverOffset = json?["offset"] as? Int {
            return .resync(serverOffset)
        }
        throw ResumableError.chunkFailed(http.statusCode)
    }

    private func finalize(id: String) async throws -> String {
        var req = URLRequest(url: URL(string: "\(baseURL)/finalize/\(id)")!)
        req.httpMethod = "POST"
        let (data, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw ResumableError.badResponse }
        guard http.statusCode == 200,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let cid = json["IpfsHash"] as? String else {
            throw ResumableError.finalizeFailed(http.statusCode)
        }
        return cid
    }

    // MARK: - Resume-state persistence

    private static func resumeMapURL() -> URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        return dir.appendingPathComponent("oshi_resumable_uploads.json")
    }

    private static func loadResumeMap() -> [String: String] {
        guard let data = try? Data(contentsOf: resumeMapURL()),
              let map = try? JSONSerialization.jsonObject(with: data) as? [String: String] else { return [:] }
        return map
    }

    private func persistResumeMap() {
        guard let data = try? JSONSerialization.data(withJSONObject: resumeMap) else { return }
        try? data.write(to: Self.resumeMapURL(), options: .atomic)
    }
}
