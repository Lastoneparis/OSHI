//
//  MeshCoreRadioTests.swift
//
//  Pins OSHI-over-MeshCore to the two things it must match byte for byte: the "OB" envelope of
//  oshi-meshcore-bridge (vectors below were produced by oshi_bridge/wire.py) and the MeshCore companion
//  protocol (examples/companion_radio/MyMesh.cpp). A drift in either reads as "MeshCore never delivers":
//  the bridge drops a foreign envelope silently and the radio answers ERR to a malformed command.
//

import XCTest
import CoreBluetooth
@testable import OSHI

final class MeshCoreRadioTests: XCTestCase {

    func testEnvelopeMatchesBridge() throws {
        let frame = Data((0..<200).map { UInt8($0 & 0xFF) })
        let parts = try XCTUnwrap(OSHIBridgeWire.split(frame, sender: 0x11223344, seq: 7))
        XCTAssertEqual(parts.map(\.count), [160, 58])
        XCTAssertEqual(parts[0].prefix(12).mcHex, "4f4210443322110702000102")
        XCTAssertEqual(parts[1].prefix(12).mcHex, "4f4210443322110712979899")

        let joiner = OSHIBridgeWire.Joiner()
        let p1 = try XCTUnwrap(OSHIBridgeWire.parse(parts[1]))
        let p0 = try XCTUnwrap(OSHIBridgeWire.parse(parts[0]))
        XCTAssertEqual(p0.sender, 0x11223344)
        XCTAssertNil(joiner.push(p1))
        XCTAssertEqual(joiner.push(p0), frame)
    }

    func testChannelSecretMatchesBridge() {
        XCTAssertEqual(OSHIBridgeWire.channelSecret.mcHex, "485a8d4bcd50fd075ea87d7ee19f4135")
    }

    func testEnvelopeRejectsForeignDatagrams() {
        XCTAssertNil(OSHIBridgeWire.parse(Data("hello world".utf8)))
        XCTAssertNil(OSHIBridgeWire.parse(Data([0x4F, 0x42, 0x10, 0, 0, 0, 0, 0, 0x22])))   // part 2 of 2
        XCTAssertNil(OSHIBridgeWire.parse(Data([0x4F, 0x42, 0x20, 0, 0, 0, 0, 0, 0x01])))   // version 2
    }

    func testCompanionCommands() {
        XCTAssertEqual(MeshCore.appStart().prefix(8), Data([1, 0, 0, 0, 0, 0, 0, 0]))
        XCTAssertEqual(MeshCore.getChannel(3), Data([31, 3]))
        let set = MeshCore.setChannel(7, name: "#oshi-bridge", secret: OSHIBridgeWire.channelSecret)
        XCTAssertEqual(set.count, 2 + 32 + 16)             // the firmware refuses 2+32+32 (256-bit) as unsupported
        XCTAssertEqual(set.prefix(2), Data([32, 7]))
        XCTAssertEqual(set[2..<14], Data("#oshi-bridge".utf8))
        XCTAssertEqual(set[14..<34], Data(count: 20))
        XCTAssertEqual(MeshCore.sendChannelData(7, dataType: 0xFF4F, payload: Data([0xAA])),
                       Data([62, 7, 0xFF, 0x4F, 0xFF, 0xAA]))
    }

    func testCompanionReplies() throws {
        var info = Data([18, 7]) + Data("#oshi-bridge".utf8)
        info.append(Data(count: 32 - 12))
        info.append(OSHIBridgeWire.channelSecret)
        let ch = try XCTUnwrap(MeshCore.parseChannelInfo(info))
        XCTAssertEqual(ch.idx, 7)
        XCTAssertEqual(ch.name, "#oshi-bridge")
        XCTAssertEqual(ch.secret, OSHIBridgeWire.channelSecret)

        let recv = Data([27, 0x18, 0, 0, 7, 3, 0x4F, 0xFF, 3, 0x4F, 0x42, 0x10])
        let d = try XCTUnwrap(MeshCore.parseChannelData(recv))
        XCTAssertEqual(d.channelIdx, 7)
        XCTAssertEqual(d.dataType, 0xFF4F)
        XCTAssertEqual(d.payload, Data([0x4F, 0x42, 0x10]))
        XCTAssertNil(MeshCore.parseChannelData(Data([27, 0, 0, 0, 7, 3, 0x4F, 0xFF, 9, 1])))   // truncated

        let selfInfo = Data([5, 1, 22, 22]) + Data((0..<32).map { UInt8($0) }) + Data(count: 20)
        XCTAssertEqual(MeshCore.selfPublicKey(selfInfo), Data((0..<32).map { UInt8($0) }))
    }

    /// End to end on the wire: a message a MeshCore phone sends comes back out of the envelope and the OMP
    /// reassembler as the same body, with the phone's id as origin.
    func testOMPOverEnvelopeRoundTrip() throws {
        let body = OMP.encodeBody(Data(repeating: 0x41, count: 900))
        let frames = try XCTUnwrap(OMP.dataFrames(msgId: 99, dest: OMP.broadcast, body: body, origin: 0xCAFEBABE))
        let joiner = OSHIBridgeWire.Joiner()
        let rx = OMPReassembler()
        var out: Data?
        for (i, fr) in frames.enumerated() {
            for dg in try XCTUnwrap(OSHIBridgeWire.split(fr, sender: 0xCAFEBABE, seq: UInt8(i))) {
                if let joined = joiner.push(try XCTUnwrap(OSHIBridgeWire.parse(dg))) {
                    let f = try XCTUnwrap(OMP.parseData(joined))
                    XCTAssertEqual(f.origin, 0xCAFEBABE)
                    if let full = rx.accept(f) { out = full }
                }
            }
        }
        XCTAssertEqual(out, body)
    }
}

// MARK: - Plain text with ordinary MeshCore users (__MESHCORE_INTEROP_2026_11_25__)
//
// Frames below are written out byte by byte from examples/companion_radio/MyMesh.cpp (handleCmdFrame,
// queueMessage, onChannelMessageRecv, writeContactRespFrame, processAck), not produced by the code under test.

final class MeshCoreInteropTests: XCTestCase {

    private let prefix = Data([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF])

    // MARK: Commands

    func testSendTextFrame() {
        // CMD_SEND_TXT_MSG, TXT_TYPE_PLAIN, attempt, timestamp LE32, pub_key_prefix[6], text
        let f = MeshCore.sendText(attempt: 1, timestamp: 0x01020304, pubKeyPrefix: prefix + Data([0x11, 0x22]), text: "hi")
        XCTAssertEqual(f, Data([0x02, 0x00, 0x01, 0x04, 0x03, 0x02, 0x01, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF, 0x68, 0x69]))
        XCTAssertGreaterThanOrEqual(f.count, 14)                           // the firmware's `len >= 14`
        XCTAssertEqual(MeshCore.sendText(attempt: 9, timestamp: 0, pubKeyPrefix: prefix, text: "x")[2], 3)   // attempt 0...3
    }

    func testSendChannelTextFrame() {
        // CMD_SEND_CHANNEL_TXT_MSG, TXT_TYPE_PLAIN, channel_idx, timestamp LE32, text
        XCTAssertEqual(MeshCore.sendChannelText(3, timestamp: 0x65000000, text: "yo"),
                       Data([0x03, 0x00, 0x03, 0x00, 0x00, 0x00, 0x65, 0x79, 0x6F]))
    }

    func testGetContactsAndLongestFrameFitMaxFrameSize() {
        XCTAssertEqual(MeshCore.getContacts, Data([0x04]))
        let longest = String(repeating: "a", count: MeshCore.maxTextBytes)
        XCTAssertLessThanOrEqual(MeshCore.sendText(attempt: 3, timestamp: 1, pubKeyPrefix: prefix, text: longest).count,
                                 MeshCore.maxFrameSize)
        XCTAssertLessThanOrEqual(MeshCore.sendChannelText(7, timestamp: 1, text: longest).count, MeshCore.maxFrameSize)
    }

    // MARK: Replies and pushes

    private func contactFrame(code: UInt8, key: Data, type: UInt8, name: String, lastAdvert: UInt32) -> Data {
        var f = Data([code]) + key + Data([type, 0x00, 0xFF]) + Data(count: 64)
        var n = Data(name.utf8); n.append(Data(count: 32 - n.count))
        f += n + Data([UInt8(lastAdvert & 0xFF), UInt8(lastAdvert >> 8 & 0xFF), UInt8(lastAdvert >> 16 & 0xFF), UInt8(lastAdvert >> 24)])
        f += Data(count: 12)                                               // lat, lon, lastmod
        return f
    }

