//
//  LoRaWireSafetyTests.swift
//  OSHITests
//
//  Guards the fix for a measured leak: MeshtasticManager.sendSecureMessage
//  encoded the SecureMessage whole. SecureMessage has no custom encode(to:) and
//  plaintextContent is in its CodingKeys, so the user's actual text went on the
//  air beside the ciphertext — broadcast, on the default LongFast channel, whose
//  key is public. The BLE/Wi-Fi mesh path had stripped it since the [C2] audit
//  via meshWireSafe(); the LoRa path never got the same treatment.
//
//  These tests assert the property that matters — no cleartext on the wire —
//  rather than asserting that a particular function is called, so they keep
//  working if the send path is refactored.
//

import XCTest
@testable import OSHI

final class LoRaWireSafetyTests: XCTestCase {

    private let secret = "meet me at the north bridge at 21:00"

    private func sampleMessage() -> SecureMessage {
        var m = SecureMessage(
            id: "test-message-id",
            senderAddress: "0xSENDER",
            recipientAddress: "0xRECIPIENT",
            encryptedContent: EncryptedMessage(
                ciphertext: "3q2+7w==",
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
        m.plaintextContent = secret
        return m
    }

    /// The strip itself: every cleartext sidecar field gone.
    func testMeshWireSafeRemovesEveryCleartextField() {
        var m = sampleMessage()
        m.mediaAttachment = Data([0xAA, 0xBB])
        m.originalMediaData = Data([0x01, 0x02, 0x03])
        m.editedContent = "an earlier draft of the secret"

        let safe = m.meshWireSafe()

        XCTAssertNil(safe.plaintextContent)
        XCTAssertNil(safe.mediaAttachment)
        XCTAssertNil(safe.originalMediaData)
        XCTAssertNil(safe.editedContent)
    }

    /// The ciphertext must survive the strip, or the recipient gets nothing.
    func testMeshWireSafeKeepsTheCiphertextAndRouting() {
        let m = sampleMessage()
        let safe = m.meshWireSafe()

        XCTAssertEqual(safe.encryptedContent.ciphertext, m.encryptedContent.ciphertext)
        XCTAssertEqual(safe.senderPublicKey, m.senderPublicKey)
        XCTAssertEqual(safe.recipientPublicKey, m.recipientPublicKey)
        XCTAssertEqual(safe.id, m.id)
    }

    /// The regression that started this: the bytes that would actually be
    /// chunked into LoRa frames must not contain the message text.
    func testEncodedLoRaFrameBytesContainNoCleartext() throws {
        let m = sampleMessage()

        // What the old code put on the air.
        let unsafeJSON = try JSONEncoder().encode(m)
        XCTAssertTrue(
            String(decoding: unsafeJSON, as: UTF8.self).contains(secret),
            "Preconditions changed: SecureMessage no longer encodes plaintextContent, "
            + "so this test would pass for the wrong reason."
        )

        // What sendSecureMessage encodes now.
        let wireJSON = try JSONEncoder().encode(m.meshWireSafe())
        XCTAssertFalse(
            String(decoding: wireJSON, as: UTF8.self).contains(secret),
            "Cleartext message text is on the LoRa wire."
        )
    }
}
