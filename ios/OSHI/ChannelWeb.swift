//
//  ChannelWeb.swift
//  OSHI
//
//  __CHANNEL_WEB_2026_11_26__ Public web preview for channels — the CLIENT half of
//  ServerVPS/v2/PUBLIC_CHANNEL_WEB_SPEC.md (server: branch
//  feat/group-handle-registry-server, 055e72f1a). Telegram-style post links
//  `https://oshi-messenger.com/c/<handle>/<n>` and a one-line embed.
//
//  What lives here:
//    • `ChannelWeb`            — URLs, embed snippet, deep-link parsing (pure).
//    • `ChannelWebText`        — app text → the web's §5 markup, payload filtering (pure).
//    • `ChannelWebContent`     — which part of a channel message is published (pure).
//    • `ChannelWebImage`       — HEIC/JPEG/PNG → oriented, metadata-free JPEG ≤ 2560 px.
//    • `ChannelWebState`       — the durable outbox + postId → n map (sealed FileStorage).
//    • `ChannelWebPublisher`   — runs the outbox: signed, re-signed retries with backoff.
//
//  Never on the E2E path: the normal channel send has already happened when a job
//  is queued, and a failure here only delays the web copy. No App Group file and no
//  cross-process lock is involved (FileStorage writes Documents/, atomic rename,
//  in-process NSLock only for the duration of one write) — nothing is held across
//  an `await` or a suspension (see the 0xdead10cc history in CrossProcessLock.swift).
//  Each running job holds a UIKit background-task assertion that it gives back
//  when the job ends OR when iOS says time is up; an interrupted job simply stays
//  in the queue and runs again (every write is idempotent server-side).
//

import Foundation
import CryptoKit
import ImageIO
import UniformTypeIdentifiers
#if canImport(UIKit)
import UIKit
#endif

// MARK: - URLs, snippet, deep links

enum ChannelWeb {
    static let site = "https://oshi-messenger.com"
    static let maxTextCodePoints = 4096
    static let maxTitleCodePoints = 64
    static let maxDescriptionCodePoints = 255
    static let maxImageBytes = 10_485_760

    /// §3.4 `^[A-Za-z0-9._:-]{1,128}$`.
    nonisolated static func isValidPostId(_ s: String) -> Bool {
        guard (1...128).contains(s.unicodeScalars.count) else { return false }
        return s.unicodeScalars.allSatisfy {
            ("A"..."Z").contains($0) || ("a"..."z").contains($0) || ("0"..."9").contains($0)
                || $0 == "." || $0 == "_" || $0 == ":" || $0 == "-"
        }
    }

    /// The id a channel message is published under: its UUID, upper-case (the
    /// form every platform writes on the wire).
    nonisolated static func postId(_ id: UUID) -> String { id.uuidString.uppercased() }

    static func channelURL(handle: String) -> String { "\(site)/c/\(handle)" }
    static func postURL(handle: String, n: Int) -> String { "\(site)/c/\(handle)/\(n)" }
    static func appLink(handle: String, n: Int?) -> String {
        n.map { "oshi://channel?h=\(handle)&n=\($0)" } ?? "oshi://channel?h=\(handle)"
    }

    /// §8, byte for byte. `dark == nil` → the reader's system theme (no attribute).
    static func embedSnippet(handle: String, n: Int, dark: Bool? = nil) -> String {
        let darkAttr = dark.map { " data-dark=\"\($0 ? "1" : "0")\"" } ?? ""
        return "<script async src=\"https://oshi-messenger.com/js/oshi-widget.js?1\" "
            + "data-oshi-post=\"\(handle)/\(n)\" data-width=\"100%\"\(darkAttr)></script>"
    }

    // Wire paths (callers pass validated handles / post ids only).
    static func pathChannel(_ h: String) -> String { "/v2/c/\(h)" }
    static func pathMedia(_ h: String) -> String { "/v2/c/\(h)/media" }
    static func pathPost(_ h: String, _ postId: String) -> String { "/v2/c/\(h)/posts/\(postId)" }
    static func pathAdmin(_ h: String) -> String { "/v2/c/\(h)/admin" }
    static func pathPublicPost(_ h: String, _ n: Int) -> String { "/v2/c/\(h)/\(n)" }

    /// A link that opens a channel (and maybe one of its posts).
    struct Link: Equatable {
        /// Raw, lower-cased; validated by the join flow (`GroupSettings.normalizedHandle`).
        let handle: String
        let n: Int?
    }

    /// `oshi://channel?h=<h>[&n=<n>]`, and universal links
    /// `https://oshi-messenger.com/c/<h>[/<n>]` (+ www., .org). `/c/<h>/media/…` is
    /// not a channel link (the AASA excludes it too). A malformed `n` is ignored
    /// rather than refusing the whole link: the channel still opens.
    static func parseLink(_ url: URL) -> Link? {
        func number(_ s: String?) -> Int? {
            guard let s, !s.isEmpty, s.count <= 9, s.allSatisfy({ $0.isASCII && $0.isNumber }),
                  let v = Int(s), v > 0 else { return nil }
            return v
        }
        let scheme = url.scheme?.lowercased()
        if scheme == "oshi" {
            guard url.host?.lowercased() == "channel" else { return nil }
            let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
            guard let h = items.first(where: { $0.name == "h" })?.value, !h.isEmpty else { return nil }
            return Link(handle: h.lowercased(), n: number(items.first(where: { $0.name == "n" })?.value))
        }
        guard scheme == "https", let host = url.host?.lowercased(),
              ["oshi-messenger.com", "www.oshi-messenger.com", "oshi-messenger.org", "www.oshi-messenger.org"].contains(host)
        else { return nil }
        let parts = url.pathComponents.filter { $0 != "/" }
        guard parts.count >= 2, parts.count <= 3, parts[0] == "c", !parts[1].isEmpty else { return nil }
        if parts.count == 3, parts[2] == "media" { return nil }
        return Link(handle: parts[1].lowercased(), n: parts.count == 3 ? number(parts[2]) : nil)
    }
}