    func testParseContactAndNewAdvert() throws {
        let key = Data((0..<32).map { UInt8($0 + 1) })
        let f = contactFrame(code: 3, key: key, type: 1, name: "Alice", lastAdvert: 0x11223344)
        XCTAssertEqual(f.count, 148)
        let c = try XCTUnwrap(MeshCore.parseContact(f))
        XCTAssertEqual(c.publicKey, key)
        XCTAssertEqual(c.type, 1)
        XCTAssertEqual(c.name, "Alice")
        XCTAssertEqual(c.lastAdvert, 0x11223344)
        XCTAssertEqual(MeshCore.parseContact(contactFrame(code: 0x8A, key: key, type: 2, name: "Rpt", lastAdvert: 1))?.type, 2)
        XCTAssertNil(MeshCore.parseContact(f.prefix(120)))
        XCTAssertNil(MeshCore.parseContact(contactFrame(code: 5, key: key, type: 1, name: "x", lastAdvert: 0)))
    }

    func testParseSentAndConfirmed() throws {
        let s = try XCTUnwrap(MeshCore.parseSent(Data([6, 1, 0x78, 0x56, 0x34, 0x12, 0x10, 0x27, 0x00, 0x00])))
        XCTAssertEqual(s, MeshCore.Sent(flood: true, ackTag: 0x12345678, timeoutMs: 10_000))
        XCTAssertEqual(MeshCore.parseSendConfirmed(Data([0x82, 0x78, 0x56, 0x34, 0x12, 0xE8, 0x03, 0, 0])), 0x12345678)
        XCTAssertNil(MeshCore.parseSent(Data([6, 0, 1])))
    }

    func testParseContactMessages() throws {
        let ts: [UInt8] = [0x04, 0x03, 0x02, 0x01]
        // v < 3: code 7, prefix, path_len, txt_type, timestamp, text
        let v2 = try XCTUnwrap(MeshCore.parseIncomingText(Data([7]) + prefix + Data([0xFF, 0]) + Data(ts) + Data("hello".utf8)))
        XCTAssertEqual(v2, MeshCore.IncomingText(source: .contact(prefix: prefix), txtType: 0, timestamp: 0x01020304, text: "hello"))
        // v3: code 16, snr, 0, 0, then the same
        let v3 = try XCTUnwrap(MeshCore.parseIncomingText(Data([16, 0x14, 0, 0]) + prefix + Data([2, 0]) + Data(ts) + Data("hé".utf8)))
        XCTAssertEqual(v3.text, "hé")
        XCTAssertEqual(v3.source, .contact(prefix: prefix))
        // TXT_TYPE_SIGNED_PLAIN carries 4 signer-prefix bytes before the text
        let signed = try XCTUnwrap(MeshCore.parseIncomingText(Data([7]) + prefix + Data([0xFF, 2]) + Data(ts) + Data([9, 9, 9, 9]) + Data("ok".utf8)))
        XCTAssertEqual(signed.text, "ok")
        XCTAssertNil(MeshCore.parseIncomingText(Data([7, 1, 2, 3])))
    }

    func testParseChannelMessages() throws {
        let ts: [UInt8] = [1, 0, 0, 0]
        let v2 = try XCTUnwrap(MeshCore.parseIncomingText(Data([8, 2, 0xFF, 0]) + Data(ts) + Data("Bob: hi there".utf8)))
        XCTAssertEqual(v2.source, .channel(idx: 2))
        XCTAssertEqual(v2.timestamp, 1)
        let (who, body) = MeshCore.splitChannelSender(v2.text)
        XCTAssertEqual(who, "Bob")
        XCTAssertEqual(body, "hi there")
        let v3 = try XCTUnwrap(MeshCore.parseIncomingText(Data([17, 0xF0, 0, 0, 5, 3, 0]) + Data(ts) + Data("A: b".utf8)))
        XCTAssertEqual(v3.source, .channel(idx: 5))
        XCTAssertEqual(MeshCore.splitChannelSender("no prefix").sender, nil)
    }

    func testSelfName() {
        var f = Data([5, 1, 22, 22]) + Data(count: 32) + Data(count: 22)
        XCTAssertEqual(f.count, 58)
        XCTAssertNil(MeshCore.selfName(f))
        f += Data("Hugo T1000".utf8)
        XCTAssertEqual(MeshCore.selfName(f), "Hugo T1000")
    }

    // MARK: Channels

    func testHashtagAndPublicSecrets() {
        // docs/companion_protocol.md: "#test" has the key 9cd8fcf22a47333b591d96a2b848b73f
        XCTAssertEqual(MeshCoreInterop.hex(MeshCoreInterop.hashtagSecret("#test")), "9cd8fcf22a47333b591d96a2b848b73f")
        XCTAssertEqual(MeshCoreInterop.hashtagSecret(OSHIBridgeWire.channelName), OSHIBridgeWire.channelSecret)
        XCTAssertEqual(MeshCore.publicChannelSecret, Data(base64Encoded: "izOH6cXN6mrJ5e26oRXNcg=="))
        XCTAssertTrue(MeshCoreInterop.isPubliclyReadable(name: "Public", secret: MeshCore.publicChannelSecret))
        XCTAssertTrue(MeshCoreInterop.isPubliclyReadable(name: "#test", secret: MeshCoreInterop.hashtagSecret("#test")))
        XCTAssertFalse(MeshCoreInterop.isPubliclyReadable(name: "#test", secret: Data(repeating: 7, count: 16)))
        XCTAssertEqual(MeshCoreInterop.normalizedHashtag(" paris "), "#paris")
        XCTAssertNil(MeshCoreInterop.normalizedHashtag("two words"))
        XCTAssertNil(MeshCoreInterop.normalizedHashtag("#"))
        XCTAssertEqual(MeshCoreInterop.parseSecret("9cd8fcf22a47333b591d96a2b848b73f"), MeshCoreInterop.hashtagSecret("#test"))
        XCTAssertNil(MeshCoreInterop.parseSecret("9cd8"))
        let set = MeshCore.setChannel(4, name: "#test", secret: MeshCoreInterop.hashtagSecret("#test"))
        XCTAssertEqual(set.count, 50)
        XCTAssertEqual(set.suffix(16).mcHex, "9cd8fcf22a47333b591d96a2b848b73f")
    }

    // MARK: Splitting

    func testShortTextIsOneMessage() {
        XCTAssertEqual(MeshCoreInterop.splitText("hello"), ["hello"])
        XCTAssertEqual(MeshCoreInterop.splitText(String(repeating: "a", count: 133))?.count, 1)
        XCTAssertNil(MeshCoreInterop.splitText("   "))
    }

    func testLongTextIsNumberedParts() throws {
        let words = (0..<60).map { "word\($0)" }.joined(separator: " ")         // ~400 characters
        let parts = try XCTUnwrap(MeshCoreInterop.splitText(words))
        XCTAssertGreaterThan(parts.count, 1)
        for (i, p) in parts.enumerated() {
            XCTAssertTrue(p.hasPrefix("(\(i + 1)/\(parts.count)) "), p)
            XCTAssertLessThanOrEqual(p.count, 133)
            XCTAssertLessThanOrEqual(p.utf8.count, MeshCoreInterop.dmTextBytes)
            XCTAssertFalse(p.hasSuffix(" "))
        }
        // Cut at spaces: every word survives whole, in order.
        let rejoined = parts.map { $0.drop(while: { $0 != ")" }).dropFirst(2) }.joined(separator: " ")
        XCTAssertEqual(rejoined, words)
        XCTAssertEqual(MeshCoreInterop.splitText(String(repeating: "a", count: 134))?.count, 2)
    }

    func testSplitRespectsTheByteBudget() throws {
        let emoji = String(repeating: "🛰️", count: 100)                       // 7 bytes per grapheme
        let parts = try XCTUnwrap(MeshCoreInterop.splitText(emoji))
        XCTAssertTrue(parts.allSatisfy { $0.utf8.count <= MeshCoreInterop.dmTextBytes })
        // Same budgets as Android MeshCoreText: DM 160 - 4, channel 160 - min(31, name bytes or 31) - 2 - 2.
        XCTAssertEqual(MeshCoreInterop.dmTextBytes, 156)
        let channelBudget = MeshCoreInterop.channelTextBytes(nodeName: "Hugo")
        XCTAssertEqual(channelBudget, 160 - 4 - 2 - 2)
        XCTAssertEqual(MeshCoreInterop.channelTextBytes(nodeName: nil), 125)
        XCTAssertEqual(MeshCoreInterop.channelTextBytes(nodeName: String(repeating: "n", count: 40)), 125)
        // 156 ASCII bytes but over 133 characters: split; 133 characters of 2-byte text (266 B): split too.
        XCTAssertEqual(MeshCoreInterop.splitText(String(repeating: "é", count: 78))?.count, 1)   // 156 B
        XCTAssertEqual(MeshCoreInterop.splitText(String(repeating: "é", count: 79))?.count, 2)   // 158 B
        let ch = try XCTUnwrap(MeshCoreInterop.splitText(String(repeating: "b", count: 200), maxBytes: channelBudget))
        XCTAssertTrue(ch.allSatisfy { $0.utf8.count <= channelBudget })
        XCTAssertNil(MeshCoreInterop.splitText(String(repeating: "c", count: 133 * 25)))  // beyond maxParts
    }

