//
//  MeshCoreRadioTests.swift
//
//  Pins OSHI-over-MeshCore to the two things it must match byte for byte: the "OB" envelope of
//  oshi-meshcore-bridge (vectors below were produced by oshi_bridge/wire.py) and the MeshCore companion
//  protocol (examples/companion_radio/MyMesh.cpp). A drift in either reads as "MeshCore never delivers":
//  the bridge drops a foreign envelope silently and the radio answers ERR to a malformed command.
//

import XCTest
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

private extension Data {
    var mcHex: String { map { String(format: "%02x", $0) }.joined() }
}
