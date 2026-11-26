//
//  ChannelWebTests.swift
//  OSHITests
//
//  __CHANNEL_WEB_2026_11_26__ Public channel web preview, client side
//  (ServerVPS/v2/PUBLIC_CHANNEL_WEB_SPEC.md): the §10 signing vectors, the app →
//  web markup conversion, links / embed snippet / deep-link parsing, the outbox
//  (coalescing, retry with backoff, unknown-media re-upload, delete/disable) and
//  the postId → n persistence — all against fakes, no network.
//
//  The optional live test at the bottom publishes a real signed post to a LOCAL
//  v2 server when `OSHI_CHANNEL_WEB_IT_URL` is set (see its comment).
//

import XCTest
import CryptoKit
@testable import OSHI

final class ChannelWebTests: XCTestCase {

    // MARK: - §10 signing vectors

    private let seedHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    private let vectorPub = "A6EHv/POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg="
    private let publishBody = #"{"text":"**Weekly position** — long BTC, see https://example.org/x","media":["3f2a9c0d4e5b6a7182930a1b2c3d4e5f"],"createdAt":1795708790000}"#
    private let publishPath = "/v2/c/swiss_quant/posts/7F3C2A10-1B2C-4D5E-8F90-A1B2C3D4E5F6"

    private func seedKey() throws -> Curve25519.Signing.PrivateKey {
        var bytes = [UInt8]()
        var i = seedHex.startIndex
        while i < seedHex.endIndex {
            let j = seedHex.index(i, offsetBy: 2)
            bytes.append(UInt8(seedHex[i..<j], radix: 16)!)
            i = j
        }
        return try Curve25519.Signing.PrivateKey(rawRepresentation: Data(bytes))
    }

    private func verify(_ sigB64: String, _ canonical: String) throws -> Bool {
        let pub = try Curve25519.Signing.PublicKey(rawRepresentation: XCTUnwrap(Data(base64Encoded: vectorPub)))
        return pub.isValidSignature(try XCTUnwrap(Data(base64Encoded: sigB64)), for: Data(canonical.utf8))
    }

    func testSeedGivesTheVectorPublicKey() throws {
        XCTAssertEqual(try seedKey().publicKey.rawRepresentation.base64EncodedString(), vectorPub)
    }

    func testPublishVectorCanonicalStringAndSignature() throws {
        XCTAssertEqual(V2Client.bodySHA256Hex(Data(publishBody.utf8)),
                       "f19a8868d211b8769176a033d27ae6656c0f0a0fca56380915b70393560169e4")
        let canonical = V2Client.canonicalRequestString(method: "PUT", path: publishPath,
                                                        body: Data(publishBody.utf8), timestamp: "1795708800001")
        XCTAssertEqual(canonical, """
        PUT
        /v2/c/swiss_quant/posts/7F3C2A10-1B2C-4D5E-8F90-A1B2C3D4E5F6
        f19a8868d211b8769176a033d27ae6656c0f0a0fca56380915b70393560169e4
        1795708800001
        """)
        XCTAssertTrue(try verify("HQW0P7TPX+Z81sUL1kU69gJ/ihOu6z1gx4OLyQZKTEJkZfaGdDXEFkB8LGNHC23nfepHUk7T+iBLY31Oa0RVBA==", canonical))
        // Our signer (CryptoKit Ed25519, randomised) produces a signature the server's
        // verifier accepts over the same canonical bytes.
        let mine = try seedKey().signature(for: Data(canonical.utf8)).base64EncodedString()
        XCTAssertTrue(try verify(mine, canonical))
    }

