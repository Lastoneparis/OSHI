//
//  LoRaRoundTripTests.swift
//  OSHITests
//
//  Stripping plaintext off the LoRa wire changed the bytes that get chunked,
//  framed, transmitted, reassembled and decoded. This exercises that whole
//  round trip against the real frame format, because a decoder that threw on a
//  missing key would drop every LoRa message silently — the failure mode would
//  look exactly like "the radio isn't working".
//
//  The framing here mirrors MeshtasticManager.sendSecureMessage /
//  handleOshiChunk, which are private. If that format ever changes, these tests
//  should be updated in lockstep — they are the written-down contract for it.
//

import XCTest
@testable import OSHI

final class LoRaRoundTripTests: XCTestCase {

    /// Must match MeshtasticManager.loRaChunk.
    private let chunkSize = 180
    private let magic: [UInt8] = [0x4F, 0x4D]   // "OM"

    private func message(text: String) -> SecureMessage {
        var m = SecureMessage(
            id: "round-trip-id",
            senderAddress: "0xSENDER",
            recipientAddress: "0xRECIPIENT",
            encryptedContent: EncryptedMessage(
                ciphertext: String(repeating: "A", count: 256),
                signature: "c2ln",
                senderPublicKey: "sender-pub-key",
                timestamp: Date(timeIntervalSince1970: 1_700_000_000)
            ),
            timestamp: Date(timeIntervalSince1970: 1_700_000_000),
            isRead: false,
            deliveryStatus: .sent
        )
        m.senderPublicKey = "sender-pub-key"
        m.recipientPublicKey = "recipient-pub-key"
        m.plaintextContent = text
        return m
    }

    /// Frame exactly as sendSecureMessage does.
    private func frames(for data: Data) -> [Data] {
        let chunks = stride(from: 0, to: data.count, by: chunkSize).map {
            data.subdata(in: $0 ..< min($0 + chunkSize, data.count))
        }
        let msgId = UInt32(0xDEADBEEF)
        return chunks.enumerated().map { i, chunk in
            var frame = Data(magic)
            var idLE = msgId.littleEndian
            withUnsafeBytes(of: &idLE) { frame.append(contentsOf: $0) }
            frame.append(UInt8(i))
            frame.append(UInt8(chunks.count))
            frame.append(chunk)
            return frame
        }
    }

    /// Reassemble exactly as handleOshiChunk does, in whatever order given.
    private func reassemble(_ frames: [Data]) -> Data? {
        var parts: [Int: Data] = [:]
        var total = 0
        for payload in frames {
            guard payload.count > 8,
                  payload[payload.startIndex] == 0x4F,
                  payload[payload.startIndex + 1] == 0x4D else { return nil }
            let body = payload.dropFirst(2)
            let seq = Int(body[body.startIndex + 4])
            total = Int(body[body.startIndex + 5])
            guard total > 0, seq < total, total <= 40 else { return nil }
            parts[seq] = Data(body.dropFirst(6))
        }
        guard parts.count == total else { return nil }
        var full = Data()
        for i in 0 ..< total { guard let p = parts[i] else { return nil }; full.append(p) }
        return full
    }

    /// The whole path: strip → encode → frame → reassemble → decode.
    func testMessageSurvivesTheFullLoRaRoundTrip() throws {
        let original = message(text: "rendezvous at the old mill, 06:00")
        let wire = try JSONEncoder().encode(original.meshWireSafe())

        let sent = frames(for: wire)
        let received = try XCTUnwrap(reassemble(sent), "reassembly returned nothing")
        XCTAssertEqual(received, wire, "bytes changed in transit")

        let decoded = try JSONDecoder().decode(SecureMessage.self, from: received)

        // Identity and addressing must survive, or the recipient check drops it.
        XCTAssertEqual(decoded.id, original.id)
        XCTAssertEqual(decoded.recipientPublicKey, original.recipientPublicKey)
        XCTAssertEqual(decoded.senderPublicKey, original.senderPublicKey)
        XCTAssertEqual(decoded.encryptedContent.ciphertext, original.encryptedContent.ciphertext)

        // And the plaintext must NOT have travelled.
        XCTAssertNil(decoded.plaintextContent)
    }

    /// A missing plaintextContent key must decode to nil, not throw. If this
    /// ever regresses to a non-optional decode, every LoRa message is dropped
    /// in silence and the radio looks broken.
    func testDecoderToleratesAbsentPlaintextKey() throws {
        let wire = try JSONEncoder().encode(message(text: "anything").meshWireSafe())
        let json = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: wire) as? [String: Any])
        XCTAssertNil(json["plaintextContent"], "strip did not remove the key")

        XCTAssertNoThrow(try JSONDecoder().decode(SecureMessage.self, from: wire))
    }

    /// Frames arrive over the air in whatever order the mesh delivers them.
    func testOutOfOrderFramesReassembleCorrectly() throws {
        let original = message(text: String(repeating: "long message ", count: 40))
        let wire = try JSONEncoder().encode(original.meshWireSafe())
        let sent = frames(for: wire)
        XCTAssertGreaterThan(sent.count, 1, "test needs a multi-frame message")

        let received = try XCTUnwrap(reassemble(sent.reversed()))
        XCTAssertEqual(received, wire)
        XCTAssertEqual(try JSONDecoder().decode(SecureMessage.self, from: received).id, original.id)
    }

    /// Stripping the plaintext can only make the payload smaller, so it can
    /// never push a message over the 12-frame cap that used to fit.
    func testStrippingNeverIncreasesFrameCount() throws {
        for length in [0, 1, 50, 200, 1000] {
            let m = message(text: String(repeating: "x", count: length))
            let before = frames(for: try JSONEncoder().encode(m)).count
            let after = frames(for: try JSONEncoder().encode(m.meshWireSafe())).count
            XCTAssertLessThanOrEqual(after, before, "text length \(length) produced more frames after the strip")
        }
    }
}
