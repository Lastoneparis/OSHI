//
//  V2PaddingTests.swift
//  OSHITests
//
//  __V2_PADDING_2026_11_09__ Bucketed plaintext padding on the internet relay path.
//  The cross-platform vector below is asserted byte for byte by
//  OSHI-Android `V2PaddingTest` and OSHI-Desktop `V2PaddingDesktopTest`: change it
//  in all three or in none.
//

import XCTest
import CryptoKit
@testable import OSHI

final class V2PaddingTests: XCTestCase {

    private var suite: UserDefaults!
    private let suiteName = "V2PaddingTests"

    override func setUp() {
        super.setUp()
        suite = UserDefaults(suiteName: suiteName)
        suite.removePersistentDomain(forName: suiteName)
        V2PaddingPeers.defaults = suite
    }

    override func tearDown() {
        suite.removePersistentDomain(forName: suiteName)
        V2PaddingPeers.defaults = .standard
        super.tearDown()
    }

    private func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
    private func sha(_ d: Data) -> String { hex(Data(SHA256.hash(data: d))) }
    private func bytes(_ hex: String) -> Data {
        var out = Data(); var i = hex.startIndex
        while i < hex.endIndex {
            let j = hex.index(i, offsetBy: 2)
            out.append(UInt8(hex[i..<j], radix: 16)!); i = j
        }
        return out
    }

    // MARK: Shared cross-platform vector

    func testSharedVector() {
        let pt = Data("héllo 👋".utf8)
        XCTAssertEqual(hex(pt), "68c3a96c6c6f20f09f918b")
        let padded = V2Padding.pad(pt)
        XCTAssertEqual(padded.count, 256)
        XCTAssertEqual(hex(padded.prefix(15)), "ff500168c3a96c6c6f20f09f918b80")
        XCTAssertTrue(padded.dropFirst(15).allSatisfy { $0 == 0 })
        XCTAssertEqual(sha(padded), "0c052d9de9ec87516729769b8157f701379a272b35818911dae78ad5b4cba84c")
        XCTAssertEqual(V2Padding.unpad(padded), .padded(pt))

        let a253 = V2Padding.pad(Data(repeating: 0x61, count: 253))
        XCTAssertEqual(a253.count, 1024)
        XCTAssertEqual(sha(a253), "d16075e9b0dadb54e365d0163e2409bee1f5b5f54d3a9290ccadca898ceaa438")
        XCTAssertEqual(V2Padding.capabilityBit, 4)
        XCTAssertEqual(V2Padding.buckets, [256, 1024, 4096, 16384, 65536, 131072])
    }

    // MARK: Round trip and bucket boundaries

    func testRoundTripAcrossSizes() {
        for n in [0, 1, 60, 251, 252, 253, 1020, 1021, 4092, 4093, 16380, 65532, 65533, 131068] {
            let pt = Data((0..<n).map { UInt8(truncatingIfNeeded: $0 * 31 + 7) })
            let padded = V2Padding.pad(pt)
            XCTAssertTrue(V2Padding.buckets.contains(padded.count), "n=\(n) → \(padded.count)")
            XCTAssertEqual(V2Padding.unpad(padded), .padded(pt), "n=\(n)")
        }
    }

    func testBucketBoundaries() {
        XCTAssertEqual(V2Padding.paddedLength(forPlaintextLength: 0), 256)
        XCTAssertEqual(V2Padding.paddedLength(forPlaintextLength: 252), 256)
        XCTAssertEqual(V2Padding.paddedLength(forPlaintextLength: 253), 1024)
        XCTAssertEqual(V2Padding.paddedLength(forPlaintextLength: 1020), 1024)
        XCTAssertEqual(V2Padding.paddedLength(forPlaintextLength: 1021), 4096)
        XCTAssertEqual(V2Padding.paddedLength(forPlaintextLength: 131068), 131072)
        XCTAssertNil(V2Padding.paddedLength(forPlaintextLength: 131069))
        // Too large for the last bucket: sent exactly as today, no flag.
        let big = Data(repeating: 0x41, count: 131069)
        XCTAssertEqual(V2Padding.pad(big), big)
        XCTAssertEqual(V2Padding.unpad(big), .plain(big))
    }

