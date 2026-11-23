//
//  V2LegacyReaderFanoutTests.swift
//  OSHITests
//
//  __LEGACY_READER_2026_09_28__ "Incoming messages arrive; what I send never reaches the recipient."
//
//  Android 1.6.26–1.6.32 shipped it (fixed in bc0e3fc3); iOS 1.0.46 carries the same design in
//  `V2MessageRouter.sendFanout`. In device mode (`/v2/config device_mailbox_enabled:true` AND this
//  install registered) a 1:1 or group message becomes one copy PER DEVICE that
//  `GET /v2/keys/<peer>/devices` lists. The relay shows an install that is NOT registered in device
//  mode only ACCOUNT envelopes (`relay_v2.js visibleTo`: no deviceId ⇒ `isAccountItem`). So when the
//  peer's live install reads that legacy view while its account still lists a bundled device — a
//  reinstalled phone (its old deviceId stays bundled 30 days, and the new install needs an approval
//  from it), an iPhone on 1.0.43 next to a registered Mac, an Android that fell back to the account
//  path — every copy went to a device nobody reads. The relay accepted it (✓), the peer never saw it,
//  and the peer's own messages (account envelopes) kept arriving.
//
//  These tests drive the REAL `V2MessageRouter` (X3DH, ratchet, fan-out, decrypt) of two identities
//  against an in-process stub of the relay + prekey server (`LegacyReaderStubRelay`), so nothing
//  touches the network. The key assertion is on the envelopes the relay receives, filtered through
//  the relay's own visibility rule for a legacy-view install.
//

import XCTest
import CryptoKit
@testable import OSHI

// MARK: - In-process relay / prekey server

/// Just enough of `/v2/keys` + `/v2/messages` for the fan-out: bundles (account and per-device),
/// the bundled-device list, and a capture of every posted envelope.
final class LegacyReaderStubRelay: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var bundles: [String: [String: Any]] = [:]   // "user" or "user#device"
    nonisolated(unsafe) private static var deviceLists: [String: [String]] = [:]
    nonisolated(unsafe) private static var posted: [[String: Any]] = []
    nonisolated(unsafe) private static var nextSeq = 1

    static func reset() {
        lock.lock(); defer { lock.unlock() }
        bundles = [:]; deviceLists = [:]; posted = []; nextSeq = 1
    }

    /// Every envelope posted to `to` since the last call, as the relay would store them.
    static func take(to: String) -> [V2.ReceivedEnvelope] {
        lock.lock(); defer { lock.unlock() }
        let norm = V2Device.normalizeIdentity(to)
        var mine: [[String: Any]] = []
        posted.removeAll { e in
            guard V2Device.normalizeIdentity(e["to"] as? String ?? "") == norm else { return false }
            mine.append(e); return true
        }
        return mine.compactMap { e in
            var e = e
            e["seq"] = nextSeq; nextSeq += 1
            guard let d = try? JSONSerialization.data(withJSONObject: e) else { return nil }
            return try? JSONDecoder().decode(V2.ReceivedEnvelope.self, from: d)
        }
    }

    static var session: URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.protocolClasses = [LegacyReaderStubRelay.self]
        return URLSession(configuration: c)
    }

    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "legacy-reader.invalid" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    private static func body(of request: URLRequest) -> Data {
        if let b = request.httpBody { return b }
        guard let s = request.httpBodyStream else { return Data() }
        s.open(); defer { s.close() }
        var out = Data(); var buf = [UInt8](repeating: 0, count: 16 * 1024)
        while s.hasBytesAvailable {
            let n = s.read(&buf, maxLength: buf.count)
            if n <= 0 { break }
            out.append(buf, count: n)
        }
        return out
    }

    override func startLoading() {
        let (status, json) = Self.route(request)
        let data = (try? JSONSerialization.data(withJSONObject: json)) ?? Data()
        let resp = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1",
                                   headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }

    private static func route(_ request: URLRequest) -> (Int, Any) {
        lock.lock(); defer { lock.unlock() }
        guard let url = request.url, let comps = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return (400, ["error": "bad-url"])
        }
        let segs = comps.percentEncodedPath.split(separator: "/").map { String($0).removingPercentEncoding ?? String($0) }
        let device = comps.queryItems?.first(where: { $0.name == "device" })?.value
        let method = request.httpMethod ?? "GET"
        let body = (try? JSONSerialization.jsonObject(with: Self.body(of: request))) as? [String: Any]

        // POST /v2/keys/publish
        if method == "POST", segs == ["v2", "keys", "publish"], let b = body, let user = b["userKey"] as? String {
            let u = V2Device.normalizeIdentity(user)
            if let d = b["deviceId"] as? String {
                bundles[u + "#" + d] = b
                if !(deviceLists[u] ?? []).contains(d) { deviceLists[u, default: []].append(d) }
            } else {
                bundles[u] = b
            }
            return (200, ["remaining": (b["oneTimePreKeys"] as? [Any])?.count ?? 0])
        }
        // GET /v2/keys/<user>[/devices|/count][?device=]
        if method == "GET", segs.count >= 3, segs[0] == "v2", segs[1] == "keys" {
            let u = V2Device.normalizeIdentity(segs[2])
            if segs.count == 4, segs[3] == "devices" {
                return (200, ["enabled": true, "devices": (deviceLists[u] ?? []).map { ["deviceId": $0] }])
            }
            if segs.count == 4, segs[3] == "count" { return (200, ["remaining": 20]) }
            let k = device.map { u + "#" + $0 } ?? u
            guard var b = bundles[k] else { return (404, ["error": "no-bundle"]) }
            var opks = b["oneTimePreKeys"] as? [[String: Any]] ?? []
            let opk = opks.isEmpty ? nil : opks.removeFirst()
            b["oneTimePreKeys"] = opks
            bundles[k] = b
            var out: [String: Any] = ["identityKey": b["identityKey"]!, "signingKey": b["signingKey"]!,
                                      "signedPreKey": b["signedPreKey"]!, "caps": b["caps"] ?? 0]
            if let opk { out["oneTimePreKey"] = opk }
            return (200, out)
        }
        // POST /v2/messages — a bare envelope or {envelopes:[…]}
        if method == "POST", segs == ["v2", "messages"], let b = body {
            let envs = (b["envelopes"] as? [[String: Any]]) ?? [b]
            posted.append(contentsOf: envs)
            return (200, ["accepted": envs.compactMap { $0["msgId"] as? String }])
        }
        return (404, ["error": "not-found"])
    }
}

