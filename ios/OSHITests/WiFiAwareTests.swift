//
//  WiFiAwareTests.swift
//  OSHITests
//
//  __WIFI_AWARE_2026_11_25__ The parts of the Wi-Fi Aware link that do not need
//  two iPhones: the pairing store, the per-contact state machine (incl. the
//  fallback path), the bridge's "never claims a send it cannot make" rule, the
//  duplicate-socket rule, and the byte framing on the wire.
//
//  What these tests CANNOT show: discovery, Apple's pairing UI, the data path
//  itself. Those need two iPhone 12+ on iOS 26 with the entitlement.
//

import XCTest
import CryptoKit
@testable import OSHI

final class WiFiAwareTests: XCTestCase {

    private var defaults: UserDefaults!
    private var suiteName: String!

    override func setUp() {
        super.setUp()
        suiteName = "WiFiAwareTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    private func makeStore() -> WiFiAwarePairingStore {
        WiFiAwarePairingStore(defaults: defaults, key: "pairings")
    }

    // MARK: - Pairing store

    func testStoreStartsEmpty() {
        let store = makeStore()
        XCTAssertTrue(store.pairedContacts.isEmpty)
        XCTAssertFalse(store.isPaired("alice"))
        XCTAssertNil(store.deviceID(for: "alice"))
        XCTAssertNil(store.contact(forDevice: 7))
    }

    func testBindIsVisibleBothWaysAndPersists() {
        makeStore().bind(contact: "alice", device: 42)
        let reopened = makeStore()   // a fresh instance reads the same defaults
        XCTAssertTrue(reopened.isPaired("alice"))
        XCTAssertEqual(reopened.deviceID(for: "alice"), 42)
        XCTAssertEqual(reopened.contact(forDevice: 42), "alice")
        XCTAssertEqual(reopened.pairedDeviceIDs, [42])
    }

    func testRebindingAContactMovesItToTheNewDevice() {
        let store = makeStore()
        store.bind(contact: "alice", device: 1)
        store.bind(contact: "alice", device: 2)
        XCTAssertEqual(store.deviceID(for: "alice"), 2)
        XCTAssertNil(store.contact(forDevice: 1))
        XCTAssertEqual(store.pairedContacts, ["alice"])
    }

    func testOneDeviceBelongsToOneContact() {
        let store = makeStore()
        store.bind(contact: "alice", device: 9)
        store.bind(contact: "bob", device: 9)
        XCTAssertFalse(store.isPaired("alice"), "a device re-bound to bob must no longer route alice's mail")
        XCTAssertEqual(store.contact(forDevice: 9), "bob")
    }

    func testUnbindRemovesOnlyThatContact() {
        let store = makeStore()
        store.bind(contact: "alice", device: 1)
        store.bind(contact: "bob", device: 2)
        store.unbind(contact: "alice")
        XCTAssertFalse(store.isPaired("alice"))
        XCTAssertEqual(store.deviceID(for: "bob"), 2)
        store.unbind(contact: "bob")
        XCTAssertNil(defaults.data(forKey: "pairings"), "an empty store leaves nothing behind")
    }

    func testEmptyContactIsNeverBound() {
        let store = makeStore()
        store.bind(contact: "", device: 3)
        XCTAssertTrue(store.pairedContacts.isEmpty)
    }

    func testCorruptStoredValueReadsAsEmpty() {
        defaults.set(Data("not json".utf8), forKey: "pairings")
        XCTAssertTrue(makeStore().pairedContacts.isEmpty)
    }

    // MARK: - State machine

    private func run(_ events: [WiFiAwareLinkEvent], from start: WiFiAwareLinkState = .unsupported) -> WiFiAwareLinkState {
        events.reduce(start, WiFiAwareLinkReducer.reduce)
    }

    func testHappyPathAvailablePairedConnectingConnected() {
        var s = WiFiAwareLinkState.unsupported
        s = WiFiAwareLinkReducer.reduce(s, .capability(supported: true)); XCTAssertEqual(s, .available)
        s = WiFiAwareLinkReducer.reduce(s, .paired);                      XCTAssertEqual(s, .paired)
        s = WiFiAwareLinkReducer.reduce(s, .connecting);                  XCTAssertEqual(s, .connecting)
        s = WiFiAwareLinkReducer.reduce(s, .verified);                    XCTAssertEqual(s, .connected)
        XCTAssertTrue(s.canCarryMessages)
    }

