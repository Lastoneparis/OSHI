//
//  Censorship2026Tests.swift
//  iOS twin of Android CoverShortConnectionsTest / CoverDnsTest + pull budget + §7 ranged
//  blob reads (docs/CENSORSHIP_2026_THREAT_MODEL.md). The connection tests run against a
//  real loopback HTTP/1.1 server (`CapServer`) that can freeze each TCP connection after a
//  number of server→client bytes, like Russia's TSPU (netem `tspu16k`).
//

import XCTest
import Network
import CryptoKit
@testable import OSHI

// MARK: - Loopback HTTP/1.1 server with a per-connection volume cap

final class CapServer: @unchecked Sendable {
    struct Seen { let connection: Int; let sequence: Int; let method: String; let target: String; let headers: [String: String]; let body: Data }
    typealias Handler = (Seen) -> (status: Int, headers: [String: String], body: Data)

    private let listener: NWListener
    private let queue = DispatchQueue(label: "capserver")
    private let lock = NSLock()
    private var connections: [NWConnection] = []
    private var _seen: [Seen] = []
    private var nextConnection = 0
    let capBytes: Int?
    let handler: Handler
    private(set) var port: UInt16 = 0

    init(capBytes: Int? = nil, handler: @escaping Handler) throws {
        self.capBytes = capBytes
        self.handler = handler
        let params = NWParameters.tcp
        params.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: params)
        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { if case .ready = $0 { ready.signal() } }
        listener.newConnectionHandler = { [weak self] c in self?.accept(c) }
        listener.start(queue: queue)
        _ = ready.wait(timeout: .now() + 5)
        port = listener.port?.rawValue ?? 0
    }

    var seen: [Seen] { lock.withLock { _seen } }

    func stop() {
        listener.cancel()
        lock.withLock { connections }.forEach { $0.cancel() }
    }

    private func accept(_ c: NWConnection) {
        let id: Int = lock.withLock { nextConnection += 1; connections.append(c); return nextConnection }
        c.start(queue: queue)
        var buffer = Data(), sequence = 0, sent = 0, frozen = false
        func loop() {
            c.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, done, error in
                guard let self else { return }
                if let data { buffer.append(data) }
                while !frozen, let sep = buffer.range(of: Data("\r\n\r\n".utf8)) {
                    let head = String(decoding: buffer[buffer.startIndex..<sep.lowerBound], as: UTF8.self)
                    var lines = head.components(separatedBy: "\r\n")
                    let first = lines.removeFirst().split(separator: " ")
                    var headers: [String: String] = [:]
                    for l in lines { if let i = l.firstIndex(of: ":") { headers[l[..<i].lowercased()] = l[l.index(after: i)...].trimmingCharacters(in: .whitespaces) } }
                    let length = Int(headers["content-length"] ?? "0") ?? 0
                    guard buffer.distance(from: sep.upperBound, to: buffer.endIndex) >= length else { break }
                    let body = Data(buffer[sep.upperBound..<buffer.index(sep.upperBound, offsetBy: length)])
                    buffer = Data(buffer[buffer.index(sep.upperBound, offsetBy: length)...])
                    let seen = Seen(connection: id, sequence: sequence, method: String(first[0]), target: String(first[1]), headers: headers, body: body)
                    sequence += 1
                    self.lock.withLock { self._seen.append(seen) }
                    let r = self.handler(seen)
                    if r.status == 0 { c.cancel(); return }   // drop the connection (reset/EOF)
                    var out = "HTTP/1.1 \(r.status) X\r\nContent-Length: \(r.body.count)\r\nConnection: keep-alive\r\n"
                    for (k, v) in r.headers { out += "\(k): \(v)\r\n" }
                    var wire = Data((out + "\r\n").utf8); wire.append(r.body)
                    if let cap = self.capBytes, sent + wire.count > cap {
                        // TSPU: the reply just stops — no RST, no FIN.
                        wire = wire.prefix(max(0, cap - sent)); frozen = true
                    }
                    sent += wire.count
                    if !wire.isEmpty { c.send(content: wire, completion: .contentProcessed { _ in }) }
                }
                if done || error != nil { return }
                loop()
            }
        }
        loop()
    }
}