// MARK: - Text

enum ChannelWebText {

    /// The app's markup (iOS `InlineMarkup`: `**b**`/`*b*` bold, `_i_` italic,
    /// `__u__` underline, `~s~` strike, `` `c` `` code) rewritten in the web's §5
    /// markup (`**bold**`, `_italic_`). Underline / strike / code have no web form:
    /// the text stays, the markers go — what a reader sees is what the app showed,
    /// minus the styles the page cannot draw.
    static func webMarkup(fromApp text: String) -> String {
        let (plain, spans) = InlineMarkup.parse(text)
        let kept = spans.filter { ($0.kind == .bold || $0.kind == .italic) && $0.length > 0 }
        guard !kept.isEmpty else { return plain }
        // Marker insertions per UTF-16 offset: at one offset, closings first (inner
        // before outer), then openings (outer before inner). Equal ranges: bold
        // outside italic, which is the §10 `**_both_**` vector.
        struct Ins { let offset: Int; let closing: Bool; let length: Int; let bold: Bool; let marker: String }
        var ins: [Ins] = []
        for s in kept {
            let m = s.kind == .bold ? "**" : "_"
            ins.append(Ins(offset: s.location, closing: false, length: s.length, bold: s.kind == .bold, marker: m))
            ins.append(Ins(offset: s.location + s.length, closing: true, length: s.length, bold: s.kind == .bold, marker: m))
        }
        ins.sort { a, b in
            if a.offset != b.offset { return a.offset < b.offset }
            if a.closing != b.closing { return a.closing }
            if a.closing {
                if a.length != b.length { return a.length < b.length }
                return !a.bold && b.bold
            }
            if a.length != b.length { return a.length > b.length }
            return a.bold && !b.bold
        }
        let units = Array(plain.utf16)
        var out: [UInt16] = []
        out.reserveCapacity(units.count + ins.count * 2)
        var k = 0
        for i in 0...units.count {
            while k < ins.count, ins[k].offset == i { out.append(contentsOf: Array(ins[k].marker.utf16)); k += 1 }
            if i < units.count { out.append(units[i]) }
        }
        return String(decoding: out, as: UTF16.self)
    }

    /// The text of a channel message as it goes to the web, or nil when there is
    /// nothing to publish: control payloads (polls, locations, reactions, call
    /// summaries…) are not posts; a reply / forward envelope publishes its body.
    static func publishableText(_ raw: String?) -> String? {
        guard var t = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty else { return nil }
        if let p = OSHIControlPayload.matchedPrefix(t) {
            if p == OSHIControlPayload.replyPrefix, let r = MessageWithReply.fromMessageString(t) {
                t = r.content
            } else if p == OSHIControlPayload.forwardPrefix, let f = ForwardedMessage.fromMessageString(t) {
                t = f.content
            } else {
                return nil
            }
            t = t.trimmingCharacters(in: .whitespacesAndNewlines)
            if t.isEmpty || OSHIControlPayload.matchedPrefix(t) != nil { return nil }
        }
        if t.hasPrefix("{"), t.contains("\"type\":\"media\"") { return nil }
        let web = webMarkup(fromApp: t).trimmingCharacters(in: .whitespacesAndNewlines)
        return web.isEmpty ? nil : truncate(web, maxCodePoints: ChannelWeb.maxTextCodePoints)
    }

    /// At most `max` Unicode scalars (the server counts code points), never
    /// splitting a character; "…" marks a cut.
    static func truncate(_ s: String, maxCodePoints max: Int) -> String {
        guard s.unicodeScalars.count > max else { return s }
        var out = ""
        var n = 0
        for ch in s {
            let c = ch.unicodeScalars.count
            if n + c > max - 1 { break }
            out.append(ch)
            n += c
        }
        return out + "…"
    }

    /// One line, ≤ 64 code points (`title`).
    static func title(_ s: String) -> String {
        let one = s.components(separatedBy: .newlines).joined(separator: " ")
            .trimmingCharacters(in: .whitespaces)
        return truncate(one, maxCodePoints: ChannelWeb.maxTitleCodePoints)
    }
}

// MARK: - What a channel message publishes

struct ChannelWebPostSource: Equatable {
    var text: String
    /// The message's own photo / GIF (one per OSHI message).
    var hasImage: Bool
    var createdAtMs: Int64
}

enum ChannelWebContent {
    nonisolated static func ms(_ d: Date) -> Int64 { Int64((d.timeIntervalSince1970 * 1000).rounded(.down)) }

    /// Photos and GIFs only (§9.2): video, voice, files, contact cards, polls and
    /// stickers are not published — the caption is, if there is one.
    static func isPublishableImage(_ m: GroupMessage) -> Bool {
        guard m.mediaType == .photo else { return false }
        return !(m.mediaFileName?.lowercased().contains("sticker") ?? false)
    }

