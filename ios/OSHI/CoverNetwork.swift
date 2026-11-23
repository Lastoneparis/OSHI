import Foundation
import Network
import Security

// Censorship 2026 hardening (docs/CENSORSHIP_2026_THREAT_MODEL.md §3/§5), the iOS +
// Mac Catalyst twin of Android feat/cover-2026-hardening-android:
//   5812999b CoverConnectionPolicy  → `CoverConnectionPolicy` below
//   012f7286 CoverDns               → `CoverDns` + `OneShotHTTP` below
//   7e78be74 pull budget            → `CoverConnectionPolicy.smallPullBudget`, V2Client.pullPath
// Every signed /v2 request (direct, disguised, and the OHTTP relay hop) leaves through
// `CoverNet.data(for:session:cover:)`.

/// __COVER_SHORT_CONNECTIONS_2026_11_19__ Survive a per-connection volume cap.
///
/// Since June 2025 Russia's TSPU silently freezes TCP connections to foreign hosting
/// ranges once ~16 KB (later measured as ~25 packets, ~8 on some mobile networks) have
/// flowed from the server: no RST, the reply just stops (net4people/bbs#490). Contabo
/// AS51167 — where oshi-messenger.com lives — is in the reported set. The counter is PER
/// CONNECTION: many short connections pass, one long-lived one dies.
///
/// A padded cover exchange is ~5.5 KB each way (4 KiB bucket + base64 + JSON), so a
/// keep-alive (or HTTP/2) connection reused for polls dies on the 3rd exchange and every
/// later call on it waits for its timeout. A fresh TLS connection per cover call carries
/// one handshake + one exchange: under the cap for every text/poll.
///
/// Policy (same as Android): normal pooling until a cover exchange fails at the TRANSPORT
/// level (timeout, reset, lost connection — not an HTTP status the relay chose to send,
/// not a malformed carrier, not a pinning rejection). Then, for `windowSeconds`, each cover
/// call gets its own ephemeral URLSession (same delegate, so same SPKI pinning), which is
/// invalidated afterwards. A further transport failure extends the window.
///
/// Not fixed: media chunks (≥16 KiB padded bucket) exceed the cap inside one request.
nonisolated enum CoverConnectionPolicy {
    static let windowSeconds: TimeInterval = 15 * 60

    /// __PULL_BUDGET_2026_11_19__ Pull page size (bytes of inner JSON) while the window is
    /// open: base64 (x4/3) plus ~200 bytes of record keeps the reply in the 4 KiB padding
    /// bucket (~5.5 KB on the wire, under a 16 KB cap even with a fresh TLS handshake).
    /// Mirrors Android `CoverConnectionPolicy.SMALL_PULL_BUDGET`.
    static let smallPullBudget = 2400

    private static let lock = NSLock()
    nonisolated(unsafe) private static var freshUntil: TimeInterval = 0
    nonisolated(unsafe) private static var clockOverride: (() -> TimeInterval)?

    static var now: TimeInterval {
        if let clock = lock.withLock({ clockOverride }) { return clock() }
        return Date().timeIntervalSince1970
    }

    static func freshConnections() -> Bool {
        let t = now
        return lock.withLock { t < freshUntil }
    }

    /// Called with the failure of one cover exchange.
    static func onFailure(_ error: Error) {
        guard isTransportFailure(error) else { return }
        let until = now + windowSeconds
        lock.withLock { freshUntil = until }
    }

    /// The `budget=` query value for a pull, or nil (Android `pullBudgetQuery`).
    /// Whenever capped, cover or not (Android 85eea147): the blocked-host relay fallback's
    /// OHTTP replies need the same small pages.
    static func pullBudget() -> Int? {
        freshConnections() ? smallPullBudget : nil
    }

    /// Test hooks.
    static func reset() { lock.withLock { freshUntil = 0 } }
    static func setClock(_ clock: (() -> TimeInterval)?) { lock.withLock { clockOverride = clock } }

    /// Our own "the relay answered with a status / garbage" errors are not a stalled link;
    /// neither is a cancellation (URLSession reports a pin rejection as -999 cancelled).
    static func isTransportFailure(_ error: Error) -> Bool {
        if error is CoverTransport.Failure { return false }
        if error is CancellationError { return false }
        if let e = error as? URLError {
            switch e.code {
            case .cancelled, .badURL, .unsupportedURL, .userAuthenticationRequired,
                 .userCancelledAuthentication, .serverCertificateUntrusted,
                 .serverCertificateHasBadDate, .serverCertificateNotYetValid,
                 .serverCertificateHasUnknownRoot, .clientCertificateRejected,
                 .clientCertificateRequired, .appTransportSecurityRequiresSecureConnection:
                return false
            default:
                return true
            }
        }
        if let e = error as? OneShotHTTP.Failure { return e != .badResponse }
        if error is NWError { return true }
        let ns = error as NSError
        return ns.domain == NSPOSIXErrorDomain
    }
}

