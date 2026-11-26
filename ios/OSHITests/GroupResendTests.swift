//
//  GroupResendTests.swift
//  OSHITests
//
//  __GROUP_BLOB_2026_11_25__ / __GROUP_RESEND_2026_11_25__
//  * a group photo is uploaded ONCE (reserve with `recipientKeys`; a server that stored the list
//    echoes `recipients`), with a per-member fallback when an older server does not echo it;
//  * a group message that missed members keeps a durable pending list, retried 1, 5, 30 min,
//    then every 3 h for 7 days, minus members who left / have no v2 bundle;
//  * a download resumes from the chunks already staged on disk, whichever path staged them.
//  Mirror of Android `GroupResendTest`.
//

import XCTest
@testable import OSHI

final class GroupResendTests: XCTestCase {

    private var dir: URL!

    override func setUp() {
        super.setUp()
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("group-resend-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        GroupBlobStubProtocol.state.reset(echoRecipients: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
        super.tearDown()
    }

    private func store() -> GroupPendingDeliveryStore {
        GroupPendingDeliveryStore(fileURL: dir.appendingPathComponent("pending.json"))
    }

    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    // MARK: schedule

    func testBackoffIsOneFiveThirtyMinutesThenEveryThreeHours() {
        let d = (0...6).map { GroupResendSchedule.delay(afterAttempts: $0) }
        XCTAssertEqual(d, [60, 60, 300, 1800, 10800, 10800, 10800])
        XCTAssertEqual(GroupResendSchedule.maxAge, 7 * 24 * 3600)
    }

    // MARK: durable queue

    func testPendingListSurvivesARestart() {
        let s = store()
        s.begin(msgId: "m1", groupId: "g1", recipients: ["a", "b", "c"], now: t0)
        s.settle(msgId: "m1", reached: ["a"], noBundle: [], now: t0)
        // A new process: a fresh store on the same file.
        let again = store()
        let e = again.get("m1")
        XCTAssertEqual(e?.pending, ["b", "c"])
        XCTAssertEqual(e?.total, 3)
        XCTAssertEqual(e?.delivered, 1)
        XCTAssertEqual(e?.attempts, 1)
        XCTAssertEqual(e?.nextAt, t0.addingTimeInterval(60))
    }

    func testBeginBeforeTheSendMakesAKilledSendDueAfterOneMinute() {
        let s = store()
        s.begin(msgId: "m1", groupId: "g1", recipients: ["a", "b"], now: t0)
        XCTAssertTrue(s.due(now: t0.addingTimeInterval(59)).isEmpty)
        XCTAssertEqual(store().due(now: t0.addingTimeInterval(60)).map(\.msgId), ["m1"])
    }

    func testEverybodyReachedRemovesTheEntry() {
        let s = store()
        s.begin(msgId: "m1", groupId: "g1", recipients: ["a", "b"], now: t0)
        XCTAssertNil(s.settle(msgId: "m1", reached: ["a", "b"], noBundle: [], now: t0))
        XCTAssertNil(store().get("m1"))
    }

    func testMembersWithNoBundleAreNotPendingAndNotCounted() {
        let s = store()
        s.begin(msgId: "m1", groupId: "g1", recipients: ["a", "b", "old"], now: t0)
        let e = s.settle(msgId: "m1", reached: ["a"], noBundle: ["old"], now: t0)
        XCTAssertEqual(e?.pending, ["b"])
        XCTAssertEqual(e?.total, 2, "a member who must update is not part of X of Y")
        XCTAssertNil(s.settle(msgId: "m1", reached: [], noBundle: ["b"], now: t0))
    }

    func testScheduleAdvancesPerAttemptAndHonoursRetryAfter() {
        let s = store()
        s.begin(msgId: "m1", groupId: "g1", recipients: ["a"], now: t0)
        var next: [TimeInterval] = []
        for _ in 0..<5 {
            let e = s.settle(msgId: "m1", reached: [], noBundle: [], now: t0)
            next.append(e!.nextAt.timeIntervalSince(t0))
        }
        XCTAssertEqual(next, [60, 300, 1800, 10800, 10800])
        let e = s.settle(msgId: "m1", reached: [], noBundle: [], now: t0, retryAfter: 4 * 3600)
        XCTAssertEqual(e?.nextAt, t0.addingTimeInterval(4 * 3600))
    }

    func testMemberWhoLeftIsDropped() {
        let s = store()
        s.begin(msgId: "m1", groupId: "G1", recipients: ["a", "b"], now: t0)
        s.begin(msgId: "m2", groupId: "G1", recipients: ["b"], now: t0)
        let done = s.dropDeparted(groupId: "g1", members: ["a", "me"])
        XCTAssertEqual(done, ["m2"])
        XCTAssertEqual(s.get("m1")?.pending, ["a"])
        XCTAssertEqual(s.get("m1")?.total, 1)
    }

    func testGivesUpAfterSevenDaysButRetryNowRevives() {
        let s = store()
        s.begin(msgId: "m1", groupId: "g1", recipients: ["a"], now: t0)
        let late = t0.addingTimeInterval(GroupResendSchedule.maxAge + 1)
        let e = s.settle(msgId: "m1", reached: [], noBundle: [], now: late)
        XCTAssertEqual(e?.gaveUp, true)
        XCTAssertTrue(s.due(now: late.addingTimeInterval(1e6)).isEmpty, "no automatic retry after 7 days")
        s.makeDue(now: late)   // network back: given-up entries stay given up
        XCTAssertTrue(s.due(now: late).isEmpty)
        s.makeDue(msgId: "m1", now: late)   // "Retry now"
        XCTAssertEqual(s.due(now: late).map(\.msgId), ["m1"])
        XCTAssertEqual(s.get("m1")?.gaveUp, false)
    }

    func testConnectivityBackMakesEveryLiveEntryDueNow() {
        let s = store()
        s.begin(msgId: "m1", groupId: "g1", recipients: ["a"], now: t0)
        s.settle(msgId: "m1", reached: [], noBundle: [], now: t0)
        s.settle(msgId: "m1", reached: [], noBundle: [], now: t0)   // next in 5 min
        XCTAssertTrue(s.due(now: t0.addingTimeInterval(10)).isEmpty)
        s.makeDue(now: t0.addingTimeInterval(10))
        XCTAssertEqual(s.due(now: t0.addingTimeInterval(10)).count, 1)
    }

    func testKeySpellingsCompareEqual() {
        let s = store()
        let key = Data(repeating: 0xFB, count: 32).base64EncodedString()   // contains '+' and '/'
        let url = key.replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        s.begin(msgId: "m1", groupId: "g1", recipients: [key, "b"], now: t0)
        XCTAssertEqual(s.settle(msgId: "m1", reached: [url], noBundle: [], now: t0)?.pending, ["b"])
    }

    func testABotReplyKeepsItsBotName() {
        let s = store()
        s.begin(msgId: "m1", groupId: "g1", recipients: ["a"], now: t0, botAuthor: "Weather")
        XCTAssertEqual(store().get("m1")?.botAuthor, "Weather")
    }

    func testCorruptFileDegradesToNothingPending() throws {
        try Data("{not json".utf8).write(to: dir.appendingPathComponent("pending.json"))
        XCTAssertTrue(store().all().isEmpty)
    }

    // MARK: status text

    func testStatusCountsAndText() {
        let e = GroupPendingDeliveryStore.Entry(msgId: "m", groupId: "g", total: 10, pending: ["a", "b", "c", "d", "e", "f", "g"],
                                                attempts: 1, firstAt: t0, nextAt: t0)
        XCTAssertEqual(e.delivered, 3)
        XCTAssertEqual(GroupPartialDelivery.compact(e), "3/10")
        let title = GroupPartialDelivery.title(e)
        XCTAssertTrue(title.contains("3") && title.contains("10"), title)
        XCTAssertFalse(title.contains("group.sent_partial"), "raw key shown")
        XCTAssertTrue(GroupPartialDelivery.body(e).contains("7"))
    }

    func testResendStringsExistInEveryLocale() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("OSHI")
        let locales = try FileManager.default.contentsOfDirectory(atPath: root.path).filter { $0.hasSuffix(".lproj") }
        XCTAssertGreaterThanOrEqual(locales.count, 34)
        for loc in locales {
            let text = (try? String(contentsOf: root.appendingPathComponent(loc).appendingPathComponent("Localizable.strings"),
                                    encoding: .utf8)) ?? ""
            for key in ["group.sent_partial", "group.sent_partial.body", "group.sent_partial.gave_up", "group.retry_now"] {
                XCTAssertTrue(text.contains("\"\(key)\" ="), "\(loc) misses \(key)")
            }
            XCTAssertTrue(text.contains("%1$d") && text.contains("%2$d"), "\(loc): X of Y placeholders")
        }
    }