    /// nil = nothing to publish (or not a post at all).
    static func source(for m: GroupMessage) -> ChannelWebPostSource? {
        guard !m.isSystemMessage, m.senderPublicKey != "SYSTEM", m.commentOn == nil,
              !m.senderPublicKey.hasPrefix("bot:") else { return nil }
        let raw: String?
        if let e = m.editedContent, !e.isEmpty { raw = e }
        else if let p = m.plaintextContent, !p.isEmpty { raw = p }
        else if m.mediaType == nil { raw = String(data: m.encryptedContent, encoding: .utf8) }
        else { raw = nil }
        let text = ChannelWebText.publishableText(raw) ?? ""
        let image = isPublishableImage(m)
        guard !text.isEmpty || image else { return nil }
        return ChannelWebPostSource(text: text, hasImage: image, createdAtMs: ms(m.timestamp))
    }

    /// §3.4 body. Keys sorted so the bytes (and the signed hash) are stable.
    static func postBody(text: String, media: [String], createdAtMs: Int64) -> Data {
        let o: [String: Any] = ["text": text, "media": media, "createdAt": createdAtMs]
        return (try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys, .withoutEscapingSlashes])) ?? Data()
    }

    /// §3.1 body. `avatar`: absent key = unchanged, `.some(nil)` = remove.
    static func profileBody(title: String?, description: String?, searchIndexing: Bool,
                            avatar: String?? = nil) -> Data {
        var o: [String: Any] = ["kind": "channel", "searchIndexing": searchIndexing]
        o["title"] = title.map { ChannelWebText.title($0) }.flatMap { $0.isEmpty ? nil : $0 } ?? NSNull()
        if let d = description?.trimmingCharacters(in: .whitespacesAndNewlines), !d.isEmpty {
            o["description"] = ChannelWebText.truncate(ChannelWebText.webMarkup(fromApp: d),
                                                      maxCodePoints: ChannelWeb.maxDescriptionCodePoints)
        } else {
            o["description"] = NSNull()
        }
        if let avatar { o["avatarMediaId"] = avatar ?? NSNull() }
        return (try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys, .withoutEscapingSlashes])) ?? Data()
    }
}

// MARK: - Image preparation

enum ChannelWebImage {
    /// GIF: uploaded as is (animated allowed; the server strips comments and
    /// application extensions). Anything else ImageIO can read (HEIC, JPEG, PNG,
    /// WebP…) → orientation applied, long side ≤ `maxPixel`, re-encoded as JPEG
    /// with NO metadata (no EXIF, no GPS). nil = cannot be published.
    nonisolated static func prepare(_ data: Data, maxPixel: Int = 2560, quality: Double = 0.82) -> Data? {
        if MediaManager.isGIFData(data) { return data.count <= ChannelWeb.maxImageBytes ? data : nil }
        guard let src = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
        let out = NSMutableData()
        guard let dst = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dst, cg, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(dst) else { return nil }
        let bytes = out as Data
        return bytes.count <= ChannelWeb.maxImageBytes ? bytes : nil
    }
}

// MARK: - Durable state

struct ChannelWebState: Codable, Equatable {
    struct PostRecord: Codable, Equatable {
        var handle: String
        var groupId: UUID
        var n: Int?
        /// Media ids attached to the published version (reused by edits).
        var mediaIds: [String] = []
        /// true when THIS device published it (an author's record), false when it
        /// was learned from `GET …/admin` or a public read.
        var mine: Bool = false
    }

    struct ChannelRecord: Codable, Equatable {
        var groupId: UUID
        /// Signature of the last profile the server accepted (title, description,
        /// avatar, indexing) — a change re-PUTs it.
        var profileSignature: String?
        var avatarHash: String?
        var lastAdminSync: Date?
    }

    struct Job: Codable, Equatable, Identifiable {
        enum Kind: String, Codable { case profile, disable, publish, delete, sync }
        var id = UUID()
        var kind: Kind
        var groupId: UUID
        var handle: String
        var postId: String?
        var attempts = 0
        var notBefore = Date.distantPast
        var lastError: String?
    }

    var posts: [String: PostRecord] = [:]       // key: postId
    var channels: [String: ChannelRecord] = [:] // key: handle
    var jobs: [Job] = []

    func n(postId: String, handle: String) -> Int? {
        guard let r = posts[postId], r.handle == handle else { return nil }
        return r.n
    }

    func postId(handle: String, n: Int) -> String? {
        posts.first { $0.value.handle == handle && $0.value.n == n }?.key
    }

    /// Queue with coalescing: one pending publish per post (it reads the latest
    /// content when it runs, so an edit queued behind it is already covered); a
    /// delete cancels the publish; a disable cancels every write for the handle;
    /// a profile replaces an older profile and cancels a disable.
    mutating func enqueue(_ job: Job) {
        switch job.kind {
        case .publish:
            if jobs.contains(where: { $0.kind == .publish && $0.postId == job.postId && $0.handle == job.handle }) { return }
            jobs.removeAll { $0.kind == .delete && $0.postId == job.postId && $0.handle == job.handle }
        case .delete:
            jobs.removeAll { ($0.kind == .publish || $0.kind == .delete) && $0.postId == job.postId && $0.handle == job.handle }
        case .disable:
            jobs.removeAll { $0.handle == job.handle }
        case .profile:
            // NOT the disable: a purge that was asked for still happens first.
            jobs.removeAll { $0.handle == job.handle && $0.kind == .profile }
        case .sync:
            if jobs.contains(where: { $0.kind == .sync && $0.handle == job.handle }) { return }
        }
        jobs.append(job)
    }