/// __COVER_DNS_FALLBACK_2026_11_19__ Resolve the OSHI hosts when the network's resolver lies.
///
/// DNS tampering is the cheapest block there is and is still used everywhere in 2026:
/// Iran answers blocked names with 10.10.34.34-36, Turkmenistan/Pakistan/Turkey/Indonesia
/// run DNS blocklists, many ISPs return NXDOMAIN or a private block-page address.
///
/// Order (nothing changes on a network whose DNS is honest):
///  1. the system resolver (getaddrinfo — exactly what URLSession would use);
///  2. ONLY if it fails, or returned a non-routable address (private, loopback,
///     link-local, CGNAT, unspecified — never a real answer for a public name):
///     DNS-over-HTTPS to Cloudflare (https://1.1.1.1/dns-query) then Google
///     (https://8.8.8.8/resolve), addressed by IP so no bootstrap lookup is needed;
///  3. if DoH also fails, the system outcome is used unchanged (URLSession as before).
///
/// When DoH answers, the request goes out on a one-shot TLS connection to that address
/// (`OneShotHTTP`) with the real name as SNI and Host, and the same SPKI pins for the API
/// host: a wrong address cannot impersonate OSHI. Positive DoH answers are cached 5 min;
/// a dead DoH path 60 s (a whitelist network costs one timeout per minute).
///
/// Only the hosts the /v2 transport talks to are guarded (`isGuarded`): the API host and
/// the configured OHTTP relay. Local/test names are never second-guessed.
/// Limit: defeats DNS-only blocking, nothing against SNI or IP blocking. Privacy cost:
/// when it engages, the looked-up name goes to Cloudflare or Google.
nonisolated final class CoverDns: @unchecked Sendable {
    enum Outcome: Equatable {
        /// Use the normal path (system DNS via URLSession).
        case system
        /// Connect to these addresses instead (the real name stays SNI/Host).
        case override([String])
    }

    typealias Lookup = @Sendable (String) async throws -> [String]

    private let system: Lookup
    private let doh: Lookup
    private let clock: @Sendable () -> TimeInterval
    private let lock = NSLock()
    private var cache: [String: (answers: [String], until: TimeInterval)] = [:]
    /// Diagnostics: last time DoH had to answer (0 = never).
    private(set) var lastFallbackAt: TimeInterval = 0

    static let positiveTTL: TimeInterval = 5 * 60
    static let negativeTTL: TimeInterval = 60
    static let dohEndpoints = ["https://1.1.1.1/dns-query", "https://8.8.8.8/resolve"]

    static let shared = CoverDns()

    init(system: @escaping Lookup = { try await CoverDns.systemLookup($0) },
         doh: @escaping Lookup = { await CoverDns.dohLookup($0) },
         clock: @escaping @Sendable () -> TimeInterval = { Date().timeIntervalSince1970 }) {
        self.system = system
        self.doh = doh
        self.clock = clock
    }

    func resolve(_ hostname: String) async -> Outcome {
        if Self.isLocalName(hostname) || Self.isIPLiteral(hostname) { return .system }
        let answers = try? await system(hostname)
        if let answers, !answers.isEmpty, !answers.contains(where: Self.isNonRoutable) { return .system }
        let routableFromSystem = (answers ?? []).filter { !Self.isNonRoutable($0) }
        if !routableFromSystem.isEmpty { return .override(routableFromSystem) }   // mixed answer: drop the poison
        let key = hostname.lowercased()
        let now = clock()
        let cached: [String]? = lock.withLock {
            guard let c = cache[key], c.until > now else { return nil }
            return c.answers
        }
        let viaDoh: [String]
        if let cached { viaDoh = cached } else {
            let fresh = ((try? await doh(hostname)) ?? []).filter { !Self.isNonRoutable($0) }
            lock.withLock { cache[key] = (fresh, now + (fresh.isEmpty ? Self.negativeTTL : Self.positiveTTL)) }
            viaDoh = fresh
        }
        guard !viaDoh.isEmpty else { return .system }
        lock.withLock { lastFallbackAt = now }
        return .override(viaDoh)
    }

    // MARK: Guarded hosts

    nonisolated(unsafe) static var extraGuardedHostsForTests: Set<String> = []

    static func isGuarded(_ host: String) -> Bool {
        let h = host.lowercased()
        if h == V2Client.apiPinnedHost || h.hasSuffix("." + V2Client.apiPinnedHost) { return true }
        if let relay = OHTTPSettings.relayURL?.host?.lowercased(), relay == h { return true }
        if RelayFallback.fallbackRelayURLs.contains(where: { $0.host?.lowercased() == h }) { return true }
        return extraGuardedHostsForTests.contains(h)
    }

    // MARK: Address classification

    static func isLocalName(_ h: String) -> Bool {
        var n = h.lowercased()
        while n.hasSuffix(".") { n.removeLast() }
        // .invalid / .example are RFC 2606 reserved too (iOS tests use relay.invalid).
        return !n.contains(".") || n == "localhost" ||
            [".local", ".test", ".internal", ".lan", ".localhost", ".home.arpa", ".invalid", ".example"].contains { n.hasSuffix($0) }
    }

    static func isIPLiteral(_ h: String) -> Bool {
        IPv4Address(h) != nil || IPv6Address(h.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))) != nil
    }

    /// Same ranges as Android (`InetAddress` any/loopback/link/site-local/multicast,
    /// 0/8, 100.64/10 CGNAT, fc00::/7 ULA). An IPv4-mapped IPv6 address is judged as IPv4.
    static func isNonRoutable(_ literal: String) -> Bool {
        if let v4 = IPv4Address(literal) { return nonRoutable4([UInt8](v4.rawValue)) }
        guard let v6 = IPv6Address(literal) else { return true }   // not an address at all
        let b = [UInt8](v6.rawValue)
        if b[0..<10].allSatisfy({ $0 == 0 }) && b[10] == 0xff && b[11] == 0xff { return nonRoutable4(Array(b[12..<16])) }
        if b.allSatisfy({ $0 == 0 }) { return true }                                  // ::
        if b[0..<15].allSatisfy({ $0 == 0 }) && b[15] == 1 { return true }            // ::1
        if b[0] == 0xff { return true }                                               // multicast
        if b[0] == 0xfe && (b[1] & 0xc0) == 0x80 { return true }                      // fe80::/10 link-local
        if b[0] == 0xfe && (b[1] & 0xc0) == 0xc0 { return true }                      // fec0::/10 site-local
        if (b[0] & 0xfe) == 0xfc { return true }                                      // fc00::/7 ULA
        return false
    }

    private static func nonRoutable4(_ b: [UInt8]) -> Bool {
        b[0] == 0 || b[0] == 10 || b[0] == 127 ||
            (b[0] == 169 && b[1] == 254) ||
            (b[0] == 172 && (b[1] & 0xf0) == 16) ||
            (b[0] == 192 && b[1] == 168) ||
            (b[0] == 100 && (b[1] & 0xc0) == 64) ||
            b[0] >= 224
    }

    // MARK: Resolvers

    static func systemLookup(_ hostname: String) async throws -> [String] {
        try await withCheckedThrowingContinuation { cont in
            DispatchQueue.global(qos: .userInitiated).async {
                var hints = addrinfo(ai_flags: AI_DEFAULT, ai_family: AF_UNSPEC, ai_socktype: SOCK_STREAM,
                                     ai_protocol: IPPROTO_TCP, ai_addrlen: 0, ai_canonname: nil, ai_addr: nil, ai_next: nil)
                var res: UnsafeMutablePointer<addrinfo>?
                let rc = getaddrinfo(hostname, "443", &hints, &res)
                guard rc == 0, let first = res else {
                    cont.resume(throwing: URLError(.cannotFindHost)); return
                }
                defer { freeaddrinfo(first) }
                var out: [String] = []
                var p: UnsafeMutablePointer<addrinfo>? = first
                while let ai = p {
                    var buf = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    if getnameinfo(ai.pointee.ai_addr, ai.pointee.ai_addrlen, &buf, socklen_t(buf.count), nil, 0, NI_NUMERICHOST) == 0 {
                        var s = String(cString: buf)
                        if let pct = s.firstIndex(of: "%") { s = String(s[..<pct]) }   // drop a scope id
                        if !out.contains(s) { out.append(s) }
                    }
                    p = ai.pointee.ai_next
                }
                cont.resume(returning: out)
            }
        }
    }

    private static let dohSession: URLSession = {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 4
        cfg.timeoutIntervalForResource = 6
        cfg.urlCache = nil
        cfg.httpCookieStorage = nil
        cfg.waitsForConnectivity = false
        return URLSession(configuration: cfg)
    }()

    /// A first, AAAA only when there is no A answer; Cloudflare then Google.
    static func dohLookup(_ hostname: String) async -> [String] {
        for endpoint in dohEndpoints {
            var out: [String] = []
            for type in ["A", "AAAA"] where out.isEmpty {
                var c = URLComponents(string: endpoint)!
                c.queryItems = [URLQueryItem(name: "name", value: hostname), URLQueryItem(name: "type", value: type)]
                guard let url = c.url else { continue }
                var req = URLRequest(url: url)
                req.setValue("application/dns-json", forHTTPHeaderField: "Accept")
                if let (data, resp) = try? await dohSession.data(for: req),
                   (resp as? HTTPURLResponse)?.statusCode == 200 {
                    out += parseDnsJson(data)
                }
            }
            if !out.isEmpty { return out }
        }
        return []
    }

    /// RFC 8427-style JSON (Cloudflare/Google): Status 0 and type-1/28 answers only.
    static func parseDnsJson(_ data: Data) -> [String] {
        guard let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (o["Status"] as? Int) == 0, let answers = o["Answer"] as? [[String: Any]] else { return [] }
        var out: [String] = []
        for a in answers {
            guard let type = a["type"] as? Int, let s = a["data"] as? String else { continue }
            if type == 1, IPv4Address(s) != nil, s.split(separator: ".").count == 4 { out.append(s) }
            else if type == 28, s.contains(":"), IPv6Address(s) != nil { out.append(s) }
        }
        return out
    }
}