    // MARK: reserve wire

    private func json(_ d: Data) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] ?? [:]
    }

    func testOneToOneReserveBodyIsUnchanged() throws {
        let d = try V2Client.reserveBody(encoder: JSONEncoder(), ownerKey: "o", recipientKey: "r",
                                         recipientKeys: nil, totalSize: 10, chunkCount: 1)
        XCTAssertEqual(Set(json(d).keys), ["ownerKey", "recipientKey", "totalSize", "chunkCount"])
        XCTAssertFalse(String(decoding: d, as: UTF8.self).contains("recipientKeys"))
    }

    func testGroupReserveBodyCarriesTheList() throws {
        let d = try V2Client.reserveBody(encoder: JSONEncoder(), ownerKey: "o", recipientKey: "a",
                                         recipientKeys: ["a", "b", "c"], totalSize: 10, chunkCount: 1)
        XCTAssertEqual(json(d)["recipientKey"] as? String, "a")
        XCTAssertEqual(json(d)["recipientKeys"] as? [String], ["a", "b", "c"])
    }

    func testReserveResultDecodesRecipientsOnlyWhenPresent() throws {
        let old = try JSONDecoder().decode(V2.ReserveResult.self,
                                           from: Data(#"{"blobId":"b","chunkSize":2097152,"missing":[0]}"#.utf8))
        XCTAssertNil(old.recipients)
        XCTAssertFalse(V2GroupBlobPlan.isShared(old))
        let new = try JSONDecoder().decode(V2.ReserveResult.self,
                                           from: Data(#"{"blobId":"b","chunkSize":2097152,"missing":[0],"recipients":3}"#.utf8))
        XCTAssertEqual(new.recipients, 3)
        XCTAssertTrue(V2GroupBlobPlan.isShared(new))
    }

    func testSharedVersusFallbackDecision() {
        XCTAssertEqual(V2GroupBlobPlan.covered(listed: ["a", "b", "c"], shared: true), ["a", "b", "c"])
        XCTAssertEqual(V2GroupBlobPlan.covered(listed: ["a", "b", "c"], shared: false), ["a"])
        XCTAssertEqual(V2GroupBlobPlan.needOwnBlob(reachable: ["a", "b", "c"], covered: ["a"]), ["b", "c"])
        XCTAssertEqual(V2GroupBlobPlan.needOwnBlob(reachable: ["a", "b"], covered: ["a", "b", "c"]), [])
        // The shared list: reachable first, then the members unknown right now, never no-bundle.
        XCTAssertEqual(V2GroupBlobPlan.readers(reachable: ["b"], recipients: ["a", "b", "c", "old"], noBundle: ["old"]),
                       ["b", "a", "c"])
    }

    // MARK: one upload against a stub blob store

    private func stubbedClient() -> V2Client {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [GroupBlobStubProtocol.self]
        return V2Client(config: V2Client.Config(baseURL: URL(string: "https://stub.invalid")!,
                                                requestTimeout: 10, maxRetryAttempts: 1),
                        crypto: V2EphemeralCrypto(),
                        session: URLSession(configuration: cfg))
    }

    func testPatchedServerOneUploadSharedByEveryMember() async throws {
        let chunks = [Data(repeating: 1, count: 100), Data(repeating: 2, count: 50)]
        let r = try await stubbedClient().uploadGroupBlob(recipientKeys: ["a", "b", "c"], chunkCount: 2, totalSize: 150,
                                                          chunk: { chunks[$0] }, useBackground: false)
        XCTAssertTrue(r.shared)
        let st = GroupBlobStubProtocol.state
        XCTAssertEqual(st.reserves.count, 1)
        XCTAssertEqual(st.reserves.first?["recipientKeys"] as? [String], ["a", "b", "c"])
        XCTAssertEqual(st.reserves.first?["recipientKey"] as? String, "a")
        XCTAssertEqual(st.puts, 2)
        XCTAssertTrue(st.committed)
    }

    func testOldServerIsDetectedByTheMissingEcho() async throws {
        GroupBlobStubProtocol.state.reset(echoRecipients: false)
        let chunks = [Data(repeating: 1, count: 100)]
        var reserved: (String, Bool)?
        let lock = NSLock()
        let r = try await stubbedClient().uploadGroupBlob(recipientKeys: ["a", "b"], chunkCount: 1, totalSize: 100,
                                                          chunk: { chunks[$0] }, useBackground: false,
                                                          onReserved: { id, shared in lock.lock(); reserved = (id, shared); lock.unlock() })
        XCTAssertFalse(r.shared, "no `recipients` echo = only recipientKey may read it")
        XCTAssertEqual(reserved?.1, false)
        XCTAssertEqual(V2GroupBlobPlan.needOwnBlob(reachable: ["a", "b"], covered: V2GroupBlobPlan.covered(listed: ["a", "b"], shared: r.shared)), ["b"])
    }

    func testResumedUploadPutsOnlyTheMissingChunks() async throws {
        let st = GroupBlobStubProtocol.state
        st.preload(blobId: "blob-r", stored: [0, 2], chunkCount: 3)
        let chunks = (0..<3).map { Data(repeating: UInt8($0), count: 10) }
        let r = try await stubbedClient().uploadGroupBlob(recipientKeys: ["a", "b"], chunkCount: 3, totalSize: 30,
                                                          chunk: { chunks[$0] }, useBackground: false,
                                                          resumeBlobId: "blob-r", resumeShared: true)
        XCTAssertEqual(r.blobId, "blob-r")
        XCTAssertTrue(r.shared)
        XCTAssertEqual(st.reserves.count, 0, "a resume never reserves again")
        XCTAssertEqual(st.putIndices, [1])
        XCTAssertTrue(st.committed)
    }

    // MARK: download resume

    func testDownloadOpensFromChunksStagedByEarlierAttemptsWithoutRefetching() async throws {
        let plain = Data((0..<5000).map { UInt8($0 % 251) })
        let enc = try OSHICryptoV2.encryptFile(bytes: plain, chunkSize: 1024)
        let blobId = "resume-\(UUID().uuidString)"
        let stage = V2BackgroundBlobDownloader.stageDir(blobId: blobId)
        defer { try? FileManager.default.removeItem(at: stage) }
        // Staged by three different paths before an app kill.
        for (n, c) in enc.chunks.enumerated() {
            let name = ["\(n).part", "ranged-\(n).part", "ohttp-\(n).part"][n % 3]
            try c.write(to: stage.appendingPathComponent(name))
        }
        GroupBlobStubProtocol.state.failEverything = true   // any network fetch would fail the test
        let out = try await stubbedClient().downloadAndDecryptFileToDisk(
            blobId: blobId, chunkCount: enc.chunks.count,
            fileKeyB64: enc.fileKey.base64EncodedString(), fileNonceB64: enc.fileNonce.base64EncodedString(),
            manifestB64: enc.manifest.base64EncodedString())
        XCTAssertEqual(try Data(contentsOf: out), plain)
        XCTAssertEqual(GroupBlobStubProtocol.state.requests, 0)
        for n in 0..<enc.chunks.count { XCTAssertNil(V2BackgroundBlobDownloader.stagedChunk(blobId: blobId, index: n)) }
    }

    func testABadStagedChunkIsDiscardedSoTheNextAttemptRefetches() async throws {
        let plain = Data((0..<3000).map { UInt8($0 % 7) })
        let enc = try OSHICryptoV2.encryptFile(bytes: plain, chunkSize: 1024)
        let blobId = "bad-\(UUID().uuidString)"
        let stage = V2BackgroundBlobDownloader.stageDir(blobId: blobId)
        defer { try? FileManager.default.removeItem(at: stage) }
        for (n, c) in enc.chunks.enumerated() {
            var bytes = c
            if n == 1 { bytes[bytes.startIndex] ^= 0xFF }
            try bytes.write(to: stage.appendingPathComponent("\(n).part"))
        }
        do {
            _ = try await stubbedClient().downloadAndDecryptFileToDisk(
                blobId: blobId, chunkCount: enc.chunks.count,
                fileKeyB64: enc.fileKey.base64EncodedString(), fileNonceB64: enc.fileNonce.base64EncodedString(),
                manifestB64: enc.manifest.base64EncodedString())
            XCTFail("a tampered chunk must not open")
        } catch {}
        for n in 0..<enc.chunks.count {
            XCTAssertNil(V2BackgroundBlobDownloader.stagedChunk(blobId: blobId, index: n),
                         "chunk \(n) kept: every later attempt would fail on the same bytes")
        }
    }

    func testAnEmptyStagedFileIsNotAChunk() throws {
        let blobId = "empty-\(UUID().uuidString)"
        let stage = V2BackgroundBlobDownloader.stageDir(blobId: blobId)
        defer { try? FileManager.default.removeItem(at: stage) }
        try Data().write(to: stage.appendingPathComponent("0.part"))
        XCTAssertNil(V2BackgroundBlobDownloader.stagedChunk(blobId: blobId, index: 0))
        try Data([1]).write(to: stage.appendingPathComponent("cover-0.part"))
        XCTAssertEqual(V2BackgroundBlobDownloader.stagedChunk(blobId: blobId, index: 0)?.lastPathComponent, "cover-0.part")
    }

    // MARK: wiring

    func testResendRunsThroughTheNormalSendPath() throws {
        let src = try String(contentsOf: URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("OSHI/GroupMessaging.swift"), encoding: .utf8)
        XCTAssertTrue(src.contains("await self.broadcastGroupMessageViaRelay(wire, to: group, senderKey: me,"),
                      "the resend must re-encrypt through the normal group path")
        XCTAssertTrue(src.contains("pendingDeliveryStore.settle(msgId: msgKey, reached: Array(handled), noBundle: listed)"))
    }
}

// MARK: - Stub blob store

final class GroupBlobStubState: @unchecked Sendable {
    private let lock = NSLock()
    private var _echo = true
    private var _reserves: [[String: Any]] = []
    private var _stored: [String: Set<Int>] = [:]
    private var _count: [String: Int] = [:]
    private var _committed = false
    private var _putIndices: [Int] = []
    private var _requests = 0
    private var _fail = false

    func reset(echoRecipients: Bool) {
        lock.lock(); defer { lock.unlock() }
        _echo = echoRecipients; _reserves = []; _stored = [:]; _count = [:]; _committed = false
        _putIndices = []; _requests = 0; _fail = false
    }
    func preload(blobId: String, stored: Set<Int>, chunkCount: Int) {
        lock.lock(); defer { lock.unlock() }
        _stored[blobId] = stored; _count[blobId] = chunkCount
    }
    var failEverything: Bool {
        get { lock.lock(); defer { lock.unlock() }; return _fail }
        set { lock.lock(); _fail = newValue; lock.unlock() }
    }
    var reserves: [[String: Any]] { lock.lock(); defer { lock.unlock() }; return _reserves }
    var puts: Int { lock.lock(); defer { lock.unlock() }; return _putIndices.count }
    var putIndices: [Int] { lock.lock(); defer { lock.unlock() }; return _putIndices.sorted() }
    var committed: Bool { lock.lock(); defer { lock.unlock() }; return _committed }
    var requests: Int { lock.lock(); defer { lock.unlock() }; return _requests }

    /// (status, body)
    func handle(method: String, path: String, body: Data?) -> (Int, Data) {
        lock.lock(); defer { lock.unlock() }
        _requests += 1
        if _fail { return (500, Data(#"{"error":"stub"}"#.utf8)) }
        let parts = path.split(separator: "/").map(String.init)   // v2 blobs <id> ...
        func missing(_ id: String) -> [Int] {
            (0..<(_count[id] ?? 0)).filter { !(_stored[id] ?? []).contains($0) }
        }
        if method == "POST", parts == ["v2", "blobs"] {
            let obj = (body.flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any]) ?? [:]
            _reserves.append(obj)
            let id = "blob-\(_reserves.count)"
            _count[id] = obj["chunkCount"] as? Int ?? 0
            _stored[id] = []
            var reply: [String: Any] = ["blobId": id, "chunkSize": 2_097_152, "missing": missing(id)]
            if _echo, let list = obj["recipientKeys"] as? [String] { reply["recipients"] = list.count }
            return (200, try! JSONSerialization.data(withJSONObject: reply))
        }
        guard parts.count >= 4, parts[0] == "v2", parts[1] == "blobs" else { return (404, Data()) }
        let id = parts[2]
        switch (method, parts[3]) {
        case ("PUT", "chunk"):
            let n = Int(parts[4]) ?? -1
            _stored[id, default: []].insert(n); _putIndices.append(n)
            return (200, Data(#"{"ok":true}"#.utf8))
        case ("GET", "status"):
            let reply: [String: Any] = ["blobId": id, "missing": missing(id), "committed": _committed, "expiresAt": 0]
            return (200, try! JSONSerialization.data(withJSONObject: reply))
        case ("POST", "commit"):
            guard missing(id).isEmpty else { return (409, Data()) }
            _committed = true
            return (200, Data(#"{"ok":true}"#.utf8))
        default:
            return (404, Data())
        }
    }
}

final class GroupBlobStubProtocol: URLProtocol, @unchecked Sendable {
    static let state = GroupBlobStubState()

    override class func canInit(with request: URLRequest) -> Bool { request.url?.path.hasPrefix("/v2/") == true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else { client?.urlProtocol(self, didFailWithError: URLError(.badURL)); return }
        var body = request.httpBody
        if body == nil, let stream = request.httpBodyStream {
            var d = Data(); stream.open()
            var buf = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable { let n = stream.read(&buf, maxLength: buf.count); if n <= 0 { break }; d.append(buf, count: n) }
            stream.close(); body = d
        }
        let (status, data) = Self.state.handle(method: request.httpMethod ?? "GET", path: url.path, body: body)
        let resp = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1",
                                   headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