    /// The next job allowed to run at `now` (FIFO among due jobs).
    func nextDue(_ now: Date) -> Job? { jobs.first { $0.notBefore <= now } }
}

// MARK: - Outcome of one HTTP answer (pure)

enum ChannelWebOutcome: Equatable {
    case done
    /// Transient: back off, re-sign, try again.
    case retry
    /// `400 unknown-media`: forget the media ids, upload again.
    case reuploadMedia
    /// A request this build should not have sent, or a state the server will not
    /// leave by waiting (not a controller, unknown handle, preview off…).
    case drop

    static func errorCode(_ body: Data) -> String? {
        (try? JSONSerialization.jsonObject(with: body) as? [String: Any])?["error"] as? String
    }

    /// §6 table. `status < 0` = no HTTP answer (network).
    static func classify(status: Int, error: String?, kind: ChannelWebState.Job.Kind) -> ChannelWebOutcome {
        switch status {
        case 200...299: return .done
        case 404 where kind == .delete || kind == .disable: return .done   // already gone
        case 400 where error == "unknown-media": return .reuploadMedia
        case 401, 408, 425, 429, 500...599: return .retry   // 507 storage-full included
        case ..<100: return .retry
        default: return .drop
        }
    }

    /// 5 s, 10 s, 20 s … capped at one hour, ±20 % jitter.
    static func backoff(attempt: Int, jitter: Double) -> TimeInterval {
        let base = min(3600, 5 * pow(2, Double(max(0, attempt - 1))))
        return base * (0.8 + 0.4 * min(1, max(0, jitter)))
    }

    /// Publishes, profiles and syncs give up after this many tries; a delete or a
    /// disable never does (it is what removes something from the public web).
    static let maxAttempts = 25
}

// MARK: - Seams (live implementations at the bottom)

protocol ChannelWebTransport {
    func send(method: String, path: String, query: String?, body: Data?, contentType: String?,
              signed: Bool) async throws -> (Int, Data)
}

@MainActor
protocol ChannelWebContentSource: AnyObject {
    func post(groupId: UUID, postId: String) -> ChannelWebPostSource?
    /// Upload-ready bytes of the post's image (`ChannelWebImage.prepare`).
    func imageBytes(groupId: UUID, postId: String) -> Data?
    func profile(groupId: UUID) -> ChannelWebProfile?
}

struct ChannelWebProfile: Equatable {
    var title: String
    var description: String?
    var searchIndexing: Bool
    /// Upload-ready avatar, or nil when the channel has no picture.
    var avatar: Data?

    var avatarHash: String? {
        avatar.map { SHA256.hash(data: $0).prefix(12).map { String(format: "%02x", $0) }.joined() }
    }
    var signature: String {
        let s = "\(title)\u{1}\(description ?? "")\u{1}\(searchIndexing)\u{1}\(avatarHash ?? "-")"
        return SHA256.hash(data: Data(s.utf8)).prefix(12).map { String(format: "%02x", $0) }.joined()
    }
}

struct ChannelWebPersistence {
    var load: () -> ChannelWebState?
    var save: (ChannelWebState) -> Void

    static func memory() -> ChannelWebPersistence {
        final class Box { var s: ChannelWebState? }
        let b = Box()
        return ChannelWebPersistence(load: { b.s }, save: { b.s = $0 })
    }

    /// Sealed (AtRestCipher) JSON in Documents — the app's own sandbox, NOT the
    /// App Group container, so no cross-process lock exists to be held.
    static let fileStorage = ChannelWebPersistence(
        load: { FileStorage.shared.load(forKey: "channel_web_state_v1") },
        save: { FileStorage.shared.save($0, forKey: "channel_web_state_v1") })
}

// MARK: - Publisher

@MainActor
final class ChannelWebPublisher {
    static let didChange = Notification.Name("ChannelWebDidChange")

    static let shared = ChannelWebPublisher(transport: V2ChannelWebTransport(),
                                            source: LiveChannelWebSource(),
                                            persistence: .fileStorage,
                                            autoRun: !MessageGroupManager.isRunningUnitTests)

    private let transport: ChannelWebTransport
    private let source: ChannelWebContentSource
    private let persistence: ChannelWebPersistence
    private let autoRun: Bool
    var now: () -> Date = Date.init
    var jitter: () -> Double = { Double.random(in: 0...1) }

    private(set) var state: ChannelWebState
    private var draining = false
    private var wakeTask: Task<Void, Never>?
    private var observers: [NSObjectProtocol] = []

    init(transport: ChannelWebTransport, source: ChannelWebContentSource,
         persistence: ChannelWebPersistence, autoRun: Bool) {
        self.transport = transport
        self.source = source
        self.persistence = persistence
        self.autoRun = autoRun
        self.state = persistence.load() ?? ChannelWebState()
    }

    // MARK: Lifecycle