    // MARK: Ids (identical to Android)

    func testContactIds() {
        let key = prefix + Data((0..<26).map { UInt8($0) })
        let alice = MeshCore.Contact(publicKey: key, type: 1, name: "Alice", lastAdvert: 0)
        let full = MeshCoreInterop.contactKey(publicKey: key)
        XCTAssertEqual(full, "meshcore!" + key.mcHex)
        XCTAssertEqual(MeshCoreInterop.contactKey(forPrefix: prefix, in: [alice]), full)
        XCTAssertEqual(MeshCoreInterop.contactKey(forPrefix: prefix, in: []), "meshcore!aabbccddeeff")
        // Two contacts behind one prefix: never guess.
        let twin = MeshCore.Contact(publicKey: prefix + Data(repeating: 9, count: 26), type: 1, name: "Eve", lastAdvert: 0)
        XCTAssertEqual(MeshCoreInterop.contactKey(forPrefix: prefix, in: [alice, twin]), "meshcore!aabbccddeeff")
        XCTAssertEqual(MeshCoreInterop.publicKeyBytes(fromContactKey: full), key)
        XCTAssertEqual(MeshCoreInterop.publicKeyBytes(fromContactKey: "meshcore!aabbccddeeff"), prefix)
        XCTAssertNil(MeshCoreInterop.publicKeyBytes(fromContactKey: "meshcore!zz"))
        XCTAssertNil(MeshCoreInterop.publicKeyBytes(fromContactKey: "lora!0000abcd"))
        XCTAssertEqual(MeshCoreInterop.displayName(forKey: full, contacts: [alice]), "Alice")
        XCTAssertEqual(MeshCoreInterop.displayName(forKey: "meshcore!aabbccddeeff", contacts: [alice]), "Alice")
        // Not an OSHI identity, not a Meshtastic node.
        XCTAssertFalse(LoRaNodeIdentityMap.looksLikeIdentityKey(full))
        XCTAssertFalse(LoRaNodeIdentityMap.isFallbackConversationKey(full))
        XCTAssertEqual(MessageManager.canonicalIdentity(full), full)
    }

    func testChannelIds() {
        XCTAssertEqual(MeshCoreInterop.channelKey(name: " Public "), "mcchan!Public")
        XCTAssertEqual(MeshCoreInterop.channelKey(name: "#paris"), "mcchan!#paris")
        XCTAssertNil(MeshCoreInterop.channelKey(name: OSHIBridgeWire.channelName))   // hidden
        XCTAssertNil(MeshCoreInterop.channelKey(name: ""))
        XCTAssertEqual(MeshCoreInterop.channelName(fromKey: "mcchan!#paris"), "#paris")
        // A MeshCore channel is a room (filed under the channel), but never a Meshtastic slot.
        XCTAssertTrue(LoRaChannelThread.isChannelKey("mcchan!#paris"))
        XCTAssertEqual(LoRaChannelThread.displayName("mcchan!#paris"), "#paris")
        XCTAssertNil(LoRaChannelThread.channelIndex(forKey: "mcchan!#paris", in: []))
        XCTAssertEqual(MessageManager.canonicalIdentity("mcchan!Public"), "mcchan!Public")
    }

    func testIncomingRowsAreFiledLikeMeshtasticInterop() throws {
        let key = prefix + Data(repeating: 1, count: 26)
        let alice = MeshCore.Contact(publicKey: key, type: 1, name: "Alice", lastAdvert: 0)
        let channels = [MeshCore.ChannelInfo(idx: 0, name: "Public", secret: MeshCore.publicChannelSecret),
                        MeshCore.ChannelInfo(idx: 1, name: OSHIBridgeWire.channelName, secret: OSHIBridgeWire.channelSecret)]
        let dm = MeshCore.IncomingText(source: .contact(prefix: prefix), txtType: 0, timestamp: 5, text: "salut")
        let row = try XCTUnwrap(MeshCoreManager.interopMessage(for: dm, contacts: [alice], channels: channels,
                                                              bridgeIdx: 1, myKey: "MYKEY"))
        XCTAssertEqual(row.senderPublicKey, "meshcore!" + key.mcHex)
        XCTAssertEqual(row.senderAddress, "Alice")
        XCTAssertEqual(row.recipientPublicKey, "MYKEY")
        XCTAssertEqual(row.plaintextContent, "salut")
        XCTAssertEqual(row.deliveryMethod, .loraPlaintext)
        XCTAssertEqual(row.encryptedContent.signature, MeshCoreInterop.signature)
        // Same text, same timestamp (a retry the sender's radio repeated): same id, stored once.
        XCTAssertEqual(MeshCoreManager.interopMessage(for: dm, contacts: [alice], channels: channels,
                                                      bridgeIdx: 1, myKey: "MYKEY")?.id, row.id)

        let pub = MeshCore.IncomingText(source: .channel(idx: 0), txtType: 0, timestamp: 6, text: "Bob: hello all")
        let room = try XCTUnwrap(MeshCoreManager.interopMessage(for: pub, contacts: [], channels: channels,
                                                               bridgeIdx: 1, myKey: "MYKEY"))
        XCTAssertEqual(room.recipientPublicKey, "mcchan!Public")
        XCTAssertEqual(room.senderPublicKey, "mcname!Bob")
        XCTAssertEqual(room.plaintextContent, "hello all")
        XCTAssertEqual(LoRaChannelThread.speakerName(senderKey: room.senderPublicKey, nodes: []), "Bob")

        let bridge = MeshCore.IncomingText(source: .channel(idx: 1), txtType: 0, timestamp: 7, text: "x: y")
        XCTAssertNil(MeshCoreManager.interopMessage(for: bridge, contacts: [], channels: channels, bridgeIdx: 1, myKey: "K"))
    }

    // MARK: Routing rules

    func testMeshCoreOnlyPhoneIsRadioReady() {
        XCTAssertTrue(MeshCoreInterop.radioReady(meshtasticReady: false, meshCoreReady: true))
        XCTAssertTrue(MeshCoreInterop.radioReady(meshtasticReady: true, meshCoreReady: false))
        XCTAssertFalse(MeshCoreInterop.radioReady(meshtasticReady: false, meshCoreReady: false))
        XCTAssertFalse(MeshCoreInterop.radioJammed(meshtasticJammed: true, meshCoreReady: true))
        XCTAssertTrue(MeshCoreInterop.radioJammed(meshtasticJammed: true, meshCoreReady: false))
        // Off-grid with only a MeshCore radio: LoRa is in the order (it used to be empty).
        let c = TransportPolicy.Conditions(physicalPath: false, peerOnMesh: false,
                                           radioReady: MeshCoreInterop.radioReady(meshtasticReady: false, meshCoreReady: true),
                                           peerLikelyOnline: true, carriesMedia: false)
        XCTAssertEqual(TransportPolicy.order(c).first, .loRa)
    }

    func testBroadcastIsNeverDelivered() {
        XCTAssertEqual(MeshCoreInterop.finalStatus(isChannel: true, acked: true, radioAccepted: true), .sent)
        XCTAssertEqual(MeshCoreInterop.finalStatus(isChannel: true, acked: false, radioAccepted: true), .sent)
        XCTAssertEqual(MeshCoreInterop.finalStatus(isChannel: false, acked: true, radioAccepted: true), .delivered)
        XCTAssertEqual(MeshCoreInterop.finalStatus(isChannel: false, acked: false, radioAccepted: true), .failed)
        XCTAssertEqual(MeshCoreInterop.finalStatus(isChannel: true, acked: false, radioAccepted: false), .failed)
    }
}

// MARK: - Frames captured from REAL MeshCore firmware

