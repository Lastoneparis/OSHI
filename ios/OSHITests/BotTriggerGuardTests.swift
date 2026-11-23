//
//  BotTriggerGuardTests.swift
//  OSHITests
//
//  __BOT_RUNTIME_OWNS_DELIVERY_2026_08_16__ — étape 2 du plan de migration.
//
//  Step 2 moves the bot hook out of `GroupViews` and into the manager, on BOTH
//  sides: the message you send and — new — the message you receive. That second
//  half is what makes bots real, and it is also what makes a feedback loop
//  possible for the first time: before, a bot could only ever see its owner's own
//  typing, so a bot answering a bot was not reachable.
//
//  `shouldEvaluateTriggers` is the guard that keeps that door shut, and it is
//  pure, so it is asserted here directly rather than inferred from behaviour.
//

import XCTest
@testable import OSHI

final class BotTriggerGuardTests: XCTestCase {

    private let human = "SHVtYW5LZXlBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQQ=="

    // MARK: - The loop

    func testBotOutputNeverFiresATrigger() {
        XCTAssertFalse(BotManager.shouldEvaluateTriggers(senderKey: "bot:1a2b3c4d", content: "[Météo] 12°C"),
                       "two bots with crossing keywords would answer each other forever")
    }

    func testHumanProseFiresTriggers() {
        XCTAssertTrue(BotManager.shouldEvaluateTriggers(senderKey: human, content: "/meteo"))
        XCTAssertTrue(BotManager.shouldEvaluateTriggers(senderKey: human, content: "quelqu'un a vu la carte ?"))
    }

    // MARK: - Plumbing is not prose

    /// A keyword trigger could match a receipt and make a bot answer the
    /// transport. Every silent control payload is excluded by the shared catalog,
    /// so this cannot drift out of sync with the wire.
    func testControlPayloadsAreNotProse() {
        let control = [
            "📬DELIVERY_RECEIPT📬ABC-123",
            "📖READ_RECEIPT📖",
            "⌨️TYPING⌨️",
            "📸PROFILE_REQUEST📸",
            "🔧ACTION🔧{}",
            "__SESSION_RESET__|1|key",
        ]
        for payload in control {
            XCTAssertFalse(BotManager.shouldEvaluateTriggers(senderKey: human, content: payload),
                           "\(payload.prefix(16))… reached a trigger")
        }
    }

    /// Rendered payloads — a call summary, a location — are control too: they are
    /// events, not something a keyword bot should reply to.
    func testRenderedControlPayloadsAreAlsoExcluded() {
        XCTAssertFalse(BotManager.shouldEvaluateTriggers(senderKey: human, content: "📍LOCATION📍{\"lat\":1}"))
        XCTAssertFalse(BotManager.shouldEvaluateTriggers(senderKey: human, content: "📱CALL_SUMMARY📱📞↗️call.outgoing|1:23"))
    }

    /// A message that merely CONTAINS a sentinel is ordinary prose — the catalog
    /// anchors at the start of the string, and a bot must still see this.
    func testProseMentioningASentinelStillFires() {
        XCTAssertTrue(BotManager.shouldEvaluateTriggers(
            senderKey: human, content: "on devrait ajouter un marqueur 📍LOCATION📍 ici"))
    }

    // MARK: - Empty

    func testEmptyAndBlankAreIgnored() {
        XCTAssertFalse(BotManager.shouldEvaluateTriggers(senderKey: human, content: ""))
        XCTAssertFalse(BotManager.shouldEvaluateTriggers(senderKey: human, content: "   \n  "))
    }

    /// The guard is decided by the SENDER and the CONTENT only — no group, no
    /// device state, no clock. That is what lets it sit on both the inbound and
    /// the outbound funnel without either one needing to know about the other.
    func testGuardIsAFunctionOfSenderAndContentAlone() {
        for _ in 0..<3 {
            XCTAssertTrue(BotManager.shouldEvaluateTriggers(senderKey: human, content: "/help"))
            XCTAssertFalse(BotManager.shouldEvaluateTriggers(senderKey: "bot:deadbeef", content: "/help"))
        }
    }
}

// MARK: - __BOT_IN_THE_PIPELINE_2026_11_09__ a local bot's reply on the wire

/// A local bot's reply used to leave with `senderPublicKey: "bot:<id8>"` inside an
/// envelope sealed by the owner's identity, so every receiver dropped it as
/// `sender-mismatch`. These pin the replacement, which is Android's wire format
/// (`GroupManager.sendBotMessage` / `BOT_AUTHOR_FIELD`).
final class BotReplyWireTests: XCTestCase {

    private let owner = "T3duZXJLZXlBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQQ=="
    private let groupId = UUID(uuidString: "B0742222-2222-3333-4444-555555555555")!

    private func localReply() -> GroupMessage {
        let body = BotManager.botReplyBody(botName: "Météo", content: "12°C")
        return GroupMessage(
            id: UUID(), groupId: groupId,
            senderPublicKey: BotManager.localBotSenderKey(for: UUID()),
            encryptedContent: Data(body.utf8), timestamp: Date(),
            isRead: true, messageChainIndex: 0, plaintextContent: body,
            deliveryStatus: .pending)
    }

