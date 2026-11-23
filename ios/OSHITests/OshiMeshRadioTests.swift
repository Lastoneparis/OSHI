//
//  OshiMeshRadioTests.swift
//
//  Pins the app half of OSHI Mesh Protocol v1 (OSHI/OshiMeshRadio.swift) to the bytes the firmware
//  expects (src/oshi/OshiProtocol.cpp, test/test_oshi_protocol in oshi-mesh-firmware). The firmware
//  REJECTS a message whose middle fragments are not exactly 182 bytes and silently ignores a frame
//  with a wrong magic/version, so a drift here would read as "LoRa is flaky", not as a failure.
//
//  Regressions guarded: fragments sized differently from the firmware's OMP_MAX_FRAG_DATA, a header
//  field moved by one byte, STATUS states renumbered (DELIVERED shown for a message only IN_CUSTODY),
//  a compressed body the receiver cannot inflate, and reassembly that delivers a partial message.
//

import XCTest
@testable import OSHI

final class OshiMeshRadioTests: XCTestCase {

    func testDataFrameLayoutMatchesFirmware() throws {
        let body = Data((0..<400).map { UInt8($0 & 0xFF) })
        let frames = try XCTUnwrap(OMP.dataFrames(msgId: 0xDEADBEEF, dest: 0x55667788, body: body, flags: OMP.flagCustodyOK))
        XCTAssertEqual(frames.count, 3)
        XCTAssertEqual(frames[0].count, 18 + 182)
        XCTAssertEqual(frames[1].count, 18 + 182)
        XCTAssertEqual(frames[2].count, 18 + 400 - 364)
        let h = [UInt8](frames[1].prefix(18))
        XCTAssertEqual(h, [0x4F, 0x53, 0x11,
                           0xEF, 0xBE, 0xAD, 0xDE,      // msgId
                           0, 0, 0, 0,                  // origin: stamped by the radio
                           0x88, 0x77, 0x66, 0x55,      // dest
                           1, 3, 0x01])                 // idx, count, flags
        let parsed = try XCTUnwrap(OMP.parseData(frames[1]))
        XCTAssertEqual(parsed.idx, 1)
        XCTAssertEqual(parsed.count, 3)
        XCTAssertEqual(parsed.payload, body[182..<364])
    }

    /// On a stock radio the phone does OMP itself: nothing downstream stamps the origin, so the phone must, and
    /// the receiving side keys reassembly on it (two phones' msgId 7 must not merge).
    func testPhoneStampsOriginOnStockRadio() throws {
        let frames = try XCTUnwrap(OMP.dataFrames(msgId: 7, dest: OMP.broadcast, body: Data(count: 300), origin: 0xA23033DB))
        XCTAssertEqual([UInt8](frames[0][7..<11]), [0xDB, 0x33, 0x30, 0xA2])
        let a = try XCTUnwrap(OMP.parseData(frames[0]))
        XCTAssertEqual(a.origin, 0xA23033DB)
        XCTAssertEqual(a.dest, OMP.broadcast)

        let rx = OMPReassembler()
        let other = try XCTUnwrap(OMP.dataFrames(msgId: 7, dest: OMP.broadcast, body: Data(count: 300), origin: 0x11111111))
        XCTAssertNil(rx.accept(a))
        XCTAssertNil(rx.accept(try XCTUnwrap(OMP.parseData(other[1]))))
        XCTAssertEqual(rx.accept(try XCTUnwrap(OMP.parseData(frames[1])))?.count, 300)
    }

    func testOversizeMessageRefused() {
        XCTAssertNotNil(OMP.dataFrames(msgId: 1, dest: 2, body: Data(count: OMP.maxMessage)))
        XCTAssertNil(OMP.dataFrames(msgId: 1, dest: 2, body: Data(count: OMP.maxMessage + 1)))
    }

