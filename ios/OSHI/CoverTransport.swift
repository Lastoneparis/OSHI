import Foundation
import Security

/// Versioned, lossless wrapper around signed V2 packets. The packet's existing
/// end-to-end encryption and signatures remain unchanged. This is not a
/// steganographic or anonymity transport: fingerprintable image, zero-width
/// text and cookie carriers are intentionally unsupported.
nonisolated enum CoverTransport {
    static let maxBytes = 4 * 1024 * 1024
    static let maxBase64Bytes = ((maxBytes + 2) / 3) * 4
    /// Shared with Android and the relay. These are presentation aliases only;
    /// every path terminates at the same authenticated cover handler.
    static let coverPaths = ["/v2/cover", "/v2/collect", "/v2/metrics"]
    /// Deliberately coarse, shared client/server buckets.  A 1 KiB increment
    /// leaks an almost exact plaintext-size range to any observer that can see
    /// the carrier size.  These buckets trade a bounded amount of bandwidth for
    /// materially less useful size correlation and are mirrored by Android and
    /// the relay.
    static let paddingBuckets = [4 * 1024, 16 * 1024, 64 * 1024, 256 * 1024, 1024 * 1024, maxBytes]
    enum Failure: Error { case invalidCarrier, tooLarge, badRandom, transport(Int), legacyRelayRefused }

    /// __COVER_FAIL_CLOSED_2026_11_18__ With "Disguise OSHI traffic" ON, nothing
    /// addressed to ONE person (message, receipt, profile update, ratchet reset…)
    /// may use the undisguised legacy relay (`POST /api/queue`). Group fan-out and
    /// calls are not disguised in any mode (docs/stego/PRIVACY_DISGUISE_SCOPE.md).
    static var legacyOneToOneRelayAllowed: Bool { mode == nil }
    struct Encoded { let body: Data; let type: String }
    private enum Profile: CaseIterable { case event, batch }
    static var mode: String? {
        let shared = UserDefaults(suiteName: "group.Moriceau-lab.Genesis")
        let keys = ["covertChannelEnabled"]
        let isExtension = Bundle.main.bundleURL.pathExtension == "appex"
        let prefs = isExtension ? (shared ?? .standard) : .standard
        if !isExtension {
            // __COVER_MODE_NO_RECURSION_2026_11_18__ Mirror only a CHANGE. Every write
            // posts `UserDefaults.didChangeNotification`, whose observer in
            // CovertChannelReceiver calls `startPolling()`, which reads `mode` again —
            // with cover ON and the poll timer not yet armed that was an unbounded
            // recursion (stack overflow at launch, seen in the simulator 2026-11-18
            // with a working App Group suite).
            for key in keys {
                let value = prefs.object(forKey: key) as? NSObject
                let mirrored = shared?.object(forKey: key) as? NSObject
                if let value {
                    if mirrored != value { shared?.set(value, forKey: key) }
                } else if mirrored != nil {
                    shared?.removeObject(forKey: key)
                }
            }
        }
        guard prefs.bool(forKey: "covertChannelEnabled") else { return nil }
        return "event"
    }
    /// 1-in-N chance that a frame whose minimal bucket is small is padded one
    /// bucket further (never above `bucketSpreadMax`). Every relay copy accepts
    /// any shared bucket that holds the record, so no server change is needed.
    /// Mirrors Android `CoverCodec.BUCKET_SPREAD_ONE_IN` (2026-11-13).
    static let bucketSpreadOneIn = 4
    static let bucketSpreadMax = 64 * 1024
    static func frame(_ object: [String: Any], spread: Bool = false) throws -> Data {
        let json = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes])
        guard json.count <= maxBytes - 4 else { throw Failure.tooLarge }
        guard var bucket = paddingBuckets.first(where: { json.count + 4 <= $0 }) else { throw Failure.tooLarge }
        if spread, let next = paddingBuckets.first(where: { $0 > bucket }), next <= bucketSpreadMax,
           Int.random(in: 0..<bucketSpreadOneIn) == 0 {
            bucket = next
        }
        var bytes = [UInt8](repeating: 0, count: bucket)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw Failure.badRandom }
        let n = UInt32(json.count)
        for i in 0..<4 { bytes[i] = UInt8((n >> (24 - i * 8)) & 255) }
        bytes.replaceSubrange(4..<(4 + json.count), with: json)
        return Data(bytes)
    }
    static func unframe(_ data: Data) throws -> [String: Any] {
        let bytes = [UInt8](data)
        guard paddingBuckets.contains(bytes.count) else { throw Failure.invalidCarrier }
        let count = bytes.prefix(4).reduce(0) { ($0 << 8) | Int($1) }
        guard count > 0, count <= bytes.count - 4,
              let object = try JSONSerialization.jsonObject(with: Data(bytes[4..<(4 + count)])) as? [String: Any],
              object["v"] as? Int == 1 else { throw Failure.invalidCarrier }
        return object
    }
    static func encode(_ object: [String: Any], mode requested: String) throws -> Encoded {
        guard requested == "event" else { throw Failure.invalidCarrier }
        let payload = try frame(object, spread: true).base64EncodedString()
        // Written byte for byte (base64 is JSON-safe, so nothing is escaped) with
        // the inner member order chosen per request: two closed schemas, four
        // byte shapes, identical to Android and accepted by every relay copy
        // (docs/fixtures/cover_transport/vectors.json). Member order is not
        // significant to any decoder. Previously the order followed Swift's
        // per-process dictionary hash seed: stable within a run, random across runs.
        let swapped = Bool.random()
        let body: String
        switch Profile.allCases.randomElement()! {
        case .event:
            body = swapped ? #"{"events":[{"params":{"data":"\#(payload)"},"name":"page_view"}]}"#
                           : #"{"events":[{"name":"page_view","params":{"data":"\#(payload)"}}]}"#
        case .batch:
            body = swapped ? #"{"batch":[{"payload":"\#(payload)","event":"screen_view"}]}"#
                           : #"{"batch":[{"event":"screen_view","payload":"\#(payload)"}]}"#
        }
        return Encoded(body: Data(body.utf8), type: "application/json")
    }
    static func decode(_ body: Data, type: String) throws -> [String: Any] {
        guard body.count <= maxBytes * 4 else { throw Failure.tooLarge }
        guard type.lowercased().hasPrefix("application/json") else { throw Failure.invalidCarrier }
        guard let json = try JSONSerialization.jsonObject(with: body) as? [String: Any] else { throw Failure.invalidCarrier }
        let encoded: String?
        // Closed, canonical schemas keep the carrier boundary reviewable. A
        // future extension must be a new versioned profile on every platform;
        // silently accepting extra fields would make that boundary ambiguous.
        func hasExactKeys(_ value: [String: Any], _ keys: Set<String>) -> Bool {
            Set(value.keys) == keys
        }
        if hasExactKeys(json, ["events"]), let events = json["events"] as? [[String: Any]], events.count == 1,
           let event = events.first, hasExactKeys(event, ["name", "params"]),
           event["name"] as? String == "page_view",
           let params = event["params"] as? [String: Any], hasExactKeys(params, ["data"]) {
            encoded = params["data"] as? String
        } else if hasExactKeys(json, ["batch"]), let batch = json["batch"] as? [[String: Any]], batch.count == 1,
                  let event = batch.first, hasExactKeys(event, ["event", "payload"]),
                  event["event"] as? String == "screen_view" {
            encoded = event["payload"] as? String
        } else {
            throw Failure.invalidCarrier
        }
        guard let encoded, encoded.utf8.count <= maxBase64Bytes,
              let data = Data(base64Encoded: encoded) else { throw Failure.invalidCarrier }
        return try unframe(data)
    }
    static func data(for request: URLRequest, session: URLSession, selectedMode: String? = mode) async throws -> (Data, URLResponse) {
        // __COVER_DNS_FALLBACK_2026_11_19__ the direct path too goes through CoverNet (DoH fallback).
        guard let selectedMode else { return try await CoverNet.data(for: request, session: session, cover: false) }
        guard let url = request.url, var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { throw Failure.invalidCarrier }
        let path = components.percentEncodedPath + (components.percentEncodedQuery.map { "?" + $0 } ?? "")
        guard path.hasPrefix("/v2/") else { throw Failure.invalidCarrier }
        var headers: [String: String] = [:]
        for (k, v) in request.allHTTPHeaderFields ?? [:] where k.lowercased().hasPrefix("x-oshi-") || k.lowercased() == "content-type" { headers[k.lowercased()] = v }
        let object: [String: Any] = ["v": 1, "method": request.httpMethod ?? "GET", "path": path, "headers": headers, "body": (request.httpBody ?? Data()).base64EncodedString()]
        let carrier = try encode(object, mode: selectedMode)
        let look = Presentation.next()
        components.percentEncodedPath = look.path; components.percentEncodedQuery = look.query; components.fragment = nil
        guard let endpoint = components.url else { throw Failure.invalidCarrier }
        var outer = URLRequest(url: endpoint); outer.httpMethod = "POST"
        // __COVER_SLOW_LINK_2026_11_18__ Padding makes the cover request up to ~3x the
        // bytes it carries and the reply waits for all of them; on a 64 kbit/s link
        // the fixed 30 s timeout expired before the relay could answer (netem "edge":
        // every iOS media upload timed out). Budget proportional to the bytes sent.
        outer.timeoutInterval = timeoutOverrideForTests ?? max(request.timeoutInterval, coverTimeout(outerBytes: carrier.body.count))
        outer.cachePolicy = .reloadIgnoringLocalCacheData; outer.httpBody = carrier.body
        for (name, value) in look.headers { outer.setValue(value, forHTTPHeaderField: name) }
        outer.setValue(look.contentType, forHTTPHeaderField: "Content-Type")
        // __COVER_NO_GZIP_2026_11_18__ (audit finding 5) URLSession otherwise sends
        // `Accept-Encoding: gzip, deflate, br`; the relay's nginx then gzips the cover
        // reply, and the compressed size separates an empty poll from a full one.
        // The server patch refuses to compress these routes; this is the client half.
        outer.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        // __COVER_SHORT_CONNECTIONS_2026_11_19__ / __COVER_DNS_FALLBACK_2026_11_19__ (CoverNetwork.swift)
        let (raw, response) = try await CoverNet.data(for: outer, session: session, cover: true)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else { throw Failure.transport((response as? HTTPURLResponse)?.statusCode ?? 0) }
        // __COVER_MIDDLEBOX_2026_11_18__ The relay always labels a cover reply
        // application/json; a middlebox in front of it may relabel it (netem
        // "rewrite": text/plain + chunked made every iOS and Android request fail).
        // The outer label is not trusted either way — the closed JSON schema in
        // `decode` is the check — so the reply is read as JSON whatever it says.
        // (A gzip-encoded reply is already inflated by URLSession.)
        let decoded = try decode(raw, type: "application/json")
        guard let status = decoded["status"] as? Int, (200...599).contains(status),
              let fields = decoded["headers"] as? [String: Any], let body = decoded["body"] as? String,
              let bytes = Data(base64Encoded: body) else { throw Failure.invalidCarrier }
        var safeHeaders: [String: String] = [:]
        for (key, value) in fields where ["content-type", "retry-after", "content-range"].contains(key.lowercased()) { safeHeaders[key] = String(describing: value) }
        guard let inner = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: safeHeaders) else { throw Failure.invalidCarrier }
        return (bytes, inner)
    }

    /// 15 s plus one second per 4 KiB of outer body (still finishing on ~32 kbit/s),
    /// at most 10 min. Mirrors Android `coverTimeoutMs`.
    static func coverTimeout(outerBytes: Int) -> TimeInterval { min(600, 15 + Double(outerBytes) / 4096) }
    /// Tests only (a frozen-connection test would otherwise wait the 15 s floor).
    nonisolated(unsafe) static var timeoutOverrideForTests: TimeInterval?

    /// Per-request OUTER presentation, mirroring Android `CoverPresentation`:
    /// alias path, analytics-style query, optional header set and order, and
    /// Content-Type spelling. None of it carries data. Relay compatibility: the
    /// router matches the alias on the path without its query, `decode` only
    /// requires `application/json*`, and no other outer header is read
    /// (`tools/covert-regression/cover_vectors.test.js`). Under TLS a passive
    /// observer sees only the resulting LENGTHS; the hostname is unchanged.
    struct Presentation {
        let path: String
        let query: String?
        let headers: [(String, String)]
        let contentType: String

        static let contentTypes = ["application/json", "application/json; charset=utf-8", "application/json;charset=UTF-8"]
        static let queryTemplates = 5
        private static let accepts = ["*/*", "application/json", "application/json, text/plain, */*"]
        // Process-stable, like a real analytics SDK's property/session id.
        private static let sessionTid = token(10, "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789")
        private static let sessionCid = "\(Int64.random(in: 1_000_000_000...9_999_999_999)).\(Int(Date().timeIntervalSince1970))"
        private static let sessionSid = token(16, "0123456789abcdef")
        private static let siteId = Int.random(in: 1...12)
        private static let sequenceLock = NSLock()
        nonisolated(unsafe) private static var sequence = 0

        private static func token(_ n: Int, _ alphabet: String) -> String {
            let chars = Array(alphabet)
            return String((0..<n).map { _ in chars.randomElement()! })
        }

        static func next(now: Date = Date(), languageTag: String = Locale.preferredLanguages.first ?? "en-US") -> Presentation {
            let seq: Int = { sequenceLock.lock(); defer { sequenceLock.unlock() }; sequence += 1; return sequence }()
            let ms = Int64(now.timeIntervalSince1970 * 1000)
            let query: String?
            switch Int.random(in: 0..<queryTemplates) {
            case 0: query = nil
            case 1: query = "v=2&tid=G-\(sessionTid)&cid=\(sessionCid)&_s=\(seq % 200)&_p=\(1_000_000_000 + Int.random(in: 0..<Int(Int32.max)))"
            case 2: query = "idsite=\(siteId)&rec=1&r=\(Int.random(in: 100_000...999_999))&h=\((ms / 3_600_000) % 24)&m=\((ms / 60_000) % 60)&s=\((ms / 1000) % 60)"
            case 3: query = "_=\(ms)"
            default: query = "ver=\(Int.random(in: 1...3)).\(Int.random(in: 0...9)).\(Int.random(in: 0...20))&sid=\(sessionSid)&seq=\(seq)"
            }
            var headers: [(String, String)] = []
            if Int.random(in: 0..<10) < 7 { headers.append(("Accept", accepts.randomElement()!)) }
            if Int.random(in: 0..<10) < 6 { headers.append(("Accept-Language", acceptLanguage(languageTag))) }
            if Bool.random() { headers.append(("Cache-Control", "no-cache")) }
            if Int.random(in: 0..<10) < 3 { headers.append(("Pragma", "no-cache")) }
            headers.shuffle()
            return Presentation(path: coverPaths.randomElement()!, query: query, headers: headers,
                                contentType: contentTypes.randomElement()!)
        }

        private static func acceptLanguage(_ tag: String) -> String {
            let primary = tag.split(separator: "-").first.map(String.init) ?? "en"
            switch Int.random(in: 0..<3) {
            case 0: return tag
            case 1: return primary == tag ? tag : "\(tag),\(primary);q=0.9"
            default: return primary == "en" ? "\(tag),en;q=0.9" : "\(tag),\(primary);q=0.9,en;q=0.8"
            }
        }
    }
}