/// One HTTP/1.1 exchange on its own TCP(+TLS) connection to a given address, with the
/// URL's real host as SNI and `Host` — what URLSession cannot do (it always resolves the
/// URL's host itself). Used only when `CoverDns` overrides the system answer. No
/// redirects, no retries, `Connection: close`: one connection per exchange by
/// construction, which also satisfies `CoverConnectionPolicy`.
nonisolated enum OneShotHTTP {
    enum Failure: Error, Equatable { case connectFailed, timedOut, closed, badResponse, tooLarge }

    static let maxResponseBytes = 64 * 1024 * 1024

    static func data(for request: URLRequest, address: String, pins: Set<String>?) async throws -> (Data, HTTPURLResponse) {
        guard let url = request.url, let host = url.host, let scheme = url.scheme?.lowercased(),
              scheme == "https" || scheme == "http" else { throw URLError(.badURL) }
        let tls = scheme == "https"
        let port = url.port ?? (tls ? 443 : 80)
        var comps = URLComponents(url: url, resolvingAgainstBaseURL: false)
        comps?.scheme = nil; comps?.host = nil; comps?.port = nil
        var target = comps?.percentEncodedPath ?? "/"
        if target.isEmpty { target = "/" }
        if let q = comps?.percentEncodedQuery { target += "?" + q }
        let body = request.httpBody ?? Data()
        let method = request.httpMethod ?? "GET"
        var head = "\(method) \(target) HTTP/1.1\r\n"
        head += "Host: \(host)\((url.port != nil) ? ":\(port)" : "")\r\n"
        var fields = request.allHTTPHeaderFields ?? [:]
        for k in fields.keys where ["host", "connection", "content-length", "transfer-encoding", "keep-alive"].contains(k.lowercased()) {
            fields.removeValue(forKey: k)
        }
        if !fields.keys.contains(where: { $0.lowercased() == "accept-encoding" }) { fields["Accept-Encoding"] = "identity" }
        if !fields.keys.contains(where: { $0.lowercased() == "accept" }) { fields["Accept"] = "*/*" }
        for (k, v) in fields { head += "\(k): \(v)\r\n" }
        if !body.isEmpty || ["POST", "PUT", "PATCH"].contains(method) { head += "Content-Length: \(body.count)\r\n" }
        head += "Connection: close\r\n\r\n"
        var wire = Data(head.utf8); wire.append(body)

        let parameters: NWParameters
        if tls {
            let opts = NWProtocolTLS.Options()
            let sec = opts.securityProtocolOptions
            sec_protocol_options_set_tls_server_name(sec, host)
            sec_protocol_options_add_tls_application_protocol(sec, "http/1.1")
            sec_protocol_options_set_verify_block(sec, { _, secTrust, complete in
                let trust = sec_trust_copy_ref(secTrust).takeRetainedValue()
                SecTrustSetPolicies(trust, SecPolicyCreateSSL(true, host as CFString))
                var ok = SecTrustEvaluateWithError(trust, nil)
                if ok, let pins { ok = OshiSPKIPinningDelegate.trust(trust, matchesAnyOf: pins) }
                complete(ok)
            }, DispatchQueue.global(qos: .userInitiated))
            parameters = NWParameters(tls: opts, tcp: NWProtocolTCP.Options())
        } else {
            parameters = .tcp
        }
        let endpoint = NWEndpoint.hostPort(host: NWEndpoint.Host(address), port: NWEndpoint.Port(rawValue: UInt16(port))!)
        let exchange = Exchange(connection: NWConnection(to: endpoint, using: parameters),
                                timeout: max(1, request.timeoutInterval))
        let raw = try await exchange.run(sending: wire)
        return try parse(raw, url: url)
    }

    /// Parse a complete HTTP/1.1 response (Content-Length, chunked, or read-to-close).
    static func parse(_ raw: Data, url: URL) throws -> (Data, HTTPURLResponse) {
        guard let sep = raw.range(of: Data("\r\n\r\n".utf8)),
              let headText = String(data: raw[raw.startIndex..<sep.lowerBound], encoding: .isoLatin1) else { throw Failure.badResponse }
        var lines = headText.components(separatedBy: "\r\n")
        let statusLine = lines.removeFirst().split(separator: " ")
        guard statusLine.count >= 2, statusLine[0].hasPrefix("HTTP/1."), let status = Int(statusLine[1]) else { throw Failure.badResponse }
        var headers: [String: String] = [:]
        for l in lines {
            guard let c = l.firstIndex(of: ":") else { continue }
            let k = String(l[..<c]).trimmingCharacters(in: .whitespaces)
            let v = String(l[l.index(after: c)...]).trimmingCharacters(in: .whitespaces)
            headers[k] = headers[k].map { $0 + ", " + v } ?? v
        }
        func header(_ name: String) -> String? { headers.first { $0.key.lowercased() == name }?.value }
        var body = Data(raw[sep.upperBound...])
        if header("transfer-encoding")?.lowercased().contains("chunked") == true {
            body = try dechunk(body)
            headers = headers.filter { $0.key.lowercased() != "transfer-encoding" }
        } else if let cl = header("content-length").flatMap({ Int($0) }) {
            guard body.count >= cl else { throw Failure.closed }
            body = body.prefix(cl)
        }
        guard let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers) else {
            throw Failure.badResponse
        }
        return (Data(body), response)
    }

    static func dechunk(_ data: Data) throws -> Data {
        var out = Data()
        var i = data.startIndex
        let crlf = Data("\r\n".utf8)
        while true {
            guard let lineEnd = data.range(of: crlf, in: i..<data.endIndex) else { throw Failure.closed }
            let sizeText = String(data: data[i..<lineEnd.lowerBound], encoding: .ascii)?
                .split(separator: ";").first.map { $0.trimmingCharacters(in: .whitespaces) } ?? ""
            guard let size = Int(sizeText, radix: 16) else { throw Failure.badResponse }
            i = lineEnd.upperBound
            if size == 0 { return out }
            guard data.distance(from: i, to: data.endIndex) >= size + 2 else { throw Failure.closed }
            out.append(data[i..<data.index(i, offsetBy: size)])
            i = data.index(i, offsetBy: size + 2)
        }
    }

    /// True once `raw` holds a whole response, so we need not wait for the close.
    static func isComplete(_ raw: Data) -> Bool {
        guard let sep = raw.range(of: Data("\r\n\r\n".utf8)),
              let head = String(data: raw[raw.startIndex..<sep.lowerBound], encoding: .isoLatin1)?.lowercased() else { return false }
        let bodyCount = raw.distance(from: sep.upperBound, to: raw.endIndex)
        for line in head.components(separatedBy: "\r\n") {
            if line.hasPrefix("content-length:"), let n = Int(line.dropFirst(15).trimmingCharacters(in: .whitespaces)) {
                return bodyCount >= n
            }
            if line.hasPrefix("transfer-encoding:") && line.contains("chunked") {
                return raw.suffix(5) == Data("0\r\n\r\n".utf8) && (try? dechunk(Data(raw[sep.upperBound...]))) != nil
            }
        }
        return false
    }

    /// Drives one NWConnection: connect, send, read until complete/closed. The timeout is
    /// an IDLE timeout like URLRequest.timeoutInterval: re-armed by every byte received.
    private final class Exchange: @unchecked Sendable {
        let connection: NWConnection
        let timeout: TimeInterval
        let queue = DispatchQueue(label: "oshi.oneshot-http")
        var buffer = Data()
        var finished = false
        var ready = false
        var timer: DispatchSourceTimer?
        var continuation: CheckedContinuation<Data, Error>?

        init(connection: NWConnection, timeout: TimeInterval) { self.connection = connection; self.timeout = timeout }

        func run(sending wire: Data) async throws -> Data {
            try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Data, Error>) in
                    queue.async { self.start(cont, wire) }
                }
            } onCancel: {
                queue.async { self.finish(.failure(CancellationError())) }
            }
        }

        private func start(_ cont: CheckedContinuation<Data, Error>, _ wire: Data) {
            continuation = cont
            armTimer()
            connection.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                switch state {
                case .ready:
                    self.ready = true
                    self.connection.send(content: wire, completion: .contentProcessed { err in
                        if let err { self.finish(.failure(err)) } else { self.receive() }
                    })
                case .failed(let e): self.finish(.failure(self.ready ? e : Failure.connectFailed))
                case .waiting: if !self.ready { self.finish(.failure(Failure.connectFailed)) }
                default: break
                }
            }
            connection.start(queue: queue)
        }

        private func receive() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
                guard let self, !self.finished else { return }
                if let data, !data.isEmpty {
                    self.buffer.append(data)
                    self.armTimer()
                    if self.buffer.count > OneShotHTTP.maxResponseBytes { self.finish(.failure(Failure.tooLarge)); return }
                    if OneShotHTTP.isComplete(self.buffer) { self.finish(.success(self.buffer)); return }
                }
                if isComplete {
                    self.finish(self.buffer.isEmpty ? .failure(Failure.closed) : .success(self.buffer)); return
                }
                if let error { self.finish(.failure(error)); return }
                self.receive()
            }
        }

        private func armTimer() {
            timer?.cancel()
            let t = DispatchSource.makeTimerSource(queue: queue)
            t.schedule(deadline: .now() + timeout)
            t.setEventHandler { [weak self] in self?.finish(.failure(Failure.timedOut)) }
            t.resume()
            timer = t
        }

        private func finish(_ result: Result<Data, Error>) {
            guard !finished else { return }
            finished = true
            timer?.cancel(); timer = nil
            connection.stateUpdateHandler = nil
            connection.cancel()
            let c = continuation; continuation = nil
            c?.resume(with: result)
        }
    }
}