/// The same capture Android pins in app/src/test/resources/meshcore/real-frames-v1.18.0.txt: MeshCore companion
/// firmware v1.18.0 (FIRMWARE_VER_CODE 14, MeshCore 75b04ef) on two Seeed T1000-E radios over USB serial,
/// 2026-11-25, while a host harness drove OSHI's MeshCore codec through setup, adverts, contacts, DMs with ACKs,
/// a 3-part DM, Public and hashtag channels, the #oshi-bridge datagram path and the v3 formats.
final class MeshCoreRealFrameTests: XCTestCase {
    fileprivate static let fixture = """
# Real MeshCore companion frames: firmware v1.18.0 (FIRMWARE_VER_CODE 14, MeshCore 75b04ef), 2x Seeed T1000-E over USB serial,
# captured 2026-11-25 by a host harness driving the real MeshCoreProtocol.kt. name=hex (frame payload, serial header stripped).
# A = OSHI-TEA-MC key f007359830cf..., B = OSHI-TEB-MC key 26aa91559d44...
self_info_A_named=05011616f007359830cf8dc7cb245a9113658ed97a89e47e06fd8db1ab67c0141972b6c9000000000000000000000000f2440d0024f4000008054f5348492d5445412d4d43
self_info_B_unnamed=0501161626aa91559d4456c50fb81e155ec9bb993162475d95e853cbe7af039bf828c50e000000000000000000000000f2440d0024f4000008053236414139313535
channel_info_public=12005075626c696300cf8dc7cb245a9113658ed97a89e47e06fd8db1ab67c01419728b3387e9c5cdea6ac9e5edbaa115cd72
channel_info_bridge_slot1=1201236f7368692d627269646765009113658ed97a89e47e06fd8db1ab67c0141972485a8d4bcd50fd075ea87d7ee19f4135
channel_info_empty_slot_with_residue=12020075626c6963004456c50fb81e155ec9bb993162475d95e853cbe7af039bf82800000000000000000000000000000000
channel_info_hashtag_readback=1202236f7368692d68772d74657374002d4d433a204f534849207465737494799f31e6dbc772a0eb27d0e51cc714ddc96193
contacts_start=0201000000
contact_B_in_A=0326aa91559d4456c50fb81e155ec9bb993162475d95e853cbe7af039bf828c50e0100ff000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000004f5348492d5445422d4d4300000000000000000000000000000000000000000076b1066b000000000000000077b1066b
contact_A_in_B=03f007359830cf8dc7cb245a9113658ed97a89e47e06fd8db1ab67c0141972b6c90100ff000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000004f5348492d5445412d4d4300000000000000000000000000000000000000000072b1066b000000000000000073b1066b
end_of_contacts=0473b1066b
push_advert_A_heard_by_B=80f007359830cf8dc7cb245a9113658ed97a89e47e06fd8db1ab67c0141972b6c9
push_path_updated=8126aa91559d4456c50fb81e155ec9bb993162475d95e853cbe7af039bf828c50e
push_log_rx_data=883cff1200f007359830cf8dc7cb245a9113658ed97a89e47e06fd8db1ab67c0141972b6c972b1066b6f6e70f0edf4d483064a5fee143217e52e8f7cf7a10b8282dc1ee64878dadcccab0dd22d45e131bb5d2eecd0d1678cfb4697675bf19f5f8bc3f50ea7ffec890e814f5348492d5445412d4d43
err_not_found=0102
resp_sent_direct_dm1=0601a24510e0541b0000
push_send_confirmed=82a24510e0a7030000
contact_msg_recv_v0_dm1=07f007359830cf000082b1066b4f53484920696e7465726f702074657374204120746f2042
contact_msg_recv_v0_long_part2_first=07f007359830cfff0086b1066b28322f33292062792074686520726563656976696e6720726164696f2e2049742068617320746f206265206c6f6e676572207468616e206f6e652068756e6472656420616e642074686972747920746872656520636861726163746572732c20736f207468652073656e6465722073706c69747320697420617420776f7264
contact_msg_recv_v0_long_part2_retry=07f007359830cfff0086b1066b28322f33292062792074686520726563656976696e6720726164696f2e2049742068617320746f206265206c6f6e676572207468616e206f6e652068756e6472656420616e642074686972747920746872656520636861726163746572732c20736f207468652073656e6465722073706c69747320697420617420776f7264
contact_msg_recv_v0_long_part3_utf8=07f007359830cfff0087b1066b28332f332920626f756e6461726965732e20c39c6ec3af63c3b664c3a920e29c9320656e642e
channel_msg_recv_v0_public=0800000091b1066b4f5348492d5445422d4d433a204f5348492074657374
channel_msg_recv_v0_hashtag=0802000094b1066b4f5348492d5445412d4d433a20686173687461672068656c6c6f
channel_data_recv_ob_part0=1b3a000001004fffa04f4210f007359801024f531101005a5af0073598ffffffff000100030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dce3eaf1f8ff060d141b222930373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910171e252c333a41484f565d646b727980878e959ca3aab1b8bfc6cdd4dbe2e9f0f7fe050c131a21282f363d444b525960676e757c838a91989f
channel_data_recv_ob_part1=1b42000001004fff3a4f4210f00735980112a6adb4bbc2c9d0d7dee5ecf3fa01080f161d242b323940474e555c636a71787f868d949ba2a9b0b7bec5ccd3dae1e8eff6
device_info=0d0eaf28000000003135204f6374203230323600536565656420547261636b65722054313030302d450000000000000000000000000000000000000076312e31382e30000000000000000000000000000000
contact_msg_recv_v3=103c000026aa91559d44ff00e4b1066b763320666f726d617420636865636b
channel_msg_recv_v3_hashtag=113b0000020000e5b1066b4f5348492d5445422d4d433a207633206368616e6e656c20636865636b
cmd_app_start=01000000000000004f5348492d6861726e657373
cmd_set_channel_bridge=2001236f7368692d6272696467650000000000000000000000000000000000000000485a8d4bcd50fd075ea87d7ee19f4135
cmd_send_txt_long_part2_attempt1=02000186b1066b26aa91559d4428322f33292062792074686520726563656976696e6720726164696f2e2049742068617320746f206265206c6f6e676572207468616e206f6e652068756e6472656420616e642074686972747920746872656520636861726163746572732c20736f207468652073656e6465722073706c69747320697420617420776f7264
cmd_send_channel_data_part0=3e01ff4fff4f4210f007359801024f531101005a5af0073598ffffffff000100030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dce3eaf1f8ff060d141b222930373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910171e252c333a41484f565d646b727980878e959ca3aab1b8bfc6cdd4dbe2e9f0f7fe050c131a21282f363d444b525960676e757c838a91989f
"""

    private lazy var fx: [String: Data] = {
        var out: [String: Data] = [:]
        for line in Self.fixture.split(separator: "\n") where !line.hasPrefix("#") {
            let kv = line.split(separator: "=", maxSplits: 1)
            guard kv.count == 2 else { continue }
            var d = Data(); var i = kv[1].startIndex
            while i < kv[1].endIndex {
                let j = kv[1].index(i, offsetBy: 2)
                d.append(UInt8(kv[1][i..<j], radix: 16)!); i = j
            }
            out[String(kv[0])] = d
        }
        return out
    }()

    private func f(_ name: String) throws -> Data { try XCTUnwrap(fx[name], "missing fixture \(name)") }

    func testSelfInfo() throws {
        XCTAssertEqual(MeshCore.selfName(try f("self_info_A_named")), "OSHI-TEA-MC")
        XCTAssertEqual(MeshCore.selfPublicKey(try f("self_info_A_named"))?.prefix(8).mcHex, "f007359830cf8dc7")
        XCTAssertEqual(MeshCore.selfName(try f("self_info_B_unnamed")), "26AA9155")
    }

    func testChannelInfoIncludingAnEmptySlotWithStaleBytes() throws {
        XCTAssertEqual(MeshCore.parseChannelInfo(try f("channel_info_public")),
                       MeshCore.ChannelInfo(idx: 0, name: "Public", secret: MeshCore.publicChannelSecret))
        XCTAssertEqual(MeshCore.parseChannelInfo(try f("channel_info_bridge_slot1")),
                       MeshCore.ChannelInfo(idx: 1, name: OSHIBridgeWire.channelName, secret: OSHIBridgeWire.channelSecret))
        // The firmware strcpy()s "" over a reused buffer: stale "ublic\0" + key bytes follow the NUL.
        let empty = try XCTUnwrap(MeshCore.parseChannelInfo(try f("channel_info_empty_slot_with_residue")))
        XCTAssertEqual(empty.name, "")
        XCTAssertEqual(empty.secret, Data(count: 16))
        let tag = try XCTUnwrap(MeshCore.parseChannelInfo(try f("channel_info_hashtag_readback")))
        XCTAssertEqual(tag.name, "#oshi-hw-test")
        XCTAssertEqual(tag.secret, MeshCoreInterop.hashtagSecret("#oshi-hw-test"))
    }