    func testFailureFallsBackAndRecovers() {
        let failed = run([.capability(supported: true), .paired, .connecting, .failed(reason: "timeout")])
        XCTAssertEqual(failed, .fallback(reason: "timeout"))
        XCTAssertFalse(failed.canCarryMessages, "fallback never carries messages — other transports do")
        XCTAssertTrue(failed.isPaired, "a failed link does not forget the pairing")
        XCTAssertEqual(WiFiAwareLinkReducer.reduce(failed, .connecting), .connecting)
        XCTAssertEqual(run([.connecting, .verified], from: failed), .connected)
    }

    func testConnectedLinkThatFailsFallsBack() {
        let s = run([.capability(supported: true), .paired, .connecting, .verified, .failed(reason: "terminated")])
        XCTAssertEqual(s, .fallback(reason: "terminated"))
    }

    func testDisconnectGoesBackToPaired() {
        XCTAssertEqual(run([.capability(supported: true), .paired, .connecting, .verified, .disconnected]), .paired)
        XCTAssertEqual(run([.capability(supported: true), .paired, .connecting, .disconnected]), .paired)
    }

    func testUnsupportedIgnoresEverythingButCapability() {
        for e: WiFiAwareLinkEvent in [.paired, .connecting, .verified, .disconnected, .failed(reason: "x"), .unpaired] {
            XCTAssertEqual(WiFiAwareLinkReducer.reduce(.unsupported, e), .unsupported, "\(e)")
        }
    }

    func testLosingCapabilityAlwaysWins() {
        for s: WiFiAwareLinkState in [.available, .paired, .connecting, .connected, .fallback(reason: "x")] {
            XCTAssertEqual(WiFiAwareLinkReducer.reduce(s, .capability(supported: false)), .unsupported, "\(s)")
        }
    }

    func testUnpairAlwaysReturnsToAvailable() {
        for s: WiFiAwareLinkState in [.paired, .connecting, .connected, .fallback(reason: "x")] {
            XCTAssertEqual(WiFiAwareLinkReducer.reduce(s, .unpaired), .available, "\(s)")
        }
    }

    func testAnUnpairedContactCanNeverBecomeConnected() {
        // A verified socket from a contact the user never paired is not a link.
        for e: WiFiAwareLinkEvent in [.connecting, .verified, .disconnected, .failed(reason: "x")] {
            XCTAssertEqual(WiFiAwareLinkReducer.reduce(.available, e), .available, "\(e)")
        }
    }

    func testOnlyConnectedCarriesMessages() {
        let all: [WiFiAwareLinkState] = [.unsupported, .available, .paired, .connecting, .connected, .fallback(reason: "x")]
        XCTAssertEqual(all.filter(\.canCarryMessages), [.connected])
    }

    // MARK: - Bridge (what MessageManager sees)

    func testBridgeIsUnsupportedAndSilentUntilTheDriverSaysOtherwise() async {
        let bridge = WiFiAwareBridge(store: makeStore())
        bridge.store.bind(contact: "alice", device: 1)
        XCTAssertEqual(bridge.state(for: "alice"), .unsupported)
        XCTAssertFalse(bridge.isConnected("alice"))
        let sent = await bridge.send(Self.sampleMessage(), to: "alice")
        XCTAssertFalse(sent, "no Wi-Fi Aware → the send is declined so normal routing takes it")
    }