/// The single exit for every /v2 request body (direct, disguised, OHTTP relay hop).
nonisolated enum CoverNet {
    static func isRangedBlobRead(_ request: URLRequest) -> Bool {
        guard let url = request.url, url.path.hasPrefix("/v2/blobs/"),
              let q = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems else { return false }
        return q.contains { $0.name == "offset" }
    }

    /// - cover: this is a disguised exchange (`CoverTransport`), so a transport failure
    ///   opens the short-connection window and the window applies to it.
    static func data(for request: URLRequest, session: URLSession, cover: Bool,
                     resolve: (String) async -> CoverDns.Outcome = { await CoverDns.shared.resolve($0) }) async throws -> (Data, URLResponse) {
        do {
            if let host = request.url?.host, CoverDns.isGuarded(host),
               case .override(let addresses) = await resolve(host) {
                let pins: Set<String>? = (host == V2Client.apiPinnedHost || host.hasSuffix("." + V2Client.apiPinnedHost))
                    ? V2Client.apiSPKIPins : nil
                var lastError: Error = OneShotHTTP.Failure.connectFailed
                // Like OkHttp's route selection: the next address only when the previous
                // one never connected (nothing was sent, so nothing is replayed).
                for address in addresses.prefix(3) {
                    do { return try await OneShotHTTP.data(for: request, address: address, pins: pins) }
                    catch OneShotHTTP.Failure.connectFailed { lastError = OneShotHTTP.Failure.connectFailed; continue }
                }
                throw lastError
            }
            // __BLOB_RANGE_2026_11_19__ a ranged blob part on the DIRECT path also gets its
            // own connection while capped (§7.3: one ~13 KB part per connection).
            if (cover || isRangedBlobRead(request)) && CoverConnectionPolicy.freshConnections() {
                // __COVER_SHORT_CONNECTIONS_2026_11_19__ own pool = own TCP/TLS connection.
                let fresh = URLSession(configuration: session.configuration, delegate: session.delegate, delegateQueue: nil)
                defer { fresh.invalidateAndCancel() }
                return try await fresh.data(for: request)
            }
            return try await session.data(for: request)
        } catch {
            if cover { CoverConnectionPolicy.onFailure(error) }
            throw error
        }
    }
}