    /// Launch (deferred) — resumes the persisted outbox, reconciles every channel
    /// this device administers, and listens for foreground / group changes.
    func start() {
        guard autoRun, observers.isEmpty else { return }
        #if canImport(UIKit)
        observers.append(NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
                Task { @MainActor in ChannelWebPublisher.shared.kick() }
            })
        #endif
        observers.append(NotificationCenter.default.addObserver(
            forName: NSNotification.Name("GroupUpdated"), object: nil, queue: .main) { n in
                let gid = (n.userInfo?["groupId"] as? String).flatMap(UUID.init(uuidString:))
                Task { @MainActor in ChannelWebPublisher.shared.reconcile(only: gid) }
            })
        reconcile(only: nil)
        kick()
    }

    private func persist() {
        persistence.save(state)
        NotificationCenter.default.post(name: Self.didChange, object: nil)
    }

    // MARK: Queries

    func n(for postId: UUID, handle: String) -> Int? { state.n(postId: ChannelWeb.postId(postId), handle: handle) }
    func isPublished(_ postId: UUID) -> Bool { state.posts[ChannelWeb.postId(postId)] != nil }
    func pendingJobs(handle: String) -> Int { state.jobs.filter { $0.handle == handle }.count }

    /// Queue a job directly (tests; `record` creates the author's post record).
    func testEnqueue(_ job: ChannelWebState.Job, record: Bool) {
        if record, let pid = job.postId, state.posts[pid] == nil {
            state.posts[pid] = .init(handle: job.handle, groupId: job.groupId, mine: true)
        }
        state.enqueue(job)
        persist()
    }

    // MARK: Hooks from the channel send path

    /// A new channel post by THIS device. Only the author publishes (§9.2), only
    /// while the preview is on, only posts written after it was turned on — which
    /// is guaranteed by construction: this runs at send time.
    func didSend(_ m: GroupMessage, in group: MessageGroup, me: String?) {
        guard let s = group.settings, s.webPreviewActive, let h = s.handle,
              let me, MessageManager.canonicalIdentity(m.senderPublicKey) == MessageManager.canonicalIdentity(me),
              GroupSettingsPolicy.isAdmin(me, in: group),
              ChannelWebContent.source(for: m) != nil else { return }
        let pid = ChannelWeb.postId(m.id)
        state.posts[pid] = ChannelWebState.PostRecord(handle: h, groupId: group.id, n: nil, mediaIds: [], mine: true)
        state.enqueue(.init(kind: .publish, groupId: group.id, handle: h, postId: pid))
        persist()
        kick()
    }

    /// An edit-for-everyone of a post that is on the web → re-publish (same `n`).
    func didEdit(groupId: UUID, messageId: UUID, group: MessageGroup?) {
        let pid = ChannelWeb.postId(messageId)
        guard let r = state.posts[pid], let s = group?.settings, s.webPreviewActive, s.handle == r.handle else { return }
        state.enqueue(.init(kind: .publish, groupId: groupId, handle: r.handle, postId: pid))
        persist()
        kick()
    }

    /// A delete-for-everyone of a post that is (or is being put) on the web.
    func didDelete(groupId: UUID, messageId: UUID) {
        let pid = ChannelWeb.postId(messageId)
        guard let r = state.posts[pid] else { return }
        state.enqueue(.init(kind: .delete, groupId: groupId, handle: r.handle, postId: pid))
        persist()
        kick()
    }

    // MARK: Admin actions (UI)

    enum EnableResult: Equatable { case ok, notRegistered, notController, offline, failed }

    /// Toggle ON. Awaited by the UI (the toggle only flips on success): PUT the
    /// profile now; the avatar and later profile changes ride the queue.
    func enable(group: MessageGroup, searchIndexing: Bool) async -> EnableResult {
        guard let h = group.settings?.handle, var p = source.profile(groupId: group.id) else { return .failed }
        p.searchIndexing = searchIndexing
        // Off → on before the purge went out: purge first, or the old posts would
        // stay public under the new switch-on.
        if let d = state.jobs.first(where: { $0.kind == .disable && $0.handle == h }) {
            let o = outcome(await call("DELETE", ChannelWeb.pathChannel(h), body: nil, contentType: nil), .disable)
            guard o.0 == .done else { return .offline }
            state.jobs.removeAll { $0.id == d.id }
            state.posts = state.posts.filter { $0.value.handle != h }
            state.channels[h] = nil
            persist()
        }
        var r = await putProfile(handle: h, profile: p, avatar: nil)
        if r.status == 403 || r.status == 404 {
            // The registry may not list this device as a controller yet (new admin,
            // expired handle): re-PUT the handle once, then try again.
            if let s = group.settings, await GroupHandleRegistry.register(h, group: group, settings: s) == .ok {
                r = await putProfile(handle: h, profile: p, avatar: nil)
            }
        }
        switch r.status {
        case 200...299:
            var c = state.channels[h] ?? .init(groupId: group.id)
            c.groupId = group.id
            c.profileSignature = nil   // the avatar step still has to run
            state.channels[h] = c
            state.enqueue(.init(kind: .profile, groupId: group.id, handle: h))
            state.enqueue(.init(kind: .sync, groupId: group.id, handle: h))
            persist()
            kick()
            return .ok
        case 404: return .notRegistered
        case 403: return .notController
        case ..<100: return .offline
        default: return .failed
        }
    }

    /// Toggle OFF (confirmed by the UI): DELETE purges posts + images. Durable —
    /// queued first, so an offline device purges as soon as it can.
    func disable(groupId: UUID, handle: String) {
        state.enqueue(.init(kind: .disable, groupId: groupId, handle: handle))
        persist()
        kick()
    }

    // MARK: Reconcile (launch, group changes)

    /// For every channel with the preview on where we are admin: profile changed →
    /// re-PUT; never synced on this device → learn the postId → n map. For a
    /// channel where it went off (another admin): drop our queued writes.
    func reconcile(only gid: UUID?) {
        let mgr = MessageGroupManager.shared
        let me = mgr.currentUserPublicKey
        var changed = false
        for g in mgr.groups where gid == nil || g.id == gid {
            let s = g.settings ?? GroupSettings()
            let admin = GroupSettingsPolicy.isAdmin(me, in: g)
            if s.webPreviewActive, admin, let h = s.handle {
                let rec = state.channels[h]
                if let p = source.profile(groupId: g.id), rec?.profileSignature != p.signature,
                   !state.jobs.contains(where: { $0.kind == .profile && $0.handle == h }) {
                    state.enqueue(.init(kind: .profile, groupId: g.id, handle: h))
                    changed = true
                }
                if rec?.lastAdminSync == nil || now().timeIntervalSince(rec?.lastAdminSync ?? .distantPast) > 86_400 {
                    state.enqueue(.init(kind: .sync, groupId: g.id, handle: h))
                    changed = true
                }
            } else {
                let before = state.jobs.count
                state.jobs.removeAll { $0.groupId == g.id && ($0.kind == .publish || $0.kind == .profile || $0.kind == .sync) }
                changed = changed || before != state.jobs.count
            }
        }
        if changed { persist(); kick() }
    }

    // MARK: Runner

    /// Run due jobs now (no-op while already running).
    func kick() {
        guard autoRun else { return }
        Task { await drain() }
    }

    /// Runs every job that is due, one at a time, then sleeps until the next one.
    /// Tests call this directly (autoRun off).
    func drain() async {
        guard !draining else { return }
        draining = true
        defer { draining = false }
        while let job = state.nextDue(now()) {
            let bg = BackgroundAssertion.begin("oshi.channelweb")
            let outcome = await run(job)
            bg.end()
            apply(outcome, to: job)
        }
        scheduleWake()
    }

    private func scheduleWake() {
        wakeTask?.cancel()
        wakeTask = nil
        guard autoRun, let next = state.jobs.map(\.notBefore).min() else { return }
        let delay = max(1, next.timeIntervalSince(now()))
        wakeTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(min(delay, 3600) * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await self?.drain()
        }
    }

    private func apply(_ outcome: (ChannelWebOutcome, String?), to job: ChannelWebState.Job) {
        guard let i = state.jobs.firstIndex(where: { $0.id == job.id }) else { persist(); return }
        switch outcome.0 {
        case .done, .drop:
            state.jobs.remove(at: i)
        case .reuploadMedia:
            if let pid = job.postId { state.posts[pid]?.mediaIds = [] }
            state.jobs[i].attempts += 1
            state.jobs[i].notBefore = now()
            if state.jobs[i].attempts > ChannelWebOutcome.maxAttempts { state.jobs.remove(at: i) }
        case .retry:
            state.jobs[i].attempts += 1
            state.jobs[i].lastError = outcome.1
            let a = state.jobs[i].attempts
            let forever = job.kind == .delete || job.kind == .disable
            if !forever && a > ChannelWebOutcome.maxAttempts {
                state.jobs.remove(at: i)
            } else {
                state.jobs[i].notBefore = now().addingTimeInterval(ChannelWebOutcome.backoff(attempt: a, jitter: jitter()))
            }
        }
        persist()
    }

    private func call(_ method: String, _ path: String, query: String? = nil, body: Data?,
                      contentType: String?, signed: Bool = true) async -> (status: Int, data: Data) {
        do {
            return try await transport.send(method: method, path: path, query: query, body: body,
                                            contentType: contentType, signed: signed)
        } catch {
            return (-1, Data())
        }
    }

    private func outcome(_ r: (status: Int, data: Data), _ kind: ChannelWebState.Job.Kind) -> (ChannelWebOutcome, String?) {
        let code = ChannelWebOutcome.errorCode(r.data)
        return (ChannelWebOutcome.classify(status: r.status, error: code, kind: kind), code ?? "\(r.status)")
    }

    private func run(_ job: ChannelWebState.Job) async -> (ChannelWebOutcome, String?) {
        let h = job.handle
        switch job.kind {
        case .publish:
            guard let pid = job.postId, ChannelWeb.isValidPostId(pid) else { return (.drop, "bad-id") }
            guard let post = source.post(groupId: job.groupId, postId: pid) else { return (.drop, "gone") }
            var rec = state.posts[pid] ?? .init(handle: h, groupId: job.groupId, mine: true)
            if rec.handle != h { rec = .init(handle: h, groupId: job.groupId, mine: true) }
            var media = post.hasImage ? rec.mediaIds : []
            if post.hasImage && media.isEmpty {
                if let bytes = source.imageBytes(groupId: job.groupId, postId: pid) {
                    let up = await call("POST", ChannelWeb.pathMedia(h), body: bytes, contentType: "application/octet-stream")
                    if (200...299).contains(up.status),
                       let id = (try? JSONSerialization.jsonObject(with: up.data) as? [String: Any])?["mediaId"] as? String {
                        media = [id]
                    } else {
                        let o = outcome(up, .publish)
                        // An image the server will never take (415, 413, unparseable):
                        // publish the text alone rather than nothing.
                        if o.0 == .drop, !post.text.isEmpty { media = [] } else { return o }
                    }
                } else if post.text.isEmpty {
                    return (.drop, "no-image")
                }
            }
            let body = ChannelWebContent.postBody(text: post.text, media: media, createdAtMs: post.createdAtMs)
            let r = await call("PUT", ChannelWeb.pathPost(h, pid), body: body, contentType: "application/json")
            if (200...299).contains(r.status),
               let n = (try? JSONSerialization.jsonObject(with: r.data) as? [String: Any])?["n"] as? Int {
                rec.n = n
                rec.mediaIds = media
                state.posts[pid] = rec
                return (.done, nil)
            }
            if (200...299).contains(r.status) { return (.retry, "no-n") }
            return outcome(r, .publish)

        case .delete:
            guard let pid = job.postId, ChannelWeb.isValidPostId(pid) else { return (.drop, "bad-id") }
            let r = await call("DELETE", ChannelWeb.pathPost(h, pid), body: nil, contentType: nil)
            let o = outcome(r, .delete)
            if o.0 == .done || o.0 == .drop { state.posts[pid] = nil }
            return o

        case .disable:
            let r = await call("DELETE", ChannelWeb.pathChannel(h), body: nil, contentType: nil)
            let o = outcome(r, .disable)
            if o.0 == .done {
                state.posts = state.posts.filter { $0.value.handle != h }
                state.channels[h] = nil
            }
            return o

        case .profile:
            guard let p = source.profile(groupId: job.groupId) else { return (.drop, "gone") }
            var c = state.channels[h] ?? .init(groupId: job.groupId)
            var avatar: String?? = nil
            if let bytes = p.avatar, p.avatarHash != c.avatarHash {
                // PUT (fields) → POST media → PUT avatarMediaId (§3.1 avatar flow).
                let r1 = await putProfile(handle: h, profile: p, avatar: nil)
                guard (200...299).contains(r1.status) else { return outcome(r1, .profile) }
                let up = await call("POST", ChannelWeb.pathMedia(h), body: bytes, contentType: "application/octet-stream")
                if (200...299).contains(up.status),
                   let id = (try? JSONSerialization.jsonObject(with: up.data) as? [String: Any])?["mediaId"] as? String {
                    avatar = .some(id)
                } else if outcome(up, .profile).0 != .drop {
                    return outcome(up, .profile)
                }
            } else if p.avatar == nil, c.avatarHash != nil {
                avatar = .some(nil)
            }
            let r = await putProfile(handle: h, profile: p, avatar: avatar)
            guard (200...299).contains(r.status) else { return outcome(r, .profile) }
            c.profileSignature = p.signature
            if case .some(let v) = avatar { c.avatarHash = v == nil ? nil : p.avatarHash }
            state.channels[h] = c
            return (.done, nil)

        case .sync:
            let r = await call("GET", ChannelWeb.pathAdmin(h), body: nil, contentType: nil)
            guard (200...299).contains(r.status) else { return outcome(r, .sync) }
            mergeAdmin(r.data, handle: h, groupId: job.groupId)
            return (.done, nil)
        }
    }

    private func putProfile(handle h: String, profile p: ChannelWebProfile, avatar: String??) async -> (status: Int, data: Data) {
        let body = ChannelWebContent.profileBody(title: p.title, description: p.description,
                                                 searchIndexing: p.searchIndexing, avatar: avatar)
        return await call("PUT", ChannelWeb.pathChannel(h), body: body, contentType: "application/json")
    }

    /// §3.6: adopt the server's postId → n (a new device, a co-admin).
    func mergeAdmin(_ data: Data, handle h: String, groupId: UUID) {
        guard let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
        var c = state.channels[h] ?? .init(groupId: groupId)
        c.lastAdminSync = now()
        state.channels[h] = c
        for p in (o["posts"] as? [[String: Any]]) ?? [] {
            guard let pid = p["postId"] as? String, ChannelWeb.isValidPostId(pid), let n = p["n"] as? Int else { continue }
            var r = state.posts[pid] ?? .init(handle: h, groupId: groupId, n: n, mine: false)
            r.handle = h
            r.n = n
            state.posts[pid] = r
        }
    }

    // MARK: Resolving n for the share menu

    /// The post number of a channel message, for "Copy link" / "Share" / "Embed".
    /// Known locally (author / admin map) → immediately. Admin without it → one
    /// signed `GET …/admin`. Reader → the PUBLIC feed, matched on the post's send
    /// time (the feed never exposes postId by design, §4). Only runs when the
    /// person taps a share action, so a reader's device never contacts the web
    /// server just for reading the channel.
    func resolveN(message m: GroupMessage, handle h: String, isAdmin: Bool) async -> Int? {
        if let n = n(for: m.id, handle: h) { return n }
        if isAdmin {
            let r = await call("GET", ChannelWeb.pathAdmin(h), body: nil, contentType: nil)
            if (200...299).contains(r.status) {
                mergeAdmin(r.data, handle: h, groupId: m.groupId)
                persist()
                if let n = n(for: m.id, handle: h) { return n }
            }
        }
        let target = ChannelWebContent.ms(m.timestamp)
        let text = ChannelWebContent.source(for: m)?.text
        var before: Int? = nil
        for _ in 0..<6 {
            let q = "limit=50" + (before.map { "&before=\($0)" } ?? "")
            let r = await call("GET", ChannelWeb.pathChannel(h), query: q, body: nil, contentType: nil, signed: false)
            guard (200...299).contains(r.status),
                  let o = try? JSONSerialization.jsonObject(with: r.data) as? [String: Any] else { return nil }
            let posts = (o["posts"] as? [[String: Any]]) ?? []
            if let n = Self.matchFeed(posts, createdAtMs: target, text: text) {
                var rec = state.posts[ChannelWeb.postId(m.id)] ?? .init(handle: h, groupId: m.groupId, mine: false)
                rec.handle = h
                rec.n = n
                state.posts[ChannelWeb.postId(m.id)] = rec
                persist()
                return n
            }
            let oldest = posts.compactMap { ($0["createdAt"] as? NSNumber)?.int64Value }.min()
            guard let next = o["nextBefore"] as? Int, let oldest, oldest > target - 2000 else { return nil }
            before = next
        }
        return nil
    }

    /// Exact send time (±1 ms: platforms round differently), else same text within
    /// 2 s. Pure, unit-tested.
    nonisolated static func matchFeed(_ posts: [[String: Any]], createdAtMs: Int64, text: String?) -> Int? {
        func ts(_ p: [String: Any]) -> Int64? { (p["createdAt"] as? NSNumber)?.int64Value }
        if let p = posts.first(where: { ts($0).map { abs($0 - createdAtMs) <= 1 } ?? false }), let n = p["n"] as? Int {
            return n
        }
        guard let text, !text.isEmpty else { return nil }
        return posts.first(where: {
            (ts($0).map { abs($0 - createdAtMs) <= 2000 } ?? false) && ($0["text"] as? String) == text
        })?["n"] as? Int
    }

    /// Deep link → local message id: from the map, else the public post's send time.
    func localMessageId(handle h: String, n: Int, groupId: UUID) async -> UUID? {
        if let pid = state.postId(handle: h, n: n), let id = UUID(uuidString: pid) { return id }
        let r = await call("GET", ChannelWeb.pathPublicPost(h, n), body: nil, contentType: nil, signed: false)
        guard (200...299).contains(r.status),
              let o = try? JSONSerialization.jsonObject(with: r.data) as? [String: Any],
              let post = o["post"] as? [String: Any],
              let created = (post["createdAt"] as? NSNumber)?.int64Value else { return nil }
        let msgs = MessageGroupManager.shared.groupMessages[groupId] ?? []
        return msgs.first { abs(ChannelWebContent.ms($0.timestamp) - created) <= 1 }?.id
    }
}