    func testContacts() throws {
        let b = try XCTUnwrap(MeshCore.parseContact(try f("contact_B_in_A")))
        XCTAssertEqual(b.name, "OSHI-TEB-MC")
        XCTAssertEqual(b.type, 1)
        XCTAssertEqual(b.publicKey.prefix(6).mcHex, "26aa91559d44")
        XCTAssertEqual(MeshCore.parseContact(try f("contact_A_in_B"))?.name, "OSHI-TEA-MC")
        // Default firmware pushes ADVERT (0x80, key only), never NEW_ADVERT: not a contact frame.
        XCTAssertNil(MeshCore.parseContact(try f("push_advert_A_heard_by_B")))
        XCTAssertEqual(try f("push_advert_A_heard_by_B").count, 1 + MeshCore.pubKeySize)
    }

    func testSentAndConfirmedCarryTheSameAckTag() throws {
        let sent = try XCTUnwrap(MeshCore.parseSent(try f("resp_sent_direct_dm1")))
        XCTAssertTrue(sent.flood)
        XCTAssertEqual(MeshCore.parseSendConfirmed(try f("push_send_confirmed")), sent.ackTag)
    }

    func testDirectTextBothFormats() throws {
        let dm = try XCTUnwrap(MeshCore.parseIncomingText(try f("contact_msg_recv_v0_dm1")))
        XCTAssertEqual(dm.text, "OSHI interop test A to B")
        XCTAssertEqual(dm.source, .contact(prefix: Data([0xf0, 0x07, 0x35, 0x98, 0x30, 0xcf])))
        XCTAssertEqual(MeshCore.parseIncomingText(try f("contact_msg_recv_v0_long_part3_utf8"))?.text,
                       "(3/3) boundaries. Ünïcödé ✓ end.")
        let v3 = try XCTUnwrap(MeshCore.parseIncomingText(try f("contact_msg_recv_v3")))
        XCTAssertEqual(v3.text, "v3 format check")
        XCTAssertEqual(v3.source, .contact(prefix: Data([0x26, 0xaa, 0x91, 0x55, 0x9d, 0x44])))
    }

    func testChannelTextBothFormats() throws {
        let pub = try XCTUnwrap(MeshCore.parseIncomingText(try f("channel_msg_recv_v0_public")))
        XCTAssertEqual(pub.source, .channel(idx: 0))
        XCTAssertEqual(MeshCore.splitChannelSender(pub.text).sender, "OSHI-TEB-MC")
        XCTAssertEqual(MeshCore.splitChannelSender(pub.text).body, "OSHI test")
        let tag = try XCTUnwrap(MeshCore.parseIncomingText(try f("channel_msg_recv_v0_hashtag")))
        XCTAssertEqual(tag.source, .channel(idx: 2))
        XCTAssertEqual(MeshCore.splitChannelSender(tag.text).body, "hashtag hello")
        let v3 = try XCTUnwrap(MeshCore.parseIncomingText(try f("channel_msg_recv_v3_hashtag")))
        XCTAssertEqual(MeshCore.splitChannelSender(v3.text).sender, "OSHI-TEB-MC")
        XCTAssertEqual(MeshCore.splitChannelSender(v3.text).body, "v3 channel check")
    }

    func testBridgeDatagramsReassembleIntoTheOmpFrame() throws {
        let joiner = OSHIBridgeWire.Joiner()
        var frame: Data?
        for name in ["channel_data_recv_ob_part0", "channel_data_recv_ob_part1"] {
            let d = try XCTUnwrap(MeshCore.parseChannelData(try f(name)))
            XCTAssertEqual(d.channelIdx, 1)
            XCTAssertEqual(d.dataType, OSHIBridgeWire.dataType)
            let part = try XCTUnwrap(OSHIBridgeWire.parse(d.payload))
            XCTAssertEqual(part.sender, 0x983507f0)          // A's MeshCore-derived id
            frame = joiner.push(part)
        }
        let omp = try XCTUnwrap(OMP.parseData(try XCTUnwrap(frame)))
        XCTAssertEqual(omp.msgId, 0x5A5A0001)
        XCTAssertEqual(omp.origin, 0x983507f0)
        XCTAssertEqual(omp.payload, Data((0..<182).map { UInt8(truncatingIfNeeded: $0 * 7 + 3) }))
    }

    func testEncodersMatchWhatTheRadioAccepted() throws {
        XCTAssertEqual(MeshCore.appStart(appName: "OSHI-harness"), try f("cmd_app_start"))
        XCTAssertEqual(MeshCore.setChannel(1, name: OSHIBridgeWire.channelName, secret: OSHIBridgeWire.channelSecret),
                       try f("cmd_set_channel_bridge"))
        let part2 = try XCTUnwrap(MeshCore.parseIncomingText(try f("contact_msg_recv_v0_long_part2_first")))
        XCTAssertEqual(MeshCore.sendText(attempt: 1, timestamp: part2.timestamp,
                                         pubKeyPrefix: Data([0x26, 0xaa, 0x91, 0x55, 0x9d, 0x44]), text: part2.text),
                       try f("cmd_send_txt_long_part2_attempt1"))
        XCTAssertEqual(try f("cmd_send_channel_data_part0").prefix(5), Data([62, 1, 0xFF, 0x4F, 0xFF]))
    }

    /// Real radios lost the ACK of part 2 to a collision; the sender's retry (same timestamp) reached the
    /// receiver as a byte-identical second frame. iOS must file both under one id so the row shows once.
    func testRetriedDirectMessageGetsTheSameRowId() throws {
        let first = try f("contact_msg_recv_v0_long_part2_first")
        let retry = try f("contact_msg_recv_v0_long_part2_retry")
        XCTAssertEqual(first, retry)
        let a = try XCTUnwrap(MeshCore.parseIncomingText(first))
        let b = try XCTUnwrap(MeshCore.parseIncomingText(retry))
        let id1 = MeshCoreManager.interopMessage(for: a, contacts: [], channels: [], bridgeIdx: 1, myKey: "K")?.id
        let id2 = MeshCoreManager.interopMessage(for: b, contacts: [], channels: [], bridgeIdx: 1, myKey: "K")?.id
        XCTAssertNotNil(id1)
        XCTAssertEqual(id1, id2)
    }
}

private extension Data {
    var mcHex: String { map { String(format: "%02x", $0) }.joined() }
}

// MARK: - The whole app path, replaying the real frames

/// The captured frames pushed through a MeshCoreManager without Bluetooth → MessageManager → what the screens
/// read (`messages`, `conversations`, `getMessages`, statuses). Every command the app writes is compared byte for
/// byte with what the companion protocol expects; the radio side is the real firmware's answers.
@MainActor
final class MeshCoreAppFlowTests: XCTestCase {
    private var manager: MessageManager!
    private var radio: MeshCoreManager!
    private var written: [Data] = []
    private var previousShared: MessageManager?
    private var previousIdentity: IdentityManager?
    /// 40 base64-safe characters: `canonicalIdentity` leaves it exactly as is.
    private let myKey = "mcflowte" + UUID().uuidString.replacingOccurrences(of: "-", with: "")

    private static let fx: [String: Data] = {
        var out: [String: Data] = [:]
        for line in MeshCoreRealFrameTests.fixture.split(separator: "\n") where !line.hasPrefix("#") {
            let kv = line.split(separator: "=", maxSplits: 1)
            guard kv.count == 2 else { continue }
            var d = Data(); var i = kv[1].startIndex
            while i < kv[1].endIndex {
                let j = kv[1].index(i, offsetBy: 2)
                d.append(UInt8(kv[1][i..<j], radix: 16)!); i = j
            }
            out[String(kv[0])] = d
        }
        return out
    }()
    private func f(_ name: String) -> Data { Self.fx[name]! }

    /// OSHI-TEB-MC, the other radio in the capture (a contact of ours, radio A).
    private var bKey: Data { MeshCore.parseContact(f("contact_B_in_A"))!.publicKey }
    private var bThread: String { MeshCoreInterop.contactKey(publicKey: bKey) }

    override func setUp() async throws {
        previousShared = MessageManager.shared
        previousIdentity = MessageManager.sharedIdentityManager
        manager = MessageManager()
        try? await Task.sleep(nanoseconds: 300_000_000)     // let init's deferred loads land first
        MessageManager.shared = manager
        let identity = IdentityManager()
        identity.publicKey = myKey
        MessageManager.sharedIdentityManager = identity
        radio = MeshCoreManager(testing: ())
        radio.deliver = { [weak self] in self?.manager.receiveMessage($0, suppressNotification: true) }
        radio.statusSink = { [weak self] id, status in self?.manager.meshCoreTextStatus(messageId: id, status: status) }
        manager.meshCoreRadioOverride = radio
        written = []
    }