    /// The privacy property itself: a receipt and a sentence are the same size on the relay.
    func testReceiptAndSentenceShareACiphertextSize() throws {
        let receipt = Data("📬DELIVERY_RECEIPT📬\(UUID().uuidString)".utf8)
        let sentence = Data("On se retrouve devant la gare à 20 h, je serai un peu en retard.".utf8)
        let ad = Data()
        let (alice, _) = try ratchetPair()
        let r = try OSHICryptoV2.ratchetEncrypt(alice, plaintext: V2Padding.pad(receipt), associatedData: ad)
        let s = try OSHICryptoV2.ratchetEncrypt(alice, plaintext: V2Padding.pad(sentence), associatedData: ad)
        XCTAssertEqual(r.ciphertext.count, s.ciphertext.count)
        XCTAssertEqual(r.ciphertext.count, 256 + 16)
    }

    func testPaddedPlaintextSurvivesTheRealRatchet() throws {
        let ad = Data()
        let (alice, bob) = try ratchetPair()
        let pt = Data("hello over the relay".utf8)
        let e = try OSHICryptoV2.ratchetEncrypt(alice, plaintext: V2Padding.pad(pt), associatedData: ad)
        let d = try OSHICryptoV2.ratchetDecrypt(bob, header: e.header, ciphertext: e.ciphertext, associatedData: ad)
        XCTAssertEqual(V2Padding.unpad(d), .padded(pt))
    }

    // MARK: Flag absent ⇒ untouched; malformed ⇒ rejected

    func testUnflaggedPlaintextIsUntouched() {
        for s in ["", "ok", "📬DELIVERY_RECEIPT📬abc", "{\"kind\":\"v2file\"}", "text ending in \u{0}\u{0}"] {
            let d = Data(s.utf8)
            XCTAssertEqual(V2Padding.unpad(d), .plain(d), s)
        }
        // Trailing 0x80 0x00… on an UNFLAGGED message is never taken for padding.
        let tricky = Data([0x41, 0x80, 0x00, 0x00])
        XCTAssertEqual(V2Padding.unpad(tricky), .plain(tricky))
    }

    func testMalformedPaddingIsRejectedWithoutCrashing() {
        XCTAssertEqual(V2Padding.unpad(Data([0xFF])), .malformed)
        XCTAssertEqual(V2Padding.unpad(Data([0xFF, 0x50])), .malformed)
        XCTAssertEqual(V2Padding.unpad(Data([0xFF, 0x50, 0x01])), .malformed)
        XCTAssertEqual(V2Padding.unpad(Data([0xFF, 0x50, 0x02, 0x80])), .malformed, "unknown version")
        XCTAssertEqual(V2Padding.unpad(Data([0xFF, 0x51, 0x01, 0x80])), .malformed)
        XCTAssertEqual(V2Padding.unpad(Data([0xFF, 0x50, 0x01, 0x41, 0x00, 0x00])), .malformed, "no 0x80")
        XCTAssertEqual(V2Padding.unpad(Data([0xFF, 0x50, 0x01, 0x41, 0x81])), .malformed)
        XCTAssertEqual(V2Padding.unpad(Data([0xFF, 0x50, 0x01] + [UInt8](repeating: 0, count: 300))), .malformed)
        XCTAssertEqual(V2Padding.unpad(Data([0xFF, 0x50, 0x01, 0x80])), .padded(Data()))
        XCTAssertEqual(V2DecryptFailureKind.classify(V2MessageRouter.RouterError.malformedPadding), .malformedEnvelope,
                       "a malformed tail must be acked and dropped, never trigger a session heal")
    }