/// __COVER_RETRY_2026_11_18__ When "Disguise OSHI traffic" is ON, a 1:1 message the
/// disguised (V2) path could not deliver is retried over that path ONLY, on this
/// schedule — the iOS twin of Android `CoverOutbox` (same backoff, same 1 h limit).
///
/// Before it, iOS failed closed and then waited: the row went red and the retry
/// queue was drained only on a reachability change or a return to the foreground,
/// at most three times per process. On a censored or flapping network where the
/// interface stays up, neither event fires, so a message sent with cover ON was
/// re-sent only when the user left and reopened the app (netem runs: censor,
/// captive portal, flapping link, 10-min block). Pure value type, unit-tested
/// (`StegoV1Tests.testCoverRetrySchedule*`), driven by `MessageManager.coverRetryTick`.
nonisolated struct CoverRetrySchedule: Equatable, Sendable {
    enum Decision: Equatable { case wait, attempt, giveUp }
    /// Seconds between disguised retries; the last value repeats (= Android BACKOFF_MS).
    static let backoff: [TimeInterval] = [10, 30, 60, 120, 300, 600]
    /// After this long the row stays "failed" (manual retry still possible).
    static let giveUpAfter: TimeInterval = 3600
    /// A successful disguised request ("the path is back") may bring the next retry
    /// forward, but never closer than this to the previous one: no retry storm.
    static let minSpacing: TimeInterval = 5

    let firstFailure: Date
    private(set) var attempts = 0
    private(set) var lastAttempt: Date

    init(firstFailure: Date) { self.firstFailure = firstFailure; lastAttempt = firstFailure }

    var nextDue: Date { lastAttempt.addingTimeInterval(Self.backoff[min(attempts, Self.backoff.count - 1)]) }

    /// `kicked`: a disguised request has just succeeded (e.g. a V2 pull), so the
    /// path works again and waiting out the rest of a 10-minute backoff is pointless.
    func decision(now: Date, kicked: Bool = false) -> Decision {
        if now.timeIntervalSince(firstFailure) >= Self.giveUpAfter { return .giveUp }
        if now >= nextDue { return .attempt }
        if kicked, now.timeIntervalSince(lastAttempt) >= Self.minSpacing { return .attempt }
        return .wait
    }

    mutating func recordAttempt(now: Date) { attempts += 1; lastAttempt = now }
}


/// __COVER_WAITING_BUBBLE_2026_11_18__ What an outgoing 1:1 bubble says while cover
/// mode holds it. A message the disguised path could not carry yet is NOT failed: it
/// sits in the retry queue and `MessageManager.coverRetryTick` re-drives it until the
/// path answers (or `CoverRetrySchedule.giveUpAfter`). Showing the red "tap to retry"
/// for that whole time told people their message was lost while it was in fact
/// waiting — Android has said "Waiting for disguised connection" since the start.
/// Only `.failed` rows qualify: every in-flight send is ALSO queued (the pre-flight
/// enqueue in `sendMessage`), and a message being sent right now is "sending".
enum CoverWaitingState {
    static func isWaiting(failed: Bool, coverOn: Bool, queued: Bool, gaveUp: Bool) -> Bool {
        failed && coverOn && queued && !gaveUp
    }
}