    override func tearDown() async throws {
        manager?.messages.removeAll { MeshCoreInterop.isInteropKey($0.senderPublicKey) || MeshCoreInterop.isInteropKey($0.recipientPublicKey) }
        manager = nil
        radio = nil
        MessageManager.shared = previousShared
        MessageManager.sharedIdentityManager = previousIdentity
    }

    // MARK: Tooling

    private func feed(_ frame: Data) { radio.receiveForTesting(frame) }
    private func feed(_ name: String) { feed(f(name)) }

    /// Lets the main-queue hop in `sendToMeshCore` (store the row, then hand it to the radio) run.
    private func drainMain() async {
        let e = expectation(description: "main")
        DispatchQueue.main.async { e.fulfill() }
        await fulfillment(of: [e], timeout: 2)
    }

    private func emptySlot(_ idx: UInt8) -> Data {
        var d = f("channel_info_empty_slot_with_residue"); d[1] = idx; return d
    }

    /// Connect, read the radio, list contacts, drain the message queue — asserting each command on the way.
    private func bringUp(listContactB: Bool = true) {
        radio.attachForTesting { [weak self] in self?.written.append($0) }
        XCTAssertEqual(written.last, MeshCore.appStart())
        feed("self_info_A_named")
        XCTAssertEqual(written.last, MeshCore.getChannel(0))
        feed("channel_info_public")
        XCTAssertEqual(written.last, MeshCore.getChannel(1))
        feed("channel_info_bridge_slot1")
        XCTAssertEqual(written.last, MeshCore.getChannel(2))
        feed("channel_info_hashtag_readback")
        for idx in 3..<8 {
            XCTAssertEqual(written.last, MeshCore.getChannel(idx))
            feed(emptySlot(UInt8(idx)))
        }
        // "#oshi-bridge" was already in slot 1: nothing claimed, the radio is ready.
        XCTAssertEqual(radio.state, .ready)
        XCTAssertTrue(radio.isReady)
        XCTAssertEqual(radio.selfName, "OSHI-TEA-MC")
        XCTAssertEqual(radio.userChannels.map(\.name), ["Public", "#oshi-hw-test"])
        XCTAssertEqual(written.last, MeshCore.getContacts)
        feed("contacts_start")
        if listContactB { feed("contact_B_in_A") }
        feed("end_of_contacts")
        XCTAssertEqual(written.last, MeshCore.syncNextMessage)
        feed(Data([MeshCore.respNoMoreMessages]))
        written = []
    }

    private func row(_ id: String) -> SecureMessage? { manager.messages.first { $0.id == id } }

    private func sendDM(_ text: String, to key: String? = nil) async throws -> String {
        let outcome = manager.sendToMeshCore(content: text, conversationKey: key ?? bThread,
                                             identityManager: MessageManager.sharedIdentityManager!, mediaAttachment: nil)
        XCTAssertEqual(outcome, .sent)
        await drainMain()
        return try XCTUnwrap(manager.messages.last { $0.recipientPublicKey == (key ?? bThread) && $0.senderPublicKey == myKey }?.id)
    }

    /// The frame the radio must have received for one DM part: everything byte-exact, the timestamp read back.
    private func assertDirectFrame(_ frame: Data?, attempt: UInt8, text: String, file: StaticString = #filePath, line: UInt = #line) {
        guard let frame, frame.count > 13 else { return XCTFail("no frame", file: file, line: line) }
        let ts = MeshCore.readLE32([UInt8](frame), 3)
        XCTAssertEqual(frame, MeshCore.sendText(attempt: attempt, timestamp: ts, pubKeyPrefix: bKey.prefix(6), text: text),
                       file: file, line: line)
        XCTAssertLessThan(abs(Double(ts) - Date().timeIntervalSince1970), 60, file: file, line: line)
    }

    // MARK: Receiving

    func testDirectMessageCreatesANamedThread() throws {
        bringUp()
        feed(Data([MeshCore.pushMsgWaiting]))
        XCTAssertEqual(written.last, MeshCore.syncNextMessage)
        feed("contact_msg_recv_v3")                               // from B, "v3 format check"
        XCTAssertEqual(written.last, MeshCore.syncNextMessage)  // and asks for the next one
        feed(Data([MeshCore.respNoMoreMessages]))

        let msg = try XCTUnwrap(manager.messages.first { $0.plaintextContent == "v3 format check" })
        XCTAssertEqual(msg.senderPublicKey, bThread, "the full key, resolved from the 6-byte prefix via the contact table")
        XCTAssertEqual(msg.recipientPublicKey, myKey)
        XCTAssertEqual(msg.deliveryMethod, .loraPlaintext)
        XCTAssertEqual(msg.encryptedContent.signature, MeshCoreInterop.signature)
        manager.refreshConversations()
        XCTAssertTrue(manager.conversations.contains { $0.participantPublicKey == bThread })
        XCTAssertEqual(manager.getMessages(for: bThread).map(\.plaintextContent), ["v3 format check"])
        XCTAssertEqual(MeshCoreInterop.displayName(forKey: bThread, contacts: radio.contacts), "OSHI-TEB-MC")
        XCTAssertEqual(MessageManager.radioBanner(sender: msg.senderPublicKey, recipient: msg.recipientPublicKey,
                                                  text: "v3 format check").threadKey, bThread)
    }

    func testChannelMessageIsFiledUnderTheChannelAndItsBannerOpensIt() throws {
        bringUp()
        feed("channel_msg_recv_v0_hashtag")                       // slot 2 "#oshi-hw-test": "OSHI-TEA-MC: hashtag hello"
        let msg = try XCTUnwrap(manager.messages.first { $0.plaintextContent == "hashtag hello" })
        XCTAssertEqual(msg.recipientPublicKey, "mcchan!#oshi-hw-test")
        XCTAssertEqual(msg.senderPublicKey, "mcname!OSHI-TEA-MC")
        manager.refreshConversations()
        XCTAssertTrue(manager.conversations.contains { $0.participantPublicKey == "mcchan!#oshi-hw-test" })
        XCTAssertFalse(manager.conversations.contains { $0.participantPublicKey == "mcname!OSHI-TEA-MC" },
                       "a speaker never gets a one-to-one thread")
        let banner = MessageManager.radioBanner(sender: msg.senderPublicKey, recipient: msg.recipientPublicKey,
                                                text: "hashtag hello")
        XCTAssertEqual(banner.threadKey, "mcchan!#oshi-hw-test")
        XCTAssertEqual(banner.text, "OSHI-TEA-MC: hashtag hello")
        XCTAssertEqual(MeshCoreInterop.threadTitle(forKey: banner.threadKey), "#oshi-hw-test")
    }

    func testARetriedDirectMessageShowsOnce() {
        bringUp()
        feed("contact_msg_recv_v0_long_part2_first")
        feed("contact_msg_recv_v0_long_part2_retry")
        feed("contact_msg_recv_v0_long_part3_utf8")
        let rows = manager.messages.filter { $0.deliveryMethod == .loraPlaintext && $0.senderPublicKey != myKey
            && ($0.plaintextContent ?? "").hasPrefix("(") }
        XCTAssertEqual(rows.count, 2)
        XCTAssertTrue(rows.contains { $0.plaintextContent == "(3/3) boundaries. Ünïcödé ✓ end." })
    }

    // MARK: Sending

    func testDirectMessageGoesSentThenDeliveredOnTheRecipientsAck() async throws {
        bringUp()
        let id = try await sendDM("OSHI interop test A to B")
        assertDirectFrame(written.last, attempt: 0, text: "OSHI interop test A to B")
        XCTAssertEqual(row(id)?.deliveryStatus, .pending)
        XCTAssertEqual(row(id)?.deliveryMethod, .loraPlaintext)
        feed("resp_sent_direct_dm1")
        XCTAssertEqual(row(id)?.deliveryStatus, .sent)
        feed("push_send_confirmed")                               // the ACK tag RESP_SENT announced
        XCTAssertEqual(row(id)?.deliveryStatus, .delivered)
    }