    // MARK: Capability gating

    func testCapabilityIsAdvertised() {
        XCTAssertEqual(RatchetHealSignal.Caps.padding, V2Padding.capabilityBit)
        XCTAssertTrue(RatchetHealSignal.Caps.has(RatchetHealSignal.Caps.mine, V2Padding.capabilityBit))
    }

    func testOldOrUnknownPeerIsNeverPadded() {
        let peer = Data(repeating: 1, count: 32).base64EncodedString()
        XCTAssertFalse(V2PaddingPeers.slotCapable(peer: peer, device: nil), "unknown ⇒ no padding")
        V2PaddingPeers.noteBundle(peer: peer, device: nil, caps: nil)
        XCTAssertFalse(V2PaddingPeers.slotCapable(peer: peer, device: nil), "pre-caps bundle ⇒ no padding")
        V2PaddingPeers.noteBundle(peer: peer, device: nil, caps: 3)
        XCTAssertFalse(V2PaddingPeers.slotCapable(peer: peer, device: nil), "today's clients (caps 3) ⇒ no padding")
        XCTAssertFalse(V2Padding.shouldPad(padGate: true, slotCapable: V2PaddingPeers.slotCapable(peer: peer, device: nil)))
        V2PaddingPeers.noteBundle(peer: peer, device: nil, caps: 7)
        XCTAssertTrue(V2PaddingPeers.slotCapable(peer: peer, device: nil))
        // A later bundle without the bit (the slot's counterpart changed) turns it off again.
        V2PaddingPeers.noteBundle(peer: peer, device: nil, caps: 3)
        XCTAssertFalse(V2PaddingPeers.slotCapable(peer: peer, device: nil))
    }

    func testSlotsAreIndependent() {
        let peer = Data(repeating: 2, count: 32).base64EncodedString()
        V2PaddingPeers.noteBundle(peer: peer, device: "devNEW", caps: 7)
        XCTAssertTrue(V2PaddingPeers.slotCapable(peer: peer, device: "devNEW"))
        XCTAssertFalse(V2PaddingPeers.slotCapable(peer: peer, device: "devOLD"))
        XCTAssertFalse(V2PaddingPeers.slotCapable(peer: peer, device: nil), "a device bundle says nothing of the account session")
        // base64url spelling of the same identity lands on the same slot.
        let url = peer.replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .trimmingCharacters(in: CharacterSet(charactersIn: "="))
        XCTAssertTrue(V2PaddingPeers.slotCapable(peer: url, device: "devNEW"))
    }

    func testInboundProofAndCounterpartChange() {
        let peer = Data(repeating: 3, count: 32).base64EncodedString()
        V2PaddingPeers.noteInbound(slot: peer, padded: false, carriedX3DH: false)
        XCTAssertFalse(V2PaddingPeers.slotCapable(peer: peer, device: nil))
        V2PaddingPeers.noteInbound(slot: peer, padded: true, carriedX3DH: false)
        XCTAssertTrue(V2PaddingPeers.slotCapable(peer: peer, device: nil), "a padded message proves the sender strips padding")
        V2PaddingPeers.noteInbound(slot: peer, padded: false, carriedX3DH: false)
        XCTAssertTrue(V2PaddingPeers.slotCapable(peer: peer, device: nil), "an unpadded one does not disprove it")
        V2PaddingPeers.noteInbound(slot: peer, padded: false, carriedX3DH: true)
        XCTAssertFalse(V2PaddingPeers.slotCapable(peer: peer, device: nil), "a new X3DH session may be another (older) install")
        V2PaddingPeers.noteInbound(slot: peer + "#devA", padded: true, carriedX3DH: true)
        XCTAssertTrue(V2PaddingPeers.slotCapable(peer: peer, device: "devA"))
        // Device list: upgrade only.
        V2PaddingPeers.noteDeviceList(peer: peer, device: "devA", caps: 3)
        XCTAssertTrue(V2PaddingPeers.slotCapable(peer: peer, device: "devA"))
        V2PaddingPeers.noteDeviceList(peer: peer, device: "devB", caps: nil)
        XCTAssertFalse(V2PaddingPeers.slotCapable(peer: peer, device: "devB"))
    }