    func testLegacyOMFrameIsNotOMP() {
        XCTAssertNil(OMP.type(of: Data([0x4F, 0x4D, 1, 2, 3, 4, 0, 1])))
        XCTAssertNil(OMP.type(of: Data([0x4F, 0x53, 0x21])))   // version 2 is not ours
    }

    func testStatusStatesMatchFirmwareNumbering() throws {
        // encodeStatus: prefix | msgId u32 | state u8 | node u32
        let frame = Data([0x4F, 0x53, 0x16, 9, 0, 0, 0, 4, 0x01, 0xEF, 0xCD, 0xAB])
        let st = try XCTUnwrap(OMP.parseStatus(frame))
        XCTAssertEqual(st.msgId, 9)
        XCTAssertEqual(st.state, .inCustody)
        XCTAssertEqual(st.node, 0xABCDEF01)
        XCTAssertEqual(OMP.State.delivered.rawValue, 3)
        XCTAssertEqual(OMP.State.rejected.rawValue, 7)
        XCTAssertNil(OMP.parseStatus(Data([0x4F, 0x53, 0x16, 9, 0, 0, 0, 99, 0, 0, 0, 0])))
    }

    func testBeaconProbeAndReply() throws {
        XCTAssertEqual([UInt8](OMP.beaconProbe()), [0x4F, 0x53, 0x15, 0, 0, 0, 0])
        let reply = try XCTUnwrap(OMP.parseBeacon(Data([0x4F, 0x53, 0x15, 0x03, 0x00, 0x01, 31])))
        XCTAssertEqual(reply.caps, OMP.capCustodian | OMP.capGatewayOnline)
        XCTAssertEqual(reply.version, 0x0100)
        XCTAssertEqual(reply.custodyFreeKb, 31)
    }

    func testBodyRoundTripAndCompression() throws {
        let json = Data(String(repeating: "{\"ciphertext\":\"QUJDREVGR0g=\",\"senderPublicKey\":\"abc\"}", count: 20).utf8)
        let body = OMP.encodeBody(json)
        XCTAssertEqual(body.first, 0x02)
        XCTAssertLessThan(body.count, json.count)
        XCTAssertEqual(OMP.decodeBody(body), json)
        let tiny = Data("{}".utf8)
        XCTAssertEqual(OMP.decodeBody(OMP.encodeBody(tiny)), tiny)
        XCTAssertNil(OMP.decodeBody(Data([0x44, 1, 4])))   // a gateway 'D' frame is not an envelope body
    }

    func testReassemblyOutOfOrderAndPartial() throws {
        let body = Data((0..<500).map { UInt8(($0 * 7) & 0xFF) })
        let frames = try XCTUnwrap(OMP.dataFrames(msgId: 42, dest: 7, body: body)).compactMap(OMP.parseData)
        let r = OMPReassembler()
        XCTAssertNil(r.accept(frames[2]))
        XCTAssertNil(r.accept(frames[0]))
        XCTAssertNil(r.accept(frames[0]))              // duplicate does not complete it
        XCTAssertEqual(r.accept(frames[1]), body)
        // A partial message never surfaces, and expires.
        let other = try XCTUnwrap(OMP.dataFrames(msgId: 43, dest: 7, body: body)).compactMap(OMP.parseData)
        XCTAssertNil(r.accept(other[0], now: Date(timeIntervalSinceNow: -1000)))
        XCTAssertNil(r.accept(other[1]))
    }

    func testChannelPSKMatchesFirmware() {
        // SHA-256("OSHI Mesh channel v1"); a mismatch puts the app and the firmware on different channels.
        XCTAssertEqual(OMP.oshiChannelPSK.count, 32)
        XCTAssertEqual(OMP.oshiChannelPSK.prefix(4), Data([0x29, 0x43, 0x6d, 0x19]))
        XCTAssertEqual(OMP.oshiChannelPSK.suffix(4), Data([0x68, 0x4c, 0xb9, 0x50]))
    }
}