    func testEnableDeleteAdminVectors() throws {
        let enable = #"{"kind":"channel","title":"Swiss Quant Notes","searchIndexing":false}"#
        XCTAssertEqual(V2Client.bodySHA256Hex(Data(enable.utf8)), "29e23ba1db701ad6da1c70432e0f7907d32d66d8bf80c8f6dadcc3185472757a")
        XCTAssertTrue(try verify("TwhiDsrdmc5I/PCFEiZDQ4eIt4GtJFRx4Kh3PDJobEYRyId1asVN+4cXiTov6AlyJ7ORtDJIpmh/DmqPlhC8CA==",
                                 V2Client.canonicalRequestString(method: "PUT", path: "/v2/c/swiss_quant",
                                                                 body: Data(enable.utf8), timestamp: "1795708800000")))
        XCTAssertEqual(V2Client.bodySHA256Hex(Data()), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        XCTAssertTrue(try verify("SiCYwd57OUWnVFabd8ZEGJzIcN/J+sm57joNAg2H9Y2zZJ/DXSXvCHgiLDeW2y7NpQGNeK9BRiQ4E8pWkyr1Bw==",
                                 V2Client.canonicalRequestString(method: "DELETE", path: publishPath,
                                                                 body: Data(), timestamp: "1795708800002")))
        XCTAssertTrue(try verify("FksWcYVBXrETLgzFK9KGtdZZye/VE2JT+nPFZE3ZFrkbZ9OlNcBGIsXbpa/0lyQAgARiwpDS2zZzV1R2+0TwAw==",
                                 V2Client.canonicalRequestString(method: "GET", path: "/v2/c/swiss_quant/admin",
                                                                 body: Data(), timestamp: "1795708800003")))
    }

    func testPostBodyIsValidJSONWithRawUnicodeAndSlashes() throws {
        let body = ChannelWebContent.postBody(text: "**W** — see https://example.org/x", media: ["ab"], createdAtMs: 1795708790000)
        let s = try XCTUnwrap(String(data: body, encoding: .utf8))
        XCTAssertTrue(s.contains("https://example.org/x"), "no \\/ escaping")
        XCTAssertTrue(s.contains("—"))
        let o = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(o["createdAt"] as? Int64, 1795708790000)
        XCTAssertEqual(o["media"] as? [String], ["ab"])
    }

    func testProfileBodyFieldsAndAvatarTriState() throws {
        func obj(_ d: Data) throws -> [String: Any] { try XCTUnwrap(JSONSerialization.jsonObject(with: d) as? [String: Any]) }
        let a = try obj(ChannelWebContent.profileBody(title: "Swiss\nQuant", description: "*Daily* notes", searchIndexing: true))
        XCTAssertEqual(a["kind"] as? String, "channel")
        XCTAssertEqual(a["title"] as? String, "Swiss Quant", "one line")
        XCTAssertEqual(a["description"] as? String, "**Daily** notes", "app bold → web bold")
        XCTAssertEqual(a["searchIndexing"] as? Bool, true)
        XCTAssertNil(a["avatarMediaId"], "absent = unchanged")
        let b = try obj(ChannelWebContent.profileBody(title: "x", description: nil, searchIndexing: false, avatar: .some(nil)))
        XCTAssertTrue(b["avatarMediaId"] is NSNull, "null = remove")
        XCTAssertTrue(b["description"] is NSNull)
        let c = try obj(ChannelWebContent.profileBody(title: String(repeating: "é", count: 100), description: nil,
                                                      searchIndexing: false, avatar: .some("m1")))
        XCTAssertEqual(c["avatarMediaId"] as? String, "m1")
        XCTAssertEqual((c["title"] as? String)?.unicodeScalars.count, 64)
    }

    // MARK: - Formatting conversion (app markup → §5)

    func testWebMarkupConversion() {
        let f = ChannelWebText.webMarkup(fromApp:)
        XCTAssertEqual(f("**bold** and _italic_"), "**bold** and _italic_")
        XCTAssertEqual(f("*bold* on iOS"), "**bold** on iOS", "iOS single * is bold; on the web it would be italic")
        XCTAssertEqual(f("__under__ line"), "under line", "no underline on the web: text kept, markers dropped")
        XCTAssertEqual(f("~gone~ and `code`"), "gone and code")
        XCTAssertEqual(f("**_both_**"), "**_both_**")
        XCTAssertEqual(f("snake_case_name stays, 2*3*4 stays"), "snake_case_name stays, 2*3*4 stays")
        XCTAssertEqual(f("see https://x.org/a_b_c."), "see https://x.org/a_b_c.")
        XCTAssertEqual(f("line1\nline2"), "line1\nline2")
        XCTAssertEqual(f("<b>\"hi\"</b> & co"), "<b>\"hi\"</b> & co", "escaping is the server's job")
        XCTAssertEqual(f("a **b** c *d* e _f_"), "a **b** c **d** e _f_")
    }

    func testPublishableTextFiltersPayloadsAndUnwrapsEnvelopes() {
        let p = ChannelWebText.publishableText
        XCTAssertNil(p(nil))
        XCTAssertNil(p("   \n "))
        XCTAssertNil(p("📊POLL📊{\"q\":\"x\"}"), "a poll is not a web post")
        XCTAssertNil(p("📍LOCATION📍{}"))
        XCTAssertNil(p("🔥REACTION🔥{}"))
        XCTAssertNil(p("{\"type\":\"media\",\"content\":\"AAAA\"}"))
        let reply = MessageWithReply(content: "*Answer*", replyTo: ReplyMessage(
            originalMessageId: "x", originalSenderKey: "k", originalText: "q", originalTimestamp: Date(), originalMediaType: nil))
        XCTAssertEqual(p(reply.toMessageString()), "**Answer**")
        XCTAssertEqual(p("  hello  "), "hello")
        let long = String(repeating: "a", count: 5000)
        let t = p(long)
        XCTAssertEqual(t?.unicodeScalars.count, 4096)
        XCTAssertTrue(t?.hasSuffix("…") ?? false)
    }

    func testTruncateNeverSplitsACharacter() {
        let flag = "🇨🇭"   // 2 scalars
        let s = String(repeating: flag, count: 10)
        let t = ChannelWebText.truncate(s, maxCodePoints: 6)
        XCTAssertLessThanOrEqual(t.unicodeScalars.count, 6)
        XCTAssertEqual(t, flag + flag + "…")
    }

    // MARK: - Links, embed snippet, deep links

    func testLinksAndEmbedSnippet() {
        XCTAssertEqual(ChannelWeb.channelURL(handle: "swiss_quant"), "https://oshi-messenger.com/c/swiss_quant")
        XCTAssertEqual(ChannelWeb.postURL(handle: "swiss_quant", n: 7), "https://oshi-messenger.com/c/swiss_quant/7")
        XCTAssertEqual(ChannelWeb.appLink(handle: "swiss_quant", n: 7), "oshi://channel?h=swiss_quant&n=7")
        XCTAssertEqual(ChannelWeb.embedSnippet(handle: "swiss_quant", n: 7),
                       #"<script async src="https://oshi-messenger.com/js/oshi-widget.js?1" data-oshi-post="swiss_quant/7" data-width="100%"></script>"#)
        XCTAssertEqual(ChannelWeb.embedSnippet(handle: "swiss_quant", n: 7, dark: true),
                       #"<script async src="https://oshi-messenger.com/js/oshi-widget.js?1" data-oshi-post="swiss_quant/7" data-width="100%" data-dark="1"></script>"#)
        XCTAssertTrue(ChannelWeb.embedSnippet(handle: "a_b_c_d", n: 1, dark: false).contains(#"data-dark="0""#))
    }

    func testPostIdRule() {
        XCTAssertTrue(ChannelWeb.isValidPostId("7F3C2A10-1B2C-4D5E-8F90-A1B2C3D4E5F6"))
        XCTAssertTrue(ChannelWeb.isValidPostId("a.b_c:d-e"))
        XCTAssertFalse(ChannelWeb.isValidPostId(""))
        XCTAssertFalse(ChannelWeb.isValidPostId("a/b"))
        XCTAssertFalse(ChannelWeb.isValidPostId("é"))
        XCTAssertFalse(ChannelWeb.isValidPostId(String(repeating: "a", count: 129)))
        let id = UUID()
        XCTAssertEqual(ChannelWeb.postId(id), id.uuidString.uppercased())
    }

    func testDeepLinkParsing() {
        func p(_ s: String) -> ChannelWeb.Link? { ChannelWeb.parseLink(URL(string: s)!) }
        XCTAssertEqual(p("oshi://channel?h=swiss_quant&n=7"), .init(handle: "swiss_quant", n: 7))
        XCTAssertEqual(p("oshi://channel?h=Swiss_Quant"), .init(handle: "swiss_quant", n: nil))
        XCTAssertEqual(p("https://oshi-messenger.com/c/swiss_quant/7"), .init(handle: "swiss_quant", n: 7))
        XCTAssertEqual(p("https://oshi-messenger.com/c/swiss_quant"), .init(handle: "swiss_quant", n: nil))
        XCTAssertEqual(p("https://www.oshi-messenger.org/c/swiss_quant/12?embed=1"), .init(handle: "swiss_quant", n: 12))
        XCTAssertEqual(p("https://oshi-messenger.com/c/swiss_quant/abc"), .init(handle: "swiss_quant", n: nil), "bad n: open the channel")
        XCTAssertEqual(p("https://oshi-messenger.com/c/swiss_quant/0"), .init(handle: "swiss_quant", n: nil))
        XCTAssertNil(p("https://oshi-messenger.com/c/swiss_quant/media/3f2a"))
        XCTAssertNil(p("https://oshi-messenger.com/g/swiss_quant"))
        XCTAssertNil(p("https://evil.example/c/swiss_quant/7"))
        XCTAssertNil(p("http://oshi-messenger.com/c/swiss_quant/7"))
        XCTAssertNil(p("oshi://channel?n=7"))
        XCTAssertNil(p("oshi://group/join?h=swiss_quant"))
        // The group-link parser must not claim channel links (and vice versa).
        XCTAssertNil(GroupPublicLink.rawHandle(from: URL(string: "https://oshi-messenger.com/c/swiss_quant/7")!))
    }

    // MARK: - Outbox rules (pure)

    private func job(_ k: ChannelWebState.Job.Kind, _ pid: String? = nil, h: String = "swiss_quant") -> ChannelWebState.Job {
        .init(kind: k, groupId: UUID(uuidString: "5F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9")!, handle: h, postId: pid)
    }

    func testQueueCoalescing() {
        var s = ChannelWebState()
        s.enqueue(job(.publish, "A"))
        s.enqueue(job(.publish, "A"))
        XCTAssertEqual(s.jobs.count, 1, "one publish per post: it reads the latest content when it runs")
        s.enqueue(job(.delete, "A"))
        XCTAssertEqual(s.jobs.map(\.kind), [.delete], "delete cancels the pending publish")
        s.enqueue(job(.publish, "B"))
        s.enqueue(job(.profile))
        s.enqueue(job(.publish, "C", h: "other_chan"))
        s.enqueue(job(.disable))
        XCTAssertEqual(s.jobs.map(\.kind), [.publish, .disable], "disable cancels every write of its handle only")
        s.enqueue(job(.profile))
        XCTAssertEqual(s.jobs.map(\.kind), [.publish, .disable, .profile], "a re-enable never cancels the purge before it")
    }

    func testOutcomeTable() {
        let c = ChannelWebOutcome.classify
        XCTAssertEqual(c(200, nil, .publish), .done)
        XCTAssertEqual(c(400, "unknown-media", .publish), .reuploadMedia)
        XCTAssertEqual(c(400, "text-too-long", .publish), .drop)
        XCTAssertEqual(c(401, "unauthorized", .publish), .retry)
        XCTAssertEqual(c(403, "signer-not-a-controller", .publish), .drop)
        XCTAssertEqual(c(404, "unknown-post", .delete), .done)
        XCTAssertEqual(c(404, "unknown-handle", .disable), .done)
        XCTAssertEqual(c(404, "unknown-handle", .publish), .drop)
        XCTAssertEqual(c(409, "web-preview-off", .publish), .drop)
        XCTAssertEqual(c(413, "body-too-large", .publish), .drop)
        XCTAssertEqual(c(415, "unsupported-type", .publish), .drop)
        XCTAssertEqual(c(429, "rate-limited", .publish), .retry)
        XCTAssertEqual(c(503, nil, .publish), .retry)
        XCTAssertEqual(c(507, "storage-full", .publish), .retry)
        XCTAssertEqual(c(-1, nil, .publish), .retry)
    }

    func testBackoffGrowsAndIsCapped() {
        XCTAssertEqual(ChannelWebOutcome.backoff(attempt: 1, jitter: 0.5), 5, accuracy: 0.001)
        XCTAssertEqual(ChannelWebOutcome.backoff(attempt: 3, jitter: 0.5), 20, accuracy: 0.001)
        XCTAssertEqual(ChannelWebOutcome.backoff(attempt: 40, jitter: 0.5), 3600, accuracy: 0.001)
        XCTAssertEqual(ChannelWebOutcome.backoff(attempt: 2, jitter: 0), 8, accuracy: 0.001)
        XCTAssertEqual(ChannelWebOutcome.backoff(attempt: 2, jitter: 1), 12, accuracy: 0.001)
    }

    func testFeedMatching() {
        let posts: [[String: Any]] = [
            ["n": 9, "createdAt": NSNumber(value: Int64(1795708799000)), "text": "later"],
            ["n": 8, "createdAt": NSNumber(value: Int64(1795708790001)), "text": "x"],
            ["n": 7, "createdAt": NSNumber(value: Int64(1795708780000)), "text": "same text"],
        ]
        XCTAssertEqual(ChannelWebPublisher.matchFeed(posts, createdAtMs: 1795708790000, text: nil), 8, "±1 ms")
        XCTAssertEqual(ChannelWebPublisher.matchFeed(posts, createdAtMs: 1795708781500, text: "same text"), 7)
        XCTAssertNil(ChannelWebPublisher.matchFeed(posts, createdAtMs: 1795708781500, text: "other"))
        XCTAssertNil(ChannelWebPublisher.matchFeed(posts, createdAtMs: 1, text: nil))
    }

    // MARK: - Publisher against fakes

    final class FakeTransport: ChannelWebTransport {
        struct Call { let method: String; let path: String; let query: String?; let body: Data?; let signed: Bool }
        var calls: [Call] = []
        var responder: (Call) -> (Int, Data) = { _ in (200, Data("{}".utf8)) }
        func send(method: String, path: String, query: String?, body: Data?, contentType: String?,
                  signed: Bool) async throws -> (Int, Data) {
            let c = Call(method: method, path: path, query: query, body: body, signed: signed)
            calls.append(c)
            let r = responder(c)
            if r.0 < 0 { throw URLError(.notConnectedToInternet) }
            return r
        }
    }

    @MainActor
    final class FakeSource: ChannelWebContentSource {
        var posts: [String: ChannelWebPostSource] = [:]
        var image: Data? = Data([0xFF, 0xD8, 0xFF])
        var profileValue: ChannelWebProfile? = ChannelWebProfile(title: "Swiss Quant", description: nil, searchIndexing: false, avatar: nil)
        func post(groupId: UUID, postId: String) -> ChannelWebPostSource? { posts[postId] }
        func imageBytes(groupId: UUID, postId: String) -> Data? { image }
        func profile(groupId: UUID) -> ChannelWebProfile? { profileValue }
    }

    private let gid = UUID(uuidString: "5F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9")!
    private let pid = "7F3C2A10-1B2C-4D5E-8F90-A1B2C3D4E5F6"

    private func json(_ o: [String: Any]) -> Data { try! JSONSerialization.data(withJSONObject: o) }

    @MainActor
    private func makePublisher(_ t: FakeTransport, _ s: FakeSource, _ p: ChannelWebPersistence) -> ChannelWebPublisher {
        let pub = ChannelWebPublisher(transport: t, source: s, persistence: p, autoRun: false)
        pub.jitter = { 0.5 }
        return pub
    }

    @MainActor
    func testPublishStoresNAndSurvivesRelaunch() async throws {
        let t = FakeTransport(), s = FakeSource(), persist = ChannelWebPersistence.memory()
        s.posts[pid] = ChannelWebPostSource(text: "**hi**", hasImage: false, createdAtMs: 1795708790000)
        t.responder = { c in (200, self.json(["ok": true, "n": 7, "url": "https://oshi-messenger.com/c/swiss_quant/7"])) }
        let pub = makePublisher(t, s, persist)
        pub.testEnqueue(.init(kind: .publish, groupId: gid, handle: "swiss_quant", postId: pid), record: true)
        await pub.drain()
        XCTAssertEqual(t.calls.map(\.method), ["PUT"])
        XCTAssertEqual(t.calls.first?.path, "/v2/c/swiss_quant/posts/\(pid)")
        XCTAssertTrue(t.calls.first?.signed ?? false)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: t.calls[0].body ?? Data()) as? [String: Any])
        XCTAssertEqual(body["text"] as? String, "**hi**")
        XCTAssertEqual(body["media"] as? [String], [])
        XCTAssertEqual(pub.state.n(postId: pid, handle: "swiss_quant"), 7)
        XCTAssertTrue(pub.state.jobs.isEmpty)
        // Relaunch: a new publisher on the same storage knows n.
        let again = makePublisher(FakeTransport(), FakeSource(), persist)
        XCTAssertEqual(again.state.n(postId: pid, handle: "swiss_quant"), 7)
        XCTAssertNil(again.state.n(postId: pid, handle: "other_chan"), "n belongs to a handle")
        XCTAssertEqual(again.state.postId(handle: "swiss_quant", n: 7), pid)
    }

    @MainActor
    func testRetryWithBackoffThenSuccess() async throws {
        let t = FakeTransport(), s = FakeSource(), persist = ChannelWebPersistence.memory()
        s.posts[pid] = ChannelWebPostSource(text: "x", hasImage: false, createdAtMs: 1)
        var fail = 2
        t.responder = { _ in
            if fail > 0 { fail -= 1; return fail == 1 ? (-1, Data()) : (429, self.json(["error": "rate-limited"])) }
            return (200, self.json(["ok": true, "n": 3]))
        }
        let pub = makePublisher(t, s, persist)
        var clock = Date(timeIntervalSince1970: 1_800_000_000)
        pub.now = { clock }
        pub.testEnqueue(.init(kind: .publish, groupId: gid, handle: "swiss_quant", postId: pid), record: true)
        await pub.drain()
        XCTAssertEqual(t.calls.count, 1, "network error → backed off, not hammered")
        XCTAssertEqual(pub.state.jobs.first?.attempts, 1)
        XCTAssertEqual(pub.state.jobs.first?.notBefore, clock.addingTimeInterval(5))
        // Persisted with its backoff: a relaunch keeps the queue.
        XCTAssertEqual(makePublisher(FakeTransport(), FakeSource(), persist).state.jobs.count, 1)
        clock = clock.addingTimeInterval(6)
        await pub.drain()
        XCTAssertEqual(t.calls.count, 2)
        XCTAssertEqual(pub.state.jobs.first?.attempts, 2)
        XCTAssertEqual(pub.state.jobs.first?.lastError, "rate-limited")
        clock = clock.addingTimeInterval(11)
        await pub.drain()
        XCTAssertEqual(t.calls.count, 3)
        XCTAssertTrue(pub.state.jobs.isEmpty)
        XCTAssertEqual(pub.state.n(postId: pid, handle: "swiss_quant"), 3)
    }

    @MainActor
    func testImageUploadThenUnknownMediaReuploads() async throws {
        let t = FakeTransport(), s = FakeSource(), persist = ChannelWebPersistence.memory()
        s.posts[pid] = ChannelWebPostSource(text: "", hasImage: true, createdAtMs: 1)
        var uploads = 0, puts = 0
        t.responder = { c in
            if c.method == "POST" { uploads += 1; return (200, self.json(["ok": true, "mediaId": "m\(uploads)"])) }
            puts += 1
            if puts == 1 { return (400, self.json(["error": "unknown-media", "mediaId": "m1"])) }
            return (200, self.json(["ok": true, "n": 1]))
        }
        let pub = makePublisher(t, s, persist)
        pub.testEnqueue(.init(kind: .publish, groupId: gid, handle: "swiss_quant", postId: pid), record: true)
        await pub.drain()
        XCTAssertEqual(t.calls.map(\.method), ["POST", "PUT", "POST", "PUT"])
        XCTAssertEqual(t.calls[0].path, "/v2/c/swiss_quant/media")
        let last = try XCTUnwrap(JSONSerialization.jsonObject(with: t.calls[3].body ?? Data()) as? [String: Any])
        XCTAssertEqual(last["media"] as? [String], ["m2"])
        XCTAssertEqual(pub.state.posts[pid]?.mediaIds, ["m2"])
        // An edit reuses the attached media id instead of uploading again.
        s.posts[pid]?.text = "caption added"
        pub.testEnqueue(.init(kind: .publish, groupId: gid, handle: "swiss_quant", postId: pid), record: false)
        await pub.drain()
        XCTAssertEqual(t.calls.map(\.method).suffix(1), ["PUT"])
        let edit = try XCTUnwrap(JSONSerialization.jsonObject(with: t.calls.last?.body ?? Data()) as? [String: Any])
        XCTAssertEqual(edit["media"] as? [String], ["m2"])
    }

    @MainActor
    func testUnsupportedImagePublishesTextAlone() async throws {
        let t = FakeTransport(), s = FakeSource(), persist = ChannelWebPersistence.memory()
        s.posts[pid] = ChannelWebPostSource(text: "caption", hasImage: true, createdAtMs: 1)
        t.responder = { c in
            c.method == "POST" ? (415, self.json(["error": "unsupported-type"])) : (200, self.json(["ok": true, "n": 2]))
        }
        let pub = makePublisher(t, s, persist)
        pub.testEnqueue(.init(kind: .publish, groupId: gid, handle: "swiss_quant", postId: pid), record: true)
        await pub.drain()
        XCTAssertEqual(pub.state.n(postId: pid, handle: "swiss_quant"), 2)
    }

    @MainActor
    func testGoneMessageAndBadRequestsAreDropped() async {
        let t = FakeTransport(), s = FakeSource(), persist = ChannelWebPersistence.memory()
        let pub = makePublisher(t, s, persist)
        pub.testEnqueue(.init(kind: .publish, groupId: gid, handle: "swiss_quant", postId: pid), record: true)
        await pub.drain()
        XCTAssertTrue(t.calls.isEmpty, "message deleted locally before publish: nothing sent")
        XCTAssertTrue(pub.state.jobs.isEmpty)
        s.posts[pid] = ChannelWebPostSource(text: "x", hasImage: false, createdAtMs: 1)
        t.responder = { _ in (403, self.json(["error": "signer-not-a-controller"])) }
        pub.testEnqueue(.init(kind: .publish, groupId: gid, handle: "swiss_quant", postId: pid), record: true)
        await pub.drain()
        XCTAssertTrue(pub.state.jobs.isEmpty, "403 is not retried")
    }

    @MainActor
    func testDeleteAndDisable() async {
        let t = FakeTransport(), s = FakeSource(), persist = ChannelWebPersistence.memory()
        s.posts[pid] = ChannelWebPostSource(text: "x", hasImage: false, createdAtMs: 1)
        t.responder = { c in
            if c.method == "DELETE" { return (404, self.json(["error": "unknown-post"])) }
            return (200, self.json(["ok": true, "n": 5]))
        }
        let pub = makePublisher(t, s, persist)
        pub.testEnqueue(.init(kind: .publish, groupId: gid, handle: "swiss_quant", postId: pid), record: true)
        await pub.drain()
        XCTAssertEqual(pub.state.n(postId: pid, handle: "swiss_quant"), 5)
        pub.testEnqueue(.init(kind: .delete, groupId: gid, handle: "swiss_quant", postId: pid), record: false)
        await pub.drain()
        XCTAssertEqual(t.calls.last?.method, "DELETE")
        XCTAssertEqual(t.calls.last?.path, "/v2/c/swiss_quant/posts/\(pid)")
        XCTAssertNil(pub.state.posts[pid], "404 unknown-post = done")

        // Disable: offline first (kept, never dropped), then purges.
        pub.testEnqueue(.init(kind: .publish, groupId: gid, handle: "swiss_quant", postId: "OTHER"), record: true)
        var clock = Date(timeIntervalSince1970: 1_800_000_000)
        pub.now = { clock }
        t.responder = { _ in (-1, Data()) }
        pub.disable(groupId: gid, handle: "swiss_quant")
        XCTAssertEqual(pub.state.jobs.map(\.kind), [.disable])
        for _ in 0..<30 {
            await pub.drain()
            clock = clock.addingTimeInterval(4000)
        }
        XCTAssertEqual(pub.state.jobs.map(\.kind), [.disable], "a purge is never given up")
        t.responder = { _ in (200, self.json(["ok": true, "enabled": false])) }
        await pub.drain()
        XCTAssertTrue(pub.state.jobs.isEmpty)
        XCTAssertNil(pub.state.posts["OTHER"])
        XCTAssertEqual(t.calls.last?.path, "/v2/c/swiss_quant")
    }

    @MainActor
    func testAdminMapMerge() {
        let pub = makePublisher(FakeTransport(), FakeSource(), .memory())
        pub.mergeAdmin(json(["ok": true, "handle": "swiss_quant", "enabled": true,
                             "posts": [["postId": pid, "n": 7, "createdAt": 1795708790000],
                                       ["postId": "bad/id", "n": 8]]]),
                       handle: "swiss_quant", groupId: gid)
        XCTAssertEqual(pub.state.n(postId: pid, handle: "swiss_quant"), 7)
        XCTAssertNil(pub.state.posts["bad/id"])
        XCTAssertNotNil(pub.state.channels["swiss_quant"]?.lastAdminSync)
    }

    @MainActor
    func testProfileJobUploadsAvatarOnce() async throws {
        let t = FakeTransport(), s = FakeSource(), persist = ChannelWebPersistence.memory()
        s.profileValue = ChannelWebProfile(title: "Swiss Quant", description: "d", searchIndexing: true, avatar: Data([1, 2, 3]))
        t.responder = { c in c.method == "POST" ? (200, self.json(["ok": true, "mediaId": "av1"])) : (200, self.json(["ok": true])) }
        let pub = makePublisher(t, s, persist)
        pub.testEnqueue(.init(kind: .profile, groupId: gid, handle: "swiss_quant"), record: false)
        await pub.drain()
        XCTAssertEqual(t.calls.map(\.method), ["PUT", "POST", "PUT"])
        let last = try XCTUnwrap(JSONSerialization.jsonObject(with: t.calls[2].body ?? Data()) as? [String: Any])
        XCTAssertEqual(last["avatarMediaId"] as? String, "av1")
        XCTAssertEqual(last["searchIndexing"] as? Bool, true)
        XCTAssertEqual(pub.state.channels["swiss_quant"]?.profileSignature, s.profileValue?.signature)
        // Same profile again: one PUT, no re-upload.
        pub.testEnqueue(.init(kind: .profile, groupId: gid, handle: "swiss_quant"), record: false)
        await pub.drain()
        XCTAssertEqual(t.calls.map(\.method), ["PUT", "POST", "PUT", "PUT"])
        XCTAssertNil((try JSONSerialization.jsonObject(with: t.calls[3].body ?? Data()) as? [String: Any])?["avatarMediaId"])
    }

    // MARK: - Settings subkeys

    func testSettingsWebPreviewRoundTripAndActiveRule() throws {
        var s = GroupSettings()
        XCTAssertFalse(s.webPreviewActive)
        s.webPreview = true
        XCTAssertFalse(s.webPreviewActive, "needs a channel with a public handle")
        s.postPolicy = .admins
        s.visibility = .public
        s.handle = "swiss_quant"
        XCTAssertTrue(s.webPreviewActive)
        s.webIndexing = true
        let back = try JSONDecoder().decode(GroupSettings.self, from: JSONEncoder().encode(s))
        XCTAssertTrue(back.webPreview)
        XCTAssertTrue(back.webIndexing)
        XCTAssertFalse(GroupSettings(json: [:]).webPreview, "absent ⇒ off")
    }

    // MARK: - Content of a message

    func testSourceForMessages() {
        let g = UUID()
        func msg(_ text: String?, media: MediaType? = nil, file: String? = nil) -> GroupMessage {
            GroupMessage(id: UUID(), groupId: g, senderPublicKey: "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                         encryptedContent: Data((text ?? "").utf8), timestamp: Date(timeIntervalSince1970: 1795708790.0005),
                         mediaType: media, mediaFileName: file, plaintextContent: text)
        }
        XCTAssertEqual(ChannelWebContent.source(for: msg("*hi*")),
                       ChannelWebPostSource(text: "**hi**", hasImage: false, createdAtMs: 1795708790000))
        XCTAssertEqual(ChannelWebContent.source(for: msg("cap", media: .photo))?.hasImage, true)
        XCTAssertEqual(ChannelWebContent.source(for: msg(nil, media: .photo))?.text, "")
        XCTAssertEqual(ChannelWebContent.source(for: msg("cap", media: .video)), ChannelWebPostSource(text: "cap", hasImage: false, createdAtMs: 1795708790000), "video: caption only")
        XCTAssertNil(ChannelWebContent.source(for: msg(nil, media: .audio)), "voice note without text: nothing")
        XCTAssertNil(ChannelWebContent.source(for: msg(nil, media: .photo, file: "sticker_1.webp")))
        XCTAssertNil(ChannelWebContent.source(for: msg("📊POLL📊{}")))
        var comment = msg("a comment")
        comment.commentOn = UUID().uuidString
        XCTAssertNil(ChannelWebContent.source(for: comment), "comments are not posts")
        var edited = msg("old")
        edited.editedContent = "new"
        XCTAssertEqual(ChannelWebContent.source(for: edited)?.text, "new")
    }

    func testImagePreparationStripsMetadataAndResizes() throws {
        // A 3000×1000 PNG → JPEG, long side 2560, no EXIF/GPS dictionary.
        let w = 3000, h = 1000
        let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.setFillColor(CGColor(red: 1, green: 0, blue: 0, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        let img = ctx.makeImage()!
        let src = NSMutableData()
        let dst = CGImageDestinationCreateWithData(src, "public.jpeg" as CFString, 1, nil)!
        let gps: [CFString: Any] = [kCGImagePropertyGPSLatitude: 46.2, kCGImagePropertyGPSLongitude: 6.1]
        CGImageDestinationAddImage(dst, img, [kCGImagePropertyGPSDictionary: gps,
                                              kCGImagePropertyOrientation: 6] as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(dst))
        let out = try XCTUnwrap(ChannelWebImage.prepare(src as Data))
        let s = try XCTUnwrap(CGImageSourceCreateWithData(out as CFData, nil))
        let props = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(s, 0, nil) as? [CFString: Any])
        XCTAssertNil(props[kCGImagePropertyGPSDictionary], "no GPS")
        XCTAssertEqual(props[kCGImagePropertyOrientation] as? Int ?? 1, 1, "orientation applied, not carried")
        let pw = props[kCGImagePropertyPixelWidth] as? Int ?? 0, ph = props[kCGImagePropertyPixelHeight] as? Int ?? 0
        XCTAssertEqual(max(pw, ph), 2560)
        XCTAssertGreaterThan(ph, pw, "orientation 6 rotates the landscape pixels to portrait")
        // GIF passes through untouched.
        let gif = Data("GIF89a".utf8) + Data(repeating: 0, count: 20)
        XCTAssertEqual(ChannelWebImage.prepare(gif), gif)
        XCTAssertNil(ChannelWebImage.prepare(Data("not an image".utf8)))
    }

    // MARK: - Live integration (opt-in)

    /// Runs only with `OSHI_CHANNEL_WEB_IT_URL=http://127.0.0.1:<port>` in the test
    /// environment and a server started from feat/group-handle-registry-server with
    /// a prekey binding + handle registration for the seed key (done by the shell
    /// driver). Publishes a real signed post, then fetches /c/<handle>/<n>.
    @MainActor
    func testLiveServerPublishAndFetch() async throws {
        guard let base = ProcessInfo.processInfo.environment["OSHI_CHANNEL_WEB_IT_URL"], let url = URL(string: base),
              let handle = ProcessInfo.processInfo.environment["OSHI_CHANNEL_WEB_IT_HANDLE"],
              let userKey = ProcessInfo.processInfo.environment["OSHI_CHANNEL_WEB_IT_USER"] else {
            throw XCTSkip("set OSHI_CHANNEL_WEB_IT_URL / _HANDLE / _USER to run against a local server")
        }
        struct SeedCrypto: V2CryptoProviding {
            let key: Curve25519.Signing.PrivateKey
            let userKey: String
            func signCanonical(_ message: Data) throws -> (signature: String, signingPublicKey: String) {
                (try key.signature(for: message).base64EncodedString(), key.publicKey.rawRepresentation.base64EncodedString())
            }
        }
        let client = V2Client(config: .init(baseURL: url), crypto: SeedCrypto(key: try seedKey(), userKey: userKey),
                              session: URLSession(configuration: .ephemeral))
        let transport = V2ChannelWebTransport(client: client)
        let enable = ChannelWebContent.profileBody(title: "IT Channel", description: "**IT** run", searchIndexing: false)
        let (es, ed) = try await transport.send(method: "PUT", path: ChannelWeb.pathChannel(handle), query: nil,
                                                body: enable, contentType: "application/json", signed: true)
        XCTAssertEqual(es, 200, String(decoding: ed, as: UTF8.self))

        let source = FakeSource()
        let postId = ChannelWeb.postId(UUID())
        let now = ChannelWebContent.ms(Date())
        source.posts[postId] = ChannelWebPostSource(text: ChannelWebText.publishableText("*Weekly* position — see https://example.org/x")!,
                                                    hasImage: true, createdAtMs: now)
        // A real JPEG made by the same preparation path as the app.
        let ctx = CGContext(data: nil, width: 64, height: 48, bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.setFillColor(CGColor(red: 0, green: 0.5, blue: 1, alpha: 1)); ctx.fill(CGRect(x: 0, y: 0, width: 64, height: 48))
        let png = NSMutableData()
        let d = CGImageDestinationCreateWithData(png, "public.png" as CFString, 1, nil)!
        CGImageDestinationAddImage(d, ctx.makeImage()!, nil); CGImageDestinationFinalize(d)
        source.image = ChannelWebImage.prepare(png as Data)

        let pub = ChannelWebPublisher(transport: transport, source: source, persistence: .memory(), autoRun: false)
        pub.testEnqueue(.init(kind: .publish, groupId: gid, handle: handle, postId: postId), record: true)
        await pub.drain()
        let n = try XCTUnwrap(pub.state.n(postId: postId, handle: handle), "\(pub.state.jobs)")

        let (pd, presp) = try await URLSession.shared.data(from: URL(string: "\(base)/v2/c/\(handle)/\(n)")!)
        XCTAssertEqual((presp as? HTTPURLResponse)?.statusCode, 200)
        let o = try XCTUnwrap(JSONSerialization.jsonObject(with: pd) as? [String: Any])
        let post = try XCTUnwrap(o["post"] as? [String: Any])
        XCTAssertEqual(post["n"] as? Int, n)
        XCTAssertEqual(post["html"] as? String,
                       "<strong>Weekly</strong> position — see <a href=\"https://example.org/x\" target=\"_blank\" rel=\"noopener noreferrer nofollow ugc\">https://example.org/x</a>")
        XCTAssertEqual((post["media"] as? [[String: Any]])?.count, 1)

        let (page, pageResp) = try await URLSession.shared.data(from: URL(string: "\(base)/c/\(handle)/\(n)")!)
        XCTAssertEqual((pageResp as? HTTPURLResponse)?.statusCode, 200)
        XCTAssertTrue(String(decoding: page, as: UTF8.self).contains("oshi://channel?h=\(handle)&amp;n=\(n)")
                      || String(decoding: page, as: UTF8.self).contains("oshi://channel?h=\(handle)&n=\(n)"))

        // Edit → same n; delete → gone.
        source.posts[postId]?.text = "edited"
        pub.testEnqueue(.init(kind: .publish, groupId: gid, handle: handle, postId: postId), record: false)
        await pub.drain()
        XCTAssertEqual(pub.state.n(postId: postId, handle: handle), n)
        pub.testEnqueue(.init(kind: .delete, groupId: gid, handle: handle, postId: postId), record: false)
        await pub.drain()
        // Fresh request: the public JSON is `Cache-Control: max-age=30`, and the shared
        // session would answer from the copy fetched above.
        var gone = URLRequest(url: URL(string: "\(base)/v2/c/\(handle)/\(n)")!)
        gone.cachePolicy = .reloadIgnoringLocalCacheData
        let (_, goneResp) = try await URLSession(configuration: .ephemeral).data(for: gone)
        XCTAssertEqual((goneResp as? HTTPURLResponse)?.statusCode, 404)
    }
}