// MARK: - Background assertion

/// One UIKit background-task assertion per running job. Ended exactly once:
/// by the job, or by the expiration handler (the job's work is then simply
/// redone later — nothing was committed half-way, nothing is locked).
final class BackgroundAssertion {
    #if canImport(UIKit) && !os(watchOS)
    private var id: UIBackgroundTaskIdentifier = .invalid
    #endif

    static func begin(_ name: String) -> BackgroundAssertion {
        let a = BackgroundAssertion()
        #if canImport(UIKit) && !os(watchOS)
        a.id = UIApplication.shared.beginBackgroundTask(withName: name) { [weak a] in a?.end() }
        #endif
        return a
    }

    func end() {
        #if canImport(UIKit) && !os(watchOS)
        guard id != .invalid else { return }
        UIApplication.shared.endBackgroundTask(id)
        id = .invalid
        #endif
    }
}

// MARK: - Live seams

struct V2ChannelWebTransport: ChannelWebTransport {
    var client: V2Client = V2Client()
    func send(method: String, path: String, query: String?, body: Data?, contentType: String?,
              signed: Bool) async throws -> (Int, Data) {
        try await client.channelWebRequest(method: method, path: path, query: query, body: body,
                                           contentType: contentType, signed: signed,
                                           timeout: method == "POST" ? 90 : 20)
    }
}