// MARK: - Tests

final class V2LegacyReaderFanoutTests: XCTestCase {

    private struct TestCrypto: V2CryptoProviding {
        let signing: Curve25519.Signing.PrivateKey
        let identity: String
        var userKey: String { identity }
        func signCanonical(_ message: Data) throws -> (signature: String, signingPublicKey: String) {
            (try signing.signature(for: message).base64EncodedString(),
             signing.publicKey.rawRepresentation.base64EncodedString())
        }
    }

    /// One identity (contact address), whose installs are separate routers.
    private final class Person {
        let ik = Curve25519.KeyAgreement.PrivateKey()
        let sk = Curve25519.Signing.PrivateKey()
        let tag = "lrtest.\(UUID().uuidString.prefix(8))"
        var userKey: String { ik.publicKey.rawRepresentation.base64EncodedString() }
        var norm: String { V2Device.normalizeIdentity(userKey) }
        var ikItem: String { tag + ".ik" }
        var skItem: String { tag + ".sk" }
        init() {
            try? KeychainHelper.save(key: ikItem, data: ik.rawRepresentation)
            try? KeychainHelper.save(key: skItem, data: sk.rawRepresentation)
        }
        func install(_ name: String, peerViewSuite: String) -> V2MessageRouter {
            let run = "\(tag).\(name)"
            let client = V2Client(config: .init(baseURL: URL(string: "https://legacy-reader.invalid")!,
                                                requestTimeout: 5, maxRetryAttempts: 1),
                                  crypto: TestCrypto(signing: sk, identity: userKey),
                                  session: LegacyReaderStubRelay.session)
            return V2MessageRouter(client: client,
                                   store: V2SessionStore(sessionPrefix: run + ".s.", ownMaterialKey: run + ".own"),
                                   deviceStore: V2SessionStore(ownMaterialKey: run + ".devown"),
                                   crypto: TestCrypto(signing: sk, identity: userKey),
                                   signingKeychainKey: skItem, identityKeychainKey: ikItem,
                                   oneTimePreKeyCount: 5,
                                   peerViewSuite: peerViewSuite)
        }
        func cleanup() {
            try? KeychainHelper.delete(key: ikItem)
            try? KeychainHelper.delete(key: skItem)
        }
    }

    private var people: [Person] = []
    private var suites: [String] = []
    private var savedCover: Any?

    override func setUp() {
        super.setUp()
        LegacyReaderStubRelay.reset()
        // CoverTransport wraps every /v2 request when this is on; the stub speaks plain /v2.
        savedCover = UserDefaults.standard.object(forKey: "covertChannelEnabled")
        UserDefaults.standard.set(false, forKey: "covertChannelEnabled")
    }