    func testBridgeStateFollowsStoreAndEvents() {
        let bridge = WiFiAwareBridge(store: makeStore())
        bridge.setSupported(true)
        XCTAssertEqual(bridge.state(for: "alice"), .available)
        bridge.didPair(contact: "alice", device: 5)
        XCTAssertEqual(bridge.state(for: "alice"), .paired)
        XCTAssertEqual(bridge.store.deviceID(for: "alice"), 5)
        bridge.apply(.connecting, for: "alice")
        bridge.apply(.verified, for: "alice")
        XCTAssertTrue(bridge.isConnected("alice"))
        bridge.apply(.failed(reason: "gone"), for: "alice")
        XCTAssertFalse(bridge.isConnected("alice"))
        bridge.unpair(contact: "alice")
        XCTAssertEqual(bridge.state(for: "alice"), .available)
        XCTAssertFalse(bridge.store.isPaired("alice"))
        bridge.setSupported(false)
        XCTAssertEqual(bridge.state(for: "alice"), .unsupported)
    }

    func testConnectedWithoutADriverStillDeclines() async {
        let bridge = WiFiAwareBridge(store: makeStore())
        bridge.setSupported(true)
        bridge.didPair(contact: "alice", device: 5)
        bridge.apply(.connecting, for: "alice")
        bridge.apply(.verified, for: "alice")
        let sent = await bridge.send(Self.sampleMessage(), to: "alice")
        XCTAssertFalse(sent)
    }

    func testPendingPairingIsConsumedOnceAndOnlyForThatContact() {
        let bridge = WiFiAwareBridge(store: makeStore())
        let t0 = Date()
        bridge.beginPairing(contact: "alice", now: t0)
        XCTAssertFalse(bridge.consumePendingPairing(for: "bob", now: t0))
        XCTAssertTrue(bridge.consumePendingPairing(for: "alice", now: t0))
        XCTAssertFalse(bridge.consumePendingPairing(for: "alice", now: t0), "one pairing screen binds one device")
        bridge.beginPairing(contact: "alice", now: t0)
        XCTAssertFalse(bridge.consumePendingPairing(
            for: "alice", now: t0.addingTimeInterval(WiFiAwareBridge.pendingPairingWindow + 1)),
            "an old pairing screen does not bind a device hours later")
    }

    func testFeatureIsOffInABuildWithoutTheSetting() {
        // The test host is a normal build: OSHI_WIFI_AWARE is not defined, so
        // Info.plist carries "" and the feature must be dormant.
        XCTAssertFalse(WiFiAwareBridge.isEnabledInThisBuild)
    }