// MARK: - Long text over an OSHI Mesh radio, held queue, airtime hint (18/11)
//
// __LORA_LONG_TEXT_OMP_2026_11_18__ Seeed's failure: an off-grid 2,000-char text was split into
// five ≤500 B messages although the OSHI Mesh firmware carries 64 x 182 B. __LORA_HELD_DURABLE__ /
// __LORA_PROBE_BEFORE_FLUSH__ / __LORA_AIRTIME_HINT__: the anti-jamming audit's message-loss points.
// Android twins: LoRaLongTextTest, LoRaHeldQueueTest.

final class LoRaLongTextAndHeldQueueTests: XCTestCase {

    // MARK: limit selection

    func testOshiMeshRadioGets4000StockOrUnknownKeeps500() {
        XCTAssertEqual(MessageTextSplitter.loRaOmpUTF8Bytes, 4_000)
        func limit(paired: Bool, path: Bool, oshi: Bool?) -> Int {
            if let oshi {
                return MessageTextSplitter.maxUTF8Bytes(radioPaired: paired, hasPhysicalPath: path,
                                                        internetAllowedForSending: true,
                                                        prefersRadioOverRelay: false, oshiFirmware: oshi)
            }
            return MessageTextSplitter.maxUTF8Bytes(radioPaired: paired, hasPhysicalPath: path,
                                                    internetAllowedForSending: true, prefersRadioOverRelay: false)
        }
        XCTAssertEqual(limit(paired: true, path: false, oshi: true), 4_000)
        XCTAssertEqual(limit(paired: true, path: false, oshi: false), 500)
        XCTAssertEqual(limit(paired: true, path: false, oshi: nil), 500)      // unknown firmware
        XCTAssertEqual(limit(paired: true, path: true, oshi: true), 16_000)   // LoRa is not the route
        XCTAssertEqual(limit(paired: false, path: false, oshi: true), 16_000)
    }

    func testSeeedsTwoThousandCharacterTextIsOneMessageWithAnOshiMeshRadio() {
        let text = String(repeating: "LoRa long text test. ", count: 100)   // 2,100 B
        let oshi = MessageTextSplitter.maxUTF8Bytes(radioPaired: true, hasPhysicalPath: false,
                                                    internetAllowedForSending: true,
                                                    prefersRadioOverRelay: false, oshiFirmware: true)
        if case .single = MessageTextSplitter.splitForSend(text, maxUTF8Bytes: oshi) {} else {
            XCTFail("2,100 B must leave as ONE message over an OSHI Mesh radio")
        }
        let stock = MessageTextSplitter.maxUTF8Bytes(radioPaired: true, hasPhysicalPath: false,
                                                     internetAllowedForSending: true, prefersRadioOverRelay: false)
        if case .single = MessageTextSplitter.splitForSend(text, maxUTF8Bytes: stock) {
            XCTFail("a stock radio still needs the 500 B parts")
        }
    }