    func testGroupWithOneOldMemberIsNotPadded() {
        let me = Data(repeating: 9, count: 32).base64EncodedString()
        let a = Data(repeating: 4, count: 32).base64EncodedString()
        let b = Data(repeating: 5, count: 32).base64EncodedString()
        let old = Data(repeating: 6, count: 32).base64EncodedString()
        V2PaddingPeers.noteBundle(peer: a, device: nil, caps: 7)
        V2PaddingPeers.noteBundle(peer: b, device: "d1", caps: 7)
        V2PaddingPeers.noteBundle(peer: old, device: nil, caps: 3)
        XCTAssertTrue(V2PaddingPeers.groupAllows(members: [me, a, b], me: me))
        XCTAssertFalse(V2PaddingPeers.groupAllows(members: [me, a, b, old], me: me))
        let unknown = Data(repeating: 7, count: 32).base64EncodedString()
        XCTAssertFalse(V2PaddingPeers.groupAllows(members: [a, b, unknown], me: me), "unknown member ⇒ no padding")
        XCTAssertFalse(V2PaddingPeers.groupAllows(members: [me], me: me))
        // Even a capable member's copy is unpadded when the gate is shut.
        XCTAssertFalse(V2Padding.shouldPad(padGate: false, slotCapable: true))
    }

    // MARK: No padding on mesh / LoRa

    /// The padding is applied in ONE place, the relay seal (`encrypt1to1`), and the
    /// offline transports never call it. Guard both facts in the source, so a future
    /// "pad everywhere" cannot quietly reach a 200-byte LoRa frame.
    func testPaddingNeverReachesTheOfflineTransports() throws {
        let osh = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("OSHI")
        let files = try FileManager.default.contentsOfDirectory(at: osh, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "swift" }
        XCTAssertGreaterThan(files.count, 50, "source tree not found at \(osh.path)")
        var padders: [String] = []
        for f in files {
            let text = try String(contentsOf: f, encoding: .utf8)
            if text.contains("V2Padding.pad(") { padders.append(f.lastPathComponent) }
            let name = f.lastPathComponent
            if name.hasPrefix("Meshtastic") || name.hasPrefix("CrossPlatformMesh") || name.hasPrefix("MeshCore")
                || name.hasPrefix("LoRa") || name.hasPrefix("OshiMeshRadio") || name == "MessageTextSplitter.swift"
                || name == "MessageSendPolicy.swift" || name == "GroupEnvelopeV3.swift" {
                XCTAssertFalse(text.contains("V2Padding"), "\(name) must not pad")
            }
        }
        XCTAssertEqual(padders, ["V2MessageRouter.swift"])
        let router = try String(contentsOf: osh.appendingPathComponent("V2MessageRouter.swift"), encoding: .utf8)
        XCTAssertEqual(router.components(separatedBy: "V2Padding.pad(").count - 1, 1,
                       "exactly one call: inside encrypt1to1, the relay seal")
    }

    // MARK: helpers

    private func ratchetPair() throws -> (OSHICryptoV2.RatchetState, OSHICryptoV2.RatchetState) {
        let sk = Data(repeating: 0x42, count: 32)
        let bobSpk = OSHICryptoV2.generateX25519()
        let alice = try OSHICryptoV2.ratchetInitAlice(SK: sk, bobRatchetPub: bobSpk.pub)
        let bob = OSHICryptoV2.ratchetInitBob(SK: sk, bobRatchetPair: bobSpk)
        return (alice, bob)
    }
}