    func testInfoPlistDeclaresTheServiceTheLinkUses() throws {
        let services = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "WiFiAwareServices") as? [String: Any])
        let entry = try XCTUnwrap(services["_oshi-aware._tcp"] as? [String: Any])
        XCTAssertNotNil(entry["Publishable"])
        XCTAssertNotNil(entry["Subscribable"])
        // Apple: name component ≤ 15 chars of [A-Za-z0-9-], not starting/ending with '-'.
        let name = "oshi-aware"
        XCTAssertLessThanOrEqual(name.count, 15)
        XCTAssertNil(name.range(of: "[^A-Za-z0-9-]", options: .regularExpression))
    }

    // MARK: - Duplicate sockets / ack timeout

    func testBothSidesKeepTheSameSocket() {
        let a = "AAAA", b = "BBBB"
        // Socket dialled by A: A keeps it, B keeps it.
        XCTAssertTrue(WiFiAwareDuplicateRule.keep(initiatorKey: a, myKey: a, peerKey: b))
        XCTAssertTrue(WiFiAwareDuplicateRule.keep(initiatorKey: a, myKey: b, peerKey: a))
        // Socket dialled by B: both drop it.
        XCTAssertFalse(WiFiAwareDuplicateRule.keep(initiatorKey: b, myKey: a, peerKey: b))
        XCTAssertFalse(WiFiAwareDuplicateRule.keep(initiatorKey: b, myKey: b, peerKey: a))
    }

    func testAckTimeoutScalesAndIsBounded() {
        XCTAssertEqual(WiFiAwareAckTimeout.seconds(forPayloadBytes: 0), 4)
        XCTAssertEqual(WiFiAwareAckTimeout.seconds(forPayloadBytes: 10_000_000), 14)
        XCTAssertEqual(WiFiAwareAckTimeout.seconds(forPayloadBytes: 500_000_000), 60)
    }

    // MARK: - Framing

    func testFrameBytesArePinned() {
        let f = WiFiAwareFrame(kind: .data, payload: Data([0xAA, 0xBB, 0xCC]))
        XCTAssertEqual([UInt8](f.encoded()), [0x01, 0x03, 0x00, 0x00, 0x00, 0x03, 0xAA, 0xBB, 0xCC])
        let big = WiFiAwareFrame(kind: .ack, payload: Data(count: 0x0102_0304 & 0xFFFF))
        XCTAssertEqual([UInt8](big.encoded().prefix(6)), [0x01, 0x04, 0x00, 0x00, 0x03, 0x04])
    }

    func testRoundTripEveryKind() throws {
        for kind in [WiFiAwareFrame.Kind.hello, .proof, .data, .ack] {
            let f = WiFiAwareFrame(kind: kind, payload: Data("payload-\(kind)".utf8))
            var d = WiFiAwareFrameDecoder()
            XCTAssertEqual(try d.feed(f.encoded()), [f])
            XCTAssertEqual(d.bufferedByteCount, 0)
        }
    }

    func testEmptyPayload() throws {
        let f = WiFiAwareFrame(kind: .ack, payload: Data())
        var d = WiFiAwareFrameDecoder()
        XCTAssertEqual(try d.feed(f.encoded()), [f])
    }

    func testFramesSplitAtEveryByteBoundaryDecodeIdentically() throws {
        let frames = [
            WiFiAwareFrame(kind: .hello, payload: Data((0..<40).map { UInt8($0) })),
            WiFiAwareFrame(kind: .data, payload: Data(repeating: 0x5A, count: 300)),
            WiFiAwareFrame(kind: .ack, payload: Data("m-1".utf8)),
        ]
        let wire = frames.reduce(Data()) { $0 + $1.encoded() }
        for cut in 0...wire.count {
            var d = WiFiAwareFrameDecoder()
            var out = try d.feed(wire.prefix(cut))
            out += try d.feed(wire.suffix(from: cut))
            XCTAssertEqual(out, frames, "cut at \(cut)")
            XCTAssertEqual(d.bufferedByteCount, 0)
        }
        // And one byte at a time.
        var d = WiFiAwareFrameDecoder()
        var out: [WiFiAwareFrame] = []
        for b in wire { out += try d.feed(Data([b])) }
        XCTAssertEqual(out, frames)
    }

    func testHeaderRejections() {
        XCTAssertThrowsError(try WiFiAwareFrame.parseHeader(Data([1, 3, 0]))) {
            XCTAssertEqual($0 as? WiFiAwareFrame.HeaderError, .truncated)
        }
        XCTAssertThrowsError(try WiFiAwareFrame.parseHeader(Data([2, 3, 0, 0, 0, 0]))) {
            XCTAssertEqual($0 as? WiFiAwareFrame.HeaderError, .badVersion(2))
        }
        XCTAssertThrowsError(try WiFiAwareFrame.parseHeader(Data([1, 99, 0, 0, 0, 0]))) {
            XCTAssertEqual($0 as? WiFiAwareFrame.HeaderError, .unknownKind(99))
        }
        // 0x7FFFFFFF bytes announced: refused from the header alone.
        var d = WiFiAwareFrameDecoder()
        XCTAssertThrowsError(try d.feed(Data([1, 3, 0x7F, 0xFF, 0xFF, 0xFF]))) {
            XCTAssertEqual($0 as? WiFiAwareFrame.HeaderError, .tooLarge(0x7FFF_FFFF))
        }
    }

    func testHelloAndProofPayloads() throws {
        let nonce = Data(repeating: 7, count: MeshPeerAuth.nonceLength)
        let hello = WiFiAwareFrame.hello(publicKey: "key+/=", nonce: nonce)
        let (key, n) = try XCTUnwrap(WiFiAwareFrame.parseHello(hello.payload))
        XCTAssertEqual(key, "key+/=")
        XCTAssertEqual(n, nonce)
        XCTAssertNil(WiFiAwareFrame.parseHello(Data("no-separator".utf8)))
        XCTAssertNil(WiFiAwareFrame.parseHello(Data([0]) + nonce), "empty key")
        XCTAssertNil(WiFiAwareFrame.parseHello(Data("k".utf8) + Data([0]) + nonce.prefix(5)), "short nonce")

        let tag = Data(repeating: 9, count: MeshPeerAuth.tagLength)
        let proof = WiFiAwareFrame.proof(nonce: nonce, tag: tag)
        let parsed = try XCTUnwrap(WiFiAwareFrame.parseProof(proof.payload))
        XCTAssertEqual(parsed.nonce, nonce)
        XCTAssertEqual(parsed.tag, tag)
        XCTAssertNil(WiFiAwareFrame.parseProof(Data(count: 10)))
    }

    /// The handshake the link enforces, run with two real key pairs: only the
    /// holder of the announced key's private half produces a tag that verifies.
    func testIdentityProofAcceptsTheKeyHolderAndRejectsAnImpostor() throws {
        let alice = Curve25519.KeyAgreement.PrivateKey()
        let bob = Curve25519.KeyAgreement.PrivateKey()
        let mallory = Curve25519.KeyAgreement.PrivateKey()
        let nonce = MeshPeerAuth.makeNonce()

        // Bob challenges "Alice" with `nonce`; Bob verifies with DH(bob, alice_pub).
        let bobSide = try MeshPeerAuth.sharedSecret(myPrivateKeyRaw: bob.rawRepresentation,
                                                    peerPublicKeyRaw: alice.publicKey.rawRepresentation)
        let aliceTag = MeshPeerAuth.tag(sharedSecret: try MeshPeerAuth.sharedSecret(
            myPrivateKeyRaw: alice.rawRepresentation, peerPublicKeyRaw: bob.publicKey.rawRepresentation), nonce: nonce)
        XCTAssertTrue(MeshPeerAuth.verify(tag: aliceTag, sharedSecret: bobSide, nonce: nonce))

        // Mallory announces Alice's key but only has her own private key.
        let malloryTag = MeshPeerAuth.tag(sharedSecret: try MeshPeerAuth.sharedSecret(
            myPrivateKeyRaw: mallory.rawRepresentation, peerPublicKeyRaw: bob.publicKey.rawRepresentation), nonce: nonce)
        XCTAssertFalse(MeshPeerAuth.verify(tag: malloryTag, sharedSecret: bobSide, nonce: nonce))
    }

    /// The DATA frame carries the mesh-wire copy: ciphertext only, no
    /// plaintext / media sidecar, and it decodes back to the same envelope.
    func testDataFrameCarriesOnlyTheWireSafeEnvelope() throws {
        var m = Self.sampleMessage()
        m.plaintextContent = "secret"
        m.mediaAttachment = Data([1, 2, 3])
        let wire = try JSONEncoder().encode(m.meshWireSafe())
        let frame = WiFiAwareFrame(kind: .data, payload: wire)
        var d = WiFiAwareFrameDecoder()
        let back = try XCTUnwrap(try d.feed(frame.encoded()).first)
        let decoded = try JSONDecoder().decode(SecureMessage.self, from: back.payload)
        XCTAssertEqual(decoded.id, m.id)
        XCTAssertEqual(decoded.encryptedContent.ciphertext, m.encryptedContent.ciphertext)
        XCTAssertNil(decoded.plaintextContent)
        XCTAssertNil(decoded.mediaAttachment)
        XCTAssertFalse(String(decoding: wire, as: UTF8.self).contains("secret"))
    }

    // MARK: - Fixtures

    static func sampleMessage() -> SecureMessage {
        var m = SecureMessage(
            id: "msg-1",
            senderAddress: "alice",
            recipientAddress: "bob",
            encryptedContent: EncryptedMessage(
                ciphertext: "3q2+7w==",
                signature: "c2ln",
                senderPublicKey: "alice",
                timestamp: Date(timeIntervalSince1970: 1_700_000_000)
            ),
            timestamp: Date(timeIntervalSince1970: 1_700_000_000),
            isRead: false,
            deliveryStatus: .pending
        )
        m.senderPublicKey = "alice"
        m.recipientPublicKey = "bob"
        return m
    }
}