@MainActor
final class LiveChannelWebSource: ChannelWebContentSource {
    private func message(_ groupId: UUID, _ postId: String) -> GroupMessage? {
        guard let id = UUID(uuidString: postId) else { return nil }
        return MessageGroupManager.shared.groupMessages[groupId]?.first { $0.id == id }
    }

    func post(groupId: UUID, postId: String) -> ChannelWebPostSource? {
        message(groupId, postId).flatMap { ChannelWebContent.source(for: $0) }
    }

    func imageBytes(groupId: UUID, postId: String) -> Data? {
        guard let m = message(groupId, postId), ChannelWebContent.isPublishableImage(m) else { return nil }
        let raw = MessageGroupManager.readGroupMediaFile(for: m.id.uuidString, type: .photo)
            ?? m.mediaData
            ?? Self.inlineMedia(m.encryptedContent)
        return raw.flatMap { ChannelWebImage.prepare($0) }
    }

    /// Older rows still carry the media inline as `{"type":"media","content":"<b64>"}`.
    private static func inlineMedia(_ d: Data) -> Data? {
        guard d.first == UInt8(ascii: "{"),
              let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              o["type"] as? String == "media", let b64 = o["content"] as? String else { return nil }
        return Data(base64Encoded: b64)
    }

    func profile(groupId: UUID) -> ChannelWebProfile? {
        guard let g = MessageGroupManager.shared.groups.first(where: { $0.id == groupId }) else { return nil }
        let pic = g.groupPictureData.flatMap { d in
            ChannelWebImage.prepare(d, maxPixel: 512) ?? Data(base64Encoded: d).flatMap { ChannelWebImage.prepare($0, maxPixel: 512) }
        }
        return ChannelWebProfile(title: g.name, description: g.groupDescription,
                                 searchIndexing: g.settings?.webIndexing ?? false, avatar: pic)
    }
}