    /// The envelope for 4,000 B of text, layered as the app layers it (ratchet AES-GCM → JSON → base64 →
    /// AES-GCM → base64 → SecureMessage → meshWireSafe JSON), fits 64 x 182 even without deflate.
    func testFourThousandBytesFitAnOshiMeshRadioButNotTheStockPath() throws {
        func rnd(_ n: Int) -> Data { Data((0..<n).map { _ in UInt8.random(in: 0...255) }) }
        let textBytes = 4_000
        let ratchetJSON = #"{"header":{"publicKey":"\#(rnd(32).base64EncodedString())","messageNumber":4242,"previousChainLength":4241,"isDHRatchet":false,"timestamp":785000000.123456,"sessionVersion":3,"sessionId":"\#(UUID().uuidString)"},"ciphertext":"\#(rnd(textBytes + 28).base64EncodedString())"}"#
        let ratchetB64 = Data(ratchetJSON.utf8).base64EncodedString()
        let key = rnd(65).base64EncodedString()
        var m = SecureMessage(
            id: UUID().uuidString, senderAddress: key, recipientAddress: key,
            encryptedContent: EncryptedMessage(ciphertext: rnd(ratchetB64.utf8.count + 28).base64EncodedString(),
                                               signature: "", senderPublicKey: key, timestamp: Date()),
            timestamp: Date(), isRead: false, deliveryStatus: .sent)
        m.senderPublicKey = key
        m.recipientPublicKey = key
        m.plaintextContent = String(repeating: "é", count: textBytes / 2)
        let json = try JSONEncoder().encode(m.meshWireSafe())
        // Measured layering ≈ 1,350 + 2.38 × bytes (three base64 layers); must fit 64 × 182 even raw.
        XCTAssertLessThanOrEqual(json.count, OMP.maxFrags * OMP.maxFragData, "wire \(json.count) B")
        let body = OMP.encodeBody(json)
        XCTAssertNotNil(OMP.dataFrames(msgId: 1, dest: 0x51e37c4a, body: body, flags: OMP.flagCustodyOK))
        XCTAssertTrue(LoRaRadioGate.fits(ompBodyBytes: body.count, oshiFirmware: true))
        XCTAssertFalse(LoRaRadioGate.fits(ompBodyBytes: body.count, oshiFirmware: false))
    }

    // MARK: probe before flush

    func testAReadyRadioHoldsUntilTheFirmwareProbeIsSettled() {
        XCTAssertFalse(LoRaRadioGate.mayFlush(ready: true, probeDone: false, answeredOSHI: false, excludedForJamming: false))
        XCTAssertEqual(LoRaRadioGate.holdReason(probeDone: false, answeredOSHI: false, excludedForJamming: false),
                       "waiting for the radio's firmware probe")
        XCTAssertTrue(LoRaRadioGate.mayFlush(ready: true, probeDone: false, answeredOSHI: true, excludedForJamming: false))
        XCTAssertNil(LoRaRadioGate.holdReason(probeDone: false, answeredOSHI: true, excludedForJamming: false))
        XCTAssertTrue(LoRaRadioGate.mayFlush(ready: true, probeDone: true, answeredOSHI: false, excludedForJamming: false))
        XCTAssertFalse(LoRaRadioGate.mayFlush(ready: false, probeDone: true, answeredOSHI: true, excludedForJamming: false))
        XCTAssertFalse(LoRaRadioGate.mayFlush(ready: true, probeDone: true, answeredOSHI: true, excludedForJamming: true))
        XCTAssertEqual(LoRaRadioGate.holdReason(probeDone: true, answeredOSHI: true, excludedForJamming: true),
                       "radio channel looks jammed")
    }

    func testFitsFollowsTheRadio() {
        let stockMax = MeshtasticManager.loRaMaxFrames * OMP.maxFragData
        XCTAssertTrue(LoRaRadioGate.fits(ompBodyBytes: stockMax, oshiFirmware: false))
        XCTAssertFalse(LoRaRadioGate.fits(ompBodyBytes: stockMax + 1, oshiFirmware: false))
        XCTAssertTrue(LoRaRadioGate.fits(ompBodyBytes: stockMax + 1, oshiFirmware: true))
        XCTAssertTrue(LoRaRadioGate.fits(ompBodyBytes: OMP.maxFrags * OMP.maxFragData, oshiFirmware: true))
        XCTAssertFalse(LoRaRadioGate.fits(ompBodyBytes: OMP.maxFrags * OMP.maxFragData + 1, oshiFirmware: true))
    }

    // MARK: held queue

    private func msg(_ id: String) -> SecureMessage {
        SecureMessage(id: id, senderAddress: "s", recipientAddress: "r",
                      encryptedContent: EncryptedMessage(ciphertext: "3q2+7w==", signature: "", senderPublicKey: "s",
                                                         timestamp: Date(timeIntervalSince1970: 1_700_000_000)),
                      timestamp: Date(timeIntervalSince1970: 1_700_000_000), isRead: false, deliveryStatus: .sent)
    }