    func testUnansweredDirectMessageFailsAfterFourAttemptsAndRetryReusesTheRow() async throws {
        bringUp()
        let id = try await sendDM("are you there")
        for attempt in 0...3 {
            assertDirectFrame(written.last, attempt: UInt8(attempt), text: "are you there")
            let ts = written.last.map { MeshCore.readLE32([UInt8]($0), 3) }
            if attempt > 0 { XCTAssertEqual(ts, MeshCore.readLE32([UInt8](written[0]), 3), "same timestamp: the far radio dedupes") }
            feed("resp_sent_direct_dm1")
            radio.fireTextTimerForTesting()                       // the ACK never comes
        }
        XCTAssertEqual(row(id)?.deliveryStatus, .failed)
        XCTAssertEqual(MeshCoreUI.failedTextKey(recipientKey: bThread, radioReady: true), "meshcore.failed.dm")

        written = []
        XCTAssertEqual(manager.retryMeshCoreText(messageId: id), .sent)
        XCTAssertEqual(row(id)?.deliveryStatus, .pending)
        XCTAssertEqual(manager.messages.filter { $0.plaintextContent == "are you there" }.count, 1, "the same row, never a copy")
        assertDirectFrame(written.last, attempt: 0, text: "are you there")
        feed("resp_sent_direct_dm1")
        feed("push_send_confirmed")
        XCTAssertEqual(row(id)?.deliveryStatus, .delivered)
    }

    func testLongDirectMessageLeavesAsNumberedPartsOneAfterTheOther() async throws {
        bringUp()
        let text = (0..<45).map { "word\($0)" }.joined(separator: " ")
        let parts = try XCTUnwrap(MeshCoreInterop.splitText(text))
        XCTAssertEqual(parts.count, 3)
        let id = try await sendDM(text)
        for (i, part) in parts.enumerated() {
            assertDirectFrame(written.last, attempt: 0, text: part)
            XCTAssertEqual(written.filter { $0.first == MeshCore.cmdSendTxtMsg }.count, i + 1, "one part on the air at a time")
            feed("resp_sent_direct_dm1")
            XCTAssertEqual(row(id)?.deliveryStatus, .sent)
            feed("push_send_confirmed")
            if i < parts.count - 1 {
                XCTAssertNotEqual(row(id)?.deliveryStatus, .delivered, "delivered only when every part is ACKed")
                radio.fireTextTimerForTesting()                   // the 1.5 s gap between parts
            }
        }
        XCTAssertEqual(row(id)?.deliveryStatus, .delivered)
        // Each part keeps its own timestamp (+1 per part), so the receiving radio never takes one for a retry.
        let stamps = written.filter { $0.first == MeshCore.cmdSendTxtMsg }.map { MeshCore.readLE32([UInt8]($0), 3) }
        XCTAssertEqual(stamps, [stamps[0], stamps[0] + 1, stamps[0] + 2])
    }

    func testChannelMessageIsSentAndNeverDelivered() async throws {
        bringUp()
        let outcome = manager.sendToMeshCore(content: "hello public", conversationKey: "mcchan!Public",
                                             identityManager: MessageManager.sharedIdentityManager!, mediaAttachment: nil)
        XCTAssertEqual(outcome, .sent)
        await drainMain()
        let id = try XCTUnwrap(manager.messages.last { $0.recipientPublicKey == "mcchan!Public" }?.id)
        let frame = try XCTUnwrap(written.last)
        let ts = MeshCore.readLE32([UInt8](frame), 3)
        XCTAssertEqual(frame, MeshCore.sendChannelText(0, timestamp: ts, text: "hello public"))
        feed(Data([MeshCore.respOK]))
        XCTAssertEqual(row(id)?.deliveryStatus, .sent)
        feed("push_send_confirmed")                               // an unrelated ACK must not upgrade a broadcast
        XCTAssertEqual(row(id)?.deliveryStatus, .sent)
    }

    func testAChannelNotOnTheRadioIsRefused() {
        bringUp()
        XCTAssertEqual(manager.sendToMeshCore(content: "hi", conversationKey: "mcchan!#elsewhere",
                                              identityManager: MessageManager.sharedIdentityManager!, mediaAttachment: nil),
                       .loRaChannelUnknown)
        XCTAssertEqual(MeshCoreUI.radioNoteKey(key: "mcchan!#elsewhere", state: radio.state, isPaired: true,
                                               channelOnRadio: radio.channelSlot(forKey: "mcchan!#elsewhere") != nil),
                       "meshcore.radio.channelMissing")
    }

    func testWithoutARadioTheTextIsKeptAsNotDeliveredAndRetriesOnceConnected() {
        XCTAssertEqual(manager.sendToMeshCore(content: "written out of range", conversationKey: bThread,
                                              identityManager: MessageManager.sharedIdentityManager!, mediaAttachment: nil),
                       .sent)
        let id = manager.messages.last { $0.plaintextContent == "written out of range" }?.id
        XCTAssertEqual(id.flatMap(row)?.deliveryStatus, .failed, "kept, never lost; tap to retry")
        XCTAssertEqual(MeshCoreUI.failedTextKey(recipientKey: bThread, radioReady: false), "message.tap_to_retry")
        bringUp()
        XCTAssertEqual(manager.retryMeshCoreText(messageId: id!), .sent)
        assertDirectFrame(written.last, attempt: 0, text: "written out of range")
        XCTAssertEqual(MeshCoreUI.radioNoteKey(key: bThread, state: .off, isPaired: false, channelOnRadio: false),
                       "meshcore.radio.notConnected")
    }

    func testAReplyLeavesAsThePlainTypedText() async throws {
        bringUp()
        let reply = ReplyMessage(originalMessageId: "x", originalSenderKey: bThread, originalText: "earlier",
                                 originalTimestamp: Date(), originalMediaType: nil)
        let wire = MessageWithReply(content: "yes indeed", replyTo: reply).toMessageString()
        _ = try await sendDM(wire)
        assertDirectFrame(written.last, attempt: 0, text: "yes indeed")
    }

    func testAContactOnlyHeardIsAddedToTheRadioBeforeTheFirstDM() async throws {
        bringUp(listContactB: false)
        var advert = f("contact_B_in_A"); advert[0] = MeshCore.pushNewAdvert   // auto-add off: pushed, not stored
        feed(advert)
        XCTAssertEqual(radio.contacts.map(\.name), ["OSHI-TEB-MC"])
        _ = try await sendDM("first contact")
        var add = f("contact_B_in_A"); add[0] = MeshCore.cmdAddUpdateContact
        XCTAssertEqual(written.first, add, "CMD_ADD_UPDATE_CONTACT replays the pushed frame")
        feed(Data([MeshCore.respOK]))
        assertDirectFrame(written.last, attempt: 0, text: "first contact")
        // A later contact listing that does not include B keeps B (the radio only heard it).
        radio.refreshContacts()
        feed("resp_sent_direct_dm1")
        feed("contacts_start"); feed("end_of_contacts")
        XCTAssertEqual(radio.contacts.map(\.name), ["OSHI-TEB-MC"])
    }

    func testLosingTheRadioFailsWhatWasInFlight() async throws {
        bringUp()
        let id = try await sendDM("going out of range")
        feed("resp_sent_direct_dm1")
        XCTAssertEqual(row(id)?.deliveryStatus, .sent)
        radio.dropLinkForTesting()
        XCTAssertEqual(row(id)?.deliveryStatus, .failed)
        XCTAssertEqual(radio.lastError, MeshCoreError.connectionLost)
        XCTAssertEqual(MeshCoreUI.statusKey(state: .off, awaitingPin: false, lastError: radio.lastError,
                                            isPaired: true, bridgeReady: false), "meshcore.error.connectionLost")
        XCTAssertEqual(manager.retryMeshCoreText(messageId: id), .loRaInteropNoRadio)
        XCTAssertEqual(row(id)?.deliveryStatus, .failed, "still there, still tap-to-retry")
    }

    func testJoiningAHashtagChannelUsesTheFirstEmptySlotAndOpensIt() {
        bringUp()
        let name = MeshCoreInterop.normalizedHashtag("paris")!
        XCTAssertTrue(radio.joinChannel(name: name, secret: MeshCoreInterop.hashtagSecret(name)))
        XCTAssertEqual(written.last, MeshCore.setChannel(3, name: "#paris", secret: MeshCoreInterop.hashtagSecret("#paris")))
        XCTAssertNil(radio.lastJoinedChannelKey)
        feed(Data([MeshCore.respOK]))
        XCTAssertEqual(radio.lastJoinedChannelKey, "mcchan!#paris")
        XCTAssertEqual(radio.channelSlot(forKey: "mcchan!#paris"), 3)
    }

    func testAnnounceSendsSelfAdvert() {
        bringUp()
        XCTAssertTrue(radio.announce())
        XCTAssertEqual(written.last, Data([MeshCore.cmdSendSelfAdvert, 1]))
        XCTAssertNil(radio.lastAdvertAt)
        feed(Data([MeshCore.respOK]))
        XCTAssertNotNil(radio.lastAdvertAt)
    }
}