    override func tearDown() {
        people.forEach { $0.cleanup() }
        for s in suites { UserDefaults.standard.removePersistentDomain(forName: s) }
        if let savedCover { UserDefaults.standard.set(savedCover, forKey: "covertChannelEnabled") }
        else { UserDefaults.standard.removeObject(forKey: "covertChannelEnabled") }
        super.tearDown()
    }

    private func person() -> Person { let p = Person(); people.append(p); return p }
    private func suite() -> String { let s = "lrtest.defaults.\(UUID().uuidString)"; suites.append(s); return s }
    private func pull(_ envs: [V2.ReceivedEnvelope]) -> V2.PullResult {
        V2.PullResult(messages: envs, maxSeq: envs.map(\.seq).max() ?? 0)
    }
    private func texts(_ items: [V2IncomingMessage]) -> [String] {
        items.compactMap { if case .text(_, _, _, let t, _) = $0 { return t } else { return nil } }
    }
    /// `relay_v2.js visibleTo(item, deviceId = null, devMode = true)`: an install not registered in
    /// device mode sees account items only.
    private func legacyView(_ envs: [V2.ReceivedEnvelope]) -> [V2.ReceivedEnvelope] {
        envs.filter { $0.toDevice == nil }
    }
    private func deviceMode(_ r: V2MessageRouter, _ p: Person, holder: Bool = true) async throws -> V2Device.Keys {
        let k = V2Device.Keys(dk: .init(), dsk: .init())
        try await r.publishDeviceBundle(signer: k.signer)
        await r.setDeviceContext(.init(me: p.norm, deviceId: k.deviceId, signer: k.signer, holdsAccountBundle: holder))
        return k
    }

    // ------------------------------------------------------------------ 1. the rule

    func testAnEnvelopeWithNeitherDeviceIdComesFromALegacyViewInstall() {
        XCTAssertTrue(V2PeerReadView.isLegacyWriter(toDevice: nil, fromDevice: nil))
        let d = String(repeating: "ab", count: 16)
        XCTAssertFalse(V2PeerReadView.isLegacyWriter(toDevice: d, fromDevice: d))
        XCTAssertFalse(V2PeerReadView.isLegacyWriter(toDevice: d, fromDevice: nil))   // §3.4 row 2
        XCTAssertFalse(V2PeerReadView.isLegacyWriter(toDevice: nil, fromDevice: d))
    }

    // ------------------------------------------------------------------ 2. the bug, end to end

    /// Alice's iPhone is in device mode. Bob reinstalled: his account still lists the old phone's
    /// device bundle, his live install reads the account view. Bob's message reaches Alice; Alice's
    /// reply must reach Bob. Red before the fix: the reply was one copy to the OLD device only.
    func testAPeerThatWritesOnTheAccountPathGetsTheAccountEnvelope() async throws {
        let alice = person(), bob = person()
        let a = alice.install("iphone", peerViewSuite: suite())
        let bobOld = bob.install("old", peerViewSuite: suite())
        let b = bob.install("new", peerViewSuite: suite())
        try await a.register(); try await b.register()
        _ = try await deviceMode(a, alice)
        let old = V2Device.Keys(dk: .init(), dsk: .init())
        try await bobOld.publishDeviceBundle(signer: old.signer)          // still bundled, nobody reads it

        // Bob (legacy view) → Alice: an account envelope, which Alice decrypts.
        _ = try await b.sendText(peer: alice.userKey, "hi from bob")
        let inbound = LegacyReaderStubRelay.take(to: alice.userKey)
        XCTAssertEqual(inbound.map(\.toDevice), [nil])
        let got1 = await a.receiveAndDecrypt(pull(inbound))
        XCTAssertEqual(texts(got1), ["hi from bob"])
        let recorded = await a.peerReadsLegacy(bob.userKey)
        XCTAssertTrue(recorded, "a decrypted account envelope from Bob marks his live install as a legacy reader")

        // Alice → Bob.
        _ = try await a.sendText(peer: bob.userKey, "hi bob", selfCopy: true)
        let outbound = LegacyReaderStubRelay.take(to: bob.userKey)
        XCTAssertFalse(legacyView(outbound).isEmpty,
                       "Bob's install reads the account view; per-device copies only (\(outbound.map { $0.toDevice ?? "account" })) never reach it")
        let got2 = await b.receiveAndDecrypt(pull(legacyView(outbound)))
        XCTAssertEqual(texts(got2), ["hi bob"])
    }