    func testHeldMessagesSurviveARelaunch() throws {
        var disk: Data?
        func make() -> LoRaHeldQueue {
            LoRaHeldQueue(load: { disk.flatMap { try? JSONDecoder().decode([LoRaHeldQueue.Held].self, from: $0) } },
                          save: { disk = try? JSONEncoder().encode($0) })
        }
        let q = make()
        _ = q.add(msg("a"), now: Date(timeIntervalSince1970: 10))
        _ = q.add(msg("b"), now: Date(timeIntervalSince1970: 20))
        q.remove("a")
        let revived = make()
        XCTAssertEqual(revived.snapshot().map(\.message.id), ["b"])
        XCTAssertEqual(revived.snapshot()[0].heldAt, Date(timeIntervalSince1970: 20))
    }

    func testOverCapacityEvictionsAreReturnedNeverSilent() {
        let q = LoRaHeldQueue(capacity: 3)
        XCTAssertTrue(q.add(msg("1"), now: Date()).isEmpty)
        _ = q.add(msg("2"), now: Date())
        _ = q.add(msg("3"), now: Date())
        _ = q.add(msg("2"), now: Date())          // same message again: still one entry
        XCTAssertEqual(q.count, 3)
        XCTAssertEqual(q.add(msg("4"), now: Date()), ["1"])
        XCTAssertEqual(q.snapshot().map(\.message.id), ["3", "2", "4"])
        XCTAssertGreaterThanOrEqual(LoRaHeldQueue.defaultCapacity, 200)
    }

    // MARK: airtime hint

    func testQueuedForOverAMinuteIsFlaggedAndProgressClearsIt() {
        let t0 = Date(timeIntervalSince1970: 1_000)
        var q: [String: Date] = ["a": t0, "b": t0.addingTimeInterval(30)]
        XCTAssertTrue(LoRaAirtimeStatus.waiting(q, now: t0.addingTimeInterval(59)).isEmpty)
        XCTAssertEqual(LoRaAirtimeStatus.waiting(q, now: t0.addingTimeInterval(60)), ["a"])
        XCTAssertEqual(LoRaAirtimeStatus.waiting(q, now: t0.addingTimeInterval(95)), ["a", "b"])
        q["a"] = nil
        XCTAssertEqual(LoRaAirtimeStatus.waiting(q, now: t0.addingTimeInterval(95)), ["b"])
        XCTAssertTrue(LoRaAirtimeStatus.waiting(q, now: t0.addingTimeInterval(3 * 3600)).isEmpty)
    }

    // MARK: wiring (sources), parity with Android

    private func source(_ name: String) throws -> String {
        let here = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        return try String(contentsOf: here.appendingPathComponent("OSHI/\(name)"), encoding: .utf8)
    }

    func testConfigCompleteNoLongerFlushesBeforeTheProbe() throws {
        let s = try source("MeshtasticManager.swift")
        guard let r = s.range(of: "startOshiMeshSetup()      // OSHI Mesh firmware?") else { return XCTFail() }
        let after = String(s[r.upperBound...].prefix(400))
        XCTAssertFalse(after.contains("\n                    flushStoreAndForward()"))
        XCTAssertTrue(s.contains("rememberOshiFirmware(true)"))
        // Override parity: bridged frames obey "Send over LoRa anyway" (Android aligned on this).
        guard let rr = s.range(of: "func relayOMPFrame(") else { return XCTFail() }
        XCTAssertTrue(String(s[rr.upperBound...].prefix(200)).contains("excludesLoRaForJamming"))
    }

    func testInternetOffHandsTheMessageToTheRadioHeldQueue() throws {
        let s = try source("MessageManager.swift")
        guard let r = s.range(of: "Internet sending is off for this conversation") else { return XCTFail() }
        XCTAssertTrue(String(s[r.upperBound...].prefix(900)).contains("holdForRadio(message)"))
        XCTAssertTrue(s.contains("oshiFirmware: MeshtasticManager.lastKnownOshiFirmware"))
    }
}