    // The loop guard

    func testBotAuthoredFlagSuppressesTriggersEvenFromARealKey() {
        XCTAssertFalse(BotManager.shouldEvaluateTriggers(senderKey: owner, content: "/help", botAuthored: true))
        XCTAssertTrue(BotManager.shouldEvaluateTriggers(senderKey: owner, content: "/help", botAuthored: false))
        // The flag can only suppress: it never re-enables a `bot:` sender.
        XCTAssertFalse(BotManager.shouldEvaluateTriggers(senderKey: "bot:deadbeef", content: "/help", botAuthored: false))
    }

    // The emitted message

    func testWireCopyCarriesOwnerKeyAndBotAuthor() throws {
        let local = localReply()
        XCTAssertTrue(local.senderPublicKey.hasPrefix("bot:"), "the owner's own row stays a bot bubble")

        let wire = MessageGroupManager.botReplyWireMessage(local, ownerKey: owner, botName: "Météo")
        XCTAssertEqual(wire.senderPublicKey, owner)
        XCTAssertEqual(wire.botAuthor, "Météo")
        XCTAssertTrue(wire.isBotAuthored)
        XCTAssertEqual(wire.id, local.id, "delivery status must land on the local row")
        XCTAssertEqual(String(data: wire.encryptedContent, encoding: .utf8), "[Météo] 12°C")

        // On the wire: top-level `botAuthor`, `content` = the visible body.
        let b64 = try XCTUnwrap(GroupWireV2.encodeContent(wire))
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: XCTUnwrap(Data(base64Encoded: b64))) as? [String: Any])
        XCTAssertEqual(json["botAuthor"] as? String, "Météo")
        XCTAssertEqual(json["senderPublicKey"] as? String, owner)
        XCTAssertEqual(json["content"] as? String, "[Météo] 12°C")

        // The receiver: authenticated against the envelope, and flagged.
        let decoded = try XCTUnwrap(GroupWireV2.decodeContent(b64))
        XCTAssertEqual(GroupWireV2.check(decoded, envelopeFrom: owner, envelopeGroupId: groupId.uuidString), .accept)
        XCTAssertTrue(decoded.isBotAuthored)
    }

    /// The root cause, pinned: the old wire form is what receivers drop.
    func testOldBotSenderFormIsDroppedByReceivers() throws {
        let b64 = try XCTUnwrap(GroupWireV2.encodeContent(localReply()))
        let decoded = try XCTUnwrap(GroupWireV2.decodeContent(b64))
        XCTAssertEqual(GroupWireV2.check(decoded, envelopeFrom: owner, envelopeGroupId: groupId.uuidString),
                       .drop("sender-mismatch"))
    }

    func testHumanMessageHasNoBotAuthorKey() throws {
        let human = GroupMessage(id: UUID(), groupId: groupId, senderPublicKey: owner,
                                 encryptedContent: Data("salut".utf8), timestamp: Date(),
                                 isRead: true, messageChainIndex: 0, plaintextContent: "salut")
        let b64 = try XCTUnwrap(GroupWireV2.encodeContent(human))
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: XCTUnwrap(Data(base64Encoded: b64))) as? [String: Any])
        XCTAssertNil(json["botAuthor"])
        XCTAssertFalse(try XCTUnwrap(GroupWireV2.decodeContent(b64)).isBotAuthored)
    }

    /// The JSON Android `GroupManager.wireGroupMessageJson(botAuthor:)` writes.
    func testDecodesAndroidBotReply() throws {
        let android = """
        {"id":"6F1C1B0E-3D2A-4C55-9A3E-1B2C3D4E5F60","groupId":"\(groupId.uuidString)",\
        "senderPublicKey":"\(owner)","encryptedContent":"W01ldGVvXSAxMsKwQw==",\
        "timestamp":784000000.123,"isRead":false,"messageChainIndex":0,\
        "messageId":"6F1C1B0E-3D2A-4C55-9A3E-1B2C3D4E5F60","content":"[Meteo] 12°C",\
        "senderName":"","botAuthor":"Meteo"}
        """
        let b64 = Data(android.utf8).base64EncodedString()
        let decoded = try XCTUnwrap(GroupWireV2.decodeContent(b64))
        XCTAssertEqual(decoded.botAuthor, "Meteo")
        XCTAssertEqual(GroupWireV2.check(decoded, envelopeFrom: owner, envelopeGroupId: groupId.uuidString), .accept)
        // An empty flag is no flag (Android: `optString(...).isNotEmpty()`).
        let empty = android.replacingOccurrences(of: "\"botAuthor\":\"Meteo\"", with: "\"botAuthor\":\"\"")
        XCTAssertFalse(try XCTUnwrap(GroupWireV2.decodeContent(Data(empty.utf8).base64EncodedString())).isBotAuthored)
    }
}