    /// Once Bob's new install is approved and writes device copies again, Alice goes back to
    /// per-device copies (the account view is no longer his only one).
    func testThePeerReturnsToPerDeviceCopiesOnceItWritesADeviceCopy() async throws {
        let alice = person(), bob = person()
        let a = alice.install("iphone", peerViewSuite: suite())
        let b = bob.install("phone", peerViewSuite: suite())
        try await a.register(); try await b.register()
        _ = try await deviceMode(a, alice)

        _ = try await b.sendText(peer: alice.userKey, "legacy")
        _ = await a.receiveAndDecrypt(pull(LegacyReaderStubRelay.take(to: alice.userKey)))
        let legacyAfterFirst = await a.peerReadsLegacy(bob.userKey)
        XCTAssertTrue(legacyAfterFirst)

        let kb = try await deviceMode(b, bob)
        _ = try await b.sendText(peer: alice.userKey, "device mode again")
        let copy = LegacyReaderStubRelay.take(to: alice.userKey)
        XCTAssertEqual(copy.map(\.fromDevice), [kb.deviceId])
        let got3 = await a.receiveAndDecrypt(pull(copy))
        XCTAssertEqual(texts(got3), ["device mode again"])
        let legacyAfterDeviceCopy = await a.peerReadsLegacy(bob.userKey)
        XCTAssertFalse(legacyAfterDeviceCopy)

        await a.setPeerDevices(bob.userKey, [kb.deviceId])
        _ = try await a.sendText(peer: bob.userKey, "per device")
        let out = LegacyReaderStubRelay.take(to: bob.userKey)
        XCTAssertEqual(out.map(\.toDevice), [kb.deviceId])
        let got4 = await b.receiveAndDecrypt(pull(out))
        XCTAssertEqual(texts(got4), ["per device"])
    }

    // ------------------------------------------------------------------ 3. what must NOT count

    /// Only an envelope that DECRYPTED counts: a forged `from` cannot flip a peer's view.
    func testAnUndecryptableEnvelopeDoesNotChangeThePeersView() async throws {
        let alice = person(), mallory = person()
        let a = alice.install("iphone", peerViewSuite: suite())
        try await a.register()
        _ = try await deviceMode(a, alice)
        let forged = try JSONDecoder().decode(V2.ReceivedEnvelope.self, from: JSONSerialization.data(withJSONObject: [
            "seq": 1, "v": 4, "msgId": UUID().uuidString, "from": mallory.userKey, "to": alice.userKey,
            "type": "1to1", "header": Data(repeating: 1, count: 40).base64EncodedString(),
            "ciphertext": Data(repeating: 2, count: 40).base64EncodedString(), "ts": 1]))
        _ = await a.receiveAndDecrypt(pull([forged]))
        let flipped = await a.peerReadsLegacy(mallory.userKey)
        XCTAssertFalse(flipped)
    }

    /// Outside device mode there is nothing to learn (every envelope is an account one) and
    /// nothing to change (every send already is the account envelope).
    func testOutsideDeviceModeNothingIsRecorded() async throws {
        let alice = person(), bob = person()
        let a = alice.install("iphone", peerViewSuite: suite())
        let b = bob.install("phone", peerViewSuite: suite())
        try await a.register(); try await b.register()
        _ = try await b.sendText(peer: alice.userKey, "hello")
        let got5 = await a.receiveAndDecrypt(pull(LegacyReaderStubRelay.take(to: alice.userKey)))
        XCTAssertEqual(texts(got5), ["hello"])
        let recorded = await a.peerReadsLegacy(bob.userKey)
        XCTAssertFalse(recorded)
    }

    /// Persisted: after a relaunch the first message to that peer must still take the account path.
    func testTheRecordedViewSurvivesANewRouter() async throws {
        let alice = person(), bob = person()
        let defaults = suite()
        let a = alice.install("iphone", peerViewSuite: defaults)
        let b = bob.install("phone", peerViewSuite: suite())
        try await a.register(); try await b.register()
        _ = try await deviceMode(a, alice)
        _ = try await b.sendText(peer: alice.userKey, "hi")
        _ = await a.receiveAndDecrypt(pull(LegacyReaderStubRelay.take(to: alice.userKey)))

        let relaunched = alice.install("iphone-relaunched", peerViewSuite: defaults)
        let persisted = await relaunched.peerReadsLegacy(bob.userKey)
        XCTAssertTrue(persisted)
        // Not stored under the contact address in clear.
        let keys = UserDefaults(suiteName: defaults)?.dictionaryRepresentation().keys.map { $0 } ?? []
        XCTAssertFalse(keys.contains { $0.contains(bob.userKey) || $0.contains(bob.norm) })
    }
}