/// __RELAY_FALLBACK_2026_11_19__ Blocked-host fallback through OSHI's own OHTTP relay
/// (docs/CENSORSHIP_2026_THREAT_MODEL.md §9; iOS twin of Android `RelayFallback`, 85eea147).
///
/// When oshi-messenger.com itself is blocked (SNI / IP / DNS block), every /v2 call fails at
/// the transport level. `fallbackRelayURLs` live on other IPs under unrelated hostnames;
/// they forward HPKE-sealed requests to the gateway, which replays them to /v2 unchanged (the
/// signature still verifies). Once the direct path has shown the block, eligible /v2 calls
/// go through a relay for `windowSeconds`; then the next call tries direct again.
///
/// __RELAY_LIST_CH1_2026_11_19__ Several relays, in order (Android `RelayFallback`): a call
/// tries the preferred one and, on any relay-hop failure except a gateway key mismatch (same
/// gateway behind every relay), the NEXT one, each at most once per call. The one that
/// answered becomes preferred. A retry through another relay may duplicate a request whose
/// reply was lost: the v2 idempotency cache answers it (same rule as direct -> relay).
///
/// Activation: a connect-phase failure (DNS, refused, no route, TLS handshake) at once; any
/// other transport failure (reset, lost connection, timeout) when it is the 2nd in a row
/// without a success in between, within `streakSeconds`. HTTP statuses never count.
///
/// Privacy: OSHI runs both the relay and the gateway — circumvention, not OHTTP's operator
/// separation, which is why this is NOT the "Privacy relay" default. The user's own Privacy
/// relay, when ON, takes precedence and keeps its no-direct-fallback rule.
nonisolated enum RelayFallback {
    /// 1. foodies.agency — 213.199.60.210, Contabo (same provider as the main server).
    /// 2. CH-1 Gland — 84.75.255.200, UP-NETWORK AS213929 (Switzerland, not Contabo).
    static let fallbackRelayURLs: [URL] = [
        URL(string: "https://foodies.agency/api/v1/batch")!,
        URL(string: "https://static-84-75-255-200.sslip.io/api/v1/batch")!,
    ]
    static var fallbackRelayURL: URL { fallbackRelayURLs[0] }
    static let windowSeconds: TimeInterval = 15 * 60
    static let streakSeconds: TimeInterval = 3 * 60

    private static let lock = NSLock()
    nonisolated(unsafe) private static var activeUntil: TimeInterval = 0
    nonisolated(unsafe) private static var lastFailureAt: TimeInterval = 0
    nonisolated(unsafe) private static var streak = 0

    struct Transport { let relayURL: URL; let keyConfig: OHTTPKeyConfig; let directOrigin: URL? }
    nonisolated(unsafe) private static var override: [Transport]?
    /// Index (into the configured list) of the relay that answered last.
    nonisolated(unsafe) private static var preferred = 0

    private static func configured() -> [Transport] {
        if let o = lock.withLock({ override }) { return o }
        guard OHTTPSettings.isSupportedOS, let key = OHTTPSettings.pinnedKeyConfig else { return [] }
        return fallbackRelayURLs.map { Transport(relayURL: $0, keyConfig: key, directOrigin: nil) }
    }

    /// All relays, in the order the next call tries them (preferred first).
    static func transports() -> [Transport] {
        let all = configured()
        guard all.count > 1 else { return all }
        let p = min(max(lock.withLock { preferred }, 0), all.count - 1)
        return Array(all[p...] + all[..<p])
    }

    static func transport() -> Transport? { transports().first }

    static func overrideForTests(_ t: Transport?) { overrideAllForTests(t.map { [$0] }) }
    static func overrideAllForTests(_ list: [Transport]?) {
        lock.withLock { override = (list?.isEmpty ?? true) ? nil : list; preferred = 0 }; reset()
    }

    /// `t` answered: later calls try it first.
    static func onRelaySuccess(_ t: Transport) {
        if let i = configured().firstIndex(where: { $0.relayURL == t.relayURL }) { lock.withLock { preferred = i } }
    }

    static func active() -> Bool { let now = CoverConnectionPolicy.now; return lock.withLock { now < activeUntil } }

    static func onDirectSuccess() { lock.withLock { streak = 0 } }

    /// True when this failure (just) put the fallback in force.
    @discardableResult
    static func onDirectFailure(_ error: Error) -> Bool {
        guard CoverConnectionPolicy.isTransportFailure(error) else { return false }
        let now = CoverConnectionPolicy.now
        return lock.withLock {
            streak = now - lastFailureAt <= streakSeconds ? streak + 1 : 1
            lastFailureAt = now
            if isConnectPhase(error) || streak >= 2 { activeUntil = now + windowSeconds; return true }
            return false
        }
    }

    /// Every relay failed (last error `error`); unreachable: let the next call probe direct again.
    static func onRelayFailure(_ error: Error) {
        if CoverConnectionPolicy.isTransportFailure(error) && isConnectPhase(error) { lock.withLock { activeUntil = 0 } }
    }

    static func reset() { lock.withLock { activeUntil = 0; lastFailureAt = 0; streak = 0 } }

    static func isConnectPhase(_ error: Error) -> Bool {
        if let e = error as? URLError {
            return [.cannotFindHost, .cannotConnectToHost, .dnsLookupFailed, .secureConnectionFailed].contains(e.code)
        }
        if let e = error as? OneShotHTTP.Failure { return e == .connectFailed }
        let ns = error as NSError
        return ns.domain == NSPOSIXErrorDomain && [Int(ECONNREFUSED), Int(EHOSTUNREACH), Int(ENETUNREACH)].contains(ns.code)
    }

    static func isEligible(_ request: URLRequest, _ t: Transport) -> Bool {
        guard let url = request.url, url.path.hasPrefix("/v2/") else { return false }
        if let origin = t.directOrigin {
            return url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port
        }
        return url.scheme?.lowercased() == "https" && url.host?.lowercased() == OHTTPSettings.targetAuthority
    }

    /// Privacy relay OFF: `direct` (plain or cover) as before, unless the OSHI host is shown
    /// blocked; then through the built-in relay. A direct transport failure that puts the
    /// fallback in force is retried once through the relay at once (same signed request; the
    /// relay idempotency cache answers a duplicate whose direct copy did arrive).
    static func data(for request: URLRequest, session: URLSession,
                     direct: () async throws -> (Data, URLResponse)) async throws -> (Data, URLResponse) {
        let relays = transports()
        guard let first = relays.first, isEligible(request, first) else { return try await direct() }
        func viaRelay() async throws -> (Data, URLResponse) {
            var last: Error?
            for t in relays {
                do {
                    let result = try await OHTTPTransport.data(for: request, session: session, relayURL: t.relayURL,
                                                               keyConfig: t.keyConfig, shortConnections: true)
                    onRelaySuccess(t)
                    return result
                } catch OHTTPError.keyConfigMismatch {
                    throw OHTTPError.keyConfigMismatch   // same gateway behind every relay
                } catch {
                    last = error
                }
            }
            let e = last ?? OHTTPError.notConfigured
            onRelayFailure(e); throw e
        }
        if active() { return try await viaRelay() }
        do {
            let result = try await direct()
            onDirectSuccess()
            return result
        } catch {
            guard onDirectFailure(error) else { throw error }
            return try await viaRelay()
        }
    }
}
