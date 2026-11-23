//
//  DeliveryMethodWireTests.swift
//  OSHITests
//
//  Guards the change that added `DeliveryMethod.lora` so the chat can label
//  which transport actually carried a message.
//
//  Adding a case to a `String`-backed enum is only safe here because of two
//  properties, and BOTH are invisible at the call site — which is exactly why
//  they need tests rather than comments:
//
//   1. `SecureMessage.init(from:)` must survive a `deliveryMethod` it does not
//      recognise. `decodeIfPresent` returns nil for a MISSING key but THROWS on
//      a present-but-unknown raw value, and because it is the whole message's
//      decoder, that throw discards the entire message — text, media and all.
//      A peer on a newer build sending "LoRa" to an older one would have looked
//      exactly like messages vanishing at random.
//
//   2. `meshWireSafe()` must strip `deliveryMethod` entirely, so the new case
//      never reaches such a peer in the first place. Belt as well as braces:
//      property 1 is the safety net for every FUTURE case too, including ones
//      Android might introduce.
//
//  These assert the properties, not the implementations, so they survive a
//  refactor of either side.
//

import XCTest
@testable import OSHI

final class DeliveryMethodWireTests: XCTestCase {

    private func sampleMessage(method: DeliveryMethod?) -> SecureMessage {
        var m = SecureMessage(
            id: "delivery-method-id",
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
        m.plaintextContent = "hello"
        m.deliveryMethod = method
        return m
    }

    /// Swap the raw value of `deliveryMethod` in an encoded message for an
    /// arbitrary string, simulating a peer that knows a transport we do not.
    private func encoded(withDeliveryMethodRawValue raw: String) throws -> Data {
        let wire = try JSONEncoder().encode(sampleMessage(method: .ipfs))
        var json = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: wire) as? [String: Any])
        json["deliveryMethod"] = raw
        return try JSONSerialization.data(withJSONObject: json)
    }

    // MARK: The safety net

    /// THE important one. An unrecognised transport must cost us the LABEL,
    /// never the MESSAGE.
    func testUnknownDeliveryMethodDegradesToNilInsteadOfDroppingTheMessage() throws {
        let data = try encoded(withDeliveryMethodRawValue: "SomeTransportFromTheFuture")

        let decoded = try JSONDecoder().decode(SecureMessage.self, from: data)

        XCTAssertNil(decoded.deliveryMethod,
                     "an unknown transport should degrade to nil, not decode as something else")
        // Everything that actually matters survived.
        XCTAssertEqual(decoded.id, "delivery-method-id")
        XCTAssertEqual(decoded.plaintextContent, "hello")
        XCTAssertEqual(decoded.senderPublicKey, "sender-pub-key")
        XCTAssertEqual(decoded.recipientPublicKey, "recipient-pub-key")
        XCTAssertEqual(decoded.encryptedContent.ciphertext, "3q2+7w==")
    }

    // NOTE — there is deliberately NO test here asserting that "an older build
    // still decodes a message labelled LoRa". Such a test would feed the string
    // "LoRa" to THIS build's decoder, which knows the case, so it passes whether
    // or not the tolerance exists — verified by reverting the fix and watching it
    // pass anyway. It would read as coverage of the cross-version case while
    // proving nothing about it. The unknown-value test above is the real guard;
    // the only true statement about older builds is that `meshWireSafe()` never
    // lets the value reach them, which `testMeshWireSafeStripsTheDeliveryMethod`
    // does check.

    /// A null, and an absent key, must both mean "not stated" — not a failure.
    func testNullAndAbsentDeliveryMethodBothDecodeToNil() throws {
        let wire = try JSONEncoder().encode(sampleMessage(method: .ipfs))
        var json = try XCTUnwrap(try JSONSerialization.jsonObject(with: wire) as? [String: Any])

        json.removeValue(forKey: "deliveryMethod")
        let absent = try JSONDecoder().decode(
            SecureMessage.self, from: try JSONSerialization.data(withJSONObject: json))
        XCTAssertNil(absent.deliveryMethod)

        json["deliveryMethod"] = NSNull()
        let null = try JSONDecoder().decode(
            SecureMessage.self, from: try JSONSerialization.data(withJSONObject: json))
        XCTAssertNil(null.deliveryMethod)
    }

    // MARK: Known values still work

    /// The tolerance above must not have made the field permanently nil.
    func testEveryKnownDeliveryMethodStillRoundTrips() throws {
        for method: DeliveryMethod in [.mesh, .ipfs, .lora, .pending] {
            let wire = try JSONEncoder().encode(sampleMessage(method: method))
            let decoded = try JSONDecoder().decode(SecureMessage.self, from: wire)
            XCTAssertEqual(decoded.deliveryMethod, method,
                           "\(method.rawValue) did not survive a round trip")
        }
    }

    /// Raw values are persisted in the message store and were chosen before
    /// `.lora` existed. Changing one silently relabels every stored message.
    func testRawValuesAreStableBecauseTheyArePersisted() {
        XCTAssertEqual(DeliveryMethod.mesh.rawValue, "Direct")
        XCTAssertEqual(DeliveryMethod.ipfs.rawValue, "Cloud")
        XCTAssertEqual(DeliveryMethod.lora.rawValue, "LoRa")
        XCTAssertEqual(DeliveryMethod.pending.rawValue, "Sending...")
    }

    // MARK: The strip

    /// `.lora` must not travel. Each side stamps the transport it observed.
    func testMeshWireSafeStripsTheDeliveryMethod() throws {
        for method: DeliveryMethod in [.mesh, .ipfs, .lora, .pending] {
            let safe = sampleMessage(method: method).meshWireSafe()
            XCTAssertNil(safe.deliveryMethod)

            let wire = try JSONEncoder().encode(safe)
            let json = try XCTUnwrap(
                try JSONSerialization.jsonObject(with: wire) as? [String: Any])
            XCTAssertNil(json["deliveryMethod"],
                         "\(method.rawValue) reached the wire; a new case would break older peers")
        }
    }

    /// The strip must not have become a general-purpose eraser: identity and
    /// addressing still have to survive, or the recipient check drops the
    /// message on arrival.
    func testStrippingTheMethodLeavesAddressingIntact() {
        let safe = sampleMessage(method: .lora).meshWireSafe()
        XCTAssertEqual(safe.id, "delivery-method-id")
        XCTAssertEqual(safe.senderPublicKey, "sender-pub-key")
        XCTAssertEqual(safe.recipientPublicKey, "recipient-pub-key")
        XCTAssertEqual(safe.encryptedContent.ciphertext, "3q2+7w==")
    }

    // MARK: - __MESH_INBOUND_PLAINTEXT_2026_11_20__

    /// A forged mesh frame: a neighbour with no key claims a contact's identity
    /// and ships the "already decrypted" sidecar. After the ingress strip
    /// nothing it wrote can reach `receiveMessage` as trusted plaintext or
    /// media, and only the ciphertext (which will fail the ratchet) remains.
    func testForgedMeshFrameLosesItsPlaintextAndMediaAtIngress() throws {
        let forged: [String: Any] = [
            "id": "forged-1",
            "senderAddress": "x", "recipientAddress": "y",
            "encryptedContent": ["ciphertext": "", "signature": "",
                                 "senderPublicKey": "victim-contact-key",
                                 "timestamp": 700_000_000.0],
            "timestamp": 700_000_000.0,
            "isRead": false, "deliveryStatus": "sent",
            "senderPublicKey": "victim-contact-key",
            "recipientPublicKey": "me",
            "plaintextContent": "\u{1F4E2}GROUP_UPDATE\u{1F4E2}{\"id\":\"x\"}",
            "mediaAttachment": Data("not-a-photo".utf8).base64EncodedString(),
            "originalMediaData": Data("x".utf8).base64EncodedString(),
            "mediaType": "image",
            "editedContent": "edited by attacker",
        ]
        let data = try JSONSerialization.data(withJSONObject: forged)
        let decoded = try JSONDecoder().decode(SecureMessage.self, from: data)
        XCTAssertNotNil(decoded.plaintextContent, "precondition: the wire format DOES carry it")

        let safe = decoded.meshInboundSafe()
        XCTAssertNil(safe.plaintextContent)
        XCTAssertNil(safe.mediaAttachment)
        XCTAssertNil(safe.originalMediaData)
        XCTAssertNil(safe.editedContent)
        XCTAssertNil(safe.deliveryMethod)
        XCTAssertEqual(safe.senderPublicKey, "victim-contact-key")
        XCTAssertEqual(safe.recipientPublicKey, "me")
    }

    /// Every place a SecureMessage is decoded off an unauthenticated radio must
    /// go through the strip. Source scan: comments removed first.
    func testEveryMeshIngressDecodeIsStripped() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        func code(_ rel: String) throws -> String {
            let text = try String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)
            return text.split(separator: "\n", omittingEmptySubsequences: false)
                .map { line -> Substring in
                    if let r = line.range(of: "//") { return line[..<r.lowerBound] }
                    return line
                }.joined(separator: "\n")
        }
        let mesh = try code("OSHI/MeshNetworkManager.swift")
        XCTAssertEqual(mesh.components(separatedBy: "decode(SecureMessage.self, from: data))?.meshInboundSafe()").count - 1, 2)
        XCTAssertEqual(mesh.components(separatedBy: "decode(SecureMessage.self, from: data)").count - 1, 2,
                       "a new unstripped SecureMessage decode appeared in MeshNetworkManager")
        XCTAssertTrue(mesh.contains("relayMessage.message.meshInboundSafe()"))
        XCTAssertFalse(mesh.contains("let message = relayMessage.message\n"))
        XCTAssertTrue(try code("OSHI/MeshtasticManager.swift").contains("msg = msg.meshInboundSafe()"))
        XCTAssertTrue(try code("OSHI/MeshCoreRadio.swift").contains("msg = msg.meshInboundSafe()"))
    }

    /// The legacy raw MEDIA_MESSAGE caption becomes `plaintextContent`, which
    /// `receiveMessage` routes on before any decryption. Prose survives;
    /// a protocol sentinel does not.
    func testLegacyMeshCaptionCannotCarryAControlPayload() {
        XCTAssertEqual(MeshNetworkManager.legacyMeshCaption("la photo d'hier"), "la photo d'hier")
        XCTAssertEqual(MeshNetworkManager.legacyMeshCaption(""), "")
        for forged in ["\u{1F4E2}GROUP_UPDATE\u{1F4E2}{\"type\":\"member_removed\"}",
                       "🔧ACTION🔧{}", "📸PROFILE_UPDATE📸{}", "📖READ_RECEIPT📖",
                       "  📢GROUP_UPDATE📢{}"] {
            XCTAssertEqual(MeshNetworkManager.legacyMeshCaption(forged), "", forged)
        }
    }

    /// __BLE_DIAL_ANY_IP_2026_11_20__ A BLE-announced address is dialled only
    /// when it is on a local network.
    func testOnlyLanAddressesFromBLEAreDialled() {
        for ok in ["192.168.1.20", "10.0.0.5", "172.20.10.2", "172.31.255.1", "169.254.3.4", "100.64.0.1"] {
            XCTAssertTrue(CrossPlatformMesh.isDialableMeshIPv4(ok), ok)
        }
        for bad in ["8.8.8.8", "45.67.216.197", "172.32.0.1", "127.0.0.1", "224.0.0.1", "0.0.0.0",
                    "192.168.1", "192.168.1.256", "192.168.1.1.1", "", "evil.example", "::1",
                    "192.168.01x.1", "1e2.0.0.1"] {
            XCTAssertFalse(CrossPlatformMesh.isDialableMeshIPv4(bad), bad)
        }
    }
}