/// Cover reply body exactly as the relay frames it, but WITHOUT the random bucket spread
/// so its size is deterministic (4 KiB bucket ≈ 5.5 KB on the wire).
private func coverReply(status: Int = 200, body: Data = Data("{}".utf8), headers: [String: String] = ["content-type": "application/json"]) -> Data {
    let inner: [String: Any] = ["v": 1, "status": status, "headers": headers, "body": body.base64EncodedString()]
    let framed = try! CoverTransport.frame(inner, spread: false).base64EncodedString()
    return Data(#"{"events":[{"name":"page_view","params":{"data":"\#(framed)"}}]}"#.utf8)
}

// MARK: - __COVER_SHORT_CONNECTIONS_2026_11_19__

final class CoverShortConnectionsTests: XCTestCase {
    private var server: CapServer!
    private var now: TimeInterval = 1_000_000
    private let session = URLSession(configuration: .ephemeral)

    override func setUp() {
        super.setUp()
        CoverConnectionPolicy.reset()
        CoverConnectionPolicy.setClock { [unowned self] in self.now }
        CoverTransport.timeoutOverrideForTests = 2
    }

    override func tearDown() {
        server?.stop()
        CoverConnectionPolicy.reset(); CoverConnectionPolicy.setClock(nil)
        CoverTransport.timeoutOverrideForTests = nil
        super.tearDown()
    }

    private func send() async throws -> Int {
        var req = URLRequest(url: URL(string: "http://127.0.0.1:\(server.port)/v2/messages")!)
        req.httpMethod = "POST"; req.httpBody = Data("{}".utf8)
        req.setValue("sig", forHTTPHeaderField: "x-oshi-signature")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let (_, r) = try await CoverTransport.data(for: req, session: session, selectedMode: "event")
        return (r as? HTTPURLResponse)?.statusCode ?? -1
    }

    func testHealthyLinkKeepsPooling() async throws {
        server = try CapServer { _ in (200, ["Content-Type": "application/json"], coverReply()) }
        for _ in 0..<3 { let s = try await send(); XCTAssertEqual(s, 200) }
        XCTAssertEqual(server.seen.map(\.sequence), [0, 1, 2])
        XCTAssertEqual(Set(server.seen.map(\.connection)).count, 1)
        XCTAssertFalse(CoverConnectionPolicy.freshConnections())
    }

    /// The server cuts (freezes) EACH connection after 16 KB server→client.
    func testStalledExchangeSwitchesToOneConnectionPerCall() async throws {
        server = try CapServer(capBytes: 16 * 1024) { _ in (200, ["Content-Type": "application/json"], coverReply()) }
        for _ in 0..<2 { let s = try await send(); XCTAssertEqual(s, 200) }   // ~11 KB on one connection
        do { _ = try await send(); XCTFail("a frozen exchange must fail") } catch {}
        XCTAssertTrue(CoverConnectionPolicy.freshConnections())
        for _ in 0..<3 { let s = try await send(); XCTAssertEqual(s, 200) }
        let seen = server.seen
        XCTAssertEqual(seen.prefix(3).map(\.sequence), [0, 1, 2])          // before: pooled (3rd froze)
        XCTAssertEqual(seen.suffix(3).map(\.sequence), [0, 0, 0])          // after: each on its own connection
        XCTAssertEqual(Set(seen.suffix(3).map(\.connection)).count, 3)
    }

    func testRelayHttpErrorIsNotAStall() async throws {
        server = try CapServer { _ in (503, [:], Data()) }
        do { _ = try await send(); XCTFail("503 must throw") } catch {}
        XCTAssertFalse(CoverConnectionPolicy.freshConnections())
    }

    func testWindowExpiresAndFurtherStallsExtendIt() {
        CoverConnectionPolicy.onFailure(URLError(.timedOut))
        XCTAssertTrue(CoverConnectionPolicy.freshConnections())
        now += CoverConnectionPolicy.windowSeconds - 1
        CoverConnectionPolicy.onFailure(URLError(.networkConnectionLost))
        now += CoverConnectionPolicy.windowSeconds - 1
        XCTAssertTrue(CoverConnectionPolicy.freshConnections())
        now += 2
        XCTAssertFalse(CoverConnectionPolicy.freshConnections())
        CoverConnectionPolicy.onFailure(CoverTransport.Failure.transport(502))
        CoverConnectionPolicy.onFailure(URLError(.cancelled))                // pin rejection reads as -999
        CoverConnectionPolicy.onFailure(CoverTransport.Failure.invalidCarrier)
        XCTAssertFalse(CoverConnectionPolicy.freshConnections())
    }

    // MARK: __PULL_BUDGET_2026_11_19__

    func testPullAsksForSmallPagesOnlyWhileCapped() {
        XCTAssertEqual(V2Client.pullPath(encodedKey: "K", afterSeq: 0, budget: nil), "/v2/messages/K")
        XCTAssertEqual(V2Client.pullPath(encodedKey: "K", afterSeq: 7, budget: nil), "/v2/messages/K?after=7")
        XCTAssertEqual(V2Client.pullPath(encodedKey: "K", afterSeq: 0, budget: 2400), "/v2/messages/K?budget=2400")
        XCTAssertEqual(V2Client.pullPath(encodedKey: "K", afterSeq: 7, budget: 2400), "/v2/messages/K?after=7&budget=2400")
        XCTAssertNil(CoverConnectionPolicy.pullBudget())
        CoverConnectionPolicy.onFailure(URLError(.timedOut))
        XCTAssertEqual(CoverConnectionPolicy.pullBudget(), 2400)
    }

    /// The budget page really fits the 4 KiB bucket: a 2400-byte inner reply frames to 4096.
    func testBudgetPageStaysInTheSmallestBucket() throws {
        let inner: [String: Any] = ["v": 1, "status": 200, "headers": ["content-type": "application/json"],
                                    "body": Data(repeating: 0x41, count: CoverConnectionPolicy.smallPullBudget).base64EncodedString()]
        XCTAssertEqual(try CoverTransport.frame(inner, spread: false).count, 4096)
    }
}

// MARK: - __COVER_DNS_FALLBACK_2026_11_19__

final class CoverDnsTests: XCTestCase {
    private let real = "45.67.216.197"
    private let poison = "10.10.34.34"   // Iran's block answer

    private final class Box: @unchecked Sendable { var dohCalls = 0; var now: TimeInterval = 1_000_000 }
    private let box = Box()

    private func dns(_ system: @escaping CoverDns.Lookup, doh: [String]? = nil) -> CoverDns {
        let answer = doh ?? [real]
        let b = box
        return CoverDns(system: system, doh: { _ in b.dohCalls += 1; return answer }, clock: { b.now })
    }
    private let host = "oshi-messenger.com"

    func testHonestResolverIsUsedAndDohNeverCalled() async {
        let r = await dns({ [self] _ in [real] }).resolve(host)
        XCTAssertEqual(r, .system); XCTAssertEqual(box.dohCalls, 0)
    }
    func testIranStylePoisonFallsBackToDoh() async {
        let r = await dns({ [self] _ in [poison] }).resolve(host)
        XCTAssertEqual(r, .override([real])); XCTAssertEqual(box.dohCalls, 1)
    }
    func testNxdomainFallsBackToDoh() async {
        let r = await dns({ _ in throw URLError(.cannotFindHost) }).resolve(host)
        XCTAssertEqual(r, .override([real]))
    }
    func testMixedAnswerKeepsOnlyRoutableAddresses() async {
        let r = await dns({ [self] _ in [poison, real] }).resolve(host)
        XCTAssertEqual(r, .override([real])); XCTAssertEqual(box.dohCalls, 0)
    }
    func testDohFailureLeavesTheSystemOutcomeUnchanged() async {
        let a = await dns({ [self] _ in [poison] }, doh: []).resolve(host)
        XCTAssertEqual(a, .system)
        let b = await dns({ _ in throw URLError(.cannotFindHost) }, doh: []).resolve(host)
        XCTAssertEqual(b, .system)
    }
    func testLocalNamesAreNeverSecondGuessed() async {
        let r = await dns({ _ in ["127.0.0.1"] }).resolve("localhost")
        XCTAssertEqual(r, .system); XCTAssertEqual(box.dohCalls, 0)
        XCTAssertTrue(CoverDns.isLocalName("relay.test")); XCTAssertTrue(CoverDns.isLocalName("relay.invalid"))
        XCTAssertFalse(CoverDns.isLocalName("oshi-messenger.com"))
    }
    func testNonRoutableRanges() {
        for ip in ["10.10.34.35", "192.168.1.1", "172.16.0.1", "127.0.0.1", "0.0.0.0", "169.254.1.1", "100.64.0.1", "::1", "fd00::1", "fe80::1", "::", "::ffff:10.0.0.1", "224.0.0.1"] {
            XCTAssertTrue(CoverDns.isNonRoutable(ip), ip)
        }
        for ip in ["45.67.216.197", "1.1.1.1", "100.128.0.1", "172.32.0.1", "2a02:c207:2284:3891::1", "64:ff9b::2d43:d8c5"] {
            XCTAssertFalse(CoverDns.isNonRoutable(ip), ip)
        }
    }
    func testParsesCloudflareAndGoogleJson() {
        let cf = #"{"Status":0,"TC":false,"Question":[{"name":"oshi-messenger.com","type":1}],"Answer":[{"name":"oshi-messenger.com","type":5,"TTL":60,"data":"x.example."},{"name":"oshi-messenger.com","type":1,"TTL":300,"data":"45.67.216.197"}]}"#
        XCTAssertEqual(CoverDns.parseDnsJson(Data(cf.utf8)), [real])
        XCTAssertTrue(CoverDns.parseDnsJson(Data(#"{"Status":3}"#.utf8)).isEmpty)
        XCTAssertTrue(CoverDns.parseDnsJson(Data(#"{"Status":0,"Answer":[{"type":1,"data":"evil.example"}]}"#.utf8)).isEmpty)
        XCTAssertEqual(CoverDns.parseDnsJson(Data(#"{"Status":0,"Answer":[{"type":28,"data":"2a02:c207:2284:3891::1"}]}"#.utf8)).count, 1)
    }
    func testDohAnswerIsReusedThenRefreshed() async {
        let d = dns({ [self] _ in [poison] })
        for _ in 0..<3 { let r = await d.resolve(host); XCTAssertEqual(r, .override([real])) }
        XCTAssertEqual(box.dohCalls, 1)
        box.now += CoverDns.positiveTTL + 1
        _ = await d.resolve(host); XCTAssertEqual(box.dohCalls, 2)
    }
    func testDeadDohPathCostsOneAttemptPerMinute() async {
        let d = dns({ _ in throw URLError(.cannotFindHost) }, doh: [])
        for _ in 0..<5 { let r = await d.resolve(host); XCTAssertEqual(r, .system) }
        XCTAssertEqual(box.dohCalls, 1)
        box.now += CoverDns.negativeTTL + 1
        _ = await d.resolve(host); XCTAssertEqual(box.dohCalls, 2)
    }

    func testGuardedHostsAreOnlyTheV2Hosts() {
        XCTAssertTrue(CoverDns.isGuarded("oshi-messenger.com"))
        XCTAssertFalse(CoverDns.isGuarded("ipfs.io"))
    }

    /// End to end: the name does not resolve; the DoH answer is used, the request goes to
    /// that address on its own connection, and the real name is sent as Host.
    func testOverriddenRequestReachesTheDohAddressWithTheRealHost() async throws {
        let server = try CapServer { seen in (200, ["Content-Type": "text/plain"], Data("hello \(seen.target)".utf8)) }
        defer { server.stop() }
        let name = "blocked-by-dns.oshi-cen-test.org"
        CoverDns.extraGuardedHostsForTests = [name]
        defer { CoverDns.extraGuardedHostsForTests = [] }
        var req = URLRequest(url: URL(string: "http://\(name):\(server.port)/v2/messages?after=3")!)
        req.httpMethod = "POST"; req.httpBody = Data("{\"a\":1}".utf8); req.timeoutInterval = 5
        let (data, response) = try await CoverNet.data(for: req, session: URLSession(configuration: .ephemeral), cover: false,
                                                       resolve: { _ in .override(["127.0.0.1"]) })
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        XCTAssertEqual(String(decoding: data, as: UTF8.self), "hello /v2/messages?after=3")
        XCTAssertEqual(server.seen.first?.headers["host"], "\(name):\(server.port)")
        XCTAssertEqual(server.seen.first?.body, Data("{\"a\":1}".utf8))
        XCTAssertEqual(response.url?.host, name)
    }

    func testOneShotParsesChunkedAndContentLength() throws {
        let url = URL(string: "https://oshi-messenger.com/x")!
        let chunked = Data("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Type: a/b\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n".utf8)
        XCTAssertTrue(OneShotHTTP.isComplete(chunked))
        let (b1, r1) = try OneShotHTTP.parse(chunked, url: url)
        XCTAssertEqual(String(decoding: b1, as: UTF8.self), "hello world"); XCTAssertEqual(r1.statusCode, 200)
        let cl = Data("HTTP/1.1 206 Partial\r\nContent-Length: 3\r\nContent-Range: bytes 0-2/9\r\n\r\nabc".utf8)
        XCTAssertTrue(OneShotHTTP.isComplete(cl))
        let (b2, r2) = try OneShotHTTP.parse(cl, url: url)
        XCTAssertEqual(b2, Data("abc".utf8)); XCTAssertEqual(r2.value(forHTTPHeaderField: "content-range"), "bytes 0-2/9")
        XCTAssertFalse(OneShotHTTP.isComplete(Data("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nabc".utf8)))
        XCTAssertThrowsError(try OneShotHTTP.parse(Data("<html>".utf8), url: url))
    }
}

// MARK: - __BLOB_RANGE_2026_11_19__ (threat model §7)

final class BlobRangeTests: XCTestCase {
    private var server: CapServer!
    private let chunk = Data((0..<60_000).map { UInt8($0 % 251) })
    private var savedCover: Any?

    override func setUp() {
        super.setUp()
        CoverConnectionPolicy.reset(); CoverConnectionPolicy.setClock(nil)
        savedCover = UserDefaults.standard.object(forKey: "covertChannelEnabled")
        UserDefaults.standard.set(false, forKey: "covertChannelEnabled")   // direct path
    }

    override func tearDown() {
        server?.stop()
        CoverConnectionPolicy.reset()
        if let savedCover { UserDefaults.standard.set(savedCover, forKey: "covertChannelEnabled") }
        else { UserDefaults.standard.removeObject(forKey: "covertChannelEnabled") }
        super.tearDown()
    }

    private func client() -> V2Client {
        V2Client(config: V2Client.Config(baseURL: URL(string: "http://127.0.0.1:\(server.port)")!, requestTimeout: 2, maxRetryAttempts: 1),
                 crypto: V2EphemeralCrypto(), session: URLSession(configuration: .ephemeral))
    }

    /// §7.2 server: 206 + Content-Range for `offset/length` (length clamped to ≥1024), whole chunk otherwise.
    private func rangedServer(cap: Int?, honourRanges: Bool = true, lie: Bool = false) throws -> CapServer {
        let chunk = self.chunk
        return try CapServer(capBytes: cap) { seen in
            let comps = URLComponents(string: "http://x" + seen.target)!
            let q = Dictionary(uniqueKeysWithValues: (comps.queryItems ?? []).map { ($0.name, $0.value ?? "") })
            guard honourRanges, let o = Int(q["offset"] ?? ""), let n = Int(q["length"] ?? "") else {
                return (200, ["Content-Type": "application/octet-stream"], chunk)
            }
            let len = min(max(n, 1024), 1_048_576)
            let e = min(o + len, chunk.count) - 1
            let start = lie ? o + 1 : o
            return (206, ["Content-Type": "application/octet-stream", "Content-Range": "bytes \(start)-\(e)/\(chunk.count)"],
                    chunk.subdata(in: o..<(e + 1)))
        }
    }

    func testContentRangeParsing() {
        XCTAssertEqual(V2Client.parseContentRange("bytes 0-2687/60000")?.end, 2687)
        XCTAssertEqual(V2Client.parseContentRange("BYTES 10-19/20")?.size, 20)
        XCTAssertNil(V2Client.parseContentRange("bytes */60000"))
        XCTAssertNil(V2Client.parseContentRange("bytes 5-4/10"))
        XCTAssertNil(V2Client.parseContentRange("bytes 0-10/10"))
        XCTAssertNil(V2Client.parseContentRange(nil))
    }

    func testUncappedNetworkReadsTheWholeChunkUnchanged() async throws {
        server = try rangedServer(cap: nil)
        let got = try await client().downloadChunk(blobId: "b1", index: 0)
        XCTAssertEqual(got, chunk)
        XCTAssertEqual(server.seen.map(\.target), ["/v2/blobs/b1/chunk/0"])
    }

    /// A 60 KB chunk behind a 16 KB-per-connection freeze: the whole read stalls, then the
    /// chunk arrives in 12 KiB parts, each on its own connection, each freshly signed.
    func testCappedNetworkFallsBackToRangedPartsOnFreshConnections() async throws {
        server = try rangedServer(cap: 16 * 1024)
        let got = try await client().downloadChunk(blobId: "b1", index: 0)
        XCTAssertEqual(got, chunk)
        let parts = server.seen.dropFirst()
        XCTAssertEqual(server.seen.first?.target, "/v2/blobs/b1/chunk/0")
        XCTAssertEqual(parts.map(\.target), stride(from: 0, to: chunk.count, by: 12288).map { "/v2/blobs/b1/chunk/0?offset=\($0)&length=12288" })
        XCTAssertTrue(parts.allSatisfy { $0.sequence == 0 })
        XCTAssertEqual(Set(parts.map(\.connection)).count, parts.count)
        XCTAssertEqual(Set(parts.compactMap { $0.headers["x-oshi-signature"] }).count, parts.count)   // each part signed anew
        XCTAssertTrue(CoverConnectionPolicy.freshConnections())
    }

    func testOldServerAnswering200IsTheWholeChunk() async throws {
        server = try rangedServer(cap: nil, honourRanges: false)
        CoverConnectionPolicy.onFailure(URLError(.timedOut))
        let got = try await client().downloadChunk(blobId: "b1", index: 2)
        XCTAssertEqual(got, chunk)
        XCTAssertEqual(server.seen.map(\.target), ["/v2/blobs/b1/chunk/2?offset=0&length=12288"])
    }

    func testA206ThatDoesNotStartAtTheAskedOffsetFails() async throws {
        server = try rangedServer(cap: nil, lie: true)
        CoverConnectionPolicy.onFailure(URLError(.timedOut))
        do { _ = try await client().downloadChunk(blobId: "b1", index: 0); XCTFail("bad Content-Range accepted") } catch {}
    }

    func testCoverPartLengthKeepsThe206RecordInTheSmallestBucket() throws {
        let inner: [String: Any] = ["v": 1, "status": 206,
                                    "headers": ["content-type": "application/octet-stream", "content-range": "bytes 1048576-1051263/2097152", "content-length": "2688"],
                                    "body": Data(count: V2Client.rangedPartLengthCover).base64EncodedString()]
        XCTAssertEqual(try CoverTransport.frame(inner, spread: false).count, 4096)
    }
}

// MARK: - __RELAY_FALLBACK_2026_11_19__ (threat model §9; Android RelayFallbackTest)

@available(iOS 17.0, macOS 14.0, *)
final class RelayFallbackTests: XCTestCase {
    private var direct: CapServer!
    private var relay: CapServer!
    private var now: TimeInterval = 1_000_000
    private var savedCover: Any?
    private let session = URLSession(configuration: .ephemeral)
    private var relayed: Int { relay.seen.count }

    // RFC 9458 Appendix A key (ObliviousHTTPTests), playing the gateway.
    private static let sk = "3c168975674b2fa8e465970b79c8dcf09f1c741626480bd4c6162fc5b6a98e1a"
    private static let keyConfig = "01002031e1f05a740102115220e9af918f738674aec95f54db6e04eb705aae8e79815500080001000100010003"

    override func setUpWithError() throws {
        try super.setUpWithError()
        savedCover = UserDefaults.standard.object(forKey: "covertChannelEnabled")
        UserDefaults.standard.set(false, forKey: "covertChannelEnabled")
        CoverConnectionPolicy.reset(); CoverConnectionPolicy.setClock { [unowned self] in self.now }
        let cfg = try OHTTPKeyConfig.parse(Data(ohttpHex: Self.keyConfig)!)
        relay = try CapServer { seen in
            let body = seen.body
            let enc = body.subdata(in: 7..<39)
            let sk = try! Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(ohttpHex: Self.sk)!)
            var r = try! HPKE.Recipient(privateKey: sk, ciphersuite: OHTTP.ciphersuite, info: cfg.requestInfo, encapsulatedKey: enc)
            let inner = try! BinaryHTTP.decodeRequest(r.open(body.subdata(in: 39..<body.count)))
            XCTAssertEqual(inner.authority, "oshi-messenger.com")
            let secret = try! r.exportSecret(context: Data(OHTTP.responseLabel.utf8), outputByteCount: 16).withUnsafeBytes { Data($0) }
            let reply = BinaryHTTP.Response(status: 200, headers: [("content-type", "application/json")],
                                            content: Data(#"{"via":"relay","path":"\#(inner.path)"}"#.utf8))
            let sealed = try! OHTTP.encapsulateResponse(BinaryHTTP.encode(reply), secret: secret, enc: enc, responseNonce: Data(count: 16))
            return (200, ["Content-Type": "message/ohttp-res"], sealed)
        }
        RelayFallback.overrideForTests(.init(relayURL: URL(string: "http://127.0.0.1:\(relay.port)/api/v1/batch")!, keyConfig: cfg,
                                             directOrigin: URL(string: "http://127.0.0.1:\(blockedPort)")!))
    }

    override func tearDown() {
        direct?.stop(); relay?.stop()
        RelayFallback.overrideForTests(nil)
        CoverConnectionPolicy.reset(); CoverConnectionPolicy.setClock(nil)
        if let savedCover { UserDefaults.standard.set(savedCover, forKey: "covertChannelEnabled") }
        else { UserDefaults.standard.removeObject(forKey: "covertChannelEnabled") }
        super.tearDown()
    }

    /// Port of the "direct" origin: a CapServer when started, else a port nothing listens on.
    private var blockedPort: UInt16 = 9

    private func useDirect(_ handler: @escaping CapServer.Handler) throws {
        direct = try CapServer(handler: handler)
        blockedPort = direct.port
        let t = RelayFallback.transport()!
        RelayFallback.overrideForTests(.init(relayURL: t.relayURL, keyConfig: t.keyConfig, directOrigin: URL(string: "http://127.0.0.1:\(blockedPort)")!))
    }

    private func call(_ method: String = "GET", path: String = "/v2/messages/abc?after=0") async throws -> String {
        var req = URLRequest(url: URL(string: "http://127.0.0.1:\(blockedPort)\(path)")!)
        req.httpMethod = method; req.timeoutInterval = 3
        req.setValue("s", forHTTPHeaderField: "x-oshi-signature")
        if method == "POST" { req.httpBody = Data("{}".utf8); req.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let (data, _) = try await V2Transport.data(for: req, session: session)
        return String(decoding: data, as: UTF8.self)
    }

    func testHealthyDirectNeverTouchesTheRelay() async throws {
        try useDirect { _ in (200, ["Content-Type": "application/json"], Data(#"{"via":"direct"}"#.utf8)) }
        for _ in 0..<3 { let s = try await call(); XCTAssertTrue(s.contains("direct")) }
        XCTAssertEqual(relayed, 0); XCTAssertFalse(RelayFallback.active())
    }

    func testOneTransientResetDoesNotMoveTraffic() async throws {
        var n = 0
        // POST: URLSession silently replays an idempotent GET whose connection was lost,
        // which would hide the reset from the transport (the same holds in production).
        try useDirect { _ in n += 1; return n == 2 ? (200, [:], Data(#"{"via":"direct"}"#.utf8)) : (0, [:], Data()) }
        do { _ = try await call("POST", path: "/v2/messages"); XCTFail() } catch {}
        let s = try await call("POST", path: "/v2/messages"); XCTAssertTrue(s.contains("direct"))
        do { _ = try await call("POST", path: "/v2/messages"); XCTFail() } catch {}       // streak restarted by the success
        XCTAssertEqual(relayed, 0); XCTAssertFalse(RelayFallback.active())
    }

    func testABlockedHostMovesToTheRelayOnTheSecondFailureAndRetriesThatCallThroughIt() async throws {
        try useDirect { _ in (0, [:], Data()) }               // every direct exchange is reset
        do { _ = try await call("POST", path: "/v2/messages"); XCTFail("first failure is surfaced") } catch {}
        let second = try await call("POST", path: "/v2/messages")
        XCTAssertTrue(second.contains("relay"), "2nd failure: the same call is answered by the relay")
        XCTAssertTrue(RelayFallback.active())
        let before = direct.seen.count
        let third = try await call()
        XCTAssertTrue(third.contains("relay") && third.contains("/v2/messages/abc?after=0"))
        XCTAssertEqual(direct.seen.count, before, "while active, direct is not tried")
        now += RelayFallback.windowSeconds + 1
        XCTAssertFalse(RelayFallback.active())
        do { _ = try await call("POST", path: "/v2/messages"); XCTFail() } catch {}        // the window ended: direct probed again
        XCTAssertEqual(direct.seen.count, before + 1)
    }

    func testConnectPhaseFailuresActivateAtOnce() async throws {
        // blockedPort 9: nothing listens -> connection refused -> relay on the FIRST call.
        let s = try await call()
        XCTAssertTrue(s.contains("relay")); XCTAssertTrue(RelayFallback.active())
        XCTAssertTrue(RelayFallback.isConnectPhase(URLError(.cannotFindHost)))
        XCTAssertTrue(RelayFallback.isConnectPhase(URLError(.secureConnectionFailed)))
        XCTAssertFalse(RelayFallback.isConnectPhase(URLError(.timedOut)))
    }

    func testHttpStatusesAndCoverDecodeErrorsNeverCount() async throws {
        try useDirect { _ in (503, ["Content-Type": "application/json"], Data(#"{"error":"busy"}"#.utf8)) }
        for _ in 0..<3 { _ = try? await call() }
        XCTAssertFalse(RelayFallback.onDirectFailure(CoverTransport.Failure.transport(403)))
        XCTAssertFalse(RelayFallback.onDirectFailure(CoverTransport.Failure.invalidCarrier))
        XCTAssertEqual(relayed, 0); XCTAssertFalse(RelayFallback.active())
    }

    func testTheBuiltInRelaysAreTheDeployedOnesInOrderAndNotThePrivacyRelayDefault() {
        XCTAssertEqual(RelayFallback.fallbackRelayURLs.map(\.absoluteString),
                       ["https://foodies.agency/api/v1/batch", "https://static-84-75-255-200.sslip.io/api/v1/batch"])
        XCTAssertEqual(OHTTPSettings.relayURLString, "", "the OSHI-operated relay must not become the Privacy relay default")
        XCTAssertTrue(CoverDns.isGuarded("foodies.agency"))
        XCTAssertTrue(CoverDns.isGuarded("static-84-75-255-200.sslip.io"))
        XCTAssertFalse(CoverDns.isGuarded("84-75-255-200.sslip.io"))
    }

    // __RELAY_LIST_CH1_2026_11_19__ ordered relay list: next relay on failure, sticky preference.
    private func twoRelays(firstURL: URL) throws {
        let t = RelayFallback.transport()!
        RelayFallback.overrideAllForTests([
            .init(relayURL: firstURL, keyConfig: t.keyConfig, directOrigin: t.directOrigin),
            .init(relayURL: t.relayURL, keyConfig: t.keyConfig, directOrigin: t.directOrigin),
        ])
    }

    func testABlockedFirstRelayFallsThroughToTheSecondInTheSameCallAndTheSecondStaysPreferred() async throws {
        try twoRelays(firstURL: URL(string: "http://127.0.0.1:7/api/v1/batch")!)   // nothing listens: refused
        let s = try await call()                                               // direct refused (port 9) -> relays
        XCTAssertTrue(s.contains("relay")); XCTAssertEqual(relayed, 1)
        XCTAssertEqual(RelayFallback.transport()?.relayURL.port, Int(relay.port), "the relay that answered is tried first")
        let s2 = try await call()
        XCTAssertTrue(s2.contains("relay")); XCTAssertEqual(relayed, 2)
    }

    func testAllRelaysUnreachableSurfacesTheErrorAndLetsTheNextCallProbeDirect() async throws {
        let t = RelayFallback.transport()!
        RelayFallback.overrideAllForTests([
            .init(relayURL: URL(string: "http://127.0.0.1:7/a")!, keyConfig: t.keyConfig, directOrigin: t.directOrigin),
            .init(relayURL: URL(string: "http://127.0.0.1:5/b")!, keyConfig: t.keyConfig, directOrigin: t.directOrigin),
        ])
        do { _ = try await call(); XCTFail("both relays refused") } catch {}
        XCTAssertFalse(RelayFallback.active(), "connect-phase failure of every relay ends the window")
        XCTAssertEqual(relayed, 0)
    }
}