// MARK: - Screen decisions and the shared split rule

final class MeshCoreUITests: XCTestCase {
    func testComposerCounterAppearsNearTheLimitAndCountsParts() {
        XCTAssertFalse(MeshCoreUI.composerCount("hello", isChannel: false, selfName: nil).visible)
        let near = MeshCoreUI.composerCount(String(repeating: "a", count: 120), isChannel: false, selfName: nil)
        XCTAssertTrue(near.visible)
        XCTAssertEqual(near.parts, 1)
        XCTAssertEqual(near.limit, 133)
        let over = MeshCoreUI.composerCount(String(repeating: "a", count: 140), isChannel: false, selfName: nil)
        XCTAssertEqual(over.parts, 2)
        XCTAssertEqual(over.chars, 140)
        // A channel has less room: the radio puts "<name>: " in front.
        let ch = MeshCoreUI.composerCount(String(repeating: "é", count: 75), isChannel: true, selfName: "OSHI-TEA-MC")
        XCTAssertEqual(ch.parts, 2)
        XCTAssertTrue(MeshCoreUI.composerCount(String(repeating: "word ", count: 1000), isChannel: false, selfName: nil).tooLong)
    }

    func testStatusLines() {
        XCTAssertEqual(MeshCoreUI.statusKey(state: .configuring, awaitingPin: true, lastError: nil, isPaired: true, bridgeReady: false),
                       "meshcore.state.pin")
        XCTAssertEqual(MeshCoreUI.statusKey(state: .off, awaitingPin: false, lastError: MeshCoreError.pairingFailed,
                                            isPaired: true, bridgeReady: false), "meshcore.error.pairing")
        XCTAssertEqual(MeshCoreUI.statusKey(state: .ready, awaitingPin: false, lastError: nil, isPaired: true, bridgeReady: false),
                       "meshcore.ready.textOnly")
        XCTAssertNil(MeshCoreUI.statusKey(state: .off, awaitingPin: false, lastError: nil, isPaired: false, bridgeReady: false))
        XCTAssertTrue(MeshCoreError.needsAction(MeshCoreError.notMeshCore))
        XCTAssertFalse(MeshCoreError.needsAction(MeshCoreError.connectionLost))
        XCTAssertTrue(MeshCoreError.isPairingError(CBATTError(.insufficientEncryption)))
        XCTAssertTrue(MeshCoreError.isPairingError(CBError(.peerRemovedPairingInformation)))
        XCTAssertFalse(MeshCoreError.isPairingError(CBError(.connectionTimeout)))
    }

    func testThreadNotes() {
        XCTAssertEqual(MeshCoreUI.threadNoteKey(key: "meshcore!aabbccddeeff", channel: nil), "meshcore.interop.dm.detail")
        let pub = MeshCore.ChannelInfo(idx: 0, name: "Public", secret: MeshCore.publicChannelSecret)
        XCTAssertEqual(MeshCoreUI.threadNoteKey(key: "mcchan!Public", channel: pub), "meshcore.interop.channel.public")
        let priv = MeshCore.ChannelInfo(idx: 3, name: "team", secret: Data(repeating: 7, count: 16))
        XCTAssertEqual(MeshCoreUI.threadNoteKey(key: "mcchan!team", channel: priv), "meshcore.interop.channel.private")
        XCTAssertEqual(MeshCoreUI.threadNoteKey(key: "mcchan!team", channel: nil), "meshcore.interop.channel.public",
                       "unknown: the cautious sentence")
    }

    // The rule (MeshCoreInterop.splitText doc): code points, label room "(9/9) " up to 9 parts and "(99/99) " beyond,
    // never inside a grapheme cluster, cut at the last space at index ≥ end / 2.

    func testLabelRoomIsReservedForTheDigitCount() throws {
        // 12 parts: every label costs 8, so no part exceeds 133 even at "(12/12) ".
        let text = String(repeating: "x", count: 125 * 12 + 1)
        let parts = try XCTUnwrap(MeshCoreInterop.splitText(text))
        XCTAssertEqual(parts.count, 13)
        XCTAssertTrue(parts[0].hasPrefix("(1/13) "))
        XCTAssertEqual(parts[0].unicodeScalars.count, "(1/13) ".count + 133 - 8)
        XCTAssertTrue(parts.allSatisfy { $0.unicodeScalars.count <= 133 && $0.utf8.count <= 156 })
        // Up to 9 parts the label room is 6.
        let nine = try XCTUnwrap(MeshCoreInterop.splitText(String(repeating: "y", count: 127 * 3)))
        XCTAssertEqual(nine.count, 3)
        XCTAssertEqual(nine[0], "(1/3) " + String(repeating: "y", count: 127))
    }

    func testCharactersAreCodePoints() throws {
        // "🛰️" is two code points (U+1F6F0 U+FE0F), 7 bytes: 133 code points = 66 of them + 1.
        XCTAssertEqual(MeshCoreInterop.splitText(String(repeating: "🛰️", count: 22))?.count, 1)   // 44 cp, 154 B
        XCTAssertEqual(MeshCoreInterop.splitText(String(repeating: "🛰️", count: 23))?.count, 2)   // 161 B > 156
    }

    func testAnEmojiIsNeverCutInHalf() throws {
        let family = "👨‍👩‍👧‍👦"                                  // 7 code points, 25 bytes, one grapheme
        let text = String(repeating: "a", count: 124) + family + String(repeating: "b", count: 40)
        let parts = try XCTUnwrap(MeshCoreInterop.splitText(text))
        XCTAssertEqual(parts.count, 2)
        XCTAssertTrue(parts.allSatisfy { !$0.contains("\u{200D}") || $0.contains(family) }, "\(parts)")
        XCTAssertEqual(parts[0], "(1/2) " + String(repeating: "a", count: 124), "backed up to the emoji's start")
        XCTAssertTrue(parts[1].contains(family))
    }

    func testCutsAtTheLastSpaceInTheSecondHalf() throws {
        let text = String(repeating: "a", count: 100) + " " + String(repeating: "b", count: 60)
        let parts = try XCTUnwrap(MeshCoreInterop.splitText(text))
        XCTAssertEqual(parts, ["(1/2) " + String(repeating: "a", count: 100), "(2/2) " + String(repeating: "b", count: 60)])
        // A space in the first half is ignored: a hard cut keeps parts full.
        let early = String(repeating: "a", count: 20) + " " + String(repeating: "b", count: 200)
        let p2 = try XCTUnwrap(MeshCoreInterop.splitText(early))
        XCTAssertEqual(p2[0].unicodeScalars.count, 133)
    }
}

/// The open thread of a radio channel shows every speaker, not only our own lines.
final class ChannelThreadPipelineTests: XCTestCase {
    private func row(_ id: String, from: String, to: String) -> SecureMessage {
        SecureMessage(id: id, senderAddress: from, recipientAddress: to,
                      encryptedContent: EncryptedMessage(ciphertext: "", signature: "", senderPublicKey: from, timestamp: Date()),
                      timestamp: Date(timeIntervalSince1970: Double(id.count)), isRead: true, deliveryStatus: .delivered,
                      senderPublicKey: from, recipientPublicKey: to, plaintextContent: id)
    }

    func testAChannelThreadShowsEverySpeaker() {
        let source = [row("a", from: "mcname!Marco", to: "mcchan!Public"),
                      row("bb", from: "me", to: "mcchan!Public"),
                      row("ccc", from: "mcname!Marco", to: "me"),            // not the room
                      row("dddd", from: "mcname!Ana", to: "mcchan!#alps")]   // another room
        XCTAssertEqual(ChatThreadPipeline.threadMessages(from: source, peerKey: "mcchan!Public", myKey: "me").map(\.id),
                       ["a", "bb"])
        // A Meshtastic LoRa room behaves the same.
        let lora = [row("x", from: "lora!0a0b0c0d", to: LoRaChannelThread.key(channelIndex: 0, channelName: "LongFast")),
                    row("yy", from: "me", to: LoRaChannelThread.key(channelIndex: 0, channelName: "LongFast"))]
        XCTAssertEqual(ChatThreadPipeline.threadMessages(from: lora, peerKey: LoRaChannelThread.key(channelIndex: 0, channelName: "LongFast"),
                                                         myKey: "me").count, 2)
        // A one-to-one thread keeps the two-party rule.
        XCTAssertEqual(ChatThreadPipeline.threadMessages(from: source, peerKey: "mcname!Marco", myKey: "me").map(\.id), ["ccc"])
    }
}
